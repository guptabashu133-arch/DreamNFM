package org.mydreamduels.dreamscreens.screen;

import org.bukkit.map.MapFont;
import org.bukkit.map.MinecraftFont;

/**
 * A whole screen's pixels (one byte per pixel, map colours). Everything is
 * bounds-checked, so a badly placed element can never throw.
 */
public final class Canvas {

    public enum Align { LEFT, CENTER, RIGHT }

    public final int width, height;
    public final byte[] pixels;
    /** Drawing is limited to this rectangle (the whole canvas unless clip() was called). */
    private int clipX0, clipY0, clipX1, clipY1;

    public Canvas(int width, int height) {
        this(width, height, new byte[width * height]);
    }

    /** Draws into an existing array (must be width*height long). */
    public Canvas(int width, int height, byte[] pixels) {
        this.width = width;
        this.height = height;
        this.pixels = pixels;
        unclip();
    }

    /** Only draw inside x..x+w, y..y+h until unclip(). */
    public void clip(int x, int y, int w, int h) {
        clipX0 = Math.max(0, x);
        clipY0 = Math.max(0, y);
        clipX1 = Math.min(width, x + Math.max(0, w));
        clipY1 = Math.min(height, y + Math.max(0, h));
    }

    public void unclip() {
        clipX0 = 0;
        clipY0 = 0;
        clipX1 = width;
        clipY1 = height;
    }

    public void set(int x, int y, byte c) {
        if (x >= clipX0 && y >= clipY0 && x < clipX1 && y < clipY1 && c != Palette.TRANSPARENT) pixels[y * width + x] = c;
    }

    public byte get(int x, int y) {
        return x >= 0 && y >= 0 && x < width && y < height ? pixels[y * width + x] : Palette.TRANSPARENT;
    }

    public void fill(byte c) {
        java.util.Arrays.fill(pixels, c);
    }

    public void rect(int x, int y, int w, int h, byte c) {
        int x0 = Math.max(clipX0, x), y0 = Math.max(clipY0, y), x1 = Math.min(clipX1, x + w), y1 = Math.min(clipY1, y + h);
        if (c == Palette.TRANSPARENT) return;
        for (int yy = y0; yy < y1; yy++) java.util.Arrays.fill(pixels, yy * width + x0, yy * width + Math.max(x0, x1), c);
    }

    public void border(int x, int y, int w, int h, int t, byte c) {
        rect(x, y, w, t, c);
        rect(x, y + h - t, w, t, c);
        rect(x, y, t, h, c);
        rect(x + w - t, y, t, h, c);
    }

    /** Rounded corners: 1-2 px cut so buttons don't look like plain boxes. */
    public void roundRect(int x, int y, int w, int h, byte c) {
        if (w < 6 || h < 6) {
            rect(x, y, w, h, c);
            return;
        }
        rect(x + 2, y, w - 4, h, c);
        rect(x, y + 2, w, h - 4, c);
        rect(x + 1, y + 1, 1, 1, c);
        rect(x + w - 2, y + 1, 1, 1, c);
        rect(x + 1, y + h - 2, 1, 1, c);
        rect(x + w - 2, y + h - 2, 1, 1, c);
    }

    public void blit(byte[] img, int iw, int ih, int x, int y) {
        if (img == null) return;
        for (int yy = 0; yy < ih; yy++) {
            int ty = y + yy;
            if (ty < 0 || ty >= height) continue;
            for (int xx = 0; xx < iw; xx++) {
                byte c = img[yy * iw + xx];
                if (c != Palette.TRANSPARENT) set(x + xx, ty, c);
            }
        }
    }

    public void line(int x0, int y0, int x1, int y1, byte c) {
        int dx = Math.abs(x1 - x0), dy = -Math.abs(y1 - y0), sx = x0 < x1 ? 1 : -1, sy = y0 < y1 ? 1 : -1, err = dx + dy;
        for (int guard = 0; guard < 4096; guard++) {
            set(x0, y0, c);
            if (x0 == x1 && y0 == y1) return;
            int e2 = 2 * err;
            if (e2 >= dy) {
                err += dy;
                x0 += sx;
            }
            if (e2 <= dx) {
                err += dx;
                y0 += sy;
            }
        }
    }

    // ------------------------------------------------------------------ text

    private static final MapFont FONT = MinecraftFont.Font;

    private static MapFont.CharacterSprite sprite(char ch) {
        MapFont.CharacterSprite s = FONT.getChar(ch);
        return s != null ? s : FONT.getChar('?');
    }

