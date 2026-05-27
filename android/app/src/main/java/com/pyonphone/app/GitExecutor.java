package com.pyonphone.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.PullResult;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.api.RemoteAddCommand;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.BranchTrackingStatus;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class GitExecutor {

    private static final String TAG = "GitExecutor";
    private static final String PREFS_NAME = "git_config";
    private static final String REMOTE_URL_PREFIX = "remote_url_";

    public interface GitCallback<T> {
        void onSuccess(T result);
        void onError(String error);
    }

    public static class GitStatus {
        public String branch;
        public int ahead;
        public int behind;
        public List<String> added = new ArrayList<>();
        public List<String> changed = new ArrayList<>();
        public List<String> modified = new ArrayList<>();
        public List<String> removed = new ArrayList<>();
        public List<String> untracked = new ArrayList<>();
        public List<String> conflicting = new ArrayList<>();
    }

    public static class GitCommit {
        public String hash;
        public String message;
        public String author;
        public long time;
    }

    private static GitExecutor instance;
    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final SharedPreferences prefs;

    private GitExecutor(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static synchronized GitExecutor getInstance(Context context) {
        if (instance == null) {
            instance = new GitExecutor(context);
        }
        return instance;
    }

    private String getProjectPath(String projectId) {
        return context.getFilesDir() + "/projects/" + projectId;
    }

    private Git openGit(String projectId) throws IOException {
        File projectDir = new File(getProjectPath(projectId));
        File gitDir = new File(projectDir, ".git");

        if (!gitDir.exists()) {
            // Initialize git repository
            try {
                return Git.init().setDirectory(projectDir).call();
            } catch (GitAPIException e) {
                throw new IOException("Failed to initialize git repository", e);
            }
        }

        Repository repository = new FileRepositoryBuilder()
                .setGitDir(gitDir)
                .build();
        return new Git(repository);
    }

    // Remote URL management
    public void setRemoteUrl(String projectId, String url, GitCallback<String> callback) {
        executor.execute(() -> {
            try (Git git = openGit(projectId)) {
                Repository repo = git.getRepository();
                StoredConfig config = repo.getConfig();

                // Check if remote "origin" exists
                List<RemoteConfig> remotes = RemoteConfig.getAllRemoteConfigs(config);

                if (remotes.isEmpty()) {
                    // Add new remote
                    RemoteAddCommand command = git.remoteAdd();
                    command.setName("origin");
                    command.setUri(new URIish(url));
                    command.call();
                } else {
                    // Update existing remote
                    config.setString("remote", "origin", "url", url);
                    config.save();
                }

                // Save to SharedPreferences for quick access
                prefs.edit().putString(REMOTE_URL_PREFIX + projectId, url).apply();

                Log.d(TAG, "Remote URL set to: " + url);
                mainHandler.post(() -> callback.onSuccess("远程仓库已配置"));
            } catch (Exception e) {
                Log.e(TAG, "Failed to set remote URL", e);
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    public String getRemoteUrl(String projectId) {
        // First try to get from git config
        try (Git git = openGit(projectId)) {
            Repository repo = git.getRepository();
            StoredConfig config = repo.getConfig();
            String url = config.getString("remote", "origin", "url");
            if (url != null) {
                return url;
            }
        } catch (Exception e) {
            // ignore
        }

        // Fallback to SharedPreferences
        return prefs.getString(REMOTE_URL_PREFIX + projectId, null);
    }

    public void getRemoteUrl(String projectId, GitCallback<String> callback) {
        executor.execute(() -> {
            String url = getRemoteUrl(projectId);
            mainHandler.post(() -> {
                if (url != null) {
                    callback.onSuccess(url);
                } else {
                    callback.onError("未配置远程仓库");
                }
            });
        });
    }

    public void getStatus(String projectId, GitCallback<GitStatus> callback) {
        executor.execute(() -> {
            try (Git git = openGit(projectId)) {
                Status status = git.status().call();
                Repository repo = git.getRepository();

                GitStatus result = new GitStatus();
                result.branch = repo.getBranch();
                result.added.addAll(status.getAdded());
                result.changed.addAll(status.getChanged());
                result.modified.addAll(status.getModified());
                result.removed.addAll(status.getRemoved());
                result.untracked.addAll(status.getUntracked());
                result.conflicting.addAll(status.getConflicting());

                // Get tracking status
                try {
                    BranchTrackingStatus trackingStatus = BranchTrackingStatus.of(repo, result.branch);
                    if (trackingStatus != null) {
                        result.ahead = trackingStatus.getAheadCount();
                        result.behind = trackingStatus.getBehindCount();
                    }
                } catch (IOException e) {
                    // ignore
                }

                mainHandler.post(() -> callback.onSuccess(result));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    public void commit(String projectId, String message, GitCallback<String> callback) {
        executor.execute(() -> {
            try (Git git = openGit(projectId)) {
                // Add all changes
                git.add().addFilepattern(".").call();

                // Commit
                RevCommit commit = git.commit()
                        .setMessage(message)
                        .call();

                Log.d(TAG, "Committed: " + commit.getName());
                mainHandler.post(() -> callback.onSuccess(commit.getName()));
            } catch (Exception e) {
                Log.e(TAG, "Commit failed", e);
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    public void getLog(String projectId, int maxCount, GitCallback<List<GitCommit>> callback) {
        executor.execute(() -> {
            try (Git git = openGit(projectId)) {
                Iterable<RevCommit> commits = git.log().setMaxCount(maxCount).call();
                List<GitCommit> result = new ArrayList<>();

                for (RevCommit commit : commits) {
                    GitCommit gc = new GitCommit();
                    gc.hash = commit.getName().substring(0, 7);
                    gc.message = commit.getShortMessage();
                    gc.author = commit.getAuthorIdent().getName();
                    gc.time = commit.getCommitTime() * 1000L;
                    result.add(gc);
                }

                mainHandler.post(() -> callback.onSuccess(result));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    public void push(String projectId, GitCallback<String> callback) {
        executor.execute(() -> {
            try (Git git = openGit(projectId)) {
                // Check if remote is configured
                String remoteUrl = getRemoteUrl(projectId);
                if (remoteUrl == null) {
                    mainHandler.post(() -> callback.onError("请先配置远程仓库地址"));
                    return;
                }

                Log.d(TAG, "Pushing to: " + remoteUrl);
                Iterable<PushResult> results = git.push().call();
                StringBuilder sb = new StringBuilder();
                for (PushResult result : results) {
                    sb.append(result.getMessages());
                }
                mainHandler.post(() -> callback.onSuccess(sb.toString()));
            } catch (Exception e) {
                Log.e(TAG, "Push failed", e);
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    public void pull(String projectId, GitCallback<String> callback) {
        executor.execute(() -> {
            try (Git git = openGit(projectId)) {
                // Check if remote is configured
                String remoteUrl = getRemoteUrl(projectId);
                if (remoteUrl == null) {
                    mainHandler.post(() -> callback.onError("请先配置远程仓库地址"));
                    return;
                }

                Log.d(TAG, "Pulling from: " + remoteUrl);
                PullResult result = git.pull().call();
                String message = result.isSuccessful() ? "拉取成功" : "拉取失败";
                mainHandler.post(() -> callback.onSuccess(message));
            } catch (Exception e) {
                Log.e(TAG, "Pull failed", e);
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    public void initRepository(String projectId, GitCallback<String> callback) {
        executor.execute(() -> {
            try {
                File projectDir = new File(getProjectPath(projectId));
                if (!projectDir.exists()) {
                    projectDir.mkdirs();
                }

                try (Git git = Git.init().setDirectory(projectDir).call()) {
                    Log.d(TAG, "Repository initialized: " + projectDir.getAbsolutePath());
                    mainHandler.post(() -> callback.onSuccess("仓库已初始化"));
                }
            } catch (Exception e) {
                Log.e(TAG, "Init failed", e);
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    public boolean isRepository(String projectId) {
        File gitDir = new File(getProjectPath(projectId), ".git");
        return gitDir.exists();
    }

    public void shutdown() {
        executor.shutdown();
    }
}