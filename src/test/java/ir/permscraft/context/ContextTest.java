package ir.permscraft.context;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Context model")
class ContextTest {

    @Test @DisplayName("world() factory creates world context")
    void world_factory() {
        Context ctx = Context.world("survival");
        assertEquals("world", ctx.getKey());
        assertEquals("survival", ctx.getValue());
    }

    @Test @DisplayName("server() factory creates server context")
    void server_factory() {
        Context ctx = Context.server("lobby");
        assertEquals("server", ctx.getKey());
        assertEquals("lobby", ctx.getValue());
    }

    @Test @DisplayName("global() factory creates global context")
    void global_factory() {
        Context ctx = Context.global();
        assertTrue(ctx.isGlobal());
    }

    @Test @DisplayName("isGlobal returns false for non-global contexts")
    void isGlobal_false() {
        assertFalse(Context.world("nether").isGlobal());
    }

    @Test @DisplayName("key and value are stored lowercase")
    void lowercase_storage() {
        Context ctx = new Context("WORLD", "SURVIVAL");
        assertEquals("world", ctx.getKey());
        assertEquals("survival", ctx.getValue());
    }

    @Test @DisplayName("equals is symmetric and value-based")
    void equals_symmetric() {
        Context a = Context.world("survival");
        Context b = Context.world("survival");
        assertEquals(a, b);
        assertEquals(b, a);
    }

    @Test @DisplayName("equals returns false for different values")
    void equals_differentValue() {
        assertNotEquals(Context.world("survival"), Context.world("nether"));
    }

    @Test @DisplayName("equals returns false for different keys")
    void equals_differentKey() {
        assertNotEquals(Context.world("lobby"), Context.server("lobby"));
    }

    @Test @DisplayName("hashCode is consistent with equals")
    void hashCode_consistency() {
        assertEquals(
                Context.world("survival").hashCode(),
                Context.world("survival").hashCode()
        );
    }

    @Test @DisplayName("toString produces key=value format")
    void toString_format() {
        assertEquals("world=survival", Context.world("survival").toString());
    }

    @Test @DisplayName("fromString parses key=value correctly")
    void fromString_valid() {
        Context ctx = Context.fromString("world=nether");
        assertEquals("world", ctx.getKey());
        assertEquals("nether", ctx.getValue());
    }

    @Test @DisplayName("fromString with missing '=' returns global context")
    void fromString_invalid() {
        Context ctx = Context.fromString("justtext");
        assertTrue(ctx.isGlobal());
    }

    @Test @DisplayName("fromString handles value containing '='")
    void fromString_valueWithEquals() {
        Context ctx = Context.fromString("server=my=server");
        assertEquals("server", ctx.getKey());
        assertEquals("my=server", ctx.getValue());
    }
}
