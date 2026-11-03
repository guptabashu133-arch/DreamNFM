package org.mydreamduels.dreamscreens.command;

import org.bukkit.command.CommandSender;
import org.mydreamduels.dreamscreens.DreamScreens;
import org.mydreamduels.dreamscreens.platform.PlatformSupport;
import org.mydreamduels.dreamscreens.util.Placeholders;
import org.mydreamduels.dreamscreens.util.Text;

import java.util.List;
import java.util.Locale;

/** /dreamscreens - overview, reload, version. */
public final class MainCommand extends Cmd {

    public MainCommand(DreamScreens plugin) {
        super(plugin, "dreamscreens.admin");
    }

    @Override
    protected void run(CommandSender s, String[] a) {
        String sub = a.length == 0 ? "help" : a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> {
                plugin.reload();
                Text.ok(s, "Config, placeholders, screen pages and images reloaded.");
            }
            case "version", "info" -> {
                Text.raw(s, "&#7AA2FF&lDreamScreens &f" + plugin.getDescription().getVersion() + " &7on &f" + PlatformSupport.name());
                Text.raw(s, "&7 Holograms &f" + plugin.holograms().names().size() + "&7, NPCs &f" + plugin.npcs().names().size()
                        + (plugin.npcs().available() ? "" : " &c(needs 1.21.9+)") + "&7, screens &f" + plugin.screens().names().size());
                Text.raw(s, "&7 PlaceholderAPI: " + (Placeholders.hasPapi() ? "&ahooked" : "&8not installed"));
            }
            default -> {
                Text.raw(s, "&#7AA2FF&lDreamScreens &7- holograms, NPCs and touchscreens");
                Text.raw(s, "&f /holo help &8- &7holograms (spin, bob, proximity pop-up, live status)");
                Text.raw(s, "&f /npc help &8- &7NPCs (skins, look-at, spinning icon, pop-up panel)");
                Text.raw(s, "&f /screen help &8- &7map touchscreens with images and buttons");
                Text.raw(s, "&f /dreamscreens reload &8| &fversion");
            }
        }
    }

    @Override
    protected List<String> tab(CommandSender s, String[] a) {
        return a.length == 1 ? List.of("reload", "version", "help") : List.of();
    }

    /** /npc on servers older than 1.21.9. */
    public static final class NpcUnavailable extends Cmd {
        public NpcUnavailable(DreamScreens plugin) {
            super(plugin, "dreamscreens.npc");
        }

        @Override
        protected void run(CommandSender s, String[] a) {
            Text.err(s, "NPCs need Minecraft 1.21.9 or newer (they use the vanilla Mannequin). Screens and holograms work on this server.");
        }

        @Override
        protected List<String> tab(CommandSender s, String[] a) {
            return List.of();
        }
    }
}
