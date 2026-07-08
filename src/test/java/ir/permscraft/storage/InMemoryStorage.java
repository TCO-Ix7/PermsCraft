package ir.permscraft.storage;

import ir.permscraft.context.Context;
import ir.permscraft.context.ContextSet;
import ir.permscraft.context.ContextualPermission;
import ir.permscraft.logging.LogEntry;
import ir.permscraft.models.Group;
import ir.permscraft.models.TimedPermission;
import ir.permscraft.models.Track;
import ir.permscraft.models.User;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory StorageBackend for unit/integration tests.
 * Extracted from InMemoryStorageTest so it can be reused across test packages.
 */
public class InMemoryStorage implements StorageBackend {

    private final Map<String, Group>              groups     = new LinkedHashMap<>();
    private final Map<UUID, User>                 users      = new LinkedHashMap<>();
    private final Map<String, Map<String,String>> meta       = new HashMap<>();
    private final List<Track>                     tracks     = new ArrayList<>();
    private final List<LogEntry>                  logs       = new ArrayList<>();
    private final List<TimedPermission>           timedPerms = new ArrayList<>();
    private final List<ContextRow>                ctxPerms   = new ArrayList<>();
    private long logIdSeq = 0;

    @Override public boolean init() { return true; }
    @Override public void close() {}

    // ── groups ────────────────────────────────────────────────────────────────

    @Override public List<Group> loadAllGroups() { return new ArrayList<>(groups.values()); }
    @Override public void saveGroup(Group g) { groups.put(g.getName().toLowerCase(), g); }
    @Override public void deleteGroup(String name) { groups.remove(name.toLowerCase()); }
    @Override public void addGroupPermission(String g, String p) { getGroup(g).addPermission(p); }
    @Override public void removeGroupPermission(String g, String p) { getGroup(g).removePermission(p); }
    @Override public void addGroupInheritance(String g, String parent) { getGroup(g).addInheritance(parent); }
    @Override public void removeGroupInheritance(String g, String parent) { getGroup(g).removeInheritance(parent); }
    @Override public void clearGroup(String g) {
        Group grp = getGroup(g); if (grp == null) return;
        grp.getPermissions().clear(); grp.getInheritedGroups().clear();
    }

    // ── users ─────────────────────────────────────────────────────────────────

    @Override public User loadUser(UUID uuid, String username) {
        return users.computeIfAbsent(uuid, id -> new User(id, username));
    }
    @Override public List<User> loadAllUsers() { return new ArrayList<>(users.values()); }
    @Override public void saveUser(User u) { users.put(u.getUuid(), u); }
    @Override public void saveUserPrimaryGroup(UUID uuid, String pg) {
        User u = users.get(uuid); if (u != null) u.setPrimaryGroup(pg);
    }
    @Override public void addUserToGroup(UUID uuid, String g) {
        users.computeIfAbsent(uuid, id -> new User(id, "")).addGroup(g);
    }
    @Override public void removeUserFromGroup(UUID uuid, String g) {
        User u = users.get(uuid); if (u != null) u.removeGroup(g);
    }
    @Override public void addUserPermission(UUID uuid, String p) {
        users.computeIfAbsent(uuid, id -> new User(id, "")).addPermission(p);
    }
    @Override public void removeUserPermission(UUID uuid, String p) {
        User u = users.get(uuid); if (u != null) u.removePermission(p);
    }
    @Override public void clearUser(UUID uuid) {
        User u = users.get(uuid); if (u == null) return;
        u.getPermissions().clear(); u.getGroups().clear();
    }
    @Override public UUID findUUIDByUsername(String username) {
        return users.values().stream()
            .filter(u -> u.getUsername().equalsIgnoreCase(username))
            .map(User::getUuid).findFirst().orElse(null);
    }

    // ── meta ──────────────────────────────────────────────────────────────────

    @Override public Map<String, String> loadMeta(String target, boolean isGroup) {
        return meta.getOrDefault(key(target, isGroup), Map.of());
    }
    @Override public void saveMeta(String target, boolean isGroup, String k, String v) {
        meta.computeIfAbsent(key(target, isGroup), x -> new HashMap<>()).put(k, v);
    }
    @Override public void deleteMeta(String target, boolean isGroup, String k) {
        Map<String, String> m = meta.get(key(target, isGroup)); if (m != null) m.remove(k);
    }
    @Override public void deleteAllMeta(String target, boolean isGroup) {
        meta.remove(key(target, isGroup));
    }
    @Override public void saveTimedMeta(String t, boolean ig, String k, String v, long expiry) {}

    // ── search ────────────────────────────────────────────────────────────────

    @Override public List<String> searchPermission(String perm) {
        List<String> result = new ArrayList<>();
        groups.forEach((name, g) -> { if (g.getPermissions().contains(perm)) result.add("group:" + name); });
        users.forEach((uuid, u) -> { if (u.getPermissions().contains(perm)) result.add(uuid.toString()); });
        return result;
    }

