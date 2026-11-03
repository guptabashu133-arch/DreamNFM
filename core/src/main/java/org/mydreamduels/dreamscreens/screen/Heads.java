package org.mydreamduels.dreamscreens.screen;

import org.bukkit.Bukkit;
import org.mydreamduels.dreamscreens.DreamScreens;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Player faces (8x8 + hat layer) for head elements; downloaded off-thread and cached. */
final class Heads {

    private static final long KEEP_MS = 30 * 60_000L, RETRY_MS = 5 * 60_000L;

    private record Entry(ImageStore.Img img, long at) {
    }

    private final DreamScreens plugin;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();
    private final java.util.Set<String> loading = ConcurrentHashMap.newKeySet();

    Heads(DreamScreens plugin) {
        this.plugin = plugin;
    }

    ImageStore.Img get(String name) {
        if (name == null || !name.matches("[A-Za-z0-9_]{1,16}")) return null;
        String key = name.toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();
        Entry e = cache.get(key);
        boolean ok = e != null && e.img.pixels() != null;
        boolean stale = e == null || now - e.at >= (ok ? KEEP_MS : RETRY_MS);
        if (stale && loading.add(key)) fetch(name, key);
        return ok ? e.img : null;
    }

    private void fetch(String name, String key) {
        plugin.scheduler().runAsync(() -> {
            ImageStore.Img img = new ImageStore.Img(0, 0, null);
            try {
                org.bukkit.profile.PlayerProfile profile = Bukkit.createPlayerProfile(name)
                        .update().get(15, java.util.concurrent.TimeUnit.SECONDS);
                java.net.URL skinUrl = profile == null ? null : profile.getTextures().getSkin();
                if (skinUrl != null && skinUrl.getHost().endsWith("textures.minecraft.net")) {
                    BufferedImage skin = ImageIO.read(skinUrl);
                    if (skin != null) img = face(skin);
                }
            } catch (Throwable t) {
                plugin.getLogger().fine("Head for " + name + " failed: " + t);
            } finally {
                Entry old = cache.get(key);
                // keep a previously good face if the refresh failed
                if (img.pixels() != null || old == null || old.img.pixels() == null) cache.put(key, new Entry(img, System.currentTimeMillis()));
                else cache.put(key, new Entry(old.img, System.currentTimeMillis() - KEEP_MS + RETRY_MS));
                loading.remove(key);
            }
            if (img.pixels() != null) plugin.screens().redrawAll();
        });
    }

    static ImageStore.Img face(BufferedImage skin) {
        int unit = Math.max(1, skin.getWidth() / 64);
        BufferedImage out = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                int base = skin.getRGB((8 + x) * unit, (8 + y) * unit) | 0xFF000000;
                int hat = skin.getHeight() >= 16 * unit ? skin.getRGB((40 + x) * unit, (8 + y) * unit) : 0;
                out.setRGB(x, y, ((hat >>> 24) & 0xFF) >= 128 ? hat | 0xFF000000 : base);
            }
        }
        return new ImageStore.Img(8, 8, ImageStore.convert(out, false));
    }
}
