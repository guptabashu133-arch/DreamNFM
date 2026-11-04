package org.mydreamduels.dreamscreens.screen;

import org.mydreamduels.dreamscreens.DreamScreens;

import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Your own fonts (.ttf / .otf in plugins/DreamScreens/fonts) for text on
 * screens. Text is drawn without anti-aliasing (maps have no half-colours,
 * soft edges would look dirty) and cached, so the same text is only drawn once.
 */
public final class Fonts {

    private static final Font LOADING = new Font(Font.SANS_SERIF, Font.PLAIN, 1);
    private static final int MAX_CACHE = 512;

    private final DreamScreens plugin;
    private final File dir;
    private final Map<String, Font> fonts = new ConcurrentHashMap<>();
    private final Map<String, ImageStore.Img> rendered = new ConcurrentHashMap<>();

    public Fonts(DreamScreens plugin) {
        this.plugin = plugin;
        this.dir = new File(plugin.getDataFolder(), "fonts");
    }

    public File dir() {
        return dir;
    }

    public void clear() {
        fonts.clear();
        rendered.clear();
    }

    public List<String> files() {
        List<String> out = new ArrayList<>();
        File[] files = dir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".ttf") || n.toLowerCase(Locale.ROOT).endsWith(".otf"));
        if (files != null) for (File f : files) out.add(f.getName());
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    /** null while the font file is still loading (the screen redraws once it's ready) or if it can't be read. */
    Font font(String file) {
        if (file == null || file.contains("..") || file.contains("/") || file.contains("\\")) return null;
        Font f = fonts.get(file);
        if (f != null) return f == LOADING ? null : f;
        if (fonts.putIfAbsent(file, LOADING) != null) return null;
        plugin.scheduler().runAsync(() -> {
            try {
                fonts.put(file, Font.createFont(Font.TRUETYPE_FONT, new File(dir, file)));
                plugin.screens().redrawAll();
            } catch (Exception e) {
                plugin.getLogger().warning("Font " + file + " could not be loaded: " + e.getMessage());
                fonts.put(file, new Font(Font.SANS_SERIF, Font.PLAIN, 1)); // something to draw with
            }
        });
        return null;
    }

    /** Text (section/& colours already turned into section codes) as map pixels, or null while loading. */
    ImageStore.Img render(String file, int size, String text, int rgb, int shadowRgb) {
        Font base = font(file);
        if (base == null) return null;
        String key = file + "|" + size + "|" + rgb + "|" + shadowRgb + "|" + text;
        ImageStore.Img cached = rendered.get(key);
        if (cached != null) return cached;
        ImageStore.Img img = draw(base, size, text, rgb, shadowRgb);
        if (rendered.size() >= MAX_CACHE) rendered.clear();
        rendered.put(key, img);
        return img;
    }

    record Run(String text, int rgb, boolean bold) {
    }

    /** Splits on colour codes (incl. section-x hex) and bold. */
    static List<Run> runs(String text, int baseRgb) {
        List<Run> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int rgb = baseRgb;
        boolean bold = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '§' && i + 1 < text.length()) {
                char code = Character.toLowerCase(text.charAt(i + 1));
                int nrgb = rgb;
                boolean nbold = bold;
                int skip = 1;
                if (code == 'x' && i + 13 < text.length()) {
                    StringBuilder hex = new StringBuilder();
                    for (int k = 0; k < 6; k++) hex.append(text.charAt(i + 3 + k * 2));
                    try {
                        nrgb = Integer.parseInt(hex.toString(), 16);
                    } catch (NumberFormatException ignored) {
                    }
                    nbold = false;
                    skip = 13;
                } else if (Palette.legacy(code) >= 0) {
                    nrgb = Palette.legacy(code);
                    nbold = false;
                } else if (code == 'l') {
                    nbold = true;
                } else if (code == 'r') {
                    nrgb = baseRgb;
                    nbold = false;
                }
                if (nrgb != rgb || nbold != bold) {
                    if (cur.length() > 0) out.add(new Run(cur.toString(), rgb, bold));
                    cur.setLength(0);
                    rgb = nrgb;
                    bold = nbold;
                }
                i += skip;
                continue;
            }
            cur.append(ch);
        }
        if (cur.length() > 0) out.add(new Run(cur.toString(), rgb, bold));
        return out;
    }

    static ImageStore.Img draw(Font base, int size, String text, int baseRgb, int shadowRgb) {
        Font plain = base.deriveFont(Font.PLAIN, (float) size), bold = base.deriveFont(Font.BOLD, (float) size);
        List<Run> runs = runs(text, baseRgb);
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D pg = probe.createGraphics();
        FontMetrics pm = pg.getFontMetrics(plain), bm = pg.getFontMetrics(bold);
        int width = 0;
        for (Run r : runs) width += (r.bold ? bm : pm).stringWidth(r.text);
        int ascent = Math.max(pm.getAscent(), bm.getAscent()), height = ascent + Math.max(pm.getDescent(), bm.getDescent());
        pg.dispose();
        boolean shadow = shadowRgb != -2;
        int sh = shadow ? Math.max(1, size / 12) : 0;
        int w = Math.max(1, width + sh), h = Math.max(1, height + sh);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
        for (int pass = shadow ? 0 : 1; pass < 2; pass++) {
            int x = pass == 0 ? sh : 0, y = ascent + (pass == 0 ? sh : 0);
            for (Run r : runs) {
                g.setFont(r.bold ? bold : plain);
                int c = pass == 0 ? (shadowRgb >= 0 ? shadowRgb : Palette.darker(r.rgb, 0.25)) : r.rgb;
                g.setColor(new java.awt.Color(c));
                g.drawString(r.text, x, y);
                x += g.getFontMetrics().stringWidth(r.text);
            }
        }
        g.dispose();
        return new ImageStore.Img(w, h, ImageStore.convert(img, false));
    }
}
