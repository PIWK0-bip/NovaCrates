package com.skritped.novacrates.gui;

import com.skritped.novacrates.manager.CrateManager;
import com.skritped.novacrates.model.CrateDefinition;
import com.skritped.novacrates.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ProgressGUI extends InventoryGUI {
    private final CrateManager manager;

    public ProgressGUI(CrateManager manager) {
        this.manager = manager;
    }

    @Override
    protected Inventory createInventory() {
        return Bukkit.createInventory(null, 54, Text.legacy("&8✦ &aPostęp &8✦"));
    }

    @Override
    public void decorate(Player player) {
        clearButtons();
        getInventory().clear();
        GuiStyle.drawBorder54(getInventory());

        getInventory().setItem(4, GuiStyle.named(Material.EXPERIENCE_BOTTLE, "&a&lTwój postęp",
                "&7Klucze, pity i unlocki"));

        // Virtual keys row
        int slot = 10;
        Map<String, Integer> keys = manager.getAllVirtualKeys(player.getUniqueId());
        if (keys.isEmpty()) {
            getInventory().setItem(13, GuiStyle.named(Material.STRUCTURE_VOID, "&7Brak kluczy virtual"));
        } else {
            for (var e : keys.entrySet()) {
                if (slot > 16) break;
                ItemStack icon = GuiStyle.named(Material.TRIPWIRE_HOOK, "&e" + e.getKey(),
                        "&7Ilość: &f" + e.getValue());
                addButton(slot++, GuiStyle.button(icon, ev -> {}));
            }
        }

        // Crates progress in content rows
        int idx = 0;
        int[] slots = {19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};
        for (CrateDefinition crate : manager.getCrates().values()) {
            if (idx >= slots.length) break;
            int opens = manager.getPlayerCrateOpens(player.getUniqueId(), crate.getId());
            boolean unlocked = manager.isUnlocked(player, crate);
            List<String> lore = new ArrayList<>();
            lore.add("&7Otwarć: &f" + opens);
            if (crate.hasUnlockRequirement()) {
                String req = crate.getUnlockRequiresCrate() == null ? "?" : crate.getUnlockRequiresCrate();
                int need = crate.getUnlockRequiresOpens();
                int have = req.equals("?") ? 0 : manager.getPlayerCrateOpens(player.getUniqueId(), req);
                lore.add("&7Unlock: &f" + have + "/" + need + " &8(" + req + ")");
                lore.add(unlocked ? "&a✔ ODBLOKOWANA" : "&c✘ ZABLOKOWANA");
            } else {
                lore.add("&aBez wymagań unlock");
            }
            int pity = manager.getPlayerData().getPity(player.getUniqueId(), crate.getId());
            if (crate.getPityThreshold() > 0) {
                lore.add("&7Pity: &d" + pity + "&7/&d" + crate.getPityThreshold());
            }
            Material mat = unlocked ? Material.CHEST : Material.ENDER_CHEST;
            addButton(slots[idx++], GuiStyle.button(
                    GuiStyle.named(mat, crate.getDisplayName(), lore), ev -> {}));
        }

        for (int s : slots) {
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
