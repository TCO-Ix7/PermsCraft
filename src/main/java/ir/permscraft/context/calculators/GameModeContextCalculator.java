package ir.permscraft.context.calculators;

import ir.permscraft.context.Context;
import ir.permscraft.context.ContextCalculator;
import ir.permscraft.context.ContextSet;
import org.bukkit.entity.Player;

/**
 * Built-in calculator. Supplies:
 *   gamemode = survival | creative | adventure | spectator
 */
public class GameModeContextCalculator implements ContextCalculator {
    @Override
    public void calculate(Player player, ContextSet.Builder builder) {
        builder.put(Context.KEY_GAMEMODE, player.getGameMode().name().toLowerCase());
    }
    @Override public String name()     { return "GameModeContextCalculator"; }
    @Override public int    priority() { return 0; }
}
