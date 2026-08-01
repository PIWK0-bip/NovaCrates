package com.skritped.novacrates.gui;

import com.skritped.novacrates.manager.CrateManager;
import com.skritped.novacrates.model.CrateDefinition;
import com.skritped.novacrates.model.RewardDefinition;
import com.skritped.novacrates.service.RewardSelector;
import com.skritped.novacrates.util.Text;
import com.cryptomorin.xseries.XMaterial;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Main crate editor: slots 0–44 = rewards, 45–53 = control panel.
 * Right-click a reward to open detailed chance/rarity editor.
 */
public class EditorGUI extends InventoryGUI {
    private static final Set<InventoryAction> BLOCKED = EnumSet.of(
            InventoryAction.MOVE_TO_OTHER_INVENTORY,
            InventoryAction.COLLECT_TO_CURSOR,
            InventoryAction.HOTBAR_SWAP
    );

    private final JavaPlugin plugin;
    private final GUIManager guiManager;
    private final CrateManager manager;
    private final String crateId;
    private boolean skipSave;

    public EditorGUI(JavaPlugin plugin, GUIManager guiManager, CrateManager manager, CrateDefinition crate) {
        this.plugin = plugin;
        this.guiManager = guiManager;
        this.manager = manager;
        this.crateId = crate.getId();
    }

    /** Back-compat for call sites without GUIManager */
    public EditorGUI(JavaPlugin plugin, CrateManager manager, CrateDefinition crate) {
        this(plugin, null, manager, crate);
    }

    private CrateDefinition crate() {
        CrateDefinition c = manager.getCrate(crateId);
        return c != null ? c : manager.getCrate(crateId);
    }

    @Override
    protected Inventory createInventory() {
        CrateDefinition c = crate();
        String title = c != null ? c.getDisplayName() : crateId;
        return Bukkit.createInventory(null, 54, Text.legacy("&8✦ Edytor &7» &f" + (title == null ? crateId : title)));
    }

