package ir.permscraft.api.node;

/**
 * The type of a permission node.
 *
 */
public enum NodeType {
    /** A regular permission node (e.g. essentials.fly) */
    PERMISSION,
    /** A group membership node (e.g. group.admin) */
    GROUP,
    /** A prefix meta node */
    PREFIX,
    /** A suffix meta node */
    SUFFIX,
    /** An arbitrary key=value meta node */
    META,
    /** A temporary (timed) permission node */
    TIMED_PERMISSION,
    /** A temporary (timed) group membership */
    TIMED_GROUP,
    /** A context-bound permission node */
    CONTEXT_PERMISSION
}
