package com.skritped.novacrates.service;

import com.skritped.novacrates.model.CostDefinition;
import com.cryptomorin.xseries.XMaterial;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * Supports MONEY (Vault), EXP, ITEM, POINTS (PlayerPoints), COINS (CoinsEngine soft).
 */
public class CostService {
    private final EconomyService economyService;
    private Object playerPointsApi;
    private Object coinsEngineApi; // su.nightexpress.coinsengine.api.CoinsEngineAPI or plugin facade

    public CostService(EconomyService economyService) {
        this.economyService = economyService;
        tryHookPlayerPoints();
        tryHookCoinsEngine();
    }

    private void tryHookPlayerPoints() {
        try {
            Plugin pp = Bukkit.getPluginManager().getPlugin("PlayerPoints");
            if (pp == null || !pp.isEnabled()) return;
            Object inst = pp.getClass().getMethod("getInstance").invoke(null);
            playerPointsApi = inst.getClass().getMethod("getAPI").invoke(inst);
        } catch (Throwable ignored) {
            playerPointsApi = null;
        }
    }

    private void tryHookCoinsEngine() {
        try {
            Plugin ce = Bukkit.getPluginManager().getPlugin("CoinsEngine");
            if (ce == null || !ce.isEnabled()) return;
            // CoinsEngineAPI static methods: getBalance, removeBalance, addBalance
            Class<?> api = Class.forName("su.nightexpress.coinsengine.api.CoinsEngineAPI");
            coinsEngineApi = api; // store Class for static invoke
        } catch (Throwable ignored) {
            coinsEngineApi = null;
        }
    }

    private boolean isPoints(String type) {
        if (type == null) return false;
        String t = type.toUpperCase();
        return t.equals("POINTS") || t.equals("PLAYERPOINTS") || t.equals("PP");
    }

    private boolean isCoins(String type) {
        if (type == null) return false;
        String t = type.toUpperCase();
        return t.equals("COINS") || t.equals("COINSENGINE") || t.equals("CE");
    }

    private int getPoints(Player player) {
        if (playerPointsApi == null) return 0;
        try {
            Object r = playerPointsApi.getClass()
                    .getMethod("look", java.util.UUID.class)
                    .invoke(playerPointsApi, player.getUniqueId());
            return r instanceof Integer ? (Integer) r : ((Number) r).intValue();
        } catch (Throwable t) {
            return 0;
        }
    }

