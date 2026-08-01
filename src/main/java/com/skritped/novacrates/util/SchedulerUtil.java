package com.skritped.novacrates.util;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.java.JavaPlugin;

/** Folia-safe scheduling helpers with Bukkit fallback. */
public final class SchedulerUtil {
    private static final boolean FOLIA;

    static {
        boolean f = false;
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            f = true;
        } catch (ClassNotFoundException ignored) {
        }
        FOLIA = f;
    }

    private SchedulerUtil() {}

    public static boolean isFolia() {
        return FOLIA;
    }

    public static void run(JavaPlugin plugin, Runnable task) {
        if (FOLIA) {
            try {
                Bukkit.getGlobalRegionScheduler().execute(plugin, task);
                return;
            } catch (Throwable ignored) {
            }
        }
        Bukkit.getScheduler().runTask(plugin, task);
    }

    public static void runLater(JavaPlugin plugin, Runnable task, long delayTicks) {
        if (FOLIA) {
            try {
                Bukkit.getGlobalRegionScheduler().runDelayed(plugin, t -> task.run(), Math.max(1, delayTicks));
                return;
            } catch (Throwable ignored) {
            }
        }
        Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
    }

    public static void runEntity(JavaPlugin plugin, Entity entity, Runnable task) {
        if (FOLIA && entity != null) {
            try {
                entity.getScheduler().run(plugin, t -> task.run(), null);
                return;
            } catch (Throwable ignored) {
            }
        }
        Bukkit.getScheduler().runTask(plugin, task);
    }

    /**
     * Repeating task. On Folia uses global region scheduler; returns a cancellable handle.
     */
    public static Cancellable runTimer(JavaPlugin plugin, Runnable task, long delayTicks, long periodTicks) {
        if (FOLIA) {
            try {
                Object scheduled = Bukkit.getGlobalRegionScheduler().runAtFixedRate(
                        plugin, t -> task.run(), Math.max(1, delayTicks), Math.max(1, periodTicks));
                return () -> {
                    try {
                        scheduled.getClass().getMethod("cancel").invoke(scheduled);
                    } catch (Throwable ignored) {
                    }
                };
            } catch (Throwable ignored) {
            }
        }
        org.bukkit.scheduler.BukkitTask bt = Bukkit.getScheduler().runTaskTimer(plugin, task, delayTicks, periodTicks);
        return bt::cancel;
    }

    /** Location-aware repeating task (Folia region scheduler when available). */
    public static Cancellable runAtLocationTimer(JavaPlugin plugin, Location loc, Runnable task,
                                                  long delayTicks, long periodTicks) {
        if (FOLIA && loc != null && loc.getWorld() != null) {
            try {
                Object scheduled = Bukkit.getRegionScheduler().runAtFixedRate(
                        plugin, loc, t -> task.run(), Math.max(1, delayTicks), Math.max(1, periodTicks));
                return () -> {
                    try {
                        scheduled.getClass().getMethod("cancel").invoke(scheduled);
                    } catch (Throwable ignored) {
                    }
                };
            } catch (Throwable ignored) {
            }
        }
        return runTimer(plugin, task, delayTicks, periodTicks);
    }

    /** Entity-tied repeating task. */
    public static Cancellable runEntityTimer(JavaPlugin plugin, Entity entity, Runnable task,
                                             long delayTicks, long periodTicks) {
        if (FOLIA && entity != null) {
            try {
                Object scheduled = entity.getScheduler().runAtFixedRate(
                        plugin, t -> task.run(), null, Math.max(1, delayTicks), Math.max(1, periodTicks));
                return () -> {
                    try {
                        scheduled.getClass().getMethod("cancel").invoke(scheduled);
                    } catch (Throwable ignored) {
                    }
                };
            } catch (Throwable ignored) {
            }
        }
        return runTimer(plugin, task, delayTicks, periodTicks);
    }

    @FunctionalInterface
    public interface Cancellable {
        void cancel();
    }
}
