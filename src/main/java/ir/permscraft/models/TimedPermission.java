package ir.permscraft.models;

import java.time.Instant;

public class TimedPermission {
    private final String permission;
    private final Instant expiry;
    private final String target; // UUID or group name
    private final boolean isGroup;

    public TimedPermission(String target, boolean isGroup, String permission, Instant expiry) {
        this.target = target;
        this.isGroup = isGroup;
        this.permission = permission;
        this.expiry = expiry;
    }

    public String getPermission() { return permission; }
    public Instant getExpiry() { return expiry; }
    public String getTarget() { return target; }
    public boolean isGroup() { return isGroup; }
    public boolean isExpired() { return Instant.now().isAfter(expiry); }

    public String getFormattedExpiry() {
        long seconds = expiry.getEpochSecond() - Instant.now().getEpochSecond();
        if (seconds < 0) return "Expired";
        long days = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        long mins = (seconds % 3600) / 60;
        if (days > 0) return days + "d " + hours + "h";
        if (hours > 0) return hours + "h " + mins + "m";
        return mins + "m " + (seconds % 60) + "s";
    }
}
