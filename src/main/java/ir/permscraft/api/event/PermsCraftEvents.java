package ir.permscraft.api.event;

import ir.permscraft.api.node.Node;
import org.bukkit.event.HandlerList;

import java.util.Map;
import java.util.UUID;

// ═══════════════════════════════════════════════════════════════════════════════
// UserDataRecalculateEvent
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Fired after a user's resolved permission map is recalculated.
 * The resolved map is the final result after inheritance, wildcards, and contexts.
 *
 * so listeners can diff permissions without calling the API again.
 */
class UserDataRecalculateEvent extends PermsCraftEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID              uuid;
    private final String            username;
    private final Map<String,Boolean> resolvedPermissions; // immutable snapshot

    public UserDataRecalculateEvent(UUID uuid, String username,
                                    Map<String,Boolean> resolvedPermissions) {
        this.uuid                = uuid;
        this.username            = username;
        this.resolvedPermissions = Map.copyOf(resolvedPermissions);
    }

    public UUID              getUuid()                { return uuid; }
    public String            getUsername()             { return username; }
    /** Immutable snapshot of resolved permissions at recalculation time. */
    public Map<String,Boolean> getResolvedPermissions(){ return resolvedPermissions; }

    @Override public HandlerList getHandlers()    { return HANDLERS; }
    public static  HandlerList   getHandlerList() { return HANDLERS; }
}

// ═══════════════════════════════════════════════════════════════════════════════
// GroupDataRecalculateEvent
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Fired after a group's resolved permission map is recalculated (e.g. after
 * a permission is added, removed, or a parent changes).
 */
class GroupDataRecalculateEvent extends PermsCraftEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String              groupName;
    private final Map<String,Boolean> resolvedPermissions;

    public GroupDataRecalculateEvent(String groupName, Map<String,Boolean> resolvedPermissions) {
        this.groupName           = groupName;
        this.resolvedPermissions = Map.copyOf(resolvedPermissions);
    }

    public String              getGroupName()           { return groupName; }
    public Map<String,Boolean> getResolvedPermissions() { return resolvedPermissions; }

    @Override public HandlerList getHandlers()    { return HANDLERS; }
    public static  HandlerList   getHandlerList() { return HANDLERS; }
}

// ═══════════════════════════════════════════════════════════════════════════════
// UserTrackPromoteEvent
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Fired after a user is promoted on a track.
 *
 * so listeners can implement custom rewards for each tier.
 */
class UserTrackPromoteEvent extends PermsCraftEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID   uuid;
    private final String username;
    private final String trackName;
    private final String fromGroup;   // null if user was not on the track before
    private final String toGroup;
    private final String actor;

    public UserTrackPromoteEvent(UUID uuid, String username, String trackName,
                                 String fromGroup, String toGroup, String actor) {
        this.uuid      = uuid;
        this.username  = username;
        this.trackName = trackName;
        this.fromGroup = fromGroup;
        this.toGroup   = toGroup;
        this.actor     = actor;
    }

    public UUID   getUuid()      { return uuid; }
    public String getUsername()  { return username; }
    public String getTrackName() { return trackName; }
    public String getFromGroup() { return fromGroup; }
    public String getToGroup()   { return toGroup; }
    public String getActor()     { return actor; }

    @Override public HandlerList getHandlers()    { return HANDLERS; }
    public static  HandlerList   getHandlerList() { return HANDLERS; }
}

// ═══════════════════════════════════════════════════════════════════════════════
// UserTrackDemoteEvent
// ═══════════════════════════════════════════════════════════════════════════════

/** Fired after a user is demoted on a track. Mirror of {@link UserTrackPromoteEvent}. */
class UserTrackDemoteEvent extends PermsCraftEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID   uuid;
    private final String username;
    private final String trackName;
    private final String fromGroup;
    private final String toGroup;    // null if user was at the bottom
    private final String actor;

    public UserTrackDemoteEvent(UUID uuid, String username, String trackName,
                                String fromGroup, String toGroup, String actor) {
        this.uuid      = uuid;
        this.username  = username;
        this.trackName = trackName;
        this.fromGroup = fromGroup;
        this.toGroup   = toGroup;
        this.actor     = actor;
    }

    public UUID   getUuid()      { return uuid; }
    public String getUsername()  { return username; }
    public String getTrackName() { return trackName; }
    public String getFromGroup() { return fromGroup; }
    public String getToGroup()   { return toGroup; }
    public String getActor()     { return actor; }

    @Override public HandlerList getHandlers()    { return HANDLERS; }
    public static  HandlerList   getHandlerList() { return HANDLERS; }
}

// ═══════════════════════════════════════════════════════════════════════════════
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Fired when a timed permission or timed group membership expires naturally.
 *
 * Allows plugins to implement post-expiry logic (e.g. send a notification,
 * downgrade a shop tier, trigger a workflow).
 */
class NodeExpireEvent extends PermsCraftEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final TargetType targetType;
    private final UUID       userUUID;
    private final String     targetName;
    private final Node       expiredNode;

    public NodeExpireEvent(TargetType targetType, UUID userUUID,
                           String targetName, Node expiredNode) {
        this.targetType  = targetType;
        this.userUUID    = userUUID;
        this.targetName  = targetName;
        this.expiredNode = expiredNode;
    }

    public TargetType getTargetType()  { return targetType; }
    public UUID       getUserUUID()    { return userUUID; }
    public String     getTargetName()  { return targetName; }
    public Node       getExpiredNode() { return expiredNode; }

    @Override public HandlerList getHandlers()    { return HANDLERS; }
    public static  HandlerList   getHandlerList() { return HANDLERS; }
}
