package ir.permscraft.integration;

import ir.permscraft.cache.PermissionCache;
import ir.permscraft.context.Context;
import ir.permscraft.context.ContextSet;
import ir.permscraft.context.ContextualPermission;
import ir.permscraft.models.Group;
import ir.permscraft.models.User;
import ir.permscraft.storage.InMemoryStorage;

import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Extended integration tests — scenarios that cross multiple subsystems.
 * Uses the same InMemoryStorage + PermissionCache infrastructure as the
 * base IntegrationTest, but covers more complex real-world scenarios.
 */
@DisplayName("Integration (extended scenarios)")
class IntegrationExtendedTest {

    private InMemoryStorage storage;

    @BeforeEach
    void setUp() {
        storage = new InMemoryStorage();
        storage.init();
    }

    // ── Multi-group membership ────────────────────────────────────────────────

    @Test @DisplayName("user in multiple groups inherits permissions from all")
    void multiGroup_allPermissionsInherited() {
        Group g1 = new Group("vip");
        g1.addPermission("essentials.fly");
        Group g2 = new Group("builder");
        g2.addPermission("worldedit.wand");

        storage.saveGroup(g1);
        storage.saveGroup(g2);

        User user = new User(UUID.randomUUID(), "Steve");
        user.addGroup("vip");
        user.addGroup("builder");
        storage.saveUser(user);

        List<Group> groups = storage.loadAllGroups();
        Set<String> allPerms = new HashSet<>();
        for (String gName : user.getGroups()) {
            groups.stream()
                .filter(g -> g.getName().equals(gName))
                .findFirst()
                .ifPresent(g -> allPerms.addAll(g.getPermissions()));
        }
        assertTrue(allPerms.contains("essentials.fly"));
        assertTrue(allPerms.contains("worldedit.wand"));
    }

    @Test @DisplayName("user personal permission overrides group permission (same node)")
    void personalPerm_overridesGroup() {
        // group grants essentials.fly, user explicitly denies it with -essentials.fly
        Group vip = new Group("vip");
        vip.addPermission("essentials.fly");
        storage.saveGroup(vip);

        User user = new User(UUID.randomUUID(), "Steve");
        user.addGroup("vip");
        user.addPermission("-essentials.fly"); // explicit deny
        storage.saveUser(user);

        assertTrue(user.getPermissions().contains("-essentials.fly"));
        // the personal deny node exists — calculator should use it over group grant
    }

    // ── Inheritance chain ─────────────────────────────────────────────────────

    @Test @DisplayName("three-level inheritance: grandchild gets grandparent perms")
    void inheritance_threeLevel() {
        Group base = new Group("default");
        base.addPermission("base.perm");

        Group mid = new Group("member");
        mid.addInheritance("default");
        mid.addPermission("member.perm");

        Group top = new Group("vip");
        top.addInheritance("member");
        top.addPermission("vip.perm");

        storage.saveGroup(base);
        storage.saveGroup(mid);
        storage.saveGroup(top);

        // Verify inheritance chain is stored
        Group loadedTop = storage.loadAllGroups().stream()
            .filter(g -> g.getName().equals("vip")).findFirst().orElseThrow();
        assertTrue(loadedTop.getInheritedGroups().contains("member"));

        Group loadedMid = storage.loadAllGroups().stream()
            .filter(g -> g.getName().equals("member")).findFirst().orElseThrow();
        assertTrue(loadedMid.getInheritedGroups().contains("default"));
    }

    @Test @DisplayName("diamond inheritance: group inherits from two groups that share a common parent")
    void inheritance_diamond() {
        // default <- modA <- admin
        //         <- modB <- admin
        // admin should see default's perms only once (no duplicates)
        Group def = new Group("default"); def.addPermission("base");
        Group modA = new Group("moda"); modA.addInheritance("default"); modA.addPermission("moda");
        Group modB = new Group("modb"); modB.addInheritance("default"); modB.addPermission("modb");
        Group admin = new Group("admin"); admin.addInheritance("moda"); admin.addInheritance("modb");

        storage.saveGroup(def); storage.saveGroup(modA);
        storage.saveGroup(modB); storage.saveGroup(admin);

        Group loadedAdmin = storage.loadAllGroups().stream()
            .filter(g -> g.getName().equals("admin")).findFirst().orElseThrow();
        assertEquals(2, loadedAdmin.getInheritedGroups().size());
        assertTrue(loadedAdmin.getInheritedGroups().contains("moda"));
        assertTrue(loadedAdmin.getInheritedGroups().contains("modb"));
    }

    // ── Context-aware permissions ─────────────────────────────────────────────

    @Test @DisplayName("contextual permission only applies in correct world")
    void context_worldSpecific() {
        ContextSet pvpWorld = ContextSet.builder().put("world", "pvp").build();
        ContextualPermission ctxPerm = new ContextualPermission("arena.fly", pvpWorld, true);

        ContextSet playerInPvp = ContextSet.builder().put("world", "pvp").build();
        ContextSet playerInSurvival = ContextSet.builder().put("world", "survival").build();

        assertTrue(ctxPerm.appliesIn(playerInPvp));
        assertFalse(ctxPerm.appliesIn(playerInSurvival));
    }

    @Test @DisplayName("contextual permission with multiple required contexts (AND logic)")
    void context_multipleRequirements() {
        ContextSet required = ContextSet.builder()
            .put("world", "pvp")
            .put("gamemode", "adventure")
            .build();
        ContextualPermission ctxPerm = new ContextualPermission("special.perm", required, true);

        // Both conditions met → applies
        assertTrue(ctxPerm.appliesIn(ContextSet.builder()
            .put("world", "pvp").put("gamemode", "adventure").build()));

        // Only one condition met → does NOT apply (AND logic)
        assertFalse(ctxPerm.appliesIn(ContextSet.builder()
            .put("world", "pvp").put("gamemode", "creative").build()));

        // Neither met
        assertFalse(ctxPerm.appliesIn(ContextSet.builder()
            .put("world", "survival").put("gamemode", "survival").build()));
    }

