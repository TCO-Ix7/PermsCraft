package ir.permscraft.models;

import org.junit.jupiter.api.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("User — edge cases")
class UserEdgeCaseTest {

    @Test @DisplayName("two users with same username but different UUIDs are not equal")
    void uuid_uniqueness() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        User ua = new User(a, "Steve");
        User ub = new User(b, "Steve");
        assertNotEquals(ua.getUuid(), ub.getUuid());
    }

    @Test @DisplayName("adding 200 permissions stores all of them")
    void permissions_bulk() {
        User u = new User(UUID.randomUUID(), "Alex");
        for (int i = 0; i < 200; i++) u.addPermission("perm." + i);
        assertEquals(200, u.getPermissions().size());
    }

    @Test @DisplayName("removing non-existent permission does not throw")
    void removePermission_absent() {
        User u = new User(UUID.randomUUID(), "Alex");
        assertDoesNotThrow(() -> u.removePermission("perm.absent"));
    }

    @Test @DisplayName("removing non-existent group does not throw")
    void removeGroup_absent() {
        User u = new User(UUID.randomUUID(), "Alex");
        assertDoesNotThrow(() -> u.removeGroup("nonexistent"));
    }

    @Test @DisplayName("user has no permissions by default")
    void permissions_emptyDefault() {
        User u = new User(UUID.randomUUID(), "Test");
        assertTrue(u.getPermissions().isEmpty());
    }

    @Test @DisplayName("user has no groups by default")
    void groups_emptyDefault() {
        User u = new User(UUID.randomUUID(), "Test");
        assertTrue(u.getGroups().isEmpty());
    }

    @Test @DisplayName("getPrimaryGroup returns 'default' when no groups set")
    void primaryGroup_noGroups() {
        User u = new User(UUID.randomUUID(), "Test");
        assertEquals("default", u.getPrimaryGroup());
    }

    @Test @DisplayName("adding same group twice does not duplicate it")
    void addGroup_noDuplicate() {
        User u = new User(UUID.randomUUID(), "Steve");
        u.addGroup("vip");
        u.addGroup("vip");
        assertEquals(1, u.getGroups().size());
    }

    @Test @DisplayName("multiple meta entries are all stored independently")
    void meta_multiple() {
        User u = new User(UUID.randomUUID(), "Steve");
        u.setMeta("rank",  "diamond");
        u.setMeta("color", "&b");
        u.setMeta("score", "9999");
        assertEquals("diamond", u.getMetaValue("rank"));
        assertEquals("&b",      u.getMetaValue("color"));
        assertEquals("9999",    u.getMetaValue("score"));
    }

    @Test @DisplayName("overwriting meta key replaces the value")
    void meta_overwrite() {
        User u = new User(UUID.randomUUID(), "Steve");
        u.setMeta("rank", "gold");
        u.setMeta("rank", "diamond");
        assertEquals("diamond", u.getMetaValue("rank"));
    }

    @Test @DisplayName("concurrent group additions are all stored")
    void groups_concurrentAdd() throws InterruptedException {
        User u = new User(UUID.randomUUID(), "Steve");
        Thread[] threads = new Thread[5];
        String[] groupNames = {"g1", "g2", "g3", "g4", "g5"};
        for (int i = 0; i < 5; i++) {
            final String g = groupNames[i];
            threads[i] = new Thread(() -> u.addGroup(g));
        }
        for (Thread t : threads) t.start();
        for (Thread t : threads) t.join();
        assertEquals(5, u.getGroups().size());
    }
}
