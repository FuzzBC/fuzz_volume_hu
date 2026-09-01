package com.fuzz.volumehu;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;

/**
 * File:        BootReceiver.java
 * Description: Relaunches the overlay after the head unit reboots (every
 *              ignition cycle on most car hardware), but only if overlay
 *              permission is already granted and the widget was actually
 *              running before the reboot - a user who explicitly stopped it
 *              shouldn't have it come back on its own.
 * Author:      FuzzBC
 * Date:        2026-09-01
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        boolean overlayOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context);
        Prefs prefs = new Prefs(context);
        if (overlayOk && prefs.wasOverlayStarted()) {
            VolumeOverlayService.start(context);
        }
    }
}
