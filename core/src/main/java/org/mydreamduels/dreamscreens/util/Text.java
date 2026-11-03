package org.mydreamduels.dreamscreens.util;

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

    private static final Pattern MINI = Pattern.compile("</?([a-z_]+|#[0-9a-fA-F]{6})>");
    private static final java.util.Map<String, String> MINI_CODES = java.util.Map.ofEntries(
            java.util.Map.entry("black", "&0"), java.util.Map.entry("dark_blue", "&1"), java.util.Map.entry("dark_green", "&2"),
            java.util.Map.entry("dark_aqua", "&3"), java.util.Map.entry("dark_red", "&4"), java.util.Map.entry("dark_purple", "&5"),
            java.util.Map.entry("gold", "&6"), java.util.Map.entry("gray", "&7"), java.util.Map.entry("grey", "&7"),
            java.util.Map.entry("dark_gray", "&8"), java.util.Map.entry("dark_grey", "&8"), java.util.Map.entry("blue", "&9"),
            java.util.Map.entry("green", "&a"), java.util.Map.entry("aqua", "&b"), java.util.Map.entry("red", "&c"),
            java.util.Map.entry("light_purple", "&d"), java.util.Map.entry("yellow", "&e"), java.util.Map.entry("white", "&f"),
            java.util.Map.entry("bold", "&l"), java.util.Map.entry("b", "&l"), java.util.Map.entry("italic", "&o"),
            java.util.Map.entry("i", "&o"), java.util.Map.entry("underlined", "&n"), java.util.Map.entry("u", "&n"),
            java.util.Map.entry("strikethrough", "&m"), java.util.Map.entry("reset", "&r"));

    /**
     * Simple MiniMessage colour tags (as other plugins' placeholders return
     * them, e.g. {@code <#7CFC9B>} or {@code <white>}) to &-codes. Closing
     * tags are dropped; unknown tags stay as they are.
     */
    static String mini(String text) {
        if (text.indexOf('<') < 0) return text;
        Matcher m = MINI.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String tag = m.group(1).toLowerCase(java.util.Locale.ROOT);
            boolean closing = m.group().startsWith("</");
            String rep;
            if (tag.startsWith("#")) rep = closing ? "" : "&" + tag;
            else if (MINI_CODES.containsKey(tag)) rep = closing ? "&r" : MINI_CODES.get(tag);
            else rep = m.group();
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    @SuppressWarnings("deprecation")
    public static String color(String text) {
        if (text == null) return "";
        text = mini(text);
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

    private static final boolean ADVENTURE = hasAdventure();

    private static boolean hasAdventure() {
        try {
            Class.forName("net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer");
            org.bukkit.entity.TextDisplay.class.getMethod("text", Class.forName("net.kyori.adventure.text.Component"));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Sets a text display's text from &-codes: Paper's own text API there, the classic one on Spigot. */
    @SuppressWarnings("deprecation")
    public static void display(org.bukkit.entity.TextDisplay td, String text) {
        if (ADVENTURE) PaperText.display(td, color(text));
        else td.setText(color(text));
    }

    /** Action bar message from &-codes (Paper API on Paper, BungeeCord chat API on Spigot). */
    public static void actionBar(org.bukkit.entity.Player p, String text) {
        try {
            if (ADVENTURE) PaperText.actionBar(p, color(text));
            else p.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR, net.md_5.bungee.api.chat.TextComponent.fromLegacyText(color(text)));
        } catch (Throwable ignored) {
            // action bar is a nice-to-have
        }
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
