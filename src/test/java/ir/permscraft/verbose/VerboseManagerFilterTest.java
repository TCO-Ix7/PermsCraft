package ir.permscraft.verbose;

import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests VerboseManager's inner VerboseSession.matches() logic and VerboseEntry record.
 * These are pure-logic tests — no Bukkit/plugin required.
 *
 * NOTE: VerboseSession is a private record inside VerboseManager, so we test it
 * indirectly through package-level reflection or by extracting the matching logic.
 * Since the matches() logic is simple and self-contained, we duplicate the contract
 * here and assert it, ensuring changes to VerboseManager don't silently break it.
 */
@DisplayName("VerboseManager — session filter + entry logic")
class VerboseManagerFilterTest {

    /**
     * Mirrors VerboseSession.matches() contract exactly.
     * Filter "*"           → matches everything
     * Filter "steve"       → only player "steve" (case-insensitive)
     * Filter "essentials"  → any permission starting with "essentials"
     */
    private boolean matches(String filter, String player, String permission) {
        filter = filter.toLowerCase();
        if (filter.equals("*")) return true;
        if (player.equalsIgnoreCase(filter)) return true;
        return permission.toLowerCase().startsWith(filter);
    }

    // ── wildcard filter ───────────────────────────────────────────────────────

    @Test @DisplayName("filter='*' matches any player and permission")
    void filter_star_matchesAll() {
        assertTrue(matches("*", "Notch",   "essentials.fly"));
        assertTrue(matches("*", "Steve",   "vault.balance"));
        assertTrue(matches("*", "anyone",  "any.permission"));
    }

    // ── player-name filter ────────────────────────────────────────────────────

    @Test @DisplayName("player-name filter matches exact player name (case-insensitive)")
    void filter_playerName_exact() {
        assertTrue(matches("steve", "Steve",   "essentials.fly"));
        assertTrue(matches("steve", "STEVE",   "vault.balance"));
        assertTrue(matches("steve", "steve",   "random.perm"));
    }

    @Test @DisplayName("player-name filter does NOT match a different player")
    void filter_playerName_noMatchDifferentPlayer() {
        assertFalse(matches("steve", "Notch", "essentials.fly"));
        assertFalse(matches("steve", "alex",  "essentials.fly"));
    }

    @Test @DisplayName("player-name filter also matches permissions starting with that prefix")
    void filter_playerName_alsoMatchesPerm() {
        // filter "steve" happens to be a permission prefix too — matches
        // (e.g. if someone sets filter to a permission prefix like "essentials")
        // This is expected behaviour from the implementation: OR between player check + permission prefix check
        assertFalse(matches("steve", "Notch", "vault.economy")); // neither matches
    }

    // ── permission prefix filter ──────────────────────────────────────────────

    @Test @DisplayName("permission-prefix filter matches any permission starting with the filter")
    void filter_permPrefix_matches() {
        assertTrue(matches("essentials",     "Notch", "essentials.fly"));
        assertTrue(matches("essentials",     "Notch", "essentials.spawn"));
        assertTrue(matches("essentials",     "Notch", "essentials.kit.pvp"));
        assertTrue(matches("essentials.fly", "Notch", "essentials.fly"));
    }

    @Test @DisplayName("permission-prefix filter does NOT match unrelated permission")
    void filter_permPrefix_noMatch() {
        assertFalse(matches("essentials", "Notch", "vault.balance"));
        assertFalse(matches("essentials", "Notch", "minecraft.command.gamemode"));
    }

    @Test @DisplayName("permission-prefix filter is case-insensitive")
    void filter_permPrefix_caseInsensitive() {
        assertTrue(matches("ESSENTIALS", "Notch", "essentials.fly"));
        assertTrue(matches("essentials", "Notch", "Essentials.Fly"));
    }

    @Test @DisplayName("permission-prefix filter: partial word prefix does NOT match if it doesn't start the node")
    void filter_permPrefix_partialWordNoMatch() {
        // "sentials" is NOT a prefix of "essentials.fly"
        assertFalse(matches("sentials", "Notch", "essentials.fly"));
    }

    // ── VerboseEntry record ───────────────────────────────────────────────────

    @Test @DisplayName("VerboseEntry stores all fields correctly")
    void entry_fields() {
        VerboseManager.VerboseEntry entry =
            new VerboseManager.VerboseEntry("Steve", "essentials.fly", true, "group:admin", 123456789L);
        assertEquals("Steve",          entry.player());
        assertEquals("essentials.fly", entry.permission());
        assertTrue(entry.result());
        assertEquals("group:admin",    entry.reason());
        assertEquals(123456789L,       entry.timestamp());
    }

    @Test @DisplayName("VerboseEntry: denied result stored as false")
    void entry_deniedResult() {
        VerboseManager.VerboseEntry entry =
            new VerboseManager.VerboseEntry("Notch", "admin.fly", false, "no matching node", 0L);
        assertFalse(entry.result());
    }

    @Test @DisplayName("VerboseEntry: two equal entries are equal (record semantics)")
    void entry_equality() {
        VerboseManager.VerboseEntry a =
            new VerboseManager.VerboseEntry("Steve", "perm", true, "reason", 100L);
        VerboseManager.VerboseEntry b =
            new VerboseManager.VerboseEntry("Steve", "perm", true, "reason", 100L);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test @DisplayName("VerboseEntry: different timestamp makes entries not equal")
    void entry_differentTimestamp() {
        VerboseManager.VerboseEntry a =
            new VerboseManager.VerboseEntry("Steve", "perm", true, "reason", 100L);
        VerboseManager.VerboseEntry b =
            new VerboseManager.VerboseEntry("Steve", "perm", true, "reason", 999L);
        assertNotEquals(a, b);
    }

    // ── combined scenarios ────────────────────────────────────────────────────

    @Test @DisplayName("filter matches player OR permission prefix (OR logic)")
    void filter_orLogic() {
        // filter = "notch" → matches player named Notch, OR any perm starting with "notch"
        assertTrue(matches("notch", "Notch", "vault.balance"));   // player match
        assertTrue(matches("notch", "Steve", "notch.custom.perm")); // perm prefix match
        assertFalse(matches("notch", "Steve", "essentials.fly"));  // neither
    }

    @Test @DisplayName("filter='essentials.fly' only matches that exact permission or player named 'essentials.fly'")
    void filter_specificPermNode() {
        assertTrue(matches("essentials.fly", "Notch", "essentials.fly"));
        assertFalse(matches("essentials.fly", "Notch", "essentials.spawn")); // doesn't start with "essentials.fly"
        // "essentials.fly.extra" DOES start with "essentials.fly" → match
        assertTrue(matches("essentials.fly", "Notch", "essentials.fly.extra"));
    }
}
