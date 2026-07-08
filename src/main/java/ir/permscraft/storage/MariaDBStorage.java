package ir.permscraft.storage;

import com.zaxxer.hikari.HikariConfig;
import ir.permscraft.PermsCraft;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * MariaDB storage backend.
 *
 * MariaDB is wire-protocol compatible with MySQL but uses its own JDBC
 * driver (org.mariadb.jdbc.Driver). For the SQL subset PermsCraft uses
 * (INSERT IGNORE, ON DUPLICATE KEY UPDATE, AUTO_INCREMENT), MariaDB's
 * dialect is identical to MySQL's, so this class simply re-uses every
 * shared method from SqlStorage with a MariaDB-specific connection pool.
 */
public class MariaDBStorage extends SqlStorage {

    private final PermsCraft plugin;

    public MariaDBStorage(PermsCraft plugin) {
        super(plugin.getLogger());
        this.plugin = plugin;
    }

    @Override
    protected HikariConfig buildHikariConfig() {
        HikariConfig cfg = new HikariConfig();

        String host     = plugin.getConfig().getString("storage.mariadb.host", "localhost");
        int    port     = plugin.getConfig().getInt("storage.mariadb.port", 3306);
        String database = plugin.getConfig().getString("storage.mariadb.database", "permscraft");
        boolean useSsl  = plugin.getConfig().getBoolean("storage.mariadb.use-ssl", false);

        cfg.setJdbcUrl("jdbc:mariadb://" + host + ":" + port + "/" + database
                + "?useSSL=" + useSsl
                + "&allowPublicKeyRetrieval=true"
                + "&characterEncoding=utf8mb4"
                + "&connectionCollation=utf8mb4_unicode_ci"
                + "&autoReconnect=true"
                + "&serverTimezone=UTC");

        // Driver class intentionally not set explicitly: like MySQLStorage, we rely
        // on JDBC's ServiceLoader-based driver discovery (META-INF/services), which
        // the shade plugin's ServicesResourceTransformer merges correctly even after
        // relocating org.mariadb.jdbc -> ir.permscraft.libs.mariadb.
        cfg.setUsername(plugin.getConfig().getString("storage.mariadb.username", "root"));
        cfg.setPassword(plugin.getConfig().getString("storage.mariadb.password", ""));
        cfg.setMaximumPoolSize(plugin.getConfig().getInt("storage.mariadb.pool-size", 10));
        cfg.setMinimumIdle(2);
        cfg.setConnectionTimeout(30_000);
        cfg.setIdleTimeout(600_000);
        cfg.setMaxLifetime(1_800_000);
        cfg.setPoolName("PermsCraft-MariaDB");
        cfg.setConnectionTestQuery("SELECT 1");

        return cfg;
    }

    @Override
    protected String autoIncrementSyntax() {
        return "INT AUTO_INCREMENT PRIMARY KEY";
    }

    /** MariaDB (MySQL-compatible dialect): INSERT IGNORE handles duplicates silently. */
    @Override
    protected void insertIgnore(Connection conn, String sql, Object... params) throws SQLException {
        String ignoreSql = sql.replaceFirst("(?i)^INSERT INTO", "INSERT IGNORE INTO");
        exec(conn, ignoreSql, params);
    }

    // upsertGroup/upsertUser/upsertMeta use the default MySQL-style
    // "ON DUPLICATE KEY UPDATE" implementations from SqlStorage, which
    // MariaDB also supports natively — no overrides needed.
}
