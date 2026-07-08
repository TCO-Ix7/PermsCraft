package ir.permscraft.context;

import ir.permscraft.FoliaScheduler;
import ir.permscraft.PermsCraft;
import ir.permscraft.context.calculators.GameModeContextCalculator;
import ir.permscraft.context.calculators.WorldContextCalculator;
import ir.permscraft.storage.ContextRow;
import ir.permscraft.utils.WildcardUtil;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages context-bound permissions and the ContextCalculator pipeline.
 *
 * WHAT'S NEW vs v1.0.x
 * ─────────────────────
 * 1. Active context is now a ContextSet (multi-key) instead of a single Context.
 *    A player's active set includes: world, dimension, world-uuid, gamemode, server,
 *    and any custom keys supplied by registered ContextCalculators.
 *
 * 2. ContextCalculator pipeline:
 *    Built-in calculators run at priority 0.
 *    Third-party calculators can register with higher priority and override keys.
 *    Calculators are sorted by priority and run in order.
 *
 * 3. resolvePermissionsWithDenied() now accepts ContextSet, so a single node can
 *    require multiple keys simultaneously (world=pvp AND gamemode=survival).
 *
 * 4. /pc context debug <player> shows exactly which calculator supplied each key
 *    and which permissions are active/inactive and why.
 */
public class ContextManager {

    private final PermsCraft plugin;

    // target (uuid or groupName) → list of context-bound permissions
    private final Map<String, CopyOnWriteArrayList<ContextualPermission>> contextPerms =
            new ConcurrentHashMap<>();

    // Registered calculators, sorted by priority ascending
    private final List<ContextCalculator> calculators = new CopyOnWriteArrayList<>();

    public ContextManager(PermsCraft plugin) {
        this.plugin = plugin;
        registerBuiltInCalculators();
        loadAll();
    }

    // ── Calculator registration ───────────────────────────────────────────────

    private void registerBuiltInCalculators() {
        registerCalculator(new WorldContextCalculator());
        registerCalculator(new GameModeContextCalculator());
        registerOptionalCalculators();
    }

    /**
     * Registers the new (v1.2.x) optional context calculators:
     *   time            → time-of-day (day/night)
     *   playtime-tier   → newcomer/regular/veteran
     *   balance-tier    → poor/standard/rich (only if Vault economy present)
     *
     * All of these are additive and individually toggleable via config.yml
     * so they never interfere with existing setups. Any failure here is
     * caught and logged, never thrown — the plugin keeps working with the
     * original world/gamemode calculators regardless.
     */
    private void registerOptionalCalculators() {
        try {
            var cfg = plugin.getConfig();

            if (cfg.getBoolean("context.time-of-day.enabled", true)) {
                registerCalculator(new ir.permscraft.context.calculators.TimeOfDayContextCalculator());
            }

            if (cfg.getBoolean("context.playtime.enabled", true)) {
                double newcomerHours = cfg.getDouble("context.playtime.newcomer-hours", 1.0);
                double veteranHours  = cfg.getDouble("context.playtime.veteran-hours", 100.0);
                registerCalculator(new ir.permscraft.context.calculators.PlaytimeContextCalculator(
                        newcomerHours, veteranHours));
            }

            if (cfg.getBoolean("context.economy.enabled", true)
                    && plugin.getServer().getPluginManager().getPlugin("Vault") != null) {
                double poorBelow = cfg.getDouble("context.economy.poor-below", 100.0);
                double richAbove = cfg.getDouble("context.economy.rich-above", 10000.0);
                registerCalculator(new ir.permscraft.context.calculators.EconomyContextCalculator(
                        plugin.getServer().getServicesManager(), poorBelow, richAbove));
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[PermsCraft] Failed to register optional context calculators: "
                    + e.getMessage());
        }
    }

    /**
     * Register a custom context calculator.
     * Safe to call from any thread (CopyOnWriteArrayList + re-sort).
     */
    public void registerCalculator(ContextCalculator calculator) {
        calculators.add(calculator);
        // Keep sorted by priority ascending (lower runs first, higher overrides)
        calculators.sort(Comparator.comparingInt(ContextCalculator::priority));
        plugin.getLogger().info("[PermsCraft] Context calculator registered: "
                + calculator.name() + " (priority=" + calculator.priority() + ")");
    }

    public void unregisterCalculator(ContextCalculator calculator) {
        calculators.remove(calculator);
    }

    public List<ContextCalculator> getCalculators() {
        return Collections.unmodifiableList(calculators);
    }

    // ── Active context resolution ─────────────────────────────────────────────

    /**
     * Build the full active ContextSet for a player by running all calculators.
     * Called on the region/main thread (never async).
     *
     * Always includes server name if configured.
     */
    public ContextSet getActiveContextSet(Player player) {
        ContextSet.Builder builder = ContextSet.builder();

        // Server name from config
        String serverName = plugin.getServerName();
        if (serverName != null && !serverName.isBlank() && !serverName.equals("default")) {
            builder.put(Context.KEY_SERVER, serverName);
        }

        // Run all calculators in priority order
        for (ContextCalculator calc : calculators) {
            try {
                calc.calculate(player, builder);
            } catch (Exception e) {
                plugin.getLogger().warning("[PermsCraft] Calculator " + calc.name()
                        + " threw exception: " + e.getMessage());
            }
        }

        return builder.build();
    }

    /**
     * Legacy single-Context API — returns a simple world Context.
     * Kept for backward compat; prefer getActiveContextSet().
     */
    public Context getActiveContext(Player player) {
        return Context.world(player.getWorld().getName());
    }

