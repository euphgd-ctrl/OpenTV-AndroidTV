package com.eritv.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
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
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Optional, user-confirmed updater. Never interrupts playback or installs silently. */
public final class AppUpdater {
    private static final String META = "https://github.com/euphgd-ctrl/OpenTV-AndroidTV/releases/download/eritv-current/eritv-update.json";
    private static final String APK_URL = "https://github.com/euphgd-ctrl/OpenTV-AndroidTV/releases/download/eritv-current/EriTV.apk";
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
                String sha = data.getString("sha256").toLowerCase(Locale.US);
                if (!APK_URL.equals(url) || !sha.matches("[0-9a-f]{64}")) return;
                String name = data.optString("versionName", "new version");
                ui.post(() -> {
                    if (!activity.isFinishing() && !activity.isDestroyed()) {
                        new AlertDialog.Builder(activity)
                            .setTitle("EriTV update available")
                            .setMessage("Version " + name + " is available. Update now? Live TV will continue until installation.")
                            .setPositiveButton("Update", (d,w) -> download(url, latest, sha))
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

    private void download(String url, int expectedVersion, String expectedSha) {
        Toast.makeText(activity, "Downloading EriTV update…", Toast.LENGTH_LONG).show();
        io.execute(() -> {
            HttpURLConnection c = null;
            File file = null;
            try {
                c = connection(url);
                File dir = new File(activity.getCacheDir(), "updates");
                if (!dir.exists() && !dir.mkdirs()) throw new Exception("Cannot create update directory");
                file = new File(dir, "EriTV-update.apk");
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                long total = 0;
                try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(file)) {
                    byte[] buf = new byte[32768];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        total += n;
                        if (total > 100_000_000L) throw new Exception("APK too large");
                        digest.update(buf, 0, n);
                        out.write(buf, 0, n);
                    }
                }
                if (total < 100_000L || !hex(digest.digest()).equals(expectedSha))
                    throw new Exception("APK download integrity check failed");
                verifyPackageAndSigning(file, expectedVersion);
                apk = file;
                ui.post(this::install);
            } catch (SecurityException e) {
                if (file != null) file.delete();
                ui.post(() -> {
                    if (!activity.isFinishing() && !activity.isDestroyed())
                        new AlertDialog.Builder(activity)
                            .setTitle("One-time reinstall required")
                            .setMessage("Your existing EriTV has an older signing certificate. Android cannot update it directly. Uninstall EriTV, then open Downloader and enter code 6861670 to install the new version. Future updates can be installed inside the app.")
                            .setPositiveButton("OK", null).show();
                });
            } catch (Exception e) {
                if (file != null) file.delete();
                ui.post(() -> Toast.makeText(activity, "Update failed. Please try again later.", Toast.LENGTH_LONG).show());
            } finally { if (c != null) c.disconnect(); }
        });
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) result.append(String.format(Locale.US, "%02x", b & 255));
        return result.toString();
    }

    @SuppressWarnings("deprecation")
    private void verifyPackageAndSigning(File file, int expectedVersion) throws Exception {
        PackageManager pm = activity.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28
                ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo incoming = pm.getPackageArchiveInfo(file.getAbsolutePath(), flags);
        PackageInfo existing = pm.getPackageInfo(activity.getPackageName(), flags);
        if (incoming == null || !activity.getPackageName().equals(incoming.packageName))
            throw new Exception("Downloaded APK has the wrong package");
        long v = Build.VERSION.SDK_INT >= 28
                ? incoming.getLongVersionCode() : incoming.versionCode;
        if (v != expectedVersion) throw new Exception("Downloaded APK has an unexpected version");
        Signature[] newSigners = Build.VERSION.SDK_INT >= 28
                ? (incoming.signingInfo == null ? null : incoming.signingInfo.getApkContentsSigners())
                : incoming.signatures;
        Signature[] oldSigners = Build.VERSION.SDK_INT >= 28
                ? (existing.signingInfo == null ? null : existing.signingInfo.getApkContentsSigners())
                : existing.signatures;
        if (newSigners == null || oldSigners == null
                || newSigners.length != 1 || oldSigners.length != 1
                || !Arrays.equals(newSigners[0].toByteArray(), oldSigners[0].toByteArray()))
            throw new SecurityException("Installed APK signed with a different certificate");
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
