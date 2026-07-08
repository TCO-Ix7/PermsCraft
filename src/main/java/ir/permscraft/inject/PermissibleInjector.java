package ir.permscraft.inject;

import ir.permscraft.PermsCraft;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissibleBase;
import org.bukkit.permissions.PermissionAttachment;

import java.lang.reflect.Field;
import java.util.List;

/**
 * Injects and uninjects {@link PCPermissible} into a {@link Player}.
 *
 * All permission checks for a Bukkit player ultimately delegate to the
 * {@code perm} field on {@code CraftHumanEntity}. By replacing that field
 * with our own {@link PCPermissible} we intercept every single
 * {@code player.hasPermission()} call — directly, without an attachment.
 *
 * credit: lucko (Luck) <luck@lucko.me>
 */
public final class PermissibleInjector {

    private PermissibleInjector() {}

    // ── Reflection fields ─────────────────────────────────────────────────────

    /**
     * {@code CraftHumanEntity.perm} — the PermissibleBase field that Bukkit
     * routes all permission checks through.
     *
     * FIX (Bug #14): may be {@code null} if reflection setup fails (e.g. a
     * future Paper build renames/refactors this internal field). Previously
     * a failure here threw {@link ExceptionInInitializerError}, which made
     * the entire {@code PermissibleInjector} class fail to load — any code
     * referencing it (including {@code inject}/{@code uninject}/{@code get})
     * would then throw {@code NoClassDefFoundError} at runtime, effectively
     * crashing permission checks for every player. Now we log a warning once
     * and every public method becomes a graceful no-op, so PermsCraft falls
     * back to the older attachment-based permission model instead of crashing.
     */
    private static final Field HUMAN_ENTITY_PERMISSIBLE_FIELD;

    /**
     * {@code PermissibleBase.attachments} — needed so we can migrate any
     * existing attachments to the new permissible on injection.
     *
     * FIX (Bug #14): also nullable, see {@link #HUMAN_ENTITY_PERMISSIBLE_FIELD}.
     */
    private static final Field PERMISSIBLE_BASE_ATTACHMENTS_FIELD;

    /**
     * FIX (Bug #14): true if both reflection fields were resolved
     * successfully and direct-permissible injection can be used.
     */
    private static final boolean AVAILABLE;

    /** Records the failure reason for logging, if {@link #AVAILABLE} is false. */
    private static final String UNAVAILABLE_REASON;

