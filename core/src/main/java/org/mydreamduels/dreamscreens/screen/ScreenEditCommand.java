package org.mydreamduels.dreamscreens.screen;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.mydreamduels.dreamscreens.DreamScreens;
import org.mydreamduels.dreamscreens.action.Action;
import org.mydreamduels.dreamscreens.command.Cmd;
import org.mydreamduels.dreamscreens.util.Colors;
import org.mydreamduels.dreamscreens.util.Text;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * /se - edit a screen in game, FancyHolograms style.
 *   /se <screen>  (or just /se while looking at one)  -> start editing
 *   click an element on the screen to select it (click again = the one underneath)
 *   then: move, here, up/down/left/right, size, text, color, add, remove, copy, page, bg, undo ...
 */
public final class ScreenEditCommand extends Cmd {

    static final List<String> TYPES = List.of("text", "button", "rect", "image", "head", "progress", "graph", "lines");
    static final List<String> KEYS = List.of("x", "y", "w", "h", "size", "text", "lines", "scale", "spacing", "color", "hover-color",
            "text-color", "border", "border-color", "background", "shadow", "shadow-color", "round", "align", "file", "icon", "icon-size",
            "player", "value", "max", "source", "permission", "cooldown", "max-width", "scroll", "speed", "font", "font-size");
    private static final List<String> SUBS = List.of("select", "list", "info", "add", "remove", "move", "here", "up", "down", "left", "right",
            "size", "grow", "text", "color", "hover", "textcolor", "border", "scale", "align", "shadow", "set", "unset", "action", "copy",
            "front", "back", "page", "bg", "bgimage", "grid", "undo", "save", "done", "help",
            "transition", "sync", "sound", "auto", "schedule", "scroll", "font");

    public ScreenEditCommand(DreamScreens plugin) {
        super(plugin, "dreamscreens.screen");
    }

    private ScreenManager screens() {
        return plugin.screens();
    }

    // ------------------------------------------------------------ helpers

    private EditSession session(Player p) {
        EditSession ed = screens().session(p);
        if (ed == null) throw new Fail("You're not editing a screen. Look at one and type &f/se&c, or &f/se <screen>");
        if (screens().get(ed.screen.name()) != ed.screen) {
            screens().endSession(p);
            throw new Fail("That screen was deleted.");
        }
        return ed;
    }

    private PageEditor editor(EditSession ed) {
        PageEditor pe;
        try {
            pe = new PageEditor(ed.screen);
        } catch (IOException e) {
            throw new Fail(e.getMessage());
        }
        if (ed.page == null || pe.pageKey(ed.page) == null) {
            List<String> names = pe.pageNames();
            ed.page = names.isEmpty() ? null : names.get(0);
        }
        return pe;
    }

    /** The page being edited, or a clear error when the screen has none yet. */
    private ConfigurationSection current(PageEditor pe, EditSession ed) {
        ConfigurationSection s = ed.page == null ? null : pe.page(ed.page);
        if (s == null) throw new Fail("This screen has no pages yet. Make one: &f/se page add home");
        return s;
    }

    private Map<String, Object> selected(EditSession ed, List<Map<String, Object>> els) {
        if (ed.selected < 0 || ed.selected >= els.size()) throw new Fail("Select something first: click it on the screen, or &f/se select <number>&c (&f/se list&c).");
        return els.get(ed.selected);
    }

    private void save(Player p, EditSession ed, PageEditor pe, String done) {
        String err = pe.commit(screens());
        if (err != null) throw new Fail("Not changed - " + err);
        ed.screen.tick(p);
        Text.ok(p, done + " &8(/se undo)");
    }

    /** One element changed: load, change, save. */
    private void change(Player p, java.util.function.BiConsumer<Map<String, Object>, EditSession> how, String done) {
        EditSession ed = session(p);
        PageEditor pe = editor(ed);
        List<Map<String, Object>> els = pe.elements(ed.page);
        Map<String, Object> el = selected(ed, els);
        how.accept(el, ed);
        pe.setElements(ed.page, els);
        save(p, ed, pe, done);
    }

    private static Object dim(String raw, String what) {
        try {
            Dim.parse(PageEditor.value(raw), Dim.ZERO);
        } catch (IllegalArgumentException e) {
            throw new Fail(what + ": use a number (20), from the far edge (-20), a percent (50%) or center.");
        }
        return PageEditor.value(raw);
    }

    /** Named colour, #hex, none/transparent, or a live expression (<status:..>, {..}, %..%). */
    static String color(String raw) {
        String t = raw.trim();
        if (t.equalsIgnoreCase("none") || t.equalsIgnoreCase("transparent") || t.equalsIgnoreCase("clear")) return "none";
        if (t.contains("<") || t.contains("{") || t.contains("%")) return t;
        int rgb = Colors.rgb(t);
        if (rgb < 0) throw new Fail("'" + raw + "' isn't a colour. Use #FF5555, a name (black, white, red, gold, aqua, purple...) or none.");
        return String.format(Locale.ROOT, "#%06X", rgb);
    }

