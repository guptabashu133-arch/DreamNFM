package org.mydreamduels.dreamscreens.screen;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws a page for one viewer into a Canvas. No Bukkit calls in here (all
 * lookups go through Context), so it can run on any thread and be tested.
 */
public final class PageRenderer {

    public interface Context {
        /** Placeholders + colour codes applied. */
        String text(String raw);

        ImageStore.Img image(String file, int w, int h);

        /** 8x8 face (with hat layer) or null while loading. */
        ImageStore.Img head(String player);

        /** Graph samples, oldest first. */
        double[] samples(String source);

        boolean allowed(String permission);
    }

    /** Area of an element as drawn: index into page.elements(). */
    public record Box(int index, int x, int y, int w, int h, boolean clickable) {
        public Box(int index, int x, int y, int w, int h) {
            this(index, x, y, w, h, true);
        }

        public boolean contains(int px, int py) {
            return px >= x && py >= y && px < x + w && py < y + h;
        }
    }

    /** Editor extras drawn on top for one player: selected element outline and a positioning grid. */
    public record Overlay(int selected, boolean grid) {
        public static final Overlay NONE = new Overlay(-1, false);
    }

    public record Result(Canvas canvas, List<Box> boxes) {
        /** Clickable boxes only (in draw order). */
        public List<Box> clickableBoxes() {
            List<Box> out = new ArrayList<>();
            for (Box b : boxes) if (b.clickable()) out.add(b);
            return out;
        }

        /** Top-most clickable element under a pixel, or -1. */
        public int elementAt(int px, int py) {
            for (int i = boxes.size() - 1; i >= 0; i--) {
                Box b = boxes.get(i);
                if (b.clickable() && b.contains(px, py)) return b.index();
            }
            return -1;
        }

        /** Every element under a pixel, top-most first (the editor cycles through these). */
        public List<Integer> allAt(int px, int py) {
            List<Integer> out = new ArrayList<>();
            for (int i = boxes.size() - 1; i >= 0; i--) if (boxes.get(i).contains(px, py)) out.add(boxes.get(i).index());
            return out;
        }

        public Box box(int index) {
            for (Box b : boxes) if (b.index() == index) return b;
            return null;
        }
    }

    private PageRenderer() {
    }

    public static Result render(Page page, int width, int height, Context ctx, int hover, int pressed) {
        return render(page, width, height, ctx, hover, pressed, Overlay.NONE, null);
    }

    /** reuse: a previous frame's pixel array to draw into (saves allocating a big array every redraw), or null. */
    public static Result render(Page page, int width, int height, Context ctx, int hover, int pressed, Overlay overlay, byte[] reuse) {
        Canvas c = reuse != null && reuse.length == width * height ? new Canvas(width, height, reuse) : new Canvas(width, height);
        List<Box> boxes = new ArrayList<>();
        if (page == null) {
            c.fill(Palette.of(0x14161F));
            c.text("No pages - edit screens/<name>.yml", width / 2, height / 2 - 4, 1, 0xFF5555, true, Canvas.Align.CENTER);
            return new Result(c, boxes);
        }
        byte bg = color(ctx, page.background(), 0x14161F);
        c.fill(bg);
        if (page.backgroundImage() != null) {
            ImageStore.Img img = ctx.image(page.backgroundImage(), width, height);
            if (img != null) c.blit(img.pixels(), img.width(), img.height(), 0, 0);
        }
        List<Element> els = page.elements();
        for (int i = 0; i < els.size(); i++) {
            Element e = els.get(i);
            if (e.broken != null || (e.permission != null && !ctx.allowed(e.permission))) continue;
            int[] box = draw(c, e, ctx, i == hover, i == pressed);
            if (box != null) boxes.add(new Box(i, box[0], box[1], box[2], box[3], e.clickable()));
        }
        if (overlay != null && overlay != Overlay.NONE) drawOverlay(c, boxes, overlay);
        return new Result(c, boxes);
    }

