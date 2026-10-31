package org.mydreamduels.dreamnfm.screen;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.mydreamduels.dreamnfm.DreamNFM;
import org.mydreamduels.dreamnfm.command.Cmd;
import org.mydreamduels.dreamnfm.platform.PlatformSupport;
import org.mydreamduels.dreamnfm.util.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** /screen - map touchscreens. Pages live in plugins/DreamNFM/screens/<name>.yml. */
public final class ScreenCommand extends Cmd {

    private static final List<String> SUBS = List.of("create", "delete", "list", "info", "tp", "reload", "page", "images", "set", "icon", "design", "designs", "respawn");

    public ScreenCommand(DreamNFM plugin) {
        super(plugin, "dreamnfm.screen");
    }

    private Screen screen(String name) {
        Screen s = plugin.screens().get(name);
        if (s == null) throw new Fail("No screen called '" + name + "'.");
        return s;
    }

    @Override
    protected void run(CommandSender s, String[] a) {
        if (a.length == 0) {
            help(s);
            return;
        }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "create" -> create(s, a);
            case "delete", "remove" -> {
                need(a, 2, "/screen delete <name>");
                if (!plugin.screens().remove(a[1])) throw new Fail("No screen called '" + a[1] + "'.");
                Text.ok(s, "Screen &f" + a[1] + "&7 deleted &8(its page file in screens/ was kept)");
            }
            case "list" -> {
                List<String> names = plugin.screens().names();
                if (names.isEmpty()) {
                    Text.send(s, "No screens yet. &f/screen create <name> <width> <height>");
                    return;
                }
                Text.send(s, "&fScreens (" + names.size() + "): &7" + String.join("&8, &7", names));
            }
            case "info" -> {
                need(a, 2, "/screen info <name>");
                Screen sc = screen(a[1]);
                Geometry g = sc.geometry();
                Text.raw(s, "&#7AA2FF&l" + sc.name() + " &7(" + g.width() + "x" + g.height() + " maps, " + g.pixelWidth() + "x" + g.pixelHeight() + " px)");
                Text.raw(s, "&7 At &f" + sc.worldName() + " " + g.x() + " " + g.y() + " " + g.z() + " &7facing &f" + g.facing().name().toLowerCase(Locale.ROOT));
                Text.raw(s, "&7 Pages: &f" + String.join(", ", sc.pages().names()) + " &7(start: &f" + sc.pages().start() + "&7)");
                Text.raw(s, "&7 View range &f" + (int) sc.viewRange() + "&7, touch range &f" + (int) sc.touchRange() + "&7, cursor &f" + sc.cursor()
                        + "&7, viewers &f" + sc.viewers() + "&7, ready &f" + sc.ready());
                Text.raw(s, "&7 Edit: &fplugins/DreamNFM/screens/" + sc.name() + ".yml &7then &f/screen reload " + sc.name());
            }
            case "tp" -> {
                need(a, 2, "/screen tp <name>");
                Player p = player(s);
                Screen sc = screen(a[1]);
                World w = Bukkit.getWorld(sc.worldName());
                if (w == null) throw new Fail("World " + sc.worldName() + " is not loaded.");
                BlockFace f = sc.geometry().facing();
                double dist = Math.max(3, sc.geometry().width() * 0.9);
                Location to = new Location(w, sc.x() + f.getModX() * dist, sc.y() - 1, sc.z() + f.getModZ() * dist);
                to.setDirection(new org.bukkit.util.Vector(-f.getModX(), 0, -f.getModZ()));
                if (PlatformSupport.hasUnifiedSchedulers()) p.teleportAsync(to, PlayerTeleportEvent.TeleportCause.PLUGIN);
                else p.teleport(to);
            }
            case "reload" -> {
                if (a.length >= 2) {
                    screen(a[1]).reloadPages();
                    plugin.screens().images().clear();
                    Text.ok(s, "Reloaded pages of &f" + a[1]);
                } else {
                    plugin.screens().reloadAll();
                    Text.ok(s, "Reloaded all screen pages and images.");
                }
            }
            case "page" -> {
                need(a, 3, "/screen page <screen> <page|back|home> [player]");
                Screen sc = screen(a[1]);
                Player target = a.length >= 4 ? Bukkit.getPlayerExact(a[3]) : player(s);
                if (target == null) throw new Fail("That player isn't online.");
                String page = a[2];
                if (!page.equalsIgnoreCase("back") && !page.equalsIgnoreCase("home") && sc.pages().page(page) == null)
                    throw new Fail("No page '" + page + "'. Pages: " + String.join(", ", sc.pages().names()));
                plugin.scheduler().runForEntity(target, () -> {
                    if (page.equalsIgnoreCase("home")) sc.home(target);
                    else sc.open(target, page);
                    sc.tick(target);
                });
                Text.ok(s, "Opened &f" + page + "&7 on &f" + sc.name() + "&7 for &f" + target.getName());
            }
            case "images" -> {
                List<String> files = plugin.screens().images().files();
                Text.send(s, files.isEmpty() ? "No images. Put PNG/JPG files in &fplugins/DreamNFM/images"
                        : "&fImages (" + files.size() + "): &7" + String.join("&8, &7", files));
            }
            case "set" -> {
                need(a, 4, "/screen set <name> <viewrange|touchrange|cursor> <value>");
                Screen sc = screen(a[1]);
                switch (a[2].toLowerCase(Locale.ROOT)) {
                    case "viewrange" -> sc.setViewRange(num(a[3], "Range"));
                    case "touchrange" -> sc.setTouchRange(num(a[3], "Range"));
                    case "cursor" -> sc.setCursor(bool(a[3]));
                    default -> throw new Fail("Use viewrange, touchrange or cursor.");
                }
                plugin.screens().save();
                Text.ok(s, "&f" + sc.name() + "&7: " + a[2].toLowerCase(Locale.ROOT) + " = &f" + a[3]);
            }
            case "icon" -> {
                need(a, 3, "/screen icon <name> <item|off> [scale] [spin deg/s]");
                Screen sc = screen(a[1]);
                if (a[2].equalsIgnoreCase("off") || a[2].equalsIgnoreCase("none")) {
                    sc.setIcon(null, sc.iconScale(), sc.iconSpin());
                    plugin.screens().save();
                    Text.ok(s, "Icon above &f" + sc.name() + "&7 removed.");
                    return;
                }
                org.bukkit.Material m = org.bukkit.Material.matchMaterial(a[2]);
                if (m == null || !m.isItem() || m.isAir()) throw new Fail("Unknown item '" + a[2] + "'. Example: red_bed");
                float scale = a.length > 3 ? (float) num(a[3], "Scale") : sc.iconScale();
                double spin = a.length > 4 ? num(a[4], "Spin") : sc.iconSpin();
                sc.setIcon(m, scale, spin);
                plugin.screens().save();
                Text.ok(s, "&f" + m.name().toLowerCase(Locale.ROOT) + "&7 now spins above &f" + sc.name() + "&7 (scale " + scale + ", " + spin + " deg/s)");
            }
            case "design", "template" -> {
                need(a, 3, "/screen design <screen> <design>   (/screen designs = list)");
                Screen sc = screen(a[1]);
                if (plugin.screens().designs().file(a[2]) == null)
                    throw new Fail("No design '" + a[2] + "'. Designs: " + String.join(", ", plugin.screens().designs().names()));
                try {
                    plugin.screens().designs().apply(sc, a[2]);
                } catch (java.io.IOException e) {
                    throw new Fail("Could not apply it: " + e.getMessage());
                }
                Text.ok(s, "Design &f" + a[2] + "&7 is now on &f" + sc.name() + "&7. Old page saved as &fscreens/" + sc.name() + ".yml.bak");
            }
            case "designs", "templates" -> {
                List<String> names = plugin.screens().designs().names();
                Text.send(s, names.isEmpty() ? "No designs in plugins/DreamNFM/designs" : "&fDesigns (" + names.size() + "): &7" + String.join("&8, &7", names));
                Text.raw(s, "&7 Use: &f/screen design <screen> <design> &7or &f/screen create <name> <w> <h> <design>");
            }
            case "respawn" -> {
                need(a, 2, "/screen respawn <name>");
                screen(a[1]).respawnFrames();
                Text.ok(s, "Frames of &f" + a[1] + "&7 will be placed again.");
            }
            default -> help(s);
        }
    }

    private void create(CommandSender s, String[] a) {
        need(a, 4, "/screen create <name> <width> <height> [design]  (look at the block for the TOP-LEFT corner)");
        Player p = player(s);
        String name = a[1];
        if (!name.matches("[A-Za-z0-9_\\-]{1,32}")) throw new Fail("Names may use letters, numbers, _ and -.");
        if (plugin.screens().get(name) != null) throw new Fail("That screen already exists.");
        int w = integer(a[2], "Width"), h = integer(a[3], "Height");
        if (w < 1 || h < 1 || w > ScreenManager.MAX_SIZE || h > ScreenManager.MAX_SIZE)
            throw new Fail("Width and height must be 1-" + ScreenManager.MAX_SIZE + " (each map is one block).");
        Block target = p.getTargetBlockExact(12);
        BlockFace face = p.getTargetBlockFace(12);
        if (target == null || face == null) throw new Fail("Look at a wall (within 12 blocks) - that block becomes the top-left corner.");
        if (!Geometry.validFacing(face)) throw new Fail("Look at the side of a block, not its top or bottom.");
        Block start = target.getRelative(face);
        Geometry g = new Geometry(start.getX(), start.getY(), start.getZ(), face, w, h);
        World world = p.getWorld();
        for (int c = 0; c < w; c++) {
            for (int r = 0; r < h; r++) {
                Block b = world.getBlockAt(g.cellBlockX(c), g.cellBlockY(r), g.cellBlockZ(c));
                if (b.getType().isSolid())
                    throw new Fail("The screen would go through " + b.getType().name().toLowerCase(Locale.ROOT) + " at " + b.getX() + " " + b.getY() + " " + b.getZ()
                            + ". Clear space in front of the wall (it grows right and down).");
            }
        }
        Screen other = plugin.screens().overlapping(world.getName(), g);
        if (other != null) throw new Fail("That overlaps screen '" + other.name() + "'.");
        String design = a.length > 4 ? a[4] : null;
        if (design != null && plugin.screens().designs().file(design) == null)
            throw new Fail("No design '" + design + "'. Designs: " + String.join(", ", plugin.screens().designs().names()));
        Screen sc = plugin.screens().create(name, world.getName(), g, design);
        Text.ok(s, "Screen &f" + sc.name() + "&7 created (" + w + "x" + h + "). Edit &fscreens/" + sc.name() + ".yml&7, then &f/screen reload " + sc.name());
    }

    private void help(CommandSender s) {
        Text.raw(s, "&#7AA2FF&l/screen &7- map touchscreens");
        Text.raw(s, "&f create <name> <w> <h> [design] &8- &7look at the wall block for the top-left corner");
        Text.raw(s, "&f designs &8| &fdesign <screen> <design> &8- &7ready-made pages (homes, duels, stats...)");
        Text.raw(s, "&f delete &8| &flist &8| &finfo &8| &ftp &8| &freload [name] &8| &fimages &8| &frespawn <name>");
        Text.raw(s, "&f page <screen> <page|back|home> [player] &8| &fset <name> viewrange|touchrange|cursor <value>");
        Text.raw(s, "&f icon <name> <item|off> [scale] [spin] &8- &7spinning item above the screen");
        Text.raw(s, "&7Pages: &fplugins/DreamNFM/screens/<name>.yml &7Images: &fplugins/DreamNFM/images/");
    }

    @Override
    protected List<String> tab(CommandSender s, String[] a) {
        if (a.length == 1) {
            List<String> out = new ArrayList<>(SUBS);
            out.add("help");
            return out;
        }
        String sub = a[0].toLowerCase(Locale.ROOT);
        if (a.length == 2) return sub.equals("create") || sub.equals("list") || sub.equals("images") || sub.equals("designs") ? List.of() : plugin.screens().names();
        if (a.length == 3) {
            if (sub.equals("create")) return List.of("1", "2", "3", "4", "5");
            if (sub.equals("set")) return List.of("viewrange", "touchrange", "cursor");
            if (sub.equals("design") || sub.equals("template")) return plugin.screens().designs().names();
            if (sub.equals("icon")) {
                List<String> out = new ArrayList<>(List.of("off", "red_bed", "compass", "diamond_sword", "ender_pearl", "nether_star"));
                for (org.bukkit.Material m : org.bukkit.Material.values()) if (m.isItem() && !m.isLegacy() && out.size() < 300) out.add(m.name().toLowerCase(Locale.ROOT));
                return out;
            }
            if (sub.equals("page")) {
                Screen sc = plugin.screens().get(a[1]);
                List<String> out = new ArrayList<>(sc == null ? List.of() : sc.pages().names());
                out.add("back");
                out.add("home");
                return out;
            }
        }
        if (a.length == 4) {
            if (sub.equals("create")) return List.of("1", "2", "3", "4");
            if (sub.equals("icon")) return List.of("0.8", "1.2", "1.6", "2");
        }
        if (a.length == 5) {
            if (sub.equals("create")) return plugin.screens().designs().names();
            if (sub.equals("page")) return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
            if (sub.equals("set")) return a[2].equalsIgnoreCase("cursor") ? List.of("true", "false") : List.of("8", "16", "48");
        }
        return List.of();
    }
}
