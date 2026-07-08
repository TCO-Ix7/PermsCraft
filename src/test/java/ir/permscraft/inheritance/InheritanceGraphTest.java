package ir.permscraft.inheritance;

import ir.permscraft.PermsCraft;
import ir.permscraft.cache.PermissionCache;
import ir.permscraft.managers.GroupManager;
import ir.permscraft.managers.TimedGroupManager;
import ir.permscraft.managers.TimedPermissionManager;
import ir.permscraft.models.Group;
import ir.permscraft.models.User;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("InheritanceGraph")
class InheritanceGraphTest {

    @Mock private PermsCraft plugin;
    @Mock private GroupManager groupManager;
    @Mock private PermissionCache permissionCache;
    @Mock private TimedPermissionManager timedManager;
    @Mock private TimedGroupManager timedGroupManager;

    private InheritanceGraph graph;

    @BeforeEach
    void setUp() {
        when(plugin.getGroupManager()).thenReturn(groupManager);
        when(plugin.getPermissionCache()).thenReturn(permissionCache);
        when(plugin.getTimedPermissionManager()).thenReturn(timedManager);
        when(plugin.getTimedGroupManager()).thenReturn(timedGroupManager);
        when(timedGroupManager.getActiveGroupNames(anyString())).thenReturn(Set.of());
        when(permissionCache.getGroupPermissions(anyString())).thenReturn(null);
        when(timedManager.getActivePermissions(anyString())).thenReturn(Set.of());
        doNothing().when(permissionCache).setGroupPermissions(anyString(), any());
        graph = new InheritanceGraph(plugin);
    }

    @Test @DisplayName("single group with direct permission resolves correctly")
    void resolveGroup_direct() {
        Group admin = group("admin", 100, "server.admin");
        when(groupManager.getGroup("admin")).thenReturn(admin);
        assertTrue(graph.resolveGroup("admin").getOrDefault("server.admin", false));
    }

    @Test @DisplayName("negated permission resolves as false")
    void resolveGroup_negated() {
        Group vip = group("vip", 10, "-essentials.fly");
        when(groupManager.getGroup("vip")).thenReturn(vip);
        assertFalse(graph.resolveGroup("vip").getOrDefault("essentials.fly", true));
    }

    @Test @DisplayName("child inherits permissions from parent")
    void resolveGroup_inheritedFromParent() {
        Group parent = group("default", 0, "essentials.help");
        Group child  = group("vip", 10, "essentials.fly");
        child.addInheritance("default");
        when(groupManager.getGroup("default")).thenReturn(parent);
        when(groupManager.getGroup("vip")).thenReturn(child);
        Map<String, Boolean> r = graph.resolveGroup("vip");
        assertTrue(r.getOrDefault("essentials.fly",  false));
        assertTrue(r.getOrDefault("essentials.help", false));
    }

    @Test @DisplayName("child negation overrides parent grant")
    void resolveGroup_childOverridesParent() {
        Group parent = group("default", 0, "essentials.fly");
        Group child  = group("muted", 10, "-essentials.fly");
        child.addInheritance("default");
        when(groupManager.getGroup("default")).thenReturn(parent);
        when(groupManager.getGroup("muted")).thenReturn(child);
        assertFalse(graph.resolveGroup("muted").getOrDefault("essentials.fly", true));
    }

    @Test @DisplayName("unknown group returns empty map")
    void resolveGroup_unknownGroup() {
        when(groupManager.getGroup("ghost")).thenReturn(null);
        assertTrue(graph.resolveGroup("ghost").isEmpty());
    }

    @Test @DisplayName("cycle in inheritance does not cause infinite loop")
    void resolveGroup_cycle() {
        Group a = group("a", 0, "perm.a");
        Group b = group("b", 0, "perm.b");
        a.addInheritance("b");
        b.addInheritance("a");
        when(groupManager.getGroup("a")).thenReturn(a);
        when(groupManager.getGroup("b")).thenReturn(b);
        assertDoesNotThrow(() -> graph.resolveGroup("a"));
    }

    @Test @DisplayName("resolveUser includes personal permissions")
    void resolveUser_personalPerms() {
        User user = new User(UUID.randomUUID(), "Steve");
        user.addPermission("my.personal.perm");
        assertTrue(graph.resolveUser(user).getOrDefault("my.personal.perm", false));
    }

    @Test @DisplayName("personal permission overrides group negation")
    void resolveUser_personalOverridesGroup() {
        Group staff = group("staff", 50, "-essentials.fly");
        when(groupManager.getGroup("staff")).thenReturn(staff);
        User user = new User(UUID.randomUUID(), "Steve");
        user.addGroup("staff");
        user.addPermission("essentials.fly");
        assertTrue(graph.resolveUser(user).getOrDefault("essentials.fly", false));
    }

    @Test @DisplayName("hasPermission true for direct permission")
    void hasPermission_direct() {
        User user = new User(UUID.randomUUID(), "Alex");
        user.addPermission("essentials.fly");
        assertTrue(graph.hasPermission(user, "essentials.fly"));
    }

    @Test @DisplayName("hasPermission false for absent permission")
    void hasPermission_absent() {
        User user = new User(UUID.randomUUID(), "Alex");
        assertFalse(graph.hasPermission(user, "essentials.fly"));
    }

    @Test @DisplayName("getInheritanceChain returns ordered parent chain")
    void inheritanceChain() {
        Group base   = group("base",   0,  "p");
        Group member = group("member", 10, "p");
        Group vip    = group("vip",    20, "p");
        member.addInheritance("base");
        vip.addInheritance("member");
        when(groupManager.getGroup("base")).thenReturn(base);
        when(groupManager.getGroup("member")).thenReturn(member);
        when(groupManager.getGroup("vip")).thenReturn(vip);
        List<String> chain = graph.getInheritanceChain("vip");
        assertTrue(chain.contains("base"));
        assertTrue(chain.contains("member"));
        assertTrue(chain.indexOf("base") < chain.indexOf("member"));
    }

    @Test @DisplayName("getInheritanceChain empty for group with no parents")
    void inheritanceChain_noParents() {
        Group solo = group("solo", 0, "p");
        when(groupManager.getGroup("solo")).thenReturn(solo);
        assertTrue(graph.getInheritanceChain("solo").isEmpty());
    }

    private Group group(String name, int weight, String... perms) {
        Group g = new Group(name);
        g.setWeight(weight);
        for (String p : perms) g.addPermission(p);
        return g;
    }
}
