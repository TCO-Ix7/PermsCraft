package ir.permscraft.managers;

import ir.permscraft.FoliaScheduler;
import ir.permscraft.api.event.EventBus;
import ir.permscraft.api.event.PermsCraftEvent;
import ir.permscraft.api.node.Node;
import ir.permscraft.PermsCraft;
import ir.permscraft.models.Group;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class GroupManager {

    private final PermsCraft plugin;
    // FIX (Bug #race): groups was a plain ConcurrentHashMap. loadGroups() calls
    // groups.clear() then re-populates in two separate steps. If a concurrent
    // reader (e.g. a Redis callback calling getGroup()) ran between clear() and
    // the re-insert loop it could see an empty or partially-populated map.
    // Solution: swap the entire reference atomically. Readers always see either
    // the old complete map or the new complete map, never a partial state.
    private volatile Map<String, Group> groups = new ConcurrentHashMap<>();

    public GroupManager(PermsCraft plugin) {
        this.plugin = plugin;
    }

    public void loadGroups() {
        List<Group> loaded = plugin.getStorage().loadAllGroups();
        // FIX (Bug #race): build a fresh map, then replace the reference atomically.
        // This way concurrent readers (including Redis/BungeeCord callbacks) always
        // see a fully-populated snapshot — never a half-cleared intermediate state.
        Map<String, Group> fresh = new ConcurrentHashMap<>();
        for (Group g : loaded) fresh.put(g.getName().toLowerCase(), g);
        this.groups = fresh;
        plugin.getLogger().info("[PermsCraft] Loaded " + groups.size() + " groups.");
    }

    public Group getGroup(String name) {
        if (name == null) return null;
        return groups.get(name.toLowerCase());
    }

    public Collection<Group> getAllGroups() { return groups.values(); }

    public boolean groupExists(String name) {
        return name != null && groups.containsKey(name.toLowerCase());
    }

    public Group createGroup(String name) {
        Group g = new Group(name.toLowerCase());
        groups.put(name.toLowerCase(), g);
        // FIX: async DB write — avoid blocking main thread
        FoliaScheduler.runAsync(plugin, () ->
                plugin.getStorage().saveGroup(g));
        return g;
    }

    /**
     * FIX (Bug #3 — /pc group rename didn't actually rename the group):
     * the previous implementation only changed Group#displayName, leaving
     * the real group key (used as the storage primary key, in-memory map
     * key, every user's membership/primary-group string, every other
     * group's inheritance reference, and every track's group list)
     * completely untouched — so the group remained accessible only under
     * its old name despite the command claiming success.
     *
     * Real rename requires migrating the key everywhere it's the source of
     * truth. {@code Group#name} is immutable by design (it's used as a map
     * key throughout the codebase), so a true rename means: create a new
     * Group under the new name with identical data, repoint every
     * reference at it, then remove the old Group — all via the same
     * already-verified manager methods used elsewhere (no new untested
     * storage primitives), to keep this safe even though it touches a lot
     * of data.
     *
     * This walks every known user (loadAllUsers()) to migrate membership,
     * so it can be slow on very large user bases — it always runs off the
     * calling thread.
     *
     * @return a CompletableFuture completing with the new Group once the
     *         full migration (including all users) has finished.
     */
    public java.util.concurrent.CompletableFuture<Group> renameGroup(String oldName, String newName) {
        java.util.concurrent.CompletableFuture<Group> future = new java.util.concurrent.CompletableFuture<>();
        String oldLower = oldName.toLowerCase();
        String newLower = newName.toLowerCase();

        Group original = getGroup(oldLower);
        if (original == null) {
            future.completeExceptionally(new IllegalArgumentException("Group '" + oldName + "' not found."));
            return future;
        }
        if (groupExists(newLower)) {
            future.completeExceptionally(new IllegalArgumentException("Group '" + newName + "' already exists."));
            return future;
        }
        if (oldLower.equals("default")) {
            future.completeExceptionally(new IllegalArgumentException("Cannot rename the 'default' group."));
            return future;
        }

        // ── Step 1: create the new group with identical data (in-memory + async DB) ──
        Group renamed = createGroup(newLower);
        renamed.setDisplayName(original.getDisplayName().equals(original.getName())
                ? newLower : original.getDisplayName());
        renamed.setPrefix(original.getPrefix());
        renamed.setSuffix(original.getSuffix());
        renamed.setWeight(original.getWeight());
        original.getPermissions().forEach(p -> addPermission(newLower, p));
        original.getInheritedGroups().forEach(parent -> addInheritance(newLower, parent));
        original.getMeta().forEach((k, v) -> setMeta(newLower, k, v));

        // ── Step 2 onward: heavy, multi-user migration work — off the calling thread ──
        FoliaScheduler.runAsync(plugin, () -> {
            try {
                // Repoint every OTHER group's inheritance that pointed at the old name.
                for (Group g : new ArrayList<>(getAllGroups())) {
                    if (g.getName().equals(newLower)) continue; // skip the group we just created
                    if (g.getInheritedGroups().contains(oldLower)) {
                        addInheritance(g.getName(), newLower);
                        removeInheritance(g.getName(), oldLower);
                    }
                }

                // Repoint every track that listed the old group name.
                for (var track : plugin.getTrackManager().getAllTracks()) {
                    if (track.containsGroup(oldLower)) {
                        plugin.getTrackManager().addGroupToTrack(track.getName(), newLower);
                        plugin.getTrackManager().removeGroupFromTrack(track.getName(), oldLower);
                    }
                }

                // Migrate every user's membership (online + offline). Order matters:
                // add the new membership BEFORE removing the old one, so
                // UserManager.removeFromGroup() never sees a user with zero
                // groups and doesn't trigger its "re-add to default" fallback.
                for (var user : plugin.getStorage().loadAllUsers()) {
                    if (!user.getGroups().contains(oldLower)) continue;
                    boolean wasPrimary = oldLower.equals(user.getPrimaryGroup());
                    plugin.getUserManager().addToGroup(user.getUuid(), newLower);
                    plugin.getUserManager().removeFromGroup(user.getUuid(), oldLower);
                    if (wasPrimary) {
                        // switchPrimaryGroup() updates + persists the primary group for an
                        // ONLINE user (no-op if offline, since it only mutates the cached
                        // in-memory User). Call it first for the online case, then persist
                        // directly only if the user wasn't in memory — covering offline users
                        // without double-writing for online ones.
                        boolean wasOnline = plugin.getUserManager().getUser(user.getUuid()) != null;
                        plugin.getUserManager().switchPrimaryGroup(user.getUuid(), newLower);
                        if (!wasOnline) {
                            plugin.getStorage().saveUserPrimaryGroup(user.getUuid(), newLower);
                        }
                    }
                }

                // Finally remove the old group now that nothing references it.
                deleteGroup(oldLower);

                FoliaScheduler.runSync(plugin, () -> future.complete(renamed));
            } catch (Exception ex) {
                plugin.getLogger().severe("[PermsCraft] renameGroup migration failed for '"
                        + oldLower + "' -> '" + newLower + "': " + ex.getMessage());
                FoliaScheduler.runSync(plugin, () -> future.completeExceptionally(ex));
            }
        });

        return future;
    }

    public void deleteGroup(String name) {
        groups.remove(name.toLowerCase());
        // FIX: async DB write
        FoliaScheduler.runAsync(plugin, () ->
                plugin.getStorage().deleteGroup(name.toLowerCase()));
    }

    public void addPermission(String groupName, String permission) {
        Group g = getGroup(groupName);
        if (g == null) return;
        g.addPermission(permission);
        // FIX: async DB write
        FoliaScheduler.runAsync(plugin, () ->
                plugin.getStorage().addGroupPermission(groupName.toLowerCase(), permission));
        EventBus.fireNodeAdd(plugin, PermsCraftEvent.TargetType.GROUP, null,
                groupName, EventBus.nodeFromString(permission), "internal");
        // FIX: Invalidate cache so players see the change immediately (not after TTL)
        plugin.getPermissionCache().invalidateGroupAndChildren(groupName, () -> getAllGroups());
        refreshGroupMembers(groupName);
    }

    public void removePermission(String groupName, String permission) {
        Group g = getGroup(groupName);
        if (g == null) return;
        g.removePermission(permission);
        // FIX: async DB write
        FoliaScheduler.runAsync(plugin, () ->
                plugin.getStorage().removeGroupPermission(groupName.toLowerCase(), permission));
        EventBus.fireNodeRemove(plugin, PermsCraftEvent.TargetType.GROUP, null,
                groupName, EventBus.nodeFromString(permission), "internal");
        // FIX: same — must invalidate or the old permission stays cached up to 5 min
        plugin.getPermissionCache().invalidateGroupAndChildren(groupName, () -> getAllGroups());
        refreshGroupMembers(groupName);
    }

    public void addInheritance(String groupName, String parentName) {
        if (wouldCreateCircle(groupName, parentName)) {
            plugin.getLogger().warning("[PermsCraft] Circular inheritance blocked: "
                    + groupName + " -> " + parentName);
            return;
        }
        Group g = getGroup(groupName);
        if (g == null) return;
        g.addInheritance(parentName);
        // FIX: async DB write
        FoliaScheduler.runAsync(plugin, () ->
                plugin.getStorage().addGroupInheritance(groupName.toLowerCase(), parentName.toLowerCase()));
        // FIX: Inheritance change affects resolved perms — invalidate both groups
        plugin.getPermissionCache().invalidateGroupAndChildren(groupName, () -> getAllGroups());
        plugin.getPermissionCache().invalidateGroupAndChildren(parentName, () -> getAllGroups());
        refreshGroupMembers(groupName);
    }

    public void removeInheritance(String groupName, String parentName) {
        Group g = getGroup(groupName);
        if (g == null) return;
        g.removeInheritance(parentName);
        // FIX: async DB write
        FoliaScheduler.runAsync(plugin, () ->
                plugin.getStorage().removeGroupInheritance(groupName.toLowerCase(), parentName.toLowerCase()));
        // FIX: same
        plugin.getPermissionCache().invalidateGroupAndChildren(groupName, () -> getAllGroups());
        plugin.getPermissionCache().invalidateGroupAndChildren(parentName, () -> getAllGroups());
        refreshGroupMembers(groupName);
    }

    public void setPrefix(String groupName, String prefix) {
        Group g = getGroup(groupName);
        if (g == null) return;
        g.setPrefix(prefix);
        // FIX: async DB write
        FoliaScheduler.runAsync(plugin, () ->
                plugin.getStorage().saveGroup(g));
    }

    public void setSuffix(String groupName, String suffix) {
        Group g = getGroup(groupName);
        if (g == null) return;
        g.setSuffix(suffix);
        // FIX: async DB write
        FoliaScheduler.runAsync(plugin, () ->
                plugin.getStorage().saveGroup(g));
    }

    public void setWeight(String groupName, int weight) {
        Group g = getGroup(groupName);
        if (g == null) return;
        g.setWeight(weight);
        // FIX: async DB write
        FoliaScheduler.runAsync(plugin, () ->
                plugin.getStorage().saveGroup(g));
        // FIX: Weight change affects priority order — must re-apply to all members
        plugin.getPermissionCache().invalidateGroupAndChildren(groupName, () -> getAllGroups());
        refreshGroupMembers(groupName);
    }

    /**
     * FIX: Refresh permissions for all online players who are members of this group.
     * Called after any mutation that changes effective permissions for the group.
     * Safe to call from any thread — refreshPermissions() already dispatches to
     * the main thread when needed.
     */
    public void refreshGroupMembers(String groupName) {
        String lower = groupName.toLowerCase();
        plugin.getServer().getOnlinePlayers().forEach(p -> {
            var user = plugin.getUserManager().getUser(p.getUniqueId());
            if (user != null && user.inGroup(lower)) {
                plugin.getUserManager().refreshPermissions(p.getUniqueId());
            }
        });
    }

    /**
     * Resolve all permissions including inherited ones, respecting weight order.
     * FIX: Uses visited set to prevent infinite loops from circular refs.
     */
    public Set<String> getResolvedPermissions(String groupName) {
        Set<String> resolved = new LinkedHashSet<>();
        Set<String> visited  = new HashSet<>();
        resolvePermissions(groupName, resolved, visited);
        return resolved;
    }

    private void resolvePermissions(String groupName, Set<String> resolved, Set<String> visited) {
        if (visited.contains(groupName)) return; // FIX: breaks circles
        visited.add(groupName);
        Group g = getGroup(groupName);
        if (g == null) return;
        // Parents first (lower priority), then own perms override
        for (String parent : g.getInheritedGroups()) {
            resolvePermissions(parent, resolved, visited);
        }
        resolved.addAll(g.getPermissions());
    }

    /**
     * FIX: Check if adding parentName to groupName would create a circular inheritance.
     * e.g. A -> B -> C, adding C -> A would be circular.
     */
    private boolean wouldCreateCircle(String groupName, String parentName) {
        if (groupName.equalsIgnoreCase(parentName)) return true;
        // Check if groupName is reachable from parentName (i.e. parentName already inherits from groupName)
        Set<String> visited = new HashSet<>();
        return isReachable(parentName, groupName, visited);
    }

    private boolean isReachable(String from, String target, Set<String> visited) {
        if (visited.contains(from)) return false;
        visited.add(from);
        Group g = getGroup(from);
        if (g == null) return false;
        for (String parent : g.getInheritedGroups()) {
            if (parent.equalsIgnoreCase(target)) return true;
            if (isReachable(parent, target, visited)) return true;
        }
        return false;
    }

    /** Clear all permissions and parent-groups for a group. Keeps the group row. */
    public void clearGroup(String groupName) {
        Group g = getGroup(groupName);
        if (g == null) return;
        g.getPermissions().clear();
        g.getInheritedGroups().clear();
        g.getMeta().clear();
        // FIX: async DB write
        FoliaScheduler.runAsync(plugin, () ->
                plugin.getStorage().clearGroup(groupName));
        plugin.getPermissionCache().invalidateGroupAndChildren(groupName, () -> getAllGroups());
        refreshGroupMembers(groupName);
    }

    // ── Meta ─────────────────────────────────────────────────────────────────

    public void setMeta(String groupName, String key, String value) {
        Group g = getGroup(groupName);
        if (g != null) g.setMeta(key, value);
        // FIX: async DB write
        FoliaScheduler.runAsync(plugin, () ->
                plugin.getStorage().saveMeta(groupName, true, key, value));
    }

    public void unsetMeta(String groupName, String key) {
        Group g = getGroup(groupName);
        if (g != null) g.unsetMeta(key);
        // FIX: async DB write
        FoliaScheduler.runAsync(plugin, () ->
                plugin.getStorage().deleteMeta(groupName, true, key));
    }
}
