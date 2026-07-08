package ir.permscraft.models;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A Track is an ordered list of groups that players can progress through.
 * Example: member -> vip -> mvp -> legend
 */
public class Track {

    private final String name;
    private final List<String> groups = new ArrayList<>();

    public Track(String name) {
        this.name = name;
    }

    public String getName() { return name; }

    public List<String> getGroups() { return Collections.unmodifiableList(groups); }

    public void addGroup(String group) {
        if (!groups.contains(group)) groups.add(group);
    }

    /** Clear all groups from this track (used by reorder operations). */
    public void clearGroups() {
        groups.clear();
    }

    public void removeGroup(String group) { groups.remove(group); }

    public boolean containsGroup(String group) { return groups.contains(group); }

    public int getPosition(String group) { return groups.indexOf(group); }

    /** Returns the next group in the track after the given group, or null if at end. */
    public String getNext(String currentGroup) {
        int idx = groups.indexOf(currentGroup);
        if (idx == -1 || idx >= groups.size() - 1) return null;
        return groups.get(idx + 1);
    }

    /** Returns the previous group in the track before the given group, or null if at start. */
    public String getPrevious(String currentGroup) {
        int idx = groups.indexOf(currentGroup);
        if (idx <= 0) return null;
        return groups.get(idx - 1);
    }

    public boolean isEmpty() { return groups.isEmpty(); }
    public int size() { return groups.size(); }
}
