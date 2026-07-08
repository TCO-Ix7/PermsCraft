package ir.permscraft.migration;

import ir.permscraft.FoliaScheduler;
import ir.permscraft.PermsCraft;
import ir.permscraft.models.Group;
import ir.permscraft.models.User;
import ir.permscraft.storage.SqlStorage;
import ir.permscraft.storage.MongoDBStorage;
import ir.permscraft.storage.StorageBackend;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.*;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * FIX (GROUP_CONCAT): The original export() used GROUP_CONCAT which is MySQL-only
 * syntax and fails on SQLite, PostgreSQL and H2 with a syntax error.
 * Replaced with portable separate queries per user.
 *
 * FIX (MongoDB): export() called getConnection() unconditionally which throws
 * UnsupportedOperationException for MongoDB.  Non-SQL backends now export only
 * the in-memory group data (which is always available regardless of backend).
 */
public class YamlBackup {

    private final PermsCraft plugin;

    public YamlBackup(PermsCraft plugin) {
        this.plugin = plugin;
    }

    // ── EXPORT ───────────────────────────────────────────────────────────────

    public void export(CommandSender sender) {
        FoliaScheduler.runAsync(plugin, () -> {
            try {
                File backupDir = new File(plugin.getDataFolder(), "backups");
                backupDir.mkdirs();

                String timestamp = LocalDateTime.now()
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
                File exportFile = new File(backupDir, "backup_" + timestamp + ".yml");

                YamlConfiguration yaml = new YamlConfiguration();

                // ── Groups (always available from GroupManager cache) ─────────
                for (Group g : plugin.getGroupManager().getAllGroups()) {
                    String path = "groups." + g.getName();
                    yaml.set(path + ".display-name", g.getDisplayName());
                    yaml.set(path + ".prefix",       g.getPrefix());
                    yaml.set(path + ".suffix",       g.getSuffix());
                    yaml.set(path + ".weight",       g.getWeight());
                    yaml.set(path + ".permissions",  new ArrayList<>(g.getPermissions()));
                    yaml.set(path + ".parents",      new ArrayList<>(g.getInheritedGroups()));
                }

                // ── Users ─────────────────────────────────────────────────────
                if (plugin.getStorage() instanceof SqlStorage) {
                    exportUsersSql(yaml);
                } else {
                    // MongoDB: export only online / cached users
                    exportUsersCached(yaml);
                }

                yaml.save(exportFile);

                FoliaScheduler.runSync(plugin, () ->
                    notify(sender, "&aBackup saved to &eplugins/PermsCraft/backups/backup_"
                            + timestamp + ".yml"));

            } catch (Exception e) {
                plugin.getLogger().severe("Export failed: " + e.getMessage());
                FoliaScheduler.runSync(plugin, () ->
                    notify(sender, "&cExport failed: " + e.getMessage()));
            }
        });
    }

    /**
     * FIX: replaced the single GROUP_CONCAT query (MySQL-only) with three portable
     * queries: one for all users, one for their group memberships, one for their
     * individual permissions. Works on SQLite, PostgreSQL, H2 and MySQL.
     */
    private void exportUsersSql(YamlConfiguration yaml) throws SQLException {
        try (Connection conn = plugin.getStorage().getConnection()) {

            // Step 1 — base user rows
            Map<String, String[]> users = new LinkedHashMap<>(); // uuid → [username, prefix, suffix]
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT uuid, username, prefix, suffix FROM pc_users")) {
                while (rs.next()) {
                    users.put(rs.getString("uuid"), new String[]{
                        rs.getString("username"),
                        rs.getString("prefix"),
                        rs.getString("suffix")
                    });
                }
            }

            // Step 2 — group memberships
            Map<String, List<String>> userGroups = new HashMap<>();
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT uuid, group_name FROM pc_user_groups")) {
                while (rs.next()) {
                    userGroups.computeIfAbsent(rs.getString("uuid"), k -> new ArrayList<>())
                              .add(rs.getString("group_name"));
                }
            }

