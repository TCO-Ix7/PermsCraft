package ir.permscraft.calculator;

import ir.permscraft.PermsCraft;
import ir.permscraft.managers.GroupManager;
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
@DisplayName("PermissionCalculator")
class PermissionCalculatorTest {

    @Mock private PermsCraft plugin;
    @Mock private GroupManager groupManager;
    @Mock private TimedPermissionManager timedManager;

    private PermissionCalculator calculator;

    @BeforeEach
    void setUp() {
        when(plugin.getGroupManager()).thenReturn(groupManager);
        when(plugin.getTimedPermissionManager()).thenReturn(timedManager);
        when(timedManager.getActivePermissions(anyString())).thenReturn(Set.of());
        calculator = new PermissionCalculator(plugin);
    }

    @Test @DisplayName("direct personal permission — GRANTED via PERSONAL")
    void personal_grant() {
        User user = user("Steve", "essentials.fly");
        var r = calculator.calculate(user, "essentials.fly");
        assertTrue(r.finalResult);
        assertEquals(PermissionCalculator.Source.PERSONAL, r.finalSource);
    }

    @Test @DisplayName("direct personal negation — DENIED via PERSONAL")
    void personal_deny() {
        User user = user("Steve", "-essentials.fly");
        var r = calculator.calculate(user, "essentials.fly");
        assertFalse(r.finalResult);
        assertEquals(PermissionCalculator.Source.PERSONAL, r.finalSource);
    }

    @Test @DisplayName("permission not found anywhere — false + NONE source")
    void notFound() {
        User user = new User(UUID.randomUUID(), "Ghost");
        var r = calculator.calculate(user, "some.missing.perm");
        assertFalse(r.finalResult);
        assertEquals(PermissionCalculator.Source.NONE, r.finalSource);
    }

    @Test @DisplayName("steps list is non-empty after calculation")
    void steps_nonEmpty() {
        User user = user("Steve", "essentials.fly");
        var r = calculator.calculate(user, "essentials.fly");
        assertFalse(r.steps.isEmpty());
    }

    @Test @DisplayName("personal grant produces a step with PERSONAL source")
    void steps_personalStep() {
        User user = user("Steve", "essentials.fly");
        var r = calculator.calculate(user, "essentials.fly");
        assertTrue(r.steps.stream()
                .anyMatch(s -> s.source() == PermissionCalculator.Source.PERSONAL));
    }

    @Test @DisplayName("active timed permission — GRANTED via TIMED")
    void timed_grant() {
        User user = new User(UUID.randomUUID(), "Alex");
        when(timedManager.getActivePermissions(user.getUuid().toString()))
                .thenReturn(Set.of("essentials.fly"));
        var r = calculator.calculate(user, "essentials.fly");
        assertTrue(r.finalResult);
        assertEquals(PermissionCalculator.Source.TIMED, r.finalSource);
    }

    @Test @DisplayName("timed negation — DENIED")
    void timed_deny() {
        User user = new User(UUID.randomUUID(), "Alex");
        when(timedManager.getActivePermissions(user.getUuid().toString()))
                .thenReturn(Set.of("-essentials.fly"));
        var r = calculator.calculate(user, "essentials.fly");
        assertFalse(r.finalResult);
    }

    @Test @DisplayName("group permission — GRANTED via GROUP")
    void group_grant() {
        Group vip = group("vip", 10, "essentials.fly");
        when(groupManager.getGroup("vip")).thenReturn(vip);
        User user = new User(UUID.randomUUID(), "Steve");
        user.addGroup("vip");
        var r = calculator.calculate(user, "essentials.fly");
        assertTrue(r.finalResult);
        assertEquals(PermissionCalculator.Source.GROUP, r.finalSource);
    }

    @Test @DisplayName("group negation — DENIED via GROUP")
    void group_deny() {
        Group muted = group("muted", 5, "-essentials.chat");
        when(groupManager.getGroup("muted")).thenReturn(muted);
        User user = new User(UUID.randomUUID(), "Steve");
        user.addGroup("muted");
        var r = calculator.calculate(user, "essentials.chat");
        assertFalse(r.finalResult);
    }

    @Test @DisplayName("higher weight group wins over lower weight group")
    void group_weightPriority() {
        Group low  = group("default", 0,  "-essentials.fly");
        Group high = group("vip",    100,  "essentials.fly");
        when(groupManager.getGroup("default")).thenReturn(low);
        when(groupManager.getGroup("vip")).thenReturn(high);
        User user = new User(UUID.randomUUID(), "Steve");
        user.addGroup("default");
        user.addGroup("vip");
        var r = calculator.calculate(user, "essentials.fly");
        assertTrue(r.finalResult);
    }

    @Test @DisplayName("inherited group permission — GRANTED")
    void inherited_grant() {
        Group parent = group("default", 0, "essentials.help");
        Group child  = new Group("vip");
        child.setWeight(10);
        child.addInheritance("default");
        when(groupManager.getGroup("default")).thenReturn(parent);
        when(groupManager.getGroup("vip")).thenReturn(child);
        User user = new User(UUID.randomUUID(), "Steve");
        user.addGroup("vip");
        var r = calculator.calculate(user, "essentials.help");
        assertTrue(r.finalResult);
    }

    private User user(String name, String... perms) {
        User u = new User(UUID.randomUUID(), name);
        for (String p : perms) u.addPermission(p);
        return u;
    }

    private Group group(String name, int weight, String... perms) {
        Group g = new Group(name);
        g.setWeight(weight);
        for (String p : perms) g.addPermission(p);
        return g;
    }
}
