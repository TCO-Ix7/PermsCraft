package ir.permscraft.context;

import ir.permscraft.context.ContextSet;

import org.bukkit.entity.Player;

/**
 * A ContextCalculator contributes key=value entries to a player's active
 * ContextSet each time permissions are evaluated.
 *
 * BUILT-IN calculators (registered automatically by ContextManager):
 *   WorldContextCalculator     → world, dimension, world-uuid
 *   GameModeContextCalculator  → gamemode
 *
 * CUSTOM calculators (third-party plugins or admins via API):
 *   Register via ContextManager.registerCalculator(MyCalculator.class)
 *   Example use-cases:
 *     • region=<regionId>   (WorldGuard integration)
 *     • rank=<rankName>     (economy plugin)
 *     • flag=<flagName>     (custom game flag)
 *     • time=day|night      (in-game time)
 *
 *   PermsCraft improves on it by:
 *     1. Returning a ContextSet.Builder so a single calculator can add
 *        MULTIPLE keys in one call (e.g. world + dimension + world-uuid).
 *     2. Providing a priority() method — higher priority calculators run
 *        last and can OVERRIDE keys set by lower-priority ones.
 *     3. Built-in calculators auto-supply dimension and gamemode which
 */
public interface ContextCalculator {

    /**
     * Populate context entries for {@code player} into {@code builder}.
     * Called on the region/main thread (never async).
     */
    void calculate(Player player, ContextSet.Builder builder);

    /**
     * Higher value = runs last = can override lower-priority keys.
     * Built-ins run at priority 0. Custom calculators should use > 0.
     */
    default int priority() { return 0; }

    /** Human-readable name used in /pc context debug output. */
    default String name() { return getClass().getSimpleName(); }
}
