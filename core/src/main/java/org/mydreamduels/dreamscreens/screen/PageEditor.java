package org.mydreamduels.dreamscreens.screen;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.mydreamduels.dreamscreens.util.FileUtil;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Changes a screen's page file (screens/<name>.yml) the same way a person
 * would by hand, so commands and hand edits always agree. Every change is
 * checked before it is saved, and the previous file goes on the undo stack.
 */
final class PageEditor {

    private final Screen screen;
    private final File file;
    private final String before;
    final YamlConfiguration yaml = new YamlConfiguration();

    PageEditor(Screen screen) throws IOException {
        this.screen = screen;
        this.file = screen.pagesFile();
        this.before = file.exists() ? Files.readString(file.toPath(), StandardCharsets.UTF_8) : "";
        try {
            yaml.loadFromString(before);
        } catch (InvalidConfigurationException e) {
            throw new IOException("screens/" + file.getName() + " is not valid YAML (fix it or /se undo): " + e.getMessage());
        }
    }

    String previous() {
        return before;
    }

    // ------------------------------------------------------------ pages

    List<String> pageNames() {
        ConfigurationSection root = yaml.getConfigurationSection("pages");
        return root == null ? new ArrayList<>() : new ArrayList<>(root.getKeys(false));
    }

    /** Exact key of a page (case-insensitive lookup), or null. */
    String pageKey(String name) {
        for (String n : pageNames()) if (n.equalsIgnoreCase(name)) return n;
        return null;
    }

    ConfigurationSection page(String name) {
        String k = pageKey(name);
        return k == null ? null : yaml.getConfigurationSection("pages." + k);
    }

    ConfigurationSection createPage(String name, String background) {
        ConfigurationSection s = yaml.createSection("pages." + name);
        s.set("background", background);
        s.set("refresh", 20);
        s.set("elements", new ArrayList<>());
        return s;
    }

    void copyPage(String from, String to) {
        ConfigurationSection src = page(from);
        ConfigurationSection dst = yaml.createSection("pages." + to);
        for (String k : src.getKeys(false)) {
            Object v = src.get(k);
            dst.set(k, k.equals("elements") ? elements(from) : v);
        }
    }

    void removePage(String name) {
        yaml.set("pages." + pageKey(name), null);
    }

    // ------------------------------------------------------------ elements

    /** Mutable deep-ish copy of a page's elements. */
    List<Map<String, Object>> elements(String page) {
        String k = pageKey(page);
        List<Map<String, Object>> out = new ArrayList<>();
        if (k == null) return out;
        for (Map<?, ?> m : yaml.getMapList("pages." + k + ".elements")) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                Object v = e.getValue();
                if (v instanceof List<?> l) v = new ArrayList<>(l);
                copy.put(String.valueOf(e.getKey()), v);
            }
            out.add(copy);
        }
        return out;
    }

    void setElements(String page, List<Map<String, Object>> els) {
        yaml.set("pages." + pageKey(page) + ".elements", els);
    }

    // ------------------------------------------------------------ save

    /**
     * Validates (every element must still parse, nothing skipped) and writes.
     * Returns the reason it was refused, or null when saved.
     */
    String commit(ScreenManager manager) {
        String text = yaml.saveToString();
        List<String> problems = problems(text);
        if (problems == null) return "that would break the file.";
        // problems the file already had (hand edits) don't block; only new ones do
        List<String> old = problems(before);
        if (old != null) problems.removeAll(old);
        if (!problems.isEmpty()) return problems.get(0).replaceFirst("^[^ ]+ ", "");
        try {
            FileUtil.writeAtomically(file, text);
        } catch (IOException e) {
            return "could not save: " + e.getMessage();
        }
        manager.pushUndo(screen, before);
        screen.reloadPages();
        return null;
    }

    /** Warnings the page parser gives for this file text, or null if it isn't YAML at all. */
    private List<String> problems(String text) {
        List<String> problems = new ArrayList<>();
        Logger probe = Logger.getAnonymousLogger();
        probe.setUseParentHandlers(false);
        probe.addHandler(new Handler() {
            public void publish(LogRecord r) {
                problems.add(r.getMessage());
            }

            public void flush() {
            }

            public void close() {
            }
        });
        try {
            YamlConfiguration check = new YamlConfiguration();
            check.loadFromString(text);
            Page.Set.parse(check, probe, file.getName());
        } catch (InvalidConfigurationException e) {
            return null;
        }
        return problems;
    }

    // ------------------------------------------------------------ values

    /** Turns a typed value into what YAML should store: numbers stay numbers, true/false booleans. */
    static Object value(String raw) {
        String t = raw.trim();
        if (t.equalsIgnoreCase("true") || t.equalsIgnoreCase("on") || t.equalsIgnoreCase("yes")) return Boolean.TRUE;
        if (t.equalsIgnoreCase("false") || t.equalsIgnoreCase("off") || t.equalsIgnoreCase("no")) return Boolean.FALSE;
        if (t.matches("-?\\d{1,9}")) return Integer.parseInt(t);
        return t;
    }

    static String typeOf(Map<String, Object> el) {
        Object t = el.get("type");
        return t == null ? "?" : t.toString().toLowerCase(Locale.ROOT);
    }
}
