package com.skritped.novacrates.gui;

import com.skritped.novacrates.manager.CrateManager;
import com.skritped.novacrates.model.CrateDefinition;
import com.skritped.novacrates.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.java.JavaPlugin;

public class MultiOpenConfirmGUI extends InventoryGUI {
    private final JavaPlugin plugin;
    private final CrateManager manager;
    private final String crateId;
    private final int times;

    public MultiOpenConfirmGUI(JavaPlugin plugin, CrateManager manager, String crateId, int times) {
        this.plugin = plugin;
        this.manager = manager;
        this.crateId = crateId;
        this.times = Math.max(1, times);
    }

    @Override
    protected Inventory createInventory() {
        return Bukkit.createInventory(null, 27, Text.legacy("&8✦ &ePotwierdzenie &8✦"));
    }

    @Override
    public void decorate(Player player) {
        clearButtons();
        getInventory().clear();
        GuiStyle.drawBorder27(getInventory());

        CrateDefinition crate = manager.getCrate(crateId);
        String name = crate == null ? crateId : Text.strip(crate.getDisplayName());
        String costLine = "&7Bez dodatkowego kosztu";
        if (crate != null && !crate.getCost().isFree()) {
            double total = crate.getCost().getAmount() * times;
            costLine = "&7Koszt łącznie: &6" + total + " " + crate.getCost().getType();
        }

        getInventory().setItem(4, GuiStyle.named(Material.CHEST, "&e&l" + name,
                "&7Ilość otwarć: &f&l" + times,
                costLine,
                "&8──────────────",
                "&ePotwierdź poniżej"));

        addButton(11, GuiStyle.button(Material.LIME_CONCRETE, "&a&l✔ POTWIERDŹ", e -> {
            Player pl = (Player) e.getWhoClicked();
            pl.closeInventory();
            plugin.getServer().getScheduler().runTask(plugin, () ->
                    manager.openCrateConfirmed(pl, crateId, times));
        }, "&7Otwórz &f" + times + "x"));

        addButton(15, GuiStyle.button(Material.RED_CONCRETE, "&c&l✘ ANULUJ", e ->
                e.getWhoClicked().closeInventory()));

        // Decorative fillers in middle
        for (int s : new int[]{10, 12, 13, 14, 16}) {
            if (getInventory().getItem(s) == null) {
                getInventory().setItem(s, GuiStyle.pane(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "&8•"));
            }
        }

        super.decorate(player);
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        super.onClick(event);
    }
}
