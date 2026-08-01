package com.skritped.novacrates.storage;

import com.skritped.novacrates.model.CostDebt;
import com.skritped.novacrates.model.DropRecord;
import com.skritped.novacrates.model.OfflineOpen;
import com.skritped.novacrates.model.PendingOpen;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * High-scale player data: SQLite + in-memory cache + async dirty flush.
 * Same public API as the old YAML repository so callers stay unchanged.
 */
public class PlayerDataRepository {
    private final JavaPlugin plugin;
    private final Database database;
    private int historySize = 20;

    private final ConcurrentHashMap<String, Integer> keyCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> pityCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastOpenCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, int[]> dailyCache = new ConcurrentHashMap<>(); // [dayHash, count] via string day
    private final ConcurrentHashMap<UUID, String> dailyDay = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> dailyCount = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> statsCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> blockCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> opensCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> passPointsCache = new ConcurrentHashMap<>();

    private final ConcurrentHashMap.KeySetView<String, Boolean> dirtyKeys = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap.KeySetView<String, Boolean> dirtyPity = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap.KeySetView<String, Boolean> dirtyLastOpen = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap.KeySetView<UUID, Boolean> dirtyDaily = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap.KeySetView<String, Boolean> dirtyStats = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap.KeySetView<String, Boolean> dirtyBlocks = ConcurrentHashMap.newKeySet();

    private BukkitTask flushTask;

    public PlayerDataRepository(JavaPlugin plugin, Database database) {
        this.plugin = plugin;
        this.database = database;
        loadBlocksIntoCache();
        loadStatsIntoCache();
        migrateYamlIfPresent();
    }

    public void setHistorySize(int historySize) {
        this.historySize = Math.max(1, historySize);
    }

