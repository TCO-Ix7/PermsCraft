package ir.permscraft.storage;

import ir.permscraft.context.Context;
import ir.permscraft.context.ContextSet;
import ir.permscraft.context.ContextualPermission;
import ir.permscraft.logging.LogEntry;
import ir.permscraft.models.Group;
import ir.permscraft.models.TimedPermission;
import ir.permscraft.models.Track;
import ir.permscraft.models.User;

import org.junit.jupiter.api.*;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration-style tests for storage logic using an in-memory stub.
 * No database or Bukkit is required.
 */
@DisplayName("InMemoryStorage (StorageBackend contract)")
class InMemoryStorageTest {

    private InMemoryStorage storage;

    @BeforeEach
    void setUp() {
        storage = new InMemoryStorage();
        storage.init();
    }

    // ── groups ────────────────────────────────────────────────────────────────

    @Test @DisplayName("saveGroup and loadAllGroups round-trip")
    void group_saveAndLoad() {
        Group g = new Group("admin");
        g.setWeight(100);
        storage.saveGroup(g);
        List<Group> groups = storage.loadAllGroups();
        assertEquals(1, groups.size());
        assertEquals("admin", groups.get(0).getName());
    }

    @Test @DisplayName("deleteGroup removes the group")
    void group_delete() {
        storage.saveGroup(new Group("vip"));
        storage.deleteGroup("vip");
        assertTrue(storage.loadAllGroups().isEmpty());
    }

    @Test @DisplayName("addGroupPermission persists permission on the group")
    void group_addPermission() {
        storage.saveGroup(new Group("default"));
        storage.addGroupPermission("default", "essentials.help");
        Group g = storage.loadAllGroups().get(0);
        assertTrue(g.getPermissions().contains("essentials.help"));
    }

    @Test @DisplayName("removeGroupPermission removes permission from group")
    void group_removePermission() {
        Group g = new Group("default");
        g.addPermission("essentials.help");
        storage.saveGroup(g);
        storage.removeGroupPermission("default", "essentials.help");
        assertFalse(storage.loadAllGroups().get(0).getPermissions().contains("essentials.help"));
    }

    @Test @DisplayName("addGroupInheritance persists parent on the group")
    void group_addInheritance() {
        storage.saveGroup(new Group("default"));
        storage.saveGroup(new Group("vip"));
        storage.addGroupInheritance("vip", "default");
        Group vip = storage.loadAllGroups().stream()
                .filter(g -> g.getName().equals("vip")).findFirst().orElseThrow();
        assertTrue(vip.getInheritedGroups().contains("default"));
    }

    // ── users ─────────────────────────────────────────────────────────────────

    @Test @DisplayName("saveUser and loadUser round-trip")
    void user_saveAndLoad() {
        UUID uuid = UUID.randomUUID();
        User user = new User(uuid, "Steve");
        user.addGroup("default");
        storage.saveUser(user);
        User loaded = storage.loadUser(uuid, "Steve");
        assertNotNull(loaded);
        assertEquals("Steve", loaded.getUsername());
        assertTrue(loaded.inGroup("default"));
    }

    @Test @DisplayName("addUserPermission persists on subsequent load")
    void user_addPermission() {
        UUID uuid = UUID.randomUUID();
        storage.saveUser(new User(uuid, "Alex"));
        storage.addUserPermission(uuid, "essentials.fly");
        User loaded = storage.loadUser(uuid, "Alex");
        assertTrue(loaded.hasPermission("essentials.fly"));
    }

    @Test @DisplayName("removeUserPermission removes from subsequent load")
    void user_removePermission() {
        UUID uuid = UUID.randomUUID();
        User u = new User(uuid, "Alex");
        u.addPermission("essentials.fly");
        storage.saveUser(u);
        storage.removeUserPermission(uuid, "essentials.fly");
        assertFalse(storage.loadUser(uuid, "Alex").hasPermission("essentials.fly"));
    }

    @Test @DisplayName("addUserToGroup persists on subsequent load")
    void user_addToGroup() {
        UUID uuid = UUID.randomUUID();
        storage.saveUser(new User(uuid, "Alex"));
        storage.addUserToGroup(uuid, "vip");
        assertTrue(storage.loadUser(uuid, "Alex").inGroup("vip"));
    }

