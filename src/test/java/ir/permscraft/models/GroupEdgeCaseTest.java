package ir.permscraft.models;

import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Group — edge cases")
class GroupEdgeCaseTest {

    @Test @DisplayName("weight negative value is stored correctly")
    void weight_negative() {
        Group g = new Group("special");
        g.setWeight(-5);
        assertEquals(-5, g.getWeight());
    }

    @Test @DisplayName("weight Integer.MAX_VALUE is stored correctly")
    void weight_maxInt() {
        Group g = new Group("op");
        g.setWeight(Integer.MAX_VALUE);
        assertEquals(Integer.MAX_VALUE, g.getWeight());
    }

    @Test @DisplayName("adding many permissions stores all of them")
    void permissions_many() {
        Group g = new Group("admin");
        for (int i = 0; i < 200; i++) {
            g.addPermission("perm." + i);
        }
        assertEquals(200, g.getPermissions().size());
    }

    @Test @DisplayName("permissions set is thread-safe under concurrent writes")
    void permissions_threadSafe() throws InterruptedException {
        Group g = new Group("concurrent");
        Thread[] threads = new Thread[10];
        for (int i = 0; i < 10; i++) {
            final int idx = i;
            threads[i] = new Thread(() -> {
                for (int j = 0; j < 10; j++) {
                    g.addPermission("perm." + idx + "." + j);
                }
            });
        }
        for (Thread t : threads) t.start();
        for (Thread t : threads) t.join();
        assertEquals(100, g.getPermissions().size());
    }

    @Test @DisplayName("adding same parent twice does not create duplicates in inheritance")
    void inheritance_noDuplicate() {
        Group g = new Group("vip");
        g.addInheritance("default");
        g.addInheritance("default");
        assertEquals(1, g.getInheritedGroups().size());
    }

    @Test @DisplayName("group with no permissions has empty permission set")
    void permissions_emptyOnCreation() {
        assertTrue(new Group("empty").getPermissions().isEmpty());
    }

    @Test @DisplayName("setMeta with null value stores null without throwing")
    void meta_nullValue() {
        Group g = new Group("g");
        assertDoesNotThrow(() -> g.setMeta("key", null));
    }

    @Test @DisplayName("removing non-existent permission does not throw")
    void removePermission_absent() {
        Group g = new Group("g");
        assertDoesNotThrow(() -> g.removePermission("does.not.exist"));
    }

    @Test @DisplayName("removing non-existent parent does not throw")
    void removeInheritance_absent() {
        Group g = new Group("g");
        assertDoesNotThrow(() -> g.removeInheritance("nobody"));
    }

    @Test @DisplayName("group name is stored as provided (not lowercased by model itself)")
    void name_preservedAsIs() {
        Group g = new Group("MyGroup");
        assertEquals("MyGroup", g.getName());
    }
}
