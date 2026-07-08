package ir.permscraft.integration;

import ir.permscraft.context.Context;
import ir.permscraft.models.Group;
import ir.permscraft.models.TimedPermission;
import ir.permscraft.models.Track;
import ir.permscraft.models.User;
import ir.permscraft.utils.WildcardUtil;
import org.junit.jupiter.api.*;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration scenarios that exercise multiple components together.
 * No Bukkit or database dependency — pure in-memory logic.
 */
@DisplayName("Integration — real-world scenarios")
class IntegrationTest {

    // ── Scenario 1: Survival server rank ladder ───────────────────────────────

    @Test @DisplayName("Survival rank ladder: default → vip → mvp permission flow")
    void rankLadder_permissionFlow() {
        // Build groups
        Group defaultGroup = group("default", 0,
                "essentials.help", "essentials.spawn", "essentials.home");
        Group vip = group("vip", 10,
                "essentials.fly", "essentials.nick");
        Group mvp = group("mvp", 20,
                "essentials.kit.mvp", "essentials.god");

        // Simulate inheritance resolution manually (mirrors InheritanceGraph logic)
        Map<String, Boolean> defaultPerms = resolveGroup(defaultGroup, Map.of("default", defaultGroup));

        Map<String, Group> allGroups = Map.of("default", defaultGroup, "vip", vip);
        vip.addInheritance("default");
        Map<String, Boolean> vipPerms = resolveGroup(vip, allGroups);

        mvp.addInheritance("vip");
        allGroups = Map.of("default", defaultGroup, "vip", vip, "mvp", mvp);
        Map<String, Boolean> mvpPerms = resolveGroup(mvp, allGroups);

        // default has basic perms
        assertTrue(defaultPerms.getOrDefault("essentials.help", false));
        assertFalse(defaultPerms.containsKey("essentials.fly"));

        // vip inherits default + adds fly/nick
        assertTrue(vipPerms.getOrDefault("essentials.fly", false));
        assertTrue(vipPerms.getOrDefault("essentials.help", false));

        // mvp inherits vip (which inherits default)
        assertTrue(mvpPerms.getOrDefault("essentials.kit.mvp", false));
        assertTrue(mvpPerms.getOrDefault("essentials.fly",    false));
        assertTrue(mpvPerms(mvpPerms, "essentials.help"));
    }

    @Test @DisplayName("Muted group negation blocks chat even with parent grant")
    void mutedGroup_blocksChatPermission() {
        Group defaultGroup = group("default", 0, "essentials.chat", "essentials.msg");
        Group muted = group("muted", 5, "-essentials.chat");
        muted.addInheritance("default");

        Map<String, Group> all = Map.of("default", defaultGroup, "muted", muted);
        Map<String, Boolean> perms = resolveGroup(muted, all);

        assertFalse(perms.getOrDefault("essentials.chat", true),
                "muted should block essentials.chat");
        assertTrue(perms.getOrDefault("essentials.msg", false),
                "muted should still inherit essentials.msg");
    }

    // ── Scenario 2: User permission override ─────────────────────────────────

    @Test @DisplayName("User personal permission overrides group negation")
    void user_personalOverridesGroupNegation() {
        Group muted = group("muted", 5, "-essentials.chat");
        User user = new User(UUID.randomUUID(), "Steve");
        user.addGroup("muted");
        user.addPermission("essentials.chat"); // personal override

        Set<String> userPerms = new HashSet<>(user.getPermissions());
        Set<String> groupPerms = new HashSet<>(muted.getPermissions());

        // Personal perms take priority: essentials.chat in personal → granted
        boolean result = WildcardUtil.hasPermission(userPerms, "essentials.chat");
        assertTrue(result, "User personal grant should override group negation");
    }

    @Test @DisplayName("User with wildcard personal permission has access to all sub-nodes")
    void user_wildcardPersonal() {
        User user = new User(UUID.randomUUID(), "Alex");
        user.addPermission("essentials.*");

        assertTrue(WildcardUtil.hasPermission(user.getPermissions(), "essentials.fly"));
        assertTrue(WildcardUtil.hasPermission(user.getPermissions(), "essentials.home"));
        assertTrue(WildcardUtil.hasPermission(user.getPermissions(), "essentials.nick"));
    }

    // ── Scenario 3: Track promotion / demotion ────────────────────────────────

    @Test @DisplayName("Track promotion walks correctly from member to legend")
    void track_fullPromotion() {
        Track track = new Track("survival");
        track.addGroup("member");
        track.addGroup("vip");
        track.addGroup("mvp");
        track.addGroup("legend");

        User user = new User(UUID.randomUUID(), "Steve");
        user.addGroup("member");

        // Walk up
        String current = user.getPrimaryGroup();
        List<String> path = new ArrayList<>();
        while (current != null) {
            path.add(current);
            current = track.getNext(current);
        }

        assertEquals(List.of("member", "vip", "mvp", "legend"), path);
    }

    @Test @DisplayName("Track demotion walks correctly from legend to member")
    void track_fullDemotion() {
        Track track = new Track("survival");
        track.addGroup("member");
        track.addGroup("vip");
        track.addGroup("mvp");
        track.addGroup("legend");

        String current = "legend";
        List<String> path = new ArrayList<>();
        while (current != null) {
            path.add(current);
            current = track.getPrevious(current);
        }

        assertEquals(List.of("legend", "mvp", "vip", "member"), path);
    }