    private boolean takePoints(Player player, int amount) {
        if (playerPointsApi == null) return false;
        try {
            Object r = playerPointsApi.getClass()
                    .getMethod("take", java.util.UUID.class, int.class)
                    .invoke(playerPointsApi, player.getUniqueId(), amount);
            return r instanceof Boolean ? (Boolean) r : true;
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean givePoints(Player player, int amount) {
        if (playerPointsApi == null) return false;
        try {
            playerPointsApi.getClass()
                    .getMethod("give", java.util.UUID.class, int.class)
                    .invoke(playerPointsApi, player.getUniqueId(), amount);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private double getCoins(Player player) {
        if (coinsEngineApi == null) return 0;
        try {
            Class<?> api = (Class<?>) coinsEngineApi;
            // getBalance(Player) or getBalance(UUID)
            try {
                Object r = api.getMethod("getBalance", Player.class).invoke(null, player);
                return r instanceof Number ? ((Number) r).doubleValue() : 0;
            } catch (NoSuchMethodException e) {
                Object r = api.getMethod("getBalance", java.util.UUID.class).invoke(null, player.getUniqueId());
                return r instanceof Number ? ((Number) r).doubleValue() : 0;
            }
        } catch (Throwable t) {
            return 0;
        }
    }

    private boolean takeCoins(Player player, double amount) {
        if (coinsEngineApi == null) return false;
        try {
            Class<?> api = (Class<?>) coinsEngineApi;
            try {
                Object r = api.getMethod("removeBalance", Player.class, double.class).invoke(null, player, amount);
                return r instanceof Boolean ? (Boolean) r : true;
            } catch (NoSuchMethodException e) {
                Object r = api.getMethod("removeBalance", java.util.UUID.class, double.class)
                        .invoke(null, player.getUniqueId(), amount);
                return r instanceof Boolean ? (Boolean) r : true;
            }
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean giveCoins(Player player, double amount) {
        if (coinsEngineApi == null) return false;
        try {
            Class<?> api = (Class<?>) coinsEngineApi;
            try {
                api.getMethod("addBalance", Player.class, double.class).invoke(null, player, amount);
                return true;
            } catch (NoSuchMethodException e) {
                api.getMethod("addBalance", java.util.UUID.class, double.class)
                        .invoke(null, player.getUniqueId(), amount);
                return true;
            }
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean canPay(Player player, CostDefinition cost) {
        if (cost == null || cost.isFree()) return true;
        if ("EXP".equalsIgnoreCase(cost.getType())) {
            return getTotalExperience(player) >= (int) cost.getAmount();
        }
        if ("ITEM".equalsIgnoreCase(cost.getType())) {
            return countItems(player, cost.getMaterial()) >= (int) cost.getAmount();
        }
        if ("MONEY".equalsIgnoreCase(cost.getType())) {
            return economyService.canAfford(player, cost.getAmount());
        }
        if (isPoints(cost.getType())) {
            return playerPointsApi != null && getPoints(player) >= (int) cost.getAmount();
        }
        if (isCoins(cost.getType())) {
            return coinsEngineApi != null && getCoins(player) >= cost.getAmount();
        }
        return false;
    }

    public boolean pay(Player player, CostDefinition cost) {
        if (cost == null || cost.isFree()) return true;
        if ("EXP".equalsIgnoreCase(cost.getType())) {
            int amount = (int) cost.getAmount();
            int total = getTotalExperience(player);
            if (total < amount) return false;
            setTotalExperience(player, total - amount);
            return true;
        }
        if ("ITEM".equalsIgnoreCase(cost.getType())) {
            return removeItems(player, cost.getMaterial(), (int) cost.getAmount());
        }
        if ("MONEY".equalsIgnoreCase(cost.getType())) {
            return economyService.withdraw(player, cost.getAmount());
        }
        if (isPoints(cost.getType())) {
            return takePoints(player, (int) cost.getAmount());
        }
        if (isCoins(cost.getType())) {
            return takeCoins(player, cost.getAmount());
        }
        return false;
    }

    public boolean refund(Player player, CostDefinition cost) {
        if (player == null || cost == null || cost.isFree()) return true;
        if ("EXP".equalsIgnoreCase(cost.getType())) {
            setTotalExperience(player, getTotalExperience(player) + (int) cost.getAmount());
            return true;
        }
        if ("ITEM".equalsIgnoreCase(cost.getType())) {
            Material material = XMaterial.matchXMaterial(cost.getMaterial())
                    .map(XMaterial::parseMaterial).orElse(Material.AIR);
            if (material != Material.AIR && material != null) {
                player.getInventory().addItem(new ItemStack(material, (int) cost.getAmount()));
            }
            return true;
        }
        if ("MONEY".equalsIgnoreCase(cost.getType())) {
            if (!economyService.isAvailable()) return false;
            return economyService.deposit(player, cost.getAmount());
        }
        if (isPoints(cost.getType())) {
            return givePoints(player, (int) cost.getAmount());
        }
        if (isCoins(cost.getType())) {
            return giveCoins(player, cost.getAmount());
        }
        return false;
    }

    public static int getTotalExperience(Player player) {
        int level = player.getLevel();
        float progress = player.getExp();
        int xpAtLevel = xpForLevel(level);
        int xpToNext = player.getExpToLevel();
        return xpAtLevel + Math.round(progress * xpToNext);
    }

    public static void setTotalExperience(Player player, int total) {
        total = Math.max(0, total);
        player.setExp(0f);
        player.setLevel(0);
        player.setTotalExperience(0);
        int remaining = total;
        while (remaining > 0) {
            int need = player.getExpToLevel();
            if (remaining >= need) {
                remaining -= need;
                player.giveExp(need);
            } else {
                player.giveExp(remaining);
                remaining = 0;
            }
        }
    }

    private static int xpForLevel(int level) {
        if (level <= 0) return 0;
        if (level <= 16) return level * level + 6 * level;
        if (level <= 31) return (int) (2.5 * level * level - 40.5 * level + 360);
        return (int) (4.5 * level * level - 162.5 * level + 2220);
    }

    private int countItems(Player player, String materialName) {
        Material material = XMaterial.matchXMaterial(materialName)
                .map(XMaterial::parseMaterial).orElse(Material.AIR);
        int count = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType() == material) count += item.getAmount();
        }
        return count;
    }

    private boolean removeItems(Player player, String materialName, int amount) {
        Material material = XMaterial.matchXMaterial(materialName)
                .map(XMaterial::parseMaterial).orElse(Material.AIR);
        int left = amount;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType() == material) {
                int removed = Math.min(left, item.getAmount());
                item.setAmount(item.getAmount() - removed);
                left -= removed;
                if (left == 0) return true;
            }
        }
        return left == 0;
    }
}