    public void startAutosave(int intervalTicks) {
        stopAutosave();
        flushTask = plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, this::flushDirty, intervalTicks, intervalTicks);
    }

    public void stopAutosave() {
        if (flushTask != null) {
            flushTask.cancel();
            flushTask = null;
        }
        flushDirty();
    }

    public void flushDirty() {
        long t0 = System.nanoTime();
        flushKeys();
        flushPity();
        flushLastOpen();
        flushDaily();
        flushStats();
        flushBlocks();
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        if (ms > 50 || plugin.getConfig().getBoolean("settings.debug", false)) {
            plugin.getLogger().info("PlayerData flush took " + ms + "ms");
        }
        // Adaptive: if slow, could increase interval — logged for ops
    }

    private static String k(UUID player, String id) {
        return player + ":" + id.toLowerCase();
    }

    // --- keys ---
    public int getVirtualKeys(UUID playerId, String keyId) {
        String key = k(playerId, keyId);
        Integer cached = keyCache.get(key);
        if (cached != null) {
            return cached;
        }
        try (Connection c = database.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT amount FROM virtual_keys WHERE player=? AND key_id=?")) {
            ps.setString(1, playerId.toString());
            ps.setString(2, keyId.toLowerCase());
            try (ResultSet rs = ps.executeQuery()) {
                int amount = rs.next() ? rs.getInt(1) : 0;
                keyCache.put(key, amount);
                return amount;
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "getVirtualKeys failed", e);
            return 0;
        }
    }

    public void setVirtualKeys(UUID playerId, String keyId, int amount) {
        String key = k(playerId, keyId);
        keyCache.put(key, Math.max(0, amount));
        dirtyKeys.add(key);
    }

    private void flushKeys() {
        if (dirtyKeys.isEmpty()) {
            return;
        }
        List<String> batch = new ArrayList<>(dirtyKeys);
        dirtyKeys.removeAll(batch);
        try (Connection c = database.connection();
             PreparedStatement ups = c.prepareStatement(
                     database.isMysql()
                             ? "INSERT INTO virtual_keys(player,key_id,amount) VALUES(?,?,?) ON DUPLICATE KEY UPDATE amount=VALUES(amount)"
                             : "INSERT INTO virtual_keys(player,key_id,amount) VALUES(?,?,?) ON CONFLICT(player,key_id) DO UPDATE SET amount=excluded.amount");
             PreparedStatement del = c.prepareStatement(
                     "DELETE FROM virtual_keys WHERE player=? AND key_id=?")) {
            for (String key : batch) {
                String[] parts = key.split(":", 2);
                int amount = keyCache.getOrDefault(key, 0);
                if (amount <= 0) {
                    del.setString(1, parts[0]);
                    del.setString(2, parts[1]);
                    del.addBatch();
                } else {
                    ups.setString(1, parts[0]);
                    ups.setString(2, parts[1]);
                    ups.setInt(3, amount);
                    ups.addBatch();
                }
            }
            ups.executeBatch();
            del.executeBatch();
        } catch (SQLException e) {
            dirtyKeys.addAll(batch);
            plugin.getLogger().log(Level.SEVERE, "flushKeys failed", e);
        }
    }

    // --- pity ---
    public int getPity(UUID playerId, String crateId) {
        String key = k(playerId, crateId);
        Integer cached = pityCache.get(key);
        if (cached != null) {
            return cached;
        }
        try (Connection c = database.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT value FROM pity WHERE player=? AND crate_id=?")) {
            ps.setString(1, playerId.toString());
            ps.setString(2, crateId.toLowerCase());
            try (ResultSet rs = ps.executeQuery()) {
                int v = rs.next() ? rs.getInt(1) : 0;
                pityCache.put(key, v);
                return v;
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "getPity failed", e);
            return 0;
        }
    }

    public void setPity(UUID playerId, String crateId, int value) {
        String key = k(playerId, crateId);
        pityCache.put(key, Math.max(0, value));
        dirtyPity.add(key);
    }

    private void flushPity() {
        if (dirtyPity.isEmpty()) {
            return;
        }
        List<String> batch = new ArrayList<>(dirtyPity);
        dirtyPity.removeAll(batch);
        try (Connection c = database.connection();
             PreparedStatement ps = c.prepareStatement(
                     database.isMysql()
                             ? "INSERT INTO pity(player,crate_id,value) VALUES(?,?,?) ON DUPLICATE KEY UPDATE value=VALUES(value)"
                             : "INSERT INTO pity(player,crate_id,value) VALUES(?,?,?) ON CONFLICT(player,crate_id) DO UPDATE SET value=excluded.value")) {
            for (String key : batch) {
                String[] parts = key.split(":", 2);
                ps.setString(1, parts[0]);
                ps.setString(2, parts[1]);
                ps.setInt(3, pityCache.getOrDefault(key, 0));
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            dirtyPity.addAll(batch);
            plugin.getLogger().log(Level.SEVERE, "flushPity failed", e);
        }
    }

    // --- last open ---
    public long getLastOpen(UUID playerId, String crateId) {
        String key = k(playerId, crateId);
        Long cached = lastOpenCache.get(key);
        if (cached != null) {
            return cached;
        }
        try (Connection c = database.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT ts FROM last_open WHERE player=? AND crate_id=?")) {
            ps.setString(1, playerId.toString());
            ps.setString(2, crateId.toLowerCase());
            try (ResultSet rs = ps.executeQuery()) {
                long ts = rs.next() ? rs.getLong(1) : 0L;
                lastOpenCache.put(key, ts);
                return ts;
            }
        } catch (SQLException e) {
            return 0L;
        }
    }

    public void setLastOpen(UUID playerId, String crateId, long ts) {
        String key = k(playerId, crateId);
        lastOpenCache.put(key, ts);
        dirtyLastOpen.add(key);
    }

    private void flushLastOpen() {
        if (dirtyLastOpen.isEmpty()) {
            return;
        }
        List<String> batch = new ArrayList<>(dirtyLastOpen);
        dirtyLastOpen.removeAll(batch);
        try (Connection c = database.connection();
             PreparedStatement ps = c.prepareStatement(
                     database.isMysql()
                             ? "INSERT INTO last_open(player,crate_id,ts) VALUES(?,?,?) ON DUPLICATE KEY UPDATE ts=VALUES(ts)"
                             : "INSERT INTO last_open(player,crate_id,ts) VALUES(?,?,?) ON CONFLICT(player,crate_id) DO UPDATE SET ts=excluded.ts")) {
            for (String key : batch) {
                String[] parts = key.split(":", 2);
                ps.setString(1, parts[0]);
                ps.setString(2, parts[1]);
                ps.setLong(3, lastOpenCache.getOrDefault(key, 0L));
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            dirtyLastOpen.addAll(batch);
            plugin.getLogger().log(Level.SEVERE, "flushLastOpen failed", e);
        }
    }

    // --- daily ---
    public int getDailyOpens(UUID playerId) {
        String today = LocalDate.now().toString();
        String day = dailyDay.get(playerId);
        if (today.equals(day)) {
            return dailyCount.getOrDefault(playerId, 0);
        }
        try (Connection c = database.connection();
             PreparedStatement ps = c.prepareStatement("SELECT day, count FROM daily WHERE player=?")) {
            ps.setString(1, playerId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String d = rs.getString(1);
                    int count = rs.getInt(2);
                    if (today.equals(d)) {
                        dailyDay.put(playerId, d);
                        dailyCount.put(playerId, count);
                        return count;
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "getDailyOpens failed", e);
        }
        dailyDay.put(playerId, today);
        dailyCount.put(playerId, 0);
        return 0;
    }

    public void incrementDailyOpens(UUID playerId) {
        String today = LocalDate.now().toString();
        int current = getDailyOpens(playerId);
        if (!today.equals(dailyDay.get(playerId))) {
            current = 0;
            dailyDay.put(playerId, today);
        }
        dailyCount.put(playerId, current + 1);
        dirtyDaily.add(playerId);
    }

    private void flushDaily() {
        if (dirtyDaily.isEmpty()) {
            return;
        }
        List<UUID> batch = new ArrayList<>(dirtyDaily);
        dirtyDaily.removeAll(batch);
        try (Connection c = database.connection();
             PreparedStatement ps = c.prepareStatement(
                     database.isMysql()
                             ? "INSERT INTO daily(player,day,count) VALUES(?,?,?) ON DUPLICATE KEY UPDATE day=VALUES(day), count=VALUES(count)"
                             : "INSERT INTO daily(player,day,count) VALUES(?,?,?) ON CONFLICT(player) DO UPDATE SET day=excluded.day, count=excluded.count")) {
            for (UUID id : batch) {
                ps.setString(1, id.toString());
                ps.setString(2, dailyDay.getOrDefault(id, LocalDate.now().toString()));
                ps.setInt(3, dailyCount.getOrDefault(id, 0));
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            dirtyDaily.addAll(batch);
            plugin.getLogger().log(Level.SEVERE, "flushDaily failed", e);
        }
    }

    // --- pending ---
    public void setPending(PendingOpen pending) {
        try (Connection c = database.connection();
             PreparedStatement ps = c.prepareStatement(
                     database.isMysql()
                             ? "INSERT INTO pending(player,crate_id,key_id,virtual_key,cost_type,cost_amount,cost_material,created_at) "
                               + "VALUES(?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE "
                               + "crate_id=VALUES(crate_id), key_id=VALUES(key_id), virtual_key=VALUES(virtual_key), "
                               + "cost_type=VALUES(cost_type), cost_amount=VALUES(cost_amount), "
                               + "cost_material=VALUES(cost_material), created_at=VALUES(created_at)"
                             : "INSERT INTO pending(player,crate_id,key_id,virtual_key,cost_type,cost_amount,cost_material,created_at) "
                               + "VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(player) DO UPDATE SET "
                               + "crate_id=excluded.crate_id, key_id=excluded.key_id, virtual_key=excluded.virtual_key, "
                               + "cost_type=excluded.cost_type, cost_amount=excluded.cost_amount, "
                               + "cost_material=excluded.cost_material, created_at=excluded.created_at")) {
            ps.setString(1, pending.getPlayerId().toString());
            ps.setString(2, pending.getCrateId());
            ps.setString(3, pending.getKeyId());
            ps.setInt(4, pending.isVirtualKey() ? 1 : 0);
            ps.setString(5, pending.getCostType());
            ps.setDouble(6, pending.getCostAmount());
            ps.setString(7, pending.getCostMaterial());
            ps.setLong(8, pending.getCreatedAtMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "setPending failed", e);
        }
    }

    public void removePending(UUID playerId) {
        try (Connection c = database.connection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM pending WHERE player=?")) {
            ps.setString(1, playerId.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "removePending failed", e);
        }
    }

    public List<PendingOpen> getPending() {
        List<PendingOpen> list = new ArrayList<>();
        try (Connection c = database.connection();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM pending");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(new PendingOpen(
                        UUID.fromString(rs.getString("player")),
                        rs.getString("crate_id"),
                        rs.getString("key_id"),
                        rs.getInt("virtual_key") == 1,
                        rs.getString("cost_type"),
                        rs.getDouble("cost_amount"),
                        rs.getString("cost_material"),
                        rs.getLong("created_at")
                ));
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "getPending failed", e);
        }
        return list;
    }

    // --- debt ---
    public void addDebt(CostDebt debt) {
        try (Connection c = database.connection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO debt(player,cost_type,cost_amount,cost_material,virtual_key,key_id,crate_id) "
                             + "VALUES(?,?,?,?,?,?,?)")) {
            ps.setString(1, debt.getPlayerId().toString());
            ps.setString(2, debt.getCostType());
            ps.setDouble(3, debt.getCostAmount());
            ps.setString(4, debt.getCostMaterial());
            ps.setInt(5, debt.isVirtualKey() ? 1 : 0);
            ps.setString(6, debt.getKeyId());
            ps.setString(7, debt.getCrateId());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "addDebt failed", e);
        }
    }

    public List<CostDebt> takeDebts(UUID playerId) {
        List<CostDebt> list = new ArrayList<>();
        try (Connection c = database.connection()) {
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM debt WHERE player=?")) {
                ps.setString(1, playerId.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(new CostDebt(
                                playerId,
                                rs.getString("cost_type"),
                                rs.getDouble("cost_amount"),
                                rs.getString("cost_material"),
                                rs.getInt("virtual_key") == 1,
                                rs.getString("key_id"),
                                rs.getString("crate_id")
                        ));
                    }
                }
            }
            try (PreparedStatement del = c.prepareStatement("DELETE FROM debt WHERE player=?")) {
                del.setString(1, playerId.toString());
                del.executeUpdate();
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "takeDebts failed", e);
        }
        return list;
    }

    // --- history ---

    public void addHistoryBatch(UUID playerId, List<DropRecord> records) {
        if (records == null || records.isEmpty()) return;
        try (Connection c = database.connection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO history(player,crate_id,reward_id,reward_name,ts) VALUES(?,?,?,?,?)")) {
            c.setAutoCommit(false);
            for (DropRecord record : records) {
                ps.setString(1, playerId.toString());
                ps.setString(2, record.getCrateId());
                ps.setString(3, record.getRewardId());
                ps.setString(4, record.getRewardName());
                ps.setLong(5, record.getTimeMillis());
                ps.addBatch();
            }
            ps.executeBatch();
            c.commit();
            c.setAutoCommit(true);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "addHistoryBatch failed", e);
        }
        // trim once
        try (Connection c = database.connection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM history WHERE player=? AND id NOT IN ("
                             + "SELECT id FROM history WHERE player=? ORDER BY ts DESC LIMIT ?)")) {
            ps.setString(1, playerId.toString());
            ps.setString(2, playerId.toString());
            ps.setInt(3, historySize);
            ps.executeUpdate();
        } catch (SQLException ignored) {
        }
    }

    public void addHistory(UUID playerId, DropRecord record) {
        try (Connection c = database.connection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO history(player,crate_id,reward_id,reward_name,ts) VALUES(?,?,?,?,?)")) {
            ps.setString(1, playerId.toString());
            ps.setString(2, record.getCrateId());
            ps.setString(3, record.getRewardId());
            ps.setString(4, record.getRewardName());
            ps.setLong(5, record.getTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "addHistory failed", e);
        }
        // trim
        try (Connection c = database.connection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM history WHERE player=? AND id NOT IN ("
                             + "SELECT id FROM history WHERE player=? ORDER BY ts DESC LIMIT ?)")) {
            ps.setString(1, playerId.toString());
            ps.setString(2, playerId.toString());
            ps.setInt(3, historySize);
            ps.executeUpdate();
        } catch (SQLException ignored) {
        }
    }

    public List<DropRecord> getHistory(UUID playerId) {
        List<DropRecord> list = new ArrayList<>();
        try (Connection c = database.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT crate_id,reward_id,reward_name,ts FROM history WHERE player=? ORDER BY ts DESC LIMIT ?")) {
            ps.setString(1, playerId.toString());
            ps.setInt(2, historySize);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new DropRecord(
                            rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4)));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "getHistory failed", e);
        }
        return list;
    }

    public void logDrop(UUID playerId, String playerName, String crateId, String rewardId, String rewardName) {
        try (Connection c = database.connection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO drops_log(player,player_name,crate_id,reward_id,reward_name,ts) VALUES(?,?,?,?,?,?)")) {
            ps.setString(1, playerId.toString());
            ps.setString(2, playerName);
            ps.setString(3, crateId);
            ps.setString(4, rewardId);
            ps.setString(5, rewardName);
            ps.setLong(6, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "logDrop failed", e);
        }
    }

    // --- stats ---
    public long getStat(String path) {
        Long cached = statsCache.get(path);
        if (cached != null) {
            return cached;
        }
        try (Connection c = database.connection();
             PreparedStatement ps = c.prepareStatement("SELECT value FROM stats WHERE path=?")) {
            ps.setString(1, path);
            try (ResultSet rs = ps.executeQuery()) {
                long v = rs.next() ? rs.getLong(1) : 0L;
                statsCache.put(path, v);
                return v;
            }
        } catch (SQLException e) {
            return 0L;
        }
    }

    public void incrementStat(String path) {
        long next = getStat(path) + 1;
        statsCache.put(path, next);
        dirtyStats.add(path);
    }

    private void flushStats() {
        if (dirtyStats.isEmpty()) {
            return;
        }
        List<String> batch = new ArrayList<>(dirtyStats);
        dirtyStats.removeAll(batch);
        try (Connection c = database.connection();
             PreparedStatement ps = c.prepareStatement(
                     database.isMysql()
                             ? "INSERT INTO stats(path,value) VALUES(?,?) ON DUPLICATE KEY UPDATE value=VALUES(value)"
                             : "INSERT INTO stats(path,value) VALUES(?,?) ON CONFLICT(path) DO UPDATE SET value=excluded.value")) {
            for (String path : batch) {
                ps.setString(1, path);
                ps.setLong(2, statsCache.getOrDefault(path, 0L));
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            dirtyStats.addAll(batch);
            plugin.getLogger().log(Level.SEVERE, "flushStats failed", e);
        }
    }

    private void loadStatsIntoCache() {
        try (Connection c = database.connection();
             PreparedStatement ps = c.prepareStatement("SELECT path,value FROM stats");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                statsCache.put(rs.getString(1), rs.getLong(2));
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "loadStats failed", e);
        }
    }

    // --- blocks ---
    public void setBlock(String locationKey, String crateId) {
        blockCache.put(locationKey, crateId);
        dirtyBlocks.add(locationKey);
    }

    public void removeBlock(String locationKey) {
        blockCache.remove(locationKey);
        dirtyBlocks.add(locationKey);
        try (Connection c = database.connection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM blocks WHERE location=?")) {
            ps.setString(1, locationKey);
            ps.executeUpdate();
            dirtyBlocks.remove(locationKey);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "removeBlock failed", e);
        }
    }

    public String getBlockCrate(String locationKey) {
        return blockCache.get(locationKey);
    }

    public Map<String, String> getBlocks() {
        return new HashMap<>(blockCache);
    }

    private void flushBlocks() {
        if (dirtyBlocks.isEmpty()) {
            return;
        }
        List<String> batch = new ArrayList<>(dirtyBlocks);
        dirtyBlocks.removeAll(batch);
        try (Connection c = database.connection();
             PreparedStatement ups = c.prepareStatement(
                     database.isMysql()
                             ? "INSERT INTO blocks(location,crate_id) VALUES(?,?) ON DUPLICATE KEY UPDATE crate_id=VALUES(crate_id)"
                             : "INSERT INTO blocks(location,crate_id) VALUES(?,?) ON CONFLICT(location) DO UPDATE SET crate_id=excluded.crate_id");
             PreparedStatement del = c.prepareStatement("DELETE FROM blocks WHERE location=?")) {
            for (String loc : batch) {
                String crate = blockCache.get(loc);
                if (crate == null) {
                    del.setString(1, loc);
                    del.addBatch();
                } else {
                    ups.setString(1, loc);
                    ups.setString(2, crate);
                    ups.addBatch();
                }
            }
            ups.executeBatch();
            del.executeBatch();
        } catch (SQLException e) {
            dirtyBlocks.addAll(batch);
            plugin.getLogger().log(Level.SEVERE, "flushBlocks failed", e);
        }
    }

    private void loadBlocksIntoCache() {
        try (Connection c = database.connection();
             PreparedStatement ps = c.prepareStatement("SELECT location,crate_id FROM blocks");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                blockCache.put(rs.getString(1), rs.getString(2));
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "loadBlocks failed", e);
        }
    }

    public List<String> getPityLeaderboard(String crateId, int limit) {
        List<String> lines = new ArrayList<>();
        try (Connection c = database.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT player, value FROM pity WHERE crate_id=? AND value>0 ORDER BY value DESC LIMIT ?")) {
            ps.setString(1, crateId.toLowerCase());
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    lines.add(rs.getString(1) + ":" + rs.getInt(2));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "pity leaderboard failed", e);
        }
        return lines;
    }


    public java.util.Map<String, Integer> getAllVirtualKeys(UUID playerId) {
        java.util.Map<String, Integer> map = new java.util.LinkedHashMap<>();
        String prefix = playerId.toString() + ":";
        for (var e : keyCache.entrySet()) {
            if (e.getKey().startsWith(prefix) && e.getValue() > 0) {
                map.put(e.getKey().substring(prefix.length()), e.getValue());
            }
        }
        try (Connection c = database.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT key_id, amount FROM virtual_keys WHERE player=? AND amount>0")) {
            ps.setString(1, playerId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    map.putIfAbsent(rs.getString(1), rs.getInt(2));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "getAllVirtualKeys failed", e);
        }
        return map;
    }

    /** One-time migration from legacy data.yml */
    private void migrateYamlIfPresent() {
        File yamlFile = new File(plugin.getDataFolder(), "data.yml");
        if (!yamlFile.exists()) {
            return;
        }
        File marker = new File(plugin.getDataFolder(), "data.yml.migrated");
        if (marker.exists()) {
            return;
        }
        plugin.getLogger().info("Migrating data.yml → SQLite…");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(yamlFile);
        try (Connection c = database.connection()) {
            c.setAutoCommit(false);
            migrateSectionKeys(c, yaml.getConfigurationSection("keys"));
            migrateSectionPity(c, yaml.getConfigurationSection("pity"));
            migrateSectionLastOpen(c, yaml.getConfigurationSection("last-open"));
            migrateBlocks(c, yaml.getConfigurationSection("blocks"));
            migrateStats(c, yaml.getConfigurationSection("stats"));
            c.commit();
            if (!marker.createNewFile()) {
                plugin.getLogger().warning("Could not write migration marker");
            }
            plugin.getLogger().info("YAML → SQLite migration complete.");
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Migration failed — YAML kept as backup", e);
        }
    }

    private void migrateSectionKeys(Connection c, ConfigurationSection section) throws SQLException {
        if (section == null) {
            return;
        }
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT OR REPLACE INTO virtual_keys(player,key_id,amount) VALUES(?,?,?)")) {
            for (String player : section.getKeys(false)) {
                ConfigurationSection keys = section.getConfigurationSection(player);
                if (keys == null) {
                    continue;
                }
                for (String keyId : keys.getKeys(false)) {
                    ps.setString(1, player);
                    ps.setString(2, keyId.toLowerCase());
                    ps.setInt(3, keys.getInt(keyId));
                    ps.addBatch();
                }
            }
            ps.executeBatch();
        }
    }

    private void migrateSectionPity(Connection c, ConfigurationSection section) throws SQLException {
        if (section == null) {
            return;
        }
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT OR REPLACE INTO pity(player,crate_id,value) VALUES(?,?,?)")) {
            for (String player : section.getKeys(false)) {
                ConfigurationSection crates = section.getConfigurationSection(player);
                if (crates == null) {
                    continue;
                }
                for (String crate : crates.getKeys(false)) {
                    ps.setString(1, player);
                    ps.setString(2, crate.toLowerCase());
                    ps.setInt(3, crates.getInt(crate));
                    ps.addBatch();
                }
            }
            ps.executeBatch();
        }
    }

    private void migrateSectionLastOpen(Connection c, ConfigurationSection section) throws SQLException {
        if (section == null) {
            return;
        }
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT OR REPLACE INTO last_open(player,crate_id,ts) VALUES(?,?,?)")) {
            for (String player : section.getKeys(false)) {
                ConfigurationSection crates = section.getConfigurationSection(player);
                if (crates == null) {
                    continue;
                }
                for (String crate : crates.getKeys(false)) {
                    ps.setString(1, player);
                    ps.setString(2, crate.toLowerCase());
                    ps.setLong(3, crates.getLong(crate));
                    ps.addBatch();
                }
            }
            ps.executeBatch();
        }
    }

    private void migrateBlocks(Connection c, ConfigurationSection section) throws SQLException {
        if (section == null) {
            return;
        }
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT OR REPLACE INTO blocks(location,crate_id) VALUES(?,?)")) {
            for (String loc : section.getKeys(false)) {
                ps.setString(1, loc);
                ps.setString(2, section.getString(loc));
                ps.addBatch();
                blockCache.put(loc, section.getString(loc));
            }
            ps.executeBatch();
        }
    }

    private void migrateStats(Connection c, ConfigurationSection section) throws SQLException {
        if (section == null) {
            return;
        }
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT OR REPLACE INTO stats(path,value) VALUES(?,?)")) {
            for (String path : section.getKeys(true)) {
                if (section.isConfigurationSection(path)) {
                    continue;
                }
                long v = section.getLong(path);
                ps.setString(1, path);
                ps.setLong(2, v);
                ps.addBatch();
                statsCache.put(path, v);
            }
            ps.executeBatch();
        }
    }

    // --- player opens (dedicated table) ---
    public int getPlayerCrateOpens(UUID playerId, String crateId) {
        String key = k(playerId, crateId);
        Integer cached = opensCache.get(key);
        if (cached != null) {
            return cached;
        }
        try (Connection c = database.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count FROM player_opens WHERE player=? AND crate_id=?")) {
            ps.setString(1, playerId.toString());
            ps.setString(2, crateId.toLowerCase());
            try (ResultSet rs = ps.executeQuery()) {
                int v = rs.next() ? rs.getInt(1) : 0;
                opensCache.put(key, v);
                return v;
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "getPlayerCrateOpens failed", e);
            return 0;
        }
    }

    public void incrementPlayerCrateOpens(UUID playerId, String crateId) {
        String key = k(playerId, crateId);
        opensCache.merge(key, 1, Integer::sum);
        try (Connection c = database.connection();
             PreparedStatement ps = c.prepareStatement(
                     database.isMysql()
                             ? "INSERT INTO player_opens(player,crate_id,count) VALUES(?,?,1) ON DUPLICATE KEY UPDATE count=count+1"
                             : "INSERT INTO player_opens(player,crate_id,count) VALUES(?,?,1) ON CONFLICT(player,crate_id) DO UPDATE SET count=player_opens.count+1")) {
            ps.setString(1, playerId.toString());
            ps.setString(2, crateId.toLowerCase());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "incrementPlayerCrateOpens failed", e);
        }
    }

    // --- daily per crate ---
    public int getDailyCrateOpens(UUID playerId, String crateId) {
        String today = java.time.LocalDate.now().toString();
        try (Connection c = database.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count FROM daily_crate WHERE player=? AND crate_id=? AND day=?")) {
            ps.setString(1, playerId.toString());
            ps.setString(2, crateId.toLowerCase());
            ps.setString(3, today);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            return 0;
        }
    }

    public void incrementDailyCrateOpens(UUID playerId, String crateId) {
        String today = java.time.LocalDate.now().toString();
        try (Connection c = database.connection();
             PreparedStatement ps = c.prepareStatement(
                     database.isMysql()
                             ? "INSERT INTO daily_crate(player,crate_id,day,count) VALUES(?,?,?,1) ON DUPLICATE KEY UPDATE count=count+1"
                             : "INSERT INTO daily_crate(player,crate_id,day,count) VALUES(?,?,?,1) ON CONFLICT(player,crate_id,day) DO UPDATE SET count=daily_crate.count+1")) {
            ps.setString(1, playerId.toString());
            ps.setString(2, crateId.toLowerCase());
            ps.setString(3, today);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "incrementDailyCrateOpens failed", e);
        }
    }

    // --- crate unlocks ---
    public boolean isCrateUnlocked(UUID playerId, String crateId) {
        try (Connection c = database.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT 1 FROM crate_unlocks WHERE player=? AND crate_id=?")) {
            ps.setString(1, playerId.toString());
            ps.setString(2, crateId.toLowerCase());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            return false;
        }
    }

    public void unlockCrate(UUID playerId, String crateId) {
        try (Connection c = database.connection();
             PreparedStatement ps = c.prepareStatement(
                     database.isMysql()
                             ? "INSERT IGNORE INTO crate_unlocks(player,crate_id,unlocked_at) VALUES(?,?,?)"
                             : "INSERT OR IGNORE INTO crate_unlocks(player,crate_id,unlocked_at) VALUES(?,?,?)")) {
            ps.setString(1, playerId.toString());
            ps.setString(2, crateId.toLowerCase());
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "unlockCrate failed", e);
        }
    }

    public java.util.Set<String> getUnlockedCrates(UUID playerId) {
        java.util.Set<String> set = new java.util.HashSet<>();
        try (Connection c = database.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT crate_id FROM crate_unlocks WHERE player=?")) {
            ps.setString(1, playerId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) set.add(rs.getString(1));
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "getUnlockedCrates failed", e);
        }
        return set;
    }

    // --- battle pass ---
    public int getPassPoints(UUID playerId, String track) {
        String key = k(playerId, track);
        Integer cached = passPointsCache.get(key);
        if (cached != null) {
            return cached;
        }
        try (Connection c = database.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT points FROM pass_progress WHERE player=? AND track=?")) {
            ps.setString(1, playerId.toString());
            ps.setString(2, track.toLowerCase());
            try (ResultSet rs = ps.executeQuery()) {
                int v = rs.next() ? rs.getInt(1) : 0;
                passPointsCache.put(key, v);
                return v;
            }
        } catch (SQLException e) {
            return 0;
        }
    }

    public void addPassPoints(UUID playerId, String track, int delta) {
        if (delta == 0) return;
        int next = Math.max(0, getPassPoints(playerId, track) + delta);
        passPointsCache.put(k(playerId, track), next);
        try (Connection c = database.connection();
             PreparedStatement ps = c.prepareStatement(
                     database.isMysql()
                             ? "INSERT INTO pass_progress(player,track,points) VALUES(?,?,?) ON DUPLICATE KEY UPDATE points=VALUES(points)"
                             : "INSERT INTO pass_progress(player,track,points) VALUES(?,?,?) ON CONFLICT(player,track) DO UPDATE SET points=excluded.points")) {
            ps.setString(1, playerId.toString());
            ps.setString(2, track.toLowerCase());
            ps.setInt(3, next);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "addPassPoints failed", e);
        }
    }

    public boolean isPassTierClaimed(UUID playerId, String track, int tier) {
        try (Connection c = database.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT 1 FROM pass_claimed WHERE player=? AND track=? AND tier=?")) {
            ps.setString(1, playerId.toString());
            ps.setString(2, track.toLowerCase());
            ps.setInt(3, tier);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            return false;
        }
    }

    public void claimPassTier(UUID playerId, String track, int tier) {
        try (Connection c = database.connection();
             PreparedStatement ps = c.prepareStatement(
                     database.isMysql()
                             ? "INSERT IGNORE INTO pass_claimed(player,track,tier) VALUES(?,?,?)"
                             : "INSERT OR IGNORE INTO pass_claimed(player,track,tier) VALUES(?,?,?)")) {
            ps.setString(1, playerId.toString());
            ps.setString(2, track.toLowerCase());
            ps.setInt(3, tier);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "claimPassTier failed", e);
        }
    }

    // --- offline queue ---
    public long enqueueOfflineOpen(OfflineOpen open) {
        try (Connection c = database.connection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO offline_queue(player,crate_id,queued_by,created_at,forced_reward,consume_key) VALUES(?,?,?,?,?,?)",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, open.getPlayerId().toString());
            ps.setString(2, open.getCrateId());
            ps.setString(3, open.getQueuedBy());
            ps.setLong(4, open.getCreatedAtMillis());
            ps.setString(5, open.getForcedRewardId());
            ps.setInt(6, open.isConsumeKey() ? 1 : 0);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) return keys.getLong(1);
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "enqueueOfflineOpen failed", e);
        }
        return -1;
    }

    public List<OfflineOpen> takeOfflineQueue(UUID playerId) {
        List<OfflineOpen> list = new ArrayList<>();
        try (Connection c = database.connection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id,player,crate_id,queued_by,created_at,forced_reward,consume_key FROM offline_queue WHERE player=? ORDER BY id ASC")) {
                ps.setString(1, playerId.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(new OfflineOpen(
                                rs.getLong("id"),
                                UUID.fromString(rs.getString("player")),
                                rs.getString("crate_id"),
                                rs.getString("queued_by"),
                                rs.getLong("created_at"),
                                rs.getString("forced_reward"),
                                rs.getInt("consume_key") == 1
                        ));
                    }
                }
            }
            try (PreparedStatement del = c.prepareStatement("DELETE FROM offline_queue WHERE player=?")) {
                del.setString(1, playerId.toString());
                del.executeUpdate();
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "takeOfflineQueue failed", e);
        }
        return list;
    }

    public int countOfflineQueue(UUID playerId) {
        try (Connection c = database.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM offline_queue WHERE player=?")) {
            ps.setString(1, playerId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            return 0;
        }
    }

    public void logAdminAudit(String admin, String action, String targetPlayer, String detail) {
        try (Connection c = database.connection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO admin_audit(admin,action,target_player,detail,ts) VALUES(?,?,?,?,?)")) {
            ps.setString(1, admin);
            ps.setString(2, action);
            ps.setString(3, targetPlayer);
            ps.setString(4, detail);
            ps.setLong(5, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "logAdminAudit failed", e);
        }
    }


    public void enqueueGift(UUID playerId, String keyId, int amount, String fromPlayer) {
        try (Connection c = database.connection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO gift_queue(player,key_id,amount,from_player,created_at) VALUES(?,?,?,?,?)")) {
            ps.setString(1, playerId.toString());
            ps.setString(2, keyId.toLowerCase());
            ps.setInt(3, Math.max(1, amount));
            ps.setString(4, fromPlayer);
            ps.setLong(5, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "enqueueGift failed", e);
        }
    }

    public record PendingGift(String keyId, int amount, String fromPlayer) {}

    public List<PendingGift> takeGifts(UUID playerId) {
        List<PendingGift> list = new ArrayList<>();
        try (Connection c = database.connection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT key_id,amount,from_player FROM gift_queue WHERE player=? ORDER BY id ASC")) {
                ps.setString(1, playerId.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(new PendingGift(rs.getString(1), rs.getInt(2), rs.getString(3)));
                    }
                }
            }
            try (PreparedStatement del = c.prepareStatement("DELETE FROM gift_queue WHERE player=?")) {
                del.setString(1, playerId.toString());
                del.executeUpdate();
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "takeGifts failed", e);
        }
        return list;
    }

    public List<String> exportDropsCsv(UUID playerId, int limit) {
        List<String> lines = new ArrayList<>();
        lines.add("ts,player,player_name,crate_id,reward_id,reward_name");
        try (Connection c = database.connection();
             PreparedStatement ps = c.prepareStatement(
                     playerId == null
                             ? "SELECT ts,player,player_name,crate_id,reward_id,reward_name FROM drops_log ORDER BY ts DESC LIMIT ?"
                             : "SELECT ts,player,player_name,crate_id,reward_id,reward_name FROM drops_log WHERE player=? ORDER BY ts DESC LIMIT ?")) {
            if (playerId == null) {
                ps.setInt(1, limit);
            } else {
                ps.setString(1, playerId.toString());
                ps.setInt(2, limit);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    lines.add(rs.getLong(1) + "," + rs.getString(2) + ","
                            + safe(rs.getString(3)) + "," + safe(rs.getString(4)) + ","
                            + safe(rs.getString(5)) + "," + safe(rs.getString(6)));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "exportDropsCsv failed", e);
        }
        return lines;
    }

    private static String safe(String s) {
        if (s == null) return "";
        return s.replace(",", ";").replace("\n", " ");
    }

    public List<DropRecord> getDropsLog(UUID playerId, int limit) {
        List<DropRecord> list = new ArrayList<>();
        try (Connection c = database.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT crate_id,reward_id,reward_name,ts FROM drops_log WHERE player=? ORDER BY ts DESC LIMIT ?")) {
            ps.setString(1, playerId.toString());
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new DropRecord(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4)));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "getDropsLog failed", e);
        }
        return list;
    }

    public java.util.List<java.util.Map.Entry<java.util.UUID, Integer>> getTopOpeners(String crateId, int limit) {
        java.util.List<java.util.Map.Entry<java.util.UUID, Integer>> list = new java.util.ArrayList<>();
        String sql = crateId == null || crateId.isBlank()
                ? "SELECT player, SUM(count) AS c FROM player_opens GROUP BY player ORDER BY c DESC LIMIT ?"
                : "SELECT player, count AS c FROM player_opens WHERE crate_id=? ORDER BY count DESC LIMIT ?";
        try (Connection c = database.connection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            if (crateId == null || crateId.isBlank()) {
                ps.setInt(1, limit);
            } else {
                ps.setString(1, crateId.toLowerCase());
                ps.setInt(2, limit);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    try {
                        list.add(java.util.Map.entry(java.util.UUID.fromString(rs.getString(1)), rs.getInt(2)));
                    } catch (Exception ignored) {}
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "getTopOpeners failed", e);
        }
        return list;
    }

}
