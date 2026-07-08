package ir.permscraft.context;

import java.util.Objects;

/**
 * An immutable key=value pair describing one dimension of "where" a permission applies.
 *
 * Built-in keys (resolved automatically from the player's state):
 *   world        — Bukkit world name           e.g. world=survival
 *   server       — server identifier           e.g. server=lobby
 *   gamemode     — player's gamemode           e.g. gamemode=creative
 *   dimension    — NORMAL / NETHER / THE_END   e.g. dimension=nether
 *   world-uuid   — world UUID (collision-safe) e.g. world-uuid=<uuid>
 *
 * Custom keys (set by admins / other plugins):
 *   Any key not in the list above is treated as a custom/static context.
 *   Examples:  region=pvp   rank=vip   flag=fly
 *
 * Wildcard value "*":
 *   world=*  → matches ANY world (all dimensions still filtered by other keys)
 *
 * GLOBAL:
 *   A Context whose key AND value are both "global" matches everything.
 */
public final class Context {

    // ── Built-in key constants ────────────────────────────────────────────────

    public static final String KEY_WORLD      = "world";
    public static final String KEY_SERVER     = "server";
    public static final String KEY_GAMEMODE   = "gamemode";
    public static final String KEY_DIMENSION  = "dimension";
    public static final String KEY_WORLD_UUID = "world-uuid";

    public static final String GLOBAL         = "global";
    public static final String WILDCARD_VALUE = "*";

    // ── Fields ────────────────────────────────────────────────────────────────

    private final String key;
    private final String value;

    public Context(String key, String value) {
        this.key   = key.toLowerCase().trim();
        this.value = value.toLowerCase().trim();
    }

    // ── Factory helpers ───────────────────────────────────────────────────────

    public static Context of(String key, String value)       { return new Context(key, value); }
    public static Context world(String name)                 { return new Context(KEY_WORLD,     name); }
    public static Context server(String name)                { return new Context(KEY_SERVER,    name); }
    public static Context gamemode(String gm)                { return new Context(KEY_GAMEMODE,  gm);   }
    public static Context dimension(String dim)              { return new Context(KEY_DIMENSION, dim);  }
    public static Context worldUuid(String uuid)             { return new Context(KEY_WORLD_UUID, uuid);}
    public static Context global()                           { return new Context(GLOBAL, GLOBAL); }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public String getKey()   { return key; }
    public String getValue() { return value; }

    public boolean isGlobal()   { return GLOBAL.equals(key) && GLOBAL.equals(value); }
    public boolean isWildcard() { return WILDCARD_VALUE.equals(value); }

    /**
     * Check whether this context entry matches a given active context value.
     * Supports wildcard: world=* matches any world.
     */
    public boolean matchesValue(String activeValue) {
        return isWildcard() || value.equals(activeValue.toLowerCase().trim());
    }

    // ── Serialization ─────────────────────────────────────────────────────────

    @Override public String toString() { return key + "=" + value; }

    public static Context fromString(String s) {
        if (s == null || s.isBlank()) return global();
        String[] parts = s.split("=", 2);
        if (parts.length == 2) return new Context(parts[0], parts[1]);
        return global();
    }

    // ── Equality ──────────────────────────────────────────────────────────────

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Context c)) return false;
        return Objects.equals(key, c.key) && Objects.equals(value, c.value);
    }

    @Override public int hashCode() { return Objects.hash(key, value); }
}
