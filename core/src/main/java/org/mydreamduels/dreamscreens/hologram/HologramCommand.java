package org.mydreamduels.dreamscreens.hologram;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.mydreamduels.dreamscreens.DreamScreens;
import org.mydreamduels.dreamscreens.action.Action;
import org.mydreamduels.dreamscreens.command.Cmd;
import org.mydreamduels.dreamscreens.platform.PlatformSupport;
import org.mydreamduels.dreamscreens.util.Colors;
import org.mydreamduels.dreamscreens.util.Text;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** /holo - everything FancyHolograms can do, plus spin/bob/proximity/personal. */
public final class HologramCommand extends Cmd {

    private static final List<String> SUBS = List.of("create", "delete", "list", "info", "movehere", "tp", "line", "item", "block",
            "type", "scale", "billboard", "background", "shadow", "seethrough", "align", "opacity", "linewidth", "glow", "viewrange",
            "update", "personal", "proximity", "spin", "bob", "brightness", "transform", "action", "hitbox", "copy", "rotate");

    public HologramCommand(DreamScreens plugin) {
        super(plugin, "dreamscreens.hologram");
    }

    private Hologram holo(String name) {
        Hologram h = plugin.holograms().get(name);
        if (h == null || h.managed()) throw new Fail("No hologram called '" + name + "'.");
        return h;
    }

    private void changed(CommandSender s, Hologram h, String what) {
        h.respawnAll();
        plugin.holograms().save();
        Text.ok(s, "&f" + h.name() + "&7: " + what);
    }

