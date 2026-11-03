package org.mydreamduels.dreamscreens;

import java.util.List;

/**
 * NPCs live in a separate part of the jar (they need the 1.21.9+ Mannequin
 * entity and Java 21). The core only talks to them through this interface,
 * so older servers never load a single NPC class.
 */
public interface NpcService {

    /** Registers listeners/commands and loads npcs.yml. */
    void start();

    void shutdown();

    List<String> names();

    boolean available();

    NpcService NONE = new NpcService() {
        public void start() {
        }

        public void shutdown() {
        }

        public List<String> names() {
            return List.of();
        }

        public boolean available() {
            return false;
        }
    };

    static boolean serverHasMannequins() {
        try {
            Class.forName("org.bukkit.entity.Mannequin");
            Class.forName("com.destroystokyo.paper.profile.PlayerProfile");
            return Runtime.version().feature() >= 21;
        } catch (Throwable t) {
            return false;
        }
    }

    static NpcService create(DreamScreens plugin) {
        if (!serverHasMannequins()) return NONE;
        try {
            return (NpcService) Class.forName("org.mydreamduels.dreamscreens.npc.NpcManager")
                    .getConstructor(DreamScreens.class).newInstance(plugin);
        } catch (Throwable t) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "NPCs could not be started", t);
            return NONE;
        }
    }
}