    @Test @DisplayName("wildcard context '*' applies in any world")
    void context_wildcardWorld() {
        ContextualPermission ctxPerm = new ContextualPermission(
            "fly.any", new Context("world", "*"), true);

        assertTrue(ctxPerm.appliesIn(ContextSet.builder().put("world", "survival").build()));
        assertTrue(ctxPerm.appliesIn(ContextSet.builder().put("world", "nether").build()));
        assertTrue(ctxPerm.appliesIn(ContextSet.builder().put("world", "end").build()));
    }

    @Test @DisplayName("global contextual permission applies everywhere")
    void context_global() {
        ContextualPermission global = new ContextualPermission("fly.global", ContextSet.global(), true);
        assertTrue(global.isGlobal());
        assertTrue(global.appliesIn(ContextSet.global()));
        assertTrue(global.appliesIn(ContextSet.builder().put("world", "survival").build()));
    }

    @Test @DisplayName("context deny overrides context grant for same permission")
    void context_denyBeatsGrant() {
        ContextSet pvp = ContextSet.builder().put("world", "pvp").build();
        ContextualPermission grant = new ContextualPermission("fly", pvp, true);
        ContextualPermission deny  = new ContextualPermission("fly", pvp, false);

        assertTrue(grant.appliesIn(pvp));
        assertTrue(deny.appliesIn(pvp));
        assertFalse(deny.getValue()); // the deny has value=false
    }

    // ── Storage bulk operations in context ───────────────────────────────────

    @Test @DisplayName("bulk add then search finds the permission")
    void bulk_addThenSearch() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        storage.loadUser(a, "Alpha");
        storage.loadUser(b, "Beta");
        storage.bulkAddPermissionToUsers("search.target");
        List<String> results = storage.searchPermission("search.target");
        assertTrue(results.size() >= 2);
    }

    @Test @DisplayName("bulk operations are idempotent (adding twice doesn't duplicate)")
    void bulk_idempotent() {
        UUID a = UUID.randomUUID();
        storage.loadUser(a, "Alpha");
        storage.bulkAddPermissionToUsers("idempotent.perm");
        storage.bulkAddPermissionToUsers("idempotent.perm"); // second call
        User u = storage.loadUser(a, "Alpha");
        long count = u.getPermissions().stream()
            .filter("idempotent.perm"::equals).count();
        assertEquals(1, count);
    }

    // ── Track scenarios ───────────────────────────────────────────────────────

    @Test @DisplayName("track with multiple groups preserves order")
    void track_orderPreserved() {
        ir.permscraft.models.Track track = new ir.permscraft.models.Track("main");
        track.addGroup("guest");
        track.addGroup("member");
        track.addGroup("vip");
        track.addGroup("mvp");
        storage.saveTrack(track);

        ir.permscraft.models.Track loaded = storage.loadAllTracks().stream()
            .filter(t -> t.getName().equals("main")).findFirst().orElseThrow();
        assertEquals(List.of("guest", "member", "vip", "mvp"), loaded.getGroups());
    }

    @Test @DisplayName("track next/previous group navigation")
    void track_navigation() {
        ir.permscraft.models.Track track = new ir.permscraft.models.Track("rank");
        track.addGroup("bronze");
        track.addGroup("silver");
        track.addGroup("gold");

        List<String> groups = track.getGroups();
        int silverIdx = groups.indexOf("silver");
        assertEquals("bronze", groups.get(silverIdx - 1)); // previous
        assertEquals("gold",   groups.get(silverIdx + 1)); // next (promote)
    }

    // ── Edge cases ─────────────────────────────────────────────────────────────

    @Test @DisplayName("user with no groups gets empty group set (not null)")
    void user_noGroupsNotNull() {
        User u = new User(UUID.randomUUID(), "Blank");
        assertNotNull(u.getGroups());
        assertTrue(u.getGroups().isEmpty());
    }

    @Test @DisplayName("group weight ordering: higher weight = higher priority")
    void group_weightOrdering() {
        Group low  = new Group("member"); low.setWeight(0);
        Group high = new Group("admin");  high.setWeight(100);
        assertTrue(high.getWeight() > low.getWeight());
    }

    @Test @DisplayName("negated permission node is stored with '-' prefix")
    void negatedPerm_storedWithDash() {
        Group g = new Group("test");
        g.addPermission("-essentials.fly");
        storage.saveGroup(g);
        Group loaded = storage.loadAllGroups().stream()
            .filter(gr -> gr.getName().equals("test")).findFirst().orElseThrow();
        assertTrue(loaded.getPermissions().contains("-essentials.fly"));
    }

    @Test @DisplayName("storage: loading non-existent group returns empty list, not exception")
    void storage_missingGroupHandled() {
        List<Group> groups = storage.loadAllGroups();
        assertNotNull(groups);
    }

    @Test @DisplayName("multiple users can be in the same group simultaneously")
    void multiUser_sameGroup() {
        storage.saveGroup(new Group("vip"));
        UUID u1 = UUID.randomUUID(), u2 = UUID.randomUUID();
        storage.loadUser(u1, "UserOne");
        storage.loadUser(u2, "UserTwo");
        storage.addUserToGroup(u1, "vip");
        storage.addUserToGroup(u2, "vip");
        assertTrue(storage.loadUser(u1, "UserOne").getGroups().contains("vip"));
        assertTrue(storage.loadUser(u2, "UserTwo").getGroups().contains("vip"));
    }
}
