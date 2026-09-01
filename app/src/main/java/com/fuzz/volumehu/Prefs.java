package com.fuzz.volumehu;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * File:        Prefs.java
 * Description: Everything the overlay remembers between launches - which
 *              edge the tab is docked to, how far down that edge, and which
 *              of the 24 color themes is active. The volume itself is never
 *              stored here; it's always read live from AudioManager (see
 *              VolumeOverlayService), so it can't drift from the real
 *              system value.
 * Author:      FuzzBC
 * Date:        2026-09-01
 */
public class Prefs {

    private static final String FILE = "fuzz_volume_hu_prefs";
    private static final String KEY_SIDE = "side";           // "left" | "right"
    private static final String KEY_VPOS = "vpos";            // 10f..90f, % of screen height
    private static final String KEY_THEME = "theme";          // index into ThemeColors.THEMES
    private static final String KEY_OVERLAY_STARTED = "overlay_started"; // was the overlay running when the app was last used

    private final SharedPreferences sp;

    public Prefs(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public String getSide() { return sp.getString(KEY_SIDE, "right"); }
    public void setSide(String side) { sp.edit().putString(KEY_SIDE, side).apply(); }

    public float getVpos() { return sp.getFloat(KEY_VPOS, 50f); }
    public void setVpos(float vpos) { sp.edit().putFloat(KEY_VPOS, vpos).apply(); }

    public int getTheme() { return sp.getInt(KEY_THEME, 0); }
    public void setTheme(int theme) { sp.edit().putInt(KEY_THEME, theme).apply(); }

    public boolean wasOverlayStarted() { return sp.getBoolean(KEY_OVERLAY_STARTED, false); }
    public void setOverlayStarted(boolean started) { sp.edit().putBoolean(KEY_OVERLAY_STARTED, started).apply(); }
}
