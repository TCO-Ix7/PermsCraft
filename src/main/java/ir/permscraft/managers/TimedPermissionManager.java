package ir.permscraft.managers;

import ir.permscraft.FoliaScheduler;
import ir.permscraft.api.event.EventBus;
import ir.permscraft.api.event.PermsCraftEvent;
import ir.permscraft.api.node.Node;
import ir.permscraft.PermsCraft;
import ir.permscraft.models.TimedPermission;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class TimedPermissionManager {

    private final PermsCraft plugin;
    private final Map<String, CopyOnWriteArrayList<TimedPermission>> timedPerms = new ConcurrentHashMap<>();
    // FOLIA: replaced BukkitTask with FoliaScheduler.TaskHandle
    private FoliaScheduler.TaskHandle cleanupTask;

    public TimedPermissionManager(PermsCraft plugin) {
        this.plugin = plugin;
        loadAll();
        startCleanupTask();
    }

    private void loadAll() {
        timedPerms.clear();
        plugin.getStorage().loadActiveTimedPermissions().forEach(tp ->
            timedPerms.computeIfAbsent(tp.getTarget(), k -> new CopyOnWriteArrayList<>()).add(tp)
        );
    }

    public void addTimedPermission(String target, boolean isGroup, String permission, long durationSeconds) {
        Instant expiry = Instant.now().plusSeconds(durationSeconds);
        TimedPermission tp = new TimedPermission(target, isGroup, permission, expiry);
        timedPerms.computeIfAbsent(target, k -> new CopyOnWriteArrayList<>()).add(tp);
        // FIX: async DB write — was blocking the calling thread (main thread for
        // command/GUI callers) on a remote-DB round-trip.
        FoliaScheduler.runAsync(plugin, () ->
                plugin.getStorage().saveTimedPermission(target, isGroup, permission, expiry.getEpochSecond()));
        if (!isGroup) {
            try {
                UUID uuid = UUID.fromString(target);
                plugin.getUserManager().refreshPermissions(uuid);
            } catch (IllegalArgumentException ignored) {}
        }
    }

    public void removeTimedPermission(String target, String permission) {
        List<TimedPermission> list = timedPerms.get(target);
        if (list != null) {
            list.removeIf(tp -> tp.getPermission().equalsIgnoreCase(permission));
        }
        // FOLIA: async I/O
        FoliaScheduler.runAsync(plugin, () ->
            plugin.getStorage().deleteTimedPermission(target, permission));
        try {
            UUID uuid = UUID.fromString(target);
            plugin.getUserManager().refreshPermissions(uuid);
        } catch (IllegalArgumentException ignored) {}
    }

    public List<TimedPermission> getTimedPermissions(String target) {
        return timedPerms.getOrDefault(target, new CopyOnWriteArrayList<>());
    }

    public Set<String> getActivePermissions(String target) {
        Set<String> perms = new HashSet<>();
        List<TimedPermission> list = timedPerms.get(target);
        if (list == null) return perms;
        for (TimedPermission tp : list) {
            if (!tp.isExpired()) perms.add(tp.getPermission());
        }
        return perms;
    }

    private void startCleanupTask() {
        // FOLIA: use runAsyncTimer — cleanup is pure I/O + memory, no world access
        cleanupTask = FoliaScheduler.runAsyncTimer(plugin, () -> {

            final long snapshotEpoch = Instant.now().getEpochSecond();

            List<TimedPermission> expired = new ArrayList<>();
            for (Map.Entry<String, CopyOnWriteArrayList<TimedPermission>> entry :
                    new ArrayList<>(timedPerms.entrySet())) {
                entry.getValue().removeIf(tp -> {
                    if (tp.getExpiry().getEpochSecond() > snapshotEpoch) return false;
                    expired.add(tp);
                    return true;
                });
            }

            if (expired.isEmpty()) return;

            // Fire NodeExpireEvent for each expired permission
            for (ir.permscraft.models.TimedPermission tp : expired) {
                try {
                    java.util.UUID eu = java.util.UUID.fromString(tp.getTarget());
                    ir.permscraft.models.User eu2 = plugin.getUserManager().getUser(eu);
                    String eName = eu2 != null ? eu2.getUsername() : tp.getTarget();
                    Node expiredNode = Node.permission(tp.getPermission())
                            .expiryEpochSeconds(tp.getExpiry().getEpochSecond()).build();
                    EventBus.fireNodeExpire(plugin, PermsCraftEvent.TargetType.USER, eu, eName, expiredNode);
                } catch (Exception ignored) {}
            }

            plugin.getStorage().deleteExpiredTimedPermissions(snapshotEpoch);

            // FOLIA: schedule permission refresh per-entity or globally
            for (TimedPermission tp : expired) {
                if (!tp.isGroup()) {
                    try {
                        UUID uuid = UUID.fromString(tp.getTarget());
                        // refreshPermissions() internally uses FoliaScheduler.runForEntity
                        // so we just call it directly — it's already Folia-safe
                        plugin.getUserManager().refreshPermissions(uuid);
                    } catch (IllegalArgumentException ignored) {}
                } else {
                    final String groupTarget = tp.getTarget();
                    // For group expiry we need to touch all online players →
                    // schedule on the global region (Folia) or main thread (Bukkit)
                    FoliaScheduler.runSync(plugin, () ->
                        plugin.getServer().getOnlinePlayers().forEach(p -> {
                            var user = plugin.getUserManager().getUser(p.getUniqueId());
                            if (user != null && user.inGroup(groupTarget)) {
                                plugin.getUserManager().refreshPermissions(p.getUniqueId());
                            }
                        })
                    );
                }
            }

        }, 20L * 60, 20L * 60);
    }

    public void shutdown() { if (cleanupTask != null) cleanupTask.cancel(); }

    public void reload() { loadAll(); }

    /**
     * Parse a duration string into seconds.
     * Delegates to {@link ir.permscraft.utils.DurationParser} — see that class
     * for the full list of supported units (s, m, h, d, w, mo, y) and compound
     * formats (e.g. 1d10h, 2w3d, 1y6mo).
     */
    public static long parseDuration(String input) {
        return ir.permscraft.utils.DurationParser.parseOrNegative(input);
    }
}
