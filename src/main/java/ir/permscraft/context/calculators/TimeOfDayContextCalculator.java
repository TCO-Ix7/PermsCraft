package ir.permscraft.context.calculators;

import ir.permscraft.context.ContextCalculator;
import ir.permscraft.context.ContextSet;
import org.bukkit.entity.Player;

/**
 * Built-in (optional) calculator. Supplies:
 *   time = day | night
 *
 * Based on the player's current world time (a full day/night cycle is
 * 24000 ticks). Day is roughly ticks 0-12999, night is 13000-23999.
 *
 * Example use-cases:
 *   /pc context group survivor set essentials.fly time=night
 *     → survivors can only /fly at night (e.g. to escape mobs)
 *
 *   /pc context group default set -minecraft.command.sleep time=day
 *     → block /sleep during the day
 *
 * Toggle in config.yml:
 *   context:
 *     time-of-day:
 *       enabled: true
 */
public class TimeOfDayContextCalculator implements ContextCalculator {

    public static final String KEY_TIME = "time";

    @Override
    public void calculate(Player player, ContextSet.Builder builder) {
        long time = player.getWorld().getTime();
        String phase = (time >= 13000 && time < 24000) ? "night" : "day";
        builder.put(KEY_TIME, phase);
    }

    @Override public String name()     { return "TimeOfDayContextCalculator"; }
    @Override public int    priority() { return 1; }
}
