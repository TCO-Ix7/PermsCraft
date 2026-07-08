package ir.permscraft.api;

import ir.permscraft.PermsCraft;
import ir.permscraft.api.event.EventBus;
import ir.permscraft.api.event.PermsCraftEvent;
import ir.permscraft.api.node.Node;
import ir.permscraft.api.node.NodeType;
import ir.permscraft.context.ContextSet;
import ir.permscraft.models.Group;
import ir.permscraft.models.Track;
import ir.permscraft.models.User;
import ir.permscraft.utils.DurationParser;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Implementation of {@link PermsCraftAPI} v2.0.
 * Delegates to the existing managers — thin wrapper, no business logic here.
 */
public class PermsCraftAPIImpl implements PermsCraftAPI {

    private final PermsCraft plugin;
    private final PCUserManagerImpl  userManager;
    private final PCGroupManagerImpl groupManager;
    private final PCTrackManagerImpl trackManager;
    private final PCContextManagerImpl contextManager;

    public PermsCraftAPIImpl(PermsCraft plugin) {
        this.plugin         = plugin;
        this.userManager    = new PCUserManagerImpl(plugin);
        this.groupManager   = new PCGroupManagerImpl(plugin);
        this.trackManager   = new PCTrackManagerImpl(plugin);
        this.contextManager = new PCContextManagerImpl(plugin);
    }

    @Override public PCUserManager    users()    { return userManager; }
    @Override public PCGroupManager   groups()   { return groupManager; }
    @Override public PCTrackManager   tracks()   { return trackManager; }
    @Override public PCContextManager contexts() { return contextManager; }

    // ═════════════════════════════════════════════════════════════════════════
    // User Manager Impl
    // ═════════════════════════════════════════════════════════════════════════

    private static class PCUserManagerImpl implements PCUserManager {
        private final PermsCraft p;
        PCUserManagerImpl(PermsCraft p) { this.p = p; }

        @Override public Optional<User> getUser(UUID uuid) {
            return Optional.ofNullable(p.getUserManager().getUser(uuid));
        }
        @Override public Optional<User> getUser(String username) {
            return Optional.ofNullable(p.getUserManager().getUser(username));
        }
        @Override public CompletableFuture<Optional<User>> loadUser(UUID uuid, String username) {
            return p.getUserManager().getOrLoadUserAsync(uuid, username)
                    .thenApply(Optional::ofNullable);
        }

        @Override
        public void addNode(UUID uuid, Node node) {
            String actor = "API";
            switch (node.getType()) {
                case GROUP, TIMED_GROUP -> {
                    String group = node.getPermission().startsWith("group.")
                            ? node.getPermission().substring(6) : node.getPermission();
                    if (node.isTemporary()) {
                        long secs = node.getRemainingTime().getSeconds();
                        p.getTimedGroupManager().addTimedGroup(uuid.toString(), group, secs);
                    } else {
                        p.getUserManager().addToGroup(uuid, group);
                    }
                }
                case TIMED_PERMISSION -> {
                    long secs = node.getRemainingTime().getSeconds();
                    p.getTimedPermissionManager().addTimedPermission(
                            uuid.toString(), false, node.getPermission(), secs);
                }
                case PREFIX -> {
                    p.getUserManager().setPrefix(uuid, node.getPermission()
                            .replaceFirst("prefix\\.\\d+\\.", ""));
                }
                case SUFFIX -> {
                    p.getUserManager().setSuffix(uuid, node.getPermission()
                            .replaceFirst("suffix\\.\\d+\\.", ""));
                }
                default -> {
                    String raw = node.toStorageString();
                    p.getUserManager().addPermission(uuid, raw);
                }
            }
            User user = p.getUserManager().getUser(uuid);
            String name = user != null ? user.getUsername() : uuid.toString();
            EventBus.fireNodeAdd(p, PermsCraftEvent.TargetType.USER, uuid, name, node, actor);
        }

        @Override
        public boolean removeNode(UUID uuid, Node node) {
            boolean removed = removeNodeByString(uuid, node.toStorageString());
            if (removed) {
                User user = p.getUserManager().getUser(uuid);
                String name = user != null ? user.getUsername() : uuid.toString();
                EventBus.fireNodeRemove(p, PermsCraftEvent.TargetType.USER, uuid, name, node, "API");
            }
            return removed;
        }

