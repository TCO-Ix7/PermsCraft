package ir.permscraft.models;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class Group {

    private final String name;
    private String displayName;
    private String prefix;
    private String suffix;
    private int weight;
    // FIX: HashSet is not thread-safe — concurrent addPermission calls from multiple
    // threads (e.g. storage load + live update) caused lost writes in GroupEdgeCaseTest.
    // ConcurrentHashMap.newKeySet() is a thread-safe Set with O(1) add/contains.
    private final Set<String> permissions = ConcurrentHashMap.newKeySet();
    private final Set<String> inheritedGroups = ConcurrentHashMap.newKeySet();
    private final Map<String, String> meta = new HashMap<>();

    public Group(String name) {
        this.name = name;
        this.displayName = name;
        this.prefix = "";
        this.suffix = "";
        this.weight = 0;
    }

    public String getName() { return name; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getPrefix() { return prefix; }
    public void setPrefix(String prefix) { this.prefix = prefix; }
    public String getSuffix() { return suffix; }
    public void setSuffix(String suffix) { this.suffix = suffix; }
    public int getWeight() { return weight; }
    public void setWeight(int weight) { this.weight = weight; }
    public Set<String> getPermissions() { return permissions; }
    public Set<String> getInheritedGroups() { return inheritedGroups; }

    public void addPermission(String permission) { permissions.add(permission); }
    public void removePermission(String permission) { permissions.remove(permission); }
    public boolean hasPermission(String permission) { return permissions.contains(permission); }

    public void addInheritance(String groupName) { inheritedGroups.add(groupName); }
    public void removeInheritance(String groupName) { inheritedGroups.remove(groupName); }

    public Map<String, String> getMeta() { return meta; }
    public String getMetaValue(String key) { return meta.get(key); }
    public void setMeta(String key, String value) { meta.put(key, value); }
    public void unsetMeta(String key) { meta.remove(key); }
}
