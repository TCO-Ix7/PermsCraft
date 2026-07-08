package ir.permscraft.models;

import org.junit.jupiter.api.*;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TimedPermission model")
class TimedPermissionTest {

    @Test @DisplayName("isExpired returns true for past expiry")
    void isExpired_past() {
        TimedPermission tp = new TimedPermission(
                "uuid-1", false, "essentials.fly",
                Instant.now().minusSeconds(60)
        );
        assertTrue(tp.isExpired());
    }

    @Test @DisplayName("isExpired returns false for future expiry")
    void isExpired_future() {
        TimedPermission tp = new TimedPermission(
                "uuid-1", false, "essentials.fly",
                Instant.now().plusSeconds(3600)
        );
        assertFalse(tp.isExpired());
    }

    @Test @DisplayName("getPermission returns the permission node")
    void getPermission() {
        TimedPermission tp = new TimedPermission(
                "uuid-1", false, "essentials.fly",
                Instant.now().plusSeconds(100)
        );
        assertEquals("essentials.fly", tp.getPermission());
    }

    @Test @DisplayName("getTarget returns the target string")
    void getTarget() {
        TimedPermission tp = new TimedPermission(
                "some-uuid", false, "essentials.fly",
                Instant.now().plusSeconds(100)
        );
        assertEquals("some-uuid", tp.getTarget());
    }

    @Test @DisplayName("isGroup returns true when target is a group")
    void isGroup_true() {
        TimedPermission tp = new TimedPermission(
                "admin", true, "essentials.fly",
                Instant.now().plusSeconds(100)
        );
        assertTrue(tp.isGroup());
    }

    @Test @DisplayName("isGroup returns false for player target")
    void isGroup_false() {
        TimedPermission tp = new TimedPermission(
                "some-uuid", false, "essentials.fly",
                Instant.now().plusSeconds(100)
        );
        assertFalse(tp.isGroup());
    }

    @Test @DisplayName("getFormattedExpiry shows days when over 24h remain")
    void getFormattedExpiry_days() {
        TimedPermission tp = new TimedPermission(
                "uuid-1", false, "perm",
                Instant.now().plusSeconds(86400 * 3 + 7200) // 3 days + 2 hours
        );
        String fmt = tp.getFormattedExpiry();
        assertTrue(fmt.startsWith("3d"), "Expected to start with '3d', got: " + fmt);
    }

    @Test @DisplayName("getFormattedExpiry shows hours when under 24h remain")
    void getFormattedExpiry_hours() {
        TimedPermission tp = new TimedPermission(
                "uuid-1", false, "perm",
                Instant.now().plusSeconds(7200 + 300) // 2h 5m
        );
        String fmt = tp.getFormattedExpiry();
        assertTrue(fmt.startsWith("2h"), "Expected to start with '2h', got: " + fmt);
    }

    @Test @DisplayName("getFormattedExpiry shows 'Expired' for past expiry")
    void getFormattedExpiry_expired() {
        TimedPermission tp = new TimedPermission(
                "uuid-1", false, "perm",
                Instant.now().minusSeconds(10)
        );
        assertEquals("Expired", tp.getFormattedExpiry());
    }
}
