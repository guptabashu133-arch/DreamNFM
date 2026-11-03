package org.mydreamduels.dreamscreens.screen;

import org.bukkit.entity.Player;
import org.mydreamduels.dreamscreens.util.Text;

/** One player editing one screen: which page, which element is selected, grid on/off, where they point. */
public final class EditSession {

    final Screen screen;
    volatile String page;
    volatile int selected = -1;
    volatile boolean grid;
    volatile int cursorX = -1, cursorY = -1;

    EditSession(Screen screen, String page) {
        this.screen = screen;
        this.page = page;
    }

    public Screen screen() {
        return screen;
    }

    public String page() {
        return page;
    }

    public int selected() {
        return selected;
    }

    static void actionBar(Player p, String text) {
        Text.actionBar(p, text);
    }
}
