package ir.permscraft.migration;

import ir.permscraft.PermsCraft;
import ir.permscraft.models.Group;
import ir.permscraft.utils.MessageUtil;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.*;

/**
 * Migrates permission data from other plugins into PermsCraft.
 *
 * Supported sources:
 *   luckperms  — reads LuckPerms YAML storage files directly
 *               (works even if LuckPerms is not installed — reads files only)
 *   standard   — reads GroupManager-style YAML (groups.yml / users/*.yml)
 *
 * Usage:
 *   /pc migrate luckperms
 *   /pc migrate standard
 *
 * LuckPerms YAML layout (plugins/LuckPerms/yaml-storage/):
 *   groups/<group>.yml   — group permissions, parents, meta, weight
 *   users/<uuid>.yml     — user permissions, groups, meta
 */
public class PermissionMigrator {

    private final PermsCraft plugin;

    public PermissionMigrator(PermsCraft plugin) { this.plugin = plugin; }

    public void migrate(CommandSender sender, String source) {
        MessageUtil.send(sender, "&7Starting migration from &b" + source + "&7...");
        switch (source.toLowerCase()) {
            case "luckperms", "lp" -> migrateLuckPerms(sender);
            case "standard"        -> migrateStandard(sender);
            default -> MessageUtil.send(sender,
                    "&cUnknown source: &e" + source + "&c. Use: &bluckperms&c, &bstandard");
        }
    }

    // ── LuckPerms Migration ───────────────────────────────────────────────────

    private void migrateLuckPerms(CommandSender sender) {
        File lpDir = new File(plugin.getServer().getPluginsFolder(), "LuckPerms");
        File yamlDir = new File(lpDir, "yaml-storage");

        if (!yamlDir.exists()) {
            // Try legacy path
            yamlDir = new File(lpDir, "data");
        }
        if (!yamlDir.exists()) {
            MessageUtil.send(sender,
                    "&cLuckPerms YAML storage folder not found at: &e" + yamlDir.getPath());
            MessageUtil.send(sender,
                    "&7Make sure LuckPerms used YAML storage type (storage-method: yaml in luckperms.yml).");
            return;
        }

        int groupsImported = 0;
        int usersImported  = 0;
        int errors         = 0;
        List<String> warnings = new ArrayList<>();

        // ── Groups ────────────────────────────────────────────────────────────
        File groupsDir = new File(yamlDir, "groups");
        if (groupsDir.exists()) {
            File[] groupFiles = groupsDir.listFiles(f -> f.getName().endsWith(".yml"));
            if (groupFiles != null) {
                // Sort so "default" is processed first
                Arrays.sort(groupFiles, (a, b) -> {
                    if (a.getName().equals("default.yml")) return -1;
                    if (b.getName().equals("default.yml")) return 1;
                    return a.getName().compareTo(b.getName());
                });

                for (File gf : groupFiles) {
                    try {
                        if (importLuckPermsGroup(gf, sender, warnings)) groupsImported++;
                    } catch (Exception e) {
                        errors++;
                        warnings.add("Error importing group " + gf.getName() + ": " + e.getMessage());
                    }
                }
            }
        } else {
            warnings.add("No groups/ directory found in LuckPerms YAML storage.");
        }

        // ── Users ─────────────────────────────────────────────────────────────
        File usersDir = new File(yamlDir, "users");
        if (usersDir.exists()) {
            File[] userFiles = usersDir.listFiles(f -> f.getName().endsWith(".yml"));
            if (userFiles != null) {
                for (File uf : userFiles) {
                    try {
                        if (importLuckPermsUser(uf, warnings)) usersImported++;
                    } catch (Exception e) {
                        errors++;
                        warnings.add("Error importing user " + uf.getName() + ": " + e.getMessage());
                    }
                }
            }
        } else {
            warnings.add("No users/ directory found in LuckPerms YAML storage.");
        }

        // Reload
        plugin.getGroupManager().loadGroups();

        // Report
        MessageUtil.send(sender, "&a--- LuckPerms Migration Complete ---");
        MessageUtil.send(sender, "&7Groups imported: &a" + groupsImported);
        MessageUtil.send(sender, "&7Users imported:  &a" + usersImported);
        if (errors > 0)
            MessageUtil.send(sender, "&cErrors: " + errors);
        if (!warnings.isEmpty()) {
            MessageUtil.send(sender, "&eWarnings (" + warnings.size() + "):");
            warnings.forEach(w -> MessageUtil.send(sender, "&8  » &e" + w));
        }
        MessageUtil.send(sender, "&7Use &b/pc reload &7to apply changes.");
    }

