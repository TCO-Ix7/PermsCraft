package ir.permscraft.context;

import java.util.*;

/**
 * An immutable set of Context entries that collectively describe a player's
 * current "state" — world, server, gamemode, dimension, and any custom keys.
 *
 * with richer semantics:
 *
 *   • Multi-key: a ContextSet holds one value per key (world, gamemode, etc.)
 *     simultaneously — not just a single key=value pair.
 *
 *   • Matching: a ContextualPermission applies when ALL of its required
 *     context keys match the active ContextSet (AND logic across keys).
 *
 *   • Wildcard values: world=* matches any world.
 *
 *   • Custom keys: any key not built-in is stored and matched normally,
 *     so plugins / admins can add arbitrary dimensions.
 *
 * Usage:
 *   ContextSet active = ContextSet.builder()
 *       .put(Context.KEY_WORLD,    "survival")
 *       .put(Context.KEY_GAMEMODE, "survival")
 *       .put(Context.KEY_DIMENSION,"normal")
 *       .build();
 *
 *   contextualPermission.appliesIn(active);
 */
public final class ContextSet {

    // Internally keyed by context key → value (one value per key)
    private final Map<String, String> map;

    private ContextSet(Map<String, String> map) {
        this.map = Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }

    // ── Builder ───────────────────────────────────────────────────────────────

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private final Map<String, String> map = new LinkedHashMap<>();

        public Builder put(String key, String value) {
            map.put(key.toLowerCase().trim(), value.toLowerCase().trim());
            return this;
        }

        public Builder put(Context ctx) {
            return put(ctx.getKey(), ctx.getValue());
        }

        public ContextSet build() { return new ContextSet(map); }
    }

    // ── Global singleton ──────────────────────────────────────────────────────

    private static final ContextSet GLOBAL = builder().build(); // empty = global
    public static ContextSet global() { return GLOBAL; }

    // ── Query ─────────────────────────────────────────────────────────────────

    /** Returns the active value for a key, or null if not present. */
    public String get(String key) { return map.get(key.toLowerCase().trim()); }

    public boolean containsKey(String key) { return map.containsKey(key.toLowerCase().trim()); }

    public boolean isEmpty() { return map.isEmpty(); }

    public Map<String, String> asMap() { return map; }

    /**
     * Check whether a single required Context entry is satisfied by this set.
     *
     * Rules:
     *   1. A global Context (key=global, value=global) always matches.
     *   2. If the active set does not contain the required key → no match.
     *   3. Wildcard value (*) matches any active value for that key.
     *   4. Otherwise an exact (case-insensitive) value match is required.
     */
    public boolean satisfies(Context required) {
        if (required.isGlobal()) return true;
        String activeValue = map.get(required.getKey());
        if (activeValue == null) return false;
        return required.matchesValue(activeValue);
    }

    /**
     * Check whether ALL entries in a required ContextSet are satisfied
     * (AND logic).  An empty required set = global = always matches.
     */
    public boolean satisfiesAll(ContextSet required) {
        if (required.isEmpty()) return true;
        for (Map.Entry<String, String> entry : required.map.entrySet()) {
            Context req = new Context(entry.getKey(), entry.getValue());
            if (!satisfies(req)) return false;
        }
        return true;
    }

    @Override
    public String toString() {
        if (map.isEmpty()) return "global";
        StringBuilder sb = new StringBuilder();
        map.forEach((k, v) -> { if (!sb.isEmpty()) sb.append(", "); sb.append(k).append('=').append(v); });
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ContextSet c)) return false;
        return map.equals(c.map);
    }

    @Override public int hashCode() { return map.hashCode(); }
}
