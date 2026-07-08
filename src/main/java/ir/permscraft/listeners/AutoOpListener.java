package ir.permscraft.listeners;

import ir.permscraft.PermsCraft;
import ir.permscraft.models.User;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Implements the PermsCraft auto-op feature.
 *
 * If a player (or their group) has the node "permscraft.autoop" set to true,
 * they are automatically granted server OP while online.
 * The OP flag is removed when they log out.
 *
 *
 * Enable/disable via config: settings.auto-op: true
 */
public class AutoOpListener implements Listener {

    private static final String AUTO_OP_NODE = "permscraft.autoop";

    private final PermsCraft plugin;

    public AutoOpListener(PermsCraft plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!plugin.getConfig().getBoolean("settings.auto-op", false)) return;
        refreshAutoOp(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (!plugin.getConfig().getBoolean("settings.auto-op", false)) return;
        Player player = event.getPlayer();

        // FIX Bug #1: Always check OP status on quit — unconditionally.
        // The previous implementation only called setOp(false) when the User
        // object was still in memory (user != null). If unloadUser() fired before
        // this event (race condition on some server implementations), user was null
        // and the OP flag was NEVER cleared, leaving the player permanently OP'd
        // after logout — a serious security hole.
        //
        // New logic: if the player is currently OP, we try to find the user.
        // If user is found and had autoop → strip OP (normal path).
        // If user is null (already unloaded) → strip OP anyway as a safe fallback,
        //   because we cannot confirm they no longer deserve it.
        if (player.isOp()) {
            User user = plugin.getUserManager().getUser(player.getUniqueId());
            if (user != null) {
                boolean hadAutoOp = plugin.getInheritanceGraph().hasPermission(user, AUTO_OP_NODE);
                if (hadAutoOp) {
                    player.setOp(false);
                }
            } else {
                // Safe fallback: user already unloaded, can't verify — revoke OP.
                player.setOp(false);
                plugin.getLogger().info("[PermsCraft] AutoOp: revoked OP for "
                        + player.getName() + " on quit (user was already unloaded — safe fallback).");
            }
        }
    }

    /**
     * Called after permission refresh to update the OP flag.
     */
    public void refreshAutoOp(Player player) {
        if (!plugin.getConfig().getBoolean("settings.auto-op", false)) return;
        User user = plugin.getUserManager().getUser(player.getUniqueId());
        if (user == null) return;
        boolean hasAutoOp = plugin.getInheritanceGraph().hasPermission(user, AUTO_OP_NODE);
        if (player.isOp() != hasAutoOp) {
            player.setOp(hasAutoOp);
        }
    }
}
