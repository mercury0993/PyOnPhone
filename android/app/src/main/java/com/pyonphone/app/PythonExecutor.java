package com.pyonphone.app;

import android.content.Context;
import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class PythonExecutor {

    private static final String TAG = "PythonExecutor";
    private static final long EXECUTION_TIMEOUT_MS = 30000; // 30 seconds timeout
    private static final long MEMORY_CHECK_INTERVAL_MS = 1000; // Check memory every second

    public interface OutputCallback {
        void onStdout(String data);
        void onStderr(String data);
        void onExit(int exitCode);
    }

    public interface StatusCallback {
        void onStatusUpdate(String status);
        void onMemoryWarning(long usedMemory, long maxMemory);
    }

    private static PythonExecutor instance;
    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private Python python;
    private boolean initialized = false;
    private AtomicBoolean running = new AtomicBoolean(false);
    private AtomicLong lastMemoryCheck = new AtomicLong(0);
    private Future<?> currentTask;
    private Runnable timeoutWatchdog;
    private StatusCallback statusCallback;

    private PythonExecutor(Context context) {
        this.context = context.getApplicationContext();
    }

    public static synchronized PythonExecutor getInstance(Context context) {
        if (instance == null) {
            instance = new PythonExecutor(context);
        }
        return instance;
    }

    public void setStatusCallback(StatusCallback callback) {
        this.statusCallback = callback;
    }

    public void initialize() {
        if (!initialized) {
            long startTime = System.currentTimeMillis();
            Log.d(TAG, "Initializing Python...");

            if (!Python.isStarted()) {
                Python.start(new AndroidPlatform(context));
            }
            python = Python.getInstance();
            initialized = true;

            long duration = System.currentTimeMillis() - startTime;
            Log.d(TAG, "Python initialized in " + duration + "ms");

            if (statusCallback != null) {
                mainHandler.post(() -> statusCallback.onStatusUpdate(
                        "Python initialized in " + duration + "ms"));
            }
        }
    }

    public boolean isInitialized() {
        return initialized;
    }

    public boolean isRunning() {
        return running.get();
    }

    public void stopExecution() {
        if (currentTask != null && !currentTask.isDone()) {
            currentTask.cancel(true);
            running.set(false);
            Log.d(TAG, "Execution stopped by user");
        }
    }

    public void executeScript(String scriptPath, String projectDir, OutputCallback callback) {
        if (!initialized) {
            initialize();
        }

        if (running.get()) {
            mainHandler.post(() -> callback.onStderr("错误: 已有脚本在运行中\n"));
            mainHandler.post(() -> callback.onExit(1));
            return;
        }

        running.set(true);
        currentTask = executor.submit(() -> {
            long startTime = System.currentTimeMillis();
            long startMemory = getUsedMemory();

            try {
                Log.d(TAG, "Executing script: " + scriptPath);
                if (statusCallback != null) {
                    mainHandler.post(() -> statusCallback.onStatusUpdate("执行中..."));
                }

                PyObject sys = python.getModule("sys");
                PyObject os = python.getModule("os");

                // Set working directory
                os.callAttr("chdir", projectDir);

                // Redirect stdout and stderr
                PyObject io = python.getModule("io");
                PyObject stdout = io.callAttr("StringIO");
                PyObject stderr = io.callAttr("StringIO");
                sys.put("stdout", stdout);
                sys.put("stderr", stderr);

                // Read and execute script
                PyObject builtins = python.getModule("builtins");
                PyObject openFunc = builtins.get("open");
                PyObject file = openFunc.call(scriptPath, "r");
                String code = file.callAttr("read").toString();
                file.callAttr("close");

                // Execute
                int exitCode = 0;
                try {
                    PyObject mainModule = python.getModule("__main__");
                    builtins.callAttr("exec", code, mainModule);
                } catch (Exception e) {
                    exitCode = 1;
                    String errorMsg = e.getMessage();
                    if (errorMsg != null) {
                        final String msg = errorMsg;
                        mainHandler.post(() -> callback.onStderr(msg + "\n"));
                    }
                }

                // Get output
                String stdoutStr = stdout.callAttr("getvalue").toString();
                String stderrStr = stderr.callAttr("getvalue").toString();

                if (!stdoutStr.isEmpty()) {
                    final String out = stdoutStr;
                    mainHandler.post(() -> callback.onStdout(out));
                }
                if (!stderrStr.isEmpty()) {
                    final String err = stderrStr;
                    mainHandler.post(() -> callback.onStderr(err));
                }

                final int code2 = exitCode;
                if (running.get()) {
                    mainHandler.post(() -> callback.onExit(code2));
                }

                // Log performance
                long duration = System.currentTimeMillis() - startTime;
                long endMemory = getUsedMemory();
                long memoryUsed = endMemory - startMemory;
                Log.d(TAG, String.format("Script executed in %dms, memory used: %dKB",
                        duration, memoryUsed / 1024));

                if (statusCallback != null) {
                    final String status = String.format("完成 (%dms, %dKB)", duration, memoryUsed / 1024);
                    mainHandler.post(() -> statusCallback.onStatusUpdate(status));
                }

            } catch (Exception e) {
                final String error = e.getMessage();
                Log.e(TAG, "Execution error", e);
                if (running.get()) {
                    mainHandler.post(() -> {
                        callback.onStderr("执行错误: " + error + "\n");
                        callback.onExit(1);
                    });
                }
            } finally {
                mainHandler.removeCallbacks(timeoutWatchdog);
                running.set(false);
                checkMemoryUsage();
            }
        });
        scheduleTimeoutWatchdog(callback);
    }

    public void executeCode(String code, String projectDir, OutputCallback callback) {
        if (!initialized) {
            initialize();
        }

        if (running.get()) {
            mainHandler.post(() -> callback.onStderr("错误: 已有脚本在运行中\n"));
            mainHandler.post(() -> callback.onExit(1));
            return;
        }

        running.set(true);
        currentTask = executor.submit(() -> {
            long startTime = System.currentTimeMillis();

            try {
                Log.d(TAG, "Executing code snippet");
                if (statusCallback != null) {
                    mainHandler.post(() -> statusCallback.onStatusUpdate("执行中..."));
                }

                PyObject sys = python.getModule("sys");
                PyObject os = python.getModule("os");

                // Set working directory
                if (projectDir != null && !projectDir.isEmpty()) {
                    os.callAttr("chdir", projectDir);
                }

                // Redirect stdout and stderr
                PyObject io = python.getModule("io");
                PyObject stdout = io.callAttr("StringIO");
                PyObject stderr = io.callAttr("StringIO");
                sys.put("stdout", stdout);
                sys.put("stderr", stderr);

                // Execute code
                int exitCode = 0;
                try {
                    PyObject builtins = python.getModule("builtins");
                    PyObject mainModule = python.getModule("__main__");
                    builtins.callAttr("exec", code, mainModule);
                } catch (Exception e) {
                    exitCode = 1;
                    String errorMsg = e.getMessage();
                    if (errorMsg != null) {
                        final String msg = errorMsg;
                        mainHandler.post(() -> callback.onStderr(msg + "\n"));
                    }
                }

                // Get output
                String stdoutStr = stdout.callAttr("getvalue").toString();
                String stderrStr = stderr.callAttr("getvalue").toString();

                if (!stdoutStr.isEmpty()) {
                    final String out = stdoutStr;
                    mainHandler.post(() -> callback.onStdout(out));
                }
                if (!stderrStr.isEmpty()) {
                    final String err = stderrStr;
                    mainHandler.post(() -> callback.onStderr(err));
                }

                final int code2 = exitCode;
                if (running.get()) {
                    mainHandler.post(() -> callback.onExit(code2));
                }

                // Log performance
                long duration = System.currentTimeMillis() - startTime;
                Log.d(TAG, "Code executed in " + duration + "ms");

                if (statusCallback != null) {
                    final String status = "完成 (" + duration + "ms)";
                    mainHandler.post(() -> statusCallback.onStatusUpdate(status));
                }

            } catch (Exception e) {
                final String error = e.getMessage();
                Log.e(TAG, "Execution error", e);
                if (running.get()) {
                    mainHandler.post(() -> {
                        callback.onStderr("执行错误: " + error + "\n");
                        callback.onExit(1);
                    });
                }
            } finally {
                mainHandler.removeCallbacks(timeoutWatchdog);
                running.set(false);
                checkMemoryUsage();
            }
        });
        scheduleTimeoutWatchdog(callback);
    }

    private void scheduleTimeoutWatchdog(OutputCallback callback) {
        timeoutWatchdog = () -> {
            if (running.get() && currentTask != null && !currentTask.isDone()) {
                Log.w(TAG, "Execution timed out after " + EXECUTION_TIMEOUT_MS + "ms");
                currentTask.cancel(true);
                running.set(false);
                mainHandler.post(() -> {
                    callback.onStderr("执行超时（" + (EXECUTION_TIMEOUT_MS / 1000) + "秒）\n");
                    callback.onExit(124); // 124 = timeout exit code
                });
            }
        };
        mainHandler.postDelayed(timeoutWatchdog, EXECUTION_TIMEOUT_MS);
    }

    private void checkMemoryUsage() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastMemoryCheck.get() < MEMORY_CHECK_INTERVAL_MS) {
            return;
        }
        lastMemoryCheck.set(currentTime);

        long usedMemory = getUsedMemory();
        long maxMemory = Runtime.getRuntime().maxMemory();
        float usagePercent = (float) usedMemory / maxMemory * 100;

        Log.d(TAG, String.format("Memory usage: %dKB / %dKB (%.1f%%)",
                usedMemory / 1024, maxMemory / 1024, usagePercent));

        if (usagePercent > 80 && statusCallback != null) {
            mainHandler.post(() -> statusCallback.onMemoryWarning(usedMemory, maxMemory));
        }
    }

    private long getUsedMemory() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    public void shutdown() {
        stopExecution();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
        }
        initialized = false;
        Log.d(TAG, "PythonExecutor shutdown");
    }
}