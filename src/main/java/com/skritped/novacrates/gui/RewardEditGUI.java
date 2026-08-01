package com.skritped.novacrates.gui;

import com.skritped.novacrates.NovaCratesPlugin;
import com.skritped.novacrates.manager.CrateManager;
import com.skritped.novacrates.model.CrateDefinition;
import com.skritped.novacrates.model.RewardDefinition;
import com.skritped.novacrates.service.RewardSelector;
import com.skritped.novacrates.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;

/** Simple reward settings panel — via FancyDialogs / Paper Dialogs or chat. */
public class RewardEditGUI extends InventoryGUI {
    private static final String[] RARITIES = {
            "COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC"
    };

    private final JavaPlugin plugin;
    private final GUIManager guiManager;
    private final CrateManager manager;
    private final String crateId;
    private final String rewardId;

    private double weight;
    private Double displayChance;
    private String rarity;
    private boolean broadcast;
    private int amount;

    public RewardEditGUI(JavaPlugin plugin, GUIManager guiManager, CrateManager manager,
                         String crateId, String rewardId) {
        this.plugin = plugin;
        this.guiManager = guiManager;
        this.manager = manager;
        this.crateId = crateId;
        this.rewardId = rewardId;
        RewardDefinition r = findReward();
        if (r != null) {
            this.weight = r.getChance();
            this.displayChance = r.getDisplayChance();
            this.rarity = r.getRarity() == null ? "COMMON" : r.getRarity();
            this.broadcast = r.isBroadcast();
            this.amount = Math.max(1, r.getAmount());
        } else {
            this.weight = 1;
            this.rarity = "COMMON";
            this.amount = 1;
        }
    }

    private RewardDefinition findReward() {
        CrateDefinition c = manager.getCrate(crateId);
        if (c == null) return null;
        return c.getRewards().stream().filter(r -> r.getId().equals(rewardId)).findFirst().orElse(null);
    }

    private DialogService dialogs() {
        return ((NovaCratesPlugin) plugin).getDialogService();
    }

    private AnvilInputService anvil() {
        return ((NovaCratesPlugin) plugin).getAnvilInput();
    }

    @Override
    protected Inventory createInventory() {
        return Bukkit.createInventory(null, 45, Text.legacy("&8Ustawienia nagrody"));
    }

