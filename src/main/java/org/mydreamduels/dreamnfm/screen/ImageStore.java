package org.mydreamduels.dreamnfm.screen;

import org.mydreamduels.dreamnfm.DreamNFM;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PNG/JPG files from plugins/DreamNFM/images, scaled and converted to map
 * colours (with Floyd-Steinberg dithering so gradients/photos look right).
 * Loading happens off-thread; until an image is ready it's simply not drawn,
 * and screens are told to redraw once it is.
 */
public final class ImageStore {

    public record Img(int width, int height, byte[] pixels) {
    }

    private static final Img LOADING = new Img(0, 0, null);

    private final DreamNFM plugin;
    private final File dir;
    private final Map<String, Img> cache = new ConcurrentHashMap<>();

    public ImageStore(DreamNFM plugin) {
        this.plugin = plugin;
        this.dir = new File(plugin.getDataFolder(), "images");
    }

    public File dir() {
        return dir;
    }

    public void clear() {
        cache.clear();
    }

    public List<String> files() {
        List<String> out = new ArrayList<>();
        File[] files = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".png") || n.toLowerCase().endsWith(".jpg") || n.toLowerCase().endsWith(".jpeg"));
        if (files != null) for (File f : files) out.add(f.getName());
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    /** null while loading or if missing. w/h <= 0 = the image's own size (capped to the screen). */
    public Img get(String file, int w, int h) {
        if (file == null || file.isBlank() || file.contains("..") || file.contains("/") || file.contains("\\")) return null;
        String key = file + "|" + w + "|" + h;
        Img img = cache.get(key);
        if (img != null) return img == LOADING ? null : img;
        if (cache.putIfAbsent(key, LOADING) != null) return null;
        plugin.scheduler().runAsync(() -> {
            try {
                BufferedImage src = ImageIO.read(new File(dir, file));
                if (src == null) throw new IllegalArgumentException("not an image");
                int tw = w > 0 ? w : Math.min(src.getWidth(), 1024);
                int th = h > 0 ? h : Math.min(src.getHeight(), 1024);
                cache.put(key, new Img(tw, th, convert(scale(src, tw, th), plugin.getConfig().getBoolean("screens.dither", true))));
                plugin.screens().redrawAll();
            } catch (Exception e) {
                plugin.getLogger().warning("Image " + file + " could not be loaded: " + e.getMessage());
                cache.put(key, new Img(0, 0, null));
            }
        });
        return null;
    }

    static BufferedImage scale(BufferedImage src, int w, int h) {
        if (src.getWidth() == w && src.getHeight() == h) return src;
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        boolean up = w > src.getWidth();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, up ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
                : RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    /** ARGB image -> map colour bytes. Pixels under 50% alpha become transparent. */
    public static byte[] convert(BufferedImage img, boolean dither) {
        int w = img.getWidth(), h = img.getHeight();
        byte[] out = new byte[w * h];
        float[][] err = new float[3][(w + 2) * 2];
        float[] r = new float[w + 2], g = new float[w + 2], b = new float[w + 2];
        float[] nr = new float[w + 2], ng = new float[w + 2], nb = new float[w + 2];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = img.getRGB(x, y);
                if (((argb >>> 24) & 0xFF) < 128) {
                    out[y * w + x] = Palette.TRANSPARENT;
                    continue;
                }
                float cr = ((argb >> 16) & 0xFF) + (dither ? r[x + 1] : 0);
                float cg = ((argb >> 8) & 0xFF) + (dither ? g[x + 1] : 0);
                float cb = (argb & 0xFF) + (dither ? b[x + 1] : 0);
                int ir = clamp(cr), ig = clamp(cg), ib = clamp(cb);
                byte m = Palette.of((ir << 16) | (ig << 8) | ib);
                out[y * w + x] = m;
                if (!dither) continue;
                int got = Palette.rgb(m);
                float er = cr - ((got >> 16) & 0xFF), eg = cg - ((got >> 8) & 0xFF), eb = cb - (got & 0xFF);
                r[x + 2] += er * 7 / 16f; g[x + 2] += eg * 7 / 16f; b[x + 2] += eb * 7 / 16f;
                nr[x] += er * 3 / 16f; ng[x] += eg * 3 / 16f; nb[x] += eb * 3 / 16f;
                nr[x + 1] += er * 5 / 16f; ng[x + 1] += eg * 5 / 16f; nb[x + 1] += eb * 5 / 16f;
                nr[x + 2] += er / 16f; ng[x + 2] += eg / 16f; nb[x + 2] += eb / 16f;
            }
            float[] t;
            t = r; r = nr; nr = t; java.util.Arrays.fill(nr, 0);
            t = g; g = ng; ng = t; java.util.Arrays.fill(ng, 0);
            t = b; b = nb; nb = t; java.util.Arrays.fill(nb, 0);
        }
        return out;
    }

    private static int clamp(float v) {
        return v < 0 ? 0 : v > 255 ? 255 : (int) v;
    }
}
