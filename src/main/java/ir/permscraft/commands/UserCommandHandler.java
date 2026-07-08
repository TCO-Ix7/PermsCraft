package ir.permscraft.commands;

import ir.permscraft.FoliaScheduler;
import ir.permscraft.PermsCraft;
import ir.permscraft.models.User;
import ir.permscraft.utils.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.UUID;

/**
 * Handles all /pc user <name> subcommands.
 * Extracted from PCCommand to keep each handler focused and maintainable.
 */
public class UserCommandHandler {

    private final PermsCraft plugin;
    private final TimedCommandHandler timedHandler;

    public UserCommandHandler(PermsCraft plugin, TimedCommandHandler timedHandler) {
        this.plugin = plugin;
        this.timedHandler = timedHandler;
    }

    /** Entry point: /pc user <name> <action> [...] */
    public void handle(CommandSender sender, String[] args) {
        if (args.length < 3) { sendHelp(sender); return; }

        String targetName = args[1];
        String action = args[2].toLowerCase();

        resolveUUIDAsync(targetName).thenAccept(uuid -> {
            FoliaScheduler.runSync(plugin, () -> {
                if (uuid == null) {
                    MessageUtil.send(sender, "&cPlayer &e" + targetName + " &cnot found.");
                    return;
                }
                handleWithUUID(sender, args, targetName, action, uuid);
            });
        });
    }

