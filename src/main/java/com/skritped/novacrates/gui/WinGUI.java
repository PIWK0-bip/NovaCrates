package com.skritped.novacrates.gui;

import com.skritped.novacrates.model.RewardDefinition;
import com.skritped.novacrates.service.ItemFactory;
import com.skritped.novacrates.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class WinGUI extends InventoryGUI {
    private final RewardDefinition reward;
    private final ItemFactory itemFactory;
    private final String crateName;
    private final int pity;
    private final double chancePercent;

    public WinGUI(RewardDefinition reward, ItemFactory itemFactory, String crateName) {
        this(reward, itemFactory, crateName, 0, 0);
    }


    /** Open win screen for player via GUIManager. */
    public static void show(JavaPlugin plugin, GUIManager guiManager, Player player,
                            RewardDefinition reward, ItemFactory itemFactory,
                            String crateName, int holdTicks, int pity, double chancePercent) {
        if (player == null || !player.isOnline() || reward == null) return;
        WinGUI gui = new WinGUI(reward, itemFactory, crateName, pity, chancePercent);
        if (guiManager != null) {
            guiManager.openGUI(gui, player);
        } else {
            player.openInventory(gui.getInventory());
            gui.decorate(player);
        }
        // Auto-close after holdTicks
        if (plugin != null && holdTicks > 0) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline() && player.getOpenInventory() != null) {
                    try { player.closeInventory(); } catch (Throwable ignored) {}
                }
            }, Math.max(20, holdTicks));
        }
    }

    public WinGUI(RewardDefinition reward, ItemFactory itemFactory, String crateName, int pity, double chancePercent) {
        this.reward = reward;
        this.itemFactory = itemFactory;
        this.crateName = crateName;
        this.pity = pity;
        this.chancePercent = chancePercent;
    }

    @Override
    protected Inventory createInventory() {
        return Bukkit.createInventory(null, 27, Text.legacy("&8✦ &a&lWYGRANA! &8✦"));
    }

    @Override
    public void decorate(Player player) {
        clearButtons();
        getInventory().clear();

        Material glass = GuiStyle.rarityGlass(reward.getRarity());
        ItemStack pane = GuiStyle.pane(glass, " ");
        for (int i = 0; i < 27; i++) getInventory().setItem(i, pane.clone());

        // Accent corners
        ItemStack accent = GuiStyle.accent();
        getInventory().setItem(0, accent);
        getInventory().setItem(8, accent);
        getInventory().setItem(18, accent);
        getInventory().setItem(26, accent);

        ItemStack prize = itemFactory.create(reward);
        getInventory().setItem(13, prize);

        String rarity = reward.getRarity() == null ? "COMMON" : reward.getRarity();
        List<String> lore = new ArrayList<>();
        lore.add("&8──────────────");
        lore.add("&7Skrzynia: &f" + crateName);
        lore.add(GuiStyle.rarityColor(rarity) + "● " + rarity);
        if (chancePercent > 0) {
            lore.add("&7Szansa: &e" + String.format(Locale.US, "%.2f%%", chancePercent));
        }
        if (pity > 0) {
            lore.add("&7Pity: &d" + pity);
        }
        lore.add("&8──────────────");
        lore.add("&aGratulacje!");

        addButton(11, GuiStyle.button(GuiStyle.named(Material.PAPER, "&eSzczegóły", lore), e -> {}));
        addButton(15, GuiStyle.button(GuiStyle.named(Material.NETHER_STAR, "&6✦ " + Text.strip(reward.getDisplayName()),
                GuiStyle.rarityColor(rarity) + rarity), e -> {}));
        addButton(22, GuiStyle.button(Material.LIME_CONCRETE, "&a✔ Odbierz", e ->
                e.getWhoClicked().closeInventory(), "&7Kliknij aby zamknąć"));

        super.decorate(player);
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        super.onClick(event);
    }
}
