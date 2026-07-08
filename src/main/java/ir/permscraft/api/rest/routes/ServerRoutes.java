package ir.permscraft.api.rest.routes;

import io.javalin.Javalin;
import ir.permscraft.PermsCraft;
import ir.permscraft.api.rest.ApiException;
import ir.permscraft.api.rest.auth.ApiKeyManager;

import java.util.*;
import java.util.stream.Collectors;

/**
 * REST routes for server info and API key management.
 *
 * GET  /api/v1/server                          — server info (name, version, players, TPS)
 * GET  /api/v1/server/players                  — list online players with their primary group
 * GET  /api/v1/server/players/{uuid}           — online status of a specific player
 *
 * GET  /api/v1/apikeys                         — list all API keys (labels + scopes, no hashes)
 * POST /api/v1/apikeys                         — create API key  { "label": "...", "scopes": [...] }
 * DELETE /api/v1/apikeys/{label}               — revoke API key by label
 *
 * POST /api/v1/server/reload                   — reload plugin config + permissions (equiv. /pc reload)
 * GET  /api/v1/server/tracks                   — list all promotion tracks
 */
public class ServerRoutes {

    private final PermsCraft plugin;

    public ServerRoutes(PermsCraft plugin) {
        this.plugin = plugin;
    }

    public void register(Javalin app) {

        // ── Server info ───────────────────────────────────────────────────────
        app.get("/api/v1/server", ctx -> {
            ApiKeyManager.requireScope(ctx, ApiKeyManager.SCOPE_READ);
            var server = org.bukkit.Bukkit.getServer();
            ctx.json(Map.of(
                    "name",          plugin.getServerName(),
                    "pluginVersion", plugin.getPluginMeta().getVersion(),
                    "minecraftVersion", server.getVersion(),
                    "onlinePlayers", server.getOnlinePlayers().size(),
                    "maxPlayers",    server.getMaxPlayers(),
                    "tps",           getTps(),
                    "uptime",        plugin.getUptimeSeconds(),
                    "storageType",   plugin.getConfig().getString("storage.type", "sqlite"),
                    "redisEnabled",  plugin.getRedisManager().isEnabled()
            ));
        });

        // ── Online players ────────────────────────────────────────────────────
        app.get("/api/v1/server/players", ctx -> {
            ApiKeyManager.requireScope(ctx, ApiKeyManager.SCOPE_READ);
            var players = org.bukkit.Bukkit.getOnlinePlayers().stream()
                    .map(p -> {
                        var user = plugin.getUserManager().getUser(p.getUniqueId());
                        List<String> groups = user != null
                                ? new ArrayList<>(user.getGroups())
                                : List.of("default");
                        return Map.of(
                                "uuid",   p.getUniqueId().toString(),
                                "name",   p.getName(),
                                "groups", groups,
                                "world",  p.getWorld().getName()
                        );
                    })
                    .collect(Collectors.toList());
            ctx.json(Map.of("count", players.size(), "players", players));
        });

        // ── Single player online status ───────────────────────────────────────
        app.get("/api/v1/server/players/{uuid}", ctx -> {
            ApiKeyManager.requireScope(ctx, ApiKeyManager.SCOPE_READ);
            UUID uuid = parseUuid(ctx.pathParam("uuid"));
            var player = org.bukkit.Bukkit.getPlayer(uuid);
            if (player == null) {
                ctx.json(Map.of("uuid", uuid.toString(), "online", false));
            } else {
                var user = plugin.getUserManager().getUser(uuid);
                ctx.json(Map.of(
                        "uuid",   uuid.toString(),
                        "name",   player.getName(),
                        "online", true,
                        "world",  player.getWorld().getName(),
                        "groups", user != null ? new ArrayList<>(user.getGroups()) : List.of("default")
                ));
            }
        });

        // ── List API keys ─────────────────────────────────────────────────────
        app.get("/api/v1/apikeys", ctx -> {
            ApiKeyManager.requireScope(ctx, ApiKeyManager.SCOPE_ADMIN);
            var keys = plugin.getRestApiServer().getKeyManager().listKeys()
                    .stream()
                    .map(k -> Map.of(
                            "label",     k.label(),
                            "scopes",    new ArrayList<>(k.scopes()),
                            "created",   k.created().toString(),
                            "createdBy", k.createdBy()
                    ))
                    .collect(Collectors.toList());
            ctx.json(Map.of("count", keys.size(), "keys", keys));
        });

        // ── Create API key ────────────────────────────────────────────────────
        app.post("/api/v1/apikeys", ctx -> {
            ApiKeyManager.requireScope(ctx, ApiKeyManager.SCOPE_ADMIN);
            Map<?, ?> body = ctx.bodyAsClass(Map.class);
            String label = requireString(body, "label");
            List<String> scopes = body.containsKey("scopes") && body.get("scopes") instanceof List<?>
                    ? ((List<?>) body.get("scopes")).stream()
                            .map(String::valueOf).collect(Collectors.toList())
                    : List.of(ApiKeyManager.SCOPE_READ);

            // Validate scopes
            Set<String> valid = Set.of(
                    ApiKeyManager.SCOPE_READ, ApiKeyManager.SCOPE_WRITE,
                    ApiKeyManager.SCOPE_LOG, ApiKeyManager.SCOPE_SYNC,
                    ApiKeyManager.SCOPE_BACKUP, ApiKeyManager.SCOPE_ADMIN);
            List<String> invalid = scopes.stream()
                    .filter(s -> !valid.contains(s)).collect(Collectors.toList());
            if (!invalid.isEmpty())
                throw ApiException.badRequest("Unknown scope(s): " + invalid +
                        ". Valid: " + valid);

            String actor = Optional.ofNullable(
                    ctx.<ApiKeyManager.KeyEntry>attribute("apiKey"))
                    .map(ApiKeyManager.KeyEntry::label)
                    .orElse("api");

            String plaintext = plugin.getRestApiServer()
                    .getKeyManager().createKey(label, scopes, actor);

            ctx.status(201).json(Map.of(
                    "message", "API key created. Save this token — it will NOT be shown again.",
                    "label",   label,
                    "scopes",  scopes,
                    "token",   plaintext
            ));
        });

        // ── Revoke API key ────────────────────────────────────────────────────
        app.delete("/api/v1/apikeys/{label}", ctx -> {
            ApiKeyManager.requireScope(ctx, ApiKeyManager.SCOPE_ADMIN);
            String label = ctx.pathParam("label");
            boolean removed = plugin.getRestApiServer().getKeyManager().revokeByLabel(label);
            if (!removed) throw ApiException.notFound("No API key with label '" + label + "'.");
            ctx.json(Map.of("message", "API key '" + label + "' revoked."));
        });

        // ── Reload plugin ─────────────────────────────────────────────────────
        app.post("/api/v1/server/reload", ctx -> {
            ApiKeyManager.requireScope(ctx, ApiKeyManager.SCOPE_ADMIN);
            // Run on main thread — config reload touches Bukkit API
            org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
                plugin.reloadConfig();
                plugin.getGroupManager().loadGroups();
                plugin.getContextManager().reload();
                plugin.getPermissionCache().invalidateAll();
                plugin.getLogger().info("[PermsCraft REST] Reload triggered via API.");
            });
            ctx.json(Map.of("message", "Reload scheduled on the main thread."));
        });

        // ── Tracks ────────────────────────────────────────────────────────────
        app.get("/api/v1/server/tracks", ctx -> {
            ApiKeyManager.requireScope(ctx, ApiKeyManager.SCOPE_READ);
            ctx.json(Map.of("tracks", plugin.getStorage().loadAllTracks()));
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static UUID parseUuid(String raw) {
        try { return UUID.fromString(raw); }
        catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Invalid UUID: '" + raw + "'");
        }
    }

    private static String requireString(Map<?, ?> body, String key) {
        Object v = body.get(key);
        if (v == null || v.toString().isBlank())
            throw ApiException.badRequest("Missing required field: '" + key + "'");
        return v.toString().trim();
    }

    /** Read TPS from the Paper API if available, fall back to -1. */
    private static double[] getTps() {
        try {
            double[] tps = org.bukkit.Bukkit.getTPS();
            // Returns [1m, 5m, 15m]
            return new double[]{
                    Math.round(tps[0] * 100.0) / 100.0,
                    Math.round(tps[1] * 100.0) / 100.0,
                    Math.round(tps[2] * 100.0) / 100.0
            };
        } catch (Throwable t) {
            return new double[]{-1, -1, -1};
        }
    }
}
