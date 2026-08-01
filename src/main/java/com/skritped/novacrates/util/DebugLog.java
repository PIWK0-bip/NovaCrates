package com.skritped.novacrates.util;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

/** Logs at FINE/WARNING when settings.debug is true. */
public final class DebugLog {
    private DebugLog() {}

    public static boolean enabled(JavaPlugin plugin) {
        return plugin != null && plugin.getConfig().getBoolean("settings.debug", false);
    }

    public static void fine(JavaPlugin plugin, String msg) {
        if (enabled(plugin)) {
            plugin.getLogger().log(Level.INFO, "[debug] " + msg);
        }
    }

    public static void warn(JavaPlugin plugin, String msg, Throwable t) {
        if (enabled(plugin)) {
            plugin.getLogger().log(Level.WARNING, "[debug] " + msg, t);
        } else if (t != null) {
            plugin.getLogger().log(Level.FINE, msg, t);
        }
    }
}
