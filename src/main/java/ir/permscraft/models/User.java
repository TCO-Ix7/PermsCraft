package ir.permscraft.models;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class User {

    private final UUID uuid;
    private String username;
    // FIX: LinkedHashSet is not thread-safe. Wrap in synchronizedSet to keep
    // insertion order (first = primary group) while preventing concurrent modification
    // errors when multiple threads call addGroup simultaneously (UserEdgeCaseTest).
    private final Set<String> groups = Collections.synchronizedSet(new LinkedHashSet<>());
    private final Set<String> permissions = ConcurrentHashMap.newKeySet();
    private String prefix = "";
    private String suffix = "";
    // FIX: meta was a plain HashMap, but setMeta/unsetMeta can be called from async
    // threads (e.g. TimedPermissionManager expiry, Vault async checks). Replace with
    // ConcurrentHashMap so reads and writes are thread-safe without extra locking.
    private final Map<String, String> meta = new ConcurrentHashMap<>();

    // Explicit primary group field — fixes Bug #1 (switchPrimaryGroup)
    private String primaryGroup = "default";

    public User(UUID uuid, String username) {
        this.uuid = uuid;
        this.username = username;
    }

    public UUID getUuid()                   { return uuid; }
    public String getUsername()             { return username; }
    public void setUsername(String u)       { this.username = u; }

    // ── Groups ────────────────────────────────────────────────────────────────

    public Set<String> getGroups()          { return groups; }

    public void addGroup(String group) {
        group = group.toLowerCase();
        groups.add(group);
        if (groups.size() == 1) primaryGroup = group; // first group = default primary
    }
    public void removeGroup(String group) {
        group = group.toLowerCase();
        groups.remove(group);
        // If we just removed the primary, shift to next
        if (primaryGroup.equals(group)) {
            primaryGroup = groups.isEmpty() ? "default" : groups.iterator().next();
        }
    }
    public boolean inGroup(String group)    { return groups.contains(group.toLowerCase()); }

    /**
     * Get primary group — uses explicit field (fixes Bug #1).
     */
    public String getPrimaryGroup() {
        // Validate: if the stored primary is no longer a member group, fall back
        if (groups.contains(primaryGroup)) return primaryGroup;
        return groups.isEmpty() ? "default" : groups.iterator().next();
    }

    /**
     * Set primary group explicitly — used by switchPrimaryGroup().
     * Only updates the in-memory field; DB persistence is via saveUserPrimaryGroup().
     *
     * FIX: The previous implementation did groups.clear() then groups.addAll() as two
     * separate operations. A concurrent addGroup() call between those two operations
     * would silently lose the newly added group. We now synchronize on the groups set
     * so that clear+rebuild is atomic.
     */
    public void setPrimaryGroup(String group) {
        group = group.toLowerCase();
        if (!groups.contains(group) && !group.equals("default")) return;
        this.primaryGroup = group;
        // Reorder groups set so primary is first (for visual consistency).
        // Synchronize on the set itself to make the read-clear-addAll atomic.
        synchronized (groups) {
            Set<String> reordered = new LinkedHashSet<>();
            reordered.add(group);
            groups.forEach(reordered::add);
            groups.clear();
            groups.addAll(reordered);
        }
    }

    // ── Permissions ───────────────────────────────────────────────────────────

    public Set<String> getPermissions()     { return permissions; }
    public void addPermission(String p)     { permissions.add(p); }
    public void removePermission(String p)  { permissions.remove(p); }
    public boolean hasPermission(String p)  { return permissions.contains(p); }

    // ── Prefix / Suffix ───────────────────────────────────────────────────────

    public String getPrefix()               { return prefix; }
    public void setPrefix(String prefix)    { this.prefix = prefix; }
    public String getSuffix()               { return suffix; }
    public void setSuffix(String suffix)    { this.suffix = suffix; }

    // ── Meta ──────────────────────────────────────────────────────────────────

    public Map<String, String> getMeta()                    { return meta; }
    public String getMetaValue(String key)                  { return meta.get(key); }
    /** Alias for PlaceholderAPI expansion compatibility */
    public String getMeta(String key)                       { return meta.get(key); }
    public void setMeta(String key, String value)           { meta.put(key, value); }
    public void unsetMeta(String key)                       { meta.remove(key); }
}
