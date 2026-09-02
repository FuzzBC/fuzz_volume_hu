package com.fuzz.volumehu;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * File:        Prefs.java
 * Description: Everything the overlay remembers between launches - which
 *              edge the tab is docked to, how far down that edge, which of
 *              the 24 color themes is active, whether that theme is dynamic
 *              (color follows volume) or a flat merge color, the popup/panel
 *              size sliders from the Size tab, the volume tiers from the
 *              Conf tab, and the settings popup's own dragged position. The
 *              volume itself is never stored here; it's always read live
 *              from AudioManager (see VolumeOverlayService), so it can't
 *              drift from the real system value.
 *
 *              Every setter here uses commit() (synchronous, writes to
 *              disk before returning), not the usual apply() (async,
 *              fire-and-forget) - this app's target head units are known
 *              to kill the process aggressively (see CHANGELOG history),
 *              and an in-flight apply() isn't guaranteed to survive that.
 *              Safe because every setter here is already called at most
 *              once per discrete user action (a drag's release, a tap),
 *              never on every intermediate tick of a drag - see
 *              VolumeOverlayService.onSeek()/persistSizeAndConfPrefsNow().
 * Author:      FuzzBC
 * Date:        2026-09-01
 */
public class Prefs {

    private static final String FILE = "fuzz_volume_hu_prefs";
    private static final String KEY_SIDE = "side";           // "left" | "right"
    private static final String KEY_VPOS = "vpos";            // 10f..90f, % of screen height
    private static final String KEY_THEME = "theme";          // index into ThemeColors.THEMES
    private static final String KEY_OVERLAY_STARTED = "overlay_started"; // was the overlay running when the app was last used
    private static final String KEY_STORAGE_SETUP_DONE = "storage_setup_done"; // asked about (or skipped) the main-storage log location once already

    // Theme tab
    private static final String KEY_DYNAMIC_COLOR = "dynamic_color"; // true: color follows volume (theme color -> red at max); false: flat theme color everywhere ("merge")

    // Size tab (all dp)
    private static final String KEY_BUBBLE_WIDTH = "bubble_width_dp"; // floating bubble's own size - height/icon scale with it
    private static final String KEY_PANEL_WIDTH = "panel_width_dp";
    private static final String KEY_PANEL_BAR_HEIGHT = "panel_bar_height_dp";

    // Conf tab
    private static final String KEY_MAX_VOLUME_SUPPORTED = "max_volume_supported"; // top of the EQ bar's scale
    private static final String KEY_WIDGET_MAX = "widget_max";                     // "limited to" - this widget's own write ceiling
    private static final String KEY_DRAG_CAP = "drag_cap";                         // "when go slowly" - direct-drag ceiling

    // Settings popup's own dragged position (-1 = never moved, center it)
    private static final String KEY_POPUP_X = "popup_x";
    private static final String KEY_POPUP_Y = "popup_y";

    public static final int DEFAULT_BUBBLE_WIDTH_DP = 52;
    public static final int DEFAULT_PANEL_WIDTH_DP = 150;
    public static final int DEFAULT_PANEL_BAR_HEIGHT_DP = 150;
    public static final int DEFAULT_MAX_VOLUME_SUPPORTED = 40;
    public static final int DEFAULT_WIDGET_MAX = 25;
    public static final int DEFAULT_DRAG_CAP = 20;

    private final SharedPreferences sp;

    public Prefs(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public String getSide() { return sp.getString(KEY_SIDE, "right"); }
    public void setSide(String side) { sp.edit().putString(KEY_SIDE, side).commit(); }

    public float getVpos() { return sp.getFloat(KEY_VPOS, 50f); }
    public void setVpos(float vpos) { sp.edit().putFloat(KEY_VPOS, vpos).commit(); }

    public int getTheme() { return sp.getInt(KEY_THEME, 0); }
    public void setTheme(int theme) { sp.edit().putInt(KEY_THEME, theme).commit(); }

    public boolean wasOverlayStarted() { return sp.getBoolean(KEY_OVERLAY_STARTED, false); }
    public void setOverlayStarted(boolean started) { sp.edit().putBoolean(KEY_OVERLAY_STARTED, started).commit(); }

    public boolean isStorageSetupDone() { return sp.getBoolean(KEY_STORAGE_SETUP_DONE, false); }
    public void setStorageSetupDone(boolean done) { sp.edit().putBoolean(KEY_STORAGE_SETUP_DONE, done).commit(); }

    public boolean isDynamicColor() { return sp.getBoolean(KEY_DYNAMIC_COLOR, true); }
    public void setDynamicColor(boolean dynamic) { sp.edit().putBoolean(KEY_DYNAMIC_COLOR, dynamic).commit(); }

    public int getBubbleWidthDp() { return sp.getInt(KEY_BUBBLE_WIDTH, DEFAULT_BUBBLE_WIDTH_DP); }
    public void setBubbleWidthDp(int dp) { sp.edit().putInt(KEY_BUBBLE_WIDTH, dp).commit(); }

    public int getPanelWidthDp() { return sp.getInt(KEY_PANEL_WIDTH, DEFAULT_PANEL_WIDTH_DP); }
    public void setPanelWidthDp(int dp) { sp.edit().putInt(KEY_PANEL_WIDTH, dp).commit(); }

    public int getPanelBarHeightDp() { return sp.getInt(KEY_PANEL_BAR_HEIGHT, DEFAULT_PANEL_BAR_HEIGHT_DP); }
    public void setPanelBarHeightDp(int dp) { sp.edit().putInt(KEY_PANEL_BAR_HEIGHT, dp).commit(); }

    public int getMaxVolumeSupported() { return sp.getInt(KEY_MAX_VOLUME_SUPPORTED, DEFAULT_MAX_VOLUME_SUPPORTED); }
    public void setMaxVolumeSupported(int v) { sp.edit().putInt(KEY_MAX_VOLUME_SUPPORTED, v).commit(); }

    public int getWidgetMax() { return sp.getInt(KEY_WIDGET_MAX, DEFAULT_WIDGET_MAX); }
    public void setWidgetMax(int v) { sp.edit().putInt(KEY_WIDGET_MAX, v).commit(); }

    public int getDragCap() { return sp.getInt(KEY_DRAG_CAP, DEFAULT_DRAG_CAP); }
    public void setDragCap(int v) { sp.edit().putInt(KEY_DRAG_CAP, v).commit(); }

    public int getPopupX() { return sp.getInt(KEY_POPUP_X, -1); }
    public int getPopupY() { return sp.getInt(KEY_POPUP_Y, -1); }
    public void setPopupPos(int x, int y) { sp.edit().putInt(KEY_POPUP_X, x).putInt(KEY_POPUP_Y, y).commit(); }
}
