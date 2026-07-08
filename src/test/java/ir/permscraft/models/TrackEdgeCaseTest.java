package ir.permscraft.models;

import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Track — edge cases")
class TrackEdgeCaseTest {

    @Test @DisplayName("single-group track: getNext returns null")
    void singleGroup_next() {
        Track t = new Track("t");
        t.addGroup("only");
        assertNull(t.getNext("only"));
    }

    @Test @DisplayName("single-group track: getPrevious returns null")
    void singleGroup_prev() {
        Track t = new Track("t");
        t.addGroup("only");
        assertNull(t.getPrevious("only"));
    }

    @Test @DisplayName("two-group track: middle transitions work")
    void twoGroups() {
        Track t = new Track("t");
        t.addGroup("a");
        t.addGroup("b");
        assertEquals("b", t.getNext("a"));
        assertEquals("a", t.getPrevious("b"));
        assertNull(t.getNext("b"));
        assertNull(t.getPrevious("a"));
    }

    @Test @DisplayName("clearGroups + addGroup works without residual state")
    void clearThenAdd() {
        Track t = new Track("t");
        t.addGroup("old1");
        t.addGroup("old2");
        t.clearGroups();
        t.addGroup("new1");
        assertEquals(1, t.size());
        assertEquals("new1", t.getGroups().get(0));
    }

    @Test @DisplayName("removeGroup from middle: order of remaining groups is preserved")
    void removeMiddle_orderPreserved() {
        Track t = new Track("t");
        t.addGroup("a");
        t.addGroup("b");
        t.addGroup("c");
        t.removeGroup("b");
        assertEquals(List.of("a", "c"), t.getGroups());
    }

    @Test @DisplayName("track with 100 groups: getNext walks full chain")
    void largeTrack_fullWalk() {
        Track t = new Track("large");
        for (int i = 0; i < 100; i++) t.addGroup("rank" + i);
        int count = 0;
        String cur = "rank0";
        while (cur != null) { count++; cur = t.getNext(cur); }
        assertEquals(100, count);
    }

    @Test @DisplayName("getPosition returns -1 for group not in track")
    void getPosition_absent() {
        Track t = new Track("t");
        t.addGroup("a");
        assertEquals(-1, t.getPosition("x"));
    }

    @Test @DisplayName("containsGroup is case-sensitive")
    void containsGroup_caseSensitive() {
        Track t = new Track("t");
        t.addGroup("VIP");
        // Track stores as-is (case-sensitive list)
        assertTrue(t.containsGroup("VIP"));
        assertFalse(t.containsGroup("vip"));
    }

    @Test @DisplayName("getName returns the name given in constructor")
    void getName() {
        assertEquals("mytrack", new Track("mytrack").getName());
    }

    @Test @DisplayName("re-adding a group to track after removal works correctly")
    void removeAndReAdd() {
        Track t = new Track("t");
        t.addGroup("a");
        t.addGroup("b");
        t.removeGroup("b");
        t.addGroup("b"); // add back at end
        assertEquals(List.of("a", "b"), t.getGroups());
        assertEquals("b", t.getNext("a"));
    }
}
