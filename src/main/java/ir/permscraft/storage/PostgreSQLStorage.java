package ir.permscraft.storage;

import com.zaxxer.hikari.HikariConfig;
import ir.permscraft.PermsCraft;

import java.sql.Connection;
import java.sql.SQLException;

public class PostgreSQLStorage extends SqlStorage {

    private final PermsCraft plugin;

    public PostgreSQLStorage(PermsCraft plugin) {
        super(plugin.getLogger());
        this.plugin = plugin;
    }

    @Override
    protected HikariConfig buildHikariConfig() {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:postgresql://"
                + plugin.getConfig().getString("storage.postgresql.host", "localhost") + ":"
                + plugin.getConfig().getInt("storage.postgresql.port", 5432) + "/"
                + plugin.getConfig().getString("storage.postgresql.database", "permscraft"));
        cfg.setUsername(plugin.getConfig().getString("storage.postgresql.username", "postgres"));
        cfg.setPassword(plugin.getConfig().getString("storage.postgresql.password", ""));
        cfg.setMaximumPoolSize(plugin.getConfig().getInt("storage.postgresql.pool-size", 10));
        cfg.setMinimumIdle(2);
        cfg.setConnectionTimeout(30_000);
        cfg.setPoolName("PermsCraft-PostgreSQL");
        return cfg;
    }

    @Override
    protected String autoIncrementSyntax() {
        return "SERIAL PRIMARY KEY";
    }

    /** PostgreSQL: ON CONFLICT DO NOTHING */
    @Override
    protected void insertIgnore(Connection conn, String sql, Object... params) throws SQLException {
        String pgSql = sql + " ON CONFLICT DO NOTHING";
        exec(conn, pgSql, params);
    }

    /** PostgreSQL: ON CONFLICT ... DO UPDATE */
    @Override
    protected String upsertGroup() {
        return "INSERT INTO pc_groups (name, display_name, prefix, suffix, weight) VALUES (?,?,?,?,?) " +
               "ON CONFLICT (name) DO UPDATE SET display_name=EXCLUDED.display_name, " +
               "prefix=EXCLUDED.prefix, suffix=EXCLUDED.suffix, weight=EXCLUDED.weight";
    }

    @Override
    protected int upsertGroupParamCount() { return 5; }

    @Override
    protected String upsertUser() {
        return "INSERT INTO pc_users (uuid, username, prefix, suffix) VALUES (?,?,?,?) " +
               "ON CONFLICT (uuid) DO UPDATE SET username=EXCLUDED.username, " +
               "prefix=EXCLUDED.prefix, suffix=EXCLUDED.suffix";
    }

    @Override
    protected int upsertUserParamCount() { return 4; }

    @Override
    protected String upsertMeta() {
        return "INSERT INTO pc_meta (target, is_group, meta_key, meta_value, expiry) VALUES (?,?,?,?,?) " +
               "ON CONFLICT (target, is_group, meta_key) DO UPDATE SET meta_value=EXCLUDED.meta_value, expiry=EXCLUDED.expiry";
    }

    @Override
    protected int upsertMetaParamCount() { return 5; } // PostgreSQL uses EXCLUDED — no extra params needed
}
