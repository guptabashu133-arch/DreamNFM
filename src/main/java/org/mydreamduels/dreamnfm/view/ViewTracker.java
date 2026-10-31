package org.mydreamduels.dreamnfm.view;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.mydreamduels.dreamnfm.DreamNFM;
import org.mydreamduels.dreamnfm.platform.CancelHandle;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * One small repeating task per online player, on that player's own thread.
 * It tells every Viewable in the player's world whether the player is in
 * range; viewables spawn/show/hide/remove their entities from there.
 *
 * Driving everything from players means entities only exist where someone
 * can see them, and spawning always targets chunks a player has loaded -
 * which on Folia guarantees the region exists to run the spawn.
 */
public final class ViewTracker implements Listener {

    private final DreamNFM plugin;
    private final List<Viewable> viewables = new CopyOnWriteArrayList<>();
    private final Map<UUID, CancelHandle> tasks = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastWorld = new ConcurrentHashMap<>();

    public ViewTracker(DreamNFM plugin) {
        this.plugin = plugin;
    }

    public void register(Viewable v) {
        viewables.add(v);
    }

    public void unregister(Viewable v) {
        viewables.remove(v);
        for (Player p : plugin.getServer().getOnlinePlayers()) plugin.scheduler().runForEntity(p, () -> v.forget(p));
    }

    public List<Viewable> all() {
        return viewables;
    }

    public void start(Player p) {
        stop(p);
        long period = Math.max(2, plugin.getConfig().getLong("performance.tracker-ticks", 5));
        CancelHandle h = plugin.scheduler().runForEntityRepeating(p, () -> tick(p), 2L, period);
        if (h != null) tasks.put(p.getUniqueId(), h);
    }

    public void stop(Player p) {
        CancelHandle h = tasks.remove(p.getUniqueId());
        if (h != null) h.cancel();
    }

    private void tick(Player p) {
        if (!p.isOnline()) return;
        Location l = p.getLocation();
        String world = l.getWorld().getName();
        String before = lastWorld.put(p.getUniqueId(), world);
        if (before != null && !before.equals(world)) {
            for (Viewable v : viewables) v.forget(p);
        }
        for (Viewable v : viewables) {
            try {
                if (!v.worldName().equals(world)) continue;
                double dx = v.x() - l.getX(), dy = v.y() - l.getY(), dz = v.z() - l.getZ();
                double d2 = dx * dx + dy * dy + dz * dz;
                double r = v.viewRange();
                v.update(p, d2 <= r * r, d2);
            } catch (Throwable t) {
                plugin.getLogger().log(java.util.logging.Level.WARNING, "View update failed", t);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        start(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        stop(p);
        lastWorld.remove(p.getUniqueId());
        for (Viewable v : viewables) v.forget(p);
    }

    public void shutdown() {
        for (CancelHandle h : tasks.values()) h.cancel();
        tasks.clear();
    }
}