    /**
     * Import one LuckPerms group YAML file.
     *
     * LuckPerms group.yml format:
     * <pre>
     * name: admin
     * permissions:
     *   - permission: essentials.fly
     *     value: true
     *     contexts: {}
     *   - permission: weight.100
     *     value: true
     *     contexts: {}
     *   - permission: group.mod         (parent group)
     *     value: true
     *     contexts: {}
     *   - permission: prefix.100.&c[Admin]
     *     value: true
     *     contexts: {}
     * </pre>
     */
    private boolean importLuckPermsGroup(File file, CommandSender sender,
                                          List<String> warnings) {
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        String name = yml.getString("name",
                file.getName().replace(".yml", "")).toLowerCase();

        if (!plugin.getGroupManager().groupExists(name)) {
            plugin.getGroupManager().createGroup(name);
        }
        Group g = plugin.getGroupManager().getGroup(name);
        if (g == null) return false;

        List<?> permissions = yml.getList("permissions", Collections.emptyList());
        for (Object obj : permissions) {
            if (!(obj instanceof Map<?, ?> entry)) continue;

            String  node    = String.valueOf(entry.get("permission"));
            boolean granted = !Boolean.FALSE.equals(entry.get("value"));

            // Skip context-specific nodes (not yet supported in migration)
            Object ctxObj = entry.get("contexts");
            if (ctxObj instanceof Map<?, ?> ctx && !ctx.isEmpty()) {
                warnings.add("Group " + name + ": skipped context-bound node: " + node);
                continue;
            }

            // ── Parse LP meta-permissions ─────────────────────────────────────
            if (node.startsWith("group.")) {
                // Parent group inheritance
                String parent = node.substring(6).toLowerCase();
                if (granted) plugin.getGroupManager().addInheritance(name, parent);

            } else if (node.startsWith("weight.")) {
                // Group weight
                try {
                    int weight = Integer.parseInt(node.substring(7));
                    plugin.getGroupManager().setWeight(name, weight);
                } catch (NumberFormatException e) {
                    warnings.add("Group " + name + ": invalid weight node: " + node);
                }

            } else if (node.startsWith("prefix.")) {
                // prefix.<priority>.<value>
                String[] parts = node.split("\\.", 3);
                if (parts.length == 3 && granted) {
                    plugin.getGroupManager().setPrefix(name,
                            parts[2].replace("&", "§"));
                }

            } else if (node.startsWith("suffix.")) {
                String[] parts = node.split("\\.", 3);
                if (parts.length == 3 && granted) {
                    plugin.getGroupManager().setSuffix(name,
                            parts[2].replace("&", "§"));
                }

            } else if (node.startsWith("meta.")) {
                // meta.<key>.<value>
                String[] parts = node.split("\\.", 3);
                if (parts.length == 3 && granted) {
                    plugin.getGroupManager().setMeta(name, parts[1], parts[2]);
                }

            } else if (node.startsWith("displayname.")) {
                if (granted) g.setDisplayName(node.substring(12));

            } else {
                // Regular permission node
                String finalNode = granted ? node : "-" + node;
                plugin.getGroupManager().addPermission(name, finalNode);
            }
        }
        return true;
    }

    /**
     * Import one LuckPerms user YAML file.
     *
     * LuckPerms user.yml format:
     * <pre>
     * uuid: <uuid>
     * name: Steve
     * primaryGroup: default
     * permissions:
     *   - permission: essentials.fly
     *     value: true
     *     contexts: {}
     *   - permission: group.admin
     *     value: true
     *     contexts: {}
     * </pre>
     */
    private boolean importLuckPermsUser(File file, List<String> warnings) {
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);

        String uuidStr = yml.getString("uuid",
                file.getName().replace(".yml", ""));
        String username = yml.getString("name", "unknown");

        java.util.UUID uuid;
        try {
            uuid = java.util.UUID.fromString(uuidStr);
        } catch (IllegalArgumentException e) {
            warnings.add("Invalid UUID in file: " + file.getName());
            return false;
        }