    @Override
    public void decorate(Player player) {
        clearButtons();
        getInventory().clear();
        CrateDefinition c = crate();
        if (c == null) {
            return;
        }


        // Bottom control bar — clean look
        ItemStack bar = pane(Material.BLACK_STAINED_GLASS_PANE, " ");
        ItemStack accent = pane(Material.CYAN_STAINED_GLASS_PANE, " ");
        for (int slot = 45; slot < 54; slot++) {
            getInventory().setItem(slot, bar.clone());
        }
        getInventory().setItem(45, accent.clone());
        getInventory().setItem(53, accent.clone());

        EditorSession.Session session = EditorSession.getOrCreate(player.getUniqueId(), crateId);
        boolean hasWorking = false;
        for (ItemStack it : session.slots) {
            if (it != null) { hasWorking = true; break; }
        }
        if (hasWorking) {
            session.applyTo(getInventory());
            for (int si = 0; si < 45; si++) {
                ItemStack it = getInventory().getItem(si);
                if (it == null || it.getType().isAir()) continue;
                RewardDefinition reward = resolveReward(it, c);
                if (reward != null) decorateRewardIcon(it, reward, c, player);
            }
        } else {
            int i = 0;
            for (RewardDefinition reward : c.getRewards()) {
                if (i >= 45) break;
                ItemStack item = manager.createRewardItem(reward);
                decorateRewardIcon(item, reward, c, player);
                getInventory().setItem(i++, item);
            }
            session.captureFrom(getInventory());
        }

        // Re-paint bar after session apply
        for (int slot = 45; slot < 54; slot++) {
            getInventory().setItem(slot, bar.clone());
        }
        getInventory().setItem(45, accent.clone());
        getInventory().setItem(53, accent.clone());

        addButton(46, button(Material.BOOK, "&bℹ Podsumowanie", summaryLore(c, player), e -> {}));

        addButton(47, button(Material.HOPPER, "&eWstaw z ręki", List.of(
                "&7Wkłada trzymany item",
                "&7do pierwszego wolnego slotu"
        ), e -> {
            Player pl = (Player) e.getWhoClicked();
            ItemStack hand = pl.getInventory().getItemInMainHand();
            if (hand == null || hand.getType().isAir()) {
                pl.sendMessage(Text.legacy("&cTrzymasz powietrze."));
                return;
            }
            for (int s = 0; s < 45; s++) {
                ItemStack cur = getInventory().getItem(s);
                if (cur == null || cur.getType().isAir()) {
                    getInventory().setItem(s, hand.clone());
                    EditorSession.getOrCreate(pl.getUniqueId(), crateId).captureFrom(getInventory());
                    pl.sendMessage(Text.legacy("&aWstawiono do slotu &f" + (s + 1)));
                    return;
                }
            }
            pl.sendMessage(Text.legacy("&cBrak wolnego slotu."));
        }));

        addButton(48, button(Material.STRUCTURE_VOID, "&e↩ Undo", List.of(
                "&7Cofnij ostatni układ"
        ), e -> {
            Player pl = (Player) e.getWhoClicked();
            EditorSession.Session s = EditorSession.getOrCreate(pl.getUniqueId(), crateId);
            if (s.restoreUndo()) {
                s.applyTo(getInventory());
                // repaint bar
                for (int slot = 45; slot < 54; slot++) getInventory().setItem(slot, bar.clone());
                getInventory().setItem(45, accent.clone());
                getInventory().setItem(53, accent.clone());
                pl.sendMessage(Text.legacy("&aPrzywrócono."));
            } else {
                pl.sendMessage(Text.legacy("&cBrak snapshotu undo."));
            }
        }));

        addButton(49, button(Material.LIME_CONCRETE, "&a&l✔ ZAPISZ", List.of(
                "&7Zapisuje nagrody do crates.yml",
                "&aKliknij aby zapisać i zamknąć"
        ), e -> {
            skipSave = false;
            int count = countRewardSlots();
            Player pl = (Player) e.getWhoClicked();
            EditorSession.getOrCreate(pl.getUniqueId(), crateId).captureFrom(getInventory());
            manager.getCrateRepository().backupBeforeSave();
            saveFromInventory(pl);
            EditorSession.clear(pl.getUniqueId());
            skipSave = true;
            pl.closeInventory();
            manager.getMessages().send(pl, "editor-saved",
                    java.util.Map.of("count", String.valueOf(count), "crate", crateId));
        }));

        addButton(50, button(Material.RED_CONCRETE, "&c✖ Anuluj", List.of(
                "&7Zamyka bez zapisu"
        ), e -> {
            skipSave = true;
            e.getWhoClicked().closeInventory();
        }));

        addButton(51, button(Material.ENDER_CHEST, "&dPodgląd", List.of(
                "&7Preview nagród i %"
        ), e -> {
            skipSave = true;
            saveFromInventory((Player) e.getWhoClicked());
            Player p = (Player) e.getWhoClicked();
            CrateDefinition latest = manager.getCrate(crateId);
            if (latest != null && guiManager != null) {
                plugin.getServer().getScheduler().runTask(plugin, () ->
                        guiManager.openGUI(new PreviewGUI(plugin, guiManager, manager, latest), p));
            }
        }));

        addButton(52, button(Material.BARRIER, "&cWyczyść sloty", List.of(
                "&cUsuwa wszystkie itemy z edytora",
                "&7(zapisz, żeby utrwalić)"
        ), e -> {
            Player pl = (Player) e.getWhoClicked();
            for (int s = 0; s < 45; s++) getInventory().setItem(s, null);
            EditorSession.getOrCreate(pl.getUniqueId(), crateId).captureFrom(getInventory());
            pl.sendMessage(Text.legacy("&eWyczyszczono sloty (Zapisz = usunięcie z crates.yml)."));
        }));

        super.decorate(player);
    }

