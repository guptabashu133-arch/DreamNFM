package org.mydreamduels.dreamnfm.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** &-codes + &#RRGGBB -> section codes / Adventure components. */
public final class Text {

    private static final Pattern HEX = Pattern.compile("&#([0-9a-fA-F]{6})");
    private static volatile String prefix = "";

    private Text() {
    }

    public static void setPrefix(String raw) {
        prefix = color(raw == null ? "" : raw);
    }

    @SuppressWarnings("deprecation")
    public static String color(String text) {
        if (text == null) return "";
        Matcher m = HEX.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            StringBuilder rep = new StringBuilder("§x");
            for (char c : m.group(1).toCharArray()) rep.append('§').append(c);
            m.appendReplacement(sb, Matcher.quoteReplacement(rep.toString()));
        }
        m.appendTail(sb);
        return ChatColor.translateAlternateColorCodes('&', sb.toString());
    }

    @SuppressWarnings("deprecation")
    public static String strip(String text) {
        return ChatColor.stripColor(color(text));
    }

    public static Component component(String text) {
        return LegacyComponentSerializer.legacySection().deserialize(color(text));
    }

    public static Component lines(List<String> lines) {
        return component(String.join("\n", lines));
    }

    public static void send(CommandSender to, String message) {
        if (to != null) to.sendMessage(prefix + color(message));
    }

    public static void ok(CommandSender to, String message) {
        send(to, "&#7CF5A6✔ &f" + message);
    }

    public static void err(CommandSender to, String message) {
        send(to, "&#FF6B81✖ " + message);
    }

    public static void raw(CommandSender to, String message) {
        if (to != null) to.sendMessage(color(message));
    }
}
