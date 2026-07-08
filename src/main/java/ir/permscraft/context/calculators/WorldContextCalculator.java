package ir.permscraft.context.calculators;

import ir.permscraft.context.Context;
import ir.permscraft.context.ContextCalculator;
import ir.permscraft.context.ContextSet;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * Built-in calculator. Supplies:
 *   world      = <worldName>
 *   dimension  = normal | nether | the_end
 *   world-uuid = <uuid>
 */
public class WorldContextCalculator implements ContextCalculator {
    @Override
    public void calculate(Player player, ContextSet.Builder builder) {
        World world = player.getWorld();
        builder.put(Context.KEY_WORLD,      world.getName());
        builder.put(Context.KEY_WORLD_UUID, world.getUID().toString());
        builder.put(Context.KEY_DIMENSION,  dimensionOf(world));
    }
    private String dimensionOf(World world) {
        return switch (world.getEnvironment()) {
            case NETHER  -> "nether";
            case THE_END -> "the_end";
            default      -> "normal";
        };
    }
    @Override public String name()     { return "WorldContextCalculator"; }
    @Override public int    priority() { return 0; }
}
