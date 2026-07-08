package ir.permscraft.cache;

import ir.permscraft.FoliaScheduler;
import ir.permscraft.PermsCraft;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * High-performance permission cache.
 * Caches resolved permission sets per player/group so we don't
 * recalculate on every permission check.
 *
 * FIX Bug #2: The previous implementation cached user permissions with just
 * UUID as key. Context (per-world) permissions were layered on top AFTER the
 * cache was read, but were never stored — so when a player changed worlds the
 * cache still returned the old world's resolved set until TTL expired (up to
 * 5 minutes of wrong permissions).
 *
 * Fix: user cache key is now "uuid:worldName". When a player changes worlds,
 * invalidateUserInWorld() removes only that world's entry; other worlds stay
 * warm. invalidateUser(uuid) wipes ALL world entries for a user (used when
 * group/perm changes, not just world changes).
 */
public class PermissionCache {

    private final PermsCraft plugin;

    // "uuid:worldName" -> resolved permission set (includes context layer)
    private final Map<String, CacheEntry> userCache  = new ConcurrentHashMap<>();
    // groupName -> resolved permission set (no context — groups are world-agnostic)
    private final Map<String, CacheEntry> groupCache = new ConcurrentHashMap<>();

    // Cache TTL in ms (default 5 minutes)
    private final long ttlMs;

    /** Production constructor — requires a live Bukkit plugin. */
    public PermissionCache(PermsCraft plugin) {
        this.plugin = plugin;
        this.ttlMs  = plugin.getConfig().getLong("cache.ttl-seconds", 300) * 1000;
        startCleanupTask();
    }

    /**
     * Test-only constructor — no Bukkit, no scheduler.
     * Package-private so only test classes in the same package can use it.
     */
    PermissionCache(long ttlMs) {
        this.plugin = null;
        this.ttlMs  = ttlMs;
    }

    // ── User cache (world-aware) ──────────────────────────────────────────────

    /**
     * Look up cached permissions for a player in a specific world.
     * Returns null on miss or expiry (caller must recompute and call set).
     */
    public Set<String> getUserPermissions(UUID uuid, String worldName) {
        CacheEntry entry = userCache.get(userKey(uuid, worldName));
        if (entry == null || entry.isExpired()) return null;
        entry.hit();
        return entry.permissions;
    }

    /**
     * Store the fully-resolved permission set (base + context layer) for a
     * player in a specific world.
     */
    public void setUserPermissions(UUID uuid, String worldName, Set<String> permissions) {
        userCache.put(userKey(uuid, worldName), new CacheEntry(permissions, ttlMs));
    }

    /**
     * Invalidate all world entries for a user (used when their groups/perms
     * change — every world's resolved set is now stale).
     */
    public void invalidateUser(UUID uuid) {
        String prefix = uuid.toString() + ":";
        userCache.keySet().removeIf(k -> k.startsWith(prefix));
    }

    /**
     * FIX Bug #2: Invalidate only the specific world entry for a user.
     * Called from PlayerChangedWorldEvent so we recompute only the new world's
     * context layer instead of wiping all worlds.
     */
    public void invalidateUserInWorld(UUID uuid, String worldName) {
        userCache.remove(userKey(uuid, worldName));
    }

    public void invalidateAllUsers() {
        userCache.clear();
    }

    // ── Backward-compat shim (old callers that don't pass worldName) ──────────

    /**
     * Legacy single-arg getter — used by tests and callers without a world context.
     * Uses a virtual world key "__default__" so the entry is still stored/retrieved
     * correctly without breaking the world-aware cache.
     */
    public Set<String> getUserPermissions(UUID uuid) {
        return getUserPermissions(uuid, "__default__");
    }

    /**
     * Legacy single-arg setter — stores under the virtual "__default__" world key.
     * Used by tests and legacy callers. Production code should use the world-aware variant.
     */
    public void setUserPermissions(UUID uuid, Set<String> permissions) {
        setUserPermissions(uuid, "__default__", permissions);
    }

    // ── Group cache ───────────────────────────────────────────────────────────

    public Set<String> getGroupPermissions(String groupName) {
        CacheEntry entry = groupCache.get(groupName.toLowerCase());
        if (entry == null || entry.isExpired()) return null;
        entry.hit();
        return entry.permissions;
    }

