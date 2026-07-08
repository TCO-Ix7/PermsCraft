package ir.permscraft.api.rest.auth;

import io.javalin.http.Context;
import ir.permscraft.PermsCraft;
import ir.permscraft.api.rest.ApiException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages API keys for the REST API.
 *
 * Keys are stored in plugins/PermsCraft/apikeys.yml as SHA-256 hashes —
 * the plaintext key is shown ONCE at creation time and never persisted.
 *
 * Format in apikeys.yml:
 * ───────────────────────
 * keys:
 *   <hash-hex>:
 *     label: "discord-bot"
 *     scopes: [read, write, log, sync, backup]
 *     created: 2025-01-01T00:00:00Z
 *     created-by: "console"
 *
 * Scopes:
 *   read    — GET endpoints (groups, users, logs, server info)
 *   write   — POST/PUT/DELETE for groups and users
 *   log     — GET /logs
 *   sync    — POST /sync
 *   backup  — POST /backup/export, POST /backup/import
 *   admin   — all of the above
 *
 * Commands:
 *   /pc apikey create <label> [scopes...]
 *   /pc apikey list
 *   /pc apikey revoke <label>
 */
public class ApiKeyManager {

    public static final String SCOPE_READ   = "read";
    public static final String SCOPE_WRITE  = "write";
    public static final String SCOPE_LOG    = "log";
    public static final String SCOPE_SYNC   = "sync";
    public static final String SCOPE_BACKUP = "backup";
    public static final String SCOPE_ADMIN  = "admin";

    private final PermsCraft plugin;
    private final File keyFile;

    /** hash → KeyEntry (hot cache so we don't hit disk on every request) */
    private final ConcurrentHashMap<String, KeyEntry> cache = new ConcurrentHashMap<>();

    public record KeyEntry(String label, Set<String> scopes, Instant created, String createdBy) {}

    public ApiKeyManager(PermsCraft plugin) {
        this.plugin  = plugin;
        this.keyFile = new File(plugin.getDataFolder(), "apikeys.yml");
        reload();
    }

    // ── Authentication ────────────────────────────────────────────────────────

    /**
     * Validate the Bearer token in the Authorization header.
     * Stores the resolved {@link KeyEntry} in the Javalin context attribute
     * "apiKey" so route handlers can check scopes without re-hashing.
     *
     * @throws ApiException 401 if missing or invalid
     */
    public void authenticate(Context ctx) {
        String header = ctx.header("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            throw ApiException.unauthorized("Missing or malformed Authorization header. Use: Bearer <api-key>");
        }
        String token = header.substring(7).trim();
        if (token.isBlank()) {
            throw ApiException.unauthorized("Empty API key.");
        }
        String hash = sha256(token);
        KeyEntry entry = cache.get(hash);
        if (entry == null) {
            throw ApiException.unauthorized("Invalid API key.");
        }
        ctx.attribute("apiKey", entry);
    }

    /**
     * Assert the authenticated key has the given scope.
     * Call this at the top of each route handler that needs a specific scope.
     */
    public static void requireScope(Context ctx, String scope) {
        KeyEntry key = ctx.attribute("apiKey");
        if (key == null) throw ApiException.unauthorized("Not authenticated.");
        if (key.scopes().contains(SCOPE_ADMIN)) return; // admin has all
        if (!key.scopes().contains(scope)) {
            throw ApiException.forbidden("This API key lacks the '" + scope + "' scope.");
        }
    }

    // ── Key management ────────────────────────────────────────────────────────

    /**
     * Create a new API key. Returns the plaintext token — shown ONCE.
     *
     * @param label     human-readable label (e.g. "discord-bot")
     * @param scopes    list of scope strings
     * @param createdBy actor name (player name or "console")
     * @return the plaintext API key (prefix + 32 random bytes as hex)
     */
    public String createKey(String label, List<String> scopes, String createdBy) {
        if (labelExists(label)) {
            throw ApiException.conflict("A key with label '" + label + "' already exists.");
        }

        // Generate a cryptographically secure random key: "pc_" + 32 hex bytes
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        String plaintext = "pc_" + bytesToHex(raw);
        String hash      = sha256(plaintext);

        Set<String> scopeSet = new HashSet<>(scopes);
        KeyEntry entry = new KeyEntry(label, Collections.unmodifiableSet(scopeSet),
                Instant.now(), createdBy);
        cache.put(hash, entry);
        persist();
        return plaintext;
    }

    /** Revoke a key by label. Returns true if a key was removed. */
    public boolean revokeByLabel(String label) {
        boolean removed = cache.entrySet().removeIf(e -> e.getValue().label().equals(label));
        if (removed) persist();
        return removed;
    }

    public Collection<KeyEntry> listKeys() {
        return Collections.unmodifiableCollection(cache.values());
    }

    public boolean labelExists(String label) {
        return cache.values().stream().anyMatch(e -> e.label().equals(label));
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    public void reload() {
        cache.clear();
        if (!keyFile.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(keyFile);
        var section = yml.getConfigurationSection("keys");
        if (section == null) return;
        for (String hash : section.getKeys(false)) {
            String label     = section.getString(hash + ".label", "unknown");
            List<String> sc  = section.getStringList(hash + ".scopes");
            String created   = section.getString(hash + ".created", Instant.now().toString());
            String by        = section.getString(hash + ".created-by", "unknown");
            cache.put(hash, new KeyEntry(label,
                    Collections.unmodifiableSet(new HashSet<>(sc)),
                    Instant.parse(created), by));
        }
        plugin.getLogger().info("[PermsCraft REST] Loaded " + cache.size() + " API key(s).");
    }

    private void persist() {
        YamlConfiguration yml = new YamlConfiguration();
        cache.forEach((hash, entry) -> {
            String base = "keys." + hash;
            yml.set(base + ".label",      entry.label());
            yml.set(base + ".scopes",     new ArrayList<>(entry.scopes()));
            yml.set(base + ".created",    entry.created().toString());
            yml.set(base + ".created-by", entry.createdBy());
        });
        try {
            plugin.getDataFolder().mkdirs();
            yml.save(keyFile);
        } catch (IOException e) {
            plugin.getLogger().warning("[PermsCraft REST] Failed to save apikeys.yml: " + e.getMessage());
        }
    }

    // ── Crypto helpers ────────────────────────────────────────────────────────

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