    private List<String> summaryLore(CrateDefinition c, Player player) {
        List<String> lore = new ArrayList<>();
        lore.add("&7Crate: &f" + c.getId());
        lore.add("&7Nagród: &f" + c.getRewards().size());
        double total = 0;
        for (RewardDefinition r : c.getRewards()) {
            total += Math.max(0, r.getChance());
        }
        lore.add("&7Suma wag: &f" + String.format(Locale.US, "%.2f", total));
        lore.add("");
        lore.add("&8Top szanse:");
        c.getRewards().stream()
                .sorted((a, b) -> Double.compare(b.getChance(), a.getChance()))
                .limit(5)
                .forEach(r -> {
                    double pct = RewardSelector.normalizedPercent(r, c, player);
                    lore.add("&7• &f" + Text.strip(r.getDisplayName())
                            + " &8— &d" + String.format(Locale.US, "%.1f%%", pct)
                            + " &7(w=" + r.getChance() + ")");
                });
        return lore;
    }

    /** True if lore line is editor metadata (must not be saved into reward). */
    private static boolean isEditorMetaLine(String line) {
        if (line == null) return true;
        String s = Text.strip(line);
        if (s.isEmpty() || s.startsWith("──") || s.startsWith("---") || s.startsWith("─")) return true;
        String low = s.toLowerCase(java.util.Locale.ROOT);
        return s.startsWith("Weight:") || s.startsWith("Chance:") || s.startsWith("Rarity:")
                || s.startsWith("Waga:") || s.startsWith("Szansa:")
                || s.startsWith("Broadcast:") || s.startsWith("Komendy:")
                || s.startsWith("Commands:") || s.startsWith("ID:")
                || low.startsWith("ppm") || low.startsWith("lpm") || low.startsWith("śpm") || low.startsWith("spm")
                || low.startsWith("q —") || low.startsWith("q -") || low.startsWith("q–")
                || s.contains("Prawy klik") || s.contains("Right-click")
                || s.contains("edytuj szans") || s.contains("ŚPM") || s.contains("Shift+PPM")
                || s.contains("ustaw wagę") || s.contains("szansa %") || s.contains("szansa / rarity")
                || low.contains("display)") || low.contains("(display)")
                || s.contains("Kliknij") || low.startsWith("waga:");
    }

