package ir.permscraft.managers;

import ir.permscraft.models.TimedPermission;
import org.junit.jupiter.api.*;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the pure-logic layer of TimedPermissionManager:
 * the in-memory ConcurrentHashMap<String, CopyOnWriteArrayList<TimedPermission>>
 * without Bukkit scheduler or storage. We replicate that logic inline so there
 * is zero dependency on a live server.
 */
@DisplayName("TimedPermission in-memory logic")
class TimedPermissionManagerLogicTest {

    /**
     * Minimal in-memory replica of the manager's core data structure + methods
     * (extracted from TimedPermissionManager so we can test without Bukkit).
     */
    static class MemTimedManager {
        private final Map<String, CopyOnWriteArrayList<TimedPermission>> data =
                new ConcurrentHashMap<>();

        void add(String target, boolean isGroup, String perm, long durationSeconds) {
            Instant expiry = Instant.now().plusSeconds(durationSeconds);
            data.computeIfAbsent(target, k -> new CopyOnWriteArrayList<>())
                .add(new TimedPermission(target, isGroup, perm, expiry));
        }

        void addWithExpiry(String target, boolean isGroup, String perm, Instant expiry) {
            data.computeIfAbsent(target, k -> new CopyOnWriteArrayList<>())
                .add(new TimedPermission(target, isGroup, perm, expiry));
        }

        void remove(String target, String perm) {
            List<TimedPermission> list = data.get(target);
            if (list != null) list.removeIf(tp -> tp.getPermission().equalsIgnoreCase(perm));
        }

        Set<String> getActivePermissions(String target) {
            Set<String> perms = new HashSet<>();
            List<TimedPermission> list = data.get(target);
            if (list == null) return perms;
            for (TimedPermission tp : list) {
                if (!tp.isExpired()) perms.add(tp.getPermission());
            }
            return perms;
        }

        List<TimedPermission> getTimedPermissions(String target) {
            return data.getOrDefault(target, new CopyOnWriteArrayList<>());
        }

        void purgeExpired() {
            long now = Instant.now().getEpochSecond();
            data.values().forEach(list -> list.removeIf(tp ->
                    tp.getExpiry().getEpochSecond() <= now));
        }
    }

    private MemTimedManager mgr;

    @BeforeEach
    void setUp() {
        mgr = new MemTimedManager();
    }

    // ── add + getActive ───────────────────────────────────────────────────────

    @Test @DisplayName("added active permission appears in getActivePermissions")
    void add_appearsActive() {
        mgr.add("uuid-1", false, "essentials.fly", 3600);
        assertTrue(mgr.getActivePermissions("uuid-1").contains("essentials.fly"));
    }

    @Test @DisplayName("expired permission is excluded from getActivePermissions")
    void add_expiredExcluded() {
        mgr.addWithExpiry("uuid-1", false, "essentials.fly", Instant.now().minusSeconds(1));
        assertFalse(mgr.getActivePermissions("uuid-1").contains("essentials.fly"));
    }

    @Test @DisplayName("unknown target returns empty active set")
    void getActive_unknownTarget() {
        assertTrue(mgr.getActivePermissions("no-such-uuid").isEmpty());
    }

    @Test @DisplayName("multiple permissions for same target all appear")
    void add_multiplePerms() {
        mgr.add("uuid-1", false, "essentials.fly",  3600);
        mgr.add("uuid-1", false, "essentials.home", 3600);
        Set<String> active = mgr.getActivePermissions("uuid-1");
        assertTrue(active.contains("essentials.fly"));
        assertTrue(active.contains("essentials.home"));
    }

    @Test @DisplayName("permissions for different targets are isolated")
    void add_targetsIsolated() {
        mgr.add("uuid-A", false, "perm.a", 3600);
        mgr.add("uuid-B", false, "perm.b", 3600);
        assertFalse(mgr.getActivePermissions("uuid-A").contains("perm.b"));
        assertFalse(mgr.getActivePermissions("uuid-B").contains("perm.a"));
    }

