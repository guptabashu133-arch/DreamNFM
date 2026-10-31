package org.mydreamduels.dreamnfm.hologram;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.mydreamduels.dreamnfm.DreamNFM;
import org.mydreamduels.dreamnfm.action.ActionList;
import org.mydreamduels.dreamnfm.util.Colors;
import org.mydreamduels.dreamnfm.util.Placeholders;
import org.mydreamduels.dreamnfm.util.Text;
import org.mydreamduels.dreamnfm.view.Entities;
import org.mydreamduels.dreamnfm.view.Viewable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A text / item / block hologram built from vanilla display entities.
 *
 * Shared mode: one entity everybody sees (server-wide placeholders).
 * Personal mode (personal: true, or proximity > 0): one entity per nearby
 * player, visible only to them - their own placeholders, and with
 * proximity it "pops" open when they walk up and folds away when they leave.
 *
 * spin/bob animate with client-side interpolation, so the motion is smooth
 * while the server only sends one update per step.
 */
public final class Hologram implements Viewable {

    public enum Type { TEXT, ITEM, BLOCK }

    private final DreamNFM plugin;
    private final String name;
    private final boolean managed;

    // ------------------------------------------------------------------ settings
    private volatile String world = "world";
    private volatile double x, y, z;
    private volatile float yaw;
    private volatile Type type = Type.TEXT;
    private final List<String> lines = new CopyOnWriteArrayList<>();
    private volatile ItemStack item = new ItemStack(Material.DIAMOND);
    private volatile String block = "minecraft:grass_block";
    private volatile Display.Billboard billboard = Display.Billboard.CENTER;
    private volatile float scaleX = 1, scaleY = 1, scaleZ = 1;
    private volatile String background = "default";
    private volatile boolean shadow;
    private volatile boolean seeThrough;
    private volatile TextDisplay.TextAlignment alignment = TextDisplay.TextAlignment.CENTER;
    private volatile int opacity = 255;
    private volatile int lineWidth = 200;
    private volatile String glow;
    private volatile double viewRange = 48;
    private volatile int updateTicks = 20;
    private volatile boolean personal;
    private volatile double proximity;
    private volatile double spin;
    private volatile double bob;
    private volatile int brightness = -1;
    private volatile String itemTransform = "FIXED";
    private volatile float hitboxW, hitboxH;
    private final ActionList actions = new ActionList();

    // ------------------------------------------------------------------ runtime
    private final AtomicReference<Display> shared = new AtomicReference<>();
    private final AtomicLong sharedSpawnAt = new AtomicLong();
    private final AtomicLong sharedUpdatedAt = new AtomicLong();
    private volatile String sharedText;
    private final AtomicReference<Interaction> hitbox = new AtomicReference<>();
    private final AtomicLong hitboxSpawnAt = new AtomicLong();
    private final Map<UUID, Viewer> viewers = new ConcurrentHashMap<>();
    /** Bumped whenever all entities are thrown away; late spawns from an older generation remove themselves. */
    private volatile int generation;
    private volatile boolean removed;
    /** Popups currently folding away - the spin/bob task must not re-inflate them. */
    private final java.util.Set<Display> closing = ConcurrentHashMap.newKeySet();

    private static final class Viewer {
        volatile Display entity;
        volatile long spawnAt;
        volatile long updatedAt;
        volatile String text;
    }

    public Hologram(DreamNFM plugin, String name, boolean managed) {
        this.plugin = plugin;
        this.name = name;
        this.managed = managed;
    }

    // ------------------------------------------------------------------ Viewable

    @Override public String worldName() { return world; }
    @Override public double x() { return x; }
    @Override public double y() { return y; }
    @Override public double z() { return z; }

    @Override
    public double viewRange() {
        return proximity > 0 ? proximity : viewRange;
    }

    private boolean personalMode() {
        return personal || proximity > 0;
    }

