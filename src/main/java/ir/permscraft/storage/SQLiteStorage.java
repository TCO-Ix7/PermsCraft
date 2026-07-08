package ir.permscraft.storage;

import com.zaxxer.hikari.HikariConfig;
import ir.permscraft.PermsCraft;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;

public class SQLiteStorage extends SqlStorage {

    private final PermsCraft plugin;

    public SQLiteStorage(PermsCraft plugin) {
        super(plugin.getLogger());
        this.plugin = plugin;
    }

    @Override
    protected HikariConfig buildHikariConfig() {
        File dbFile = new File(plugin.getDataFolder(), "permscraft.db");
        plugin.getDataFolder().mkdirs();

        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
        cfg.setMaximumPoolSize(1);       // SQLite فقط یک writer دارد
        cfg.setMinimumIdle(1);           // FIX: همیشه یک connection آماده نگه دار
        cfg.setConnectionTimeout(5000);  // FIX: 5 ثانیه به جای 30 ثانیه پیش‌فرض
        cfg.setIdleTimeout(60000);       // FIX: 1 دقیقه
        cfg.setMaxLifetime(1800000);     // FIX: 30 دقیقه
        cfg.setConnectionTestQuery("SELECT 1");
        cfg.setPoolName("PermsCraft-SQLite");
        // WAL mode برای read بهتر در حین write
        cfg.addDataSourceProperty("journal_mode", "WAL");
        cfg.addDataSourceProperty("synchronous", "NORMAL");
        // FIX: busy timeout برای SQLite تا اگر locked بود صبر کند
        cfg.addDataSourceProperty("busy_timeout", "3000");
        return cfg;
    }

    @Override
    protected String autoIncrementSyntax() {
        return "INTEGER PRIMARY KEY AUTOINCREMENT";
    }

    /** SQLite: INSERT OR IGNORE */
    @Override
    protected void insertIgnore(Connection conn, String sql, Object... params) throws SQLException {
        String sqliteSql = sql.replaceFirst("(?i)^INSERT INTO", "INSERT OR IGNORE INTO");
        exec(conn, sqliteSql, params);
    }

    /** SQLite: INSERT OR REPLACE for upserts */
    @Override
    protected String upsertGroup() {
        return "INSERT OR REPLACE INTO pc_groups (name, display_name, prefix, suffix, weight) VALUES (?,?,?,?,?)";
    }

    @Override
    protected int upsertGroupParamCount() { return 5; }

    @Override
    protected String upsertUser() {
        return "INSERT OR REPLACE INTO pc_users (uuid, username, prefix, suffix, primary_group) VALUES (?,?,?,?,?)";
    }

    @Override
    protected int upsertUserParamCount() { return 5; }

    @Override
    protected String upsertMeta() {
        return "INSERT OR REPLACE INTO pc_meta (target, is_group, meta_key, meta_value, expiry) VALUES (?,?,?,?,?)";
    }

    @Override
    protected int upsertMetaParamCount() { return 5; } // INSERT OR REPLACE — no extra params needed
}
