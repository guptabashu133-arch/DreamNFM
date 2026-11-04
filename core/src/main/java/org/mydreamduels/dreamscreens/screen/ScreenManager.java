package org.mydreamduels.dreamscreens.screen;

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
import org.mydreamduels.dreamscreens.DreamScreens;
import org.mydreamduels.dreamscreens.platform.CancelHandle;
import org.mydreamduels.dreamscreens.view.Entities;

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

    private final DreamScreens plugin;
    private final File file;
    private final Map<String, Screen> screens = new ConcurrentHashMap<>();
    private final ImageStore images;
    private final Heads heads;
    private final Designs designs;
    private final Fonts fonts;

    /** Takes over screens for a while: id (changes each time), the page, until when, which screens (null = all). */
    public record Announcement(String id, Page page, long until, java.util.Set<String> screens) {
    }

    private volatile Announcement announcement;
    private volatile Page.Set announcementPages = Page.Set.EMPTY;
    private final Deque<Integer> freeMaps = new ArrayDeque<>();

    private record Focus(Screen screen, CancelHandle task) {
    }

    private final Map<UUID, Focus> focus = new ConcurrentHashMap<>();
    private final Map<UUID, EditSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, Deque<String>> undo = new ConcurrentHashMap<>();
    private volatile ScreenEditCommand editor;
    private final Deque<Double> online = new ArrayDeque<>(), tps = new ArrayDeque<>(), mspt = new ArrayDeque<>();
    private CancelHandle sampler;

    public ScreenManager(DreamScreens plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "screens.yml");
        this.images = new ImageStore(plugin);
        this.heads = new Heads(plugin);
        this.designs = new Designs(plugin);
        this.fonts = new Fonts(plugin);
    }

    public ImageStore images() {
        return images;
    }

    // ------------------------------------------------------------ editing

    public void setEditor(ScreenEditCommand editor) {
        this.editor = editor;
    }

    ScreenEditCommand editor() {
        return editor;
    }

    public EditSession session(Player p) {
        return sessions.get(p.getUniqueId());
    }

    EditSession startSession(Player p, Screen s, String page) {
        EditSession ed = new EditSession(s, page);
        EditSession old = sessions.put(p.getUniqueId(), ed);
        if (old != null && old.screen != s) old.screen.redraw();
        s.redraw();
        return ed;
    }

    EditSession endSession(Player p) {
        EditSession ed = sessions.remove(p.getUniqueId());
        if (ed != null) {
            ed.screen.redraw();
            ed.screen.tick(p);
        }
        return ed;
    }

    /** Screen the player looks at within 16 blocks (to start editing), or null. */
    Screen aimedAny(Player p) {
        String world = p.getWorld().getName();
        Screen best = null;
        double bestD = Double.MAX_VALUE;
        for (Screen s : screens.values()) {
            if (!s.worldName().equals(world) || !s.ready()) continue;
            org.bukkit.Location eye = p.getEyeLocation();
            org.bukkit.util.Vector d = eye.getDirection();
            Geometry.Hit h = s.geometry().hit(eye.getX(), eye.getY(), eye.getZ(), d.getX(), d.getY(), d.getZ(), 16);
            if (h != null && h.distance() < bestD) {
                best = s;
                bestD = h.distance();
            }
        }
        return best;
    }

    void pushUndo(Screen s, String previousFile) {
        Deque<String> d = undo.computeIfAbsent(s.name().toLowerCase(Locale.ROOT), k -> new ArrayDeque<>());
        synchronized (d) {
            d.push(previousFile);
            while (d.size() > 30) d.removeLast();
        }
    }

    boolean undo(Screen s) {
        Deque<String> d = undo.get(s.name().toLowerCase(Locale.ROOT));
        if (d == null) return false;
        String text;
        synchronized (d) {
            text = d.poll();
        }
        if (text == null) return false;
        try {
            org.mydreamduels.dreamscreens.util.FileUtil.writeAtomically(s.pagesFile(), text);
        } catch (java.io.IOException e) {
            return false;
        }
        s.reloadPages();
        return true;
    }

    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent e) {
        sessions.remove(e.getPlayer().getUniqueId());
    }

    public Fonts fonts() {
        return fonts;
    }

    // ------------------------------------------------------------ announcements

    public Page.Set announcementPages() {
        return announcementPages;
    }

    void loadAnnouncements() {
        File f = new File(plugin.getDataFolder(), "announcements.yml");
        if (!f.exists() && plugin.getResource("announcements.yml") != null) plugin.saveResource("announcements.yml", false);
        announcementPages = Page.Set.load(f, plugin.getLogger());
    }

    /** The announcement this screen shows right now, or null. */
    Announcement announcementFor(Screen s, long now) {
        Announcement a = announcement;
        if (a == null) return null;
        if (now >= a.until()) {
            if (announcement == a) {
                announcement = null;
                redrawAll();
            }
            return null;
        }
        return a.screens() == null || a.screens().contains(s.name().toLowerCase(Locale.ROOT)) ? a : null;
    }

    public Announcement announcement() {
        Announcement a = announcement;
        return a != null && System.currentTimeMillis() < a.until() ? a : null;
    }

    /** Shows a page from announcements.yml. targets = screen names, or null for every screen. */
    public void announce(Page page, int seconds, java.util.Set<String> targets) {
        java.util.Set<String> t = targets == null ? null : new java.util.HashSet<>();
        if (targets != null) for (String n : targets) t.add(n.toLowerCase(Locale.ROOT));
        announcement = new Announcement(java.util.UUID.randomUUID().toString().substring(0, 8), page,
                System.currentTimeMillis() + Math.max(1, seconds) * 1000L, t == null ? null : java.util.Set.copyOf(t));
        redrawAll();
    }

    /** A quick text announcement: first line big, the rest under it (lines split with |). */
    public void announceText(String message, int seconds, java.util.Set<String> targets) {
        announce(textPage(message), seconds, targets);
    }

    static Page textPage(String message) {
        String[] lines = message.split("\\|");
        YamlConfiguration y = new YamlConfiguration();
        List<Map<String, Object>> els = new ArrayList<>();
        els.add(new java.util.LinkedHashMap<>(Map.of("type", "rect", "x", "center", "y", "center", "w", "94%", "h", "70%",
                "color", "#11131A", "border", 2, "border-color", "#FACC15")));
        Map<String, Object> title = new java.util.LinkedHashMap<>(Map.of("type", "text", "x", "center", "y", lines.length > 1 ? "32%" : "45%",
                "text", "&l" + lines[0].trim(), "scale", 2, "color", "#FACC15", "shadow-color", "#000000", "max-width", "90%"));
        els.add(title);
        if (lines.length > 1) {
            List<String> rest = new ArrayList<>();
            for (int i = 1; i < lines.length; i++) rest.add("&f" + lines[i].trim());
            els.add(new java.util.LinkedHashMap<>(Map.of("type", "lines", "x", "center", "y", "55%", "lines", rest, "max-width", "90%")));
        }
        y.set("pages.text.background", "none");
        y.set("pages.text.sound", "block.note_block.pling");
        y.set("pages.text.elements", els);
        return Page.Set.parse(y, java.util.logging.Logger.getAnonymousLogger(), "announcement").page("text");
    }

    public boolean stopAnnouncement() {
        boolean was = announcement() != null;
        announcement = null;
        redrawAll();
        return was;
    }

    // ------------------------------------------------------------ mirrors

    void reloadMirrorsOf(Screen source) {
        for (Screen s : screens.values()) {
            if (s != source && source.name().equalsIgnoreCase(s.mirror())) s.reloadPages();
        }
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
        fonts.dir().mkdirs();
        loadAnnouncements();
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
        // mirrors can only find their source once every screen is loaded
        for (Screen sc : screens.values()) if (sc.mirror() != null) sc.reloadPages();
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
        sessions.values().removeIf(ed -> ed.screen == s);
        undo.remove(s.name().toLowerCase(Locale.ROOT));
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
        fonts.clear();
        loadAnnouncements();
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
        long period = Math.max(1, plugin.getConfig().getLong("screens.cursor-ticks", 1));
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
        double[] st = org.mydreamduels.dreamscreens.util.ServerStats.now();
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

    /** Paper-only events live in their own class so Spigot never has to load them. */
    public void registerPaperEvents() {
        try {
            Class.forName("io.papermc.paper.event.player.PrePlayerAttackEntityEvent");
            Class.forName("io.papermc.paper.event.player.PlayerItemFrameChangeEvent");
        } catch (Throwable t) {
            return; // Spigot: the arm-swing fallback below handles left clicks
        }
        plugin.getServer().getPluginManager().registerEvents(new PaperScreenEvents(this), plugin);
    }

    Screen screenOf(Entity e) {
        if (!(e instanceof GlowItemFrame)) return null;
        String tag = Entities.tag(e);
        if (tag == null || !tag.startsWith("screen:")) return null;
        int end = tag.lastIndexOf(':');
        return end <= 7 ? null : get(tag.substring(7, end));
    }

    static boolean isOurs(Entity e) {
        if (!(e instanceof GlowItemFrame)) return false;
        String tag = Entities.tag(e);
        return tag != null && tag.startsWith("screen:");
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

    /** The screen this player points at (nearest, within its touch range, nothing in between), or null. */
    Screen aimed(Player p) {
        if (screens.isEmpty()) return null;
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
        return best == null || best.obstructed(p, bestHit) ? null : best;
    }

    /** Clicks from further away than entity reach land on the wall/air - raycast them onto the screen. */
    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() == Action.PHYSICAL) return;
        Player p = e.getPlayer();
        Screen best = aimed(p);
        if (best == null) return;
        e.setUseInteractedBlock(Event.Result.DENY);
        e.setUseItemInHand(Event.Result.DENY);
        if (e.getHand() == null || e.getHand() == EquipmentSlot.HAND) best.touch(p, false);
    }

    /**
     * Every left click swings the arm - also when the server sends no other
     * event (punching an item frame on Spigot, clicking air far away). The
     * touch debounce stops a click that already arrived another way from
     * counting twice.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onSwing(org.bukkit.event.player.PlayerAnimationEvent e) {
        if (e.getAnimationType() != org.bukkit.event.player.PlayerAnimationType.ARM_SWING) return;
        Player p = e.getPlayer();
        Screen best = aimed(p);
        if (best != null) best.touch(p, false);
    }
}