        @Override
        public boolean removeNodeByString(UUID uuid, String rawNode) {
            User user = p.getUserManager().getUser(uuid);
            if (user == null) return false;
            String clean = rawNode.startsWith("-") ? rawNode.substring(1) : rawNode;
            if (user.getPermissions().contains(rawNode) || user.getPermissions().contains(clean)) {
                p.getUserManager().removePermission(uuid, clean);
                return true;
            }
            if (clean.startsWith("group.")) {
                String group = clean.substring(6);
                if (user.getGroups().contains(group)) {
                    p.getUserManager().removeFromGroup(uuid, group);
                    return true;
                }
            }
            return false;
        }

        @Override public List<Node> getNodes(UUID uuid) { return getNodes(uuid, null); }
        @Override public List<Node> getNodes(UUID uuid, NodeType type) {
            User user = p.getUserManager().getUser(uuid);
            if (user == null) return List.of();
            List<Node> result = new ArrayList<>();
            for (String raw : user.getPermissions()) {
                Node n = EventBus.nodeFromString(raw);
                if (type == null || n.getType() == type) result.add(n);
            }
            for (String g : user.getGroups()) {
                Node n = Node.group(g).build();
                if (type == null || type == NodeType.GROUP) result.add(n);
            }
            return Collections.unmodifiableList(result);
        }

        @Override
        public CachedData getCachedData(UUID uuid) {
            return getCachedData(uuid, ContextSet.global());
        }

        @Override
        public CachedData getCachedData(UUID uuid, ContextSet context) {
            User user = p.getUserManager().getUser(uuid);
            if (user == null) return null;
            Map<String,Boolean> resolved = p.getInheritanceGraph().resolveUser(user);
            Map<String,String>  sources  = new LinkedHashMap<>();
            // Fill sources from group resolution
            for (String g : user.getGroups()) {
                p.getInheritanceGraph().resolveGroup(g).forEach((perm, v) ->
                        sources.putIfAbsent(perm, "group:" + g));
            }
            user.getPermissions().forEach(raw -> {
                String clean = raw.startsWith("-") ? raw.substring(1) : raw;
                sources.put(clean, "personal");
            });
            // Prefix/suffix stacks
            NavigableMap<Integer,String> prefixStack = new TreeMap<>(Collections.reverseOrder());
            NavigableMap<Integer,String> suffixStack = new TreeMap<>(Collections.reverseOrder());
            if (user.getPrefix() != null && !user.getPrefix().isEmpty())
                prefixStack.put(100, user.getPrefix());
            for (String g : user.getGroups()) {
                Group grp = p.getGroupManager().getGroup(g);
                if (grp != null) {
                    if (grp.getPrefix() != null && !grp.getPrefix().isEmpty())
                        prefixStack.putIfAbsent(grp.getWeight(), grp.getPrefix());
                    if (grp.getSuffix() != null && !grp.getSuffix().isEmpty())
                        suffixStack.putIfAbsent(grp.getWeight(), grp.getSuffix());
                }
            }
            // Timed nodes
            List<Node> timedNodes = new ArrayList<>();
            p.getTimedPermissionManager().getTimedPermissions(uuid.toString())
                    .forEach(tp -> timedNodes.add(
                            Node.permission(tp.getPermission())
                                    .expiryEpochSeconds(tp.getExpiry().getEpochSecond()).build()));
            p.getTimedGroupManager().getTimedGroups(uuid.toString())
                    .forEach(tg -> timedNodes.add(
                            Node.group(tg.getGroupName())
                                    .expiryEpochSeconds(tg.getExpiry().getEpochSecond()).build()));

            return new CachedData(resolved, sources, timedNodes,
                    new LinkedHashMap<>(user.getMeta()), prefixStack, suffixStack);
        }