    /** Pixel width of text with & / section colour codes removed. */
    public static int textWidth(String text, int scale) {
        int w = 0;
        boolean bold = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if ((ch == '§' || ch == '&') && i + 1 < text.length()) {
                char code = Character.toLowerCase(text.charAt(i + 1));
                if (code == 'x' && i + 13 < text.length()) {
                    bold = false;
                    i += 13;
                    continue;
                }
                if (code == '#' && i + 7 < text.length() && isHex(text, i + 2, 6)) {
                    bold = false;
                    i += 7;
                    continue;
                }
                if ("0123456789abcdefklmnor".indexOf(code) >= 0) {
                    if (code == 'l') bold = true;
                    else if (code == 'r' || Character.digit(code, 16) >= 0) bold = false;
                    i++;
                    continue;
                }
            }
            MapFont.CharacterSprite s = sprite(ch);
            if (s == null) continue;
            w += (s.getWidth() + 1 + (bold ? 1 : 0)) * scale;
        }
        return Math.max(0, w - scale);
    }

    /**
     * Draws text with &/section colours (incl. &#RRGGBB / section-x hex), bold
     * (&l), and an optional 1px shadow. Returns the drawn width.
     */
    public int text(String text, int x, int y, int scale, int rgb, boolean shadow, Align align) {
        return text(text, x, y, scale, rgb, shadow ? -1 : -2, align);
    }

    /**
     * shadowRgb: -2 = no shadow, -1 = automatic (a dark version of each
     * colour), otherwise one fixed shadow colour. The shadow is offset by
     * one font pixel (scale pixels).
     */
    public int text(String text, int x, int y, int scale, int rgb, int shadowRgb, Align align) {
        boolean shadow = shadowRgb != -2;
        int s = Math.max(1, scale);
        int w = textWidth(text, s);
        int startX = switch (align) {
            case LEFT -> x;
            case CENTER -> x - w / 2;
            case RIGHT -> x - w;
        };
        if (shadow) drawRun(text, startX + s, y + s, s, rgb, true, shadowRgb);
        drawRun(text, startX, y, s, rgb, false, -1);
        return w;
    }

    private void drawRun(String text, int x, int y, int s, int baseRgb, boolean shadow, int fixedShadow) {
        int rgb = baseRgb;
        boolean bold = false;
        int cx = x;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if ((ch == '§' || ch == '&') && i + 1 < text.length()) {
                char code = Character.toLowerCase(text.charAt(i + 1));
                if (code == 'x' && i + 13 < text.length()) {
                    StringBuilder hex = new StringBuilder();
                    for (int k = 0; k < 6; k++) hex.append(text.charAt(i + 3 + k * 2));
                    try {
                        rgb = Integer.parseInt(hex.toString(), 16);
                    } catch (NumberFormatException ignored) {
                    }
                    bold = false;
                    i += 13;
                    continue;
                }
                if (code == '#' && i + 7 < text.length() && isHex(text, i + 2, 6)) {
                    rgb = Integer.parseInt(text.substring(i + 2, i + 8), 16);
                    bold = false;
                    i += 7;
                    continue;
                }
                int legacy = Palette.legacy(code);
                if (legacy >= 0) {
                    rgb = legacy;
                    bold = false;
                    i++;
                    continue;
                }
                if (code == 'l') {
                    bold = true;
                    i++;
                    continue;
                }
                if (code == 'r') {
                    rgb = baseRgb;
                    bold = false;
                    i++;
                    continue;
                }
                if ("kmno".indexOf(code) >= 0) {
                    i++;
                    continue;
                }
            }
            MapFont.CharacterSprite sp = sprite(ch);
            if (sp == null) continue;
            byte c = Palette.of(shadow ? (fixedShadow >= 0 ? fixedShadow : Palette.darker(rgb, 0.25)) : rgb);
            for (int py = 0; py < sp.getHeight(); py++) {
                for (int px = 0; px < sp.getWidth(); px++) {
                    if (!sp.get(py, px)) continue;
                    rect(cx + px * s, y + py * s, s, s, c);
                    if (bold) rect(cx + px * s + s, y + py * s, s, s, c);
                }
            }
            cx += (sp.getWidth() + 1 + (bold ? 1 : 0)) * s;
        }
    }

    private static boolean isHex(String s, int from, int n) {
        if (from + n > s.length()) return false;
        for (int i = from; i < from + n; i++) if (Character.digit(s.charAt(i), 16) < 0) return false;
        return true;
    }

    public static int lineHeight(int scale) {
        return 9 * Math.max(1, scale);
    }
}
