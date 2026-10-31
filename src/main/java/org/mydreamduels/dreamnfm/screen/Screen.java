package org.mydreamduels.dreamnfm.screen;

import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Rotation;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.GlowItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapView;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.mydreamduels.dreamnfm.DreamNFM;
import org.mydreamduels.dreamnfm.action.Action;
import org.mydreamduels.dreamnfm.util.Placeholders;
import org.mydreamduels.dreamnfm.util.Text;
import org.mydreamduels.dreamnfm.view.Entities;
import org.mydreamduels.dreamnfm.view.Viewable;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * A wall of invisible glow item frames holding maps. Every player gets their
 * own picture (hover highlight, their own page, their placeholders), drawn by
 * a contextual map renderer from a per-player frame buffer.
 *
 * Threads: update/tick/touch run on the player's thread; frames are spawned
 * on their own location's thread; the renderer runs wherever the frame is
 * tracked from (same region as the viewer on Folia) and only reads state
 * under the state's lock.
 */
public final class Screen implements Viewable {

    /** Mouse pointer drawn over the screen where you look. X = outline, . = fill. */
    static final String[] CURSOR = {
            "X         ",
            "XX        ",
            "X.X       ",
            "X..X      ",
            "X...X     ",
            "X....X    ",
            "X.....X   ",
            "X......X  ",
            "X.......X ",
            "X....XXXXX",
            "X..X..X   ",
            "X.X X..X  ",
            "XX  X..X  ",
            "     XX   "};
    static final int CURSOR_W = 10, CURSOR_H = CURSOR.length;
    private static final long TOUCH_DEBOUNCE_MS = 150, PRESS_MS = 180, RESPAWN_RETRY_MS = 5000;

    private final DreamNFM plugin;
    private final String name;
    private final String world;
    private final Geometry geo;
    private volatile double viewRange = 48, touchRange = 8;
    private volatile boolean cursor = true;
    private final int[] mapIds;

    private volatile MapView[] views;
    private volatile boolean ready, destroyed;
    private volatile Page.Set pages = Page.Set.EMPTY;

    private final AtomicReferenceArray<GlowItemFrame> frames;
    private final AtomicLongArray spawnAt;
    private final AtomicInteger entityGen = new AtomicInteger();
    private final AtomicInteger drawGen = new AtomicInteger();
    private final Map<UUID, ScreenState> states = new ConcurrentHashMap<>();

    public Screen(DreamNFM plugin, String name, String world, Geometry geo, int[] mapIds) {
        this.plugin = plugin;
        this.name = name;
        this.world = world;
        this.geo = geo;
        int cells = geo.width() * geo.height();
        this.mapIds = mapIds != null && mapIds.length == cells ? mapIds.clone() : filled(cells);
        this.frames = new AtomicReferenceArray<>(cells);
        this.spawnAt = new AtomicLongArray(cells);
    }

    private static int[] filled(int n) {
        int[] a = new int[n];
        Arrays.fill(a, -1);
        return a;
    }

    // ------------------------------------------------------------ accessors

    public String name() {
        return name;
    }

    public Geometry geometry() {
        return geo;
    }

    public int cells() {
        return geo.width() * geo.height();
    }

    public boolean ready() {
        return ready;
    }

    public Page.Set pages() {
        return pages;
    }

    public int[] mapIds() {
        synchronized (mapIds) {
            return mapIds.clone();
        }
    }

    public double touchRange() {
        return touchRange;
    }

    public void setTouchRange(double r) {
        this.touchRange = Math.max(1, Math.min(32, r));
    }

    public void setViewRange(double r) {
        this.viewRange = Math.max(4, Math.min(256, r));
    }

    public boolean cursor() {
        return cursor;
    }

    public void setCursor(boolean on) {
        this.cursor = on;
        redraw();
    }

    public File pagesFile() {
        return new File(new File(plugin.getDataFolder(), "screens"), name + ".yml");
    }

    public void reloadPages() {
        pages = Page.Set.load(pagesFile(), plugin.getLogger());
        for (ScreenState st : states.values()) {
            synchronized (st) {
                if (pages.page(st.page) == null) {
                    Page start = pages.startPage();
                    st.page = start == null ? null : start.name();
                    st.history.clear();
                }
            }
        }
        redraw();
    }

    /** Everyone's picture gets rebuilt on their next tick (image loaded, pages reloaded...). */
    public void redraw() {
        drawGen.incrementAndGet();
    }

