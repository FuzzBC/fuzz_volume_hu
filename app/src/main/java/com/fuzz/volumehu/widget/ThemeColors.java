package com.fuzz.volumehu.widget;

import android.graphics.Color;

/**
 * File:        ThemeColors.java
 * Description: The 24 selectable volume-color themes plus the same
 *              low->mid->high interpolation used by the HTML prototype:
 *              0-12.5 blends low into mid, 12.5-25 blends mid into high.
 *              Theme 0 ("Original") is the neumorphic skin's own default
 *              and is what a fresh install starts on.
 * Author:      FuzzBC
 * Date:        2026-09-01
 */
public final class ThemeColors {

    public static final class Theme {
        public final String name;
        public final int low, mid, high;
        Theme(String name, String low, String mid, String high) {
            this.name = name;
            this.low = Color.parseColor(low);
            this.mid = Color.parseColor(mid);
            this.high = Color.parseColor(high);
        }
    }

    public static final Theme[] THEMES = new Theme[]{
            new Theme("Original",      "#86EFAC", "#D97706", "#DC2626"),
            new Theme("Ember",         "#FDE68A", "#F97316", "#DC2626"),
            new Theme("Ocean",         "#67E8F9", "#0EA5E9", "#1D4ED8"),
            new Theme("Mint",          "#6EE7B7", "#10B981", "#047857"),
            new Theme("Grape",         "#D8B4FE", "#A855F7", "#6D28D9"),
            new Theme("Sunset",        "#FECACA", "#FB7185", "#BE123C"),
            new Theme("Lime",          "#D9F99D", "#84CC16", "#4D7C0F"),
            new Theme("Rose Gold",     "#FBCFE8", "#F472B6", "#DB2777"),
            new Theme("Steel",         "#CBD5E1", "#64748B", "#334155"),
            new Theme("Amber Classic", "#FDE047", "#F59E0B", "#B45309"),
            new Theme("Cyber Lime",    "#BEF264", "#A3E635", "#65A30D"),
            new Theme("Blush",         "#FECDD3", "#FB7185", "#9F1239"),
            new Theme("Arctic",        "#E0F2FE", "#7DD3FC", "#0369A1"),
            new Theme("Coral",         "#FED7AA", "#FB923C", "#C2410C"),
            new Theme("Violet Storm",  "#C4B5FD", "#8B5CF6", "#5B21B6"),
            new Theme("Forest",        "#BBF7D0", "#22C55E", "#14532D"),
            new Theme("Gold",          "#FEF08A", "#EAB308", "#A16207"),
            new Theme("Berry",         "#F9A8D4", "#DB2777", "#831843"),
            new Theme("Slate Blue",    "#93C5FD", "#3B82F6", "#1E40AF"),
            new Theme("Peach",         "#FED7AA", "#FDBA74", "#EA580C"),
            new Theme("Emerald Night", "#6EE7B7", "#059669", "#064E3B"),
            new Theme("Crimson",       "#FCA5A5", "#EF4444", "#7F1D1D"),
            new Theme("Turquoise",     "#99F6E4", "#14B8A6", "#115E59"),
            new Theme("Mono Ash",      "#E5E7EB", "#9CA3AF", "#374151"),
            new Theme("FuZz Signature","#F3F4F6", "#F87171", "#DC2626"),
    };

    private ThemeColors() {}

    /**
     * Interpolates a theme's low/mid/high stops for a bar-scale volume.
     *
     * @param themeIndex Index into {@link #THEMES}, clamped to range.
     * @param volume0to25 Volume already clamped to the widget's own 0-25 bar scale.
     * @returns The interpolated ARGB color for that volume under that theme.
     */
    public static int colorFor(int themeIndex, int volume0to25) {
        Theme t = THEMES[Math.max(0, Math.min(THEMES.length - 1, themeIndex))];
        float frac = Math.max(0f, Math.min(1f, volume0to25 / 25f));
        int from, to;
        float localT;
        if (frac <= 0.5f) { from = t.low; to = t.mid; localT = frac / 0.5f; }
        else { from = t.mid; to = t.high; localT = (frac - 0.5f) / 0.5f; }
        return lerpColor(from, to, localT);
    }

    private static int lerpColor(int from, int to, float t) {
        int a = lerpChannel(Color.alpha(from), Color.alpha(to), t);
        int r = lerpChannel(Color.red(from), Color.red(to), t);
        int g = lerpChannel(Color.green(from), Color.green(to), t);
        int b = lerpChannel(Color.blue(from), Color.blue(to), t);
        return Color.argb(a, r, g, b);
    }

    private static int lerpChannel(int a, int b, float t) {
        return Math.round(a + (b - a) * t);
    }
}
