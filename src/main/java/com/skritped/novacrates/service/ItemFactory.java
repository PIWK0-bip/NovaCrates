package com.skritped.novacrates.service;

import com.skritped.novacrates.model.RewardDefinition;
import com.skritped.novacrates.util.Text;
import com.cryptomorin.xseries.XMaterial;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

public class ItemFactory {
    private final NamespacedKey rewardIdKey;
    private final java.util.concurrent.ConcurrentHashMap<String, Material> materialCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    public ItemFactory(JavaPlugin plugin) {
        this.rewardIdKey = new NamespacedKey(plugin, "reward_id");
    }

    private Material resolveMaterial(String name) {
        if (name == null || name.isBlank()) return Material.STONE;
        return materialCache.computeIfAbsent(name.toUpperCase(Locale.ROOT), key -> {
            Material m = XMaterial.matchXMaterial(key)
                    .map(XMaterial::parseMaterial)
                    .orElse(null);
            return m != null ? m : Material.STONE;
        });
    }

    public ItemStack create(RewardDefinition reward) {
        Material material = resolveMaterial(reward.getMaterial());
        ItemStack item = new ItemStack(material, Math.max(1, reward.getAmount()));
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }

        if (reward.getDisplayName() != null) {
            meta.setDisplayName(Text.legacy(reward.getDisplayName()));
        }
        if (!reward.getLore().isEmpty()) {
            meta.setLore(reward.getLore().stream().map(Text::legacy).toList());
        }
        if (reward.getCustomModelData() > 0) {
            meta.setCustomModelData(reward.getCustomModelData());
        }
        if (reward.getId() != null) {
            meta.getPersistentDataContainer().set(rewardIdKey, PersistentDataType.STRING, reward.getId());
        }

        for (var entry : reward.getEnchantments().entrySet()) {
            Enchantment enchant = resolveEnchantment(entry.getKey());
            if (enchant != null) {
                meta.addEnchant(enchant, entry.getValue(), true);
            }
        }

        if (material == Material.PLAYER_HEAD && reward.getTexture() != null && !reward.getTexture().isBlank()) {
            applyTexture(meta, reward.getTexture());
        }

        item.setItemMeta(meta);
        return item;
    }

    public Optional<String> readRewardId(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return Optional.empty();
        }
        String id = item.getItemMeta().getPersistentDataContainer().get(rewardIdKey, PersistentDataType.STRING);
        return Optional.ofNullable(id);
    }

    @SuppressWarnings("deprecation")
    private Enchantment resolveEnchantment(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String key = name.toLowerCase(Locale.ROOT).replace(' ', '_');
        try {
            Class<?> registryClass = Class.forName("org.bukkit.Registry");
            Object enchantRegistry = registryClass.getField("ENCHANTMENT").get(null);
            var get = enchantRegistry.getClass().getMethod("get", org.bukkit.NamespacedKey.class);
            org.bukkit.NamespacedKey nk = key.contains(":")
                    ? org.bukkit.NamespacedKey.fromString(key)
                    : org.bukkit.NamespacedKey.minecraft(key);
            if (nk != null) {
                Object found = get.invoke(enchantRegistry, nk);
                if (found instanceof Enchantment enchantment) {
                    return enchantment;
                }
            }
        } catch (Throwable ignored) {
            // fall through
        }
        try {
            Enchantment byName = Enchantment.getByName(name.toUpperCase(Locale.ROOT));
            if (byName != null) {
                return byName;
            }
        } catch (Throwable ignored) {
            // fall through
        }
        for (Enchantment candidate : Enchantment.values()) {
            if (candidate.getKey().getKey().equalsIgnoreCase(key)) {
                return candidate;
            }
        }
        return null;
    }

    private void applyTexture(ItemMeta meta, String textureOrUrl) {
        if (!(meta instanceof SkullMeta skull)) {
            return;
        }
        try {
            String url = textureOrUrl;
            if (!textureOrUrl.startsWith("http")) {
                if (textureOrUrl.length() < 80 && !textureOrUrl.contains("{")) {
                    url = "http://textures.minecraft.net/texture/" + textureOrUrl;
                } else {
                    try {
                        String decoded = new String(Base64.getDecoder().decode(textureOrUrl));
                        int idx = decoded.indexOf("http");
                        if (idx >= 0) {
                            int end = decoded.indexOf('"', idx);
                            url = end > idx ? decoded.substring(idx, end) : decoded.substring(idx);
                        }
                    } catch (IllegalArgumentException ignored) {
                        // keep original
                    }
                }
            }
            Object profile = Bukkit.class.getMethod("createPlayerProfile", UUID.class)
                    .invoke(null, UUID.randomUUID());
            Object textures = profile.getClass().getMethod("getTextures").invoke(profile);
            textures.getClass().getMethod("setSkin", java.net.URL.class)
                    .invoke(textures, URI.create(url).toURL());
            try {
                skull.getClass().getMethod("setOwnerProfile", Class.forName("org.bukkit.profile.PlayerProfile"))
                        .invoke(skull, profile);
            } catch (Throwable ignored) {
                // older API
            }
        } catch (Exception ignored) {
            // texture optional
        }
    }
}