    /** Called on the global thread once the map views exist. */
    void attach(MapView[] views) {
        if (destroyed) return;
        synchronized (mapIds) {
            for (int i = 0; i < views.length; i++) mapIds[i] = views[i].getId();
        }
        this.views = views;
        this.ready = true;
    }

    MapView[] views() {
        return views;
    }

    // ------------------------------------------------------------ Viewable

    @Override
    public String worldName() {
        return world;
    }

    @Override
    public double x() {
        return geo.x() + 0.5 + geo.rightX() * (geo.width() - 1) / 2.0;
    }

    @Override
    public double y() {
        return geo.y() + 0.5 - (geo.height() - 1) / 2.0;
    }

    @Override
    public double z() {
        return geo.z() + 0.5 + geo.rightZ() * (geo.width() - 1) / 2.0;
    }

    @Override
    public double viewRange() {
        return viewRange;
    }

    @Override
    public void update(Player p, boolean inRange, double distanceSq) {
        if (!ready || destroyed) return;
        if (!inRange) {
            hideFrames(p);
            plugin.screens().unfocus(p, this);
            states.remove(p.getUniqueId());
            return;
        }
        ensureFrames();
        tick(p);
        showFrames(p);
        double near = touchRange + 3;
        if (distanceSq <= near * near) plugin.screens().focus(p, this);
        else plugin.screens().unfocus(p, this);
    }

    @Override
    public void forget(Player p) {
        states.remove(p.getUniqueId());
        plugin.screens().unfocus(p, this);
    }

    // ------------------------------------------------------------ frames

    private void ensureFrames() {
        long now = System.currentTimeMillis();
        for (int i = 0; i < cells(); i++) {
            GlowItemFrame f = frames.get(i);
            if (f != null && f.isValid()) continue;
            long last = spawnAt.get(i);
            if (now - last < RESPAWN_RETRY_MS || !spawnAt.compareAndSet(i, last, now)) continue;
            spawnFrame(i);
        }
    }

    private void spawnFrame(int cell) {
        World w = Bukkit.getWorld(world);
        MapView[] v = views;
        if (w == null || v == null) return;
        int col = cell % geo.width(), row = cell / geo.width();
        Location at = new Location(w, geo.cellBlockX(col), geo.cellBlockY(row), geo.cellBlockZ(col));
        ItemStack map = new ItemStack(Material.FILLED_MAP);
        if (map.getItemMeta() instanceof MapMeta meta) {
            meta.setMapView(v[cell]);
            map.setItemMeta(meta);
        }
        int gen = entityGen.get();
        BlockFace facing = geo.facing();
        Entities.spawn(plugin, at, GlowItemFrame.class, "screen:" + name + ":" + cell, f -> {
            f.setFacingDirection(facing, true);
            f.setItem(map, false);
            f.setRotation(Rotation.NONE);
            f.setVisible(false);
            f.setFixed(true);
            f.setInvulnerable(true);
            f.setSilent(true);
            f.setItemDropChance(0f);
            f.setVisibleByDefault(false);
        }, f -> {
            if (destroyed || entityGen.get() != gen) {
                f.remove();
                return;
            }
            GlowItemFrame old = frames.getAndSet(cell, f);
            if (old != null && old != f) Entities.remove(plugin, old);
        });
    }

    private void showFrames(Player p) {
        for (int i = 0; i < cells(); i++) {
            GlowItemFrame f = frames.get(i);
            if (f != null && f.isValid() && !p.canSee(f)) p.showEntity(plugin, f);
        }
    }

    private void hideFrames(Player p) {
        for (int i = 0; i < cells(); i++) {
            GlowItemFrame f = frames.get(i);
            if (f != null && p.canSee(f)) p.hideEntity(plugin, f);
        }
    }

    /** True if this entity is one of our frames (checked by tag, so it also matches stale ones). */
    public boolean owns(org.bukkit.entity.Entity e) {
        String tag = e instanceof GlowItemFrame ? Entities.tag(e) : null;
        return tag != null && tag.startsWith("screen:" + name + ":");
    }

    /** Removes the frames so they are spawned again (e.g. after moving the maps). */
    public void respawnFrames() {
        entityGen.incrementAndGet();
        for (int i = 0; i < cells(); i++) {
            Entities.remove(plugin, frames.getAndSet(i, null));
            spawnAt.set(i, 0);
        }
    }

