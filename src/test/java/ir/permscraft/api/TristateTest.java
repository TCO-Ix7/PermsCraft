package ir.permscraft.api;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Tristate")
class TristateTest {

    // ── of(Boolean) ──────────────────────────────────────────────────────────

    @Test @DisplayName("of(true) returns TRUE")
    void ofBoxed_true() { assertEquals(Tristate.TRUE, Tristate.of(Boolean.TRUE)); }

    @Test @DisplayName("of(false) returns FALSE")
    void ofBoxed_false() { assertEquals(Tristate.FALSE, Tristate.of(Boolean.FALSE)); }

    @Test @DisplayName("of(null) returns UNDEFINED")
    void ofBoxed_null() { assertEquals(Tristate.UNDEFINED, Tristate.of((Boolean) null)); }

    // ── of(boolean) ──────────────────────────────────────────────────────────

    @Test @DisplayName("of(primitive true) returns TRUE")
    void ofPrimitive_true() { assertEquals(Tristate.TRUE, Tristate.of(true)); }

    @Test @DisplayName("of(primitive false) returns FALSE")
    void ofPrimitive_false() { assertEquals(Tristate.FALSE, Tristate.of(false)); }

    @Test @DisplayName("of(primitive) never returns UNDEFINED")
    void ofPrimitive_neverUndefined() {
        assertNotEquals(Tristate.UNDEFINED, Tristate.of(true));
        assertNotEquals(Tristate.UNDEFINED, Tristate.of(false));
    }

    // ── asBoolean(boolean def) ───────────────────────────────────────────────

    @Test @DisplayName("TRUE.asBoolean(def) always returns true")
    void asBoolean_withDef_true() {
        assertTrue(Tristate.TRUE.asBoolean(false));
        assertTrue(Tristate.TRUE.asBoolean(true));
    }

    @Test @DisplayName("FALSE.asBoolean(def) always returns false")
    void asBoolean_withDef_false() {
        assertFalse(Tristate.FALSE.asBoolean(true));
        assertFalse(Tristate.FALSE.asBoolean(false));
    }

    @Test @DisplayName("UNDEFINED.asBoolean(true) returns true")
    void asBoolean_withDef_undefined_true() {
        assertTrue(Tristate.UNDEFINED.asBoolean(true));
    }

    @Test @DisplayName("UNDEFINED.asBoolean(false) returns false")
    void asBoolean_withDef_undefined_false() {
        assertFalse(Tristate.UNDEFINED.asBoolean(false));
    }

    // ── asBoolean() (no-arg, default=false) ──────────────────────────────────

    @Test @DisplayName("TRUE.asBoolean() returns true")
    void asBoolean_noArg_true() { assertTrue(Tristate.TRUE.asBoolean()); }

    @Test @DisplayName("FALSE.asBoolean() returns false")
    void asBoolean_noArg_false() { assertFalse(Tristate.FALSE.asBoolean()); }

    @Test @DisplayName("UNDEFINED.asBoolean() returns false (default)")
    void asBoolean_noArg_undefined() { assertFalse(Tristate.UNDEFINED.asBoolean()); }

    // ── isDefined() ───────────────────────────────────────────────────────────

    @Test @DisplayName("TRUE.isDefined() is true")
    void isDefined_true() { assertTrue(Tristate.TRUE.isDefined()); }

    @Test @DisplayName("FALSE.isDefined() is true")
    void isDefined_false() { assertTrue(Tristate.FALSE.isDefined()); }

    @Test @DisplayName("UNDEFINED.isDefined() is false")
    void isDefined_undefined() { assertFalse(Tristate.UNDEFINED.isDefined()); }

    // ── enum identity ─────────────────────────────────────────────────────────

    @Test @DisplayName("exactly three values exist")
    void enumValues_count() {
        assertEquals(3, Tristate.values().length);
    }

    @Test @DisplayName("values() contains TRUE, FALSE, UNDEFINED in order")
    void enumValues_order() {
        Tristate[] vals = Tristate.values();
        assertEquals(Tristate.TRUE,      vals[0]);
        assertEquals(Tristate.FALSE,     vals[1]);
        assertEquals(Tristate.UNDEFINED, vals[2]);
    }

    @Test @DisplayName("valueOf round-trips correctly")
    void valueOf_roundTrip() {
        assertEquals(Tristate.TRUE,      Tristate.valueOf("TRUE"));
        assertEquals(Tristate.FALSE,     Tristate.valueOf("FALSE"));
        assertEquals(Tristate.UNDEFINED, Tristate.valueOf("UNDEFINED"));
    }

    // ── symmetry sanity ───────────────────────────────────────────────────────

    @Test @DisplayName("of(true) then asBoolean() returns true")
    void symmetry_true() { assertTrue(Tristate.of(true).asBoolean()); }

    @Test @DisplayName("of(false) then asBoolean() returns false")
    void symmetry_false() { assertFalse(Tristate.of(false).asBoolean()); }

    @Test @DisplayName("of(null) then isDefined() returns false")
    void symmetry_null() { assertFalse(Tristate.of((Boolean) null).isDefined()); }
}
