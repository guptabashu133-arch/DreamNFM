package org.mydreamduels.dreamscreens.npc;

import com.destroystokyo.paper.profile.ProfileProperty;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.mydreamduels.dreamscreens.DreamScreens;
import org.mydreamduels.dreamscreens.action.ActionList;
import org.mydreamduels.dreamscreens.hologram.Hologram;
import org.mydreamduels.dreamscreens.platform.PlatformSupport;
import org.mydreamduels.dreamscreens.view.Entities;
import org.mydreamduels.dreamscreens.view.Viewable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * An NPC built on Minecraft's own Mannequin entity (1.21.9+) - real skins,
 * equipment and poses with no packet hacks, so it works on Folia as-is.
 * Above its head it can stack a name, a spinning icon and a proximity
 * info panel (all managed holograms).
 */
public final class Npc implements Viewable {

    private final DreamScreens plugin;
    private final String name;

    private volatile String world = "world";
    private volatile double x, y, z;
    private volatile float yaw, pitch;
    private volatile String displayName = "";
    private volatile String skinName;
    private volatile String skinValue;
    private volatile String skinSignature;
    private volatile boolean lookAt = true;
    private volatile double lookRadius = 6;
    private volatile String glow;
    private volatile double scale = 1;
    private volatile String pose = "STANDING";
    private final Map<EquipmentSlot, ItemStack> equipment = new EnumMap<>(EquipmentSlot.class);
    private final ActionList actions = new ActionList();
    private volatile ItemStack icon;
    private volatile double iconSpin = 60;
    private final List<String> panel = new CopyOnWriteArrayList<>();
    private volatile double panelRadius = 5;
    private volatile String panelBackground = "#C0141022";
    private volatile double viewRange = 48;

    private final AtomicReference<Mannequin> entity = new AtomicReference<>();
    private final AtomicLong spawnAt = new AtomicLong();
    private volatile int generation;
    private volatile boolean removed;
    private final List<Hologram> overheads = new CopyOnWriteArrayList<>();

    public Npc(DreamScreens plugin, String name) {
        this.plugin = plugin;
        this.name = name;
    }

    // ------------------------------------------------------------------ Viewable

    @Override public String worldName() { return world; }
    @Override public double x() { return x; }
    @Override public double y() { return y; }
    @Override public double z() { return z; }
    @Override public double viewRange() { return viewRange; }

    @Override
    public void update(Player p, boolean inRange, double distanceSq) {
        if (removed || !inRange) return;
        Mannequin m = entity.get();
        if (m != null && m.isValid()) return;
        long now = System.currentTimeMillis();
        long started = spawnAt.get();
        if (started != 0 && now - started < 5000) return;
        if (!spawnAt.compareAndSet(started, now)) return;
        Location loc = location();
        if (loc == null) return;
        int gen = generation;
        Entities.spawn(plugin, loc, Mannequin.class, "npc:" + name, this::configure, spawned -> {
            if (gen != generation || removed) {
                spawned.remove();
                return;
            }
            Mannequin old = entity.getAndSet(spawned);
            if (old != null && old != spawned) Entities.remove(plugin, old);
            spawnAt.set(0);
            applyGlowTeam(spawned);
            if (lookAt) startLooking(spawned);
        });
    }

    @Override
    public void forget(Player p) {
        actions.forget(p.getUniqueId());
    }

    public Location location() {
        World w = Bukkit.getWorld(world);
        return w == null ? null : new Location(w, x, y, z, yaw, pitch);
    }

    /** Entity thread. */
    private void configure(Mannequin m) {
        m.setImmovable(true);
        m.setGravity(false);
        m.setInvulnerable(true);
        m.setSilent(true);
        m.setCollidable(false);
        m.setRemoveWhenFarAway(false);
        m.setCustomNameVisible(false);
        m.setDescription(Component.empty());
        m.setProfile(profile());
        m.setRotation(yaw, pitch);
        m.setBodyYaw(yaw);
        EntityEquipment eq = m.getEquipment();
        for (Map.Entry<EquipmentSlot, ItemStack> e : equipment.entrySet()) {
            eq.setItem(e.getKey(), e.getValue().clone());
            eq.setDropChance(e.getKey(), 0f);
        }
        try {
            Pose p = Pose.valueOf(pose.toUpperCase(Locale.ROOT));
            if (Mannequin.validPoses().contains(p)) m.setPose(p, true);
        } catch (IllegalArgumentException ignored) {
        }
        AttributeInstance sc = m.getAttribute(Attribute.SCALE);
        if (sc != null) sc.setBaseValue(Math.max(0.1, Math.min(10, scale)));
        if (glow != null) m.setGlowing(true);
    }

