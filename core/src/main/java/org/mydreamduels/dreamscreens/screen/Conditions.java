package org.mydreamduels.dreamscreens.screen;

import java.util.Locale;

/**
 * Tiny comparisons for auto-pages, checked AFTER placeholders were filled in:
 *   "true == true"   "3 >= 1"   "Castle != None"   "LIVE contains LIVE"   "yes"
 * Numbers compare as numbers, everything else as text (case-insensitive).
 * Colour codes are ignored.
 */
public final class Conditions {

    private static final String[] OPS = {"!contains", " contains ", ">=", "<=", "!=", "==", ">", "<", "="};

    private Conditions() {
    }

    public static boolean test(String resolved) {
        if (resolved == null) return false;
        String s = resolved.replaceAll("(?i)[§&][0-9a-fk-orx]", "").trim();
        for (String op : OPS) {
            int i = indexOf(s, op);
            if (i < 0) continue;
            String a = s.substring(0, i).trim(), b = s.substring(i + op.length()).trim();
            return compare(a, op.trim(), b);
        }
        return truthy(s);
    }

    private static int indexOf(String s, String op) {
        return op.trim().equals("contains") || op.equals("!contains") ? s.toLowerCase(Locale.ROOT).indexOf(op.toLowerCase(Locale.ROOT)) : s.indexOf(op);
    }

    private static boolean compare(String a, String op, String b) {
        Double x = num(a), y = num(b);
        switch (op) {
            case "contains" -> {
                return a.toLowerCase(Locale.ROOT).contains(b.toLowerCase(Locale.ROOT));
            }
            case "!contains" -> {
                return !a.toLowerCase(Locale.ROOT).contains(b.toLowerCase(Locale.ROOT));
            }
            case "==", "=" -> {
                return x != null && y != null ? x.doubleValue() == y.doubleValue() : a.equalsIgnoreCase(b);
            }
            case "!=" -> {
                return x != null && y != null ? x.doubleValue() != y.doubleValue() : !a.equalsIgnoreCase(b);
            }
            default -> {
                if (x == null || y == null) return false;
                return switch (op) {
                    case ">" -> x > y;
                    case "<" -> x < y;
                    case ">=" -> x >= y;
                    case "<=" -> x <= y;
                    default -> false;
                };
            }
        }
    }

    private static boolean truthy(String s) {
        String t = s.toLowerCase(Locale.ROOT);
        return t.equals("true") || t.equals("yes") || t.equals("1") || t.equals("on") || t.equals("live");
    }

    private static Double num(String s) {
        String t = s.replace(",", "");
        if (!t.matches("-?\\d+(\\.\\d+)?")) return null;
        try {
            return Double.parseDouble(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
