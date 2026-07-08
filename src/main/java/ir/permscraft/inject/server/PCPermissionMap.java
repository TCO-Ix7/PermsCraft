package ir.permscraft.inject.server;

import ir.permscraft.PermsCraft;
import org.bukkit.permissions.Permission;
import org.bukkit.plugin.PluginManager;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Replaces the 'permissions' map inside Bukkit's SimplePluginManager.
 *
 * WHY: When a plugin registers a Permission via Bukkit API, PermsCraft needs to:
 *   1. Know about all registered permissions (for wildcard expansion, /pc tree, etc.)
 *   2. Invalidate caches that depend on the permission tree when child relationships change.
 *
 */
public final class PCPermissionMap extends AbstractMap<String, Permission> {

    private final Map<String, Permission> delegate = new ConcurrentHashMap<>();
    private final PermsCraft plugin;

    public PCPermissionMap(PermsCraft plugin, Map<String, Permission> existing) {
        this.plugin = plugin;
        delegate.putAll(existing);
    }

    @Override
    public Permission put(String key, Permission value) {
        if (key == null || value == null) return null;
        Permission old = delegate.put(key.toLowerCase(Locale.ROOT), value);
        invalidateCaches();
        return old;
    }

    @Override
    public void putAll(Map<? extends String, ? extends Permission> m) {
        for (Entry<? extends String, ? extends Permission> e : m.entrySet()) {
            delegate.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
        }
        invalidateCaches();
    }

    @Override
    public Permission remove(Object key) {
        Permission old = delegate.remove(key);
        if (old != null) invalidateCaches();
        return old;
    }

    @Override
    public Permission get(Object key) {
        if (key == null) return null;
        return delegate.get(((String) key).toLowerCase(Locale.ROOT));
    }

    @Override
    public boolean containsKey(Object key) {
        return key != null && delegate.containsKey(((String) key).toLowerCase(Locale.ROOT));
    }

    @Override
    public Set<Entry<String, Permission>> entrySet() {
        return delegate.entrySet();
    }

    /** Convert back to a plain HashMap for uninject. */
    public Map<String, Permission> detach() {
        return new HashMap<>(delegate);
    }

    private void invalidateCaches() {
        // Invalidate permission cache so wildcard expansion picks up new nodes
        if (plugin.getPermissionCache() != null) {
            plugin.getPermissionCache().invalidateAll();
        }
    }
}
