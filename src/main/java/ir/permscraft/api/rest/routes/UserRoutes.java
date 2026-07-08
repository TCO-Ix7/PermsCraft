package ir.permscraft.api.rest.routes;

import io.javalin.Javalin;
import ir.permscraft.PermsCraft;
import ir.permscraft.api.rest.ApiException;
import ir.permscraft.api.rest.auth.ApiKeyManager;
import ir.permscraft.models.User;

import java.util.*;
import java.util.stream.Collectors;

/**
 * REST routes for User management.
 *
 * GET    /api/v1/users/{uuid}                        — get user info
 * GET    /api/v1/users/name/{name}                   — lookup by username
 * GET    /api/v1/users/{uuid}/permissions/check      — check permission  ?node=essentials.fly
 * GET    /api/v1/users/{uuid}/permissions/resolved   — full resolved permission map
 *
 * POST   /api/v1/users/{uuid}/permissions            — add permission    { "permission": "..." }
 * DELETE /api/v1/users/{uuid}/permissions/{perm}     — remove permission
 *
 * POST   /api/v1/users/{uuid}/groups                 — add to group      { "group": "..." }
 * DELETE /api/v1/users/{uuid}/groups/{group}         — remove from group
 *
 * PUT    /api/v1/users/{uuid}/prefix                 — set prefix        { "prefix": "..." }
 * PUT    /api/v1/users/{uuid}/suffix                 — set suffix        { "suffix": "..." }
 * PUT    /api/v1/users/{uuid}/meta                   — set meta          { "key": "...", "value": "..." }
 * DELETE /api/v1/users/{uuid}/meta/{key}             — delete meta key
 *
 * POST   /api/v1/users/{uuid}/timed-permissions      — add timed perm    { "permission": "...", "duration": "1d" }
 * GET    /api/v1/users/{uuid}/timed-permissions      — list timed perms
 * DELETE /api/v1/users/{uuid}/timed-permissions/{perm} — revoke timed perm
 */
public class UserRoutes {

    private final PermsCraft plugin;

    public UserRoutes(PermsCraft plugin) {
        this.plugin = plugin;
    }

