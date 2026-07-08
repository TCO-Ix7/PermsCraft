package ir.permscraft.cache;

import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PermissionCache")
class PermissionCacheTest {

    private PermissionCache cache;

    @BeforeEach
    void setUp() {
        cache = new PermissionCache(60_000L); // 60 second TTL
    }

    // ── user cache ────────────────────────────────────────────────────────────

    @Test @DisplayName("setUserPermissions then getUserPermissions returns the set")
    void user_setAndGet() {
        UUID uuid = UUID.randomUUID();
        Set<String> perms = Set.of("essentials.fly", "essentials.home");
        cache.setUserPermissions(uuid, perms);
        Set<String> result = cache.getUserPermissions(uuid);
        assertNotNull(result);
        assertTrue(result.containsAll(perms));
    }

    @Test @DisplayName("getUserPermissions returns null for unknown UUID")
    void user_getMissing() {
        assertNull(cache.getUserPermissions(UUID.randomUUID()));
    }

    @Test @DisplayName("invalidateUser removes the cached entry")
    void user_invalidate() {
        UUID uuid = UUID.randomUUID();
        cache.setUserPermissions(uuid, Set.of("essentials.fly"));
        cache.invalidateUser(uuid);
        assertNull(cache.getUserPermissions(uuid));
    }

    @Test @DisplayName("invalidateAllUsers removes all entries")
    void user_invalidateAll() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        cache.setUserPermissions(a, Set.of("perm.a"));
        cache.setUserPermissions(b, Set.of("perm.b"));
        cache.invalidateAllUsers();
        assertNull(cache.getUserPermissions(a));
        assertNull(cache.getUserPermissions(b));
    }

    @Test @DisplayName("cached set is unmodifiable (defensive copy was made)")
    void user_immutableCopy() {
        UUID uuid = UUID.randomUUID();
        cache.setUserPermissions(uuid, new HashSet<>(Set.of("perm")));
        Set<String> cached = cache.getUserPermissions(uuid);
        assertThrows(UnsupportedOperationException.class, () -> cached.add("hacked"));
    }

    // ── group cache ───────────────────────────────────────────────────────────

    @Test @DisplayName("setGroupPermissions then getGroupPermissions returns the set")
    void group_setAndGet() {
        cache.setGroupPermissions("admin", Set.of("*"));
        assertNotNull(cache.getGroupPermissions("admin"));
    }

    @Test @DisplayName("group cache lookup is case-insensitive")
    void group_caseInsensitive() {
        cache.setGroupPermissions("Admin", Set.of("perm.x"));
        assertNotNull(cache.getGroupPermissions("admin"));
        assertNotNull(cache.getGroupPermissions("ADMIN"));
    }

    @Test @DisplayName("invalidateGroup removes group and all users")
    void group_invalidate_clearsUsers() {
        UUID uuid = UUID.randomUUID();
        cache.setGroupPermissions("vip", Set.of("perm.vip"));
        cache.setUserPermissions(uuid, Set.of("perm.x"));
        cache.invalidateGroup("vip");
        assertNull(cache.getGroupPermissions("vip"));
        assertNull(cache.getUserPermissions(uuid)); // users also cleared
    }

    @Test @DisplayName("getGroupPermissions returns null for unknown group")
    void group_getMissing() {
        assertNull(cache.getGroupPermissions("nonexistent"));
    }

    // ── invalidateAll ─────────────────────────────────────────────────────────

    @Test @DisplayName("invalidateAll clears both user and group caches")
    void invalidateAll() {
        UUID uuid = UUID.randomUUID();
        cache.setUserPermissions(uuid, Set.of("perm"));
        cache.setGroupPermissions("admin", Set.of("*"));
        cache.invalidateAll();
        assertNull(cache.getUserPermissions(uuid));
        assertNull(cache.getGroupPermissions("admin"));
    }

    // ── stats ─────────────────────────────────────────────────────────────────

    @Test @DisplayName("getStats reflects number of cached entries")
    void stats_entryCount() {
        cache.setUserPermissions(UUID.randomUUID(), Set.of("p"));
        cache.setGroupPermissions("admin", Set.of("*"));
        PermissionCache.CacheStats stats = cache.getStats();
        assertEquals(1, stats.userEntries());
        assertEquals(1, stats.groupEntries());
    }

    @Test @DisplayName("stats toString is non-null and contains 'Users'")
    void stats_toString() {
        String s = cache.getStats().toString();
        assertNotNull(s);
        assertTrue(s.contains("Users"));
    }

    // ── expiry ────────────────────────────────────────────────────────────────

    @Test @DisplayName("entry expires after TTL elapses")
    void user_expiry() throws Exception {
        PermissionCache shortTtlCache = new PermissionCache(1L); // 1ms TTL
        UUID uuid = UUID.randomUUID();
        shortTtlCache.setUserPermissions(uuid, Set.of("perm"));
        Thread.sleep(5); // let it expire
        assertNull(shortTtlCache.getUserPermissions(uuid));
    }

    @Test @DisplayName("entry does not expire before TTL")
    void user_notExpiredYet() {
        PermissionCache longTtlCache = new PermissionCache(60_000L);
        UUID uuid = UUID.randomUUID();
        longTtlCache.setUserPermissions(uuid, Set.of("perm"));
        assertNotNull(longTtlCache.getUserPermissions(uuid));
    }

    @Test @DisplayName("group entry expires after TTL")
    void group_expiry() throws Exception {
        PermissionCache shortTtlCache = new PermissionCache(1L);
        shortTtlCache.setGroupPermissions("admin", Set.of("*"));
        Thread.sleep(5);
        assertNull(shortTtlCache.getGroupPermissions("admin"));
    }
}