    // ── remove ────────────────────────────────────────────────────────────────

    @Test @DisplayName("remove eliminates the specific permission")
    void remove_eliminesPerm() {
        mgr.add("uuid-1", false, "essentials.fly", 3600);
        mgr.remove("uuid-1", "essentials.fly");
        assertFalse(mgr.getActivePermissions("uuid-1").contains("essentials.fly"));
    }

    @Test @DisplayName("remove is case-insensitive")
    void remove_caseInsensitive() {
        mgr.add("uuid-1", false, "Essentials.Fly", 3600);
        mgr.remove("uuid-1", "essentials.fly");
        assertTrue(mgr.getActivePermissions("uuid-1").isEmpty());
    }

    @Test @DisplayName("remove does not affect other permissions on same target")
    void remove_doesNotAffectOthers() {
        mgr.add("uuid-1", false, "essentials.fly",  3600);
        mgr.add("uuid-1", false, "essentials.home", 3600);
        mgr.remove("uuid-1", "essentials.fly");
        assertTrue(mgr.getActivePermissions("uuid-1").contains("essentials.home"));
    }

    @Test @DisplayName("remove on unknown target does not throw")
    void remove_unknownTarget() {
        assertDoesNotThrow(() -> mgr.remove("nobody", "perm"));
    }

    // ── getTimedPermissions ───────────────────────────────────────────────────

    @Test @DisplayName("getTimedPermissions includes both active and expired entries")
    void getTimedPerms_includesExpired() {
        mgr.add("uuid-1", false, "perm.active", 3600);
        mgr.addWithExpiry("uuid-1", false, "perm.expired", Instant.now().minusSeconds(1));
        assertEquals(2, mgr.getTimedPermissions("uuid-1").size());
    }

    @Test @DisplayName("getTimedPermissions returns empty list for unknown target")
    void getTimedPerms_unknownTarget() {
        assertTrue(mgr.getTimedPermissions("nobody").isEmpty());
    }

    // ── purgeExpired ──────────────────────────────────────────────────────────

    @Test @DisplayName("purgeExpired removes expired entries from memory")
    void purgeExpired_removesExpired() {
        mgr.addWithExpiry("uuid-1", false, "perm.old", Instant.now().minusSeconds(10));
        mgr.add("uuid-1", false, "perm.active", 3600);
        mgr.purgeExpired();
        assertEquals(1, mgr.getTimedPermissions("uuid-1").size());
        assertEquals("perm.active", mgr.getTimedPermissions("uuid-1").get(0).getPermission());
    }

    @Test @DisplayName("purgeExpired does not remove active entries")
    void purgeExpired_keepsActive() {
        mgr.add("uuid-1", false, "perm.active", 3600);
        mgr.purgeExpired();
        assertEquals(1, mgr.getTimedPermissions("uuid-1").size());
    }

    // ── isGroup flag ─────────────────────────────────────────────────────────

    @Test @DisplayName("group timed permission isGroup flag is stored correctly")
    void groupFlag_stored() {
        mgr.add("admin", true, "some.perm", 3600);
        TimedPermission tp = mgr.getTimedPermissions("admin").get(0);
        assertTrue(tp.isGroup());
    }

    @Test @DisplayName("user timed permission isGroup flag is false")
    void userFlag_false() {
        mgr.add("uuid-1", false, "some.perm", 3600);
        assertFalse(mgr.getTimedPermissions("uuid-1").get(0).isGroup());
    }

    // ── concurrent access ─────────────────────────────────────────────────────

    @Test @DisplayName("concurrent adds from multiple threads do not lose entries")
    void concurrent_adds() throws InterruptedException {
        int threads = 10;
        Thread[] workers = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            workers[i] = new Thread(() ->
                mgr.add("uuid-1", false, "perm." + idx, 3600));
        }
        for (Thread t : workers) t.start();
        for (Thread t : workers) t.join();
        assertEquals(threads, mgr.getTimedPermissions("uuid-1").size());
    }
}
