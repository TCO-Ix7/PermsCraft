package ir.permscraft.cache;

import org.junit.jupiter.api.*;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Extended unit tests for PermissionCache.
 * Uses the package-private test constructor (no Bukkit, no scheduler).
 * Complements PermissionCacheTest with additional scenarios.
 */
@DisplayName("PermissionCache (extended)")
class PermissionCacheExtendedTest {

    // Long TTL so entries don't expire during tests
    private PermissionCache cache;

    @BeforeEach
    void setUp() {
        cache = new PermissionCache(60_000L); // 60 second TTL
    }

    // ── User cache: world-aware ───────────────────────────────────────────────

    @Test @DisplayName("setUserPermissions and getUserPermissions round-trip")
    void user_setAndGet() {
        UUID uuid = UUID.randomUUID();
        Set<String> perms = Set.of("essentials.fly", "vault.balance");
        cache.setUserPermissions(uuid, "survival", perms);
        assertEquals(perms, cache.getUserPermissions(uuid, "survival"));
    }

    @Test @DisplayName("getUserPermissions returns null on cache miss (unknown uuid)")
    void user_missUnknownUuid() {
        assertNull(cache.getUserPermissions(UUID.randomUUID(), "survival"));
    }

    @Test @DisplayName("getUserPermissions returns null on cache miss (unknown world)")
    void user_missUnknownWorld() {
        UUID uuid = UUID.randomUUID();
        cache.setUserPermissions(uuid, "survival", Set.of("fly"));
        assertNull(cache.getUserPermissions(uuid, "nether"));
    }

    @Test @DisplayName("different worlds are isolated for same user")
    void user_worldIsolation() {
        UUID uuid = UUID.randomUUID();
        cache.setUserPermissions(uuid, "survival", Set.of("survival.perm"));
        cache.setUserPermissions(uuid, "pvp",      Set.of("pvp.perm"));

        assertTrue(cache.getUserPermissions(uuid, "survival").contains("survival.perm"));
        assertFalse(cache.getUserPermissions(uuid, "survival").contains("pvp.perm"));
        assertTrue(cache.getUserPermissions(uuid, "pvp").contains("pvp.perm"));
    }

    @Test @DisplayName("different users are isolated in the same world")
    void user_uuidIsolation() {
        UUID u1 = UUID.randomUUID(), u2 = UUID.randomUUID();
        cache.setUserPermissions(u1, "survival", Set.of("u1.perm"));
        cache.setUserPermissions(u2, "survival", Set.of("u2.perm"));

        assertTrue(cache.getUserPermissions(u1, "survival").contains("u1.perm"));
        assertFalse(cache.getUserPermissions(u1, "survival").contains("u2.perm"));
    }

    @Test @DisplayName("overwriting an entry replaces the old set")
    void user_overwrite() {
        UUID uuid = UUID.randomUUID();
        cache.setUserPermissions(uuid, "survival", Set.of("old.perm"));
        cache.setUserPermissions(uuid, "survival", Set.of("new.perm"));
        Set<String> result = cache.getUserPermissions(uuid, "survival");
        assertFalse(result.contains("old.perm"));
        assertTrue(result.contains("new.perm"));
    }

    // ── User cache: invalidation ──────────────────────────────────────────────

    @Test @DisplayName("invalidateUser clears all worlds for that user")
    void user_invalidate_allWorlds() {
        UUID uuid = UUID.randomUUID();
        cache.setUserPermissions(uuid, "survival", Set.of("a"));
        cache.setUserPermissions(uuid, "nether",   Set.of("b"));
        cache.invalidateUser(uuid);
        assertNull(cache.getUserPermissions(uuid, "survival"));
        assertNull(cache.getUserPermissions(uuid, "nether"));
    }

    @Test @DisplayName("invalidateUser does not affect other users")
    void user_invalidate_otherUsersUnaffected() {
        UUID u1 = UUID.randomUUID(), u2 = UUID.randomUUID();
        cache.setUserPermissions(u1, "survival", Set.of("u1.perm"));
        cache.setUserPermissions(u2, "survival", Set.of("u2.perm"));
        cache.invalidateUser(u1);
        assertNull(cache.getUserPermissions(u1, "survival"));
        assertNotNull(cache.getUserPermissions(u2, "survival"));
    }

