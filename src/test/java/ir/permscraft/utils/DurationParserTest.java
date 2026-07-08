package ir.permscraft.utils;

import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DurationParser")
class DurationParserTest {

    // ── parse: single units ─────────────────────────────────────────────────

    @Test @DisplayName("parses seconds")
    void parse_seconds() {
        assertEquals(45, DurationParser.parse("45s"));
    }

    @Test @DisplayName("parses minutes")
    void parse_minutes() {
        assertEquals(30 * 60, DurationParser.parse("30m"));
    }

    @Test @DisplayName("parses hours")
    void parse_hours() {
        assertEquals(2 * 3600, DurationParser.parse("2h"));
    }

    @Test @DisplayName("parses days")
    void parse_days() {
        assertEquals(86400, DurationParser.parse("1d"));
    }

    @Test @DisplayName("parses weeks")
    void parse_weeks() {
        assertEquals(604800, DurationParser.parse("1w"));
    }

    @Test @DisplayName("parses months as 30 days")
    void parse_months() {
        assertEquals(2_592_000, DurationParser.parse("1mo"));
    }

    @Test @DisplayName("parses years as 365 days")
    void parse_years() {
        assertEquals(31_536_000, DurationParser.parse("1y"));
    }

    // ── parse: compound durations ───────────────────────────────────────────

    @Test @DisplayName("compound: days + hours")
    void parse_compound_daysHours() {
        assertEquals(86400 + 10 * 3600, DurationParser.parse("1d10h"));
    }

    @Test @DisplayName("compound: weeks + days + hours")
    void parse_compound_weeksDaysHours() {
        long expected = 2 * 604800 + 3 * 86400 + 12 * 3600;
        assertEquals(expected, DurationParser.parse("2w3d12h"));
    }

    @Test @DisplayName("compound: months + weeks")
    void parse_compound_monthsWeeks() {
        long expected = 2_592_000 + 2 * 604800;
        assertEquals(expected, DurationParser.parse("1mo2w"));
    }

    @Test @DisplayName("compound: years + months")
    void parse_compound_yearsMonths() {
        long expected = 31_536_000 + 6L * 2_592_000;
        assertEquals(expected, DurationParser.parse("1y6mo"));
    }

    @Test @DisplayName("compound: minutes + seconds")
    void parse_compound_minutesSeconds() {
        assertEquals(30 * 60 + 45, DurationParser.parse("30m45s"));
    }

    @Test @DisplayName("'mo' is correctly distinguished from 'm' (minutes)")
    void parse_moVsM_distinction() {
        // "1m" = 60 seconds, "1mo" = 2,592,000 seconds — must not collide
        assertEquals(60, DurationParser.parse("1m"));
        assertEquals(2_592_000, DurationParser.parse("1mo"));
    }

    @Test @DisplayName("'1mo1m' correctly separates month and minute tokens")
    void parse_moThenM() {
        assertEquals(2_592_000 + 60, DurationParser.parse("1mo1m"));
    }

    // ── parse: formatting tolerance ─────────────────────────────────────────

    @Test @DisplayName("is case-insensitive")
    void parse_caseInsensitive() {
        assertEquals(86400, DurationParser.parse("1D"));
        assertEquals(86400 + 3600, DurationParser.parse("1D1H"));
    }

    @Test @DisplayName("ignores whitespace between tokens")
    void parse_ignoresWhitespace() {
        assertEquals(86400 + 3600, DurationParser.parse("1d 1h"));
        assertEquals(86400 + 3600, DurationParser.parse(" 1d   1h "));
    }

    @Test @DisplayName("multi-digit amounts are parsed correctly")
    void parse_multiDigitAmount() {
        assertEquals(120 * 60, DurationParser.parse("120m"));
        assertEquals(365L * 86400, DurationParser.parse("365d"));
    }

    // ── parse: invalid input ─────────────────────────────────────────────────