    private static int intArg(String s, String what) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            throw new Fail(what + " must be a whole number.");
        }
    }

    // ------------------------------------------------------------ run

    @Override
    protected void run(CommandSender s, String[] a) {
        Player p = player(s);
        if (a.length == 0) {
            if (screens().session(p) != null) {
                help(p);
                return;
            }
            Screen aimed = screens().aimedAny(p);
            if (aimed == null) throw new Fail("Look at a screen (or type &f/se <screen>&c) to start editing.");
            start(p, aimed);
            return;
        }
        String sub = a[0].toLowerCase(Locale.ROOT);
        if (!SUBS.contains(sub) && screens().get(a[0]) != null) {
            start(p, screens().get(a[0]));
            return;
        }
        switch (sub) {
            case "help" -> help(p);
            case "done", "exit", "stop", "off" -> {
                if (screens().endSession(p) == null) throw new Fail("You weren't editing.");
                Text.ok(p, "Stopped editing. Players see the screen normally again.");
            }
            case "select", "sel" -> select(p, a);
            case "list" -> list(p);
            case "info" -> describeSelection(p, session(p));
            case "add", "new" -> add(p, a);
            case "remove", "delete", "del" -> {
                EditSession ed = session(p);
                PageEditor pe = editor(ed);
                List<Map<String, Object>> els = pe.elements(ed.page);
                Map<String, Object> el = selected(ed, els);
                els.remove(ed.selected);
                pe.setElements(ed.page, els);
                ed.selected = -1;
                save(p, ed, pe, "Removed the " + PageEditor.typeOf(el) + ".");
            }
            case "move", "pos" -> {
                need(a, 3, "/se move <x> <y>   (20, -20 from the far edge, 50%, center)");
                Object x = dim(a[1], "x"), y = dim(a[2], "y");
                change(p, (el, ed) -> {
                    el.put("x", x);
                    el.put("y", y);
                }, "Moved to &f" + a[1] + " " + a[2]);
            }
            case "here" -> {
                EditSession ed = session(p);
                if (ed.cursorX < 0) throw new Fail("Look at the spot on the screen where it should go.");
                int cx = ed.cursorX, cy = ed.cursorY;
                change(p, (el, e) -> {
                    el.put("x", cx);
                    el.put("y", cy);
                }, "Moved to where you look (&f" + cx + " " + cy + "&7)");
            }
            case "up", "down", "left", "right" -> nudge(p, sub, a.length > 1 ? intArg(a[1], "Pixels") : 1);
            case "size", "resize" -> {
                need(a, 2, "/se size <w> <h>   (heads: /se size <size>)");
                change(p, (el, ed) -> {
                    String t = PageEditor.typeOf(el);
                    if (t.equals("text") || t.equals("lines")) throw new Fail("Text grows with &f/se scale <1-8>&c.");
                    if (t.equals("head")) {
                        el.put("size", dim(a[1], "size"));
                        return;
                    }
                    need(a, 3, "/se size <w> <h>");
                    el.put("w", dim(a[1], "w"));
                    el.put("h", dim(a[2], "h"));
                }, "Resized.");
            }
            case "grow" -> {
                need(a, 3, "/se grow <+w> <+h>   (negative shrinks)");
                int dw = intArg(a[1], "Width"), dh = intArg(a[2], "Height");
                EditSession ed = session(p);
                PageRenderer.Box b = ed.screen.drawnBox(p, ed.selected);
                if (b == null) throw new Fail("Select something first.");
                change(p, (el, e) -> {
                    el.put("w", Math.max(2, b.w() + dw));
                    el.put("h", Math.max(2, b.h() + dh - ("button".equals(PageEditor.typeOf(el)) ? 2 : 0)));
                }, "Size now &f" + Math.max(2, b.w() + dw) + "x" + Math.max(2, b.h() + dh));
            }
            case "text" -> {
                need(a, 2, "/se text <text>   (lines: separate with |)");
                String t = join(a, 1);
                change(p, (el, ed) -> {
                    if (PageEditor.typeOf(el).equals("lines")) el.put("lines", new ArrayList<>(List.of(t.split("\\|"))));
                    else el.put("text", t);
                }, "Text set.");
            }
            case "color", "colour" -> {
                need(a, 2, "/se color <colour|none>");
                String c = color(join(a, 1));
                change(p, (el, ed) -> el.put("color", c), "Colour &f" + c);
            }
            case "hover" -> {
                need(a, 2, "/se hover <colour>");
                String c = color(join(a, 1));
                change(p, (el, ed) -> el.put("hover-color", c), "Hover colour &f" + c);
            }
            case "textcolor", "textcolour" -> {
                need(a, 2, "/se textcolor <colour>");
                String c = color(join(a, 1));
                change(p, (el, ed) -> el.put("text-color", c), "Text colour &f" + c);
            }
            case "border" -> {
                need(a, 2, "/se border <px|off> [colour]");
                int px = a[1].equalsIgnoreCase("off") ? 0 : intArg(a[1], "Border");
                String c = a.length > 2 ? color(a[2]) : null;
                change(p, (el, ed) -> {
                    el.put("border", px);
                    if (c != null) el.put("border-color", c);
                }, px == 0 ? "Border off." : "Border &f" + px + "px");
            }
            case "scale" -> {
                need(a, 2, "/se scale <1-8>");
                int sc = Math.max(1, Math.min(8, intArg(a[1], "Scale")));
                change(p, (el, ed) -> el.put("scale", sc), "Scale &f" + sc);
            }
            case "align" -> {
                need(a, 2, "/se align <left|center|right>");
                String al = a[1].toLowerCase(Locale.ROOT);
                if (!List.of("left", "center", "right").contains(al)) throw new Fail("Use left, center or right.");
                change(p, (el, ed) -> el.put("align", al), "Aligned &f" + al);
            }
            case "shadow" -> {
                need(a, 2, "/se shadow <on|off|colour>");
                String v = a[1].toLowerCase(Locale.ROOT);
                change(p, (el, ed) -> {
                    if (v.equals("on") || v.equals("true")) {
                        el.put("shadow", true);
                        el.remove("shadow-color");
                    } else if (v.equals("off") || v.equals("false")) el.put("shadow", false);
                    else {
                        el.put("shadow", true);
                        el.put("shadow-color", color(a[1]));
                    }
                }, "Shadow &f" + a[1]);
            }
            case "set" -> {
                need(a, 3, "/se set <property> <value>");
                String key = a[1].toLowerCase(Locale.ROOT);
                if (!KEYS.contains(key)) throw new Fail("Unknown property. Use: " + String.join(", ", KEYS));
                String raw = join(a, 2);
                Object v = key.endsWith("color") || key.equals("background") ? color(raw)
                        : key.equals("lines") ? new ArrayList<>(List.of(raw.split("\\|"))) : PageEditor.value(raw);
                change(p, (el, ed) -> el.put(key, v), key + " = &f" + raw);
            }
            case "unset" -> {
                need(a, 2, "/se unset <property>");
                String key = a[1].toLowerCase(Locale.ROOT);
                change(p, (el, ed) -> el.remove(key), key + " back to default.");
            }
            case "action", "actions" -> action(p, a);
            case "copy", "duplicate" -> {
                EditSession ed = session(p);
                PageEditor pe = editor(ed);
                List<Map<String, Object>> els = pe.elements(ed.page);
                Map<String, Object> copy = new LinkedHashMap<>(selected(ed, els));
                PageRenderer.Box b = ed.screen.drawnBox(p, ed.selected);
                if (b != null) {
                    copy.put("x", b.x() + 8);
                    copy.put("y", b.y() + 8);
                    copy.remove("align");
                }
                els.add(copy);
                pe.setElements(ed.page, els);
                ed.selected = els.size() - 1;
                save(p, ed, pe, "Copied - the copy is selected (&f#" + els.size() + "&7).");
            }
            case "front", "back" -> {
                EditSession ed = session(p);
                PageEditor pe = editor(ed);
                List<Map<String, Object>> els = pe.elements(ed.page);
                Map<String, Object> el = selected(ed, els);
                els.remove(ed.selected);
                if (sub.equals("front")) els.add(el);
                else els.add(0, el);
                ed.selected = sub.equals("front") ? els.size() - 1 : 0;
                pe.setElements(ed.page, els);
                save(p, ed, pe, sub.equals("front") ? "Brought to the front." : "Sent to the back.");
            }
            case "page", "pages" -> page(p, a);
            case "bg", "background" -> {
                need(a, 2, "/se bg <colour|none>   (none = see-through)");
                EditSession ed = session(p);
                PageEditor pe = editor(ed);
                String c = color(join(a, 1));
                current(pe, ed).set("background", c);
                save(p, ed, pe, "Background of &f" + ed.page + "&7: &f" + (c.equals("none") ? "see-through" : c));
            }
            case "bgimage" -> {
                need(a, 2, "/se bgimage <file.png|off>");
                EditSession ed = session(p);
                PageEditor pe = editor(ed);
                boolean off = a[1].equalsIgnoreCase("off") || a[1].equalsIgnoreCase("none");
                if (!off && !screens().images().files().contains(a[1])) throw new Fail("No image '" + a[1] + "' in plugins/DreamScreens/images (&f/screen images&c).");
                current(pe, ed).set("background-image", off ? null : a[1]);
                save(p, ed, pe, off ? "Background image removed." : "Background image &f" + a[1]);
            }
            case "scroll" -> {
                need(a, 2, "/se scroll <on|off> [speed]   (text slides sideways inside its width)");
                boolean on = !a[1].equalsIgnoreCase("off") && !a[1].equalsIgnoreCase("false");
                Integer speed = a.length > 2 ? intArg(a[2], "Speed") : null;
                change(p, (el, ed) -> {
                    if (!PageEditor.typeOf(el).equals("text")) throw new Fail("Only text can scroll.");
                    el.put("scroll", on);
                    if (speed != null) el.put("speed", Math.max(1, speed));
                    if (on && !el.containsKey("w")) el.put("w", 160);
                    el.remove("align");
                }, on ? "Scrolling" + (speed == null ? "" : " at &f" + speed + " px/s") + " &7(width = &f/se size <w> 10&7)" : "Scrolling off.");
            }
            case "font" -> {
                need(a, 2, "/se font <file.ttf|default> [size]");
                boolean def = a[1].equalsIgnoreCase("default") || a[1].equalsIgnoreCase("off");
                if (!def && !screens().fonts().files().contains(a[1]))
                    throw new Fail("No font '" + a[1] + "' in plugins/DreamScreens/fonts (&f/screen fonts&c).");
                Integer size = a.length > 2 ? intArg(a[2], "Size") : null;
                change(p, (el, ed) -> {
                    if (!PageEditor.typeOf(el).equals("text")) throw new Fail("Custom fonts work on text elements.");
                    if (def) {
                        el.remove("font");
                        el.remove("font-size");
                    } else {
                        el.put("font", a[1]);
                        if (size != null) el.put("font-size", Math.max(6, size));
                    }
                }, def ? "Back to the Minecraft font." : "Font &f" + a[1] + (size == null ? "" : " " + size + "px"));
            }
            case "transition" -> {
                need(a, 2, "/se transition <" + String.join("|", Page.Set.TRANSITIONS) + "> [ticks]");
                String type = a[1].toLowerCase(Locale.ROOT);
                if (!Page.Set.TRANSITIONS.contains(type)) throw new Fail("Use " + String.join(", ", Page.Set.TRANSITIONS) + ".");
                EditSession ed = session(p);
                PageEditor pe = editor(ed);
                pe.yaml.set("transition", type);
                if (a.length > 2) pe.yaml.set("transition-ticks", Math.max(2, Math.min(40, intArg(a[2], "Ticks"))));
                save(p, ed, pe, "Page changes now use &f" + type + (type.equals("none") ? "" : " &8(try it: /se done, then click a button)"));
            }
            case "sync" -> {
                need(a, 2, "/se sync <on|off>   (slideshow by the clock: everyone + linked screens see the same page)");
                boolean on = a[1].equalsIgnoreCase("on") || a[1].equalsIgnoreCase("true");
                EditSession ed = session(p);
                PageEditor pe = editor(ed);
                pe.yaml.set("sync", on ? true : null);
                save(p, ed, pe, on ? "Slideshow synced to the clock." : "Each player's slideshow runs on its own again.");
            }
            case "sound" -> {
                need(a, 2, "/se sound <sound|off>   (plays when the page changes; /se page sound for one page)");
                EditSession ed = session(p);
                PageEditor pe = editor(ed);
                boolean off = a[1].equalsIgnoreCase("off") || a[1].equalsIgnoreCase("none");
                pe.yaml.set("switch-sound", off ? null : a[1].toLowerCase(Locale.ROOT));
                save(p, ed, pe, off ? "No page-change sound." : "Page-change sound &f" + a[1]);
            }
            case "auto" -> auto(p, a);
            case "schedule" -> schedule(p, a);
            case "grid" -> {
                EditSession ed = session(p);
                ed.grid = !ed.grid;
                ed.screen.tick(p);
                Text.ok(p, "Grid " + (ed.grid ? "&aon &7(lines every 16 px, cyan = middle)" : "&coff"));
            }
            case "undo" -> {
                EditSession ed = session(p);
                if (!screens().undo(ed.screen)) throw new Fail("Nothing to undo.");
                ed.selected = -1;
                ed.screen.tick(p);
                Text.ok(p, "Undone.");
            }
            case "save" -> {
                need(a, 2, "/se save <design-name>   (saves this screen as a design you can put on other screens)");
                EditSession ed = session(p);
                if (!a[1].matches("[A-Za-z0-9_\\-]{1,48}")) throw new Fail("Names may use letters, numbers, _ and -.");
                try {
                    java.nio.file.Files.copy(ed.screen.pagesFile().toPath(), new java.io.File(screens().designs().dir(), a[1] + ".yml").toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    throw new Fail("Could not save: " + e.getMessage());
                }
                Text.ok(p, "Saved as design &f" + a[1] + "&7. Use it anywhere: &f/screen design <screen> " + a[1]);
            }
            default -> help(p);
        }
    }

    private void start(Player p, Screen sc) {
        Page start = sc.pages().startPage();
        String current = sc.currentPage(p);
        EditSession ed = screens().startSession(p, sc, current != null ? current : start == null ? null : start.name());
        sc.tick(p);
        Text.ok(p, "Editing &f" + sc.name() + "&7, page &f" + ed.page + "&7. &fClick&7 something on the screen to select it.");
        Text.raw(p, "&7 Then: &f/se move &8| &fhere &8| &fup/down/left/right &8| &fsize &8| &ftext &8| &fcolor &8| &fadd &8| &fremove &8| &fundo");
        Text.raw(p, "&7 Pages: &f/se page &8| &7Background: &f/se bg black&7/&fnone &8| &7Grid: &f/se grid &8| &7All: &f/se help &8| &7Stop: &f/se done");
    }

    private void select(Player p, String[] a) {
        EditSession ed = session(p);
        Page page = ed.screen.pages().page(ed.page);
        int count = page == null ? 0 : page.elements().size();
        if (a.length < 2) {
            if (ed.cursorX < 0) throw new Fail("Click an element, look at one and type &f/se select&c, or &f/se select <number>");
            ed.screen.touch(p, true);
            return;
        }
        int n = intArg(a[1], "Number");
        if (n < 1 || n > count) throw new Fail("Pick 1-" + count + " (&f/se list&c).");
        ed.selected = n - 1;
        ed.screen.tick(p);
        describeSelection(p, ed);
    }

    private void list(Player p) {
        EditSession ed = session(p);
        PageEditor pe = editor(ed);
        List<Map<String, Object>> els = pe.elements(ed.page);
        Text.raw(p, "&#22D3EE&lPage " + ed.page + " &7(" + els.size() + " elements, top = drawn last)");
        for (int i = 0; i < els.size(); i++) {
            Map<String, Object> el = els.get(i);
            Text.raw(p, (i == ed.selected ? "&e> " : "&8  ") + "#" + (i + 1) + " &f" + PageEditor.typeOf(el) + " &7" + summary(el));
        }
    }

    private static String summary(Map<String, Object> el) {
        Object t = el.get("text");
        if (t == null && el.get("lines") instanceof List<?> l && !l.isEmpty()) t = l.get(0) + (l.size() > 1 ? " (+" + (l.size() - 1) + ")" : "");
        if (t == null) t = el.get("file");
        if (t == null) t = el.get("player");
        if (t == null) t = el.get("source");
        String s = t == null ? "" : "'" + Text.strip(String.valueOf(t)) + "' ";
        if (s.length() > 32) s = s.substring(0, 30) + "..' ";
        return s + "at " + el.getOrDefault("x", 0) + "," + el.getOrDefault("y", 0);
    }

    /** Chat summary of what's selected (also used right after a click). */
    void describeSelection(Player p, EditSession ed) {
        Page page = ed.screen.pages().page(ed.page);
        if (ed.selected < 0 || page == null || ed.selected >= page.elements().size()) {
            Text.send(p, "Nothing there. Click on an element of the screen.");
            return;
        }
        Element e = page.elements().get(ed.selected);
        if (e.broken != null) {
            Text.send(p, "&e#" + (ed.selected + 1) + " &ccan't be read: &f" + e.broken + " &7- fix it with &f/se set&7, or &f/se remove");
            return;
        }
        PageRenderer.Box b = ed.screen.drawnBox(p, ed.selected);
        StringBuilder line = new StringBuilder("&e#" + (ed.selected + 1) + " &f" + e.type.name().toLowerCase(Locale.ROOT));
        if (b != null) line.append(" &7at &f").append(b.x()).append(",").append(b.y()).append(" &7size &f").append(b.w()).append("x").append(b.h());
        if (!e.text.isEmpty() && e.type != Element.Type.IMAGE) line.append(" &7'").append(e.text).append("&7'");
        Text.send(p, line.toString());
        if (e.clickable()) {
            List<String> acts = new ArrayList<>();
            for (Action act : e.actions.actions()) acts.add(act.serialize());
            Text.raw(p, "&7 Actions: &f" + String.join("&8, &f", acts));
        }
        Text.raw(p, "&8 Click again for the element underneath. &7/se move, here, up 5, size, text, color, action add, remove");
    }

    private void nudge(Player p, String dir, int px) {
        EditSession ed = session(p);
        PageRenderer.Box b = ed.screen.drawnBox(p, ed.selected);
        if (b == null) throw new Fail("Select something first (click it).");
        int dx = dir.equals("left") ? -px : dir.equals("right") ? px : 0;
        int dy = dir.equals("up") ? -px : dir.equals("down") ? px : 0;
        change(p, (el, e) -> {
            String t = PageEditor.typeOf(el);
            int x = b.x();
            if (t.equals("text") || t.equals("lines")) {
                // text x is its anchor: the left edge, middle or right edge depending on align
                String align = String.valueOf(el.getOrDefault("align", isCenter(el.get("x")) ? "center" : "left")).toLowerCase(Locale.ROOT);
                x = align.equals("center") ? b.x() + b.w() / 2 : align.equals("right") ? b.x() + b.w() : b.x();
                el.put("align", align);
            }
            el.put("x", x + dx);
            el.put("y", b.y() + dy);
        }, "Moved " + dir + " " + px + "px");
    }

    private static boolean isCenter(Object x) {
        return x != null && List.of("center", "middle", "centre").contains(x.toString().toLowerCase(Locale.ROOT));
    }

    private void add(Player p, String[] a) {
        need(a, 2, "/se add <" + String.join("|", TYPES) + "> [text / file / player]");
        String type = a[1].toLowerCase(Locale.ROOT);
        if (!TYPES.contains(type)) throw new Fail("Types: " + String.join(", ", TYPES));
        EditSession ed = session(p);
        String arg = a.length > 2 ? join(a, 2) : null;
        int x = ed.cursorX >= 0 ? ed.cursorX : -1, y = ed.cursorY >= 0 ? ed.cursorY : -1;
        Map<String, Object> el = new LinkedHashMap<>();
        el.put("type", type);
        el.put("x", x >= 0 ? (Object) x : "center");
        el.put("y", y >= 0 ? (Object) y : "center");
        switch (type) {
            case "text" -> {
                el.put("text", arg == null ? "&fNew text" : arg);
                if (x < 0) el.put("align", "center");
            }
            case "lines" -> el.put("lines", new ArrayList<>(List.of((arg == null ? "&fLine one|&7Line two" : arg).split("\\|"))));
            case "button" -> {
                el.put("w", 96);
                el.put("h", 22);
                el.put("text", arg == null ? "&lButton" : arg);
                el.put("color", "#3B82F6");
                el.put("actions", new ArrayList<>(List.of("message:&aEdit me with /se action")));
            }
            case "rect" -> {
                el.put("w", 96);
                el.put("h", 48);
                el.put("color", "#171B24");
            }
            case "image" -> {
                if (arg == null) throw new Fail("Which image? &f/se add image <file.png>&c - files: " + String.join(", ", screens().images().files()));
                if (!screens().images().files().contains(arg)) throw new Fail("No image '" + arg + "' in plugins/DreamScreens/images.");
                el.put("file", arg);
                el.put("w", 32);
                el.put("h", 32);
            }
            case "head" -> {
                el.put("player", arg == null ? "{player}" : arg);
                el.put("size", 32);
            }
            case "progress" -> {
                el.put("w", 120);
                el.put("h", 8);
                el.put("value", "{online}");
                el.put("max", "{max}");
            }
            case "graph" -> {
                el.put("w", 160);
                el.put("h", 60);
                el.put("source", arg == null ? "online" : arg.toLowerCase(Locale.ROOT));
            }
            default -> {
            }
        }
        PageEditor pe = editor(ed);
        current(pe, ed);
        List<Map<String, Object>> els = pe.elements(ed.page);
        els.add(el);
        pe.setElements(ed.page, els);
        ed.selected = els.size() - 1;
        save(p, ed, pe, "Added a &f" + type + "&7 (" + (x >= 0 ? "where you look" : "in the middle") + ") - it's selected.");
    }

    private void action(Player p, String[] a) {
        need(a, 2, "/se action <add|remove|clear|list> ...");
        String op = a[1].toLowerCase(Locale.ROOT);
        switch (op) {
            case "list" -> {
                EditSession ed = session(p);
                List<Map<String, Object>> els = editor(ed).elements(ed.page);
                Object acts = selected(ed, els).get("actions");
                List<?> l = acts instanceof List<?> x ? x : List.of();
                if (l.isEmpty()) Text.send(p, "No actions. &f/se action add page:shop &7or &fplayer:spawn &7...");
                for (int i = 0; i < l.size(); i++) Text.raw(p, "&8 " + (i + 1) + ". &f" + l.get(i));
            }
            case "add" -> {
                need(a, 3, "/se action add <type:value>   e.g. page:shop  player:spawn  message:&aHi  server:lobby  close");
                String raw = join(a, 2);
                try {
                    Action.parse(raw);
                } catch (IllegalArgumentException e) {
                    throw new Fail(e.getMessage());
                }
                change(p, (el, ed) -> {
                    List<Object> l = el.get("actions") instanceof List<?> x ? new ArrayList<>(x) : new ArrayList<>();
                    // a fresh button's placeholder action goes away with the first real one
                    l.removeIf(o -> String.valueOf(o).equals("message:&aEdit me with /se action"));
                    l.add(raw);
                    el.put("actions", l);
                }, "Action added: &f" + raw);
            }
            case "remove" -> {
                need(a, 3, "/se action remove <number>");
                int n = intArg(a[2], "Number");
                change(p, (el, ed) -> {
                    List<Object> l = el.get("actions") instanceof List<?> x ? new ArrayList<>(x) : new ArrayList<>();
                    if (n < 1 || n > l.size()) throw new Fail("Pick 1-" + l.size() + " (&f/se action list&c).");
                    l.remove(n - 1);
                    el.put("actions", l);
                }, "Action removed.");
            }
            case "clear" -> change(p, (el, ed) -> el.remove("actions"), "Actions cleared (not clickable now).");
            default -> throw new Fail("Use add, remove, clear or list.");
        }
    }

    private List<Map<String, Object>> rules(PageEditor pe, String key) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<?, ?> m : pe.yaml.getMapList(key)) {
            Map<String, Object> c = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) c.put(String.valueOf(e.getKey()), e.getValue());
            out.add(c);
        }
        return out;
    }

    private void auto(Player p, String[] a) {
        need(a, 2, "/se auto <add|list|remove|clear>");
        EditSession ed = session(p);
        PageEditor pe = editor(ed);
        List<Map<String, Object>> rules = rules(pe, "auto-pages");
        switch (a[1].toLowerCase(Locale.ROOT)) {
            case "list" -> {
                if (rules.isEmpty()) Text.send(p, "No auto pages. &f/se auto add live %koth_is_running% == true");
                for (int i = 0; i < rules.size(); i++)
                    Text.raw(p, "&8 " + (i + 1) + ". &7show &f" + rules.get(i).get("page") + " &7when &f" + rules.get(i).get("when"));
                Text.raw(p, "&8 First rule that holds wins. Checked every second; announcements still come first.");
            }
            case "add" -> {
                need(a, 4, "/se auto add <page> <condition>   e.g. live %koth_is_running% == true   or   soon {countdown:2026-12-31 20:00} == LIVE");
                String key = pe.pageKey(a[2]);
                if (key == null) throw new Fail("No page '" + a[2] + "'.");
                String when = join(a, 3);
                rules.add(new LinkedHashMap<>(Map.of("page", key, "when", when)));
                pe.yaml.set("auto-pages", rules);
                save(p, ed, pe, "Shows &f" + key + "&7 while &f" + when);
            }
            case "remove" -> {
                need(a, 3, "/se auto remove <number>");
                int n = intArg(a[2], "Number");
                if (n < 1 || n > rules.size()) throw new Fail("Pick 1-" + rules.size() + " (&f/se auto list&c).");
                rules.remove(n - 1);
                pe.yaml.set("auto-pages", rules.isEmpty() ? null : rules);
                save(p, ed, pe, "Auto page removed.");
            }
            case "clear" -> {
                pe.yaml.set("auto-pages", null);
                save(p, ed, pe, "All auto pages removed.");
            }
            default -> throw new Fail("Use add, list, remove or clear.");
        }
    }

    private void schedule(Player p, String[] a) {
        need(a, 2, "/se schedule <add|list|remove|clear>");
        EditSession ed = session(p);
        PageEditor pe = editor(ed);
        List<Map<String, Object>> rules = rules(pe, "schedule");
        switch (a[1].toLowerCase(Locale.ROOT)) {
            case "list" -> {
                if (rules.isEmpty()) Text.send(p, "Nothing scheduled. &f/se schedule add night 22:00 06:00");
                for (int i = 0; i < rules.size(); i++)
                    Text.raw(p, "&8 " + (i + 1) + ". &f" + rules.get(i).get("page") + " &7from &f" + rules.get(i).get("from") + " &7to &f" + rules.get(i).get("to"));
                Text.raw(p, "&8 Server time now: &7" + java.time.LocalTime.now().withNano(0).withSecond(0));
            }
            case "add" -> {
                need(a, 5, "/se schedule add <page> <from HH:mm> <to HH:mm>   e.g. night 22:00 06:00");
                String key = pe.pageKey(a[2]);
                if (key == null) throw new Fail("No page '" + a[2] + "'.");
                try {
                    Page.Set.time(a[3]);
                    Page.Set.time(a[4]);
                } catch (RuntimeException e) {
                    throw new Fail("Times look like 08:00 or 22:30.");
                }
                rules.add(new LinkedHashMap<>(Map.of("page", key, "from", a[3], "to", a[4])));
                pe.yaml.set("schedule", rules);
                save(p, ed, pe, "&f" + key + "&7 shows from &f" + a[3] + "&7 to &f" + a[4] + " &8(server time)");
            }
            case "remove" -> {
                need(a, 3, "/se schedule remove <number>");
                int n = intArg(a[2], "Number");
                if (n < 1 || n > rules.size()) throw new Fail("Pick 1-" + rules.size() + " (&f/se schedule list&c).");
                rules.remove(n - 1);
                pe.yaml.set("schedule", rules.isEmpty() ? null : rules);
                save(p, ed, pe, "Schedule entry removed.");
            }
            case "clear" -> {
                pe.yaml.set("schedule", null);
                save(p, ed, pe, "Schedule cleared.");
            }
            default -> throw new Fail("Use add, list, remove or clear.");
        }
    }

    private void page(Player p, String[] a) {
        EditSession ed = session(p);
        PageEditor pe = editor(ed);
        if (a.length < 2) {
            String start = pe.yaml.getString("start", "");
            List<String> out = new ArrayList<>();
            for (String n : pe.pageNames()) out.add((n.equalsIgnoreCase(ed.page) ? "&e" : "&f") + n + (n.equalsIgnoreCase(start) ? " &8(start)" : ""));
            Text.send(p, "Pages: " + String.join("&8, ", out));
            Text.raw(p, "&7 /se page <name> &8| &fopen <name> &8| &fadd <name> [copy-of] &8| &fremove <name> &8| &frename <old> <new> &8| &fstart <name> &8| &fnext <page|off> [sec] &8| &frefresh <ticks>");
            return;
        }
        String op = a[1].toLowerCase(Locale.ROOT);
        switch (op) {
            case "add" -> {
                need(a, 3, "/se page add <name> [copy-of]");
                String name = a[2];
                if (!name.matches("[A-Za-z0-9_\\-]{1,32}")) throw new Fail("Page names may use letters, numbers, _ and -.");
                if (pe.pageKey(name) != null) throw new Fail("That page exists.");
                if (a.length > 3) {
                    if (pe.pageKey(a[3]) == null) throw new Fail("No page '" + a[3] + "'.");
                    pe.copyPage(pe.pageKey(a[3]), name);
                } else {
                    ConfigurationSection cur = pe.page(ed.page);
                    pe.createPage(name, cur == null ? "none" : cur.getString("background", "none"));
                }
                ed.page = name;
                ed.selected = -1;
                save(p, ed, pe, "Page &f" + name + "&7 added and opened. Link a button to it: &f/se action add page:" + name);
            }
            case "remove" -> {
                need(a, 3, "/se page remove <name>");
                String key = pe.pageKey(a[2]);
                if (key == null) throw new Fail("No page '" + a[2] + "'.");
                if (pe.pageNames().size() <= 1) throw new Fail("A screen needs at least one page.");
                pe.removePage(key);
                if (key.equalsIgnoreCase(pe.yaml.getString("start", ""))) pe.yaml.set("start", pe.pageNames().get(0));
                if (key.equalsIgnoreCase(ed.page)) ed.page = pe.yaml.getString("start");
                ed.selected = -1;
                save(p, ed, pe, "Page &f" + key + "&7 removed.");
            }
            case "rename" -> {
                need(a, 4, "/se page rename <old> <new>");
                String key = pe.pageKey(a[2]);
                if (key == null) throw new Fail("No page '" + a[2] + "'.");
                String to = a[3];
                if (!to.matches("[A-Za-z0-9_\\-]{1,32}")) throw new Fail("Page names may use letters, numbers, _ and -.");
                if (pe.pageKey(to) != null) throw new Fail("That name is taken.");
                pe.copyPage(key, to);
                pe.removePage(key);
                if (key.equalsIgnoreCase(pe.yaml.getString("start", ""))) pe.yaml.set("start", to);
                int links = 0;
                for (String n : pe.pageNames()) {
                    List<Map<String, Object>> els = pe.elements(n);
                    for (Map<String, Object> el : els) {
                        if (!(el.get("actions") instanceof List<?> l)) continue;
                        List<Object> fixed = new ArrayList<>();
                        for (Object o : l) {
                            String s = String.valueOf(o);
                            if (s.equalsIgnoreCase("page:" + key)) {
                                s = "page:" + to;
                                links++;
                            }
                            fixed.add(s);
                        }
                        el.put("actions", fixed);
                    }
                    pe.setElements(n, els);
                    ConfigurationSection ps = pe.page(n);
                    if (ps != null && key.equalsIgnoreCase(ps.getString("next", ""))) ps.set("next", to);
                }
                if (key.equalsIgnoreCase(ed.page)) ed.page = to;
                save(p, ed, pe, "Renamed &f" + key + "&7 to &f" + to + "&7 (" + links + " button links updated).");
            }
            case "start" -> {
                need(a, 3, "/se page start <name>");
                String key = pe.pageKey(a[2]);
                if (key == null) throw new Fail("No page '" + a[2] + "'.");
                pe.yaml.set("start", key);
                save(p, ed, pe, "Players now land on &f" + key);
            }
            case "next" -> {
                need(a, 3, "/se page next <page|off> [seconds]   (this page switches to <page> by itself)");
                ConfigurationSection cur = current(pe, ed);
                if (a[2].equalsIgnoreCase("off")) {
                    cur.set("next", null);
                    cur.set("seconds", null);
                    save(p, ed, pe, "Slideshow off for &f" + ed.page);
                    return;
                }
                String key = pe.pageKey(a[2]);
                if (key == null) throw new Fail("No page '" + a[2] + "'.");
                int sec = a.length > 3 ? Math.max(1, intArg(a[3], "Seconds")) : 10;
                cur.set("next", key);
                cur.set("seconds", sec);
                save(p, ed, pe, "&f" + ed.page + "&7 moves on to &f" + key + "&7 after &f" + sec + "s &8(not while you edit)");
            }
            case "sound" -> {
                need(a, 3, "/se page sound <sound|off>   (plays when this page opens)");
                boolean off = a[2].equalsIgnoreCase("off") || a[2].equalsIgnoreCase("none");
                current(pe, ed).set("sound", off ? null : a[2].toLowerCase(Locale.ROOT));
                save(p, ed, pe, off ? "No sound for &f" + ed.page : "&f" + ed.page + "&7 plays &f" + a[2] + "&7 when it opens.");
            }
            case "refresh" -> {
                need(a, 3, "/se page refresh <ticks>   (20 = every second, 0 = only when needed)");
                int t = Math.max(0, intArg(a[2], "Ticks"));
                current(pe, ed).set("refresh", t);
                save(p, ed, pe, "Refresh every &f" + t + "&7 ticks.");
            }
            case "open" -> {
                need(a, 3, "/se page open <name>");
                String key = pe.pageKey(a[2]);
                if (key == null) throw new Fail("No page '" + a[2] + "'.");
                ed.page = key;
                ed.selected = -1;
                ed.screen.tick(p);
                Text.ok(p, "Now editing page &f" + key);
            }
            default -> {
                String key = pe.pageKey(a[1]);
                if (key == null) throw new Fail("No page '" + a[1] + "'. &f/se page add " + a[1] + "&c to make it.");
                ed.page = key;
                ed.selected = -1;
                ed.screen.tick(p);
                Text.ok(p, "Now editing page &f" + key);
            }
        }
    }

    private void help(CommandSender s) {
        Text.raw(s, "&#22D3EE&l/se &7- edit a screen in game &8(click an element to select it)");
        Text.raw(s, "&f /se <screen> &8| &f/se &7(look at it) &8| &f/se done");
        Text.raw(s, "&f select [n] &8| &flist &8| &finfo &8| &fadd <text|button|rect|image|head|progress|graph|lines> [text]");
        Text.raw(s, "&f move <x> <y> &8| &fhere &8| &fup/down/left/right [px] &8| &fsize <w> <h> &8| &fgrow <+w> <+h>");
        Text.raw(s, "&f text <text> &8| &fcolor <c> &8| &fhover <c> &8| &ftextcolor <c> &8| &fborder <px> [c] &8| &fscale <n> &8| &falign &8| &fshadow");
        Text.raw(s, "&f action add|remove|clear|list &8| &fcopy &8| &ffront &8| &fback &8| &fremove &8| &fset/unset <property>");
        Text.raw(s, "&f page [name|add|remove|rename|start|next|refresh] &8| &fbg <colour|none> &8| &fbgimage <file|off>");
        Text.raw(s, "&f scroll <on|off> [speed] &8| &ffont <file.ttf> [size] &8| &ftransition <wipe|slide|down|split|none> &8| &fsync on|off");
        Text.raw(s, "&f sound <sound|off> &8| &fpage sound <sound> &8| &fauto add <page> <condition> &8| &fschedule add <page> <from> <to>");
        Text.raw(s, "&f grid &8| &fundo &8| &fsave <design> &7- colours: #FF5555 or black, white, red, gold, aqua, purple...");
    }

    @Override
    protected List<String> tab(CommandSender s, String[] a) {
        if (a.length == 1) {
            List<String> out = new ArrayList<>(SUBS);
            out.addAll(screens().names());
            return out;
        }
        String sub = a[0].toLowerCase(Locale.ROOT);
        EditSession ed = s instanceof Player p ? screens().session(p) : null;
        if (a.length == 2) {
            return switch (sub) {
                case "add" -> TYPES;
                case "move" -> List.of("center", "10", "50%", "-10");
                case "up", "down", "left", "right" -> List.of("1", "5", "10", "20");
                case "color", "hover", "textcolor", "bg", "border-color" -> List.of("none", "black", "white", "red", "gold", "yellow", "green", "aqua", "blue", "purple", "pink", "gray", "#171B24");
                case "border" -> List.of("off", "1", "2");
                case "scale" -> List.of("1", "2", "3", "4");
                case "align" -> List.of("left", "center", "right");
                case "shadow" -> List.of("on", "off", "black");
                case "set", "unset" -> KEYS;
                case "transition" -> Page.Set.TRANSITIONS;
                case "sync", "scroll" -> List.of("on", "off");
                case "sound" -> List.of("off", "ui.button.click", "block.note_block.pling", "entity.experience_orb.pickup", "item.book.page_turn");
                case "auto", "schedule" -> List.of("add", "list", "remove", "clear");
                case "font" -> {
                    List<String> out = new ArrayList<>(screens().fonts().files());
                    out.add("default");
                    yield out;
                }
                case "action" -> List.of("add", "remove", "clear", "list");
                case "bgimage", "image" -> {
                    List<String> out = new ArrayList<>(screens().images().files());
                    out.add("off");
                    yield out;
                }
                case "page" -> {
                    List<String> out = new ArrayList<>(List.of("add", "remove", "rename", "start", "next", "refresh", "open", "sound"));
                    if (ed != null) out.addAll(ed.screen.pages().names());
                    yield out;
                }
                case "select" -> {
                    List<String> out = new ArrayList<>();
                    Page pg = ed == null ? null : ed.screen.pages().page(ed.page);
                    if (pg != null) for (int i = 1; i <= pg.elements().size(); i++) out.add(String.valueOf(i));
                    yield out;
                }
                default -> List.of();
            };
        }
        if (a.length == 3) {
            if (sub.equals("move")) return List.of("center", "10", "50%", "-10");
            if (sub.equals("action") && a[1].equalsIgnoreCase("add")) return Action.EXAMPLES;
            if (sub.equals("add") && a[1].equalsIgnoreCase("image")) return screens().images().files();
            if (sub.equals("add") && a[1].equalsIgnoreCase("graph")) return List.of("online", "tps", "mspt");
            if ((sub.equals("auto") || sub.equals("schedule")) && a[1].equalsIgnoreCase("add") && ed != null) return ed.screen.pages().names();
            if (sub.equals("transition")) return List.of("6", "8", "12");
            if (sub.equals("page") && ed != null && List.of("remove", "rename", "start", "next", "open").contains(a[1].toLowerCase(Locale.ROOT))) {
                List<String> out = new ArrayList<>(ed.screen.pages().names());
                if (a[1].equalsIgnoreCase("next")) out.add("off");
                return out;
            }
        }
        if (a.length == 4 && sub.equals("page") && a[1].equalsIgnoreCase("add") && ed != null) return ed.screen.pages().names();
        return List.of();
    }
}