    private void decorateRewardIcon(ItemStack item, RewardDefinition reward, CrateDefinition c, Player player) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        List<String> lore = new ArrayList<>();
        if (reward.getLore() != null) {
            for (String line : reward.getLore()) {
                if (!isEditorMetaLine(line)) lore.add(Text.legacy(line));
            }
        }
        double pct = reward.getDisplayChance() != null
                ? reward.getDisplayChance()
                : RewardSelector.normalizedPercent(reward, c, player);
        lore.add(Text.legacy("&8────────────"));
        lore.add(Text.legacy("&7ID: &f" + reward.getId()));
        lore.add(Text.legacy("&7Waga: &e" + reward.getChance()));
        lore.add(Text.legacy("&7Szansa: &d" + String.format(java.util.Locale.US, "%.2f%%", pct)));
        if (reward.getRarity() != null) {
            lore.add(Text.legacy("&7Rarity: &b" + reward.getRarity()));
        }
        lore.add(Text.legacy("&ePPM &7— ustawienia"));
        lore.add(Text.legacy("&7LPM &7— przenieś / usuń"));
        meta.setLore(lore);
        item.setItemMeta(meta);
    
    }

    /** Remove editor footer before persisting item as reward. */
    public static ItemStack stripEditorLore(ItemStack source) {
        if (source == null) return null;
        ItemStack clone = source.clone();
        ItemMeta meta = clone.getItemMeta();
        if (meta == null || !meta.hasLore() || meta.getLore() == null) return clone;
        List<String> cleaned = new ArrayList<>();
        for (String line : meta.getLore()) {
            if (!isEditorMetaLine(line)) {
                cleaned.add(line);
            }
        }
        // drop trailing empty
        while (!cleaned.isEmpty() && Text.strip(cleaned.get(cleaned.size() - 1)).isEmpty()) {
            cleaned.remove(cleaned.size() - 1);
        }
        meta.setLore(cleaned.isEmpty() ? null : cleaned);
        clone.setItemMeta(meta);
        return clone;
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        int raw = event.getRawSlot();
        // Control panel
        if (raw >= 45 && raw < 54) {
            event.setCancelled(true);
            super.onClick(event);
            return;
        }
        if (BLOCKED.contains(event.getAction())) {
            event.setCancelled(true);
            return;
        }
        // Middle-click → amount via Anvil
        if (raw >= 0 && raw < 45 && event.getClick().name().contains("MIDDLE")
                && event.getClickedInventory() == event.getView().getTopInventory()) {
            event.setCancelled(true);
            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || clicked.getType().isAir()) return;
            Player player = (Player) event.getWhoClicked();
            final int slot = raw;
            final ItemStack base = clicked.clone();
            EditorSession.getOrCreate(player.getUniqueId(), crateId).captureFrom(getInventory());
            player.closeInventory();
            var dialogs = ((com.skritped.novacrates.NovaCratesPlugin) plugin).getDialogService();
            dialogs.askInteger(player, "Ilość itemu", base.getAmount(), amount -> {
                int a = Math.max(1, Math.min(64, amount));
                base.setAmount(a);
                EditorSession.Session ses = EditorSession.getOrCreate(player.getUniqueId(), crateId);
                if (slot < ses.slots.size()) ses.slots.set(slot, base.clone());
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    skipSave = true;
                    GUIManager gm = guiManager != null ? guiManager
                            : ((com.skritped.novacrates.NovaCratesPlugin) plugin).getGuiManager();
                    gm.openGUI(new EditorGUI(plugin, gm, manager, manager.getCrate(crateId)), player);
                });
            }, () -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                GUIManager gm = guiManager != null ? guiManager
                        : ((com.skritped.novacrates.NovaCratesPlugin) plugin).getGuiManager();
                gm.openGUI(new EditorGUI(plugin, gm, manager, manager.getCrate(crateId)), player);
            }));
            return;
        }

        // Shift+Right-click → rename display via Anvil
        if (raw >= 0 && raw < 45 && event.isRightClick() && event.isShiftClick()
                && event.getClickedInventory() == event.getView().getTopInventory()) {
            event.setCancelled(true);
            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || clicked.getType().isAir()) return;
            Player player = (Player) event.getWhoClicked();
            final int slot = raw;
            final ItemStack base = clicked.clone();
            String curName = base.hasItemMeta() && base.getItemMeta().hasDisplayName()
                    ? base.getItemMeta().getDisplayName() : base.getType().name();
            player.closeInventory();
            var dialogs = ((com.skritped.novacrates.NovaCratesPlugin) plugin).getDialogService();
            dialogs.askText(player, "Nazwa wyświetlana", curName.replaceAll("§.", ""), name -> {
                ItemMeta meta = base.getItemMeta();
                if (meta != null) {
                    meta.setDisplayName(Text.legacy(name));
                    base.setItemMeta(meta);
                }
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    GUIManager gm = guiManager != null ? guiManager
                            : ((com.skritped.novacrates.NovaCratesPlugin) plugin).getGuiManager();
                    EditorGUI editor = new EditorGUI(plugin, gm, manager, manager.getCrate(crateId));
                    gm.openGUI(editor, player);
                    plugin.getServer().getScheduler().runTask(plugin, () ->
                            editor.getInventory().setItem(slot, base));
                });
            }, () -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                GUIManager gm = guiManager != null ? guiManager
                        : ((com.skritped.novacrates.NovaCratesPlugin) plugin).getGuiManager();
                gm.openGUI(new EditorGUI(plugin, gm, manager, manager.getCrate(crateId)), player);
            }));
            return;
        }

                // ONLY right-click (no shift) → settings GUI
        // Left-click keeps pick/place/remove working
        if (raw >= 0 && raw < 45 && event.isRightClick() && !event.isShiftClick()
                && event.getClickedInventory() == event.getView().getTopInventory()) {
            ItemStack clicked = event.getCurrentItem();
            if (clicked != null && !clicked.getType().isAir()) {
                event.setCancelled(true);
                Player player = (Player) event.getWhoClicked();
                skipSave = true;
                EditorSession.getOrCreate(player.getUniqueId(), crateId).captureFrom(getInventory());
                saveFromInventory(player);
                CrateDefinition c = manager.getCrate(crateId);
                if (c == null) return;
                RewardDefinition reward = resolveReward(clicked, c);
                if (reward == null) {
                    player.sendMessage(Text.legacy("&cNajpierw &aZAPISZ&c (zielony), potem PPM = ustawienia."));
                    return;
                }
                GUIManager gm = guiManager != null ? guiManager
                        : ((com.skritped.novacrates.NovaCratesPlugin) plugin).getGuiManager();
                plugin.getServer().getScheduler().runTask(plugin, () ->
                        gm.openGUI(new RewardEditGUI(plugin, gm, manager, crateId, reward.getId()), player));
                return;
            }
        }

