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

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = new Prefs(this);

        statusText = findViewById(R.id.statusText);
        grantOverlayBtn = findViewById(R.id.grantOverlayBtn);
        toggleServiceBtn = findViewById(R.id.toggleServiceBtn);
        batteryOptBtn = findViewById(R.id.batteryOptBtn);
        Button checkUpdateBtn = findViewById(R.id.checkUpdateBtn);

        grantOverlayBtn.setOnClickListener(v -> openOverlaySettings(true));

        toggleServiceBtn.setOnClickListener(v -> {
            if (prefs.wasOverlayStarted()) {
                VolumeOverlayService.stop(this);
            } else {
                if (!canDrawOverlays()) {
                    Toast.makeText(this, R.string.overlay_perm_needed, Toast.LENGTH_LONG).show();
                    return;
                }
                VolumeOverlayService.start(this);
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

        // SYSTEM_ALERT_WINDOW has no system Allow/Deny popup - the only way to
        // grant it is the Settings screen below, so a fresh install is taken
        // there automatically instead of waiting for someone to notice the
        // "Grant permission" button. POST_NOTIFICATIONS (13+) does have a real
        // popup, requested here too so the ongoing notification actually shows.
        // Everything here is wrapped defensively: some head-unit firmware ships
        // without the usual Settings screens for these, and an unresolvable
        // Intent must never be allowed to crash the app on launch.
        try {
            requestNeededPermissions();
        } catch (Exception e) {
            android.util.Log.w("MainActivity", "requestNeededPermissions failed", e);
        }
    }

    private void requestNeededPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
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
        // Opening the app is also the simplest way to (re)launch the overlay
        // if it isn't running and permission is already granted.
        if (canDrawOverlays() && !prefs.wasOverlayStarted()) {
            VolumeOverlayService.start(this);
            refreshStatus();
        }
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
