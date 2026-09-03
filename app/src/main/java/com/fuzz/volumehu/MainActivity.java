package com.fuzz.volumehu;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

/**
 * File:        MainActivity.java
 * Description: Not the widget itself - just the launcher screen that grants
 *              the overlay permission, starts/stops VolumeOverlayService,
 *              offers the battery-optimization exemption, and runs the
 *              GitHub update check. The actual tab/panel live entirely in
 *              the service; this Activity can be closed right after.
 * Author:      FuzzBC
 * Date:        2026-09-01
 */
public class MainActivity extends AppCompatActivity {

    private static final int REQ_NOTIF = 501;

    private TextView statusText;
    private Button grantOverlayBtn;
    private Button toggleServiceBtn;
    private Button batteryOptBtn;
    private Prefs prefs;
    private UpdateInstaller updateInstaller; // lazily-created APK download/install helper
    // Set when this launch is showing a just-happened crash - onResume then
    // skips its usual auto-start-the-overlay so a crash always lands on the
    // plain main screen with the error, never straight back into whatever
    // just crashed. Cleared after being consumed once.
    private boolean justShowedCrash = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TraceLog.step(this, "MainActivity.onCreate start");
        setContentView(R.layout.activity_main);
        TraceLog.step(this, "setContentView done");
        prefs = new Prefs(this);

        // No adb on the target head units - this is how a crash actually
        // gets seen: FuzzVolumeApp wrote it to a file when it happened, shown
        // here on the very next launch so it can be read/screenshotted.
        try {
            String crash = FuzzVolumeApp.takeLastCrash(this);
            if (crash != null) {
                justShowedCrash = true;
                new AlertDialog.Builder(this)
                        .setTitle("FuZz Volume HU crashed last time")
                        .setMessage(crash + "\n\nFull step-by-step trace: \"View trace log\" below, or "
                                + TraceLog.logFile(this).getAbsolutePath())
                        .setCancelable(false)
                        .setPositiveButton("OK", null)
                        .show();
            }
        } catch (Exception e) {
            android.util.Log.w("MainActivity", "showing last crash failed", e);
        }

        TextView versionText = findViewById(R.id.versionText);
        versionText.setText("v" + BuildConfig.VERSION_MAJOR + "."
                + String.format(java.util.Locale.US, "%03d", BuildConfig.VERSION_CODE));

        statusText = findViewById(R.id.statusText);
        grantOverlayBtn = findViewById(R.id.grantOverlayBtn);
        toggleServiceBtn = findViewById(R.id.toggleServiceBtn);
        batteryOptBtn = findViewById(R.id.batteryOptBtn);
        Button checkUpdateBtn = findViewById(R.id.checkUpdateBtn);
        Button viewTraceBtn = findViewById(R.id.viewTraceBtn);
        viewTraceBtn.setOnClickListener(v -> showTraceLog());
        TraceLog.step(this, "views found, listeners about to be wired");

        grantOverlayBtn.setOnClickListener(v -> openOverlaySettings(true));

        toggleServiceBtn.setOnClickListener(v -> {
            try {
                if (prefs.wasOverlayStarted()) {
                    TraceLog.step(this, "toggleServiceBtn: calling VolumeOverlayService.stop()");
                    VolumeOverlayService.stop(this);
                } else {
                    if (!canDrawOverlays()) {
                        Toast.makeText(this, R.string.overlay_perm_needed, Toast.LENGTH_LONG).show();
                        return;
                    }
                    TraceLog.step(this, "toggleServiceBtn: calling VolumeOverlayService.start()");
                    VolumeOverlayService.start(this);
                }
            } catch (Exception e) {
                android.util.Log.e("MainActivity", "toggle overlay failed", e);
                Toast.makeText(this, "Couldn't start the overlay - see logs.", Toast.LENGTH_LONG).show();
            }
            refreshStatusSoon();
        });

