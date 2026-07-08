package ir.permscraft.api.rest.routes;

import io.javalin.Javalin;
import io.javalin.http.Context;
import ir.permscraft.PermsCraft;
import ir.permscraft.api.rest.ApiException;
import ir.permscraft.api.rest.auth.ApiKeyManager;
import ir.permscraft.models.Group;

import java.util.*;
import java.util.stream.Collectors;

/**
 * REST routes for Group management.
 *
 * GET    /api/v1/groups                     — list all groups
 * GET    /api/v1/groups/{name}              — get one group (perms, meta, inheritance)
 * POST   /api/v1/groups                     — create group  { "name": "...", "weight": 0 }
 * DELETE /api/v1/groups/{name}              — delete group
 *
 * POST   /api/v1/groups/{name}/permissions  — add permission    { "permission": "..." }
 * DELETE /api/v1/groups/{name}/permissions/{perm} — remove permission
 *
 * POST   /api/v1/groups/{name}/parents      — add parent        { "parent": "..." }
 * DELETE /api/v1/groups/{name}/parents/{parent} — remove parent
 *
 * PUT    /api/v1/groups/{name}/meta         — set meta          { "key": "...", "value": "..." }
 * DELETE /api/v1/groups/{name}/meta/{key}   — delete meta key
 *
 * PUT    /api/v1/groups/{name}/weight       — set weight        { "weight": 10 }
 * PUT    /api/v1/groups/{name}/prefix       — set prefix        { "prefix": "&c[Admin] " }
 * PUT    /api/v1/groups/{name}/suffix       — set suffix        { "suffix": " &7✦" }
 */
public class GroupRoutes {

    private final PermsCraft plugin;

    public GroupRoutes(PermsCraft plugin) {
        this.plugin = plugin;
    }

