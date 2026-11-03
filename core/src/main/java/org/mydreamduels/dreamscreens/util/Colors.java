package org.mydreamduels.dreamscreens.util;

import org.bukkit.Color;

import java.util.Locale;

public final class Colors {

    private Colors() {
    }

    /** "#RRGGBB" or "#AARRGGBB" -> Bukkit Color (alpha kept). Null if invalid. */
    public static Color argb(String text) {
        if (text == null) return null;
        String t = text.trim();
        if (t.startsWith("#")) t = t.substring(1);
        try {
            if (t.length() == 6) return Color.fromARGB(255, Integer.parseInt(t.substring(0, 2), 16),
                    Integer.parseInt(t.substring(2, 4), 16), Integer.parseInt(t.substring(4, 6), 16));
            if (t.length() == 8) return Color.fromARGB((int) Long.parseLong(t, 16));
        } catch (NumberFormatException ignored) {
        }
        return null;
    }

    /** Colour names people actually type (chosen to look good on maps). */
    private static final java.util.Map<String, Integer> NAMES = java.util.Map.ofEntries(
            java.util.Map.entry("black", 0x0B0D12), java.util.Map.entry("white", 0xFFFFFF), java.util.Map.entry("gray", 0x6B7280),
            java.util.Map.entry("grey", 0x6B7280), java.util.Map.entry("dark", 0x171B24), java.util.Map.entry("darkgray", 0x374151),
            java.util.Map.entry("dark_gray", 0x374151), java.util.Map.entry("lightgray", 0xD1D5DB), java.util.Map.entry("light_gray", 0xD1D5DB),
            java.util.Map.entry("red", 0xEF4444), java.util.Map.entry("darkred", 0x991B1B), java.util.Map.entry("dark_red", 0x991B1B),
            java.util.Map.entry("orange", 0xF97316), java.util.Map.entry("gold", 0xF59E0B), java.util.Map.entry("yellow", 0xFACC15),
            java.util.Map.entry("lime", 0x84CC16), java.util.Map.entry("green", 0x22C55E), java.util.Map.entry("darkgreen", 0x166534),
            java.util.Map.entry("dark_green", 0x166534), java.util.Map.entry("aqua", 0x22D3EE), java.util.Map.entry("cyan", 0x22D3EE),
            java.util.Map.entry("teal", 0x14B8A6), java.util.Map.entry("blue", 0x3B82F6), java.util.Map.entry("darkblue", 0x1E3A8A),
            java.util.Map.entry("dark_blue", 0x1E3A8A), java.util.Map.entry("navy", 0x1E3A8A), java.util.Map.entry("purple", 0xA78BFA),
            java.util.Map.entry("violet", 0x8B5CF6), java.util.Map.entry("magenta", 0xD946EF), java.util.Map.entry("pink", 0xF27FA5),
            java.util.Map.entry("brown", 0x92400E));

    /** "#RRGGBB" or a colour name -> 0xRRGGBB, or -1. */
    public static int rgb(String text) {
        if (text == null) return -1;
        String t = text.trim().toLowerCase(Locale.ROOT);
        Integer named = NAMES.get(t.replace(" ", ""));
        if (named != null) return named;
        if (t.startsWith("#")) t = t.substring(1);
        if (t.length() != 6) return -1;
        try {
            return Integer.parseInt(t, 16);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
