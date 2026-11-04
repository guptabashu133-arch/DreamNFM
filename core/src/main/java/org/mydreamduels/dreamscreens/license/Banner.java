package org.mydreamduels.dreamscreens.license;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The startup logo and status lines in the console (same style as the other
 * Dream plugins). Block/box characters exist in every console code page.
 */
public final class Banner {

    public static final String SITE = "https://dreamscube.fun";

    private static final String[] LOGO = {
            "██████╗ ██████╗ ███████╗ █████╗ ███╗   ███╗███████╗ ██████╗██████╗ ███████╗███████╗███╗   ██╗███████╗",
            "██╔══██╗██╔══██╗██╔════╝██╔══██╗████╗ ████║██╔════╝██╔════╝██╔══██╗██╔════╝██╔════╝████╗  ██║██╔════╝",
            "██║  ██║██████╔╝█████╗  ███████║██╔████╔██║███████╗██║     ██████╔╝█████╗  █████╗  ██╔██╗ ██║███████╗",
            "██║  ██║██╔══██╗██╔══╝  ██╔══██║██║╚██╔╝██║╚════██║██║     ██╔══██╗██╔══╝  ██╔══╝  ██║╚██╗██║╚════██║",
            "██████╔╝██║  ██║███████╗██║  ██║██║ ╚═╝ ██║███████║╚██████╗██║  ██║███████╗███████╗██║ ╚████║███████║",
            "╚═════╝ ╚═╝  ╚═╝╚══════╝╚═╝  ╚═╝╚═╝     ╚═╝╚══════╝ ╚═════╝╚═╝  ╚═╝╚══════╝╚══════╝╚═╝  ╚═══╝╚══════╝"};
    private static final int WIDTH = 101;
    /** Column where "Screens" starts in the logo. */
    private static final int SCREENS_FROM = 43;

    private Banner() {
    }

    /** lines: label/value pairs shown under the logo. */
    @SuppressWarnings("deprecation") // getDescription(): plain Spigot has no getPluginMeta()
    public static void print(JavaPlugin plugin, String... lines) {
        CommandSender c = Bukkit.getConsoleSender();
        String rule = "\u00a78" + "-".repeat(WIDTH);
        c.sendMessage("");
        for (String line : LOGO) c.sendMessage(gradient(line));
        c.sendMessage("");
        c.sendMessage(center("\u00a77Map touchscreens \u00a78|\u00a77 In-game editor \u00a78|\u00a77 Holograms \u00a78|\u00a77 NPCs"));
        c.sendMessage(center("\u00a77by \u00a7fBasuGamer56 \u00a78-\u00a77 v" + plugin.getDescription().getVersion()));
        c.sendMessage(rule);
        for (int i = 0; i + 1 < lines.length; i += 2) line(c, lines[i], lines[i + 1]);
        line(c, "License", "\u00a77checking... \u00a78|\u00a77 manage at \u00a7b" + SITE);
        c.sendMessage(rule);
    }

    /** Filled blocks in the brand colours (Dream yellow, Screens aqua), the 3D shadow dark grey. */
    private static String gradient(String line) {
        StringBuilder sb = new StringBuilder();
        String current = null;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            String color = ch == ' ' ? current : ch != '\u2588' ? "\u00a78" : i < SCREENS_FROM ? "\u00a7e" : "\u00a7b";
            if (color != null && !color.equals(current)) {
                sb.append(color);
                current = color;
            }
            sb.append(ch);
        }
        return sb.toString();
    }

    private static String center(String text) {
        int visible = text.replaceAll("\u00a7.", "").length();
        return " ".repeat(Math.max(0, (WIDTH - visible) / 2)) + text;
    }

    private static void line(CommandSender c, String label, String value) {
        c.sendMessage("\u00a78 > \u00a77" + String.format("%-8s", label) + " " + value);
    }
}
