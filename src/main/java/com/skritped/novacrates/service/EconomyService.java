package com.skritped.novacrates.service;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Optional Vault economy bridge via reflection (softdepend).
 */
public class EconomyService {
    private final JavaPlugin plugin;
    private Object economy;

    public EconomyService(JavaPlugin plugin) {
        this.plugin = plugin;
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            plugin.getLogger().info("Vault not found; MONEY costs are disabled.");
            return;
        }
        try {
            Class<?> economyClass = Class.forName("net.milkbowl.vault.economy.Economy");
            @SuppressWarnings({"unchecked", "rawtypes"})
            RegisteredServiceProvider registration =
                    plugin.getServer().getServicesManager().getRegistration(economyClass);
            if (registration != null) {
                economy = registration.getProvider();
                plugin.getLogger().info("Hooked into Vault economy.");
            } else {
                plugin.getLogger().info("Vault present but no economy provider; MONEY costs disabled.");
            }
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            plugin.getLogger().info("Vault API not available; MONEY costs are disabled.");
        }
    }

    public boolean isAvailable() {
        return economy != null;
    }

    public boolean canAfford(Player player, double amount) {
        if (economy == null) {
            return false;
        }
        try {
            return (boolean) economy.getClass()
                    .getMethod("has", org.bukkit.OfflinePlayer.class, double.class)
                    .invoke(economy, player, amount);
        } catch (ReflectiveOperationException e) {
            plugin.getLogger().warning("Economy has() failed: " + e.getMessage());
            return false;
        }
    }

    public boolean withdraw(Player player, double amount) {
        if (economy == null) {
            return false;
        }
        try {
            Object response = economy.getClass()
                    .getMethod("withdrawPlayer", org.bukkit.OfflinePlayer.class, double.class)
                    .invoke(economy, player, amount);
            return (boolean) response.getClass().getMethod("transactionSuccess").invoke(response);
        } catch (ReflectiveOperationException e) {
            plugin.getLogger().warning("Economy withdraw failed: " + e.getMessage());
            return false;
        }
    }

    public boolean deposit(Player player, double amount) {
        if (economy == null) {
            return false;
        }
        try {
            Object response = economy.getClass()
                    .getMethod("depositPlayer", org.bukkit.OfflinePlayer.class, double.class)
                    .invoke(economy, player, amount);
            return (boolean) response.getClass().getMethod("transactionSuccess").invoke(response);
        } catch (ReflectiveOperationException e) {
            plugin.getLogger().warning("Economy deposit failed: " + e.getMessage());
            return false;
        }
    }
}
