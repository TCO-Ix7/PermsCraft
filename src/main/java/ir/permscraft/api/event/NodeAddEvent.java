package ir.permscraft.api.event;

import ir.permscraft.api.node.Node;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/**
 * Fired after a {@link Node} is added to a user or group.
 *
 * Example:
 *   @EventHandler
 *   public void onNodeAdd(NodeAddEvent e) {
 *       if (e.getTargetType() == TargetType.USER && e.getNode().getType() == NodeType.PERMISSION) {
 *           Bukkit.broadcastMessage(e.getTargetName() + " got " + e.getNode().getPermission());
 *       }
 *   }
 */
public class NodeAddEvent extends PermsCraftEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final TargetType targetType;
    private final UUID       userUUID;    // null if targetType == GROUP
    private final String     targetName;  // player name or group name
    private final Node       node;
    private final String     actor;       // who made the change (player name or "CONSOLE" or "API")

    public NodeAddEvent(TargetType targetType, UUID userUUID,
                        String targetName, Node node, String actor) {
        this.targetType = targetType;
        this.userUUID   = userUUID;
        this.targetName = targetName;
        this.node       = node;
        this.actor      = actor;
    }

    public TargetType getTargetType() { return targetType; }
    public UUID       getUserUUID()   { return userUUID; }
    public String     getTargetName() { return targetName; }
    public Node       getNode()       { return node; }
    public String     getActor()      { return actor; }

    @Override public HandlerList getHandlers()           { return HANDLERS; }
    public static  HandlerList   getHandlerList()        { return HANDLERS; }
}