    private void handleWithUUID(CommandSender sender, String[] args,
                                String targetName, String action, UUID finalUUID) {
        switch (action) {

            case "info" -> withUser(finalUUID, targetName, sender, user -> {
                MessageUtil.send(sender, "&7--- &bUser: &e" + user.getUsername() + " &7---");
                MessageUtil.sendRaw(sender, "  &7UUID: &f" + user.getUuid());
                MessageUtil.sendRaw(sender, "  &7Groups: &a" + String.join("&7, &a", user.getGroups()));
                MessageUtil.sendRaw(sender, "  &7Permissions: &e" + user.getPermissions().size() + " node(s)");
                MessageUtil.sendRaw(sender, "  &7Prefix: &r" + MessageUtil.colorizeString(user.getPrefix()));
                MessageUtil.sendRaw(sender, "  &7Suffix: &r" + MessageUtil.colorizeString(user.getSuffix()));
                var timed = plugin.getTimedPermissionManager().getTimedPermissions(finalUUID.toString());
                long active = timed.stream().filter(t -> !t.isExpired()).count();
                if (active > 0) MessageUtil.sendRaw(sender, "  &7Timed Permissions: &6" + active + " active");
            });

            case "permission", "perm" -> handlePermission(sender, args, targetName, finalUUID);

            case "group" -> handleGroup(sender, args, targetName, finalUUID);

            case "parent" -> {
                // Alias: rewrite args[2] to "group" and re-dispatch
                String[] rewritten = Arrays.copyOf(args, args.length);
                rewritten[2] = "group";
                handle(sender, rewritten);
            }

            case "setprefix" -> {
                if (args.length < 4) { MessageUtil.send(sender, "&cUsage: /pc user <name> setprefix <prefix>"); return; }
                String prefix = String.join(" ", Arrays.copyOfRange(args, 3, args.length));
                plugin.getUserManager().setPrefix(finalUUID, prefix);
                MessageUtil.send(sender, "&aSet prefix for &b" + targetName + "&a: &r" + MessageUtil.colorizeString(prefix));
            }

            case "setsuffix" -> {
                if (args.length < 4) { MessageUtil.send(sender, "&cUsage: /pc user <name> setsuffix <suffix>"); return; }
                String suffix = String.join(" ", Arrays.copyOfRange(args, 3, args.length));
                plugin.getUserManager().setSuffix(finalUUID, suffix);
                MessageUtil.send(sender, "&aSet suffix for &b" + targetName + "&a: &r" + MessageUtil.colorizeString(suffix));
            }

            case "clearprefix" -> {
                plugin.getUserManager().setPrefix(finalUUID, "");
                MessageUtil.send(sender, "&aCleared prefix for &b" + targetName);
            }

            case "clearsuffix" -> {
                plugin.getUserManager().setSuffix(finalUUID, "");
                MessageUtil.send(sender, "&aCleared suffix for &b" + targetName);
            }

            case "promote" -> {
                if (args.length < 4) { MessageUtil.send(sender, "&cUsage: /pc user <name> promote <track>"); return; }
                String newGroup = plugin.getTrackManager().promote(finalUUID, args[3]);
                if (newGroup == null) {
                    MessageUtil.send(sender, "&e" + targetName + " &7is already at the top of track &b" + args[3] + " &7or not in it.");
                } else {
                    MessageUtil.send(sender, "&aPromoted &b" + targetName + " &ato &e" + newGroup + " &aon track &b" + args[3]);
                    Player target = Bukkit.getPlayer(finalUUID);
                    if (target != null) MessageUtil.send(target, "&aYou have been promoted to &b" + newGroup + "&a!");
                }
            }

            case "demote" -> {
                if (args.length < 4) { MessageUtil.send(sender, "&cUsage: /pc user <name> demote <track>"); return; }
                String newGroup = plugin.getTrackManager().demote(finalUUID, args[3]);
                if (newGroup == null) {
                    MessageUtil.send(sender, "&e" + targetName + " &7is at the bottom of track &b" + args[3] + " &7or not in it.");
                } else {
                    MessageUtil.send(sender, "&cDemoted &b" + targetName + " &cto &e" + newGroup + " &con track &b" + args[3]);
                }
            }

            case "clear" -> withUser(finalUUID, targetName, sender, user -> {
                plugin.getUserManager().clearUser(finalUUID, targetName);
                MessageUtil.send(sender, "&aCleared all permissions, groups, and meta for &b" + targetName);
            });

            case "showtracks" -> withUser(finalUUID, targetName, sender, user -> {
                MessageUtil.send(sender, "&7Tracks for &b" + targetName + "&7:");
                boolean found = false;
                for (var track : plugin.getTrackManager().getAllTracks()) {
                    for (String g : user.getGroups()) {
                        if (track.containsGroup(g)) {
                            MessageUtil.sendRaw(sender, "  &8- &b" + track.getName()
                                    + " &8(&7current: &e" + g + "&8, pos &7"
                                    + (track.getGroups().indexOf(g) + 1) + "/" + track.getGroups().size() + "&8)");
                            found = true;
                            break;
                        }
                    }
                }
                if (!found) MessageUtil.sendRaw(sender, "  &8(not in any track)");
            });

            case "switchprimarygroup" -> {
                if (args.length < 4) { MessageUtil.send(sender, "&cUsage: /pc user <name> switchprimarygroup <group>"); return; }
                String targetGroup = args[3].toLowerCase();
                withUser(finalUUID, targetName, sender, user -> {
                    if (!user.getGroups().contains(targetGroup)) {
                        MessageUtil.send(sender, "&cUser &b" + targetName + " &cis not in group &e" + targetGroup + "&c. Add them first.");
                        return;
                    }
                    plugin.getUserManager().switchPrimaryGroup(finalUUID, targetGroup);
                    MessageUtil.send(sender, "&aSwitched primary group of &b" + targetName + " &ato &e" + targetGroup);
                });
            }

            case "meta" -> handleMeta(sender, args, targetName, finalUUID);

            case "clone" -> {
                if (args.length < 4) { MessageUtil.send(sender, "&cUsage: /pc user <name> clone <targetPlayer>"); return; }
                String targetName2 = args[3];
                UUID targetUUID = resolveUUID(targetName2);
                if (targetUUID == null) { MessageUtil.send(sender, "&cTarget player &e" + targetName2 + " &cnot found."); return; }
                UUID finalTarget = targetUUID;
                withUser(finalUUID, targetName, sender, sourceUser ->
                    withUser(finalTarget, targetName2, sender, targetUser -> {
                        sourceUser.getGroups().forEach(g -> plugin.getUserManager().addToGroup(finalTarget, g));
                        sourceUser.getPermissions().forEach(p -> plugin.getUserManager().addPermission(finalTarget, p));
                        if (!sourceUser.getPrefix().isEmpty()) plugin.getUserManager().setPrefix(finalTarget, sourceUser.getPrefix());
                        if (!sourceUser.getSuffix().isEmpty()) plugin.getUserManager().setSuffix(finalTarget, sourceUser.getSuffix());
                        sourceUser.getMeta().forEach((k, v) -> plugin.getUserManager().setMeta(finalTarget, k, v));
                        MessageUtil.send(sender, "&aCloned permissions from &b" + targetName + " &ato &b" + targetName2);
                    }));
            }

            default -> sendHelp(sender);
        }
    }