        List<?> permissions = yml.getList("permissions", Collections.emptyList());
        for (Object obj : permissions) {
            if (!(obj instanceof Map<?, ?> entry)) continue;

            String  node    = String.valueOf(entry.get("permission"));
            boolean granted = !Boolean.FALSE.equals(entry.get("value"));

            // Skip context-specific
            Object ctxObj = entry.get("contexts");
            if (ctxObj instanceof Map<?, ?> ctx && !ctx.isEmpty()) {
                warnings.add("User " + username + ": skipped context-bound node: " + node);
                continue;
            }

            if (node.startsWith("group.")) {
                String group = node.substring(6).toLowerCase();
                if (granted) plugin.getUserManager().addToGroup(uuid, group);
                else         plugin.getUserManager().removeFromGroup(uuid, group);

            } else if (node.startsWith("prefix.")) {
                String[] parts = node.split("\\.", 3);
                if (parts.length == 3 && granted)
                    plugin.getUserManager().setPrefix(uuid, parts[2].replace("&", "§"));

            } else if (node.startsWith("suffix.")) {
                String[] parts = node.split("\\.", 3);
                if (parts.length == 3 && granted)
                    plugin.getUserManager().setSuffix(uuid, parts[2].replace("&", "§"));

            } else if (node.startsWith("meta.")) {
                String[] parts = node.split("\\.", 3);
                if (parts.length == 3 && granted)
                    plugin.getUserManager().setMeta(uuid, parts[1], parts[2]);

            } else {
                String finalNode = granted ? node : "-" + node;
                plugin.getUserManager().addPermission(uuid, finalNode);
            }
        }

        // Primary group
        String primary = yml.getString("primaryGroup", "default");
        plugin.getUserManager().switchPrimaryGroup(uuid, primary);

        return true;
    }

    // ── Standard (GroupManager-style) Migration ───────────────────────────────

    private void migrateStandard(CommandSender sender) {
        int groupsImported = 0, usersImported = 0, errors = 0;
        List<String> warnings = new ArrayList<>();

        try {
            // Try to get all groups from any registered permissions plugin
            org.bukkit.plugin.Plugin pm =
                    plugin.getServer().getPluginManager().getPlugin("GroupManager");
            if (pm == null)
                pm = plugin.getServer().getPluginManager().getPlugin("PermissionsEx");

            if (pm == null) {
                MessageUtil.send(sender,
                        "&cNo supported source plugin found (GroupManager, PermissionsEx).");
                MessageUtil.send(sender,
                        "&7For LuckPerms data use: &b/pc migrate luckperms");
                return;
            }

            org.bukkit.permissions.Permission[] allPerms =
                    plugin.getServer().getPluginManager().getPermissions()
                            .toArray(new org.bukkit.permissions.Permission[0]);

            for (org.bukkit.permissions.Permission perm : allPerms) {
                String name = perm.getName().toLowerCase();
                if (name.startsWith("group.")) {
                    String groupName = name.substring(6);
                    if (!plugin.getGroupManager().groupExists(groupName)) {
                        plugin.getGroupManager().createGroup(groupName);
                        groupsImported++;
                    }
                    for (Map.Entry<String, Boolean> child : perm.getChildren().entrySet()) {
                        String childNode = child.getKey();
                        boolean childGrant = child.getValue();
                        if (childNode.startsWith("group.")) {
                            plugin.getGroupManager().addInheritance(groupName,
                                    childNode.substring(6));
                        } else if (childNode.startsWith("prefix.")) {
                            String[] parts = childNode.split("\\.", 3);
                            if (parts.length == 3)
                                plugin.getGroupManager().setPrefix(groupName, parts[2]);
                        } else if (childNode.startsWith("suffix.")) {
                            String[] parts = childNode.split("\\.", 3);
                            if (parts.length == 3)
                                plugin.getGroupManager().setSuffix(groupName, parts[2]);
                        } else {
                            plugin.getGroupManager().addPermission(groupName,
                                    childGrant ? childNode : "-" + childNode);
                        }
                    }
                }
            }
            plugin.getGroupManager().loadGroups();

        } catch (Exception e) {
            errors++;
            warnings.add("Standard migration error: " + e.getMessage());
        }

        MessageUtil.send(sender, "&a--- Standard Migration Complete ---");
        MessageUtil.send(sender, "&7Groups imported: &a" + groupsImported);
        if (errors > 0) MessageUtil.send(sender, "&cErrors: " + errors);
        warnings.forEach(w -> MessageUtil.send(sender, "&e  » " + w));
    }
}
