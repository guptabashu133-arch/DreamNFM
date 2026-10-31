package org.mydreamduels.dreamnfm.screen;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import org.bukkit.Bukkit;
import org.mydreamduels.dreamnfm.DreamNFM;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Player faces (8x8 + hat layer) for head elements; downloaded off-thread and cached. */
final class Heads {

    private static final Pattern URL = Pattern.compile("\"url\"\\s*:\\s*\"(https?://[^\"]+)\"");
    private static final long KEEP_MS = 30 * 60_000L, RETRY_MS = 5 * 60_000L;

    private record Entry(ImageStore.Img img, long at) {
    }

    private final DreamNFM plugin;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();
    private final java.util.Set<String> loading = ConcurrentHashMap.newKeySet();

    Heads(DreamNFM plugin) {
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
                PlayerProfile profile = Bukkit.createProfile(name);
                if (profile.complete(true)) {
                    for (ProfileProperty p : profile.getProperties()) {
                        if (!p.getName().equals("textures")) continue;
                        String json = new String(Base64.getDecoder().decode(p.getValue()), StandardCharsets.UTF_8);
                        Matcher m = URL.matcher(json);
                        if (!m.find()) break;
                        String url = m.group(1);
                        if (!url.startsWith("http://textures.minecraft.net/") && !url.startsWith("https://textures.minecraft.net/")) break;
                        BufferedImage skin = ImageIO.read(URI.create(url).toURL());
                        if (skin != null) img = face(skin);
                        break;
                    }
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