    private void handlePermission(CommandSender sender, String[] args, String targetName, UUID finalUUID) {
        if (args.length < 4) { sendPermHelp(sender); return; }
        String permAction = args[3].toLowerCase();
        switch (permAction) {
            case "set", "add" -> {
                if (args.length < 5) { MessageUtil.send(sender, "&cUsage: /pc user <name> permission set <permission>"); return; }
                plugin.getUserManager().addPermission(finalUUID, args[4]);
                MessageUtil.send(sender, "&aSet &e" + args[4] + " &afor &b" + targetName);
            }
            case "unset", "remove" -> {
                if (args.length < 5) { MessageUtil.send(sender, "&cUsage: /pc user <name> permission unset <permission>"); return; }
                plugin.getUserManager().removePermission(finalUUID, args[4]);
                MessageUtil.send(sender, "&cUnset &e" + args[4] + " &cfrom &b" + targetName);
            }
            case "settemp" -> {
                if (args.length < 6) { MessageUtil.send(sender, "&cUsage: /pc user <name> permission settemp <permission> <duration>"); return; }
                timedHandler.handleUserTimed(sender, targetName, args[4], args[5]);
            }
            case "unsettemp" -> {
                if (args.length < 5) { MessageUtil.send(sender, "&cUsage: /pc user <name> permission unsettemp <permission>"); return; }
                plugin.getUserManager().removePermission(finalUUID, args[4]);
                MessageUtil.send(sender, "&cRemoved timed permission &e" + args[4] + " &cfrom &b" + targetName);
            }
            case "list" -> withUser(finalUUID, targetName, sender, user -> {
                MessageUtil.send(sender, "&7Permissions for &b" + targetName + "&7:");
                if (user.getPermissions().isEmpty()) {
                    MessageUtil.sendRaw(sender, "  &8(none)");
                } else {
                    user.getPermissions().forEach(p -> MessageUtil.sendRaw(sender, "  &8- &f" + p));
                }
                var timed = plugin.getTimedPermissionManager().getTimedPermissions(finalUUID.toString());
                timed.stream().filter(t -> !t.isExpired()).forEach(t ->
                    MessageUtil.sendRaw(sender, "  &8- &f" + t.getPermission() + " &7(&6" + t.getFormattedExpiry() + "&7)"));
            });
            default -> sendPermHelp(sender);
        }
    }

    private void handleGroup(CommandSender sender, String[] args, String targetName, UUID finalUUID) {
        if (args.length < 4) {
            withUser(finalUUID, targetName, sender, user ->
                MessageUtil.send(sender, "&7Groups for &b" + targetName + "&7: &a" + String.join(", ", user.getGroups())));
            return;
        }
        String groupAction = args[3].toLowerCase();
        switch (groupAction) {
            case "add" -> {
                if (args.length < 5) { MessageUtil.send(sender, "&cUsage: /pc user <name> group add <group>"); return; }
                if (!plugin.getGroupManager().groupExists(args[4])) { MessageUtil.send(sender, "&cGroup &e" + args[4] + " &cdoesn't exist."); return; }
                plugin.getUserManager().addToGroup(finalUUID, args[4].toLowerCase());
                MessageUtil.send(sender, "&aAdded &b" + targetName + " &ato group &e" + args[4]);
            }
            case "remove" -> {
                if (args.length < 5) { MessageUtil.send(sender, "&cUsage: /pc user <name> group remove <group>"); return; }
                plugin.getUserManager().removeFromGroup(finalUUID, args[4].toLowerCase());
                MessageUtil.send(sender, "&cRemoved &b" + targetName + " &cfrom group &e" + args[4]);
            }
            case "list" -> withUser(finalUUID, targetName, sender, user ->
                MessageUtil.send(sender, "&7Groups for &b" + targetName + "&7: &a" + String.join(", ", user.getGroups())));
            default -> MessageUtil.send(sender, "&cUsage: /pc user <name> group <add|remove|list> [group]");
        }
    }

