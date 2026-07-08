package ir.permscraft.api;

import ir.permscraft.api.node.Node;
import ir.permscraft.api.node.NodeType;
import ir.permscraft.context.ContextSet;
import ir.permscraft.models.Group;
import ir.permscraft.models.User;

import java.time.Duration;
import java.util.*;

/**
 * PermsCraft Public API — version 2.0
 *
 *
 *   PermsCraft: api.users().addNode(uuid, node)  ← simpler
 *               api.users().getCachedData(uuid).queryPermission("essentials.fly")
 *                   .sourceGroup()  ← which group gave this? (LP has no equivalent)
 *
 * New in v2.0:
 *   • Node builder API (ir.permscraft.api.node.Node)
 *   • CachedData with QueryResult + Diff
 *   • Context-aware permission queries
 *   • Timed group membership via API
 *   • Event registration helper
 *   • Tristate with UNDEFINED propagation
 *
 * Usage:
 *   PermsCraftAPI api = PermsCraftAPI.get();
 *
 *   // Add timed VIP with reason
 *   api.users().addNode(uuid,
 *       Node.group("vip")
 *           .expiry(Duration.ofDays(30))
 *           .reason("Purchased on shop")
 *           .build()
 *   );
 *
 *   // Query with source
 *   CachedData data = api.users().getCachedData(uuid);
 *   CachedData.QueryResult r = data.queryPermission("essentials.fly");
 *   // r.tristate() == TRUE, r.sourceGroup() == "admin", r.wildcardMatch() == false
 *
 *   // Diff permissions before/after
 *   CachedData before = api.users().getCachedData(uuid);
 *   api.users().addNode(uuid, Node.permission("foo.bar").build());
 *   CachedData after = api.users().getCachedData(uuid);
 *   CachedData.Diff diff = before.diff(after);
 *   diff.added()   // ["foo.bar"]
 *   diff.removed() // []
 */
public interface PermsCraftAPI {

    /** Get the singleton API instance. */
    static PermsCraftAPI get() {
        return ir.permscraft.PermsCraft.getInstance().getApi();
    }

    // ── Sub-managers ──────────────────────────────────────────────────────────

    PCUserManager  users();
    PCGroupManager groups();
    PCTrackManager tracks();
    PCContextManager contexts();

    /** @deprecated Use {@link #users()} */
    @Deprecated default PCUserManager  getUserManager()  { return users(); }
    /** @deprecated Use {@link #groups()} */
    @Deprecated default PCGroupManager getGroupManager() { return groups(); }

    // ═════════════════════════════════════════════════════════════════════════
    // User Manager
    // ═════════════════════════════════════════════════════════════════════════

    interface PCUserManager {

        // ── Basic lookup ──────────────────────────────────────────────────────

        Optional<User> getUser(UUID uuid);
        Optional<User> getUser(String username);

        /** Load user from storage if not cached (async-friendly). */
        java.util.concurrent.CompletableFuture<Optional<User>> loadUser(UUID uuid, String username);

        // ── Node API ──────────────────────────────────────────────────────────

        /**
         * Add any node (permission, group, timed, prefix, meta, etc.) to a user.
         * This is the primary write method — replaces all individual addPermission,
         * addToGroup, setPrefix, etc. calls when using the API.
         *
         * Fires {@link ir.permscraft.api.event.NodeAddEvent}.
         */
        void addNode(UUID uuid, Node node);

        /**
         * Remove a node from a user.
         * Fires {@link ir.permscraft.api.event.NodeRemoveEvent}.
         */
        boolean removeNode(UUID uuid, Node node);

        /** Remove a node by its raw storage string (e.g. "-essentials.fly", "group.admin"). */
        boolean removeNodeByString(UUID uuid, String rawNode);

        /** Get all nodes explicitly set on this user (not inherited). */
        List<Node> getNodes(UUID uuid);

        /** Get all nodes of a specific type set on this user. */
        List<Node> getNodes(UUID uuid, NodeType type);

        // ── Cached data ───────────────────────────────────────────────────────

        /**
         * Get the resolved, cached data for a user.
         * Includes inherited permissions, active context, prefix/suffix stacks,
         * timed nodes, and full diff capability.
         *
         * Returns {@code null} if the user is not loaded (offline and not cached).
         */
        CachedData getCachedData(UUID uuid);

        /**
         * Get cached data for a specific context.
         * PermsCraft exclusive — LP requires QueryOptions boilerplate.
         */
        CachedData getCachedData(UUID uuid, ContextSet context);

        // ── Convenience wrappers (kept for familiarity) ───────────────────────

