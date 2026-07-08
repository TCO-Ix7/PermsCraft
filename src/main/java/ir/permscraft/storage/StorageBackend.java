package ir.permscraft.storage;

import ir.permscraft.context.Context;
import ir.permscraft.context.ContextSet;
import ir.permscraft.context.ContextualPermission;
import ir.permscraft.models.Group;
import ir.permscraft.models.TimedGroup;
import ir.permscraft.models.TimedPermission;
import ir.permscraft.models.User;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Abstraction layer for all storage backends.
 * Implementations: MySQL, PostgreSQL, SQLite, H2, MongoDB
 */
public interface StorageBackend {

    /** Initialize the backend (connect, create tables/collections, etc.) */
    boolean init();

    /** Gracefully shut down the backend. */
    void close();

    // ─── Groups ─────────────────────────────────────────────────────────────

    List<Group> loadAllGroups();
    void saveGroup(Group group);
    void deleteGroup(String groupName);
    void addGroupPermission(String groupName, String permission);
    void removeGroupPermission(String groupName, String permission);
    void addGroupInheritance(String groupName, String parentGroup);
    void removeGroupInheritance(String groupName, String parentGroup);

    // ─── Users ──────────────────────────────────────────────────────────────

    User loadUser(UUID uuid, String username);
    void saveUser(User user);
    /** FIX Bug #1: Persist the primary group explicitly. */
    void saveUserPrimaryGroup(UUID uuid, String primaryGroup);
    void addUserToGroup(UUID uuid, String groupName);
    void removeUserFromGroup(UUID uuid, String groupName);
    void addUserPermission(UUID uuid, String permission);
    void removeUserPermission(UUID uuid, String permission);

    /** Wipe ALL permissions, groups, and meta for a user (keeps the user row). */
    void clearUser(UUID uuid);

    /** Wipe ALL permissions and parent-groups for a group (keeps the group row). */
    void clearGroup(String groupName);

    // ─── Meta ────────────────────────────────────────────────────────────────

    /** Load all meta key/value pairs for a target (uuid string or group name). */
    Map<String, String> loadMeta(String target, boolean isGroup);

    void saveMeta(String target, boolean isGroup, String key, String value);
    void deleteMeta(String target, boolean isGroup, String key);
    void deleteAllMeta(String target, boolean isGroup);

    /** Save a timed meta entry (expiry = epoch seconds). */
    void saveTimedMeta(String target, boolean isGroup, String key, String value, long expiryEpochSecond);

    // ─── Search ──────────────────────────────────────────────────────────────

    /**
     * Return all targets (user UUIDs and group names) that hold the given
     * permission as a direct node (not inherited, not timed).
     * Result entries are either a UUID string (user) or a group name prefixed with "group:".
     */
    List<String> searchPermission(String permission);

    // ─── Sync ────────────────────────────────────────────────────────────────

    /** Reload all groups from storage into memory (used by /pc sync). */
    // No extra method needed — callers use loadAllGroups() directly.

    /**
     * FIX (case-insensitive username lookup): implementations must query with
     * a case-insensitive comparison (LOWER() in SQL, regex in MongoDB) so that
     * "Steve" and "steve" always resolve to the same UUID.
     */
    UUID findUUIDByUsername(String username);

    // ─── Timed Permissions ──────────────────────────────────────────────────

    List<TimedPermission> loadActiveTimedPermissions();
    void saveTimedPermission(String target, boolean isGroup, String permission, long expiryEpochSecond);
    void deleteExpiredTimedPermissions(long nowEpochSecond);
    void deleteTimedPermission(String target, String permission);

    // ─── Timed Group Memberships ────────────────────────────────────────────────
    // Separate from timed permissions — these temporarily add a user to a group.

    /**
     * Load all non-expired timed group memberships from storage.
     * Default: returns empty list — backends override.
     */
    default List<ir.permscraft.models.TimedGroup> loadActiveTimedGroups() {
        return java.util.Collections.emptyList();
    }

    /**
     * Persist a timed group membership.
     * @param userUuid        UUID string of the player
     * @param groupName       group to temporarily assign
     * @param expiryEpochSecond absolute expiry (Unix epoch seconds)
     */
    default void saveTimedGroup(String userUuid, String groupName, long expiryEpochSecond) {}

