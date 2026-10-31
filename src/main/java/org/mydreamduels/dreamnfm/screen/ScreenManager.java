package org.mydreamduels.dreamnfm.screen;

import io.papermc.paper.event.player.PlayerItemFrameChangeEvent;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.GlowItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.mydreamduels.dreamnfm.DreamNFM;
import org.mydreamduels.dreamnfm.platform.CancelHandle;
import org.mydreamduels.dreamnfm.view.Entities;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** screens.yml, map ids, images/heads, graph samples, fast cursor tasks and all click/protection events. */
public final class ScreenManager implements Listener {

    public static final int MAX_SIZE = 10;
    private static final int SAMPLES = 60;

    private final DreamNFM plugin;
    private final File file;
    private final Map<String, Screen> screens = new ConcurrentHashMap<>();
    private final ImageStore images;
    private final Heads heads;
    private final Designs designs;
    private final Deque<Integer> freeMaps = new ArrayDeque<>();

    private record Focus(Screen screen, CancelHandle task) {
    }

    private final Map<UUID, Focus> focus = new ConcurrentHashMap<>();
    private final Deque<Double> online = new ArrayDeque<>(), tps = new ArrayDeque<>(), mspt = new ArrayDeque<>();
    private CancelHandle sampler;

    public ScreenManager(DreamNFM plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "screens.yml");
        this.images = new ImageStore(plugin);
        this.heads = new Heads(plugin);
        this.designs = new Designs(plugin);
    }

    public ImageStore images() {
        return images;
    }

    public Designs designs() {
        return designs;
    }

    Heads heads() {
        return heads;
    }

    // ------------------------------------------------------------ storage

    public void load() {
        images.dir().mkdirs();
        new File(plugin.getDataFolder(), "screens").mkdirs();
        designs.exportBundled();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        synchronized (freeMaps) {
            freeMaps.clear();
            freeMaps.addAll(y.getIntegerList("free-maps"));
        }
        ConfigurationSection root = y.getConfigurationSection("screens");
        if (root != null) {
            for (String name : root.getKeys(false)) {
                ConfigurationSection s = root.getConfigurationSection(name);
                if (s == null) continue;
                try {
                    Screen sc = Screen.load(plugin, name, s);
                    sc.reloadPages();
                    sc.rebuildIcon();
                    screens.put(name.toLowerCase(Locale.ROOT), sc);
                    plugin.tracker().register(sc);
                    attachMaps(sc);
                } catch (RuntimeException e) {
                    plugin.getLogger().warning("Screen " + name + " could not be loaded: " + e.getMessage());
                }
            }
        }
        long every = Math.max(5, plugin.getConfig().getLong("screens.graph-interval-seconds", 30)) * 20L;
        sampler = plugin.scheduler().runGlobalRepeating(this::sample, 20L, every);
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        synchronized (freeMaps) {
            y.set("free-maps", new ArrayList<>(freeMaps));
        }
        for (Screen s : screens.values()) s.save(y.createSection("screens." + s.name()));
        plugin.io().write(file, y.saveToString());
    }

    /** Finds or creates the map views on the global thread, then hooks our renderers in. */
    void attachMaps(Screen s) {
        plugin.scheduler().runGlobal(() -> {
            try {
                World base = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
                int[] ids = s.mapIds();
                MapView[] views = new MapView[ids.length];
                boolean changed = false;
                for (int i = 0; i < ids.length; i++) {
                    MapView v = ids[i] >= 0 ? Bukkit.getMap(ids[i]) : null;
                    if (v == null) {
                        v = takeFreeMap();
                        if (v == null) {
                            if (base == null) throw new IllegalStateException("no world loaded");
                            v = Bukkit.createMap(base);
                        }
                        changed = true;
                    }
                    prepare(v, s, i);
                    views[i] = v;
                }
                s.attach(views);
                if (changed) save();
            } catch (RuntimeException e) {
                plugin.getLogger().log(java.util.logging.Level.WARNING, "Screen " + s.name() + ": could not set up its maps", e);
            }
        });
    }

    private MapView takeFreeMap() {
        synchronized (freeMaps) {
            while (!freeMaps.isEmpty()) {
                MapView v = Bukkit.getMap(freeMaps.poll());
                if (v != null) return v;
            }
        }
        return null;
    }

    private static void prepare(MapView v, Screen s, int cell) {
        for (MapRenderer r : new ArrayList<>(v.getRenderers())) v.removeRenderer(r);
        // cosmetic settings - a server that refuses one must not stop the screen from working
        optional(() -> v.setScale(MapView.Scale.CLOSEST));
        optional(() -> v.setTrackingPosition(false));
        optional(() -> v.setUnlimitedTracking(false));
        optional(() -> v.setLocked(true));
        v.addRenderer(new ScreenRenderer(s, cell));
    }

    private static void optional(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException ignored) {
        }
    }

    // ------------------------------------------------------------ registry

    public Screen get(String name) {
        return name == null ? null : screens.get(name.toLowerCase(Locale.ROOT));
    }

    public Collection<Screen> all() {
        return screens.values();
    }

    public List<String> names() {
        List<String> out = new ArrayList<>();
        for (Screen s : screens.values()) out.add(s.name());
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    /** Creates, writes a starter page if there is none, registers and sets up maps. */
    public Screen create(String name, String world, Geometry g) {
        return create(name, world, g, null);
    }

    /** design = a file in designs/ to start from, or null for the built-in starter page. */
    public Screen create(String name, String world, Geometry g, String design) {
        Screen s = new Screen(plugin, name, world, g, null);
        File pages = s.pagesFile();
        File src = design == null ? null : designs.file(design);
        if (src != null) {
            pages.getParentFile().mkdirs();
            try {
                java.nio.file.Files.copy(src.toPath(), pages.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.io.IOException e) {
                throw new IllegalStateException("Could not copy design " + design + ": " + e.getMessage(), e);
            }
        } else if (!pages.exists()) Templates.writeDefault(pages, g.width(), g.height());
        Templates.ensureExampleImage(images.dir());
        s.reloadPages();
        screens.put(name.toLowerCase(Locale.ROOT), s);
        plugin.tracker().register(s);
        attachMaps(s);
        save();
        return s;
    }

    public boolean remove(String name) {
        Screen s = screens.remove(name.toLowerCase(Locale.ROOT));
        if (s == null) return false;
        plugin.tracker().unregister(s);
        for (Map.Entry<UUID, Focus> e : new ArrayList<>(focus.entrySet())) {
            if (e.getValue().screen() == s && focus.remove(e.getKey(), e.getValue())) e.getValue().task().cancel();
        }
        s.destroy();
        synchronized (freeMaps) {
            for (int id : s.mapIds()) if (id >= 0 && !freeMaps.contains(id)) freeMaps.add(id);
        }
        save();
        return true;
    }

    /** Some screen cell already uses this block? */
    public Screen overlapping(String world, Geometry g) {
        for (Screen s : screens.values()) {
            if (!s.worldName().equals(world)) continue;
            Geometry o = s.geometry();
            for (int c = 0; c < g.width(); c++) {
                for (int r = 0; r < g.height(); r++) {
                    int bx = g.cellBlockX(c), by = g.cellBlockY(r), bz = g.cellBlockZ(c);
                    for (int c2 = 0; c2 < o.width(); c2++) {
                        if (o.cellBlockX(c2) != bx || o.cellBlockZ(c2) != bz) continue;
                        if (by <= o.y() && by > o.y() - o.height()) return s;
                    }
                }
            }
        }
        return null;
    }

    public void reloadAll() {
        images.clear();
        for (Screen s : screens.values()) s.reloadPages();
    }

    public void redrawAll() {
        for (Screen s : screens.values()) s.redraw();
    }

    // ------------------------------------------------------------ fast cursor task

    /** While a player stands near a screen, its cursor/hover is checked every few ticks. */
    void focus(Player p, Screen s) {
        Focus f = focus.get(p.getUniqueId());
        if (f != null && f.screen() == s) return;
        if (f != null && focus.remove(p.getUniqueId(), f)) f.task().cancel();
        long period = Math.max(1, plugin.getConfig().getLong("screens.cursor-ticks", 2));
        CancelHandle h = plugin.scheduler().runForEntityRepeating(p, () -> {
            if (!p.isOnline()) return;
            try {
                s.tick(p);
            } catch (Throwable t) {
                plugin.getLogger().log(java.util.logging.Level.WARNING, "Screen tick failed", t);
            }
        }, 1L, period);
        if (h == null) return;
        Focus nf = new Focus(s, h);
        Focus prev = focus.put(p.getUniqueId(), nf);
        if (prev != null && prev != nf) prev.task().cancel();
    }

    void unfocus(Player p, Screen s) {
        Focus f = focus.get(p.getUniqueId());
        if (f != null && f.screen() == s && focus.remove(p.getUniqueId(), f)) f.task().cancel();
    }

    // ------------------------------------------------------------ graph samples

    private void sample() {
        add(online, Bukkit.getOnlinePlayers().size());
        double[] st = org.mydreamduels.dreamnfm.util.ServerStats.now();
        if (!Double.isNaN(st[0])) add(tps, st[0]);
        if (!Double.isNaN(st[1])) add(mspt, st[1]);
    }

    private static void add(Deque<Double> d, double v) {
        synchronized (d) {
            d.addLast(v);
            while (d.size() > SAMPLES) d.removeFirst();
        }
    }

    double[] samples(String source) {
        Deque<Double> d = "tps".equals(source) ? tps : "mspt".equals(source) ? mspt : online;
        synchronized (d) {
            double[] out = new double[d.size()];
            int i = 0;
            for (double v : d) out[i++] = v;
            return out;
        }
    }

    public void shutdown() {
        if (sampler != null) sampler.cancel();
        for (Focus f : focus.values()) f.task().cancel();
        focus.clear();
        for (Screen s : screens.values()) {
            s.respawnFrames();
            MapView[] v = s.views();
            if (v == null) continue;
            for (MapView view : v) {
                try {
                    for (MapRenderer r : new ArrayList<>(view.getRenderers())) if (r instanceof ScreenRenderer) view.removeRenderer(r);
                } catch (RuntimeException ignored) {
                }
            }
        }
    }

    // ------------------------------------------------------------ events

    private Screen screenOf(Entity e) {
        if (!(e instanceof GlowItemFrame)) return null;
        String tag = Entities.tag(e);
        if (tag == null || !tag.startsWith("screen:")) return null;
        int end = tag.lastIndexOf(':');
        return end <= 7 ? null : get(tag.substring(7, end));
    }

    private static boolean isOurs(Entity e) {
        if (!(e instanceof GlowItemFrame)) return false;
        String tag = Entities.tag(e);
        return tag != null && tag.startsWith("screen:");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onAttack(PrePlayerAttackEntityEvent e) {
        if (!isOurs(e.getAttacked())) return;
        e.setCancelled(true);
        Screen s = screenOf(e.getAttacked());
        if (s != null) s.touch(e.getPlayer(), true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        if (!isOurs(e.getRightClicked())) return;
        e.setCancelled(true);
        Screen s = screenOf(e.getRightClicked());
        if (s != null && e.getHand() == EquipmentSlot.HAND) s.touch(e.getPlayer(), true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(EntityDamageEvent e) {
        if (isOurs(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBreak(HangingBreakEvent e) {
        if (isOurs(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onFrameChange(PlayerItemFrameChangeEvent e) {
        if (isOurs(e.getItemFrame())) e.setCancelled(true);
    }

    /** Clicks from further away than entity reach land on the wall/air - raycast them onto the screen. */
    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent e) {
        Action a = e.getAction();
        if (a == Action.PHYSICAL) return;
        Player p = e.getPlayer();
        if (screens.isEmpty()) return;
        String world = p.getWorld().getName();
        Screen best = null;
        Geometry.Hit bestHit = null;
        for (Screen s : screens.values()) {
            if (!s.worldName().equals(world)) continue;
            Geometry.Hit h = s.aim(p);
            if (h != null && (bestHit == null || h.distance() < bestHit.distance())) {
                best = s;
                bestHit = h;
            }
        }
        if (best == null || best.obstructed(p, bestHit)) return;
        e.setUseInteractedBlock(Event.Result.DENY);
        e.setUseItemInHand(Event.Result.DENY);
        if (e.getHand() == null || e.getHand() == EquipmentSlot.HAND) best.touch(p, false);
    }
}
