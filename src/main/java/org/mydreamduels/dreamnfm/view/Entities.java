package org.mydreamduels.dreamnfm.view;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataType;
import org.mydreamduels.dreamnfm.DreamNFM;

import java.util.function.Consumer;

/**
 * Spawning/removing plugin entities on the correct thread. Everything is
 * non-persistent (never saved with the chunk, so no leftovers after a crash)
 * and tagged so stray ones can always be recognised and cleaned up.
 */
public final class Entities {

    private static NamespacedKey key;

    private Entities() {
    }

    public static void init(DreamNFM plugin) {
        key = new NamespacedKey(plugin, "managed");
    }

    public static NamespacedKey key() {
        return key;
    }

    /** Spawns on the location's thread; done runs there too (with the entity). */
    public static <T extends Entity> void spawn(DreamNFM plugin, Location at, Class<T> type, String tag, Consumer<T> configure, Consumer<T> done) {
        plugin.scheduler().runAtLocation(at, () -> {
            try {
                if (at.getWorld() == null) return;
                T e = at.getWorld().spawn(at, type, ent -> {
                    ent.setPersistent(false);
                    ent.getPersistentDataContainer().set(key, PersistentDataType.STRING, tag);
                    configure.accept(ent);
                });
                if (done != null) done.accept(e);
            } catch (Throwable t) {
                plugin.getLogger().log(java.util.logging.Level.WARNING, "Could not spawn " + type.getSimpleName() + " for " + tag, t);
            }
        });
    }

    public static void remove(DreamNFM plugin, Entity e) {
        if (e == null) return;
        plugin.scheduler().runForEntity(e, () -> {
            if (e.isValid()) e.remove();
        });
    }

    /** Our tag on an entity, or null. */
    public static String tag(Entity e) {
        return e.getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }
}
