package com.skritped.novacrates.gui;

import com.skritped.novacrates.model.CrateDefinition;
import com.skritped.novacrates.model.RewardDefinition;
import com.skritped.novacrates.service.RewardSelector;
import com.skritped.novacrates.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ItemMetaUtil {
    private ItemMetaUtil() {
    }

    public static void addChancePercent(ItemStack item, double percent) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        lore.add(Text.legacy("&7Chance: &d" + String.format(Locale.US, "%.2f", percent) + "%"));
        meta.setLore(lore);
        item.setItemMeta(meta);
    }

    public static void decoratePreview(ItemStack item, RewardDefinition reward, CrateDefinition crate, Player player) {
        double percent = reward.getDisplayChance() != null
                ? reward.getDisplayChance()
                : RewardSelector.normalizedPercent(reward, crate, player);
        addChancePercent(item, percent);
        addRarity(item, reward.getRarity());
    }

    public static void addRarity(ItemStack item, String rarity) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null || rarity == null) {
            return;
        }
        List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        lore.add(Text.legacy("&8Rarity: " + rarityColor(rarity) + rarity));
        meta.setLore(lore);
        item.setItemMeta(meta);
    }

    private static String rarityColor(String rarity) {
        return switch (rarity.toUpperCase(Locale.ROOT)) {
            case "UNCOMMON" -> "&a";
            case "RARE" -> "&b";
            case "EPIC" -> "&d";
            case "LEGENDARY" -> "&6";
            case "MYTHIC" -> "&c";
            default -> "&7";
        };
    }
}
