package ir.permscraft.storage;

import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("LogFilter")
class LogFilterTest {

    // ── construction ─────────────────────────────────────────────────────────

    @Test @DisplayName("all fields are stored correctly")
    void allFields() {
        LogFilter f = new LogFilter("admin1", "player1", "ADD_PERMISSION", 1000L, 2000L, 50, 0);
        assertEquals("admin1",         f.actor());
        assertEquals("player1",        f.target());
        assertEquals("ADD_PERMISSION", f.action());
        assertEquals(1000L,            f.from());
        assertEquals(2000L,            f.to());
        assertEquals(50,               f.limit());
        assertEquals(0,                f.offset());
    }

    @Test @DisplayName("null optional fields are accepted")
    void nullOptionals() {
        LogFilter f = new LogFilter(null, null, null, null, null, 25, 0);
        assertNull(f.actor());
        assertNull(f.target());
        assertNull(f.action());
        assertNull(f.from());
        assertNull(f.to());
        assertEquals(25, f.limit());
    }

    @Test @DisplayName("zero offset is valid")
    void zeroOffset() {
        LogFilter f = new LogFilter(null, null, null, null, null, 100, 0);
        assertEquals(0, f.offset());
    }

    @Test @DisplayName("non-zero offset for pagination")
    void paginationOffset() {
        LogFilter f = new LogFilter(null, null, null, null, null, 25, 50);
        assertEquals(25, f.limit());
        assertEquals(50, f.offset());
    }

    // ── record equality ───────────────────────────────────────────────────────

    @Test @DisplayName("two identical LogFilters are equal")
    void equals_identical() {
        LogFilter a = new LogFilter("actor", "target", "ACTION", 0L, 9999L, 10, 0);
        LogFilter b = new LogFilter("actor", "target", "ACTION", 0L, 9999L, 10, 0);
        assertEquals(a, b);
    }

    @Test @DisplayName("different actor makes them not equal")
    void equals_differentActor() {
        LogFilter a = new LogFilter("actor1", "target", "ACTION", 0L, 9999L, 10, 0);
        LogFilter b = new LogFilter("actor2", "target", "ACTION", 0L, 9999L, 10, 0);
        assertNotEquals(a, b);
    }

    @Test @DisplayName("different limit makes them not equal")
    void equals_differentLimit() {
        LogFilter a = new LogFilter(null, null, null, null, null, 10, 0);
        LogFilter b = new LogFilter(null, null, null, null, null, 20, 0);
        assertNotEquals(a, b);
    }

    @Test @DisplayName("different offset makes them not equal")
    void equals_differentOffset() {
        LogFilter a = new LogFilter(null, null, null, null, null, 10, 0);
        LogFilter b = new LogFilter(null, null, null, null, null, 10, 5);
        assertNotEquals(a, b);
    }

    // ── hashCode ─────────────────────────────────────────────────────────────

    @Test @DisplayName("equal LogFilters have same hashCode")
    void hashCode_equal() {
        LogFilter a = new LogFilter("actor", null, null, 0L, null, 10, 0);
        LogFilter b = new LogFilter("actor", null, null, 0L, null, 10, 0);
        assertEquals(a.hashCode(), b.hashCode());
    }

    // ── edge cases ────────────────────────────────────────────────────────────

    @Test @DisplayName("from=to creates a point-in-time filter")
    void pointInTime() {
        LogFilter f = new LogFilter(null, null, null, 5000L, 5000L, 1, 0);
        assertEquals(f.from(), f.to());
    }

    @Test @DisplayName("large limit value is stored correctly")
    void largeLimit() {
        LogFilter f = new LogFilter(null, null, null, null, null, Integer.MAX_VALUE, 0);
        assertEquals(Integer.MAX_VALUE, f.limit());
    }

    @Test @DisplayName("large offset value is stored correctly")
    void largeOffset() {
        LogFilter f = new LogFilter(null, null, null, null, null, 10, Integer.MAX_VALUE);
        assertEquals(Integer.MAX_VALUE, f.offset());
    }

    @Test @DisplayName("all-null filter except limit/offset")
    void allNullOptionals() {
        LogFilter f = new LogFilter(null, null, null, null, null, 50, 100);
        assertNull(f.actor());
        assertNull(f.target());
        assertNull(f.action());
        assertNull(f.from());
        assertNull(f.to());
        assertEquals(50,  f.limit());
        assertEquals(100, f.offset());
    }
}
