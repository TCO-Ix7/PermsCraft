package ir.permscraft.utils;

import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("WildcardUtil — edge cases & stress")
class WildcardUtilEdgeCaseTest {

    // ── hasPermission edge cases ───────────────────────────────────────────────

    @Test @DisplayName("permission with only a dot is handled without crash")
    void hasPermission_dotOnly() {
        assertDoesNotThrow(() ->
            WildcardUtil.hasPermission(Set.of("essentials.fly"), "."));
    }

    @Test @DisplayName("permission with multiple dots matches exact multi-level node")
    void hasPermission_multiLevel() {
        Set<String> perms = Set.of("a.b.c.d.e");
        assertTrue(WildcardUtil.hasPermission(perms, "a.b.c.d.e"));
        assertFalse(WildcardUtil.hasPermission(perms, "a.b.c.d"));
    }

    @Test @DisplayName("wildcard at top level: 'a.*' does NOT match 'a' itself")
    void hasPermission_wildcardDoesNotMatchPrefix() {
        Set<String> perms = Set.of("a.*");
        assertFalse(WildcardUtil.hasPermission(perms, "a"));
    }

    @Test @DisplayName("very large permission set does not cause performance issues")
    void hasPermission_largeset() {
        Set<String> perms = new HashSet<>();
        for (int i = 0; i < 10_000; i++) perms.add("perm." + i);
        // Should complete quickly
        long start = System.currentTimeMillis();
        WildcardUtil.hasPermission(perms, "perm.9999");
        assertTrue(System.currentTimeMillis() - start < 500, "Should be < 500ms");
    }

    @Test @DisplayName("negation of non-existent permission does not grant anything")
    void hasPermission_negateNonExistent() {
        Set<String> perms = Set.of("-perm.fly");
        assertFalse(WildcardUtil.hasPermission(perms, "perm.fly"));
        assertFalse(WildcardUtil.hasPermission(perms, "perm.home"));
    }

    @Test @DisplayName("multiple wildcards with same prefix: most specific wins concept applies")
    void hasPermission_multipleWildcards() {
        Set<String> perms = new HashSet<>(Arrays.asList("essentials.*", "-essentials.fly"));
        // Exact negation beats wildcard grant
        assertFalse(WildcardUtil.hasPermission(perms, "essentials.fly"));
        // Other sub-nodes still granted
        assertTrue(WildcardUtil.hasPermission(perms, "essentials.home"));
    }

    @Test @DisplayName("global wildcard with a specific negation: negation wins for that node")
    void hasPermission_globalWildcardNegation() {
        Set<String> perms = new HashSet<>(Arrays.asList("*", "-essentials.fly"));
        assertFalse(WildcardUtil.hasPermission(perms, "essentials.fly"));
        assertTrue(WildcardUtil.hasPermission(perms, "some.other.perm"));
    }

    // ── matches edge cases ────────────────────────────────────────────────────

    @Test @DisplayName("matches: empty string node does not match any target")
    void matches_emptyNode() {
        assertFalse(WildcardUtil.matches("", "essentials.fly"));
    }

    @Test @DisplayName("matches: empty target does not match wildcard")
    void matches_emptyTarget() {
        assertFalse(WildcardUtil.matches("essentials.*", ""));
    }

    @Test @DisplayName("matches: wildcard '.*' only matches if preceded by a namespace")
    void matches_dotWildcardAlone() {
        // ".*" should not match "essentials.fly" (no prefix before the dot)
        assertFalse(WildcardUtil.matches(".*", "essentials.fly"));
    }

    // ── expand edge cases ─────────────────────────────────────────────────────

    @Test @DisplayName("expand: empty nodes set returns empty result")
    void expand_emptyNodes() {
        Set<String> result = WildcardUtil.expand(Set.of(), Set.of("perm.a", "perm.b"));
        assertTrue(result.isEmpty());
    }

    @Test @DisplayName("expand: empty known set returns only non-wildcard nodes")
    void expand_emptyKnown() {
        Set<String> result = WildcardUtil.expand(Set.of("perm.x", "perm.*"), Set.of());
        assertTrue(result.contains("perm.x"));
        // perm.* expands against empty known set — nothing extra added
    }

    @Test @DisplayName("expand: multiple wildcards are all expanded")
    void expand_multipleWildcards() {
        Set<String> nodes = Set.of("a.*", "b.*");
        Set<String> known = Set.of("a.one", "a.two", "b.three", "c.four");
        Set<String> result = WildcardUtil.expand(nodes, known);
        assertTrue(result.contains("a.one"));
        assertTrue(result.contains("a.two"));
        assertTrue(result.contains("b.three"));
        assertFalse(result.contains("c.four"));
    }

    @Test @DisplayName("expand: mixed wildcards and non-wildcards in same call")
    void expand_mixed() {
        Set<String> nodes = Set.of("essentials.*", "minecraft.op");
        Set<String> known = Set.of("essentials.fly", "essentials.home", "minecraft.op", "other.perm");
        Set<String> result = WildcardUtil.expand(nodes, known);
        assertTrue(result.contains("essentials.fly"));
        assertTrue(result.contains("essentials.home"));
        assertTrue(result.contains("minecraft.op"));
        assertFalse(result.contains("other.perm"));
    }
}