    @Test @DisplayName("invalidateUserInWorld clears only that world")
    void user_invalidateInWorld() {
        UUID uuid = UUID.randomUUID();
        cache.setUserPermissions(uuid, "survival", Set.of("a"));
        cache.setUserPermissions(uuid, "nether",   Set.of("b"));
        cache.invalidateUserInWorld(uuid, "survival");
        assertNull(cache.getUserPermissions(uuid, "survival"));
        assertNotNull(cache.getUserPermissions(uuid, "nether"));
    }

    @Test @DisplayName("invalidateAll clears all user and group entries")
    void invalidateAll_clearsEverything() {
        UUID u1 = UUID.randomUUID(), u2 = UUID.randomUUID();
        cache.setUserPermissions(u1, "survival", Set.of("a"));
        cache.setUserPermissions(u2, "nether",   Set.of("b"));
        cache.setGroupPermissions("vip", Set.of("c"));
        cache.invalidateAll();
        assertNull(cache.getUserPermissions(u1, "survival"));
        assertNull(cache.getUserPermissions(u2, "nether"));
        assertNull(cache.getGroupPermissions("vip"));
    }

    // ── World-agnostic user cache ─────────────────────────────────────────────

    @Test @DisplayName("setUserPermissions (no world) and getUserPermissions (no world) round-trip")
    void user_noWorld_setAndGet() {
        UUID uuid = UUID.randomUUID();
        cache.setUserPermissions(uuid, Set.of("global.perm"));
        assertNotNull(cache.getUserPermissions(uuid));
        assertTrue(cache.getUserPermissions(uuid).contains("global.perm"));
    }

    @Test @DisplayName("world-agnostic and world-aware entries don't collide")
    void user_noWorldVsWorld_noCollision() {
        UUID uuid = UUID.randomUUID();
        cache.setUserPermissions(uuid, Set.of("global.perm"));
        cache.setUserPermissions(uuid, "survival", Set.of("survival.perm"));

        assertTrue(cache.getUserPermissions(uuid).contains("global.perm"));
        assertFalse(cache.getUserPermissions(uuid).contains("survival.perm"));
    }

    // ── Group cache ───────────────────────────────────────────────────────────

    @Test @DisplayName("setGroupPermissions and getGroupPermissions round-trip")
    void group_setAndGet() {
        cache.setGroupPermissions("admin", Set.of("admin.kick", "admin.ban"));
        Set<String> result = cache.getGroupPermissions("admin");
        assertNotNull(result);
        assertTrue(result.contains("admin.kick"));
        assertTrue(result.contains("admin.ban"));
    }

    @Test @DisplayName("getGroupPermissions returns null for unknown group")
    void group_missUnknown() {
        assertNull(cache.getGroupPermissions("nonexistent"));
    }

    @Test @DisplayName("different groups are isolated")
    void group_isolation() {
        cache.setGroupPermissions("vip",   Set.of("vip.fly"));
        cache.setGroupPermissions("admin", Set.of("admin.kick"));
        assertFalse(cache.getGroupPermissions("vip").contains("admin.kick"));
        assertFalse(cache.getGroupPermissions("admin").contains("vip.fly"));
    }

    @Test @DisplayName("invalidateGroup clears only that group")
    void group_invalidate() {
        cache.setGroupPermissions("vip",   Set.of("vip.fly"));
        cache.setGroupPermissions("admin", Set.of("admin.kick"));
        cache.invalidateGroup("vip");
        assertNull(cache.getGroupPermissions("vip"));
        assertNotNull(cache.getGroupPermissions("admin"));
    }

    @Test @DisplayName("overwriting group entry replaces old permissions")
    void group_overwrite() {
        cache.setGroupPermissions("staff", Set.of("old.perm"));
        cache.setGroupPermissions("staff", Set.of("new.perm"));
        assertFalse(cache.getGroupPermissions("staff").contains("old.perm"));
        assertTrue(cache.getGroupPermissions("staff").contains("new.perm"));
    }