    public void destroy() {
        destroyed = true;
        ready = false;
        respawnFrames();
        states.clear();
        MapView[] v = views;
        if (v != null) {
            plugin.scheduler().runGlobal(() -> {
                for (MapView view : v) {
                    for (org.bukkit.map.MapRenderer r : new ArrayList<>(view.getRenderers())) {
                        if (r instanceof ScreenRenderer sr && sr.screen == this) view.removeRenderer(r);
                    }
                }
            });
        }
    }

    // ------------------------------------------------------------ drawing

    private Geometry.Hit look(Player p, double range) {
        Location eye = p.getEyeLocation();
        if (eye.getWorld() == null || !eye.getWorld().getName().equals(world)) return null;
        Vector d = eye.getDirection();
        return geo.hit(eye.getX(), eye.getY(), eye.getZ(), d.getX(), d.getY(), d.getZ(), range);
    }

    private ScreenState state(Player p) {
        return states.computeIfAbsent(p.getUniqueId(), k -> {
            Page start = pages.startPage();
            return new ScreenState(cells(), start == null ? null : start.name());
        });
    }

    /** Player's thread: rebuild what changed and push the changed maps. */
    public void tick(Player p) {
        if (!ready || destroyed) return;
        MapView[] v = views;
        ScreenState st = state(p);
        List<Integer> send = new ArrayList<>(2);
        Page.Set set = pages;
        long now = System.currentTimeMillis();
        synchronized (st) {
            Location l = p.getLocation();
            double dx = l.getX() - x(), dy = l.getY() - y(), dz = l.getZ() - z();
            Page idlePage = set.idlePage() == null || set.wakeRange() <= 0 ? null : set.page(set.idlePage());
            boolean idle = idlePage != null && dx * dx + dy * dy + dz * dz > set.wakeRange() * set.wakeRange();
            if (idle != st.idle) {
                st.idle = idle;
                if (!idle) {
                    Page start = set.startPage();
                    st.page = start == null ? null : start.name();
                    st.history.clear();
                    st.pageSince = now;
                }
            }
            Page page = idle ? idlePage : set.page(st.page);
            if (page == null && !idle) {
                page = set.startPage();
                st.page = page == null ? null : page.name();
                st.pageSince = now;
            }
            if (!idle && page != null && page.next() != null && now - st.pageSince >= page.seconds() * 1000L) {
                Page next = set.page(page.next());
                if (next != null) {
                    st.page = next.name();
                    st.pageSince = now;
                    page = next;
                }
            }

            Geometry.Hit hit = idle ? null : look(p, Math.min(touchRange, viewRange));
            int cx = hit == null ? -1 : hit.px(), cy = hit == null ? -1 : hit.py();
            String key = (idle ? "idle:" : "page:") + (page == null ? "" : page.name());
            boolean samePage = key.equals(st.builtPage);
            int hover = !samePage || st.result == null || hit == null ? -1 : st.result.elementAt(cx, cy);
            boolean force = false;
            if (st.pressed >= 0 && now >= st.pressedUntil) {
                st.pressed = -1;
                force = true;
            }
            int gen = drawGen.get();
            boolean rebuild = st.frame == null || force || !samePage || st.builtGeneration != gen || hover != st.hover
                    || (page != null && page.refreshTicks() > 0 && now - st.lastBuild >= page.refreshTicks() * 50L);
            st.hover = hover;
            if (rebuild) {
                build(p, st, page, now, gen, key);
                if (hit != null) {
                    int again = st.result.elementAt(cx, cy);
                    if (again != st.hover) {
                        st.hover = again;
                        build(p, st, page, now, gen, key);
                    }
                }
            }
            boolean showCursor = cursor && hit != null;
            int ncx = showCursor ? cx : -1, ncy = showCursor ? cy : -1;
            boolean hot = st.hover >= 0;
            if (ncx != st.cursorX || ncy != st.cursorY || hot != st.cursorHot) {
                bumpCursor(st, st.cursorX, st.cursorY);
                bumpCursor(st, ncx, ncy);
                st.cursorX = ncx;
                st.cursorY = ncy;
                st.cursorHot = hot;
            }
            for (int i = 0; i < st.version.length; i++) {
                if (st.sent[i] != st.version[i]) {
                    st.sent[i] = st.version[i];
                    send.add(i);
                }
            }
        }
        if (v != null) for (int i : send) p.sendMap(v[i]);
    }

