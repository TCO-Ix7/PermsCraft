package ir.permscraft.api;

import ir.permscraft.api.node.Node;
import ir.permscraft.context.ContextSet;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Resolved, cached data for a user or group.
 *
 *
 *     - PermissionData  → queryPermission(node, options) → TristateResult
 *     - MetaData        → getPrefix() / getSuffix() / getMetaValue(key)
 *     - No direct Map access to resolved permissions
 *
 *   PermsCraft CachedData:
 *     + Full Map<String,Boolean> access (resolved, inherited, with context)
 *     + Tristate query with wildcard resolution details
 *     + QueryResult with SOURCE information (which group granted this?)
 *     + getActiveTimedNodes() — expiry-aware snapshot
 *     + Permission diff between two CachedData snapshots
 *     + Meta stack with priority (multiple prefixes/suffixes)
 *     + Thread-safe — all accessors are safe to call from async threads
 *
 * Usage:
 *   CachedData data = api.getUserManager().getCachedData(uuid);
 *   QueryResult result = data.queryPermission("essentials.fly");
 *   switch (result.tristate()) {
 *     case TRUE  -> player.sendMessage("You can fly");
 *     case FALSE -> player.sendMessage("Fly is denied");
 *     case UNDEFINED -> player.sendMessage("Not set");
 *   }
 *   String source = result.sourceGroup(); // "admin", "vip", "personal", ...
 */
public final class CachedData {

    // ── Core resolved map ─────────────────────────────────────────────────────

    /** Full resolved permission map including inherited. node → granted */
    private final Map<String, Boolean> resolvedPermissions;

    /** Map of node → which group or "personal" it came from */
    private final Map<String, String> permissionSources;

    /** Active timed nodes (permissions + groups) snapshot */
    private final List<Node> activeTimedNodes;

    /** Meta: key → value (from highest-priority source) */
    private final Map<String, String> meta;

    /** Prefix stack: priority → prefix string (sorted, highest first) */
    private final NavigableMap<Integer, String> prefixStack;

    /** Suffix stack: priority → suffix string (sorted, highest first) */
    private final NavigableMap<Integer, String> suffixStack;

    /** When this cache entry was built */
    private final Instant calculatedAt;

    // ── Constructor ───────────────────────────────────────────────────────────

    public CachedData(Map<String, Boolean> resolvedPermissions,
                      Map<String, String>  permissionSources,
                      List<Node>           activeTimedNodes,
                      Map<String, String>  meta,
                      NavigableMap<Integer, String> prefixStack,
                      NavigableMap<Integer, String> suffixStack) {
        this.resolvedPermissions = Collections.unmodifiableMap(new LinkedHashMap<>(resolvedPermissions));
        this.permissionSources   = Collections.unmodifiableMap(new LinkedHashMap<>(permissionSources));
        this.activeTimedNodes    = Collections.unmodifiableList(new ArrayList<>(activeTimedNodes));
        this.meta                = Collections.unmodifiableMap(new LinkedHashMap<>(meta));
        this.prefixStack         = new TreeMap<>(Collections.reverseOrder());
        this.prefixStack.putAll(prefixStack);
        this.suffixStack         = new TreeMap<>(Collections.reverseOrder());
        this.suffixStack.putAll(suffixStack);
        this.calculatedAt        = Instant.now();
    }

    // ── Permission queries ────────────────────────────────────────────────────

    /**
     * Full query with source information.
     * Returns a {@link QueryResult} that includes Tristate + which group granted.
     */
    public QueryResult queryPermission(String permission) {
        String lower = permission.toLowerCase();

        // 1. Exact match
        if (resolvedPermissions.containsKey(lower)) {
            return new QueryResult(
                    permission,
                    Tristate.of(resolvedPermissions.get(lower)),
                    permissionSources.getOrDefault(lower, "unknown"),
                    false
            );
        }

        // 2. Wildcard match — pick most specific (longest non-wildcard prefix)
        String bestNode = null;
        int bestSpec = -1;
        for (String node : resolvedPermissions.keySet()) {
            if (!node.contains("*")) continue;
            if (!ir.permscraft.utils.WildcardUtil.matches(node, lower)) continue;
            int spec = node.indexOf('*');
            if (spec > bestSpec) { bestSpec = spec; bestNode = node; }
        }
        if (bestNode != null) {
            return new QueryResult(
                    permission,
                    Tristate.of(resolvedPermissions.get(bestNode)),
                    permissionSources.getOrDefault(bestNode, "unknown"),
                    true
            );
        }

        return new QueryResult(permission, Tristate.UNDEFINED, "none", false);
    }

    /** Simple boolean check — false for UNDEFINED (not set). */
    public boolean hasPermission(String permission) {
        return queryPermission(permission).tristate().asBoolean(false);
    }

