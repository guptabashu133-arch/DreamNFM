package org.mydreamduels.dreamnfm.screen;

import java.util.Locale;

/**
 * A position or size on a screen: 20 (pixels), -20 (from the right/bottom
 * edge), 50% (of the screen), center, right/bottom.
 */
public record Dim(double value, Kind kind) {

    public enum Kind { ABS, PERCENT, CENTER, END }

    public static final Dim ZERO = new Dim(0, Kind.ABS);
    public static final Dim AUTO = new Dim(-1, Kind.ABS);

    public static Dim parse(Object o, Dim def) {
        if (o == null) return def;
        if (o instanceof Number n) {
            double v = n.doubleValue();
            return v < 0 ? new Dim(v, Kind.END) : new Dim(v, Kind.ABS);
        }
        String s = o.toString().trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty() || s.equals("auto")) return def;
        switch (s) {
            case "center", "middle", "centre" -> {
                return new Dim(0, Kind.CENTER);
            }
            case "right", "bottom", "end" -> {
                return new Dim(0, Kind.END);
            }
            case "left", "top", "start" -> {
                return ZERO;
            }
            case "full" -> {
                return new Dim(100, Kind.PERCENT);
            }
            default -> {
            }
        }
        try {
            if (s.endsWith("%")) return new Dim(Double.parseDouble(s.substring(0, s.length() - 1).trim()), Kind.PERCENT);
            double v = Double.parseDouble(s.endsWith("px") ? s.substring(0, s.length() - 2) : s);
            return v < 0 ? new Dim(v, Kind.END) : new Dim(v, Kind.ABS);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + o + "' is not a position/size (use 20, -20, 50%, center).");
        }
    }

    /** As a position: total = screen size on this axis, size = the element's size. */
    public int pos(int total, int size) {
        return switch (kind) {
            case ABS -> (int) value;
            case PERCENT -> (int) Math.round(total * value / 100.0);
            case CENTER -> (total - size) / 2;
            case END -> total - size + (int) value;
        };
    }

    /** As a size. AUTO / non-positive results fall back to def. */
    public int size(int total, int def) {
        int v = switch (kind) {
            case ABS -> (int) value;
            case PERCENT -> (int) Math.round(total * value / 100.0);
            case CENTER -> total / 2;
            case END -> total + (int) value;
        };
        return v <= 0 ? def : v;
    }

    public boolean isAuto() {
        return this == AUTO || (kind == Kind.ABS && value < 0);
    }

    public boolean isCenter() {
        return kind == Kind.CENTER;
    }
}
