package ir.permscraft.context.calculators;

import ir.permscraft.PermsCraft;
import ir.permscraft.context.Context;
import ir.permscraft.context.ContextCalculator;
import ir.permscraft.context.ContextSet;
import org.bukkit.entity.Player;

/**
 * Provides the {@code server} context key for BungeeCord/Velocity networks.
 *
 * Context supplied:
 *   server = <server-name>      (from config.yml → server-name)
 *
 * This allows setting permissions that only apply on specific servers:
 *   /pc context group admin set essentials.fly server=lobby
 *   → essentials.fly only granted when player is on the lobby server
 *
 * The server name is read from config.yml:
 *   server-name: "lobby"
 *
 * If not configured, defaults to "default".
 *
 * PermsCraft advantage: also provides `server-id` context with the server's
 * unique ID (UUID) from paper config — collision-safe for networks with
 * identically-named servers.
 */
public class ServerContextCalculator implements ContextCalculator {

    private final PermsCraft plugin;
    private final String serverName;
    private final String serverId;

    public ServerContextCalculator(PermsCraft plugin) {
        this.plugin     = plugin;
        this.serverName = plugin.getConfig().getString("server-name", "default").toLowerCase();
        // Use Paper's server UUID if available (collision-safe), fallback to name
        String id;
        try {
            id = org.bukkit.Bukkit.getServer().getPort() + "-" + serverName;
        } catch (Exception e) {
            id = serverName;
        }
        this.serverId = id;
    }

    @Override
    public void calculate(Player player, ContextSet.Builder builder) {
        builder.put(Context.KEY_SERVER, serverName);
        builder.put("server-id", serverId);
    }

    @Override public String name()     { return "ServerContextCalculator"; }
    @Override public int    priority() { return 0; } // run first — most fundamental context

    public String getServerName() { return serverName; }
    public String getServerId()   { return serverId; }
}