    /** Tristate-only check. */
    public Tristate checkPermission(String permission) {
        return queryPermission(permission).tristate();
    }

    // ── Full permission map ───────────────────────────────────────────────────

    /** All resolved permissions (granted AND denied). */
    public Map<String, Boolean> getResolvedPermissions() { return resolvedPermissions; }

    /** Only the granted (true) permissions. */
    public Set<String> getGrantedPermissions() {
        return resolvedPermissions.entrySet().stream()
                .filter(Map.Entry::getValue)
                .map(Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Only the explicitly denied (false) permissions. */
    public Set<String> getDeniedPermissions() {
        return resolvedPermissions.entrySet().stream()
                .filter(e -> !e.getValue())
                .map(Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Source group or "personal" for each resolved node. */
    public Map<String, String> getPermissionSources() { return permissionSources; }

    // ── Timed nodes ───────────────────────────────────────────────────────────

    public List<Node> getActiveTimedNodes() { return activeTimedNodes; }

    // ── Meta ─────────────────────────────────────────────────────────────────

    public Map<String, String> getMeta() { return meta; }

    public Optional<String> getMetaValue(String key) {
        return Optional.ofNullable(meta.get(key.toLowerCase()));
    }

    // ── Prefix / Suffix stack ─────────────────────────────────────────────────

    /**
     * Highest-priority prefix (or empty string if none set).
     */
    public String getPrefix() {
        Map.Entry<Integer, String> e = prefixStack.firstEntry();
        return e != null ? e.getValue() : "";
    }

    /**
     * Highest-priority suffix (or empty string if none set).
     */
    public String getSuffix() {
        Map.Entry<Integer, String> e = suffixStack.firstEntry();
        return e != null ? e.getValue() : "";
    }

    /**
     * Full prefix stack ordered by priority descending.
     *
     * Useful for plugins that want to display ALL prefixes (e.g. custom tab format).
     */
    public NavigableMap<Integer, String> getPrefixStack() {
        return Collections.unmodifiableNavigableMap(prefixStack);
    }

    /**
     * Full suffix stack ordered by priority descending.
     */
    public NavigableMap<Integer, String> getSuffixStack() {
        return Collections.unmodifiableNavigableMap(suffixStack);
    }

    // ── Diff ─────────────────────────────────────────────────────────────────

    /**
     * Compute the difference between this CachedData and another snapshot.
     * Returns a Diff with added and removed nodes.
     *
     * Useful for logging what changed after a recalculation.
     */
    public Diff diff(CachedData other) {
        Set<String> added   = new LinkedHashSet<>();
        Set<String> removed = new LinkedHashSet<>();
        Set<String> changed = new LinkedHashSet<>();

        for (Map.Entry<String, Boolean> entry : other.resolvedPermissions.entrySet()) {
            String node = entry.getKey();
            if (!this.resolvedPermissions.containsKey(node)) {
                added.add((entry.getValue() ? "+" : "-") + node);
            } else if (!this.resolvedPermissions.get(node).equals(entry.getValue())) {
                changed.add(node + " → " + (entry.getValue() ? "granted" : "denied"));
            }
        }
        for (String node : this.resolvedPermissions.keySet()) {
            if (!other.resolvedPermissions.containsKey(node)) {
                removed.add(node);
            }
        }
        return new Diff(
                Collections.unmodifiableSet(added),
                Collections.unmodifiableSet(removed),
                Collections.unmodifiableSet(changed)
        );
    }

    public Instant getCalculatedAt() { return calculatedAt; }

    // ── Inner types ───────────────────────────────────────────────────────────

    /**
     *
     * @param permission  the queried node
     * @param tristate    TRUE / FALSE / UNDEFINED
     * @param sourceGroup which group granted/denied this ("personal", "admin", etc.)
     * @param wildcardMatch true if the result came from a wildcard node
     */
    public record QueryResult(
            String    permission,
            Tristate  tristate,
            String    sourceGroup,
            boolean   wildcardMatch
    ) {
        public boolean isGranted()   { return tristate == Tristate.TRUE; }
        public boolean isDenied()    { return tristate == Tristate.FALSE; }
        public boolean isUndefined() { return tristate == Tristate.UNDEFINED; }

        @Override public String toString() {
            return "QueryResult{" + permission + "=" + tristate
                    + " from=" + sourceGroup
                    + (wildcardMatch ? " [wildcard]" : "") + "}";
        }
    }

    /**
     * Difference between two CachedData snapshots.
     */
    public record Diff(
            Set<String> added,
            Set<String> removed,
            Set<String> changed
    ) {
        public boolean isEmpty() {
            return added.isEmpty() && removed.isEmpty() && changed.isEmpty();
        }
    }
}