    static void drawOverlay(Canvas c, List<Box> boxes, Overlay o) {
        if (o.grid()) {
            byte dot = Palette.of(0x5A6072), mid = Palette.of(0x22D3EE);
            for (int y = 0; y < c.height; y += 16)
                for (int x = 0; x < c.width; x += 2) c.set(x, y, dot);
            for (int x = 0; x < c.width; x += 16)
                for (int y = 0; y < c.height; y += 2) c.set(x, y, dot);
            for (int y = 0; y < c.height; y += 2) c.set(c.width / 2, y, mid);
            for (int x = 0; x < c.width; x += 2) c.set(x, c.height / 2, mid);
        }
        if (o.selected() >= 0) {
            for (Box b : boxes) {
                if (b.index() != o.selected()) continue;
                byte a = Palette.of(0xFFFF55), z = Palette.of(0x000000);
                int x0 = b.x() - 2, y0 = b.y() - 2, x1 = b.x() + b.w() + 1, y1 = b.y() + b.h() + 1;
                for (int x = x0; x <= x1; x++) {
                    c.set(x, y0, (x / 2) % 2 == 0 ? a : z);
                    c.set(x, y1, (x / 2) % 2 == 0 ? a : z);
                }
                for (int y = y0; y <= y1; y++) {
                    c.set(x0, y, (y / 2) % 2 == 0 ? a : z);
                    c.set(x1, y, (y / 2) % 2 == 0 ? a : z);
                }
                String label = "#" + (b.index() + 1);
                int lw = Canvas.textWidth(label, 1) + 4, ly = y0 - 11 >= 0 ? y0 - 11 : y1 + 2;
                c.rect(x0, ly, lw, 10, a);
                c.text(label, x0 + 2, ly + 1, 1, 0x000000, false, Canvas.Align.LEFT);
            }
        }
    }

    static byte color(Context ctx, String raw, int fallback) {
        if (raw == null) return Palette.of(fallback);
        String s = raw.trim();
        if (s.indexOf('%') >= 0 || s.indexOf('{') >= 0 || s.indexOf('<') >= 0) s = ctx.text(s).trim();
        s = stripCodes(s);
        if (s.equalsIgnoreCase("none") || s.equalsIgnoreCase("transparent")) return Palette.TRANSPARENT;
        int rgb = org.mydreamduels.dreamscreens.util.Colors.rgb(s);
        return Palette.of(rgb < 0 ? fallback : rgb);
    }

    static int rgb(Context ctx, String raw, int fallback) {
        if (raw == null) return fallback;
        String s = raw.trim();
        if (s.indexOf('%') >= 0 || s.indexOf('{') >= 0 || s.indexOf('<') >= 0) s = stripCodes(ctx.text(s).trim());
        int rgb = org.mydreamduels.dreamscreens.util.Colors.rgb(s);
        return rgb < 0 ? fallback : rgb;
    }

    private static String stripCodes(String s) {
        return s.replaceAll("(?i)[§&][0-9a-fk-orx]", "").trim();
    }

    /** -2 none, -1 automatic, else the element's shadow-color. */
    static int shadow(Context ctx, Element e) {
        if (!e.shadow) return -2;
        return e.shadowColor == null ? -1 : rgb(ctx, e.shadowColor, -1);
    }

