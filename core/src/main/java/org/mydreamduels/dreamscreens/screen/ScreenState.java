package org.mydreamduels.dreamscreens.screen;

import org.bukkit.map.MapCanvas;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

/**
 * What one player currently sees on one screen. Guarded by its own monitor:
 * written on the player's thread, read by the map renderer (which runs on the
 * thread of whoever is tracking the frames - the same region on Folia).
 */
final class ScreenState {

    String page;
    final Deque<String> history = new ArrayDeque<>();
    long pageSince = System.currentTimeMillis();
    boolean idle;

    int hover = -1;
    int pressed = -1;
    long pressedUntil;
    int cursorX = -1, cursorY = -1;
    boolean cursorHot;
    long lastTouch;

    long lastBuild;
    int builtGeneration = -1;
    String builtPage;
    PageRenderer.Result result;
    byte[] frame;
    byte[] spare;
    long lastBar;

    final int[] version;
    final int[] sent;
    final int[] drawn;
    final MapCanvas[] canvas;

    ScreenState(int cells, String page) {
        this.page = page;
        this.version = new int[cells];
        this.sent = new int[cells];
        this.drawn = new int[cells];
        this.canvas = new MapCanvas[cells];
        Arrays.fill(version, 1);
    }

    void open(String next, boolean remember) {
        if (next == null || next.equalsIgnoreCase(page)) return;
        if (remember && page != null) {
            history.push(page);
            while (history.size() > 16) history.removeLast();
        }
        page = next;
        pageSince = System.currentTimeMillis();
        hover = -1;
    }

    String back() {
        return history.isEmpty() ? null : history.pop();
    }
}
