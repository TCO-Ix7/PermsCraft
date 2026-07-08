package ir.permscraft.api;

/**
 * Represents the result of a permission check as a three-state value.
 *
 *
 * WHY:
 *   A plain boolean cannot distinguish between "explicitly denied" and
 *   "not set at all". For example, a plugin may want to apply a fallback
 *   default only when the permission is UNDEFINED, but not when it was
 *   explicitly set to false.
 *
 * USAGE:
 *   Tristate result = api.getUserManager().checkPermission(uuid, "my.perm");
 *   switch (result) {
 *     case TRUE      -> // explicitly granted
 *     case FALSE     -> // explicitly denied  (negation node like -my.perm)
 *     case UNDEFINED -> // not set anywhere — apply your own default
 *   }
 */
public enum Tristate {

    /** The permission is explicitly granted (true). */
    TRUE,

    /** The permission is explicitly denied (negation node). */
    FALSE,

    /** The permission is not set anywhere in the inheritance chain. */
    UNDEFINED;

    // ── Factories ─────────────────────────────────────────────────────────────

    /** Convert a Boolean (nullable) to a Tristate. */
    public static Tristate of(Boolean value) {
        if (value == null) return UNDEFINED;
        return value ? TRUE : FALSE;
    }

    /** Convert a primitive boolean. Never returns UNDEFINED. */
    public static Tristate of(boolean value) {
        return value ? TRUE : FALSE;
    }

    // ── Conversions ───────────────────────────────────────────────────────────

    /**
     * Convert to boolean using {@code def} as the value for UNDEFINED.
     *
     * @param def the fallback boolean when this Tristate is UNDEFINED
     */
    public boolean asBoolean(boolean def) {
        return switch (this) {
            case TRUE      -> true;
            case FALSE     -> false;
            case UNDEFINED -> def;
        };
    }

    /**
     * Convert to boolean with {@code false} as the fallback for UNDEFINED.
     * Equivalent to {@code asBoolean(false)}.
     */
    public boolean asBoolean() {
        return asBoolean(false);
    }

    /** Whether the value is set (not UNDEFINED). */
    public boolean isDefined() {
        return this != UNDEFINED;
    }
}
