package com.pyonphone.app;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

public class GitFragment extends Fragment {

    private String projectId;
    private TextView txtBranch, txtChanges, txtChangedFiles, txtMode, txtRemoteUrl;
    private ImageButton btnMode, btnConfigRemote;
    private CommitAdapter commitAdapter;
    private GitExecutor gitExecutor;
    private boolean localMode = false; // false=remote, true=local

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_git, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        txtBranch = view.findViewById(R.id.txt_branch);
        txtChanges = view.findViewById(R.id.txt_changes);
        txtChangedFiles = view.findViewById(R.id.txt_changed_files);
        txtMode = view.findViewById(R.id.txt_git_mode);
        txtRemoteUrl = view.findViewById(R.id.txt_remote_url);
        btnMode = view.findViewById(R.id.btn_git_mode);
        btnConfigRemote = view.findViewById(R.id.btn_config_remote);
        MaterialButton btnCommit = view.findViewById(R.id.btn_commit);
        MaterialButton btnPush = view.findViewById(R.id.btn_push);
        MaterialButton btnPull = view.findViewById(R.id.btn_pull);
        RecyclerView commitList = view.findViewById(R.id.commit_list);

        commitAdapter = new CommitAdapter();
        commitList.setLayoutManager(new LinearLayoutManager(requireContext()));
        commitList.setAdapter(commitAdapter);

        gitExecutor = GitExecutor.getInstance(requireContext());

        btnCommit.setOnClickListener(v -> showCommitDialog());
        btnPush.setOnClickListener(v -> doPush());
        btnPull.setOnClickListener(v -> doPull());
        btnMode.setOnClickListener(v -> toggleExecutionMode());
        btnConfigRemote.setOnClickListener(v -> showRemoteConfigDialog());