    private ResolvableProfile profile() {
        ResolvableProfile.Builder b = ResolvableProfile.resolvableProfile();
        if (skinName != null && !skinName.isBlank()) b.name(skinName);
        if (skinValue != null && !skinValue.isBlank()) b.addProperty(new ProfileProperty("textures", skinValue, skinSignature));
        return b.build();
    }

    /** Glow colour needs a scoreboard team - Folia has no scoreboards, so there it glows white. */
    private void applyGlowTeam(Mannequin m) {
        if (glow == null || PlatformSupport.isFolia()) return;
        plugin.scheduler().runGlobal(() -> {
            try {
                NamedTextColor color = NamedTextColor.NAMES.value(glow.toLowerCase(Locale.ROOT));
                if (color == null) return;
                var board = Bukkit.getScoreboardManager().getMainScoreboard();
                String teamName = "dscreens_" + glow.toLowerCase(Locale.ROOT);
                var team = board.getTeam(teamName);
                if (team == null) team = board.registerNewTeam(teamName);
                team.color(color);
                team.addEntry(m.getUniqueId().toString());
            } catch (Throwable ignored) {
                // scoreboard unavailable - plain white glow it is
            }
        });
    }

    private void startLooking(Mannequin m) {
        plugin.scheduler().runForEntityRepeating(m, () -> {
            Player nearest = null;
            double best = lookRadius * lookRadius;
            for (Entity e : m.getNearbyEntities(lookRadius, lookRadius, lookRadius)) {
                if (!(e instanceof Player p) || p.getGameMode() == org.bukkit.GameMode.SPECTATOR) continue;
                double d = p.getLocation().distanceSquared(m.getLocation());
                if (d < best) {
                    best = d;
                    nearest = p;
                }
            }
            if (nearest == null) {
                m.setRotation(yaw, pitch);
                m.setBodyYaw(yaw);
                return;
            }
            Location eye = m.getEyeLocation();
            Location target = nearest.getEyeLocation();
            double dx = target.getX() - eye.getX(), dy = target.getY() - eye.getY(), dz = target.getZ() - eye.getZ();
            float lookYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float lookPitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
            m.setRotation(lookYaw, lookPitch);
            // turn the body only when the head would twist too far
            float diff = wrap(lookYaw - yaw);
            m.setBodyYaw(Math.abs(diff) > 60 ? lookYaw - Math.signum(diff) * 60 : yaw);
        }, 5L, 3L);
    }

    private static float wrap(float a) {
        a %= 360;
        if (a > 180) a -= 360;
        if (a < -180) a += 360;
        return a;
    }

    // ------------------------------------------------------------------ overhead holograms

    /** (Re)builds name / icon / panel above the head. */
    public void rebuildOverheads() {
        for (Hologram h : overheads) plugin.holograms().remove(h.name());
        overheads.clear();
        if (removed) return;
        double cursor = y + 1.85 * scale + 0.1;
        if (!displayName.isBlank()) {
            Hologram h = new Hologram(plugin, "npc:" + name + ":name", true);
            h.setPosition(world, x, cursor, z, yaw);
            h.lines().add(displayName);
            h.setBackground("transparent");
            h.setShadow(true);
            h.setViewRange(Math.min(viewRange, 32));
            add(h);
            cursor += 0.3 + 0.27 * Math.max(0, displayName.split("\\\\n|\n").length - 1);
        }
        if (icon != null && !icon.getType().isAir()) {
            Hologram h = new Hologram(plugin, "npc:" + name + ":icon", true);
            h.setPosition(world, x, cursor + 0.35, z, yaw);
            h.setType(Hologram.Type.ITEM);
            h.setItem(icon);
            h.setScale(0.6f, 0.6f, 0.6f);
            h.setSpin(iconSpin);
            h.setBob(0.06);
            h.setViewRange(viewRange);
            add(h);
            cursor += 0.75;
        }
        if (!panel.isEmpty()) {
            Hologram h = new Hologram(plugin, "npc:" + name + ":panel", true);
            h.setPosition(world, x, cursor + 0.05, z, yaw);
            h.lines().addAll(panel);
            h.setBackground(panelBackground);
            h.setProximity(panelRadius);
            h.setUpdateTicks(20);
            h.setShadow(true);
            add(h);
        }
    }

    private void add(Hologram h) {
        overheads.add(h);
        plugin.holograms().add(h);
    }

    public void respawn() {
        generation++;
        Mannequin m = entity.getAndSet(null);
        Entities.remove(plugin, m);
        spawnAt.set(0);
        rebuildOverheads();
    }

    public void destroy() {
        removed = true;
        generation++;
        Entities.remove(plugin, entity.getAndSet(null));
        for (Hologram h : overheads) plugin.holograms().remove(h.name());
        overheads.clear();
    }

