package com.pyonphone.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.ECGenParameterSpec;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SshKeyManager {

    private static final String TAG = "SshKeyManager";
    private static final String PREFS_NAME = "ssh_config";
    private static final String KEY_PRIVATE_KEY = "private_key";
    private static final String KEY_PUBLIC_KEY = "public_key";
    private static final String KEY_KEY_TYPE = "key_type";

    public interface KeyCallback {
        void onSuccess(String publicKey);
        void onError(String error);
    }

    private static SshKeyManager instance;
    private final Context context;
    private final SharedPreferences prefs;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private SshKeyManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static synchronized SshKeyManager getInstance(Context context) {
        if (instance == null) {
            instance = new SshKeyManager(context);
        }
        return instance;
    }

    public boolean hasKey() {
        return prefs.contains(KEY_PRIVATE_KEY) && prefs.contains(KEY_PUBLIC_KEY);
    }

    public String getPublicKey() {
        return prefs.getString(KEY_PUBLIC_KEY, null);
    }

    public String getKeyType() {
        return prefs.getString(KEY_KEY_TYPE, "RSA");
    }

    public void generateKeyPair(String keyType, KeyCallback callback) {
        executor.execute(() -> {
            try {
                KeyPairGenerator keyGen;
                int keySize;

                switch (keyType.toUpperCase()) {
                    case "ECDSA":
                        keyGen = KeyPairGenerator.getInstance("EC");
                        keyGen.initialize(new ECGenParameterSpec("secp256r1"));
                        break;
                    case "ED25519":
                        // Ed25519 requires Android API 26+ and specific provider
                        keyGen = KeyPairGenerator.getInstance("Ed25519");
                        break;
                    case "RSA":
                    default:
                        keyGen = KeyPairGenerator.getInstance("RSA");
                        keySize = 2048;
                        keyGen.initialize(keySize);
                        break;
                }

                KeyPair keyPair = keyGen.generateKeyPair();
                PrivateKey privateKey = keyPair.getPrivate();
                PublicKey publicKey = keyPair.getPublic();

                // Encode keys
                String privateKeyBase64 = Base64.encodeToString(privateKey.getEncoded(), Base64.NO_WRAP);
                String publicKeyBase64 = Base64.encodeToString(publicKey.getEncoded(), Base64.NO_WRAP);

                // Format public key for SSH
                String sshPublicKey = formatSshPublicKey(publicKey, keyType);

                // Save to SharedPreferences
                prefs.edit()
                        .putString(KEY_PRIVATE_KEY, privateKeyBase64)
                        .putString(KEY_PUBLIC_KEY, sshPublicKey)
                        .putString(KEY_KEY_TYPE, keyType)
                        .apply();

                Log.d(TAG, "Generated " + keyType + " key pair");
                callback.onSuccess(sshPublicKey);

            } catch (Exception e) {
                Log.e(TAG, "Failed to generate key pair", e);
                callback.onError("生成密钥失败: " + e.getMessage());
            }
        });
    }

    private String formatSshPublicKey(PublicKey publicKey, String keyType) {
        byte[] encoded = publicKey.getEncoded();
        String base64 = Base64.encodeToString(encoded, Base64.NO_WRAP);

        String keyPrefix;
        switch (keyType.toUpperCase()) {
            case "ECDSA":
                keyPrefix = "ecdsa-sha2-nistp256";
                break;
            case "ED25519":
                keyPrefix = "ssh-ed25519";
                break;
            case "RSA":
            default:
                keyPrefix = "ssh-rsa";
                break;
        }

        return keyPrefix + " " + base64 + " pyonphone@" + android.os.Build.MODEL;
    }

    public void deleteKey() {
        prefs.edit()
                .remove(KEY_PRIVATE_KEY)
                .remove(KEY_PUBLIC_KEY)
                .remove(KEY_KEY_TYPE)
                .apply();
        Log.d(TAG, "SSH key deleted");
    }

    public PrivateKey getPrivateKey() {
        String privateKeyBase64 = prefs.getString(KEY_PRIVATE_KEY, null);
        if (privateKeyBase64 == null) return null;

        try {
            byte[] keyBytes = Base64.decode(privateKeyBase64, Base64.NO_WRAP);
            String keyType = getKeyType();

            KeyFactory keyFactory;
            switch (keyType.toUpperCase()) {
                case "ECDSA":
                    keyFactory = KeyFactory.getInstance("EC");
                    break;
                case "ED25519":
                    keyFactory = KeyFactory.getInstance("Ed25519");
                    break;
                case "RSA":
                default:
                    keyFactory = KeyFactory.getInstance("RSA");
                    break;
            }

            PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(keyBytes);
            return keyFactory.generatePrivate(keySpec);
        } catch (Exception e) {
            Log.e(TAG, "Failed to load private key", e);
            return null;
        }
    }

    public String getFingerprint() {
        String publicKey = getPublicKey();
        if (publicKey == null) return null;

        try {
            // Simple fingerprint based on public key hash
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(publicKey.getBytes());

            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < hash.length; i++) {
                if (i > 0) sb.append(":");
                sb.append(String.format("%02x", hash[i] & 0xff));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}