    // ── CacheStats ────────────────────────────────────────────────────────────

    @Test @DisplayName("getStats returns non-null CacheStats")
    void stats_notNull() {
        assertNotNull(cache.getStats());
    }

    @Test @DisplayName("getStats: userEntries count increases when users are cached")
    void stats_userEntries() {
        int before = cache.getStats().userEntries();
        cache.setUserPermissions(UUID.randomUUID(), "survival", Set.of("a"));
        assertTrue(cache.getStats().userEntries() > before);
    }

    @Test @DisplayName("getStats: groupEntries count increases when groups are cached")
    void stats_groupEntries() {
        int before = cache.getStats().groupEntries();
        cache.setGroupPermissions("teststats", Set.of("x"));
        assertTrue(cache.getStats().groupEntries() > before);
    }

    @Test @DisplayName("getStats: toString is non-null and non-empty")
    void stats_toString() {
        cache.setUserPermissions(UUID.randomUUID(), "survival", Set.of("a"));
        String str = cache.getStats().toString();
        assertNotNull(str);
        assertFalse(str.isBlank());
    }

    // ── TTL expiry ────────────────────────────────────────────────────────────

    @Test @DisplayName("entry expires after TTL elapses")
    void ttl_expiry() throws InterruptedException {
        PermissionCache shortTtl = new PermissionCache(50L); // 50ms TTL
        UUID uuid = UUID.randomUUID();
        shortTtl.setUserPermissions(uuid, "survival", Set.of("temp.perm"));
        assertNotNull(shortTtl.getUserPermissions(uuid, "survival")); // immediate hit

        Thread.sleep(100); // wait for expiry
        assertNull(shortTtl.getUserPermissions(uuid, "survival")); // should be expired
    }

    @Test @DisplayName("entry is still alive just before TTL elapses")
    void ttl_stillAliveBeforeExpiry() throws InterruptedException {
        PermissionCache longTtl = new PermissionCache(5_000L); // 5s TTL
        UUID uuid = UUID.randomUUID();
        longTtl.setUserPermissions(uuid, "survival", Set.of("live.perm"));
        Thread.sleep(10); // tiny wait, well within TTL
        assertNotNull(longTtl.getUserPermissions(uuid, "survival")); // should still be alive
    }

    // ── Edge cases ────────────────────────────────────────────────────────────

    @Test @DisplayName("empty permission set is stored and returned correctly")
    void user_emptyPermSet() {
        UUID uuid = UUID.randomUUID();
        cache.setUserPermissions(uuid, "survival", Set.of());
        Set<String> result = cache.getUserPermissions(uuid, "survival");
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test @DisplayName("large permission set is stored correctly")
    void user_largePermSet() {
        UUID uuid = UUID.randomUUID();
        Set<String> large = new java.util.HashSet<>();
        for (int i = 0; i < 1000; i++) large.add("perm.node." + i);
        cache.setUserPermissions(uuid, "survival", large);
        assertEquals(large, cache.getUserPermissions(uuid, "survival"));
    }

    @Test @DisplayName("many users can be cached simultaneously")
    void user_manyUsers() {
        for (int i = 0; i < 100; i++) {
            cache.setUserPermissions(UUID.randomUUID(), "survival", Set.of("perm." + i));
        }
        assertTrue(cache.getStats().userEntries() >= 100);
    }

    @Test @DisplayName("invalidateAll after many entries leaves cache empty")
    void user_invalidateAll_afterMany() {
        for (int i = 0; i < 50; i++) {
            cache.setUserPermissions(UUID.randomUUID(), "survival", Set.of("p." + i));
            cache.setGroupPermissions("group" + i, Set.of("g." + i));
        }
        cache.invalidateAll();
        assertEquals(0, cache.getStats().userEntries());
        assertEquals(0, cache.getStats().groupEntries());
    }
}
