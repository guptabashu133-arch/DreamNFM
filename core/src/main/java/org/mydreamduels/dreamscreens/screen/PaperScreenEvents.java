package org.mydreamduels.dreamscreens.screen;

import io.papermc.paper.event.player.PlayerItemFrameChangeEvent;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/** Paper-only: punching a frame is a touch (never damage), and maps can't be taken out. Not loaded on Spigot. */
final class PaperScreenEvents implements Listener {

    private final ScreenManager screens;

    PaperScreenEvents(ScreenManager screens) {
        this.screens = screens;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onAttack(PrePlayerAttackEntityEvent e) {
        if (!ScreenManager.isOurs(e.getAttacked())) return;
        e.setCancelled(true);
        Screen s = screens.screenOf(e.getAttacked());
        if (s != null) s.touch(e.getPlayer(), true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onFrameChange(PlayerItemFrameChangeEvent e) {
        if (ScreenManager.isOurs(e.getItemFrame())) e.setCancelled(true);
    }
}
