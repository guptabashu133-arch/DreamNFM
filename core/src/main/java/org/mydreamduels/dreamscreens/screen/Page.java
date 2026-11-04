package org.mydreamduels.dreamscreens.screen;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/** One page of a screen. */
public record Page(String name, String background, String backgroundImage, int refreshTicks, String next, int seconds, List<Element> elements,
                   boolean dynamic, boolean animated, String sound) {

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

    /** Moving parts (scrolling text, GIFs) - these pages redraw several times a second for players nearby. */
    static boolean isAnimated(String backgroundImage, List<Element> els) {
        if (gif(backgroundImage)) return true;
        for (Element e : els) {
            if (e.broken != null) continue;
            if (e.scroll) return true;
            if (e.type == Element.Type.IMAGE && gif(e.file)) return true;
            if (e.type == Element.Type.BUTTON && gif(e.icon)) return true;
        }
        return false;
    }

    static boolean gif(String file) {
        return file != null && file.toLowerCase(Locale.ROOT).endsWith(".gif");
    }

    private static boolean live(String s) {
        return s != null && (s.indexOf('{') >= 0 || s.indexOf('%') >= 0 || s.contains("<status:"));
    }

    /** Show a page while a condition holds, e.g. when: "%koth_is_running% == true". */
    public record AutoRule(String page, String when) {
    }

    /** Show a page between two times of day (server clock), e.g. 20:00 - 22:00. Wraps past midnight. */
    public record TimeRule(String page, LocalTime from, LocalTime to) {
        public boolean active(LocalTime now) {
            return from.isBefore(to) || from.equals(to) ? !now.isBefore(from) && now.isBefore(to) : !now.isBefore(from) || now.isBefore(to);
        }

        public String describe() {
            return page + " " + from + "-" + to;
        }
    }

    /** All pages of one screen (screens/<name>.yml). */
    public record Set(String start, String idlePage, double wakeRange, String clickSound, Map<String, Page> pages,
                      boolean sync, String transition, int transitionTicks, String switchSound,
                      List<AutoRule> auto, List<TimeRule> schedule) {

        public static final Set EMPTY = new Set("home", null, 0, null, Map.of(), false, "none", 8, null, List.of(), List.of());
        public static final List<String> TRANSITIONS = List.of("none", "wipe", "slide", "down", "split");

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

        /**
         * Slideshow driven by the clock: the pages reached from 'from' by
         * following next:, and which one is up at this moment. Everyone (and
         * every linked screen) sees the same page at the same time.
         * Returns null when 'from' isn't part of a slideshow.
         */
        public Page syncedPage(String from, long nowMs) {
            Page p = page(from);
            if (p == null || p.next() == null) return null;
            List<Page> loop = new ArrayList<>();
            Page cur = p;
            // walk forward until we come back round (or the chain ends)
            while (cur != null && !loop.contains(cur) && loop.size() < 64) {
                loop.add(cur);
                cur = cur.next() == null ? null : page(cur.next());
            }
            // keep only the repeating part, then always start it at the same page (the start page if it's in
            // the loop, else the one listed first in the file) so every viewer gets the same answer
            if (cur != null && loop.contains(cur)) loop = new ArrayList<>(loop.subList(loop.indexOf(cur), loop.size()));
            Page anchor = page(start) != null && loop.contains(page(start)) ? page(start) : null;
            if (anchor == null) {
                for (Page q : pages.values()) {
                    if (loop.contains(q)) {
                        anchor = q;
                        break;
                    }
                }
            }
            int at = loop.indexOf(anchor);
            if (at > 0) {
                List<Page> rotated = new ArrayList<>(loop.subList(at, loop.size()));
                rotated.addAll(loop.subList(0, at));
                loop = rotated;
            }
            long total = 0;
            for (Page q : loop) total += q.seconds() * 1000L;
            if (total <= 0) return p;
            long t = Math.floorMod(nowMs, total);
            for (Page q : loop) {
                t -= q.seconds() * 1000L;
                if (t < 0) return q;
            }
            return loop.get(loop.size() - 1);
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
                    String bgImage = s.getString("background-image", null);
                    pages.put(name.toLowerCase(Locale.ROOT), new Page(name, bg, bgImage, Math.max(0, s.getInt("refresh", 20)),
                            s.getString("next", null), Math.max(1, s.getInt("seconds", 10)), Collections.unmodifiableList(els),
                            isDynamic(bg, els), isAnimated(bgImage, els), s.getString("sound", null)));
                }
            }
            if (pages.isEmpty()) log.warning(where + " has no pages.");

            String transition = y.getString("transition", "none").toLowerCase(Locale.ROOT);
            if (!TRANSITIONS.contains(transition)) {
                log.warning(where + ": transition '" + transition + "' unknown (use " + String.join(", ", TRANSITIONS) + ")");
                transition = "none";
            }
            List<AutoRule> auto = new ArrayList<>();
            for (Map<?, ?> m : y.getMapList("auto-pages")) {
                Object page = m.get("page"), when = m.get("when");
                if (page == null || when == null) {
                    log.warning(where + ": auto-pages entries need 'page' and 'when'");
                    continue;
                }
                if (!pages.containsKey(page.toString().toLowerCase(Locale.ROOT))) {
                    log.warning(where + ": auto-pages points to missing page '" + page + "'");
                    continue;
                }
                auto.add(new AutoRule(page.toString(), when.toString()));
            }
            List<TimeRule> schedule = new ArrayList<>();
            for (Map<?, ?> m : y.getMapList("schedule")) {
                Object page = m.get("page"), from = m.get("from"), to = m.get("to");
                try {
                    if (page == null || from == null || to == null) throw new IllegalArgumentException("needs page, from and to");
                    if (!pages.containsKey(page.toString().toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("missing page '" + page + "'");
                    schedule.add(new TimeRule(page.toString(), time(from), time(to)));
                } catch (IllegalArgumentException | DateTimeParseException e) {
                    log.warning(where + ": schedule entry skipped - " + e.getMessage());
                }
            }
            return new Set(y.getString("start", "home"), y.getString("idle-page", null), y.getDouble("wake-range", 0),
                    y.getString("click-sound", "ui.button.click"), Collections.unmodifiableMap(pages),
                    y.getBoolean("sync", false), transition, Math.max(2, Math.min(40, y.getInt("transition-ticks", 8))),
                    y.getString("switch-sound", null), List.copyOf(auto), List.copyOf(schedule));
        }

        /** "8:00", "08:00", "20:30" (YAML may also hand us 480 = 8:00 in minutes for unquoted 8:00). */
        static LocalTime time(Object raw) {
            if (raw instanceof Number n) {
                int minutes = n.intValue();
                return LocalTime.of((minutes / 60) % 24, minutes % 60);
            }
            String t = raw.toString().trim();
            if (t.matches("\\d:\\d\\d")) t = "0" + t;
            return LocalTime.parse(t);
        }
    }
}
