package org.mydreamduels.dreamnfm.command;

import org.bukkit.command.CommandSender;
import org.mydreamduels.dreamnfm.DreamNFM;
import org.mydreamduels.dreamnfm.npc.NpcManager;
import org.mydreamduels.dreamnfm.platform.PlatformSupport;
import org.mydreamduels.dreamnfm.util.Placeholders;
import org.mydreamduels.dreamnfm.util.Text;

import java.util.List;
import java.util.Locale;

/** /dreamnfm - overview, reload, version. */
public final class MainCommand extends Cmd {

    public MainCommand(DreamNFM plugin) {
        super(plugin, "dreamnfm.admin");
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
                Text.raw(s, "&#7AA2FF&lDreamNFM &f" + plugin.getPluginMeta().getVersion() + " &7on &f" + PlatformSupport.name());
                Text.raw(s, "&7 Holograms &f" + plugin.holograms().names().size() + "&7, NPCs &f" + plugin.npcs().names().size()
                        + (NpcManager.supported() ? "" : " &c(no Mannequin on this server)") + "&7, screens &f" + plugin.screens().names().size());
                Text.raw(s, "&7 PlaceholderAPI: " + (Placeholders.hasPapi() ? "&ahooked" : "&8not installed"));
            }
            default -> {
                Text.raw(s, "&#7AA2FF&lDreamNFM &7- holograms, NPCs and touchscreens");
                Text.raw(s, "&f /holo help &8- &7holograms (spin, bob, proximity pop-up, live status)");
                Text.raw(s, "&f /npc help &8- &7NPCs (skins, look-at, spinning icon, pop-up panel)");
                Text.raw(s, "&f /screen help &8- &7map touchscreens with images and buttons");
                Text.raw(s, "&f /dreamnfm reload &8| &fversion");
            }
        }
    }

    @Override
    protected List<String> tab(CommandSender s, String[] a) {
        return a.length == 1 ? List.of("reload", "version", "help") : List.of();
    }
}
