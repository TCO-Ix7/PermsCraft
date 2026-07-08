package ir.permscraft.listeners;

import ir.permscraft.PermsCraft;
import ir.permscraft.utils.MessageUtil;
import org.bukkit.command.CommandSender;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.RemoteServerCommandEvent;
import org.bukkit.event.server.ServerCommandEvent;

import java.util.regex.Pattern;

/**
 * Blocks /op and /deop commands when settings.disable-op-command is enabled.
 *
 * the /op command becomes dangerous because it bypasses the permission system.
 * Admins can still use /pc setup op <player> from the console for initial setup.
 *
 * Enable/disable via config: settings.disable-op-command: true
 */
public class OpCommandBlocker implements Listener {

    private static final Pattern OP_PATTERN = Pattern.compile(
            "^/?(?:\\w+:)?(?:deop|op)(?:\\s.*)?$", Pattern.CASE_INSENSITIVE);

    private final PermsCraft plugin;

    public OpCommandBlocker(PermsCraft plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        handleCommand(event.getPlayer(), event.getMessage(), event);
    }

    @EventHandler(ignoreCancelled = true)
    public void onServerCommand(ServerCommandEvent event) {
        handleCommand(event.getSender(), event.getCommand(), event);
    }

    @EventHandler(ignoreCancelled = true)
    public void onRconCommand(RemoteServerCommandEvent event) {
        handleCommand(event.getSender(), event.getCommand(), event);
    }

    private void handleCommand(CommandSender sender, String cmdLine, Cancellable event) {
        if (!plugin.getConfig().getBoolean("settings.disable-op-command", false)) return;
        if (cmdLine == null || cmdLine.isEmpty()) return;

        if (OP_PATTERN.matcher(cmdLine.trim()).matches()) {
            event.setCancelled(true);
            MessageUtil.send(sender, "&cThe /op and /deop commands are disabled by PermsCraft.");
            MessageUtil.send(sender, "&7Use &b/pc group <name> permission set <node> &7to manage permissions.");
        }
    }
}