        batteryOptBtn.setOnClickListener(v -> {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && pm != null
                    && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName()));
                if (!safeStartActivity(i)) {
                    Toast.makeText(this, "This device doesn't support that screen - nothing to worry about, the overlay still runs fine without it.", Toast.LENGTH_LONG).show();
                }
            } else {
                Toast.makeText(this, "Already exempt.", Toast.LENGTH_SHORT).show();
            }
        });

        checkUpdateBtn.setOnClickListener(v -> checkForUpdate(true));

        // Silent check on every launch; only nags with a dialog if something's newer.
        checkForUpdate(false);

        // Announce anything missing with an actual dialog instead of silently
        // jumping to Settings or just toasting - wrapped defensively, same
        // reasoning as everywhere else here: this must never crash the app.
        // Skipped right after a crash so it isn't stacked on top of that
        // dialog.
        //
        // NOTE: auto-starting the overlay here used to be removed entirely -
        // "white page, then crash" was reported specifically (and only)
        // when permissions were already granted, i.e. exactly this
        // condition. That turned out to be a wrong XML resource namespace
        // in every layout file (see CHANGELOG 1.015), not the auto-start
        // itself - every layout inflation crashed instantly regardless of
        // what triggered it. Fixed and confirmed stable across several
        // releases since, so maybeAutoStartOverlay() below restores it:
        // opening the app now puts up the permanent/ongoing notification
        // (foreground service) right away instead of requiring a manual
        // "Start volume overlay" tap every single time.
        if (!justShowedCrash) {
            try {
                showMissingPermissionsDialog();
            } catch (Exception e) {
                android.util.Log.w("MainActivity", "showMissingPermissionsDialog failed", e);
            }
            try {
                maybeAutoStartOverlay();
            } catch (Exception e) {
                android.util.Log.w("MainActivity", "maybeAutoStartOverlay failed", e);
            }
        }
        TraceLog.step(this, "MainActivity.onCreate end");
    }

    /**
     * Shows the full step-by-step trace log in a scrollable dialog. The same
     * file also sits on external storage - Android/data/com.fuzz.volumehu/
     * files/fuzz_volume_trace.log - readable with any file manager even if
     * the app can't get this far to show it itself.
     */
    private void showTraceLog() {
        String log = TraceLog.readTail(this, 20000);
        if (log == null || log.trim().isEmpty()) {
            Toast.makeText(this, "No trace log yet.", Toast.LENGTH_SHORT).show();
            return;
        }
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        TextView tv = new TextView(this);
        tv.setText(log);
        tv.setTextIsSelectable(true);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setTextSize(10);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        tv.setPadding(pad, pad, pad, pad);
        scroll.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle("Trace log")
                .setView(scroll)
                .setPositiveButton("Close", null)
                .setNeutralButton("Clear", (d, w) -> {
                    try { TraceLog.logFile(this).delete(); } catch (Exception ignored) {}
                })
                .show();
    }

    /**
     * Starts the overlay (its permanent/ongoing notification is what keeps
     * the foreground service - and so the app - alive) the moment this
     * screen opens, but only if the needed permissions are already granted
     * and it isn't running already; never forces a permission prompt from
     * here. A no-op call otherwise, so it's always safe to call unconditionally.
     */
    private void maybeAutoStartOverlay() {
        if (prefs.wasOverlayStarted()) return;
        if (!canDrawOverlays() || !notificationsGranted()) return;
        TraceLog.step(this, "auto-starting overlay on app open");
        VolumeOverlayService.start(this);
        refreshStatusSoon();
    }

    /**
     * VolumeOverlayService.start()/stop() only *request* a state change -
     * the flag refreshStatus() actually reads (Prefs.wasOverlayStarted())
     * doesn't flip until the service's own onCreate()/onDestroy() runs a
     * moment later, on a separate dispatch. Calling refreshStatus() only
     * once, immediately after start()/stop(), reliably shows the OLD
     * state - "running" right after Stop, "stopped" right after Start -
     * which reads as backwards even though neither string is actually
     * wired wrong. This refreshes right away (so the screen doesn't feel
     * unresponsive) and again once the dispatch has almost certainly
     * landed, to correct itself.
     */
    private void refreshStatusSoon() {
        refreshStatus();
        new android.os.Handler(getMainLooper()).postDelayed(() -> {
            try {
                if (!isFinishing() && !isDestroyed()) refreshStatus();
            } catch (Exception ignored) {}
        }, 400);
    }

    private boolean notificationsGranted() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                        == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Builds the list of what's actually missing and, if there's anything,
     * announces it in a dialog before touching Settings or the system
     * notification prompt - so the user always sees *why* before anything
     * happens, rather than a screen just appearing (or a toast that's easy
     * to miss on a head unit's small status bar).
     */
    private boolean showMissingPermissionsDialog() {
        java.util.List<String> missing = new java.util.ArrayList<>();
        if (!canDrawOverlays()) {
            missing.add("Display over other apps - lets the floating volume tab draw on top of everything else");
        }
        if (!notificationsGranted()) {
            missing.add("Notifications - shows the ongoing status notification that keeps the overlay running");
        }
        if (missing.isEmpty()) return false;

        StringBuilder msg = new StringBuilder("FuZz Volume HU needs:\n");
        for (String line : missing) msg.append("\n- ").append(line);

        new AlertDialog.Builder(this)
                .setTitle("Permissions needed")
                .setMessage(msg.toString())
                .setCancelable(true)
                .setPositiveButton("Grant now", (d, w) -> requestNeededPermissions())
                .setNegativeButton("Not now", null)
                .show();
        return true;
    }

    private void requestNeededPermissions() {
        if (!notificationsGranted()) {
            try {
                ActivityCompat.requestPermissions(this, new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
            } catch (Exception e) {
                android.util.Log.w("MainActivity", "POST_NOTIFICATIONS request failed", e);
            }
        }
        if (!canDrawOverlays()) {
            openOverlaySettings(false);
        }
    }

    /**
     * Opens the "display over other apps" screen for this app, trying the
     * package-scoped Intent first and falling back to the bare action (some
     * firmware only supports one form). Never throws - some head units simply
     * don't ship this screen at all, in which case the user is told to enable
     * it manually instead of the app crashing trying to open it.
     *
     * @param manualTap True when triggered by the "Grant permission" button
     *                  (always shows a result toast), false for the silent
     *                  first-launch attempt (only toasts on outright failure).
     */
    private void openOverlaySettings(boolean manualTap) {
        Intent scoped = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        boolean opened = safeStartActivity(scoped);
        if (!opened) {
            opened = safeStartActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
        }
        if (opened) {
            if (manualTap) Toast.makeText(this, R.string.overlay_perm_needed, Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, "Couldn't open that screen automatically on this device. Enable \"Display over other apps\" for FuZz Volume HU from your device's Settings > Apps manually.", Toast.LENGTH_LONG).show();
        }
    }

    /** Starts an Activity, swallowing any failure (unresolvable Intent, missing
     *  screen on this firmware, etc.) instead of letting it crash the app. */
    private boolean safeStartActivity(Intent intent) {
        try {
            startActivity(intent);
            return true;
        } catch (Exception e) {
            android.util.Log.w("MainActivity", "startActivity failed for " + intent.getAction(), e);
            return false;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        TraceLog.step(this, "MainActivity.onResume");
        refreshStatus();
        justShowedCrash = false;
        // Starting the overlay from here (a return to this screen) is never
        // automatic - see maybeAutoStartOverlay() (onCreate only, i.e. an
        // actual app open) for the one exception.
    }

    private boolean canDrawOverlays() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
    }

    private void refreshStatus() {
        boolean overlayOk = canDrawOverlays();
        boolean running = prefs.wasOverlayStarted();
        grantOverlayBtn.setEnabled(!overlayOk);
        toggleServiceBtn.setText(running ? R.string.stop_overlay : R.string.start_overlay);
        statusText.setText((overlayOk
                ? (running ? "Overlay permission granted - widget running" : "Overlay permission granted - widget stopped")
                : "Overlay permission not granted yet")
                + "\nLog: " + TraceLog.logFile(this).getAbsolutePath());
    }

    private void checkForUpdate(boolean showUpToDateToast) {
        UpdateChecker.check(this, new UpdateChecker.Callback() {
            @Override
            public void onUpdateAvailable(String tagName, int versionCode, String apkUrl, String releaseNotes) {
                String displayVersion = BuildConfig.VERSION_MAJOR + "." + String.format(java.util.Locale.US, "%03d", versionCode);
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Update available: " + displayVersion)
                        .setMessage(releaseNotes == null || releaseNotes.trim().isEmpty()
                                ? "A newer version is available on GitHub." : releaseNotes)
                        .setPositiveButton("Update", (d, w) -> startUpdateDownload(apkUrl, displayVersion, tagName))
                        .setNegativeButton("Later", null)
                        .show();
            }

            @Override
            public void onUpToDate() {
                if (showUpToDateToast) Toast.makeText(MainActivity.this, "You're up to date.", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(String message) {
                if (showUpToDateToast) Toast.makeText(MainActivity.this, "Update check failed: " + message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * Kicks off the APK download behind a live progress dialog (percent,
     * size, speed, Cancel) and hands the finished file straight to the
     * system installer - same UpdateInstaller/DownloadManager pattern as
     * the other FuZz apps (FuZz LED, LEDCAR), replacing the old "open a
     * browser to the raw APK URL" flow. On API 26+, installing from a
     * downloaded file requires the user to have granted "install unknown
     * apps" for this app first - if not granted, sends them straight to
     * that settings screen instead of downloading (they can just tap
     * Update again after).
     */
    private void startUpdateDownload(String apkUrl, String displayVersion, String tagName) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(this, "Allow \"install unknown apps\" for FuZz Volume HU, then tap Update again", Toast.LENGTH_LONG).show();
            safeStartActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            return;
        }

        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setIndeterminate(true);
        root.addView(bar);
        TextView status = new TextView(this);
        status.setText("Starting download...");
        status.setPadding(0, pad / 2, 0, 0);
        root.addView(status);

        if (updateInstaller == null) updateInstaller = new UpdateInstaller(this);
        AlertDialog progressDialog = new AlertDialog.Builder(this)
                .setTitle("Downloading " + displayVersion)
                .setView(root)
                .setCancelable(false)
                .setNegativeButton("Cancel", (d, w) -> {
                    updateInstaller.cancel();
                    Toast.makeText(this, "Update cancelled", Toast.LENGTH_SHORT).show();
                })
                .show();

        updateInstaller.download(apkUrl, tagName, new UpdateInstaller.ProgressListener() {
            @Override
            public void onProgress(int percent, long downloaded, long total, double speedBps) {
                if (percent < 0) {
                    bar.setIndeterminate(true);
                    status.setText(humanBytes(downloaded) + " downloaded...");
                } else {
                    bar.setIndeterminate(false);
                    bar.setProgress(percent);
                    status.setText(percent + "%  -  " + humanBytes(downloaded) + " / " + humanBytes(total)
                            + "  (" + humanBytes((long) speedBps) + "/s)");
                }
            }

            @Override
            public void onComplete() {
                progressDialog.dismiss();
            }

            @Override
            public void onFailed(String reason) {
                progressDialog.dismiss();
                Toast.makeText(MainActivity.this, "Update download failed: " + reason, Toast.LENGTH_LONG).show();
            }
        });
    }

    private String humanBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0);
        return String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
