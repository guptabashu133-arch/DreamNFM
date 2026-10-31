package org.mydreamduels.dreamnfm.screen;

import org.bukkit.entity.Player;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

/** Renders one 128x128 cell of a screen, per player (contextual). */
final class ScreenRenderer extends MapRenderer {

    final Screen screen;
    final int cell;

    ScreenRenderer(Screen screen, int cell) {
        super(true);
        this.screen = screen;
        this.cell = cell;
    }

    @Override
    public void render(MapView map, MapCanvas canvas, Player player) {
        screen.renderCell(cell, canvas, player);
    }
}
