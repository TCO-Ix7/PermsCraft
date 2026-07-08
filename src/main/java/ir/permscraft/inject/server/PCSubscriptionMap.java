package ir.permscraft.inject.server;

import ir.permscraft.PermsCraft;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permissible;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Replaces the 'permSubs' map inside Bukkit's SimplePluginManager.
 *
 * WHY: Bukkit sometimes checks subscription status (instead of hasPermission) to
 * decide if a Permissible has a node — e.g. Server#broadcast. Without this,
 * online players appear to NOT have broadcast permissions even when PermsCraft
 * grants them.
 *
 * Instead of registering every online player into the real subscription map
 * (memory-heavy), we intercept #get() and return a live view that asks
 *
 */
public final class PCSubscriptionMap implements Map<String, Map<Permissible, Boolean>> {

    private final PermsCraft plugin;
    // Non-player subscriptions (other plugins registering themselves)
    private final Map<Permissible, Set<String>> subs =
            Collections.synchronizedMap(new WeakHashMap<>());

    public PCSubscriptionMap(PermsCraft plugin,
                              Map<String, Map<Permissible, Boolean>> existing) {
        this.plugin = plugin;
        // Migrate existing non-player entries
        for (Entry<String, Map<Permissible, Boolean>> e : existing.entrySet()) {
            for (Permissible p : e.getValue().keySet()) {
                if (!(p instanceof Player)) subscribe(p, e.getKey());
            }
        }
    }

    public void subscribe(Permissible p, String perm) {
        if (p instanceof Player) return;
        subs.computeIfAbsent(p, k -> Collections.synchronizedSet(new HashSet<>())).add(perm);
    }

    public boolean unsubscribe(Permissible p, String perm) {
        if (p instanceof Player) return false;
        Set<String> set = subs.get(p);
        return set != null && set.remove(perm);
    }

    public Set<Permissible> subscribers(String perm) {
        Set<Permissible> result = new HashSet<>();
        // Non-player subscribers
        subs.forEach((p, perms) -> { if (perms.contains(perm)) result.add(p); });
        // Online players — ask PermsCraft directly
        for (Player pl : plugin.getServer().getOnlinePlayers()) {
            if (pl.hasPermission(perm) || pl.isPermissionSet(perm)) result.add(pl);
        }
        return result;
    }

    /** Detach back to plain map for uninject */
    public Map<String, Map<Permissible, Boolean>> detach() {
        Map<String, Map<Permissible, Boolean>> out = new HashMap<>();
        subs.forEach((p, perms) -> {
            for (String perm : perms) {
                out.computeIfAbsent(perm, k -> new WeakHashMap<>()).put(p, true);
            }
        });
        return out;
    }

    // ── Map interface — only get() is actually called by Bukkit ──────────────

    @Override
    public Map<Permissible, Boolean> get(Object key) {
        return new ValueMap((String) key);
    }

    @Override public int size() { return 0; }
    @Override public boolean isEmpty() { return false; }
    @Override public boolean containsKey(Object k) { throw new UnsupportedOperationException(); }
    @Override public boolean containsValue(Object v) { throw new UnsupportedOperationException(); }
    @Override public Map<Permissible, Boolean> put(String k, Map<Permissible, Boolean> v) { throw new UnsupportedOperationException(); }
    @Override public Map<Permissible, Boolean> remove(Object k) { throw new UnsupportedOperationException(); }
    @Override public void putAll(Map<? extends String, ? extends Map<Permissible, Boolean>> m) { throw new UnsupportedOperationException(); }
    @Override public void clear() { throw new UnsupportedOperationException(); }
    @Override public Set<String> keySet() { throw new UnsupportedOperationException(); }
    @Override public Collection<Map<Permissible, Boolean>> values() { throw new UnsupportedOperationException(); }
    @Override public Set<Entry<String, Map<Permissible, Boolean>>> entrySet() { throw new UnsupportedOperationException(); }

    public final class ValueMap implements Map<Permissible, Boolean> {
        private final String perm;
        ValueMap(String perm) { this.perm = perm; }

        @Override public Boolean put(Permissible k, Boolean v) { subscribe(k, perm); return null; }
        @Override public Boolean remove(Object k) { return unsubscribe((Permissible) k, perm) ? true : null; }
        @Override public Set<Permissible> keySet() { return subscribers(perm); }
        @Override public boolean isEmpty() { return false; }
        @Override public int size() { return 1; }
        @Override public void putAll(Map<? extends Permissible, ? extends Boolean> m) { throw new UnsupportedOperationException(); }
        @Override public void clear() { throw new UnsupportedOperationException(); }
        @Override public Collection<Boolean> values() { throw new UnsupportedOperationException(); }
        @Override public Set<Entry<Permissible, Boolean>> entrySet() { throw new UnsupportedOperationException(); }
        @Override public boolean containsKey(Object k) { throw new UnsupportedOperationException(); }
        @Override public boolean containsValue(Object v) { throw new UnsupportedOperationException(); }
        @Override public Boolean get(Object k) { throw new UnsupportedOperationException(); }
    }
}