        // ── Convenience ───────────────────────────────────────────────────────
        @Override public void addPermission(UUID u, String perm)    { p.getUserManager().addPermission(u, perm); }
        @Override public void removePermission(UUID u, String perm) { p.getUserManager().removePermission(u, perm); }
        @Override public void addToGroup(UUID u, String g)          { p.getUserManager().addToGroup(u, g); }
        @Override public void removeFromGroup(UUID u, String g)     { p.getUserManager().removeFromGroup(u, g); }
        @Override public void addTimedPermission(UUID u, String perm, Duration d) {
            p.getTimedPermissionManager().addTimedPermission(u.toString(), false, perm, d.getSeconds());
        }
        @Override public void addTimedGroup(UUID u, String g, Duration d) {
            p.getTimedGroupManager().addTimedGroup(u.toString(), g, d.getSeconds());
        }
        @Override public void setPrefix(UUID u, String prefix) { p.getUserManager().setPrefix(u, prefix); }
        @Override public void setSuffix(UUID u, String suf)    { p.getUserManager().setSuffix(u, suf); }
        @Override public void setMeta(UUID u, String k, String v) { p.getUserManager().setMeta(u, k, v); }
        @Override public void clearUser(UUID u) { User user = p.getUserManager().getUser(u);
            if (user != null) p.getUserManager().clearUser(u, user.getUsername()); }
        @Override public boolean hasPermission(UUID u, String perm) { return p.getUserManager().hasPermissionAsync(u, perm); }
        @Override public Tristate checkPermission(UUID u, String perm) {
            CachedData d = getCachedData(u); if (d == null) return Tristate.UNDEFINED;
            return d.checkPermission(perm);
        }
        @Override public Tristate checkPermission(UUID u, String perm, ContextSet ctx) {
            CachedData d = getCachedData(u, ctx); if (d == null) return Tristate.UNDEFINED;
            return d.checkPermission(perm);
        }
        @Override public String getPrimaryGroup(UUID u) {
            User user = p.getUserManager().getUser(u);
            return user != null ? user.getPrimaryGroup() : "default";
        }
        @Override public String getPrefix(UUID u) {
            User user = p.getUserManager().getUser(u); return user != null ? user.getPrefix() : "";
        }
        @Override public String getSuffix(UUID u) {
            User user = p.getUserManager().getUser(u); return user != null ? user.getSuffix() : "";
        }
        @Override public Optional<String> getMeta(UUID u, String key) {
            User user = p.getUserManager().getUser(u);
            return user != null ? Optional.ofNullable(user.getMeta().get(key)) : Optional.empty();
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Group Manager Impl
    // ═════════════════════════════════════════════════════════════════════════

    private static class PCGroupManagerImpl implements PCGroupManager {
        private final PermsCraft p;
        PCGroupManagerImpl(PermsCraft p) { this.p = p; }

        @Override public Optional<Group>    getGroup(String n) { return Optional.ofNullable(p.getGroupManager().getGroup(n)); }
        @Override public Collection<Group>  getAllGroups()      { return p.getGroupManager().getAllGroups(); }
        @Override public boolean            groupExists(String n) { return p.getGroupManager().groupExists(n); }
        @Override public Group              createGroup(String n) { return p.getGroupManager().createGroup(n); }
        @Override public void               deleteGroup(String n) { p.getGroupManager().deleteGroup(n); }

        @Override public void addNode(String g, Node node) {
            p.getGroupManager().addPermission(g, node.toStorageString());
            EventBus.fireNodeAdd(p, PermsCraftEvent.TargetType.GROUP, null, g, node, "API");
        }
        @Override public boolean removeNode(String g, Node node) {
            p.getGroupManager().removePermission(g, node.getPermission());
            EventBus.fireNodeRemove(p, PermsCraftEvent.TargetType.GROUP, null, g, node, "API");
            return true;
        }
        @Override public List<Node> getNodes(String g) { return getNodes(g, null); }
        @Override public List<Node> getNodes(String g, NodeType type) {
            Group grp = p.getGroupManager().getGroup(g); if (grp == null) return List.of();
            return grp.getPermissions().stream()
                    .map(EventBus::nodeFromString)
                    .filter(n -> type == null || n.getType() == type)
                    .toList();
        }
        @Override public CachedData getCachedData(String g) {
            Map<String,Boolean> resolved = p.getInheritanceGraph().resolveGroup(g);
            Group grp = p.getGroupManager().getGroup(g);
            NavigableMap<Integer,String> px = new TreeMap<>(Collections.reverseOrder());
            NavigableMap<Integer,String> sx = new TreeMap<>(Collections.reverseOrder());
            if (grp != null) {
                if (grp.getPrefix() != null) px.put(grp.getWeight(), grp.getPrefix());
                if (grp.getSuffix() != null) sx.put(grp.getWeight(), grp.getSuffix());
            }
            return new CachedData(resolved, new LinkedHashMap<>(), List.of(),
                    grp != null ? new LinkedHashMap<>(grp.getMeta()) : Map.of(), px, sx);
        }
        @Override public void addPermission(String g, String p2)    { p.getGroupManager().addPermission(g, p2); }
        @Override public void removePermission(String g, String p2) { p.getGroupManager().removePermission(g, p2); }
        @Override public void addParent(String g, String par)       { p.getGroupManager().addInheritance(g, par); }
        @Override public void removeParent(String g, String par)    { p.getGroupManager().removeInheritance(g, par); }
        @Override public String getPrefix(String g)   { Group grp = p.getGroupManager().getGroup(g); return grp != null ? grp.getPrefix() : ""; }
        @Override public String getSuffix(String g)   { Group grp = p.getGroupManager().getGroup(g); return grp != null ? grp.getSuffix() : ""; }
        @Override public int    getWeight(String g)   { Group grp = p.getGroupManager().getGroup(g); return grp != null ? grp.getWeight() : 0; }
        @Override public void   setWeight(String g, int w) { p.getGroupManager().setWeight(g, w); }
        @Override public List<String> getInheritanceChain(String g) { return p.getInheritanceGraph().getInheritanceChain(g); }
        @Override public boolean inheritsFrom(String a, String b) { return getInheritanceChain(a).contains(b.toLowerCase()); }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Track Manager Impl
    // ═════════════════════════════════════════════════════════════════════════

    private static class PCTrackManagerImpl implements PCTrackManager {
        private final PermsCraft p;
        PCTrackManagerImpl(PermsCraft p) { this.p = p; }
        @Override public Optional<Track>    getTrack(String n)    { return Optional.ofNullable(p.getTrackManager().getTrack(n)); }
        @Override public Collection<Track>  getAllTracks()         { return p.getTrackManager().getAllTracks(); }
        @Override public boolean            trackExists(String n)  { return p.getTrackManager().trackExists(n); }
        @Override public Optional<String> promote(UUID u, String t, String actor) {
            String result = p.getTrackManager().promote(u, t);
            return Optional.ofNullable(result);
        }
        @Override public Optional<String> demote(UUID u, String t, String actor) {
            String result = p.getTrackManager().demote(u, t);
            return Optional.ofNullable(result);
        }
        @Override public Optional<String> getNextGroup(UUID u, String t) {
            Track track = p.getTrackManager().getTrack(t); if (track == null) return Optional.empty();
            User user = p.getUserManager().getUser(u); if (user == null) return Optional.empty();
            List<String> groups = track.getGroups();
            for (String g : user.getGroups()) {
                int idx = groups.indexOf(g);
                if (idx >= 0 && idx + 1 < groups.size()) return Optional.of(groups.get(idx + 1));
            }
            return groups.isEmpty() ? Optional.empty() : Optional.of(groups.get(0));
        }
        @Override public Optional<String> getPreviousGroup(UUID u, String t) {
            Track track = p.getTrackManager().getTrack(t); if (track == null) return Optional.empty();
            User user = p.getUserManager().getUser(u); if (user == null) return Optional.empty();
            List<String> groups = track.getGroups();
            for (String g : user.getGroups()) {
                int idx = groups.indexOf(g);
                if (idx > 0) return Optional.of(groups.get(idx - 1));
            }
            return Optional.empty();
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Context Manager Impl
    // ═════════════════════════════════════════════════════════════════════════

    private static class PCContextManagerImpl implements PCContextManager {
        private final PermsCraft p;
        PCContextManagerImpl(PermsCraft p) { this.p = p; }
        @Override public ContextSet getActiveContext(org.bukkit.entity.Player player) {
            return p.getContextManager().getActiveContextSet(player);
        }
        @Override public void registerCalculator(ir.permscraft.context.ContextCalculator c) {
            p.getContextManager().registerCalculator(c);
        }
        @Override public void unregisterCalculator(ir.permscraft.context.ContextCalculator c) {
            p.getContextManager().unregisterCalculator(c);
        }
        @Override public List<ir.permscraft.context.ContextCalculator> getCalculators() {
            return p.getContextManager().getCalculators();
        }
    }
}
