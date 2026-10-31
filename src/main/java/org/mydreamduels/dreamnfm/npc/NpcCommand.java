package org.mydreamduels.dreamnfm.npc;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.mydreamduels.dreamnfm.DreamNFM;
import org.mydreamduels.dreamnfm.action.Action;
import org.mydreamduels.dreamnfm.command.Cmd;
import org.mydreamduels.dreamnfm.platform.PlatformSupport;
import org.mydreamduels.dreamnfm.util.Colors;
import org.mydreamduels.dreamnfm.util.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** /npc - FancyNpcs-style management on vanilla Mannequins. */
public final class NpcCommand extends Cmd {

    private static final List<String> SUBS = List.of("create", "delete", "list", "info", "movehere", "tp", "name", "skin", "lookat",
            "glow", "scale", "pose", "equip", "action", "icon", "panel", "viewrange");
    private static final List<String> POSES = List.of("standing", "sneaking", "sleeping", "swimming", "sitting", "fall_flying", "spin_attack");

    public NpcCommand(DreamNFM plugin) {
        super(plugin, "dreamnfm.npc");
    }

    private Npc npc(String name) {
        Npc n = plugin.npcs().get(name);
        if (n == null) throw new Fail("No NPC called '" + name + "'.");
        return n;
    }

    private void changed(CommandSender s, Npc n, String what) {
        n.respawn();
        plugin.npcs().save();
        Text.ok(s, "&f" + n.name() + "&7: " + what);
    }

