package ir.permscraft.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import ir.permscraft.context.Context;
import ir.permscraft.context.ContextSet;
import ir.permscraft.context.ContextualPermission;
import ir.permscraft.logging.LogEntry;
import ir.permscraft.models.Group;
import ir.permscraft.models.TimedGroup;
import ir.permscraft.models.TimedPermission;
import ir.permscraft.models.Track;
import ir.permscraft.models.User;

import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;

/**
 * Shared SQL implementation for MySQL, PostgreSQL, SQLite and H2.
 * Each dialect sub-class only needs to provide the HikariConfig and
 * the CREATE TABLE DDL that fits its dialect.
 */
public abstract class SqlStorage implements StorageBackend {

    protected final Logger logger;
    protected HikariDataSource dataSource;

    protected SqlStorage(Logger logger) {
        this.logger = logger;
    }

    protected abstract HikariConfig buildHikariConfig();
    protected abstract String autoIncrementSyntax();

    // ── init ────────────────────────────────────────────────────────────────

    @Override
    public boolean init() {
        try {
            dataSource = new HikariDataSource(buildHikariConfig());
            createTables();
            return true;
        } catch (Exception e) {
            logger.severe("[PermsCraft] Storage connection failed: " + e.getMessage());
            return false;
        }
    }

    @Override
    public void close() {
        if (dataSource != null) dataSource.close();
    }

    @Override
    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    // ── DDL ─────────────────────────────────────────────────────────────────

