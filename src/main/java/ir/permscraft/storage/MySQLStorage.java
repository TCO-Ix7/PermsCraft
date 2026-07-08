package ir.permscraft.storage;

import com.zaxxer.hikari.HikariConfig;
import ir.permscraft.PermsCraft;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.logging.Logger;

public class MySQLStorage extends SqlStorage {

    private final PermsCraft plugin;

    public MySQLStorage(PermsCraft plugin) {
        super(plugin.getLogger());
        this.plugin = plugin;
    }

    @Override
    protected HikariConfig buildHikariConfig() {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:mysql://"
                + plugin.getConfig().getString("storage.mysql.host", "localhost") + ":"
                + plugin.getConfig().getInt("storage.mysql.port", 3306) + "/"
                + plugin.getConfig().getString("storage.mysql.database", "permscraft")
                + "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8"
                + "&autoReconnect=true&serverTimezone=UTC");
        cfg.setUsername(plugin.getConfig().getString("storage.mysql.username", "root"));
        cfg.setPassword(plugin.getConfig().getString("storage.mysql.password", ""));
        cfg.setMaximumPoolSize(plugin.getConfig().getInt("storage.mysql.pool-size", 10));
        cfg.setMinimumIdle(2);
        cfg.setConnectionTimeout(30_000);
        cfg.setIdleTimeout(600_000);
        cfg.setMaxLifetime(1_800_000);
        cfg.setPoolName("PermsCraft-MySQL");
        return cfg;
    }

    @Override
    protected String autoIncrementSyntax() {
        return "INT AUTO_INCREMENT PRIMARY KEY";
    }

    /** MySQL: INSERT IGNORE handles duplicates silently. */
    @Override
    protected void insertIgnore(Connection conn, String sql, Object... params) throws SQLException {
        // Replace "INSERT INTO" with "INSERT IGNORE INTO"
        String ignoreSql = sql.replaceFirst("(?i)^INSERT INTO", "INSERT IGNORE INTO");
        exec(conn, ignoreSql, params);
    }
}
