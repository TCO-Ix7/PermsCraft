package ir.permscraft.context.calculators;

import ir.permscraft.context.ContextCalculator;
import ir.permscraft.context.ContextSet;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;

/**
 * Built-in (optional) calculator. Supplies:
 *   playtime-tier = newcomer | regular | veteran
 *
 * Derived from Bukkit's PLAY_ONE_MINUTE statistic (recorded in ticks,
 * 20 ticks = 1 second).
 *
 * Thresholds are configurable in config.yml under "context.playtime":
 *   newcomer-hours: 1     (below this   → newcomer)
 *   veteran-hours:  100   (at/above this → veteran)
 *   (everything in between → regular)
 *
 * Example use-cases:
 *   /pc context group default set essentialsx.kit.starter playtime-tier=newcomer
 *     → only brand-new players can claim the starter kit
 *
 *   /pc context group member set permscraft.fly.lobby playtime-tier=veteran
 *     → reward long-time players with /fly in the lobby
 *
 * Toggle in config.yml:
 *   context:
 *     playtime:
 *       enabled: true
 *       newcomer-hours: 1
 *       veteran-hours: 100
 */
public class PlaytimeContextCalculator implements ContextCalculator {

    public static final String KEY_PLAYTIME_TIER = "playtime-tier";

    private final double newcomerHours;
    private final double veteranHours;

    public PlaytimeContextCalculator(double newcomerHours, double veteranHours) {
        this.newcomerHours = newcomerHours;
        this.veteranHours  = veteranHours;
    }

    @Override
    public void calculate(Player player, ContextSet.Builder builder) {
        int ticks = player.getStatistic(Statistic.PLAY_ONE_MINUTE);
        double hours = ticks / 20.0 / 3600.0;

        String tier;
        if (hours < newcomerHours)      tier = "newcomer";
        else if (hours >= veteranHours) tier = "veteran";
        else                             tier = "regular";

        builder.put(KEY_PLAYTIME_TIER, tier);
    }

    @Override public String name()     { return "PlaytimeContextCalculator"; }
    @Override public int    priority() { return 1; }
}