    public void setGroupPermissions(String groupName, Set<String> permissions) {
        groupCache.put(groupName.toLowerCase(), new CacheEntry(permissions, ttlMs));
    }

    public void invalidateGroup(String groupName) {
        groupCache.remove(groupName.toLowerCase());
        // FIX (Bug #cache-spike): previously wiped the entire userCache on every
        // group change. On large servers this causes a thundering-herd.
        // Instead, only evict users who belong to this group.
        invalidateUsersInGroup(groupName.toLowerCase());
    }

    /**
     * FIX (Bug #cache-spike): Remove userCache entries for players in the given group.
     * Falls back to full wipe only if UserManager is not yet ready.
     */
    private void invalidateUsersInGroup(String groupNameLower) {
        if (plugin == null) { userCache.clear(); return; }
        try {
            plugin.getUserManager().getAllUsers().forEach(user -> {
                if (user.inGroup(groupNameLower)) {
                    String prefix = user.getUuid().toString() + ":";
                    userCache.keySet().removeIf(k -> k.startsWith(prefix));
                }
            });
        } catch (Exception e) {
            userCache.clear(); // safety net
        }
    }

    /**
     * Invalidate a group AND all child groups that inherit from it (BFS).
     */
    public void invalidateGroupAndChildren(String groupName,
            java.util.function.Supplier<java.util.Collection<ir.permscraft.models.Group>> allGroupsSupplier) {
        Set<String> toInvalidate = new HashSet<>();
        Queue<String> queue = new LinkedList<>();
        queue.add(groupName.toLowerCase());
        while (!queue.isEmpty()) {
            String current = queue.poll();
            allGroupsSupplier.get().forEach(g -> {
                if (g.getInheritedGroups().contains(current) &&
                        toInvalidate.add(g.getName().toLowerCase())) {
                    queue.add(g.getName().toLowerCase());
                }
            });
        }
        toInvalidate.forEach(name -> {
            groupCache.remove(name);
            invalidateUsersInGroup(name);
        });
        groupCache.remove(groupName.toLowerCase());
        invalidateUsersInGroup(groupName.toLowerCase());
    }

    public void invalidateAll() {
        userCache.clear();
        groupCache.clear();
    }

    // ── Stats ─────────────────────────────────────────────────────────────────

    public CacheStats getStats() {
        long totalHits = userCache.values().stream().mapToLong(e -> e.hits.get()).sum()
                + groupCache.values().stream().mapToLong(e -> e.hits.get()).sum();
        return new CacheStats(userCache.size(), groupCache.size(), totalHits);
    }

    public record CacheStats(int userEntries, int groupEntries, long totalHits) {
        @Override public String toString() {
            return "Users: " + userEntries + " | Groups: " + groupEntries + " | Hits: " + totalHits;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String userKey(UUID uuid, String worldName) {
        return uuid.toString() + ":" + worldName.toLowerCase();
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    private void startCleanupTask() {
        FoliaScheduler.runAsyncTimer(plugin, () -> {
            userCache.entrySet().removeIf(e -> e.getValue().isExpired());
            groupCache.entrySet().removeIf(e -> e.getValue().isExpired());
        }, 20L * 60, 20L * 60);
    }

    // ── CacheEntry ────────────────────────────────────────────────────────────

    private static class CacheEntry {
        final Set<String> permissions;
        final long expiresAt;
        // FIX (thread-safety): plain `long hits` with `hits++` is not atomic —
        // concurrent hasPermission() calls from multiple threads (REST API
        // workers, async tasks, main thread) could lose hit counts under
        // contention. This only affects the stats display, never correctness
        // of permission resolution, but the fix is cheap so do it properly.
        final java.util.concurrent.atomic.AtomicLong hits = new java.util.concurrent.atomic.AtomicLong();

        CacheEntry(Set<String> permissions, long ttlMs) {
            this.permissions = Collections.unmodifiableSet(new HashSet<>(permissions));
            this.expiresAt   = System.currentTimeMillis() + ttlMs;
        }

        boolean isExpired() { return System.currentTimeMillis() > expiresAt; }
        void hit() { hits.incrementAndGet(); }
    }
}
