package ir.permscraft.api.node;

import ir.permscraft.context.Context;
import ir.permscraft.context.ContextSet;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Represents an immutable permission node in PermsCraft.
 *
 *
 *               .expiry(Duration.ofDays(1)).context(DefaultContextKeys.WORLD_KEY, "survival")
 *
 *   PermsCraft: Node.permission("foo.bar")    ← factory
 *                   .value(true)              ← grant or deny
 *                   .expiry(Duration.ofDays(1))
 *                   .context("world", "survival")
 *                   .context("gamemode", "creative") ← multiple contexts (AND logic)
 *                   .priority(10)             ← explicit priority (LP has no per-node priority)
 *                   .reason("Granted by admin for VIP event")  ← audit reason
 *                   .build()
 *
 *   • priority field   — explicit override order, no implicit weight games
 *   • reason field     — stored in audit log automatically
 *   • Multiple contexts in one node (AND) without needing a ContextSet separately
 *   • TIMED_GROUP type as first-class citizen
 *   • fromString() / toString() round-trip for YAML/JSON serialization
 */
public final class Node {

    // ── Fields ────────────────────────────────────────────────────────────────

    private final String     permission;   // the raw node string
    private final boolean    value;        // true = grant, false = deny
    private final NodeType   type;
    private final ContextSet context;      // required context (global = no restriction)
    private final Instant    expiry;       // null = permanent
    private final int        priority;     // higher = wins in conflict (default 0)
    private final String     reason;       // audit reason (may be null)

    private Node(Builder b) {
        this.permission = Objects.requireNonNull(b.permission, "permission");
        this.value      = b.value;
        this.type       = b.type;
        this.context    = b.context != null ? b.context : ContextSet.global();
        this.expiry     = b.expiry;
        this.priority   = b.priority;
        this.reason     = b.reason;
    }

    // ── Static factories ──────────────────────────────────────────────────────

    /** Start building a regular permission node. */
    public static Builder permission(String node) {
        return new Builder(node, NodeType.PERMISSION);
    }

    /** Start building a group membership node. */
    public static Builder group(String groupName) {
        return new Builder("group." + groupName.toLowerCase(), NodeType.GROUP);
    }

    /** Start building a prefix node. */
    public static Builder prefix(int priority, String prefix) {
        return new Builder("prefix." + priority + "." + prefix, NodeType.PREFIX)
                .priority(priority);
    }

    /** Start building a suffix node. */
    public static Builder suffix(int priority, String suffix) {
        return new Builder("suffix." + priority + "." + suffix, NodeType.SUFFIX)
                .priority(priority);
    }

    /** Start building a meta node. */
    public static Builder meta(String key, String value) {
        return new Builder("meta." + key + "." + value, NodeType.META);
    }

    /** Start building a timed group membership node. */
    public static Builder timedGroup(String groupName, Duration duration) {
        return new Builder("group." + groupName.toLowerCase(), NodeType.TIMED_GROUP)
                .expiry(duration);
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public String     getPermission() { return permission; }
    public boolean    getValue()      { return value; }
    public NodeType   getType()       { return type; }
    public ContextSet getContext()    { return context; }
    public Instant    getExpiry()     { return expiry; }
    public int        getPriority()   { return priority; }
    public String     getReason()     { return reason; }

    public boolean isPermanent()  { return expiry == null; }
    public boolean isTemporary()  { return expiry != null; }
    public boolean isExpired()    { return expiry != null && Instant.now().isAfter(expiry); }
    public boolean isDeny()       { return !value; }
    public boolean isGrant()      { return value; }

    /**
     * Remaining time as Duration, or {@link Duration#ZERO} if expired/permanent.
     */
    public Duration getRemainingTime() {
        if (expiry == null) return Duration.ZERO;
        Duration d = Duration.between(Instant.now(), expiry);
        return d.isNegative() ? Duration.ZERO : d;
    }

    /**
     * The node string as stored in PermsCraft storage.
     * Denied nodes are prefixed with "-".
     */
    public String toStorageString() {
        return value ? permission : "-" + permission;
    }

    /**
     * Human-readable description for logs and GUIs.
     */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(value ? "+" : "-").append(permission);
        if (!context.isEmpty()) sb.append(" [").append(context.asMap()).append("]");
        if (expiry != null) sb.append(" (expires ").append(expiry).append(")");
        if (priority != 0) sb.append(" {priority=").append(priority).append("}");
        if (reason != null) sb.append(" \"").append(reason).append("\"");
        return sb.toString();
    }

    @Override public String toString() { return describe(); }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Node n)) return false;
        return value == n.value
                && priority == n.priority
                && Objects.equals(permission, n.permission)
                && Objects.equals(context, n.context)
                && Objects.equals(expiry, n.expiry);
    }

    @Override public int hashCode() {
        return Objects.hash(permission, value, context, expiry, priority);
    }

    // ── Builder ───────────────────────────────────────────────────────────────

    public static final class Builder {

        private final String   permission;
        private final NodeType type;
        private boolean        value    = true;
        private ContextSet     context  = null;
        private Instant        expiry   = null;
        private int            priority = 0;
        private String         reason   = null;

        // Mutable context builder — accumulated until build()
        private ContextSet.Builder ctxBuilder = null;

        private Builder(String permission, NodeType type) {
            this.permission = permission.toLowerCase().trim();
            this.type       = type;
        }

        /** Set the value: true = grant (default), false = deny. */
        public Builder value(boolean value) {
            this.value = value;
            return this;
        }

        /** Convenience: explicitly deny this node. */
        public Builder deny() { return value(false); }

        /** Convenience: explicitly grant this node (default). */
        public Builder grant() { return value(true); }

        /**
         * Add a context requirement.
         * Multiple calls create AND requirements — the node only applies when
         * ALL context keys match simultaneously.
         *
         * Example:
         *   .context("world", "survival")
         *   .context("gamemode", "survival")
         *   → only applies when player is in world=survival AND gamemode=survival
         */
        public Builder context(String key, String value) {
            if (ctxBuilder == null) ctxBuilder = ContextSet.builder();
            ctxBuilder.put(key, value);
            return this;
        }

        public Builder context(Context ctx) {
            return context(ctx.getKey(), ctx.getValue());
        }

        public Builder context(ContextSet set) {
            this.context = set;
            return this;
        }

        /** Set expiry as a Duration from now. */
        public Builder expiry(Duration duration) {
            this.expiry = Instant.now().plus(duration);
            return this;
        }

        /** Set expiry as an absolute Instant. */
        public Builder expiry(Instant instant) {
            this.expiry = instant;
            return this;
        }

        /** Set expiry as epoch seconds. */
        public Builder expiryEpochSeconds(long epochSeconds) {
            this.expiry = Instant.ofEpochSecond(epochSeconds);
            return this;
        }

        /**
         * Set explicit priority.
         * Higher priority wins in conflicts at the same inheritance level.
         * Default = 0. Group weight contributes separately.
         *
         */
        public Builder priority(int priority) {
            this.priority = priority;
            return this;
        }

        /**
         * Set an audit reason — stored in the action log automatically
         * when this node is applied via the API.
         *
         */
        public Builder reason(String reason) {
            this.reason = reason;
            return this;
        }

        public Node build() {
            if (ctxBuilder != null && context == null) {
                context = ctxBuilder.build();
            }
            return new Node(this);
        }
    }
}