    @Override
    public void decorate(Player player) {
        clearButtons();
        getInventory().clear();

        ItemStack border = GuiStyle.pane(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 45; i++) getInventory().setItem(i, border.clone());

        RewardDefinition r = findReward();
        CrateDefinition c = manager.getCrate(crateId);
        double pct = displayChance != null ? displayChance
                : (r != null && c != null ? RewardSelector.normalizedPercent(r, c, player) : 0);

        // Preview of item
        if (r != null) {
            ItemStack icon = manager.getItemFactory().create(r);
            addButton(4, GuiStyle.button(icon, e -> {}));
        }

        addButton(19, GuiStyle.button(Material.GOLD_INGOT,
                manager.getMessages().gui("gui-reward-weight", java.util.Map.of("value", fmt(weight))),
                e -> {
                    Player p = (Player) e.getWhoClicked();
                    p.closeInventory();
                    final double cur = weight;
                    plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                        if (!p.isOnline()) return;
                        // Anvil/chat — reliable: set value → persist (jak Zapisz) → reopen GUI
                        anvil().openNumberAnvil(p, "Waga dropu", cur, v -> {
                            weight = Math.max(0.5, Math.min(500.0, v));
                            persist();
                            plugin.getLogger().info("[Editor] weight=" + weight + " reward=" + rewardId);
                            reopen(p);
                        }, () -> reopen(p));
                    }, 1L);
                },
                "&7Kliknij, aby zmienić wagę",
                "&8Kowadło / chat → zapis automatyczny"));

        addButton(21, GuiStyle.button(Material.EXPERIENCE_BOTTLE,
                manager.getMessages().gui("gui-reward-chance", java.util.Map.of("value",
                        String.format(Locale.US, "%.1f", pct))),
                e -> {
                    Player p = (Player) e.getWhoClicked();
                    p.closeInventory();
                    final double curPct = pct;
                    plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                        if (!p.isOnline()) return;
                        anvil().openNumberAnvil(p, "Szansa %", curPct, v -> {
                            displayChance = Math.max(0, Math.min(100, v));
                            persist();
                            reopen(p);
                        }, () -> reopen(p));
                    }, 1L);
                },
                "&7Wyświetlana szansa w %",
                "&8Kowadło / chat → zapis automatyczny"));

        addButton(23, GuiStyle.button(Material.IRON_INGOT,
                "&f&lIlość: &e" + amount,
                e -> {
                    Player p = (Player) e.getWhoClicked();
                    p.closeInventory();
                    final int curAmt = amount;
                    plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                        if (!p.isOnline()) return;
                        anvil().openIntegerAnvil(p, "Ilość", curAmt, v -> {
                            amount = Math.max(1, Math.min(64, v));
                            persistAmount();
                            reopen(p);
                        }, () -> reopen(p));
                    }, 1L);
                },
                "&7Stack size 1–64",
                "&8Kowadło / chat → zapis automatyczny"));

        addButton(25, GuiStyle.button(Material.NAME_TAG,
                "&d&lRarity: &f" + rarity,
                e -> {
                    int idx = 0;
                    for (int i = 0; i < RARITIES.length; i++) {
                        if (RARITIES[i].equalsIgnoreCase(rarity)) { idx = i; break; }
                    }
                    rarity = RARITIES[(idx + 1) % RARITIES.length];
                    persist();
                    decorate(player);
                },
                "&7Kliknij, aby zmienić rarity"));

        addButton(29, GuiStyle.button(broadcast ? Material.LIME_DYE : Material.GRAY_DYE,
                broadcast ? "&a&lBroadcast: ON" : "&7&lBroadcast: OFF",
                e -> {
                    broadcast = !broadcast;
                    persist();
                    decorate(player);
                },
                "&7Ogłoszenie przy dropie"));

        addButton(31, GuiStyle.button(Material.BOOK,
                "&6&lKomendy",
                e -> {
                    Player p = (Player) e.getWhoClicked();
                    guiManager.openGUI(new CommandEditGUI(plugin, guiManager, manager, crateId, rewardId), p);
                },
                "&7Edytuj komendy nagrody"));

        addButton(40, GuiStyle.button(Material.ARROW, "&7« Powrót do edytora", e -> {
            Player p = (Player) e.getWhoClicked();
            CrateDefinition crate = manager.getCrate(crateId);
            if (crate != null) {
                guiManager.openGUI(new EditorGUI(plugin, guiManager, manager, crate), p);
            } else {
                p.closeInventory();
            }
        }));

        addButton(36, GuiStyle.button(Material.LIME_CONCRETE, "&a&lZapisz i wróć", e -> {
            persist();
            Player p = (Player) e.getWhoClicked();
            CrateDefinition crate = manager.getCrate(crateId);
            if (crate != null) {
                guiManager.openGUI(new EditorGUI(plugin, guiManager, manager, crate), p);
            }
        }));

        super.decorate(player);
    }

    private static String fmt(double v) {
        if (v == Math.rint(v)) return String.valueOf((long) v);
        return String.format(Locale.US, "%.2f", v);
    }

    private void persist() {
        manager.updateRewardFields(crateId, rewardId, weight, displayChance, rarity, broadcast);
    }

    private void persistAmount() {
        // update via rebuild of reward amount through manager if available
        try {
            java.lang.reflect.Method m = manager.getClass().getMethod(
                    "updateRewardAmount", String.class, String.class, int.class);
            m.invoke(manager, crateId, rewardId, amount);
        } catch (Throwable t) {
            // fallback: only weight/chance fields
            persist();
        }
    }

    private void reopen(Player p) {
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (p == null || !p.isOnline()) return;
            guiManager.openGUI(new RewardEditGUI(plugin, guiManager, manager, crateId, rewardId), p);
        }, 2L);
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        super.onClick(event);
    }
}