    @Override
    public void update(Player p, boolean inRange, double distanceSq) {
        if (removed) return;
        if (!personalMode()) {
            if (inRange) {
                ensureShared();
                ensureHitbox();
                refreshShared();
            }
            return;
        }
        Viewer v = viewers.get(p.getUniqueId());
        if (inRange) {
            if (v == null) {
                v = new Viewer();
                viewers.put(p.getUniqueId(), v);
                spawnFor(p, v);
            } else if (v.entity != null && v.entity.isValid()) {
                refreshFor(p, v);
            } else if (v.entity != null || System.currentTimeMillis() - v.spawnAt > 5000) {
                // chunk unloaded or the spawn never came back - try again
                v.entity = null;
                spawnFor(p, v);
            }
            ensureHitbox();
        } else if (v != null) {
            viewers.remove(p.getUniqueId());
            despawnViewer(v.entity, proximity > 0);
        }
    }

    @Override
    public void forget(Player p) {
        Viewer v = viewers.remove(p.getUniqueId());
        if (v != null) despawnViewer(v.entity, false);
        actions.forget(p.getUniqueId());
    }

    // ------------------------------------------------------------------ spawning

    public Location location() {
        World w = Bukkit.getWorld(world);
        return w == null ? null : new Location(w, x, y, z, yaw, 0);
    }

    private Class<? extends Display> displayClass() {
        return switch (type) {
            case TEXT -> TextDisplay.class;
            case ITEM -> ItemDisplay.class;
            case BLOCK -> BlockDisplay.class;
        };
    }

    private String resolvedText(Player viewer) {
        return Placeholders.apply(viewer, String.join("\n", lines));
    }

    private void ensureShared() {
        Display e = shared.get();
        if (e != null && e.isValid()) return;
        long now = System.currentTimeMillis();
        long started = sharedSpawnAt.get();
        if (started != 0 && now - started < 5000) return; // a spawn is already on its way
        if (!sharedSpawnAt.compareAndSet(started, now)) return;
        Location loc = location();
        if (loc == null) return;
        int gen = generation;
        String text = type == Type.TEXT ? resolvedText(null) : null;
        sharedText = text;
        sharedUpdatedAt.set(now);
        Entities.spawn(plugin, loc, displayClass(), "holo:" + name, d -> configure(d, text, 1f), d -> {
            if (gen != generation || removed) {
                d.remove();
                return;
            }
            Display old = shared.getAndSet(d);
            if (old != null && old != d) Entities.remove(plugin, old);
            sharedSpawnAt.set(0);
            startAnimation(d);
        });
    }

    private void refreshShared() {
        if (type != Type.TEXT || updateTicks <= 0) return;
        long now = System.currentTimeMillis();
        long last = sharedUpdatedAt.get();
        if (now - last < updateTicks * 50L || !sharedUpdatedAt.compareAndSet(last, now)) return;
        Display d = shared.get();
        if (!(d instanceof TextDisplay td) || !d.isValid()) return;
        String text = resolvedText(null);
        if (text.equals(sharedText)) return;
        sharedText = text;
        plugin.scheduler().runForEntity(td, () -> td.text(Text.component(text)));
    }

    private void spawnFor(Player p, Viewer v) {
        Location loc = location();
        if (loc == null) return;
        int gen = generation;
        v.spawnAt = System.currentTimeMillis();
        String text = type == Type.TEXT ? resolvedText(p) : null;
        v.text = text;
        v.updatedAt = v.spawnAt;
        boolean pop = proximity > 0;
        Entities.spawn(plugin, loc, displayClass(), "holo:" + name, d -> {
            d.setVisibleByDefault(false);
            configure(d, text, pop ? 0f : 1f);
        }, d -> {
            if (gen != generation || removed || viewers.get(p.getUniqueId()) != v || !p.isOnline()) {
                d.remove();
                return;
            }
            v.entity = d;
            plugin.scheduler().runForEntity(p, () -> {
                if (d.isValid()) p.showEntity(plugin, d);
            });
            if (pop) {
                plugin.scheduler().runForEntityDelayed(d, () -> applyTransform(d, 1f, 6), 2L);
            }
            startAnimation(d);
        });
    }

    private void refreshFor(Player p, Viewer v) {
        if (type != Type.TEXT || updateTicks <= 0) return;
        long now = System.currentTimeMillis();
        if (now - v.updatedAt < updateTicks * 50L) return;
        v.updatedAt = now;
        String text = resolvedText(p);
        if (text.equals(v.text)) return;
        v.text = text;
        Display d = v.entity;
        if (d instanceof TextDisplay td) plugin.scheduler().runForEntity(td, () -> td.text(Text.component(text)));
    }

