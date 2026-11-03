package org.mydreamduels.dreamscreens.view;

import org.bukkit.entity.Player;

/** Something that appears near players (hologram, NPC, screen). Called on the player's own thread. */
public interface Viewable {

    String worldName();

    double x();

    double y();

    double z();

    /** Blocks. */
    double viewRange();

    /** Every tracker tick for players in the same world. inRange = within viewRange. */
    void update(Player player, boolean inRange, double distanceSq);

    /** Player left / changed world / plugin reload. */
    void forget(Player player);
}
