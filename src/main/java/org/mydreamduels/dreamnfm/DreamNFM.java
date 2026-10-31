package org.mydreamduels.dreamnfm;

import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.mydreamduels.dreamnfm.command.MainCommand;
import org.mydreamduels.dreamnfm.hologram.HologramCommand;
import org.mydreamduels.dreamnfm.hologram.HologramManager;
import org.mydreamduels.dreamnfm.npc.NpcCommand;
import org.mydreamduels.dreamnfm.npc.NpcManager;
import org.mydreamduels.dreamnfm.platform.PaperSchedulerBridge;
import org.mydreamduels.dreamnfm.platform.PlatformSupport;
import org.mydreamduels.dreamnfm.platform.SchedulerBridge;
import org.mydreamduels.dreamnfm.platform.SpigotSchedulerBridge;
import org.mydreamduels.dreamnfm.screen.ScreenCommand;
import org.mydreamduels.dreamnfm.screen.ScreenManager;
import org.mydreamduels.dreamnfm.util.IoQueue;
import org.mydreamduels.dreamnfm.util.Placeholders;
import org.mydreamduels.dreamnfm.util.Text;
import org.mydreamduels.dreamnfm.view.Entities;
import org.mydreamduels.dreamnfm.view.ViewTracker;

/**
 * DreamNFM - holograms (FancyHolograms-style + spinning icons, proximity
 * pop-up panels and live status colours), NPCs on the vanilla Mannequin
 * entity (FancyNpcs-style) and clickable map touchscreens with custom
 * images. Paper and Folia.
 */
public class DreamNFM extends JavaPlugin {

    private SchedulerBridge scheduler;
    private IoQueue io;
    private ViewTracker tracker;
    private HologramManager holograms;
    private NpcManager npcs;
    private ScreenManager screens;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();
        Text.setPrefix(getConfig().getString("prefix", "&#7AA2FF&lNFM &8» &7"));

        scheduler = PlatformSupport.hasUnifiedSchedulers() ? new PaperSchedulerBridge(this) : new SpigotSchedulerBridge(this);
        io = new IoQueue(getLogger());
        Entities.init(this);
        tracker = new ViewTracker(this);
        holograms = new HologramManager(this);
        npcs = new NpcManager(this);
        screens = new ScreenManager(this);

        var pm = getServer().getPluginManager();
        pm.registerEvents(tracker, this);
        pm.registerEvents(holograms, this);
        pm.registerEvents(screens, this);
        boolean npcSupport = NpcManager.supported();
        if (npcSupport) pm.registerEvents(npcs, this);
        getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");

        holograms.load();
        if (npcSupport) npcs.load();
        else getLogger().warning("This server has no Mannequin entity (needs 1.21.9+) - NPCs are disabled.");
        screens.load();

        bind("dreamnfm", new MainCommand(this));
        bind("holo", new HologramCommand(this));
        bind("npc", new NpcCommand(this));
        bind("screen", new ScreenCommand(this));

        for (Player p : getServer().getOnlinePlayers()) tracker.start(p);
        getLogger().info("Enabled on " + PlatformSupport.name() + " - " + holograms.names().size() + " holograms, "
                + npcs.names().size() + " NPCs, " + screens.names().size() + " screens"
                + (Placeholders.hasPapi() ? ", PlaceholderAPI hooked." : "."));
    }

    private void bind(String name, TabExecutor executor) {
        PluginCommand c = getCommand(name);
        if (c == null) {
            getLogger().warning("Command /" + name + " missing from plugin.yml");
            return;
        }
        c.setExecutor(executor);
        c.setTabCompleter(executor);
    }

    @Override
    public void onDisable() {
        if (tracker != null) tracker.shutdown();
        if (screens != null) screens.shutdown();
        if (npcs != null) npcs.shutdown();
        if (holograms != null) holograms.shutdown();
        if (scheduler != null) scheduler.cancelAll();
        if (io != null) io.shutdown();
    }

    /** /dreamnfm reload: config, placeholders hook, screen pages and images. */
    public void reload() {
        reloadConfig();
        Text.setPrefix(getConfig().getString("prefix", "&#7AA2FF&lNFM &8» &7"));
        Placeholders.reset();
        screens.reloadAll();
        for (Player p : getServer().getOnlinePlayers()) scheduler.runForEntity(p, () -> tracker.start(p));
    }

    public SchedulerBridge scheduler() {
        return scheduler;
    }

    public IoQueue io() {
        return io;
    }

    public ViewTracker tracker() {
        return tracker;
    }

    public HologramManager holograms() {
        return holograms;
    }

    public NpcManager npcs() {
        return npcs;
    }

    public ScreenManager screens() {
        return screens;
    }
}