    private void despawnViewer(Display d, boolean animate) {
        if (d == null) return;
        if (!animate) {
            Entities.remove(plugin, d);
            return;
        }
        closing.add(d);
        plugin.scheduler().runForEntity(d, () -> {
            if (!d.isValid()) {
                closing.remove(d);
                return;
            }
            applyTransform(d, 0f, 4);
            plugin.scheduler().runForEntityDelayed(d, () -> {
                closing.remove(d);
                if (d.isValid()) d.remove();
            }, 5L);
        });
    }

    private void ensureHitbox() {
        if (actions.isEmpty()) return;
        Interaction e = hitbox.get();
        if (e != null && e.isValid()) return;
        long now = System.currentTimeMillis();
        long started = hitboxSpawnAt.get();
        if (started != 0 && now - started < 5000) return;
        if (!hitboxSpawnAt.compareAndSet(started, now)) return;
        Location loc = location();
        if (loc == null) return;
        float[] size = hitboxSize();
        if (type == Type.ITEM) loc.add(0, -size[1] / 2, 0);
        int gen = generation;
        Entities.spawn(plugin, loc, Interaction.class, "holo-hit:" + name, i -> {
            i.setInteractionWidth(size[0]);
            i.setInteractionHeight(size[1]);
            i.setResponsive(true);
        }, i -> {
            if (gen != generation || removed) {
                i.remove();
                return;
            }
            Interaction old = hitbox.getAndSet(i);
            if (old != null && old != i) Entities.remove(plugin, old);
            hitboxSpawnAt.set(0);
            plugin.holograms().registerHitbox(i.getUniqueId(), this);
        });
    }

    private float[] hitboxSize() {
        if (hitboxW > 0 && hitboxH > 0) return new float[]{hitboxW, hitboxH};
        if (type == Type.TEXT) {
            int longest = 1;
            for (String l : lines) longest = Math.max(longest, Text.strip(l).length());
            float w = Math.min(lineWidth, longest * 6) / 40f * scaleX + 0.1f;
            float h = Math.max(1, lines.size()) * 0.27f * scaleY + 0.05f;
            return new float[]{w, h};
        }
        return new float[]{Math.max(scaleX, scaleZ) * (type == Type.ITEM ? 0.6f : 1f), scaleY * (type == Type.ITEM ? 0.6f : 1f)};
    }

