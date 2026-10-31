package org.mydreamduels.dreamnfm.util;

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

    /** "#RRGGBB" -> 0xRRGGBB, or -1. */
    public static int rgb(String text) {
        if (text == null) return -1;
        String t = text.trim().toLowerCase(Locale.ROOT);
        if (t.startsWith("#")) t = t.substring(1);
        if (t.length() != 6) return -1;
        try {
            return Integer.parseInt(t, 16);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
