package org.mydreamduels.dreamscreens.screen;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/** One page of a screen. */
public record Page(String name, String background, String backgroundImage, int refreshTicks, String next, int seconds, List<Element> elements,
                   boolean dynamic) {

    /** True if anything on the page can change by itself (placeholders, graphs, heads) - static pages never redraw on a timer. */
    static boolean isDynamic(String background, List<Element> els) {
        if (live(background)) return true;
        for (Element e : els) {
            if (e.type == Element.Type.GRAPH || e.type == Element.Type.HEAD || e.type == Element.Type.PROGRESS) return true;
            if (live(e.text) || live(e.color) || live(e.hoverColor) || live(e.textColor) || live(e.background) || live(e.value) || live(e.max)) return true;
            for (String l : e.lines) if (live(l)) return true;
        }
        return false;
    }

    private static boolean live(String s) {
        return s != null && (s.indexOf('{') >= 0 || s.indexOf('%') >= 0 || s.contains("<status:"));
    }

    /** All pages of one screen (screens/<name>.yml). */
    public record Set(String start, String idlePage, double wakeRange, String clickSound, Map<String, Page> pages) {

        public static final Set EMPTY = new Set("home", null, 0, null, Map.of());

        public Page page(String name) {
            return name == null ? null : pages.get(name.toLowerCase(Locale.ROOT));
        }

        public Page startPage() {
            Page p = page(start);
            if (p != null) return p;
            return pages.isEmpty() ? null : pages.values().iterator().next();
        }

        public List<String> names() {
            List<String> out = new ArrayList<>();
            for (Page p : pages.values()) out.add(p.name());
            return out;
        }

        public static Set load(File file, Logger log) {
            if (!file.exists()) return EMPTY;
            return parse(YamlConfiguration.loadConfiguration(file), log, file.getName());
        }

        public static Set parse(ConfigurationSection y, Logger log, String where) {
            Map<String, Page> pages = new LinkedHashMap<>();
            ConfigurationSection root = y.getConfigurationSection("pages");
            if (root != null) {
                for (String name : root.getKeys(false)) {
                    ConfigurationSection s = root.getConfigurationSection(name);
                    if (s == null) continue;
                    List<Element> els = new ArrayList<>();
                    List<Map<?, ?>> raw = s.getMapList("elements");
                    for (int i = 0; i < raw.size(); i++) {
                        String at = where + " page '" + name + "' element #" + (i + 1);
                        try {
                            els.add(Element.parse(raw.get(i), log, at));
                        } catch (IllegalArgumentException e) {
                            log.warning(at + " skipped: " + e.getMessage());
                            els.add(Element.broken(e.getMessage()));
                        }
                    }
                    String bg = s.getString("background", "#14161F");
                    pages.put(name.toLowerCase(Locale.ROOT), new Page(name, bg,
                            s.getString("background-image", null), Math.max(0, s.getInt("refresh", 20)),
                            s.getString("next", null), Math.max(1, s.getInt("seconds", 10)), Collections.unmodifiableList(els),
                            isDynamic(bg, els)));
                }
            }
            if (pages.isEmpty()) log.warning(where + " has no pages.");
            return new Set(y.getString("start", "home"), y.getString("idle-page", null), y.getDouble("wake-range", 0),
                    y.getString("click-sound", "ui.button.click"), Collections.unmodifiableMap(pages));
        }
    }
}
