package ir.permscraft.inject;

import ir.permscraft.PermsCraft;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches every permission node registered by ANY plugin (via plugin.yml's
 * "permissions:" section, or registered programmatically) whose
 * {@code default} value is something other than {@link PermissionDefault#FALSE}.
 *
 * WHY THIS EXISTS:
 * On a vanilla server (no PermsCraft), Bukkit's PluginManager grants these
 * "default" permissions automatically:
 *   - default: true     -> granted to EVERY player
 *   - default: op       -> granted to OP players only
 *   - default: not op   -> granted to NON-OP players only
 *   - default: false    -> granted to nobody automatically (no entry needed)
 *
 * PermsCraft replaces each player's PermissibleBase with {@link PCPermissible},
 * which resolves permissions purely from its own group/user graph. If a node
 * is not present anywhere in that graph (no grant, no deny), the old behaviour
 * was to return false — silently REMOVING permissions that vanilla Bukkit (and
 *
 * Example real-world bug this prevents:
 *   essentials.motd:
 *     default: true
 * If an admin never explicitly adds "essentials.motd" to the default group,
 * every player loses access to /motd compared to a vanilla server — even
 * though nobody intended to deny it.
 *
 * THIS CACHE is consulted as the LAST fallback in PCPermissible.hasPermission(),
 * only when the permission is not found anywhere in PermsCraft's own graph
 * (no direct node, no wildcard/regex match, no third-party attachment). An
 * explicit PermsCraft deny ALWAYS wins over a plugin.yml default.
 *
 * The cache is rebuilt whenever a plugin enables/disables (see
 * {@link ir.permscraft.listeners.PluginLoadListener}), so permissions
 * registered by plugins that load after PermsCraft are picked up without
 * requiring a server restart.
 *
 * implemented as a simple read-only cache instead of replacing Bukkit's
 * internal defaults map, since PCPermissible already intercepts
 * hasPermission() directly and doesn't need PluginManager to know about it.
 */
public final class BukkitDefaultsCache {

    private final PermsCraft plugin;

    /**
     * lowercase permission node -> its PermissionDefault.
     * Only contains entries where default != FALSE (FALSE is the same as
     * "not present", so we skip storing those to keep the map small).
     */
    private final Map<String, PermissionDefault> defaults = new ConcurrentHashMap<>();

    public BukkitDefaultsCache(PermsCraft plugin) {
        this.plugin = plugin;
        rebuild();
    }

    /**
     * Re-scans every Permission currently registered with Bukkit's
     * PluginManager and rebuilds the cache. Cheap enough to call on every
     * PluginEnableEvent/PluginDisableEvent (typically only a few hundred
     * permissions, only at plugin (un)load time, not per-tick).
     */
    public void rebuild() {
        Map<String, PermissionDefault> fresh = new ConcurrentHashMap<>();
        for (Permission perm : plugin.getServer().getPluginManager().getPermissions()) {
            PermissionDefault def = perm.getDefault();
            if (def == null || def == PermissionDefault.FALSE) continue;
            fresh.put(perm.getName().toLowerCase(), def);
        }
        defaults.clear();
        defaults.putAll(fresh);
    }

    /**
     * Returns the registered {@link PermissionDefault} for this node, or
     * {@code null} if no plugin registered it with a non-FALSE default
     * (i.e. PermsCraft's own resolution should be treated as final/false).
     */
    public PermissionDefault get(String permission) {
        return defaults.get(permission.toLowerCase());
    }

    /**
     * Resolves a {@link PermissionDefault} against a player's OP status,
     * mirroring exactly what {@code org.bukkit.permissions.PermissionDefault#getValue}
     * does — true/false/op-gated.
     */
    public static boolean resolve(PermissionDefault def, boolean isOp) {
        return switch (def) {
            case TRUE -> true;
            case FALSE -> false;
            case OP -> isOp;
            case NOT_OP -> !isOp;
        };
    }

    /** Number of cached non-FALSE default permissions, for /pc info diagnostics. */
    public int size() {
        return defaults.size();
    }

    /**
     * Read-only view of all cached (permission -> default) entries.
     * Used by PCPermissible.getEffectivePermissions() to enumerate
     * plugin.yml-default permissions that resolve to true for a given player.
     */
    public java.util.Set<Map.Entry<String, PermissionDefault>> entrySet() {
        return java.util.Collections.unmodifiableSet(defaults.entrySet());
    }
}
