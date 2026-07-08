package ir.permscraft.logging;

import java.time.Instant;

public class LogEntry {

    public enum Action {
        USER_PERM_ADD, USER_PERM_REMOVE,
        USER_GROUP_ADD, USER_GROUP_REMOVE,
        USER_PREFIX_SET, USER_SUFFIX_SET,
        GROUP_CREATE, GROUP_DELETE,
        GROUP_PERM_ADD, GROUP_PERM_REMOVE,
        GROUP_PARENT_ADD, GROUP_PARENT_REMOVE,
        GROUP_PREFIX_SET, GROUP_SUFFIX_SET,
        TRACK_CREATE, TRACK_DELETE,
        TRACK_PROMOTE, TRACK_DEMOTE,
        TIMED_PERM_ADD, TIMED_PERM_EXPIRE
    }

    private final long id;
    private final Instant timestamp;
    private final String actor;      // who did it (player name or CONSOLE)
    private final Action action;
    private final String target;     // affected user/group
    private final String detail;     // permission, group name, etc.

    public LogEntry(long id, Instant timestamp, String actor, Action action, String target, String detail) {
        this.id        = id;
        this.timestamp = timestamp;
        this.actor     = actor;
        this.action    = action;
        this.target    = target;
        this.detail    = detail;
    }

    public long getId()          { return id; }
    public Instant getTimestamp(){ return timestamp; }
    public String getActor()     { return actor; }
    public Action getAction()    { return action; }
    public String getTarget()    { return target; }
    public String getDetail()    { return detail; }

    @Override
    public String toString() {
        return "[" + timestamp + "] " + actor + " -> " + action + " on " + target + ": " + detail;
    }
}
