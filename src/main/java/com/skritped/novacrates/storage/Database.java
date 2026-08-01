package com.skritped.novacrates.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * SQLite or MySQL/MariaDB via HikariCP. Schema version tracked in schema_version.
 */
public final class Database implements AutoCloseable {
    private final HikariDataSource dataSource;
    private final boolean mysql;

    public Database(JavaPlugin plugin) {
        String type = plugin.getConfig().getString("database.type", "sqlite");
        this.mysql = "mysql".equalsIgnoreCase(type) || "mariadb".equalsIgnoreCase(type);

        HikariConfig config = new HikariConfig();
        if (mysql) {
            String host = plugin.getConfig().getString("database.mysql.host", "localhost");
            int port = plugin.getConfig().getInt("database.mysql.port", 3306);
            String db = plugin.getConfig().getString("database.mysql.database", "novacrates");
            String user = plugin.getConfig().getString("database.mysql.username", "root");
            String pass = plugin.getConfig().getString("database.mysql.password", "");
            boolean ssl = plugin.getConfig().getBoolean("database.mysql.use-ssl", false);
            config.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + db
                    + "?useSSL=" + ssl + "&allowPublicKeyRetrieval=true&characterEncoding=utf8");
            config.setUsername(user);
            config.setPassword(pass);
            try {
                Class.forName("com.mysql.cj.jdbc.Driver");
            } catch (ClassNotFoundException e) {
                plugin.getLogger().severe("MySQL driver missing! Put mysql-connector-j into plugins/ or change database.type to sqlite. See MYSQL.md");
                throw new IllegalStateException("MySQL driver not found", e);
            }
            config.setDriverClassName("com.mysql.cj.jdbc.Driver");
            config.setPoolName("NovaCrates-MySQL");
        } else {
            File dbFile = new File(plugin.getDataFolder(), "playerdata.db");
            plugin.getDataFolder().mkdirs();
            config.setJdbcUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
            config.setPoolName("NovaCrates-SQLite");
            config.addDataSourceProperty("journal_mode", "WAL");
            config.addDataSourceProperty("synchronous", "NORMAL");
        }
        config.setMaximumPoolSize(plugin.getConfig().getInt("database.pool-size", 10));
        config.setMinimumIdle(1);
        config.setConnectionTimeout(10_000);
        config.setIdleTimeout(60_000);
        config.setMaxLifetime(600_000);
        config.addDataSourceProperty("cachePrepStmts", "true");
        this.dataSource = new HikariDataSource(config);
        initSchema(plugin);
        plugin.getLogger().info("Database ready: type=" + (mysql ? "mysql" : "sqlite")
                + " pool=" + config.getMaximumPoolSize());
    }

    public boolean isMysql() {
        return mysql;
    }

    private String idColumn() {
        return mysql ? "BIGINT PRIMARY KEY AUTO_INCREMENT" : "INTEGER PRIMARY KEY AUTOINCREMENT";
    }

    private void initSchema(JavaPlugin plugin) {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            if (!mysql) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA synchronous=NORMAL");
                st.execute("PRAGMA foreign_keys=ON");
            }
            st.execute("""
                CREATE TABLE IF NOT EXISTS schema_version (
                    id INT PRIMARY KEY,
                    version INT NOT NULL
                )""");
            st.execute("""
                CREATE TABLE IF NOT EXISTS virtual_keys (
                    player VARCHAR(36) NOT NULL,
                    key_id VARCHAR(64) NOT NULL,
                    amount INT NOT NULL DEFAULT 0,
                    PRIMARY KEY (player, key_id)
                )""");
            st.execute("""
                CREATE TABLE IF NOT EXISTS pity (
                    player VARCHAR(36) NOT NULL,
                    crate_id VARCHAR(64) NOT NULL,
                    value INT NOT NULL DEFAULT 0,
                    PRIMARY KEY (player, crate_id)
                )""");
            st.execute("""
                CREATE TABLE IF NOT EXISTS last_open (
                    player VARCHAR(36) NOT NULL,
                    crate_id VARCHAR(64) NOT NULL,
                    ts BIGINT NOT NULL,
                    PRIMARY KEY (player, crate_id)
                )""");
            st.execute("""
                CREATE TABLE IF NOT EXISTS daily (
                    player VARCHAR(36) NOT NULL PRIMARY KEY,
                    day VARCHAR(16) NOT NULL,
                    count INT NOT NULL DEFAULT 0
                )""");
            st.execute("""
                CREATE TABLE IF NOT EXISTS daily_crate (
                    player VARCHAR(36) NOT NULL,
                    crate_id VARCHAR(64) NOT NULL,
                    day VARCHAR(16) NOT NULL,
                    count INT NOT NULL DEFAULT 0,
                    PRIMARY KEY (player, crate_id, day)
                )""");
            st.execute("""
                CREATE TABLE IF NOT EXISTS player_opens (
                    player VARCHAR(36) NOT NULL,
                    crate_id VARCHAR(64) NOT NULL,
                    count INT NOT NULL DEFAULT 0,
                    PRIMARY KEY (player, crate_id)
                )""");
            st.execute("""
                CREATE TABLE IF NOT EXISTS pending (
                    player VARCHAR(36) NOT NULL PRIMARY KEY,
                    crate_id VARCHAR(64) NOT NULL,
                    key_id VARCHAR(64) NOT NULL,
                    virtual_key INT NOT NULL,
                    cost_type VARCHAR(32),
                    cost_amount DOUBLE,
                    cost_material VARCHAR(64),
                    created_at BIGINT NOT NULL
                )""");
            st.execute("CREATE TABLE IF NOT EXISTS debt ("
                    + "id " + idColumn() + ","
                    + "player VARCHAR(36) NOT NULL,"
                    + "cost_type VARCHAR(32),"
                    + "cost_amount DOUBLE,"
                    + "cost_material VARCHAR(64),"
                    + "virtual_key INT,"
                    + "key_id VARCHAR(64),"
                    + "crate_id VARCHAR(64)"
                    + ")");
            st.execute("CREATE TABLE IF NOT EXISTS history ("
                    + "id " + idColumn() + ","
                    + "player VARCHAR(36) NOT NULL,"
                    + "crate_id VARCHAR(64) NOT NULL,"
                    + "reward_id VARCHAR(64) NOT NULL,"
                    + "reward_name TEXT,"
                    + "ts BIGINT NOT NULL"
                    + ")");
            st.execute("CREATE INDEX IF NOT EXISTS idx_history_player ON history(player, ts)");
            st.execute("""
                CREATE TABLE IF NOT EXISTS stats (
                    path VARCHAR(191) PRIMARY KEY,
                    value BIGINT NOT NULL DEFAULT 0
                )""");
            st.execute("""
                CREATE TABLE IF NOT EXISTS blocks (
                    location VARCHAR(191) PRIMARY KEY,
                    crate_id VARCHAR(64) NOT NULL
                )""");
            st.execute("CREATE TABLE IF NOT EXISTS drops_log ("
                    + "id " + idColumn() + ","
                    + "player VARCHAR(36) NOT NULL,"
                    + "player_name VARCHAR(64),"
                    + "crate_id VARCHAR(64) NOT NULL,"
                    + "reward_id VARCHAR(64) NOT NULL,"
                    + "reward_name TEXT,"
                    + "ts BIGINT NOT NULL"
                    + ")");
            st.execute("CREATE INDEX IF NOT EXISTS idx_drops_ts ON drops_log(ts)");
            st.execute("""
                CREATE TABLE IF NOT EXISTS crate_unlocks (
                    player VARCHAR(36) NOT NULL,
                    crate_id VARCHAR(64) NOT NULL,
                    unlocked_at BIGINT NOT NULL,
                    PRIMARY KEY (player, crate_id)
                )""");
            st.execute("""
                CREATE TABLE IF NOT EXISTS pass_progress (
                    player VARCHAR(36) NOT NULL,
                    track VARCHAR(64) NOT NULL,
                    points INT NOT NULL DEFAULT 0,
                    PRIMARY KEY (player, track)
                )""");
            st.execute("""
                CREATE TABLE IF NOT EXISTS pass_claimed (
                    player VARCHAR(36) NOT NULL,
                    track VARCHAR(64) NOT NULL,
                    tier INT NOT NULL,
                    PRIMARY KEY (player, track, tier)
                )""");
            st.execute("CREATE TABLE IF NOT EXISTS offline_queue ("
                    + "id " + idColumn() + ","
                    + "player VARCHAR(36) NOT NULL,"
                    + "crate_id VARCHAR(64) NOT NULL,"
                    + "queued_by VARCHAR(64),"
                    + "created_at BIGINT NOT NULL,"
                    + "forced_reward VARCHAR(64),"
                    + "consume_key INT NOT NULL DEFAULT 0"
                    + ")");
            st.execute("CREATE INDEX IF NOT EXISTS idx_offline_player ON offline_queue(player)");
            st.execute("CREATE TABLE IF NOT EXISTS gift_queue ("
                    + "id " + idColumn() + ","
                    + "player VARCHAR(36) NOT NULL,"
                    + "key_id VARCHAR(64) NOT NULL,"
                    + "amount INT NOT NULL DEFAULT 1,"
                    + "from_player VARCHAR(64),"
                    + "created_at BIGINT NOT NULL"
                    + ")");
            st.execute("CREATE INDEX IF NOT EXISTS idx_gift_player ON gift_queue(player)");
            st.execute("CREATE TABLE IF NOT EXISTS admin_audit ("
                    + "id " + idColumn() + ","
                    + "admin VARCHAR(64),"
                    + "action VARCHAR(64),"
                    + "target_player VARCHAR(36),"
                    + "detail TEXT,"
                    + "ts BIGINT NOT NULL"
                    + ")");

            // ensure consume_key column on older DBs
            try {
                st.execute("ALTER TABLE offline_queue ADD COLUMN consume_key INT NOT NULL DEFAULT 0");
            } catch (SQLException ignored) {
                // column already exists
            }

            int ver = 0;
            try (ResultSet rs = st.executeQuery("SELECT version FROM schema_version WHERE id=1")) {
                if (rs.next()) {
                    ver = rs.getInt(1);
                }
            } catch (SQLException ignored) {
            }
            if (ver < 3) {
                migratePlayerOpensFromStats(c);
                if (mysql) {
                    st.execute("INSERT INTO schema_version(id,version) VALUES(1,3) ON DUPLICATE KEY UPDATE version=3");
                } else {
                    st.execute("INSERT INTO schema_version(id,version) VALUES(1,3) ON CONFLICT(id) DO UPDATE SET version=3");
                }
                plugin.getLogger().info("Schema migrated to version 3");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to init schema", e);
        }
    }

    private void migratePlayerOpensFromStats(Connection c) {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT path, value FROM stats WHERE path LIKE 'player.%'")) {
            while (rs.next()) {
                String path = rs.getString(1);
                long val = rs.getLong(2);
                String[] parts = path.split("\\.");
                if (parts.length >= 4 && "opens".equals(parts[2])) {
                    String player = parts[1];
                    String crate = parts[3];
                    String sql = mysql
                            ? "INSERT INTO player_opens(player,crate_id,count) VALUES(?,?,?) ON DUPLICATE KEY UPDATE count=GREATEST(count,VALUES(count))"
                            : "INSERT INTO player_opens(player,crate_id,count) VALUES(?,?,?) ON CONFLICT(player,crate_id) DO UPDATE SET count=CASE WHEN excluded.count>player_opens.count THEN excluded.count ELSE player_opens.count END";
                    try (PreparedStatement ps = c.prepareStatement(sql)) {
                        ps.setString(1, player);
                        ps.setString(2, crate);
                        ps.setLong(3, val);
                        ps.executeUpdate();
                    }
                }
            }
        } catch (SQLException ignored) {
            // stats table may be empty / missing
        }
    }

    public Connection connection() throws SQLException {
        return dataSource.getConnection();
    }

    @Override
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }
}