    static {
        Field humanEntityField = null;
        Field attachmentsField = null;
        String reason = null;
        try {
            // Works on CraftBukkit, Spigot, Paper, Folia.
            // The field is named "perm" on CraftHumanEntity in all known CB forks.
            Class<?> craftHumanEntity = getCraftHumanEntityClass();
            humanEntityField = craftHumanEntity.getDeclaredField("perm");
            humanEntityField.setAccessible(true);

            attachmentsField = PermissibleBase.class.getDeclaredField("attachments");
            attachmentsField.setAccessible(true);
        } catch (Exception e) {
            // FIX (Bug #14): don't throw ExceptionInInitializerError — that would
            // make this class fail to load entirely, crashing every caller.
            humanEntityField = null;
            attachmentsField = null;
            reason = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        HUMAN_ENTITY_PERMISSIBLE_FIELD = humanEntityField;
        PERMISSIBLE_BASE_ATTACHMENTS_FIELD = attachmentsField;
        AVAILABLE = humanEntityField != null && attachmentsField != null;
        UNAVAILABLE_REASON = reason;
    }

    private static final java.util.concurrent.atomic.AtomicBoolean WARNED_ONCE =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * FIX (Bug #14): logs the reflection-unavailable warning exactly once
     * (not once per player) so the console isn't spammed on a busy server.
     */
    private static void warnUnavailableOnce(PermsCraft plugin) {
        if (WARNED_ONCE.compareAndSet(false, true)) {
            plugin.getLogger().warning("[PermsCraft] Direct-permissible injection is unavailable on this "
                    + "server (" + UNAVAILABLE_REASON + "). Falling back to attachment-based permissions "
                    + "for all players. This is non-fatal but may be slightly slower under heavy "
                    + "permission-check load. Please report this server version to the PermsCraft issue tracker.");
        }
    }

    private static Class<?> getCraftHumanEntityClass() throws ClassNotFoundException {
        // Try standard OBC path first
        String serverPackage = org.bukkit.Bukkit.getServer().getClass().getPackage().getName();
        // Paper/Folia moved CraftBukkit into a fixed package; handle both cases.
        try {
            return Class.forName(serverPackage.replace("org.bukkit.craftbukkit", "org.bukkit.craftbukkit")
                    + ".entity.CraftHumanEntity");
        } catch (ClassNotFoundException e) {
            // Fall-through: try unversioned path used by newer Paper builds
            return Class.forName("org.bukkit.craftbukkit.entity.CraftHumanEntity");
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Inject a {@link PCPermissible} into {@code player}.
     *
     * <p>If injection fails (e.g. unsupported server fork), we log a warning
     * and fall back gracefully — PermsCraft continues operating in the old
     * attachment-based mode.
     */
    public static void inject(Player player, PCPermissible newPermissible, PermsCraft plugin) {
        if (!AVAILABLE) {
            warnUnavailableOnce(plugin);
            return;
        }
        try {
            PermissibleBase old = (PermissibleBase) HUMAN_ENTITY_PERMISSIBLE_FIELD.get(player);

            // Guard: don't double-inject.
            if (old instanceof PCPermissible) return;

            // Warn if another permission plugin has already injected something.
            if (!PermissibleBase.class.equals(old.getClass())) {
                plugin.getLogger().warning("[PermsCraft] Player " + player.getName()
                        + " already has a custom permissible (" + old.getClass().getName() + ")."
                        + " Multiple permission plugins installed?");
            }

            // Migrate any existing third-party attachments so they are not lost.
            @SuppressWarnings("unchecked")
            List<PermissionAttachment> existingAttachments =
                    (List<PermissionAttachment>) PERMISSIBLE_BASE_ATTACHMENTS_FIELD.get(old);
            if (existingAttachments != null) {
                newPermissible.hookedAttachments.addAll(existingAttachments);
                existingAttachments.clear();
            }
            old.clearPermissions();

            newPermissible.setPreviousPermissible(old);
            newPermissible.active.set(true);

            HUMAN_ENTITY_PERMISSIBLE_FIELD.set(player, newPermissible);

        } catch (Exception e) {
            plugin.getLogger().warning("[PermsCraft] Permissible injection failed for "
                    + player.getName() + ": " + e.getMessage()
                    + ". Falling back to attachment mode.");
        }
    }

    /**
     * Remove the {@link PCPermissible} from {@code player} and restore their
     * previous permissible.
     *
     * @param dummy if {@code true}, install a no-op dummy instead of restoring
     *              the original (used on player quit where the player is about
     *              to be removed from the server anyway).
     */
    public static void uninject(Player player, boolean dummy) {
        if (!AVAILABLE) return;
        try {
            PermissibleBase current =
                    (PermissibleBase) HUMAN_ENTITY_PERMISSIBLE_FIELD.get(player);

            if (!(current instanceof PCPermissible pcPerm)) return;

            pcPerm.clearPermissions();
            pcPerm.active.set(false);

            PermissibleBase replacement;
            if (dummy) {
                replacement = new DummyPermissible(player);
            } else {
                replacement = pcPerm.getPreviousPermissible();
                if (replacement == null) replacement = new PermissibleBase(player);
            }

            HUMAN_ENTITY_PERMISSIBLE_FIELD.set(player, replacement);
        } catch (Exception e) {
            // Uninject failure is non-fatal; player is quitting anyway.
        }
    }

    /**
     * Returns the injected {@link PCPermissible} for {@code player}, or
     * {@code null} if we are not injected.
     */
    public static PCPermissible get(Player player) {
        if (!AVAILABLE) return null;
        try {
            PermissibleBase pb = (PermissibleBase) HUMAN_ENTITY_PERMISSIBLE_FIELD.get(player);
            return pb instanceof PCPermissible ? (PCPermissible) pb : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ── DummyPermissible ──────────────────────────────────────────────────────

    /**
     * Lightweight no-op permissible used only as a placeholder after uninject
     * on quit. The player object is about to be invalidated anyway.
     */
    private static final class DummyPermissible extends PermissibleBase {
        DummyPermissible(Player player) { super(player); }

        @Override public boolean hasPermission(String p)    { return false; }
        @Override public boolean hasPermission(org.bukkit.permissions.Permission p) { return false; }
        @Override public boolean isPermissionSet(String p)  { return false; }
        @Override public boolean isPermissionSet(org.bukkit.permissions.Permission p) { return false; }
    }
}
