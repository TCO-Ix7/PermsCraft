package ir.permscraft.inheritance;

import ir.permscraft.PermsCraft;
import ir.permscraft.models.Group;
import ir.permscraft.utils.WildcardUtil;

import java.util.*;
import ir.permscraft.managers.TimedGroupManager;

/**
 * Resolves group permissions using a weighted inheritance graph.
 *
 * Priority rules (highest → lowest):
 *   1. Personal user permissions (direct)
 *   2. Timed permissions
 *   3. Higher-weight group permissions
 *   4. Inherited (parent) group permissions
 *
 * Negation semantics:
 *   - A node stored as "-foo.bar" means DENY foo.bar
 *   - Specific nodes always beat wildcard nodes at the same level
 *   - DENY always beats GRANT when from the SAME source level
 *   - A child-group DENY overrides a parent-group GRANT (child wins)
 *
 * Wildcard expansion (second pass after full resolution):
 *   - Wildcards only fill nodes that have NO specific rule
 *   - Specific rules (non-wildcard) always beat wildcards
 *
 * Cycle detection:
 *   - Tracks full path (not just visited set) so A→B→A is caught even
 *     when A or B appears legitimately in another branch
 *   - Hard depth limit of 20 as a secondary safety net
 */
public class InheritanceGraph {

    private final PermsCraft plugin;

    public InheritanceGraph(PermsCraft plugin) { this.plugin = plugin; }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Resolve ALL permissions for a group, including inherited ones.
     * Returns map of permission node → granted(true)/denied(false).
     */
    public Map<String, Boolean> resolveGroup(String groupName) {
        // Cache check — uses "DENY:" prefix encoding for denied nodes
        Set<String> cached = plugin.getPermissionCache().getGroupPermissions(groupName);
        if (cached != null) {
            Map<String, Boolean> result = new LinkedHashMap<>();
            for (String p : cached) {
                if (p.startsWith("DENY:")) result.put(p.substring(5), false);
                else                        result.put(p, true);
            }
            return result;
        }

        Map<String, Boolean> resolved = new LinkedHashMap<>();
        // Track which keys were set by SPECIFIC (non-wildcard) rules so wildcard
        // expansion in the second pass knows what it can and cannot override.
        Set<String> specificKeys = new LinkedHashSet<>();

        Deque<String> path = new ArrayDeque<>(); // full traversal path for cycle detection
        resolveGroupInternal(groupName, resolved, specificKeys, path, 0);

        // ── Wildcard second pass ──────────────────────────────────────────────
        // Wildcards apply only to nodes that have NO specific rule covering them.
        for (Map.Entry<String, Boolean> entry : new ArrayList<>(resolved.entrySet())) {
            String node = entry.getKey();
            if (!node.contains("*")) continue;
            boolean granted = entry.getValue();
            for (String existing : new ArrayList<>(resolved.keySet())) {
                if (existing.contains("*")) continue;
                if (!WildcardUtil.matches(node, existing)) continue;
                if (!specificKeys.contains(existing)) {
                    // No specific rule covers this node — wildcard applies
                    resolved.put(existing, granted);
                }
                // Specific rule present → leave it alone (specific beats wildcard)
            }
        }

        // Store in cache
        Set<String> cacheSet = new HashSet<>();
        resolved.forEach((p, v) -> cacheSet.add(v ? p : "DENY:" + p));
        plugin.getPermissionCache().setGroupPermissions(groupName, cacheSet);

        return resolved;
    }