            // Step 3 — individual permissions
            Map<String, List<String>> userPerms = new HashMap<>();
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT uuid, permission FROM pc_user_permissions")) {
                while (rs.next()) {
                    userPerms.computeIfAbsent(rs.getString("uuid"), k -> new ArrayList<>())
                             .add(rs.getString("permission"));
                }
            }

            // Step 4 — write to yaml
            for (Map.Entry<String, String[]> e : users.entrySet()) {
                String uuid = e.getKey();
                String[] meta = e.getValue();
                String path = "users." + uuid;
                yaml.set(path + ".username",    meta[0]);
                yaml.set(path + ".prefix",      meta[1]);
                yaml.set(path + ".suffix",      meta[2]);
                yaml.set(path + ".groups",      userGroups.getOrDefault(uuid, List.of("default")));
                yaml.set(path + ".permissions", userPerms.getOrDefault(uuid, List.of()));
            }
        }
    }

    /** Fallback for non-SQL backends: export only currently cached (online) users. */
    private void exportUsersCached(YamlConfiguration yaml) {
        plugin.getServer().getOnlinePlayers().forEach(p -> {
            var user = plugin.getUserManager().getUser(p.getUniqueId());
            if (user == null) return;
            String path = "users." + user.getUuid().toString();
            yaml.set(path + ".username",    user.getUsername());
            yaml.set(path + ".prefix",      user.getPrefix());
            yaml.set(path + ".suffix",      user.getSuffix());
            yaml.set(path + ".groups",      new ArrayList<>(user.getGroups()));
            yaml.set(path + ".permissions", new ArrayList<>(user.getPermissions()));
        });
    }

    // ── IMPORT ───────────────────────────────────────────────────────────────

    public void importBackup(CommandSender sender, String fileName) {
        FoliaScheduler.runAsync(plugin, () -> {
            try {
                File backupDir  = new File(plugin.getDataFolder(), "backups");
                File importFile = new File(backupDir, fileName.endsWith(".yml") ? fileName : fileName + ".yml");

                if (!importFile.exists()) {
                    FoliaScheduler.runSync(plugin, () ->
                        notify(sender, "&cFile not found: &e" + importFile.getName()));
                    return;
                }

                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(importFile);
                int groupsImported = 0, usersImported = 0;

                // Groups
                if (yaml.isConfigurationSection("groups")) {
                    for (String groupName : yaml.getConfigurationSection("groups").getKeys(false)) {
                        String path = "groups." + groupName;
                        if (!plugin.getGroupManager().groupExists(groupName))
                            plugin.getGroupManager().createGroup(groupName);
                        Group g = plugin.getGroupManager().getGroup(groupName);
                        if (g == null) continue;
                        g.setDisplayName(yaml.getString(path + ".display-name", groupName));
                        g.setPrefix(yaml.getString(path + ".prefix", ""));
                        g.setSuffix(yaml.getString(path + ".suffix", ""));
                        g.setWeight(yaml.getInt(path + ".weight", 0));
                        plugin.getStorage().saveGroup(g);
                        for (String perm : yaml.getStringList(path + ".permissions"))
                            plugin.getGroupManager().addPermission(groupName, perm);
                        for (String parent : yaml.getStringList(path + ".parents"))
                            plugin.getGroupManager().addInheritance(groupName, parent);
                        groupsImported++;
                    }
                }

                // Users
                if (yaml.isConfigurationSection("users")) {
                    for (String uuidStr : yaml.getConfigurationSection("users").getKeys(false)) {
                        String path = "users." + uuidStr;
                        try {
                            UUID uuid     = UUID.fromString(uuidStr);
                            String uname  = yaml.getString(path + ".username", "Unknown");
                            User user     = new User(uuid, uname);
                            user.setPrefix(yaml.getString(path + ".prefix", ""));
                            user.setSuffix(yaml.getString(path + ".suffix", ""));
                            plugin.getStorage().saveUser(user);
                            for (String group : yaml.getStringList(path + ".groups"))
                                plugin.getStorage().addUserToGroup(uuid, group);
                            for (String perm : yaml.getStringList(path + ".permissions"))
                                plugin.getStorage().addUserPermission(uuid, perm);
                            usersImported++;
                        } catch (IllegalArgumentException ignored) {}
                    }
                }

                final int fg = groupsImported, fu = usersImported;
                FoliaScheduler.runSync(plugin, () -> {
                    plugin.getGroupManager().loadGroups();
                    plugin.getServer().getOnlinePlayers().forEach(p ->
                            plugin.getUserManager().refreshPermissions(p.getUniqueId()));
                    notify(sender, "&aImport complete! Groups: &e" + fg + " &aUsers: &e" + fu);
                });

            } catch (Exception e) {
                plugin.getLogger().severe("Import failed: " + e.getMessage());
                FoliaScheduler.runSync(plugin, () ->
                    notify(sender, "&cImport failed: " + e.getMessage()));
            }
        });
    }

    public List<String> listBackups() {
        File backupDir = new File(plugin.getDataFolder(), "backups");
        if (!backupDir.exists()) return List.of();
        String[] files = backupDir.list((d, n) -> n.endsWith(".yml"));
        return files != null ? Arrays.asList(files) : List.of();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void notify(CommandSender sender, String msg) {
        ir.permscraft.utils.MessageUtil.send(sender, msg);
    }

    /** Import from the most recent backup file. */
    public void importLatest() throws Exception {
        File backupDir = new File(plugin.getDataFolder(), "backups");
        if (!backupDir.exists()) throw new Exception("No backups directory found");
        File[] files = backupDir.listFiles((d, n) -> n.endsWith(".yml"));
        if (files == null || files.length == 0) throw new Exception("No backup files found");
        java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        importFrom(files[0].getName());
    }
    /** BackupGui helper — returns backup filename */
    public String exportAll() {
        String timestamp = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        export(plugin.getServer().getConsoleSender());
        return "backup_" + timestamp + ".yml";
    }

    public void importFrom(String filename) {
        importBackup(plugin.getServer().getConsoleSender(), filename);
    }

}