    @Override
    protected void run(CommandSender s, String[] a) {
        if (a.length == 0) {
            help(s);
            return;
        }
        String sub = a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "create" -> {
                need(a, 2, "/holo create <name> [text|item|block] [first line...]");
                if (!a[1].matches("[A-Za-z0-9_\\-]{1,32}")) throw new Fail("Names may use letters, numbers, _ and -.");
                if (plugin.holograms().get(a[1]) != null) throw new Fail("That hologram already exists.");
                Player p = player(s);
                Hologram h = new Hologram(plugin, a[1], false);
                Location l = p.getLocation().add(0, 1.8, 0);
                h.setLocation(l);
                Hologram.Type type = Hologram.Type.TEXT;
                int textFrom = 2;
                if (a.length > 2) {
                    try {
                        type = Hologram.Type.valueOf(a[2].toUpperCase(Locale.ROOT));
                        textFrom = 3;
                    } catch (IllegalArgumentException ignored) {
                    }
                }
                h.setType(type);
                if (type == Hologram.Type.TEXT) h.lines().add(a.length > textFrom ? join(a, textFrom) : "&#C084FC&l" + a[1]);
                if (type == Hologram.Type.ITEM) {
                    ItemStack hand = p.getInventory().getItemInMainHand();
                    if (!hand.getType().isAir()) h.setItem(hand);
                }
                plugin.holograms().add(h);
                Text.ok(s, "Hologram &f" + h.name() + "&7 created. &8/holo help for every option");
            }
            case "delete", "remove" -> {
                need(a, 2, "/holo delete <name>");
                if (!plugin.holograms().remove(holo(a[1]).name())) throw new Fail("No such hologram.");
                Text.ok(s, "Hologram &f" + a[1] + "&7 deleted.");
            }
            case "list" -> {
                List<String> names = plugin.holograms().names();
                Text.send(s, names.isEmpty() ? "&7No holograms yet." : "&7Holograms (" + names.size() + "): &f" + String.join("&7, &f", names));
            }
            case "info" -> {
                need(a, 2, "/holo info <name>");
                Hologram h = holo(a[1]);
                Text.raw(s, "&#C084FC&l" + h.name() + " &8(" + h.type().name().toLowerCase(Locale.ROOT) + ")");
                Text.raw(s, String.format(Locale.ROOT, "&7At &f%s %.1f %.1f %.1f &7view &f%.0f &7update &f%dt", h.worldName(), h.x(), h.y(), h.z(),
                        h.viewRangeSetting(), h.updateTicks()));
                Text.raw(s, "&7billboard &f" + h.billboard().name().toLowerCase(Locale.ROOT) + " &7background &f" + h.background()
                        + " &7spin &f" + h.spin() + " &7bob &f" + h.bob() + " &7proximity &f" + h.proximity() + " &7personal &f" + h.personal());
                for (int i = 0; i < h.lines().size(); i++) Text.raw(s, "&8" + (i + 1) + ". &r" + h.lines().get(i));
                for (int i = 0; i < h.actions().actions().size(); i++) Text.raw(s, "&8action " + (i + 1) + ": &f" + h.actions().actions().get(i).serialize());
            }
            case "movehere", "move" -> {
                need(a, 2, "/holo movehere <name>");
                Hologram h = holo(a[1]);
                h.setLocation(player(s).getLocation().add(0, 1.8, 0));
                changed(s, h, "moved here.");
            }
            case "tp", "teleport" -> {
                need(a, 2, "/holo tp <name>");
                Location l = holo(a[1]).location();
                if (l == null) throw new Fail("Its world isn't loaded.");
                Player p = player(s);
                if (PlatformSupport.hasUnifiedSchedulers()) p.teleportAsync(l);
                else p.teleport(l);
            }
            case "line" -> line(s, a);
            case "item" -> {
                need(a, 2, "/holo item <name> [material]  (no material = item in hand)");
                Hologram h = holo(a[1]);
                ItemStack it;
                if (a.length > 2) {
                    Material m = Material.matchMaterial(a[2]);
                    if (m == null || !m.isItem()) throw new Fail("Unknown item '" + a[2] + "'.");
                    it = new ItemStack(m);
                } else {
                    it = player(s).getInventory().getItemInMainHand();
                    if (it.getType().isAir()) throw new Fail("Hold an item or name one.");
                }
                h.setItem(it);
                h.setType(Hologram.Type.ITEM);
                changed(s, h, "item set to " + it.getType().name().toLowerCase(Locale.ROOT) + ".");
            }
            case "block" -> {
                need(a, 3, "/holo block <name> <block>");
                Hologram h = holo(a[1]);
                String b = a[2].contains(":") ? a[2] : "minecraft:" + a[2];
                try {
                    plugin.getServer().createBlockData(b);
                } catch (IllegalArgumentException e) {
                    throw new Fail("Unknown block '" + a[2] + "'.");
                }
                h.setBlock(b);
                h.setType(Hologram.Type.BLOCK);
                changed(s, h, "block set.");
            }
            case "type" -> {
                need(a, 3, "/holo type <name> <text|item|block>");
                Hologram h = holo(a[1]);
                h.setType(Hologram.Type.valueOf(a[2].toUpperCase(Locale.ROOT)));
                changed(s, h, "type " + a[2] + ".");
            }
            case "scale" -> {
                need(a, 3, "/holo scale <name> <size> | <x> <y> <z>");
                Hologram h = holo(a[1]);
                float x = (float) num(a[2], "Scale");
                float y = a.length > 3 ? (float) num(a[3], "Scale") : x;
                float z = a.length > 4 ? (float) num(a[4], "Scale") : x;
                h.setScale(x, y, z);
                changed(s, h, "scale set.");
            }
            case "billboard" -> {
                need(a, 3, "/holo billboard <name> <center|fixed|vertical|horizontal>");
                Hologram h = holo(a[1]);
                h.setBillboard(Display.Billboard.valueOf(a[2].toUpperCase(Locale.ROOT)));
                changed(s, h, "billboard " + a[2] + ".");
            }
            case "background" -> {
                need(a, 3, "/holo background <name> <default|transparent|#AARRGGBB|#RRGGBB>");
                Hologram h = holo(a[1]);
                String v = a[2];
                if (!v.equalsIgnoreCase("default") && !v.equalsIgnoreCase("transparent") && Colors.argb(v) == null) {
                    throw new Fail("Use default, transparent, #RRGGBB or #AARRGGBB (AA = opacity, e.g. #80000000).");
                }
                h.setBackground(v);
                changed(s, h, "background " + v + ".");
            }
            case "shadow" -> {
                need(a, 3, "/holo shadow <name> <true|false>");
                Hologram h = holo(a[1]);
                h.setShadow(bool(a[2]));
                changed(s, h, "shadow " + a[2] + ".");
            }
            case "seethrough" -> {
                need(a, 3, "/holo seethrough <name> <true|false>");
                Hologram h = holo(a[1]);
                h.setSeeThrough(bool(a[2]));
                changed(s, h, "see-through " + a[2] + ".");
            }
            case "align" -> {
                need(a, 3, "/holo align <name> <left|center|right>");
                Hologram h = holo(a[1]);
                h.setAlignment(TextDisplay.TextAlignment.valueOf(a[2].toUpperCase(Locale.ROOT)));
                changed(s, h, "alignment " + a[2] + ".");
            }
            case "opacity" -> {
                need(a, 3, "/holo opacity <name> <0-255>");
                Hologram h = holo(a[1]);
                h.setOpacity(Math.max(0, Math.min(255, integer(a[2], "Opacity"))));
                changed(s, h, "opacity " + a[2] + ".");
            }
            case "linewidth" -> {
                need(a, 3, "/holo linewidth <name> <pixels>");
                Hologram h = holo(a[1]);
                h.setLineWidth(integer(a[2], "Width"));
                changed(s, h, "line width " + a[2] + ".");
            }
            case "glow" -> {
                need(a, 3, "/holo glow <name> <#RRGGBB|off>");
                Hologram h = holo(a[1]);
                if (a[2].equalsIgnoreCase("off")) h.setGlow(null);
                else if (Colors.argb(a[2]) == null) throw new Fail("Use #RRGGBB or off.");
                else h.setGlow(a[2]);
                changed(s, h, "glow " + a[2] + ".");
            }
            case "viewrange" -> {
                need(a, 3, "/holo viewrange <name> <blocks>");
                Hologram h = holo(a[1]);
                h.setViewRange(num(a[2], "Range"));
                changed(s, h, "view range " + a[2] + ".");
            }
            case "update" -> {
                need(a, 3, "/holo update <name> <ticks>  (0 = never)");
                Hologram h = holo(a[1]);
                h.setUpdateTicks(integer(a[2], "Ticks"));
                changed(s, h, "updates every " + a[2] + " ticks.");
            }
            case "personal" -> {
                need(a, 3, "/holo personal <name> <true|false>");
                Hologram h = holo(a[1]);
                h.setPersonal(bool(a[2]));
                changed(s, h, "personal " + a[2] + (h.personal() ? " &8(each player sees their own placeholders)" : "") + ".");
            }
            case "proximity" -> {
                need(a, 3, "/holo proximity <name> <radius|off>");
                Hologram h = holo(a[1]);
                h.setProximity(a[2].equalsIgnoreCase("off") ? 0 : num(a[2], "Radius"));
                changed(s, h, h.proximity() > 0 ? "pops open within " + a[2] + " blocks." : "proximity off.");
            }
            case "spin" -> {
                need(a, 3, "/holo spin <name> <degrees per second|off>");
                Hologram h = holo(a[1]);
                h.setSpin(a[2].equalsIgnoreCase("off") ? 0 : num(a[2], "Speed"));
                changed(s, h, "spin " + a[2] + ".");
            }
            case "bob" -> {
                need(a, 3, "/holo bob <name> <blocks|off>");
                Hologram h = holo(a[1]);
                h.setBob(a[2].equalsIgnoreCase("off") ? 0 : num(a[2], "Amount"));
                changed(s, h, "bob " + a[2] + ".");
            }
            case "brightness" -> {
                need(a, 3, "/holo brightness <name> <0-15|off>");
                Hologram h = holo(a[1]);
                h.setBrightness(a[2].equalsIgnoreCase("off") ? -1 : Math.max(0, Math.min(15, integer(a[2], "Brightness"))));
                changed(s, h, "brightness " + a[2] + ".");
            }
            case "transform" -> {
                need(a, 3, "/holo transform <name> <fixed|ground|head|gui|none|...>");
                Hologram h = holo(a[1]);
                ItemDisplay.ItemDisplayTransform.valueOf(a[2].toUpperCase(Locale.ROOT));
                h.setItemTransform(a[2].toUpperCase(Locale.ROOT));
                changed(s, h, "item transform " + a[2] + ".");
            }
            case "action" -> action(s, a);
            case "hitbox" -> {
                need(a, 3, "/holo hitbox <name> <width> <height> | auto");
                Hologram h = holo(a[1]);
                if (a[2].equalsIgnoreCase("auto")) h.setHitbox(0, 0);
                else {
                    need(a, 4, "/holo hitbox <name> <width> <height>");
                    h.setHitbox((float) num(a[2], "Width"), (float) num(a[3], "Height"));
                }
                changed(s, h, "hitbox set.");
            }
            case "copy" -> {
                need(a, 3, "/holo copy <name> <new name>");
                Hologram from = holo(a[1]);
                if (plugin.holograms().get(a[2]) != null) throw new Fail("That name is taken.");
                org.bukkit.configuration.file.YamlConfiguration tmp = new org.bukkit.configuration.file.YamlConfiguration();
                from.save(tmp.createSection("x"));
                Hologram h = Hologram.load(plugin, a[2], tmp.getConfigurationSection("x"));
                h.setLocation(player(s).getLocation().add(0, 1.8, 0));
                plugin.holograms().add(h);
                Text.ok(s, "Copied to &f" + h.name() + "&7 here.");
            }
            case "rotate" -> {
                need(a, 3, "/holo rotate <name> <yaw>");
                Hologram h = holo(a[1]);
                h.setPosition(h.worldName(), h.x(), h.y(), h.z(), (float) num(a[2], "Yaw"));
                changed(s, h, "rotated (only visible with billboard fixed).");
            }
            case "help" -> help(s);
            default -> throw new Fail("Unknown option. /holo help");
        }
    }

    private void line(CommandSender s, String[] a) {
        need(a, 3, "/holo line <name> <add|set|insert|remove> ...");
        Hologram h = holo(a[1]);
        List<String> lines = h.lines();
        switch (a[2].toLowerCase(Locale.ROOT)) {
            case "add" -> {
                need(a, 4, "/holo line <name> add <text>");
                lines.add(join(a, 3));
            }
            case "set" -> {
                need(a, 5, "/holo line <name> set <line> <text>");
                int i = integer(a[3], "Line") - 1;
                if (i < 0 || i >= lines.size()) throw new Fail("There is no line " + a[3] + ".");
                lines.set(i, join(a, 4));
            }
            case "insert" -> {
                need(a, 5, "/holo line <name> insert <line> <text>");
                int i = integer(a[3], "Line") - 1;
                if (i < 0 || i > lines.size()) throw new Fail("Line must be 1-" + (lines.size() + 1) + ".");
                lines.add(i, join(a, 4));
            }
            case "remove" -> {
                need(a, 4, "/holo line <name> remove <line>");
                int i = integer(a[3], "Line") - 1;
                if (i < 0 || i >= lines.size()) throw new Fail("There is no line " + a[3] + ".");
                lines.remove(i);
            }
            default -> throw new Fail("Use add, set, insert or remove.");
        }
        changed(s, h, "lines updated (" + lines.size() + ").");
    }

    private void action(CommandSender s, String[] a) {
        need(a, 3, "/holo action <name> <add|remove|list|cooldown> ...");
        Hologram h = holo(a[1]);
        switch (a[2].toLowerCase(Locale.ROOT)) {
            case "add" -> {
                need(a, 4, "/holo action <name> add <type:value>  e.g. player:spawn, console:give {player} apple, message:&aHi");
                h.actions().actions().add(Action.parse(join(a, 3)));
                changed(s, h, "action added - click it to run.");
            }
            case "remove" -> {
                need(a, 4, "/holo action <name> remove <number>");
                int i = integer(a[3], "Number") - 1;
                if (i < 0 || i >= h.actions().actions().size()) throw new Fail("No action #" + a[3] + ".");
                h.actions().actions().remove(i);
                changed(s, h, "action removed.");
            }
            case "list" -> {
                if (h.actions().isEmpty()) Text.send(s, "&7No actions.");
                for (int i = 0; i < h.actions().actions().size(); i++) Text.raw(s, "&8" + (i + 1) + ". &f" + h.actions().actions().get(i).serialize());
            }
            case "cooldown" -> {
                need(a, 4, "/holo action <name> cooldown <ms>");
                h.actions().setCooldownMs(integer(a[3], "Cooldown"));
                changed(s, h, "click cooldown " + a[3] + "ms.");
            }
            default -> throw new Fail("Use add, remove, list or cooldown.");
        }
    }

    private void help(CommandSender s) {
        Text.raw(s, "&#C084FC&l/holo &7- holograms");
        Text.raw(s, "&f create <name> [text|item|block] [line] &8| &fdelete &8| &flist &8| &finfo &8| &fmovehere &8| &ftp &8| &fcopy");
        Text.raw(s, "&f line <name> add|set|insert|remove &8| &fitem <name> [material] &8| &fblock <name> <block> &8| &ftype");
        Text.raw(s, "&f scale &8| &fbillboard &8| &fbackground &8| &fshadow &8| &fseethrough &8| &falign &8| &fopacity &8| &flinewidth &8| &fglow");
        Text.raw(s, "&f spin <deg/s> &8| &fbob <blocks> &8| &fproximity <radius> &8| &fpersonal &8| &fupdate <ticks> &8| &fviewrange &8| &fbrightness");
        Text.raw(s, "&f action <name> add|remove|list|cooldown &8| &fhitbox &8| &ftransform &8| &frotate");
        Text.raw(s, "&7Placeholders: &f{player} {online} %papi% {countdown:2026-12-31 20:00} <status:%x%;0=&cEmpty;1+=&aOpen>");
    }

    @Override
    protected List<String> tab(CommandSender s, String[] a) {
        if (a.length == 1) {
            List<String> out = new ArrayList<>(SUBS);
            out.add("help");
            return out;
        }
        String sub = a[0].toLowerCase(Locale.ROOT);
        if (a.length == 2) return sub.equals("create") || sub.equals("list") ? List.of() : plugin.holograms().names();
        if (a.length == 3) {
            return switch (sub) {
                case "create", "type" -> List.of("text", "item", "block");
                case "line" -> List.of("add", "set", "insert", "remove");
                case "billboard" -> List.of("center", "fixed", "vertical", "horizontal");
                case "background" -> List.of("default", "transparent", "#80000000", "#CC1E1B2E");
                case "shadow", "seethrough", "personal" -> List.of("true", "false");
                case "align" -> List.of("left", "center", "right");
                case "glow" -> List.of("off", "#C084FC", "#55FF55", "#FF5555");
                case "proximity" -> List.of("off", "4", "6", "8");
                case "spin" -> List.of("off", "45", "90");
                case "bob" -> List.of("off", "0.1", "0.2");
                case "action" -> List.of("add", "remove", "list", "cooldown");
                case "transform" -> Arrays.stream(ItemDisplay.ItemDisplayTransform.values()).map(t -> t.name().toLowerCase(Locale.ROOT)).toList();
                case "item" -> Arrays.stream(Material.values()).filter(Material::isItem).map(m -> m.name().toLowerCase(Locale.ROOT)).limit(400).toList();
                case "hitbox" -> List.of("auto", "1", "2");
                default -> List.of();
            };
        }
        if (a.length == 4 && sub.equals("action") && a[2].equalsIgnoreCase("add")) return Action.EXAMPLES;
        return List.of();
    }
}
