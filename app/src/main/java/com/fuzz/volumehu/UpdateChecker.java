package com.fuzz.volumehu;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * File:        UpdateChecker.java
 * Description: Checks the public FuzzBC/fuzz_volume_hu GitHub repo for a
 *              newer release than the one installed, and hands back the APK
 *              asset URL - same GitHub-releases pattern as the other FuZz
 *              apps' UpdateChecker (fuzzapp_rmk), just repointed here.
 *              Release tags look like "V<versionCode>" - the numeric
 *              versionCode is the run of digits after the last '.'.
 * Author:      FuzzBC
 * Date:        2026-09-01
 */
public class UpdateChecker {

    private static final String OWNER = "FuzzBC";
    private static final String REPO = "fuzz_volume_hu";
    private static final String API_URL =
            "https://api.github.com/repos/" + OWNER + "/" + REPO + "/releases/latest";

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public interface Callback {
        void onUpdateAvailable(String tagName, int versionCode, String apkUrl, String releaseNotes);
        void onUpToDate();
        void onError(String message);
    }

    public static void check(Context ctx, Callback cb) {
        final Context appCtx = ctx.getApplicationContext();
        IO.execute(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(API_URL).openConnection();
                conn.setRequestProperty("Accept", "application/vnd.github+json");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);

                int code = conn.getResponseCode();
                if (code != 200) { postError(cb, "Update check failed (HTTP " + code + ")"); return; }

                String body = readAll(conn.getInputStream());
                JSONObject json = new JSONObject(body);
                String tagName = json.optString("tag_name", "");
                int remoteCode = parseVersionCode(tagName);
                int localCode = getLocalVersionCode(appCtx);

                if (remoteCode <= 0 || remoteCode <= localCode) { postUpToDate(cb); return; }

                String apkUrl = findApkUrl(json.optJSONArray("assets"));
                if (apkUrl == null) { postError(cb, "Release " + tagName + " has no APK asset"); return; }

                String notes = json.optString("body", "");
                postAvailable(cb, tagName, remoteCode, apkUrl, notes);
            } catch (Exception e) {
                Log.w("UpdateChecker", "check failed", e);
                postError(cb, e.getMessage());
            } catch (Throwable t) {
                Log.e("UpdateChecker", "check failed with Throwable", t);
                postError(cb, String.valueOf(t.getMessage()));
            }
        });
    }

    @SuppressWarnings("deprecation")
    private static int getLocalVersionCode(Context appCtx) {
        try {
            PackageInfo info = appCtx.getPackageManager().getPackageInfo(appCtx.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? (int) info.getLongVersionCode()
                    : info.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return Integer.MAX_VALUE;
        }
    }

    private static String findApkUrl(JSONArray assets) {
        if (assets == null) return null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.optJSONObject(i);
            if (a == null) continue;
            String name = a.optString("name", "");
            if (name.toLowerCase(java.util.Locale.ROOT).endsWith(".apk")) {
                return a.optString("browser_download_url", null);
            }
        }
        return null;
    }

    private static int parseVersionCode(String tag) {
        int lastDot = tag.lastIndexOf('.');
        String segment = (lastDot >= 0 && lastDot < tag.length() - 1) ? tag.substring(lastDot + 1) : tag;
        StringBuilder digits = new StringBuilder();
        boolean started = false;
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (Character.isDigit(c)) { digits.append(c); started = true; }
            else if (started) break;
        }
        if (digits.length() == 0) return -1;
        try { return Integer.parseInt(digits.toString()); } catch (NumberFormatException e) { return -1; }
    }

    private static String readAll(InputStream is) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    private static void postAvailable(Callback cb, String tag, int code, String url, String notes) {
        MAIN.post(() -> cb.onUpdateAvailable(tag, code, url, notes));
    }
    private static void postUpToDate(Callback cb) { MAIN.post(cb::onUpToDate); }
    private static void postError(Callback cb, String msg) { MAIN.post(() -> cb.onError(msg)); }
}