    public void register(Javalin app) {

        // ── Get user by UUID ──────────────────────────────────────────────────
        app.get("/api/v1/users/{uuid}", ctx -> {
            ApiKeyManager.requireScope(ctx, ApiKeyManager.SCOPE_READ);
            UUID uuid = parseUuid(ctx.pathParam("uuid"));
            User user = loadUser(uuid);
            ctx.json(toDetailMap(user));
        });

        // ── Lookup user by name ───────────────────────────────────────────────
        app.get("/api/v1/users/name/{name}", ctx -> {
            ApiKeyManager.requireScope(ctx, ApiKeyManager.SCOPE_READ);
            String name = ctx.pathParam("name");
            UUID uuid = plugin.getStorage().getUuidByName(name);
            if (uuid == null) throw ApiException.notFound("Player '" + name + "' not found in storage.");
            User user = loadUser(uuid);
            ctx.json(toDetailMap(user));
        });

        // ── Check single permission ───────────────────────────────────────────
        app.get("/api/v1/users/{uuid}/permissions/check", ctx -> {
            ApiKeyManager.requireScope(ctx, ApiKeyManager.SCOPE_READ);
            UUID uuid = parseUuid(ctx.pathParam("uuid"));
            String node = ctx.queryParam("node");
            if (node == null || node.isBlank()) throw ApiException.badRequest("Query param 'node' is required.");
            User user = loadUser(uuid);
            boolean granted = plugin.getUserManager().hasPermissionAsync(uuid, node);
            ctx.json(Map.of(
                    "uuid",      uuid.toString(),
                    "name",      user.getUsername(),
                    "node",      node,
                    "granted",   granted
            ));
        });

        // ── Full resolved permission map ──────────────────────────────────────
        app.get("/api/v1/users/{uuid}/permissions/resolved", ctx -> {
            ApiKeyManager.requireScope(ctx, ApiKeyManager.SCOPE_READ);
            UUID uuid = parseUuid(ctx.pathParam("uuid"));
            User user = loadUser(uuid);
            // resolveUser returns Map<String,Boolean> — perm → granted
            Map<String, Boolean> resolved = plugin.getInheritanceGraph().resolveUser(user);
            ctx.json(Map.of(
                    "uuid",        uuid.toString(),
                    "name",        user.getUsername(),
                    "count",       resolved.size(),
                    "permissions", resolved
            ));
        });

        // ── Add permission ────────────────────────────────────────────────────
        app.post("/api/v1/users/{uuid}/permissions", ctx -> {
            ApiKeyManager.requireScope(ctx, ApiKeyManager.SCOPE_WRITE);
            UUID uuid = parseUuid(ctx.pathParam("uuid"));
            Map<?, ?> body = ctx.bodyAsClass(Map.class);
            String perm = requireString(body, "permission");
            plugin.getUserManager().addPermission(uuid, perm);
            ctx.status(201).json(Map.of("message", "Permission added.", "permission", perm));
        });

        // ── Remove permission ─────────────────────────────────────────────────
        app.delete("/api/v1/users/{uuid}/permissions/{perm}", ctx -> {
            ApiKeyManager.requireScope(ctx, ApiKeyManager.SCOPE_WRITE);
            UUID uuid = parseUuid(ctx.pathParam("uuid"));
            String perm = ctx.pathParam("perm");
            plugin.getUserManager().removePermission(uuid, perm);
            ctx.json(Map.of("message", "Permission removed.", "permission", perm));
        });

        // ── Add to group ──────────────────────────────────────────────────────
        app.post("/api/v1/users/{uuid}/groups", ctx -> {
            ApiKeyManager.requireScope(ctx, ApiKeyManager.SCOPE_WRITE);
            UUID uuid = parseUuid(ctx.pathParam("uuid"));
            Map<?, ?> body = ctx.bodyAsClass(Map.class);
            String group = requireString(body, "group").toLowerCase();
            if (!plugin.getGroupManager().groupExists(group))
                throw ApiException.notFound("Group '" + group + "' not found.");
            plugin.getUserManager().addToGroup(uuid, group);
            ctx.status(201).json(Map.of("message", "Added to group.", "group", group));
        });

        // ── Remove from group ─────────────────────────────────────────────────
        app.delete("/api/v1/users/{uuid}/groups/{group}", ctx -> {
            ApiKeyManager.requireScope(ctx, ApiKeyManager.SCOPE_WRITE);
            UUID uuid = parseUuid(ctx.pathParam("uuid"));
            String group = ctx.pathParam("group").toLowerCase();
            plugin.getUserManager().removeFromGroup(uuid, group);
            ctx.json(Map.of("message", "Removed from group.", "group", group));
        });

        // ── Set prefix ────────────────────────────────────────────────────────
        app.put("/api/v1/users/{uuid}/prefix", ctx -> {
            ApiKeyManager.requireScope(ctx, ApiKeyManager.SCOPE_WRITE);
            UUID uuid = parseUuid(ctx.pathParam("uuid"));
            Map<?, ?> body = ctx.bodyAsClass(Map.class);
            String prefix = body.containsKey("prefix") ? String.valueOf(body.get("prefix")) : "";
            plugin.getUserManager().setPrefix(uuid, prefix);
            ctx.json(Map.of("message", "Prefix updated.", "prefix", prefix));
        });

        // ── Set suffix ────────────────────────────────────────────────────────
        app.put("/api/v1/users/{uuid}/suffix", ctx -> {
            ApiKeyManager.requireScope(ctx, ApiKeyManager.SCOPE_WRITE);
            UUID uuid = parseUuid(ctx.pathParam("uuid"));
            Map<?, ?> body = ctx.bodyAsClass(Map.class);
            String suffix = body.containsKey("suffix") ? String.valueOf(body.get("suffix")) : "";
            plugin.getUserManager().setSuffix(uuid, suffix);
            ctx.json(Map.of("message", "Suffix updated.", "suffix", suffix));
        });

        // ── Set meta ──────────────────────────────────────────────────────────
        app.put("/api/v1/users/{uuid}/meta", ctx -> {
            ApiKeyManager.requireScope(ctx, ApiKeyManager.SCOPE_WRITE);
            UUID uuid = parseUuid(ctx.pathParam("uuid"));
            Map<?, ?> body = ctx.bodyAsClass(Map.class);
            String key   = requireString(body, "key");
            String value = requireString(body, "value");
            plugin.getUserManager().setMeta(uuid, key, value);
            ctx.json(Map.of("message", "Meta set.", "key", key, "value", value));
        });

        // ── Delete meta ───────────────────────────────────────────────────────
        app.delete("/api/v1/users/{uuid}/meta/{key}", ctx -> {
            ApiKeyManager.requireScope(ctx, ApiKeyManager.SCOPE_WRITE);
            UUID uuid = parseUuid(ctx.pathParam("uuid"));
            plugin.getUserManager().unsetMeta(uuid, ctx.pathParam("key"));
            ctx.json(Map.of("message", "Meta key removed."));
        });

        // ── Add timed permission ──────────────────────────────────────────────
        app.post("/api/v1/users/{uuid}/timed-permissions", ctx -> {
            ApiKeyManager.requireScope(ctx, ApiKeyManager.SCOPE_WRITE);
            UUID uuid = parseUuid(ctx.pathParam("uuid"));
            Map<?, ?> body = ctx.bodyAsClass(Map.class);
            String perm     = requireString(body, "permission");
            String duration = requireString(body, "duration"); // e.g. "1d", "12h", "30m"
            long seconds    = parseDuration(duration);
            long expiry     = System.currentTimeMillis() / 1000L + seconds;
            plugin.getTimedPermissionManager().addTimedPermission(
                    uuid.toString(), false, perm, seconds);
            ctx.status(201).json(Map.of(
                    "message",    "Timed permission added.",
                    "permission", perm,
                    "duration",   duration,
                    "expiresAt",  expiry
            ));
        });

        // ── List timed permissions ────────────────────────────────────────────
        app.get("/api/v1/users/{uuid}/timed-permissions", ctx -> {
            ApiKeyManager.requireScope(ctx, ApiKeyManager.SCOPE_READ);
            UUID uuid = parseUuid(ctx.pathParam("uuid"));
            var list = plugin.getTimedPermissionManager().getTimedPermissions(uuid.toString())
                    .stream()
                    .map(tp -> Map.of(
                            "permission", tp.getPermission(),
                            "expiresAt",  tp.getExpiry().getEpochSecond(),
                            "remaining",  Math.max(0, tp.getExpiry().getEpochSecond() - System.currentTimeMillis() / 1000L)
                    ))
                    .collect(Collectors.toList());
            ctx.json(Map.of("uuid", uuid.toString(), "timedPermissions", list));
        });

        // ── Revoke timed permission ───────────────────────────────────────────
        app.delete("/api/v1/users/{uuid}/timed-permissions/{perm}", ctx -> {
            ApiKeyManager.requireScope(ctx, ApiKeyManager.SCOPE_WRITE);
            UUID uuid = parseUuid(ctx.pathParam("uuid"));
            String perm = ctx.pathParam("perm");
            plugin.getTimedPermissionManager().removeTimedPermission(uuid.toString(), perm);
            ctx.json(Map.of("message", "Timed permission revoked.", "permission", perm));
        });

        // ── Add timed group membership ────────────────────────────────────────
        // POST /api/v1/users/{uuid}/timed-groups  { "group": "vip", "duration": "7d" }
        app.post("/api/v1/users/{uuid}/timed-groups", ctx -> {
            ApiKeyManager.requireScope(ctx, ApiKeyManager.SCOPE_WRITE);
            UUID uuid = parseUuid(ctx.pathParam("uuid"));
            Map<?, ?> body = ctx.bodyAsClass(Map.class);
            String group    = requireString(body, "group").toLowerCase();
            String duration = requireString(body, "duration");
            if (!plugin.getGroupManager().groupExists(group))
                throw ApiException.notFound("Group '" + group + "' not found.");
            long seconds = parseDuration(duration);
            long expiry  = System.currentTimeMillis() / 1000L + seconds;
            plugin.getTimedGroupManager().addTimedGroup(uuid.toString(), group, seconds);
            ctx.status(201).json(Map.of(
                    "message",   "Timed group membership added.",
                    "group",     group,
                    "duration",  duration,
                    "expiresAt", expiry
            ));
        });

        // ── List timed groups ─────────────────────────────────────────────────
        app.get("/api/v1/users/{uuid}/timed-groups", ctx -> {
            ApiKeyManager.requireScope(ctx, ApiKeyManager.SCOPE_READ);
            UUID uuid = parseUuid(ctx.pathParam("uuid"));
            var list = plugin.getTimedGroupManager()
                    .getTimedGroups(uuid.toString())
                    .stream()
                    .map(tg -> Map.of(
                            "group",     tg.getGroupName(),
                            "expiresAt", tg.getExpiry().getEpochSecond(),
                            "remaining", tg.getFormattedExpiry()
                    ))
                    .collect(java.util.stream.Collectors.toList());
            ctx.json(Map.of("uuid", uuid.toString(), "timedGroups", list));
        });

        // ── Revoke timed group ────────────────────────────────────────────────
        app.delete("/api/v1/users/{uuid}/timed-groups/{group}", ctx -> {
            ApiKeyManager.requireScope(ctx, ApiKeyManager.SCOPE_WRITE);
            UUID uuid  = parseUuid(ctx.pathParam("uuid"));
            String grp = ctx.pathParam("group").toLowerCase();
            boolean removed = plugin.getTimedGroupManager().removeTimedGroup(uuid.toString(), grp);
            if (!removed) throw ApiException.notFound(
                    "No active timed group '" + grp + "' for this user.");
            ctx.json(Map.of("message", "Timed group membership revoked.", "group", grp));
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private User loadUser(UUID uuid) {
        // FIX (LP Gap #3 — Offline Users): previously this read straight from
        // storage for offline players without registering the result into
        // UserManager's in-memory map. Any follow-up call in the same request
        // (hasPermissionAsync, addPermission, addToGroup, setPrefix, ...) reads
        // from that map directly, so it would silently miss the user, see a
        // stale/empty view, or no-op. getOrLoadUserSync() loads AND registers
        // it, so this route and all mutation routes now share one consistent
        // User instance per UUID.
        User user = plugin.getUserManager().getOrLoadUserSync(uuid);
        if (user == null) throw ApiException.notFound("User '" + uuid + "' not found.");
        return user;
    }

    private Map<String, Object> toDetailMap(User user) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("uuid",        user.getUuid().toString());
        m.put("name",        user.getUsername());
        m.put("online",      org.bukkit.Bukkit.getPlayer(user.getUuid()) != null);
        m.put("prefix",      user.getPrefix());
        m.put("suffix",      user.getSuffix());
        m.put("groups",      new ArrayList<>(user.getGroups()));
        m.put("permissions", new ArrayList<>(user.getPermissions()));
        m.put("meta",        new HashMap<>(user.getMeta()));
        return m;
    }

    private static UUID parseUuid(String raw) {
        try { return UUID.fromString(raw); }
        catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Invalid UUID format: '" + raw + "'");
        }
    }

    private static String requireString(Map<?, ?> body, String key) {
        Object v = body.get(key);
        if (v == null || v.toString().isBlank())
            throw ApiException.badRequest("Missing required field: '" + key + "'");
        return v.toString().trim();
    }

    /**
     * Parse a duration string using the central {@link ir.permscraft.utils.DurationParser}.
     * Supported units: s, m, h, d, w, mo, y — compound formats allowed (e.g. 1d10h, 2w3d).
     */
    public static long parseDuration(String s) {
        try {
            return ir.permscraft.utils.DurationParser.parse(s);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Invalid duration: " + e.getMessage());
        }
    }
}