    /**
     * Resolve all permissions for a user across all their groups + personal nodes.
     */
    public Map<String, Boolean> resolveUser(ir.permscraft.models.User user) {
        Map<String, Boolean> resolved = new LinkedHashMap<>();
        Set<String> specificKeys = new LinkedHashSet<>();

        // Collect all groups: permanent + active timed group memberships
        // Timed groups are resolved at the same priority as permanent groups
        // (weight-based ordering applies to both).
        Set<String> allGroupNames = new LinkedHashSet<>(user.getGroups());
        allGroupNames.addAll(
            plugin.getTimedGroupManager().getActiveGroupNames(user.getUuid().toString())
        );

        List<String> groups = new ArrayList<>(allGroupNames);
        // Sort ASCENDING by weight: lower-weight groups are applied first so that
        // higher-weight groups' putAll() calls override them — giving higher-weight
        // groups the final say. This is consistent with PermissionCalculator which
        // iterates groups descending (first-match wins = highest weight checked first).
        groups.sort((a, b) -> {
            Group ga = plugin.getGroupManager().getGroup(a);
            Group gb = plugin.getGroupManager().getGroup(b);
            return Integer.compare(
                    ga != null ? ga.getWeight() : 0,
                    gb != null ? gb.getWeight() : 0);
        });

        for (String groupName : groups) {
            resolved.putAll(resolveGroup(groupName));
        }

        // Personal permissions — highest priority, override everything
        applyNodeList(user.getPermissions(), resolved, specificKeys);

        // Timed permissions (treated as personal, same priority level)
        Set<String> timedNodes = plugin.getTimedPermissionManager()
                .getActivePermissions(user.getUuid().toString());
        applyNodeList(timedNodes, resolved, specificKeys);

        return resolved;
    }

    /**
     * Check a single permission for a user using the full resolution pipeline.
     * Specific match beats wildcard match. DENY beats GRANT at the same level.
     */
    public boolean hasPermission(ir.permscraft.models.User user, String permission) {
        Map<String, Boolean> resolved = resolveUser(user);

        // 1. Exact match wins immediately
        if (resolved.containsKey(permission)) return resolved.get(permission);

        // 2. Wildcard match — most specific wildcard wins
        //    Specificity = length of the non-wildcard prefix
        String bestNode = null;
        int bestSpecificity = -1;
        for (String node : resolved.keySet()) {
            if (!node.contains("*")) continue;
            if (!WildcardUtil.matches(node, permission)) continue;
            int specificity = node.indexOf('*');
            if (specificity > bestSpecificity) {
                bestSpecificity = specificity;
                bestNode = node;
            }
        }
        if (bestNode != null) return resolved.get(bestNode);

        return false; // not set
    }

    /**
     * Get a flat Set of only the GRANTED permissions for a group.
     * Used for Bukkit PermissionAttachment (which has no concept of denial).
     */
    public Set<String> getGrantedPermissions(String groupName) {
        Set<String> granted = new HashSet<>();
        resolveGroup(groupName).forEach((perm, value) -> { if (value) granted.add(perm); });
        return granted;
    }

    /**
     * Get ordered list of groups a group inherits from (full chain, no duplicates).
     * Cycle-safe.
     */
    public List<String> getInheritanceChain(String groupName) {
        List<String> chain = new ArrayList<>();
        Set<String> visited = new LinkedHashSet<>();
        buildChain(groupName, chain, visited);
        return chain;
    }

    // ── Source-tracking resolution (diagnostic use, e.g. /pc tree) ─────────────

    /**
     * Result of a permission resolution including WHERE the final value
     * came from. {@code source} is one of:
     *   - "personal" / "timed" — set directly on the user
     *   - the name of a group in the inheritance chain that supplied the node
     *     (possibly suffixed with " (wildcard)" if it only matched via a
     *     wildcard rule in that group)
     *
     * Intentionally bypasses the permission cache (which stores only
     * grant/deny, not provenance) — this is for admin diagnostics, not the
     * hot permission-check path.
     */
    public record ResolvedPermission(boolean value, String source) {}

    /**
     * Like {@link #resolveGroup(String)}, but also tracks which group in the
     * inheritance chain supplied each resolved node.
     */
    public Map<String, ResolvedPermission> resolveGroupWithSource(String groupName) {
        Map<String, ResolvedPermission> resolved = new LinkedHashMap<>();
        Set<String> specificKeys = new LinkedHashSet<>();
        Deque<String> path = new ArrayDeque<>();
        resolveGroupInternalWithSource(groupName, resolved, specificKeys, path, 0);

        // Wildcard second pass — mirrors resolveGroup()'s logic, additionally
        // tagging any newly-filled node with the wildcard's source.
        for (Map.Entry<String, ResolvedPermission> entry : new ArrayList<>(resolved.entrySet())) {
            String node = entry.getKey();
            if (!node.contains("*")) continue;
            boolean granted = entry.getValue().value();
            String  source  = entry.getValue().source();
            for (String existing : new ArrayList<>(resolved.keySet())) {
                if (existing.contains("*")) continue;
                if (!WildcardUtil.matches(node, existing)) continue;
                if (!specificKeys.contains(existing)) {
                    resolved.put(existing, new ResolvedPermission(granted, source + " (wildcard)"));
                }
            }
        }

        return resolved;
    }

