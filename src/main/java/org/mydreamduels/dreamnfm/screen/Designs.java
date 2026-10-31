package org.mydreamduels.dreamnfm.screen;

import org.mydreamduels.dreamnfm.DreamNFM;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Ready-made page files (plugins/DreamNFM/designs/*.yml). The plugin ships a
 * set and copies any that are missing on start; you can edit them or drop
 * in your own. "/screen design <screen> <design>" puts one on a screen.
 */
public final class Designs {

    private final DreamNFM plugin;
    private final File dir;

    public Designs(DreamNFM plugin) {
        this.plugin = plugin;
        this.dir = new File(plugin.getDataFolder(), "designs");
    }

    public File dir() {
        return dir;
    }

    /** Copies bundled designs that aren't on disk yet (never overwrites your edits). */
    public void exportBundled() {
        dir.mkdirs();
        for (String name : bundled()) {
            File out = new File(dir, name + ".yml");
            if (out.exists()) continue;
            try (InputStream in = plugin.getResource("designs/" + name + ".yml")) {
                if (in != null) Files.copy(in, out.toPath());
            } catch (IOException e) {
                plugin.getLogger().warning("Could not export design " + name + ": " + e.getMessage());
            }
        }
    }

    List<String> bundled() {
        List<String> out = new ArrayList<>();
        try (InputStream in = plugin.getResource("designs/index.txt")) {
            if (in == null) return out;
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                String n = line.trim();
                if (!n.isEmpty() && !n.startsWith("#")) out.add(n);
            }
        } catch (IOException ignored) {
        }
        return out;
    }

    public List<String> names() {
        List<String> out = new ArrayList<>();
        File[] files = dir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (files != null) for (File f : files) out.add(f.getName().substring(0, f.getName().length() - 4));
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    public File file(String design) {
        if (design == null || !design.matches("[A-Za-z0-9_\\-]{1,48}")) return null;
        File f = new File(dir, design + ".yml");
        if (f.exists()) return f;
        for (String n : names()) if (n.equalsIgnoreCase(design)) return new File(dir, n + ".yml");
        return null;
    }

    /** Writes the design as the screen's page file (old one kept as .yml.bak) and reloads it. */
    public void apply(Screen screen, String design) throws IOException {
        File src = file(design);
        if (src == null) throw new IOException("No design '" + design + "'");
        File dst = screen.pagesFile();
        dst.getParentFile().mkdirs();
        if (dst.exists()) Files.copy(dst.toPath(), new File(dst.getParentFile(), dst.getName() + ".bak").toPath(), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING);
        plugin.screens().images().clear();
        screen.reloadPages();
    }
}
