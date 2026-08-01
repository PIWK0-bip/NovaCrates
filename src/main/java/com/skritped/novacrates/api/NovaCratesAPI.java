package com.skritped.novacrates.api;

import com.skritped.novacrates.NovaCratesPlugin;
import com.skritped.novacrates.manager.CrateManager;
import com.skritped.novacrates.model.CrateDefinition;
import com.skritped.novacrates.model.DropRecord;
import com.skritped.novacrates.model.RewardDefinition;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiPredicate;

/**
 * Public API for other plugins.
 */
public final class NovaCratesAPI {
    private static final List<BiPredicate<Player, RewardDefinition>> rewardFilters = new CopyOnWriteArrayList<>();

    private NovaCratesAPI() {}

    private static NovaCratesPlugin plugin() {
        return (NovaCratesPlugin) Bukkit.getPluginManager().getPlugin("NovaCrates");
    }

    private static CrateManager manager() {
        NovaCratesPlugin p = plugin();
        return p == null ? null : p.getCrateManager();
    }

    public static boolean isReady() {
        return manager() != null;
    }

    public static void openCrate(Player player, String crateId) {
        CrateManager m = manager();
        if (m != null) m.openCrate(player, crateId);
    }

    public static void openCrate(Player player, String crateId, int times) {
        CrateManager m = manager();
        if (m != null) m.openCrate(player, crateId, times);
    }

    /**
     * Opens a single crate and completes when the reward is granted (after animation).
     */
    public static CompletableFuture<RewardDefinition> openCrateAsync(Player player, String crateId) {
        CrateManager m = manager();
        if (m == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("NovaCrates not ready"));
        }
        if (m.getCrate(crateId) == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Unknown crate: " + crateId));
        }
        return m.openCrateAsync(player, crateId);
    }

    public static int getVirtualKeys(UUID playerId, String keyId) {
        CrateManager m = manager();
        return m == null ? 0 : m.getPlayerData().getVirtualKeys(playerId, keyId);
    }

    public static void giveVirtualKey(Player player, String keyId, int amount) {
        NovaCratesPlugin p = plugin();
        if (p == null) return;
        p.getKeyService().giveVirtualKey(player, keyId, p.getRepository(), amount);
    }

    public static boolean giveKeys(UUID playerId, String keyId, int amount, boolean virtual) {
        NovaCratesPlugin p = plugin();
        if (p == null || keyId == null || amount < 1) return false;
        if (virtual) {
            int current = p.getRepository().getVirtualKeys(playerId, keyId);
            p.getRepository().setVirtualKeys(playerId, keyId, current + amount);
            return true;
        }
        Player online = Bukkit.getPlayer(playerId);
        if (online == null) return false;
        p.getKeyService().givePhysicalKey(online, keyId, amount);
        return true;
    }

    public static int getPlayerCrateOpens(UUID playerId, String crateId) {
        CrateManager m = manager();
        return m == null ? 0 : m.getPlayerCrateOpens(playerId, crateId);
    }

    public static boolean isUnlocked(Player player, String crateId) {
        CrateManager m = manager();
        if (m == null) return false;
        CrateDefinition c = m.getCrate(crateId);
        return c != null && m.isUnlocked(player, c);
    }

    public static Map<String, CrateDefinition> getCrates() {
        CrateManager m = manager();
        return m == null ? Collections.emptyMap() : m.getCrates();
    }

    public static List<DropRecord> getHistory(UUID playerId, int limit) {
        CrateManager m = manager();
        if (m == null) return List.of();
        List<DropRecord> all = m.getPlayerData().getHistory(playerId);
        if (limit <= 0 || all.size() <= limit) return all;
        return all.subList(0, Math.min(limit, all.size()));
    }

    public static void registerRewardFilter(BiPredicate<Player, RewardDefinition> filter) {
        if (filter != null) rewardFilters.add(filter);
    }

    public static void unregisterRewardFilter(BiPredicate<Player, RewardDefinition> filter) {
        rewardFilters.remove(filter);
    }

    public static List<BiPredicate<Player, RewardDefinition>> getRewardFilters() {
        return rewardFilters;
    }
}