    // ------------------------------------------------------------------ persistence

    public void save(ConfigurationSection s) {
        s.set("world", world);
        s.set("x", x);
        s.set("y", y);
        s.set("z", z);
        s.set("yaw", (double) yaw);
        s.set("pitch", (double) pitch);
        s.set("display-name", displayName);
        s.set("skin.name", skinName);
        s.set("skin.value", skinValue);
        s.set("skin.signature", skinSignature);
        s.set("look-at", lookAt);
        s.set("look-radius", lookRadius);
        s.set("glow", glow);
        s.set("scale", scale);
        s.set("pose", pose);
        for (Map.Entry<EquipmentSlot, ItemStack> e : equipment.entrySet()) s.set("equipment." + e.getKey().name(), e.getValue());
        s.set("actions", actions.serialize());
        s.set("click-cooldown-ms", actions.cooldownMs());
        s.set("icon", icon);
        s.set("icon-spin", iconSpin);
        s.set("panel", new ArrayList<>(panel));
        s.set("panel-radius", panelRadius);
        s.set("panel-background", panelBackground);
        s.set("view-range", viewRange);
    }

    public static Npc load(DreamScreens plugin, String name, ConfigurationSection s) {
        Npc n = new Npc(plugin, name);
        n.world = s.getString("world", "world");
        n.x = s.getDouble("x");
        n.y = s.getDouble("y");
        n.z = s.getDouble("z");
        n.yaw = (float) s.getDouble("yaw");
        n.pitch = (float) s.getDouble("pitch");
        n.displayName = s.getString("display-name", "");
        n.skinName = s.getString("skin.name");
        n.skinValue = s.getString("skin.value");
        n.skinSignature = s.getString("skin.signature");
        n.lookAt = s.getBoolean("look-at", true);
        n.lookRadius = s.getDouble("look-radius", 6);
        n.glow = s.getString("glow");
        n.scale = s.getDouble("scale", 1);
        n.pose = s.getString("pose", "STANDING");
        ConfigurationSection eq = s.getConfigurationSection("equipment");
        if (eq != null) {
            for (String k : eq.getKeys(false)) {
                try {
                    ItemStack it = eq.getItemStack(k);
                    if (it != null) n.equipment.put(EquipmentSlot.valueOf(k), it);
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        n.actions.load(s.getStringList("actions"), plugin.getLogger(), "NPC " + name);
        n.actions.setCooldownMs(s.getLong("click-cooldown-ms", 500));
        n.icon = s.getItemStack("icon");
        n.iconSpin = s.getDouble("icon-spin", 60);
        n.panel.addAll(s.getStringList("panel"));
        n.panelRadius = s.getDouble("panel-radius", 5);
        n.panelBackground = s.getString("panel-background", n.panelBackground);
        n.viewRange = s.getDouble("view-range", 48);
        return n;
    }

    // ------------------------------------------------------------------ accessors

    public String name() { return name; }
    public void setLocation(Location l) {
        world = l.getWorld().getName();
        x = l.getX();
        y = l.getY();
        z = l.getZ();
        yaw = l.getYaw();
        pitch = 0;
    }
    public String displayName() { return displayName; }
    public void setDisplayName(String v) { displayName = v == null ? "" : v; }
    public String skinName() { return skinName; }
    public void setSkin(String skinName, String value, String signature) {
        this.skinName = skinName;
        this.skinValue = value;
        this.skinSignature = signature;
    }
    public boolean hasSkinTexture() { return skinValue != null && !skinValue.isBlank(); }
    public boolean lookAt() { return lookAt; }
    public void setLookAt(boolean v) { lookAt = v; }
    public void setLookRadius(double v) { lookRadius = Math.max(1, Math.min(32, v)); }
    public String glow() { return glow; }
    public void setGlow(String v) { glow = v; }
    public double scale() { return scale; }
    public void setScale(double v) { scale = v; }
    public String pose() { return pose; }
    public void setPose(String v) { pose = v; }
    public Map<EquipmentSlot, ItemStack> equipment() { return equipment; }
    public ActionList actions() { return actions; }
    public ItemStack icon() { return icon == null ? null : icon.clone(); }
    public void setIcon(ItemStack v) {
        icon = v == null ? null : v.clone();
        if (icon != null) icon.setAmount(1);
    }
    public void setIconSpin(double v) { iconSpin = v; }
    public List<String> panel() { return panel; }
    public void setPanelRadius(double v) { panelRadius = Math.max(1, v); }
    public void setPanelBackground(String v) { panelBackground = v; }
    public void setViewRange(double v) { viewRange = Math.max(8, v); }
    public static ItemStack defaultIcon() { return new ItemStack(Material.NETHER_STAR); }
}
