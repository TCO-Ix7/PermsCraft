package ir.permscraft;

import ir.permscraft.api.PermsCraftAPI;
import ir.permscraft.api.PermsCraftAPIImpl;
import ir.permscraft.context.calculators.ServerContextCalculator;
import ir.permscraft.cache.PermissionCache;
import ir.permscraft.calculator.PermissionCalculator;
import ir.permscraft.inject.BukkitDefaultsCache;

import ir.permscraft.commands.PCCommand;
import ir.permscraft.context.ContextManager;
import ir.permscraft.gui.GuiManager;
import ir.permscraft.inheritance.InheritanceGraph;
import ir.permscraft.listeners.AutoOpListener;
import ir.permscraft.listeners.OpCommandBlocker;
import ir.permscraft.listeners.PlayerLoginListener;
import ir.permscraft.logging.LogManager;
import ir.permscraft.managers.*;
import ir.permscraft.messaging.MessagingManager;
import ir.permscraft.migration.PermissionMigrator;
import ir.permscraft.migration.YamlBackup;
import ir.permscraft.redis.RedisManager;
import ir.permscraft.storage.StorageBackend;
import ir.permscraft.storage.StorageFactory;
import ir.permscraft.vault.VaultHook;
import ir.permscraft.verbose.VerboseManager;
import ir.permscraft.listeners.PluginLoadListener;
import ir.permscraft.placeholder.PermsCraftExpansion;
import ir.permscraft.FoliaScheduler;
import ir.permscraft.api.rest.RestApiServer;
import ir.permscraft.api.rest.RateLimiter;
import ir.permscraft.backup.BackupManager;
import org.bukkit.plugin.java.JavaPlugin;

public class PermsCraft extends JavaPlugin {

    private static PermsCraft instance;

    private StorageBackend storage;
    private UserManager userManager;
    private GroupManager groupManager;
    private TrackManager trackManager;
    private TimedGroupManager timedGroupManager;
    private TimedPermissionManager timedPermissionManager;
    private ContextManager contextManager;
    private LogManager logManager;
    private RedisManager redisManager;
    private MessagingManager messagingManager;
    private GuiManager guiManager;
    private PermissionCache permissionCache;
    private PermissionCalculator permissionCalculator;
    private InheritanceGraph inheritanceGraph;
    private VerboseManager verboseManager;
    private BukkitDefaultsCache defaultsCache;
    private PermissionMigrator migrator;
    private YamlBackup yamlBackup;
    private PermsCraftAPIImpl api;
    private BulkUpdateManager bulkUpdateManager;
    private AutoOpListener autoOpListener;
    private RestApiServer restApiServer;
    private BackupManager backupManager;
    private long startedAt;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        // Storage
        storage = StorageFactory.create(this);
        if (!storage.init()) {
            getLogger().severe("[PermsCraft] Could not connect to storage! Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Cache (init early — managers use it)
        permissionCache = new PermissionCache(this);

        // Core managers
        groupManager           = new GroupManager(this);
        userManager            = new UserManager(this);
        timedPermissionManager = new TimedPermissionManager(this);
        timedGroupManager     = new TimedGroupManager(this);
        contextManager         = new ContextManager(this);
        logManager             = new LogManager(this);
        trackManager           = new TrackManager(this);

        // Advanced systems
        inheritanceGraph       = new InheritanceGraph(this);
        permissionCalculator   = new PermissionCalculator(this);
        verboseManager         = new VerboseManager(this);

        // Bukkit "default: true / op / not op" cache — must be built after all
        // plugins that loaded BEFORE PermsCraft have registered their permissions.
        // PluginLoadListener rebuilds this for plugins that load AFTER PermsCraft.
        defaultsCache          = new BukkitDefaultsCache(this);

        groupManager.loadGroups();

        // Migration tools
        migrator   = new PermissionMigrator(this);
        yamlBackup = new YamlBackup(this);

        // Sync
        redisManager = new RedisManager(this);
        redisManager.init();
        messagingManager = new MessagingManager(this);
        messagingManager.init();

        // GUI
        guiManager = new GuiManager(this);
        guiManager.init();
        getServer().getPluginManager().registerEvents(guiManager, this);

        // API & Vault
        api = new PermsCraftAPIImpl(this);
        new VaultHook(this).hook();

        // PlaceholderAPI — register expansion if PAPI is present
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new PermsCraftExpansion(this).register();
            getLogger().info("[PermsCraft] PlaceholderAPI expansion registered.");
        }

        // Register plugin-load listener to keep serverKnown cache fresh (Bug #5 fix)
        getServer().getPluginManager().registerEvents(new PluginLoadListener(this), this);

        // Commands
        PCCommand pcCmd = new PCCommand(this);
        getCommand("pc").setExecutor(pcCmd);
        getCommand("pc").setTabCompleter(pcCmd);

        // Listeners
        autoOpListener = new AutoOpListener(this);
        getServer().getPluginManager().registerEvents(new PlayerLoginListener(this), this);
        getServer().getPluginManager().registerEvents(autoOpListener, this);
        getServer().getPluginManager().registerEvents(new OpCommandBlocker(this), this);

        // Bulk update manager
        bulkUpdateManager = new BulkUpdateManager(this);

        // REST API (starts last — all managers must be ready)
        backupManager = new BackupManager(this);
        restApiServer = new RestApiServer(this);
        startedAt = System.currentTimeMillis();
        FoliaScheduler.runAsync(this, () -> restApiServer.start());

        // RateLimiter stale-entry eviction (every 5 minutes)
        FoliaScheduler.runAsyncTimer(this,
                RateLimiter::evictStale, 20L * 300, 20L * 300);

        // Log cleanup
        int keepDays = getConfig().getInt("log.keep-days", 30);
        FoliaScheduler.runAsyncTimer(this,
                () -> logManager.clearOlderThan(keepDays), 20L * 3600, 20L * 86400);

        getLogger().info("[PermsCraft] Running on " + (FoliaScheduler.isFolia() ? "Folia" : "Paper/Spigot") + " scheduler.");
        getLogger().info("[PermsCraft] v" + getDescription().getVersion()
                + " enabled | storage: " + storage.getClass().getSimpleName());
    }

