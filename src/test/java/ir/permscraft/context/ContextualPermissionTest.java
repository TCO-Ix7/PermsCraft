package ir.permscraft.context;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ContextualPermission model")
class ContextualPermissionTest {

    @Test @DisplayName("granted permission stores correctly")
    void granted_stored() {
        ContextualPermission cp = new ContextualPermission(
                "essentials.fly", Context.world("survival"), true);
        assertEquals("essentials.fly", cp.getPermission());
        assertTrue(cp.getValue());
        assertEquals(Context.world("survival"), cp.getContext());
    }

    @Test @DisplayName("denied permission stores correctly")
    void denied_stored() {
        ContextualPermission cp = new ContextualPermission(
                "essentials.fly", Context.world("nether"), false);
        assertFalse(cp.getValue());
    }

    @Test @DisplayName("appliesIn returns true for exact matching context")
    void appliesIn_exactMatch() {
        ContextualPermission cp = new ContextualPermission(
                "perm", Context.world("survival"), true);
        assertTrue(cp.appliesIn(Context.world("survival")));
    }

    @Test @DisplayName("appliesIn returns false for different context value")
    void appliesIn_differentValue() {
        ContextualPermission cp = new ContextualPermission(
                "perm", Context.world("nether"), true);
        assertFalse(cp.appliesIn(Context.world("survival")));
    }

    @Test @DisplayName("appliesIn returns true when permission context is global")
    void appliesIn_globalAlwaysApplies() {
        ContextualPermission cp = new ContextualPermission(
                "perm", Context.global(), true);
        assertTrue(cp.appliesIn(Context.world("survival")));
        assertTrue(cp.appliesIn(Context.server("lobby")));
    }

    @Test @DisplayName("isGlobal returns true for global context")
    void isGlobal_true() {
        ContextualPermission cp = new ContextualPermission(
                "perm", Context.global(), true);
        assertTrue(cp.isGlobal());
    }

    @Test @DisplayName("isGlobal returns false for world context")
    void isGlobal_false() {
        ContextualPermission cp = new ContextualPermission(
                "perm", Context.world("survival"), true);
        assertFalse(cp.isGlobal());
    }

    @Test @DisplayName("toString contains permission name")
    void toString_containsPerm() {
        ContextualPermission cp = new ContextualPermission(
                "perm.x", Context.world("survival"), true);
        assertTrue(cp.toString().contains("perm.x"));
    }

    @Test @DisplayName("toString negated starts with '-'")
    void toString_negatedPrefix() {
        ContextualPermission cp = new ContextualPermission(
                "perm.x", Context.world("survival"), false);
        assertTrue(cp.toString().startsWith("-"));
    }
}
