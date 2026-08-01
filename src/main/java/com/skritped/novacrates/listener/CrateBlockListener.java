package com.skritped.novacrates.listener;

import com.skritped.novacrates.gui.GUIManager;
import com.skritped.novacrates.gui.OpenSelectGUI;
import com.skritped.novacrates.gui.PreviewGUI;
import com.skritped.novacrates.manager.CrateManager;
import com.skritped.novacrates.model.CrateDefinition;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.plugin.java.JavaPlugin;

public class CrateBlockListener implements Listener {
    private final JavaPlugin plugin;
    private final CrateManager manager;
    private final GUIManager guiManager;

    public CrateBlockListener(JavaPlugin plugin, CrateManager manager, GUIManager guiManager) {
        this.plugin = plugin;
        this.manager = manager;
        this.guiManager = guiManager;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getClickedBlock() == null) {
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK && event.getAction() != Action.LEFT_CLICK_BLOCK) {
            return;
        }
        String crateId = manager.getBlockCrate(key(event.getClickedBlock().getLocation()));
        if (crateId == null) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        CrateDefinition crate = manager.getCrate(crateId);
        if (crate == null) {
            return;
        }
        // Left click = preview only
        if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
            guiManager.openGUI(new PreviewGUI(plugin, guiManager, manager, crate), player);
            return;
        }
        // Right click = simple open menu (anim / no anim)
        if (!player.hasPermission("novacrates.open")) {
            return;
        }
        if (player.isSneaking()) {
            guiManager.openGUI(new PreviewGUI(plugin, guiManager, manager, crate), player);
            return;
        }
        guiManager.openGUI(new OpenSelectGUI(plugin, guiManager, manager, crate), player);
    }

    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        String locationKey = key(event.getBlock().getLocation());
        if (manager.getBlockCrate(locationKey) != null) {
            if (!event.getPlayer().hasPermission("novacrates.admin")) {
                event.setCancelled(true);
                return;
            }
            manager.removeBlock(locationKey);
        }
    }

    public static String key(Location location) {
        return location.getWorld().getName() + ":" + location.getBlockX() + ":"
                + location.getBlockY() + ":" + location.getBlockZ();
    }
}
