package org.mydreamduels.dreamscreens.util;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;

/** Paper-only text calls, in their own class so Spigot never loads it. */
final class PaperText {

    private PaperText() {
    }

    static void display(TextDisplay td, String legacy) {
        td.text(LegacyComponentSerializer.legacySection().deserialize(legacy));
    }

    static void actionBar(Player p, String legacy) {
        p.sendActionBar(LegacyComponentSerializer.legacySection().deserialize(legacy));
    }
}
