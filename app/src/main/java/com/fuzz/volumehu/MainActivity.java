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

        grantOverlayBtn.setOnClickListener(v -> {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        });

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
                startActivity(i);
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
        requestNeededPermissions();
    }

    private void requestNeededPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
        if (!canDrawOverlays()) {
            Toast.makeText(this, R.string.overlay_perm_needed, Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
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
                ? (running ? "Overlay permission granted · widget running" : "Overlay permission granted · widget stopped")
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
                        .setPositiveButton("Download", (d, w) ->
                                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(apkUrl))))
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