        void addPermission(UUID uuid, String permission);
        void removePermission(UUID uuid, String permission);
        void addToGroup(UUID uuid, String groupName);
        void removeFromGroup(UUID uuid, String groupName);
        void addTimedPermission(UUID uuid, String permission, Duration duration);
        void addTimedGroup(UUID uuid, String groupName, Duration duration);
        void setPrefix(UUID uuid, String prefix);
        void setSuffix(UUID uuid, String suffix);
        void setMeta(UUID uuid, String key, String value);
        void clearUser(UUID uuid);

        // ── Permission checks ─────────────────────────────────────────────────

        /** Simple boolean permission check. */
        boolean hasPermission(UUID uuid, String permission);

        /** Tristate check — distinguishes UNDEFINED from explicitly FALSE. */
        Tristate checkPermission(UUID uuid, String permission);

        /**
         * Context-aware tristate check.
         * PermsCraft exclusive — check permission only in a specific context.
         *
         * Example:
         *   api.users().checkPermission(uuid, "essentials.fly",
         *       ContextSet.builder().put("world", "survival").build())
         */
        Tristate checkPermission(UUID uuid, String permission, ContextSet context);

        // ── Meta ─────────────────────────────────────────────────────────────

        String getPrimaryGroup(UUID uuid);
        String getPrefix(UUID uuid);
        String getSuffix(UUID uuid);
        Optional<String> getMeta(UUID uuid, String key);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Group Manager
    // ═════════════════════════════════════════════════════════════════════════

    interface PCGroupManager {

        Optional<Group> getGroup(String name);
        Collection<Group> getAllGroups();
        boolean groupExists(String name);
        Group createGroup(String name);
        void deleteGroup(String name);

        // ── Node API ──────────────────────────────────────────────────────────

        void addNode(String groupName, Node node);
        boolean removeNode(String groupName, Node node);
        List<Node> getNodes(String groupName);
        List<Node> getNodes(String groupName, NodeType type);

        // ── Cached data ───────────────────────────────────────────────────────

        /** Resolved data for a group (includes inherited permissions). */
        CachedData getCachedData(String groupName);

        // ── Convenience ───────────────────────────────────────────────────────

        void addPermission(String groupName, String permission);
        void removePermission(String groupName, String permission);
        void addParent(String groupName, String parent);
        void removeParent(String groupName, String parent);
        String getPrefix(String groupName);
        String getSuffix(String groupName);
        int getWeight(String groupName);
        void setWeight(String groupName, int weight);

        /**
         * Get the full inheritance chain for a group.
         * Returns groups in resolution order (parents first, child last).
         */
        List<String> getInheritanceChain(String groupName);

        /**
         * Check if groupA inherits from groupB (directly or transitively).
         * PermsCraft exclusive.
         */
        boolean inheritsFrom(String groupA, String groupB);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Track Manager
    // ═════════════════════════════════════════════════════════════════════════

    interface PCTrackManager {

        Optional<ir.permscraft.models.Track> getTrack(String name);
        Collection<ir.permscraft.models.Track> getAllTracks();
        boolean trackExists(String name);

        /**
         * Promote a user on a track.
         * @return the group the user was promoted to, or empty if at the top
         */
        Optional<String> promote(UUID uuid, String trackName, String actor);

        /**
         * Demote a user on a track.
         * @return the group the user was demoted to, or empty if at the bottom
         */
        Optional<String> demote(UUID uuid, String trackName, String actor);

        /** Get the next group on the track after the user's current group. */
        Optional<String> getNextGroup(UUID uuid, String trackName);

        /** Get the previous group on the track before the user's current group. */
        Optional<String> getPreviousGroup(UUID uuid, String trackName);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Context Manager
    // ═════════════════════════════════════════════════════════════════════════

    interface PCContextManager {

        /**
         * Get the active ContextSet for an online player right now.
         * Includes all registered calculators (world, gamemode, server, economy, etc.)
         */
        ContextSet getActiveContext(org.bukkit.entity.Player player);

        /**
         * Register a custom ContextCalculator.
         * PermsCraft exclusive — LP requires a service registration.
         *
         * Example:
         *   api.contexts().registerCalculator(new MyRegionContextCalculator());
         *   // Adds: region=pvp, region=spawn, etc. to player contexts
         */
        void registerCalculator(ir.permscraft.context.ContextCalculator calculator);

        /** Unregister a previously registered calculator. */
        void unregisterCalculator(ir.permscraft.context.ContextCalculator calculator);

        /** Get all registered calculators (built-in + custom). */
        List<ir.permscraft.context.ContextCalculator> getCalculators();
    }
}
