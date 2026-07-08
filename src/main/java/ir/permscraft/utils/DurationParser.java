package ir.permscraft.utils;

/**
 * Central duration parser for PermsCraft.
 *
 * Supported units:
 *   s  — seconds   (1s  = 1)
 *   m  — minutes   (1m  = 60)
 *   h  — hours     (1h  = 3600)
 *   d  — days      (1d  = 86400)
 *   w  — weeks     (1w  = 604800)
 *   mo — months    (1mo = 2592000  → 30 days)
 *   y  — years     (1y  = 31536000 → 365 days)
 *
 * Compound durations are fully supported:
 *   1d10h     → 1 day + 10 hours
 *   2w3d12h   → 2 weeks + 3 days + 12 hours
 *   1mo2w     → 1 month + 2 weeks
 *   1y6mo     → 1 year + 6 months
 *   30m45s    → 30 minutes + 45 seconds
 *
 * Case-insensitive. Whitespace between tokens is ignored.
 * "mo" must come before "m" in the switch to be matched correctly.
 *
 * Returns total seconds as long.
 * Throws {@link IllegalArgumentException} on invalid input.
 */
public final class DurationParser {

    private DurationParser() {}

    // ── Unit constants (seconds) ───────────────────────────────────────────────

    public static final long SECOND = 1L;
    public static final long MINUTE = 60L;
    public static final long HOUR   = 3_600L;
    public static final long DAY    = 86_400L;
    public static final long WEEK   = 604_800L;
    public static final long MONTH  = 2_592_000L;   // 30 days
    public static final long YEAR   = 31_536_000L;  // 365 days

    /**
     * Parse a duration string into total seconds.
     *
     * @param input duration string (e.g. "1d", "2h30m", "1mo", "1y6mo2w3d")
     * @return total seconds
     * @throws IllegalArgumentException if the string is blank, contains no
     *         recognised unit, or contains a negative/zero amount
     */
    public static long parse(String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException(
                "Duration cannot be empty. Examples: 1d, 12h, 30m, 1w, 1mo, 1y");
        }

        String s = input.trim().toLowerCase();
        long total = 0L;
        int  i     = 0;
        int  len   = s.length();
        boolean parsed = false;

        while (i < len) {
            // Skip whitespace between tokens
            while (i < len && s.charAt(i) == ' ') i++;
            if (i >= len) break;

            // Read numeric part
            int numStart = i;
            while (i < len && Character.isDigit(s.charAt(i))) i++;

            if (i == numStart) {
                throw new IllegalArgumentException(
                    "Expected a number at position " + i + " in \"" + input + "\"");
            }

            long amount;
            try {
                amount = Long.parseLong(s.substring(numStart, i));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                    "Number too large in \"" + input + "\"");
            }
            if (amount < 0) {
                throw new IllegalArgumentException(
                    "Duration amounts must be positive, got " + amount);
            }

            // Read unit — "mo" is two chars, everything else is one char
            if (i >= len) {
                throw new IllegalArgumentException(
                    "Missing unit after " + amount + " in \"" + input + "\". "
                    + "Valid units: s, m, h, d, w, mo, y");
            }

            long multiplier;
            // Check for two-char unit "mo" first
            if (i + 1 < len && s.charAt(i) == 'm' && s.charAt(i + 1) == 'o') {
                multiplier = MONTH;
                i += 2;
            } else {
                char unit = s.charAt(i);
                i++;
                multiplier = switch (unit) {
                    case 's' -> SECOND;
                    case 'm' -> MINUTE;
                    case 'h' -> HOUR;
                    case 'd' -> DAY;
                    case 'w' -> WEEK;
                    case 'y' -> YEAR;
                    default  -> throw new IllegalArgumentException(
                        "Unknown duration unit '" + unit + "' in \"" + input + "\". "
                        + "Valid units: s, m, h, d, w, mo, y");
                };
            }

            total  += amount * multiplier;
            parsed  = true;
        }

        if (!parsed || total == 0) {
            throw new IllegalArgumentException(
                "Could not parse any duration from \"" + input + "\". "
                + "Examples: 1s, 1m, 1h, 1d, 1w, 1mo, 1y, 1d10h, 2w3d12h30m");
        }

        return total;
    }

    /**
     * Parse without throwing — returns {@code -1} if input is invalid.
     * Useful in places where you want to handle errors gracefully without try/catch.
     */
    public static long parseOrNegative(String input) {
        try { return parse(input); }
        catch (IllegalArgumentException e) { return -1L; }
    }

    /**
     * Format a duration in seconds back to a human-readable string.
     * Uses the largest unit first.
     *
     * Examples:
     *   31536000 → "1y"
     *   90061    → "1d1h1m1s"
     *   3600     → "1h"
     *   0        → "0s"
     */
    public static String format(long seconds) {
        if (seconds <= 0) return "0s";

        StringBuilder sb = new StringBuilder();
        long rem = seconds;

        long years   = rem / YEAR;   rem %= YEAR;
        long months  = rem / MONTH;  rem %= MONTH;
        long weeks   = rem / WEEK;   rem %= WEEK;
        long days    = rem / DAY;    rem %= DAY;
        long hours   = rem / HOUR;   rem %= HOUR;
        long minutes = rem / MINUTE; rem %= MINUTE;
        long secs    = rem;

        if (years   > 0) sb.append(years).append('y');
        if (months  > 0) sb.append(months).append("mo");
        if (weeks   > 0) sb.append(weeks).append('w');
        if (days    > 0) sb.append(days).append('d');
        if (hours   > 0) sb.append(hours).append('h');
        if (minutes > 0) sb.append(minutes).append('m');
        if (secs    > 0) sb.append(secs).append('s');

        return sb.toString();
    }

    /**
     * User-facing hint string shown in GUIs and command output.
     */
    public static final String HINT =
        "&7Units: &es &7= sec, &em &7= min, &eh &7= hour, "
        + "&ed &7= day, &ew &7= week, &emo &7= month, &ey &7= year\n"
        + "&7Examples: &b1d &7| &b12h &7| &b30m &7| &b1w &7| "
        + "&b1mo &7| &b1y &7| &b1d10h &7| &b2w3d12h30m";
}
