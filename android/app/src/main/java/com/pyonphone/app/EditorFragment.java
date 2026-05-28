package com.pyonphone.app;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class EditorFragment extends Fragment {

    private static final long AUTO_SAVE_DELAY_MS = 1000;

    private WebView editorWebView;
    private WebView terminalWebView;
    private RecyclerView fileTreeView;
    private FileTreeAdapter fileTreeAdapter;
    private ImageButton btnRun, btnStop, btnFileTree, btnLayout, btnMode, btnSync;
    private ImageButton btnUndo, btnRedo, btnFind, btnGoToLine;
    private ImageButton btnDuplicateLine, btnDeleteLine, btnMoveUp, btnMoveDown;
    private ImageButton btnFormat, btnComment;
    private View floatingToolbar;
    private TextView txtFilePath, txtMode, txtSyncStatus;

    private String projectId;
    private String currentFilePath;
    private boolean editorReady = false;
    private boolean terminalReady = false;
    private boolean dirty = false;
    private boolean running = false;
    private boolean syncing = false;
    private boolean localMode = false; // false=remote, true=local

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Handler saveHandler = new Handler(Looper.getMainLooper());
    private OkHttpClient wsClient = new OkHttpClient();
    private WebSocket wsSocket;
    private PythonExecutor pythonExecutor;

    // Layout mode: 0=split, 1=editor-only, 2=terminal-only
    private int layoutMode = 0;
    private float splitRatio = 0.5f; // editor weight ratio in split mode
    private ViewGroup splitContainer;

    private final Runnable autoSaveRunnable = () -> {
        if (dirty && currentFilePath != null && projectId != null) {
            editorWebView.evaluateJavascript("getContent()", value -> {
                // value is a JSON string with quotes
                String content = value.substring(1, value.length() - 1)
                        .replace("\\n", "\n").replace("\\\"", "\"");
                saveFile(content);
            });
        }
    };

    @SuppressLint("SetJavaScriptEnabled")
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_editor, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        editorWebView = view.findViewById(R.id.editor_webview);
        terminalWebView = view.findViewById(R.id.terminal_webview);
        fileTreeView = view.findViewById(R.id.file_tree);
        btnRun = view.findViewById(R.id.btn_run);
        btnStop = view.findViewById(R.id.btn_stop);
        btnFileTree = view.findViewById(R.id.btn_file_tree);
        btnLayout = view.findViewById(R.id.btn_layout);
        btnMode = view.findViewById(R.id.btn_mode);
        btnSync = view.findViewById(R.id.btn_sync);
        txtFilePath = view.findViewById(R.id.txt_file_path);
        txtMode = view.findViewById(R.id.txt_mode);
        txtSyncStatus = view.findViewById(R.id.txt_sync_status);

        // Floating toolbar
        floatingToolbar = view.findViewById(R.id.floating_toolbar);
        btnUndo = view.findViewById(R.id.btn_undo);
        btnRedo = view.findViewById(R.id.btn_redo);
        btnFind = view.findViewById(R.id.btn_find);
        btnGoToLine = view.findViewById(R.id.btn_go_to_line);
        btnDuplicateLine = view.findViewById(R.id.btn_duplicate_line);
        btnDeleteLine = view.findViewById(R.id.btn_delete_line);
        btnMoveUp = view.findViewById(R.id.btn_move_up);
        btnMoveDown = view.findViewById(R.id.btn_move_down);
        btnFormat = view.findViewById(R.id.btn_format);
        btnComment = view.findViewById(R.id.btn_comment);

        // Get project ID from arguments
        if (getArguments() != null) {
            projectId = getArguments().getString("project_id", "");
        }

        setupFileTree();
        setupWebViews();
        setupToolbar();
        setupFloatingToolbar();
        setupContextMenu();
        setupDividerDrag();

        // Load execution mode from settings
        android.content.SharedPreferences prefs = requireContext()
                .getSharedPreferences("pyonphone", android.content.Context.MODE_PRIVATE);
        localMode = prefs.getBoolean("local_python_enabled", false);
        updateModeIndicator();
        if (!localMode) {
            btnMode.setVisibility(View.GONE);
        }

        if (projectId != null && !projectId.isEmpty()) {
            loadFileTree();
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebViews() {
        // Editor WebView
        WebSettings editorSettings = editorWebView.getSettings();
        editorSettings.setJavaScriptEnabled(true);
        editorSettings.setDomStorageEnabled(true);
        editorSettings.setAllowFileAccess(true);
        editorWebView.setFocusable(true);
        editorWebView.setFocusableInTouchMode(true);
        editorWebView.addJavascriptInterface(new EditorJsInterface(), "Android");
        editorWebView.loadUrl("file:///android_asset/editor.html");

        // Ensure WebView gets focus and shows keyboard on tap
        editorWebView.setOnTouchListener((v, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_UP) {
                v.requestFocus();
                android.view.inputmethod.InputMethodManager imm =
                        (android.view.inputmethod.InputMethodManager)
                                requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.showSoftInput(v, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
                }
            }
            return false; // let WebView handle the touch normally
        });

        // Terminal WebView
        WebSettings termSettings = terminalWebView.getSettings();
        termSettings.setJavaScriptEnabled(true);
        termSettings.setDomStorageEnabled(true);
        termSettings.setAllowFileAccess(true);
        terminalWebView.addJavascriptInterface(new TerminalJsInterface(), "Android");
        terminalWebView.loadUrl("file:///android_asset/terminal.html");
    }

    private void setupFileTree() {
        fileTreeAdapter = new FileTreeAdapter();
        fileTreeView.setLayoutManager(new LinearLayoutManager(requireContext()));
        fileTreeView.setAdapter(fileTreeAdapter);

        fileTreeAdapter.setOnFileClickListener((path, isDirectory) -> {
            if (!isDirectory) {
                openFile(path);
            }
        });
    }

    private void setupToolbar() {
        btnFileTree.setOnClickListener(v -> toggleFileTree());
        btnRun.setOnClickListener(v -> runScript());
        btnStop.setOnClickListener(v -> stopScript());
        btnLayout.setOnClickListener(v -> cycleLayoutMode());
        btnMode.setOnClickListener(v -> toggleExecutionMode());
        btnSync.setOnClickListener(v -> syncProject());

        // Initialize PythonExecutor for local mode
        pythonExecutor = PythonExecutor.getInstance(requireContext());
        pythonExecutor.setStatusCallback(new PythonExecutor.StatusCallback() {
            @Override
            public void onStatusUpdate(String status) {
                // Could show status in UI if needed
                Log.d("EditorFragment", "Python status: " + status);
            }

            @Override
            public void onMemoryWarning(long usedMemory, long maxMemory) {
                float percent = (float) usedMemory / maxMemory * 100;
                String warning = String.format("内存警告: %.1f%% (%dMB/%dMB)",
                        percent, usedMemory / 1024 / 1024, maxMemory / 1024 / 1024);
                Toast.makeText(requireContext(), warning, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void setupFloatingToolbar() {
        // Toggle floating toolbar on long press of layout button
        btnLayout.setOnLongClickListener(v -> {
            toggleFloatingToolbar();
            return true;
        });

        // Floating toolbar button click handlers
        btnUndo.setOnClickListener(v -> undo());
        btnRedo.setOnClickListener(v -> redo());
        btnFind.setOnClickListener(v -> showFindDialog());
        btnGoToLine.setOnClickListener(v -> goToLine());
        btnDuplicateLine.setOnClickListener(v -> duplicateLine());
        btnDeleteLine.setOnClickListener(v -> deleteLine());
        btnMoveUp.setOnClickListener(v -> moveLineUp());
        btnMoveDown.setOnClickListener(v -> moveLineDown());
        btnFormat.setOnClickListener(v -> formatDocument());
        btnComment.setOnClickListener(v -> toggleComment());
    }

    private void toggleFloatingToolbar() {
        if (floatingToolbar.getVisibility() == View.VISIBLE) {
            floatingToolbar.setVisibility(View.GONE);
        } else {
            floatingToolbar.setVisibility(View.VISIBLE);
        }
    }

    private void setupContextMenu() {
        // Register context menu for editor WebView
        registerForContextMenu(editorWebView);
    }

    private void setupDividerDrag() {
        View divider = requireView().findViewById(R.id.divider);
        // The parent LinearLayout containing editor, divider, terminal
        splitContainer = (ViewGroup) editorWebView.getParent();

        divider.setOnTouchListener((v, event) -> {
            if (layoutMode != 0) return false; // only drag in split mode

            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                    return true;

                case MotionEvent.ACTION_MOVE:
                    float y = event.getRawY();
                    // Get container location on screen
                    int[] loc = new int[2];
                    splitContainer.getLocationOnScreen(loc);
                    float containerTop = loc[1];
                    float containerHeight = splitContainer.getHeight();

                    // Calculate new ratio (divider center position / container height)
                    float ratio = (y - containerTop) / containerHeight;
                    splitRatio = Math.max(0.15f, Math.min(0.85f, ratio));

                    // Update weights
                    ViewGroup.LayoutParams editorLp = editorWebView.getLayoutParams();
                    ViewGroup.LayoutParams termLp = terminalWebView.getLayoutParams();
                    ((LinearLayout.LayoutParams) editorLp).weight = splitRatio;
                    ((LinearLayout.LayoutParams) termLp).weight = 1f - splitRatio;
                    editorWebView.setLayoutParams(editorLp);
                    terminalWebView.setLayoutParams(termLp);
                    return true;

                case MotionEvent.ACTION_UP:
                    v.getParent().requestDisallowInterceptTouchEvent(false);
                    return true;
            }
            return false;
        });
    }

    @Override
    public void onCreateContextMenu(@NonNull android.view.ContextMenu menu,
                                    @NonNull View v,
                                    @Nullable android.view.ContextMenu.ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);

        if (v == editorWebView) {
            menu.setHeaderTitle("编辑");
            menu.add(0, 1, 0, "撤销");
            menu.add(0, 2, 0, "重做");
            menu.add(0, 3, 0, "剪切");
            menu.add(0, 4, 0, "复制");
            menu.add(0, 5, 0, "粘贴");
            menu.add(0, 6, 0, "全选");
            menu.add(0, 7, 0, "查找");
            menu.add(0, 8, 0, "跳转到行");
            menu.add(0, 9, 0, "复制行");
            menu.add(0, 10, 0, "删除行");
            menu.add(0, 11, 0, "转大写");
            menu.add(0, 12, 0, "转小写");
            menu.add(0, 13, 0, "注释");
            menu.add(0, 14, 0, "格式化");
        }
    }

    @Override
    public boolean onContextItemSelected(@NonNull android.view.MenuItem item) {
        switch (item.getItemId()) {
            case 1: undo(); return true;
            case 2: redo(); return true;
            case 3: editorWebView.evaluateJavascript("document.execCommand('cut')", null); return true;
            case 4: editorWebView.evaluateJavascript("document.execCommand('copy')", null); return true;
            case 5: editorWebView.evaluateJavascript("document.execCommand('paste')", null); return true;
            case 6: editorWebView.evaluateJavascript("editor.getAction('editor.action.selectAll').run()", null); return true;
            case 7: showFindDialog(); return true;
            case 8: goToLine(); return true;
            case 9: duplicateLine(); return true;
            case 10: deleteLine(); return true;
            case 11: toUpperCase(); return true;
            case 12: toLowerCase(); return true;
            case 13: toggleComment(); return true;
            case 14: formatDocument(); return true;
            default: return super.onContextItemSelected(item);
        }
    }

    private void toggleFileTree() {
        int vis = fileTreeView.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE;
        fileTreeView.setVisibility(vis);
    }

    private void toggleExecutionMode() {
        localMode = !localMode;
        updateModeIndicator();
        if (localMode && !pythonExecutor.isInitialized()) {
            pythonExecutor.initialize();
            Toast.makeText(requireContext(), "本地 Python 引擎已初始化", Toast.LENGTH_SHORT).show();
        }
    }

    private void updateModeIndicator() {
        if (txtMode != null) {
            txtMode.setText(localMode ? "本地" : "远程");
        }
    }

    private void cycleLayoutMode() {
        layoutMode = (layoutMode + 1) % 3;
        applyLayoutMode();
    }

    private void applyLayoutMode() {
        ViewGroup.LayoutParams editorLp = editorWebView.getLayoutParams();
        ViewGroup.LayoutParams termLp = terminalWebView.getLayoutParams();
        View divider = requireView().findViewById(R.id.divider);
        LinearLayout.LayoutParams editorLlp = (LinearLayout.LayoutParams) editorLp;
        LinearLayout.LayoutParams termLlp = (LinearLayout.LayoutParams) termLp;

        switch (layoutMode) {
            case 0: // split
                editorLp.height = 0;
                termLp.height = 0;
                editorLlp.weight = splitRatio;
                termLlp.weight = 1f - splitRatio;
                divider.setVisibility(View.VISIBLE);
                break;
            case 1: // editor only
                editorLp.height = ViewGroup.LayoutParams.MATCH_PARENT;
                termLp.height = 0;
                divider.setVisibility(View.GONE);
                break;
            case 2: // terminal only
                editorLp.height = 0;
                termLp.height = ViewGroup.LayoutParams.MATCH_PARENT;
                divider.setVisibility(View.GONE);
                break;
        }
        editorWebView.setLayoutParams(editorLp);
        terminalWebView.setLayoutParams(termLp);
    }

    // ── Editor features ─────────────────────────────────────────

    public void showFindDialog() {
        View dialogView = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_find_replace, null);
        TextInputEditText editFind = dialogView.findViewById(R.id.edit_find);
        TextInputEditText editReplace = dialogView.findViewById(R.id.edit_replace);

        new AlertDialog.Builder(requireContext())
                .setTitle("查找和替换")
                .setView(dialogView)
                .setPositiveButton("查找全部", (dialog, which) -> {
                    String findText = editFind.getText() != null
                            ? editFind.getText().toString() : "";
                    if (!findText.isEmpty()) {
                        findInEditor(findText);
                    }
                })
                .setNeutralButton("替换全部", (dialog, which) -> {
                    String findText = editFind.getText() != null
                            ? editFind.getText().toString() : "";
                    String replaceText = editReplace.getText() != null
                            ? editReplace.getText().toString() : "";
                    if (!findText.isEmpty()) {
                        replaceInEditor(findText, replaceText);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void findInEditor(String searchText) {
        String escaped = searchText.replace("\\", "\\\\")
                .replace("'", "\\'").replace("\n", "\\n");
        editorWebView.evaluateJavascript(
                "editor.getAction('actions.find').run()", null);
    }

    private void replaceInEditor(String findText, String replaceText) {
        editorWebView.evaluateJavascript(
                "editor.getAction('editor.action.startFindReplaceAction').run()", null);
    }

    public void goToLine() {
        TextInputEditText input = new TextInputEditText(requireContext());
        input.setHint("行号");
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);

        new AlertDialog.Builder(requireContext())
                .setTitle("跳转到行")
                .setView(input)
                .setPositiveButton("跳转", (dialog, which) -> {
                    String lineStr = input.getText() != null
                            ? input.getText().toString() : "";
                    try {
                        int line = Integer.parseInt(lineStr);
                        editorWebView.evaluateJavascript(
                                "goToLine(" + line + ")", null);
                    } catch (NumberFormatException e) {
                        // ignore
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    public void formatDocument() {
        editorWebView.evaluateJavascript("formatDocument()", null);
    }

    public void toggleWordWrap() {
        editorWebView.evaluateJavascript("toggleWordWrap()", null);
    }

    // Code folding
    public void foldAll() {
        editorWebView.evaluateJavascript("foldAll()", null);
    }

    public void unfoldAll() {
        editorWebView.evaluateJavascript("unfoldAll()", null);
    }

    // Multi-cursor
    public void addCursorAbove() {
        editorWebView.evaluateJavascript("addCursorAbove()", null);
    }

    public void addCursorBelow() {
        editorWebView.evaluateJavascript("addCursorBelow()", null);
    }

    public void selectNextOccurrence() {
        editorWebView.evaluateJavascript("selectNextOccurrence()", null);
    }

    public void selectAllOccurrences() {
        editorWebView.evaluateJavascript("selectAllOccurrences()", null);
    }

    // Line operations
    public void duplicateLine() {
        editorWebView.evaluateJavascript("duplicateLine()", null);
    }

    public void deleteLine() {
        editorWebView.evaluateJavascript("deleteLine()", null);
    }

    public void moveLineUp() {
        editorWebView.evaluateJavascript("moveLineUp()", null);
    }

    public void moveLineDown() {
        editorWebView.evaluateJavascript("moveLineDown()", null);
    }

    // Transform
    public void toUpperCase() {
        editorWebView.evaluateJavascript("toUpperCase()", null);
    }

    public void toLowerCase() {
        editorWebView.evaluateJavascript("toLowerCase()", null);
    }

    public void toggleComment() {
        editorWebView.evaluateJavascript("editor.getAction('editor.action.commentLine').run()", null);
    }

    // Sort
    public void sortLinesAscending() {
        editorWebView.evaluateJavascript("sortLinesAscending()", null);
    }

    public void sortLinesDescending() {
        editorWebView.evaluateJavascript("sortLinesDescending()", null);
    }

    // Trim
    public void trimTrailingWhitespace() {
        editorWebView.evaluateJavascript("trimTrailingWhitespace()", null);
    }

    // Undo/Redo
    public void undo() {
        editorWebView.evaluateJavascript("undo()", null);
    }

    public void redo() {
        editorWebView.evaluateJavascript("redo()", null);
    }

    // ── File operations ─────────────────────────────────────────

    private void loadFileTree() {
        ApiClient.getInstance().getFileTree(projectId, new ApiClient.Callback<JSONArray>() {
            @Override
            public void onSuccess(JSONArray tree) {
                FileTreeAdapter.FileNode root = new FileTreeAdapter.FileNode("root", "", true, -1);
                parseTree(root, tree, 0);
                fileTreeAdapter.setTree(root);
            }

            @Override
            public void onError(String error) {
                if (isTimeoutError(error)) {
                    showRetrySnackbar("加载文件树超时", () -> loadFileTree());
                } else {
                    Toast.makeText(requireContext(), "加载文件树失败: " + error, Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private void parseTree(FileTreeAdapter.FileNode parent, JSONArray items, int level) {
        try {
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                String name = item.getString("name");
                String path = item.getString("path");
                boolean isDir = "directory".equals(item.getString("type"));

                FileTreeAdapter.FileNode node = new FileTreeAdapter.FileNode(name, path, isDir, level);
                if (isDir && item.has("children")) {
                    parseTree(node, item.getJSONArray("children"), level + 1);
                }
                parent.children.add(node);
            }
        } catch (Exception e) {
            // ignore parse errors
        }
    }

    private void openFile(String path) {
        if (dirty && currentFilePath != null) {
            saveCurrentFile();
        }
        currentFilePath = path;

        // Show file name in toolbar
        String fileName = path.substring(path.lastIndexOf('/') + 1);
        txtFilePath.setText(fileName);

        // Show loading indicator for large files
        if (terminalReady) {
            terminalWebView.evaluateJavascript(
                    "writeTerminal('\\033[33m加载文件: " + fileName + "\\033[0m\\r\\n')", null);
        }

        ApiClient.getInstance().readFile(projectId, path, new ApiClient.Callback<String>() {
            @Override
            public void onSuccess(String content) {
                if (editorReady) {
                    // Check content size
                    if (content.length() > 500000) {
                        // Large file warning
                        Toast.makeText(requireContext(),
                                "大文件 (" + content.length() / 1024 + "KB)，编辑可能较慢",
                                Toast.LENGTH_SHORT).show();
                    }

                    // Optimize large content handling
                    if (content.length() > 100000) {
                        // For large files, set content directly without escaping
                        editorWebView.evaluateJavascript(
                                "editor.setValue(" + JSONObject.quote(content) + ")", null);
                    } else {
                        String escaped = content.replace("\\", "\\\\")
                                .replace("'", "\\'").replace("\n", "\\n").replace("\r", "");
                        editorWebView.evaluateJavascript(
                                "setContent('" + escaped + "')", null);
                    }
                }
                // Detect language from extension
                String lang = detectLanguage(path);
                editorWebView.evaluateJavascript("setLanguage('" + lang + "')", null);

                dirty = false;
            }

            @Override
            public void onError(String error) {
                if (isTimeoutError(error)) {
                    showRetrySnackbar("打开文件超时", () -> openFile(path));
                } else {
                    Toast.makeText(requireContext(), "打开文件失败: " + error, Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private boolean isTimeoutError(String error) {
        return error != null && error.contains("timed out");
    }

    private void showRetrySnackbar(String message, Runnable retryAction) {
        if (getView() == null) return;
        Snackbar.make(getView(), message, Snackbar.LENGTH_LONG)
                .setAction("重试", v -> retryAction.run())
                .show();
    }

    private String detectLanguage(String path) {
        if (path == null) return "plaintext";
        String lower = path.toLowerCase();

        if (lower.endsWith(".py")) return "python";
        if (lower.endsWith(".json")) return "json";
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "html";
        if (lower.endsWith(".css")) return "css";
        if (lower.endsWith(".js")) return "javascript";
        if (lower.endsWith(".ts")) return "typescript";
        if (lower.endsWith(".jsx")) return "javascript";
        if (lower.endsWith(".tsx")) return "typescript";
        if (lower.endsWith(".java")) return "java";
        if (lower.endsWith(".xml")) return "xml";
        if (lower.endsWith(".yaml") || lower.endsWith(".yml")) return "yaml";
        if (lower.endsWith(".md")) return "markdown";
        if (lower.endsWith(".sql")) return "sql";
        if (lower.endsWith(".sh")) return "shell";
        if (lower.endsWith(".bat")) return "bat";
        if (lower.endsWith(".ps1")) return "powershell";
        if (lower.endsWith(".rb")) return "ruby";
        if (lower.endsWith(".go")) return "go";
        if (lower.endsWith(".rs")) return "rust";
        if (lower.endsWith(".php")) return "php";
        if (lower.endsWith(".swift")) return "swift";
        if (lower.endsWith(".kt")) return "kotlin";
        if (lower.endsWith(".c")) return "c";
        if (lower.endsWith(".cpp") || lower.endsWith(".cc") || lower.endsWith(".cxx")) return "cpp";
        if (lower.endsWith(".h") || lower.endsWith(".hpp")) return "cpp";
        if (lower.endsWith(".cs")) return "csharp";
        if (lower.endsWith(".r")) return "r";
        if (lower.endsWith(".scala")) return "scala";
        if (lower.endsWith(".lua")) return "lua";
        if (lower.endsWith(".perl") || lower.endsWith(".pl")) return "perl";
        if (lower.endsWith(".txt")) return "plaintext";
        if (lower.endsWith(".log")) return "plaintext";
        if (lower.endsWith(".ini") || lower.endsWith(".cfg") || lower.endsWith(".conf")) return "ini";
        if (lower.endsWith(".toml")) return "ini";
        if (lower.endsWith(".env")) return "plaintext";
        if (lower.endsWith(".gitignore")) return "plaintext";
        if (lower.endsWith(".dockerfile") || lower.equals("dockerfile")) return "dockerfile";
        if (lower.endsWith(".makefile") || lower.equals("makefile")) return "makefile";

        return "plaintext";
    }

    private void saveCurrentFile() {
        editorWebView.evaluateJavascript("getContent()", value -> {
            if (value != null && !value.equals("null")) {
                String content = value.substring(1, value.length() - 1)
                        .replace("\\n", "\n").replace("\\\"", "\"");
                saveFile(content);
            }
        });
    }

    private void saveFile(String content) {
        if (currentFilePath == null || projectId == null) return;
        android.content.SharedPreferences prefs = requireContext()
                .getSharedPreferences("pyonphone", android.content.Context.MODE_PRIVATE);
        boolean autoCommit = prefs.getBoolean("auto_commit_enabled", true);
        ApiClient.getInstance().saveFile(projectId, currentFilePath, content, autoCommit, new ApiClient.Callback<Void>() {
            @Override
            public void onSuccess(Void result) {
                dirty = false;
                if (txtSyncStatus != null) {
                    txtSyncStatus.setVisibility(View.GONE);
                }
            }

            @Override
            public void onError(String error) {
                if (txtSyncStatus != null) {
                    txtSyncStatus.setVisibility(View.VISIBLE);
                }
            }
        });
    }

    // ── Script execution ────────────────────────────────────────

    private void runScript() {
        if (running || currentFilePath == null || projectId == null) return;

        // Save before running
        saveCurrentFile();
        running = true;
        btnRun.setVisibility(View.GONE);
        btnStop.setVisibility(View.VISIBLE);

        // Switch to split layout if in editor-only mode
        if (layoutMode == 1) {
            layoutMode = 0;
            applyLayoutMode();
        }

        // Clear terminal
        if (terminalReady) {
            terminalWebView.evaluateJavascript("clearTerminal()", null);
        }

        if (localMode) {
            runLocalScript();
        } else {
            runRemoteScript();
        }
    }

    private void runLocalScript() {
        if (!pythonExecutor.isInitialized()) {
            pythonExecutor.initialize();
        }

        // Get project directory
        String projectDir = requireContext().getFilesDir() + "/projects/" + projectId;

        pythonExecutor.executeScript(currentFilePath, projectDir, new PythonExecutor.OutputCallback() {
            @Override
            public void onStdout(String data) {
                appendTerminal(data);
            }

            @Override
            public void onStderr(String data) {
                appendTerminal("[31m" + data + "[0m");
            }

            @Override
            public void onExit(int exitCode) {
                appendTerminal("\r\n[33m[退出码: " + exitCode + "][0m\r\n");
                running = false;
                btnRun.setVisibility(View.VISIBLE);
                btnStop.setVisibility(View.GONE);
            }
        });
    }

    private void runRemoteScript() {
        // Connect WebSocket
        String wsUrl = ApiClient.getInstance().getBaseUrl()
                .replace("http://", "ws://").replace("https://", "wss://")
                + "/socket.io/?EIO=4&transport=websocket";

        Request request = new Request.Builder().url(wsUrl).build();
        wsClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                wsSocket = webSocket;
                // Send connect packet
                webSocket.send("40");
                // Send run event
                try {
                    JSONObject data = new JSONObject();
                    data.put("project_id", projectId);
                    data.put("path", currentFilePath);
                    webSocket.send("42[\"run\"," + data.toString() + "]");
                } catch (Exception e) {
                    // ignore
                }
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                if (text.startsWith("42")) {
                    String payload = text.substring(2);
                    try {
                        JSONArray arr = new JSONArray(payload);
                        String event = arr.getString(0);
                        JSONObject data = arr.getJSONObject(1);
                        String output = data.optString("data", "");
                        handler.post(() -> handleWsEvent(event, output));
                    } catch (Exception e) {
                        // ignore
                    }
                }
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                handler.post(() -> {
                    running = false;
                    btnRun.setVisibility(View.VISIBLE);
                    btnStop.setVisibility(View.GONE);
                });
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                handler.post(() -> {
                    running = false;
                    btnRun.setVisibility(View.VISIBLE);
                    btnStop.setVisibility(View.GONE);
                    appendTerminal("\r\n[31mWebSocket 连接失败: " + t.getMessage() + "[0m\r\n");
                });
            }
        });
    }

    private void syncProject() {
        if (projectId == null || syncing) return;
        syncing = true;
        btnSync.setEnabled(false);

        Toast.makeText(requireContext(), "同步中...", Toast.LENGTH_SHORT).show();

        Runnable doSync = () -> {
            if (localMode) {
                // Local mode: commit + push via GitExecutor
                GitExecutor gitExecutor = GitExecutor.getInstance(requireContext());
                gitExecutor.commit(projectId, "sync: auto commit", new GitExecutor.GitCallback<String>() {
                    @Override
                    public void onSuccess(String message) {
                        gitExecutor.push(projectId, new GitExecutor.GitCallback<String>() {
                            @Override
                            public void onSuccess(String msg) {
                                handler.post(() -> {
                                    syncing = false;
                                    btnSync.setEnabled(true);
                                    Toast.makeText(requireContext(), "同步完成", Toast.LENGTH_SHORT).show();
                                });
                            }

                            @Override
                            public void onError(String error) {
                                handler.post(() -> {
                                    syncing = false;
                                    btnSync.setEnabled(true);
                                    Toast.makeText(requireContext(), "推送失败: " + error, Toast.LENGTH_SHORT).show();
                                });
                            }
                        });
                    }

                    @Override
                    public void onError(String error) {
                        handler.post(() -> {
                            syncing = false;
                            btnSync.setEnabled(true);
                            Toast.makeText(requireContext(), "提交失败: " + error, Toast.LENGTH_SHORT).show();
                        });
                    }
                });
            } else {
                // Remote mode: commit + push via API
                ApiClient.getInstance().gitCommit(projectId, "sync: auto commit", new ApiClient.Callback<JSONObject>() {
                    @Override
                    public void onSuccess(JSONObject result) {
                        ApiClient.getInstance().gitPush(projectId, new ApiClient.Callback<JSONObject>() {
                            @Override
                            public void onSuccess(JSONObject res) {
                                handler.post(() -> {
                                    syncing = false;
                                    btnSync.setEnabled(true);
                                    Toast.makeText(requireContext(), "同步完成", Toast.LENGTH_SHORT).show();
                                });
                            }

                            @Override
                            public void onError(String error) {
                                handler.post(() -> {
                                    syncing = false;
                                    btnSync.setEnabled(true);
                                    Toast.makeText(requireContext(), "推送失败: " + error, Toast.LENGTH_SHORT).show();
                                });
                            }
                        });
                    }

                    @Override
                    public void onError(String error) {
                        handler.post(() -> {
                            syncing = false;
                            btnSync.setEnabled(true);
                            Toast.makeText(requireContext(), "提交失败: " + error, Toast.LENGTH_SHORT).show();
                        });
                    }
                });
            }
        };

        // Save current file first if dirty
        if (dirty && currentFilePath != null) {
            editorWebView.evaluateJavascript("getContent()", value -> {
                if (value != null && !value.equals("null")) {
                    String content = value.substring(1, value.length() - 1)
                            .replace("\\n", "\n").replace("\\\"", "\"");
                    // Save first, then sync after save completes
                    android.content.SharedPreferences prefs2 = requireContext()
                            .getSharedPreferences("pyonphone", android.content.Context.MODE_PRIVATE);
                    boolean autoCommit = prefs2.getBoolean("auto_commit_enabled", true);
                    ApiClient.getInstance().saveFile(projectId, currentFilePath, content, autoCommit, new ApiClient.Callback<Void>() {
                        @Override
                        public void onSuccess(Void result) {
                            dirty = false;
                            doSync.run();
                        }

                        @Override
                        public void onError(String error) {
                            Toast.makeText(requireContext(), "保存失败，无法同步", Toast.LENGTH_SHORT).show();
                            syncing = false;
                            btnSync.setEnabled(true);
                        }
                    });
                } else {
                    doSync.run();
                }
            });
        } else {
            doSync.run();
        }
    }

    private void stopScript() {
        if (localMode) {
            // Local mode: stop Python execution
            pythonExecutor.stopExecution();
            running = false;
            btnRun.setVisibility(View.VISIBLE);
            btnStop.setVisibility(View.GONE);
            appendTerminal("\r\n[33m[执行已停止][0m\r\n");
        } else {
            // Remote mode: send stop via WebSocket
            if (wsSocket != null) {
                try {
                    JSONObject data = new JSONObject();
                    data.put("project_id", projectId);
                    wsSocket.send("42[\"stop\"," + data.toString() + "]");
                } catch (Exception e) {
                    // ignore
                }
            }
            running = false;
            btnRun.setVisibility(View.VISIBLE);
            btnStop.setVisibility(View.GONE);
        }
    }

    private void handleWsEvent(String event, String data) {
        switch (event) {
            case "stdout":
            case "stderr":
                appendTerminal(data);
                break;
            case "timeout":
                appendTerminal("\r\n[31m⚠ 执行超时（30秒）[0m\r\n");
                running = false;
                btnRun.setVisibility(View.VISIBLE);
                btnStop.setVisibility(View.GONE);
                break;
            case "exit":
                appendTerminal("\r\n[33m[退出码: " + data + "][0m\r\n");
                running = false;
                btnRun.setVisibility(View.VISIBLE);
                btnStop.setVisibility(View.GONE);
                break;
        }
    }

    private void appendTerminal(String text) {
        if (terminalReady) {
            // Escape for JS string
            String escaped = text.replace("\\", "\\\\")
                    .replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r");
            terminalWebView.evaluateJavascript("writeTerminal('" + escaped + "')", null);
        }
    }

    // ── JavaScript interfaces ───────────────────────────────────

    class EditorJsInterface {
        @JavascriptInterface
        public void onEditorReady() {
            editorReady = true;
        }

        @JavascriptInterface
        public void onContentChanged(String content) {
            dirty = true;
            saveHandler.removeCallbacks(autoSaveRunnable);
            saveHandler.postDelayed(autoSaveRunnable, AUTO_SAVE_DELAY_MS);
        }
    }

    class TerminalJsInterface {
        @JavascriptInterface
        public void onTerminalReady() {
            terminalReady = true;
        }

        @JavascriptInterface
        public void onTerminalInput(String data) {
            if (wsSocket != null && running && !localMode) {
                try {
                    JSONObject payload = new JSONObject();
                    payload.put("project_id", projectId);
                    payload.put("data", data);
                    wsSocket.send("42[\"stdin\"," + payload.toString() + "]");
                } catch (Exception e) {
                    // ignore
                }
            }
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        saveHandler.removeCallbacks(autoSaveRunnable);
        if (wsSocket != null) {
            wsSocket.close(1000, "leaving");
        }
    }
}