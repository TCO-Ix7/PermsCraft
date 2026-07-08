package ir.permscraft.utils;

import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("WildcardUtil")
class WildcardUtilTest {

    // ── matches() ────────────────────────────────────────────────────────────

    @Test @DisplayName("exact match returns true")
    void matches_exact() {
        assertTrue(WildcardUtil.matches("essentials.fly", "essentials.fly"));
    }

    @Test @DisplayName("global wildcard * matches any node")
    void matches_globalWildcard() {
        assertTrue(WildcardUtil.matches("*", "essentials.fly"));
        assertTrue(WildcardUtil.matches("*", "minecraft.command.give"));
    }

    @Test @DisplayName("prefix wildcard matches direct children")
    void matches_prefixWildcard_direct() {
        assertTrue(WildcardUtil.matches("essentials.*", "essentials.fly"));
    }

    @Test @DisplayName("prefix wildcard matches deep children")
    void matches_prefixWildcard_deep() {
        assertTrue(WildcardUtil.matches("essentials.*", "essentials.home.set"));
    }

    @Test @DisplayName("prefix wildcard does NOT match sibling namespace")
    void matches_prefixWildcard_noMatch() {
        assertFalse(WildcardUtil.matches("essentials.*", "minecraft.fly"));
    }

    @Test @DisplayName("case-insensitive exact match")
    void matches_caseInsensitive() {
        assertTrue(WildcardUtil.matches("Essentials.Fly", "essentials.fly"));
    }

    @Test @DisplayName("completely different node returns false")
    void matches_noMatch() {
        assertFalse(WildcardUtil.matches("essentials.home", "essentials.fly"));
    }

    @Test @DisplayName("prefix wildcard does not match the prefix itself without dot")
    void matches_prefixWildcard_doesNotMatchPrefix() {
        assertFalse(WildcardUtil.matches("essentials.*", "essentials"));
    }

    // ── hasPermission() ───────────────────────────────────────────────────────

    @Test @DisplayName("direct grant in set — returns true")
    void hasPermission_directGrant() {
        assertTrue(WildcardUtil.hasPermission(Set.of("essentials.fly"), "essentials.fly"));
    }

    @Test @DisplayName("wildcard grant covers sub-nodes")
    void hasPermission_wildcardGrant() {
        assertTrue(WildcardUtil.hasPermission(Set.of("essentials.*"), "essentials.fly"));
    }

    @Test
    void hasPermission_specificNegationBeatsWildcard() {
        Set<String> perms = new HashSet<>(Arrays.asList("essentials.*", "-essentials.fly"));
        assertFalse(WildcardUtil.hasPermission(perms, "essentials.fly"));
    }

    @Test @DisplayName("specific grant beats wildcard negation")
    void hasPermission_specificGrantBeatsWildcardNegation() {
        Set<String> perms = new HashSet<>(Arrays.asList("essentials.fly", "-essentials.*"));
        assertTrue(WildcardUtil.hasPermission(perms, "essentials.fly"));
    }

    @Test @DisplayName("empty permission set returns false")
    void hasPermission_emptySet() {
        assertFalse(WildcardUtil.hasPermission(Set.of(), "essentials.fly"));
    }

    @Test @DisplayName("null permission argument returns false")
    void hasPermission_nullPermission() {
        assertFalse(WildcardUtil.hasPermission(Set.of("essentials.fly"), null));
    }

    @Test @DisplayName("empty permission string returns false")
    void hasPermission_emptyPermission() {
        assertFalse(WildcardUtil.hasPermission(Set.of("essentials.fly"), ""));
    }

    @Test @DisplayName("global wildcard grants any permission")
    void hasPermission_globalWildcard() {
        assertTrue(WildcardUtil.hasPermission(Set.of("*"), "any.node.at.all"));
    }

    @Test @DisplayName("only negation present — returns false")
    void hasPermission_onlyNegation() {
        assertFalse(WildcardUtil.hasPermission(Set.of("-essentials.fly"), "essentials.fly"));
    }

    @Test @DisplayName("permission not in set returns false")
    void hasPermission_nodeAbsent() {
        assertFalse(WildcardUtil.hasPermission(Set.of("essentials.home"), "essentials.fly"));
    }

    // ── expand() ─────────────────────────────────────────────────────────────

    @Test @DisplayName("expand wildcard against known permissions")
    void expand_wildcard() {
        Set<String> nodes  = Set.of("essentials.*");
        Set<String> known  = Set.of("essentials.fly", "essentials.home", "minecraft.give");
        Set<String> result = WildcardUtil.expand(nodes, known);
        assertTrue(result.contains("essentials.fly"));
        assertTrue(result.contains("essentials.home"));
        assertFalse(result.contains("minecraft.give"));
    }

    @Test @DisplayName("expand negated wildcard produces negated nodes")
    void expand_negatedWildcard() {
        Set<String> nodes  = Set.of("-essentials.*");
        Set<String> known  = Set.of("essentials.fly", "essentials.home");
        Set<String> result = WildcardUtil.expand(nodes, known);
        assertTrue(result.contains("-essentials.fly"));
        assertTrue(result.contains("-essentials.home"));
    }

    @Test @DisplayName("non-wildcard node is returned as-is")
    void expand_nonWildcard() {
        Set<String> nodes  = Set.of("essentials.fly");
        Set<String> known  = Set.of("essentials.fly", "essentials.home");
        Set<String> result = WildcardUtil.expand(nodes, known);
        assertTrue(result.contains("essentials.fly"));
        assertFalse(result.contains("essentials.home"));
    }
}