    public void register(Javalin app) {

        // ── List all groups ───────────────────────────────────────────────────
        app.get("/api/v1/groups", ctx -> {
            ApiKeyManager.requireScope(ctx, ApiKeyManager.SCOPE_READ);
            var groups = plugin.getGroupManager().getAllGroups().stream()
                    .map(this::toMap)
                    .collect(Collectors.toList());
            ctx.json(Map.of("groups", groups, "count", groups.size()));
        });

        // ── Get single group ──────────────────────────────────────────────────
        app.get("/api/v1/groups/{name}", ctx -> {
            ApiKeyManager.requireScope(ctx, ApiKeyManager.SCOPE_READ);
            Group g = requireGroup(ctx.pathParam("name"));
            ctx.json(toDetailMap(g));
        });

        // ── Create group ──────────────────────────────────────────────────────
        app.post("/api/v1/groups", ctx -> {
            ApiKeyManager.requireScope(ctx, ApiKeyManager.SCOPE_WRITE);
            Map<?, ?> body = ctx.bodyAsClass(Map.class);
            String name = requireString(body, "name").toLowerCase();
            if (plugin.getGroupManager().groupExists(name)) {
                throw ApiException.conflict("Group '" + name + "' already exists.");
            }
            Group g = plugin.getGroupManager().createGroup(name);
            if (body.containsKey("weight")) {
                plugin.getGroupManager().setWeight(name, toInt(body.get("weight")));
            }
            if (body.containsKey("prefix")) {
                plugin.getGroupManager().setPrefix(name, String.valueOf(body.get("prefix")));
            }
            if (body.containsKey("suffix")) {
                plugin.getGroupManager().setSuffix(name, String.valueOf(body.get("suffix")));
            }
            ctx.status(201).json(toDetailMap(requireGroup(name)));
        });

        // ── Delete group ──────────────────────────────────────────────────────
        app.delete("/api/v1/groups/{name}", ctx -> {
            ApiKeyManager.requireScope(ctx, ApiKeyManager.SCOPE_WRITE);
            String name = ctx.pathParam("name").toLowerCase();
            if (name.equals("default")) throw ApiException.forbidden("Cannot delete the 'default' group.");
            requireGroup(name);
            plugin.getGroupManager().deleteGroup(name);
            ctx.json(Map.of("message", "Group '" + name + "' deleted."));
        });

        // ── Add permission ────────────────────────────────────────────────────
        app.post("/api/v1/groups/{name}/permissions", ctx -> {
            ApiKeyManager.requireScope(ctx, ApiKeyManager.SCOPE_WRITE);
            Group g = requireGroup(ctx.pathParam("name"));
            Map<?, ?> body = ctx.bodyAsClass(Map.class);
            String perm = requireString(body, "permission");
            plugin.getGroupManager().addPermission(g.getName(), perm);
            ctx.status(201).json(Map.of("message", "Permission added.", "permission", perm));
        });

        // ── Remove permission ─────────────────────────────────────────────────
        app.delete("/api/v1/groups/{name}/permissions/{perm}", ctx -> {
            ApiKeyManager.requireScope(ctx, ApiKeyManager.SCOPE_WRITE);
            Group g = requireGroup(ctx.pathParam("name"));
            String perm = ctx.pathParam("perm");
            plugin.getGroupManager().removePermission(g.getName(), perm);
            ctx.json(Map.of("message", "Permission removed.", "permission", perm));
        });

        // ── Add parent ────────────────────────────────────────────────────────
        app.post("/api/v1/groups/{name}/parents", ctx -> {
            ApiKeyManager.requireScope(ctx, ApiKeyManager.SCOPE_WRITE);
            Group g = requireGroup(ctx.pathParam("name"));
            Map<?, ?> body = ctx.bodyAsClass(Map.class);
            String parent = requireString(body, "parent").toLowerCase();
            requireGroup(parent);
            plugin.getGroupManager().addInheritance(g.getName(), parent);
            ctx.status(201).json(Map.of("message", "Parent added.", "parent", parent));
        });

        // ── Remove parent ─────────────────────────────────────────────────────
        app.delete("/api/v1/groups/{name}/parents/{parent}", ctx -> {
            ApiKeyManager.requireScope(ctx, ApiKeyManager.SCOPE_WRITE);
            Group g = requireGroup(ctx.pathParam("name"));
            plugin.getGroupManager().removeInheritance(g.getName(), ctx.pathParam("parent"));
            ctx.json(Map.of("message", "Parent removed."));
        });

        // ── Set meta ──────────────────────────────────────────────────────────
        app.put("/api/v1/groups/{name}/meta", ctx -> {
            ApiKeyManager.requireScope(ctx, ApiKeyManager.SCOPE_WRITE);
            Group g = requireGroup(ctx.pathParam("name"));
            Map<?, ?> body = ctx.bodyAsClass(Map.class);
            String key   = requireString(body, "key");
            String value = requireString(body, "value");
            plugin.getGroupManager().setMeta(g.getName(), key, value);
            ctx.json(Map.of("message", "Meta set.", "key", key, "value", value));
        });

        // ── Delete meta ───────────────────────────────────────────────────────
        app.delete("/api/v1/groups/{name}/meta/{key}", ctx -> {
            ApiKeyManager.requireScope(ctx, ApiKeyManager.SCOPE_WRITE);
            Group g = requireGroup(ctx.pathParam("name"));
            plugin.getGroupManager().unsetMeta(g.getName(), ctx.pathParam("key"));
            ctx.json(Map.of("message", "Meta key removed."));
        });

        // ── Set weight ────────────────────────────────────────────────────────
        app.put("/api/v1/groups/{name}/weight", ctx -> {
            ApiKeyManager.requireScope(ctx, ApiKeyManager.SCOPE_WRITE);
            Group g = requireGroup(ctx.pathParam("name"));
            Map<?, ?> body = ctx.bodyAsClass(Map.class);
            int weight = body.containsKey("weight") ? toInt(body.get("weight")) : 0;
            plugin.getGroupManager().setWeight(g.getName(), weight);
            ctx.json(Map.of("message", "Weight updated.", "weight", weight));
        });

        // ── Set prefix ────────────────────────────────────────────────────────
        app.put("/api/v1/groups/{name}/prefix", ctx -> {
            ApiKeyManager.requireScope(ctx, ApiKeyManager.SCOPE_WRITE);
            Group g = requireGroup(ctx.pathParam("name"));
            Map<?, ?> body = ctx.bodyAsClass(Map.class);
            String prefix = body.containsKey("prefix") ? String.valueOf(body.get("prefix")) : "";
            plugin.getGroupManager().setPrefix(g.getName(), prefix);
            ctx.json(Map.of("message", "Prefix updated.", "prefix", prefix));
        });

        // ── Set suffix ────────────────────────────────────────────────────────
        app.put("/api/v1/groups/{name}/suffix", ctx -> {
            ApiKeyManager.requireScope(ctx, ApiKeyManager.SCOPE_WRITE);
            Group g = requireGroup(ctx.pathParam("name"));
            Map<?, ?> body = ctx.bodyAsClass(Map.class);
            String suffix = body.containsKey("suffix") ? String.valueOf(body.get("suffix")) : "";
            plugin.getGroupManager().setSuffix(g.getName(), suffix);
            ctx.json(Map.of("message", "Suffix updated.", "suffix", suffix));
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Group requireGroup(String name) {
        Group g = plugin.getGroupManager().getGroup(name.toLowerCase());
        if (g == null) throw ApiException.notFound("Group '" + name + "' not found.");
        return g;
    }

    private Map<String, Object> toMap(Group g) {
        return Map.of(
                "name",        g.getName(),
                "displayName", g.getDisplayName(),
                "weight",      g.getWeight(),
                "prefix",      g.getPrefix(),
                "suffix",      g.getSuffix()
        );
    }

    private Map<String, Object> toDetailMap(Group g) {
        Map<String, Object> m = new LinkedHashMap<>(toMap(g));
        m.put("permissions",  new ArrayList<>(g.getPermissions()));
        m.put("parents",      new ArrayList<>(g.getInheritedGroups()));
        m.put("meta",         new HashMap<>(g.getMeta()));
        m.put("inheritanceChain",
                plugin.getInheritanceGraph().getInheritanceChain(g.getName()));
        return m;
    }

    private static String requireString(Map<?, ?> body, String key) {
        Object v = body.get(key);
        if (v == null || v.toString().isBlank())
            throw ApiException.badRequest("Missing required field: '" + key + "'");
        return v.toString().trim();
    }

    private static int toInt(Object v) {
        try { return Integer.parseInt(String.valueOf(v)); }
        catch (NumberFormatException e) { throw ApiException.badRequest("Expected integer, got: " + v); }
    }
}
