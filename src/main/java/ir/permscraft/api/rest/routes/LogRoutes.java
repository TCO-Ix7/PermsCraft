package ir.permscraft.api.rest.routes;

import io.javalin.Javalin;
import ir.permscraft.PermsCraft;
import ir.permscraft.api.rest.ApiException;
import ir.permscraft.api.rest.auth.ApiKeyManager;

import java.util.*;

/**
 * REST routes for audit log access.
 *
 * GET /api/v1/logs                     — list recent log entries (paginated)
 *   Query params:
 *     page     (default 1)
 *     size     (default 50, max 200)
 *     actor    — filter by actor name
 *     target   — filter by target (UUID or group name)
 *     action   — filter by action type (e.g. PERMISSION_ADD)
 *     from     — epoch seconds (start of range)
 *     to       — epoch seconds (end of range)
 *
 * GET /api/v1/logs/actions             — list all distinct action types
 *
 * DELETE /api/v1/logs                  — purge logs older than ?days=N (default: config keep-days)
 */
public class LogRoutes {

    private final PermsCraft plugin;

    public LogRoutes(PermsCraft plugin) {
        this.plugin = plugin;
    }

    public void register(Javalin app) {

        // ── List logs ─────────────────────────────────────────────────────────
        app.get("/api/v1/logs", ctx -> {
            ApiKeyManager.requireScope(ctx, ApiKeyManager.SCOPE_LOG);

            int page   = intParam(ctx.queryParam("page"),  1);
            int size   = Math.min(intParam(ctx.queryParam("size"), 50), 200);
            int offset = (page - 1) * size;

            String actor  = ctx.queryParam("actor");
            String target = ctx.queryParam("target");
            String action = ctx.queryParam("action");
            Long   from   = longParam(ctx.queryParam("from"));
            Long   to     = longParam(ctx.queryParam("to"));

            var filter = new ir.permscraft.storage.LogFilter(
                    actor, target, action, from, to, size, offset);

            List<Map<String, Object>> entries = plugin.getStorage()
                    .queryLogs(filter)
                    .stream()
                    .map(e -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id",        e.getId());
                        m.put("timestamp", e.getTimestamp().getEpochSecond());
                        m.put("actor",     e.getActor());
                        m.put("action",    e.getAction().name());
                        m.put("target",    e.getTarget());
                        m.put("detail",    e.getDetail());
                        return m;
                    })
                    .toList();

            long total = plugin.getStorage().countLogs(filter);

            ctx.json(Map.of(
                    "page",       page,
                    "size",       size,
                    "total",      total,
                    "totalPages", (int) Math.ceil((double) total / size),
                    "entries",    entries
            ));
        });

        // ── List distinct action types ────────────────────────────────────────
        app.get("/api/v1/logs/actions", ctx -> {
            ApiKeyManager.requireScope(ctx, ApiKeyManager.SCOPE_LOG);
            List<String> actions = plugin.getStorage().getDistinctLogActions();
            ctx.json(Map.of("actions", actions));
        });

        // ── Purge old logs ────────────────────────────────────────────────────
        app.delete("/api/v1/logs", ctx -> {
            ApiKeyManager.requireScope(ctx, ApiKeyManager.SCOPE_ADMIN);
            int days = intParam(ctx.queryParam("days"),
                    plugin.getConfig().getInt("log.keep-days", 30));
            if (days < 1) throw ApiException.badRequest("'days' must be >= 1.");
            int deleted = plugin.getStorage().purgeLogs(days);
            ctx.json(Map.of(
                    "message", "Purged log entries older than " + days + " days.",
                    "deleted", deleted
            ));
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static int intParam(String raw, int fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try { return Math.max(1, Integer.parseInt(raw)); }
        catch (NumberFormatException e) { return fallback; }
    }

    private static Long longParam(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return Long.parseLong(raw); }
        catch (NumberFormatException e) { return null; }
    }
}
