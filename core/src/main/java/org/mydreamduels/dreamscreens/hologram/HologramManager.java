package org.mydreamduels.dreamscreens.hologram;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.mydreamduels.dreamscreens.DreamScreens;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** holograms.yml + click routing for hologram hitboxes. Managed holograms (NPC overheads) aren't saved here. */
public final class HologramManager implements Listener {

    private final DreamScreens plugin;
    private final File file;
    private final Map<String, Hologram> holograms = new ConcurrentHashMap<>();
    private final Map<UUID, Hologram> byHitbox = new ConcurrentHashMap<>();

    public HologramManager(DreamScreens plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "holograms.yml");
    }

    public void load() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = y.getConfigurationSection("holograms");
        if (root == null) return;
        for (String name : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(name);
            if (s == null) continue;
            Hologram h = Hologram.load(plugin, name, s);
            holograms.put(name.toLowerCase(Locale.ROOT), h);
            plugin.tracker().register(h);
        }
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Hologram h : holograms.values()) if (!h.managed()) h.save(y.createSection("holograms." + h.name()));
        plugin.io().write(file, y.saveToString());
    }

    public Hologram get(String name) {
        return name == null ? null : holograms.get(name.toLowerCase(Locale.ROOT));
    }

    public Collection<Hologram> all() {
        return holograms.values();
    }

    public List<String> names() {
        List<String> out = new ArrayList<>();
        for (Hologram h : holograms.values()) if (!h.managed()) out.add(h.name());
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    public void add(Hologram h) {
        holograms.put(h.name().toLowerCase(Locale.ROOT), h);
        plugin.tracker().register(h);
        if (!h.managed()) save();
    }

    public boolean remove(String name) {
        Hologram h = holograms.remove(name.toLowerCase(Locale.ROOT));
        if (h == null) return false;
        plugin.tracker().unregister(h);
        h.destroy();
        if (!h.managed()) save();
        return true;
    }

    void registerHitbox(UUID id, Hologram h) {
        byHitbox.put(id, h);
    }

    void unregisterHitbox(UUID id) {
        byHitbox.remove(id);
    }

    public void shutdown() {
        for (Hologram h : holograms.values()) h.destroy();
    }

    private boolean click(Player p, Entity clicked) {
        Hologram h = byHitbox.get(clicked.getUniqueId());
        if (h == null) return false;
        h.actions().click(plugin, p);
        return true;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (click(e.getPlayer(), e.getRightClicked())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onHit(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player p && byHitbox.containsKey(e.getEntity().getUniqueId())) {
            e.setCancelled(true);
            click(p, e.getEntity());
        }
    }
}
