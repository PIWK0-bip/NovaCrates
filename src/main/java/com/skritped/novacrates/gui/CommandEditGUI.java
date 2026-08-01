package com.skritped.novacrates.gui;

import com.skritped.novacrates.manager.CrateManager;
import com.skritped.novacrates.model.CrateDefinition;
import com.skritped.novacrates.model.RewardDefinition;
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

public class CommandEditGUI extends InventoryGUI {
    private final JavaPlugin plugin;
    private final GUIManager guiManager;
    private final CrateManager manager;
    private final String crateId;
    private final String rewardId;
    private final List<String> commands = new ArrayList<>();

    public CommandEditGUI(JavaPlugin plugin, GUIManager guiManager, CrateManager manager,
                          String crateId, String rewardId) {
        this.plugin = plugin;
        this.guiManager = guiManager;
        this.manager = manager;
        this.crateId = crateId;
        this.rewardId = rewardId;
        RewardDefinition r = find();
        if (r != null) {
            commands.addAll(r.getCommands());
        }
    }

    private RewardDefinition find() {
        CrateDefinition c = manager.getCrate(crateId);
        if (c == null) {
            return null;
        }
        return c.getRewards().stream().filter(x -> x.getId().equals(rewardId)).findFirst().orElse(null);
    }

    private void reopen(Player player) {
        Bukkit.getScheduler().runTask(plugin, () ->
                guiManager.openGUI(new CommandEditGUI(plugin, guiManager, manager, crateId, rewardId), player));
    }

    @Override
    protected Inventory createInventory() {
        return Bukkit.createInventory(null, 54, Text.legacy("&8Komendy &7» &f" + rewardId));
    }

    @Override
    public void decorate(Player player) {
        clearButtons();
        getInventory().clear();
        int slot = 0;
        for (int i = 0; i < commands.size() && slot < 45; i++) {
            final int idx = i;
            String cmd = commands.get(i);
            addButton(slot++, new InventoryButton().creator(p -> {
                ItemStack it = new ItemStack(Material.COMMAND_BLOCK);
                ItemMeta m = it.getItemMeta();
                if (m != null) {
                    m.setDisplayName(Text.legacy("&e#" + (idx + 1)));
                    m.setLore(List.of(
                            Text.legacy("&f/" + cmd),
                            Text.legacy("&cPPM: usuń"),
                            Text.legacy("&aLPM: edytuj")
                    ));
                    it.setItemMeta(m);
                }
                return it;
            }).consumer(e -> {
                if (e.isRightClick()) {
                    if (idx < commands.size()) {
                        commands.remove(idx);
                        manager.updateRewardCommands(crateId, rewardId, new ArrayList<>(commands));
                    }
                    decorate(player);
                } else {
                    player.closeInventory();
                    ((com.skritped.novacrates.NovaCratesPlugin) plugin).getAnvilInput().open(
                            player, "Komenda (bez /)", cmd, false,
                            text -> {
                                String c = text.startsWith("/") ? text.substring(1) : text;
                                if (idx < commands.size()) {
                                    commands.set(idx, c);
                                }
                                manager.updateRewardCommands(crateId, rewardId, new ArrayList<>(commands));
                                reopen(player);
                            },
                            () -> reopen(player)
                    );
                }
            }));
        }
        addButton(45, new InventoryButton().creator(p -> named(Material.EMERALD, "&a+ Dodaj komendę")).consumer(e -> {
            player.closeInventory();
            ((com.skritped.novacrates.NovaCratesPlugin) plugin).getAnvilInput().open(
                    player, "Nowa komenda (bez /)", "give %player% diamond 1", false,
                    text -> {
                        String c = text.startsWith("/") ? text.substring(1) : text;
                        commands.add(c);
                        manager.updateRewardCommands(crateId, rewardId, new ArrayList<>(commands));
                        reopen(player);
                    },
                    () -> reopen(player)
            );
        }));
        addButton(49, new InventoryButton().creator(p -> named(Material.LIME_CONCRETE, "&aZapisz i wróć")).consumer(e -> {
            manager.updateRewardCommands(crateId, rewardId, new ArrayList<>(commands));
            player.closeInventory();
            Bukkit.getScheduler().runTask(plugin, () ->
                    guiManager.openGUI(new RewardEditGUI(plugin, guiManager, manager, crateId, rewardId), player));
        }));
        addButton(53, new InventoryButton().creator(p -> named(Material.BARRIER, "&cWróć")).consumer(e -> {
            player.closeInventory();
            Bukkit.getScheduler().runTask(plugin, () ->
                    guiManager.openGUI(new RewardEditGUI(plugin, guiManager, manager, crateId, rewardId), player));
        }));
        super.decorate(player);
    }

    private static ItemStack named(Material mat, String name) {
        ItemStack it = new ItemStack(mat);
        ItemMeta m = it.getItemMeta();
        if (m != null) {
            m.setDisplayName(Text.legacy(name));
            it.setItemMeta(m);
        }
        return it;
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        super.onClick(event);
    }
}
