package com.skritped.novacrates.listener;

import com.skritped.novacrates.animation.AnimationController;
import com.skritped.novacrates.manager.CrateManager;
import com.skritped.novacrates.util.SchedulerUtil;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

public class PlayerConnectionListener implements Listener {
    private final CrateManager manager;
    private final AnimationController animationController;
    private final JavaPlugin plugin;

    public PlayerConnectionListener(CrateManager manager, AnimationController animationController) {
        this.manager = manager;
        this.animationController = animationController;
        this.plugin = null;
    }

    public PlayerConnectionListener(JavaPlugin plugin, CrateManager manager, AnimationController animationController) {
        this.plugin = plugin;
        this.manager = manager;
        this.animationController = animationController;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        manager.settleDebts(event.getPlayer());
        Runnable process = () -> {
            manager.processOfflineQueue(event.getPlayer());
            manager.processGiftQueue(event.getPlayer());
            manager.checkAutoUnlocks(event.getPlayer());
            if (manager.getBattlePassService() != null && manager.getBattlePassService().isEnabled()) {
                manager.getBattlePassService().claimReady(event.getPlayer(), null);
            }
        };
        long delay = 20L;
        if (plugin != null) {
            delay = Math.max(1, plugin.getConfig().getInt("settings.offline-queue-process-on-join-delay-ticks", 20));
            SchedulerUtil.runLater(plugin, process, delay);
        } else {
            process.run();
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        var id = event.getPlayer().getUniqueId();
        if (animationController.isAnimating(id)) {
            animationController.forceFinish(id);
        }
        manager.releaseOpening(id);
    }
}
