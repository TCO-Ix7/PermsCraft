package ir.permscraft.utils;

import org.junit.jupiter.api.*;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RegexUtil")
class RegexUtilTest {

    // ── isRegex ───────────────────────────────────────────────────────────────

    @Test @DisplayName("isRegex: r= prefix is detected")
    void isRegex_plain() { assertTrue(RegexUtil.isRegex("r=essentials\\..*")); }

    @Test @DisplayName("isRegex: negated -r= is detected")
    void isRegex_negated() { assertTrue(RegexUtil.isRegex("-r=essentials\\..*")); }

    @Test @DisplayName("isRegex: plain node is not regex")
    void isRegex_plainNode() { assertFalse(RegexUtil.isRegex("essentials.fly")); }

    @Test @DisplayName("isRegex: wildcard node is not regex")
    void isRegex_wildcard() { assertFalse(RegexUtil.isRegex("essentials.*")); }

    @Test @DisplayName("isRegex: null returns false")
    void isRegex_null() { assertFalse(RegexUtil.isRegex(null)); }

    @Test @DisplayName("isRegex: empty string is not regex")
    void isRegex_empty() { assertFalse(RegexUtil.isRegex("")); }

    // ── matchRegex: no match ─────────────────────────────────────────────────

    @Test @DisplayName("matchRegex: empty set returns null")
    void matchRegex_emptySet() {
        assertNull(RegexUtil.matchRegex(Set.of(), "essentials.fly"));
    }

    @Test @DisplayName("matchRegex: set with no regex nodes returns null")
    void matchRegex_noRegexNodes() {
        assertNull(RegexUtil.matchRegex(Set.of("essentials.fly", "essentials.*"), "essentials.fly"));
    }

    @Test @DisplayName("matchRegex: regex present but doesn't match target returns null")
    void matchRegex_noMatch() {
        assertNull(RegexUtil.matchRegex(Set.of("r=vault\\..*"), "essentials.fly"));
    }

    @Test @DisplayName("matchRegex: null permission returns null")
    void matchRegex_nullPermission() {
        assertNull(RegexUtil.matchRegex(Set.of("r=.*"), null));
    }

    // ── matchRegex: positive match ────────────────────────────────────────────
    // NOTE: RegexUtil uses Pattern.matches() (not find()), so regex must match
    // the FULL lowercased permission string. Target is lowercased before matching.

    @Test @DisplayName("matchRegex: .* pattern matches any node (full match)")
    void matchRegex_dotStar() {
        assertEquals(Boolean.TRUE, RegexUtil.matchRegex(Set.of("r=.*"), "any.permission.node"));
    }

    @Test @DisplayName("matchRegex: alternation pattern matches correct nodes")
    void matchRegex_alternation() {
        // matches() requires full string match, so must cover whole node
        Set<String> perms = Set.of("r=essentials\\.(fly|spawn)");
        assertEquals(Boolean.TRUE, RegexUtil.matchRegex(perms, "essentials.fly"));
        assertEquals(Boolean.TRUE, RegexUtil.matchRegex(perms, "essentials.spawn"));
        assertNull(RegexUtil.matchRegex(perms, "essentials.ban")); // not in alternation
    }

    @Test @DisplayName("matchRegex: target is lowercased before matching")
    void matchRegex_targetLowercased() {
        // RegexUtil lowercases the target, so "Essentials.Fly" becomes "essentials.fly"
        assertEquals(Boolean.TRUE, RegexUtil.matchRegex(Set.of("r=essentials\\.fly"), "Essentials.Fly"));
        assertEquals(Boolean.TRUE, RegexUtil.matchRegex(Set.of("r=essentials\\.fly"), "ESSENTIALS.FLY"));
    }

    @Test @DisplayName("matchRegex: pattern must match full node (matches() not find())")
    void matchRegex_fullMatchRequired() {
        // "essentials" alone does NOT match "essentials.fly" with matches()
        assertNull(RegexUtil.matchRegex(Set.of("r=essentials"), "essentials.fly"));
        // but "essentials\\..*" does
        assertEquals(Boolean.TRUE, RegexUtil.matchRegex(Set.of("r=essentials\\..*"), "essentials.fly"));
    }

    @Test @DisplayName("matchRegex: exact full-string pattern")
    void matchRegex_exactFullString() {
        assertEquals(Boolean.TRUE, RegexUtil.matchRegex(Set.of("r=essentials\\.fly"), "essentials.fly"));
        assertNull(RegexUtil.matchRegex(Set.of("r=essentials\\.fly"), "essentials.fly.other"));
    }

    // ── matchRegex: negated match ─────────────────────────────────────────────

    @Test @DisplayName("matchRegex: negated regex returns false on match")
    void matchRegex_negatedMatch() {
        assertEquals(Boolean.FALSE, RegexUtil.matchRegex(Set.of("-r=essentials\\.fly"), "essentials.fly"));
    }

    @Test @DisplayName("matchRegex: negated regex returns null on non-match")
    void matchRegex_negatedNoMatch() {
        assertNull(RegexUtil.matchRegex(Set.of("-r=essentials\\.fly"), "vault.balance"));
    }

    // ── matchRegex: malformed regex ───────────────────────────────────────────

    @Test @DisplayName("matchRegex: malformed regex is silently skipped, returns null")
    void matchRegex_malformedRegex() {
        assertNull(RegexUtil.matchRegex(Set.of("r=[invalid(regex"), "essentials.fly"));
    }

    @Test @DisplayName("matchRegex: malformed then valid — valid still matches")
    void matchRegex_malformedThenValid() {
        assertEquals(Boolean.TRUE,
            RegexUtil.matchRegex(Set.of("r=[invalid(regex", "r=essentials\\.fly"), "essentials.fly"));
    }

    // ── matchRegex: mixed set ─────────────────────────────────────────────────

    @Test @DisplayName("matchRegex: non-regex nodes in set are skipped")
    void matchRegex_nonRegexSkipped() {
        // "essentials.fly" plain node is NOT treated as regex — skipped
        assertNull(RegexUtil.matchRegex(Set.of("essentials.fly"), "essentials.fly"));
    }

    @Test @DisplayName("matchRegex: mixed plain and regex — only regex nodes processed")
    void matchRegex_mixedNodes_regexMatches() {
        Set<String> perms = Set.of("essentials.fly", "r=vault\\..*", "essentials.*");
        assertEquals(Boolean.TRUE, RegexUtil.matchRegex(perms, "vault.balance"));
        assertNull(RegexUtil.matchRegex(perms, "essentials.fly")); // matched by plain/wildcard, not regex
    }

    @Test @DisplayName("matchRegex: when no regex node matches, returns null regardless of plain nodes")
    void matchRegex_noRegexMatch_returnNull() {
        Set<String> perms = Set.of("essentials.fly", "r=admin\\..*");
        assertNull(RegexUtil.matchRegex(perms, "essentials.fly"));
    }
}