    private void build(Player p, ScreenState st, Page page, long now, int gen, String key) {
        PageRenderer.Result r = PageRenderer.render(page, geo.pixelWidth(), geo.pixelHeight(), context(p), st.hover, st.pressed);
        byte[] old = st.frame, nw = r.canvas().pixels;
        int W = geo.pixelWidth();
        for (int cell = 0; cell < cells(); cell++) {
            if (old == null || differs(old, nw, W, cell % geo.width(), cell / geo.width())) st.version[cell]++;
        }
        st.frame = nw;
        st.result = r;
        st.lastBuild = now;
        st.builtGeneration = gen;
        st.builtPage = key;
    }

    static boolean differs(byte[] a, byte[] b, int W, int col, int row) {
        for (int y = 0; y < 128; y++) {
            int from = (row * 128 + y) * W + col * 128;
            if (!Arrays.equals(a, from, from + 128, b, from, from + 128)) return true;
        }
        return false;
    }

    private void bumpCursor(ScreenState st, int cx, int cy) {
        if (cx < 0 || cy < 0) return;
        int c0 = cx / 128, c1 = Math.min(geo.width() - 1, (cx + CURSOR_W - 1) / 128);
        int r0 = cy / 128, r1 = Math.min(geo.height() - 1, (cy + CURSOR_H - 1) / 128);
        for (int r = r0; r <= r1; r++) for (int c = c0; c <= c1; c++) st.version[r * geo.width() + c]++;
    }

    private PageRenderer.Context context(Player p) {
        ScreenManager m = plugin.screens();
        return new PageRenderer.Context() {
            @Override
            public String text(String raw) {
                return Text.color(Placeholders.apply(p, raw));
            }

            @Override
            public ImageStore.Img image(String file, int w, int h) {
                return m.images().get(file, w, h);
            }

            @Override
            public ImageStore.Img head(String player) {
                return m.heads().get(player);
            }

            @Override
            public double[] samples(String source) {
                return m.samples(source);
            }

            @Override
            public boolean allowed(String permission) {
                return p.hasPermission(permission);
            }
        };
    }

    /** Map renderer callback (any thread). Copies this player's cell pixels + cursor onto their canvas. */
    @SuppressWarnings("deprecation")
    void renderCell(int cell, MapCanvas canvas, Player player) {
        ScreenState st = states.get(player.getUniqueId());
        if (st == null) return;
        synchronized (st) {
            if (st.frame == null || cell >= st.version.length) return;
            if (st.drawn[cell] == st.version[cell] && st.canvas[cell] == canvas) return;
            st.drawn[cell] = st.version[cell];
            st.canvas[cell] = canvas;
            int W = geo.pixelWidth(), ox = (cell % geo.width()) * 128, oy = (cell / geo.width()) * 128;
            byte[] f = st.frame;
            byte outline = Palette.of(0x000000), fill = Palette.of(st.cursorHot ? 0xFFFF55 : 0xFFFFFF);
            int lx = st.cursorX - ox, ly = st.cursorY - oy;
            boolean cur = st.cursorX >= 0 && lx > -CURSOR_W && ly > -CURSOR_H && lx < 128 && ly < 128;
            for (int y = 0; y < 128; y++) {
                int row = (oy + y) * W + ox;
                int sy = y - ly;
                String line = cur && sy >= 0 && sy < CURSOR_H ? CURSOR[sy] : null;
                for (int x = 0; x < 128; x++) {
                    byte b = f[row + x];
                    if (line != null) {
                        int sx = x - lx;
                        if (sx >= 0 && sx < line.length()) {
                            char ch = line.charAt(sx);
                            if (ch == 'X') b = outline;
                            else if (ch == '.') b = fill;
                        }
                    }
                    canvas.setPixel(x, y, b);
                }
            }
        }
    }

    // ------------------------------------------------------------ touch

    /** Where the player is pointing on this screen (within touch range), or null. */
    public Geometry.Hit aim(Player p) {
        // never further than the view range: past it the frames are hidden from this player
        return ready && !destroyed ? look(p, Math.min(touchRange, viewRange)) : null;
    }