    @Override
    protected void run(CommandSender s, String[] a) {
        if (!NpcManager.supported()) throw new Fail("NPCs need Minecraft 1.21.9+ (Mannequin entity) on Paper/Folia.");
        if (a.length == 0 || a[0].equalsIgnoreCase("help")) {
            help(s);
            return;
        }
        String sub = a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "create" -> {
                need(a, 2, "/npc create <name> [skin player]");
                if (!a[1].matches("[A-Za-z0-9_\\-]{1,32}")) throw new Fail("Names may use letters, numbers, _ and -.");
                if (plugin.npcs().get(a[1]) != null) throw new Fail("That NPC already exists.");
                Player p = player(s);
                Npc n = new Npc(plugin, a[1]);
                Location l = p.getLocation();
                n.setLocation(l);
                n.setDisplayName("&#C084FC&l" + a[1]);
                String skin = a.length > 2 ? a[2] : p.getName();
                n.setSkin(skin, null, null);
                plugin.npcs().add(n);
                Text.ok(s, "NPC &f" + n.name() + "&7 created. &8Fetching skin of " + skin + "...");
                fetchSkin(s, n, skin);
            }
            case "delete", "remove" -> {
                need(a, 2, "/npc delete <name>");
                if (!plugin.npcs().remove(npc(a[1]).name())) throw new Fail("No such NPC.");
                Text.ok(s, "NPC &f" + a[1] + "&7 deleted.");
            }
            case "list" -> {
                List<String> names = plugin.npcs().names();
                Text.send(s, names.isEmpty() ? "&7No NPCs yet." : "&7NPCs (" + names.size() + "): &f" + String.join("&7, &f", names));
            }
            case "info" -> {
                need(a, 2, "/npc info <name>");
                Npc n = npc(a[1]);
                Text.raw(s, "&#C084FC&l" + n.name() + " &8skin " + n.skinName() + (n.hasSkinTexture() ? "" : " (no texture yet)"));
                Text.raw(s, String.format(Locale.ROOT, "&7At &f%s %.1f %.1f %.1f &7look &f%s &7glow &f%s &7scale &f%.2f &7pose &f%s",
                        n.worldName(), n.x(), n.y(), n.z(), n.lookAt(), n.glow(), n.scale(), n.pose().toLowerCase(Locale.ROOT)));
                Text.raw(s, "&7Name: &r" + n.displayName() + " &7icon: &f" + (n.icon() == null ? "-" : n.icon().getType().name().toLowerCase(Locale.ROOT)));
                for (int i = 0; i < n.panel().size(); i++) Text.raw(s, "&8panel " + (i + 1) + ". &r" + n.panel().get(i));
                for (int i = 0; i < n.actions().actions().size(); i++) Text.raw(s, "&8action " + (i + 1) + ": &f" + n.actions().actions().get(i).serialize());
            }
            case "movehere", "move" -> {
                need(a, 2, "/npc movehere <name>");
                Npc n = npc(a[1]);
                n.setLocation(player(s).getLocation());
                changed(s, n, "moved here.");
            }
            case "tp", "teleport" -> {
                need(a, 2, "/npc tp <name>");
                Location l = npc(a[1]).location();
                if (l == null) throw new Fail("Its world isn't loaded.");
                Player p = player(s);
                if (PlatformSupport.hasUnifiedSchedulers()) p.teleportAsync(l);
                else p.teleport(l);
            }
            case "name" -> {
                need(a, 3, "/npc name <name> <text|off>  (\\n for a second line)");
                Npc n = npc(a[1]);
                String v = join(a, 2);
                n.setDisplayName(v.equalsIgnoreCase("off") ? "" : v.replace("\\n", "\n"));
                changed(s, n, "name updated.");
            }
            case "skin" -> {
                need(a, 3, "/npc skin <name> <player>  |  /npc skin <name> texture <value> [signature]");
                Npc n = npc(a[1]);
                if (a[2].equalsIgnoreCase("texture")) {
                    need(a, 4, "/npc skin <name> texture <value> [signature]");
                    n.setSkin(null, a[3], a.length > 4 ? a[4] : null);
                    changed(s, n, "skin texture set.");
                    return;
                }
                n.setSkin(a[2], null, null);
                Text.send(s, "&7Fetching skin of &f" + a[2] + "&7...");
                fetchSkin(s, n, a[2]);
            }
            case "lookat" -> {
                need(a, 3, "/npc lookat <name> <true|false> [radius]");
                Npc n = npc(a[1]);
                n.setLookAt(bool(a[2]));
                if (a.length > 3) n.setLookRadius(num(a[3], "Radius"));
                changed(s, n, "look at players " + a[2] + ".");
            }
            case "glow" -> {
                need(a, 3, "/npc glow <name> <color|off>");
                Npc n = npc(a[1]);
                n.setGlow(a[2].equalsIgnoreCase("off") ? null : a[2].toLowerCase(Locale.ROOT));
                changed(s, n, "glow " + a[2] + (PlatformSupport.isFolia() && n.glow() != null ? " &8(Folia: always white)" : "") + ".");
            }
            case "scale" -> {
                need(a, 3, "/npc scale <name> <0.1-10>");
                Npc n = npc(a[1]);
                n.setScale(num(a[2], "Scale"));
                changed(s, n, "scale " + a[2] + ".");
            }
            case "pose" -> {
                need(a, 3, "/npc pose <name> <" + String.join("|", POSES) + ">");
                Npc n = npc(a[1]);
                n.setPose(a[2].toUpperCase(Locale.ROOT));
                changed(s, n, "pose " + a[2] + ".");
            }
            case "equip" -> {
                need(a, 3, "/npc equip <name> <hand|offhand|head|chest|legs|feet> [clear]");
                Npc n = npc(a[1]);
                EquipmentSlot slot = switch (a[2].toLowerCase(Locale.ROOT)) {
                    case "hand", "mainhand" -> EquipmentSlot.HAND;
                    case "offhand" -> EquipmentSlot.OFF_HAND;
                    case "head", "helmet" -> EquipmentSlot.HEAD;
                    case "chest", "chestplate" -> EquipmentSlot.CHEST;
                    case "legs", "leggings" -> EquipmentSlot.LEGS;
                    case "feet", "boots" -> EquipmentSlot.FEET;
                    default -> throw new Fail("Slot must be hand, offhand, head, chest, legs or feet.");
                };
                if (a.length > 3 && a[3].equalsIgnoreCase("clear")) n.equipment().remove(slot);
                else {
                    ItemStack hand = player(s).getInventory().getItemInMainHand();
                    if (hand.getType().isAir()) throw new Fail("Hold the item to give it.");
                    n.equipment().put(slot, hand.clone());
                }
                changed(s, n, "equipment updated.");
            }
            case "action" -> action(s, a);
            case "icon" -> {
                need(a, 3, "/npc icon <name> <hand|material|off> [spin deg/s]");
                Npc n = npc(a[1]);
                if (a[2].equalsIgnoreCase("off")) n.setIcon(null);
                else if (a[2].equalsIgnoreCase("hand")) {
                    ItemStack hand = player(s).getInventory().getItemInMainHand();
                    if (hand.getType().isAir()) throw new Fail("Hold the item.");
                    n.setIcon(hand);
                } else {
                    Material m = Material.matchMaterial(a[2]);
                    if (m == null || !m.isItem()) throw new Fail("Unknown item '" + a[2] + "'.");
                    n.setIcon(new ItemStack(m));
                }
                if (a.length > 3) n.setIconSpin(num(a[3], "Spin"));
                changed(s, n, "icon updated.");
            }
            case "panel" -> panel(s, a);
            case "viewrange" -> {
                need(a, 3, "/npc viewrange <name> <blocks>");
                Npc n = npc(a[1]);
                n.setViewRange(num(a[2], "Range"));
                changed(s, n, "view range " + a[2] + ".");
            }
            default -> throw new Fail("Unknown option. /npc help");
        }
    }

    private void fetchSkin(CommandSender s, Npc n, String player) {
        plugin.npcs().fetchSkin(player).thenAccept(tex -> {
            if (tex == null) {
                Text.err(s, "Couldn't fetch the skin of " + player + " - the NPC uses the name only (the client may still load it).");
                plugin.scheduler().runGlobal(n::respawn);
                return;
            }
            n.setSkin(player, tex[0], tex[1]);
            plugin.npcs().save();
            plugin.scheduler().runGlobal(n::respawn);
            Text.ok(s, "&f" + n.name() + "&7 now wears &f" + player + "&7's skin.");
        });
    }

    private void action(CommandSender s, String[] a) {
        need(a, 3, "/npc action <name> <add|remove|list|cooldown> ...");
        Npc n = npc(a[1]);
        switch (a[2].toLowerCase(Locale.ROOT)) {
            case "add" -> {
                need(a, 4, "/npc action <name> add <type:value>");
                n.actions().actions().add(Action.parse(join(a, 3)));
                plugin.npcs().save();
                Text.ok(s, "Action added.");
            }
            case "remove" -> {
                need(a, 4, "/npc action <name> remove <number>");
                int i = integer(a[3], "Number") - 1;
                if (i < 0 || i >= n.actions().actions().size()) throw new Fail("No action #" + a[3] + ".");
                n.actions().actions().remove(i);
                plugin.npcs().save();
                Text.ok(s, "Action removed.");
            }
            case "list" -> {
                if (n.actions().isEmpty()) Text.send(s, "&7No actions.");
                for (int i = 0; i < n.actions().actions().size(); i++) Text.raw(s, "&8" + (i + 1) + ". &f" + n.actions().actions().get(i).serialize());
            }
            case "cooldown" -> {
                need(a, 4, "/npc action <name> cooldown <ms>");
                n.actions().setCooldownMs(integer(a[3], "Cooldown"));
                plugin.npcs().save();
                Text.ok(s, "Click cooldown " + a[3] + "ms.");
            }
            default -> throw new Fail("Use add, remove, list or cooldown.");
        }
    }

    private void panel(CommandSender s, String[] a) {
        need(a, 3, "/npc panel <name> <add|set|remove|clear|radius|background> ...");
        Npc n = npc(a[1]);
        switch (a[2].toLowerCase(Locale.ROOT)) {
            case "add" -> {
                need(a, 4, "/npc panel <name> add <text>");
                n.panel().add(join(a, 3));
            }
            case "set" -> {
                need(a, 5, "/npc panel <name> set <line> <text>");
                int i = integer(a[3], "Line") - 1;
                if (i < 0 || i >= n.panel().size()) throw new Fail("There is no line " + a[3] + ".");
                n.panel().set(i, join(a, 4));
            }
            case "remove" -> {
                need(a, 4, "/npc panel <name> remove <line>");
                int i = integer(a[3], "Line") - 1;
                if (i < 0 || i >= n.panel().size()) throw new Fail("There is no line " + a[3] + ".");
                n.panel().remove(i);
            }
            case "clear" -> n.panel().clear();
            case "radius" -> {
                need(a, 4, "/npc panel <name> radius <blocks>");
                n.setPanelRadius(num(a[3], "Radius"));
            }
            case "background" -> {
                need(a, 4, "/npc panel <name> background <#AARRGGBB|default|transparent>");
                if (!a[3].equalsIgnoreCase("default") && !a[3].equalsIgnoreCase("transparent") && Colors.argb(a[3]) == null) {
                    throw new Fail("Use #AARRGGBB, default or transparent.");
                }
                n.setPanelBackground(a[3]);
            }
            default -> throw new Fail("Use add, set, remove, clear, radius or background.");
        }
        changed(s, n, "panel updated (" + n.panel().size() + " lines).");
    }

    private void help(CommandSender s) {
        Text.raw(s, "&#C084FC&l/npc &7- vanilla Mannequin NPCs");
        Text.raw(s, "&f create <name> [skin] &8| &fdelete &8| &flist &8| &finfo &8| &fmovehere &8| &ftp");
        Text.raw(s, "&f name <name> <text|off> &8| &fskin <name> <player> &8| &flookat <name> <bool> [radius] &8| &fglow <name> <color|off>");
        Text.raw(s, "&f scale &8| &fpose &8| &fequip <name> <slot> [clear] &8| &fviewrange");
        Text.raw(s, "&f icon <name> <hand|material|off> [spin] &8- spinning item above the head");
        Text.raw(s, "&f panel <name> add|set|remove|clear|radius|background &8- info panel that pops open when you walk up");
        Text.raw(s, "&f action <name> add|remove|list|cooldown &8- e.g. player:duel, console:..., message:..., server:lobby");
    }

    @Override
    protected List<String> tab(CommandSender s, String[] a) {
        if (a.length == 1) {
            List<String> out = new ArrayList<>(SUBS);
            out.add("help");
            return out;
        }
        String sub = a[0].toLowerCase(Locale.ROOT);
        if (a.length == 2) return sub.equals("create") || sub.equals("list") ? List.of() : plugin.npcs().names();
        if (a.length == 3) {
            return switch (sub) {
                case "lookat" -> List.of("true", "false");
                case "glow" -> List.of("off", "white", "green", "aqua", "gold", "light_purple", "red", "yellow", "blue");
                case "pose" -> POSES;
                case "equip" -> List.of("hand", "offhand", "head", "chest", "legs", "feet");
                case "action" -> List.of("add", "remove", "list", "cooldown");
                case "icon" -> List.of("hand", "off", "diamond_sword", "red_bed", "emerald", "nether_star");
                case "panel" -> List.of("add", "set", "remove", "clear", "radius", "background");
                case "skin" -> {
                    List<String> out = new ArrayList<>();
                    for (Player p : plugin.getServer().getOnlinePlayers()) out.add(p.getName());
                    out.add("texture");
                    yield out;
                }
                case "scale" -> List.of("0.5", "1", "1.5", "2");
                default -> List.of();
            };
        }
        if (a.length == 4 && sub.equals("action") && a[2].equalsIgnoreCase("add")) return Action.EXAMPLES;
        return List.of();
    }
}
