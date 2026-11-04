package org.mydreamduels.dreamscreens.screen;

import org.mydreamduels.dreamscreens.DreamScreens;

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
 * PNG/JPG files from plugins/DreamScreens/images, scaled and converted to map
 * colours (with Floyd-Steinberg dithering so gradients/photos look right).
 * Loading happens off-thread; until an image is ready it's simply not drawn,
 * and screens are told to redraw once it is.
 */
public final class ImageStore {

    public record Img(int width, int height, byte[] pixels) {
    }

    private static final Img LOADING = new Img(0, 0, null);

    private final DreamScreens plugin;
    private final File dir;
    private final Map<String, Img> cache = new ConcurrentHashMap<>();
    /** Animated GIFs: all frames converted once, the current one picked by the clock. */
    public record Anim(int width, int height, byte[][] frames, int[] delaysMs, int totalMs) {
        public Img frame(long nowMs) {
            int t = totalMs <= 0 ? 0 : (int) Math.floorMod(nowMs, (long) totalMs);
            for (int i = 0; i < frames.length; i++) {
                t -= delaysMs[i];
                if (t < 0) return new Img(width, height, frames[i]);
            }
            return new Img(width, height, frames[frames.length - 1]);
        }
    }

    private static final int MAX_GIF_FRAMES = 120;
    private final Map<String, Anim> anims = new ConcurrentHashMap<>();

    public ImageStore(DreamScreens plugin) {
        this.plugin = plugin;
        this.dir = new File(plugin.getDataFolder(), "images");
    }

    public File dir() {
        return dir;
    }

    public void clear() {
        cache.clear();
        anims.clear();
    }

    public List<String> files() {
        List<String> out = new ArrayList<>();
        File[] files = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".png") || n.toLowerCase().endsWith(".jpg") || n.toLowerCase().endsWith(".jpeg")
                || n.toLowerCase().endsWith(".gif"));
        if (files != null) for (File f : files) out.add(f.getName());
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    /** null while loading or if missing. w/h <= 0 = the image's own size (capped to the screen). */
    public Img get(String file, int w, int h) {
        if (file == null || file.isBlank() || file.contains("..") || file.contains("/") || file.contains("\\")) return null;
        String key = file + "|" + w + "|" + h;
        Anim anim = anims.get(key);
        if (anim != null) return anim.frame(System.currentTimeMillis());
        Img img = cache.get(key);
        if (img != null) return img == LOADING ? null : img;
        if (cache.putIfAbsent(key, LOADING) != null) return null;
        plugin.scheduler().runAsync(() -> {
            try {
                if (Page.gif(file)) {
                    Anim a = readGif(new File(dir, file), w, h);
                    if (a.frames().length > 1) {
                        anims.put(key, a);
                        cache.remove(key);
                    } else {
                        cache.put(key, new Img(a.width(), a.height(), a.frames()[0]));
                    }
                    plugin.screens().redrawAll();
                    return;
                }
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

    /**
     * Reads every frame of a GIF the way browsers show it: each frame is drawn
     * over the previous picture at its offset, then cleared/restored as the
     * file says (disposal), so partial-frame GIFs look right.
     */
    static Anim readGif(File f, int w, int h) throws java.io.IOException {
        try (javax.imageio.stream.ImageInputStream in = ImageIO.createImageInputStream(f)) {
            if (in == null) throw new java.io.IOException("can't open");
            java.util.Iterator<javax.imageio.ImageReader> readers = ImageIO.getImageReadersByFormatName("gif");
            if (!readers.hasNext()) throw new java.io.IOException("no GIF reader");
            javax.imageio.ImageReader r = readers.next();
            try {
                r.setInput(in, false);
                int count = Math.min(MAX_GIF_FRAMES, r.getNumImages(true));
                int lw = -1, lh = -1;
                javax.imageio.metadata.IIOMetadata sm = r.getStreamMetadata();
                if (sm != null) {
                    org.w3c.dom.Node lsd = child(sm.getAsTree("javax_imageio_gif_stream_1.0"), "LogicalScreenDescriptor");
                    if (lsd != null) {
                        lw = intAttr(lsd, "logicalScreenWidth", -1);
                        lh = intAttr(lsd, "logicalScreenHeight", -1);
                    }
                }
                BufferedImage first = r.read(0);
                if (lw <= 0 || lh <= 0) {
                    lw = first.getWidth();
                    lh = first.getHeight();
                }
                int tw = w > 0 ? w : Math.min(lw, 1024), th = h > 0 ? h : Math.min(lh, 1024);
                BufferedImage canvas = new BufferedImage(lw, lh, BufferedImage.TYPE_INT_ARGB);
                List<byte[]> frames = new ArrayList<>();
                List<Integer> delays = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    BufferedImage frame = i == 0 ? first : r.read(i);
                    org.w3c.dom.Node meta = r.getImageMetadata(i).getAsTree("javax_imageio_gif_image_1.0");
                    org.w3c.dom.Node desc = child(meta, "ImageDescriptor"), gce = child(meta, "GraphicControlExtension");
                    int fx = desc == null ? 0 : intAttr(desc, "imageLeftPosition", 0), fy = desc == null ? 0 : intAttr(desc, "imageTopPosition", 0);
                    String disposal = gce == null ? "none" : ((org.w3c.dom.Element) gce).getAttribute("disposalMethod");
                    int delay = gce == null ? 10 : intAttr(gce, "delayTime", 10);
                    BufferedImage before = "restoreToPrevious".equals(disposal) ? copy(canvas) : null;
                    Graphics2D g = canvas.createGraphics();
                    g.drawImage(frame, fx, fy, null);
                    g.dispose();
                    frames.add(convert(scale(copy(canvas), tw, th), false)); // no dithering: it would shimmer between frames
                    delays.add(delay <= 1 ? 100 : Math.max(50, delay * 10));
                    if ("restoreToBackgroundColor".equals(disposal)) {
                        Graphics2D c = canvas.createGraphics();
                        c.setComposite(java.awt.AlphaComposite.Clear);
                        c.fillRect(fx, fy, frame.getWidth(), frame.getHeight());
                        c.dispose();
                    } else if (before != null) {
                        canvas = before;
                    }
                }
                int[] d = new int[delays.size()];
                int total = 0;
                for (int i = 0; i < d.length; i++) total += d[i] = delays.get(i);
                return new Anim(tw, th, frames.toArray(new byte[0][]), d, total);
            } finally {
                r.dispose();
            }
        }
    }

    private static org.w3c.dom.Node child(org.w3c.dom.Node parent, String name) {
        for (org.w3c.dom.Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) if (name.equals(n.getNodeName())) return n;
        return null;
    }

    private static int intAttr(org.w3c.dom.Node n, String attr, int def) {
        try {
            return Integer.parseInt(((org.w3c.dom.Element) n).getAttribute(attr));
        } catch (RuntimeException e) {
            return def;
        }
    }

    private static BufferedImage copy(BufferedImage src) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return out;
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