    /** True if a block is between the eye and the screen point. */
    public boolean obstructed(Player p, Geometry.Hit hit) {
        Location eye = p.getEyeLocation();
        try {
            RayTraceResult r = eye.getWorld().rayTraceBlocks(eye, eye.getDirection(), hit.distance(), FluidCollisionMode.NEVER, true);
            return r != null && r.getHitPosition().distance(eye.toVector()) < hit.distance() - 0.02;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Player's thread. Returns true if the click landed on this screen (the
     * caller then cancels the event so nothing else happens).
     */
    public boolean touch(Player p, boolean viaFrame) {
        if (!ready || destroyed) return viaFrame;
        Geometry.Hit hit = look(p, viaFrame ? Math.max(touchRange, 6) : Math.min(touchRange, viewRange));
        if (hit == null) return viaFrame;
        if (!viaFrame && obstructed(p, hit)) return false;
        ScreenState st = state(p);
        Element el = null;
        Page.Set set = pages;
        long now = System.currentTimeMillis();
        synchronized (st) {
            if (now - st.lastTouch < TOUCH_DEBOUNCE_MS) return true;
            st.lastTouch = now;
            if (st.idle || st.result == null) return true;
            Page page = set.page(st.page);
            int idx = st.result.elementAt(hit.px(), hit.py());
            if (page == null || idx < 0 || idx >= page.elements().size() || !(page.name()).equals(pageName(st.builtPage))) return true;
            el = page.elements().get(idx);
            st.pressed = idx;
            st.pressedUntil = now + PRESS_MS;
            st.hover = -2; // force a rebuild with the pressed look
        }
        if (el.actions.click(plugin, p)) {
            String sound = set.clickSound();
            if (sound != null && !sound.isBlank() && !sound.equalsIgnoreCase("none")) {
                try {
                    p.playSound(p.getLocation(), sound, SoundCategory.MASTER, 0.6f, 1.2f);
                } catch (RuntimeException ignored) {
                }
            }
            for (Action a : el.actions.actions()) {
                if (a.type() == Action.Type.PAGE) open(p, a.value().trim());
                else if (a.type() == Action.Type.CLOSE) home(p);
            }
        }
        tick(p);
        return true;
    }

    private static String pageName(String builtKey) {
        if (builtKey == null) return "";
        int c = builtKey.indexOf(':');
        return c < 0 ? builtKey : builtKey.substring(c + 1);
    }

    /** Opens a page for one player ("back" = previous page). Player's thread. */
    public boolean open(Player p, String page) {
        ScreenState st = state(p);
        Page.Set set = pages;
        synchronized (st) {
            if (page.equalsIgnoreCase("back")) {
                String prev = st.back();
                if (prev == null) return false;
                st.page = prev;
                st.pageSince = System.currentTimeMillis();
                st.hover = -1;
                return true;
            }
            Page target = set.page(page);
            if (target == null) {
                plugin.getLogger().warning("Screen " + name + ": button opens unknown page '" + page + "'.");
                return false;
            }
            st.open(target.name(), true);
            return true;
        }
    }

    public void home(Player p) {
        ScreenState st = state(p);
        Page start = pages.startPage();
        synchronized (st) {
            st.history.clear();
            if (start != null) st.open(start.name(), false);
        }
    }

    public String currentPage(Player p) {
        ScreenState st = states.get(p.getUniqueId());
        if (st == null) return null;
        synchronized (st) {
            return st.page;
        }
    }

    public int viewers() {
        return states.size();
    }

    // ------------------------------------------------------------ storage

    public void save(ConfigurationSection s) {
        s.set("world", world);
        s.set("x", geo.x());
        s.set("y", geo.y());
        s.set("z", geo.z());
        s.set("facing", geo.facing().name());
        s.set("width", geo.width());
        s.set("height", geo.height());
        s.set("view-range", viewRange);
        s.set("touch-range", touchRange);
        s.set("cursor", cursor);
        List<Integer> ids = new ArrayList<>();
        for (int id : mapIds()) ids.add(id);
        s.set("maps", ids);
    }

    public static Screen load(DreamNFM plugin, String name, ConfigurationSection s) {
        BlockFace face;
        try {
            face = BlockFace.valueOf(s.getString("facing", "SOUTH").toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            face = BlockFace.SOUTH;
        }
        if (!Geometry.validFacing(face)) face = BlockFace.SOUTH;
        int w = Math.max(1, Math.min(ScreenManager.MAX_SIZE, s.getInt("width", 1)));
        int h = Math.max(1, Math.min(ScreenManager.MAX_SIZE, s.getInt("height", 1)));
        Geometry g = new Geometry(s.getInt("x"), s.getInt("y"), s.getInt("z"), face, w, h);
        List<Integer> raw = s.getIntegerList("maps");
        int[] ids = new int[raw.size()];
        for (int i = 0; i < ids.length; i++) ids[i] = raw.get(i);
        Screen sc = new Screen(plugin, name, s.getString("world", "world"), g, ids);
        sc.setViewRange(s.getDouble("view-range", 48));
        sc.setTouchRange(s.getDouble("touch-range", 8));
        sc.cursor = s.getBoolean("cursor", true);
        return sc;
    }
}
