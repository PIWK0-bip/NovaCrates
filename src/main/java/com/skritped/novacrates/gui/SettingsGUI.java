package com.skritped.novacrates.gui;

import com.skritped.novacrates.manager.CrateManager;
import com.skritped.novacrates.service.MessageService;
import com.skritped.novacrates.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Map;

/** In-game settings for common config.yml toggles. */
public class SettingsGUI extends InventoryGUI {
    private final JavaPlugin plugin;
    private final GUIManager guiManager;
    private final CrateManager manager;

    public SettingsGUI(JavaPlugin plugin, CrateManager manager) {
        this(plugin, null, manager);
    }

    public SettingsGUI(JavaPlugin plugin, GUIManager guiManager, CrateManager manager) {
        this.plugin = plugin;
        this.guiManager = guiManager;
        this.manager = manager;
    }

    private MessageService msg() {
        return manager.getMessages();
    }

    private FileConfiguration cfg() {
        return plugin.getConfig();
    }

    @Override
    protected Inventory createInventory() {
        return Bukkit.createInventory(null, 54, msg().gui("gui-settings-title"));
    }

    @Override
    public void decorate(Player player) {
        clearButtons();
        getInventory().clear();
        GuiStyle.drawBorder54(getInventory());

        String lang = msg().getLanguage();
        addButton(10, GuiStyle.button(Material.BOOK,
                msg().raw("gui-settings-lang").replace("%lang%", lang.toUpperCase()),
                e -> {
                    String next = lang.equalsIgnoreCase("pl") ? "en" : "pl";
                    msg().setLanguage(next);
                    manager.getMessages().send(player, "language-changed", Map.of("lang", next));
                    // reopen with new language
                    player.closeInventory();
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        SettingsGUI reopened = new SettingsGUI(plugin, guiManager, manager);
                        if (guiManager != null) {
                            guiManager.openGUI(reopened, player);
                        } else {
                            reopened.openFresh(player);
                        }
                    });
                },
                msg().raw("gui-settings-lang-lore")));

        toggle(12, Material.ENDER_EYE, "gui-settings-preview", "settings.preview-before-open", true);
        toggle(14, Material.REDSTONE, "gui-settings-debug", "settings.debug", false);
        toggle(16, Material.CHEST, "gui-settings-multi-confirm", "settings.multi-open-confirm", true);

        // Animation cycle
        String anim = cfg().getString("settings.default-animation", "HOLOGRAM");
        addButton(28, GuiStyle.button(Material.ARMOR_STAND,
                msg().raw("gui-settings-anim").replace("%value%", anim),
                e -> {
                    String[] opts = {"HOLOGRAM", "CHEST", "CSGO", "WHEEL", "SLOTS", "NONE"};
                    int idx = 0;
                    for (int i = 0; i < opts.length; i++) {
                        if (opts[i].equalsIgnoreCase(anim)) { idx = i; break; }
                    }
                    String next = opts[(idx + 1) % opts.length];
                    cfg().set("settings.default-animation", next);
                    plugin.saveConfig();
                    manager.getMessages().send(player, "settings-toggled", Map.of("key", "animation", "value", next));
                    decorate(player);
                },
                "&7Click to cycle"));

        String backend = cfg().getString("settings.hologram-backend", "auto");
        addButton(30, GuiStyle.button(Material.END_CRYSTAL,
                "&eHologramy: &f" + backend,
                e -> {
                    String[] opts = {"auto", "fancy", "decent", "armorstand"};
                    int idx = 0;
                    for (int i = 0; i < opts.length; i++) {
                        if (opts[i].equalsIgnoreCase(cfg().getString("settings.hologram-backend", "auto"))) {
                            idx = i; break;
                        }
                    }
                    String next = opts[(idx + 1) % opts.length];
                    cfg().set("settings.hologram-backend", next);
                    plugin.saveConfig();
                    manager.getMessages().send(player, "settings-toggled",
                            java.util.Map.of("key", "hologram-backend", "value", next));
                    decorate(player);
                },
                "&7auto / fancy / decent / armorstand"));
        toggle(32, Material.BLAZE_POWDER, "gui-settings-particles", "settings.particles-enabled", true);

        // Save indicator
        getInventory().setItem(40, GuiStyle.named(Material.EMERALD_BLOCK,
                msg().raw("gui-settings-saved"),
                "&7Zmiany zapisują się od razu do config.yml",
                "&7/crates reload — pełny reload usług"));

        addButton(49, GuiStyle.button(Material.BARRIER, msg().raw("gui-close"),
                e -> e.getWhoClicked().closeInventory()));

        super.decorate(player);
    }

    private void toggle(int slot, Material mat, String msgKey, String configPath, boolean def) {
        boolean val = cfg().getBoolean(configPath, def);
        String label = msg().raw(msgKey).replace("%value%", val ? "ON" : "OFF");
        addButton(slot, GuiStyle.button(mat, label, e -> {
            boolean next = !cfg().getBoolean(configPath, def);
            cfg().set(configPath, next);
            plugin.saveConfig();
            Player p = (Player) e.getWhoClicked();
            manager.getMessages().send(p, "settings-toggled", Map.of("key", configPath, "value", String.valueOf(next)));
            decorate(p);
        }, "&7Kliknij aby przełączyć"));
    }

    /** Open via GUIManager-compatible path without circular dep. */
    public void openFresh(Player player) {
        // Direct open when GUIManager not passed
        Inventory inv = createInventory();
        // Hack: use reflection-free approach via temporary GUIManager open
        try {
            var field = InventoryGUI.class.getDeclaredField("inventory");
            field.setAccessible(true);
            field.set(this, inv);
        } catch (Exception ignored) {}
        player.openInventory(getInventory());
        decorate(player);
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        super.onClick(event);
    }
}
