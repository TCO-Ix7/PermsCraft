package ir.permscraft.storage;

import ir.permscraft.PermsCraft;
import ir.permscraft.context.Context;
import ir.permscraft.context.ContextSet;
import ir.permscraft.context.ContextualPermission;
import ir.permscraft.logging.LogEntry;
import ir.permscraft.models.Group;
import ir.permscraft.models.TimedGroup;
import ir.permscraft.models.TimedPermission;
import ir.permscraft.models.Track;
import ir.permscraft.models.User;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;

/**
 * Flat-file YAML storage backend.
 *
 * Layout (relative to plugin data folder, configurable via storage.yaml.folder, default "data"):
 *   data/groups.yml                 — all groups: permissions, inheritance, meta, prefix/suffix
 *   data/users/&lt;uuid&gt;.yml     — one file per user (permissions, groups, meta, prefix/suffix)
 *   data/tracks.yml                 — tracks
 *   data/timed-permissions.yml      — active timed permissions (users + groups)
 *   data/context-permissions.yml    — context-bound permission rows
 *   data/logs.yml                   — audit log (capped, oldest entries pruned)
 *   data/usernames.yml              — uuid <-> last-known-username index (for findUUIDByUsername)
 *
 * Every mutating call writes to disk immediately (synchronous file I/O on the
 * calling thread). PermsCraft already dispatches storage mutations via async
 * schedulers, so this never blocks the main server thread.
 *
 * YAML is intentionally simple: no transactions, no joins. Bulk operations
 * iterate every user file on disk, so they may be slow on very large user
 * bases — this is the expected trade-off for a human-editable,
 * dependency-free storage backend (great for small/medium servers, easy
 * manual edits and backups via git/rsync).
 */
public class YamlStorage implements StorageBackend {

    private static final int MAX_LOG_ENTRIES = 5000;

    private final PermsCraft plugin;
    private final Logger logger;

    private File root;
    private File usersDir;
    private File groupsFile;
    private File tracksFile;
    private File timedFile;
    private File contextFile;
    private File logsFile;
    private File usernamesFile;

