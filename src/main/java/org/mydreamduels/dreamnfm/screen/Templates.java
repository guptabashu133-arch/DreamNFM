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
        boolean small = width * 128 < 256;
        int title = small ? 1 : 2;
        return """
                # Pages for this screen. Edit, then /screen reload <name>.
                # Positions/sizes: 20 (pixels), -20 (from right/bottom), 50%% (of screen), center.
                # Element types: rect, text, lines, image, button, head, progress, graph.
                # Text supports &colours, &#RRGGBB, {player} {online} {max} {time} {date} {world} {ping},
                # {countdown:2026-12-31 20:00}, PlaceholderAPI %%placeholders%% and live status rules:
                #   <status:%%server_online%%;0=&cEmpty;1-10=&eQuiet;default=&aBusy>
                # Colours can be live too: color: "<status:{online};0=#EF4444;default=#22C55E>"
                # Button actions: page:<page>  page:back  close  message:..  player:cmd  console:cmd
                #                 sound:<key>  teleport:world,x,y,z  server:<bungee-server>
                start: home
                # idle-page: sleep        # shown to players further away than wake-range
                # wake-range: 10
                click-sound: ui.button.click
                pages:
                  home:
                    background: "#14161F"
                    refresh: 20            # ticks between redraws (for placeholders)
                    elements:
                      - type: rect
                        x: 0
                        y: 0
                        w: 100%%
                        h: %d
                        color: "#1F2433"
                      - type: text
                        x: center
                        y: %d
                        scale: %d
                        text: "&#7AA2FF&lDREAM &f&lNETWORK"
                      - type: text
                        x: center
                        y: %d
                        text: "&7Welcome, &f{player}&7!"
                      - type: text
                        x: center
                        y: %d
                        text: "<status:{online};0=&cNobody online;1-5=&eQuiet right now;default=&aBusy!> &8| &f{online}&7/&f{max}"
                      - type: button
                        x: center
                        y: 50%%
                        w: 60%%
                        h: 26
                        text: "&lPLAY"
                        color: "#22C55E"
                        actions:
                          - "message:&aSend players anywhere - edit this button in screens/<name>.yml"
                      - type: button
                        x: center
                        y: -18
                        w: 60%%
                        h: 16
                        text: "Server info"
                        color: "#3B82F6"
                        actions:
                          - "page:info"
                      - type: progress
                        x: center
                        y: -8
                        w: 80%%
                        h: 5
                        value: "{online}"
                        max: "{max}"
                        color: "<status:{online};0=#EF4444;default=#22C55E>"
                  info:
                    background: "#14161F"
                    refresh: 20
                    elements:
                      - type: rect
                        x: 0
                        y: 0
                        w: 100%%
                        h: %d
                        color: "#1F2433"
                      - type: text
                        x: center
                        y: %d
                        scale: %d
                        text: "&f&lSERVER INFO"
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
                          - "&7Ping: &f{ping}ms"
                          - "&7Time: &f{time}"
                      - type: image
                        file: example.png
                        x: -8
                        y: -8
                        w: 16
                        h: 16
                      - type: graph
                        source: online
                        x: center
                        y: %d
                        w: 90%%
                        h: %d
                      - type: button
                        x: 8
                        y: -8
                        w: 50
                        h: 16
                        text: "< Back"
                        color: "#475569"
                        actions:
                          - "page:back"
                """.formatted(
                small ? 14 : 30, small ? 3 : 8, title, small ? 18 : 36, small ? 28 : 48,
                small ? 14 : 30, small ? 3 : 8, title, small ? 18 : 38, small ? 20 : 42,
                small ? 54 : 78, Math.max(24, height * 128 - (small ? 54 : 78) - 30));
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
