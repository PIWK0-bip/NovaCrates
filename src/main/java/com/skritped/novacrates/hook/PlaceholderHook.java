package com.skritped.novacrates.hook;

import com.skritped.novacrates.manager.CrateManager;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class PlaceholderHook {
    private PlaceholderHook() {
    }

    public static void tryRegister(JavaPlugin plugin, CrateManager manager) {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
            return;
        }
        try {
            NovaCratesExpansion expansion = new NovaCratesExpansion(plugin, manager);
            if (expansion.register()) {
                plugin.getLogger().info("PlaceholderAPI expansion registered (novacrates).");
            }
        } catch (NoClassDefFoundError | Exception t) {
            plugin.getLogger().warning("Could not register PlaceholderAPI expansion: " + t.getMessage());
        }
    }
}