        // Get projectId from parent activity or arguments
        Bundle args = getArguments();
        if (args != null) {
            projectId = args.getString("project_id", "");
        }
        // Also check if we're in the editor's project context
        if (projectId == null || projectId.isEmpty()) {
            // Try to get from the activity's intent or saved state
            // For simplicity, we'll use SharedPreferences
            projectId = requireContext().getSharedPreferences("pyonphone", 0)
                    .getString("current_project_id", "");
        }
        if (projectId != null && !projectId.isEmpty()) {
            refresh();
        }
    }

    private void toggleExecutionMode() {
        localMode = !localMode;
        updateModeIndicator();
        refresh();
    }

    private void updateModeIndicator() {
        if (txtMode != null) {
            txtMode.setText(localMode ? "本地" : "远程");
        }
    }

    private void showRemoteConfigDialog() {
        View dialogView = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_remote_config, null);

        TextInputEditText editUrl = dialogView.findViewById(R.id.edit_remote_url);
        String currentUrl = gitExecutor.getRemoteUrl(projectId);
        if (currentUrl != null) {
            editUrl.setText(currentUrl);
        }
        editUrl.setHint("https://github.com/username/repo.git");

        new AlertDialog.Builder(requireContext())
                .setTitle("配置远程仓库")
                .setView(dialogView)
                .setPositiveButton("保存", (dialog, which) -> {
                    String url = editUrl.getText() != null
                            ? editUrl.getText().toString().trim() : "";
                    if (!url.isEmpty()) {
                        configureRemote(url);
                    }
                })
                .setNegativeButton("取消", null)
                .setNeutralButton("删除远程", (dialog, which) -> {
                    removeRemote();
                })
                .show();
    }

    private void configureRemote(String url) {
        gitExecutor.setRemoteUrl(projectId, url, new GitExecutor.GitCallback<String>() {
            @Override
            public void onSuccess(String result) {
                Toast.makeText(requireContext(), result, Toast.LENGTH_SHORT).show();
                loadRemoteUrl();
            }

            @Override
            public void onError(String error) {
                Toast.makeText(requireContext(), "配置失败: " + error, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void removeRemote() {
        // Clear from SharedPreferences
        requireContext().getSharedPreferences("git_config", 0)
                .edit()
                .remove("remote_url_" + projectId)
                .apply();

        if (txtRemoteUrl != null) {
            txtRemoteUrl.setText("未配置远程仓库");
        }
        Toast.makeText(requireContext(), "远程仓库已删除", Toast.LENGTH_SHORT).show();
    }

    private void loadRemoteUrl() {
        if (txtRemoteUrl == null) return;

        gitExecutor.getRemoteUrl(projectId, new GitExecutor.GitCallback<String>() {
            @Override
            public void onSuccess(String url) {
                txtRemoteUrl.setText(url);
            }

            @Override
            public void onError(String error) {
                txtRemoteUrl.setText("未配置远程仓库");
            }
        });
    }

    public void setProjectId(String id) {
        this.projectId = id;
        if (isResumed()) refresh();
    }

    private void refresh() {
        loadStatus();
        loadLog();
        loadRemoteUrl();
    }

    private void loadStatus() {
        if (localMode) {
            loadLocalStatus();
        } else {
            loadRemoteStatus();
        }
    }

    private void loadLocalStatus() {
        if (!gitExecutor.isRepository(projectId)) {
            txtBranch.setText("未初始化 Git 仓库");
            txtChanges.setText("");
            txtChangedFiles.setVisibility(View.GONE);
            return;
        }

        gitExecutor.getStatus(projectId, new GitExecutor.GitCallback<GitExecutor.GitStatus>() {
            @Override
            public void onSuccess(GitExecutor.GitStatus status) {
                String branchText = "分支: " + status.branch;
                if (status.ahead > 0) {
                    branchText += " (领先 " + status.ahead + ")";
                }
                if (status.behind > 0) {
                    branchText += " (落后 " + status.behind + ")";
                }
                txtBranch.setText(branchText);

                int count = status.added.size() + status.changed.size() +
                        status.modified.size() + status.removed.size() +
                        status.untracked.size();
                txtChanges.setText(count + " 个变更");

                if (count > 0) {
                    StringBuilder sb = new StringBuilder();
                    for (String f : status.added) sb.append("A  ").append(f).append("\n");
                    for (String f : status.changed) sb.append("C  ").append(f).append("\n");
                    for (String f : status.modified) sb.append("M  ").append(f).append("\n");
                    for (String f : status.removed) sb.append("D  ").append(f).append("\n");
                    for (String f : status.untracked) sb.append("?  ").append(f).append("\n");
                    txtChangedFiles.setText(sb.toString().trim());
                    txtChangedFiles.setVisibility(View.VISIBLE);
                } else {
                    txtChangedFiles.setVisibility(View.GONE);
                }
            }

            @Override
            public void onError(String error) {
                txtBranch.setText("获取状态失败");
                Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void loadRemoteStatus() {
        ApiClient.getInstance().gitStatus(projectId, new ApiClient.Callback<JSONObject>() {
            @Override
            public void onSuccess(JSONObject result) {
                try {
                    String branch = result.optString("branch", "main");
                    txtBranch.setText("分支: " + branch);

                    JSONArray files = result.getJSONArray("files");
                    int count = files.length();
                    txtChanges.setText(count + " 个变更");

                    if (count > 0) {
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < files.length(); i++) {
                            JSONObject f = files.getJSONObject(i);
                            sb.append(f.getString("status")).append("  ")
                                    .append(f.getString("path")).append("\n");
                        }
                        txtChangedFiles.setText(sb.toString().trim());
                        txtChangedFiles.setVisibility(View.VISIBLE);
                    } else {
                        txtChangedFiles.setVisibility(View.GONE);
                    }
                } catch (Exception e) {
                    // ignore
                }
            }

            @Override
            public void onError(String error) {
                txtBranch.setText("连接失败");
            }
        });
    }

    private void loadLog() {
        if (localMode) {
            loadLocalLog();
        } else {
            loadRemoteLog();
        }
    }

    private void loadLocalLog() {
        if (!gitExecutor.isRepository(projectId)) {
            commitAdapter.setCommits(new ArrayList<>());
            return;
        }

        gitExecutor.getLog(projectId, 20, new GitExecutor.GitCallback<List<GitExecutor.GitCommit>>() {
            @Override
            public void onSuccess(List<GitExecutor.GitCommit> commits) {
                List<CommitAdapter.Commit> adapterCommits = new ArrayList<>();
                for (GitExecutor.GitCommit gc : commits) {
                    adapterCommits.add(new CommitAdapter.Commit(
                            gc.hash,
                            gc.message,
                            String.valueOf(gc.time)
                    ));
                }
                commitAdapter.setCommits(adapterCommits);
            }

            @Override
            public void onError(String error) {
                // silent
            }
        });
    }

    private void loadRemoteLog() {
        ApiClient.getInstance().gitLog(projectId, new ApiClient.Callback<JSONArray>() {
            @Override
            public void onSuccess(JSONArray result) {
                List<CommitAdapter.Commit> commits = new ArrayList<>();
                try {
                    for (int i = 0; i < result.length(); i++) {
                        JSONObject obj = result.getJSONObject(i);
                        commits.add(new CommitAdapter.Commit(
                                obj.getString("hash"),
                                obj.getString("message"),
                                obj.getString("date")
                        ));
                    }
                } catch (Exception e) {
                    // ignore
                }
                commitAdapter.setCommits(commits);
            }

            @Override
            public void onError(String error) {
                // silent
            }
        });
    }

    private void showCommitDialog() {
        View dialogView = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_new_project, null); // reuse simple input layout
        TextInputEditText editMessage = dialogView.findViewById(R.id.edit_project_name);
        editMessage.setHint("提交信息");

        new AlertDialog.Builder(requireContext())
                .setTitle("Git 提交")
                .setView(dialogView)
                .setPositiveButton("提交", (dialog, which) -> {
                    String msg = editMessage.getText() != null
                            ? editMessage.getText().toString().trim() : "";
                    if (msg.isEmpty()) msg = "manual commit";
                    doCommit(msg);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void doCommit(String message) {
        if (localMode) {
            doLocalCommit(message);
        } else {
            doRemoteCommit(message);
        }
    }

    private void doLocalCommit(String message) {
        if (!gitExecutor.isRepository(projectId)) {
            gitExecutor.initRepository(projectId, new GitExecutor.GitCallback<String>() {
                @Override
                public void onSuccess(String result) {
                    // Now commit
                    gitExecutor.commit(projectId, message, new GitExecutor.GitCallback<String>() {
                        @Override
                        public void onSuccess(String hash) {
                            Toast.makeText(requireContext(), "已提交: " + hash, Toast.LENGTH_SHORT).show();
                            refresh();
                        }

                        @Override
                        public void onError(String error) {
                            Toast.makeText(requireContext(), "提交失败: " + error, Toast.LENGTH_SHORT).show();
                        }
                    });
                }

                @Override
                public void onError(String error) {
                    Toast.makeText(requireContext(), "初始化仓库失败: " + error, Toast.LENGTH_SHORT).show();
                }
            });
            return;
        }

        gitExecutor.commit(projectId, message, new GitExecutor.GitCallback<String>() {
            @Override
            public void onSuccess(String hash) {
                Toast.makeText(requireContext(), "已提交: " + hash, Toast.LENGTH_SHORT).show();
                refresh();
            }

            @Override
            public void onError(String error) {
                Toast.makeText(requireContext(), "提交失败: " + error, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void doRemoteCommit(String message) {
        ApiClient.getInstance().gitCommit(projectId, message, new ApiClient.Callback<JSONObject>() {
            @Override
            public void onSuccess(JSONObject result) {
                try {
                    boolean ok = result.optBoolean("ok", false);
                    String msg = result.optString("message", result.optString("stderr", ""));
                    Toast.makeText(requireContext(),
                            ok ? "已提交" : msg, Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(requireContext(), "已提交", Toast.LENGTH_SHORT).show();
                }
                refresh();
            }

            @Override
            public void onError(String error) {
                Toast.makeText(requireContext(), "提交失败: " + error, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void doPush() {
        if (localMode) {
            doLocalPush();
        } else {
            doRemotePush();
        }
    }

    private void doLocalPush() {
        Toast.makeText(requireContext(), "推送中...", Toast.LENGTH_SHORT).show();
        gitExecutor.push(projectId, new GitExecutor.GitCallback<String>() {
            @Override
            public void onSuccess(String result) {
                Toast.makeText(requireContext(), "推送成功", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(String error) {
                Toast.makeText(requireContext(), "推送失败: " + error, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void doRemotePush() {
        Toast.makeText(requireContext(), "推送中...", Toast.LENGTH_SHORT).show();
        ApiClient.getInstance().gitPush(projectId, new ApiClient.Callback<JSONObject>() {
            @Override
            public void onSuccess(JSONObject result) {
                boolean ok = result.optBoolean("ok", false);
                Toast.makeText(requireContext(),
                        ok ? "推送成功" : "推送失败: " + result.optString("stderr", ""),
                        Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(String error) {
                Toast.makeText(requireContext(), "推送失败: " + error, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void doPull() {
        if (localMode) {
            doLocalPull();
        } else {
            doRemotePull();
        }
    }

    private void doLocalPull() {
        Toast.makeText(requireContext(), "拉取中...", Toast.LENGTH_SHORT).show();
        gitExecutor.pull(projectId, new GitExecutor.GitCallback<String>() {
            @Override
            public void onSuccess(String result) {
                Toast.makeText(requireContext(), "拉取成功", Toast.LENGTH_SHORT).show();
                refresh();
            }

            @Override
            public void onError(String error) {
                Toast.makeText(requireContext(), "拉取失败: " + error, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void doRemotePull() {
        Toast.makeText(requireContext(), "拉取中...", Toast.LENGTH_SHORT).show();
        ApiClient.getInstance().gitPull(projectId, new ApiClient.Callback<JSONObject>() {
            @Override
            public void onSuccess(JSONObject result) {
                boolean ok = result.optBoolean("ok", false);
                Toast.makeText(requireContext(),
                        ok ? "拉取成功" : "拉取失败: " + result.optString("stderr", ""),
                        Toast.LENGTH_SHORT).show();
                refresh();
            }

            @Override
            public void onError(String error) {
                Toast.makeText(requireContext(), "拉取失败: " + error, Toast.LENGTH_SHORT).show();
            }
        });
    }
}