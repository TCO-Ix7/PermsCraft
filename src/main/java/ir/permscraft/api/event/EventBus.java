package ir.permscraft.api.event;

import ir.permscraft.PermsCraft;
import ir.permscraft.api.node.Node;
import ir.permscraft.api.node.NodeType;

import java.util.Map;
import java.util.UUID;

/**
 * Central utility for firing PermsCraft events on the Bukkit event bus.
 *
 * All events are fired asynchronously (PermsCraftEvent is async = true),
 * so calling from async threads is safe and does not block.
 *
 * Usage (in UserManager, GroupManager, TimedPermissionManager, etc.):
 *   EventBus.fireNodeAdd(plugin, TargetType.USER, uuid, name, node, actor);
 *   EventBus.fireNodeRemove(plugin, TargetType.GROUP, null, groupName, node, actor);
 *   EventBus.fireUserRecalculate(plugin, uuid, name, resolvedMap);
 *   EventBus.fireNodeExpire(plugin, TargetType.USER, uuid, name, node);
 *   EventBus.fireTrackPromote(plugin, uuid, name, track, from, to, actor);
 */
public final class EventBus {

    private EventBus() {}

    // ── Node Add / Remove ─────────────────────────────────────────────────────

    public static void fireNodeAdd(PermsCraft plugin,
                                   PermsCraftEvent.TargetType type, UUID uuid,
                                   String targetName, Node node, String actor) {
        callEvent(plugin, new NodeAddEvent(type, uuid, targetName, node, actor));
    }

    public static void fireNodeRemove(PermsCraft plugin,
                                      PermsCraftEvent.TargetType type, UUID uuid,
                                      String targetName, Node node, String actor) {
        callEvent(plugin, new NodeRemoveEvent(type, uuid, targetName, node, actor));
    }

    // ── Data Recalculate ──────────────────────────────────────────────────────

    public static void fireUserRecalculate(PermsCraft plugin, UUID uuid,
                                           String username,
                                           Map<String, Boolean> resolved) {
        callEvent(plugin, new UserDataRecalculateEvent(uuid, username, resolved));
    }

    public static void fireGroupRecalculate(PermsCraft plugin, String groupName,
                                            Map<String, Boolean> resolved) {
        callEvent(plugin, new GroupDataRecalculateEvent(groupName, resolved));
    }

    // ── Track ─────────────────────────────────────────────────────────────────

    public static void fireTrackPromote(PermsCraft plugin,
                                        UUID uuid, String username,
                                        String track, String from, String to,
                                        String actor) {
        callEvent(plugin, new UserTrackPromoteEvent(uuid, username, track, from, to, actor));
    }

    public static void fireTrackDemote(PermsCraft plugin,
                                       UUID uuid, String username,
                                       String track, String from, String to,
                                       String actor) {
        callEvent(plugin, new UserTrackDemoteEvent(uuid, username, track, from, to, actor));
    }

    // ── Node Expire ───────────────────────────────────────────────────────────

    public static void fireNodeExpire(PermsCraft plugin,
                                      PermsCraftEvent.TargetType type, UUID uuid,
                                      String targetName, Node node) {
        callEvent(plugin, new NodeExpireEvent(type, uuid, targetName, node));
    }

    /**
     * Helper: build a simple permission Node for event payloads from a raw string.
     * Handles "-node" negation prefix automatically.
     */
    public static Node nodeFromString(String rawNode) {
        if (rawNode == null) return Node.permission("unknown").build();
        boolean deny = rawNode.startsWith("-");
        String clean = deny ? rawNode.substring(1) : rawNode;
        return Node.permission(clean).value(!deny).build();
    }

    /**
     * Helper: build a group Node for event payloads.
     */
    public static Node groupNode(String groupName) {
        return Node.group(groupName).build();
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private static void callEvent(PermsCraft plugin, org.bukkit.event.Event event) {
        try {
            plugin.getServer().getPluginManager().callEvent(event);
        } catch (Exception e) {
            // Never let event firing crash the permission system
            plugin.getLogger().warning(
                "[PermsCraft EventBus] Exception in event handler for "
                + event.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
