package com.fuzz.volumehu;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Button;
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
    private Button storageAccessBtn;
    private Prefs prefs;
    // Set when this launch is showing a just-happened crash - onResume then
    // skips its usual auto-start-the-overlay so a crash always lands on the
    // plain main screen with the error, never straight back into whatever
    // just crashed. Cleared after being consumed once.
    private boolean justShowedCrash = false;
    // Set right before sending the user to the "all files access" screen
    // during first-run setup - onResume then knows this particular return
    // means "storage step just finished", and auto-starts the overlay once
    // (the only case that still auto-starts it - see onResume).
    private boolean awaitingStorageSetupReturn = false;

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

        statusText = findViewById(R.id.statusText);
        grantOverlayBtn = findViewById(R.id.grantOverlayBtn);
        toggleServiceBtn = findViewById(R.id.toggleServiceBtn);
        batteryOptBtn = findViewById(R.id.batteryOptBtn);
        Button checkUpdateBtn = findViewById(R.id.checkUpdateBtn);
        Button viewTraceBtn = findViewById(R.id.viewTraceBtn);
        viewTraceBtn.setOnClickListener(v -> showTraceLog());
        storageAccessBtn = findViewById(R.id.storageAccessBtn);
        storageAccessBtn.setOnClickListener(v -> requestMainStorageAccess());
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
            refreshStatus();
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
        // dialog - it'll ask again next launch if still relevant. Only one
        // dialog gets shown per launch: the storage-setup prompt only comes
        // up once core permissions are already sorted, so they never stack.
        if (!justShowedCrash) {
            try {
                boolean showedPermissionsDialog = showMissingPermissionsDialog();
                if (!showedPermissionsDialog) maybePromptStorageSetup();
            } catch (Exception e) {
                android.util.Log.w("MainActivity", "permission/setup dialog failed", e);
            }
        }
        TraceLog.step(this, "MainActivity.onCreate end");
    }

    /**
     * First-run only: before ever starting the overlay for the first time,
     * offers to set up the easy-to-find log location, then auto-starts the
     * overlay once that's resolved (granted or skipped) - see onResume.
     * Never asked again after the first time either way.
     */
    private void maybePromptStorageSetup() {
        if (prefs.isStorageSetupDone()) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || TraceLog.isOnMainStorage(this)) {
            prefs.setStorageSetupDone(true);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("One more thing first")
                .setMessage("Before starting the volume overlay for the first time, it's worth pointing the trace log at an easy-to-find storage location - that way, if starting it ever goes wrong, the log is simple to grab.")
                .setCancelable(true)
                .setPositiveButton("Set up now", (d, w) -> {
                    awaitingStorageSetupReturn = true;
                    requestMainStorageAccess();
                })
                .setNegativeButton("Skip", (d, w) -> prefs.setStorageSetupDone(true))
                .show();
    }

    /**
     * Shows the full step-by-step trace log in a scrollable dialog. The same
     * file also sits on external storage - Android/data/com.fuzz.volumehu/
     * files/fuzz_volume_trace.log - readable with any file manager even if
     * the app can't get this far to show it itself.
     */
    private void showTraceLog() {
        String log = TraceLog.readAll(this);
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

    /**
     * Opens the "all files access" screen so TraceLog can write to the root
     * of shared storage (a plain fuzz_volume_trace.log any file manager can
     * see, no per-app folder or extra permission needed to browse to it)
     * instead of the app's own Android/data folder. Entirely optional - the
     * trace log works either way - so this is a deliberate button tap, never
     * announced or requested automatically.
     */
    private void requestMainStorageAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Toast.makeText(this, "Not needed on this Android version - the log already writes to a plain shared-storage location.", Toast.LENGTH_LONG).show();
            return;
        }
        if (TraceLog.isOnMainStorage(this)) {
            Toast.makeText(this, "Already granted - log is at " + TraceLog.logFile(this).getAbsolutePath(), Toast.LENGTH_LONG).show();
            return;
        }
        Intent scoped = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        boolean opened = safeStartActivity(scoped);
        if (!opened) {
            opened = safeStartActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
        }
        if (opened) {
            Toast.makeText(this, "Turn on \"Allow access to manage all files\" for FuZz Volume HU, then come back.", Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, "This device doesn't support that screen - the log stays in its current location, which still works fine.", Toast.LENGTH_LONG).show();
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
        TraceLog.step(this, "MainActivity.onResume, awaitingStorageSetupReturn=" + awaitingStorageSetupReturn);
        refreshStatus();
        justShowedCrash = false;

        if (awaitingStorageSetupReturn) {
            // The one deliberate exception to "starting the overlay is
            // always a manual tap": returning from the first-run storage
            // setup screen is the signal that onboarding just finished, so
            // this is the single moment it's started automatically.
            awaitingStorageSetupReturn = false;
            prefs.setStorageSetupDone(true);
            refreshStatus();
            try {
                if (canDrawOverlays() && !prefs.wasOverlayStarted()) {
                    TraceLog.step(this, "auto-starting overlay after first-run storage setup");
                    VolumeOverlayService.start(this);
                    refreshStatus();
                }
            } catch (Exception e) {
                android.util.Log.e("MainActivity", "auto-start after storage setup failed", e);
                TraceLog.error(this, "auto-start after storage setup failed", e);
            }
        }
        // Otherwise, starting the overlay is a deliberate "Start volume
        // overlay" tap - that auto-start was itself a repeated, hard-to-
        // diagnose crash trigger. Opening this screen is always just this
        // screen, except for the one case handled above.
    }

    private boolean canDrawOverlays() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
    }

    private void refreshStatus() {
        boolean overlayOk = canDrawOverlays();
        boolean running = prefs.wasOverlayStarted();
        grantOverlayBtn.setEnabled(!overlayOk);
        toggleServiceBtn.setText(running ? R.string.stop_overlay : R.string.start_overlay);
        boolean onMainStorage = TraceLog.isOnMainStorage(this);
        storageAccessBtn.setEnabled(!onMainStorage);
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
                        .setPositiveButton("Download", (d, w) -> {
                            if (!safeStartActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(apkUrl)))) {
                                Toast.makeText(MainActivity.this, "Couldn't open a browser - grab " + tagName + " from GitHub manually.", Toast.LENGTH_LONG).show();
                            }
                        })
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
}
