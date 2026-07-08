package ir.permscraft.commands;

import ir.permscraft.FoliaScheduler;
import ir.permscraft.PermsCraft;
import ir.permscraft.models.Group;
import ir.permscraft.utils.MessageUtil;
import org.bukkit.command.CommandSender;

import java.util.Arrays;
import java.util.Set;

/**
 * Handles all /pc group <name> subcommands.
 * Extracted from PCCommand to keep each handler focused and maintainable.
 */
public class GroupCommandHandler {

    private final PermsCraft plugin;
    private final TimedCommandHandler timedHandler;

    public GroupCommandHandler(PermsCraft plugin, TimedCommandHandler timedHandler) {
        this.plugin = plugin;
        this.timedHandler = timedHandler;
    }

    /** Entry point: /pc group [name] [action] [...] */
    public void handle(CommandSender sender, String[] args) {
        if (args.length < 2) {
            MessageUtil.send(sender, "&7All groups: &a" +
                    String.join("&7, &a", plugin.getGroupManager().getAllGroups().stream().map(Group::getName).toList()));
            return;
        }

        String groupName = args[1].toLowerCase();

        if (args.length < 3) {
            Group g = plugin.getGroupManager().getGroup(groupName);
            if (g == null) { MessageUtil.send(sender, "&cGroup &e" + groupName + " &cdoesn't exist."); return; }
            printGroupInfo(sender, g);
            return;
        }

        String action = args[2].toLowerCase();
        switch (action) {
            case "create"          -> handleCreate(sender, groupName);
            case "delete"          -> handleDelete(sender, groupName);
            case "permission",
                 "perm"            -> handlePermission(sender, args, groupName);
            case "parent"          -> handleParent(sender, args, groupName);
            case "setprefix"       -> handleSetPrefix(sender, args, groupName);
            case "setsuffix"       -> handleSetSuffix(sender, args, groupName);
            case "clearprefix"     -> { plugin.getGroupManager().setPrefix(groupName, ""); MessageUtil.send(sender, "&aCleared prefix for group &b" + groupName); }
            case "clearsuffix"     -> { plugin.getGroupManager().setSuffix(groupName, ""); MessageUtil.send(sender, "&aCleared suffix for group &b" + groupName); }
            case "setweight"       -> handleSetWeight(sender, args, groupName);
            case "rename"          -> handleRename(sender, args, groupName);
            case "clone"           -> handleClone(sender, args, groupName);
            case "clear"           -> handleClear(sender, groupName);
            case "showtracks"      -> handleShowTracks(sender, groupName);
            case "meta"            -> handleMeta(sender, args, groupName);
            default                -> sendHelp(sender);
        }
    }

    private void handleCreate(CommandSender sender, String groupName) {
        if (plugin.getGroupManager().groupExists(groupName)) {
            MessageUtil.send(sender, "&cGroup &e" + groupName + " &calready exists.");
        } else {
            plugin.getGroupManager().createGroup(groupName);
            MessageUtil.send(sender, "&aCreated group &e" + groupName);
        }
    }

    private void handleDelete(CommandSender sender, String groupName) {
        if (groupName.equals("default")) { MessageUtil.send(sender, "&cCannot delete the default group."); return; }
        if (!plugin.getGroupManager().groupExists(groupName)) { MessageUtil.send(sender, "&cGroup not found."); return; }
        plugin.getGroupManager().deleteGroup(groupName);
        MessageUtil.send(sender, "&cDeleted group &e" + groupName);
    }

