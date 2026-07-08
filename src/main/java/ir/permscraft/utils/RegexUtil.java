package ir.permscraft.utils;

import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Regex permission node support.
 *
 * Nodes that start with "r=" are treated as regex patterns.
 * Example:  r=essentials\.(fly|spawn)   matches essentials.fly and essentials.spawn
 *
 */
public final class RegexUtil {

    private static final String REGEX_PREFIX = "r=";

    private RegexUtil() {}

    /**
     * Check if any node in the set is a regex that matches the given permission.
     * Returns null if no regex matches (fall-through to wildcard/exact check).
     */
    public static Boolean matchRegex(Set<String> permissions, String permission) {
        if (permission == null) return null;
        String target = permission.toLowerCase();

        for (String node : permissions) {
            boolean negated = node.startsWith("-");
            String clean = negated ? node.substring(1) : node;

            if (!clean.startsWith(REGEX_PREFIX)) continue;

            String pattern = clean.substring(REGEX_PREFIX.length());
            try {
                if (Pattern.compile(pattern).matcher(target).matches()) {
                    return !negated;
                }
            } catch (PatternSyntaxException ignored) {
                // malformed regex — skip silently
            }
        }
        return null; // no regex matched
    }

    /** Returns true if the node string is a regex node. */
    public static boolean isRegex(String node) {
        if (node == null) return false;
        String clean = node.startsWith("-") ? node.substring(1) : node;
        return clean.startsWith(REGEX_PREFIX);
    }
}
