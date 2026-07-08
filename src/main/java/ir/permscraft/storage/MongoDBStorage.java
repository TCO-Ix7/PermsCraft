package ir.permscraft.storage;

import com.mongodb.client.*;
import com.mongodb.client.model.*;
import ir.permscraft.PermsCraft;
import ir.permscraft.context.Context;
import ir.permscraft.context.ContextSet;
import ir.permscraft.context.ContextualPermission;
import ir.permscraft.logging.LogEntry;
import ir.permscraft.models.Group;
import ir.permscraft.models.TimedPermission;
import ir.permscraft.models.Track;
import ir.permscraft.models.User;
import org.bson.Document;

import java.time.Instant;
import java.util.*;

public class MongoDBStorage implements StorageBackend {

    private static final java.util.logging.Logger logger =
            java.util.logging.Logger.getLogger("PermsCraft");

    private final PermsCraft plugin;
    private MongoClient client;
    private MongoDatabase db;

    private static final String GROUPS      = "pc_groups";
    private static final String USERS       = "pc_users";
    private static final String TIMED_PERMS = "pc_timed_permissions";
    private static final String CTX_PERMS   = "pc_context_permissions";
    private static final String TRACKS      = "pc_tracks";
    private static final String LOG         = "pc_log";

    public MongoDBStorage(PermsCraft plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean init() {
        try {
            String uri    = plugin.getConfig().getString("storage.mongodb.uri", "mongodb://localhost:27017");
            String dbName = plugin.getConfig().getString("storage.mongodb.database", "permscraft");
            client = MongoClients.create(uri);
            db = client.getDatabase(dbName);

            // Indexes
            db.getCollection(GROUPS).createIndex(Indexes.ascending("name"),
                    new IndexOptions().unique(true));
            db.getCollection(USERS).createIndex(Indexes.ascending("uuid"),
                    new IndexOptions().unique(true));
            db.getCollection(USERS).createIndex(Indexes.ascending("username_lower"),
                    new IndexOptions().unique(false));
            db.getCollection(LOG).createIndex(Indexes.descending("timestamp"));
            db.getCollection(TIMED_PERMS).createIndex(Indexes.ascending("expiry"));
            db.getCollection(TRACKS).createIndex(Indexes.ascending("name"),
                    new IndexOptions().unique(true));

            // Ensure default group exists
            db.getCollection(GROUPS).updateOne(
                    Filters.eq("name", "default"),
                    Updates.setOnInsert(new Document("name", "default")
                            .append("display_name", "Default")
                            .append("prefix", "").append("suffix", "")
                            .append("weight", 0)
                            .append("permissions", new ArrayList<>())
                            .append("parents", new ArrayList<>())),
                    new UpdateOptions().upsert(true));

            plugin.getLogger().info("[PermsCraft] MongoDB connected to: " + dbName);
            return true;
        } catch (Exception e) {
            plugin.getLogger().severe("[PermsCraft] MongoDB connection failed: " + e.getMessage());
            return false;
        }
    }

    @Override
    public void close() {
        if (client != null) client.close();
    }

    // ── Groups ───────────────────────────────────────────────────────────────

    @Override
    public List<Group> loadAllGroups() {
        List<Group> groups = new ArrayList<>();
        try (MongoCursor<Document> cursor = db.getCollection(GROUPS).find().iterator()) {
            while (cursor.hasNext()) {
                Document doc = cursor.next();
                Group g = new Group(doc.getString("name"));
                g.setDisplayName(getString(doc, "display_name", g.getName()));
                g.setPrefix(getString(doc, "prefix", ""));
                g.setSuffix(getString(doc, "suffix", ""));
                g.setWeight(doc.getInteger("weight", 0));
                getStringList(doc, "permissions").forEach(g::addPermission);
                getStringList(doc, "parents").forEach(g::addInheritance);
                groups.add(g);
            }
        }
        return groups;
    }

    @Override
    public void saveGroup(Group group) {
        Document doc = new Document("name", group.getName())
                .append("display_name", group.getDisplayName())
                .append("prefix", group.getPrefix())
                .append("suffix", group.getSuffix())
                .append("weight", group.getWeight())
                .append("permissions", new ArrayList<>(group.getPermissions()))
                .append("parents", new ArrayList<>(group.getInheritedGroups()));
        db.getCollection(GROUPS).replaceOne(
                Filters.eq("name", group.getName()), doc,
                new ReplaceOptions().upsert(true));
    }

    @Override
    public void deleteGroup(String groupName) {
        db.getCollection(GROUPS).deleteOne(Filters.eq("name", groupName));
        // Remove group from all users
        db.getCollection(USERS).updateMany(
                Filters.in("groups", groupName),
                Updates.pull("groups", groupName));
    }

    @Override
    public void addGroupPermission(String groupName, String permission) {
        db.getCollection(GROUPS).updateOne(
                Filters.eq("name", groupName),
                Updates.addToSet("permissions", permission));
    }

    @Override
    public void removeGroupPermission(String groupName, String permission) {
        db.getCollection(GROUPS).updateOne(
                Filters.eq("name", groupName),
                Updates.pull("permissions", permission));
    }

    @Override
    public void addGroupInheritance(String groupName, String parentGroup) {
        db.getCollection(GROUPS).updateOne(
                Filters.eq("name", groupName),
                Updates.addToSet("parents", parentGroup));
    }

    @Override
    public void removeGroupInheritance(String groupName, String parentGroup) {
        db.getCollection(GROUPS).updateOne(
                Filters.eq("name", groupName),
                Updates.pull("parents", parentGroup));
    }

    // ── Users ────────────────────────────────────────────────────────────────

    /**
     * FIX (Bug: loadAllUsers always empty on MongoDB): same issue as the SQL
     * backends — this was never overridden, so it silently used the
     * StorageBackend interface default (empty list), making backup
     * export/snapshot omit every user when running on MongoDB.
     */
    @Override
    public List<User> loadAllUsers() {
        List<UUID> uuids = new ArrayList<>();
        for (Document doc : db.getCollection(USERS).find()) {
            try { uuids.add(UUID.fromString(doc.getString("uuid"))); }
            catch (Exception ignored) {}
        }
        List<User> users = new ArrayList<>(uuids.size());
        for (UUID uuid : uuids) users.add(loadUser(uuid, null));
        return users;
    }

    @Override
    public User loadUser(UUID uuid, String username) {
        Document doc = db.getCollection(USERS)
                .find(Filters.eq("uuid", uuid.toString())).first();
        User user = new User(uuid, username);
        if (doc == null) {
            user.addGroup("default");
            saveUser(user);
            return user;
        }
        user.setPrefix(getString(doc, "prefix", ""));
        user.setSuffix(getString(doc, "suffix", ""));
        List<String> groups = getStringList(doc, "groups");
        if (!groups.isEmpty()) {
            groups.forEach(user::addGroup);
        } else {
            user.addGroup("default");
            saveUser(user);
        }
        getStringList(doc, "permissions").forEach(user::addPermission);

        // FIX (Bug #MongoDB-primary): loadUser never restored the primary_group
        // field that saveUserPrimaryGroup persisted, so every login reset the
        // primary back to whichever group happened to be first in the set.
        String storedPrimary = getString(doc, "primary_group", null);
        if (storedPrimary != null && user.inGroup(storedPrimary)) {
            user.setPrimaryGroup(storedPrimary);
        }

        return user;
    }

    @Override
    public void saveUser(User user) {
        Document doc = new Document("uuid", user.getUuid().toString())
                .append("username", user.getUsername())
                .append("username_lower", user.getUsername().toLowerCase())
                .append("prefix", user.getPrefix())
                .append("suffix", user.getSuffix())
                .append("groups", new ArrayList<>(user.getGroups()))
                .append("permissions", new ArrayList<>(user.getPermissions()));
        db.getCollection(USERS).replaceOne(
                Filters.eq("uuid", user.getUuid().toString()), doc,
                new ReplaceOptions().upsert(true));
    }


    @Override
    public void saveUserPrimaryGroup(java.util.UUID uuid, String primaryGroup) {
        // FIX: was filtering on "_id" (MongoDB ObjectId) but all user documents
        // are looked up by the "uuid" string field — "_id" is never set to the
        // UUID string, so the update silently matched nothing.
        try {
            db.getCollection(USERS).updateOne(
                Filters.eq("uuid", uuid.toString()),
                Updates.set("primary_group", primaryGroup.toLowerCase()),
                new UpdateOptions().upsert(false)
            );
        } catch (Exception e) {
            logger.severe("[PermsCraft] MongoDB saveUserPrimaryGroup failed: " + e.getMessage());
        }
    }

    public void addUserToGroup(UUID uuid, String groupName) {
        db.getCollection(USERS).updateOne(
                Filters.eq("uuid", uuid.toString()),
                Updates.addToSet("groups", groupName));
    }

    @Override
    public void removeUserFromGroup(UUID uuid, String groupName) {
        db.getCollection(USERS).updateOne(
                Filters.eq("uuid", uuid.toString()),
                Updates.pull("groups", groupName));
    }

    @Override
    public void addUserPermission(UUID uuid, String permission) {
        db.getCollection(USERS).updateOne(
                Filters.eq("uuid", uuid.toString()),
                Updates.addToSet("permissions", permission));
    }

    @Override
    public void removeUserPermission(UUID uuid, String permission) {
        db.getCollection(USERS).updateOne(
                Filters.eq("uuid", uuid.toString()),
                Updates.pull("permissions", permission));
    }

    @Override
    public UUID findUUIDByUsername(String username) {
        Document doc = db.getCollection(USERS)
                .find(Filters.eq("username_lower", username.toLowerCase())).first();
        if (doc == null) return null;
        try { return UUID.fromString(doc.getString("uuid")); }
        catch (IllegalArgumentException e) { return null; }
    }

    // ── Timed Permissions ────────────────────────────────────────────────────

    @Override
    public List<TimedPermission> loadActiveTimedPermissions() {
        List<TimedPermission> list = new ArrayList<>();
        long now = Instant.now().getEpochSecond();
        try (MongoCursor<Document> cursor = db.getCollection(TIMED_PERMS)
                .find(Filters.gt("expiry", now)).iterator()) {
            while (cursor.hasNext()) {
                Document doc = cursor.next();
                list.add(new TimedPermission(
                        doc.getString("target"),
                        doc.getBoolean("is_group", false),
                        doc.getString("permission"),
                        Instant.ofEpochSecond(doc.getLong("expiry"))
                ));
            }
        }
        return list;
    }

    @Override
    public void saveTimedPermission(String target, boolean isGroup, String permission, long expiryEpoch) {
        Document doc = new Document("target", target)
                .append("is_group", isGroup)
                .append("permission", permission)
                .append("expiry", expiryEpoch);
        db.getCollection(TIMED_PERMS).insertOne(doc);
    }

    @Override
    public void deleteExpiredTimedPermissions(long nowEpoch) {
        db.getCollection(TIMED_PERMS).deleteMany(Filters.lt("expiry", nowEpoch));
    }

    @Override
    public void deleteTimedPermission(String target, String permission) {
        // FIX: was using undefined variable "timedCollection" - fixed to use db.getCollection()
        db.getCollection(TIMED_PERMS).deleteOne(Filters.and(
                Filters.eq("target", target),
                Filters.eq("permission", permission)
        ));
    }

    // ── Context Permissions ──────────────────────────────────────────────────

    @Override
    public List<ContextRow> loadAllContextPermissions() {
        List<ContextRow> result = new ArrayList<>();
        try (MongoCursor<Document> cursor = db.getCollection(CTX_PERMS).find().iterator()) {
            while (cursor.hasNext()) {
                Document doc = cursor.next();
                Context ctx = new Context(
                        getString(doc, "context_key", "global"),
                        getString(doc, "context_value", "global"));
                ContextualPermission cp = new ContextualPermission(
                        doc.getString("permission"), ctx,
                        doc.getBoolean("granted", true));
                result.add(new ContextRow(
                        doc.getString("target"),
                        doc.getBoolean("is_group", false),
                        cp));
            }
        }
        return result;
    }

    @Override
    public void saveContextPermission(String target, boolean isGroup, String permission,
                                      Context context, boolean granted) {
        Document doc = new Document("target", target)
                .append("is_group", isGroup)
                .append("permission", permission)
                .append("context_key", context.getKey())
                .append("context_value", context.getValue())
                .append("granted", granted);
        db.getCollection(CTX_PERMS).insertOne(doc);
    }



    @Override
    public void saveContextPermission(String target, boolean isGroup, String permission,
                                      ContextSet requiredCtx, boolean granted) {
        String ctxKey, ctxVal;
        if (requiredCtx.isEmpty()) {
            ctxKey = "global"; ctxVal = "global";
        } else if (requiredCtx.asMap().size() == 1) {
            var entry = requiredCtx.asMap().entrySet().iterator().next();
            ctxKey = entry.getKey(); ctxVal = entry.getValue();
        } else {
            ctxKey = serializeContextSet(requiredCtx); ctxVal = "__multi__";
        }
        saveContextPermission(target, isGroup, permission, new Context(ctxKey, ctxVal), granted);
    }

    @Override
    public void deleteContextPermission(String target, String permission, ContextSet requiredCtx) {
        String ctxKey, ctxVal;
        if (requiredCtx.isEmpty()) {
            ctxKey = "global"; ctxVal = "global";
        } else if (requiredCtx.asMap().size() == 1) {
            var entry = requiredCtx.asMap().entrySet().iterator().next();
            ctxKey = entry.getKey(); ctxVal = entry.getValue();
        } else {
            ctxKey = serializeContextSet(requiredCtx); ctxVal = "__multi__";
        }
        deleteContextPermission(target, permission, new Context(ctxKey, ctxVal));
    }

    private static ContextSet deserializeContextSet(String s) {
        ContextSet.Builder b = ContextSet.builder();
        for (String part : s.split(",")) { String[] kv = part.split("=", 2); if (kv.length == 2) b.put(kv[0].trim(), kv[1].trim()); }
        return b.build();
    }

    private static String serializeContextSet(ContextSet cs) {
        StringBuilder sb = new StringBuilder();
        cs.asMap().forEach((k, v) -> { if (!sb.isEmpty()) sb.append(","); sb.append(k).append("=").append(v); });
        return sb.toString();
    }

    public void deleteContextPermission(String target, String permission, Context context) {
        db.getCollection(CTX_PERMS).deleteMany(Filters.and(
                Filters.eq("target", target),
                Filters.eq("permission", permission),
                Filters.eq("context_key", context.getKey()),
                Filters.eq("context_value", context.getValue())
        ));
    }

    // ── Tracks ───────────────────────────────────────────────────────────────

    @Override
    public List<Track> loadAllTracks() {
        List<Track> tracks = new ArrayList<>();
        try (MongoCursor<Document> cursor = db.getCollection(TRACKS).find().iterator()) {
            while (cursor.hasNext()) {
                Document doc = cursor.next();
                Track track = new Track(doc.getString("name"));
                getStringList(doc, "groups").forEach(track::addGroup);
                tracks.add(track);
            }
        }
        return tracks;
    }

    @Override
    public void saveTrack(Track track) {
        Document doc = new Document("name", track.getName())
                .append("groups", new ArrayList<>(track.getGroups()));
        db.getCollection(TRACKS).replaceOne(
                Filters.eq("name", track.getName()), doc,
                new ReplaceOptions().upsert(true));
    }

    @Override
    public void deleteTrack(String trackName) {
        db.getCollection(TRACKS).deleteOne(Filters.eq("name", trackName));
    }

    // ── Clear user / group ────────────────────────────────────────────────────

    @Override
    public void clearUser(UUID uuid) {
        String uuidStr = uuid.toString();
        // Remove permissions and groups from user document
        db.getCollection("pc_users").updateOne(
                Filters.eq("uuid", uuidStr),
                new Document("$set", new Document("permissions", new ArrayList<>())
                        .append("groups", List.of("default"))));
        // Remove timed perms and context perms
        db.getCollection("pc_timed_permissions").deleteMany(
                Filters.and(Filters.eq("target", uuidStr), Filters.eq("is_group", false)));
        db.getCollection("pc_context_permissions").deleteMany(
                Filters.and(Filters.eq("target", uuidStr), Filters.eq("is_group", false)));
        deleteAllMeta(uuidStr, false);
    }

    @Override
    public void clearGroup(String groupName) {
        db.getCollection("pc_groups").updateOne(
                Filters.eq("name", groupName),
                new Document("$set", new Document("permissions", new ArrayList<>())
                        .append("parents", new ArrayList<>())));
        db.getCollection("pc_timed_permissions").deleteMany(
                Filters.and(Filters.eq("target", groupName), Filters.eq("is_group", true)));
        db.getCollection("pc_context_permissions").deleteMany(
                Filters.and(Filters.eq("target", groupName), Filters.eq("is_group", true)));
        deleteAllMeta(groupName, true);
    }

    // ── Meta ─────────────────────────────────────────────────────────────────

    @Override
    public Map<String, String> loadMeta(String target, boolean isGroup) {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        long now = java.time.Instant.now().getEpochSecond();
        try (MongoCursor<Document> cursor = db.getCollection("pc_meta")
                .find(Filters.and(
                        Filters.eq("target", target),
                        Filters.eq("is_group", isGroup),
                        Filters.or(Filters.eq("expiry", -1L), Filters.gt("expiry", now))
                )).iterator()) {
            while (cursor.hasNext()) {
                Document doc = cursor.next();
                result.put(doc.getString("meta_key"), getString(doc, "meta_value", ""));
            }
        }
        return result;
    }

    @Override
    public void saveMeta(String target, boolean isGroup, String key, String value) {
        db.getCollection("pc_meta").replaceOne(
                Filters.and(Filters.eq("target", target), Filters.eq("is_group", isGroup),
                        Filters.eq("meta_key", key)),
                new Document("target", target).append("is_group", isGroup)
                        .append("meta_key", key).append("meta_value", value).append("expiry", -1L),
                new ReplaceOptions().upsert(true));
    }

    @Override
    public void deleteMeta(String target, boolean isGroup, String key) {
        db.getCollection("pc_meta").deleteOne(
                Filters.and(Filters.eq("target", target), Filters.eq("is_group", isGroup),
                        Filters.eq("meta_key", key)));
    }

    @Override
    public void deleteAllMeta(String target, boolean isGroup) {
        db.getCollection("pc_meta").deleteMany(
                Filters.and(Filters.eq("target", target), Filters.eq("is_group", isGroup)));
    }

    @Override
    public void saveTimedMeta(String target, boolean isGroup, String key, String value, long expiryEpochSecond) {
        db.getCollection("pc_meta").replaceOne(
                Filters.and(Filters.eq("target", target), Filters.eq("is_group", isGroup),
                        Filters.eq("meta_key", key)),
                new Document("target", target).append("is_group", isGroup)
                        .append("meta_key", key).append("meta_value", value).append("expiry", expiryEpochSecond),
                new ReplaceOptions().upsert(true));
    }

    // ── Search permission ─────────────────────────────────────────────────────

    @Override
    public List<String> searchPermission(String permission) {
        List<String> results = new ArrayList<>();
        // Users
        try (MongoCursor<Document> cursor = db.getCollection("pc_users")
                .find(Filters.regex("permissions", "(?i)^" + java.util.regex.Pattern.quote(permission) + "$"))
                .iterator()) {
            while (cursor.hasNext()) results.add(cursor.next().getString("uuid"));
        }
        // Groups
        try (MongoCursor<Document> cursor = db.getCollection("pc_groups")
                .find(Filters.regex("permissions", "(?i)^" + java.util.regex.Pattern.quote(permission) + "$"))
                .iterator()) {
            while (cursor.hasNext()) results.add("group:" + cursor.next().getString("name"));
        }
        return results;
    }

    // ── Logging ──────────────────────────────────────────────────────────────

    @Override
    public void saveLog(long timestamp, String actor, String action, String target, String detail) {
        Document doc = new Document("timestamp", timestamp)
                .append("actor", actor)
                .append("action", action)
                .append("target", target)
                .append("detail", detail);
        db.getCollection(LOG).insertOne(doc);
    }

    @Override
    public List<LogEntry> loadRecentLogs(int limit) {
        return queryLogs(Filters.exists("_id"), limit);
    }

    @Override
    public List<LogEntry> loadLogsByTarget(String target, int limit) {
        return queryLogs(Filters.eq("target", target), limit);
    }

    @Override
    public List<LogEntry> loadLogsByActor(String actor, int limit) {
        return queryLogs(Filters.eq("actor", actor), limit);
    }

    @Override
    public void deleteLogsOlderThan(long epochSecond) {
        long deleted = db.getCollection(LOG)
                .deleteMany(Filters.lt("timestamp", epochSecond))
                .getDeletedCount();
        if (deleted > 0)
            plugin.getLogger().info("[PermsCraft] Cleared " + deleted + " old log entries.");
    }

    /** FIX (Bug #13): MongoDB can return the exact deleted count via deleteMany().getDeletedCount(). */
    @Override
    public int purgeLogs(int days) {
        long cutoff = java.time.Instant.now().minusSeconds((long) days * 86400).getEpochSecond();
        long deleted = db.getCollection(LOG)
                .deleteMany(Filters.lt("timestamp", cutoff))
                .getDeletedCount();
        if (deleted > 0)
            plugin.getLogger().info("[PermsCraft] Purged " + deleted + " log entries older than " + days + " days.");
        return (int) Math.min(deleted, Integer.MAX_VALUE);
    }

    /** FIX (Bug #12): Distinct action names that actually exist in the collection. */
    @Override
    public List<String> getDistinctLogActions() {
        List<String> result = new ArrayList<>();
        db.getCollection(LOG).distinct("action", String.class)
                .forEach((String a) -> { if (a != null && !a.isBlank()) result.add(a); });
        Collections.sort(result);
        return result;
    }

    /** FIX (Bug #7): Uses MongoDB's countDocuments() — no documents loaded into RAM. */
    @Override
    public long countLogs(ir.permscraft.storage.LogFilter filter) {
        List<org.bson.conversions.Bson> conditions = new ArrayList<>();
        if (filter.actor()  != null) conditions.add(Filters.eq("actor",  filter.actor()));
        if (filter.target() != null) conditions.add(Filters.eq("target", filter.target()));
        if (filter.action() != null) conditions.add(Filters.eq("action", filter.action()));
        if (filter.from()   != null) conditions.add(Filters.gte("timestamp", filter.from()));
        if (filter.to()     != null) conditions.add(Filters.lt("timestamp",  filter.to()));
        org.bson.conversions.Bson combined = conditions.isEmpty() ? new Document() : Filters.and(conditions);
        return db.getCollection(LOG).countDocuments(combined);
    }

    private List<LogEntry> queryLogs(org.bson.conversions.Bson filter, int limit) {
        List<LogEntry> entries = new ArrayList<>();
        try (MongoCursor<Document> cursor = db.getCollection(LOG)
                .find(filter)
                .sort(new Document("timestamp", -1))
                .limit(limit)
                .iterator()) {
            while (cursor.hasNext()) {
                Document doc = cursor.next();
                // FIX: LogEntry(long id, Instant, String, Action, String, String)
                // ObjectId hash is used as a surrogate long id
                long id = doc.getObjectId("_id").getTimestamp(); // int seconds since epoch
                try {
                    entries.add(new LogEntry(
                            id,
                            Instant.ofEpochSecond(doc.getLong("timestamp")),
                            doc.getString("actor"),
                            LogEntry.Action.valueOf(doc.getString("action")),
                            doc.getString("target"),
                            getString(doc, "detail", "")
                    ));
                } catch (IllegalArgumentException ignored) {
                    // Unknown action enum value — skip entry instead of crashing
                }
            }
        }
        return entries;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Null-safe getString from Document with fallback. */
    private String getString(Document doc, String key, String fallback) {
        String val = doc.getString(key);
        return val != null ? val : fallback;
    }

    /** Null-safe list getter — never returns null. */
    private List<String> getStringList(Document doc, String key) {
        List<String> list = doc.getList(key, String.class);
        return list != null ? list : Collections.emptyList();
    }
}
