package ir.permscraft.commands;

import ir.permscraft.FoliaScheduler;
import ir.permscraft.context.ContextSet;
import ir.permscraft.PermsCraft;
import ir.permscraft.models.Group;
import ir.permscraft.models.User;
import ir.permscraft.utils.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.UUID;
import java.util.Map;

/**
 * Main command dispatcher for /pc.
 *
 * Each major sub-system lives in its own handler class:
 *   /pc user   → UserCommandHandler
 *   /pc group  → GroupCommandHandler
 *   /pc track  → TrackCommandHandler  (pre-existing)
 *   /pc timed  → TimedCommandHandler  (pre-existing)
 *
 * Utility sub-commands (log, backup, verbose, check, debug, sync, search,
 * tree, export, import, context, bulkupdate, migrate, reload, info, setup,
 * listgroups) remain here as private methods because each is self-contained
 * and small enough not to warrant its own file.
 */
public class PCCommand implements CommandExecutor, TabCompleter {

    private final PermsCraft plugin;
    private final UserCommandHandler  userHandler;
    private final GroupCommandHandler groupHandler;
    private final TrackCommandHandler trackHandler;
    private final TimedCommandHandler timedHandler;
    private final ConflictCommandHandler conflictHandler;

    public PCCommand(PermsCraft plugin) {
        this.plugin       = plugin;
        this.timedHandler = new TimedCommandHandler(plugin);
        this.userHandler  = new UserCommandHandler(plugin, timedHandler);
        this.groupHandler = new GroupCommandHandler(plugin, timedHandler);
        this.conflictHandler = new ConflictCommandHandler(plugin, userHandler);
        this.trackHandler = new TrackCommandHandler(plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Allow "setup" without permission so admins can bootstrap first-time access
        if (args.length >= 1 && args[0].equalsIgnoreCase("setup")) {
            handleSetup(sender, args);
            return true;
        }

        if (!sender.hasPermission("permscraft.admin")) {
            MessageUtil.send(sender, "&cYou don't have permission to use PermsCraft commands.");
            return true;
        }
        if (args.length == 0) {
            MessageUtil.send(sender, "&cUsage: &b/pc help &7for a list of commands.");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "user"       -> userHandler.handle(sender, args);
            case "group"      -> groupHandler.handle(sender, args);
            case "track"      -> trackHandler.handle(sender, args);
            case "bulkupdate" -> handleBulkUpdate(sender, args);
            case "help"       -> sendHelp(sender);
            case "editor"     -> { if (sender instanceof Player p) plugin.getGuiManager().openMain(p); else MessageUtil.send(sender, "&cOnly players can use the editor."); }
            case "reload"     -> handleReload(sender);
            case "info"       -> handleInfo(sender);
            case "log"        -> handleLog(sender, args);
            case "backup"     -> handleBackup(sender, args);
            case "migrate"    -> handleMigrate(sender, args);
            case "verbose"    -> handleVerbose(sender, args);
            case "check"      -> handleCheck(sender, args);
            case "debug"      -> handleDebug(sender, args);
            case "context"    -> handleContext(sender, args);
            case "sync"       -> handleSync(sender);
            case "search"     -> handleSearch(sender, args);
            case "tree"       -> handleTree(sender, args);
            case "export"     -> handleExport(sender, args);
            case "import"     -> handleImportCmd(sender, args);
            case "listgroups" -> handleListGroups(sender);
            case "conflicts"  -> conflictHandler.handle(sender, args);
            case "apikey"     -> handleApiKey(sender, args);
            default           -> sendHelp(sender);
        }
        return true;
    }

    // ==================== SETUP ====================

    /**
     * /pc setup op <player>  — grants OP to a player (console-only)
     * /pc setup              — prints quick-start guide
     */
    private void handleSetup(CommandSender sender, String[] args) {
        if (args.length >= 3 && args[1].equalsIgnoreCase("op")) {
            if (sender instanceof Player) {
                MessageUtil.send(sender, "&cThis command can only be run from the console for security.");
                return;
            }
            String playerName = args[2];
            FoliaScheduler.runAsync(plugin, () -> {
                // FIX (Bug #8): Bukkit.getOfflinePlayer(name) fabricates a random UUID
                // for players who have never joined, so we must look up the real UUID
                // from our storage index first.
                java.util.UUID targetUuid = plugin.getStorage().findUUIDByUsername(playerName);
                if (targetUuid == null) {
                    // Fall back to Bukkit's cache (covers players who joined before
                    // PermsCraft was installed and are therefore not in our index).
                    org.bukkit.OfflinePlayer offline = Bukkit.getOfflinePlayer(playerName);
                    if (offline.hasPlayedBefore()) {
                        targetUuid = offline.getUniqueId();
                    }
                }
                final java.util.UUID resolvedUuid = targetUuid;
                FoliaScheduler.runSync(plugin, () -> {
                    if (resolvedUuid == null) {
                        sender.sendMessage("[PermsCraft] Player '" + playerName
                                + "' has never joined this server — cannot grant OP safely.");
                        return;
                    }
                    org.bukkit.OfflinePlayer target = Bukkit.getOfflinePlayer(resolvedUuid);
                    target.setOp(true);
                    sender.sendMessage("[PermsCraft] Granted OP to " + playerName + " (" + resolvedUuid + "). They can now use /pc commands.");
                });
            });
            return;
        }

        sender.sendMessage(org.bukkit.ChatColor.translateAlternateColorCodes('&',
                "&8&l=== &bPermsCraft Quick Start &8&l===\n" +
                "&7Step 1: &eCreate a group\n" +
                "&f  /pc group create admin\n" +
                "&7Step 2: &eAdd permissions to the group\n" +
                "&f  /pc group admin permission set minecraft.command.gamemode\n" +
                "&7Step 3: &eAdd a player to the group\n" +
                "&f  /pc user <yourname> group add admin\n" +
                "&7Step 4: &eSet a prefix (optional, needs Vault+chat plugin)\n" +
                "&f  /pc group admin setprefix &c[Admin] \n" +
                "&7GUI: &e/pc editor &7(in-game only)\n" +
                "&7Console OP grant: &e/pc setup op <player>"));
    }

    // ==================== LOG ====================

    /**
     * /pc log [recent|user|actor|action|since|query|actions] ...
     *
     * Examples:
     *   /pc log                          -> last 10 entries
     *   /pc log recent 25                -> last 25 entries
     *   /pc log user Notch 20            -> entries targeting "Notch"
     *   /pc log actor Notch              -> entries performed BY "Notch"
     *   /pc log actions                  -> list action types present in the log
     *   /pc log action USER_PERM_REMOVE  -> entries of that action type
     *   /pc log action USER_PERM_REMOVE 7d 20
     *                                     -> same, but only last 7 days, max 20
     *   /pc log since 7d                 -> all entries from the last 7 days
     *   /pc log query actor=Notch action=USER_GROUP_ADD since=1d limit=25
     *                                     -> fully-filtered query (mirrors the
     *                                        REST API's /api/v1/logs filters)
     */
    private void handleLog(CommandSender sender, String[] args) {
        String sub = args.length >= 2 ? args[1].toLowerCase() : "recent";

        if (sub.equals("actions")) {
            FoliaScheduler.runAsync(plugin, () -> {
                List<String> actions = plugin.getLogManager().getDistinctActions();
                FoliaScheduler.runSync(plugin, () -> {
                    if (actions.isEmpty()) {
                        MessageUtil.send(sender, "&7No log entries recorded yet — no action types to show.");
                        return;
                    }
                    MessageUtil.send(sender, "&7Action types present in the log &8(" + actions.size() + ")&7:");
                    actions.forEach(a -> MessageUtil.sendRaw(sender, "  &8- &e" + a));
                    MessageUtil.sendRaw(sender, "&7Use &b/pc log action <type> &7to filter.");
                });
            });
            return;
        }

        if (sub.equals("query")) {
            handleLogQuery(sender, args);
            return;
        }

        int limit = 10;
        ir.permscraft.storage.LogFilter filter;

        switch (sub) {
            case "user" -> {
                if (args.length >= 4) limit = parseIntOrDefault(args[3], limit);
                String target = args.length >= 3 ? args[2] : null;
                filter = new ir.permscraft.storage.LogFilter(null, target, null, null, null, limit, 0);
            }
            case "actor" -> {
                if (args.length >= 4) limit = parseIntOrDefault(args[3], limit);
                String actor = args.length >= 3 ? args[2] : null;
                filter = new ir.permscraft.storage.LogFilter(actor, null, null, null, null, limit, 0);
            }
            case "action" -> {
                if (args.length < 3) {
                    MessageUtil.send(sender, "&cUsage: /pc log action <TYPE> [since] [limit]");
                    MessageUtil.sendRaw(sender, "&7Use &b/pc log actions &7to see available types.");
                    return;
                }
                String actionType = args[2].toUpperCase();
                Long from = null;
                if (args.length >= 4) {
                    long secs = ir.permscraft.utils.DurationParser.parseOrNegative(args[3]);
                    if (secs > 0) from = java.time.Instant.now().getEpochSecond() - secs;
                    else limit = parseIntOrDefault(args[3], limit);
                }
                if (args.length >= 5) limit = parseIntOrDefault(args[4], limit);
                filter = new ir.permscraft.storage.LogFilter(null, null, actionType, from, null, limit, 0);
            }
            case "since" -> {
                if (args.length < 3) { MessageUtil.send(sender, "&cUsage: /pc log since <duration> [limit]"); return; }
                long secs;
                try { secs = ir.permscraft.utils.DurationParser.parse(args[2]); }
                catch (IllegalArgumentException ex) { MessageUtil.send(sender, "&c" + ex.getMessage()); return; }
                if (args.length >= 4) limit = parseIntOrDefault(args[3], limit);
                long from = java.time.Instant.now().getEpochSecond() - secs;
                filter = new ir.permscraft.storage.LogFilter(null, null, null, from, null, limit, 0);
            }
            default -> { // "recent"
                if (args.length >= 3) limit = parseIntOrDefault(args[2], limit);
                filter = new ir.permscraft.storage.LogFilter(null, null, null, null, null, limit, 0);
            }
        }

        runLogQuery(sender, filter);
    }

    /**
     * /pc log query key=value key=value ...
     * Supported keys: actor, target, action, since (duration, e.g. 7d),
     * from/to (epoch seconds), limit, offset.
     * Mirrors the filter dimensions of GET /api/v1/logs.
     */
    private void handleLogQuery(CommandSender sender, String[] args) {
        String actor = null, target = null, action = null;
        Long from = null, to = null;
        int limit = 25, offset = 0;

        for (int i = 2; i < args.length; i++) {
            String[] kv = args[i].split("=", 2);
            if (kv.length != 2) continue;
            String key = kv[0].toLowerCase();
            String val = kv[1];
            switch (key) {
                case "actor"  -> actor  = val;
                case "target" -> target = val;
                case "action" -> action = val.toUpperCase();
                case "since"  -> {
                    long secs = ir.permscraft.utils.DurationParser.parseOrNegative(val);
                    if (secs > 0) from = java.time.Instant.now().getEpochSecond() - secs;
                }
                case "from"   -> from   = parseLongOrNull(val);
                case "to"     -> to     = parseLongOrNull(val);
                case "limit"  -> limit  = parseIntOrDefault(val, limit);
                case "offset" -> offset = parseIntOrDefault(val, offset);
                default -> { /* ignore unknown keys */ }
            }
        }

        if (args.length <= 2) {
            MessageUtil.send(sender, "&cUsage: /pc log query <key>=<value> ...");
            MessageUtil.sendRaw(sender, "&7Keys: &eactor, target, action, since, from, to, limit, offset");
            MessageUtil.sendRaw(sender, "&7Example: &b/pc log query actor=Notch action=USER_GROUP_ADD since=1d limit=25");
            return;
        }

        runLogQuery(sender, new ir.permscraft.storage.LogFilter(actor, target, action, from, to, limit, offset));
    }

    private void runLogQuery(CommandSender sender, ir.permscraft.storage.LogFilter filter) {
        FoliaScheduler.runAsync(plugin, () -> {
            var entries = plugin.getLogManager().query(filter);
            long total = plugin.getLogManager().count(filter);

            FoliaScheduler.runSync(plugin, () -> {
                MessageUtil.send(sender, "&7--- &bPermsCraft Log &7(showing &e" + entries.size()
                        + "&7 of &e" + total + "&7 match" + (total == 1 ? "" : "es") + ") ---");
                List<String> activeFilters = new ArrayList<>();
                if (filter.actor()  != null) activeFilters.add("actor=" + filter.actor());
                if (filter.target() != null) activeFilters.add("target=" + filter.target());
                if (filter.action() != null) activeFilters.add("action=" + filter.action());
                if (filter.from()   != null) activeFilters.add("from=" + filter.from());
                if (filter.to()     != null) activeFilters.add("to=" + filter.to());
                if (!activeFilters.isEmpty())
                    MessageUtil.sendRaw(sender, "  &8Filters: &7" + String.join(" &8| &7", activeFilters));
                if (entries.isEmpty()) { MessageUtil.sendRaw(sender, "  &8(no entries)"); return; }
                var fmt = java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(java.time.ZoneId.systemDefault());
                for (var e : entries) {
                    MessageUtil.sendRaw(sender, "  &8[&7" + fmt.format(e.getTimestamp()) + "&8] &b" + e.getActor()
                            + " &8| &f" + e.getAction().name().toLowerCase().replace("_", " ")
                            + " &8| &e" + e.getTarget() + " &8| &7" + e.getDetail());
                }
            });
        });
    }

    private static int parseIntOrDefault(String s, int def) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return def; }
    }

    private static Long parseLongOrNull(String s) {
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return null; }
    }

    // ==================== BACKUP ====================

    private void handleBackup(CommandSender sender, String[] args) {
        if (args.length < 2) {
            MessageUtil.send(sender, "&7Backup commands:");
            MessageUtil.sendRaw(sender, "  &b/pc backup export &7- Export all data to YAML");
            MessageUtil.sendRaw(sender, "  &b/pc backup import &e<filename> &7- Import from YAML");
            MessageUtil.sendRaw(sender, "  &b/pc backup list &7- List available backups");
            return;
        }
        switch (args[1].toLowerCase()) {
            case "export" -> plugin.getYamlBackup().export(sender);
            case "import" -> {
                if (args.length < 3) { MessageUtil.send(sender, "&cUsage: /pc backup import <filename>"); return; }
                plugin.getYamlBackup().importBackup(sender, args[2]);
            }
            case "list" -> {
                var files = plugin.getYamlBackup().listBackups();
                if (files.isEmpty()) { MessageUtil.send(sender, "&7No backups found."); return; }
                MessageUtil.send(sender, "&7Available backups:");
                files.forEach(f -> MessageUtil.sendRaw(sender, "  &8- &f" + f));
            }
            default -> MessageUtil.send(sender, "&cUsage: /pc backup <export|import|list>");
        }
    }

    // ==================== MIGRATE ====================

    private void handleMigrate(CommandSender sender, String[] args) {
        if (args.length < 2) {
            MessageUtil.send(sender, "&7Migration commands:");
            MessageUtil.sendRaw(sender, "  &b/pc migrate luckperms &7- Import data from LuckPerms (YAML storage)");
            MessageUtil.sendRaw(sender, "  &b/pc migrate standard &7- Import data from another permission plugin (GroupManager/PEX-style YAML)");
            return;
        }
        String source = args[1].toLowerCase();
        switch (source) {
            case "luckperms", "lp" -> plugin.getMigrator().migrate(sender, "luckperms");
            case "standard"        -> plugin.getMigrator().migrate(sender, "standard");
            default -> MessageUtil.send(sender, "&cUnknown migration source: &e" + args[1] + "&c. Use: &bluckperms&c, &bstandard");
        }
    }

    // ==================== API KEY ====================

    /**
     * /pc apikey create <label> [scopes...]   (default scope: read)
     * /pc apikey list
     * /pc apikey revoke <label>
     *
     * Valid scopes: read, write, log, sync, backup, admin
     * Works even when rest-api.enabled is false in config.yml — keys can be
     * pre-provisioned before the HTTP server is turned on.
     */
    private void handleApiKey(CommandSender sender, String[] args) {
        var keyManager = plugin.getRestApiServer().getKeyManager();

        if (args.length < 2) {
            sendApiKeyHelp(sender);
            return;
        }

        switch (args[1].toLowerCase()) {
            case "create" -> {
                if (args.length < 3) { MessageUtil.send(sender, "&cUsage: /pc apikey create <label> [scopes...]"); return; }
                String label = args[2];

                List<String> scopes = new ArrayList<>();
                if (args.length > 3) {
                    Set<String> valid = Set.of(
                            ir.permscraft.api.rest.auth.ApiKeyManager.SCOPE_READ,
                            ir.permscraft.api.rest.auth.ApiKeyManager.SCOPE_WRITE,
                            ir.permscraft.api.rest.auth.ApiKeyManager.SCOPE_LOG,
                            ir.permscraft.api.rest.auth.ApiKeyManager.SCOPE_SYNC,
                            ir.permscraft.api.rest.auth.ApiKeyManager.SCOPE_BACKUP,
                            ir.permscraft.api.rest.auth.ApiKeyManager.SCOPE_ADMIN);
                    for (int i = 3; i < args.length; i++) {
                        String scope = args[i].toLowerCase();
                        if (!valid.contains(scope)) {
                            MessageUtil.send(sender, "&cUnknown scope: &e" + scope
                                    + "&c. Valid scopes: &bread, write, log, sync, backup, admin");
                            return;
                        }
                        scopes.add(scope);
                    }
                } else {
                    scopes.add(ir.permscraft.api.rest.auth.ApiKeyManager.SCOPE_READ);
                }

                String actor = (sender instanceof Player p) ? p.getName() : "console";
                try {
                    String plaintext = keyManager.createKey(label, scopes, actor);
                    MessageUtil.send(sender, "&aAPI key created for &b" + label + "&a:");
                    MessageUtil.sendRaw(sender, "  &f" + plaintext);
                    MessageUtil.sendRaw(sender, "&7Scopes: &e" + String.join(", ", scopes));
                    MessageUtil.send(sender, "&c⚠ Copy this now — it will not be shown again.");
                } catch (ir.permscraft.api.rest.ApiException e) {
                    MessageUtil.send(sender, "&c" + e.getMessage());
                }
            }
            case "list" -> {
                var keys = keyManager.listKeys();
                if (keys.isEmpty()) { MessageUtil.send(sender, "&7No API keys exist."); return; }
                MessageUtil.send(sender, "&7API keys (&e" + keys.size() + "&7):");
                keys.forEach(k -> MessageUtil.sendRaw(sender,
                        "  &8- &b" + k.label() + " &7[" + String.join(", ", k.scopes()) + "] &8(by " + k.createdBy() + ")"));
            }
            case "revoke" -> {
                if (args.length < 3) { MessageUtil.send(sender, "&cUsage: /pc apikey revoke <label>"); return; }
                String label = args[2];
                if (keyManager.revokeByLabel(label)) {
                    MessageUtil.send(sender, "&aRevoked API key &e" + label);
                } else {
                    MessageUtil.send(sender, "&cNo API key found with label &e" + label);
                }
            }
            default -> sendApiKeyHelp(sender);
        }
    }

    private void sendApiKeyHelp(CommandSender sender) {
        MessageUtil.send(sender, "&7API key commands:");
        MessageUtil.sendRaw(sender, "  &b/pc apikey create &e<label> &7[scopes...] &8— default scope: read");
        MessageUtil.sendRaw(sender, "  &b/pc apikey list &7- List all keys (labels only, no plaintext)");
        MessageUtil.sendRaw(sender, "  &b/pc apikey revoke &e<label>");
        MessageUtil.sendRaw(sender, "  &7Scopes: &bread, write, log, sync, backup, admin");
    }

    // ==================== VERBOSE ====================

    private void handleVerbose(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) { MessageUtil.send(sender, "&cOnly players can use verbose mode."); return; }
        if (args.length < 2) {
            MessageUtil.send(sender, "&7Verbose commands:");
            MessageUtil.sendRaw(sender, "  &b/pc verbose on &8[&7player&8] &7- Start listening");
            MessageUtil.sendRaw(sender, "  &b/pc verbose off &7- Stop");
            MessageUtil.sendRaw(sender, "  &b/pc verbose paste &7- Show last 100 entries");
            return;
        }
        switch (args[1].toLowerCase()) {
            case "on"    -> { String filter = args.length >= 3 ? args[2] : "*"; plugin.getVerboseManager().startSession(player, filter); }
            case "off"   -> plugin.getVerboseManager().stopSession(player);
            case "paste" -> plugin.getVerboseManager().paste(player);
            case "export" -> plugin.getVerboseManager().export(player);
            default      -> MessageUtil.send(sender, "&cUsage: /pc verbose <on|off|paste>");
        }
    }

    // ==================== CHECK ====================

    private void handleCheck(CommandSender sender, String[] args) {
        if (args.length < 4) { MessageUtil.send(sender, "&cUsage: /pc check <user|group> <name> <permission>"); return; }
        String type = args[1].toLowerCase();
        String name = args[2];
        String perm = args[3];
        if (type.equals("user")) {
            UUID uuid = userHandler.resolveUUID(name);
            if (uuid == null) { MessageUtil.send(sender, "&cPlayer not found."); return; }
            var user = plugin.getUserManager().getUser(uuid);
            if (user == null) user = plugin.getStorage().loadUser(uuid, name);
            var result = plugin.getPermissionCalculator().calculate(user, perm);
            MessageUtil.send(sender, "&7--- Permission Check: &b" + name + " &8| &f" + perm + " &7---");
            for (var step : result.steps) {
                String c = step.value() ? "&a" : "&c";
                MessageUtil.sendRaw(sender, "  &8[&7" + step.source().name() + "&8] " + c + step.node() + " &8- &7" + step.reason());
            }
            String fc = result.finalResult ? "&a" : "&c";
            MessageUtil.sendRaw(sender, "  &7Final: " + fc + (result.finalResult ? "GRANTED" : "DENIED") + " &8- &7" + result.finalReason);
        } else if (type.equals("group")) {
            var g = plugin.getGroupManager().getGroup(name);
            if (g == null) { MessageUtil.send(sender, "&cGroup not found."); return; }
            var resolved = plugin.getInheritanceGraph().resolveGroup(name);
            boolean has = resolved.getOrDefault(perm, false);
            MessageUtil.send(sender, "&7Group &b" + name + "&8: &f" + perm + " &8= " + (has ? "&aGRANTED" : "&cDENIED"));
            var chain = plugin.getInheritanceGraph().getInheritanceChain(name);
            if (!chain.isEmpty()) MessageUtil.sendRaw(sender, "  &7Inheritance chain: &e" + String.join(" &8→ &e", chain));
        }
    }

    // ==================== DEBUG ====================

    private void handleDebug(CommandSender sender, String[] args) {
        MessageUtil.send(sender, "&7--- &bPermsCraft Debug &7---");
        MessageUtil.sendRaw(sender, "  &7Cache: &f" + plugin.getPermissionCache().getStats());
        MessageUtil.sendRaw(sender, "  &7Groups loaded: &a" + plugin.getGroupManager().getAllGroups().size());
        MessageUtil.sendRaw(sender, "  &7Tracks: &a" + plugin.getTrackManager().getAllTracks().size());
        MessageUtil.sendRaw(sender, "  &7Online users: &a" + Bukkit.getOnlinePlayers().size());
        MessageUtil.sendRaw(sender, "  &7Redis: " + (plugin.getRedisManager().isEnabled() ? "&aConnected" : "&cDisabled"));
        MessageUtil.sendRaw(sender, "  &7BungeeCord Messaging: " + (plugin.getMessagingManager().isEnabled() ? "&aEnabled" : "&cDisabled"));
        MessageUtil.sendRaw(sender, "  &7Verbose sessions: &e" + (plugin.getVerboseManager() != null ? "active" : "none"));
        MessageUtil.sendRaw(sender, "  &7Storage: &f" + plugin.getStorage().getClass().getSimpleName());
        MessageUtil.sendRaw(sender, "  &7Bukkit plugin defaults cached: &e" + plugin.getDefaultsCache().size());
    }

    // ==================== CONTEXT ====================

    private void handleContext(CommandSender sender, String[] args) {
        if (args.length < 2) {
            MessageUtil.send(sender, "&7Context permission commands:");
            MessageUtil.sendRaw(sender, "  &b/pc context user &e<name> set &f<perm> &7<ctx>   &8— e.g. world=survival");
            MessageUtil.sendRaw(sender, "  &b/pc context user &e<name> set &f<perm> &7<ctx,ctx,...> &8— multi-key e.g. world=pvp,gamemode=survival");
            MessageUtil.sendRaw(sender, "  &b/pc context user &e<name> unset &f<perm> &7<ctx>");
            MessageUtil.sendRaw(sender, "  &b/pc context user &e<name> list");
            MessageUtil.sendRaw(sender, "  &b/pc context user &e<name> debug  &8— show active context keys");
            MessageUtil.sendRaw(sender, "  &b/pc context group &e<name> set/unset/list ...");
            MessageUtil.sendRaw(sender, "  &7Built-in keys: &fworld, server, gamemode, dimension, world-uuid");
            MessageUtil.sendRaw(sender, "  &7Wildcard: &fworld=*  &7(matches any world)");
            return;
        }

        String type = args[1].toLowerCase();
        if (args.length < 3) { MessageUtil.send(sender, "&cUsage: /pc context <user|group> <name> <set|unset|list> ..."); return; }
        String target   = args[2];
        String action   = args.length >= 4 ? args[3].toLowerCase() : "list";
        boolean isGroup = type.equals("group");

        String key;
        if (isGroup) {
            key = target.toLowerCase();
        } else {
            UUID resolved = userHandler.resolveUUID(target);
            if (resolved == null) { MessageUtil.send(sender, "&cTarget not found."); return; }
            key = resolved.toString();
        }

        switch (action) {
            case "list" -> {
                if ("debug".equals(action)) {
                    org.bukkit.entity.Player debugTarget = plugin.getServer().getPlayerExact(target);
                    if (debugTarget == null) { MessageUtil.send(sender, "&cPlayer must be online for debug."); return; }
                    plugin.getContextManager().debugPlayer(debugTarget)
                            .forEach(line -> MessageUtil.sendRaw(sender, line));
                    return;
                }
                var perms = plugin.getContextManager().getPermissions(key);
                MessageUtil.send(sender, "&7Context permissions for &b" + target + "&7:");
                if (perms.isEmpty()) { MessageUtil.sendRaw(sender, "  &8(none)"); return; }
                perms.forEach(cp -> MessageUtil.sendRaw(sender, "  &8- &f" + cp));
            }
            case "set", "unset" -> {
                if (args.length < 6) {
                    MessageUtil.send(sender, "&cUsage: /pc context " + type + " <name> " + action + " <permission> <world=name>");
                    return;
                }
                String perm   = args[4];
                // Multi-key context: "world=survival,gamemode=survival" or single "world=survival"
                ir.permscraft.context.ContextSet ctx;
                if (args[5].contains(",")) {
                    ir.permscraft.context.ContextSet.Builder ctxBuilder = ir.permscraft.context.ContextSet.builder();
                    for (String part : args[5].split(",")) {
                        String[] kv = part.split("=", 2);
                        if (kv.length == 2) ctxBuilder.put(kv[0].trim(), kv[1].trim());
                    }
                    ctx = ctxBuilder.build();
                } else {
                    ir.permscraft.context.Context single = ir.permscraft.context.Context.fromString(args[5]);
                    ctx = single.isGlobal() ? ir.permscraft.context.ContextSet.global()
                            : ir.permscraft.context.ContextSet.builder().put(single).build();
                }
                boolean grant = action.equals("set");
                if (grant) {
                    plugin.getContextManager().addContextPermission(key, isGroup, perm, ctx, true);
                    MessageUtil.send(sender, "&aSet &e" + perm + " &ain context &b" + ctx + " &afor &f" + target);
                } else {
                    plugin.getContextManager().removeContextPermission(key, perm, ctx);
                    MessageUtil.send(sender, "&cUnset &e" + perm + " &cin context &b" + ctx + " &cfor &f" + target);
                }
                if (!isGroup) {
                    UUID uuid = userHandler.resolveUUID(target);
                    if (uuid != null) plugin.getUserManager().refreshPermissions(uuid);
                }
            }
            default -> MessageUtil.send(sender, "&cUsage: /pc context <user|group> <name> <set|unset|list>");
        }
    }

    // ==================== RELOAD / INFO ====================

    private void handleReload(CommandSender sender) {
        plugin.reloadConfig();
        plugin.getGroupManager().loadGroups();
        plugin.getTrackManager().loadTracks();
        plugin.getTimedPermissionManager().reload();
        plugin.getContextManager().reload();
        plugin.getServer().getOnlinePlayers().forEach(p ->
                plugin.getUserManager().refreshPermissions(p.getUniqueId()));
        MessageUtil.send(sender, "&aPermsCraft reloaded successfully.");
    }

    private void handleInfo(CommandSender sender) {
        MessageUtil.send(sender, "&7--- &bPermsCraft &7v" + plugin.getDescription().getVersion() + " &7---");
        MessageUtil.sendRaw(sender, "  &7Groups: &a" + plugin.getGroupManager().getAllGroups().size());
        MessageUtil.sendRaw(sender, "  &7Tracks: &a" + plugin.getTrackManager().getAllTracks().size());
        MessageUtil.sendRaw(sender, "  &7Online users: &a" + Bukkit.getOnlinePlayers().size());
        MessageUtil.sendRaw(sender, "  &7Website: &bhttps://permscraft.ir");
    }

    // ==================== SYNC ====================

    private void handleSync(CommandSender sender) {
        MessageUtil.send(sender, "&7Syncing all data from storage...");
        FoliaScheduler.runAsync(plugin, () -> {
            plugin.getGroupManager().loadGroups();
            plugin.getTrackManager().loadTracks();
            plugin.getTimedPermissionManager().reload();
            plugin.getContextManager().reload();
            FoliaScheduler.runSync(plugin, () -> {
                plugin.getServer().getOnlinePlayers().forEach(p ->
                        plugin.getUserManager().refreshPermissions(p.getUniqueId()));
                MessageUtil.send(sender, "&aSync complete. All data reloaded from storage.");
            });
        });
    }

    // ==================== SEARCH ====================

    private void handleSearch(CommandSender sender, String[] args) {
        if (args.length < 2) { MessageUtil.send(sender, "&cUsage: /pc search <permission>"); return; }
        String perm = args[1];
        FoliaScheduler.runAsync(plugin, () -> {
            List<String> results = plugin.getStorage().searchPermission(perm);
            FoliaScheduler.runSync(plugin, () -> {
                if (results.isEmpty()) {
                    MessageUtil.send(sender, "&7No user or group holds the permission &e" + perm + "&7 directly.");
                    return;
                }
                MessageUtil.send(sender, "&7Search results for &e" + perm + " &8(" + results.size() + " found)&7:");
                for (String entry : results) {
                    if (entry.startsWith("group:")) {
                        MessageUtil.sendRaw(sender, "  &8[&bGroup&8] &a" + entry.substring(6));
                    } else {
                        String name = entry;
                        try {
                            UUID uid = UUID.fromString(entry);
                            var user = plugin.getUserManager().getUser(uid);
                            if (user != null) name = user.getUsername();
                            else {
                                var online = Bukkit.getPlayer(uid);
                                if (online != null) name = online.getName();
                            }
                        } catch (IllegalArgumentException ignored) {}
                        MessageUtil.sendRaw(sender, "  &8[&eUser&8] &f" + name);
                    }
                }
            });
        });
    }

    // ==================== TREE ====================

    private void handleTree(CommandSender sender, String[] args) {
        String scope = args.length >= 2 ? args[1].toLowerCase() : null;
        if (scope != null && scope.startsWith("group:")) {
            String groupName = scope.substring(6);
            if (plugin.getGroupManager().getGroup(groupName) == null) {
                MessageUtil.send(sender, "&cGroup not found.");
                return;
            }
            var resolved = plugin.getInheritanceGraph().resolveGroupWithSource(groupName);
            MessageUtil.send(sender, "&7Permission tree for group &b" + groupName + " &8(" + resolved.size() + " nodes)&7:");
            resolved.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> {
                        var r = e.getValue();
                        String marker = r.value() ? "&8- &a" : "&8- &c-";
                        String source = r.source().equalsIgnoreCase(groupName) ? "self" : r.source();
                        MessageUtil.sendRaw(sender, "  " + marker + e.getKey() + " &8← &7" + source);
                    });
        } else if (scope != null && scope.startsWith("user:")) {
            String username = scope.substring(5);
            UUID uuid = userHandler.resolveUUID(username);
            if (uuid == null) { MessageUtil.send(sender, "&cUser not found."); return; }
            userHandler.withUser(uuid, username, sender, user -> {
                var resolved = plugin.getInheritanceGraph().resolveUserWithSource(user);
                MessageUtil.send(sender, "&7Permission tree for user &b" + username + " &8(" + resolved.size() + " nodes)&7:");
                resolved.entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .forEach(e -> {
                            var r = e.getValue();
                            String marker = r.value() ? "&8- &a" : "&8- &c-";
                            MessageUtil.sendRaw(sender, "  " + marker + e.getKey() + " &8← &7" + r.source());
                        });
            });
        } else {
            java.util.Set<String> allPerms = new java.util.TreeSet<>();
            Bukkit.getPluginManager().getPermissions().forEach(p -> allPerms.add(p.getName()));
            plugin.getGroupManager().getAllGroups().forEach(g -> allPerms.addAll(g.getPermissions()));
            MessageUtil.send(sender, "&7Server permission tree &8(" + allPerms.size() + " known nodes)&7:");
            allPerms.forEach(p -> MessageUtil.sendRaw(sender, "  &8- &f" + p));
            MessageUtil.sendRaw(sender, "  &7Tip: use &b/pc tree group:<name> &7or &b/pc tree user:<name> &7for resolved view.");
        }
    }

    // ==================== EXPORT ====================

    private void handleExport(CommandSender sender, String[] args) {
        if (args.length < 2) { MessageUtil.send(sender, "&cUsage: /pc export <file>"); return; }
        String filename = args[1];
        if (!filename.endsWith(".txt")) filename += ".txt";
        final String finalFilename = filename;
        java.io.File outFile = new java.io.File(plugin.getDataFolder(), finalFilename);
        List<String> lines = buildExportLines();
        try {
            java.nio.file.Files.write(outFile.toPath(), lines, java.nio.charset.StandardCharsets.UTF_8);
            MessageUtil.send(sender, "&aExported &e" + lines.size() + " &acommand(s) to &b" + finalFilename);
        } catch (java.io.IOException e) {
            MessageUtil.send(sender, "&cFailed to write export file: " + e.getMessage());
        }
    }

    private List<String> buildExportLines() {
        List<String> lines = new ArrayList<>();
        lines.add("# PermsCraft export — " + new java.util.Date());
        lines.add("# Import with: /pc import <file>");
        lines.add("");
        for (Group g : plugin.getGroupManager().getAllGroups()) {
            lines.add("group " + g.getName() + " create");
            if (!g.getDisplayName().equals(g.getName()))
                lines.add("group " + g.getName() + " rename " + g.getDisplayName());
            if (g.getWeight() != 0)
                lines.add("group " + g.getName() + " setweight " + g.getWeight());
            if (!g.getPrefix().isEmpty())
                lines.add("group " + g.getName() + " setprefix " + g.getPrefix());
            if (!g.getSuffix().isEmpty())
                lines.add("group " + g.getName() + " setsuffix " + g.getSuffix());
            g.getPermissions().forEach(p ->
                lines.add("group " + g.getName() + " permission set " + p));
            g.getInheritedGroups().forEach(parent ->
                lines.add("group " + g.getName() + " parent add " + parent));
            g.getMeta().forEach((k, v) ->
                lines.add("group " + g.getName() + " meta set " + k + " " + v));
        }
        return lines;
    }

    // ==================== IMPORT ====================

    private void handleImportCmd(CommandSender sender, String[] args) {
        if (args.length < 2) { MessageUtil.send(sender, "&cUsage: /pc import <file>"); return; }
        String filename = args[1];
        if (!filename.endsWith(".txt")) filename += ".txt";
        java.io.File inFile = new java.io.File(plugin.getDataFolder(), filename);
        if (!inFile.exists()) { MessageUtil.send(sender, "&cFile not found: &e" + filename); return; }
        try {
            List<String> lines = java.nio.file.Files.readAllLines(inFile.toPath(), java.nio.charset.StandardCharsets.UTF_8);
            int executed = 0;
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = ("pc " + line).split(" ");
                onCommand(sender, plugin.getCommand("pc"), "pc", parts);
                executed++;
            }
            MessageUtil.send(sender, "&aImport complete. Executed &e" + executed + " &acommands from &b" + filename);
        } catch (java.io.IOException e) {
            MessageUtil.send(sender, "&cFailed to read import file: " + e.getMessage());
        }
    }

    // ==================== BULKUPDATE ====================

    private void handleBulkUpdate(CommandSender sender, String[] args) {
        if (args.length < 4) {
            MessageUtil.send(sender, "&7--- &bBulk Update ---");
            MessageUtil.sendRaw(sender, "  &b/pc bulkupdate &e<users|groups|all> add &f<permission>");
            MessageUtil.sendRaw(sender, "  &b/pc bulkupdate &e<users|groups|all> remove &f<permission>");
            MessageUtil.sendRaw(sender, "  &b/pc bulkupdate &eusers addgroup &f<group>");
            MessageUtil.sendRaw(sender, "  &b/pc bulkupdate &eusers removegroup &f<group>");
            MessageUtil.sendRaw(sender, "  &7Add &f preview &7as a final argument to see what WOULD change first.");
            MessageUtil.sendRaw(sender, "  &7Warning: This affects ALL records in storage.");
            return;
        }

        String scope  = args[1].toLowerCase();
        String action = args[2].toLowerCase();
        String value  = args[3];
        var bulk = plugin.getBulkUpdateManager();

        // Dry-run mode: /pc bulkupdate <scope> <action> <value> preview
        if (args.length >= 5 && args[args.length - 1].equalsIgnoreCase("preview")) {
            previewBulkUpdate(sender, scope, action, value);
            return;
        }

        switch (action) {
            case "add" -> {
                if (scope.equals("users") || scope.equals("all"))   bulk.addPermissionToAllUsers(sender, value);
                if (scope.equals("groups") || scope.equals("all"))  bulk.addPermissionToAllGroups(sender, value);
            }
            case "remove" -> {
                if (scope.equals("users") || scope.equals("all"))   bulk.removePermissionFromAllUsers(sender, value);
                if (scope.equals("groups") || scope.equals("all"))  bulk.removePermissionFromAllGroups(sender, value);
            }
            case "addgroup" -> {
                if (!scope.equals("users")) { MessageUtil.send(sender, "&caddgroup only works with 'users' scope."); return; }
                bulk.addGroupToAllUsers(sender, value);
            }
            case "removegroup" -> {
                if (!scope.equals("users")) { MessageUtil.send(sender, "&cremovegroup only works with 'users' scope."); return; }
                bulk.removeGroupFromAllUsers(sender, value);
            }
            default -> MessageUtil.send(sender, "&cUnknown action. Use: add, remove, addgroup, removegroup");
        }
    }

    /**
     * Dry-run preview for /pc bulkupdate. Reports how many currently
     * LOADED users / groups would be affected, WITHOUT making any changes.
     *
     * Note: offline users sitting in storage are not counted here (they are
     * not loaded into memory), but they WILL be affected by the real run —
     * this is clearly noted in the output so admins aren't misled.
     */
    private void previewBulkUpdate(CommandSender sender, String scope, String action, String value) {
        MessageUtil.send(sender, "&7--- Bulk Update Preview &8(dry run, nothing changed)&7 ---");
        switch (action) {
            case "add", "remove" -> {
                boolean adding = action.equals("add");
                if (scope.equals("groups") || scope.equals("all")) {
                    var groups = plugin.getGroupManager().getAllGroups();
                    int affected = 0;
                    for (Group g : groups) {
                        boolean has = g.hasPermission(value);
                        if (adding ? !has : has) affected++;
                    }
                    MessageUtil.sendRaw(sender, "  &bGroups&7: &a" + affected + " &7of &f" + groups.size()
                            + " &7would change &8(already " + (adding ? "have" : "lack") + " it: "
                            + (groups.size() - affected) + ")");
                }
                if (scope.equals("users") || scope.equals("all")) {
                    var loaded = plugin.getUserManager().getAllLoadedUsers();
                    int affected = 0;
                    for (User u : loaded) {
                        boolean has = u.hasPermission(value);
                        if (adding ? !has : has) affected++;
                    }
                    MessageUtil.sendRaw(sender, "  &bUsers (loaded)&7: &a" + affected + " &7of &f" + loaded.size()
                            + " &7would change &8(already " + (adding ? "have" : "lack") + " it: "
                            + (loaded.size() - affected) + ")");
                    MessageUtil.sendRaw(sender, "  &8Note: offline users in storage are not shown here but WILL be affected.");
                }
            }
            case "addgroup", "removegroup" -> {
                if (!scope.equals("users")) {
                    MessageUtil.send(sender, "&caddgroup/removegroup preview only works with 'users' scope.");
                    return;
                }
                boolean adding = action.equals("addgroup");
                var loaded = plugin.getUserManager().getAllLoadedUsers();
                int affected = 0;
                for (User u : loaded) {
                    boolean inGroup = u.getGroups().stream().anyMatch(g -> g.equalsIgnoreCase(value));
                    if (adding ? !inGroup : inGroup) affected++;
                }
                MessageUtil.sendRaw(sender, "  &bUsers (loaded)&7: &a" + affected + " &7of &f" + loaded.size()
                        + " &7would change &8(already " + (adding ? "in" : "not in") + " group: "
                        + (loaded.size() - affected) + ")");
                MessageUtil.sendRaw(sender, "  &8Note: offline users in storage are not shown here but WILL be affected.");
            }
            default -> { MessageUtil.send(sender, "&cUnknown action. Use: add, remove, addgroup, removegroup"); return; }
        }
        MessageUtil.sendRaw(sender, "&7Run the same command without &f'preview' &7at the end to apply.");
    }

    // ==================== LISTGROUPS ====================

    private void handleListGroups(CommandSender sender) {
        var groups = plugin.getGroupManager().getAllGroups().stream()
                .sorted(java.util.Comparator.comparingInt(Group::getWeight).reversed()
                        .thenComparing(Group::getName))
                .toList();
        MessageUtil.send(sender, "&7All groups &8(" + groups.size() + ")&7:");
        for (Group g : groups) {
            MessageUtil.sendRaw(sender, "  &8- &b" + g.getName()
                    + " &8(weight: &e" + g.getWeight() + "&8, perms: &f" + g.getPermissions().size()
                    + "&8, parents: &7" + String.join(", ", g.getInheritedGroups()) + "&8)");
        }
    }

    // ==================== HELP ====================

    private void sendHelp(CommandSender sender) {
        MessageUtil.sendRaw(sender, "&8&m--------------------------------------");
        MessageUtil.sendRaw(sender, " &bPermsCraft &7v" + plugin.getDescription().getVersion() + " &8- &7Command Help");
        MessageUtil.sendRaw(sender, "&8&m--------------------------------------");
        MessageUtil.sendRaw(sender, "");
        MessageUtil.sendRaw(sender, "&b&lUser Commands");
        MessageUtil.sendRaw(sender, "  &b/pc user &e<name> info &8- &7View user info");
        MessageUtil.sendRaw(sender, "  &b/pc user &e<name> permission set &f<node> &8- &7Grant a permission");
        MessageUtil.sendRaw(sender, "  &b/pc user &e<name> permission unset &f<node> &8- &7Remove a permission");
        MessageUtil.sendRaw(sender, "  &b/pc user &e<name> permission settemp &f<node> <duration> &8- &7Temporary permission");
        MessageUtil.sendRaw(sender, "  &b/pc user &e<name> permission list &8- &7List permissions");
        MessageUtil.sendRaw(sender, "  &b/pc user &e<name> group add &f<group> &8- &7Add to group");
        MessageUtil.sendRaw(sender, "  &b/pc user &e<name> group remove &f<group> &8- &7Remove from group");
        MessageUtil.sendRaw(sender, "  &b/pc user &e<name> promote &f<track> &8- &7Promote on track");
        MessageUtil.sendRaw(sender, "  &b/pc user &e<name> demote &f<track> &8- &7Demote on track");
        MessageUtil.sendRaw(sender, "  &b/pc user &e<name> clear &8- &7Wipe all permissions, groups & meta");
        MessageUtil.sendRaw(sender, "  &b/pc user &e<name> showtracks &8- &7Show tracks user is in");
        MessageUtil.sendRaw(sender, "  &b/pc user &e<name> switchprimarygroup &f<group> &8- &7Change primary group");
        MessageUtil.sendRaw(sender, "  &b/pc user &e<name> meta set &f<key> <value> &8- &7Set meta");
        MessageUtil.sendRaw(sender, "  &b/pc user &e<name> meta unset &f<key> &8- &7Remove meta");
        MessageUtil.sendRaw(sender, "  &b/pc user &e<name> meta settemp &f<key> <value> <duration> &8- &7Temp meta");
        MessageUtil.sendRaw(sender, "  &b/pc user &e<name> meta list &8- &7List meta");
        MessageUtil.sendRaw(sender, "  &b/pc user &e<name> setprefix &f<prefix> &8- &7Set prefix");
        MessageUtil.sendRaw(sender, "  &b/pc user &e<name> setsuffix &f<suffix> &8- &7Set suffix");
        MessageUtil.sendRaw(sender, "");
        MessageUtil.sendRaw(sender, "&b&lGroup Commands");
        MessageUtil.sendRaw(sender, "  &b/pc group &8- &7List all groups");
        MessageUtil.sendRaw(sender, "  &b/pc listgroups &8- &7List all groups with weight info");
        MessageUtil.sendRaw(sender, "  &b/pc group &e<name> &8- &7View group info");
        MessageUtil.sendRaw(sender, "  &b/pc group &e<name> create &8- &7Create a new group");
        MessageUtil.sendRaw(sender, "  &b/pc group &e<name> delete &8- &7Delete a group");
        MessageUtil.sendRaw(sender, "  &b/pc group &e<name> permission set &f<node> &8- &7Grant permission");
        MessageUtil.sendRaw(sender, "  &b/pc group &e<name> permission unset &f<node> &8- &7Remove permission");
        MessageUtil.sendRaw(sender, "  &b/pc group &e<name> permission settemp &f<node> <duration> &8- &7Temporary permission");
        MessageUtil.sendRaw(sender, "  &b/pc group &e<name> permission listall &8- &7List all (incl. inherited)");
        MessageUtil.sendRaw(sender, "  &b/pc group &e<name> parent add &f<parent> &8- &7Add inheritance");
        MessageUtil.sendRaw(sender, "  &b/pc group &e<name> parent remove &f<parent> &8- &7Remove inheritance");
        MessageUtil.sendRaw(sender, "  &b/pc group &e<name> clear &8- &7Wipe all permissions & parents");
        MessageUtil.sendRaw(sender, "  &b/pc group &e<name> showtracks &8- &7Show tracks containing group");
        MessageUtil.sendRaw(sender, "  &b/pc group &e<name> meta set &f<key> <value> &8- &7Set meta");
        MessageUtil.sendRaw(sender, "  &b/pc group &e<name> meta unset &f<key> &8- &7Remove meta");
        MessageUtil.sendRaw(sender, "  &b/pc group &e<name> meta list &8- &7List meta");
        MessageUtil.sendRaw(sender, "  &b/pc group &e<name> setprefix/setsuffix/setweight/rename/clone ...");
        MessageUtil.sendRaw(sender, "");
        MessageUtil.sendRaw(sender, "&b&lTrack Commands");
        MessageUtil.sendRaw(sender, "  &b/pc track &8- &7List all tracks");
        MessageUtil.sendRaw(sender, "  &b/pc track &e<name> create/delete/append/remove/promote/demote ...");
        MessageUtil.sendRaw(sender, "");
        MessageUtil.sendRaw(sender, "&b&lUtility Commands");
        MessageUtil.sendRaw(sender, "  &b/pc sync &8- &7Refresh all data from storage");
        MessageUtil.sendRaw(sender, "  &b/pc search &e<permission> &8- &7Find all users/groups with a permission");
        MessageUtil.sendRaw(sender, "  &b/pc tree &e[group:<name>|user:<name>] &8- &7View permission tree");
        MessageUtil.sendRaw(sender, "  &b/pc export &e<file> &8- &7Export data as command list");
        MessageUtil.sendRaw(sender, "  &b/pc import &e<file> &8- &7Import from command list file");
        MessageUtil.sendRaw(sender, "  &b/pc editor &8- &7Open the in-game GUI editor");
        MessageUtil.sendRaw(sender, "  &b/pc check &e<user|group> <name> <perm> &8- &7Why is a permission granted?");
        MessageUtil.sendRaw(sender, "  &b/pc verbose on &e[player] &8- &7Watch real-time permission checks");
        MessageUtil.sendRaw(sender, "  &b/pc context user &e<name> set <perm> <world=name> &8- &7Per-world permission");
        MessageUtil.sendRaw(sender, "  &b/pc log &e[recent|user <n>|actor <n>|action <type>|since <dur>|query k=v...|actions] &8- &7View action log");
        MessageUtil.sendRaw(sender, "  &b/pc backup export &8- &7Export all data to YAML");
        MessageUtil.sendRaw(sender, "  &b/pc backup import &e<file> &8- &7Import from YAML backup");
        MessageUtil.sendRaw(sender, "  &b/pc migrate standard &8- &7Import from another permission plugin");
        MessageUtil.sendRaw(sender, "  &b/pc bulkupdate &e<users|groups|all> <add|remove> &f<permission> &8- &7Bulk permission change");
        MessageUtil.sendRaw(sender, "  &b/pc bulkupdate &eusers <addgroup|removegroup> &f<group> &8- &7Bulk group assign");
        MessageUtil.sendRaw(sender, "  &b/pc bulkupdate &e... ... &f<value> preview &8- &7Preview affected count, no changes made");
        MessageUtil.sendRaw(sender, "  &b/pc conflicts &e[user|group] [name] &8- &7Find permissions both granted & denied");
        MessageUtil.sendRaw(sender, "  &b/pc debug &8- &7Plugin diagnostics & cache stats");
        MessageUtil.sendRaw(sender, "  &b/pc reload &8- &7Reload config and groups");
        MessageUtil.sendRaw(sender, "  &b/pc info &8- &7Plugin info");
        MessageUtil.sendRaw(sender, "");
        MessageUtil.sendRaw(sender, "&8&m--------------------------------------");
    }

    // ==================== TAB COMPLETE ====================

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("permscraft.admin")) return Collections.emptyList();

        if (args.length == 1)
            return filter(List.of("user", "group", "track", "editor", "context", "log", "backup", "migrate",
                    "verbose", "check", "debug", "reload", "info", "help",
                    "sync", "search", "tree", "export", "import", "listgroups", "bulkupdate", "conflicts", "apikey"), args[0]);

        return switch (args[0].toLowerCase()) {
            case "user"    -> tabUser(args);
            case "group"   -> tabGroup(args);
            case "track"   -> tabTrack(args);
            case "apikey"  -> args.length == 2 ? filter(List.of("create", "list", "revoke"), args[1]) :
                              args.length == 3 && args[1].equalsIgnoreCase("revoke") ?
                              filter(plugin.getRestApiServer().getKeyManager().listKeys().stream()
                                      .map(ir.permscraft.api.rest.auth.ApiKeyManager.KeyEntry::label).toList(), args[2]) :
                              args.length >= 4 && args[1].equalsIgnoreCase("create") ?
                              filter(List.of("read", "write", "log", "sync", "backup", "admin"), args[args.length - 1]) :
                              Collections.emptyList();
            case "conflicts" -> args.length == 2 ? filter(List.of("user", "group"), args[1]) :
                                 args.length == 3 && args[1].equalsIgnoreCase("group") ?
                                 filter(plugin.getGroupManager().getAllGroups().stream().map(Group::getName).toList(), args[2]) :
                                 Collections.emptyList();
            case "log"     -> args.length == 2 ? filter(List.of("recent", "user", "actor", "action", "since", "actions", "query"), args[1]) :
                              args.length == 3 && args[1].equalsIgnoreCase("action") ?
                              filter(Arrays.stream(ir.permscraft.logging.LogEntry.Action.values()).map(Enum::name).toList(), args[2]) :
                              args.length >= 3 && args[1].equalsIgnoreCase("query") ?
                              filter(List.of("actor=", "target=", "action=", "since=", "from=", "to=", "limit=", "offset="), args[args.length - 1]) :
                              Collections.emptyList();
            case "backup"  -> args.length == 2 ? filter(List.of("export", "import", "list"), args[1]) :
                              args.length == 3 && args[1].equalsIgnoreCase("import") ?
                              filter(plugin.getYamlBackup().listBackups(), args[2]) : Collections.emptyList();
            case "migrate" -> args.length == 2 ? filter(List.of("luckperms", "standard"), args[1]) : Collections.emptyList();
            case "context" -> args.length == 2 ? filter(List.of("user", "group"), args[1]) :
                              args.length == 4 ? filter(List.of("set", "unset", "list"), args[3]) : Collections.emptyList();
            case "bulkupdate" -> args.length == 2 ? filter(List.of("users", "groups", "all"), args[1]) :
                                  args.length == 3 ? filter(List.of("add", "remove", "addgroup", "removegroup"), args[2]) :
                                  args.length == 4 && (args[2].equalsIgnoreCase("addgroup") || args[2].equalsIgnoreCase("removegroup")) ?
                                  filter(plugin.getGroupManager().getAllGroups().stream().map(Group::getName).toList(), args[3]) :
                                  args.length == 5 ? filter(List.of("preview"), args[4]) :
                                  Collections.emptyList();
            default        -> Collections.emptyList();
        };
    }

    private List<String> tabUser(String[] args) {
        if (args.length == 2) {
            List<String> names = new ArrayList<>();
            Bukkit.getOnlinePlayers().forEach(p -> names.add(p.getName()));
            return filter(names, args[1]);
        }
        if (args.length == 3)
            return filter(List.of("info", "permission", "perm", "group", "parent",
                    "setprefix", "setsuffix", "clearprefix", "clearsuffix", "promote", "demote",
                    "clear", "showtracks", "switchprimarygroup", "meta", "clone"), args[2]);
        if (args.length == 4) {
            return switch (args[2].toLowerCase()) {
                case "permission", "perm" -> filter(List.of("set", "unset", "settemp", "unsettemp", "list"), args[3]);
                case "group", "parent"    -> filter(List.of("add", "remove", "list"), args[3]);
                case "promote", "demote"  -> filter(plugin.getTrackManager().getAllTracks().stream().map(t -> t.getName()).toList(), args[3]);
                case "switchprimarygroup" -> filter(plugin.getGroupManager().getAllGroups().stream().map(Group::getName).toList(), args[3]);
                case "meta"               -> filter(List.of("set", "unset", "settemp", "list"), args[3]);
                default -> Collections.emptyList();
            };
        }
        if (args.length == 5 && (args[2].equalsIgnoreCase("group") || args[2].equalsIgnoreCase("parent")))
            return filter(plugin.getGroupManager().getAllGroups().stream().map(Group::getName).toList(), args[4]);
        if (args.length == 6 && (args[2].equalsIgnoreCase("permission") || args[2].equalsIgnoreCase("perm"))
                && args[3].equalsIgnoreCase("settemp"))
            return filter(List.of("1h", "1d", "7d", "30d", "1h30m"), args[5]);
        return Collections.emptyList();
    }

    private List<String> tabGroup(String[] args) {
        if (args.length == 2)
            return filter(plugin.getGroupManager().getAllGroups().stream().map(Group::getName).toList(), args[1]);
        if (args.length == 3)
            return filter(List.of("create", "delete", "permission", "perm", "parent",
                    "setprefix", "setsuffix", "clearprefix", "clearsuffix",
                    "setweight", "rename", "clone",
                    "clear", "showtracks", "meta"), args[2]);
        if (args.length == 4) {
            return switch (args[2].toLowerCase()) {
                case "permission", "perm" -> filter(List.of("set", "unset", "settemp", "list", "listall"), args[3]);
                case "parent"             -> filter(List.of("add", "remove"), args[3]);
                case "meta"               -> filter(List.of("set", "unset", "list"), args[3]);
                default -> Collections.emptyList();
            };
        }
        if (args.length == 5 && args[2].equalsIgnoreCase("parent"))
            return filter(plugin.getGroupManager().getAllGroups().stream().map(Group::getName).toList(), args[4]);
        if (args.length == 6 && (args[2].equalsIgnoreCase("permission") || args[2].equalsIgnoreCase("perm"))
                && args[3].equalsIgnoreCase("settemp"))
            return filter(List.of("1h", "1d", "7d", "30d", "1h30m"), args[5]);
        return Collections.emptyList();
    }

    private List<String> tabTrack(String[] args) {
        if (args.length == 2)
            return filter(plugin.getTrackManager().getAllTracks().stream().map(t -> t.getName()).toList(), args[1]);
        if (args.length == 3)
            return filter(List.of("create", "delete", "append", "remove", "promote", "demote"), args[2]);
        if (args.length == 4) {
            return switch (args[2].toLowerCase()) {
                case "append", "remove"  -> filter(plugin.getGroupManager().getAllGroups().stream().map(Group::getName).toList(), args[3]);
                case "promote", "demote" -> filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[3]);
                default -> Collections.emptyList();
            };
        }
        return Collections.emptyList();
    }

    private List<String> filter(List<String> list, String prefix) {
        return list.stream().filter(s -> s.toLowerCase().startsWith(prefix.toLowerCase())).toList();
    }

    // ── /pc user <player> timedgroup <add|remove|list> [group] [duration] ────

    private void handleUserTimedGroup(CommandSender sender, String[] args) {
        // args: [pc, user, <player>, timedgroup, <sub>, [group], [duration]]
        if (args.length < 5) {
            MessageUtil.send(sender, "&eUsage: &b/pc user <player> timedgroup <add|remove|list> [group] [duration]");
            return;
        }
        String playerName = args[2];
        String sub        = args[4].toLowerCase();

        java.util.UUID uuid = plugin.getStorage().findUUIDByUsername(playerName);
        if (uuid == null) {
            MessageUtil.send(sender, "&cPlayer &e" + playerName + " &cnot found in storage.");
            return;
        }

        switch (sub) {
            case "list" -> {
                var tgs = plugin.getTimedGroupManager().getTimedGroups(uuid.toString());
                if (tgs.isEmpty()) {
                    MessageUtil.send(sender, "&7" + playerName + " has no active timed group memberships.");
                    return;
                }
                MessageUtil.send(sender, "&7Timed groups for &b" + playerName + "&7:");
                tgs.forEach(tg -> MessageUtil.send(sender,
                        "  &8• &b" + tg.getGroupName()
                        + " &8| expires in &e" + tg.getFormattedExpiry()));
            }
            case "add" -> {
                if (args.length < 7) {
                    MessageUtil.send(sender, "&eUsage: &b/pc user <player> timedgroup add <group> <duration>");
                    MessageUtil.send(sender, "&7Duration: &b1s m h d w mo y &8— compound ok: &b1d10h&7, &b2w3d&7, &b1y6mo");
                    return;
                }
                String group    = args[5].toLowerCase();
                String duration = args[6];
                if (!plugin.getGroupManager().groupExists(group)) {
                    MessageUtil.send(sender, "&cGroup &e" + group + " &cdoes not exist.");
                    return;
                }
                if (!sender.hasPermission("permscraft.user.timedgroup")) {
                    MessageUtil.send(sender, "&cYou don't have permission: permscraft.user.timedgroup");
                    return;
                }
                long seconds;
                try { seconds = ir.permscraft.managers.TimedPermissionManager.parseDuration(duration); }
                catch (Exception e) {
                    MessageUtil.send(sender, "&cInvalid duration: &e" + duration);
                    return;
                }
                plugin.getTimedGroupManager().addTimedGroup(uuid.toString(), group, seconds);
                MessageUtil.send(sender, "&aAdded &b" + playerName + " &ato group &b"
                        + group + " &afor &e" + duration + "&a.");
            }
            case "remove" -> {
                if (args.length < 6) {
                    MessageUtil.send(sender, "&eUsage: &b/pc user <player> timedgroup remove <group>");
                    return;
                }
                String group = args[5].toLowerCase();
                if (!sender.hasPermission("permscraft.user.timedgroup")) {
                    MessageUtil.send(sender, "&cYou don't have permission: permscraft.user.timedgroup");
                    return;
                }
                boolean removed = plugin.getTimedGroupManager().removeTimedGroup(uuid.toString(), group);
                if (removed) MessageUtil.send(sender, "&aRemoved timed group &b" + group
                        + " &afrom &b" + playerName + "&a.");
                else         MessageUtil.send(sender, "&c" + playerName
                        + " &cdoes not have timed group &e" + group + "&c.");
            }
            default -> MessageUtil.send(sender,
                    "&eUsage: &b/pc user <player> timedgroup <add|remove|list>");
        }
    }

}