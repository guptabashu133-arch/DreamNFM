package org.mydreamduels.dreamscreens.action;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.mydreamduels.dreamscreens.DreamScreens;
import org.mydreamduels.dreamscreens.platform.PlatformSupport;
import org.mydreamduels.dreamscreens.util.Placeholders;
import org.mydreamduels.dreamscreens.util.Text;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * What happens on click. Written as "type:value":
 *   message:&aHello {player}      player:spawn         console:give {player} diamond 1
 *   sound:entity.player.levelup[:volume:pitch]         teleport:world,0,100,0[,yaw,pitch]
 *   server:lobby (BungeeCord/Velocity)                 page:shop (screens: open a page)
 *   close (screens: back to the first page)
 */
public record Action(Type type, String value) {

    public enum Type { MESSAGE, PLAYER, CONSOLE, SOUND, TELEPORT, SERVER, PAGE, CLOSE }

    public static final List<String> EXAMPLES = List.of("message:", "player:", "console:", "sound:", "teleport:", "server:", "page:", "close");

    public static Action parse(String text) {
        String t = text.trim();
        int c = t.indexOf(':');
        String head = (c < 0 ? t : t.substring(0, c)).toLowerCase(Locale.ROOT);
        String value = c < 0 ? "" : t.substring(c + 1).trim();
        Type type = switch (head) {
            case "message", "msg", "tell" -> Type.MESSAGE;
            case "player", "cmd", "command" -> Type.PLAYER;
            case "console", "server-command" -> Type.CONSOLE;
            case "sound" -> Type.SOUND;
            case "teleport", "tp" -> Type.TELEPORT;
            case "server", "connect" -> Type.SERVER;
            case "page" -> Type.PAGE;
            case "close", "home" -> Type.CLOSE;
            default -> throw new IllegalArgumentException("Unknown action '" + head + "'. Use: " + String.join(" ", EXAMPLES));
        };
        if (value.isEmpty() && type != Type.CLOSE) throw new IllegalArgumentException("Action '" + head + "' needs a value.");
        return new Action(type, value);
    }

    public String serialize() {
        return type.name().toLowerCase(Locale.ROOT) + (value.isEmpty() ? "" : ":" + value);
    }

    /** Must run on the player's thread. PAGE/CLOSE are handled by screens and ignored here. */
    public void run(DreamScreens plugin, Player p) {
        String v = Placeholders.apply(p, value);
        switch (type) {
            case MESSAGE -> p.sendMessage(Text.color(v));
            case PLAYER -> p.performCommand(v.startsWith("/") ? v.substring(1) : v);
            case CONSOLE -> {
                String cmd = v.startsWith("/") ? v.substring(1) : v;
                plugin.scheduler().runGlobal(() -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd));
            }
            case SOUND -> {
                String[] s = v.split(":");
                try {
                    float vol = s.length > 1 ? Float.parseFloat(s[1]) : 1f, pitch = s.length > 2 ? Float.parseFloat(s[2]) : 1f;
                    p.playSound(p.getLocation(), s[0], vol, pitch);
                } catch (NumberFormatException ignored) {
                }
            }
            case TELEPORT -> {
                String[] s = v.split(",");
                if (s.length < 4) return;
                World w = Bukkit.getWorld(s[0].trim());
                if (w == null) return;
                try {
                    Location to = new Location(w, Double.parseDouble(s[1]), Double.parseDouble(s[2]), Double.parseDouble(s[3]),
                            s.length > 4 ? Float.parseFloat(s[4]) : p.getLocation().getYaw(), s.length > 5 ? Float.parseFloat(s[5]) : p.getLocation().getPitch());
                    if (PlatformSupport.hasUnifiedSchedulers()) p.teleportAsync(to, PlayerTeleportEvent.TeleportCause.PLUGIN);
                    else p.teleport(to);
                } catch (NumberFormatException ignored) {
                }
            }
            case SERVER -> {
                try {
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    DataOutputStream out = new DataOutputStream(bytes);
                    out.writeUTF("Connect");
                    out.writeUTF(v);
                    p.sendPluginMessage(plugin, "BungeeCord", bytes.toByteArray());
                } catch (IOException ignored) {
                }
            }
            case PAGE, CLOSE -> {
            }
        }
    }
}
