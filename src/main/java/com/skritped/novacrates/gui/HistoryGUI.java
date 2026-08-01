package com.skritped.novacrates.gui;

import com.skritped.novacrates.manager.CrateManager;
import com.skritped.novacrates.model.CrateDefinition;
import com.skritped.novacrates.model.DropRecord;
import com.skritped.novacrates.model.RewardDefinition;
import com.skritped.novacrates.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

public class HistoryGUI extends InventoryGUI {
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd.MM HH:mm")
            .withZone(ZoneId.systemDefault());
    private final CrateManager manager;
    private final UUID target;
    private String rarityFilter;

    public HistoryGUI(CrateManager manager, UUID target) {
        this(manager, target, null);
    }

    public HistoryGUI(CrateManager manager, UUID target, String rarityFilter) {
        this.manager = manager;
        this.target = target;
        this.rarityFilter = rarityFilter;
    }

    @Override
    protected Inventory createInventory() {
        return Bukkit.createInventory(null, 54, Text.legacy("&8✦ &eHistoria dropów &8✦"));
    }

    @Override
    public void decorate(Player player) {
        clearButtons();
        getInventory().clear();
        GuiStyle.drawBorder54(getInventory());

        getInventory().setItem(4, GuiStyle.named(Material.BOOK,
                "&e&lHistoria",
                "&7Filtr: &f" + (rarityFilter == null ? "WSZYSTKIE" : rarityFilter),
                "&8Kliknij rarity poniżej aby filtrować"));

        List<DropRecord> hist = manager.getPlayerData().getHistory(target);
        int idx = 0;
        for (DropRecord d : hist) {
            if (idx >= GuiStyle.CONTENT_54.length) break;
            String rarity = resolveRarity(d);
            if (rarityFilter != null && (rarity == null || !rarity.equalsIgnoreCase(rarityFilter))) {
                continue;
            }
            int slot = GuiStyle.CONTENT_54[idx++];
            ItemStack it = GuiStyle.named(Material.CHEST,
                    "&e" + (d.getRewardName() == null ? d.getRewardId() : d.getRewardName()),
                    "&7Skrzynia: &f" + d.getCrateId(),
                    GuiStyle.rarityColor(rarity) + "● " + (rarity == null ? "?" : rarity),
                    "&7Czas: &f" + FMT.format(Instant.ofEpochMilli(d.getTimeMillis())));
            addButton(slot, GuiStyle.button(it, e -> {}));
        }
        for (int s : GuiStyle.CONTENT_54) {
            if (getInventory().getItem(s) == null) {
                getInventory().setItem(s, GuiStyle.pane(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "&8•"));
            }
        }

        // Rarity filters on bottom
        addFilter(45, Material.WHITE_STAINED_GLASS_PANE, null, "&fAll");
        addFilter(46, Material.LIME_STAINED_GLASS_PANE, "UNCOMMON", "&aUNCOMMON");
        addFilter(47, Material.LIGHT_BLUE_STAINED_GLASS_PANE, "RARE", "&bRARE");
        addFilter(48, Material.MAGENTA_STAINED_GLASS_PANE, "EPIC", "&dEPIC");
        addButton(49, GuiStyle.button(Material.BARRIER, "&cZamknij", e -> e.getWhoClicked().closeInventory()));
        addFilter(50, Material.ORANGE_STAINED_GLASS_PANE, "LEGENDARY", "&6LEGENDARY");
        addFilter(51, Material.RED_STAINED_GLASS_PANE, "MYTHIC", "&cMYTHIC");
        getInventory().setItem(52, GuiStyle.border());
        getInventory().setItem(53, GuiStyle.border());

        super.decorate(player);
    }

    private void addFilter(int slot, Material mat, String rarity, String label) {
        boolean active = (rarity == null && rarityFilter == null)
                || (rarity != null && rarity.equalsIgnoreCase(rarityFilter));
        addButton(slot, GuiStyle.button(mat, label, e -> {
            this.rarityFilter = rarity;
            decorate((Player) e.getWhoClicked());
        }, active ? "&a✔ Aktywny" : "&7Kliknij aby filtrować"));
    }

    private String resolveRarity(DropRecord d) {
        if (d.getCrateId() == null) return null;
        CrateDefinition c = manager.getCrate(d.getCrateId());
        if (c == null) return null;
        for (RewardDefinition r : c.getRewards()) {
            if (r.getId().equals(d.getRewardId())) return r.getRarity();
        }
        return null;
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        super.onClick(event);
    }
}