    @Test @DisplayName("removeUserFromGroup persists on subsequent load")
    void user_removeFromGroup() {
        UUID uuid = UUID.randomUUID();
        User u = new User(uuid, "Alex");
        u.addGroup("vip");
        storage.saveUser(u);
        storage.removeUserFromGroup(uuid, "vip");
        assertFalse(storage.loadUser(uuid, "Alex").inGroup("vip"));
    }

    @Test @DisplayName("clearUser wipes permissions and groups but keeps row")
    void user_clear() {
        UUID uuid = UUID.randomUUID();
        User u = new User(uuid, "Alex");
        u.addPermission("essentials.fly");
        u.addGroup("admin");
        storage.saveUser(u);
        storage.clearUser(uuid);
        User loaded = storage.loadUser(uuid, "Alex");
        assertTrue(loaded.getPermissions().isEmpty());
        assertTrue(loaded.getGroups().isEmpty());
    }

    @Test @DisplayName("findUUIDByUsername is case-insensitive")
    void user_findByUsername_caseInsensitive() {
        UUID uuid = UUID.randomUUID();
        storage.saveUser(new User(uuid, "Steve"));
        assertEquals(uuid, storage.findUUIDByUsername("steve"));
        assertEquals(uuid, storage.findUUIDByUsername("STEVE"));
        assertEquals(uuid, storage.findUUIDByUsername("Steve"));
    }

    // ── meta ──────────────────────────────────────────────────────────────────

    @Test @DisplayName("saveMeta and loadMeta round-trip for user")
    void meta_userSaveLoad() {
        UUID uuid = UUID.randomUUID();
        storage.saveUser(new User(uuid, "Alex"));
        storage.saveMeta(uuid.toString(), false, "rank", "diamond");
        Map<String, String> meta = storage.loadMeta(uuid.toString(), false);
        assertEquals("diamond", meta.get("rank"));
    }

    @Test @DisplayName("deleteMeta removes key")
    void meta_delete() {
        UUID uuid = UUID.randomUUID();
        storage.saveUser(new User(uuid, "Alex"));
        storage.saveMeta(uuid.toString(), false, "rank", "gold");
        storage.deleteMeta(uuid.toString(), false, "rank");
        assertFalse(storage.loadMeta(uuid.toString(), false).containsKey("rank"));
    }

    // ── tracks ────────────────────────────────────────────────────────────────

    @Test @DisplayName("saveTrack and loadAllTracks round-trip")
    void track_saveAndLoad() {
        Track track = new Track("ranks");
        track.addGroup("member");
        track.addGroup("vip");
        storage.saveTrack(track);
        List<Track> tracks = storage.loadAllTracks();
        assertEquals(1, tracks.size());
        assertEquals("ranks", tracks.get(0).getName());
        assertTrue(tracks.get(0).containsGroup("vip"));
    }

    @Test @DisplayName("deleteTrack removes the track")
    void track_delete() {
        storage.saveTrack(new Track("ranks"));
        storage.deleteTrack("ranks");
        assertTrue(storage.loadAllTracks().isEmpty());
    }

    // ── logs ──────────────────────────────────────────────────────────────────

    @Test @DisplayName("saveLog and loadRecentLogs round-trip")
    void log_saveAndLoad() {
        long ts = Instant.now().getEpochSecond();
        storage.saveLog(ts, "Steve", "USER_PERM_ADD", "Alex", "essentials.fly");
        List<LogEntry> logs = storage.loadRecentLogs(10);
        assertEquals(1, logs.size());
        assertEquals("Steve", logs.get(0).getActor());
    }

    @Test @DisplayName("loadRecentLogs respects limit")
    void log_limit() {
        long ts = Instant.now().getEpochSecond();
        for (int i = 0; i < 10; i++) {
            storage.saveLog(ts, "actor", "GROUP_CREATE", "group" + i, "");
        }
        assertEquals(5, storage.loadRecentLogs(5).size());
    }

    @Test @DisplayName("deleteLogsOlderThan removes old entries")
    void log_deleteOld() {
        long old = Instant.now().minusSeconds(86400).getEpochSecond();
        long now = Instant.now().getEpochSecond();
        storage.saveLog(old, "a", "GROUP_CREATE", "x", "");
        storage.saveLog(now, "b", "GROUP_CREATE", "y", "");
        storage.deleteLogsOlderThan(now - 3600); // delete anything older than 1h ago
        List<LogEntry> remaining = storage.loadRecentLogs(100);
        assertEquals(1, remaining.size()); // only the recent one remains
        assertEquals("b", remaining.get(0).getActor());
    }

