package ir.permscraft.context;

import java.util.*;

/**
 * A permission node bound to a required ContextSet.
 *
 * The permission applies when ALL entries in the required context set
 * are satisfied by the player's active ContextSet (AND logic).
 *
 * Examples:
 *   essentials.fly        in  {world=creative}           → fly only in creative world
 *   essentials.fly        in  {world=creative, gamemode=creative}  → fly only when in creative world AND gamemode
 *   some.permission       in  {dimension=nether}          → only in nether dimension
 *   some.permission       in  {world=*}                   → in ANY world (wildcard)
 *   some.permission       in  {}  (global)                → everywhere
 *
 *   PermsCraft stores a FULL ContextSet per node, so you can require
 *   multiple dimensions simultaneously without adding duplicate nodes.
 */
public final class ContextualPermission {

    private final String     permission;
    private final ContextSet requiredContext; // empty = global
    private final boolean    value;           // true = grant, false = deny

    public ContextualPermission(String permission, ContextSet requiredContext, boolean value) {
        this.permission      = permission.toLowerCase().trim();
        this.requiredContext = requiredContext != null ? requiredContext : ContextSet.global();
        this.value           = value;
    }

    /** Legacy single-Context constructor — wraps into a ContextSet. */
    public ContextualPermission(String permission, Context context, boolean value) {
        this(permission, singleToSet(context), value);
    }

    private static ContextSet singleToSet(Context ctx) {
        if (ctx == null || ctx.isGlobal()) return ContextSet.global();
        return ContextSet.builder().put(ctx).build();
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public String     getPermission()      { return permission; }
    public ContextSet getRequiredContext()  { return requiredContext; }
    public boolean    getValue()           { return value; }

    public boolean isGlobal() { return requiredContext.isEmpty(); }

    /**
     * Returns true if this permission should be active given the player's
     * current ContextSet.  Global permissions always apply.
     */
    public boolean appliesIn(ContextSet active) {
        if (isGlobal()) return true;
        return active.satisfiesAll(requiredContext);
    }

    // ── Legacy compatibility (single-Context API) ─────────────────────────────

    /**
     * Legacy: returns the first Context entry from the required set,
     * or Context.global() if the set is empty.
     * Used only by GUI code that hasn't been migrated yet.
     */
    public Context getContext() {
        Map<String, String> m = requiredContext.asMap();
        if (m.isEmpty()) return Context.global();
        Map.Entry<String, String> first = m.entrySet().iterator().next();
        return new Context(first.getKey(), first.getValue());
    }

    /** Legacy: single-context appliesIn (kept for backward compat). */
    public boolean appliesIn(Context active) {
        if (isGlobal()) return true;
        return requiredContext.satisfies(active);
    }

    // ── Display ───────────────────────────────────────────────────────────────

    @Override
    public String toString() {
        String prefix = value ? "" : "-";
        String ctx    = requiredContext.isEmpty() ? "global" : "[" + requiredContext + "]";
        return prefix + permission + " " + ctx;
    }
}