    public YamlStorage(PermsCraft plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    // ── init / close ──────────────────────────────────────────────────────

    @Override
    public boolean init() {
        try {
            String folder = plugin.getConfig().getString("storage.yaml.folder", "data");
            root = new File(plugin.getDataFolder(), folder);
            usersDir = new File(root, "users");
            if (!usersDir.exists() && !usersDir.mkdirs()) {
                logger.severe("[PermsCraft] Failed to create YAML storage directory: " + usersDir.getAbsolutePath());
                return false;
            }

            groupsFile    = new File(root, "groups.yml");
            tracksFile    = new File(root, "tracks.yml");
            timedFile     = new File(root, "timed-permissions.yml");
            contextFile   = new File(root, "context-permissions.yml");
            logsFile      = new File(root, "logs.yml");
            usernamesFile = new File(root, "usernames.yml");

            for (File f : new File[]{groupsFile, tracksFile, timedFile, contextFile, logsFile, usernamesFile}) {
                createIfMissing(f);
            }

            // Ensure a "default" group exists, like the SQL backends.
            YamlConfiguration groups = load(groupsFile);
            if (!groups.contains("default")) {
                ConfigurationSection def = groups.createSection("default");
                def.set("display-name", "Default");
                def.set("prefix", "");
                def.set("suffix", "");
                def.set("weight", 0);
                save(groups, groupsFile);
            }

            return true;
        } catch (Exception e) {
            logger.severe("[PermsCraft] YAML storage init failed: " + e.getMessage());
            return false;
        }
    }

    @Override
    public void close() {
        // Nothing to close — every write is flushed synchronously.
    }

    // ── file helpers ──────────────────────────────────────────────────────

    private void createIfMissing(File f) throws IOException {
        if (!f.exists()) {
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            f.createNewFile();
        }
    }

    private synchronized YamlConfiguration load(File f) {
        return YamlConfiguration.loadConfiguration(f);
    }

    private synchronized void save(YamlConfiguration yaml, File f) {
        try {
            yaml.save(f);
        } catch (IOException e) {
            logger.severe("[PermsCraft] Failed to write " + f.getName() + ": " + e.getMessage());
        }
    }

    private File userFile(UUID uuid) {
        return new File(usersDir, uuid.toString() + ".yml");
    }

    private ConfigurationSection getOrCreate(YamlConfiguration yaml, String name) {
        ConfigurationSection sec = yaml.getConfigurationSection(name);
        if (sec == null) sec = yaml.createSection(name);
        return sec;
    }

    /** YAML section names can't contain '.'; keep generated keys filesystem/yaml-safe. */
    private String sanitizeKey(String s) {
        return s.replace(".", "_DOT_").replace(":", "_C_").replace(" ", "_");
    }

    private UUID safeUuid(String target) {
        try { return UUID.fromString(target); } catch (Exception e) { return UUID.nameUUIDFromBytes(target.getBytes()); }
    }

    // ── Groups ────────────────────────────────────────────────────────────

    @Override
    public List<Group> loadAllGroups() {
        List<Group> result = new ArrayList<>();
        YamlConfiguration yaml = load(groupsFile);
        for (String name : yaml.getKeys(false)) {
            ConfigurationSection sec = yaml.getConfigurationSection(name);
            if (sec == null) continue;
            Group g = new Group(name);
            g.setDisplayName(sec.getString("display-name", name));
            g.setPrefix(sec.getString("prefix", ""));
            g.setSuffix(sec.getString("suffix", ""));
            g.setWeight(sec.getInt("weight", 0));
            for (String p : sec.getStringList("permissions")) g.addPermission(p);
            for (String parent : sec.getStringList("inheritance")) g.addInheritance(parent);
            ConfigurationSection meta = sec.getConfigurationSection("meta");
            if (meta != null) {
                for (String key : meta.getKeys(false)) {
                    Object val = meta.get(key);
                    if (val instanceof ConfigurationSection timedEntry) {
                        long expiry = timedEntry.getLong("expiry", -1);
                        if (expiry > 0 && expiry < Instant.now().getEpochSecond()) continue;
                        g.setMeta(key, timedEntry.getString("value", ""));
                    } else if (val != null) {
                        g.setMeta(key, val.toString());
                    }
                }
            }
            result.add(g);
        }
        return result;
    }

    @Override
    public synchronized void saveGroup(Group group) {
        YamlConfiguration yaml = load(groupsFile);
        ConfigurationSection sec = getOrCreate(yaml, group.getName());
        sec.set("display-name", group.getDisplayName());
        sec.set("prefix", group.getPrefix());
        sec.set("suffix", group.getSuffix());
        sec.set("weight", group.getWeight());
        sec.set("permissions", new ArrayList<>(group.getPermissions()));
        sec.set("inheritance", new ArrayList<>(group.getInheritedGroups()));
        save(yaml, groupsFile);
    }

    @Override
    public synchronized void deleteGroup(String groupName) {
        YamlConfiguration yaml = load(groupsFile);
        yaml.set(groupName, null);

        // Remove from other groups' inheritance lists
        for (String name : yaml.getKeys(false)) {
            ConfigurationSection sec = yaml.getConfigurationSection(name);
            if (sec == null) continue;
            List<String> inh = sec.getStringList("inheritance");
            if (inh.remove(groupName)) sec.set("inheritance", inh);
        }
        save(yaml, groupsFile);
    }

    @Override
    public synchronized void addGroupPermission(String groupName, String permission) {
        YamlConfiguration yaml = load(groupsFile);
        ConfigurationSection sec = getOrCreate(yaml, groupName);
        List<String> perms = sec.getStringList("permissions");
        if (!perms.contains(permission)) perms.add(permission);
        sec.set("permissions", perms);
        save(yaml, groupsFile);
    }

    @Override
    public synchronized void removeGroupPermission(String groupName, String permission) {
        YamlConfiguration yaml = load(groupsFile);
        ConfigurationSection sec = yaml.getConfigurationSection(groupName);
        if (sec == null) return;
        List<String> perms = sec.getStringList("permissions");
        if (perms.remove(permission)) sec.set("permissions", perms);
        save(yaml, groupsFile);
    }

    @Override
    public synchronized void addGroupInheritance(String groupName, String parentGroup) {
        YamlConfiguration yaml = load(groupsFile);
        ConfigurationSection sec = getOrCreate(yaml, groupName);
        List<String> inh = sec.getStringList("inheritance");
        if (!inh.contains(parentGroup)) inh.add(parentGroup);
        sec.set("inheritance", inh);
        save(yaml, groupsFile);
    }

    @Override
    public synchronized void removeGroupInheritance(String groupName, String parentGroup) {
        YamlConfiguration yaml = load(groupsFile);
        ConfigurationSection sec = yaml.getConfigurationSection(groupName);
        if (sec == null) return;
        List<String> inh = sec.getStringList("inheritance");
        if (inh.remove(parentGroup)) sec.set("inheritance", inh);
        save(yaml, groupsFile);
    }

    @Override
    public synchronized void clearGroup(String groupName) {
        YamlConfiguration yaml = load(groupsFile);
        ConfigurationSection sec = yaml.getConfigurationSection(groupName);
        if (sec == null) return;
        sec.set("permissions", new ArrayList<>());
        sec.set("inheritance", new ArrayList<>());
        sec.set("meta", null);
        save(yaml, groupsFile);
    }

    // ── Users ─────────────────────────────────────────────────────────────

    @Override
    public synchronized User loadUser(UUID uuid, String username) {
        File f = userFile(uuid);
        YamlConfiguration yaml = load(f);

        User user = new User(uuid, username);

        if (!f.exists() || yaml.getKeys(false).isEmpty()) {
            // New user — give them the default group and persist immediately.
            user.addGroup("default");
            saveUser(user);
            updateUsernameIndex(uuid, username);
            return user;
        }

        String storedUsername = yaml.getString("username", username);
        user.setUsername(storedUsername);
        user.setPrefix(yaml.getString("prefix", ""));
        user.setSuffix(yaml.getString("suffix", ""));

        for (String g : yaml.getStringList("groups")) user.addGroup(g);
        for (String p : yaml.getStringList("permissions")) user.addPermission(p);

        ConfigurationSection meta = yaml.getConfigurationSection("meta");
        if (meta != null) {
            long now = Instant.now().getEpochSecond();
            for (String key : meta.getKeys(false)) {
                Object val = meta.get(key);
                if (val instanceof ConfigurationSection timedEntry) {
                    long expiry = timedEntry.getLong("expiry", -1);
                    if (expiry > 0 && expiry < now) continue;
                    user.setMeta(key, timedEntry.getString("value", ""));
                } else if (val != null) {
                    user.setMeta(key, val.toString());
                }
            }
        }

        String primary = yaml.getString("primary-group");
        if (primary != null) user.setPrimaryGroup(primary);

        if (user.getGroups().isEmpty()) {
            user.addGroup("default");
        }

        // Keep username index fresh (handles name changes)
        if (username != null && !username.isBlank() && !username.equalsIgnoreCase(storedUsername)) {
            user.setUsername(username);
            saveUser(user);
        }
        updateUsernameIndex(uuid, user.getUsername());

        return user;
    }

    @Override
    public synchronized void saveUser(User user) {
        YamlConfiguration yaml = load(userFile(user.getUuid()));
        yaml.set("username", user.getUsername());
        yaml.set("prefix", user.getPrefix());
        yaml.set("suffix", user.getSuffix());
        yaml.set("groups", new ArrayList<>(user.getGroups()));
        yaml.set("permissions", new ArrayList<>(user.getPermissions()));
        yaml.set("primary-group", user.getPrimaryGroup());

        ConfigurationSection meta = yaml.getConfigurationSection("meta");
        if (meta == null) meta = yaml.createSection("meta");
        for (Map.Entry<String, String> e : user.getMeta().entrySet()) {
            // Only overwrite plain values here; timed meta entries (sections)
            // are managed by saveTimedMeta and left untouched.
            if (!(meta.get(e.getKey()) instanceof ConfigurationSection)) {
                meta.set(e.getKey(), e.getValue());
            }
        }

        save(yaml, userFile(user.getUuid()));
        updateUsernameIndex(user.getUuid(), user.getUsername());
    }

    @Override
    public synchronized void saveUserPrimaryGroup(UUID uuid, String primaryGroup) {
        YamlConfiguration yaml = load(userFile(uuid));
        yaml.set("primary-group", primaryGroup);
        save(yaml, userFile(uuid));
    }

    @Override
    public synchronized void addUserToGroup(UUID uuid, String groupName) {
        YamlConfiguration yaml = load(userFile(uuid));
        groupName = groupName.toLowerCase();
        List<String> groups = yaml.getStringList("groups");
        if (!groups.contains(groupName)) groups.add(groupName);
        yaml.set("groups", groups);
        if (groups.size() == 1) yaml.set("primary-group", groupName);
        save(yaml, userFile(uuid));
    }

    @Override
    public synchronized void removeUserFromGroup(UUID uuid, String groupName) {
        YamlConfiguration yaml = load(userFile(uuid));
        groupName = groupName.toLowerCase();
        List<String> groups = yaml.getStringList("groups");
        if (groups.remove(groupName)) {
            yaml.set("groups", groups);
            if (groupName.equals(yaml.getString("primary-group"))) {
                yaml.set("primary-group", groups.isEmpty() ? "default" : groups.get(0));
            }
            save(yaml, userFile(uuid));
        }
    }

    @Override
    public synchronized void addUserPermission(UUID uuid, String permission) {
        YamlConfiguration yaml = load(userFile(uuid));
        List<String> perms = yaml.getStringList("permissions");
        if (!perms.contains(permission)) perms.add(permission);
        yaml.set("permissions", perms);
        save(yaml, userFile(uuid));
    }

    @Override
    public synchronized void removeUserPermission(UUID uuid, String permission) {
        YamlConfiguration yaml = load(userFile(uuid));
        List<String> perms = yaml.getStringList("permissions");
        if (perms.remove(permission)) {
            yaml.set("permissions", perms);
            save(yaml, userFile(uuid));
        }
    }

    @Override
    public synchronized void clearUser(UUID uuid) {
        YamlConfiguration yaml = load(userFile(uuid));
        yaml.set("permissions", new ArrayList<>());
        yaml.set("groups", List.of("default"));
        yaml.set("primary-group", "default");
        yaml.set("meta", null);
        yaml.set("prefix", "");
        yaml.set("suffix", "");
        save(yaml, userFile(uuid));
    }

    @Override
    public synchronized List<User> loadAllUsers() {
        List<User> result = new ArrayList<>();
        File[] files = usersDir.listFiles((d, n) -> n.endsWith(".yml"));
        if (files == null) return result;
        for (File f : files) {
            String idStr = f.getName().substring(0, f.getName().length() - 4);
            UUID uuid;
            try { uuid = UUID.fromString(idStr); } catch (IllegalArgumentException e) { continue; }
            YamlConfiguration yaml = load(f);
            result.add(loadUser(uuid, yaml.getString("username", idStr)));
        }
        return result;
    }

    // ── Username index (for case-insensitive lookups) ───────────────────────

    private synchronized void updateUsernameIndex(UUID uuid, String username) {
        if (username == null || username.isBlank()) return;
        YamlConfiguration yaml = load(usernamesFile);
        yaml.set(uuid.toString(), username);
        save(yaml, usernamesFile);
    }

    @Override
    public synchronized UUID findUUIDByUsername(String username) {
        if (username == null) return null;
        YamlConfiguration yaml = load(usernamesFile);
        for (String key : yaml.getKeys(false)) {
            String stored = yaml.getString(key);
            if (stored != null && stored.equalsIgnoreCase(username)) {
                try {
                    return UUID.fromString(key);
                } catch (IllegalArgumentException ignored) {}
            }
        }
        return null;
    }

    // ── Meta ──────────────────────────────────────────────────────────────

    @Override
    public synchronized Map<String, String> loadMeta(String target, boolean isGroup) {
        Map<String, String> result = new LinkedHashMap<>();
        ConfigurationSection meta = metaSection(target, isGroup);
        if (meta == null) return result;
        long now = Instant.now().getEpochSecond();
        for (String key : meta.getKeys(false)) {
            Object val = meta.get(key);
            if (val instanceof ConfigurationSection entry) {
                long expiry = entry.getLong("expiry", -1);
                if (expiry > 0 && expiry < now) continue; // expired, skip
                result.put(key, entry.getString("value", ""));
            } else if (val != null) {
                result.put(key, val.toString());
            }
        }
        return result;
    }

    /** Returns the "meta" section for a target, or null if it doesn't exist (does not create). */
    private ConfigurationSection metaSection(String target, boolean isGroup) {
        if (isGroup) {
            YamlConfiguration yaml = load(groupsFile);
            ConfigurationSection sec = yaml.getConfigurationSection(target);
            return sec == null ? null : sec.getConfigurationSection("meta");
        } else {
            YamlConfiguration yaml = load(userFile(safeUuid(target)));
            return yaml.getConfigurationSection("meta");
        }
    }

    @Override
    public synchronized void saveMeta(String target, boolean isGroup, String key, String value) {
        if (isGroup) {
            YamlConfiguration yaml = load(groupsFile);
            ConfigurationSection sec = getOrCreate(yaml, target);
            ConfigurationSection meta = sec.getConfigurationSection("meta");
            if (meta == null) meta = sec.createSection("meta");
            meta.set(key, value);
            save(yaml, groupsFile);
        } else {
            UUID uuid = safeUuid(target);
            YamlConfiguration yaml = load(userFile(uuid));
            ConfigurationSection meta = yaml.getConfigurationSection("meta");
            if (meta == null) meta = yaml.createSection("meta");
            meta.set(key, value);
            save(yaml, userFile(uuid));
        }
    }

    @Override
    public synchronized void deleteMeta(String target, boolean isGroup, String key) {
        if (isGroup) {
            YamlConfiguration yaml = load(groupsFile);
            ConfigurationSection sec = yaml.getConfigurationSection(target);
            if (sec == null) return;
            ConfigurationSection meta = sec.getConfigurationSection("meta");
            if (meta != null) { meta.set(key, null); save(yaml, groupsFile); }
        } else {
            UUID uuid = safeUuid(target);
            YamlConfiguration yaml = load(userFile(uuid));
            ConfigurationSection meta = yaml.getConfigurationSection("meta");
            if (meta != null) { meta.set(key, null); save(yaml, userFile(uuid)); }
        }
    }

    @Override
    public synchronized void deleteAllMeta(String target, boolean isGroup) {
        if (isGroup) {
            YamlConfiguration yaml = load(groupsFile);
            ConfigurationSection sec = yaml.getConfigurationSection(target);
            if (sec != null) { sec.set("meta", null); save(yaml, groupsFile); }
        } else {
            UUID uuid = safeUuid(target);
            YamlConfiguration yaml = load(userFile(uuid));
            yaml.set("meta", null);
            save(yaml, userFile(uuid));
        }
    }

    @Override
    public synchronized void saveTimedMeta(String target, boolean isGroup, String key, String value, long expiryEpochSecond) {
        YamlConfiguration yaml;
        ConfigurationSection meta;
        File file;
        if (isGroup) {
            yaml = load(groupsFile);
            ConfigurationSection sec = getOrCreate(yaml, target);
            meta = sec.getConfigurationSection("meta");
            if (meta == null) meta = sec.createSection("meta");
            file = groupsFile;
        } else {
            UUID uuid = safeUuid(target);
            yaml = load(userFile(uuid));
            meta = yaml.getConfigurationSection("meta");
            if (meta == null) meta = yaml.createSection("meta");
            file = userFile(uuid);
        }
        // Remove any existing plain/timed value for this key before creating the section
        meta.set(key, null);
        ConfigurationSection entry = meta.createSection(key);
        entry.set("value", value);
        entry.set("expiry", expiryEpochSecond);
        save(yaml, file);
    }

    // ── Search ────────────────────────────────────────────────────────────

    @Override
    public synchronized List<String> searchPermission(String permission) {
        List<String> result = new ArrayList<>();

        YamlConfiguration groups = load(groupsFile);
        for (String name : groups.getKeys(false)) {
            ConfigurationSection sec = groups.getConfigurationSection(name);
            if (sec != null && sec.getStringList("permissions").contains(permission)) {
                result.add("group:" + name);
            }
        }

        File[] files = usersDir.listFiles((d, n) -> n.endsWith(".yml"));
        if (files != null) {
            for (File f : files) {
                YamlConfiguration yaml = load(f);
                if (yaml.getStringList("permissions").contains(permission)) {
                    result.add(f.getName().substring(0, f.getName().length() - 4));
                }
            }
        }
        return result;
    }

    // ── Timed Permissions ─────────────────────────────────────────────────

    @Override
    public synchronized List<TimedPermission> loadActiveTimedPermissions() {
        List<TimedPermission> result = new ArrayList<>();
        YamlConfiguration yaml = load(timedFile);
        long now = Instant.now().getEpochSecond();
        for (String id : yaml.getKeys(false)) {
            ConfigurationSection sec = yaml.getConfigurationSection(id);
            if (sec == null) continue;
            long expiry = sec.getLong("expiry");
            if (expiry <= now) continue;
            result.add(new TimedPermission(
                    sec.getString("target"),
                    sec.getBoolean("is-group"),
                    sec.getString("permission"),
                    Instant.ofEpochSecond(expiry)));
        }
        return result;
    }

    @Override
    public synchronized void saveTimedPermission(String target, boolean isGroup, String permission, long expiryEpochSecond) {
        YamlConfiguration yaml = load(timedFile);
        String id = sanitizeKey(target + ":" + isGroup + ":" + permission);
        ConfigurationSection sec = getOrCreate(yaml, id);
        sec.set("target", target);
        sec.set("is-group", isGroup);
        sec.set("permission", permission);
        sec.set("expiry", expiryEpochSecond);
        save(yaml, timedFile);
    }

    @Override
    public synchronized void deleteExpiredTimedPermissions(long nowEpochSecond) {
        YamlConfiguration yaml = load(timedFile);
        boolean changed = false;
        for (String id : new ArrayList<>(yaml.getKeys(false))) {
            ConfigurationSection sec = yaml.getConfigurationSection(id);
            if (sec != null && sec.getLong("expiry") <= nowEpochSecond) {
                yaml.set(id, null);
                changed = true;
            }
        }
        if (changed) save(yaml, timedFile);
    }

    @Override
    public synchronized void deleteTimedPermission(String target, String permission) {
        YamlConfiguration yaml = load(timedFile);
        boolean changed = false;
        for (String id : new ArrayList<>(yaml.getKeys(false))) {
            ConfigurationSection sec = yaml.getConfigurationSection(id);
            if (sec != null && target.equals(sec.getString("target")) && permission.equals(sec.getString("permission"))) {
                yaml.set(id, null);
                changed = true;
            }
        }
        if (changed) save(yaml, timedFile);
    }

    // ── Context Permissions ──────────────────────────────────────────────

    @Override
    public synchronized List<ContextRow> loadAllContextPermissions() {
        List<ContextRow> result = new ArrayList<>();
        YamlConfiguration yaml = load(contextFile);
        for (String id : yaml.getKeys(false)) {
            ConfigurationSection sec = yaml.getConfigurationSection(id);
            if (sec == null) continue;
            String target = sec.getString("target");
            boolean isGroup = sec.getBoolean("is-group");
            String permission = sec.getString("permission");
            boolean granted = sec.getBoolean("granted", true);

            ContextSet.Builder builder = ContextSet.builder();
            ConfigurationSection ctx = sec.getConfigurationSection("context");
            if (ctx != null) {
                for (String key : ctx.getKeys(false)) {
                    builder.put(key, ctx.getString(key));
                }
            }
            ContextualPermission cp = new ContextualPermission(permission, builder.build(), granted);
            result.add(new ContextRow(target, isGroup, cp));
        }
        return result;
    }

    @Override
    public synchronized void saveContextPermission(String target, boolean isGroup, String permission, Context context, boolean granted) {
        ContextSet cs = (context == null || context.isGlobal())
                ? ContextSet.global()
                : ContextSet.builder().put(context).build();
        saveContextPermission(target, isGroup, permission, cs, granted);
    }

    @Override
    public synchronized void saveContextPermission(String target, boolean isGroup, String permission, ContextSet requiredCtx, boolean granted) {
        YamlConfiguration yaml = load(contextFile);
        String id = sanitizeKey(target + ":" + permission + ":" + requiredCtx.toString());
        ConfigurationSection sec = getOrCreate(yaml, id);
        sec.set("target", target);
        sec.set("is-group", isGroup);
        sec.set("permission", permission);
        sec.set("granted", granted);
        sec.set("context", null);
        if (!requiredCtx.isEmpty()) {
            ConfigurationSection ctx = sec.createSection("context");
            requiredCtx.asMap().forEach(ctx::set);
        }
        save(yaml, contextFile);
    }

    @Override
    public synchronized void deleteContextPermission(String target, String permission, Context context) {
        ContextSet cs = (context == null || context.isGlobal())
                ? ContextSet.global()
                : ContextSet.builder().put(context).build();
        deleteContextPermission(target, permission, cs);
    }

    @Override
    public synchronized void deleteContextPermission(String target, String permission, ContextSet requiredCtx) {
        YamlConfiguration yaml = load(contextFile);
        String id = sanitizeKey(target + ":" + permission + ":" + requiredCtx.toString());
        if (yaml.contains(id)) {
            yaml.set(id, null);
            save(yaml, contextFile);
        }
    }

    // ── Tracks ────────────────────────────────────────────────────────────

    @Override
    public synchronized List<Track> loadAllTracks() {
        List<Track> result = new ArrayList<>();
        YamlConfiguration yaml = load(tracksFile);
        for (String name : yaml.getKeys(false)) {
            Track track = new Track(name);
            for (String g : yaml.getStringList(name)) track.addGroup(g);
            result.add(track);
        }
        return result;
    }

    @Override
    public synchronized void saveTrack(Track track) {
        YamlConfiguration yaml = load(tracksFile);
        yaml.set(track.getName(), new ArrayList<>(track.getGroups()));
        save(yaml, tracksFile);
    }

    @Override
    public synchronized void deleteTrack(String trackName) {
        YamlConfiguration yaml = load(tracksFile);
        yaml.set(trackName, null);
        save(yaml, tracksFile);
    }

    // ── Logging ───────────────────────────────────────────────────────────

    @Override
    public synchronized void saveLog(long timestamp, String actor, String action, String target, String detail) {
        YamlConfiguration yaml = load(logsFile);
        long nextId = yaml.getLong("next-id", 1);
        ConfigurationSection sec = yaml.createSection("entries." + nextId);
        sec.set("timestamp", timestamp);
        sec.set("actor", actor);
        sec.set("action", action);
        sec.set("target", target);
        sec.set("detail", detail);
        yaml.set("next-id", nextId + 1);

        // Prune oldest entries beyond MAX_LOG_ENTRIES
        ConfigurationSection entries = yaml.getConfigurationSection("entries");
        if (entries != null) {
            Set<String> keys = entries.getKeys(false);
            if (keys.size() > MAX_LOG_ENTRIES) {
                keys.stream()
                        .map(Long::parseLong)
                        .sorted()
                        .limit(keys.size() - MAX_LOG_ENTRIES)
                        .forEach(id -> entries.set(String.valueOf(id), null));
            }
        }
        save(yaml, logsFile);
    }

    @Override
    public synchronized List<LogEntry> loadRecentLogs(int limit) {
        return loadLogsFiltered(null, null, limit);
    }

    @Override
    public synchronized List<LogEntry> loadLogsByTarget(String target, int limit) {
        return loadLogsFiltered(target, null, limit);
    }

    @Override
    public synchronized List<LogEntry> loadLogsByActor(String actor, int limit) {
        return loadLogsFiltered(null, actor, limit);
    }

    private List<LogEntry> loadLogsFiltered(String target, String actor, int limit) {
        YamlConfiguration yaml = load(logsFile);
        ConfigurationSection entries = yaml.getConfigurationSection("entries");
        List<LogEntry> result = new ArrayList<>();
        if (entries == null) return result;

        List<Long> ids = entries.getKeys(false).stream()
                .map(Long::parseLong)
                .sorted(Comparator.reverseOrder())
                .toList();

        for (Long id : ids) {
            if (result.size() >= limit) break;
            ConfigurationSection sec = entries.getConfigurationSection(String.valueOf(id));
            if (sec == null) continue;
            String t = sec.getString("target");
            String a = sec.getString("actor");
            if (target != null && !target.equals(t)) continue;
            if (actor != null && !actor.equals(a)) continue;

            LogEntry.Action action;
            try {
                action = LogEntry.Action.valueOf(sec.getString("action"));
            } catch (Exception e) {
                continue;
            }
            result.add(new LogEntry(id, Instant.ofEpochSecond(sec.getLong("timestamp")),
                    a, action, t, sec.getString("detail")));
        }
        return result;
    }

    @Override
    public synchronized void deleteLogsOlderThan(long epochSecond) {
        YamlConfiguration yaml = load(logsFile);
        ConfigurationSection entries = yaml.getConfigurationSection("entries");
        if (entries == null) return;
        boolean changed = false;
        for (String key : new ArrayList<>(entries.getKeys(false))) {
            ConfigurationSection sec = entries.getConfigurationSection(key);
            if (sec != null && sec.getLong("timestamp") < epochSecond) {
                entries.set(key, null);
                changed = true;
            }
        }
        if (changed) save(yaml, logsFile);
    }

    // ── Bulk operations ───────────────────────────────────────────────────

    @Override
    public synchronized int bulkAddPermissionToUsers(String permission) {
        File[] files = usersDir.listFiles((d, n) -> n.endsWith(".yml"));
        if (files == null) return 0;
        int count = 0;
        for (File f : files) {
            YamlConfiguration yaml = load(f);
            List<String> perms = yaml.getStringList("permissions");
            if (!perms.contains(permission)) {
                perms.add(permission);
                yaml.set("permissions", perms);
                save(yaml, f);
                count++;
            }
        }
        return count;
    }

    @Override
    public synchronized int bulkRemovePermissionFromUsers(String permission) {
        File[] files = usersDir.listFiles((d, n) -> n.endsWith(".yml"));
        if (files == null) return 0;
        int count = 0;
        for (File f : files) {
            YamlConfiguration yaml = load(f);
            List<String> perms = yaml.getStringList("permissions");
            if (perms.remove(permission)) {
                yaml.set("permissions", perms);
                save(yaml, f);
                count++;
            }
        }
        return count;
    }

    @Override
    public synchronized int bulkAddGroupToUsers(String groupName) {
        File[] files = usersDir.listFiles((d, n) -> n.endsWith(".yml"));
        if (files == null) return 0;
        int count = 0;
        groupName = groupName.toLowerCase();
        for (File f : files) {
            YamlConfiguration yaml = load(f);
            List<String> groups = yaml.getStringList("groups");
            if (!groups.contains(groupName)) {
                groups.add(groupName);
                yaml.set("groups", groups);
                save(yaml, f);
                count++;
            }
        }
        return count;
    }

    @Override
    public synchronized int bulkRemoveGroupFromUsers(String groupName) {
        File[] files = usersDir.listFiles((d, n) -> n.endsWith(".yml"));
        if (files == null) return 0;
        int count = 0;
        groupName = groupName.toLowerCase();
        for (File f : files) {
            YamlConfiguration yaml = load(f);
            List<String> groups = yaml.getStringList("groups");
            if (groups.remove(groupName)) {
                yaml.set("groups", groups);
                if (groupName.equals(yaml.getString("primary-group"))) {
                    yaml.set("primary-group", groups.isEmpty() ? "default" : groups.get(0));
                }
                save(yaml, f);
                count++;
            }
        }
        return count;
    }

    // No SQL connection available — relies on the default
    // StorageBackend.getConnection() throwing UnsupportedOperationException.

    // ─── Timed Group Memberships ──────────────────────────────────────────────

    private File timedGroupsDir() {
        File dir = new File(root, "timed-groups");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    @Override
    public synchronized List<TimedGroup> loadActiveTimedGroups() {
        List<TimedGroup> result = new ArrayList<>();
        File dir = timedGroupsDir();
        long now = java.time.Instant.now().getEpochSecond();
        File[] files = dir.listFiles(f -> f.getName().endsWith(".yml"));
        if (files == null) return result;
        for (File f : files) {
            YamlConfiguration yml = load(f);
            String userUuid = f.getName().replace(".yml", "");
            ConfigurationSection sec = yml.getConfigurationSection("timed-groups");
            if (sec == null) continue;
            for (String groupName : sec.getKeys(false)) {
                long expiry = sec.getLong(groupName + ".expiry", 0);
                if (expiry > now) {
                    result.add(new TimedGroup(userUuid, groupName,
                            java.time.Instant.ofEpochSecond(expiry)));
                }
            }
        }
        return result;
    }

    @Override
    public synchronized void saveTimedGroup(String userUuid, String groupName, long expiryEpochSecond) {
        File dir = timedGroupsDir();
        File f = new File(dir, userUuid + ".yml");
        YamlConfiguration yml = f.exists() ? load(f) : new YamlConfiguration();
        yml.set("timed-groups." + groupName.toLowerCase() + ".expiry", expiryEpochSecond);
        save(yml, f);
    }

    @Override
    public synchronized void deleteTimedGroup(String userUuid, String groupName) {
        File f = new File(timedGroupsDir(), userUuid + ".yml");
        if (!f.exists()) return;
        YamlConfiguration yml = load(f);
        yml.set("timed-groups." + groupName.toLowerCase(), null);
        save(yml, f);
    }

    @Override
    public synchronized void deleteExpiredTimedGroups(long nowEpochSecond) {
        File dir = timedGroupsDir();
        File[] files = dir.listFiles(f -> f.getName().endsWith(".yml"));
        if (files == null) return;
        for (File f : files) {
            YamlConfiguration yml = load(f);
            ConfigurationSection sec = yml.getConfigurationSection("timed-groups");
            if (sec == null) continue;
            boolean changed = false;
            for (String key : new ArrayList<>(sec.getKeys(false))) {
                if (sec.getLong(key + ".expiry", 0) <= nowEpochSecond) {
                    yml.set("timed-groups." + key, null);
                    changed = true;
                }
            }
            if (changed) save(yml, f);
        }
    }

}