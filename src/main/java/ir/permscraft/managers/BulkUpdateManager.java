package ir.permscraft.managers;

import ir.permscraft.FoliaScheduler;
import ir.permscraft.PermsCraft;
import ir.permscraft.models.Group;
import ir.permscraft.models.User;
import org.bukkit.command.CommandSender;

import java.util.*;

/**
 * Bulk update system — apply a permission change across all users or groups at once.
 *
 * Supports:
 *   /pc bulkupdate users   add    <permission>
 *   /pc bulkupdate users   remove <permission>
 *   /pc bulkupdate groups  add    <permission>
 *   /pc bulkupdate groups  remove <permission>
 *   /pc bulkupdate all     add    <permission>
 *   /pc bulkupdate all     remove <permission>
 *
 *   /pc bulkupdate users   addgroup  <group>
 *   /pc bulkupdate users   removegroup <group>
 *
 * All operations run asynchronously to avoid blocking the main thread.
 */
public class BulkUpdateManager {

    private final PermsCraft plugin;

    public BulkUpdateManager(PermsCraft plugin) {
        this.plugin = plugin;
    }

    /**
     * Add a permission to ALL users in storage.
     * Returns the count of users affected (best-effort; -1 = unknown).
     */
    public void addPermissionToAllUsers(CommandSender sender, String permission) {
        FoliaScheduler.runAsync(plugin, () -> {
            int count = plugin.getStorage().bulkAddPermissionToUsers(permission);
            // Also update in-memory loaded users
            int inMemory = 0;
            for (User user : plugin.getUserManager().getAllLoadedUsers()) {
                if (!user.hasPermission(permission)) {
                    user.addPermission(permission);
                    plugin.getUserManager().refreshPermissions(user.getUuid());
                    inMemory++;
                }
            }
            // FIX: copy to final variable before using inside lambda
            final int total = Math.max(count, inMemory);
            FoliaScheduler.runSync(plugin, () ->
                sender.sendMessage(ir.permscraft.utils.MessageUtil.colorizeString(
                    "&8[&bPermsCraft&8] &aAdded &e" + permission + " &ato &f" + total + " &auser(s).")));
        });
    }

    /**
     * Remove a permission from ALL users in storage.
     */
    public void removePermissionFromAllUsers(CommandSender sender, String permission) {
        FoliaScheduler.runAsync(plugin, () -> {
            int count = plugin.getStorage().bulkRemovePermissionFromUsers(permission);
            for (User user : plugin.getUserManager().getAllLoadedUsers()) {
                user.removePermission(permission);
                plugin.getUserManager().refreshPermissions(user.getUuid());
            }
            FoliaScheduler.runSync(plugin, () ->
                sender.sendMessage(ir.permscraft.utils.MessageUtil.colorizeString(
                    "&8[&bPermsCraft&8] &cRemoved &e" + permission + " &cfrom &f" + count + " &cuser(s).")));
        });
    }

    /**
     * Add a permission to ALL groups.
     */
    public void addPermissionToAllGroups(CommandSender sender, String permission) {
        FoliaScheduler.runAsync(plugin, () -> {
            List<Group> changed = new ArrayList<>();
            for (Group g : plugin.getGroupManager().getAllGroups()) {
                if (!g.hasPermission(permission)) {
                    g.addPermission(permission);
                    plugin.getStorage().addGroupPermission(g.getName(), permission);
                    plugin.getPermissionCache().invalidateGroupAndChildren(
                            g.getName(), () -> plugin.getGroupManager().getAllGroups());
                    changed.add(g);
                }
            }
            final int finalCount = changed.size();
            FoliaScheduler.runSync(plugin, () -> {
                for (Group g : changed) {
                    plugin.getGroupManager().refreshGroupMembers(g.getName());
                }
                sender.sendMessage(ir.permscraft.utils.MessageUtil.colorizeString(
                    "&8[&bPermsCraft&8] &aAdded &e" + permission + " &ato &f" + finalCount + " &agroup(s)."));
            });
        });
    }

    /**
     * Remove a permission from ALL groups.
     */
    public void removePermissionFromAllGroups(CommandSender sender, String permission) {
        FoliaScheduler.runAsync(plugin, () -> {
            List<Group> changed = new ArrayList<>();
            for (Group g : plugin.getGroupManager().getAllGroups()) {
                if (g.hasPermission(permission)) {
                    g.removePermission(permission);
                    plugin.getStorage().removeGroupPermission(g.getName(), permission);
                    plugin.getPermissionCache().invalidateGroupAndChildren(
                            g.getName(), () -> plugin.getGroupManager().getAllGroups());
                    changed.add(g);
                }
            }
            final int finalCount = changed.size();
            FoliaScheduler.runSync(plugin, () -> {
                for (Group g : changed) {
                    plugin.getGroupManager().refreshGroupMembers(g.getName());
                }
                sender.sendMessage(ir.permscraft.utils.MessageUtil.colorizeString(
                    "&8[&bPermsCraft&8] &cRemoved &e" + permission + " &cfrom &f" + finalCount + " &cgroup(s)."));
            });
        });
    }

    /**
     * Add a group membership to all users (e.g. /pc bulkupdate users addgroup member).
     */
    public void addGroupToAllUsers(CommandSender sender, String groupName) {
        if (!plugin.getGroupManager().groupExists(groupName)) {
            sender.sendMessage(ir.permscraft.utils.MessageUtil.colorizeString(
                "&8[&bPermsCraft&8] &cGroup &e" + groupName + " &cdoesn't exist."));
            return;
        }
        FoliaScheduler.runAsync(plugin, () -> {
            int count = plugin.getStorage().bulkAddGroupToUsers(groupName);
            for (User user : plugin.getUserManager().getAllLoadedUsers()) {
                if (!user.inGroup(groupName)) {
                    user.addGroup(groupName);
                    plugin.getUserManager().refreshPermissions(user.getUuid());
                }
            }
            FoliaScheduler.runSync(plugin, () ->
                sender.sendMessage(ir.permscraft.utils.MessageUtil.colorizeString(
                    "&8[&bPermsCraft&8] &aAdded group &e" + groupName + " &ato &f" + count + " &auser(s).")));
        });
    }

    /**
     * Remove a group membership from all users.
     */
    public void removeGroupFromAllUsers(CommandSender sender, String groupName) {
        FoliaScheduler.runAsync(plugin, () -> {
            int count = plugin.getStorage().bulkRemoveGroupFromUsers(groupName);
            for (User user : plugin.getUserManager().getAllLoadedUsers()) {
                user.removeGroup(groupName);
                plugin.getUserManager().refreshPermissions(user.getUuid());
            }
            FoliaScheduler.runSync(plugin, () ->
                sender.sendMessage(ir.permscraft.utils.MessageUtil.colorizeString(
                    "&8[&bPermsCraft&8] &cRemoved group &e" + groupName + " &cfrom &f" + count + " &cuser(s).")));
        });
    }
}
