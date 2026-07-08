package ir.permscraft.models;

import org.junit.jupiter.api.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("User model")
class UserTest {

    private User user;
    private final UUID uuid = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        user = new User(uuid, "TestPlayer");
    }

    // ── identity ─────────────────────────────────────────────────────────────

    @Test @DisplayName("uuid and username are set correctly")
    void identity() {
        assertEquals(uuid, user.getUuid());
        assertEquals("TestPlayer", user.getUsername());
    }

    @Test @DisplayName("username can be updated")
    void setUsername() {
        user.setUsername("NewName");
        assertEquals("NewName", user.getUsername());
    }

    // ── groups ───────────────────────────────────────────────────────────────

    @Test @DisplayName("first group added becomes primary group")
    void firstGroupIsPrimary() {
        user.addGroup("default");
        assertEquals("default", user.getPrimaryGroup());
    }

    @Test @DisplayName("inGroup is case-insensitive")
    void inGroup_caseInsensitive() {
        user.addGroup("Admin");
        assertTrue(user.inGroup("admin"));
        assertTrue(user.inGroup("ADMIN"));
    }

    @Test @DisplayName("removeGroup removes from set")
    void removeGroup() {
        user.addGroup("vip");
        user.removeGroup("vip");
        assertFalse(user.inGroup("vip"));
    }

    @Test @DisplayName("removing primary group shifts primary to next group")
    void removeGroup_shiftsPrimary() {
        user.addGroup("default");
        user.addGroup("vip");
        user.removeGroup("default");
        assertEquals("vip", user.getPrimaryGroup());
    }

    @Test @DisplayName("removing last group returns 'default' as primary")
    void removeLastGroup_defaultPrimary() {
        user.addGroup("vip");
        user.removeGroup("vip");
        assertEquals("default", user.getPrimaryGroup());
    }

    @Test @DisplayName("setPrimaryGroup reorders set and changes primary")
    void setPrimaryGroup() {
        user.addGroup("default");
        user.addGroup("vip");
        user.setPrimaryGroup("vip");
        assertEquals("vip", user.getPrimaryGroup());
        assertEquals("vip", user.getGroups().iterator().next()); // first in set
    }

    @Test @DisplayName("setPrimaryGroup ignores unknown group name")
    void setPrimaryGroup_unknownGroup() {
        user.addGroup("default");
        user.setPrimaryGroup("nonexistent");
        assertEquals("default", user.getPrimaryGroup()); // unchanged
    }

    // ── permissions ──────────────────────────────────────────────────────────

    @Test @DisplayName("addPermission and hasPermission work together")
    void addAndHasPermission() {
        user.addPermission("essentials.fly");
        assertTrue(user.hasPermission("essentials.fly"));
    }

    @Test @DisplayName("removePermission removes from set")
    void removePermission() {
        user.addPermission("essentials.fly");
        user.removePermission("essentials.fly");
        assertFalse(user.hasPermission("essentials.fly"));
    }

    @Test @DisplayName("hasPermission returns false for absent node")
    void hasPermission_absent() {
        assertFalse(user.hasPermission("essentials.home"));
    }

    // ── prefix / suffix ───────────────────────────────────────────────────────

    @Test @DisplayName("prefix and suffix are empty by default")
    void prefixSuffix_default() {
        assertEquals("", user.getPrefix());
        assertEquals("", user.getSuffix());
    }

    @Test @DisplayName("prefix and suffix can be set")
    void prefixSuffix_set() {
        user.setPrefix("&c[Admin] ");
        user.setSuffix(" &7(AFK)");
        assertEquals("&c[Admin] ", user.getPrefix());
        assertEquals(" &7(AFK)", user.getSuffix());
    }

    // ── meta ─────────────────────────────────────────────────────────────────

    @Test @DisplayName("meta key-value is stored and retrieved")
    void meta_setGet() {
        user.setMeta("rank", "diamond");
        assertEquals("diamond", user.getMetaValue("rank"));
        assertEquals("diamond", user.getMeta("rank")); // alias
    }

    @Test @DisplayName("absent meta key returns null")
    void meta_absent() {
        assertNull(user.getMetaValue("nonexistent"));
    }

    @Test @DisplayName("unsetMeta removes the key")
    void meta_unset() {
        user.setMeta("rank", "gold");
        user.unsetMeta("rank");
        assertNull(user.getMetaValue("rank"));
    }
}
