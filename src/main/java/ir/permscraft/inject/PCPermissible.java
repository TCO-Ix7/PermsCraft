package ir.permscraft.inject;

import ir.permscraft.PermsCraft;
import ir.permscraft.api.Tristate;
import ir.permscraft.models.User;
import ir.permscraft.utils.WildcardUtil;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissibleBase;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Replaces each online player's PermissibleBase via reflection injection
 *
 * WHY: PermsCraft previously used PermissionAttachment to push permissions onto
 * the player. That means:
 *   1. player.hasPermission() still delegates to Bukkit's PermissibleBase which
 *      only knows about explicitly-set nodes — wildcard expansion and inheritance
 *      were not reflected unless we manually expanded every single node.
 *   2. Async calls to player.hasPermission() were not safe (Bukkit attachment
 *      list is not thread-safe).
 *
 * By replacing the permissible we:
 *   • Intercept ALL hasPermission() calls directly.
 *   • Resolve permissions through PermsCraft's own cache/graph — fast and correct.
 *   • Make hasPermission() thread-safe (ConcurrentHashMap backing).
 *   • Eliminate the O(n) attachment-build step on every refreshPermissions() call.
 *
 * The old PermissibleBase is stored and restored on player quit so that
 * other plugins that may have also injected a custom permissible are not broken.
 *
 */
public class PCPermissible extends PermissibleBase {

    // ── Reflection fields ─────────────────────────────────────────────────────

    /**
     * The "attachments" field inside PermissibleBase.
     * We inject a fake list here so that plugins which add attachments via
     * reflection (instead of the API) continue to work correctly.
     */
    private static final Field ATTACHMENTS_FIELD;

