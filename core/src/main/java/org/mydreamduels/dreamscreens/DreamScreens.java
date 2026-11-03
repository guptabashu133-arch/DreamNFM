package org.mydreamduels.dreamscreens;

import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.mydreamduels.dreamscreens.command.MainCommand;
import org.mydreamduels.dreamscreens.hologram.HologramCommand;
import org.mydreamduels.dreamscreens.hologram.HologramManager;
import org.mydreamduels.dreamscreens.platform.PaperSchedulerBridge;
import org.mydreamduels.dreamscreens.platform.PlatformSupport;
import org.mydreamduels.dreamscreens.platform.SchedulerBridge;
import org.mydreamduels.dreamscreens.platform.SpigotSchedulerBridge;
import org.mydreamduels.dreamscreens.screen.ScreenCommand;
import org.mydreamduels.dreamscreens.screen.ScreenManager;
import org.mydreamduels.dreamscreens.util.IoQueue;
import org.mydreamduels.dreamscreens.util.Placeholders;
import org.mydreamduels.dreamscreens.util.Text;
import org.mydreamduels.dreamscreens.view.Entities;
import org.mydreamduels.dreamscreens.view.ViewTracker;

/**
 * DreamScreens - holograms (FancyHolograms-style + spinning icons, proximity
 * pop-up panels and live status colours), NPCs on the vanilla Mannequin
 * entity (FancyNpcs-style) and clickable map touchscreens with custom
 * images. Paper and Folia.
 */
public class DreamScreens extends JavaPlugin {

    private SchedulerBridge scheduler;
    private IoQueue io;
    private ViewTracker tracker;
    private HologramManager holograms;
    private NpcService npcs = NpcService.NONE;
    private ScreenManager screens;

    @Override
    public void onEnable() {
        migrateOldFolder();
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();
        Text.setPrefix(getConfig().getString("prefix", "&#7AA2FF&lScreens &8» &7"));

        scheduler = PlatformSupport.hasUnifiedSchedulers() ? new PaperSchedulerBridge(this) : new SpigotSchedulerBridge(this);
        io = new IoQueue(getLogger());
        Entities.init(this);
        tracker = new ViewTracker(this);
        holograms = new HologramManager(this);
        screens = new ScreenManager(this);

        var pm = getServer().getPluginManager();
        pm.registerEvents(tracker, this);
        pm.registerEvents(holograms, this);
        pm.registerEvents(screens, this);
        screens.registerPaperEvents();
        getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");

        holograms.load();
        npcs = NpcService.create(this);
        if (npcs.available()) npcs.start();
        else {
            getLogger().info("NPCs need Paper 1.21.9+ (Mannequin entity) - screens and holograms work, /npc is off.");
            bind("npc", new MainCommand.NpcUnavailable(this));
        }
        screens.load();

        bind("dreamscreens", new MainCommand(this));
        bind("holo", new HologramCommand(this));
        bind("screen", new ScreenCommand(this));
        org.mydreamduels.dreamscreens.screen.ScreenEditCommand edit = new org.mydreamduels.dreamscreens.screen.ScreenEditCommand(this);
        screens.setEditor(edit);
        bind("screenedit", edit);

        for (Player p : getServer().getOnlinePlayers()) tracker.start(p);
        getLogger().info("Enabled on " + PlatformSupport.name() + " - " + holograms.names().size() + " holograms, "
                + npcs.names().size() + " NPCs, " + screens.names().size() + " screens"
                + (Placeholders.hasPapi() ? ", PlaceholderAPI hooked." : "."));
    }

    /** The plugin used to be called DreamNFM: carry its screens, holograms, NPCs and images over once. */
    private void migrateOldFolder() {
        java.io.File old = new java.io.File(getDataFolder().getParentFile(), "DreamNFM");
        if (getDataFolder().exists() || !old.isDirectory()) return;
        try (var walk = java.nio.file.Files.walk(old.toPath())) {
            for (java.nio.file.Path src : (Iterable<java.nio.file.Path>) walk::iterator) {
                java.nio.file.Path dst = getDataFolder().toPath().resolve(old.toPath().relativize(src));
                if (java.nio.file.Files.isDirectory(src)) java.nio.file.Files.createDirectories(dst);
                else java.nio.file.Files.copy(src, dst);
            }
            getLogger().info("Copied your old plugins/DreamNFM folder to plugins/DreamScreens (the old one can be deleted).");
        } catch (java.io.IOException e) {
            getLogger().warning("Could not copy plugins/DreamNFM: " + e.getMessage());
        }
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

    /** /dreamscreens reload: config, placeholders hook, screen pages and images. */
    public void reload() {
        reloadConfig();
        Text.setPrefix(getConfig().getString("prefix", "&#7AA2FF&lScreens &8» &7"));
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

    public NpcService npcs() {
        return npcs;
    }

    public ScreenManager screens() {
        return screens;
    }
}