    /** Remove a single timed group membership immediately. */
    default void deleteTimedGroup(String userUuid, String groupName) {}

    /** Bulk-delete all timed group rows whose expiry <= nowEpochSecond. */
    default void deleteExpiredTimedGroups(long nowEpochSecond) {}

    // ─── Context Permissions ─────────────────────────────────────────────────

    /**
     * FIX: return type changed from List<ContextualPermission[]> (the old
     * ContextualPermission[] hack) to List<ContextRow> — a proper typed DTO.
     */
    List<ContextRow> loadAllContextPermissions();
    void saveContextPermission(String target, boolean isGroup, String permission, Context context, boolean granted);
    void saveContextPermission(String target, boolean isGroup, String permission, ContextSet requiredCtx, boolean granted);
    void deleteContextPermission(String target, String permission, Context context);
    void deleteContextPermission(String target, String permission, ContextSet requiredCtx);

    // ─── Tracks ─────────────────────────────────────────────────────────────
    // These methods are now part of the interface so all backends (including
    // MongoDB) must implement them. SQL backends call through to SqlStorage;
    // MongoDB has its own document-based implementation.

    List<ir.permscraft.models.Track> loadAllTracks();
    void saveTrack(ir.permscraft.models.Track track);
    void deleteTrack(String trackName);

    // ─── Logging ────────────────────────────────────────────────────────────
    // Moved to the interface so MongoDB can persist logs too.

    void saveLog(long timestamp, String actor, String action, String target, String detail);
    List<ir.permscraft.logging.LogEntry> loadRecentLogs(int limit);
    List<ir.permscraft.logging.LogEntry> loadLogsByTarget(String target, int limit);
    List<ir.permscraft.logging.LogEntry> loadLogsByActor(String actor, int limit);
    void deleteLogsOlderThan(long epochSecond);


    /**
     * Add a permission node to every user record in storage.
     * @return number of users updated (best-effort; -1 if unknown)
     */
    default int bulkAddPermissionToUsers(String permission) { return -1; }

    /**
     * Remove a permission node from every user record in storage.
     * @return number of users affected
     */
    default int bulkRemovePermissionFromUsers(String permission) { return -1; }

    /**
     * Add a group to every user record in storage.
     * @return number of users updated
     */
    default int bulkAddGroupToUsers(String groupName) { return -1; }

    /**
     * Remove a group from every user record in storage.
     * @return number of users affected
     */
    default int bulkRemoveGroupFromUsers(String groupName) { return -1; }

    // ─── REST API extensions ─────────────────────────────────────────────────
    // These default methods add REST-API-required functionality without
    // breaking existing backend implementations. SQL backends delegate to
    // loadRecentLogs(); MongoDB uses its own implementation.

    /**
     * Load ALL user records from storage (used by backup export).
     * Default: returns empty list — SQL backends override this.
     */
    default List<User> loadAllUsers() { return java.util.Collections.emptyList(); }

    /**
     * Lookup UUID by username (case-insensitive). Delegates to findUUIDByUsername.
     */
    default UUID getUuidByName(String name) { return findUUIDByUsername(name); }

    /**
     * Paginated, filtered log query for the REST API.
     * Default implementation: load all recent logs (up to MAX_LOG_ENTRIES cap) and
     * apply filters in-memory. SQL backends should override this with a proper
     * WHERE + LIMIT + OFFSET query.
     *
     * FIX (Bug #7): previously loaded only (offset + limit) rows before filtering,
     * which discarded entries that would have matched but fell outside that window.
     * We now load all available logs once and apply every filter before paginating.
     */
    default List<ir.permscraft.logging.LogEntry> queryLogs(ir.permscraft.storage.LogFilter filter) {
        // Load all logs (bounded by the backend's internal cap, e.g. 5000 for YAML).
        List<ir.permscraft.logging.LogEntry> all = loadRecentLogs(Integer.MAX_VALUE);
        return all.stream()
                .filter(e -> filter.actor()  == null || e.getActor().equalsIgnoreCase(filter.actor()))
                .filter(e -> filter.target() == null || e.getTarget().equalsIgnoreCase(filter.target()))
                .filter(e -> filter.action() == null || e.getAction().name().equalsIgnoreCase(filter.action()))
                .filter(e -> filter.from()   == null || e.getTimestamp().getEpochSecond() >= filter.from())
                .filter(e -> filter.to()     == null || e.getTimestamp().getEpochSecond() <= filter.to())
                .skip(filter.offset())
                .limit(filter.limit())
                .collect(java.util.stream.Collectors.toList());
    }

