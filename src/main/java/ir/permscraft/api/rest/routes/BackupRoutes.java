package ir.permscraft.api.rest.routes;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import ir.permscraft.PermsCraft;
import ir.permscraft.api.rest.ApiException;
import ir.permscraft.api.rest.auth.ApiKeyManager;
import ir.permscraft.models.Group;
import ir.permscraft.models.User;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * REST routes for backup and restore.
 *
 * GET  /api/v1/backup/export           — export full data as JSON
 *   Query params:
 *     include=groups,users,tracks,context   (default: all)
 *     format=json (only option for now)
 *
 * POST /api/v1/backup/import           — import JSON backup (DANGEROUS: merges or replaces)
 *   Body: the JSON produced by export
 *   Query params:
 *     mode=merge (default) | replace
 *     dry-run=true — validate without writing
 *
 * GET  /api/v1/backup/list             — list server-side backup snapshots
 * POST /api/v1/backup/snapshot         — create a server-side named snapshot
 * POST /api/v1/backup/restore/{name}   — restore a named snapshot
 */
public class BackupRoutes {

    private final PermsCraft plugin;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public BackupRoutes(PermsCraft plugin) {
        this.plugin = plugin;
    }

    public void register(Javalin app) {

        // ── Export ────────────────────────────────────────────────────────────
        app.get("/api/v1/backup/export", ctx -> {
            ApiKeyManager.requireScope(ctx, ApiKeyManager.SCOPE_BACKUP);

            Set<String> include = parseInclude(ctx.queryParam("include"));

            Map<String, Object> export = new LinkedHashMap<>();
            export.put("meta", Map.of(
                    "exportedAt",   Instant.now().toString(),
                    "server",       plugin.getServerName(),
                    "pluginVersion", plugin.getDescription().getVersion(),
                    "include",      include
            ));

            if (include.contains("groups")) {
                export.put("groups", plugin.getGroupManager().getAllGroups()
                        .stream()
                        .map(g -> {
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("name",          g.getName());
                            m.put("displayName",   g.getDisplayName());
                            m.put("weight",        g.getWeight());
                            m.put("prefix",        g.getPrefix());
                            m.put("suffix",        g.getSuffix());
                            m.put("permissions",   new ArrayList<>(g.getPermissions()));
                            m.put("parents",       new ArrayList<>(g.getInheritedGroups()));
                            m.put("meta",          new HashMap<>(g.getMeta()));
                            return m;
                        })
                        .collect(Collectors.toList()));
            }

            if (include.contains("users")) {
                List<User> allUsers = plugin.getStorage().loadAllUsers();
                export.put("users", allUsers.stream()
                        .map(u -> {
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("uuid",        u.getUuid().toString());
                            m.put("name",        u.getUsername());
                            m.put("prefix",      u.getPrefix());
                            m.put("suffix",      u.getSuffix());
                            m.put("groups",      new ArrayList<>(u.getGroups()));
                            m.put("permissions", new ArrayList<>(u.getPermissions()));
                            m.put("meta",        new HashMap<>(u.getMeta()));
                            return m;
                        })
                        .collect(Collectors.toList()));
            }

            if (include.contains("tracks")) {
                export.put("tracks", plugin.getStorage().loadAllTracks());
            }

            if (include.contains("context")) {
                // FIX (Bug #10): stream row-by-row instead of loading all context permissions
                // into a single List, which would OOM on large servers with thousands of rows.
                List<Map<String, Object>> ctxRows = new ArrayList<>();
                plugin.getStorage().streamContextPermissions(row ->
                        ctxRows.add(new LinkedHashMap<>(row)));
                export.put("contextPermissions", ctxRows);
            }

            // Return as JSON download
            ctx.header("Content-Disposition",
                    "attachment; filename=\"permscraft-backup-"
                            + Instant.now().toString().replace(":", "-") + ".json\"");
            ctx.contentType("application/json");
            ctx.result(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(export));
        });

        // ── Import ────────────────────────────────────────────────────────────
        app.post("/api/v1/backup/import", ctx -> {
            ApiKeyManager.requireScope(ctx, ApiKeyManager.SCOPE_BACKUP);

            boolean dryRun  = "true".equalsIgnoreCase(ctx.queryParam("dry-run"));
            String  mode    = Optional.ofNullable(ctx.queryParam("mode")).orElse("merge");
            if (!mode.equals("merge") && !mode.equals("replace")) {
                throw ApiException.badRequest("'mode' must be 'merge' or 'replace'.");
            }

            Map<?, ?> body;
            try {
                body = MAPPER.readValue(ctx.body(), Map.class);
            } catch (Exception e) {
                throw ApiException.badRequest("Invalid JSON body: " + e.getMessage());
            }

            // Validate meta block
            if (!body.containsKey("meta")) {
                throw ApiException.badRequest("Missing 'meta' block — is this a valid PermsCraft backup?");
            }

            List<String> warnings = new ArrayList<>();
            int groupsImported = 0;
            int usersImported  = 0;

            if (body.containsKey("groups")) {
                List<?> groups = (List<?>) body.get("groups");
                if (!dryRun && mode.equals("replace")) {
                    plugin.getGroupManager().getAllGroups().stream()
                            .map(Group::getName)
                            .filter(n -> !n.equals("default"))
                            .forEach(n -> plugin.getGroupManager().deleteGroup(n));
                }
                for (Object obj : groups) {
                    Map<?, ?> gm = (Map<?, ?>) obj;
                    String name = String.valueOf(gm.get("name")).toLowerCase();
                    if (!dryRun) {
                        Group g = plugin.getGroupManager().groupExists(name)
                                ? plugin.getGroupManager().getGroup(name)
                                : plugin.getGroupManager().createGroup(name);
                        if (gm.get("weight") != null)
                            plugin.getGroupManager().setWeight(name,
                                    Integer.parseInt(String.valueOf(gm.get("weight"))));
                        if (gm.get("prefix") != null)
                            plugin.getGroupManager().setPrefix(name, String.valueOf(gm.get("prefix")));
                        if (gm.get("suffix") != null)
                            plugin.getGroupManager().setSuffix(name, String.valueOf(gm.get("suffix")));
                        if (gm.get("permissions") instanceof List<?> perms)
                            perms.forEach(p -> plugin.getGroupManager()
                                    .addPermission(name, String.valueOf(p)));
                        if (gm.get("parents") instanceof List<?> parents)
                            parents.forEach(p -> plugin.getGroupManager()
                                    .addInheritance(name, String.valueOf(p)));
                        if (gm.get("meta") instanceof Map<?, ?> meta)
                            meta.forEach((k, v) -> plugin.getGroupManager()
                                    .setMeta(name, String.valueOf(k), String.valueOf(v)));
                    }
                    groupsImported++;
                }
            }

            if (body.containsKey("users")) {
                List<?> users = (List<?>) body.get("users");
                for (Object obj : users) {
                    Map<?, ?> um = (Map<?, ?>) obj;
                    try {
                        UUID uuid = UUID.fromString(String.valueOf(um.get("uuid")));
                        String uname = String.valueOf(um.get("name"));
                        if (!dryRun) {
                            plugin.getUserManager().importUserData(uuid, uname, u -> {
                                if (um.get("prefix") != null)   u.setPrefix(String.valueOf(um.get("prefix")));
                                if (um.get("suffix") != null)   u.setSuffix(String.valueOf(um.get("suffix")));
                                if (um.get("groups") instanceof List<?> gs)
                                    gs.forEach(g -> u.addGroup(String.valueOf(g)));
                                if (um.get("permissions") instanceof List<?> ps)
                                    ps.forEach(p -> u.addPermission(String.valueOf(p)));
                                if (um.get("meta") instanceof Map<?, ?> meta)
                                    meta.forEach((k, v) -> u.setMeta(String.valueOf(k), String.valueOf(v)));
                            });
                        }
                        usersImported++;
                    } catch (IllegalArgumentException e) {
                        warnings.add("Skipped user with invalid UUID: " + um.get("uuid"));
                    }
                }
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("dryRun",        dryRun);
            result.put("mode",          mode);
            result.put("groupsImported", groupsImported);
            result.put("usersImported",  usersImported);
            result.put("warnings",       warnings);
            result.put("message", dryRun
                    ? "Dry-run complete. No data was written."
                    : "Import complete.");
            ctx.json(result);
        });

        // ── List snapshots ────────────────────────────────────────────────────
        app.get("/api/v1/backup/list", ctx -> {
            ApiKeyManager.requireScope(ctx, ApiKeyManager.SCOPE_BACKUP);
            List<Map<String, Object>> snapshots = plugin.getBackupManager().listSnapshots();
            ctx.json(Map.of("snapshots", snapshots, "count", snapshots.size()));
        });

        // ── Create snapshot ───────────────────────────────────────────────────
        app.post("/api/v1/backup/snapshot", ctx -> {
            ApiKeyManager.requireScope(ctx, ApiKeyManager.SCOPE_BACKUP);
            Map<?, ?> body = ctx.bodyAsClass(Map.class);
            String label = body.containsKey("label")
                    ? String.valueOf(body.get("label")).trim()
                    : "api-" + Instant.now().toString().replace(":", "-");
            String path = plugin.getBackupManager().createSnapshot(label);
            ctx.status(201).json(Map.of(
                    "message",  "Snapshot created.",
                    "label",    label,
                    "path",     path,
                    "createdAt", Instant.now().toString()
            ));
        });

        // ── Restore snapshot ──────────────────────────────────────────────────
        app.post("/api/v1/backup/restore/{name}", ctx -> {
            ApiKeyManager.requireScope(ctx, ApiKeyManager.SCOPE_BACKUP);
            String name = ctx.pathParam("name");
            boolean dryRun = "true".equalsIgnoreCase(ctx.queryParam("dry-run"));
            plugin.getBackupManager().restoreSnapshot(name, dryRun);
            ctx.json(Map.of(
                    "message", dryRun
                            ? "Dry-run: snapshot '" + name + "' validated successfully."
                            : "Snapshot '" + name + "' restored. Run /pc reload to apply.",
                    "snapshot", name,
                    "dryRun",  dryRun
            ));
        });
    }

    private static Set<String> parseInclude(String raw) {
        if (raw == null || raw.isBlank()) return Set.of("groups", "users", "tracks", "context");
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .collect(Collectors.toSet());
    }
}
