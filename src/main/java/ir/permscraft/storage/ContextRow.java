package ir.permscraft.storage;

import ir.permscraft.context.ContextualPermission;

/**
 * Typed DTO for a context permission row loaded from storage.
 * The ContextualPermission inside already carries the full required ContextSet.
 */
public record ContextRow(String target, boolean isGroup, ContextualPermission permission) {}
