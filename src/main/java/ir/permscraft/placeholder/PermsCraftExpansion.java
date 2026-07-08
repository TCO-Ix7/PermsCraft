package ir.permscraft.placeholder;

import ir.permscraft.PermsCraft;
import ir.permscraft.models.Group;
import ir.permscraft.models.TimedPermission;
import ir.permscraft.models.User;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * PlaceholderAPI Expansion for PermsCraft.
 *
 * Available placeholders:
 *  %permscraft_prefix%            — player's effective prefix (group + personal)
 *  %permscraft_suffix%            — player's effective suffix
 *  %permscraft_group%             — primary group name
 *  %permscraft_groups%            — comma-separated list of all groups
 *  %permscraft_group_prefix%      — primary group's prefix
 *  %permscraft_group_suffix%      — primary group's suffix
 *  %permscraft_group_weight%      — primary group weight
 *  %permscraft_group_display%     — primary group display name
 *  %permscraft_highest_group%     — highest-weight group name
 *  %permscraft_highest_prefix%    — highest-weight group prefix
 *  %permscraft_highest_suffix%    — highest-weight group suffix
 *  %permscraft_perm_<node>%       — true/false if player has permission node
 *  %permscraft_timed_count%       — active timed permissions count
 *  %permscraft_timed_next%        — next expiry time (HH:mm:ss) or "none"
 *  %permscraft_meta_<key>%        — user meta value
 *  %permscraft_group_meta_<key>%  — primary group meta (from Group model)
 *  %permscraft_track_<name>%      — player's position on track (group name) or "none"
 *  %permscraft_online%            — online players count
 *  %permscraft_groups_count%      — total group count on server
 */
public class PermsCraftExpansion extends PlaceholderExpansion {

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final PermsCraft plugin;

    public PermsCraftExpansion(PermsCraft plugin) {
        this.plugin = plugin;
    }

    @Override public String getIdentifier() { return "permscraft"; }
    @Override public String getAuthor()     { return "PermsCraft"; }
    @Override public String getVersion()    { return plugin.getDescription().getVersion(); }
    @Override public boolean persist()               { return true; }
    @Override public boolean canRegister()           { return true; }

    @Override
    public String onRequest(OfflinePlayer offlinePlayer, String params) {
        if (offlinePlayer == null) return "";
        UUID uuid = offlinePlayer.getUniqueId();

        User user = plugin.getUserManager().getUser(uuid);
        if (user == null) return ""; // not in cache — skip sync DB load

        // ── prefix / suffix ──────────────────────────────────────────────────
        if (params.equals("prefix")) return resolveEffectivePrefix(user);
        if (params.equals("suffix")) return resolveEffectiveSuffix(user);

        // ── primary group ────────────────────────────────────────────────────
        if (params.equals("group")) return user.getPrimaryGroup();
        if (params.equals("groups")) return String.join(", ", user.getGroups());
        if (params.equals("group_prefix")) {
            Group g = plugin.getGroupManager().getGroup(user.getPrimaryGroup());
            return g != null ? colorize(g.getPrefix()) : "";
        }
        if (params.equals("group_suffix")) {
            Group g = plugin.getGroupManager().getGroup(user.getPrimaryGroup());
            return g != null ? colorize(g.getSuffix()) : "";
        }
        if (params.equals("group_weight")) {
            Group g = plugin.getGroupManager().getGroup(user.getPrimaryGroup());
            return g != null ? String.valueOf(g.getWeight()) : "0";
        }
        if (params.equals("group_display")) {
            Group g = plugin.getGroupManager().getGroup(user.getPrimaryGroup());
            if (g == null) return user.getPrimaryGroup();
            return g.getDisplayName().isEmpty() ? g.getName() : g.getDisplayName();
        }

        // ── highest group ────────────────────────────────────────────────────
        if (params.equals("highest_group")) return getHighestGroup(user).getName();
        if (params.equals("highest_prefix")) return colorize(getHighestGroup(user).getPrefix());
        if (params.equals("highest_suffix")) return colorize(getHighestGroup(user).getSuffix());

        // ── permission check: %permscraft_perm_essentials.home% ────────────
        if (params.startsWith("perm_")) {
            String node = params.substring(5);
            Player online = offlinePlayer.getPlayer();
            if (online != null) return String.valueOf(online.hasPermission(node));
            // Offline: use inheritance graph
            return String.valueOf(plugin.getInheritanceGraph().hasPermission(user, node));
        }

        // ── timed permissions ─────────────────────────────────────────────
        if (params.equals("timed_count")) {
            long count = plugin.getTimedPermissionManager()
                    .getTimedPermissions(uuid.toString())
                    .stream().filter(t -> !t.isExpired()).count();
            return String.valueOf(count);
        }
        if (params.equals("timed_next")) {
            return plugin.getTimedPermissionManager()
                    .getTimedPermissions(uuid.toString()).stream()
                    .filter(t -> !t.isExpired())
                    .map(TimedPermission::getExpiry)
                    .min(Instant::compareTo)
                    .map(TIME_FMT::format)
                    .orElse("none");
        }

        // ── meta: %permscraft_meta_rank% ────────────────────────────────────
        if (params.startsWith("meta_")) {
            String key = params.substring(5);
            String val = user.getMeta(key);
            return val != null ? val : "";
        }

        // ── group meta from Group model (e.g. custom color stored as meta) ──
        if (params.startsWith("group_meta_")) {
            String key = params.substring(11);
            Group g = plugin.getGroupManager().getGroup(user.getPrimaryGroup());
            if (g == null) return "";
            String val = g.getMetaValue(key);
            return val != null ? val : "";
        }

        // ── track position: %permscraft_track_rankup% ───────────────────────
        if (params.startsWith("track_")) {
            String trackName = params.substring(6);
            var track = plugin.getTrackManager().getTrack(trackName);
            if (track == null) return "none";
            List<String> grps = track.getGroups();
            for (String grp : grps) {
                if (user.inGroup(grp)) return grp;
            }
            return "none";
        }

        // ── server stats ─────────────────────────────────────────────────────
        if (params.equals("online"))       return String.valueOf(org.bukkit.Bukkit.getOnlinePlayers().size());
        if (params.equals("groups_count")) return String.valueOf(plugin.getGroupManager().getAllGroups().size());

        return null;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String resolveEffectivePrefix(User user) {
        // Personal prefix first, then highest-weight group prefix
        if (user.getPrefix() != null && !user.getPrefix().isEmpty())
            return colorize(user.getPrefix());
        Group highest = getHighestGroup(user);
        return colorize(highest.getPrefix());
    }

    private String resolveEffectiveSuffix(User user) {
        if (user.getSuffix() != null && !user.getSuffix().isEmpty())
            return colorize(user.getSuffix());
        Group highest = getHighestGroup(user);
        return colorize(highest.getSuffix());
    }

    private Group getHighestGroup(User user) {
        return user.getGroups().stream()
                .map(plugin.getGroupManager()::getGroup)
                .filter(g -> g != null)
                .max(java.util.Comparator.comparingInt(Group::getWeight))
                .orElseGet(() -> plugin.getGroupManager().getGroup("default") != null
                        ? plugin.getGroupManager().getGroup("default")
                        : new Group("default"));
    }

    private String colorize(String s) {
        if (s == null) return "";
        return org.bukkit.ChatColor.translateAlternateColorCodes('&', s);
    }
}
