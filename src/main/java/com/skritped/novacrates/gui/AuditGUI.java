package com.skritped.novacrates.gui;

import com.skritped.novacrates.manager.CrateManager;
import com.skritped.novacrates.model.DropRecord;
import com.skritped.novacrates.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

public class AuditGUI extends InventoryGUI {
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());
    private final CrateManager manager;
    private final UUID target;
    private final String crateFilter;

    public AuditGUI(CrateManager manager, UUID target) {
        this(manager, target, null);
    }

    public AuditGUI(CrateManager manager, UUID target, String crateFilter) {
        this.manager = manager;
        this.target = target;
        this.crateFilter = crateFilter;
    }

    @Override
    protected Inventory createInventory() {
        return Bukkit.createInventory(null, 54, Text.legacy("&8✦ &cAudit dropów"));
    }

    @Override
    public void decorate(Player player) {
        clearButtons();
        getInventory().clear();
        GuiStyle.drawBorder54(getInventory());
        List<DropRecord> drops = manager.getPlayerData().getDropsLog(target, 200);
        int slot = 0;
        for (DropRecord d : drops) {
            if (slot >= 45) break;
            if (crateFilter != null && (d.getCrateId() == null || !d.getCrateId().equalsIgnoreCase(crateFilter))) {
                continue;
            }
            ItemStack it = new ItemStack(Material.PAPER);
            ItemMeta m = it.getItemMeta();
            if (m != null) {
                m.setDisplayName(Text.legacy("&e" + (d.getRewardName() == null ? d.getRewardId() : d.getRewardName())));
                m.setLore(List.of(
                        Text.legacy("&7Crate: &f" + d.getCrateId()),
                        Text.legacy("&7ID: &f" + d.getRewardId()),
                        Text.legacy("&7Czas: &f" + FMT.format(Instant.ofEpochMilli(d.getTimeMillis())))
                ));
                it.setItemMeta(m);
            }
            final ItemStack icon = it;
            addButton(slot++, new InventoryButton().creator(p -> icon.clone()).consumer(e -> {}));
        }
        addButton(49, new InventoryButton().creator(p -> {
            ItemStack i = new ItemStack(Material.BARRIER);
            ItemMeta m = i.getItemMeta();
            if (m != null) { m.setDisplayName(Text.legacy("&cZamknij")); i.setItemMeta(m); }
            return i;
        }).consumer(e -> e.getWhoClicked().closeInventory()));
        super.decorate(player);
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        super.onClick(event);
    }
}
