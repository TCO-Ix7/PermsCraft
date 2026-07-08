package ir.permscraft.context.calculators;

import ir.permscraft.context.ContextCalculator;
import ir.permscraft.context.ContextSet;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicesManager;

/**
 * Optional calculator — only registered when Vault AND an economy plugin
 * are present on the server (see ContextManager.registerBuiltInCalculators).
 *
 * Supplies:
 *   balance-tier = poor | standard | rich
 *
 * Thresholds are configurable in config.yml under "context.economy":
 *   poor-below:  100
 *   rich-above:  10000
 *   (everything in between → standard)
 *
 * Example use-cases:
 *   /pc context group default set permscraft.shop.vip balance-tier=rich
 *     → only wealthy players can access the VIP shop command
 *
 *   /pc context group default set essentialsx.kit.welfare balance-tier=poor
 *     → give a small income boost to players who are low on cash
 *
 * Fails safe: if Vault or the economy provider becomes unavailable at
 * runtime (e.g. unloaded), this calculator simply contributes nothing for
 * that resolution — it never throws or blocks permission checks.
 *
 * Toggle in config.yml:
 *   context:
 *     economy:
 *       enabled: true
 *       poor-below: 100
 *       rich-above: 10000
 */
public class EconomyContextCalculator implements ContextCalculator {

    public static final String KEY_BALANCE_TIER = "balance-tier";

    private final ServicesManager servicesManager;
    private final double poorBelow;
    private final double richAbove;

    public EconomyContextCalculator(ServicesManager servicesManager, double poorBelow, double richAbove) {
        this.servicesManager = servicesManager;
        this.poorBelow = poorBelow;
        this.richAbove = richAbove;
    }

    @Override
    public void calculate(Player player, ContextSet.Builder builder) {
        RegisteredServiceProvider<Economy> provider = servicesManager.getRegistration(Economy.class);
        if (provider == null) return; // fail-safe: no economy plugin registered, contribute nothing

        Economy economy = provider.getProvider();
        double balance = economy.getBalance(player);

        String tier;
        if (balance < poorBelow)       tier = "poor";
        else if (balance >= richAbove) tier = "rich";
        else                            tier = "standard";

        builder.put(KEY_BALANCE_TIER, tier);
    }

    @Override public String name()     { return "EconomyContextCalculator"; }
    @Override public int    priority() { return 1; }
}