    /**
     * Like {@link #resolveUser(ir.permscraft.models.User)}, but also tracks
     * which group / "personal" / "timed" layer supplied each resolved node.
     * Used by {@code /pc tree user:<name>} to show provenance, similar to
     */
    public Map<String, ResolvedPermission> resolveUserWithSource(ir.permscraft.models.User user) {
        Map<String, ResolvedPermission> resolved = new LinkedHashMap<>();
        Set<String> specificKeys = new LinkedHashSet<>();

        Set<String> allGroupNames = new LinkedHashSet<>(user.getGroups());
        allGroupNames.addAll(
            plugin.getTimedGroupManager().getActiveGroupNames(user.getUuid().toString())
        );

        List<String> groups = new ArrayList<>(allGroupNames);
        groups.sort((a, b) -> {
            Group ga = plugin.getGroupManager().getGroup(a);
            Group gb = plugin.getGroupManager().getGroup(b);
            return Integer.compare(
                    ga != null ? ga.getWeight() : 0,
                    gb != null ? gb.getWeight() : 0);
        });

        for (String groupName : groups) {
            resolved.putAll(resolveGroupWithSource(groupName));
        }

        applyNodeListWithSource(user.getPermissions(), resolved, specificKeys, "personal");

        Set<String> timedNodes = plugin.getTimedPermissionManager()
                .getActivePermissions(user.getUuid().toString());
        applyNodeListWithSource(timedNodes, resolved, specificKeys, "timed");

        return resolved;
    }

    private void resolveGroupInternalWithSource(String groupName,
                                                 Map<String, ResolvedPermission> resolved,
                                                 Set<String> specificKeys,
                                                 Deque<String> path,
                                                 int depth) {
        if (path.contains(groupName)) return; // cycle guard (diagnostic — fail silent)
        if (depth > 20) return;               // depth guard, same as resolveGroup()

        Group g = plugin.getGroupManager().getGroup(groupName);
        if (g == null) return;

        path.addLast(groupName);

        List<String> parents = new ArrayList<>(g.getInheritedGroups());
        parents.sort((a, b) -> {
            Group ga = plugin.getGroupManager().getGroup(a);
            Group gb = plugin.getGroupManager().getGroup(b);
            return Integer.compare(
                    ga != null ? ga.getWeight() : 0,
                    gb != null ? gb.getWeight() : 0);
        });
        for (String parent : parents) {
            resolveGroupInternalWithSource(parent, resolved, specificKeys, path, depth + 1);
        }

        List<String> grants  = new ArrayList<>();
        List<String> denials = new ArrayList<>();
        for (String node : g.getPermissions()) {
            if (node.startsWith("-")) denials.add(node.substring(1));
            else                      grants.add(node);
        }
        for (String node : grants) {
            resolved.put(node, new ResolvedPermission(true, groupName));
            if (!node.contains("*")) specificKeys.add(node);
        }
        for (String node : denials) {
            resolved.put(node, new ResolvedPermission(false, groupName));
            if (!node.contains("*")) specificKeys.add(node);
        }

        path.removeLast();
    }

