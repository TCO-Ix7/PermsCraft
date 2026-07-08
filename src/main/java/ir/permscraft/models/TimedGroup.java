package ir.permscraft.models;

import java.time.Instant;

/**
 * Represents a temporary group membership for a user.
 *
 * Stored in pc_timed_groups table (separate from pc_timed_permissions
 * so the two concepts remain cleanly separated).
 *
 * When expired:
 *   - Removed from TimedGroupManager's in-memory map
 *   - Deleted from storage
 *   - User's permission attachment is refreshed
 *   - If user was ONLY in this group (and default), no change to persistent groups
 *
 * Note: timed group membership is purely additive — it does NOT remove
 * the user from any permanent group. On expiry the temporary membership
 * simply disappears; permanent groups are unaffected.
 */
public class TimedGroup {

    private final String  userUuid;   // UUID string of the player
    private final String  groupName;  // group to temporarily assign
    private final Instant expiry;     // absolute expiry time

    public TimedGroup(String userUuid, String groupName, Instant expiry) {
        this.userUuid  = userUuid;
        this.groupName = groupName;
        this.expiry    = expiry;
    }

    public String  getUserUuid()  { return userUuid; }
    public String  getGroupName() { return groupName; }
    public Instant getExpiry()    { return expiry; }

    public boolean isExpired() { return Instant.now().isAfter(expiry); }

    /** Human-readable remaining time (e.g. "2d 3h", "45m 12s"). */
    public String getFormattedExpiry() {
        long seconds = expiry.getEpochSecond() - Instant.now().getEpochSecond();
        if (seconds <= 0) return "Expired";
        long days  = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        long mins  = (seconds % 3600) / 60;
        long secs  = seconds % 60;
        if (days  > 0) return days  + "d " + hours + "h";
        if (hours > 0) return hours + "h " + mins  + "m";
        return mins + "m " + secs + "s";
    }

    @Override
    public String toString() {
        return "TimedGroup{user=" + userUuid + ", group=" + groupName
                + ", expiry=" + expiry + "}";
    }
}
