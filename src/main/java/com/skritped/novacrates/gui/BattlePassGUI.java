package com.skritped.novacrates.gui;

import com.skritped.novacrates.manager.CrateManager;
import com.skritped.novacrates.service.BattlePassService;
import com.skritped.novacrates.util.Text;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

public class BattlePassGUI extends InventoryGUI {
    private final JavaPlugin plugin;
    private final CrateManager manager;
    private final String track;

    public BattlePassGUI(JavaPlugin plugin, CrateManager manager, String track) {
        this.plugin = plugin;
        this.manager = manager;
        this.track = track == null ? "default" : track;
    }

    @Override
    protected Inventory createInventory() {
        return Bukkit.createInventory(null, 54, Text.legacy("&8✦ &5Battle Pass &8✦"));
    }

    @Override
    public void decorate(Player player) {
        clearButtons();
        getInventory().clear();
        GuiStyle.drawBorder54(getInventory());
        BattlePassService bp = manager.getBattlePassService();
        if (bp == null || !bp.isEnabled()) {
            super.decorate(player);
            return;
        }
        int points = bp.getPoints(player.getUniqueId(), track);
        addButton(4, new InventoryButton().creator(p -> named(Material.NETHER_STAR,
                "&ePoints: &f" + points, List.of("&7Track: " + track))).consumer(e -> {}));

        int slot = 9;
        for (BattlePassService.TierInfo tier : bp.getTiers(player, track)) {
            if (slot >= 45) break;
            Material mat = switch (tier.state()) {
                case "CLAIMED" -> Material.LIME_CONCRETE;
                case "READY" -> Material.YELLOW_CONCRETE;
                default -> Material.GRAY_CONCRETE;
            };
            List<String> lore = new ArrayList<>();
            lore.add("&7Required: &f" + tier.pointsRequired() + " pts");
            lore.add("&7Status: &f" + tier.state());
            if ("READY".equals(tier.state())) lore.add("&aClick to claim");
            final int t = tier.tier();
            addButton(slot++, new InventoryButton()
                    .creator(p -> named(mat, "&bTier " + t, lore))
                    .consumer(e -> {
                        if ("READY".equals(tier.state())) {
                            bp.claimReady(player, track);
                            plugin.getServer().getScheduler().runTask(plugin, () ->
                                    decorate(player));
                        }
                    }));
        }
        addButton(49, new InventoryButton().creator(p -> named(Material.BARRIER, "&cClose", List.of()))
                .consumer(e -> e.getWhoClicked().closeInventory()));
        super.decorate(player);
    }

    private static ItemStack named(Material mat, String name, List<String> lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(Text.legacy(name));
            meta.setLore(lore.stream().map(Text::legacy).toList());
            item.setItemMeta(meta);
        }
        return item;
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        super.onClick(event);
    }
}
