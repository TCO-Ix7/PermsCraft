package ir.permscraft.utils;

import org.junit.jupiter.api.*;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SpongeWildcardUtil")
class SpongeWildcardUtilTest {

    // ── no match (returns null = fall-through) ────────────────────────────────

    @Test @DisplayName("empty set returns null")
    void noMatch_emptySet() {
        assertNull(SpongeWildcardUtil.matchSponge(Set.of(), "essentials.fly"));
    }

    @Test @DisplayName("null permission returns null")
    void noMatch_nullPermission() {
        assertNull(SpongeWildcardUtil.matchSponge(Set.of("essentials"), null));
    }

    @Test @DisplayName("permission without dot returns null (Sponge only matches children)")
    void noMatch_noDotInPermission() {
        assertNull(SpongeWildcardUtil.matchSponge(Set.of("essentials"), "essentials"));
    }

    @Test @DisplayName("unrelated parent node returns null")
    void noMatch_unrelatedParent() {
        assertNull(SpongeWildcardUtil.matchSponge(Set.of("vault"), "essentials.fly"));
    }

    @Test @DisplayName("node with * is skipped (handled by standard wildcard)")
    void noMatch_standardWildcard() {
        assertNull(SpongeWildcardUtil.matchSponge(Set.of("essentials.*"), "essentials.fly"));
    }

    @Test @DisplayName("regex node is skipped")
    void noMatch_regexNode() {
        assertNull(SpongeWildcardUtil.matchSponge(Set.of("r=essentials\\..*"), "essentials.fly"));
    }

    @Test @DisplayName("parent that is only a prefix (no dot boundary) does not match")
    void noMatch_partialPrefix() {
        // "essential" should NOT match "essentials.fly" — no dot boundary
        assertNull(SpongeWildcardUtil.matchSponge(Set.of("essential"), "essentials.fly"));
    }

    // ── positive match ────────────────────────────────────────────────────────

    @Test @DisplayName("bare parent matches direct child")
    void match_directChild() {
        assertEquals(Boolean.TRUE, SpongeWildcardUtil.matchSponge(Set.of("essentials"), "essentials.fly"));
    }

    @Test @DisplayName("bare parent matches deep nested child")
    void match_deepChild() {
        assertEquals(Boolean.TRUE, SpongeWildcardUtil.matchSponge(Set.of("essentials"), "essentials.kit.pvp"));
    }

    @Test @DisplayName("mid-level parent matches its children")
    void match_midLevelParent() {
        assertEquals(Boolean.TRUE, SpongeWildcardUtil.matchSponge(
                Set.of("essentials.kit"), "essentials.kit.pvp"));
    }

    @Test @DisplayName("match is case-insensitive on both node and target")
    void match_caseInsensitive() {
        assertEquals(Boolean.TRUE, SpongeWildcardUtil.matchSponge(Set.of("Essentials"), "essentials.fly"));
        assertEquals(Boolean.TRUE, SpongeWildcardUtil.matchSponge(Set.of("essentials"), "Essentials.Fly"));
    }

    // ── negated match ─────────────────────────────────────────────────────────

    @Test @DisplayName("negated bare parent returns false for child")
    void match_negatedParent() {
        assertEquals(Boolean.FALSE, SpongeWildcardUtil.matchSponge(Set.of("-essentials"), "essentials.fly"));
    }

    @Test @DisplayName("negated mid-level parent returns false for its children")
    void match_negatedMidLevel() {
        assertEquals(Boolean.FALSE, SpongeWildcardUtil.matchSponge(
                Set.of("-essentials.kit"), "essentials.kit.pvp"));
    }

    // ── specificity (longer parent wins) ─────────────────────────────────────

    @Test @DisplayName("more specific (longer) parent wins over shorter parent")
    void match_longerParentWins_grant() {
        // "essentials" → true, "-essentials.kit" → false for "essentials.kit.pvp"
        // The longer match (-essentials.kit) is more specific and should win.
        Set<String> perms = Set.of("essentials", "-essentials.kit");
        assertEquals(Boolean.FALSE, SpongeWildcardUtil.matchSponge(perms, "essentials.kit.pvp"));
    }

    @Test @DisplayName("more specific grant wins over shorter deny")
    void match_longerParentWins_deny() {
        // "-essentials" → deny all, "essentials.kit" → grant essentials.kit.*
        Set<String> perms = Set.of("-essentials", "essentials.kit");
        assertEquals(Boolean.TRUE, SpongeWildcardUtil.matchSponge(perms, "essentials.kit.pvp"));
    }

    // ── no false positives ────────────────────────────────────────────────────

    @Test @DisplayName("sibling namespace is not matched")
    void noFalsePositive_sibling() {
        assertNull(SpongeWildcardUtil.matchSponge(Set.of("vault"), "essentials.fly"));
    }

    @Test @DisplayName("child does not match parent (wrong direction)")
    void noFalsePositive_childToParent() {
        // "essentials.fly" should not match target "essentials"
        // (target has no dot → matchSponge returns null anyway)
        assertNull(SpongeWildcardUtil.matchSponge(Set.of("essentials.fly"), "essentials"));
    }

    @Test @DisplayName("exact same string does not match itself as Sponge parent")
    void noFalsePositive_selfMatch() {
        // "essentials.fly" as a Sponge node should NOT match "essentials.fly" (no dot after it)
        // because the target must START WITH "clean." — i.e. must have something after the parent
        assertNull(SpongeWildcardUtil.matchSponge(Set.of("essentials.fly"), "essentials.fly"));
    }
}
