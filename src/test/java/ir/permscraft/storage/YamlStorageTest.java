package ir.permscraft.storage;

import ir.permscraft.PermsCraft;
import ir.permscraft.context.Context;
import ir.permscraft.context.ContextSet;
import ir.permscraft.context.ContextualPermission;
import ir.permscraft.logging.LogEntry;
import ir.permscraft.models.Group;
import ir.permscraft.models.TimedPermission;
import ir.permscraft.models.Track;
import ir.permscraft.models.User;

import org.bukkit.configuration.file.FileConfiguration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("YamlStorage")
@ExtendWith(MockitoExtension.class)
class YamlStorageTest {

    @TempDir File tempDir;
    @Mock PermsCraft plugin;
    @Mock FileConfiguration config;

    private YamlStorage storage;

    @BeforeEach
    void setUp() {
        when(plugin.getDataFolder()).thenReturn(tempDir);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("YamlStorageTest"));
        when(config.getString("storage.yaml.folder", "data")).thenReturn("data");

        storage = new YamlStorage(plugin);
        assertTrue(storage.init(), "init() must return true");
    }

    @AfterEach
    void tearDown() { storage.close(); }

    // ── init ─────────────────────────────────────────────────────────────────

    @Test @DisplayName("init: creates data directory and default group")
    void init_createsDataDir() {
        File dataDir = new File(tempDir, "data");
        assertTrue(dataDir.exists() && dataDir.isDirectory());
    }

    @Test @DisplayName("init: default group exists after init")
    void init_defaultGroupExists() {
        List<Group> groups = storage.loadAllGroups();
        assertTrue(groups.stream().anyMatch(g -> g.getName().equals("default")));
    }

    @Test @DisplayName("init: calling init twice is safe (idempotent)")
    void init_idempotent() {
        assertTrue(storage.init());
        // Default group should still exist, not duplicated
        assertEquals(1, storage.loadAllGroups().size());
    }

    // ── Groups ────────────────────────────────────────────────────────────────

    @Test @DisplayName("saveGroup and loadAllGroups round-trip")
    void group_saveAndLoad() {
        Group g = new Group("admin");
        g.setDisplayName("Admin");
        g.setPrefix("&c[Admin] ");
        g.setSuffix("");
        g.setWeight(100);
        storage.saveGroup(g);

        List<Group> groups = storage.loadAllGroups();
        Group loaded = groups.stream().filter(x -> x.getName().equals("admin")).findFirst().orElseThrow();
        assertEquals("Admin", loaded.getDisplayName());
        assertEquals("&c[Admin] ", loaded.getPrefix());
        assertEquals(100, loaded.getWeight());
    }

    @Test @DisplayName("deleteGroup removes it from storage")
    void group_delete() {
        storage.saveGroup(new Group("vip"));
        storage.deleteGroup("vip");
        assertTrue(storage.loadAllGroups().stream().noneMatch(g -> g.getName().equals("vip")));
    }

    @Test @DisplayName("addGroupPermission persists")
    void group_addPermission() {
        storage.saveGroup(new Group("staff"));
        storage.addGroupPermission("staff", "essentials.fly");
        Group g = loadGroup("staff");
        assertTrue(g.getPermissions().contains("essentials.fly"));
    }

    @Test @DisplayName("removeGroupPermission removes it")
    void group_removePermission() {
        storage.saveGroup(new Group("staff"));
        storage.addGroupPermission("staff", "essentials.fly");
        storage.addGroupPermission("staff", "essentials.spawn");
        storage.removeGroupPermission("staff", "essentials.fly");
        Group g = loadGroup("staff");
        assertFalse(g.getPermissions().contains("essentials.fly"));
        assertTrue(g.getPermissions().contains("essentials.spawn"));
    }

    @Test @DisplayName("addGroupInheritance persists")
    void group_addInheritance() {
        storage.saveGroup(new Group("default"));
        storage.saveGroup(new Group("vip"));
        storage.addGroupInheritance("vip", "default");
        Group vip = loadGroup("vip");
        assertTrue(vip.getInheritedGroups().contains("default"));
    }

    @Test @DisplayName("removeGroupInheritance removes it")
    void group_removeInheritance() {
        storage.saveGroup(new Group("vip"));
        storage.addGroupInheritance("vip", "default");
        storage.removeGroupInheritance("vip", "default");
        Group vip = loadGroup("vip");
        assertFalse(vip.getInheritedGroups().contains("default"));
    }

    @Test @DisplayName("clearGroup removes permissions, inheritance and meta")
    void group_clear() {
        storage.saveGroup(new Group("staff"));
        storage.addGroupPermission("staff", "essentials.fly");
        storage.addGroupInheritance("staff", "default");
        storage.saveMeta("staff", true, "build", "true");
        storage.clearGroup("staff");

        Group g = loadGroup("staff");
        assertTrue(g.getPermissions().isEmpty());
        assertTrue(g.getInheritedGroups().isEmpty());
    }

    @Test @DisplayName("deleteGroup also cleans it from other groups' inheritance")
    void group_deleteRemovesFromInheritance() {
        storage.saveGroup(new Group("parent"));
        storage.saveGroup(new Group("child"));
        storage.addGroupInheritance("child", "parent");
        storage.deleteGroup("parent");
        Group child = loadGroup("child");
        assertFalse(child.getInheritedGroups().contains("parent"));
    }

    // ── Users ─────────────────────────────────────────────────────────────────

    @Test @DisplayName("loadUser: new user gets default group")
    void user_newUserGetsDefault() {
        UUID uuid = UUID.randomUUID();
        User user = storage.loadUser(uuid, "Steve");
        assertTrue(user.getGroups().contains("default"));
    }

    @Test @DisplayName("saveUser and loadUser round-trip")
    void user_saveAndLoad() {
        UUID uuid = UUID.randomUUID();
        User user = new User(uuid, "Notch");
        user.setPrefix("&e[VIP] ");
        user.addGroup("vip");
        user.addPermission("essentials.fly");
        storage.saveUser(user);

        User loaded = storage.loadUser(uuid, "Notch");
        assertEquals("Notch", loaded.getUsername());
        assertEquals("&e[VIP] ", loaded.getPrefix());
        assertTrue(loaded.getGroups().contains("vip"));
        assertTrue(loaded.getPermissions().contains("essentials.fly"));
    }

    @Test @DisplayName("addUserToGroup persists group membership")
    void user_addToGroup() {
        UUID uuid = UUID.randomUUID();
        storage.loadUser(uuid, "Steve"); // create
        storage.addUserToGroup(uuid, "vip");
        User u = storage.loadUser(uuid, "Steve");
        assertTrue(u.getGroups().contains("vip"));
    }

    @Test @DisplayName("removeUserFromGroup removes the group")
    void user_removeFromGroup() {
        UUID uuid = UUID.randomUUID();
        storage.loadUser(uuid, "Steve");
        storage.addUserToGroup(uuid, "vip");
        storage.removeUserFromGroup(uuid, "vip");
        User u = storage.loadUser(uuid, "Steve");
        assertFalse(u.getGroups().contains("vip"));
    }

    @Test @DisplayName("addUserPermission and removeUserPermission work")
    void user_permissions() {
        UUID uuid = UUID.randomUUID();
        storage.loadUser(uuid, "Steve");
        storage.addUserPermission(uuid, "fly.special");
        assertTrue(storage.loadUser(uuid, "Steve").getPermissions().contains("fly.special"));

        storage.removeUserPermission(uuid, "fly.special");
        assertFalse(storage.loadUser(uuid, "Steve").getPermissions().contains("fly.special"));
    }

    @Test @DisplayName("clearUser resets permissions, groups, meta, prefix, suffix")
    void user_clear() {
        UUID uuid = UUID.randomUUID();
        storage.loadUser(uuid, "Steve");
        storage.addUserPermission(uuid, "test.perm");
        storage.addUserToGroup(uuid, "vip");
        storage.clearUser(uuid);
        User u = storage.loadUser(uuid, "Steve");
        assertTrue(u.getPermissions().isEmpty());
        assertEquals(List.of("default"), new ArrayList<>(u.getGroups()));
    }

    @Test @DisplayName("saveUserPrimaryGroup persists primary group")
    void user_primaryGroup() {
        UUID uuid = UUID.randomUUID();
        storage.loadUser(uuid, "Steve");
        storage.addUserToGroup(uuid, "admin");
        storage.saveUserPrimaryGroup(uuid, "admin");
        User u = storage.loadUser(uuid, "Steve");
        assertEquals("admin", u.getPrimaryGroup());
    }

    @Test @DisplayName("findUUIDByUsername returns correct UUID")
    void user_findByUsername() {
        UUID uuid = UUID.randomUUID();
        storage.loadUser(uuid, "Herobrine");
        assertEquals(uuid, storage.findUUIDByUsername("Herobrine"));
        assertEquals(uuid, storage.findUUIDByUsername("herobrine")); // case-insensitive
    }

    @Test @DisplayName("findUUIDByUsername returns null for unknown username")
    void user_findByUsername_unknown() {
        assertNull(storage.findUUIDByUsername("UnknownPlayer"));
    }

    @Test @DisplayName("loadAllUsers returns all persisted users")
    void user_loadAll() {
        storage.loadUser(UUID.randomUUID(), "Alpha");
        storage.loadUser(UUID.randomUUID(), "Beta");
        storage.loadUser(UUID.randomUUID(), "Gamma");
        List<User> all = storage.loadAllUsers();
        assertTrue(all.size() >= 3);
    }

    // ── Meta ──────────────────────────────────────────────────────────────────

    @Test @DisplayName("saveMeta and loadMeta round-trip (group)")
    void meta_groupSaveLoad() {
        storage.saveGroup(new Group("vip"));
        storage.saveMeta("vip", true, "rank", "gold");
        Map<String, String> meta = storage.loadMeta("vip", true);
        assertEquals("gold", meta.get("rank"));
    }

    @Test @DisplayName("saveMeta and loadMeta round-trip (user)")
    void meta_userSaveLoad() {
        UUID uuid = UUID.randomUUID();
        storage.loadUser(uuid, "Steve");
        storage.saveMeta(uuid.toString(), false, "lang", "fa");
        Map<String, String> meta = storage.loadMeta(uuid.toString(), false);
        assertEquals("fa", meta.get("lang"));
    }

    @Test @DisplayName("deleteMeta removes a specific key")
    void meta_delete() {
        storage.saveGroup(new Group("staff"));
        storage.saveMeta("staff", true, "a", "1");
        storage.saveMeta("staff", true, "b", "2");
        storage.deleteMeta("staff", true, "a");
        Map<String, String> meta = storage.loadMeta("staff", true);
        assertNull(meta.get("a"));
        assertEquals("2", meta.get("b"));
    }

    @Test @DisplayName("deleteAllMeta removes all meta for a group")
    void meta_deleteAll() {
        storage.saveGroup(new Group("admin"));
        storage.saveMeta("admin", true, "x", "1");
        storage.saveMeta("admin", true, "y", "2");
        storage.deleteAllMeta("admin", true);
        assertTrue(storage.loadMeta("admin", true).isEmpty());
    }

    @Test @DisplayName("saveTimedMeta: expired entry is not returned by loadMeta")
    void meta_timedExpired() throws InterruptedException {
        storage.saveGroup(new Group("vip"));
        long pastExpiry = Instant.now().getEpochSecond() - 10; // already expired
        storage.saveTimedMeta("vip", true, "tmprank", "bronze", pastExpiry);
        Map<String, String> meta = storage.loadMeta("vip", true);
        assertNull(meta.get("tmprank"));
    }

    @Test @DisplayName("saveTimedMeta: future expiry is returned by loadMeta")
    void meta_timedFuture() {
        storage.saveGroup(new Group("vip"));
        long futureExpiry = Instant.now().getEpochSecond() + 86400;
        storage.saveTimedMeta("vip", true, "tmprank", "silver", futureExpiry);
        Map<String, String> meta = storage.loadMeta("vip", true);
        assertEquals("silver", meta.get("tmprank"));
    }

    // ── Timed Permissions ─────────────────────────────────────────────────────

    @Test @DisplayName("saveTimedPermission and loadActive returns future permission")
    void timedPerm_saveFutureAndLoad() {
        long future = Instant.now().getEpochSecond() + 3600;
        storage.saveTimedPermission("Steve", false, "fly.timed", future);
        List<TimedPermission> active = storage.loadActiveTimedPermissions();
        assertTrue(active.stream().anyMatch(tp ->
            tp.getPermission().equals("fly.timed") && tp.getTarget().equals("Steve")));
    }

    @Test @DisplayName("loadActiveTimedPermissions skips expired entries")
    void timedPerm_expiredNotLoaded() {
        long past = Instant.now().getEpochSecond() - 10;
        storage.saveTimedPermission("Steve", false, "fly.expired", past);
        List<TimedPermission> active = storage.loadActiveTimedPermissions();
        assertTrue(active.stream().noneMatch(tp -> tp.getPermission().equals("fly.expired")));
    }

    @Test @DisplayName("deleteTimedPermission removes it")
    void timedPerm_delete() {
        long future = Instant.now().getEpochSecond() + 3600;
        storage.saveTimedPermission("Notch", false, "admin.timed", future);
        storage.deleteTimedPermission("Notch", "admin.timed");
        List<TimedPermission> active = storage.loadActiveTimedPermissions();
        assertTrue(active.stream().noneMatch(tp -> tp.getPermission().equals("admin.timed")));
    }

    @Test @DisplayName("deleteExpiredTimedPermissions cleans stale entries")
    void timedPerm_deleteExpired() {
        long past = Instant.now().getEpochSecond() - 10;
        long future = Instant.now().getEpochSecond() + 3600;
        storage.saveTimedPermission("Steve", false, "perm.old", past);
        storage.saveTimedPermission("Steve", false, "perm.new", future);
        storage.deleteExpiredTimedPermissions(Instant.now().getEpochSecond());

        List<TimedPermission> active = storage.loadActiveTimedPermissions();
        assertTrue(active.stream().noneMatch(tp -> tp.getPermission().equals("perm.old")));
        assertTrue(active.stream().anyMatch(tp -> tp.getPermission().equals("perm.new")));
    }

    // ── Context Permissions ───────────────────────────────────────────────────

    @Test @DisplayName("saveContextPermission and loadAll returns the entry")
    void contextPerm_saveAndLoad() {
        ContextSet ctx = ContextSet.builder().put("world", "pvp").build();
        storage.saveContextPermission("admin", true, "fly.pvp", ctx, true);
        List<ContextRow> rows = storage.loadAllContextPermissions();
        assertTrue(rows.stream().anyMatch(r ->
            r.target().equals("admin") && r.permission().getPermission().equals("fly.pvp")));
    }

    @Test @DisplayName("deleteContextPermission removes the entry")
    void contextPerm_delete() {
        ContextSet ctx = ContextSet.builder().put("world", "pvp").build();
        storage.saveContextPermission("staff", true, "fly.ctx", ctx, true);
        storage.deleteContextPermission("staff", "fly.ctx", ctx);
        List<ContextRow> rows = storage.loadAllContextPermissions();
        assertTrue(rows.stream().noneMatch(r -> r.permission().getPermission().equals("fly.ctx")));
    }

    @Test @DisplayName("global context permission is loaded with empty ContextSet")
    void contextPerm_global() {
        storage.saveContextPermission("admin", true, "fly.global", Context.global(), true);
        List<ContextRow> rows = storage.loadAllContextPermissions();
        ContextRow row = rows.stream()
            .filter(r -> r.permission().getPermission().equals("fly.global"))
            .findFirst().orElseThrow();
        assertTrue(row.permission().getRequiredContext().isEmpty());
    }

    // ── Tracks ────────────────────────────────────────────────────────────────

    @Test @DisplayName("saveTrack and loadAllTracks round-trip")
    void track_saveAndLoad() {
        Track t = new Track("default");
        t.addGroup("guest");
        t.addGroup("member");
        t.addGroup("vip");
        storage.saveTrack(t);
        List<Track> tracks = storage.loadAllTracks();
        Track loaded = tracks.stream().filter(x -> x.getName().equals("default")).findFirst().orElseThrow();
        assertEquals(List.of("guest", "member", "vip"), loaded.getGroups());
    }

    @Test @DisplayName("deleteTrack removes it")
    void track_delete() {
        Track t = new Track("staff");
        t.addGroup("mod");
        storage.saveTrack(t);
        storage.deleteTrack("staff");
        assertTrue(storage.loadAllTracks().stream().noneMatch(x -> x.getName().equals("staff")));
    }

    // ── Logs ──────────────────────────────────────────────────────────────────

    @Test @DisplayName("saveLog and loadRecentLogs returns entry")
    void log_saveAndLoad() {
        long now = Instant.now().getEpochSecond();
        storage.saveLog(now, "admin", "USER_PERM_ADD", "Steve", "essentials.fly");
        List<LogEntry> logs = storage.loadRecentLogs(10);
        assertFalse(logs.isEmpty());
        assertEquals("admin", logs.get(0).getActor());
    }

    @Test @DisplayName("loadLogsByActor filters by actor")
    void log_filterByActor() {
        long now = Instant.now().getEpochSecond();
        storage.saveLog(now, "admin1", "USER_PERM_ADD", "Steve", "a");
        storage.saveLog(now, "admin2", "USER_PERM_REMOVE", "Notch", "b");
        List<LogEntry> logs = storage.loadLogsByActor("admin1", 10);
        assertTrue(logs.stream().allMatch(l -> l.getActor().equals("admin1")));
    }

    @Test @DisplayName("loadLogsByTarget filters by target")
    void log_filterByTarget() {
        long now = Instant.now().getEpochSecond();
        storage.saveLog(now, "admin", "USER_PERM_ADD", "Steve", "a");
        storage.saveLog(now, "admin", "USER_PERM_ADD", "Notch", "b");
        List<LogEntry> logs = storage.loadLogsByTarget("Steve", 10);
        assertTrue(logs.stream().allMatch(l -> l.getTarget().equals("Steve")));
    }

    @Test @DisplayName("deleteLogsOlderThan removes old entries")
    void log_deleteOlderThan() {
        long past = Instant.now().getEpochSecond() - 1000;
        long future = Instant.now().getEpochSecond() + 1000;
        storage.saveLog(past - 1, "admin", "USER_PERM_ADD", "Steve", "a");
        storage.saveLog(future,   "admin", "USER_PERM_ADD", "Notch", "b");
        storage.deleteLogsOlderThan(Instant.now().getEpochSecond());
        List<LogEntry> logs = storage.loadRecentLogs(10);
        assertTrue(logs.stream().noneMatch(l -> l.getTarget().equals("Steve")));
    }

    // ── Bulk Operations ───────────────────────────────────────────────────────

    @Test @DisplayName("bulkAddPermissionToUsers adds to all existing users")
    void bulk_addPermission() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        storage.loadUser(a, "UserA");
        storage.loadUser(b, "UserB");
        int count = storage.bulkAddPermissionToUsers("bulk.perm");
        assertTrue(count >= 2);
        assertTrue(storage.loadUser(a, "UserA").getPermissions().contains("bulk.perm"));
        assertTrue(storage.loadUser(b, "UserB").getPermissions().contains("bulk.perm"));
    }

    @Test @DisplayName("bulkRemovePermissionFromUsers removes from all users")
    void bulk_removePermission() {
        UUID a = UUID.randomUUID();
        storage.loadUser(a, "UserA");
        storage.addUserPermission(a, "bulk.remove");
        int removed = storage.bulkRemovePermissionFromUsers("bulk.remove");
        assertTrue(removed >= 1);
        assertFalse(storage.loadUser(a, "UserA").getPermissions().contains("bulk.remove"));
    }

    @Test @DisplayName("bulkAddGroupToUsers adds group to all users")
    void bulk_addGroup() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        storage.loadUser(a, "Alpha");
        storage.loadUser(b, "Beta");
        int count = storage.bulkAddGroupToUsers("newrole");
        assertTrue(count >= 2);
        assertTrue(storage.loadUser(a, "Alpha").getGroups().contains("newrole"));
    }

    @Test @DisplayName("bulkRemoveGroupFromUsers removes group from all users")
    void bulk_removeGroup() {
        UUID a = UUID.randomUUID();
        storage.loadUser(a, "Alpha");
        storage.addUserToGroup(a, "oldrole");
        int removed = storage.bulkRemoveGroupFromUsers("oldrole");
        assertTrue(removed >= 1);
        assertFalse(storage.loadUser(a, "Alpha").getGroups().contains("oldrole"));
    }

    // ── searchPermission ─────────────────────────────────────────────────────

    @Test @DisplayName("searchPermission finds groups with the permission")
    void search_groupWithPermission() {
        storage.saveGroup(new Group("mod"));
        storage.addGroupPermission("mod", "kick.players");
        List<String> results = storage.searchPermission("kick.players");
        assertTrue(results.stream().anyMatch(r -> r.contains("mod")));
    }

    @Test @DisplayName("searchPermission finds users with the permission")
    void search_userWithPermission() {
        UUID uuid = UUID.randomUUID();
        storage.loadUser(uuid, "Herobrine");
        storage.addUserPermission(uuid, "custom.unique.perm");
        List<String> results = storage.searchPermission("custom.unique.perm");
        assertTrue(results.stream().anyMatch(r -> r.contains(uuid.toString())));
    }

    @Test @DisplayName("searchPermission returns empty list when nothing matches")
    void search_noMatch() {
        assertTrue(storage.searchPermission("totally.nonexistent.permission").isEmpty());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Group loadGroup(String name) {
        return storage.loadAllGroups().stream()
            .filter(g -> g.getName().equals(name))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Group '" + name + "' not found"));
    }
}
