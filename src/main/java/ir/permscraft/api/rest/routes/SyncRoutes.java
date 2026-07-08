package ir.permscraft.api.rest.routes;

import io.javalin.Javalin;
import ir.permscraft.PermsCraft;
import ir.permscraft.api.rest.ApiException;
import ir.permscraft.api.rest.auth.ApiKeyManager;

import java.util.Map;

/**
 * REST routes for live sync & cache management.
 *
 * POST /api/v1/sync                    — broadcast full network sync via Redis
 * POST /api/v1/sync/user/{uuid}        — sync a single user across the network
 * POST /api/v1/sync/group/{name}       — sync a single group across the network
 *
 * POST /api/v1/cache/flush             — flush the entire in-memory permission cache
 * POST /api/v1/cache/flush/user/{uuid} — flush cache for one player
 * POST /api/v1/cache/flush/group/{name}— flush cache for one group
 *
 * GET  /api/v1/sync/status             — Redis connection status + last sync timestamp
 */
public class SyncRoutes {

    private final PermsCraft plugin;

    public SyncRoutes(PermsCraft plugin) {
        this.plugin = plugin;
    }

    public void register(Javalin app) {

        // ── Full network sync ─────────────────────────────────────────────────
        app.post("/api/v1/sync", ctx -> {
            ApiKeyManager.requireScope(ctx, ApiKeyManager.SCOPE_SYNC);
            if (!plugin.getRedisManager().isEnabled()) {
                throw ApiException.badRequest("Redis is not enabled on this server. " +
                        "Enable redis in config.yml to use network sync.");
            }
            plugin.getRedisManager().publishReload();
            ctx.json(Map.of(
                    "message", "Full sync broadcast sent to all servers.",
                    "server",  plugin.getServerName()
            ));
        });

        // ── Sync single user ──────────────────────────────────────────────────
        app.post("/api/v1/sync/user/{uuid}", ctx -> {
            ApiKeyManager.requireScope(ctx, ApiKeyManager.SCOPE_SYNC);
            java.util.UUID uuid = parseUuid(ctx.pathParam("uuid"));
            if (!plugin.getRedisManager().isEnabled())
                throw ApiException.badRequest("Redis is not enabled.");
            plugin.getRedisManager().publishUserRefresh(uuid);
            ctx.json(Map.of(
                    "message", "User sync published.",
                    "uuid",    uuid.toString()
            ));
        });

        // ── Sync single group ─────────────────────────────────────────────────
        app.post("/api/v1/sync/group/{name}", ctx -> {
            ApiKeyManager.requireScope(ctx, ApiKeyManager.SCOPE_SYNC);
            String name = ctx.pathParam("name").toLowerCase();
            if (plugin.getGroupManager().getGroup(name) == null)
                throw ApiException.notFound("Group '" + name + "' not found.");
            if (!plugin.getRedisManager().isEnabled())
                throw ApiException.badRequest("Redis is not enabled.");
            plugin.getRedisManager().publishGroupRefresh(name);
            ctx.json(Map.of(
                    "message", "Group sync published.",
                    "group",   name
            ));
        });

        // ── Flush all cache ───────────────────────────────────────────────────
        app.post("/api/v1/cache/flush", ctx -> {
            ApiKeyManager.requireScope(ctx, ApiKeyManager.SCOPE_SYNC);
            plugin.getPermissionCache().invalidateAll();
            ctx.json(Map.of("message", "Permission cache fully flushed."));
        });

        // ── Flush single user cache ───────────────────────────────────────────
        app.post("/api/v1/cache/flush/user/{uuid}", ctx -> {
            ApiKeyManager.requireScope(ctx, ApiKeyManager.SCOPE_SYNC);
            java.util.UUID uuid = parseUuid(ctx.pathParam("uuid"));
            plugin.getPermissionCache().invalidateUser(uuid);
            ctx.json(Map.of("message", "Cache flushed for user.", "uuid", uuid.toString()));
        });

        // ── Flush single group cache ──────────────────────────────────────────
        app.post("/api/v1/cache/flush/group/{name}", ctx -> {
            ApiKeyManager.requireScope(ctx, ApiKeyManager.SCOPE_SYNC);
            String name = ctx.pathParam("name").toLowerCase();
            plugin.getPermissionCache().invalidateGroupAndChildren(name, () -> plugin.getGroupManager().getAllGroups());
            ctx.json(Map.of("message", "Cache flushed for group.", "group", name));
        });

        // ── Redis status ──────────────────────────────────────────────────────
        app.get("/api/v1/sync/status", ctx -> {
            ApiKeyManager.requireScope(ctx, ApiKeyManager.SCOPE_READ);
            // isConnected() not in this version — isEnabled() is sufficient
            boolean redisOk = plugin.getRedisManager().isEnabled();
            ctx.json(Map.of(
                    "redis",          plugin.getRedisManager().isEnabled() ? "enabled" : "disabled",
                    "redisConnected", redisOk,
                    "serverName",     plugin.getServerName(),
                    "cacheSize",      plugin.getPermissionCache().getStats().userEntries(),
                    "uptime",         plugin.getUptimeSeconds()
            ));
        });
    }

    private static java.util.UUID parseUuid(String raw) {
        try { return java.util.UUID.fromString(raw); }
        catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Invalid UUID: '" + raw + "'");
        }
    }
}
