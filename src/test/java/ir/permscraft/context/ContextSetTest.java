package ir.permscraft.context;

import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Context + ContextSet")
class ContextSetTest {

    // ═══════════════════════════════════════════════════════════════
    // Context
    // ═══════════════════════════════════════════════════════════════

    @Nested @DisplayName("Context")
    class ContextTests {

        @Test @DisplayName("constructor normalises key and value to lowercase")
        void constructor_lowercases() {
            Context c = new Context("WORLD", "Survival");
            assertEquals("world", c.getKey());
            assertEquals("survival", c.getValue());
        }

        @Test @DisplayName("constructor trims whitespace")
        void constructor_trims() {
            Context c = new Context("  world  ", "  survival  ");
            assertEquals("world", c.getKey());
            assertEquals("survival", c.getValue());
        }

        @Test @DisplayName("factory helpers produce correct keys")
        void factory_helpers() {
            assertEquals(Context.KEY_WORLD,      Context.world("x").getKey());
            assertEquals(Context.KEY_SERVER,     Context.server("x").getKey());
            assertEquals(Context.KEY_GAMEMODE,   Context.gamemode("x").getKey());
            assertEquals(Context.KEY_DIMENSION,  Context.dimension("x").getKey());
            assertEquals(Context.KEY_WORLD_UUID, Context.worldUuid("x").getKey());
        }

        @Test @DisplayName("isGlobal: true only when both key and value are 'global'")
        void isGlobal() {
            assertTrue(Context.global().isGlobal());
            assertFalse(new Context("world", "survival").isGlobal());
            assertFalse(new Context("global", "survival").isGlobal());
            assertFalse(new Context("world", "global").isGlobal());
        }

        @Test @DisplayName("isWildcard: true only when value is '*'")
        void isWildcard() {
            assertTrue(new Context("world", "*").isWildcard());
            assertFalse(new Context("world", "survival").isWildcard());
        }

        @Test @DisplayName("matchesValue: exact match (case-insensitive)")
        void matchesValue_exact() {
            Context c = new Context("world", "survival");
            assertTrue(c.matchesValue("survival"));
            assertTrue(c.matchesValue("SURVIVAL"));
            assertFalse(c.matchesValue("nether"));
        }

        @Test @DisplayName("matchesValue: wildcard '*' matches any value")
        void matchesValue_wildcard() {
            Context c = new Context("world", "*");
            assertTrue(c.matchesValue("survival"));
            assertTrue(c.matchesValue("nether"));
            assertTrue(c.matchesValue("anything"));
        }

        @Test @DisplayName("fromString: parses key=value correctly")
        void fromString_valid() {
            Context c = Context.fromString("world=survival");
            assertEquals("world", c.getKey());
            assertEquals("survival", c.getValue());
        }

        @Test @DisplayName("fromString: null or blank returns global")
        void fromString_nullOrBlank() {
            assertTrue(Context.fromString(null).isGlobal());
            assertTrue(Context.fromString("").isGlobal());
            assertTrue(Context.fromString("   ").isGlobal());
        }

        @Test @DisplayName("fromString: no '=' returns global")
        void fromString_noEquals() {
            assertTrue(Context.fromString("justkey").isGlobal());
        }

        @Test @DisplayName("fromString: value may contain '='")
        void fromString_valueWithEquals() {
            Context c = Context.fromString("custom=a=b");
            assertEquals("custom", c.getKey());
            assertEquals("a=b", c.getValue());
        }

        @Test @DisplayName("toString: key=value format")
        void toString_format() {
            assertEquals("world=survival", new Context("world", "survival").toString());
        }

        @Test @DisplayName("equals: same key+value are equal")
        void equals_sameKeyValue() {
            assertEquals(new Context("world", "survival"), new Context("world", "survival"));
        }

        @Test @DisplayName("equals: different value is not equal")
        void equals_differentValue() {
            assertNotEquals(new Context("world", "survival"), new Context("world", "nether"));
        }

