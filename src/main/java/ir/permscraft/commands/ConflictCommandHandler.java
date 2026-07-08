package ir.permscraft.commands;

import ir.permscraft.PermsCraft;
import ir.permscraft.context.ContextSet;
import ir.permscraft.context.ContextualPermission;
import ir.permscraft.models.Group;
import ir.permscraft.models.User;
import ir.permscraft.utils.MessageUtil;
import org.bukkit.command.CommandSender;

import java.util.*;

/**
 * /pc conflicts [user|group] [name]
 *
 * Read-only diagnostic tool that scans for permission nodes which are both
 * GRANTED and DENIED for the same target under overlapping contexts.
 *
 * This commonly happens by accident, e.g.:
 *   - "essentials.fly" granted globally on a group
 *   - "-essentials.fly" denied in world=creative on the same group
 *
 * Depending on resolution order this can silently hide the intended grant.
 * This command never modifies any data — it only reports findings.
 *
 * Usage:
 *   /pc conflicts                  - scan ALL groups + all currently loaded users
 *   /pc conflicts group <name>     - scan a single group
 *   /pc conflicts user  <name>     - scan a single user (must be loaded/online)
 */
public class ConflictCommandHandler {

    private final PermsCraft plugin;
    private final UserCommandHandler userHandler;

    public ConflictCommandHandler(PermsCraft plugin, UserCommandHandler userHandler) {
        this.plugin = plugin;
        this.userHandler = userHandler;
    }

    public void handle(CommandSender sender, String[] args) {
        if (args.length >= 3) {
            String type = args[1].toLowerCase();
            String name = args[2];

            if (type.equals("group")) {
                Group g = plugin.getGroupManager().getGroup(name);
                if (g == null) { MessageUtil.send(sender, "&cGroup not found."); return; }
                report(sender, "group &b" + name, scan(name, g.getPermissions()));
            } else if (type.equals("user")) {
                UUID uuid = userHandler.resolveUUID(name);
                if (uuid == null) { MessageUtil.send(sender, "&cPlayer not found."); return; }
                User user = plugin.getUserManager().getUser(uuid);
                if (user == null) user = plugin.getStorage().loadUser(uuid, name);
                report(sender, "user &b" + name, scan(uuid.toString(), user.getPermissions()));
            } else {
                MessageUtil.send(sender, "&cUsage: /pc conflicts [user|group] <name>");
            }
            return;
        }

        // Full scan over all groups + currently loaded users
        MessageUtil.send(sender, "&7Scanning for permission conflicts...");
        int total = 0;

        for (Group g : plugin.getGroupManager().getAllGroups()) {
            List<Conflict> conflicts = scan(g.getName(), g.getPermissions());
            if (!conflicts.isEmpty()) {
                MessageUtil.sendRaw(sender, "&8--- Group &b" + g.getName() + " &8---");
                for (Conflict c : conflicts) MessageUtil.sendRaw(sender, "  " + c.describe());
                total += conflicts.size();
            }
        }

        for (User u : plugin.getUserManager().getAllLoadedUsers()) {
            List<Conflict> conflicts = scan(u.getUuid().toString(), u.getPermissions());
            if (!conflicts.isEmpty()) {
                MessageUtil.sendRaw(sender, "&8--- User &b" + u.getUsername() + " &8---");
                for (Conflict c : conflicts) MessageUtil.sendRaw(sender, "  " + c.describe());
                total += conflicts.size();
            }
        }

        if (total == 0) {
            MessageUtil.send(sender, "&aNo conflicts found.");
        } else {
            MessageUtil.send(sender, "&eFound &c" + total + " &epossible conflict(s) &7(see above).");
        }
        MessageUtil.sendRaw(sender, "&8Note: only currently loaded/online users are scanned in a full scan.");
        MessageUtil.sendRaw(sender, "&8Use &7/pc conflicts user <name> &8to check a specific offline player.");
    }

    /**
     * Combines a target's flat permission set (from getPermissions(), where
     * a leading "-" means deny/global) with its context-bound permissions
     * (from ContextManager), then looks for any node that is GRANTED under
     * one context and DENIED under an overlapping (or global) context.
     */
    private List<Conflict> scan(String targetKey, Set<String> flatPermissions) {
        // permission node (lowercase, leading '-' stripped) -> all entries for it
        Map<String, List<Entry>> byNode = new LinkedHashMap<>();

        for (String node : flatPermissions) {
            boolean negated = node.startsWith("-");
            String clean = (negated ? node.substring(1) : node).toLowerCase();
            byNode.computeIfAbsent(clean, k -> new ArrayList<>())
                    .add(new Entry(!negated, ContextSet.global(), "direct"));
        }

        for (ContextualPermission cp : plugin.getContextManager().getPermissions(targetKey)) {
            String clean = cp.getPermission().toLowerCase();
            byNode.computeIfAbsent(clean, k -> new ArrayList<>())
                    .add(new Entry(cp.getValue(), cp.getRequiredContext(), "context"));
        }

        List<Conflict> conflicts = new ArrayList<>();
        for (var e : byNode.entrySet()) {
            List<Entry> entries = e.getValue();
            for (int i = 0; i < entries.size(); i++) {
                for (int j = i + 1; j < entries.size(); j++) {
                    Entry a = entries.get(i);
                    Entry b = entries.get(j);
                    if (a.granted != b.granted && overlaps(a.context, b.context)) {
                        conflicts.add(new Conflict(e.getKey(), a, b));
                    }
                }
            }
        }
        return conflicts;
    }

    /**
     * Two context requirements "overlap" (and therefore could collide for the
     * same player at the same time) if either is global, or they are exactly
     * equal. This is intentionally conservative — it may flag a few harmless
     * cases, but it will never miss a real conflict.
     */
    private boolean overlaps(ContextSet a, ContextSet b) {
        if (a.isEmpty() || b.isEmpty()) return true;
        return a.equals(b);
    }

    private void report(CommandSender sender, String label, List<Conflict> conflicts) {
        if (conflicts.isEmpty()) {
            MessageUtil.send(sender, "&aNo conflicts found for " + label + "&a.");
            return;
        }
        MessageUtil.send(sender, "&eFound &c" + conflicts.size() + " &epossible conflict(s) for " + label + "&e:");
        for (Conflict c : conflicts) MessageUtil.sendRaw(sender, "  " + c.describe());
    }

    private record Entry(boolean granted, ContextSet context, String source) {}

    private record Conflict(String node, Entry a, Entry b) {
        String describe() {
            Entry grant = a.granted() ? a : b;
            Entry deny  = a.granted() ? b : a;
            String gctx = grant.context().isEmpty() ? "global" : grant.context().toString();
            String dctx = deny.context().isEmpty()  ? "global" : deny.context().toString();
            return "&f" + node + " &8- &aGRANT&8(" + gctx + ", " + grant.source() + ") &8vs &cDENY&8("
                    + dctx + ", " + deny.source() + ")";
        }
    }
}
