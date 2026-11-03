package org.mydreamduels.dreamscreens.npc;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.mydreamduels.dreamscreens.DreamScreens;
import org.mydreamduels.dreamscreens.view.Entities;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** npcs.yml, skin lookups and click routing. */
public final class NpcManager implements Listener, org.mydreamduels.dreamscreens.NpcService {

    private final DreamScreens plugin;
    private final File file;
    private final Map<String, Npc> npcs = new ConcurrentHashMap<>();

    public NpcManager(DreamScreens plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "npcs.yml");
    }

    public static NpcManager of(DreamScreens plugin) {
        return (NpcManager) plugin.npcs();
    }

    @Override
    public void start() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        org.bukkit.command.PluginCommand c = plugin.getCommand("npc");
        if (c != null) {
            NpcCommand cmd = new NpcCommand(plugin);
            c.setExecutor(cmd);
            c.setTabCompleter(cmd);
        }
        load();
    }

    @Override
    public boolean available() {
        return true;
    }

    public static boolean supported() {
        try {
            Class.forName("org.bukkit.entity.Mannequin");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public void load() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = y.getConfigurationSection("npcs");
        if (root == null) return;
        for (String name : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(name);
            if (s == null) continue;
            Npc n = Npc.load(plugin, name, s);
            npcs.put(name.toLowerCase(Locale.ROOT), n);
            plugin.tracker().register(n);
            n.rebuildOverheads();
        }
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Npc n : npcs.values()) n.save(y.createSection("npcs." + n.name()));
        plugin.io().write(file, y.saveToString());
    }

    public Npc get(String name) {
        return name == null ? null : npcs.get(name.toLowerCase(Locale.ROOT));
    }

    public Collection<Npc> all() {
        return npcs.values();
    }

    @Override
    public List<String> names() {
        List<String> out = new ArrayList<>();
        for (Npc n : npcs.values()) out.add(n.name());
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    public void add(Npc n) {
        npcs.put(n.name().toLowerCase(Locale.ROOT), n);
        plugin.tracker().register(n);
        n.rebuildOverheads();
        save();
    }

    public boolean remove(String name) {
        Npc n = npcs.remove(name.toLowerCase(Locale.ROOT));
        if (n == null) return false;
        plugin.tracker().unregister(n);
        n.destroy();
        save();
        return true;
    }

    @Override
    public void shutdown() {
        for (Npc n : npcs.values()) n.destroy();
    }

    /** Fetches a player's skin off-thread (Mojang lookup); completes with [value, signature] or null. */
    public CompletableFuture<String[]> fetchSkin(String playerName) {
        CompletableFuture<String[]> f = new CompletableFuture<>();
        plugin.scheduler().runAsync(() -> {
            try {
                PlayerProfile profile = Bukkit.createProfile(playerName);
                if (!profile.complete(true)) {
                    f.complete(null);
                    return;
                }
                for (ProfileProperty p : profile.getProperties()) {
                    if (p.getName().equals("textures")) {
                        f.complete(new String[]{p.getValue(), p.getSignature()});
                        return;
                    }
                }
                f.complete(null);
            } catch (Throwable t) {
                f.complete(null);
            }
        });
        return f;
    }

    private Npc npcOf(Entity e) {
        if (!(e instanceof Mannequin)) return null;
        String tag = Entities.tag(e);
        return tag != null && tag.startsWith("npc:") ? get(tag.substring(4)) : null;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEntityEvent e) {
        Npc n = npcOf(e.getRightClicked());
        if (n == null) return;
        e.setCancelled(true);
        if (e.getHand() == EquipmentSlot.HAND) n.actions().click(plugin, e.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDamage(EntityDamageEvent e) {
        Npc n = npcOf(e.getEntity());
        if (n == null) return;
        e.setCancelled(true);
        if (e instanceof EntityDamageByEntityEvent by && by.getDamager() instanceof Player p) n.actions().click(plugin, p);
    }
}