    /** Thread of the entity. */
    private void configure(Display d, String text, float factor) {
        d.setBillboard(spin != 0 ? Display.Billboard.FIXED : billboard);
        d.setViewRange((float) Math.max(0.1, viewRange / 64.0));
        if (brightness >= 0 && brightness <= 15) d.setBrightness(new Display.Brightness(brightness, brightness));
        if (glow != null) {
            org.bukkit.Color c = Colors.argb(glow);
            if (c != null) {
                d.setGlowing(true);
                d.setGlowColorOverride(c);
            }
        }
        d.setRotation(yaw, 0);
        if (d instanceof TextDisplay td) {
            td.text(Text.component(text == null ? "" : text));
            td.setShadowed(shadow);
            td.setSeeThrough(seeThrough);
            td.setAlignment(alignment);
            td.setLineWidth(lineWidth);
            td.setTextOpacity((byte) Math.max(0, Math.min(255, opacity)));
            if (background.equalsIgnoreCase("default")) {
                td.setDefaultBackground(true);
            } else if (background.equalsIgnoreCase("transparent") || background.equalsIgnoreCase("none")) {
                td.setDefaultBackground(false);
                td.setBackgroundColor(org.bukkit.Color.fromARGB(0, 0, 0, 0));
            } else {
                org.bukkit.Color c = Colors.argb(background);
                td.setDefaultBackground(false);
                if (c != null) td.setBackgroundColor(c);
            }
        } else if (d instanceof ItemDisplay id) {
            id.setItemStack(item.clone());
            try {
                id.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.valueOf(itemTransform.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                id.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            }
        } else if (d instanceof BlockDisplay bd) {
            BlockData data;
            try {
                data = Bukkit.createBlockData(block);
            } catch (IllegalArgumentException e) {
                data = Material.STONE.createBlockData();
            }
            bd.setBlock(data);
        }
        applyTransform(d, factor, 0);
    }

    /** Scale (times factor), spin around Y, bob up/down; interpolated over interp ticks. */
    private void applyTransform(Display d, float factor, int interp) {
        double t = (System.currentTimeMillis() + interp * 50L) / 1000.0;
        float angle = spin == 0 ? 0f : (float) Math.toRadians((t * spin) % 360.0);
        float bobY = bob == 0 ? 0f : (float) (bob * Math.sin(t * Math.PI * 2 / 3.0));
        Quaternionf rot = new Quaternionf(new AxisAngle4f(angle, 0, 1, 0));
        float sx = scaleX * factor, sy = scaleY * factor, sz = scaleZ * factor;
        Vector3f translation = new Vector3f(0, bobY, 0);
        if (type == Type.BLOCK) {
            // rotate around the block's centre, not its corner
            Vector3f centre = new Vector3f(-0.5f * sx, 0, -0.5f * sz);
            rot.transform(centre);
            translation.add(centre);
        }
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(interp);
        d.setTransformation(new Transformation(translation, rot, new Vector3f(sx, sy, sz), new Quaternionf()));
    }

    private void startAnimation(Display d) {
        if (spin == 0 && bob == 0) return;
        int period = 20;
        if (spin != 0) period = (int) Math.max(2, Math.min(20, Math.floor(100.0 / Math.abs(spin) * 20)));
        if (bob != 0) period = Math.min(period, 10);
        int p = period;
        plugin.scheduler().runForEntityRepeating(d, () -> {
            if (!closing.contains(d)) applyTransform(d, 1f, p);
        }, 1L, p);
    }

    /** Throws every entity away; they respawn for nearby players on the next tracker tick. */
    public void respawnAll() {
        generation++;
        Display s = shared.getAndSet(null);
        Entities.remove(plugin, s);
        sharedSpawnAt.set(0);
        Interaction h = hitbox.getAndSet(null);
        if (h != null) {
            plugin.holograms().unregisterHitbox(h.getUniqueId());
            Entities.remove(plugin, h);
        }
        hitboxSpawnAt.set(0);
        for (Viewer v : viewers.values()) Entities.remove(plugin, v.entity);
        viewers.clear();
    }

    public void destroy() {
        removed = true;
        respawnAll();
    }

    // ------------------------------------------------------------------ persistence

    public void save(ConfigurationSection s) {
        s.set("world", world);
        s.set("x", x);
        s.set("y", y);
        s.set("z", z);
        s.set("yaw", (double) yaw);
        s.set("type", type.name());
        s.set("lines", new ArrayList<>(lines));
        s.set("item", item);
        s.set("block", block);
        s.set("billboard", billboard.name());
        s.set("scale", List.of((double) scaleX, (double) scaleY, (double) scaleZ));
        s.set("background", background);
        s.set("shadow", shadow);
        s.set("see-through", seeThrough);
        s.set("alignment", alignment.name());
        s.set("opacity", opacity);
        s.set("line-width", lineWidth);
        s.set("glow", glow);
        s.set("view-range", viewRange);
        s.set("update-ticks", updateTicks);
        s.set("personal", personal);
        s.set("proximity", proximity);
        s.set("spin", spin);
        s.set("bob", bob);
        s.set("brightness", brightness);
        s.set("item-transform", itemTransform);
        s.set("hitbox", hitboxW > 0 ? List.of((double) hitboxW, (double) hitboxH) : null);
        s.set("actions", actions.serialize());
        s.set("click-cooldown-ms", actions.cooldownMs());
    }

    public static Hologram load(DreamNFM plugin, String name, ConfigurationSection s) {
        Hologram h = new Hologram(plugin, name, false);
        h.world = s.getString("world", "world");
        h.x = s.getDouble("x");
        h.y = s.getDouble("y");
        h.z = s.getDouble("z");
        h.yaw = (float) s.getDouble("yaw");
        try {
            h.type = Type.valueOf(s.getString("type", "TEXT").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
        }
        h.lines.addAll(s.getStringList("lines"));
        ItemStack it = s.getItemStack("item");
        if (it != null) h.item = it;
        h.block = s.getString("block", h.block);
        try {
            h.billboard = Display.Billboard.valueOf(s.getString("billboard", "CENTER").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
        }
        List<Double> scale = s.getDoubleList("scale");
        if (scale.size() == 3) h.setScale(scale.get(0).floatValue(), scale.get(1).floatValue(), scale.get(2).floatValue());
        h.background = s.getString("background", "default");
        h.shadow = s.getBoolean("shadow");
        h.seeThrough = s.getBoolean("see-through");
        try {
            h.alignment = TextDisplay.TextAlignment.valueOf(s.getString("alignment", "CENTER").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
        }
        h.opacity = s.getInt("opacity", 255);
        h.lineWidth = s.getInt("line-width", 200);
        h.glow = s.getString("glow");
        h.viewRange = s.getDouble("view-range", 48);
        h.updateTicks = s.getInt("update-ticks", 20);
        h.personal = s.getBoolean("personal");
        h.proximity = s.getDouble("proximity");
        h.spin = s.getDouble("spin");
        h.bob = s.getDouble("bob");
        h.brightness = s.getInt("brightness", -1);
        h.itemTransform = s.getString("item-transform", "FIXED");
        List<Double> hit = s.getDoubleList("hitbox");
        if (hit.size() == 2) {
            h.hitboxW = hit.get(0).floatValue();
            h.hitboxH = hit.get(1).floatValue();
        }
        h.actions.load(s.getStringList("actions"), plugin.getLogger(), "Hologram " + name);
        h.actions.setCooldownMs(s.getLong("click-cooldown-ms", 500));
        return h;
    }

    // ------------------------------------------------------------------ accessors (changing one needs respawnAll() to show)

    public String name() { return name; }
    public boolean managed() { return managed; }
    public Type type() { return type; }
    public void setType(Type t) { type = t; }
    public List<String> lines() { return lines; }
    public ItemStack item() { return item.clone(); }
    public void setItem(ItemStack i) { item = i.clone(); item.setAmount(1); }
    public String block() { return block; }
    public void setBlock(String b) { block = b; }
    public void setLocation(Location l) {
        world = l.getWorld().getName();
        x = l.getX();
        y = l.getY();
        z = l.getZ();
        yaw = l.getYaw();
    }
    public void setPosition(String world, double x, double y, double z, float yaw) {
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
    }
    public Display.Billboard billboard() { return billboard; }
    public void setBillboard(Display.Billboard b) { billboard = b; }
    public void setScale(float sx, float sy, float sz) {
        scaleX = Math.max(0.01f, sx);
        scaleY = Math.max(0.01f, sy);
        scaleZ = Math.max(0.01f, sz);
    }
    public float scaleX() { return scaleX; }
    public String background() { return background; }
    public void setBackground(String b) { background = b; }
    public boolean shadow() { return shadow; }
    public void setShadow(boolean v) { shadow = v; }
    public boolean seeThrough() { return seeThrough; }
    public void setSeeThrough(boolean v) { seeThrough = v; }
    public TextDisplay.TextAlignment alignment() { return alignment; }
    public void setAlignment(TextDisplay.TextAlignment a) { alignment = a; }
    public int opacity() { return opacity; }
    public void setOpacity(int v) { opacity = v; }
    public int lineWidth() { return lineWidth; }
    public void setLineWidth(int v) { lineWidth = Math.max(10, v); }
    public String glow() { return glow; }
    public void setGlow(String g) { glow = g; }
    public double viewRangeSetting() { return viewRange; }
    public void setViewRange(double v) { viewRange = Math.max(1, v); }
    public int updateTicks() { return updateTicks; }
    public void setUpdateTicks(int v) { updateTicks = Math.max(0, v); }
    public boolean personal() { return personal; }
    public void setPersonal(boolean v) { personal = v; }
    public double proximity() { return proximity; }
    public void setProximity(double v) { proximity = Math.max(0, v); }
    public double spin() { return spin; }
    public void setSpin(double v) { spin = v; }
    public double bob() { return bob; }
    public void setBob(double v) { bob = Math.max(0, v); }
    public int brightness() { return brightness; }
    public void setBrightness(int v) { brightness = v; }
    public String itemTransform() { return itemTransform; }
    public void setItemTransform(String v) { itemTransform = v; }
    public void setHitbox(float w, float h) { hitboxW = w; hitboxH = h; }
    public ActionList actions() { return actions; }
    public int viewerCount() { return viewers.size(); }
}