    @Test @DisplayName("null input throws IllegalArgumentException")
    void parse_null_throws() {
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parse(null));
    }

    @Test @DisplayName("blank input throws IllegalArgumentException")
    void parse_blank_throws() {
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parse(""));
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parse("   "));
    }

    @Test @DisplayName("unknown unit throws IllegalArgumentException")
    void parse_unknownUnit_throws() {
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parse("5x"));
    }

    @Test @DisplayName("missing unit throws IllegalArgumentException")
    void parse_missingUnit_throws() {
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parse("5"));
    }

    @Test @DisplayName("negative amount throws IllegalArgumentException")
    void parse_negativeAmount_throws() {
        // The leading '-' is not a digit, so this is treated as "no number found"
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parse("-5d"));
    }

    @Test @DisplayName("non-numeric token throws IllegalArgumentException")
    void parse_nonNumericToken_throws() {
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parse("abc"));
    }

    @Test @DisplayName("zero duration throws IllegalArgumentException")
    void parse_zero_throws() {
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parse("0s"));
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parse("0d0h"));
    }

    // ── parseOrNegative ───────────────────────────────────────────────────────

    @Test @DisplayName("parseOrNegative returns parsed value for valid input")
    void parseOrNegative_valid() {
        assertEquals(86400, DurationParser.parseOrNegative("1d"));
    }

    @Test @DisplayName("parseOrNegative returns -1 for invalid input")
    void parseOrNegative_invalid() {
        assertEquals(-1L, DurationParser.parseOrNegative("notaduration"));
        assertEquals(-1L, DurationParser.parseOrNegative(""));
        assertEquals(-1L, DurationParser.parseOrNegative(null));
    }

    // ── format ────────────────────────────────────────────────────────────────

    @Test @DisplayName("format: zero or negative seconds returns '0s'")
    void format_zeroOrNegative() {
        assertEquals("0s", DurationParser.format(0));
        assertEquals("0s", DurationParser.format(-100));
    }

    @Test @DisplayName("format: pure seconds")
    void format_seconds() {
        assertEquals("45s", DurationParser.format(45));
    }

    @Test @DisplayName("format: exact hour with no remainder omits smaller units")
    void format_exactHour() {
        assertEquals("1h", DurationParser.format(3600));
    }

    @Test @DisplayName("format: exact year with no remainder")
    void format_exactYear() {
        assertEquals("1y", DurationParser.format(31_536_000));
    }

    @Test @DisplayName("format: compound days/hours/minutes/seconds")
    void format_compound() {
        // 1 day, 1 hour, 1 minute, 1 second = 86400 + 3600 + 60 + 1 = 90061
        assertEquals("1d1h1m1s", DurationParser.format(90061));
    }

    @Test @DisplayName("format: months and weeks appear in descending order")
    void format_monthsAndWeeks() {
        long seconds = 2_592_000 + 2 * 604800; // 1mo 2w
        assertEquals("1mo2w", DurationParser.format(seconds));
    }

    @Test @DisplayName("format round-trips through parse for compound durations")
    void format_parse_roundTrip() {
        long original = DurationParser.parse("2w3d12h30m");
        String formatted = DurationParser.format(original);
        long reparsed = DurationParser.parse(formatted);
        assertEquals(original, reparsed);
    }

    // ── HINT constant ─────────────────────────────────────────────────────────

    @Test @DisplayName("HINT constant is non-null and mentions all units")
    void hint_mentionsAllUnits() {
        assertNotNull(DurationParser.HINT);
        for (String unit : new String[]{"1d", "1w", "1mo", "1y", "12h", "30m"}) {
            assertTrue(DurationParser.HINT.contains(unit), "HINT should mention " + unit);
        }
    }

    // ── constants sanity ─────────────────────────────────────────────────────

    @Test @DisplayName("unit constants have correct relative magnitudes")
    void constants_relativeMagnitudes() {
        assertEquals(1L, DurationParser.SECOND);
        assertEquals(60L, DurationParser.MINUTE);
        assertEquals(60L * 60, DurationParser.HOUR);
        assertEquals(24L * 60 * 60, DurationParser.DAY);
        assertEquals(7L * 24 * 60 * 60, DurationParser.WEEK);
        assertEquals(30L * 24 * 60 * 60, DurationParser.MONTH);
        assertEquals(365L * 24 * 60 * 60, DurationParser.YEAR);
    }
}