    static {
        try {
            ATTACHMENTS_FIELD = PermissibleBase.class.getDeclaredField("attachments");
            ATTACHMENTS_FIELD.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    // ── Instance fields ───────────────────────────────────────────────────────

    private final Player player;
    private final PermsCraft plugin;

    /**
     * Live resolved permission map.
     * Key   = lowercase permission node.
     * Value = true (granted) / false (denied).
     * Thread-safe: ConcurrentHashMap, updated atomically via updatePermissions().
     */
    private final Map<String, Boolean> permissionMap = new ConcurrentHashMap<>();

    /**
     * FIX (hot-path perf): previously hasPermission() rebuilt a fresh
     * HashSet<String> from the ENTIRE permissionMap on every single call
     * (allocating + iterating O(n) every time a permission was checked —
     * potentially thousands of times per second on a busy server). Wildcard/
     * regex/Sponge nodes only change when updatePermissions() is called
     * (login, group change, world change, /pc reload), so we precompute the
     * "signed" view once here and reuse it for every check until the next
     * update. Plain non-wildcard nodes still use the O(1) permissionMap
     * lookup in hasPermission() and never touch this set.
     */
    private volatile Set<String> wildcardNodes = Collections.emptySet();

    /** The PermissibleBase that was on this player before we injected. */
    private PermissibleBase previousPermissible = null;

    /** Whether this permissible is currently active (injected into the player). */
    final AtomicBoolean active = new AtomicBoolean(false);

    /** Extra attachments added by third-party plugins at runtime. */
    final Set<PermissionAttachment> hookedAttachments = ConcurrentHashMap.newKeySet();

    // ── Constructor ───────────────────────────────────────────────────────────

    public PCPermissible(Player player, PermsCraft plugin) {
        super(player);
        this.player = player;
        this.plugin = plugin;
        injectFakeAttachmentsList();
    }

    /**
     * Push a fake list into super.attachments so that plugins which add
     * PermissionAttachment via reflection still work.
     */
    private void injectFakeAttachmentsList() {
        try {
            ATTACHMENTS_FIELD.set(this, new FakeAttachmentList());
        } catch (Exception e) {
            plugin.getLogger().warning("[PermsCraft] Could not inject fake attachment list: " + e.getMessage());
        }
    }

    // ── Permission resolution — the core hot path ─────────────────────────────

    /**
     * Called whenever PermsCraft needs to refresh this player's permissions
     * (login, group change, world change, /pc reload, etc.).
     *
     * This replaces the old addAttachment() approach: instead of building a
     * PermissionAttachment we just update our internal ConcurrentHashMap.
     * No main-thread scheduling required — the map is thread-safe.
     */
    public void updatePermissions(Map<String, Boolean> resolved) {
        permissionMap.clear();
        resolved.forEach((perm, value) -> permissionMap.put(perm.toLowerCase(), value));

        // Merge any third-party attachment permissions (lower priority than ours)
        for (PermissionAttachment att : hookedAttachments) {
            att.getPermissions().forEach((p, v) -> permissionMap.putIfAbsent(p.toLowerCase(), v));
        }

        rebuildWildcardNodes();
    }

    /**
     * FIX (hot-path perf): rebuild the "signed" wildcard/regex/Sponge node
     * view ONCE per update instead of on every hasPermission() call. Only
     * nodes that contain a wildcard, are regex (r=...), or are bare
     * (Sponge-style, no dot) are kept — exact nodes are already covered by
     * the O(1) permissionMap lookup in hasPermission() and don't need to be
     * in this set.
     */
    private void rebuildWildcardNodes() {
        boolean spongeEnabled = plugin.getConfig().getBoolean("settings.sponge-wildcard", true);
        boolean regexEnabled  = plugin.getConfig().getBoolean("settings.regex-permissions", true);

        Set<String> signed = new HashSet<>();
        permissionMap.forEach((k, v) -> {
            boolean isRegex   = k.startsWith("r=");
            boolean isSponge  = !k.contains(".") && !k.contains("*");
            boolean isStdWild = k.contains("*");
            if (isStdWild || (isRegex && regexEnabled) || (isSponge && spongeEnabled)) {
                signed.add(v ? k : "-" + k);
            }
        });
        this.wildcardNodes = signed.isEmpty() ? Collections.emptySet() : signed;
    }

    // ── PermissibleBase overrides ─────────────────────────────────────────────

    @Override
    public boolean isPermissionSet(@NotNull String permission) {
        if (permission == null) throw new NullPointerException("permission");
        return permissionMap.containsKey(permission.toLowerCase());
    }

    @Override
    public boolean isPermissionSet(@NotNull Permission permission) {
        if (permission == null) throw new NullPointerException("permission");
        return isPermissionSet(permission.getName());
    }

    /**
     * Fast, thread-safe permission check.
     * Order:
     *   1. Direct node match (O(1) map lookup).
     *   2. Wildcard match against stored wildcard nodes.
     *   3. Third-party attachment permissions.
     *   4. false.
     */
    @Override
    public boolean hasPermission(@NotNull String permission) {
        if (permission == null) throw new NullPointerException("permission");
        String lc = permission.toLowerCase();

        // 0. OP integration: if player is OP and op-grants-all is enabled in config,
        //    return true for any permission not explicitly denied.
        if (player.isOp() && plugin.getConfig().getBoolean("settings.op-grants-all", true)) {
            Boolean explicit = permissionMap.get(lc);
            if (explicit != null && !explicit) return false; // explicit exact deny wins

            // FIX (OP + wildcard deny): the check above only catches an exact-match
            // deny (e.g. "-essentials.fly"). A deny expressed as a wildcard
            // (e.g. "-essentials.*") was previously invisible to OPs because it
            // never appears as a key in permissionMap under the exact node being
            // checked — so OPs silently bypassed wildcard denies entirely. Reuse
            // the same WildcardUtil resolution used below for non-OPs: if the
            // best-matching wildcard/regex/sponge node for this permission
            // resolves to a denial, that denial must still apply to OPs too.
            Set<String> signedNodes = wildcardNodes;
            if (!signedNodes.isEmpty() && !WildcardUtil.hasPermission(signedNodes, lc)) {
                boolean hasMatchingWildcard = signedNodes.stream().anyMatch(n -> {
                    String c = n.startsWith("-") ? n.substring(1) : n;
                    return c.contains("*") || c.startsWith("r=") ||
                           (!c.contains(".") && lc.startsWith(c + "."));
                });
                if (hasMatchingWildcard) return false; // explicit wildcard deny wins
            }
            return true;
        }

        // 1. Direct hit
        Boolean direct = permissionMap.get(lc);
        if (direct != null) return direct;

        // 2. Wildcard + Regex + Sponge scan (via WildcardUtil)
        //    Covers: essentials.*, r=essentials\.(fly|spawn), bare "essentials" (Sponge)
        //    FIX (hot-path perf): use the precomputed wildcardNodes set built once
        //    in rebuildWildcardNodes() instead of allocating + scanning the whole
        //    permissionMap on every single hasPermission() call.
        Set<String> signedNodes = wildcardNodes;
        if (!signedNodes.isEmpty()) {
            boolean wcResult = WildcardUtil.hasPermission(signedNodes, lc);
            if (wcResult) return true;
            // If WildcardUtil returned false but there was a wildcard/sponge/regex
            // node that explicitly denied — respect that denial
            if (signedNodes.stream().anyMatch(n -> {
                String c = n.startsWith("-") ? n.substring(1) : n;
                return c.contains("*") || c.startsWith("r=") ||
                       (!c.contains(".") && lc.startsWith(c + "."));
            })) return false;
        }

        // 3. Third-party attachments (fallback)
        for (PermissionAttachment att : hookedAttachments) {
            Boolean v = att.getPermissions().get(lc);
            if (v != null) return v;
        }

        // 4. Bukkit plugin.yml "default: true / op / not op" fallback.
        // Only reached if PermsCraft's own graph (direct nodes, wildcards,
        // attachments) has NOTHING to say about this permission — an explicit
        // PermsCraft deny earlier in this method always wins over this.
        org.bukkit.permissions.PermissionDefault def = plugin.getDefaultsCache().get(lc);
        if (def != null) {
            return ir.permscraft.inject.BukkitDefaultsCache.resolve(def, player.isOp());
        }

        return false;
    }

    @Override
    public boolean hasPermission(@NotNull Permission permission) {
        if (permission == null) throw new NullPointerException("permission");
        return hasPermission(permission.getName());
    }

    @Override
    public @NotNull Set<PermissionAttachmentInfo> getEffectivePermissions() {
        Set<PermissionAttachmentInfo> result = new HashSet<>();
        permissionMap.forEach((perm, value) ->
                result.add(new PermissionAttachmentInfo(player, perm, null, value)));

        // Third-party attachments not already covered by PermsCraft's own map
        for (PermissionAttachment att : hookedAttachments) {
            att.getPermissions().forEach((perm, value) -> {
                if (!permissionMap.containsKey(perm.toLowerCase())) {
                    result.add(new PermissionAttachmentInfo(player, perm, att, value));
                }
            });
        }

        // Bukkit plugin.yml "default: true/op/not op" permissions that resolve
        // to true for this player and aren't already covered above. Permissions
        // that resolve to false are omitted (matches Bukkit's own behaviour:
        // getEffectivePermissions() only lists permissions that ARE granted).
        for (Map.Entry<String, org.bukkit.permissions.PermissionDefault> e
                : plugin.getDefaultsCache().entrySet()) {
            String perm = e.getKey();
            if (permissionMap.containsKey(perm)) continue;
            if (hookedAttachments.stream().anyMatch(a -> a.getPermissions().containsKey(perm))) continue;
            if (ir.permscraft.inject.BukkitDefaultsCache.resolve(e.getValue(), player.isOp())) {
                result.add(new PermissionAttachmentInfo(player, perm, null, true));
            }
        }

        return Collections.unmodifiableSet(result);
    }

    @Override
    public @NotNull PermissionAttachment addAttachment(@NotNull Plugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        PermissionAttachment att = new PermissionAttachment(plugin, player);
        hookedAttachments.add(att);
        return att;
    }

    @Override
    public @NotNull PermissionAttachment addAttachment(@NotNull Plugin plugin,
                                                        @NotNull String permission,
                                                        boolean value) {
        PermissionAttachment att = addAttachment(plugin);
        att.setPermission(permission, value);
        return att;
    }

    @Override
    public void removeAttachment(@NotNull PermissionAttachment attachment) {
        Objects.requireNonNull(attachment, "attachment");
        hookedAttachments.remove(attachment);
    }

    @Override
    public void recalculatePermissions() {
        // No-op: our map is always up-to-date.
        // Called by Bukkit when op status changes — handled by AutoOpListener.
    }

    @Override
    public void clearPermissions() {
        permissionMap.clear();
        hookedAttachments.clear();
        wildcardNodes = Collections.emptySet();
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public Player getPlayer()                       { return player; }
    public PermissibleBase getPreviousPermissible() { return previousPermissible; }
    void setPreviousPermissible(PermissibleBase pb) { this.previousPermissible = pb; }
    public Map<String, Boolean> getPermissionMap()  { return Collections.unmodifiableMap(permissionMap); }

    // ── LP Feature #3: Tristate check ─────────────────────────────────────────

    /**
     * Check a permission and return a three-state result.
     * TRUE  = explicitly granted.
     * FALSE = explicitly denied (negation node).
     * UNDEFINED = not set anywhere.
     *
     */
    public Tristate checkPermissionTristate(String permission) {
        if (permission == null) throw new NullPointerException("permission");
        String lc = permission.toLowerCase();

        // 1. Direct hit
        Boolean direct = permissionMap.get(lc);
        if (direct != null) return Tristate.of(direct);

        // 2. Wildcard scan — reuse the precomputed set (FIX: was scanning the
        //    entire permissionMap on every call; same hot-path issue as
        //    hasPermission() above).
        for (String node : wildcardNodes) {
            boolean negated = node.startsWith("-");
            String clean = negated ? node.substring(1) : node;
            if (clean.contains("*") && WildcardUtil.matches(clean, lc)) {
                return Tristate.of(!negated);
            }
        }

        // 3. Third-party attachments
        for (PermissionAttachment att : hookedAttachments) {
            Boolean v = att.getPermissions().get(lc);
            if (v != null) return Tristate.of(v);
        }

        return Tristate.UNDEFINED;
    }

    // ── FakeAttachmentList ────────────────────────────────────────────────────

    /**
     * A fake List<PermissionAttachment> injected into super.attachments.
     * Proxies add/remove/contains back to hookedAttachments so that plugins
     * which manipulate attachments via reflection continue to work.
     *
     */
    private final class FakeAttachmentList extends AbstractList<PermissionAttachment> {

        @Override
        public boolean add(PermissionAttachment att) {
            return hookedAttachments.add(att);
        }

        @Override
        public boolean remove(Object o) {
            return hookedAttachments.remove(o);
        }

        @Override
        public boolean contains(Object o) {
            return hookedAttachments.contains(o);
        }

        @Override
        public void clear() {
            hookedAttachments.clear();
        }

        @Override
        public PermissionAttachment get(int index) {
            // Convert to array-backed access; only called by unusual plugins.
            return new ArrayList<>(hookedAttachments).get(index);
        }

        @Override
        public int size() {
            return hookedAttachments.size();
        }

        @Override
        public Iterator<PermissionAttachment> iterator() {
            // Snapshot to avoid ConcurrentModificationException for callers.
            return new ArrayList<>(hookedAttachments).iterator();
        }
    }
}
