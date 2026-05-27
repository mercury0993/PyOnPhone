package com.pyonphone.app;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SearchView;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.textfield.TextInputEditText;
import java.util.ArrayList;
import java.util.List;

public class ProjectsFragment extends Fragment {

    private static final int REQUEST_IMPORT_PROJECT = 1001;

    private ProjectsAdapter adapter;
    private RecyclerView recyclerView;
    private TextView emptyText;
    private ProgressBar loading;
    private SearchView searchView;
    private List<Project> allProjects = new ArrayList<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_projects, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        recyclerView = view.findViewById(R.id.projects_list);
        emptyText = view.findViewById(R.id.empty_text);
        loading = view.findViewById(R.id.loading);
        searchView = view.findViewById(R.id.search_view);
        FloatingActionButton fab = view.findViewById(R.id.fab_add);

        adapter = new ProjectsAdapter();
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerView.setAdapter(adapter);

        adapter.setOnProjectClickListener(project -> {
            // Save current project ID for other fragments
            requireContext().getSharedPreferences("pyonphone", 0)
                    .edit().putString("current_project_id", project.getId()).apply();
            Bundle args = new Bundle();
            args.putString("project_id", project.getId());
            Navigation.findNavController(view)
                    .navigate(R.id.nav_editor, args);
        });

        adapter.setOnProjectLongClickListener(this::showProjectOptionsDialog);

        fab.setOnClickListener(v -> showCreateDialog());

        setupSearchView();
        loadProjects();
    }

    private void setupSearchView() {
        if (searchView == null) return;

        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                filterProjects(query);
                return true;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                filterProjects(newText);
                return true;
            }
        });

        searchView.setOnCloseListener(() -> {
            filterProjects("");
            return false;
        });
    }

    private void filterProjects(String query) {
        if (query.isEmpty()) {
            adapter.setProjects(allProjects);
            return;
        }

        List<Project> filtered = new ArrayList<>();
        String lowerQuery = query.toLowerCase();
        for (Project project : allProjects) {
            if (project.getId().toLowerCase().contains(lowerQuery)) {
                filtered.add(project);
            }
        }
        adapter.setProjects(filtered);
    }

    private void loadProjects() {
        loading.setVisibility(View.VISIBLE);
        recyclerView.setVisibility(View.GONE);
        emptyText.setVisibility(View.GONE);

        ApiClient.getInstance().listProjects(new ApiClient.Callback<List<Project>>() {
            @Override
            public void onSuccess(List<Project> projects) {
                loading.setVisibility(View.GONE);
                allProjects = projects;
                if (projects.isEmpty()) {
                    emptyText.setVisibility(View.VISIBLE);
                } else {
                    recyclerView.setVisibility(View.VISIBLE);
                    adapter.setProjects(projects);
                }
            }

            @Override
            public void onError(String error) {
                loading.setVisibility(View.GONE);
                emptyText.setVisibility(View.VISIBLE);
                emptyText.setText("连接服务器失败\n" + error);
            }
        });
    }

    private void showCreateDialog() {
        View dialogView = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_new_project, null);
        TextInputEditText editName = dialogView.findViewById(R.id.edit_project_name);

        new AlertDialog.Builder(requireContext())
                .setTitle("新建项目")
                .setView(dialogView)
                .setPositiveButton("创建", (dialog, which) -> {
                    String name = editName.getText() != null
                            ? editName.getText().toString().trim() : "";
                    createProject(name);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void createProject(String name) {
        ApiClient.getInstance().createProject(name, new ApiClient.Callback<Project>() {
            @Override
            public void onSuccess(Project project) {
                Toast.makeText(requireContext(),
                        "项目已创建: " + project.getId(), Toast.LENGTH_SHORT).show();
                loadProjects();
            }

            @Override
            public void onError(String error) {
                Toast.makeText(requireContext(),
                        "创建失败: " + error, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void showProjectOptionsDialog(Project project) {
        String[] options = {"删除项目", "导出项目", "复制项目名称"};

        new AlertDialog.Builder(requireContext())
                .setTitle(project.getId())
                .setItems(options, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            showDeleteDialog(project);
                            break;
                        case 1:
                            exportProject(project);
                            break;
                        case 2:
                            copyProjectName(project);
                            break;
                    }
                })
                .show();
    }

    private void showDeleteDialog(Project project) {
        new AlertDialog.Builder(requireContext())
                .setTitle("删除项目")
                .setMessage("确定删除项目 \"" + project.getId() + "\"？\n此操作不可撤销。")
                .setPositiveButton("删除", (dialog, which) -> deleteProject(project))
                .setNegativeButton("取消", null)
                .show();
    }

    private void deleteProject(Project project) {
        ApiClient.getInstance().deleteProject(project.getId(), new ApiClient.Callback<Void>() {
            @Override
            public void onSuccess(Void result) {
                Toast.makeText(requireContext(),
                        "已删除", Toast.LENGTH_SHORT).show();
                loadProjects();
            }

            @Override
            public void onError(String error) {
                Toast.makeText(requireContext(),
                        "删除失败: " + error, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void exportProject(Project project) {
        // Export project as zip via API
        Toast.makeText(requireContext(),
                "导出功能需要服务器支持", Toast.LENGTH_SHORT).show();
    }

    private void copyProjectName(Project project) {
        android.content.ClipboardManager clipboard =
                (android.content.ClipboardManager) requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
        android.content.ClipData clip = android.content.ClipData.newPlainText("project_name", project.getId());
        clipboard.setPrimaryClip(clip);
        Toast.makeText(requireContext(),
                "已复制: " + project.getId(), Toast.LENGTH_SHORT).show();
    }

    public void importProject() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("application/zip");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, REQUEST_IMPORT_PROJECT);
    }
}