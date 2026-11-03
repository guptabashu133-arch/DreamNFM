package org.mydreamduels.dreamscreens.screen;

import org.mydreamduels.dreamscreens.action.ActionList;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/** One thing drawn on a page. Parsed once from YAML, immutable afterwards. */
public final class Element {

    public enum Type { RECT, TEXT, LINES, IMAGE, BUTTON, HEAD, PROGRESS, GRAPH }

    public final Type type;
    public final Dim x, y, w, h, maxWidth;
    public final String text;
    public final List<String> lines;
    public final int scale, spacing, borderSize, iconSize;
    public final String color, hoverColor, textColor, borderColor, background, shadowColor;
    public final boolean shadow, round;
    public final Canvas.Align align;
    public final String file, icon, player, value, max, source, permission;
    public final ActionList actions = new ActionList();
    /** Why this element couldn't be read (it then draws nothing but keeps its place in the list), or null. */
    public final String broken;

    private Element(Type type, Node n, Logger log, String where) {
        this(type, n, log, where, null);
    }

    private Element(Type type, Node n, Logger log, String where, String broken) {
        this.type = type;
        this.broken = broken;
        this.x = Dim.parse(n.raw("x"), type == Type.TEXT || type == Type.LINES ? new Dim(0, Dim.Kind.CENTER) : Dim.ZERO);
        this.y = Dim.parse(n.raw("y"), Dim.ZERO);
        Dim defW = switch (type) {
            case RECT, PROGRESS, GRAPH -> new Dim(100, Dim.Kind.PERCENT);
            case BUTTON -> new Dim(96, Dim.Kind.ABS);
            default -> Dim.AUTO;
        };
        Dim defH = switch (type) {
            case RECT, GRAPH -> new Dim(100, Dim.Kind.PERCENT);
            case BUTTON -> new Dim(24, Dim.Kind.ABS);
            case PROGRESS -> new Dim(10, Dim.Kind.ABS);
            default -> Dim.AUTO;
        };
        this.w = Dim.parse(n.raw(n.has("w") ? "w" : "width"), defW);
        this.h = Dim.parse(n.raw(n.has("h") ? "h" : "height"), type == Type.HEAD ? Dim.parse(n.raw("size"), new Dim(32, Dim.Kind.ABS)) : defH);
        this.text = n.str("text", type == Type.BUTTON ? "Button" : "");
        this.maxWidth = Dim.parse(n.raw("max-width"), Dim.AUTO);
        this.lines = n.list("lines");
        this.scale = Math.max(1, Math.min(8, n.integer("scale", 1)));
        this.spacing = Math.max(0, n.integer("spacing", 1));
        this.borderSize = Math.max(0, Math.min(16, n.integer("border", n.has("border-color") ? 1 : 0)));
        this.iconSize = Math.max(4, Math.min(128, n.integer("icon-size", 16)));
        String defColor = switch (type) {
            case BUTTON -> "#3B82F6";
            case PROGRESS, GRAPH -> "#22C55E";
            case RECT -> "#1F2433";
            default -> "#FFFFFF";
        };
        this.color = n.str("color", defColor);
        this.hoverColor = n.str("hover-color", n.str("hover", null));
        this.textColor = n.str("text-color", "#FFFFFF");
        this.borderColor = n.str("border-color", "#000000");
        this.background = n.str("background", type == Type.PROGRESS || type == Type.GRAPH ? "#2A2F3E" : null);
        this.shadow = n.bool("shadow", type != Type.RECT);
        this.shadowColor = n.str("shadow-color", null);
        this.round = n.bool("round", type == Type.BUTTON || type == Type.PROGRESS);
        String a = n.str("align", null);
        this.align = a == null ? (x.isCenter() ? Canvas.Align.CENTER : Canvas.Align.LEFT) : switch (a.toLowerCase(Locale.ROOT)) {
            case "center", "centre", "middle" -> Canvas.Align.CENTER;
            case "right" -> Canvas.Align.RIGHT;
            default -> Canvas.Align.LEFT;
        };
        this.file = n.str("file", n.str("image", null));
        this.icon = n.str("icon", null);
        this.player = n.str("player", "{player}");
        this.value = n.str("value", "0");
        this.max = n.str("max", "100");
        this.source = n.str("source", "online").toLowerCase(Locale.ROOT);
        this.permission = n.str("permission", null);
        actions.load(n.list("actions"), log, where);
        actions.setCooldownMs(n.integer("cooldown", 250));
        if (type == Type.IMAGE && (file == null || file.isBlank())) throw new IllegalArgumentException("image needs 'file: name.png'");
    }

    public boolean clickable() {
        return broken == null && !actions.isEmpty();
    }

    /** Placeholder for an element that couldn't be read, so numbering always matches the file. */
    public static Element broken(String reason) {
        return new Element(Type.RECT, new Node(Map.of()), Logger.getAnonymousLogger(), "", reason);
    }

    public static Element parse(Map<?, ?> raw, Logger log, String where) {
        Node n = new Node(raw);
        String t = n.str("type", null);
        if (t == null) throw new IllegalArgumentException("missing 'type' (rect, text, lines, image, button, head, progress, graph)");
        Type type;
        try {
            type = Type.valueOf(t.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown type '" + t + "' (rect, text, lines, image, button, head, progress, graph)");
        }
        return new Element(type, n, log, where);
    }

    /** Tolerant YAML map reader. */
    static final class Node {
        private final Map<?, ?> m;

        Node(Map<?, ?> m) {
            this.m = m;
        }

        boolean has(String k) {
            return m.containsKey(k);
        }

        Object raw(String k) {
            return m.get(k);
        }

        String str(String k, String def) {
            Object o = m.get(k);
            return o == null ? def : o.toString();
        }

        int integer(String k, int def) {
            Object o = m.get(k);
            if (o instanceof Number num) return num.intValue();
            if (o == null) return def;
            try {
                return (int) Double.parseDouble(o.toString().trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("'" + k + "' must be a number");
            }
        }

        boolean bool(String k, boolean def) {
            Object o = m.get(k);
            if (o instanceof Boolean b) return b;
            return o == null ? def : Boolean.parseBoolean(o.toString().trim());
        }

        List<String> list(String k) {
            Object o = m.get(k);
            List<String> out = new ArrayList<>();
            if (o instanceof List<?> l) {
                for (Object x : l) if (x != null) out.add(x.toString());
            } else if (o != null) {
                out.add(o.toString());
            }
            return out;
        }
    }
}
