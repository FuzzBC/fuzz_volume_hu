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
    private Prefs prefs;
    // Set when this launch is showing a just-happened crash - onResume then
    // skips its usual auto-start-the-overlay so a crash always lands on the
    // plain main screen with the error, never straight back into whatever
    // just crashed. Cleared after being consumed once.
    private boolean justShowedCrash = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
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
                        .setMessage(crash)
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

        grantOverlayBtn.setOnClickListener(v -> openOverlaySettings(true));

        toggleServiceBtn.setOnClickListener(v -> {
            try {
                if (prefs.wasOverlayStarted()) {
                    VolumeOverlayService.stop(this);
                } else {
                    if (!canDrawOverlays()) {
                        Toast.makeText(this, R.string.overlay_perm_needed, Toast.LENGTH_LONG).show();
                        return;
                    }
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
        // dialog - it'll ask again next launch if still relevant.
        if (!justShowedCrash) {
            try {
                showMissingPermissionsDialog();
            } catch (Exception e) {
                android.util.Log.w("MainActivity", "showMissingPermissionsDialog failed", e);
            }
        }
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
    private void showMissingPermissionsDialog() {
        java.util.List<String> missing = new java.util.ArrayList<>();
        if (!canDrawOverlays()) {
            missing.add("Display over other apps - lets the floating volume tab draw on top of everything else");
        }
        if (!notificationsGranted()) {
            missing.add("Notifications - shows the ongoing status notification that keeps the overlay running");
        }
        if (missing.isEmpty()) return;

        StringBuilder msg = new StringBuilder("FuZz Volume HU needs:\n");
        for (String line : missing) msg.append("\n- ").append(line);

        new AlertDialog.Builder(this)
                .setTitle("Permissions needed")
                .setMessage(msg.toString())
                .setCancelable(true)
                .setPositiveButton("Grant now", (d, w) -> requestNeededPermissions())
                .setNegativeButton("Not now", null)
                .show();
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
        refreshStatus();
        justShowedCrash = false;
        // Starting the overlay is a deliberate "Start volume overlay" tap
        // now, not something that happens automatically on every launch -
        // that auto-start was itself a repeated, hard-to-diagnose crash
        // trigger. Opening this screen is always just this screen.
    }

    private boolean canDrawOverlays() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
    }

    private void refreshStatus() {
        boolean overlayOk = canDrawOverlays();
        boolean running = prefs.wasOverlayStarted();
        grantOverlayBtn.setEnabled(!overlayOk);
        toggleServiceBtn.setText(running ? R.string.stop_overlay : R.string.start_overlay);
        statusText.setText(overlayOk
                ? (running ? "Overlay permission granted - widget running" : "Overlay permission granted - widget stopped")
                : "Overlay permission not granted yet");
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
