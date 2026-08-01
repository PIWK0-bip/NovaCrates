package com.skritped.novacrates.gui;

import com.skritped.novacrates.util.Text;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * Shared pretty GUI helpers — borders, fillers, buttons.
 */
public final class GuiStyle {
    private GuiStyle() {}

    public static final Material BORDER = Material.GRAY_STAINED_GLASS_PANE;
    public static final Material BORDER_ACCENT = Material.BLACK_STAINED_GLASS_PANE;
    public static final Material FILLER = Material.GRAY_STAINED_GLASS_PANE;

    /** Content slots for 54-slot GUI (rows 2-4, cols 2-8) = 21 slots */
    public static final int[] CONTENT_54 = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34
    };

    /** Content slots for 27-slot GUI (middle row + partial) */
    public static final int[] CONTENT_27 = {
            10, 11, 12, 13, 14, 15, 16
    };

    public static ItemStack pane(Material mat, String name) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(Text.legacy(name == null || name.isBlank() ? " " : name));
            item.setItemMeta(meta);
        }
        return item;
    }

    public static ItemStack filler() {
        return pane(FILLER, " ");
    }

    public static ItemStack border() {
        return pane(BORDER, " ");
    }

    public static ItemStack accent() {
        return pane(BORDER_ACCENT, " ");
    }

    public static ItemStack named(Material mat, String name, String... loreLines) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(Text.legacy(name));
            if (loreLines != null && loreLines.length > 0) {
                List<String> lore = new ArrayList<>();
                for (String l : loreLines) {
                    if (l != null) lore.add(Text.legacy(l));
                }
                meta.setLore(lore);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    public static ItemStack named(Material mat, String name, List<String> loreRaw) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(Text.legacy(name));
            if (loreRaw != null && !loreRaw.isEmpty()) {
                List<String> lore = new ArrayList<>();
                for (String l : loreRaw) lore.add(Text.legacy(l));
                meta.setLore(lore);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    /** Draw full border on 54-slot inv (top/bottom rows + sides). */
    public static void drawBorder54(Inventory inv) {
        ItemStack b = border();
        ItemStack a = accent();
        // Top row
        for (int i = 0; i < 9; i++) inv.setItem(i, i == 4 ? a : b);
        // Bottom row
        for (int i = 45; i < 54; i++) inv.setItem(i, b);
        // Sides
        for (int row = 1; row <= 4; row++) {
            inv.setItem(row * 9, b);
            inv.setItem(row * 9 + 8, b);
        }
    }

    /** Draw border on 27-slot inv. */
    public static void drawBorder27(Inventory inv) {
        ItemStack b = border();
        for (int i = 0; i < 9; i++) inv.setItem(i, b);
        for (int i = 18; i < 27; i++) inv.setItem(i, b);
        inv.setItem(9, b);
        inv.setItem(17, b);
    }

    /** Fill empty slots with filler panes. */
    public static void fillEmpty(Inventory inv) {
        ItemStack f = filler();
        for (int i = 0; i < inv.getSize(); i++) {
            if (inv.getItem(i) == null) {
                inv.setItem(i, f.clone());
            }
        }
    }

    public static Material rarityGlass(String rarity) {
        if (rarity == null) return Material.WHITE_STAINED_GLASS_PANE;
        return switch (rarity.toUpperCase()) {
            case "UNCOMMON" -> Material.LIME_STAINED_GLASS_PANE;
            case "RARE" -> Material.LIGHT_BLUE_STAINED_GLASS_PANE;
            case "EPIC" -> Material.MAGENTA_STAINED_GLASS_PANE;
            case "LEGENDARY" -> Material.ORANGE_STAINED_GLASS_PANE;
            case "MYTHIC" -> Material.RED_STAINED_GLASS_PANE;
            default -> Material.WHITE_STAINED_GLASS_PANE;
        };
    }

    public static String rarityColor(String rarity) {
        if (rarity == null) return "&7";
        return switch (rarity.toUpperCase()) {
            case "UNCOMMON" -> "&a";
            case "RARE" -> "&b";
            case "EPIC" -> "&d";
            case "LEGENDARY" -> "&6";
            case "MYTHIC" -> "&c&l";
            default -> "&f";
        };
    }

    public static InventoryButton button(ItemStack icon, Consumer<org.bukkit.event.inventory.InventoryClickEvent> click) {
        return new InventoryButton().creator(p -> icon.clone()).consumer(click);
    }

    public static InventoryButton button(Material mat, String name, Consumer<org.bukkit.event.inventory.InventoryClickEvent> click, String... lore) {
        return button(named(mat, name, lore), click);
    }
}