    private static void applyNodeListWithSource(Collection<String> nodes,
                                                 Map<String, ResolvedPermission> resolved,
                                                 Set<String> specificKeys,
                                                 String source) {
        List<String> grants  = new ArrayList<>();
        List<String> denials = new ArrayList<>();
        for (String node : nodes) {
            if (node.startsWith("-")) denials.add(node.substring(1));
            else                      grants.add(node);
        }
        for (String node : grants) {
            resolved.put(node, new ResolvedPermission(true, source));
            if (!node.contains("*")) specificKeys.add(node);
        }
        for (String node : denials) {
            resolved.put(node, new ResolvedPermission(false, source));
            if (!node.contains("*")) specificKeys.add(node);
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private void resolveGroupInternal(String groupName,
                                      Map<String, Boolean> resolved,
                                      Set<String> specificKeys,
                                      Deque<String> path,
                                      int depth) {
        // FIX Bug #8 — cycle detection uses the FULL traversal path, not a
        // simple visited set. A simple visited set would incorrectly block
        // groups that appear in multiple branches of a diamond inheritance.
        // Example: A→B, A→C, B→D, C→D — D should be visited twice (once per
        // branch) but should not cause infinite recursion. We detect cycles
        // by checking if groupName is already in the CURRENT path stack.
        if (path.contains(groupName)) {
            if (plugin != null && plugin.getLogger() != null) {
                plugin.getLogger().warning("[PermsCraft] Inheritance cycle detected: "
                        + String.join(" → ", path) + " → " + groupName
                        + ". Skipping to prevent infinite loop.");
            }
            return;
        }
        // Hard depth limit as secondary safety net
        if (depth > 20) {
            if (plugin != null && plugin.getLogger() != null) {
                plugin.getLogger().warning("[PermsCraft] Inheritance depth > 20 at group '"
                        + groupName + "'. Stopping resolution.");
            }
            return;
        }

        Group g = plugin.getGroupManager().getGroup(groupName);
        if (g == null) return;

        path.addLast(groupName);

        // ── Step 1: Resolve parent groups (lower priority — applied first) ───
        List<String> parents = new ArrayList<>(g.getInheritedGroups());
        // Sort ascending by weight so higher-weight parents are applied last
        // (later puts override earlier ones in the map)
        parents.sort((a, b) -> {
            Group ga = plugin.getGroupManager().getGroup(a);
            Group gb = plugin.getGroupManager().getGroup(b);
            return Integer.compare(
                    ga != null ? ga.getWeight() : 0,
                    gb != null ? gb.getWeight() : 0);
        });
        for (String parent : parents) {
            resolveGroupInternal(parent, resolved, specificKeys, path, depth + 1);
        }

        // ── Step 2: Apply this group's own permissions (overrides parents) ───
        // FIX Bug #1 — negation priority: within the same group, DENY nodes
        // must be applied AFTER grant nodes so that "-foo.bar" + "foo.*"
        // results in foo.bar being DENIED (not granted by the wildcard).
        // Strategy: collect this group's nodes into grants + denials, apply
        // grants first, then denials — so denials always win at this level.
        List<String> grants  = new ArrayList<>();
        List<String> denials = new ArrayList<>();

        for (String node : g.getPermissions()) {
            if (node.startsWith("-")) denials.add(node.substring(1));
            else                      grants.add(node);
        }

        for (String node : grants) {
            resolved.put(node, true);
            if (!node.contains("*")) specificKeys.add(node);
        }
        for (String node : denials) {
            // FIX Bug #2 — denial as false in the Map<String,Boolean>
            // getGrantedPermissions() correctly filters these out.
            resolved.put(node, false);
            if (!node.contains("*")) specificKeys.add(node); // denials are also specific
        }

        path.removeLast();
    }

    /**
     * Apply a list of permission nodes (with optional "-" prefix for denial)
     * into the resolved map, updating specificKeys accordingly.
     */
    private static void applyNodeList(Collection<String> nodes,
                                      Map<String, Boolean> resolved,
                                      Set<String> specificKeys) {
        List<String> grants  = new ArrayList<>();
        List<String> denials = new ArrayList<>();
        for (String node : nodes) {
            if (node.startsWith("-")) denials.add(node.substring(1));
            else                      grants.add(node);
        }
        for (String node : grants) {
            resolved.put(node, true);
            if (!node.contains("*")) specificKeys.add(node);
        }
        for (String node : denials) {
            resolved.put(node, false);
            if (!node.contains("*")) specificKeys.add(node);
        }
    }

    private void buildChain(String groupName, List<String> chain, Set<String> visited) {
        if (visited.contains(groupName)) return;
        visited.add(groupName);
        Group g = plugin.getGroupManager().getGroup(groupName);
        if (g == null) return;
        for (String parent : g.getInheritedGroups()) {
            buildChain(parent, chain, visited);
            if (!chain.contains(parent)) chain.add(parent);
        }
    }
}