    private void handlePermission(CommandSender sender, String[] args, String groupName) {
        if (args.length < 4) { sendPermHelp(sender); return; }
        String permAction = args[3].toLowerCase();
        switch (permAction) {
            case "set", "add" -> {
                if (args.length < 5) { MessageUtil.send(sender, "&cUsage: /pc group <name> permission set <permission>"); return; }
                if (!plugin.getGroupManager().groupExists(groupName)) { MessageUtil.send(sender, "&cGroup not found."); return; }
                plugin.getGroupManager().addPermission(groupName, args[4]);
                MessageUtil.send(sender, "&aSet &e" + args[4] + " &afor group &b" + groupName);
            }
            case "unset", "remove" -> {
                if (args.length < 5) { MessageUtil.send(sender, "&cUsage: /pc group <name> permission unset <permission>"); return; }
                plugin.getGroupManager().removePermission(groupName, args[4]);
                MessageUtil.send(sender, "&cUnset &e" + args[4] + " &cfrom group &b" + groupName);
            }
            case "settemp" -> {
                if (args.length < 6) { MessageUtil.send(sender, "&cUsage: /pc group <name> permission settemp <permission> <duration>"); return; }
                timedHandler.handleGroupTimed(sender, groupName, args[4], args[5]);
            }
            case "list" -> {
                Group g = plugin.getGroupManager().getGroup(groupName);
                if (g == null) { MessageUtil.send(sender, "&cGroup not found."); return; }
                MessageUtil.send(sender, "&7Permissions for group &b" + groupName + "&7:");
                if (g.getPermissions().isEmpty()) {
                    MessageUtil.sendRaw(sender, "  &8(none)");
                } else {
                    g.getPermissions().forEach(p -> MessageUtil.sendRaw(sender, "  &8- &f" + p));
                }
                var timed = plugin.getTimedPermissionManager().getTimedPermissions(groupName);
                timed.stream().filter(t -> !t.isExpired()).forEach(t ->
                    MessageUtil.sendRaw(sender, "  &8- &f" + t.getPermission() + " &7(&6" + t.getFormattedExpiry() + "&7)"));
            }
            case "listall" -> {
                if (!plugin.getGroupManager().groupExists(groupName)) { MessageUtil.send(sender, "&cGroup not found."); return; }
                Set<String> resolved = plugin.getGroupManager().getResolvedPermissions(groupName);
                MessageUtil.send(sender, "&7All permissions for &b" + groupName + " &7(including inherited): &e" + resolved.size());
                resolved.forEach(p -> MessageUtil.sendRaw(sender, "  &8- &f" + p));
            }
            default -> sendPermHelp(sender);
        }
    }

    private void handleParent(CommandSender sender, String[] args, String groupName) {
        if (args.length < 4) {
            Group g = plugin.getGroupManager().getGroup(groupName);
            if (g == null) { MessageUtil.send(sender, "&cGroup not found."); return; }
            MessageUtil.send(sender, "&7Parents of &b" + groupName + "&7: &a" +
                    (g.getInheritedGroups().isEmpty() ? "none" : String.join(", ", g.getInheritedGroups())));
            return;
        }
        String parentAction = args[3].toLowerCase();
        switch (parentAction) {
            case "add" -> {
                if (args.length < 5) { MessageUtil.send(sender, "&cUsage: /pc group <name> parent add <parentGroup>"); return; }
                if (!plugin.getGroupManager().groupExists(groupName)) { MessageUtil.send(sender, "&cGroup not found."); return; }
                if (!plugin.getGroupManager().groupExists(args[4])) { MessageUtil.send(sender, "&cParent group &e" + args[4] + " &cdoesn't exist."); return; }
                if (args[4].equalsIgnoreCase(groupName)) { MessageUtil.send(sender, "&cA group can't inherit itself."); return; }
                plugin.getGroupManager().addInheritance(groupName, args[4].toLowerCase());
                MessageUtil.send(sender, "&aGroup &b" + groupName + " &anow inherits from &e" + args[4]);
            }
            case "remove" -> {
                if (args.length < 5) { MessageUtil.send(sender, "&cUsage: /pc group <name> parent remove <parentGroup>"); return; }
                plugin.getGroupManager().removeInheritance(groupName, args[4].toLowerCase());
                MessageUtil.send(sender, "&cRemoved inheritance of &e" + args[4] + " &cfrom &b" + groupName);
            }
            default -> MessageUtil.send(sender, "&cUsage: /pc group <name> parent <add|remove> <parentGroup>");
        }
    }

    private void handleSetPrefix(CommandSender sender, String[] args, String groupName) {
        if (args.length < 4) { MessageUtil.send(sender, "&cUsage: /pc group <name> setprefix <prefix>"); return; }
        String prefix = String.join(" ", Arrays.copyOfRange(args, 3, args.length));
        plugin.getGroupManager().setPrefix(groupName, prefix);
        MessageUtil.send(sender, "&aSet prefix for group &b" + groupName + "&a: &r" + MessageUtil.colorizeString(prefix));
    }

    private void handleSetSuffix(CommandSender sender, String[] args, String groupName) {
        if (args.length < 4) { MessageUtil.send(sender, "&cUsage: /pc group <name> setsuffix <suffix>"); return; }
        String suffix = String.join(" ", Arrays.copyOfRange(args, 3, args.length));
        plugin.getGroupManager().setSuffix(groupName, suffix);
        MessageUtil.send(sender, "&aSet suffix for group &b" + groupName + "&a: &r" + MessageUtil.colorizeString(suffix));
    }

