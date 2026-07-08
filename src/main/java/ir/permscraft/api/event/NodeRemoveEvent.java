package ir.permscraft.api.event;

import ir.permscraft.api.node.Node;
import org.bukkit.event.HandlerList;
import java.util.UUID;

/** Fired after a {@link Node} is removed from a user or group. */
public class NodeRemoveEvent extends PermsCraftEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final TargetType targetType;
    private final UUID       userUUID;
    private final String     targetName;
    private final Node       node;
    private final String     actor;

    public NodeRemoveEvent(TargetType targetType, UUID userUUID,
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

    @Override public HandlerList getHandlers()    { return HANDLERS; }
    public static  HandlerList   getHandlerList() { return HANDLERS; }
}
