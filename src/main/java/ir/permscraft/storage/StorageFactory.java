package ir.permscraft.storage;

import ir.permscraft.PermsCraft;

public class StorageFactory {

    public static StorageBackend create(PermsCraft plugin) {
        String type = plugin.getConfig().getString("storage.type", "sqlite").toLowerCase();
        return switch (type) {
            case "mysql"      -> new MySQLStorage(plugin);
            case "mariadb"    -> new MariaDBStorage(plugin);
            case "postgresql",
                 "postgres"   -> new PostgreSQLStorage(plugin);
            case "h2"         -> new H2Storage(plugin);
            case "yaml",
                 "yml"         -> new YamlStorage(plugin);
            case "mongodb"    -> createMongoDB(plugin);
            default           -> {
                if (!type.equals("sqlite")) {
                    plugin.getLogger().warning("[PermsCraft] Unknown storage type '" + type
                            + "', falling back to SQLite.");
                }
                yield new SQLiteStorage(plugin);
            }
        };
    }

    /**
     * FIX (Bug #MongoDB-driver): MongoDB driver is intentionally excluded from
     * plugin.yml's libraries section (it cannot be auto-downloaded there) and
     * must be shaded into the jar or placed in /plugins/. When the class is
     * missing the JVM throws NoClassDefFoundError at the call-site, which
     * previously crashed the plugin with an unhelpful NullPointerException in
     * onEnable before any useful message was logged.
     *
     * Now we catch the error, print a clear action-oriented message, and fall
     * back to SQLite so the plugin still starts and operators can fix the driver.
     */
    private static StorageBackend createMongoDB(PermsCraft plugin) {
        try {
            return new MongoDBStorage(plugin);
        } catch (NoClassDefFoundError | ExceptionInInitializerError e) {
            plugin.getLogger().severe(
                "[PermsCraft] MongoDB driver not found! The MongoDB Java driver is not bundled " +
                "with PermsCraft because it cannot be auto-downloaded via plugin.yml. " +
                "Please either: (a) shade the driver into the PermsCraft jar when building from source, " +
                "or (b) place the mongodb-driver-sync-*.jar inside your server's /plugins/ folder. " +
                "Falling back to SQLite for this session. Fix the driver and restart to use MongoDB.");
            return new SQLiteStorage(plugin);
        }
    }
}
