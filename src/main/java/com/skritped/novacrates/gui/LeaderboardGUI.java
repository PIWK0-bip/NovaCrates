package com.skritped.novacrates.gui;

import com.skritped.novacrates.manager.CrateManager;
import com.skritped.novacrates.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public class LeaderboardGUI extends InventoryGUI {
    private final CrateManager manager;
    private final String crateId;

    public LeaderboardGUI(CrateManager manager, String crateId) {
        this.manager = manager;
        this.crateId = crateId;
    }

    @Override
    protected Inventory createInventory() {
        String title = crateId == null || crateId.isBlank() || crateId.equalsIgnoreCase("all")
                ? "&8✦ &6Top otwarć &8✦"
                : "&8✦ &6Top &e" + crateId + " &8✦";
        return Bukkit.createInventory(null, 54, Text.legacy(title));
    }

    @Override
    public void decorate(Player player) {
        clearButtons();
        getInventory().clear();
        GuiStyle.drawBorder54(getInventory());

        String label = crateId == null || crateId.isBlank() || "all".equalsIgnoreCase(crateId)
                ? "Wszystkie skrzynie" : crateId;
        getInventory().setItem(4, GuiStyle.named(Material.GOLDEN_HELMET, "&6&lRanking",
                "&7Skrzynia: &f" + label));

        List<Map.Entry<UUID, Integer>> top = manager.getTopOpeners(
                "all".equalsIgnoreCase(crateId) ? null : crateId, 21);

        Material[] medals = {Material.GOLD_BLOCK, Material.IRON_BLOCK, Material.COPPER_BLOCK};
        int idx = 0;
        for (var entry : top) {
            if (idx >= GuiStyle.CONTENT_54.length) break;
            int rank = idx + 1;
            String name = Bukkit.getOfflinePlayer(entry.getKey()).getName();
            if (name == null) name = entry.getKey().toString().substring(0, 8);

            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            try {
                SkullMeta sm = (SkullMeta) head.getItemMeta();
                if (sm != null) {
                    sm.setOwningPlayer(Bukkit.getOfflinePlayer(entry.getKey()));
                    sm.setDisplayName(Text.legacy(rankColor(rank) + "#" + rank + " &f" + name));
                    sm.setLore(List.of(
                            Text.legacy("&7Otwarć: &e&l" + entry.getValue()),
                            Text.legacy(rank <= 3 ? "&6✦ Top " + rank : "&8—")
                    ));
                    head.setItemMeta(sm);
                }
            } catch (Exception e) {
                head = GuiStyle.named(rank <= 3 ? medals[rank - 1] : Material.PLAYER_HEAD,
                        rankColor(rank) + "#" + rank + " &f" + name,
                        "&7Otwarć: &e" + entry.getValue());
            }
            final ItemStack icon = head;
            addButton(GuiStyle.CONTENT_54[idx++], GuiStyle.button(icon, ev -> {}));
        }

        for (int s : GuiStyle.CONTENT_54) {
            if (getInventory().getItem(s) == null) {
                getInventory().setItem(s, GuiStyle.pane(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "&8•"));
            }
        }

        addButton(49, GuiStyle.button(Material.BARRIER, "&cZamknij", e -> e.getWhoClicked().closeInventory()));
        super.decorate(player);
    }

    private static String rankColor(int rank) {
        return switch (rank) {
            case 1 -> "&6";
            case 2 -> "&7";
            case 3 -> "&c";
            default -> "&e";
        };
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        super.onClick(event);
    }
}
