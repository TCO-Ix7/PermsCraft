package ir.permscraft.managers;

import ir.permscraft.FoliaScheduler;
import ir.permscraft.PermsCraft;
import ir.permscraft.models.Track;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FIX: TrackManager now delegates ALL storage operations through StorageBackend.
 * Previously it bypassed the interface and called SqlStorage directly, which:
 *   1. Caused NullPointerExceptions with MongoDB (no SQL connection).
 *   2. Used MySQL-only INSERT IGNORE syntax on PostgreSQL/SQLite/H2.
 *   3. Made tracks in-memory-only on MongoDB (lost on restart).
 *
 * Now track persistence works correctly on every supported backend.
 */
public class TrackManager {

    private final PermsCraft plugin;
    private final Map<String, Track> tracks = new ConcurrentHashMap<>();

    public TrackManager(PermsCraft plugin) {
        this.plugin = plugin;
        loadTracks();
    }

    // ── load ─────────────────────────────────────────────────────────────────

    public void loadTracks() {
        tracks.clear();
        plugin.getStorage().loadAllTracks().forEach(t ->
            tracks.put(t.getName().toLowerCase(), t));
        plugin.getLogger().info("[PermsCraft] Loaded " + tracks.size() + " tracks.");
    }

    // ── public API ───────────────────────────────────────────────────────────

    public Track getTrack(String name) { return tracks.get(name.toLowerCase()); }
    public Collection<Track> getAllTracks() { return tracks.values(); }
    public boolean trackExists(String name) { return tracks.containsKey(name.toLowerCase()); }

    public Track createTrack(String name) {
        Track track = new Track(name.toLowerCase());
        tracks.put(name.toLowerCase(), track);
        // FIX: async DB write — was blocking the calling thread (main thread for
        // command/GUI callers) on a remote-DB round-trip.
        FoliaScheduler.runAsync(plugin, () ->
                plugin.getStorage().saveTrack(track));
        return track;
    }

    public void deleteTrack(String name) {
        tracks.remove(name.toLowerCase());
        // FIX: async DB write
        FoliaScheduler.runAsync(plugin, () ->
                plugin.getStorage().deleteTrack(name.toLowerCase()));
    }

    public void addGroupToTrack(String trackName, String groupName) {
        Track track = getTrack(trackName);
        if (track == null) return;
        track.addGroup(groupName);
        // FIX: async DB write
        FoliaScheduler.runAsync(plugin, () ->
                plugin.getStorage().saveTrack(track));
    }

    public void removeGroupFromTrack(String trackName, String groupName) {
        Track track = getTrack(trackName);
        if (track == null) return;
        track.removeGroup(groupName);
        // FIX: async DB write
        FoliaScheduler.runAsync(plugin, () ->
                plugin.getStorage().saveTrack(track));
    }

    // ── promote / demote ─────────────────────────────────────────────────────

    /** Move a group to a specific position in the track (for GUI reordering). */
    public void moveGroupInTrack(String trackName, String groupName, int newIndex) {
        Track track = tracks.get(trackName.toLowerCase());
        if (track == null) return;
        java.util.List<String> groups = new java.util.ArrayList<>(track.getGroups());
        int currentIdx = groups.indexOf(groupName.toLowerCase());
        if (currentIdx < 0) return;
        groups.remove(currentIdx);
        newIndex = Math.max(0, Math.min(newIndex, groups.size()));
        groups.add(newIndex, groupName.toLowerCase());
        // Rebuild track group list via clearGroups() + addGroup()
        track.clearGroups();
        groups.forEach(track::addGroup);
        // Persist via saveTrack (replaces all track groups atomically)
        FoliaScheduler.runAsync(plugin, () ->
                plugin.getStorage().saveTrack(track));
    }

    public String promote(UUID uuid, String trackName) {
        Track track = getTrack(trackName);
        if (track == null) return null;
        var userMgr = plugin.getUserManager();
        var user = userMgr.getUser(uuid);
        if (user == null) return null;

        String currentGroup = null;
        for (String group : user.getGroups()) {
            if (track.containsGroup(group)) { currentGroup = group; break; }
        }

        String nextGroup;
        if (currentGroup == null) {
            if (track.isEmpty()) return null;
            nextGroup = track.getGroups().get(0);
        } else {
            nextGroup = track.getNext(currentGroup);
            if (nextGroup == null) return null;
            userMgr.removeFromGroup(uuid, currentGroup);
        }
        userMgr.addToGroup(uuid, nextGroup);
        return nextGroup;
    }

    public String demote(UUID uuid, String trackName) {
        Track track = getTrack(trackName);
        if (track == null) return null;
        var userMgr = plugin.getUserManager();
        var user = userMgr.getUser(uuid);
        if (user == null) return null;

        String currentGroup = null;
        for (String group : user.getGroups()) {
            if (track.containsGroup(group)) { currentGroup = group; break; }
        }
        if (currentGroup == null) return null;

        String prevGroup = track.getPrevious(currentGroup);
        if (prevGroup == null) return null;
        userMgr.removeFromGroup(uuid, currentGroup);
        userMgr.addToGroup(uuid, prevGroup);
        return prevGroup;
    }
}