    private void handleSetWeight(CommandSender sender, String[] args, String groupName) {
        if (args.length < 4) { MessageUtil.send(sender, "&cUsage: /pc group <name> setweight <number>"); return; }
        try {
            int weight = Integer.parseInt(args[3]);
            plugin.getGroupManager().setWeight(groupName, weight);
            MessageUtil.send(sender, "&aSet weight of group &b" + groupName + " &ato &e" + weight);
        } catch (NumberFormatException e) {
            MessageUtil.send(sender, "&cWeight must be a number.");
        }
    }

    private void handleRename(CommandSender sender, String[] args, String groupName) {
        if (args.length < 4) { MessageUtil.send(sender, "&cUsage: /pc group <name> rename <newname>"); return; }
        if (groupName.equals("default")) { MessageUtil.send(sender, "&cCannot rename the default group."); return; }
        if (!plugin.getGroupManager().groupExists(groupName)) { MessageUtil.send(sender, "&cGroup not found."); return; }
        String newName = args[3].toLowerCase();
        MessageUtil.send(sender, "&7Renaming &b" + groupName + " &7to &e" + newName
                + "&7... this migrates every member, so it may take a moment on large servers.");
        // renameGroup() completes this future on the main/region thread already
        // (via FoliaScheduler.runSync internally), so no extra thread-hop is needed here.
        plugin.getGroupManager().renameGroup(groupName, newName).whenComplete((renamed, err) -> {
            if (err != null) {
                MessageUtil.send(sender, "&cRename failed: " + err.getMessage());
            } else {
                MessageUtil.send(sender, "&aRenamed group &b" + groupName + " &ato &e" + newName
                        + " &7— all members, inheritance references, and tracks updated.");
            }
        });
    }

    private void handleClone(CommandSender sender, String[] args, String groupName) {
        if (args.length < 4) { MessageUtil.send(sender, "&cUsage: /pc group <name> clone <newgroup>"); return; }
        Group original = plugin.getGroupManager().getGroup(groupName);
        if (original == null) { MessageUtil.send(sender, "&cGroup not found."); return; }
        String newName = args[3].toLowerCase();
        if (plugin.getGroupManager().groupExists(newName)) { MessageUtil.send(sender, "&cGroup &e" + newName + " &calready exists."); return; }
        Group clone = plugin.getGroupManager().createGroup(newName);
        clone.setPrefix(original.getPrefix());
        clone.setSuffix(original.getSuffix());
        clone.setWeight(original.getWeight());
        original.getPermissions().forEach(p -> plugin.getGroupManager().addPermission(newName, p));
        original.getInheritedGroups().forEach(p -> plugin.getGroupManager().addInheritance(newName, p));
        // FIX: saveGroup() is a DB write — must not block the main/region thread.
        FoliaScheduler.runAsync(plugin, () -> plugin.getStorage().saveGroup(clone));
        MessageUtil.send(sender, "&aCloned &b" + groupName + " &ato &e" + newName);
    }

    private void handleClear(CommandSender sender, String groupName) {
        if (!plugin.getGroupManager().groupExists(groupName)) { MessageUtil.send(sender, "&cGroup not found."); return; }
        plugin.getGroupManager().clearGroup(groupName);
        MessageUtil.send(sender, "&aCleared all permissions and parents for group &e" + groupName);
    }

    private void handleShowTracks(CommandSender sender, String groupName) {
        if (!plugin.getGroupManager().groupExists(groupName)) { MessageUtil.send(sender, "&cGroup not found."); return; }
        MessageUtil.send(sender, "&7Tracks containing group &b" + groupName + "&7:");
        boolean found = false;
        for (var track : plugin.getTrackManager().getAllTracks()) {
            if (track.containsGroup(groupName)) {
                int pos = track.getGroups().indexOf(groupName);
                MessageUtil.sendRaw(sender, "  &8- &b" + track.getName()
                        + " &8(pos &7" + (pos + 1) + "/" + track.getGroups().size() + "&8)");
                found = true;
            }
        }
        if (!found) MessageUtil.sendRaw(sender, "  &8(not in any track)");
    }