    static int lighter(int rgb) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        r += (255 - r) / 4;
        g += (255 - g) / 4;
        b += (255 - b) / 4;
        return (r << 16) | (g << 8) | b;
    }

    /** Draws one element; returns its box {x,y,w,h} or null. */
    static int[] draw(Canvas c, Element e, Context ctx, boolean hovered, boolean pressed) {
        int W = c.width, H = c.height;
        switch (e.type) {
            case RECT -> {
                int w = e.w.size(W, W), h = e.h.size(H, H);
                int x = e.x.pos(W, w), y = e.y.pos(H, h);
                int base = rgb(ctx, e.color, 0x1F2433);
                int fill = pressed ? Palette.darker(base, 0.75) : hovered && e.clickable() ? rgb(ctx, e.hoverColor, lighter(base)) : base;
                if (e.color != null && !e.color.equalsIgnoreCase("none") && !e.color.equalsIgnoreCase("transparent")) {
                    if (e.round) c.roundRect(x, y, w, h, Palette.of(fill));
                    else c.rect(x, y, w, h, Palette.of(fill));
                }
                if (e.borderSize > 0) c.border(x, y, w, h, e.borderSize, color(ctx, e.borderColor, 0));
                return new int[]{x, y, w, h};
            }
            case TEXT -> {
                String t = e.maxWidth.isAuto() ? ctx.text(e.text) : fit(ctx.text(e.text), e.maxWidth.size(W, W), e.scale);
                int w = Canvas.textWidth(t, e.scale), h = 8 * e.scale;
                int anchor = e.x.isCenter() ? W / 2 : e.x.pos(W, 0);
                int y = e.y.pos(H, h);
                int col = hovered && e.clickable() ? rgb(ctx, e.hoverColor, 0xFFFF55) : rgb(ctx, e.color, 0xFFFFFF);
                c.text(t, anchor, y, e.scale, col, shadow(ctx, e), e.align);
                int left = switch (e.align) {
                    case LEFT -> anchor;
                    case CENTER -> anchor - w / 2;
                    case RIGHT -> anchor - w;
                };
                return new int[]{left, y, w, h};
            }
            case LINES -> {
                int lh = Canvas.lineHeight(e.scale) + e.spacing;
                int total = Math.max(0, e.lines.size() * lh - e.spacing);
                int anchor = e.x.isCenter() ? W / 2 : e.x.pos(W, 0);
                int y = e.y.pos(H, total);
                int col = rgb(ctx, e.color, 0xFFFFFF);
                int maxW = 0;
                for (int i = 0; i < e.lines.size(); i++) {
                    String t = e.maxWidth.isAuto() ? ctx.text(e.lines.get(i)) : fit(ctx.text(e.lines.get(i)), e.maxWidth.size(W, W), e.scale);
                    maxW = Math.max(maxW, c.text(t, anchor, y + i * lh, e.scale, col, shadow(ctx, e), e.align));
                }
                int left = e.align == Canvas.Align.LEFT ? anchor : e.align == Canvas.Align.CENTER ? anchor - maxW / 2 : anchor - maxW;
                return new int[]{left, y, maxW, total};
            }
            case IMAGE -> {
                int w = e.w.isAuto() ? 0 : e.w.size(W, 0), h = e.h.isAuto() ? 0 : e.h.size(H, 0);
                ImageStore.Img img = ctx.image(e.file, w, h);
                if (img == null || img.pixels() == null) return null;
                int x = e.x.pos(W, img.width()), y = e.y.pos(H, img.height());
                c.blit(img.pixels(), img.width(), img.height(), x, y);
                if (hovered && e.clickable()) c.border(x - 1, y - 1, img.width() + 2, img.height() + 2, 1, Palette.of(rgb(ctx, e.hoverColor, 0xFFFFFF)));
                return new int[]{x, y, img.width(), img.height()};
            }
            case BUTTON -> {
                int w = e.w.size(W, 96), h = e.h.size(H, 24);
                int x = e.x.pos(W, w), y = e.y.pos(H, h);
                int base = rgb(ctx, e.color, 0x3B82F6);
                int fill = pressed ? Palette.darker(base, 0.7) : hovered ? rgb(ctx, e.hoverColor, lighter(base)) : base;
                int shift = pressed ? 1 : 0;
                // drop shadow under the button, so it looks pressable
                if (!pressed) {
                    if (e.round) c.roundRect(x, y + 2, w, h, Palette.of(Palette.darker(base, 0.45)));
                    else c.rect(x, y + 2, w, h, Palette.of(Palette.darker(base, 0.45)));
                }
                if (e.round) c.roundRect(x, y + shift, w, h, Palette.of(fill));
                else c.rect(x, y + shift, w, h, Palette.of(fill));
                if (e.borderSize > 0 || hovered) {
                    byte bc = hovered ? Palette.of(0xFFFFFF) : color(ctx, e.borderColor, 0);
                    int t = Math.max(1, e.borderSize);
                    if (e.round && w >= 6 && h >= 6) {
                        c.rect(x + 2, y + shift, w - 4, t, bc);
                        c.rect(x + 2, y + shift + h - t, w - 4, t, bc);
                        c.rect(x, y + shift + 2, t, h - 4, bc);
                        c.rect(x + w - t, y + shift + 2, t, h - 4, bc);
                    } else {
                        c.border(x, y + shift, w, h, t, bc);
                    }
                }
                String t = ctx.text(e.text);
                int tw = Canvas.textWidth(t, e.scale);
                int iconW = 0;
                if (e.icon != null) {
                    int is = Math.min(e.iconSize, h - 4);
                    ImageStore.Img img = ctx.image(e.icon, is, is);
                    if (img != null && img.pixels() != null) {
                        iconW = img.width() + (t.isEmpty() ? 0 : 4);
                        int ix = x + (w - iconW - tw) / 2;
                        c.blit(img.pixels(), img.width(), img.height(), ix, y + shift + (h - img.height()) / 2);
                    }
                }
                int tx = x + (w - iconW - tw) / 2 + iconW;
                int ty = y + shift + (h - 7 * e.scale) / 2;
                c.text(t, tx, ty, e.scale, rgb(ctx, e.textColor, 0xFFFFFF), shadow(ctx, e), Canvas.Align.LEFT);
                return new int[]{x, y, w, h + 2};
            }
            case HEAD -> {
                int size = Math.max(8, e.h.size(H, 32));
                int x = e.x.pos(W, size), y = e.y.pos(H, size);
                ImageStore.Img face = ctx.head(ctx.text(e.player).trim());
                if (face == null || face.pixels() == null) {
                    c.rect(x, y, size, size, Palette.of(0x3A3F4F));
                    c.text("?", x + size / 2, y + size / 2 - 4, Math.max(1, size / 16), 0xAAAAAA, false, Canvas.Align.CENTER);
                } else {
                    for (int py = 0; py < size; py++) {
                        int sy = py * face.height() / size;
                        for (int px = 0; px < size; px++) {
                            c.set(x + px, y + py, face.pixels()[sy * face.width() + px * face.width() / size]);
                        }
                    }
                }
                if (hovered && e.clickable()) c.border(x - 1, y - 1, size + 2, size + 2, 1, Palette.of(0xFFFFFF));
                return new int[]{x, y, size, size};
            }
            case PROGRESS -> {
                int w = e.w.size(W, W), h = e.h.size(H, 10);
                int x = e.x.pos(W, w), y = e.y.pos(H, h);
                double v = number(ctx.text(e.value)), m = number(ctx.text(e.max));
                double ratio = m <= 0 ? 0 : Math.max(0, Math.min(1, v / m));
                byte back = color(ctx, e.background, 0x2A2F3E);
                if (e.round) c.roundRect(x, y, w, h, back);
                else c.rect(x, y, w, h, back);
                int fw = (int) Math.round((w - 2) * ratio);
                if (fw > 0) {
                    byte fill = color(ctx, e.color, 0x22C55E);
                    if (e.round && fw >= 6) c.roundRect(x + 1, y + 1, fw, h - 2, fill);
                    else c.rect(x + 1, y + 1, fw, h - 2, fill);
                }
                if (!e.text.isEmpty()) {
                    String t = ctx.text(e.text.replace("{percent}", String.valueOf((int) Math.round(ratio * 100))));
                    c.text(t, x + w / 2, y + (h - 7 * e.scale) / 2, e.scale, rgb(ctx, e.textColor, 0xFFFFFF), true, Canvas.Align.CENTER);
                }
                return new int[]{x, y, w, h};
            }
            case GRAPH -> {
                int w = e.w.size(W, W), h = e.h.size(H, H);
                int x = e.x.pos(W, w), y = e.y.pos(H, h);
                byte back = color(ctx, e.background, 0x2A2F3E);
                c.rect(x, y, w, h, back);
                int line = rgb(ctx, e.color, 0x22C55E);
                double[] s = ctx.samples(e.source);
                String label = e.text.isEmpty() ? (e.source.equals("tps") ? "TPS" : e.source.equals("mspt") ? "MSPT" : "Online") : ctx.text(e.text);
                int top = y + 12, bottom = y + h - 3;
                if (s == null || s.length == 0 || bottom - top < 4 || w < 8) {
                    c.text(label + ": n/a", x + 3, y + 3, 1, 0xAAAAAA, false, Canvas.Align.LEFT);
                    return new int[]{x, y, w, h};
                }
                double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
                for (double d : s) {
                    min = Math.min(min, d);
                    max = Math.max(max, d);
                }
                if (e.source.equals("tps")) {
                    min = Math.min(min, 0);
                    max = Math.max(max, 20);
                } else if (e.source.equals("mspt")) {
                    min = 0;
                    max = Math.max(max, 50);
                } else {
                    min = 0;
                    max = Math.max(max, 1);
                }
                double range = Math.max(1e-9, max - min);
                byte area = Palette.of(Palette.darker(line, 0.45)), lc = Palette.of(line);
                int prevX = -1, prevY = -1;
                int n = s.length;
                for (int i = 0; i < n; i++) {
                    int px = x + 2 + (n == 1 ? (w - 4) : (int) Math.round((w - 5) * (i / (double) (n - 1))));
                    int py = bottom - (int) Math.round((bottom - top) * ((s[i] - min) / range));
                    if (prevX >= 0) {
                        for (int fx = prevX; fx <= px; fx++) {
                            int fy = prevX == px ? py : prevY + (py - prevY) * (fx - prevX) / (px - prevX);
                            c.rect(fx, fy + 1, 1, bottom - fy, area);
                        }
                        c.line(prevX, prevY, px, py, lc);
                    } else {
                        c.rect(px, py, 1, bottom - py + 1, area);
                    }
                    prevX = px;
                    prevY = py;
                }
                String now = e.source.equals("online") ? String.valueOf((int) s[n - 1]) : String.format(java.util.Locale.ROOT, "%.1f", s[n - 1]);
                c.text(label + ": " + now, x + 3, y + 3, 1, 0xFFFFFF, true, Canvas.Align.LEFT);
                return new int[]{x, y, w, h};
            }
        }
        return null;
    }

    /** Cuts text (keeping colour codes intact) so it fits in max pixels, ending with "..". */
    static String fit(String t, int max, int scale) {
        if (max <= 0 || Canvas.textWidth(t, scale) <= max) return t;
        String s = t;
        while (!s.isEmpty() && Canvas.textWidth(s + "..", scale) > max) {
            s = s.substring(0, s.length() - 1);
            // never leave half a colour code (section sign or a section-x hex run)
            while (!s.isEmpty() && (s.charAt(s.length() - 1) == '\u00a7' || s.charAt(s.length() - 1) == '&')) s = s.substring(0, s.length() - 1);
            int sec = s.lastIndexOf("\u00a7x");
            if (sec >= 0 && s.length() - sec < 14) s = s.substring(0, sec);
        }
        return s + "..";
    }

    static double number(String s) {
        if (s == null) return 0;
        StringBuilder b = new StringBuilder();
        String t = s.replaceAll("(?i)§[0-9a-fk-orx]", "");
        for (char ch : t.toCharArray()) if (Character.isDigit(ch) || ch == '.' || (ch == '-' && b.isEmpty())) b.append(ch);
        try {
            return b.isEmpty() ? 0 : Double.parseDouble(b.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
