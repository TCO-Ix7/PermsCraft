package ir.permscraft.utils;

import java.util.Set;

/**
 * Sponge-style wildcard matching.
 *
 * Standard wildcard:  essentials.*  matches  essentials.fly
 * Sponge wildcard:    essentials     matches  essentials.fly  (no dot-star needed)
 *
 * In Sponge's model, a bare parent node implicitly grants all children.
 * Example: giving "essentials" grants "essentials.fly", "essentials.spawn", etc.
 *
 * Priority: Sponge wildcards are checked AFTER exact and standard wildcards,
 * so an explicit deny (-essentials.fly) always wins.
 *
 */
public final class SpongeWildcardUtil {

    private SpongeWildcardUtil() {}

    /**
     * Check if any Sponge-style node matches the target.
     * Returns null if no Sponge node matches (fall-through).
     */
    public static Boolean matchSponge(Set<String> permissions, String permission) {
        if (permission == null || !permission.contains(".")) return null;
        String target = permission.toLowerCase();

        Boolean result = null;
        int bestLen = -1; // prefer longer (more specific) parent

        for (String node : permissions) {
            boolean negated = node.startsWith("-");
            String clean = negated ? node.substring(1).toLowerCase() : node.toLowerCase();

            // Skip standard wildcards and regex — handled elsewhere
            if (clean.contains("*") || clean.startsWith("r=")) continue;

            // Sponge match: target must start with "clean."
            if (target.startsWith(clean + ".") && clean.length() > bestLen) {
                bestLen = clean.length();
                result  = !negated;
            }
        }
        return result;
    }
}
