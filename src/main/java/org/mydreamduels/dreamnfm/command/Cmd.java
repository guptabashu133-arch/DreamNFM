package org.mydreamduels.dreamnfm.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.mydreamduels.dreamnfm.DreamNFM;
import org.mydreamduels.dreamnfm.util.Text;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Base for /holo /npc /screen /nfm: permission, errors, tab filtering, helpers. */
public abstract class Cmd implements TabExecutor {

    public static final class Fail extends RuntimeException {
        public Fail(String message) {
            super(message, null, false, false);
        }
    }

    protected final DreamNFM plugin;
    private final String permission;

    protected Cmd(DreamNFM plugin, String permission) {
        this.plugin = plugin;
        this.permission = permission;
    }

    @Override
    public final boolean onCommand(CommandSender s, Command command, String label, String[] args) {
        if (permission != null && !s.hasPermission(permission)) {
            Text.err(s, "You don't have permission to do that.");
            return true;
        }
        try {
            run(s, args);
        } catch (Fail e) {
            Text.err(s, e.getMessage());
        } catch (IllegalArgumentException e) {
            Text.err(s, e.getMessage() == null ? "Invalid value." : e.getMessage());
        }
        return true;
    }

    protected abstract void run(CommandSender s, String[] a);

    @Override
    public final List<String> onTabComplete(CommandSender s, Command command, String alias, String[] args) {
        if (permission != null && !s.hasPermission(permission)) return List.of();
        List<String> all = tab(s, args);
        if (all == null || args.length == 0) return List.of();
        String typed = args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String x : all) if (x.toLowerCase(Locale.ROOT).startsWith(typed)) out.add(x);
        return out;
    }

    protected abstract List<String> tab(CommandSender s, String[] a);

    protected static Player player(CommandSender s) {
        if (s instanceof Player p) return p;
        throw new Fail("Only players can do that.");
    }

    protected static String join(String[] a, int from) {
        return String.join(" ", Arrays.copyOfRange(a, Math.min(from, a.length), a.length));
    }

    protected static double num(String s, String what) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            throw new Fail(what + " must be a number.");
        }
    }

    protected static int integer(String s, String what) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            throw new Fail(what + " must be a whole number.");
        }
    }

    protected static boolean bool(String s) {
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "true", "on", "yes" -> true;
            case "false", "off", "no" -> false;
            default -> throw new Fail("Use true or false.");
        };
    }

    protected static void need(String[] a, int n, String usage) {
        if (a.length < n) throw new Fail("Usage: " + usage);
    }
}