    /** Count total log entries matching a filter (for pagination metadata). */
    default long countLogs(ir.permscraft.storage.LogFilter filter) {
        return queryLogs(new ir.permscraft.storage.LogFilter(
                filter.actor(), filter.target(), filter.action(),
                filter.from(), filter.to(), Integer.MAX_VALUE, 0)).size();
    }

    /**
     * Distinct action type names present in the log.
     *
     * FIX (Bug #12): the previous default returned all enum values regardless of
     * whether any log entry of that action actually existed.  The corrected default
     * queries the in-memory log and returns only action names that appear at least
     * once.  SQL backends should override this with a SELECT DISTINCT query.
     */
    default List<String> getDistinctLogActions() {
        return loadRecentLogs(Integer.MAX_VALUE).stream()
                .map(e -> e.getAction().name())
                .distinct()
                .sorted()
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * Purge log entries older than {@code days} days.
     *
     * FIX (Bug #13): the previous default always returned -1, giving clients no
     * feedback on how many rows were deleted.  The corrected default counts entries
     * before deletion and returns the exact number.  SQL backends should override
     * this with a DELETE … WHERE and return rowCount() directly.
     *
     * @return number of entries deleted
     */
    default int purgeLogs(int days) {
        long cutoff = java.time.Instant.now()
                .minusSeconds((long) days * 86400).getEpochSecond();
        // Count how many entries will be removed before we delete them.
        long before = countLogs(new ir.permscraft.storage.LogFilter(
                null, null, null, null, cutoff, Integer.MAX_VALUE, 0));
        deleteLogsOlderThan(cutoff);
        return (int) Math.min(before, Integer.MAX_VALUE);
    }

    /**
     * Raw context permissions export for backup.
     * Default: delegates to loadAllContextPermissions() and converts.
     *
     * FIX (Bug #10): for large servers with thousands of context rows this
     * materialises the whole table into RAM and risks OOM.  Callers that can
     * process rows one-at-a-time should use {@link #streamContextPermissions}
     * instead.  SQL and MongoDB backends override that method with a true
     * cursor/iterator so only one row is resident at a time.
     *
     * This method is kept for small-to-medium deployments and non-SQL backends
     * that have no other option.
     */
    default List<java.util.Map<String, Object>> loadAllContextPermissionsRaw() {
        return loadAllContextPermissions().stream().map(row -> {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("target",     row.target());
            m.put("isGroup",    row.isGroup());
            ir.permscraft.context.ContextualPermission cp = row.permission();
            m.put("permission", cp.getPermission());
            m.put("granted",    cp.getValue());
            m.put("context",    cp.getRequiredContext().asMap());
            return m;
        }).collect(java.util.stream.Collectors.toList());
    }

    /**
     * FIX (Bug #10): Stream context permissions row-by-row to the supplied
     * consumer without materialising the whole table.  SQL and MongoDB backends
     * override this with a cursor-based implementation.  The default falls back
     * to {@link #loadAllContextPermissionsRaw()} (in-memory) so existing
     * non-SQL backends keep working unchanged.
     *
     * @param consumer called once per row; must not escape the row's Map
     *                 reference (it may be reused across calls by some impls).
     */
    default void streamContextPermissions(java.util.function.Consumer<java.util.Map<String, Object>> consumer) {
        loadAllContextPermissionsRaw().forEach(consumer);
    }

    // ─── SQL helper (only for SQL-based backends; throws for MongoDB) ────────

    default Connection getConnection() throws SQLException {
        throw new UnsupportedOperationException("This backend does not expose raw SQL connections.");
    }

    // ─── Primary group persistence (FIX Bug #1) ─────────────────────────────

    /**
     * Persist the primary group selection for a user.
     * Default no-op — SQL backends override via SqlStorage.
     */
}