    // ── tracks ────────────────────────────────────────────────────────────────

    @Override public List<Track> loadAllTracks() { return new ArrayList<>(tracks); }
    @Override public void saveTrack(Track t) {
        tracks.removeIf(x -> x.getName().equals(t.getName())); tracks.add(t);
    }
    @Override public void deleteTrack(String name) { tracks.removeIf(t -> t.getName().equals(name)); }

    // ── logs ──────────────────────────────────────────────────────────────────

    @Override public void saveLog(long ts, String actor, String action, String target, String detail) {
        LogEntry.Action a;
        try { a = LogEntry.Action.valueOf(action); } catch (Exception e) { a = LogEntry.Action.GROUP_CREATE; }
        logs.add(new LogEntry(++logIdSeq, Instant.ofEpochSecond(ts), actor, a, target, detail));
    }
    @Override public List<LogEntry> loadRecentLogs(int limit) {
        int from = Math.max(0, logs.size() - limit);
        return new ArrayList<>(logs.subList(from, logs.size()));
    }
    @Override public List<LogEntry> loadLogsByTarget(String t, int l) {
        return logs.stream().filter(e -> t.equals(e.getTarget())).limit(l).toList();
    }
    @Override public List<LogEntry> loadLogsByActor(String a, int l) {
        return logs.stream().filter(e -> a.equals(e.getActor())).limit(l).toList();
    }
    @Override public void deleteLogsOlderThan(long epochSecond) {
        logs.removeIf(e -> e.getTimestamp().getEpochSecond() < epochSecond);
    }

    // ── timed permissions ─────────────────────────────────────────────────────

    @Override public List<TimedPermission> loadActiveTimedPermissions() {
        return timedPerms.stream().filter(tp -> !tp.isExpired()).toList();
    }
    @Override public void saveTimedPermission(String target, boolean isGroup, String perm, long expiry) {
        timedPerms.add(new TimedPermission(target, isGroup, perm, Instant.ofEpochSecond(expiry)));
    }
    @Override public void deleteExpiredTimedPermissions(long nowEpochSecond) {
        timedPerms.removeIf(tp -> tp.getExpiry().getEpochSecond() < nowEpochSecond);
    }
    @Override public void deleteTimedPermission(String target, String perm) {
        timedPerms.removeIf(tp -> tp.getTarget().equals(target) && tp.getPermission().equals(perm));
    }

    // ── context permissions ───────────────────────────────────────────────────

    @Override public List<ContextRow> loadAllContextPermissions() { return new ArrayList<>(ctxPerms); }
    @Override public void saveContextPermission(String target, boolean isGroup, String perm,
                                                 Context ctx, boolean granted) {
        ctxPerms.add(new ContextRow(target, isGroup, new ContextualPermission(perm, ctx, granted)));
    }
    @Override public void saveContextPermission(String target, boolean isGroup, String perm,
                                                 ContextSet requiredCtx, boolean granted) {
        ctxPerms.add(new ContextRow(target, isGroup, new ContextualPermission(perm, requiredCtx, granted)));
    }
    @Override public void deleteContextPermission(String target, String perm, Context ctx) {
        ctxPerms.removeIf(r -> r.target().equals(target)
            && r.permission().getPermission().equals(perm)
            && r.permission().getContext().equals(ctx));
    }
    @Override public void deleteContextPermission(String target, String perm, ContextSet requiredCtx) {
        ctxPerms.removeIf(r -> r.target().equals(target)
            && r.permission().getPermission().equals(perm)
            && r.permission().getRequiredContext().equals(requiredCtx));
    }

    // ── bulk ──────────────────────────────────────────────────────────────────

    @Override public int bulkAddPermissionToUsers(String perm) {
        int count = 0;
        for (User u : users.values()) {
            if (!u.getPermissions().contains(perm)) { u.addPermission(perm); count++; }
        }
        return count;
    }
    @Override public int bulkRemovePermissionFromUsers(String perm) {
        int count = 0;
        for (User u : users.values()) {
            if (u.getPermissions().contains(perm)) { u.removePermission(perm); count++; }
        }
        return count;
    }
    @Override public int bulkAddGroupToUsers(String group) {
        int count = 0;
        for (User u : users.values()) {
            if (!u.getGroups().contains(group)) { u.addGroup(group); count++; }
        }
        return count;
    }
    @Override public int bulkRemoveGroupFromUsers(String group) {
        int count = 0;
        for (User u : users.values()) {
            if (u.getGroups().contains(group)) { u.removeGroup(group); count++; }
        }
        return count;
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String key(String t, boolean ig) { return (ig ? "group:" : "user:") + t; }
    private Group getGroup(String name) { return groups.get(name.toLowerCase()); }
}
