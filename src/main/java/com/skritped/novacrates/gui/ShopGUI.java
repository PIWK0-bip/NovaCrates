package com.skritped.novacrates.gui;

import com.skritped.novacrates.NovaCratesPlugin;
import com.skritped.novacrates.manager.CrateManager;
import com.skritped.novacrates.model.CostDefinition;
import com.skritped.novacrates.service.CostService;
import com.skritped.novacrates.service.KeyService;
import com.skritped.novacrates.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ShopGUI extends InventoryGUI {
    private final org.bukkit.plugin.java.JavaPlugin plugin;
    private final CrateManager manager;
    private final CostService costService;

    public ShopGUI(org.bukkit.plugin.java.JavaPlugin plugin, CrateManager manager, CostService costService) {
        this.plugin = plugin;
        this.manager = manager;
        this.costService = costService;
    }

    @Override
    protected Inventory createInventory() {
        return Bukkit.createInventory(null, 54, Text.legacy("&8✦ &6Sklep kluczy &8✦"));
    }

    @Override
    public void decorate(Player player) {
        clearButtons();
        getInventory().clear();
        GuiStyle.drawBorder54(getInventory());

        getInventory().setItem(4, GuiStyle.named(Material.EMERALD, "&6&lSklep kluczy",
                "&7Kliknij ofertę aby kupić"));

        ConfigurationSection entries = plugin.getConfig().getConfigurationSection("settings.key-shop.entries");
        if (entries == null) {
            getInventory().setItem(22, GuiStyle.named(Material.BARRIER, "&cBrak ofert w configu"));
            addButton(49, GuiStyle.button(Material.BARRIER, "&cZamknij", e -> e.getWhoClicked().closeInventory()));
            super.decorate(player);
            return;
        }

        final String defaultCurrency = plugin.getConfig().getString("settings.key-shop.currency", "MONEY");
        final String defaultMaterial = plugin.getConfig().getString("settings.key-shop.material");
        final KeyService keyService = ((NovaCratesPlugin) plugin).getKeyService();

        int idx = 0;
        for (String keyId : entries.getKeys(false)) {
            if (idx >= GuiStyle.CONTENT_54.length) break;
            ConfigurationSection e = entries.getConfigurationSection(keyId);
            if (e == null) continue;

            final String fKey = keyId;
            final double fPrice = e.getDouble("price", 0);
            final int fAmount = Math.max(1, e.getInt("amount", 1));
            final boolean fVirtual = e.getBoolean("virtual", true);
            final String fCurrency = e.getString("currency", defaultCurrency).toUpperCase();
            final String fMaterial = e.getString("material", defaultMaterial);

            ItemStack built = keyService.createPhysicalKey(fKey, fAmount);
            if (built == null) built = new ItemStack(Material.TRIPWIRE_HOOK, fAmount);
            ItemMeta meta = built.getItemMeta();
            if (meta != null) {
                List<String> lore = meta.hasLore() && meta.getLore() != null
                        ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
                lore.add(Text.legacy("&8──────────────"));
                if ("ITEM".equals(fCurrency)) {
                    lore.add(Text.legacy("&7Cena: &e" + (int) fPrice + "x " + (fMaterial == null ? "?" : fMaterial)));
                } else {
                    lore.add(Text.legacy("&7Cena: &6" + fPrice + " " + fCurrency));
                }
                lore.add(Text.legacy(fVirtual ? "&a● Virtual" : "&b● Physical"));
                lore.add(Text.legacy("&e▶ Kliknij aby kupić"));
                meta.setLore(lore);
                built.setItemMeta(meta);
            }
            final ItemStack icon = built;
            int slot = GuiStyle.CONTENT_54[idx++];
            addButton(slot, GuiStyle.button(icon, ev -> {
                Player p = (Player) ev.getWhoClicked();
                CostDefinition cost = new CostDefinition(fCurrency, fPrice, fMaterial);
                if (!costService.canPay(p, cost) || !costService.pay(p, cost)) {
                    manager.getMessages().send(p, "cost-required");
                    return;
                }
                if (fVirtual) {
                    keyService.giveVirtualKey(p, fKey, manager.getPlayerData(), fAmount);
                } else {
                    keyService.givePhysicalKey(p, fKey, fAmount);
                }
                manager.getMessages().send(p, "key-received", Map.of(
                        "amount", String.valueOf(fAmount),
                        "key", fKey,
                        "player", p.getName()));
            }));
        }

        for (int s : GuiStyle.CONTENT_54) {
            if (getInventory().getItem(s) == null) {
                getInventory().setItem(s, GuiStyle.pane(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "&8•"));
            }
        }

        addButton(49, GuiStyle.button(Material.BARRIER, "&cZamknij", e -> e.getWhoClicked().closeInventory()));
        super.decorate(player);
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        super.onClick(event);
    }
}