    private void handleMeta(CommandSender sender, String[] args, String targetName, UUID finalUUID) {
        if (args.length < 4) { sendMetaHelp(sender); return; }
        String metaAction = args[3].toLowerCase();
        switch (metaAction) {
            case "set" -> {
                if (args.length < 6) { MessageUtil.send(sender, "&cUsage: /pc user <name> meta set <key> <value>"); return; }
                String val = String.join(" ", Arrays.copyOfRange(args, 5, args.length));
                plugin.getUserManager().setMeta(finalUUID, args[4], val);
                MessageUtil.send(sender, "&aMeta &e" + args[4] + " &aset to &f" + val + " &afor &b" + targetName);
            }
            case "unset" -> {
                if (args.length < 5) { MessageUtil.send(sender, "&cUsage: /pc user <name> meta unset <key>"); return; }
                plugin.getUserManager().unsetMeta(finalUUID, args[4]);
                MessageUtil.send(sender, "&cMeta &e" + args[4] + " &cunset from &b" + targetName);
            }
            case "settemp" -> {
                if (args.length < 7) { MessageUtil.send(sender, "&cUsage: /pc user <name> meta settemp <key> <value> <duration>"); return; }
                String val = args[5];
                long expiry = ir.permscraft.managers.TimedPermissionManager.parseDuration(args[6]);
                if (expiry <= 0) { MessageUtil.send(sender, "&cInvalid duration. Use e.g. &e1d2h30m"); return; }
                plugin.getUserManager().setTimedMeta(finalUUID, args[4], val, expiry);
                MessageUtil.send(sender, "&aMeta &e" + args[4] + " &aset temporarily for &b" + targetName + " &a(&6" + args[6] + "&a)");
            }
            case "list" -> withUser(finalUUID, targetName, sender, user -> {
                MessageUtil.send(sender, "&7Meta for &b" + targetName + "&7:");
                if (user.getMeta().isEmpty()) {
                    MessageUtil.sendRaw(sender, "  &8(none)");
                } else {
                    user.getMeta().forEach((k, v) -> MessageUtil.sendRaw(sender, "  &8- &e" + k + " &8= &f" + v));
                }
            });
            default -> sendMetaHelp(sender);
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    UUID resolveUUID(String name) {
        org.bukkit.entity.Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online.getUniqueId();
        return plugin.getStorage().findUUIDByUsername(name);
    }

    java.util.concurrent.CompletableFuture<UUID> resolveUUIDAsync(String name) {
        org.bukkit.entity.Player online = Bukkit.getPlayerExact(name);
        if (online != null) return java.util.concurrent.CompletableFuture.completedFuture(online.getUniqueId());
        java.util.concurrent.CompletableFuture<UUID> future = new java.util.concurrent.CompletableFuture<>();
        FoliaScheduler.runAsync(plugin, () ->
            future.complete(plugin.getStorage().findUUIDByUsername(name)));
        return future;
    }

    void withUser(UUID uuid, String username, CommandSender sender, java.util.function.Consumer<User> callback) {
        plugin.getUserManager().getOrLoadUserAsync(uuid, username)
            .thenAccept(user ->
                FoliaScheduler.runSync(plugin, () -> callback.accept(user)))
            .exceptionally(ex -> {
                FoliaScheduler.runSync(plugin, () ->
                    MessageUtil.send(sender, "&cFailed to load user data."));
                return null;
            });
    }

    // ── Help ───────────────────────────────────────────────────────────────────

    void sendHelp(CommandSender sender) {
        MessageUtil.send(sender, "&7--- &bUser Commands ---");
        MessageUtil.sendRaw(sender, "  &b/pc user &e<name> info");
        MessageUtil.sendRaw(sender, "  &b/pc user &e<name> permission &8<set|unset|settemp|list> [perm] [duration]");
        MessageUtil.sendRaw(sender, "  &b/pc user &e<name> group &8<add|remove|list> [group]");
        MessageUtil.sendRaw(sender, "  &b/pc user &e<name> promote/demote &8<track>");
        MessageUtil.sendRaw(sender, "  &b/pc user &e<name> clear &8- &7Wipe all data");
        MessageUtil.sendRaw(sender, "  &b/pc user &e<name> showtracks &8- &7Tracks user is in");
        MessageUtil.sendRaw(sender, "  &b/pc user &e<name> switchprimarygroup &8<group>");
        MessageUtil.sendRaw(sender, "  &b/pc user &e<name> meta &8<set|unset|settemp|list> [key] [value] [duration]");
        MessageUtil.sendRaw(sender, "  &b/pc user &e<name> setprefix/setsuffix/clearprefix/clearsuffix &8[value]");
    }

    private void sendPermHelp(CommandSender sender) {
        MessageUtil.send(sender, "&7User Permission Actions:");
        MessageUtil.sendRaw(sender, "  &bset &e<permission> &7- Grant a permission");
        MessageUtil.sendRaw(sender, "  &bunset &e<permission> &7- Remove a permission");
        MessageUtil.sendRaw(sender, "  &bsettemp &e<permission> <duration> &7- Grant temporarily (e.g. 1d2h30m)");
        MessageUtil.sendRaw(sender, "  &bunsettemp &e<permission> &7- Remove a timed permission");
        MessageUtil.sendRaw(sender, "  &blist &7- List all permissions");
    }

    private void sendMetaHelp(CommandSender sender) {
        MessageUtil.send(sender, "&7User Meta Actions:");
        MessageUtil.sendRaw(sender, "  &bset &e<key> <value> &7- Set a meta value");
        MessageUtil.sendRaw(sender, "  &bunset &e<key> &7- Remove a meta value");
        MessageUtil.sendRaw(sender, "  &bsettemp &e<key> <value> <duration> &7- Temporary meta (e.g. 1d)");
        MessageUtil.sendRaw(sender, "  &blist &7- List all meta");
    }
}