        @Test @DisplayName("hashCode: equal contexts have same hashCode")
        void hashCode_equal() {
            assertEquals(
                new Context("world", "survival").hashCode(),
                new Context("world", "survival").hashCode());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ContextSet
    // ═══════════════════════════════════════════════════════════════

    @Nested @DisplayName("ContextSet")
    class ContextSetTests {

        @Test @DisplayName("global() is empty")
        void global_isEmpty() {
            assertTrue(ContextSet.global().isEmpty());
        }

        @Test @DisplayName("global() singleton is always the same instance")
        void global_singleton() {
            assertSame(ContextSet.global(), ContextSet.global());
        }

        @Test @DisplayName("builder with entries is not empty")
        void builder_notEmpty() {
            ContextSet cs = ContextSet.builder().put("world", "survival").build();
            assertFalse(cs.isEmpty());
        }

        @Test @DisplayName("builder normalises keys/values to lowercase")
        void builder_lowercases() {
            ContextSet cs = ContextSet.builder().put("WORLD", "Survival").build();
            assertEquals("survival", cs.get("world"));
            assertEquals("survival", cs.get("WORLD")); // query also normalised
        }

        @Test @DisplayName("get: returns null for missing key")
        void get_missing() {
            assertNull(ContextSet.global().get("world"));
        }

        @Test @DisplayName("containsKey: true for present key")
        void containsKey_present() {
            ContextSet cs = ContextSet.builder().put("world", "survival").build();
            assertTrue(cs.containsKey("world"));
        }

        @Test @DisplayName("containsKey: false for absent key")
        void containsKey_absent() {
            assertFalse(ContextSet.global().containsKey("world"));
        }

        @Test @DisplayName("asMap: returns all key-value pairs")
        void asMap_contents() {
            ContextSet cs = ContextSet.builder()
                .put("world", "survival")
                .put("gamemode", "survival")
                .build();
            assertEquals(2, cs.asMap().size());
            assertEquals("survival", cs.asMap().get("world"));
            assertEquals("survival", cs.asMap().get("gamemode"));
        }

        @Test @DisplayName("asMap: is immutable (throws on modification)")
        void asMap_immutable() {
            ContextSet cs = ContextSet.builder().put("world", "survival").build();
            assertThrows(UnsupportedOperationException.class,
                () -> cs.asMap().put("hacked", "value"));
        }

        // ── satisfies(Context) ────────────────────────────────────────────────

        @Test @DisplayName("satisfies: global Context always matches")
        void satisfies_global() {
            ContextSet any = ContextSet.builder().put("world", "nether").build();
            assertTrue(any.satisfies(Context.global()));
            assertTrue(ContextSet.global().satisfies(Context.global()));
        }

        @Test @DisplayName("satisfies: exact value match")
        void satisfies_exactMatch() {
            ContextSet cs = ContextSet.builder().put("world", "survival").build();
            assertTrue(cs.satisfies(new Context("world", "survival")));
        }

        @Test @DisplayName("satisfies: wrong value does not match")
        void satisfies_wrongValue() {
            ContextSet cs = ContextSet.builder().put("world", "survival").build();
            assertFalse(cs.satisfies(new Context("world", "nether")));
        }

        @Test @DisplayName("satisfies: missing key does not match")
        void satisfies_missingKey() {
            ContextSet cs = ContextSet.builder().put("world", "survival").build();
            assertFalse(cs.satisfies(new Context("gamemode", "creative")));
        }

        @Test @DisplayName("satisfies: wildcard value '*' matches any active value")
        void satisfies_wildcard() {
            ContextSet cs = ContextSet.builder().put("world", "nether").build();
            assertTrue(cs.satisfies(new Context("world", "*")));
        }

        // ── satisfiesAll(ContextSet) ──────────────────────────────────────────

        @Test @DisplayName("satisfiesAll: empty required set always matches (global)")
        void satisfiesAll_emptyRequired() {
            ContextSet active = ContextSet.builder().put("world", "survival").build();
            assertTrue(active.satisfiesAll(ContextSet.global()));
        }

        @Test @DisplayName("satisfiesAll: all required keys match → true")
        void satisfiesAll_allMatch() {
            ContextSet active = ContextSet.builder()
                .put("world", "survival")
                .put("gamemode", "survival")
                .build();
            ContextSet required = ContextSet.builder()
                .put("world", "survival")
                .put("gamemode", "survival")
                .build();
            assertTrue(active.satisfiesAll(required));
        }

        @Test @DisplayName("satisfiesAll: one key mismatch → false")
        void satisfiesAll_oneMismatch() {
            ContextSet active = ContextSet.builder()
                .put("world", "survival")
                .put("gamemode", "creative")
                .build();
            ContextSet required = ContextSet.builder()
                .put("world", "survival")
                .put("gamemode", "survival")
                .build();
            assertFalse(active.satisfiesAll(required));
        }

        @Test @DisplayName("satisfiesAll: active has MORE keys than required → still matches")
        void satisfiesAll_activeHasMore() {
            ContextSet active = ContextSet.builder()
                .put("world", "survival")
                .put("gamemode", "survival")
                .put("server", "lobby")
                .build();
            ContextSet required = ContextSet.builder().put("world", "survival").build();
            assertTrue(active.satisfiesAll(required));
        }

        @Test @DisplayName("satisfiesAll: required has key not in active → false")
        void satisfiesAll_requiredHasExtra() {
            ContextSet active = ContextSet.builder().put("world", "survival").build();
            ContextSet required = ContextSet.builder()
                .put("world", "survival")
                .put("gamemode", "creative")
                .build();
            assertFalse(active.satisfiesAll(required));
        }

        @Test @DisplayName("satisfiesAll: wildcard in required matches any active value")
        void satisfiesAll_wildcardInRequired() {
            ContextSet active = ContextSet.builder().put("world", "nether").build();
            ContextSet required = ContextSet.builder().put("world", "*").build();
            assertTrue(active.satisfiesAll(required));
        }

        // ── equals + hashCode ─────────────────────────────────────────────────

        @Test @DisplayName("equals: same keys and values are equal")
        void equals_same() {
            ContextSet a = ContextSet.builder().put("world", "survival").build();
            ContextSet b = ContextSet.builder().put("world", "survival").build();
            assertEquals(a, b);
        }

        @Test @DisplayName("equals: different values are not equal")
        void equals_different() {
            ContextSet a = ContextSet.builder().put("world", "survival").build();
            ContextSet b = ContextSet.builder().put("world", "nether").build();
            assertNotEquals(a, b);
        }

        @Test @DisplayName("hashCode: equal sets have same hashCode")
        void hashCode_equal() {
            ContextSet a = ContextSet.builder().put("world", "survival").build();
            ContextSet b = ContextSet.builder().put("world", "survival").build();
            assertEquals(a.hashCode(), b.hashCode());
        }

        // ── toString ──────────────────────────────────────────────────────────

        @Test @DisplayName("toString: empty set returns 'global'")
        void toString_global() {
            assertEquals("global", ContextSet.global().toString());
        }

        @Test @DisplayName("toString: single entry uses key=value format")
        void toString_singleEntry() {
            assertEquals("world=survival",
                ContextSet.builder().put("world", "survival").build().toString());
        }
    }
}
