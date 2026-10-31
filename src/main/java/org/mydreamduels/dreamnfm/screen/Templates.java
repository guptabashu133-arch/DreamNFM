package org.mydreamduels.dreamnfm.screen;

import org.mydreamduels.dreamnfm.util.FileUtil;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;

/** Starter page for new screens + a sample image, so a fresh screen already looks good. */
final class Templates {

    private Templates() {
    }

    static String defaultPages(int width, int height) {
        boolean s = width * 128 < 256;
        int title = s ? 1 : 2;
        int graphY = s ? 58 : 82, graphH = Math.max(24, height * 128 - graphY - 30);
        return """
                # Starter page. Edit, then /screen reload <name>.
                # Want something ready-made? /screen designs  ->  /screen design <screen> <design>
                # Positions/sizes: 20 (pixels), -20 (from right/bottom), 50%% (of screen), center.
                # Element types: rect, text, lines, image, button, head, progress, graph.
                # Text: &colours, &#RRGGBB, {player} {online} {max} {time} {date} {world} {ping} {tps} {mspt} {uptime},
                #   {countdown:2026-12-31 20:00}, %%placeholderapi%% and live status rules:
                #   <status:{online};0=&cEmpty;1-10=&eQuiet;default=&aBusy>
                # Button actions: page:<page>  page:back  close  message:..  player:cmd  console:cmd
                #                 sound:<key>  teleport:world,x,y,z  server:<bungee-server>
                # background: none = see-through (the wall shows). Use a colour like "#11131A" for a solid screen.
                start: home
                click-sound: ui.button.click
                pages:
                  home:
                    background: none
                    refresh: 20
                    elements:
                      - type: rect
                        x: center
                        y: 4
                        w: 96%%
                        h: %d
                        color: "#11131A"
                      - type: text
                        x: center
                        y: %d
                        scale: %d
                        text: "&lDREAM &f&lNETWORK"
                        color: "#A78BFA"
                        shadow-color: "#05060A"
                      - type: rect
                        x: center
                        y: %d
                        w: 96%%
                        h: %d
                        color: "#171B24"
                      - type: text
                        x: center
                        y: %d
                        text: "&7Welcome, &f{player}&7!"
                      - type: text
                        x: center
                        y: %d
                        text: "<status:{online};0=&cNobody online;1-5=&eQuiet;default=&aBusy!> &8| &f{online}&7/&f{max}"
                        shadow: false
                      - type: button
                        x: center
                        y: 50%%
                        w: 60%%
                        h: 26
                        text: "&lPLAY"
                        color: "#6D28D9"
                        hover-color: "#7C3AED"
                        actions:
                          - "message:&aEdit this button in screens/<name>.yml"
                      - type: button
                        x: center
                        y: -18
                        w: 60%%
                        h: 16
                        text: "Server info"
                        color: "#171B24"
                        hover-color: "#232A37"
                        actions:
                          - "page:info"
                      - type: progress
                        x: center
                        y: -8
                        w: 80%%
                        h: 5
                        value: "{online}"
                        max: "{max}"
                        color: "#A78BFA"
                        background: "#11131A"
                  info:
                    background: none
                    refresh: 20
                    elements:
                      - type: rect
                        x: center
                        y: 4
                        w: 96%%
                        h: %d
                        color: "#11131A"
                      - type: text
                        x: center
                        y: %d
                        scale: %d
                        text: "&lSERVER INFO"
                        color: "#A78BFA"
                        shadow-color: "#05060A"
                      - type: rect
                        x: center
                        y: %d
                        w: 96%%
                        h: 36
                        color: "#171B24"
                      - type: head
                        player: "{player}"
                        x: 8
                        y: %d
                        size: 32
                      - type: lines
                        x: 46
                        y: %d
                        align: left
                        lines:
                          - "&f{player}"
                          - "&7Ping &f{ping}ms"
                          - "&7TPS &f{tps}"
                      - type: graph
                        source: online
                        x: center
                        y: %d
                        w: 96%%
                        h: %d
                        background: "#171B24"
                        color: "#A78BFA"
                      - type: button
                        x: 6
                        y: -6
                        w: 50
                        h: 16
                        text: "< Back"
                        color: "#171B24"
                        hover-color: "#232A37"
                        actions:
                          - "page:back"
                      - type: image
                        file: example.png
                        x: -6
                        y: -6
                        w: 16
                        h: 16
                """.formatted(
                s ? 14 : 28, s ? 7 : 11, title, s ? 20 : 37, s ? 20 : 26, s ? 22 : 40, s ? 31 : 52,
                s ? 14 : 28, s ? 7 : 11, title, s ? 20 : 36, s ? 22 : 38, s ? 23 : 39,
                graphY, graphH);
    }

    static void writeDefault(File file, int width, int height) {
        file.getParentFile().mkdirs();
        try {
            FileUtil.writeAtomically(file, defaultPages(width, height));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Could not write " + file.getName() + ": " + e.getMessage(), e);
        }
    }

    /** A small gradient badge so the template has an image to show. */
    static void ensureExampleImage(File dir) {
        File f = new File(dir, "example.png");
        if (f.exists()) return;
        try {
            dir.mkdirs();
            BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setPaint(new GradientPaint(0, 0, new Color(0x7A, 0xA2, 0xFF), 64, 64, new Color(0xC0, 0x84, 0xFC)));
            g.fillRoundRect(0, 0, 64, 64, 18, 18);
            g.setColor(Color.WHITE);
            g.setStroke(new BasicStroke(3));
            g.drawRoundRect(3, 3, 57, 57, 14, 14);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 28));
            String t = "DN";
            int tw = g.getFontMetrics().stringWidth(t);
            g.drawString(t, (64 - tw) / 2, 42);
            g.dispose();
            ImageIO.write(img, "png", f);
        } catch (Throwable ignored) {
            // headless font issues etc. - the example image is optional
        }
    }
}
