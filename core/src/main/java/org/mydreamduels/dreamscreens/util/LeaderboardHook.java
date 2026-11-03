package org.mydreamduels.dreamscreens.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads Dreamleaderboard's tops (it has no PlaceholderAPI expansion):
 *   {lb_<type>_<rank>_name}  {lb_<type>_<rank>_value}     rank 1-10
 *   types: money, playtime, deaths, mobs, placed, broken
 *   {stat_<type>} - the viewer's own: playtime, deaths, kills, mobs, placed, broken
 * Missing plugin / rank -> "---" (name) and "-" (value).
 */
public final class LeaderboardHook {

    static final Pattern TOP = Pattern.compile("\\{lb_([a-z]+)_(\\d{1,2})_(name|value)}");
    static final Pattern STAT = Pattern.compile("\\{stat_([a-z]+)}");
    private static final long CACHE_MS = 10_000;

    private record Row(String name, double value) {
    }

    private record Top(long at, List<Row> rows) {
    }

    private static final Map<String, Top> CACHE = new ConcurrentHashMap<>();
    private static volatile boolean broken;

    private LeaderboardHook() {
    }

    static String type(String key) {
        return switch (key) {
            case "money", "balance", "bal" -> "MONEY";
            case "playtime", "time" -> "PLAYTIME";
            case "deaths", "death" -> "DEATHS";
            case "mobs", "kills", "mobkills" -> "MOBS_KILLED";
            case "placed", "place" -> "BLOCKS_PLACED";
            case "broken", "break", "mined" -> "BLOCKS_BROKEN";
            default -> null;
        };
    }

    public static String apply(Player viewer, String t) {
        if (t.contains("{lb_")) {
            Matcher m = TOP.matcher(t);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                String type = type(m.group(1));
                int rank = Integer.parseInt(m.group(2));
                Row r = type == null ? null : row(type, rank);
                String out = m.group(3).equals("name") ? (r == null ? "---" : r.name) : (r == null ? "-" : format(type, r.value));
                m.appendReplacement(sb, Matcher.quoteReplacement(out));
            }
            m.appendTail(sb);
            t = sb.toString();
        }
        if (viewer != null && t.contains("{stat_")) {
            Matcher m = STAT.matcher(t);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                m.appendReplacement(sb, Matcher.quoteReplacement(stat(viewer, m.group(1))));
            }
            m.appendTail(sb);
            t = sb.toString();
        }
        return t;
    }

    private static Object manager() throws Exception {
        Plugin p = Bukkit.getPluginManager().getPlugin("Dreamleaderboard");
        if (p == null || !p.isEnabled()) return null;
        return p.getClass().getMethod("getStatsManager").invoke(p);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Row row(String type, int rank) {
        if (broken || rank < 1 || rank > 10) return null;
        long now = System.currentTimeMillis();
        Top top = CACHE.get(type);
        if (top == null || now - top.at > CACHE_MS) {
            try {
                Object sm = manager();
                if (sm == null) return null;
                Class enumClass = Class.forName("org.mydreamduels.dreamleaderboard.model.StatType", true, sm.getClass().getClassLoader());
                Object st = Enum.valueOf(enumClass, type);
                Method topM = sm.getClass().getMethod("top", enumClass, int.class);
                List<?> entries = (List<?>) topM.invoke(sm, st, 10);
                List<Row> rows = new java.util.ArrayList<>();
                for (Object e : entries) {
                    String name = String.valueOf(e.getClass().getMethod("name").invoke(e));
                    double v = ((Number) e.getClass().getMethod("value").invoke(e)).doubleValue();
                    rows.add(new Row(name, v));
                }
                top = new Top(now, rows);
                CACHE.put(type, top);
            } catch (Throwable t) {
                broken = true;
                Bukkit.getLogger().warning("[DreamScreens] Could not read Dreamleaderboard tops: " + t);
                return null;
            }
        }
        return rank <= top.rows.size() ? top.rows.get(rank - 1) : null;
    }

    private static String stat(Player p, String key) {
        String getter = switch (key) {
            case "playtime", "time" -> "getLivePlaytimeSeconds";
            case "deaths" -> "getDeaths";
            case "kills" -> "getKills";
            case "mobs" -> "getMobsKilled";
            case "placed" -> "getBlocksPlaced";
            case "broken", "mined" -> "getBlocksBroken";
            default -> null;
        };
        if (getter == null || broken) return "-";
        try {
            Object sm = manager();
            if (sm == null) return "-";
            Object stats = sm.getClass().getMethod("get", java.util.UUID.class).invoke(sm, p.getUniqueId());
            if (stats == null) return "0";
            long v = ((Number) stats.getClass().getMethod(getter).invoke(stats)).longValue();
            return key.startsWith("play") || key.equals("time") ? duration(v) : String.format(Locale.ROOT, "%,d", v);
        } catch (Throwable t) {
            return "-";
        }
    }

    static String format(String type, double v) {
        return switch (type) {
            case "MONEY" -> "$" + compact(v);
            case "PLAYTIME" -> duration((long) v);
            default -> compact(v);
        };
    }

    static String compact(double v) {
        double a = Math.abs(v);
        if (a >= 1e12) return String.format(Locale.ROOT, "%.1fT", v / 1e12);
        if (a >= 1e9) return String.format(Locale.ROOT, "%.1fB", v / 1e9);
        if (a >= 1e6) return String.format(Locale.ROOT, "%.1fM", v / 1e6);
        if (a >= 1e4) return String.format(Locale.ROOT, "%.1fK", v / 1e3);
        return String.format(Locale.ROOT, "%,d", (long) v);
    }

    static String duration(long seconds) {
        long m = seconds / 60, h = m / 60, d = h / 24;
        if (d > 0) return d + "d " + (h % 24) + "h";
        if (h > 0) return h + "h " + (m % 60) + "m";
        return m + "m";
    }
}
