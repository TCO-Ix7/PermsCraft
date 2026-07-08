package ir.permscraft.storage;

import com.zaxxer.hikari.HikariConfig;
import ir.permscraft.PermsCraft;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;

public class H2Storage extends SqlStorage {

    private final PermsCraft plugin;

    public H2Storage(PermsCraft plugin) {
        super(plugin.getLogger());
        this.plugin = plugin;
    }

    @Override
    protected HikariConfig buildHikariConfig() {
        File dbFile = new File(plugin.getDataFolder(), "permscraft-h2");
        plugin.getDataFolder().mkdirs();

        HikariConfig cfg = new HikariConfig();
        // FILE mode — persists between restarts; append ;AUTO_SERVER=TRUE for multi-process
        cfg.setJdbcUrl("jdbc:h2:file:" + dbFile.getAbsolutePath()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE");
        cfg.setMaximumPoolSize(10);
        cfg.setPoolName("PermsCraft-H2");
        return cfg;
    }

    @Override
    protected String autoIncrementSyntax() {
        return "BIGINT AUTO_INCREMENT PRIMARY KEY";
    }

    /** H2 (MySQL mode): MERGE INTO / INSERT IGNORE works with MySQL compat mode */
    @Override
    protected void insertIgnore(Connection conn, String sql, Object... params) throws SQLException {
        // H2 in MySQL compat mode supports INSERT IGNORE
        String h2Sql = sql.replaceFirst("(?i)^INSERT INTO", "INSERT IGNORE INTO");
        exec(conn, h2Sql, params);
    }
}