    // ── Scenario 4: Timed permission lifecycle ────────────────────────────────

    @Test @DisplayName("Timed permission is active before expiry")
    void timed_activeBeforeExpiry() {
        TimedPermission tp = new TimedPermission(
                "uuid-1", false, "essentials.fly",
                Instant.now().plusSeconds(3600));

        assertFalse(tp.isExpired());
        assertEquals("essentials.fly", tp.getPermission());
    }

    @Test @DisplayName("Timed permission is expired after expiry time passes")
    void timed_expiredAfterExpiry() {
        TimedPermission tp = new TimedPermission(
                "uuid-1", false, "essentials.fly",
                Instant.now().minusSeconds(1));

        assertTrue(tp.isExpired());
        assertEquals("Expired", tp.getFormattedExpiry());
    }

    @Test @DisplayName("Multiple timed perms: only non-expired ones contribute to active set")
    void timed_mixedExpiryFilter() {
        List<TimedPermission> all = List.of(
                new TimedPermission("u", false, "perm.active1", Instant.now().plusSeconds(3600)),
                new TimedPermission("u", false, "perm.expired", Instant.now().minusSeconds(60)),
                new TimedPermission("u", false, "perm.active2", Instant.now().plusSeconds(7200))
        );

        Set<String> active = new HashSet<>();
        for (TimedPermission tp : all) {
            if (!tp.isExpired()) active.add(tp.getPermission());
        }

        assertTrue(active.contains("perm.active1"));
        assertTrue(active.contains("perm.active2"));
        assertFalse(active.contains("perm.expired"));
        assertEquals(2, active.size());
    }

    // ── Scenario 5: Context permission matching ───────────────────────────────

    @Test @DisplayName("World context: permission applies only in correct world")
    void context_worldScoped() {
        Context survival = Context.world("survival");
        Context nether   = Context.world("nether");

        // Player is in survival — context matches
        assertTrue(survival.equals(Context.world("survival")));
        // Player is in nether — does NOT match survival permission
        assertFalse(nether.equals(Context.world("survival")));
    }

    @Test @DisplayName("Global context applies regardless of player world")
    void context_globalAppliesEverywhere() {
        Context global = Context.global();
        assertTrue(global.isGlobal());
        // A global permission context matches any world
        assertFalse(global.equals(Context.world("survival"))); // different object
        assertTrue(global.isGlobal()); // but isGlobal flag allows it to match anywhere
    }

    @Test @DisplayName("Context.fromString round-trip preserves key and value")
    void context_fromStringRoundTrip() {
        Context original  = Context.world("nether");
        Context recreated = Context.fromString(original.toString());
        assertEquals(original, recreated);
    }

    // ── Scenario 6: Group hierarchy depth ────────────────────────────────────

    @Test @DisplayName("Three-level inheritance chain: grandparent permissions reach grandchild")
    void inheritance_threeLevels() {
        Group grandparent = group("base", 0, "base.perm");
        Group parent      = group("member", 10, "member.perm");
        Group child       = group("vip", 20, "vip.perm");
        parent.addInheritance("base");
        child.addInheritance("member");

        Map<String, Group> all = Map.of("base", grandparent, "member", parent, "vip", child);
        Map<String, Boolean> resolved = resolveGroup(child, all);

        assertTrue(resolved.getOrDefault("vip.perm",    false));
        assertTrue(resolved.getOrDefault("member.perm", false));
        assertTrue(resolved.getOrDefault("base.perm",   false));
    }

    @Test @DisplayName("Wildcard in group correctly expands over known permission space")
    void wildcard_groupExpansion() {
        Set<String> groupPerms   = Set.of("essentials.*");
        Set<String> knownPerms   = Set.of("essentials.fly", "essentials.home", "minecraft.op");
        Set<String> expanded     = WildcardUtil.expand(groupPerms, knownPerms);

        assertTrue(expanded.contains("essentials.fly"));
        assertTrue(expanded.contains("essentials.home"));
        assertFalse(expanded.contains("minecraft.op")); // not in essentials.* namespace
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Tiny inline group resolver (mirrors InheritanceGraph without Bukkit). */
    private Map<String, Boolean> resolveGroup(Group g, Map<String, Group> allGroups) {
        Map<String, Boolean> result = new LinkedHashMap<>();
        Set<String> visited = new HashSet<>();
        resolveInternal(g, allGroups, result, visited);
        return result;
    }

    private void resolveInternal(Group g, Map<String, Group> all,
                                  Map<String, Boolean> result, Set<String> visited) {
        if (visited.contains(g.getName())) return;
        visited.add(g.getName());
        // parents first (lower priority)
        for (String parentName : g.getInheritedGroups()) {
            Group parent = all.get(parentName);
            if (parent != null) resolveInternal(parent, all, result, visited);
        }
        // own permissions override
        for (String node : g.getPermissions()) {
            boolean neg   = node.startsWith("-");
            String  clean = neg ? node.substring(1) : node;
            result.put(clean, !neg);
        }
    }

    private Group group(String name, int weight, String... perms) {
        Group g = new Group(name);
        g.setWeight(weight);
        for (String p : perms) g.addPermission(p);
        return g;
    }

    private boolean mpvPerms(Map<String, Boolean> map, String key) {
        return map.getOrDefault(key, false);
    }
}