    @Override
    public void onDisable() {
        if (timedGroupManager      != null) timedGroupManager.shutdown();
        if (timedPermissionManager != null) timedPermissionManager.shutdown();
        if (messagingManager != null) messagingManager.shutdown();
        if (redisManager != null) redisManager.shutdown();
        if (restApiServer != null) restApiServer.stop();
        ir.permscraft.vault.VaultHook.shutdown();
        if (storage != null) storage.close();
        getLogger().info("[PermsCraft] Disabled.");
    }

    public String getServerName() { return getConfig().getString("server.name", "default"); }

    public static PermsCraft getInstance()                    { return instance; }
    public StorageBackend getStorage()                        { return storage; }
    public UserManager getUserManager()                       { return userManager; }
    public GroupManager getGroupManager()                     { return groupManager; }
    public TrackManager getTrackManager()                     { return trackManager; }
    public TimedPermissionManager getTimedPermissionManager() { return timedPermissionManager; }
    public TimedGroupManager getTimedGroupManager()           { return timedGroupManager; }
    public ContextManager getContextManager()                 { return contextManager; }
    public LogManager getLogManager()                         { return logManager; }
    public RedisManager getRedisManager()                     { return redisManager; }
    public MessagingManager getMessagingManager()             { return messagingManager; }
    public GuiManager getGuiManager()                         { return guiManager; }
    public PermissionCache getPermissionCache()               { return permissionCache; }
    public PermissionCalculator getPermissionCalculator()     { return permissionCalculator; }
    public InheritanceGraph getInheritanceGraph()             { return inheritanceGraph; }
    public VerboseManager getVerboseManager()                 { return verboseManager; }
    public BukkitDefaultsCache getDefaultsCache()             { return defaultsCache; }
    public PermissionMigrator getMigrator()                   { return migrator; }
    public YamlBackup getYamlBackup()                         { return yamlBackup; }
    public PermsCraftAPI getApi()                             { return api; }
    public PermsCraftAPIImpl getApiImpl()                     { return api; }
    public BulkUpdateManager getBulkUpdateManager()           { return bulkUpdateManager; }
    public AutoOpListener getAutoOpListener()                 { return autoOpListener; }
    public RestApiServer  getRestApiServer()                  { return restApiServer; }
    public BackupManager  getBackupManager()                  { return backupManager; }
    public long           getUptimeSeconds()                  { return (System.currentTimeMillis() - startedAt) / 1000; }
}
