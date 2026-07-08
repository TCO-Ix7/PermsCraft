package ir.permscraft.logging;

import org.junit.jupiter.api.*;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("LogEntry model")
class LogEntryTest {

    private LogEntry entry;
    private final Instant now = Instant.now();

    @BeforeEach
    void setUp() {
        entry = new LogEntry(
                1L,
                now,
                "Steve",
                LogEntry.Action.USER_PERM_ADD,
                "Alex",
                "essentials.fly"
        );
    }

    @Test @DisplayName("getId returns assigned id")
    void getId() {
        assertEquals(1L, entry.getId());
    }

    @Test @DisplayName("getTimestamp returns the instant")
    void getTimestamp() {
        assertEquals(now, entry.getTimestamp());
    }

    @Test @DisplayName("getActor returns actor name")
    void getActor() {
        assertEquals("Steve", entry.getActor());
    }

    @Test @DisplayName("getAction returns the action enum")
    void getAction() {
        assertEquals(LogEntry.Action.USER_PERM_ADD, entry.getAction());
    }

    @Test @DisplayName("getTarget returns target name")
    void getTarget() {
        assertEquals("Alex", entry.getTarget());
    }

    @Test @DisplayName("getDetail returns detail string")
    void getDetail() {
        assertEquals("essentials.fly", entry.getDetail());
    }

    @Test @DisplayName("toString contains actor, action, target, detail")
    void toString_containsFields() {
        String s = entry.toString();
        assertTrue(s.contains("Steve"));
        assertTrue(s.contains("USER_PERM_ADD"));
        assertTrue(s.contains("Alex"));
        assertTrue(s.contains("essentials.fly"));
    }

    @Test @DisplayName("all Action enum values are defined")
    void action_allEnumValues() {
        assertNotNull(LogEntry.Action.valueOf("GROUP_CREATE"));
        assertNotNull(LogEntry.Action.valueOf("TRACK_PROMOTE"));
        assertNotNull(LogEntry.Action.valueOf("TIMED_PERM_EXPIRE"));
    }

    @Test @DisplayName("CONSOLE is a valid actor name (string field accepts any value)")
    void actor_console() {
        LogEntry consoleEntry = new LogEntry(2L, now, "CONSOLE",
                LogEntry.Action.GROUP_DELETE, "vip", "");
        assertEquals("CONSOLE", consoleEntry.getActor());
    }
}
