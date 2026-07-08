package ir.permscraft.logging;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("LogEntry — action enum & edge cases")
class LogEntryActionTest {

    @ParameterizedTest(name = "Action.{0} can be stored in LogEntry")
    @EnumSource(LogEntry.Action.class)
    @DisplayName("every Action enum value can be stored and retrieved")
    void allActions_storeAndRetrieve(LogEntry.Action action) {
        LogEntry e = new LogEntry(1L, Instant.now(), "actor", action, "target", "detail");
        assertEquals(action, e.getAction());
    }

    @Test @DisplayName("id 0 is valid")
    void id_zero() {
        LogEntry e = new LogEntry(0L, Instant.now(), "a", LogEntry.Action.GROUP_CREATE, "t", "d");
        assertEquals(0L, e.getId());
    }

    @Test @DisplayName("very large id is stored without overflow")
    void id_large() {
        LogEntry e = new LogEntry(Long.MAX_VALUE, Instant.now(), "a",
                LogEntry.Action.TRACK_CREATE, "t", "d");
        assertEquals(Long.MAX_VALUE, e.getId());
    }

    @Test @DisplayName("empty actor string is stored")
    void actor_empty() {
        LogEntry e = new LogEntry(1L, Instant.now(), "", LogEntry.Action.GROUP_DELETE, "t", "d");
        assertEquals("", e.getActor());
    }

    @Test @DisplayName("empty detail string is stored")
    void detail_empty() {
        LogEntry e = new LogEntry(1L, Instant.now(), "a", LogEntry.Action.GROUP_DELETE, "t", "");
        assertEquals("", e.getDetail());
    }

    @Test @DisplayName("toString never returns null")
    void toString_neverNull() {
        for (LogEntry.Action action : LogEntry.Action.values()) {
            LogEntry e = new LogEntry(1L, Instant.now(), "a", action, "t", "d");
            assertNotNull(e.toString());
        }
    }

    @Test @DisplayName("TIMED_PERM_ADD action is stored correctly")
    void timedPermAdd() {
        LogEntry e = new LogEntry(5L, Instant.now(), "Steve",
                LogEntry.Action.TIMED_PERM_ADD, "Alex", "essentials.fly:3600");
        assertEquals(LogEntry.Action.TIMED_PERM_ADD, e.getAction());
        assertEquals("essentials.fly:3600", e.getDetail());
    }

    @Test @DisplayName("TIMED_PERM_EXPIRE action is stored correctly")
    void timedPermExpire() {
        LogEntry e = new LogEntry(6L, Instant.now(), "SYSTEM",
                LogEntry.Action.TIMED_PERM_EXPIRE, "Alex", "essentials.fly");
        assertEquals(LogEntry.Action.TIMED_PERM_EXPIRE, e.getAction());
        assertEquals("SYSTEM", e.getActor());
    }
}