    // ── timed permissions ─────────────────────────────────────────────────────

    @Test @DisplayName("saveTimedPermission is included in loadActiveTimedPermissions")
    void timedPerm_saveAndLoad() {
        long future = Instant.now().plusSeconds(3600).getEpochSecond();
        storage.saveTimedPermission("uuid-1", false, "essentials.fly", future);
        List<TimedPermission> active = storage.loadActiveTimedPermissions();
        assertEquals(1, active.size());
        assertEquals("essentials.fly", active.get(0).getPermission());
    }

    @Test @DisplayName("deleteExpiredTimedPermissions removes past entries")
    void timedPerm_deleteExpired() {
        long past   = Instant.now().minusSeconds(60).getEpochSecond();
        long future = Instant.now().plusSeconds(3600).getEpochSecond();
        storage.saveTimedPermission("uuid-1", false, "perm.a", past);
        storage.saveTimedPermission("uuid-2", false, "perm.b", future);
        storage.deleteExpiredTimedPermissions(Instant.now().getEpochSecond());
        List<TimedPermission> active = storage.loadActiveTimedPermissions();
        assertEquals(1, active.size());
        assertEquals("perm.b", active.get(0).getPermission());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Inner in-memory stub implementation of StorageBackend
    // ══════════════════════════════════════════════════════════════════════════

    static class InMemoryStorage implements StorageBackend {

        private final Map<String, Group>     groups    = new LinkedHashMap<>();
        private final Map<UUID, User>        users     = new LinkedHashMap<>();
        private final Map<String, Map<String,String>> meta = new HashMap<>();
        private final List<Track>            tracks    = new ArrayList<>();
        private final List<LogEntry>         logs      = new ArrayList<>();
        private final List<TimedPermission>  timedPerms = new ArrayList<>();
        private final List<ContextRow>       ctxPerms  = new ArrayList<>();
        private long logIdSeq = 0;

        @Override public boolean init() { return true; }
        @Override public void close() {}

        // groups
        @Override public List<Group> loadAllGroups() { return new ArrayList<>(groups.values()); }
        @Override public void saveGroup(Group g) { groups.put(g.getName().toLowerCase(), g); }
        @Override public void deleteGroup(String name) { groups.remove(name.toLowerCase()); }
        @Override public void addGroupPermission(String g, String p) { getGroup(g).addPermission(p); }
        @Override public void removeGroupPermission(String g, String p) { getGroup(g).removePermission(p); }
        @Override public void addGroupInheritance(String g, String parent) { getGroup(g).addInheritance(parent); }
        @Override public void removeGroupInheritance(String g, String parent) { getGroup(g).removeInheritance(parent); }
        @Override public void clearGroup(String g) {
            Group grp = getGroup(g); if (grp == null) return;
            grp.getPermissions().clear(); grp.getInheritedGroups().clear();
        }

        // users
        @Override public User loadUser(UUID uuid, String username) {
            return users.getOrDefault(uuid, new User(uuid, username));
        }
        @Override public void saveUser(User u) { users.put(u.getUuid(), u); }
        @Override public void saveUserPrimaryGroup(UUID uuid, String pg) {
            User u = users.get(uuid); if (u != null) u.setPrimaryGroup(pg);
        }
        @Override public void addUserToGroup(UUID uuid, String g) {
            users.computeIfAbsent(uuid, id -> new User(id, "")).addGroup(g);
        }
        @Override public void removeUserFromGroup(UUID uuid, String g) {
            User u = users.get(uuid); if (u != null) u.removeGroup(g);
        }
        @Override public void addUserPermission(UUID uuid, String p) {
            users.computeIfAbsent(uuid, id -> new User(id, "")).addPermission(p);
        }
        @Override public void removeUserPermission(UUID uuid, String p) {
            User u = users.get(uuid); if (u != null) u.removePermission(p);
        }
        @Override public void clearUser(UUID uuid) {
            User u = users.get(uuid); if (u == null) return;
            u.getPermissions().clear(); u.getGroups().clear();
        }
        @Override public UUID findUUIDByUsername(String username) {
            String lower = username.toLowerCase();
            return users.values().stream()
                    .filter(u -> u.getUsername().equalsIgnoreCase(lower))
                    .map(User::getUuid).findFirst().orElse(null);
        }

        // meta
        @Override public Map<String, String> loadMeta(String target, boolean isGroup) {
            return meta.getOrDefault(key(target, isGroup), Map.of());
        }
        @Override public void saveMeta(String target, boolean isGroup, String k, String v) {
            meta.computeIfAbsent(key(target, isGroup), x -> new HashMap<>()).put(k, v);
        }
        @Override public void deleteMeta(String target, boolean isGroup, String k) {
            Map<String, String> m = meta.get(key(target, isGroup)); if (m != null) m.remove(k);
        }
        @Override public void deleteAllMeta(String target, boolean isGroup) {
            meta.remove(key(target, isGroup));
        }
        @Override public void saveTimedMeta(String t, boolean ig, String k, String v, long expiry) {}

        // search
        @Override public List<String> searchPermission(String perm) { return List.of(); }

        // tracks
        @Override public List<Track> loadAllTracks() { return new ArrayList<>(tracks); }
        @Override public void saveTrack(Track t) {
            tracks.removeIf(x -> x.getName().equals(t.getName())); tracks.add(t);
        }
        @Override public void deleteTrack(String name) { tracks.removeIf(t -> t.getName().equals(name)); }

        // logs
        @Override public void saveLog(long ts, String actor, String action, String target, String detail) {
            LogEntry.Action a;
            try { a = LogEntry.Action.valueOf(action); } catch (Exception e) { a = LogEntry.Action.GROUP_CREATE; }
            logs.add(new LogEntry(++logIdSeq, java.time.Instant.ofEpochSecond(ts), actor, a, target, detail));
        }
        @Override public List<LogEntry> loadRecentLogs(int limit) {
            int from = Math.max(0, logs.size() - limit);
            return new ArrayList<>(logs.subList(from, logs.size()));
        }
        @Override public List<LogEntry> loadLogsByTarget(String t, int l) { return List.of(); }
        @Override public List<LogEntry> loadLogsByActor(String a, int l) { return List.of(); }
        @Override public void deleteLogsOlderThan(long epochSecond) {
            logs.removeIf(e -> e.getTimestamp().getEpochSecond() < epochSecond);
        }

        // timed permissions
        @Override public List<TimedPermission> loadActiveTimedPermissions() {
            long now = java.time.Instant.now().getEpochSecond();
            return timedPerms.stream().filter(tp -> !tp.isExpired()).toList();
        }
        @Override public void saveTimedPermission(String target, boolean isGroup, String perm, long expiry) {
            timedPerms.add(new TimedPermission(target, isGroup, perm,
                    java.time.Instant.ofEpochSecond(expiry)));
        }
        @Override public void deleteExpiredTimedPermissions(long nowEpochSecond) {
            timedPerms.removeIf(tp -> tp.getExpiry().getEpochSecond() < nowEpochSecond);
        }
        @Override public void deleteTimedPermission(String target, String perm) {
            timedPerms.removeIf(tp -> tp.getTarget().equals(target) && tp.getPermission().equals(perm));
        }

        // context permissions
        @Override public List<ContextRow> loadAllContextPermissions() { return new ArrayList<>(ctxPerms); }
        @Override public void saveContextPermission(String target, boolean isGroup, String perm,
                                                     Context ctx, boolean granted) {
            ctxPerms.add(new ContextRow(target, isGroup,
                    new ContextualPermission(perm, ctx, granted)));
        }
        @Override public void saveContextPermission(String target, boolean isGroup, String perm,
                                                     ContextSet requiredCtx, boolean granted) {
            ctxPerms.add(new ContextRow(target, isGroup,
                    new ContextualPermission(perm, requiredCtx, granted)));
        }
        @Override public void deleteContextPermission(String target, String perm, Context ctx) {
            ctxPerms.removeIf(r -> r.target().equals(target)
                    && r.permission().getPermission().equals(perm)
                    && r.permission().getContext().equals(ctx));
        }
        @Override public void deleteContextPermission(String target, String perm, ContextSet requiredCtx) {
            ctxPerms.removeIf(r -> r.target().equals(target)
                    && r.permission().getPermission().equals(perm)
                    && r.permission().getRequiredContext().equals(requiredCtx));
        }

        // helpers
        private String key(String t, boolean ig) { return (ig ? "group:" : "user:") + t; }
        private Group getGroup(String name) { return groups.get(name.toLowerCase()); }
    }
}