    // ── Debug ─────────────────────────────────────────────────────────────────

    /**
     * Returns a human-readable breakdown of which calculator supplied each key
     * and which context-bound permissions are active for a player.
     * Used by /pc context debug <player>.
     */
    public List<String> debugPlayer(Player player) {
        List<String> lines = new ArrayList<>();
        ContextSet.Builder builder = ContextSet.builder();

        lines.add("&7--- Active context for &b" + player.getName() + " &7---");

        String serverName = plugin.getServerName();
        if (serverName != null && !serverName.isBlank() && !serverName.equals("default")) {
            builder.put(Context.KEY_SERVER, serverName);
            lines.add("&8[config] &7server=&f" + serverName);
        }

        for (ContextCalculator calc : calculators) {
            ContextSet.Builder stepBuilder = ContextSet.builder();
            try {
                calc.calculate(player, stepBuilder);
                ContextSet step = stepBuilder.build();
                step.asMap().forEach((k, v) -> {
                    builder.put(k, v);
                    lines.add("&8[" + calc.name() + "] &7" + k + "=&f" + v);
                });
            } catch (Exception e) {
                lines.add("&c[" + calc.name() + "] ERROR: " + e.getMessage());
            }
        }

        ContextSet active = builder.build();
        lines.add("&7--- Resolved: &f" + active);
        return lines;
    }

    // ── Storage load ──────────────────────────────────────────────────────────

    private void loadAll() {
        contextPerms.clear();
        for (ContextRow row : plugin.getStorage().loadAllContextPermissions()) {
            contextPerms
                .computeIfAbsent(row.target(), k -> new CopyOnWriteArrayList<>())
                .add(row.permission());
        }
    }

    // ── Mutations ─────────────────────────────────────────────────────────────

    /**
     * Add a permission bound to a full ContextSet.
     * Persists asynchronously.
     */
    public void addContextPermission(String target, boolean isGroup, String permission,
                                     ContextSet requiredCtx, boolean granted) {
        ContextualPermission cp = new ContextualPermission(permission, requiredCtx, granted);
        contextPerms.computeIfAbsent(target, k -> new CopyOnWriteArrayList<>()).add(cp);
        FoliaScheduler.runAsync(plugin, () ->
            plugin.getStorage().saveContextPermission(target, isGroup, permission, requiredCtx, granted));
    }

    /** Legacy single-Context overload — wraps into ContextSet. */
    public void addContextPermission(String target, boolean isGroup, String permission,
                                     Context context, boolean granted) {
        ContextSet cs = context.isGlobal() ? ContextSet.global()
                : ContextSet.builder().put(context).build();
        addContextPermission(target, isGroup, permission, cs, granted);
    }

    public void removeContextPermission(String target, String permission, ContextSet requiredCtx) {
        CopyOnWriteArrayList<ContextualPermission> list = contextPerms.get(target);
        if (list != null) {
            list.removeIf(cp -> cp.getPermission().equals(permission)
                    && cp.getRequiredContext().equals(requiredCtx));
        }
        FoliaScheduler.runAsync(plugin, () ->
            plugin.getStorage().deleteContextPermission(target, permission, requiredCtx));
    }

    /** Legacy single-Context overload. */
    public void removeContextPermission(String target, String permission, Context context) {
        ContextSet cs = context.isGlobal() ? ContextSet.global()
                : ContextSet.builder().put(context).build();
        removeContextPermission(target, permission, cs);
    }

    // ── Resolution ────────────────────────────────────────────────────────────

    /**
     * Resolve all active permissions for a target given the player's full ContextSet.
     * Returns Map<permNode, Boolean> (true=grant, false=deny).
     * Deny always wins over grant for the same node.
     */
    public Map<String, Boolean> resolvePermissionsWithDenied(String target, ContextSet active) {
        List<ContextualPermission> list = contextPerms.getOrDefault(target, new CopyOnWriteArrayList<>());
        // Use LinkedHashMap to preserve insertion order; last-write wins for same key
        Map<String, Boolean> grants = new LinkedHashMap<>();
        Map<String, Boolean> denies = new LinkedHashMap<>();

        for (ContextualPermission cp : list) {
            if (cp.appliesIn(active)) {
                if (cp.getValue()) grants.put(cp.getPermission(), true);
                else               denies.put(cp.getPermission(), false);
            }
        }
        // Deny wins — remove any grants that are explicitly denied
        grants.keySet().removeAll(denies.keySet());
        Map<String, Boolean> result = new LinkedHashMap<>(grants);
        result.putAll(denies);
        return result;
    }

    /** Legacy single-Context overload. */
    public Map<String, Boolean> resolvePermissionsWithDenied(String target, Context active) {
        ContextSet cs = ContextSet.builder().put(active).build();
        return resolvePermissionsWithDenied(target, cs);
    }

    public Set<String> resolvePermissions(String target, ContextSet active) {
        Map<String, Boolean> map = resolvePermissionsWithDenied(target, active);
        Set<String> result = new HashSet<>();
        map.forEach((p, v) -> { if (v) result.add(p); });
        return result;
    }

    /** Legacy. */
    public Set<String> resolvePermissions(String target, Context active) {
        return resolvePermissions(target, ContextSet.builder().put(active).build());
    }

    public boolean hasPermission(String target, String permission, ContextSet active) {
        return WildcardUtil.hasPermission(resolvePermissions(target, active), permission);
    }

    public List<ContextualPermission> getPermissions(String target) {
        return contextPerms.getOrDefault(target, new CopyOnWriteArrayList<>());
    }

    public void reload() { loadAll(); }
}
