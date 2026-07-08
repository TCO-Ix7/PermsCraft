package ir.permscraft.listeners;

import ir.permscraft.PermsCraft;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.event.server.PluginDisableEvent;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * FIX Bug #5 (LP Feature #5 port): When another plugin registers permissions
 * after PermsCraft loads, the serverKnown cache in UserManager becomes stale.
 *
 * By listening for PluginEnableEvent we:
 *   1. Invalidate the serverKnown flat set.
 *   2. Invalidate group cache entries that contain wildcards (the old code only
 *      did step 1, so stale wildcard expansions stayed in cache up to 5 minutes).
 *   3. NEW: Refresh online players who are in those wildcard groups immediately,
 *      so they receive the newly registered permissions without having to relog.
 *
 * credit: lucko (Luck) <luck@lucko.me>
 */
public class PluginLoadListener implements Listener {

    private final PermsCraft plugin;

    public PluginLoadListener(PermsCraft plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPluginEnable(PluginEnableEvent event) {
        if (event.getPlugin() == plugin) return; // ignore self

        // 0. Rebuild the Bukkit "default: true/op/not-op" permission cache so
        //    permissions registered by this newly-enabled plugin are honoured
        //    by PCPermissible.hasPermission() as a fallback.
        plugin.getDefaultsCache().rebuild();

        // 1. Invalidate serverKnown flat set.
        plugin.getUserManager().invalidateServerKnownCache();

        // 2. Find groups that have wildcard permission nodes; invalidate their cache.
        Set<String> wildcardGroups = new HashSet<>();
        plugin.getGroupManager().getAllGroups().forEach(group -> {
            boolean hasWildcard = group.getPermissions().stream()
                    .anyMatch(p -> p.contains("*"));
            if (hasWildcard) {
                plugin.getPermissionCache().invalidateGroup(group.getName());
                wildcardGroups.add(group.getName().toLowerCase());
            }
        });

        // 3. FIX (LP Feature #5): Refresh online players who are in those groups
        //    so they get the newly registered permissions without a relog.
        //    We schedule a single sync task to batch all the refreshes together.
        if (!wildcardGroups.isEmpty()) {
            ir.permscraft.FoliaScheduler.runSync(plugin, () -> {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    UUID uuid = player.getUniqueId();
                    ir.permscraft.models.User user = plugin.getUserManager().getUser(uuid);
                    if (user == null) continue;
                    boolean affected = user.getGroups().stream()
                            .anyMatch(wildcardGroups::contains);
                    if (affected) {
                        plugin.getPermissionCache().invalidateUser(uuid);
                        plugin.getUserManager().refreshPermissions(uuid);
                    }
                }
            });
        }

        // 4. Invalidate PermissionBrowserGui shared cache.
        ir.permscraft.gui.PermissionBrowserGui.invalidateCache();
    }

    /**
     * When a plugin disables, its plugin.yml-registered permissions are
     * unregistered from Bukkit's PluginManager. Rebuild the defaults cache so
     * PCPermissible.hasPermission() no longer falls back to a "default: true"
     * that no longer exists (avoids granting permissions for a plugin that
     * was just disabled).
     */
    @EventHandler
    public void onPluginDisable(PluginDisableEvent event) {
        if (event.getPlugin() == plugin) return; // ignore self
        plugin.getDefaultsCache().rebuild();
    }
}

