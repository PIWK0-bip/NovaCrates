package com.skritped.novacrates.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.Inventory;

import java.util.HashMap;
import java.util.Map;

public abstract class InventoryGUI implements InventoryHandler {
    private Inventory inventory;
    private final Map<Integer, InventoryButton> buttons = new HashMap<>();

    public Inventory getInventory() {
        if (inventory == null) {
            inventory = createInventory();
        }
        return inventory;
    }

    protected void addButton(int slot, InventoryButton button) {
        buttons.put(slot, button);
    }

    protected void clearButtons() {
        buttons.clear();
    }

    public void decorate(Player player) {
        buttons.forEach((slot, button) -> {
            if (button.getIconCreator() != null) {
                getInventory().setItem(slot, button.getIconCreator().apply(player));
            }
        });
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        InventoryButton button = buttons.get(event.getRawSlot());
        if (button != null && button.getEventConsumer() != null) {
            button.getEventConsumer().accept(event);
        }
    }

    @Override
    public void onOpen(InventoryOpenEvent event) {
        decorate((Player) event.getPlayer());
    }

    @Override
    public void onClose(InventoryCloseEvent event) {
    }

    protected abstract Inventory createInventory();
}
