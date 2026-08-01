package com.skritped.novacrates.gui;

import com.skritped.novacrates.manager.CrateManager;
import com.skritped.novacrates.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.java.JavaPlugin;

public class GiftConfirmGUI extends InventoryGUI {
    private final JavaPlugin plugin;
    private final CrateManager manager;
    private final String targetName;
    private final String keyId;
    private final int amount;

    public GiftConfirmGUI(JavaPlugin plugin, CrateManager manager, String targetName, String keyId, int amount) {
        this.plugin = plugin;
        this.manager = manager;
        this.targetName = targetName;
        this.keyId = keyId;
        this.amount = Math.max(1, amount);
    }

    @Override
    protected Inventory createInventory() {
        return Bukkit.createInventory(null, 27, Text.legacy("&8✦ &dGift &8✦"));
    }

    @Override
    public void decorate(Player player) {
        clearButtons();
        getInventory().clear();
        GuiStyle.drawBorder27(getInventory());

        int have = manager.getPlayerData().getVirtualKeys(player.getUniqueId(), keyId);
        getInventory().setItem(4, GuiStyle.named(Material.TRIPWIRE_HOOK, "&d&lPodarunek",
                "&7Dla: &f" + targetName,
                "&7Klucz: &e" + keyId,
                "&7Ilość: &f" + amount,
                "&7Masz: &f" + have,
                "&8──────────────",
                have >= amount ? "&aWystarczy kluczy" : "&cZa mało kluczy!"));

        if (have >= amount) {
            addButton(11, GuiStyle.button(Material.LIME_CONCRETE, "&a&l✔ WYŚLIJ", e -> {
                Player pl = (Player) e.getWhoClicked();
                Player target = plugin.getServer().getPlayerExact(targetName);
                int current = manager.getPlayerData().getVirtualKeys(pl.getUniqueId(), keyId);
                if (current < amount) {
                    pl.sendMessage(Text.legacy("&cZa mało kluczy."));
                    pl.closeInventory();
                    return;
                }
                manager.getPlayerData().setVirtualKeys(pl.getUniqueId(), keyId, current - amount);
                if (target != null) {
                    manager.getPlayerData().setVirtualKeys(target.getUniqueId(), keyId,
                            manager.getPlayerData().getVirtualKeys(target.getUniqueId(), keyId) + amount);
                    target.sendMessage(Text.legacy("&aOtrzymałeś &f" + amount + "x " + keyId + " &aod &f" + pl.getName()));
                } else {
                    var off = plugin.getServer().getOfflinePlayer(targetName);
                    manager.enqueueGift(off.getUniqueId(), keyId, amount, pl.getName());
                }
                pl.sendMessage(Text.legacy("&aWysłano &f" + amount + "x " + keyId + " &ado &f" + targetName));
                pl.closeInventory();
            }, "&7Przekaż klucze"));
        } else {
            getInventory().setItem(11, GuiStyle.named(Material.GRAY_CONCRETE, "&8Brak kluczy"));
        }

        addButton(15, GuiStyle.button(Material.RED_CONCRETE, "&c&l✘ ANULUJ", e ->
                e.getWhoClicked().closeInventory()));

        super.decorate(player);
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        super.onClick(event);
    }
}
