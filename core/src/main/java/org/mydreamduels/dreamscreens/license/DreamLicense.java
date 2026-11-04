package org.mydreamduels.dreamscreens.license;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.SignatureException;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dreamscube license check - the verify API from the Dreamscube SETUP.md
 * ("Plugin side: verify API").
 *
 * Self-contained on purpose - JDK + plain Bukkit API only, Java 17+, works on
 * Spigot, Paper and Folia - so this one file drops into every Dream plugin.
 * To add it to another plugin:
 *   1. Copy this file, then change the package line and PRODUCT_ID below.
 *   2. onEnable(), once config.yml is on disk:  license = DreamLicense.start(this);
 *   3. onDisable():                             if (license != null) license.stop();
 *   4. Bundled config.yml:                      # Manage your license at https://dreamscube.fun
 *                                               license-key: ""
 *      (optional - if it's missing, the line gets appended on first start)
 *
 * How it behaves:
 *  - Key: config.yml "license-key". If that's empty, the key BuiltByBit
 *    injected into the jar at download time is used and written into
 *    config.yml so the owner can see and change it.
 *  - The check runs off the main thread right away and then every
 *    heartbeat_minutes the server asks for. An answer only counts if its
 *    Ed25519 signature checks out against PUBLIC_KEY and it echoes back our
 *    nonce, key and product.
 *  - The last good answer is cached in the data folder. While the license
 *    server can't be reached, the plugin keeps running until that answer's
 *    valid_until, so a Cloudflare hiccup never takes a customer's server down.
 *  - The plugin only disables itself on a signed "not valid" answer, or once
 *    that offline grace period has run out.
 */
public final class DreamLicense implements Listener {

    /** This plugin's product ID in the admin panel. The one line to change per plugin. */
    private static final String PRODUCT_ID = "dreamscreens";

    // ---- Same for every plugin ----

    private static final String API_URL = "https://dreamscube.dreamscubemc.workers.dev/api/v1/verify";
    /** Ed25519 public key, base64 X.509. Public on purpose: it can only check signatures, never make them. */
    private static final String PUBLIC_KEY = "MCowBQYDK2VwAyEAbueWW1srlyWQjyZwtZleX8o3HMl+MMMVvcpPi8JQUOk=";
    private static final String MANAGE_SITE = "https://dreamscube.fun";

    /**
     * BuiltByBit swaps these for the buyer's license key and user ID when the
     * jar is downloaded. Left as-is in a jar that didn't come from BuiltByBit
     * (e.g. your own build) - see injected().
     */
    private static final String INJECTED_KEY = "%%__DREAMSCUBE_KEY__%%";
    private static final String INJECTED_USER = "%%__USER__%%";

    private static final String CONFIG_KEY = "license-key";
    private static final String CACHE_FILE = "license-cache.dat";
    private static final String SERVER_ID_FILE = "server-id.txt";

    private static final long DEFAULT_HEARTBEAT_MINUTES = 30;
    /** How often to retry while running on the cached license. */
    private static final long OFFLINE_RETRY_MS = TimeUnit.MINUTES.toMillis(5);
    /** With no cached license to fall back on: retry after these delays, then give up. */
    private static final long[] NO_CACHE_RETRY_SECONDS = {30, 60, 120};

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final JavaPlugin plugin;
    private final Logger log;
    private final File dataFolder;
    private final String pluginVersion;

    // Set once in begin() on the main thread, read by the check thread afterwards.
    private String key;
    private String serverId;
    private String bbbUser;
    private String mcVersion;
    private int port;
    private ScheduledExecutorService executor;

    // Only touched by the check thread.
    private boolean cacheLoaded;
    /** valid_until of the newest good answer (live or cached), or 0 if there's none. */
    private long graceUntil;
    private String cachedLicensee = "";
    private boolean licensedLogged;
    private boolean offline;
    private int failedAttempts;

    private volatile boolean stopped;
    /** latest_version from the server when it's newer than this jar, else null. */
    private volatile String newerVersion;

    @SuppressWarnings("deprecation") // getDescription(): Paper prefers getPluginMeta(), which plain Spigot doesn't have
    private DreamLicense(JavaPlugin plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
        this.dataFolder = plugin.getDataFolder();
        this.pluginVersion = plugin.getDescription().getVersion();
    }

    /** Starts checking in the background. Call from onEnable, once config.yml has been saved. */
    public static DreamLicense start(JavaPlugin plugin) {
        DreamLicense license = new DreamLicense(plugin);
        license.begin();
        return license;
    }

    /** Stops the background checks. Safe to call more than once. */
    public void stop() {
        stopped = true;
        ScheduledExecutorService running = executor;
        if (running != null) {
            running.shutdownNow();
        }
    }

    private void begin() {
        dataFolder.mkdirs();
        key = resolveKey();
        if (key == null) {
            fail("NO_KEY", "No license key found.");
            return;
        }
        serverId = loadServerId();
        String user = injected(INJECTED_USER);
        bbbUser = user == null ? "" : user;
        mcVersion = Bukkit.getBukkitVersion().split("-")[0];
        port = Bukkit.getPort();

        Bukkit.getPluginManager().registerEvents(this, plugin);
        executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, plugin.getName() + " license check");
            thread.setDaemon(true);
            return thread;
        });
        executor.execute(this::runCheck);
    }

    // ------------------------------------------------------------------
    // The check itself (always on the check thread)
    // ------------------------------------------------------------------

    private void runCheck() {
        try {
            check();
        } catch (RuntimeException e) {
            // A bug here must not silently end the heartbeat - count it as a failed attempt.
            log.log(Level.WARNING, "License check failed unexpectedly", e);
            unreachable(describe(e));
        }
    }

    private void check() {
        if (stopped) return;
        if (!plugin.isEnabled()) {
            stop();
            return;
        }
        if (!cacheLoaded) {
            cacheLoaded = true;
            loadCache();
        }

        String nonce = HexFormat.of().formatHex(randomBytes(16));
        String body;
        Map<String, Object> answer;
        try {
            body = post(nonce);
            answer = openEnvelope(body);
            if (!nonce.equals(str(answer, "nonce"))) {
                throw new BadAnswer("the answer wasn't for this request (nonce mismatch)");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // stop() was called mid-request
            return;
        } catch (Exception e) {
            if (!stopped) unreachable(describe(e));
            return;
        }
        if (stopped) return;

        // From here on the answer is genuine and fresh, so a "no" is final.
        boolean killed = bool(answer, "kill");
        if (killed || !bool(answer, "ok")) {
            deleteCache();
            // The server sets kill on REVOKED/BLACKLISTED answers - show that code, not a generic one.
            String code = str(answer, "code");
            String message = str(answer, "message");
            if (code.isEmpty() || code.equals("VALID")) {
                code = killed ? "KILLED" : "DENIED";
                if (killed) message = "This copy has been remotely disabled.";
            }
            fail(code, message);
            return;
        }
        if (!matchesThisLicense(answer)) {
            fail("MISMATCH", "The license server answered for a different key or product.");
            return;
        }

        saveCache(body);
        graceUntil = num(answer, "valid_until");
        failedAttempts = 0;
        if (!licensedLogged || offline) {
            status("§a", "License ACTIVE §7- licensed to §f" + licensee(str(answer, "licensee")) + " §8| §7" + MANAGE_SITE);
        }
        licensedLogged = true;
        offline = false;
        noteLatestVersion(str(answer, "latest_version"));

        long heartbeat = num(answer, "heartbeat_minutes");
        heartbeat = heartbeat <= 0 ? DEFAULT_HEARTBEAT_MINUTES : Math.min(heartbeat, TimeUnit.DAYS.toMinutes(1));
        schedule(TimeUnit.MINUTES.toMillis(heartbeat));
    }

    /** No usable answer this time (network down, server error, bad signature...). */
    private void unreachable(String reason) {
        if (stopped) return;
        failedAttempts++;
        long now = System.currentTimeMillis();

        if (graceUntil > now) {
            if (!offline) {
                offline = true;
                status("§6", "License OFFLINE §7- server unreachable (" + reason + "), using the saved license until §f"
                        + TIME_FORMAT.format(Instant.ofEpochMilli(graceUntil)));
                if (!licensedLogged) {
                    status("§6", "Licensed to §f" + licensee(cachedLicensee) + " §8| §7" + MANAGE_SITE);
                    licensedLogged = true;
                }
            }
            // Retry regularly, and make sure there's a check right when the grace period ends.
            schedule(Math.min(OFFLINE_RETRY_MS, graceUntil - now + 1000));
            return;
        }

        if (graceUntil == 0 && failedAttempts <= NO_CACHE_RETRY_SECONDS.length) {
            long seconds = NO_CACHE_RETRY_SECONDS[failedAttempts - 1];
            status("§e", "License server unreachable §7(" + reason + "), retrying in " + seconds + "s");
            schedule(TimeUnit.SECONDS.toMillis(seconds));
            return;
        }

        fail("OFFLINE", (graceUntil == 0
                ? "Could not reach the license server, and there's no saved license to fall back on."
                : "Could not reach the license server, and the offline grace period has run out.")
                + " Last error: " + reason);
    }

    /** A coloured status line in the console: green active, gold offline, yellow retrying, red invalid. */
    private void status(String color, String text) {
        try {
            Bukkit.getConsoleSender().sendMessage("§8[§b" + plugin.getName() + "§8] " + color + text);
        } catch (RuntimeException e) {
            log.info(text.replaceAll("§.", ""));
        }
    }

    /** Logs why, then disables the plugin on the main thread. Stops all further checks. */
    private void fail(String code, String message) {
        if (stopped) return;
        String name = plugin.getName();
        status("§c", "License INVALID §7(" + code + ") - the plugin is being disabled. Details below.");
        log.severe("============================================================");
        log.severe(" " + name + " license check failed: " + code);
        if (message != null && !message.isEmpty()) log.severe(" " + message);
        String hint = hint(code, name);
        if (hint != null) log.severe(" " + hint);
        if (key != null) log.severe(" License key: " + mask(key));
        log.severe(" Manage your license at " + MANAGE_SITE);
        log.severe(" " + name + " has been disabled.");
        log.severe("============================================================");

        runSync(() -> {
            if (plugin.isEnabled()) Bukkit.getPluginManager().disablePlugin(plugin);
        });
        stop();
    }

    private static String hint(String code, String pluginName) {
        return switch (code) {
            case "NO_KEY" -> "Put your key in plugins/" + pluginName + "/config.yml (license-key: \"...\") and restart.";
            case "INVALID_KEY" -> "Check license-key in plugins/" + pluginName + "/config.yml.";
            case "WRONG_PRODUCT" -> "That key is for a different plugin - use your " + pluginName + " key.";
            case "IP_LIMIT" -> "All IP slots on this key are in use. Reset them from your dashboard.";
            case "OFFLINE" -> "Make sure this server can make outbound HTTPS connections to "
                    + URI.create(API_URL).getHost() + ".";
            default -> null;
        };
    }

    private void schedule(long delayMs) {
        if (stopped) return;
        try {
            executor.schedule(this::runCheck, Math.max(1000, delayMs), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            // stop() raced us - nothing left to do
        }
    }

    /** Runs a task on the main thread (the global region thread on Folia). */
    private void runSync(Runnable task) {
        if (!plugin.isEnabled()) return;
        try {
            // Folia has no BukkitScheduler; Paper and Folia both have the global region
            // scheduler. Reflection keeps this compiling against plain spigot-api too.
            Class<?> globalScheduler = Class.forName("io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler");
            Object scheduler = Bukkit.class.getMethod("getGlobalRegionScheduler").invoke(null);
            globalScheduler.getMethod("execute", Plugin.class, Runnable.class).invoke(scheduler, plugin, task);
            return;
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            // Plain Spigot - fall through to the classic scheduler.
        } catch (ReflectiveOperationException | RuntimeException e) {
            // The global scheduler exists but refused (odd fork / test server): try the classic one too.
            log.log(Level.FINE, "Global region scheduler unavailable for the license task", e);
        }
        try {
            Bukkit.getScheduler().runTask(plugin, task);
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "Could not schedule the license task", e);
        }
    }

    // ------------------------------------------------------------------
    // Talking to the license server
    // ------------------------------------------------------------------

    private String post(String nonce) throws IOException, InterruptedException {
        String request = "{" + json("key", key) + "," + json("product", PRODUCT_ID) + "," + json("nonce", nonce) + ","
                + json("version", pluginVersion) + "," + json("mc_version", mcVersion) + ",\"port\":" + port + ","
                + json("server_id", serverId) + "," + json("bbb_user", bbbUser) + "}";
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        try {
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(API_URL))
                            .timeout(Duration.ofSeconds(20))
                            .header("Content-Type", "application/json")
                            .header("User-Agent", plugin.getName() + "/" + pluginVersion)
                            .POST(HttpRequest.BodyPublishers.ofString(request, StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new BadAnswer("HTTP " + response.statusCode());
            }
            return response.body();
        } finally {
            // HttpClient is AutoCloseable from Java 21 on; closing frees its worker thread right away.
            // (Cast through Object so this still compiles with -source 17.)
            Object closeable = client;
            if (closeable instanceof AutoCloseable) {
                try {
                    ((AutoCloseable) closeable).close();
                } catch (Exception ignored) {
                    // nothing useful to do
                }
            }
        }
    }

    /** Parses {"payload","sig"}, checks the signature and returns the decoded payload. */
    private static Map<String, Object> openEnvelope(String body) throws IOException, GeneralSecurityException {
        Map<String, Object> envelope = Json.parseObject(body);
        String payload = str(envelope, "payload");
        String sig = str(envelope, "sig");
        if (payload.isEmpty() || sig.isEmpty()) {
            throw new BadAnswer("unexpected answer from the license server");
        }
        byte[] payloadBytes = base64(payload);
        if (!signedByDreamscube(payloadBytes, payload, base64(sig))) {
            throw new BadAnswer("signature check failed (wrong PUBLIC_KEY, or the answer was tampered with)");
        }
        return Json.parseObject(new String(payloadBytes, StandardCharsets.UTF_8));
    }

    private static boolean signedByDreamscube(byte[] payloadBytes, String payloadBase64, byte[] sig)
            throws GeneralSecurityException {
        PublicKey publicKey = KeyFactory.getInstance("Ed25519")
                .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(PUBLIC_KEY)));
        // SETUP.md: the signature covers the payload's raw (decoded) bytes. A signature over the
        // base64 text is accepted too, so this works whichever one the worker signs. Neither can be
        // passed off as the other: decoded JSON starts with '{', which never appears in base64.
        return verify(publicKey, payloadBytes, sig)
                || verify(publicKey, payloadBase64.getBytes(StandardCharsets.US_ASCII), sig);
    }

    private static boolean verify(PublicKey publicKey, byte[] data, byte[] sig) throws GeneralSecurityException {
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(publicKey);
        verifier.update(data);
        try {
            return verifier.verify(sig);
        } catch (SignatureException e) {
            return false; // malformed signature
        }
    }

    private boolean matchesThisLicense(Map<String, Object> answer) {
        return key.equalsIgnoreCase(str(answer, "key")) && PRODUCT_ID.equals(str(answer, "product"));
    }

    private void noteLatestVersion(String latest) {
        if (latest.isEmpty() || !isNewer(latest, pluginVersion)) {
            newerVersion = null;
            return;
        }
        if (latest.equals(newerVersion)) return;
        newerVersion = latest;
        status("§b", "Update available: §fv" + latest + " §7(you have v" + pluginVersion + ") - download it from BuiltByBit");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        String latest = newerVersion;
        Player player = event.getPlayer();
        if (latest != null && player.isOp()) {
            player.sendMessage("§6[" + plugin.getName() + "] §eUpdate available: §fv" + latest
                    + " §7(you have v" + pluginVersion + ")§e. Download it from BuiltByBit.");
        }
    }

    // ------------------------------------------------------------------
    // Files in the data folder
    // ------------------------------------------------------------------

    private String resolveKey() {
        File configFile = new File(dataFolder, "config.yml");
        if (!configFile.exists() && plugin.getResource("config.yml") != null) {
            plugin.saveResource("config.yml", false);
        }
        String configured = configFile.isFile()
                ? YamlConfiguration.loadConfiguration(configFile).getString(CONFIG_KEY, "")
                : "";
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        String injected = injected(INJECTED_KEY);
        writeKeyToConfig(configFile, injected == null ? "" : injected);
        return injected;
    }

    /**
     * Fills in config.yml's license-key by editing the text directly, not via
     * YamlConfiguration#save, so the owner's comments and layout stay intact.
     * Never overwrites a key that's already there.
     */
    private void writeKeyToConfig(File configFile, String value) {
        try {
            String text = configFile.isFile() ? Files.readString(configFile.toPath()) : "";
            String newline = text.contains("\r\n") ? "\r\n" : "\n";
            String line = CONFIG_KEY + ": \"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
            Matcher emptyLine = Pattern.compile("(?m)^" + CONFIG_KEY + ":[ \\t]*(?:\"\"|'')?[ \\t]*(?:#.*)?$").matcher(text);
            String updated;
            if (emptyLine.find()) {
                if (value.isEmpty()) return;
                updated = text.substring(0, emptyLine.start()) + line + text.substring(emptyLine.end());
            } else if (Pattern.compile("(?m)^" + CONFIG_KEY + ":").matcher(text).find()) {
                return; // has a value we couldn't read (broken YAML?) - leave it alone
            } else {
                String separator = text.isEmpty() || text.endsWith("\n") ? "" : newline;
                updated = text + separator + newline + "# Manage your license at " + MANAGE_SITE + newline + line + newline;
            }
            Files.writeString(configFile.toPath(), updated);
        } catch (IOException e) {
            log.warning("Could not write the license key to config.yml: " + e.getMessage());
        }
    }

    private String loadServerId() {
        Path path = new File(dataFolder, SERVER_ID_FILE).toPath();
        try {
            if (Files.isRegularFile(path)) {
                return UUID.fromString(Files.readString(path).trim()).toString();
            }
        } catch (IOException | IllegalArgumentException e) {
            // unreadable - make a new one below
        }
        String id = UUID.randomUUID().toString();
        try {
            Files.writeString(path, id);
        } catch (IOException e) {
            log.warning("Could not save " + SERVER_ID_FILE + ": " + e.getMessage());
        }
        return id;
    }

    /** Picks up the last good answer, if it's still genuine, for this key, and not expired. */
    private void loadCache() {
        Path path = new File(dataFolder, CACHE_FILE).toPath();
        if (!Files.isRegularFile(path)) return;
        try {
            Map<String, Object> answer = openEnvelope(Files.readString(path));
            long validUntil = num(answer, "valid_until");
            if (bool(answer, "ok") && !bool(answer, "kill") && matchesThisLicense(answer)
                    && validUntil > System.currentTimeMillis()) {
                graceUntil = validUntil;
                cachedLicensee = str(answer, "licensee");
            }
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            // unreadable or edited - just don't use it
        }
    }

    private void saveCache(String body) {
        Path target = new File(dataFolder, CACHE_FILE).toPath();
        Path temp = target.resolveSibling(CACHE_FILE + ".tmp");
        try {
            Files.writeString(temp, body);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.warning("Could not save " + CACHE_FILE + ": " + e.getMessage());
        }
    }

    private void deleteCache() {
        try {
            Files.deleteIfExists(new File(dataFolder, CACHE_FILE).toPath());
        } catch (IOException ignored) {
            // a stale cache only ever extends grace for its own valid_until
        }
    }

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------

    /** The value BuiltByBit put in place of a placeholder, or null if it's still the placeholder. */
    private static String injected(String value) {
        // Compare against a prefix, not the full placeholder: BuiltByBit would replace that copy too.
        String trimmed = value.trim();
        return trimmed.isEmpty() || trimmed.startsWith("%%__") ? null : trimmed;
    }

    static boolean isNewer(String latest, String current) {
        int[] a = versionParts(latest);
        int[] b = versionParts(current);
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    /** "v1.5.0-SNAPSHOT" -> [1, 5, 0]: leading digits of each dot-separated part. */
    private static int[] versionParts(String version) {
        String[] parts = version.trim().replaceFirst("^[vV]", "").split("\\.");
        int[] numbers = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String digits = parts[i].replaceFirst("\\D.*", "");
            numbers[i] = digits.isEmpty() ? 0 : Integer.parseInt(digits.substring(0, Math.min(digits.length(), 9)));
        }
        return numbers;
    }

    private static String licensee(String name) {
        return name.isEmpty() ? "(unknown)" : name;
    }

    private static String mask(String value) {
        return value.length() <= 8 ? "****" : value.substring(0, 3) + "..." + value.substring(value.length() - 4);
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        if (e instanceof BadAnswer) return message;
        return message == null || message.isBlank() ? e.getClass().getSimpleName()
                : e.getClass().getSimpleName() + ": " + message;
    }

    private static byte[] randomBytes(int count) {
        byte[] bytes = new byte[count];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    private static byte[] base64(String value) throws BadAnswer {
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            try {
                return Base64.getUrlDecoder().decode(value);
            } catch (IllegalArgumentException e2) {
                throw new BadAnswer("bad base64 in the license server's answer");
            }
        }
    }

    private static String str(Map<String, Object> map, String name) {
        return map.get(name) instanceof String s ? s : "";
    }

    private static long num(Map<String, Object> map, String name) {
        return map.get(name) instanceof Number n ? n.longValue() : 0L;
    }

    private static boolean bool(Map<String, Object> map, String name) {
        return Boolean.TRUE.equals(map.get(name));
    }

    private static String json(String name, String value) {
        StringBuilder out = new StringBuilder().append('"').append(name).append("\":\"");
        for (char c : value.toCharArray()) {
            if (c == '"' || c == '\\') out.append('\\').append(c);
            else if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
            else out.append(c);
        }
        return out.append('"').toString();
    }

    /** A license-server answer we can't use; the message is shown to the owner as-is. */
    private static final class BadAnswer extends IOException {
        BadAnswer(String message) {
            super(message);
        }
    }

    /** Just enough of a JSON reader for the license API, so no library is needed. */
    private static final class Json {
        private final String text;
        private int pos;

        private Json(String text) {
            this.text = text;
        }

        @SuppressWarnings("unchecked")
        static Map<String, Object> parseObject(String text) throws BadAnswer {
            Json json = new Json(text);
            Object value = json.value(0);
            json.skipSpace();
            if (!(value instanceof Map) || json.pos != text.length()) {
                throw new BadAnswer("unexpected answer from the license server (not a JSON object)");
            }
            return (Map<String, Object>) value;
        }

        private Object value(int depth) throws BadAnswer {
            if (depth > 16) throw error();
            skipSpace();
            char c = peek();
            if (c == '{') return object(depth);
            if (c == '[') return array(depth);
            if (c == '"') return string();
            for (String word : new String[] {"true", "false", "null"}) {
                if (text.startsWith(word, pos)) {
                    pos += word.length();
                    return word.equals("null") ? null : Boolean.valueOf(word);
                }
            }
            return number();
        }

        private Map<String, Object> object(int depth) throws BadAnswer {
            Map<String, Object> map = new HashMap<>();
            pos++;
            skipSpace();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipSpace();
                if (peek() != '"') throw error();
                String name = string();
                skipSpace();
                expect(':');
                map.put(name, value(depth + 1));
                skipSpace();
                if (peek() != ',') break;
                pos++;
            }
            expect('}');
            return map;
        }

        private List<Object> array(int depth) throws BadAnswer {
            List<Object> list = new ArrayList<>();
            pos++;
            skipSpace();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(value(depth + 1));
                skipSpace();
                if (peek() != ',') break;
                pos++;
            }
            expect(']');
            return list;
        }

        private String string() throws BadAnswer {
            StringBuilder out = new StringBuilder();
            pos++;
            while (pos < text.length()) {
                char c = text.charAt(pos++);
                if (c == '"') return out.toString();
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                if (pos >= text.length()) break;
                char escaped = text.charAt(pos++);
                switch (escaped) {
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        if (pos + 4 > text.length()) throw error();
                        try {
                            out.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                        } catch (NumberFormatException e) {
                            throw error();
                        }
                        pos += 4;
                    }
                    default -> out.append(escaped); // \" \\ \/
                }
            }
            throw error();
        }

        private Number number() throws BadAnswer {
            int start = pos;
            while (pos < text.length() && "+-0123456789.eE".indexOf(text.charAt(pos)) >= 0) pos++;
            try {
                return new BigDecimal(text.substring(start, pos));
            } catch (NumberFormatException e) {
                throw error();
            }
        }

        private char peek() {
            return pos < text.length() ? text.charAt(pos) : '\0';
        }

        private void skipSpace() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) pos++;
        }

        private void expect(char c) throws BadAnswer {
            if (peek() != c) throw error();
            pos++;
        }

        private BadAnswer error() {
            return new BadAnswer("unexpected answer from the license server (malformed JSON at " + pos + ")");
        }
    }
}
