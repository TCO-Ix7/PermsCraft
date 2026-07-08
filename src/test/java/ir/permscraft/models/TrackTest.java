package ir.permscraft.models;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Track model")
class TrackTest {

    private Track track;

    @BeforeEach
    void setUp() {
        track = new Track("ranks");
        track.addGroup("member");
        track.addGroup("vip");
        track.addGroup("mvp");
        track.addGroup("legend");
    }

    @Test @DisplayName("name is set on construction")
    void name() {
        assertEquals("ranks", track.getName());
    }

    @Test @DisplayName("size reflects number of groups added")
    void size() {
        assertEquals(4, track.size());
    }

    @Test @DisplayName("containsGroup returns true for added groups")
    void containsGroup() {
        assertTrue(track.containsGroup("vip"));
    }

    @Test @DisplayName("containsGroup returns false for absent group")
    void containsGroup_absent() {
        assertFalse(track.containsGroup("elite"));
    }

    @Test @DisplayName("getNext returns the next group in order")
    void getNext_normal() {
        assertEquals("vip", track.getNext("member"));
        assertEquals("mvp", track.getNext("vip"));
        assertEquals("legend", track.getNext("mvp"));
    }

    @Test @DisplayName("getNext returns null at the end of track")
    void getNext_atEnd() {
        assertNull(track.getNext("legend"));
    }

    @Test @DisplayName("getNext returns null for unknown group")
    void getNext_unknown() {
        assertNull(track.getNext("nonexistent"));
    }

    @Test @DisplayName("getPrevious returns the previous group in order")
    void getPrevious_normal() {
        assertEquals("vip", track.getPrevious("mvp"));
        assertEquals("member", track.getPrevious("vip"));
    }

    @Test @DisplayName("getPrevious returns null at the start of track")
    void getPrevious_atStart() {
        assertNull(track.getPrevious("member"));
    }

    @Test @DisplayName("getPosition returns correct zero-based index")
    void getPosition() {
        assertEquals(0, track.getPosition("member"));
        assertEquals(2, track.getPosition("mvp"));
    }

    @Test @DisplayName("getPosition returns -1 for unknown group")
    void getPosition_unknown() {
        assertEquals(-1, track.getPosition("nonexistent"));
    }

    @Test @DisplayName("duplicate addGroup is ignored")
    void addGroup_noDuplicate() {
        int before = track.size();
        track.addGroup("vip");
        assertEquals(before, track.size());
    }

    @Test @DisplayName("removeGroup removes from track")
    void removeGroup() {
        track.removeGroup("vip");
        assertFalse(track.containsGroup("vip"));
        assertEquals(3, track.size());
    }

    @Test @DisplayName("clearGroups empties the track")
    void clearGroups() {
        track.clearGroups();
        assertTrue(track.isEmpty());
        assertEquals(0, track.size());
    }

    @Test @DisplayName("isEmpty returns true on new track")
    void isEmpty_newTrack() {
        assertTrue(new Track("empty").isEmpty());
    }

    @Test @DisplayName("getGroups is unmodifiable")
    void getGroups_unmodifiable() {
        assertThrows(UnsupportedOperationException.class, () -> track.getGroups().add("hacked"));
    }
}
