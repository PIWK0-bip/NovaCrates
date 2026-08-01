package com.skritped.novacrates.gui;

import com.skritped.novacrates.manager.CrateManager;
import com.skritped.novacrates.model.CrateDefinition;
import com.skritped.novacrates.model.RewardDefinition;
import com.skritped.novacrates.service.MessageService;
import com.skritped.novacrates.service.RewardSelector;
import com.skritped.novacrates.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Open GUI — same look as Preview: frame + glow dyes. */
public class OpenSelectGUI extends InventoryGUI {
    private final JavaPlugin plugin;
    private final GUIManager guiManager;
    private final CrateManager manager;
    private final CrateDefinition crate;

    public OpenSelectGUI(JavaPlugin plugin, GUIManager guiManager, CrateManager manager, CrateDefinition crate) {
        this.plugin = plugin;
        this.guiManager = guiManager;
        this.manager = manager;
        this.crate = crate;
    }

    private MessageService msg() {
        return manager.getMessages();
    }

    @Override
    protected Inventory createInventory() {
        String title = msg().gui("gui-preview-title", Map.of("crate",
                crate.getDisplayName() == null ? crate.getId() : crate.getDisplayName()));
        if (Text.strip(title).length() > 32) {
            title = Text.legacy(crate.getDisplayName() == null ? crate.getId() : crate.getDisplayName());
        }
        return Bukkit.createInventory(null, 54, title);
    }

    @Override
    public void decorate(Player player) {
        clearButtons();
        getInventory().clear();

        ItemStack border = GuiStyle.pane(Material.GRAY_STAINED_GLASS_PANE, " ");
        ItemStack top = GuiStyle.pane(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 9; i++) getInventory().setItem(i, top.clone());
        for (int i = 45; i < 54; i++) getInventory().setItem(i, border.clone());
        for (int r = 1; r <= 4; r++) {
            getInventory().setItem(r * 9, border.clone());
            getInventory().setItem(r * 9 + 8, border.clone());
        }

        int[] slots = GuiStyle.CONTENT_54;
        int i = 0;
        for (RewardDefinition reward : crate.getRewards()) {
            if (i >= slots.length) break;
            ItemStack icon = manager.getItemFactory().create(reward);
            ItemMeta meta = icon.getItemMeta();
            if (meta != null) {
                List<String> lore = new ArrayList<>();
                if (meta.hasLore() && meta.getLore() != null) {
                    for (String line : meta.getLore()) {
                        String s = Text.strip(line);
                        if (s.startsWith("Szansa:") || s.startsWith("Chance:") || s.startsWith("──")) continue;
                        lore.add(line);
                    }
                }
                double pct = reward.getDisplayChance() != null
                        ? reward.getDisplayChance()
                        : RewardSelector.normalizedPercent(reward, crate, player);
                lore.add(msg().gui("gui-preview-chance-line",
                        Map.of("chance", String.format(Locale.US, "%.2f%%", pct))));
                meta.setLore(lore);
                icon.setItemMeta(meta);
            }
            final ItemStack fi = icon;
            addButton(slots[i++], GuiStyle.button(fi, e -> {}));
        }

        ItemStack purple = glowDye(Material.PURPLE_DYE,
                msg().gui("gui-preview-open-anim"),
                msg().gui("gui-preview-open-anim-lore"));
        ItemStack green = glowDye(Material.LIME_DYE,
                msg().gui("gui-preview-open-plain"),
                msg().gui("gui-preview-open-plain-lore"));

        for (int s : new int[]{46, 47, 48}) {
            addButton(s, GuiStyle.button(purple.clone(), e -> {
                Player pl = (Player) e.getWhoClicked();
                pl.closeInventory();
                plugin.getServer().getScheduler().runTask(plugin, () ->
                        manager.openCrate(pl, crate.getId(), 1, false));
            }));
        }
        for (int s : new int[]{50, 51, 52}) {
            addButton(s, GuiStyle.button(green.clone(), e -> {
                Player pl = (Player) e.getWhoClicked();
                pl.closeInventory();
                plugin.getServer().getScheduler().runTask(plugin, () ->
                        manager.openCrate(pl, crate.getId(), 1, true));
            }));
        }
        addButton(49, GuiStyle.button(Material.BARRIER, msg().gui("gui-preview-close"),
                e -> e.getWhoClicked().closeInventory()));

        super.decorate(player);
    }

    private static ItemStack glowDye(Material mat, String name, String loreLine) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            List<String> lore = new ArrayList<>();
            if (loreLine != null && !loreLine.isEmpty()) lore.add(loreLine);
            meta.setLore(lore);
            try {
                meta.addEnchant(Enchantment.LUCK_OF_THE_SEA, 1, true);
            } catch (Throwable t) {
                try { meta.addEnchant(Enchantment.UNBREAKING, 1, true); } catch (Throwable ignored) {}
            }
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ATTRIBUTES);
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
