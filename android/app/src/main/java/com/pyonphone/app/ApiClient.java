package com.pyonphone.app;

import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class ApiClient {

    private static volatile ApiClient instance;
    private String baseUrl = "http://10.0.2.2:5000";
    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Cache for file tree and file content
    private final LruCache<String, String> fileCache = new LruCache<>(50); // 50 items
    private final LruCache<String, Long> cacheTimestamps = new LruCache<>(50);
    private static final long CACHE_EXPIRY_MS = 30000; // 30 seconds

    public interface Callback<T> {
        void onSuccess(T result);
        void onError(String error);
    }

    private ApiClient() {}

    public static ApiClient getInstance() {
        if (instance == null) {
            synchronized (ApiClient.class) {
                if (instance == null) instance = new ApiClient();
            }
        }
        return instance;
    }

    public void setBaseUrl(String url) {
        this.baseUrl = url.replaceAll("/+$", "");
        clearCache(); // Clear cache when URL changes
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void clearCache() {
        fileCache.evictAll();
        cacheTimestamps.evictAll();
    }

    private boolean isCacheValid(String key) {
        Long timestamp = cacheTimestamps.get(key);
        if (timestamp == null) return false;
        return System.currentTimeMillis() - timestamp < CACHE_EXPIRY_MS;
    }

    private void putCache(String key, String value) {
        fileCache.put(key, value);
        cacheTimestamps.put(key, System.currentTimeMillis());
    }

    // ── Projects ────────────────────────────────────────────────

    public void listProjects(Callback<List<Project>> callback) {
        executor.execute(() -> {
            try {
                String json = get("/projects");
                JSONArray arr = new JSONArray(json);
                List<Project> projects = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.getJSONObject(i);
                    projects.add(new Project(obj.getString("id")));
                }
                postSuccess(callback, projects);
            } catch (Exception e) {
                postError(callback, e.getMessage());
            }
        });
    }

    public void createProject(String name, Callback<Project> callback) {
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("name", name);
                String json = post("/projects", body.toString());
                JSONObject obj = new JSONObject(json);
                clearCache(); // Clear cache after creating project
                postSuccess(callback, new Project(obj.getString("id")));
            } catch (Exception e) {
                postError(callback, e.getMessage());
            }
        });
    }

    public void deleteProject(String projectId, Callback<Void> callback) {
        executor.execute(() -> {
            try {
                delete("/projects/" + projectId);
                clearCache(); // Clear cache after deleting project
                postSuccess(callback, null);
            } catch (Exception e) {
                postError(callback, e.getMessage());
            }
        });
    }

    // ── File management ─────────────────────────────────────────

    public void getFileTree(String projectId, Callback<JSONArray> callback) {
        String cacheKey = "tree_" + projectId;
        if (isCacheValid(cacheKey)) {
            try {
                String cached = fileCache.get(cacheKey);
                if (cached != null) {
                    postSuccess(callback, new JSONArray(cached));
                    return;
                }
            } catch (Exception e) {
                // Cache miss, continue to fetch
            }
        }

        executor.execute(() -> {
            try {
                String json = get("/projects/" + projectId + "/tree");
                JSONObject obj = new JSONObject(json);
                JSONArray tree = obj.getJSONArray("tree");
                putCache(cacheKey, tree.toString());
                postSuccess(callback, tree);
            } catch (Exception e) {
                postError(callback, e.getMessage());
            }
        });
    }

    public void readFile(String projectId, String path, Callback<String> callback) {
        String cacheKey = "file_" + projectId + "_" + path;
        if (isCacheValid(cacheKey)) {
            String cached = fileCache.get(cacheKey);
            if (cached != null) {
                postSuccess(callback, cached);
                return;
            }
        }

        executor.execute(() -> {
            try {
                String json = get("/projects/" + projectId + "/files?path=" + java.net.URLEncoder.encode(path, "UTF-8"));
                JSONObject obj = new JSONObject(json);
                String content = obj.getString("content");

                // Only cache small files (< 100KB)
                if (content.length() < 100000) {
                    putCache(cacheKey, content);
                }

                postSuccess(callback, content);
            } catch (Exception e) {
                postError(callback, e.getMessage());
            }
        });
    }

    public void saveFile(String projectId, String path, String content, boolean autoCommit, Callback<Void> callback) {
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("path", path);
                body.put("content", content);
                body.put("auto_commit", autoCommit);
                put("/projects/" + projectId + "/files", body.toString());

                // Invalidate cache for this file and tree
                fileCache.remove("file_" + projectId + "_" + path);
                fileCache.remove("tree_" + projectId);
                cacheTimestamps.remove("file_" + projectId + "_" + path);
                cacheTimestamps.remove("tree_" + projectId);

                postSuccess(callback, null);
            } catch (Exception e) {
                postError(callback, e.getMessage());
            }
        });
    }

    public void deleteFile(String projectId, String path, Callback<Void> callback) {
        executor.execute(() -> {
            try {
                delete("/projects/" + projectId + "/files?path=" + java.net.URLEncoder.encode(path, "UTF-8"));

                // Invalidate cache
                fileCache.remove("file_" + projectId + "_" + path);
                fileCache.remove("tree_" + projectId);
                cacheTimestamps.remove("file_" + projectId + "_" + path);
                cacheTimestamps.remove("tree_" + projectId);

                postSuccess(callback, null);
            } catch (Exception e) {
                postError(callback, e.getMessage());
            }
        });
    }

    // ── Git operations ──────────────────────────────────────────

    public void gitCommit(String projectId, String message, Callback<JSONObject> callback) {
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("message", message);
                String json = post("/projects/" + projectId + "/git/commit", body.toString());
                postSuccess(callback, new JSONObject(json));
            } catch (Exception e) {
                postError(callback, e.getMessage());
            }
        });
    }

    public void gitPush(String projectId, Callback<JSONObject> callback) {
        executor.execute(() -> {
            try {
                String json = post("/projects/" + projectId + "/git/push", "{}");
                postSuccess(callback, new JSONObject(json));
            } catch (Exception e) {
                postError(callback, e.getMessage());
            }
        });
    }

    public void gitPull(String projectId, Callback<JSONObject> callback) {
        executor.execute(() -> {
            try {
                String json = post("/projects/" + projectId + "/git/pull", "{}");
                postSuccess(callback, new JSONObject(json));
            } catch (Exception e) {
                postError(callback, e.getMessage());
            }
        });
    }

    public void gitLog(String projectId, Callback<JSONArray> callback) {
        executor.execute(() -> {
            try {
                String json = get("/projects/" + projectId + "/git/log");
                JSONObject obj = new JSONObject(json);
                postSuccess(callback, obj.getJSONArray("log"));
            } catch (Exception e) {
                postError(callback, e.getMessage());
            }
        });
    }

    public void gitStatus(String projectId, Callback<JSONObject> callback) {
        executor.execute(() -> {
            try {
                String json = get("/projects/" + projectId + "/git/status");
                postSuccess(callback, new JSONObject(json));
            } catch (Exception e) {
                postError(callback, e.getMessage());
            }
        });
    }

    // ── HTTP primitives ─────────────────────────────────────────

    private String get(String path) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("Accept-Encoding", "gzip");
        return readResponse(conn);
    }

    private String post(String path, String body) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(15000);
        conn.setDoOutput(true);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        return readResponse(conn);
    }

    private String put(String path, String body) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        conn.setRequestMethod("PUT");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(15000);
        conn.setDoOutput(true);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        return readResponse(conn);
    }

    private void delete(String path) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        conn.setRequestMethod("DELETE");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(15000);
        readResponse(conn);
    }

    private String readResponse(HttpURLConnection conn) throws IOException {
        int code = conn.getResponseCode();
        java.io.InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (is == null) throw new IOException("HTTP " + code);

        // Check if response is gzip encoded
        String encoding = conn.getContentEncoding();
        if ("gzip".equalsIgnoreCase(encoding)) {
            is = new java.util.zip.GZIPInputStream(is);
        }

        BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line);
        reader.close();
        if (code >= 400) throw new IOException("HTTP " + code + ": " + sb);
        return sb.toString();
    }

    // ── Helpers ─────────────────────────────────────────────────

    private <T> void postSuccess(Callback<T> callback, T result) {
        mainHandler.post(() -> callback.onSuccess(result));
    }

    private <T> void postError(Callback<T> callback, String error) {
        mainHandler.post(() -> callback.onError(error));
    }
}