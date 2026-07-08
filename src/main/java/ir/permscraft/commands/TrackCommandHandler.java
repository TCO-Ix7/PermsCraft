package ir.permscraft.commands;

import ir.permscraft.PermsCraft;
import ir.permscraft.models.Track;
import ir.permscraft.utils.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.UUID;

public class TrackCommandHandler {

    private final PermsCraft plugin;

    public TrackCommandHandler(PermsCraft plugin) {
        this.plugin = plugin;
    }

    public void handle(CommandSender sender, String[] args) {
        // /pc track [name] [action] [...]
        if (args.length < 2) {
            // List tracks
            if (plugin.getTrackManager().getAllTracks().isEmpty()) {
                MessageUtil.send(sender, "&7No tracks found. Create one with &b/pc track <name> create");
            } else {
                MessageUtil.send(sender, "&7Tracks: &a" + String.join(", ",
                        plugin.getTrackManager().getAllTracks().stream().map(Track::getName).toList()));
            }
            return;
        }

        String trackName = args[1].toLowerCase();

        if (args.length < 3) {
            // Track info
            Track track = plugin.getTrackManager().getTrack(trackName);
            if (track == null) { MessageUtil.send(sender, "&cTrack not found."); return; }
            MessageUtil.send(sender, "&7--- &bTrack: &e" + track.getName() + " &7---");
            MessageUtil.sendRaw(sender, "  &7Groups: &a" + String.join(" &7-> &a", track.getGroups()));
            return;
        }

        String action = args[2].toLowerCase();

        switch (action) {
            case "create" -> {
                if (plugin.getTrackManager().trackExists(trackName)) {
                    MessageUtil.send(sender, "&cTrack &e" + trackName + " &calready exists.");
                } else {
                    plugin.getTrackManager().createTrack(trackName);
                    MessageUtil.send(sender, "&aCreated track &e" + trackName);
                }
            }
            case "delete" -> {
                if (!plugin.getTrackManager().trackExists(trackName)) {
                    MessageUtil.send(sender, "&cTrack not found.");
                } else {
                    plugin.getTrackManager().deleteTrack(trackName);
                    MessageUtil.send(sender, "&cDeleted track &e" + trackName);
                }
            }
            case "append" -> {
                if (args.length < 4) { MessageUtil.send(sender, "&cUsage: /pc track <name> append <group>"); return; }
                String groupName = args[3].toLowerCase();
                if (!plugin.getGroupManager().groupExists(groupName)) {
                    MessageUtil.send(sender, "&cGroup &e" + groupName + " &cdoes not exist.");
                    return;
                }
                plugin.getTrackManager().addGroupToTrack(trackName, groupName);
                MessageUtil.send(sender, "&aAdded &e" + groupName + " &ato track &b" + trackName);
            }
            case "remove" -> {
                if (args.length < 4) { MessageUtil.send(sender, "&cUsage: /pc track <name> remove <group>"); return; }
                plugin.getTrackManager().removeGroupFromTrack(trackName, args[3].toLowerCase());
                MessageUtil.send(sender, "&cRemoved &e" + args[3] + " &cfrom track &b" + trackName);
            }
            case "promote" -> {
                if (args.length < 4) { MessageUtil.send(sender, "&cUsage: /pc track <name> promote <player>"); return; }
                Player target = Bukkit.getPlayerExact(args[3]);
                if (target == null) { MessageUtil.send(sender, "&cPlayer not online."); return; }
                String newGroup = plugin.getTrackManager().promote(target.getUniqueId(), trackName);
                if (newGroup == null) {
                    MessageUtil.send(sender, "&e" + target.getName() + " &7is already at the top of track &b" + trackName);
                } else {
                    MessageUtil.send(sender, "&aPromoted &e" + target.getName() + " &ato &b" + newGroup + " &aon track &e" + trackName);
                    MessageUtil.send(target, "&aYou have been promoted to &b" + newGroup + " &aon the &e" + trackName + " &atrack!");
                }
            }
            case "demote" -> {
                if (args.length < 4) { MessageUtil.send(sender, "&cUsage: /pc track <name> demote <player>"); return; }
                Player target = Bukkit.getPlayerExact(args[3]);
                if (target == null) { MessageUtil.send(sender, "&cPlayer not online."); return; }
                String newGroup = plugin.getTrackManager().demote(target.getUniqueId(), trackName);
                if (newGroup == null) {
                    MessageUtil.send(sender, "&e" + target.getName() + " &7is already at the bottom of track &b" + trackName);
                } else {
                    MessageUtil.send(sender, "&cDemoted &e" + target.getName() + " &cto &b" + newGroup + " &con track &e" + trackName);
                }
            }
            default -> {
                MessageUtil.send(sender, "&7Track commands:");
                MessageUtil.sendRaw(sender, "  &b/pc track &8- List tracks");
                MessageUtil.sendRaw(sender, "  &b/pc track &e<name> create/delete");
                MessageUtil.sendRaw(sender, "  &b/pc track &e<name> append/remove &f<group>");
                MessageUtil.sendRaw(sender, "  &b/pc track &e<name> promote/demote &f<player>");
            }
        }
    }
}
