package ir.permscraft.models;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Group model")
class GroupTest {

    private Group group;

    @BeforeEach
    void setUp() {
        group = new Group("admin");
    }

    // ── identity ─────────────────────────────────────────────────────────────

    @Test @DisplayName("name is set on construction")
    void name() {
        assertEquals("admin", group.getName());
    }

    @Test @DisplayName("displayName defaults to name")
    void displayName_default() {
        assertEquals("admin", group.getDisplayName());
    }

    @Test @DisplayName("displayName can be changed")
    void displayName_set() {
        group.setDisplayName("Admin");
        assertEquals("Admin", group.getDisplayName());
    }

    @Test @DisplayName("weight defaults to 0")
    void weight_default() {
        assertEquals(0, group.getWeight());
    }

    @Test @DisplayName("weight can be set")
    void weight_set() {
        group.setWeight(100);
        assertEquals(100, group.getWeight());
    }

    // ── permissions ──────────────────────────────────────────────────────────

    @Test @DisplayName("addPermission and hasPermission work together")
    void permissions_addAndHas() {
        group.addPermission("essentials.fly");
        assertTrue(group.hasPermission("essentials.fly"));
    }

    @Test @DisplayName("removePermission removes node")
    void permissions_remove() {
        group.addPermission("essentials.fly");
        group.removePermission("essentials.fly");
        assertFalse(group.hasPermission("essentials.fly"));
    }

    @Test @DisplayName("hasPermission false for absent node")
    void permissions_absent() {
        assertFalse(group.hasPermission("essentials.fly"));
    }

    @Test @DisplayName("duplicate addPermission does not create duplicates")
    void permissions_noDuplicates() {
        group.addPermission("essentials.fly");
        group.addPermission("essentials.fly");
        assertEquals(1, group.getPermissions().size());
    }

    // ── inheritance ───────────────────────────────────────────────────────────

    @Test @DisplayName("addInheritance adds parent group")
    void inheritance_add() {
        group.addInheritance("default");
        assertTrue(group.getInheritedGroups().contains("default"));
    }

    @Test @DisplayName("removeInheritance removes parent group")
    void inheritance_remove() {
        group.addInheritance("default");
        group.removeInheritance("default");
        assertFalse(group.getInheritedGroups().contains("default"));
    }

    @Test @DisplayName("inheritance set is empty by default")
    void inheritance_emptyByDefault() {
        assertTrue(group.getInheritedGroups().isEmpty());
    }

    // ── prefix / suffix ───────────────────────────────────────────────────────

    @Test @DisplayName("prefix defaults to empty string")
    void prefix_default() {
        assertEquals("", group.getPrefix());
    }

    @Test @DisplayName("prefix and suffix can be set")
    void prefixSuffix_set() {
        group.setPrefix("&4[Admin]");
        group.setSuffix(" &f");
        assertEquals("&4[Admin]", group.getPrefix());
        assertEquals(" &f", group.getSuffix());
    }

    // ── meta ─────────────────────────────────────────────────────────────────

    @Test @DisplayName("meta setMeta and getMetaValue")
    void meta_setGet() {
        group.setMeta("chat-color", "&c");
        assertEquals("&c", group.getMetaValue("chat-color"));
    }

    @Test @DisplayName("absent meta key returns null")
    void meta_absent() {
        assertNull(group.getMetaValue("missing"));
    }

    @Test @DisplayName("unsetMeta removes key")
    void meta_unset() {
        group.setMeta("chat-color", "&c");
        group.unsetMeta("chat-color");
        assertNull(group.getMetaValue("chat-color"));
    }
}
