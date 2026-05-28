package com.pyonphone.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.fragment.app.Fragment;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;

public class SettingsFragment extends Fragment {

    private SharedPreferences prefs;
    private SshKeyManager sshKeyManager;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        prefs = requireContext().getSharedPreferences("pyonphone", Context.MODE_PRIVATE);
        sshKeyManager = SshKeyManager.getInstance(requireContext());

        TextInputEditText editUrl = view.findViewById(R.id.edit_server_url);
        MaterialButton btnSaveUrl = view.findViewById(R.id.btn_save_url);
        SwitchMaterial switchDark = view.findViewById(R.id.switch_dark_theme);
        SeekBar seekFontSize = view.findViewById(R.id.seek_font_size);
        TextView txtFontSize = view.findViewById(R.id.txt_font_size);
        SwitchMaterial switchLocalPython = view.findViewById(R.id.switch_local_python);
        SwitchMaterial switchAutoCommit = view.findViewById(R.id.switch_auto_commit);

        // SSH key views
        MaterialButton btnGenerateKey = view.findViewById(R.id.btn_generate_key);
        MaterialButton btnShowPublicKey = view.findViewById(R.id.btn_show_public_key);
        MaterialButton btnDeleteKey = view.findViewById(R.id.btn_delete_key);
        TextView txtKeyStatus = view.findViewById(R.id.txt_key_status);
        TextView txtKeyFingerprint = view.findViewById(R.id.txt_key_fingerprint);

        // Load saved values
        String savedUrl = prefs.getString("server_url", "http://10.0.2.2:5000");
        editUrl.setText(savedUrl);

        boolean darkTheme = prefs.getBoolean("dark_theme", true);
        switchDark.setChecked(darkTheme);

        int fontSize = prefs.getInt("font_size", 14);
        seekFontSize.setProgress(fontSize - 10); // range 10-30, progress 0-20
        txtFontSize.setText(fontSize + "sp");

        // Update SSH key status
        updateSshKeyStatus(txtKeyStatus, txtKeyFingerprint);

        // Save server URL
        btnSaveUrl.setOnClickListener(v -> {
            String url = editUrl.getText() != null ? editUrl.getText().toString().trim() : "";
            if (url.isEmpty()) {
                Toast.makeText(requireContext(), "请输入服务器地址", Toast.LENGTH_SHORT).show();
                return;
            }
            prefs.edit().putString("server_url", url).apply();
            ApiClient.getInstance().setBaseUrl(url);
            Toast.makeText(requireContext(), "已保存", Toast.LENGTH_SHORT).show();
        });

        // Theme toggle
        switchDark.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("dark_theme", isChecked).apply();
            AppCompatDelegate.setDefaultNightMode(
                    isChecked ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO);
        });

        // Font size
        seekFontSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int size = progress + 10;
                txtFontSize.setText(size + "sp");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int size = seekBar.getProgress() + 10;
                prefs.edit().putInt("font_size", size).apply();
            }
        });

        // Local Python toggle
        boolean localPython = prefs.getBoolean("local_python_enabled", false);
        switchLocalPython.setChecked(localPython);
        switchLocalPython.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("local_python_enabled", isChecked).apply();
        });

        // Auto commit toggle
        boolean autoCommit = prefs.getBoolean("auto_commit_enabled", true);
        switchAutoCommit.setChecked(autoCommit);
        switchAutoCommit.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("auto_commit_enabled", isChecked).apply();
        });

        // SSH key management
        btnGenerateKey.setOnClickListener(v -> showGenerateKeyDialog(txtKeyStatus, txtKeyFingerprint));
        btnShowPublicKey.setOnClickListener(v -> showPublicKeyDialog());
        btnDeleteKey.setOnClickListener(v -> showDeleteKeyDialog(txtKeyStatus, txtKeyFingerprint));
    }

    private void updateSshKeyStatus(TextView txtStatus, TextView txtFingerprint) {
        if (sshKeyManager.hasKey()) {
            txtStatus.setText("已生成");
            txtStatus.setTextColor(getResources().getColor(R.color.ios_system_green));

            String fingerprint = sshKeyManager.getFingerprint();
            if (fingerprint != null) {
                txtFingerprint.setText("SHA256:" + fingerprint.substring(0, 20) + "...");
                txtFingerprint.setVisibility(View.VISIBLE);
            }
        } else {
            txtStatus.setText("未生成");
            txtStatus.setTextColor(getResources().getColor(R.color.ios_secondary_label));
            txtFingerprint.setVisibility(View.GONE);
        }
    }

    private void showGenerateKeyDialog(TextView txtStatus, TextView txtFingerprint) {
        String[] keyTypes = {"RSA", "ECDSA", "ED25519"};

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("生成 SSH 密钥")
                .setItems(keyTypes, (dialog, which) -> {
                    String keyType = keyTypes[which];
                    generateSshKey(keyType, txtStatus, txtFingerprint);
                })
                .show();
    }

    private void generateSshKey(String keyType, TextView txtStatus, TextView txtFingerprint) {
        Toast.makeText(requireContext(), "正在生成 " + keyType + " 密钥...", Toast.LENGTH_SHORT).show();

        sshKeyManager.generateKeyPair(keyType, new SshKeyManager.KeyCallback() {
            @Override
            public void onSuccess(String publicKey) {
                Toast.makeText(requireContext(), "密钥已生成", Toast.LENGTH_SHORT).show();
                updateSshKeyStatus(txtStatus, txtFingerprint);
            }

            @Override
            public void onError(String error) {
                Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void showPublicKeyDialog() {
        String publicKey = sshKeyManager.getPublicKey();
        if (publicKey == null) {
            Toast.makeText(requireContext(), "请先生成 SSH 密钥", Toast.LENGTH_SHORT).show();
            return;
        }

        View dialogView = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_public_key, null);
        TextView txtPublicKey = dialogView.findViewById(R.id.txt_public_key);
        MaterialButton btnCopy = dialogView.findViewById(R.id.btn_copy_key);

        txtPublicKey.setText(publicKey);

        AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle("SSH 公钥")
                .setView(dialogView)
                .setPositiveButton("关闭", null)
                .show();

        btnCopy.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) requireContext()
                    .getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("SSH Public Key", publicKey);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(requireContext(), "已复制到剪贴板", Toast.LENGTH_SHORT).show();
        });
    }

    private void showDeleteKeyDialog(TextView txtStatus, TextView txtFingerprint) {
        if (!sshKeyManager.hasKey()) {
            Toast.makeText(requireContext(), "没有可删除的密钥", Toast.LENGTH_SHORT).show();
            return;
        }

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("删除 SSH 密钥")
                .setMessage("确定删除 SSH 密钥？\n删除后将无法通过 SSH 访问远程仓库。")
                .setPositiveButton("删除", (dialog, which) -> {
                    sshKeyManager.deleteKey();
                    updateSshKeyStatus(txtStatus, txtFingerprint);
                    Toast.makeText(requireContext(), "密钥已删除", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }
}