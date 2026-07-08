package ir.permscraft.inject.server;

import ir.permscraft.PermsCraft;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.Permissible;
import org.bukkit.plugin.SimplePluginManager;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Objects;

/**
 * Injects PCPermissionMap and PCSubscriptionMap into Bukkit's SimplePluginManager.
 *
 * Call inject() on enable, uninject() on disable.
 *
 * — MIT License, credit: lucko (Luck) <luck@lucko.me>
 */
@SuppressWarnings({"deprecation", "UnstableApiUsage"})
public final class ServerInjector {

    private static final Field PERMISSIONS_FIELD;
    private static final Field PERM_SUBS_FIELD;

    static {
        Field permF = null, subsF = null;
        try {
            permF = SimplePluginManager.class.getDeclaredField("permissions");
            permF.setAccessible(true);
            subsF = SimplePluginManager.class.getDeclaredField("permSubs");
            subsF.setAccessible(true);
        } catch (Exception e) {
            // Paper/Folia may rename fields — injection will be skipped gracefully
        }
        PERMISSIONS_FIELD = permF;
        PERM_SUBS_FIELD   = subsF;
    }

    private final PermsCraft plugin;

    public ServerInjector(PermsCraft plugin) {
        this.plugin = plugin;
    }

    public void inject() {
        injectPermissionMap();
        injectSubscriptionMap();
    }

    public void uninject() {
        uninjectPermissionMap();
        uninjectSubscriptionMap();
    }

    // ── Permission map ────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void injectPermissionMap() {
        if (PERMISSIONS_FIELD == null) return;
        try {
            Object pm = plugin.getServer().getPluginManager();
            if (pm == null || !pm.getClass().getSimpleName().contains("PluginManager")) return;

            Object existing = PERMISSIONS_FIELD.get(pm);
            if (existing instanceof PCPermissionMap) return; // already injected

            Map<String, Permission> cast = (Map<String, Permission>) existing;
            PCPermissionMap newMap = new PCPermissionMap(plugin, cast);
            PERMISSIONS_FIELD.set(pm, newMap);
            plugin.getLogger().info("[PermsCraft] Injected server PermissionMap.");
        } catch (Exception e) {
            plugin.getLogger().warning("[PermsCraft] Could not inject PermissionMap: " + e.getMessage());
        }
    }

    private void uninjectPermissionMap() {
        if (PERMISSIONS_FIELD == null) return;
        try {
            Object pm = plugin.getServer().getPluginManager();
            if (pm == null || !pm.getClass().getSimpleName().contains("PluginManager")) return;
            Object existing = PERMISSIONS_FIELD.get(pm);
            if (existing instanceof PCPermissionMap) {
                PERMISSIONS_FIELD.set(pm, ((PCPermissionMap) existing).detach());
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[PermsCraft] Could not uninject PermissionMap: " + e.getMessage());
        }
    }

    // ── Subscription map ──────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void injectSubscriptionMap() {
        if (PERM_SUBS_FIELD == null) return;
        try {
            Object pm = plugin.getServer().getPluginManager();
            if (pm == null || !pm.getClass().getSimpleName().contains("PluginManager")) return;

            Object existing = PERM_SUBS_FIELD.get(pm);
            if (existing instanceof PCSubscriptionMap) return;

            Map<String, Map<Permissible, Boolean>> cast =
                    existing instanceof PCSubscriptionMap
                            ? ((PCSubscriptionMap) existing).detach()
                            : (Map<String, Map<Permissible, Boolean>>) existing;

            PCSubscriptionMap newMap = new PCSubscriptionMap(plugin, cast);
            PERM_SUBS_FIELD.set(pm, newMap);
            plugin.getLogger().info("[PermsCraft] Injected server SubscriptionMap.");
        } catch (Exception e) {
            plugin.getLogger().warning("[PermsCraft] Could not inject SubscriptionMap: " + e.getMessage());
        }
    }

    private void uninjectSubscriptionMap() {
        if (PERM_SUBS_FIELD == null) return;
        try {
            Object pm = plugin.getServer().getPluginManager();
            if (pm == null || !pm.getClass().getSimpleName().contains("PluginManager")) return;
            Object existing = PERM_SUBS_FIELD.get(pm);
            if (existing instanceof PCSubscriptionMap) {
                PERM_SUBS_FIELD.set(pm, ((PCSubscriptionMap) existing).detach());
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[PermsCraft] Could not uninject SubscriptionMap: " + e.getMessage());
        }
    }
}
