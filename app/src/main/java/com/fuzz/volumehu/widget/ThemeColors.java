package com.fuzz.volumehu.widget;

import android.graphics.Color;

/**
 * File:        ThemeColors.java
 * Description: The 60 selectable volume-color themes plus the same
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

            // 36 more, bringing the picker to 60 total.
            new Theme("Indigo Sky",    "#C7D2FE", "#6366F1", "#312E81"),
            new Theme("Sapphire",      "#93C5FD", "#2563EB", "#1E3A8A"),
            new Theme("Denim",         "#BFDBFE", "#3B82F6", "#1E40AF"),
            new Theme("Cobalt",        "#7DD3FC", "#0284C7", "#0C4A6E"),
            new Theme("Aqua Breeze",   "#A5F3FC", "#06B6D4", "#164E63"),
            new Theme("Teal Deep",     "#5EEAD4", "#0D9488", "#134E4A"),
            new Theme("Jade",          "#99F6E4", "#059669", "#064E3B"),
            new Theme("Pine",          "#86EFAC", "#16A34A", "#052E16"),
            new Theme("Moss",          "#BEF264", "#4D7C0F", "#1A2E05"),
            new Theme("Olive Grove",   "#D9F99D", "#65A30D", "#365314"),
            new Theme("Mustard Gold",  "#FEF3C7", "#D97706", "#78350F"),
            new Theme("Marigold",      "#FDE68A", "#EA580C", "#7C2D12"),
            new Theme("Rust",          "#FDBA74", "#C2410C", "#431407"),
            new Theme("Copper",        "#FED7AA", "#B45309", "#78350F"),
            new Theme("Wine",          "#FCA5A5", "#B91C1C", "#450A0A"),
            new Theme("Rosewood",      "#FECDD3", "#E11D48", "#881337"),
            new Theme("Magenta Pop",   "#FBCFE8", "#DB2777", "#500724"),
            new Theme("Fuchsia Bloom", "#F5D0FE", "#D946EF", "#86198F"),
            new Theme("Plum",          "#F3E8FF", "#C026D3", "#581C87"),
            new Theme("Amethyst",      "#E9D5FF", "#9333EA", "#4C1D95"),
            new Theme("Violet Dream",  "#DDD6FE", "#7C3AED", "#4C1D95"),
            new Theme("Lavender",      "#E0E7FF", "#818CF8", "#3730A3"),
            new Theme("Periwinkle",    "#C7D2FE", "#4F46E5", "#312E81"),
            new Theme("Sky Blue",      "#7DD3FC", "#0EA5E9", "#075985"),
            new Theme("Ice",           "#E0F2FE", "#38BDF8", "#0C4A6E"),
            new Theme("Frost",         "#F0F9FF", "#7DD3FC", "#075985"),
            new Theme("Charcoal",      "#D1D5DB", "#4B5563", "#111827"),
            new Theme("Slate",         "#CBD5E1", "#475569", "#0F172A"),
            new Theme("Ash Grey",      "#E5E7EB", "#6B7280", "#1F2937"),
            new Theme("Sandstone",     "#FDE68A", "#CA8A04", "#713F12"),
            new Theme("Desert Sun",    "#FCD34D", "#D97706", "#854D0E"),
            new Theme("Terracotta",    "#FDBA74", "#C2410C", "#7C2D12"),
            new Theme("Blush Pink",    "#FBCFE8", "#F472B6", "#9D174D"),
            new Theme("Coral Reef",    "#FED7AA", "#FB7185", "#9F1239"),
            new Theme("Midnight Blue", "#93C5FD", "#1D4ED8", "#172554"),
            new Theme("Storm Grey",    "#CBD5E1", "#64748B", "#1E293B"),
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
