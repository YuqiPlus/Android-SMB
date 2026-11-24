package com.yuqiplus.androidsmb;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

final class Settings {
    static final int DEFAULT_PORT = 1445;
    static final String DEFAULT_USER = "smb";
    static final String SHARE = "Share";
    private static final String LEGACY_PASSWORD = "password";
    private static final String ENCODED_PASSWORD = "passwordBase64";

    final String user;
    final String password;
    final int port;
    final String allowedNetworks;

    Settings(String user, String password, int port, String allowedNetworks) {
        this.user = user;
        this.password = password;
        this.port = port;
        this.allowedNetworks = allowedNetworks;
    }

    static Settings load(Context context) {
        SharedPreferences prefs = preferences(context);
        String encoded = prefs.getString(ENCODED_PASSWORD, null);
        String password = encoded == null ? prefs.getString(LEGACY_PASSWORD, null)
            : new String(Base64.decode(encoded, Base64.DEFAULT), StandardCharsets.UTF_8);
        if (password == null) {
            byte[] random = new byte[18];
            new SecureRandom().nextBytes(random);
            password = Base64.encodeToString(random, Base64.URL_SAFE | Base64.NO_WRAP);
            prefs.edit().putString(ENCODED_PASSWORD, encode(password)).apply();
        }
        return new Settings(prefs.getString("user", DEFAULT_USER), password,
            prefs.getInt("port", DEFAULT_PORT), prefs.getString("allowedNetworks", ""));
    }

    void save(Context context) {
        preferences(context).edit()
            .putString("user", user)
            .putString(ENCODED_PASSWORD, encode(password))
            .remove(LEGACY_PASSWORD)
            .putInt("port", port)
            .putString("allowedNetworks", allowedNetworks)
            .apply();
    }

    static void saveAllowedNetworks(Context context, String networks) {
        preferences(context).edit().putString("allowedNetworks", networks).apply();
    }

    static boolean validUser(String user) {
        return user.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,31}");
    }

    static boolean validPort(int port) {
        return port >= 1024 && port <= 65535;
    }

    static File directory(Context context) {
        File directory = new File(context.getFilesDir(), SHARE);
        if (!directory.isDirectory() && !directory.mkdir() && !directory.isDirectory()) {
            throw new IllegalStateException("无法创建共享目录");
        }
        return directory;
    }

    private static String encode(String password) {
        return Base64.encodeToString(password.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("smb", Context.MODE_PRIVATE);
    }
}
