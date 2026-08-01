package com.skritped.novacrates.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.Inventory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class GUIManager {
    private final Map<Inventory, InventoryHandler> activeInventories = new ConcurrentHashMap<>();

    public void openGUI(InventoryGUI gui, Player player) {
        activeInventories.put(gui.getInventory(), gui);
        player.openInventory(gui.getInventory());
    }

    public void handleClick(InventoryClickEvent event) {
        InventoryHandler handler = activeInventories.get(event.getView().getTopInventory());
        if (handler != null) {
            handler.onClick(event);
        }
    }

    public void handleOpen(InventoryOpenEvent event) {
        InventoryHandler handler = activeInventories.get(event.getInventory());
        if (handler != null) {
            handler.onOpen(event);
        }
    }

    public void handleClose(InventoryCloseEvent event) {
        Inventory inventory = event.getInventory();
        InventoryHandler handler = activeInventories.remove(inventory);
        if (handler != null) {
            handler.onClose(event);
        }
    }

    public boolean isPluginInventory(Inventory inventory) {
        return activeInventories.containsKey(inventory);
    }
}
