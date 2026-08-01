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
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ManageGUI extends InventoryGUI {
    private static final Set<String> CONFIRM_DELETE = new HashSet<>();

    private final JavaPlugin plugin;
    private final GUIManager guiManager;
    private final CrateManager manager;

    public ManageGUI(JavaPlugin plugin, GUIManager guiManager, CrateManager manager) {
        this.plugin = plugin;
        this.guiManager = guiManager;
        this.manager = manager;
    }

    @Override
    protected Inventory createInventory() {
        return Bukkit.createInventory(null, 54, Text.legacy("&8Zarządzanie skrzynkami"));
    }

    @Override
    public void decorate(Player player) {
        clearButtons();
        getInventory().clear();

        // Light border so chests stand out
        ItemStack border = GuiStyle.pane(Material.LIGHT_BLUE_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 9; i++) getInventory().setItem(i, border.clone());
        for (int i = 45; i < 54; i++) getInventory().setItem(i, border.clone());
        for (int r = 1; r <= 4; r++) {
            getInventory().setItem(r * 9, border.clone());
            getInventory().setItem(r * 9 + 8, border.clone());
        }

        int idx = 0;
        int[] slots = GuiStyle.CONTENT_54;
        for (CrateDefinition crate : manager.getCrates().values()) {
            if (idx >= slots.length) break;
            ItemStack icon = new ItemStack(Material.CHEST);
            ItemMeta meta = icon.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(Text.legacy("&6&l" + Text.strip(crate.getDisplayName())));
                List<String> lore = new ArrayList<>();
                lore.add(Text.legacy("&7ID: &f" + crate.getId()));
                lore.add(Text.legacy("&7Nagród: &e" + crate.getRewards().size()));
                lore.add(Text.legacy("&8──────────────"));
                lore.add(Text.legacy("&eLPM &7— edytor"));
                lore.add(Text.legacy("&ePPM &7— podgląd"));
                lore.add(Text.legacy("&cShift+LPM &7— usuń"));
                meta.setLore(lore);
                icon.setItemMeta(meta);
            }
            final String crateId = crate.getId();
            final ItemStack finalIcon = icon;
            addButton(slots[idx++], new InventoryButton()
                    .creator(p -> finalIcon.clone())
                    .consumer(event -> {
                        Player p = (Player) event.getWhoClicked();
                        String confirmKey = p.getUniqueId() + ":" + crateId;
                        if (event.isShiftClick() && event.isLeftClick()) {
                            if (!CONFIRM_DELETE.contains(confirmKey)) {
                                CONFIRM_DELETE.add(confirmKey);
                                p.sendMessage(Text.legacy("&cKliknij ponownie Shift+LPM aby usunąć &f" + crateId));
                                plugin.getServer().getScheduler().runTaskLater(plugin,
                                        () -> CONFIRM_DELETE.remove(confirmKey), 100L);
                                return;
                            }
                            CONFIRM_DELETE.remove(confirmKey);
                            manager.deleteCrate(crateId);
                            p.sendMessage(Text.legacy("&aUsunięto &f" + crateId));
                            p.closeInventory();
                            plugin.getServer().getScheduler().runTask(plugin,
                                    () -> guiManager.openGUI(new ManageGUI(plugin, guiManager, manager), p));
                        } else if (event.isLeftClick()) {
                            CrateDefinition c = manager.getCrate(crateId);
                            if (c != null) {
                                guiManager.openGUI(new EditorGUI(plugin, guiManager, manager, c), p);
                            }
                        } else if (event.isRightClick()) {
                            CrateDefinition c = manager.getCrate(crateId);
                            if (c != null) {
                                guiManager.openGUI(new PreviewGUI(plugin, guiManager, manager, c), p);
                            }
                        }
                    }));
        }

        addButton(49, GuiStyle.button(Material.BARRIER, "&cZamknij",
                e -> e.getWhoClicked().closeInventory()));
        super.decorate(player);
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        super.onClick(event);
    }
}
