package ir.permscraft.backup;

import com.fasterxml.jackson.databind.ObjectMapper;
import ir.permscraft.PermsCraft;
import ir.permscraft.api.rest.ApiException;
import ir.permscraft.models.Group;
import ir.permscraft.models.User;

import java.io.File;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Server-side backup snapshots.
 *
 * Snapshots are stored as JSON files in:
 *   plugins/PermsCraft/backups/<label>_<timestamp>.json
 *
 * Each snapshot is a full export of groups + users + tracks.
 * The REST API calls this; in-game /pc backup commands also go through here.
 */
public class BackupManager {

    private final PermsCraft plugin;
    private final File backupDir;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public BackupManager(PermsCraft plugin) {
        this.plugin    = plugin;
        this.backupDir = new File(plugin.getDataFolder(), "backups");
        backupDir.mkdirs();
    }

    // ── Create ────────────────────────────────────────────────────────────────

    /**
     * Serialize the current state to a named snapshot file.
     *
     * @param label human-readable label (slashes are replaced with underscores)
     * @return absolute path of the written file
     */
    public String createSnapshot(String label) {
        String safe      = label.replaceAll("[/\\\\:*?\"<>|]", "_");
        String timestamp = Instant.now().toString().replace(":", "-");
        String filename  = safe + "_" + timestamp + ".json";
        File   target    = new File(backupDir, filename);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("meta", Map.of(
                "label",         label,
                "exportedAt",    Instant.now().toString(),
                "server",        plugin.getServerName(),
                "pluginVersion", plugin.getPluginMeta().getVersion()
        ));

        data.put("groups", plugin.getGroupManager().getAllGroups()
                .stream().map(this::groupToMap).collect(Collectors.toList()));

        data.put("users", plugin.getStorage().loadAllUsers()
                .stream().map(this::userToMap).collect(Collectors.toList()));

        data.put("tracks", plugin.getStorage().loadAllTracks());

        try {
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(target, data);
            plugin.getLogger().info("[PermsCraft Backup] Snapshot created: " + filename);
            return target.getAbsolutePath();
        } catch (Exception e) {
            throw new RuntimeException("Failed to write snapshot: " + e.getMessage(), e);
        }
    }

    // ── List ──────────────────────────────────────────────────────────────────

    public List<Map<String, Object>> listSnapshots() {
        File[] files = backupDir.listFiles(f -> f.getName().endsWith(".json"));
        if (files == null) return List.of();
        return Arrays.stream(files)
                .sorted(Comparator.comparingLong(File::lastModified).reversed())
                .map(f -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name",      f.getName().replace(".json", ""));
                    m.put("filename",  f.getName());
                    m.put("sizeBytes", f.length());
                    m.put("createdAt", Instant.ofEpochMilli(f.lastModified()).toString());
                    return (Map<String, Object>) m;
                })
                .collect(Collectors.toList());
    }

    // ── Restore ───────────────────────────────────────────────────────────────

    /**
     * Restore a named snapshot.
     *
     * @param name   snapshot name (with or without .json)
     * @param dryRun if true, validate only — do not write anything
     */
    public void restoreSnapshot(String name, boolean dryRun) {
        String filename = name.endsWith(".json") ? name : name + ".json";
        File   target   = new File(backupDir, filename);
        if (!target.exists()) {
            throw ApiException.notFound("Snapshot '" + name + "' not found.");
        }

        Map<?, ?> data;
        try {
            data = MAPPER.readValue(target, Map.class);
        } catch (Exception e) {
            throw ApiException.badRequest("Cannot parse snapshot: " + e.getMessage());
        }

        if (!data.containsKey("meta")) {
            throw ApiException.badRequest("Invalid snapshot format — missing 'meta' block.");
        }

        if (dryRun) return; // validation passed

        // Restore groups
        if (data.get("groups") instanceof List<?> groups) {
            for (Object obj : groups) {
                if (!(obj instanceof Map<?, ?> gm)) continue;
                String gName = String.valueOf(gm.get("name")).toLowerCase();
                if (!plugin.getGroupManager().groupExists(gName))
                    plugin.getGroupManager().createGroup(gName);
                if (gm.get("permissions") instanceof List<?> ps)
                    ps.forEach(p -> plugin.getGroupManager().addPermission(gName, String.valueOf(p)));
                if (gm.get("parents") instanceof List<?> parents)
                    parents.forEach(p -> plugin.getGroupManager().addInheritance(gName, String.valueOf(p)));
                if (gm.get("prefix") != null)
                    plugin.getGroupManager().setPrefix(gName, String.valueOf(gm.get("prefix")));
                if (gm.get("suffix") != null)
                    plugin.getGroupManager().setSuffix(gName, String.valueOf(gm.get("suffix")));
                if (gm.get("weight") != null)
                    plugin.getGroupManager().setWeight(gName,
                            Integer.parseInt(String.valueOf(gm.get("weight"))));
                if (gm.get("meta") instanceof Map<?, ?> meta)
                    meta.forEach((k, v) -> plugin.getGroupManager()
                            .setMeta(gName, String.valueOf(k), String.valueOf(v)));
            }
        }

        // Restore users
        if (data.get("users") instanceof List<?> users) {
            for (Object obj : users) {
                if (!(obj instanceof Map<?, ?> um)) continue;
                try {
                    UUID uuid  = UUID.fromString(String.valueOf(um.get("uuid")));
                    String un  = String.valueOf(um.get("name"));
                    plugin.getUserManager().importUserData(uuid, un, fu -> {
                        if (um.get("prefix") != null)      fu.setPrefix(String.valueOf(um.get("prefix")));
                        if (um.get("suffix") != null)      fu.setSuffix(String.valueOf(um.get("suffix")));
                        if (um.get("groups") instanceof List<?> gs)
                            gs.forEach(g -> fu.addGroup(String.valueOf(g)));
                        if (um.get("permissions") instanceof List<?> ps)
                            ps.forEach(p -> fu.addPermission(String.valueOf(p)));
                        if (um.get("meta") instanceof Map<?, ?> meta)
                            meta.forEach((k, v) -> fu.setMeta(String.valueOf(k), String.valueOf(v)));
                    });
                } catch (IllegalArgumentException ignored) {}
            }
        }

        plugin.getLogger().info("[PermsCraft Backup] Snapshot '" + name + "' restored.");
    }

    // ── Serializers ───────────────────────────────────────────────────────────

    private Map<String, Object> groupToMap(Group g) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name",        g.getName());
        m.put("displayName", g.getDisplayName());
        m.put("weight",      g.getWeight());
        m.put("prefix",      g.getPrefix());
        m.put("suffix",      g.getSuffix());
        m.put("permissions", new ArrayList<>(g.getPermissions()));
        m.put("parents",     new ArrayList<>(g.getInheritedGroups()));
        m.put("meta",        new HashMap<>(g.getMeta()));
        return m;
    }

    private Map<String, Object> userToMap(User u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("uuid",        u.getUuid().toString());
        m.put("name",        u.getUsername());
        m.put("prefix",      u.getPrefix());
        m.put("suffix",      u.getSuffix());
        m.put("groups",      new ArrayList<>(u.getGroups()));
        m.put("permissions", new ArrayList<>(u.getPermissions()));
        m.put("meta",        new HashMap<>(u.getMeta()));
        return m;
    }
}
