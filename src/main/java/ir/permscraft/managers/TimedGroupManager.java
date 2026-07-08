package ir.permscraft.managers;

import ir.permscraft.FoliaScheduler;
import ir.permscraft.api.event.EventBus;
import ir.permscraft.api.event.PermsCraftEvent;
import ir.permscraft.api.node.Node;
import ir.permscraft.PermsCraft;
import ir.permscraft.models.TimedGroup;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages temporary group memberships.
 *
 * Commands:
 *   /pc user <player> group addtimed <group> <duration>
 *   /pc user <player> group removetimed <group>
 *   /pc user <player> timedgroups
 *
 * Behaviour:
 *   - Timed groups are additive on top of permanent groups
 *   - On expiry the temporary membership is silently removed and
 *     the player's permissions are refreshed
 *   - Persisted in pc_timed_groups table (see SqlStorage)
 *   - Compatible with all storage backends via StorageBackend interface
 */
public class TimedGroupManager {

    private final PermsCraft plugin;

    // userUuid → list of active timed groups
    private final Map<String, CopyOnWriteArrayList<TimedGroup>> timedGroups =
            new ConcurrentHashMap<>();

    private FoliaScheduler.TaskHandle cleanupTask;

    public TimedGroupManager(PermsCraft plugin) {
        this.plugin = plugin;
        reload();
        startCleanupTask();
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    public void reload() {
        timedGroups.clear();
        plugin.getStorage().loadActiveTimedGroups().forEach(tg ->
            timedGroups.computeIfAbsent(tg.getUserUuid(), k -> new CopyOnWriteArrayList<>()).add(tg)
        );
    }

    public void shutdown() {
        if (cleanupTask != null) cleanupTask.cancel();
    }

    // ── Mutation ──────────────────────────────────────────────────────────────

    /**
     * Add a temporary group membership.
     *
     * @param userUuid       UUID string of the player
     * @param groupName      group to temporarily assign
     * @param durationSeconds how long the membership lasts
     */
    public void addTimedGroup(String userUuid, String groupName, long durationSeconds) {
        // Remove any existing timed membership for the same group (replace semantics)
        removeTimedGroup(userUuid, groupName);

        Instant expiry = Instant.now().plusSeconds(durationSeconds);
        TimedGroup tg  = new TimedGroup(userUuid, groupName, expiry);

        timedGroups.computeIfAbsent(userUuid, k -> new CopyOnWriteArrayList<>()).add(tg);
        FoliaScheduler.runAsync(plugin, () ->
            plugin.getStorage().saveTimedGroup(userUuid, groupName, expiry.getEpochSecond())
        );

        // Refresh online player
        refreshPlayer(userUuid);
    }

    /**
     * Remove a temporary group membership immediately.
     */
    public boolean removeTimedGroup(String userUuid, String groupName) {
        List<TimedGroup> list = timedGroups.get(userUuid);
        boolean removed = false;
        if (list != null) {
            removed = list.removeIf(tg -> tg.getGroupName().equalsIgnoreCase(groupName));
        }
        if (removed) {
            FoliaScheduler.runAsync(plugin, () ->
                plugin.getStorage().deleteTimedGroup(userUuid, groupName)
            );
            refreshPlayer(userUuid);
        }
        return removed;
    }

    // ── Query ─────────────────────────────────────────────────────────────────

    /** All active (non-expired) timed group memberships for a user. */
    public List<TimedGroup> getTimedGroups(String userUuid) {
        List<TimedGroup> list = timedGroups.get(userUuid);
        if (list == null) return Collections.emptyList();
        List<TimedGroup> active = new ArrayList<>();
        for (TimedGroup tg : list) {
            if (!tg.isExpired()) active.add(tg);
        }
        return active;
    }

    /** Set of group names temporarily assigned to this user (non-expired). */
    public Set<String> getActiveGroupNames(String userUuid) {
        Set<String> names = new HashSet<>();
        for (TimedGroup tg : getTimedGroups(userUuid)) {
            names.add(tg.getGroupName());
        }
        return names;
    }

    /** True if user has this group as a timed (temporary) membership. */
    public boolean hasTimed(String userUuid, String groupName) {
        return getActiveGroupNames(userUuid).stream()
                .anyMatch(g -> g.equalsIgnoreCase(groupName));
    }

    // ── Cleanup task ──────────────────────────────────────────────────────────

    private void startCleanupTask() {
        // Runs every 5 seconds — same interval as TimedPermissionManager
        cleanupTask = FoliaScheduler.runAsyncTimer(plugin, () -> {
            long now = Instant.now().getEpochSecond();
            List<TimedGroup> expired = new ArrayList<>();

            for (Map.Entry<String, CopyOnWriteArrayList<TimedGroup>> entry : timedGroups.entrySet()) {
                entry.getValue().removeIf(tg -> {
                    if (tg.isExpired()) {
                        expired.add(tg);
                        return true;
                    }
                    return false;
                });
            }

            if (expired.isEmpty()) return;

            // Batch delete from storage
            plugin.getStorage().deleteExpiredTimedGroups(now);

            // Fire NodeExpireEvent for each expired timed group
            for (TimedGroup tg : expired) {
                try {
                    UUID expiredUuid = UUID.fromString(tg.getUserUuid());
                    ir.permscraft.models.User expiredUser = plugin.getUserManager().getUser(expiredUuid);
                    String expiredName = expiredUser != null ? expiredUser.getUsername() : tg.getUserUuid();
                    Node expiredNode = Node.group(tg.getGroupName())
                            .expiryEpochSeconds(tg.getExpiry().getEpochSecond()).build();
                    EventBus.fireNodeExpire(plugin, PermsCraftEvent.TargetType.USER,
                            expiredUuid, expiredName, expiredNode);
                } catch (IllegalArgumentException ignored) {}
            }
            // Refresh affected online players on the correct thread
            Set<String> affected = new HashSet<>();
            for (TimedGroup tg : expired) affected.add(tg.getUserUuid());

            FoliaScheduler.runSync(plugin, () -> {
                for (String uuidStr : affected) {
                    try {
                        UUID uuid = UUID.fromString(uuidStr);
                        if (plugin.getServer().getPlayer(uuid) != null) {
                            plugin.getUserManager().refreshPermissions(uuid);
                        }
                    } catch (IllegalArgumentException ignored) {}
                }
            });

        }, 20L * 5, 20L * 5);
    }

    private void refreshPlayer(String userUuid) {
        try {
            UUID uuid = UUID.fromString(userUuid);
            plugin.getUserManager().refreshPermissions(uuid);
        } catch (IllegalArgumentException ignored) {}
    }
}
