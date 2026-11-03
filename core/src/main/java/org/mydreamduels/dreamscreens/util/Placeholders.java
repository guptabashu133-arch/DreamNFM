package org.mydreamduels.dreamscreens.util;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Placeholder pipeline used by holograms, NPC overheads and screens:
 *   1. built-ins: {player} {online} {max} {world} {ping} {time} {date}
 *   2. PlaceholderAPI (if installed, looked up reflectively)
 *   3. {countdown:yyyy-MM-dd HH:mm}  -> "2d 4h 10m" / "LIVE"
 *   4. <status:VALUE;0=&cEmpty;1-3=&eFilling;4+=&aReady;default=&7?>
 *      the "live status" colour rules - VALUE is usually a placeholder.
 */
public final class Placeholders {

    private static final Pattern COUNTDOWN = Pattern.compile("\\{countdown:([^}]+)}");
    private static final Pattern STATUS = Pattern.compile("<status:([^;>]*);([^>]*)>");
    private static final DateTimeFormatter DATE_IN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);

    private static volatile Method papi;
    private static volatile boolean papiChecked;

    private Placeholders() {
    }

    public static void reset() {
        papiChecked = false;
        papi = null;
    }

    private static Method papi() {
        if (!papiChecked) {
            papiChecked = true;
            try {
                if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
                    papi = Class.forName("me.clip.placeholderapi.PlaceholderAPI").getMethod("setPlaceholders", OfflinePlayer.class, String.class);
                }
            } catch (Throwable ignored) {
                papi = null;
            }
        }
        return papi;
    }

    public static boolean hasPapi() {
        return papi() != null;
    }

    /** player may be null (shared, non-personal text). */
    public static String apply(Player player, String text) {
        if (text == null || text.isEmpty()) return "";
        String t = text;
        if (t.indexOf('{') >= 0) {
            t = t.replace("{online}", String.valueOf(Bukkit.getOnlinePlayers().size()))
                    .replace("{max}", String.valueOf(Bukkit.getMaxPlayers()))
                    .replace("{time}", LocalDateTime.now().format(TIME))
                    .replace("{date}", LocalDateTime.now().format(DATE));
            if (t.contains("{tps}") || t.contains("{mspt}")) {
                double[] st = ServerStats.now();
                t = t.replace("{tps}", ServerStats.format(st[0])).replace("{mspt}", ServerStats.format(st[1]));
            }
            if (t.contains("{uptime}")) t = t.replace("{uptime}", ServerStats.uptime());
            if (t.contains("{lb_") || t.contains("{stat_")) t = LeaderboardHook.apply(player, t);
            if (player != null) {
                t = t.replace("{player}", player.getName())
                        .replace("{world}", player.getWorld().getName())
                        .replace("{ping}", String.valueOf(player.getPing()));
            } else {
                t = t.replace("{player}", "").replace("{world}", "").replace("{ping}", "");
            }
        }
        Method m = papi();
        if (m != null && t.indexOf('%') >= 0) {
            try {
                t = (String) m.invoke(null, player, t);
            } catch (Throwable ignored) {
                // a broken expansion must never break the hologram
            }
        }
        if (t.contains("{countdown:")) t = countdowns(t);
        if (t.contains("<status:")) t = statuses(t);
        return t;
    }

    private static String countdowns(String t) {
        Matcher m = COUNTDOWN.matcher(t);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String out;
            try {
                LocalDateTime target = LocalDateTime.parse(m.group(1).trim(), DATE_IN);
                long ms = target.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() - System.currentTimeMillis();
                out = ms <= 0 ? "LIVE" : duration(ms);
            } catch (DateTimeParseException e) {
                out = "?";
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(out));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    public static String duration(long ms) {
        long s = ms / 1000;
        long d = s / 86400, h = (s % 86400) / 3600, mi = (s % 3600) / 60, se = s % 60;
        if (d > 0) return d + "d " + h + "h " + mi + "m";
        if (h > 0) return h + "h " + mi + "m " + se + "s";
        if (mi > 0) return mi + "m " + se + "s";
        return se + "s";
    }

    /** Evaluates every <status:VALUE;rules> tag. */
    static String statuses(String t) {
        Matcher m = STATUS.matcher(t);
        StringBuilder sb = new StringBuilder();
        while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(status(m.group(1).trim(), m.group(2))));
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * rules: "0=&cEmpty;1-3=&eFilling;4+=&aReady;online=&aUp;default=&7Unknown".
     * Numeric rules (N, A-B, N+, N-) apply when VALUE is a number, otherwise exact text match.
     */
    public static String status(String value, String rules) {
        Double num = null;
        try {
            num = Double.parseDouble(value.replace(",", ""));
        } catch (NumberFormatException ignored) {
        }
        String fallback = value;
        for (String rule : rules.split(";")) {
            int eq = rule.indexOf('=');
            if (eq <= 0) continue;
            String cond = rule.substring(0, eq).trim();
            String out = rule.substring(eq + 1).replace("{value}", value);
            if (cond.equalsIgnoreCase("default")) {
                fallback = out;
                continue;
            }
            if (num != null && matchesNumber(cond, num)) return out;
            if (num == null && cond.equalsIgnoreCase(value)) return out;
        }
        return fallback;
    }

    private static boolean matchesNumber(String cond, double v) {
        try {
            if (cond.endsWith("+")) return v >= Double.parseDouble(cond.substring(0, cond.length() - 1));
            if (cond.endsWith("-") && cond.length() > 1 && cond.indexOf('-') == cond.length() - 1) {
                return v <= Double.parseDouble(cond.substring(0, cond.length() - 1));
            }
            int dash = cond.indexOf('-', 1);
            if (dash > 0) {
                return v >= Double.parseDouble(cond.substring(0, dash)) && v <= Double.parseDouble(cond.substring(dash + 1));
            }
            return v == Double.parseDouble(cond);
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
