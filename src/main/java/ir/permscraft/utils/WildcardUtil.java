package ir.permscraft.utils;

import java.util.Set;

/**
 * Wildcard + Regex + Sponge permission matching.
 *
 * Priority order (highest to lowest):
 *   1. Regex nodes    (r=pattern)
 *   2. Exact nodes    (specificity 2)
 *   3. Standard wildcard nodes  (essentials.*, specificity 1)
 *   4. Sponge wildcard nodes    (essentials → essentials.fly)
 *
 * Among nodes of the same specificity, negations always win.
 */
public class WildcardUtil {

    public static boolean hasPermission(Set<String> permissions, String permission) {
        if (permission == null || permission.isEmpty()) return false;
        final String target = permission.toLowerCase();

        // ── 1. Regex ──────────────────────────────────────────────────────────
        Boolean regexResult = RegexUtil.matchRegex(permissions, target);
        if (regexResult != null) return regexResult;

        // ── 2 & 3. Exact + Standard Wildcard ─────────────────────────────────
        int bestPositive = 0;
        int bestNegative = 0;

        for (String node : permissions) {
            if (RegexUtil.isRegex(node)) continue;

            boolean negated = node.startsWith("-");
            String clean = negated ? node.substring(1).toLowerCase() : node.toLowerCase();

            // Skip bare nodes (no dot, no star) — those are Sponge-style, handled below
            if (!clean.contains("*") && !clean.equalsIgnoreCase(target)) {
                // could be sponge — skip for now
                if (!clean.equals(target)) {
                    int score = matchScore(clean, target);
                    if (score == 0) continue;
                    if (negated) { if (score > bestNegative) bestNegative = score; }
                    else         { if (score > bestPositive) bestPositive = score; }
                    continue;
                }
            }

            int score = matchScore(clean, target);
            if (score == 0) continue;
            if (negated) { if (score > bestNegative) bestNegative = score; }
            else         { if (score > bestPositive) bestPositive = score; }
        }

        if (bestPositive > 0 || bestNegative > 0) {
            return bestPositive > bestNegative;
        }

        // ── 4. Sponge Wildcard ────────────────────────────────────────────────
        Boolean spongeResult = SpongeWildcardUtil.matchSponge(permissions, target);
        if (spongeResult != null) return spongeResult;

        return false;
    }

    private static int matchScore(String node, String target) {
        if (node.equalsIgnoreCase(target)) return 2;
        if (node.equals("*"))              return 1;
        if (node.endsWith(".*")) {
            String prefix = node.substring(0, node.length() - 2).toLowerCase();
            if (target.startsWith(prefix + ".")) return 1;
        }
        return 0;
    }

    public static boolean matches(String node, String target) {
        if (node.equals("*")) return true;
        if (node.equalsIgnoreCase(target)) return true;
        if (node.endsWith(".*")) {
            String prefix = node.substring(0, node.length() - 2).toLowerCase();
            return target.toLowerCase().startsWith(prefix + ".");
        }
        return false;
    }

    public static java.util.Set<String> expand(Set<String> nodes, Set<String> knownPermissions) {
        java.util.Set<String> expanded = new java.util.HashSet<>();
        for (String node : nodes) {
            boolean negated = node.startsWith("-");
            String clean = negated ? node.substring(1) : node;
            if (clean.contains("*")) {
                for (String known : knownPermissions) {
                    if (matches(clean, known)) expanded.add(negated ? "-" + known : known);
                }
            } else {
                expanded.add(node);
            }
        }
        return expanded;
    }
}