    private void handleMeta(CommandSender sender, String[] args, String groupName) {
        if (args.length < 4) { sendMetaHelp(sender); return; }
        String metaAction = args[3].toLowerCase();
        switch (metaAction) {
            case "set" -> {
                if (args.length < 6) { MessageUtil.send(sender, "&cUsage: /pc group <name> meta set <key> <value>"); return; }
                if (!plugin.getGroupManager().groupExists(groupName)) { MessageUtil.send(sender, "&cGroup not found."); return; }
                String val = String.join(" ", Arrays.copyOfRange(args, 5, args.length));
                plugin.getGroupManager().setMeta(groupName, args[4], val);
                MessageUtil.send(sender, "&aMeta &e" + args[4] + " &aset to &f" + val + " &afor group &b" + groupName);
            }
            case "unset" -> {
                if (args.length < 5) { MessageUtil.send(sender, "&cUsage: /pc group <name> meta unset <key>"); return; }
                plugin.getGroupManager().unsetMeta(groupName, args[4]);
                MessageUtil.send(sender, "&cMeta &e" + args[4] + " &cunset from group &b" + groupName);
            }
            case "list" -> {
                Group g = plugin.getGroupManager().getGroup(groupName);
                if (g == null) { MessageUtil.send(sender, "&cGroup not found."); return; }
                MessageUtil.send(sender, "&7Meta for group &b" + groupName + "&7:");
                if (g.getMeta().isEmpty()) {
                    MessageUtil.sendRaw(sender, "  &8(none)");
                } else {
                    g.getMeta().forEach((k, v) -> MessageUtil.sendRaw(sender, "  &8- &e" + k + " &8= &f" + v));
                }
            }
            default -> sendMetaHelp(sender);
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    void printGroupInfo(CommandSender sender, Group g) {
        MessageUtil.send(sender, "&7--- &bGroup: &e" + g.getName() + " &7---");
        MessageUtil.sendRaw(sender, "  &7Display: &f" + g.getDisplayName());
        MessageUtil.sendRaw(sender, "  &7Weight: &f" + g.getWeight());
        MessageUtil.sendRaw(sender, "  &7Prefix: &r" + MessageUtil.colorizeString(g.getPrefix()));
        MessageUtil.sendRaw(sender, "  &7Suffix: &r" + MessageUtil.colorizeString(g.getSuffix()));
        MessageUtil.sendRaw(sender, "  &7Permissions: &e" + g.getPermissions().size() + " node(s)");
        MessageUtil.sendRaw(sender, "  &7Parents: &a" +
                (g.getInheritedGroups().isEmpty() ? "none" : String.join(", ", g.getInheritedGroups())));
    }

    // ── Help ───────────────────────────────────────────────────────────────────

    void sendHelp(CommandSender sender) {
        MessageUtil.send(sender, "&7--- &bGroup Commands ---");
        MessageUtil.sendRaw(sender, "  &b/pc group &7- List all groups");
        MessageUtil.sendRaw(sender, "  &b/pc listgroups &7- List groups with weight info");
        MessageUtil.sendRaw(sender, "  &b/pc group &e<name> &7- Group info");
        MessageUtil.sendRaw(sender, "  &b/pc group &e<name> create/delete");
        MessageUtil.sendRaw(sender, "  &b/pc group &e<name> permission &8<set|unset|settemp|list|listall> [perm] [duration]");
        MessageUtil.sendRaw(sender, "  &b/pc group &e<name> parent &8<add|remove> <parentGroup>");
        MessageUtil.sendRaw(sender, "  &b/pc group &e<name> clear &8- &7Wipe all permissions & parents");
        MessageUtil.sendRaw(sender, "  &b/pc group &e<name> showtracks &8- &7Tracks containing group");
        MessageUtil.sendRaw(sender, "  &b/pc group &e<name> meta &8<set|unset|list> [key] [value]");
        MessageUtil.sendRaw(sender, "  &b/pc group &e<name> setprefix/setsuffix/clearprefix/clearsuffix &8[value]");
        MessageUtil.sendRaw(sender, "  &b/pc group &e<name> setweight &8<number>");
        MessageUtil.sendRaw(sender, "  &b/pc group &e<name> rename &8<newname>");
        MessageUtil.sendRaw(sender, "  &b/pc group &e<name> clone &8<newgroup>");
    }

    private void sendPermHelp(CommandSender sender) {
        MessageUtil.send(sender, "&7Group Permission Actions:");
        MessageUtil.sendRaw(sender, "  &bset &e<permission> &7- Add permission");
        MessageUtil.sendRaw(sender, "  &bunset &e<permission> &7- Remove permission");
        MessageUtil.sendRaw(sender, "  &bsettemp &e<permission> <duration> &7- Add temporarily");
        MessageUtil.sendRaw(sender, "  &blist &7- List direct permissions");
        MessageUtil.sendRaw(sender, "  &blistall &7- List all permissions (including inherited)");
    }

    private void sendMetaHelp(CommandSender sender) {
        MessageUtil.send(sender, "&7Group Meta Actions:");
        MessageUtil.sendRaw(sender, "  &bset &e<key> <value> &7- Set a meta value");
        MessageUtil.sendRaw(sender, "  &bunset &e<key> &7- Remove a meta value");
        MessageUtil.sendRaw(sender, "  &blist &7- List all meta");
    }
}
