package com.eritv.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Optional, user-confirmed updater. Never interrupts playback or installs silently. */
public final class AppUpdater {
    private static final String META = "https://raw.githubusercontent.com/euphgd-ctrl/OpenTV-AndroidTV/main/updates/eritv.json";
    private static final int TIMEOUT = 15000;
    private final Activity activity;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean pendingInstall;
    private File apk;

    public AppUpdater(Activity activity) { this.activity = activity; }

    public void check() {
        io.execute(() -> {
            try {
                JSONObject data = new JSONObject(readText(META));
                int latest = data.getInt("versionCode");
                if (latest <= (Build.VERSION.SDK_INT >= 28 ? activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).getLongVersionCode() : activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionCode)) return;
                String url = data.getString("apkUrl");
                if (!url.startsWith("https://github.com/euphgd-ctrl/OpenTV-AndroidTV/releases/download/")) return;
                String name = data.optString("versionName", "new version");
                ui.post(() -> {
                    if (!activity.isFinishing() && !activity.isDestroyed()) {
                        new AlertDialog.Builder(activity)
                            .setTitle("EriTV update available")
                            .setMessage("Version " + name + " is available. Update now? Live TV will continue until installation.")
                            .setPositiveButton("Update", (d,w) -> download(url))
                            .setNegativeButton("Later", null).show();
                    }
                });
            } catch (Exception ignored) { /* Offline or invalid metadata: playback is unaffected. */ }
        });
    }

    private static HttpURLConnection connection(String address) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setConnectTimeout(TIMEOUT);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "EriTV-Updater");
        if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
        return c;
    }

    private static String readText(String address) throws Exception {
        HttpURLConnection c = connection(address);
        try (InputStream in = c.getInputStream()) {
            byte[] bytes = new byte[32768];
            int n = in.read(bytes);
            if (n < 1 || n == bytes.length) throw new Exception("Metadata size invalid");
            return new String(bytes, 0, n, java.nio.charset.StandardCharsets.UTF_8);
        } finally { c.disconnect(); }
    }

    private void download(String url) {
        Toast.makeText(activity, "Downloading EriTV update…", Toast.LENGTH_LONG).show();
        io.execute(() -> {
            HttpURLConnection c = null;
            try {
                c = connection(url);
                File dir = new File(activity.getCacheDir(), "updates");
                if (!dir.exists() && !dir.mkdirs()) throw new Exception("Cannot create update directory");
                File file = new File(dir, "EriTV-update.apk");
                long total = 0;
                try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(file)) {
                    byte[] buf = new byte[32768];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        total += n;
                        if (total > 100_000_000L) throw new Exception("APK too large");
                        out.write(buf, 0, n);
                    }
                }
                if (total < 100_000L) throw new Exception("Incomplete APK");
                apk = file;
                ui.post(this::install);
            } catch (Exception e) {
                ui.post(() -> Toast.makeText(activity, "Update download failed. Try again later.", Toast.LENGTH_LONG).show());
            } finally { if (c != null) c.disconnect(); }
        });
    }

    private void install() {
        if (apk == null || !apk.isFile() || activity.isFinishing()) return;
        if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
            pendingInstall = true;
            new AlertDialog.Builder(activity)
                .setTitle("Allow EriTV updates")
                .setMessage("Allow EriTV to install apps in Android settings, then return here to continue.")
                .setPositiveButton("Settings", (d,w) -> {
                    Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + activity.getPackageName()));
                    activity.startActivity(i);
                }).setNegativeButton("Cancel", (d,w) -> pendingInstall = false).show();
            return;
        }
        pendingInstall = false;
        Uri uri = FileProvider.getUriForFile(activity,
                activity.getPackageName() + ".fileprovider", apk);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try { activity.startActivity(intent); }
        catch (ActivityNotFoundException e) {
            Toast.makeText(activity, "No Android package installer found.", Toast.LENGTH_LONG).show();
        }
    }

    public void onResume() {
        if (pendingInstall && (Build.VERSION.SDK_INT < 26 ||
                activity.getPackageManager().canRequestPackageInstalls())) install();
    }
}