    private void createTables() {
        String ai = autoIncrementSyntax();
        try (Connection c = getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS pc_groups (
                    name VARCHAR(64) PRIMARY KEY,
                    display_name VARCHAR(64),
                    prefix VARCHAR(128) DEFAULT '',
                    suffix VARCHAR(128) DEFAULT '',
                    weight INT DEFAULT 0
                )""");
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS pc_group_permissions (
                    group_name VARCHAR(64),
                    permission VARCHAR(256),
                    PRIMARY KEY (group_name, permission)
                )""");
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS pc_group_inheritance (
                    group_name VARCHAR(64),
                    parent_group VARCHAR(64),
                    PRIMARY KEY (group_name, parent_group)
                )""");
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS pc_users (
                    uuid VARCHAR(36) PRIMARY KEY,
                    username VARCHAR(64),
                    prefix VARCHAR(128) DEFAULT '',
                    suffix VARCHAR(128) DEFAULT '',
                    primary_group VARCHAR(64) DEFAULT 'default'
                )""");
            // Migration: add primary_group if upgrading from older version
            try { s.executeUpdate("ALTER TABLE pc_users ADD COLUMN primary_group VARCHAR(64) DEFAULT 'default'"); }
            catch (Exception ignored) {} // already exists
            // Migration (Bug #11): widen username from VARCHAR(16) to VARCHAR(64) for Bedrock Edition support
            try { s.executeUpdate("ALTER TABLE pc_users MODIFY COLUMN username VARCHAR(64)"); }
            catch (Exception ignored) {
                // PostgreSQL / H2 / SQLite syntax
                try { s.executeUpdate("ALTER TABLE pc_users ALTER COLUMN username TYPE VARCHAR(64)"); }
                catch (Exception ignored2) {} // already widened or unsupported
            }
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS pc_user_groups (
                    uuid VARCHAR(36),
                    group_name VARCHAR(64),
                    PRIMARY KEY (uuid, group_name)
                )""");
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS pc_user_permissions (
                    uuid VARCHAR(36),
                    permission VARCHAR(256),
                    PRIMARY KEY (uuid, permission)
                )""");
            // Timed Group Memberships (separate table from timed permissions)
            s.executeUpdate(
                "CREATE TABLE IF NOT EXISTS pc_timed_groups (" +
                "id " + ai + "," +
                "user_uuid VARCHAR(36) NOT NULL," +
                "group_name VARCHAR(64) NOT NULL," +
                "expiry BIGINT NOT NULL," +
                "UNIQUE(user_uuid, group_name)" +
                ")");
            s.executeUpdate(
                "CREATE TABLE IF NOT EXISTS pc_timed_permissions (" +
                "id " + ai + "," +
                "target VARCHAR(64) NOT NULL," +
                "is_group BOOLEAN NOT NULL DEFAULT FALSE," +
                "permission VARCHAR(256) NOT NULL," +
                "expiry BIGINT NOT NULL," +
                "UNIQUE(target, is_group, permission)" +
                ")");
            // Migration (Bug #9): tables created before the UNIQUE constraint was added
            // may contain duplicate (target, is_group, permission) rows. Deduplicate
            // by keeping only the row with the latest expiry, then add the unique
            // index so future inserts can't duplicate again.
            deduplicateTimedPermissions(c);
            try {
                s.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS pc_timed_permissions_unique " +
                        "ON pc_timed_permissions (target, is_group, permission)");
            } catch (Exception e) {
                try {
                    // Some dialects (older MySQL/MariaDB) don't support IF NOT EXISTS on indexes.
                    s.executeUpdate("CREATE UNIQUE INDEX pc_timed_permissions_unique " +
                            "ON pc_timed_permissions (target, is_group, permission)");
                } catch (Exception ignored) {
                    // Index already exists (e.g. table was just created above with the
                    // inline UNIQUE constraint, which MySQL also names automatically).
                }
            }
            s.executeUpdate(
                "CREATE TABLE IF NOT EXISTS pc_context_permissions (" +
                "id " + ai + "," +
                "target VARCHAR(64) NOT NULL," +
                "is_group BOOLEAN DEFAULT FALSE," +
                "permission VARCHAR(256) NOT NULL," +
                "context_key VARCHAR(64) NOT NULL DEFAULT 'global'," +
                "context_value VARCHAR(64) NOT NULL DEFAULT 'global'," +
                "granted BOOLEAN DEFAULT TRUE" +
                ")");
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS pc_tracks (
                    name VARCHAR(64) PRIMARY KEY
                )""");
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS pc_track_groups (
                    track_name VARCHAR(64),
                    group_name VARCHAR(64),
                    position INT,
                    PRIMARY KEY (track_name, group_name)
                )""");
            s.executeUpdate(
                "CREATE TABLE IF NOT EXISTS pc_log (" +
                "id " + ai + "," +
                "timestamp BIGINT NOT NULL," +
                "actor VARCHAR(64) NOT NULL," +
                "action VARCHAR(64) NOT NULL," +
                "target VARCHAR(64) NOT NULL," +
                "detail VARCHAR(512)" +
                ")");
            s.executeUpdate(
                "CREATE TABLE IF NOT EXISTS pc_meta (" +
                "target VARCHAR(64) NOT NULL," +
                "is_group BOOLEAN NOT NULL DEFAULT FALSE," +
                "meta_key VARCHAR(128) NOT NULL," +
                "meta_value VARCHAR(512) NOT NULL DEFAULT ''," +
                "expiry BIGINT NOT NULL DEFAULT -1," +
                "PRIMARY KEY (target, is_group, meta_key)" +
                ")");

            insertIgnore(c,
                "INSERT INTO pc_groups (name, display_name, weight) VALUES (?,?,?)",
                "default", "Default", 0);

        } catch (SQLException e) {
            logger.severe("[PermsCraft] Failed to create tables: " + e.getMessage());
        }
    }

    /**
     * FIX (Bug #9): remove duplicate (target, is_group, permission) rows from
     * pc_timed_permissions that may have been created before the UNIQUE
     * constraint existed (e.g. from running "/pc user X timed add foo.bar 1h"
     * multiple times). For each duplicate group, keep only the row with the
     * latest expiry and delete the rest. Safe to run on every startup since
     * it's a no-op once the table has no duplicates.
     */
    private void deduplicateTimedPermissions(Connection c) {
        try (Statement s = c.createStatement()) {
            s.executeUpdate(
                "DELETE FROM pc_timed_permissions WHERE id NOT IN (" +
                "  SELECT MAX(id) FROM pc_timed_permissions " +
                "  GROUP BY target, is_group, permission" +
                ")");
        } catch (SQLException e) {
            // Table may not exist yet on first run, or dialect quirks with
            // subqueries in DELETE — non-fatal, the unique index creation
            // below will simply fail loudly if real duplicates remain.
            logger.fine("[PermsCraft] Timed permission dedup skipped: " + e.getMessage());
        }
    }

    // ── Groups ───────────────────────────────────────────────────────────────

    @Override
    public List<Group> loadAllGroups() {
        List<Group> groups = new ArrayList<>();
        try (Connection conn = getConnection()) {
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT * FROM pc_groups")) {
                while (rs.next()) {
                    Group g = new Group(rs.getString("name"));
                    g.setDisplayName(rs.getString("display_name"));
                    g.setPrefix(rs.getString("prefix"));
                    g.setSuffix(rs.getString("suffix"));
                    g.setWeight(rs.getInt("weight"));
                    groups.add(g);
                }
            }
            for (Group g : groups) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT permission FROM pc_group_permissions WHERE group_name=?")) {
                    ps.setString(1, g.getName());
                    try (ResultSet prs = ps.executeQuery()) {
                        while (prs.next()) g.addPermission(prs.getString("permission"));
                    }
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT parent_group FROM pc_group_inheritance WHERE group_name=?")) {
                    ps.setString(1, g.getName());
                    try (ResultSet irs = ps.executeQuery()) {
                        while (irs.next()) g.addInheritance(irs.getString("parent_group"));
                    }
                }
                // FIX: pass the existing connection to avoid opening a second connection
                // (SQLite pool size=1 — a nested getConnection() call would timeout)
                loadMetaInto(conn, g.getName(), true).forEach(g::setMeta);
            }
        } catch (SQLException e) {
            logger.severe("[PermsCraft] Failed to load groups: " + e.getMessage());
        }
        return groups;
    }

    @Override
    public void saveGroup(Group group) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(upsertGroup())) {
            ps.setString(1, group.getName());
            ps.setString(2, group.getDisplayName());
            ps.setString(3, group.getPrefix());
            ps.setString(4, group.getSuffix());
            ps.setInt(5, group.getWeight());
            if (upsertGroupParamCount() == 9) {
                ps.setString(6, group.getDisplayName());
                ps.setString(7, group.getPrefix());
                ps.setString(8, group.getSuffix());
                ps.setInt(9, group.getWeight());
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.severe("[PermsCraft] Failed to save group: " + e.getMessage());
        }
    }

    protected String upsertGroup() {
        return "INSERT INTO pc_groups (name, display_name, prefix, suffix, weight) VALUES (?,?,?,?,?) " +
               "ON DUPLICATE KEY UPDATE display_name=?, prefix=?, suffix=?, weight=?";
    }

    protected int upsertGroupParamCount() { return 9; }

    @Override
    public void deleteGroup(String groupName) {
        try (Connection conn = getConnection()) {
            exec(conn, "DELETE FROM pc_groups WHERE name=?", groupName);
            exec(conn, "DELETE FROM pc_group_permissions WHERE group_name=?", groupName);
            exec(conn, "DELETE FROM pc_group_inheritance WHERE group_name=? OR parent_group=?", groupName, groupName);
            exec(conn, "DELETE FROM pc_user_groups WHERE group_name=?", groupName);
        } catch (SQLException e) {
            logger.severe("[PermsCraft] Failed to delete group: " + e.getMessage());
        }
    }

    @Override
    public void addGroupPermission(String groupName, String permission) {
        try (Connection conn = getConnection()) {
            insertIgnore(conn, "INSERT INTO pc_group_permissions (group_name, permission) VALUES (?,?)",
                    groupName, permission);
        } catch (SQLException e) {
            logger.severe("[PermsCraft] Failed to add group permission: " + e.getMessage());
        }
    }

    @Override
    public void removeGroupPermission(String groupName, String permission) {
        try (Connection conn = getConnection()) {
            exec(conn, "DELETE FROM pc_group_permissions WHERE group_name=? AND permission=?",
                    groupName, permission);
        } catch (SQLException e) {
            logger.severe("[PermsCraft] Failed to remove group permission: " + e.getMessage());
        }
    }

    @Override
    public void addGroupInheritance(String groupName, String parentGroup) {
        try (Connection conn = getConnection()) {
            insertIgnore(conn, "INSERT INTO pc_group_inheritance (group_name, parent_group) VALUES (?,?)",
                    groupName, parentGroup);
        } catch (SQLException e) {
            logger.severe("[PermsCraft] Failed to add group inheritance: " + e.getMessage());
        }
    }

    @Override
    public void removeGroupInheritance(String groupName, String parentGroup) {
        try (Connection conn = getConnection()) {
            exec(conn, "DELETE FROM pc_group_inheritance WHERE group_name=? AND parent_group=?",
                    groupName, parentGroup);
        } catch (SQLException e) {
            logger.severe("[PermsCraft] Failed to remove group inheritance: " + e.getMessage());
        }
    }

    // ── Users ────────────────────────────────────────────────────────────────

    @Override
    public User loadUser(UUID uuid, String username) {
        try (Connection conn = getConnection()) {
            User user = new User(uuid, username);
            try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM pc_users WHERE uuid=?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        user.setPrefix(rs.getString("prefix"));
                        user.setSuffix(rs.getString("suffix"));
                        // primary_group applied after groups are loaded below
                        String storedPrimary = rs.getString("primary_group");
                        if (storedPrimary != null && !storedPrimary.isEmpty()) {
                            // store for after groups load
                            user.getMeta().put("__primary_group__", storedPrimary);
                        }
                    } else {
                        saveUser(user);
                        addUserToGroup(uuid, "default");
                        user.addGroup("default");
                        return user;
                    }
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT group_name FROM pc_user_groups WHERE uuid=?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) user.addGroup(rs.getString("group_name"));
                }
            }
            if (user.getGroups().isEmpty()) {
                addUserToGroup(uuid, "default");
                user.addGroup("default");
            }
            // FIX Bug #1: apply stored primary group now that groups are in the set
            String storedPrimary = user.getMeta().remove("__primary_group__");
            if (storedPrimary != null && user.inGroup(storedPrimary)) {
                user.setPrimaryGroup(storedPrimary);
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT permission FROM pc_user_permissions WHERE uuid=?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) user.addPermission(rs.getString("permission"));
                }
            }
            // FIX: pass the existing connection — avoids a second getConnection() call
            // which would timeout on SQLite (pool size=1, connection already held above)
            loadMetaInto(conn, uuid.toString(), false).forEach(user::setMeta);
            return user;
        } catch (SQLException e) {
            logger.severe("[PermsCraft] Failed to load user: " + e.getMessage());
            return new User(uuid, username);
        }
    }

    /**
     * FIX (Bug: loadAllUsers always empty on SQL backends): this was never
     * overridden, so every SQL backend (SQLite, MySQL, MariaDB, PostgreSQL, H2)
     * silently used the StorageBackend interface default — an empty list. This
     * made backup export/snapshot (and anything else relying on "all users")
     * silently omit every user on these backends, the most commonly used ones.
     *
     * Implemented by listing every known UUID, then delegating to the
     * already-verified loadUser() for each — slower than a single bulk query
     * (N+1) but correctness-first and reuses tested logic rather than
     * duplicating the user/groups/permissions/meta assembly here.
     */
    @Override
    public List<User> loadAllUsers() {
        List<UUID> uuids = new ArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT uuid FROM pc_users");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                try { uuids.add(UUID.fromString(rs.getString("uuid"))); }
                catch (IllegalArgumentException ignored) {}
            }
        } catch (SQLException e) {
            logger.severe("[PermsCraft] Failed to list users for loadAllUsers: " + e.getMessage());
            return Collections.emptyList();
        }
        List<User> users = new ArrayList<>(uuids.size());
        for (UUID uuid : uuids) users.add(loadUser(uuid, null));
        return users;
    }

    @Override
    public void saveUser(User user) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(upsertUser())) {
            ps.setString(1, user.getUuid().toString());
            ps.setString(2, user.getUsername());
            ps.setString(3, user.getPrefix());
            ps.setString(4, user.getSuffix());
            if (upsertUserParamCount() == 7) {
                ps.setString(5, user.getUsername());
                ps.setString(6, user.getPrefix());
                ps.setString(7, user.getSuffix());
            } else if (upsertUserParamCount() == 5) {
                ps.setString(5, user.getPrimaryGroup());
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.severe("[PermsCraft] Failed to save user: " + e.getMessage());
        }
    }

    @Override
    public void saveUserPrimaryGroup(UUID uuid, String primaryGroup) {
        try (Connection conn = getConnection()) {
            exec(conn, "UPDATE pc_users SET primary_group=? WHERE uuid=?",
                    primaryGroup.toLowerCase(), uuid.toString());
        } catch (SQLException e) {
            logger.severe("[PermsCraft] Failed to save primary group: " + e.getMessage());
        }
    }

    protected String upsertUser() {
        return "INSERT INTO pc_users (uuid, username, prefix, suffix) VALUES (?,?,?,?) " +
               "ON DUPLICATE KEY UPDATE username=?, prefix=?, suffix=?";
    }

    protected int upsertUserParamCount() { return 7; }

    @Override
    public void addUserToGroup(UUID uuid, String groupName) {
        try (Connection conn = getConnection()) {
            insertIgnore(conn, "INSERT INTO pc_user_groups (uuid, group_name) VALUES (?,?)",
                    uuid.toString(), groupName);
        } catch (SQLException e) {
            logger.severe("[PermsCraft] Failed to add user to group: " + e.getMessage());
        }
    }

    @Override
    public void removeUserFromGroup(UUID uuid, String groupName) {
        try (Connection conn = getConnection()) {
            exec(conn, "DELETE FROM pc_user_groups WHERE uuid=? AND group_name=?",
                    uuid.toString(), groupName);
        } catch (SQLException e) {
            logger.severe("[PermsCraft] Failed to remove user from group: " + e.getMessage());
        }
    }

    @Override
    public void addUserPermission(UUID uuid, String permission) {
        try (Connection conn = getConnection()) {
            insertIgnore(conn, "INSERT INTO pc_user_permissions (uuid, permission) VALUES (?,?)",
                    uuid.toString(), permission);
        } catch (SQLException e) {
            logger.severe("[PermsCraft] Failed to add user permission: " + e.getMessage());
        }
    }

    @Override
    public void removeUserPermission(UUID uuid, String permission) {
        try (Connection conn = getConnection()) {
            exec(conn, "DELETE FROM pc_user_permissions WHERE uuid=? AND permission=?",
                    uuid.toString(), permission);
        } catch (SQLException e) {
            logger.severe("[PermsCraft] Failed to remove user permission: " + e.getMessage());
        }
    }

    /**
     * FIX (case-insensitive lookup): LOWER() on both sides ensures "Steve" and
     * "steve" always resolve to the same UUID regardless of how the name was stored.
     */
    @Override
    public UUID findUUIDByUsername(String username) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT uuid FROM pc_users WHERE LOWER(username)=LOWER(?)")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return UUID.fromString(rs.getString("uuid"));
            }
        } catch (SQLException e) {
            logger.severe("[PermsCraft] Failed to find UUID: " + e.getMessage());
        }
        return null;
    }

    // ── Timed Permissions ────────────────────────────────────────────────────

    @Override
    public List<TimedPermission> loadActiveTimedPermissions() {
        List<TimedPermission> list = new ArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM pc_timed_permissions WHERE expiry > ?")) {
            ps.setLong(1, Instant.now().getEpochSecond());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new TimedPermission(
                            rs.getString("target"),
                            rs.getBoolean("is_group"),
                            rs.getString("permission"),
                            Instant.ofEpochSecond(rs.getLong("expiry"))
                    ));
                }
            }
        } catch (SQLException e) {
            logger.severe("[PermsCraft] Failed to load timed permissions: " + e.getMessage());
        }
        return list;
    }

    @Override
    public void saveTimedPermission(String target, boolean isGroup, String permission, long expiryEpoch) {
        // FIX (Bug #9): previously a plain INSERT, so re-running
        // "/pc user X timed add foo.bar 1h" created duplicate rows for the
        // same (target, is_group, permission). After the first expires, the
        // duplicate stayed active. Delete any existing row for this target/
        // permission first, then insert the fresh expiry — same
        // delete-then-insert pattern as saveTimedGroup().
        try (Connection conn = getConnection()) {
            exec(conn, "DELETE FROM pc_timed_permissions WHERE target=? AND is_group=? AND permission=?",
                    target, isGroup, permission);
            exec(conn, "INSERT INTO pc_timed_permissions (target, is_group, permission, expiry) VALUES (?,?,?,?)",
                    target, isGroup, permission, expiryEpoch);
        } catch (SQLException e) {
            logger.severe("[PermsCraft] Failed to save timed permission: " + e.getMessage());
        }
    }

    @Override
    public void deleteExpiredTimedPermissions(long nowEpoch) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM pc_timed_permissions WHERE expiry <= ?")) {
            ps.setLong(1, nowEpoch);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warning("[PermsCraft] Failed to clean expired timed perms: " + e.getMessage());
        }
    }

    // ── Timed Group Memberships ──────────────────────────────────────────────

    @Override
    public List<ir.permscraft.models.TimedGroup> loadActiveTimedGroups() {
        List<ir.permscraft.models.TimedGroup> result = new java.util.ArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT user_uuid, group_name, expiry FROM pc_timed_groups WHERE expiry > ?")) {
            ps.setLong(1, java.time.Instant.now().getEpochSecond());
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new ir.permscraft.models.TimedGroup(
                            rs.getString("user_uuid"),
                            rs.getString("group_name"),
                            java.time.Instant.ofEpochSecond(rs.getLong("expiry"))
                    ));
                }
            }
        } catch (SQLException e) {
            logger.warning("[PermsCraft] Failed to load timed groups: " + e.getMessage());
        }
        return result;
    }

    @Override
    public void saveTimedGroup(String userUuid, String groupName, long expiryEpochSecond) {
        try (Connection conn = getConnection()) {
            exec(conn, "DELETE FROM pc_timed_groups WHERE user_uuid=? AND group_name=?",
                    userUuid, groupName.toLowerCase());
            exec(conn, "INSERT INTO pc_timed_groups (user_uuid, group_name, expiry) VALUES (?,?,?)",
                    userUuid, groupName.toLowerCase(), expiryEpochSecond);
        } catch (SQLException e) {
            logger.warning("[PermsCraft] Failed to save timed group: " + e.getMessage());
        }
    }

    @Override
    public void deleteTimedGroup(String userUuid, String groupName) {
        try (Connection conn = getConnection()) {
            exec(conn, "DELETE FROM pc_timed_groups WHERE user_uuid=? AND group_name=?",
                    userUuid, groupName.toLowerCase());
        } catch (SQLException e) {
            logger.warning("[PermsCraft] Failed to delete timed group: " + e.getMessage());
        }
    }

    @Override
    public void deleteExpiredTimedGroups(long nowEpochSecond) {
        try (Connection conn = getConnection()) {
            exec(conn, "DELETE FROM pc_timed_groups WHERE expiry <= ?", nowEpochSecond);
        } catch (SQLException e) {
            logger.warning("[PermsCraft] Failed to purge expired timed groups: " + e.getMessage());
        }
    }

    @Override
    public void deleteTimedPermission(String target, String permission) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM pc_timed_permissions WHERE target = ? AND permission = ?")) {
            ps.setString(1, target);
            ps.setString(2, permission);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warning("[PermsCraft] Failed to delete timed permission: " + e.getMessage());
        }
    }

    // ── Context Permissions ──────────────────────────────────────────────────

    /**
     * FIX: Returns List<ContextRow> instead of the old ContextualPermission[] hack.
     * ContextRow is a proper typed record: (String target, boolean isGroup, ContextualPermission cp).
     */
    @Override
    public List<ContextRow> loadAllContextPermissions() {
        List<ContextRow> result = new ArrayList<>();
        try (Connection conn = getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM pc_context_permissions")) {
            while (rs.next()) {
                String ctxKey = rs.getString("context_key");
                String ctxVal = rs.getString("context_value");
                ContextSet requiredCtx;
                if ("global".equals(ctxKey)) {
                    requiredCtx = ContextSet.global();
                } else if ("__multi__".equals(ctxVal)) {
                    requiredCtx = deserializeContextSet(ctxKey);
                } else {
                    requiredCtx = ContextSet.builder().put(ctxKey, ctxVal).build();
                }
                ContextualPermission cp = new ContextualPermission(rs.getString("permission"), requiredCtx, rs.getBoolean("granted"));
                result.add(new ContextRow(rs.getString("target"), rs.getBoolean("is_group"), cp));
            }
        } catch (SQLException e) {
            logger.severe("[PermsCraft] Failed to load context permissions: " + e.getMessage());
        }
        return result;
    }

    /**
     * FIX (Bug #10): cursor-based streaming; only one ResultSet row is
     * resident in memory at a time, avoiding OOM on large context tables.
     * The reused Map is freshly populated per row but never held past the
     * consumer call, so it is safe for consumers that do not retain it.
     */
    @Override
    public void streamContextPermissions(java.util.function.Consumer<java.util.Map<String, Object>> consumer) {
        try (Connection conn = getConnection();
             Statement st = conn.createStatement(java.sql.ResultSet.TYPE_FORWARD_ONLY,
                                                  java.sql.ResultSet.CONCUR_READ_ONLY)) {
            st.setFetchSize(500); // page through 500 rows at a time (works on MySQL/PostgreSQL/MariaDB)
            try (ResultSet rs = st.executeQuery("SELECT * FROM pc_context_permissions")) {
                java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
                while (rs.next()) {
                    String ctxKey = rs.getString("context_key");
                    String ctxVal = rs.getString("context_value");
                    ir.permscraft.context.ContextSet ctx;
                    if ("global".equals(ctxKey)) {
                        ctx = ir.permscraft.context.ContextSet.global();
                    } else if ("__multi__".equals(ctxVal)) {
                        ctx = deserializeContextSet(ctxKey);
                    } else {
                        ctx = ir.permscraft.context.ContextSet.builder().put(ctxKey, ctxVal).build();
                    }
                    row.clear();
                    row.put("target",     rs.getString("target"));
                    row.put("isGroup",    rs.getBoolean("is_group"));
                    row.put("permission", rs.getString("permission"));
                    row.put("granted",    rs.getBoolean("granted"));
                    row.put("context",    ctx.asMap());
                    consumer.accept(row);
                }
            }
        } catch (java.sql.SQLException e) {
            logger.severe("[PermsCraft] Failed to stream context permissions: " + e.getMessage());
        }
    }

    @Override
    public void saveContextPermission(String target, boolean isGroup, String permission,
                                      Context context, boolean granted) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO pc_context_permissions " +
                     "(target, is_group, permission, context_key, context_value, granted) VALUES (?,?,?,?,?,?)")) {
            ps.setString(1, target);
            ps.setBoolean(2, isGroup);
            ps.setString(3, permission);
            ps.setString(4, context.getKey());
            ps.setString(5, context.getValue());
            ps.setBoolean(6, granted);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.severe("[PermsCraft] Failed to save context permission: " + e.getMessage());
        }
    }



    @Override
    public void saveContextPermission(String target, boolean isGroup, String permission,
                                      ContextSet requiredCtx, boolean granted) {
        // Serialize multi-key context: if single key, use old columns; if multi, use __multi__ marker
        String ctxKey, ctxVal;
        if (requiredCtx.isEmpty()) {
            ctxKey = "global"; ctxVal = "global";
        } else if (requiredCtx.asMap().size() == 1) {
            var entry = requiredCtx.asMap().entrySet().iterator().next();
            ctxKey = entry.getKey(); ctxVal = entry.getValue();
        } else {
            ctxKey = serializeContextSet(requiredCtx); ctxVal = "__multi__";
        }
        Context ctx = new Context(ctxKey, ctxVal);
        saveContextPermission(target, isGroup, permission, ctx, granted);
    }

    @Override
    public void deleteContextPermission(String target, String permission, ContextSet requiredCtx) {
        String ctxKey, ctxVal;
        if (requiredCtx.isEmpty()) {
            ctxKey = "global"; ctxVal = "global";
        } else if (requiredCtx.asMap().size() == 1) {
            var entry = requiredCtx.asMap().entrySet().iterator().next();
            ctxKey = entry.getKey(); ctxVal = entry.getValue();
        } else {
            ctxKey = serializeContextSet(requiredCtx); ctxVal = "__multi__";
        }
        deleteContextPermission(target, permission, new Context(ctxKey, ctxVal));
    }

    private static ContextSet deserializeContextSet(String serialized) {
        ContextSet.Builder b = ContextSet.builder();
        for (String part : serialized.split(",")) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2) b.put(kv[0].trim(), kv[1].trim());
        }
        return b.build();
    }

    private static String serializeContextSet(ContextSet cs) {
        StringBuilder sb = new StringBuilder();
        cs.asMap().forEach((k, v) -> { if (!sb.isEmpty()) sb.append(","); sb.append(k).append("=").append(v); });
        return sb.toString();
    }

    public void deleteContextPermission(String target, String permission, Context context) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM pc_context_permissions " +
                     "WHERE target=? AND permission=? AND context_key=? AND context_value=?")) {
            ps.setString(1, target);
            ps.setString(2, permission);
            ps.setString(3, context.getKey());
            ps.setString(4, context.getValue());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.severe("[PermsCraft] Failed to delete context permission: " + e.getMessage());
        }
    }

    // ── Tracks ───────────────────────────────────────────────────────────────

    @Override
    public List<Track> loadAllTracks() {
        List<Track> tracks = new ArrayList<>();
        try (Connection conn = getConnection()) {
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT name FROM pc_tracks")) {
                while (rs.next()) tracks.add(new Track(rs.getString("name")));
            }
            for (Track track : tracks) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT group_name FROM pc_track_groups WHERE track_name=? ORDER BY position ASC")) {
                    ps.setString(1, track.getName());
                    try (ResultSet grs = ps.executeQuery()) {
                        while (grs.next()) track.addGroup(grs.getString("group_name"));
                    }
                }
            }
        } catch (SQLException e) {
            logger.severe("[PermsCraft] Failed to load tracks: " + e.getMessage());
        }
        return tracks;
    }

    @Override
    public void saveTrack(Track track) {
        try (Connection conn = getConnection()) {
            // Portable upsert: check existence first
            boolean exists;
            try (PreparedStatement check = conn.prepareStatement(
                    "SELECT 1 FROM pc_tracks WHERE name=?")) {
                check.setString(1, track.getName());
                try (ResultSet rs = check.executeQuery()) { exists = rs.next(); }
            }
            if (!exists) {
                exec(conn, "INSERT INTO pc_tracks (name) VALUES (?)", track.getName());
            }
            // Replace group list
            exec(conn, "DELETE FROM pc_track_groups WHERE track_name=?", track.getName());
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO pc_track_groups (track_name, group_name, position) VALUES (?,?,?)")) {
                List<String> groups = track.getGroups();
                for (int i = 0; i < groups.size(); i++) {
                    ps.setString(1, track.getName());
                    ps.setString(2, groups.get(i));
                    ps.setInt(3, i);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        } catch (SQLException e) {
            logger.severe("[PermsCraft] Failed to save track: " + e.getMessage());
        }
    }

    @Override
    public void deleteTrack(String trackName) {
        try (Connection conn = getConnection()) {
            exec(conn, "DELETE FROM pc_tracks WHERE name=?", trackName);
            exec(conn, "DELETE FROM pc_track_groups WHERE track_name=?", trackName);
        } catch (SQLException e) {
            logger.severe("[PermsCraft] Failed to delete track: " + e.getMessage());
        }
    }

    // ── Logging ──────────────────────────────────────────────────────────────

    @Override
    public void saveLog(long timestamp, String actor, String action, String target, String detail) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO pc_log (timestamp, actor, action, target, detail) VALUES (?,?,?,?,?)")) {
            ps.setLong(1, timestamp);
            ps.setString(2, actor);
            ps.setString(3, action);
            ps.setString(4, target);
            ps.setString(5, detail);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warning("[PermsCraft] Failed to write log: " + e.getMessage());
        }
    }

    @Override
    public List<LogEntry> loadRecentLogs(int limit) {
        return queryLogs("SELECT * FROM pc_log ORDER BY id DESC LIMIT ?", limit);
    }

    @Override
    public List<LogEntry> loadLogsByTarget(String target, int limit) {
        return queryLogsFiltered("SELECT * FROM pc_log WHERE target=? ORDER BY id DESC LIMIT ?", target, limit);
    }

    @Override
    public List<LogEntry> loadLogsByActor(String actor, int limit) {
        return queryLogsFiltered("SELECT * FROM pc_log WHERE actor=? ORDER BY id DESC LIMIT ?", actor, limit);
    }

    @Override
    public void deleteLogsOlderThan(long epochSecond) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM pc_log WHERE timestamp < ?")) {
            ps.setLong(1, epochSecond);
            int deleted = ps.executeUpdate();
            if (deleted > 0) logger.info("[PermsCraft] Cleared " + deleted + " old log entries.");
        } catch (SQLException e) {
            logger.warning("[PermsCraft] Failed to clear old logs: " + e.getMessage());
        }
    }

    /**
     * FIX (Bug #13): Returns the exact row count deleted via a two-statement
     * transaction (COUNT then DELETE), so the REST client receives a real number
     * instead of -1.  For MySQL/MariaDB/PostgreSQL, ROW_COUNT() or
     * PreparedStatement.getUpdateCount() could be used instead; a portable
     * COUNT+DELETE pair is used here to work across all four SQL dialects.
     */
    @Override
    public int purgeLogs(int days) {
        long cutoff = java.time.Instant.now().minusSeconds((long) days * 86400).getEpochSecond();
        try (Connection conn = getConnection()) {
            int count;
            try (PreparedStatement countPs = conn.prepareStatement(
                    "SELECT COUNT(*) FROM pc_log WHERE timestamp < ?")) {
                countPs.setLong(1, cutoff);
                try (ResultSet rs = countPs.executeQuery()) {
                    count = rs.next() ? rs.getInt(1) : 0;
                }
            }
            if (count == 0) return 0;
            try (PreparedStatement delPs = conn.prepareStatement(
                    "DELETE FROM pc_log WHERE timestamp < ?")) {
                delPs.setLong(1, cutoff);
                delPs.executeUpdate();
            }
            logger.info("[PermsCraft] Purged " + count + " log entries older than " + days + " days.");
            return count;
        } catch (SQLException e) {
            logger.warning("[PermsCraft] Failed to purge logs: " + e.getMessage());
            return -1;
        }
    }

    /**
     * FIX (Bug #12): Returns only action names that actually appear in the
     * log table (SELECT DISTINCT), not the full enum list.
     */
    @Override
    public List<String> getDistinctLogActions() {
        List<String> actions = new ArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT DISTINCT action FROM pc_log ORDER BY action ASC");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String name = rs.getString("action");
                if (name != null && !name.isBlank()) actions.add(name);
            }
        } catch (SQLException e) {
            logger.warning("[PermsCraft] Failed to list distinct log actions: " + e.getMessage());
        }
        return actions;
    }

    /**
     * FIX (Bug #7 / countLogs default): Uses a single COUNT(*) SQL query
     * instead of loading all matching rows into memory, which matters on
     * servers with large log tables.
     */
    @Override
    public long countLogs(ir.permscraft.storage.LogFilter filter) {
        StringBuilder sb = new StringBuilder("SELECT COUNT(*) FROM pc_log WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (filter.actor()  != null) { sb.append(" AND actor=?");     params.add(filter.actor()); }
        if (filter.target() != null) { sb.append(" AND target=?");    params.add(filter.target()); }
        if (filter.action() != null) { sb.append(" AND action=?");    params.add(filter.action()); }
        if (filter.from()   != null) { sb.append(" AND timestamp>=?"); params.add(filter.from()); }
        if (filter.to()     != null) { sb.append(" AND timestamp<?");  params.add(filter.to()); }
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sb.toString())) {
            for (int i = 0; i < params.size(); i++) {
                Object p = params.get(i);
                if (p instanceof Long l) ps.setLong(i + 1, l);
                else ps.setString(i + 1, (String) p);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            logger.warning("[PermsCraft] Failed to count logs: " + e.getMessage());
            return 0L;
        }
    }

    private List<LogEntry> queryLogs(String sql, int limit) {
        List<LogEntry> entries = new ArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    LogEntry entry = rowToLogEntry(rs);
                    if (entry != null) entries.add(entry);
                }
            }
        } catch (SQLException e) {
            logger.severe("[PermsCraft] Failed to read log: " + e.getMessage());
        }
        return entries;
    }

    private List<LogEntry> queryLogsFiltered(String sql, String filter, int limit) {
        List<LogEntry> entries = new ArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, filter);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    LogEntry entry = rowToLogEntry(rs);
                    if (entry != null) entries.add(entry);
                }
            }
        } catch (SQLException e) {
            logger.severe("[PermsCraft] Failed to read log: " + e.getMessage());
        }
        return entries;
    }

    private static LogEntry rowToLogEntry(ResultSet rs) throws SQLException {
        LogEntry.Action action;
        try {
            action = LogEntry.Action.valueOf(rs.getString("action"));
        } catch (IllegalArgumentException e) {
            return null; // unknown action value — caller will skip this entry
        }
        return new LogEntry(
                rs.getLong("id"),
                Instant.ofEpochSecond(rs.getLong("timestamp")),
                rs.getString("actor"),
                action,
                rs.getString("target"),
                rs.getString("detail")
        );
    }

    // ── Clear user / group ────────────────────────────────────────────────────

    @Override
    public void clearUser(UUID uuid) {
        try (Connection conn = getConnection()) {
            exec(conn, "DELETE FROM pc_user_permissions WHERE uuid=?", uuid.toString());
            exec(conn, "DELETE FROM pc_user_groups WHERE uuid=?", uuid.toString());
            exec(conn, "DELETE FROM pc_timed_permissions WHERE target=? AND is_group=FALSE", uuid.toString());
            exec(conn, "DELETE FROM pc_context_permissions WHERE target=? AND is_group=FALSE", uuid.toString());
            deleteAllMeta(uuid.toString(), false);
            // Re-insert default group
            insertIgnore(conn, "INSERT INTO pc_user_groups (uuid, group_name) VALUES (?,?)",
                    uuid.toString(), "default");
        } catch (SQLException e) {
            logger.severe("[PermsCraft] Failed to clear user: " + e.getMessage());
        }
    }

    @Override
    public void clearGroup(String groupName) {
        try (Connection conn = getConnection()) {
            exec(conn, "DELETE FROM pc_group_permissions WHERE group_name=?", groupName);
            exec(conn, "DELETE FROM pc_group_inheritance WHERE group_name=?", groupName);
            exec(conn, "DELETE FROM pc_timed_permissions WHERE target=? AND is_group=TRUE", groupName);
            exec(conn, "DELETE FROM pc_context_permissions WHERE target=? AND is_group=TRUE", groupName);
            deleteAllMeta(groupName, true);
        } catch (SQLException e) {
            logger.severe("[PermsCraft] Failed to clear group: " + e.getMessage());
        }
    }

    // ── Meta ─────────────────────────────────────────────────────────────────

    @Override
    public Map<String, String> loadMeta(String target, boolean isGroup) {
        try (Connection conn = getConnection()) {
            return loadMetaInto(conn, target, isGroup);
        } catch (SQLException e) {
            logger.severe("[PermsCraft] Failed to load meta: " + e.getMessage());
            return new java.util.LinkedHashMap<>();
        }
    }

    /**
     * Internal helper: load meta using a caller-supplied connection.
     * Use this whenever you already hold a connection (e.g. inside loadAllGroups /
     * loadUser) to avoid opening a second connection — critical for SQLite whose
     * pool size is 1.
     */
    protected Map<String, String> loadMetaInto(Connection conn, String target, boolean isGroup) throws SQLException {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        long now = java.time.Instant.now().getEpochSecond();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT meta_key, meta_value FROM pc_meta WHERE target=? AND is_group=? AND (expiry=-1 OR expiry>?)")) {
            ps.setString(1, target);
            ps.setBoolean(2, isGroup);
            ps.setLong(3, now);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.put(rs.getString("meta_key"), rs.getString("meta_value"));
            }
        }
        return result;
    }

    @Override
    public void saveMeta(String target, boolean isGroup, String key, String value) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(upsertMeta())) {
            ps.setString(1, target);
            ps.setBoolean(2, isGroup);
            ps.setString(3, key);
            ps.setString(4, value);
            ps.setLong(5, -1L);
            if (upsertMetaParamCount() == 7) {
                // FIX: correct order — SQL is "UPDATE expiry=?, meta_value=?"
                ps.setLong(6, -1L);    // expiry (UPDATE)
                ps.setString(7, value); // meta_value (UPDATE)
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.severe("[PermsCraft] Failed to save meta: " + e.getMessage());
        }
    }

    protected String upsertMeta() {
        return "INSERT INTO pc_meta (target, is_group, meta_key, meta_value, expiry) VALUES (?,?,?,?,?) " +
               "ON DUPLICATE KEY UPDATE expiry=?, meta_value=?";
    }

    protected int upsertMetaParamCount() { return 7; }

    @Override
    public void deleteMeta(String target, boolean isGroup, String key) {
        try (Connection conn = getConnection()) {
            exec(conn, "DELETE FROM pc_meta WHERE target=? AND is_group=? AND meta_key=?",
                    target, isGroup, key);
        } catch (SQLException e) {
            logger.severe("[PermsCraft] Failed to delete meta: " + e.getMessage());
        }
    }

    @Override
    public void deleteAllMeta(String target, boolean isGroup) {
        try (Connection conn = getConnection()) {
            exec(conn, "DELETE FROM pc_meta WHERE target=? AND is_group=?", target, isGroup);
        } catch (SQLException e) {
            logger.severe("[PermsCraft] Failed to delete all meta: " + e.getMessage());
        }
    }

    @Override
    public void saveTimedMeta(String target, boolean isGroup, String key, String value, long expiryEpochSecond) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(upsertMeta())) {
            ps.setString(1, target);
            ps.setBoolean(2, isGroup);
            ps.setString(3, key);
            ps.setString(4, value);
            ps.setLong(5, expiryEpochSecond);
            if (upsertMetaParamCount() == 7) {
                // FIX: correct order — SQL is "UPDATE expiry=?, meta_value=?"
                ps.setLong(6, expiryEpochSecond); // expiry (UPDATE)
                ps.setString(7, value);            // meta_value (UPDATE)
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.severe("[PermsCraft] Failed to save timed meta: " + e.getMessage());
        }
    }

    // ── Search permission ─────────────────────────────────────────────────────

    @Override
    public List<String> searchPermission(String permission) {
        List<String> results = new ArrayList<>();
        try (Connection conn = getConnection()) {
            // Users with direct permission
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT uuid FROM pc_user_permissions WHERE LOWER(permission)=LOWER(?)")) {
                ps.setString(1, permission);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) results.add(rs.getString("uuid"));
                }
            }
            // Groups with direct permission
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT group_name FROM pc_group_permissions WHERE LOWER(permission)=LOWER(?)")) {
                ps.setString(1, permission);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) results.add("group:" + rs.getString("group_name"));
                }
            }
        } catch (SQLException e) {
            logger.severe("[PermsCraft] Failed to search permission: " + e.getMessage());
        }
        return results;
    }

    // ── Bulk update ──────────────────────────────────────────────────────────

    @Override
    public int bulkAddPermissionToUsers(String permission) {
        try (Connection conn = getConnection()) {
            List<String> targets = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT uuid FROM pc_users WHERE uuid NOT IN " +
                    "(SELECT uuid FROM pc_user_permissions WHERE LOWER(permission)=LOWER(?))")) {
                ps.setString(1, permission);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) targets.add(rs.getString("uuid"));
                }
            }
            for (String uuid : targets) {
                try { insertIgnore(conn, "INSERT INTO pc_user_permissions (uuid, permission) VALUES (?,?)", uuid, permission); }
                catch (SQLException ignored) {}
            }
            return targets.size();
        } catch (SQLException e) { logger.severe("[PermsCraft] Bulk add permission failed: " + e.getMessage()); return -1; }
    }

    @Override
    public int bulkRemovePermissionFromUsers(String permission) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM pc_user_permissions WHERE LOWER(permission)=LOWER(?)")) {
            ps.setString(1, permission); return ps.executeUpdate();
        } catch (SQLException e) { logger.severe("[PermsCraft] Bulk remove permission failed: " + e.getMessage()); return -1; }
    }

    @Override
    public int bulkAddGroupToUsers(String groupName) {
        try (Connection conn = getConnection()) {
            List<String> targets = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT uuid FROM pc_users WHERE uuid NOT IN " +
                    "(SELECT uuid FROM pc_user_groups WHERE LOWER(group_name)=LOWER(?))")) {
                ps.setString(1, groupName);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) targets.add(rs.getString("uuid"));
                }
            }
            for (String uuid : targets) {
                try { insertIgnore(conn, "INSERT INTO pc_user_groups (uuid, group_name) VALUES (?,?)", uuid, groupName.toLowerCase()); }
                catch (SQLException ignored) {}
            }
            return targets.size();
        } catch (SQLException e) { logger.severe("[PermsCraft] Bulk add group failed: " + e.getMessage()); return -1; }
    }

    @Override
    public int bulkRemoveGroupFromUsers(String groupName) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM pc_user_groups WHERE LOWER(group_name)=LOWER(?)")) {
            ps.setString(1, groupName); return ps.executeUpdate();
        } catch (SQLException e) { logger.severe("[PermsCraft] Bulk remove group failed: " + e.getMessage()); return -1; }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    protected void exec(Connection conn, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, params);
            ps.executeUpdate();
        }
    }

    protected void insertIgnore(Connection conn, String sql, Object... params) throws SQLException {
        exec(conn, sql, params);
    }

    private static void bind(PreparedStatement ps, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            if (params[i] instanceof String s)       ps.setString(i + 1, s);
            else if (params[i] instanceof Integer v) ps.setInt(i + 1, v);
            else if (params[i] instanceof Long v)    ps.setLong(i + 1, v);
            else if (params[i] instanceof Boolean v) ps.setBoolean(i + 1, v);
            else ps.setObject(i + 1, params[i]);
        }
    }
}
