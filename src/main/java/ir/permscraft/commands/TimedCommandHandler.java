package ir.permscraft.commands;

import ir.permscraft.PermsCraft;
import ir.permscraft.managers.TimedPermissionManager;
import ir.permscraft.utils.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.UUID;

public class TimedCommandHandler {

    private final PermsCraft plugin;

    public TimedCommandHandler(PermsCraft plugin) {
        this.plugin = plugin;
    }

    /**
     * /pc user <name> permission settemp <permission> <duration>
     *
     * FIX (double-write): The original code called both addTimedPermission() AND
     * addPermission() (UserManager). addPermission() persists to pc_user_permissions,
     * making the node permanent. When the timed permission expired it was cleaned from
     * pc_timed_permissions but stayed forever in pc_user_permissions.
     *
     * Fix: only addTimedPermission() is called. TimedPermissionManager.addTimedPermission()
     * already calls refreshPermissions() internally, so the player gets the permission
     * immediately without a separate addPermission() call.
     */
    public void handleUserTimed(CommandSender sender, String targetName, String permission, String durationStr) {
        UUID uuid = resolveUuid(targetName);
        if (uuid == null) {
            MessageUtil.send(sender, "&cPlayer not found.");
            return;
        }

        long seconds = TimedPermissionManager.parseDuration(durationStr);
        if (seconds <= 0) {
            MessageUtil.send(sender, "&cInvalid duration. Use format: &e1d2h30m");
            return;
        }

        // FIX: removed the extra addPermission() call that was writing to pc_user_permissions
        // and making the timed permission permanent after expiry.
        plugin.getTimedPermissionManager().addTimedPermission(uuid.toString(), false, permission, seconds);

        MessageUtil.send(sender, "&aGranted &e" + permission + " &ato &b" + targetName
                + " &afor &e" + durationStr);
    }

    /**
     * /pc group <name> permission settemp <permission> <duration>
     *
     * FIX (double-write): same issue as handleUserTimed — removed the extra
     * addPermission() call to GroupManager that persisted the node permanently.
     */
    public void handleGroupTimed(CommandSender sender, String groupName, String permission, String durationStr) {
        if (!plugin.getGroupManager().groupExists(groupName)) {
            MessageUtil.send(sender, "&cGroup not found.");
            return;
        }

        long seconds = TimedPermissionManager.parseDuration(durationStr);
        if (seconds <= 0) {
            MessageUtil.send(sender, "&cInvalid duration. Use format: &e1d2h30m");
            return;
        }

        // FIX: removed the extra addPermission() call to GroupManager.
        plugin.getTimedPermissionManager().addTimedPermission(groupName, true, permission, seconds);
        // Refresh all online members of this group so they see the permission immediately.
        plugin.getServer().getOnlinePlayers().forEach(p -> {
            var user = plugin.getUserManager().getUser(p.getUniqueId());
            if (user != null && user.inGroup(groupName))
                plugin.getUserManager().refreshPermissions(p.getUniqueId());
        });

        MessageUtil.send(sender, "&aGranted &e" + permission + " &ato group &b" + groupName
                + " &afor &e" + durationStr);
    }

    public void listTimedPerms(CommandSender sender, String target, boolean isGroup) {
        String key = isGroup ? target : null;
        if (!isGroup) {
            UUID uuid = resolveUuid(target);
            if (uuid == null) { MessageUtil.send(sender, "&cPlayer not found."); return; }
            key = uuid.toString();
        }

        var list = plugin.getTimedPermissionManager().getTimedPermissions(key);
        if (list.isEmpty()) {
            MessageUtil.send(sender, "&7No timed permissions for &b" + target);
            return;
        }
        MessageUtil.send(sender, "&7Timed permissions for &b" + target + "&7:");
        for (var tp : list) {
            if (!tp.isExpired()) {
                MessageUtil.sendRaw(sender, "  &8- &f" + tp.getPermission()
                        + " &7(expires in &e" + tp.getFormattedExpiry() + "&7)");
            }
        }
    }

    private UUID resolveUuid(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online.getUniqueId();
        return plugin.getStorage().findUUIDByUsername(name);
    }
}