// Allow placing/taking in reward slots
        if (raw >= 0 && raw < 45 && event.getClickedInventory() == event.getView().getTopInventory()) {
            return;
        }
        if (raw >= 54) {
            return; // player inv
        }
        event.setCancelled(true);
    }

    private RewardDefinition resolveReward(ItemStack item, CrateDefinition c) {
        return manager.getItemFactory().readRewardId(item)
                .flatMap(id -> c.getRewards().stream().filter(r -> r.getId().equals(id)).findFirst())
                .orElse(null);
    }

    @Override
    public void onClose(InventoryCloseEvent event) {
        if (skipSave) {
            return;
        }
        if (event.getPlayer() instanceof Player player) {
            saveFromInventory(player);
        }
    }


    private int countRewardSlots() {
        int n = 0;
        for (int i = 0; i < 45; i++) {
            var item = getInventory().getItem(i);
            if (item != null && !item.getType().isAir()) {
                n++;
            }
        }
        return n;
    }

    private void saveFromInventory(Player player) {
        List<ItemStack> items = new ArrayList<>();
        List<String> blacklist = plugin.getConfig().getStringList("settings.editor-material-blacklist");
        for (int slot = 0; slot < 45; slot++) {
            ItemStack item = getInventory().getItem(slot);
            if (item == null || item.getType().isAir()) {
                continue;
            }
            boolean blocked = false;
            if (blacklist != null) {
                for (String b : blacklist) {
                    if (item.getType().name().equalsIgnoreCase(b)) {
                        blocked = true;
                        break;
                    }
                }
            }
            if (!blocked) {
                items.add(stripEditorLore(item));
            }
        }
        manager.replaceEditorRewards(crateId, items);
    }

    private static InventoryButton button(Material mat, String name, List<String> lore,
                                          java.util.function.Consumer<InventoryClickEvent> click) {
        return new InventoryButton()
                .creator(p -> {
                    ItemStack item = new ItemStack(mat);
                    ItemMeta meta = item.getItemMeta();
                    if (meta != null) {
                        meta.setDisplayName(Text.legacy(name));
                        meta.setLore(lore.stream().map(Text::legacy).toList());
                        item.setItemMeta(meta);
                    }
                    return item;
                })
                .consumer(click);
    }

    private static ItemStack pane(Material mat, String name) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(Text.legacy(name));
            item.setItemMeta(meta);
        }
        return item;
    }

    private static String format(double v) {
        if (v == (long) v) {
            return String.valueOf((long) v);
        }
        return String.format(Locale.US, "%.2f", v);
    }

    private static String rarityColor(String rarity) {
        if (rarity == null) {
            return "&7";
        }
        return switch (rarity.toUpperCase(Locale.ROOT)) {
            case "UNCOMMON" -> "&a";
            case "RARE" -> "&b";
            case "EPIC" -> "&d";
            case "LEGENDARY" -> "&6";
            case "MYTHIC" -> "&c&l";
            default -> "&7";
        };
    }
}
