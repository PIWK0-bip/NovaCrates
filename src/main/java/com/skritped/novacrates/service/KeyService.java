package com.skritped.novacrates.service;

import com.skritped.novacrates.storage.PlayerDataRepository;
import com.cryptomorin.xseries.XMaterial;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class KeyService {
    private final NamespacedKey keyIdKey;
    private final NamespacedKey keyTypeKey;
    private final NamespacedKey keySigKey;
    private final JavaPlugin plugin;
    private String hmacSecret;
    private final Map<String, KeyStyle> styles = new HashMap<>();

    public KeyService(JavaPlugin plugin) {
        this.plugin = plugin;
        this.keyIdKey = new NamespacedKey(plugin, "key_id");
        this.keyTypeKey = new NamespacedKey(plugin, "key_type");
        this.keySigKey = new NamespacedKey(plugin, "key_sig");
        reloadStyles();
        reloadSecret();
    }

    public void reloadStyles() {
        styles.clear();
        plugin.saveResource("keys.yml", false);
        File file = new File(plugin.getDataFolder(), "keys.yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yaml.getConfigurationSection("keys");
        if (section == null) {
            return;
        }
        for (String id : section.getKeys(false)) {
            ConfigurationSection s = section.getConfigurationSection(id);
            if (s == null) {
                continue;
            }
            styles.put(id.toLowerCase(), new KeyStyle(
                    s.getString("material", "TRIPWIRE_HOOK"),
                    s.getString("name", "&b%key% Key"),
                    s.getStringList("lore"),
                    s.getInt("custom-model-data", 0)
            ));
        }
    }

    private KeyStyle styleFor(String keyId) {
        KeyStyle style = styles.get(keyId.toLowerCase());
        if (style == null) {
            style = styles.get("default");
        }
        if (style == null) {
            style = new KeyStyle("TRIPWIRE_HOOK", "&b%key% Key", List.of("&7Used by NovaCrates"), 0);
        }
        return style;
    }

    public void reloadSecret() {
        hmacSecret = plugin.getConfig().getString("settings.key-hmac-secret", "");
        if (hmacSecret == null || hmacSecret.isBlank()) {
            // Derive stable secret from server + plugin data folder path
            hmacSecret = "novacrates-" + plugin.getDataFolder().getAbsolutePath().hashCode();
        }
    }

    private String sign(String keyId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hmacSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(keyId.toLowerCase().getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(8, raw.length); i++) {
                sb.append(String.format("%02x", raw[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(keyId.hashCode());
        }
    }

    /**
     * Persist a friendly display name for a key id into keys.yml and memory.
     * Example: keyId=super_rare, display="Super Rare" → name "&fSuper Rare &7Key"
     */
    public void setKeyDisplayName(String keyId, String displayName) {
        if (keyId == null || displayName == null || displayName.isBlank()) return;
        String id = keyId.toLowerCase();
        String pretty = displayName.trim().replace('§', '&');
        String name = pretty.contains("%key%") ? pretty : ("&f" + pretty + " &7Key");
        KeyStyle old = styleFor(id);
        KeyStyle ns = new KeyStyle(old.material(), name, old.lore(), old.customModelData());
        styles.put(id, ns);
        try {
            File file = new File(plugin.getDataFolder(), "keys.yml");
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            String base = "keys." + id;
            if (!yaml.isConfigurationSection(base)) {
                yaml.set(base + ".material", ns.material());
                yaml.set(base + ".lore", ns.lore());
                yaml.set(base + ".custom-model-data", ns.customModelData());
            }
            yaml.set(base + ".name", name);
            yaml.save(file);
        } catch (Exception e) {
            plugin.getLogger().warning("Could not save key display name: " + e.getMessage());
        }
    }

    public ItemStack createPhysicalKey(String keyId, int amount) {
        KeyStyle style = styleFor(keyId);
        ItemStack item = XMaterial.matchXMaterial(style.material)
                .map(XMaterial::parseItem)
                .orElse(new ItemStack(Material.TRIPWIRE_HOOK));
        item.setAmount(Math.max(1, amount));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&',
                    style.name.replace("%key%", keyId)));
            List<String> lore = new ArrayList<>();
            for (String line : style.lore) {
                lore.add(ChatColor.translateAlternateColorCodes('&', line.replace("%key%", keyId)));
            }
            meta.setLore(lore);
            if (style.customModelData > 0) {
                meta.setCustomModelData(style.customModelData);
            }
            meta.getPersistentDataContainer().set(keyIdKey, PersistentDataType.STRING, keyId);
            meta.getPersistentDataContainer().set(keyTypeKey, PersistentDataType.STRING, "physical");
            meta.getPersistentDataContainer().set(keySigKey, PersistentDataType.STRING, sign(keyId));
            item.setItemMeta(meta);
        }
        return item;
    }

    public ItemStack createPhysicalKey(String keyId) {
        return createPhysicalKey(keyId, 1);
    }

    public boolean isPhysicalKey(ItemStack item, String keyId) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return false;
        }
        var pdc = item.getItemMeta().getPersistentDataContainer();
        String stored = pdc.get(keyIdKey, PersistentDataType.STRING);
        if (!keyId.equals(stored)) {
            return false;
        }
        if (plugin.getConfig().getBoolean("settings.key-hmac-enabled", true)) {
            String sig = pdc.get(keySigKey, PersistentDataType.STRING);
            if (sig == null || !sig.equals(sign(keyId))) {
                return false;
            }
        }
        return true;
    }

    public boolean hasPhysicalKey(Player player, String keyId) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (isPhysicalKey(item, keyId)) {
                return true;
            }
        }
        return false;
    }

    public boolean consumePhysicalKey(Player player, String keyId) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (isPhysicalKey(item, keyId)) {
                if (item.getAmount() <= 1) {
                    player.getInventory().removeItem(item);
                } else {
                    item.setAmount(item.getAmount() - 1);
                }
                return true;
            }
        }
        return false;
    }

    public void givePhysicalKey(Player player, String keyId, int amount) {
        player.getInventory().addItem(createPhysicalKey(keyId, amount));
    }

    public void givePhysicalKey(Player player, String keyId) {
        givePhysicalKey(player, keyId, 1);
    }

    public boolean consumeVirtualKey(Player player, String keyId, PlayerDataRepository repository) {
        int amount = repository.getVirtualKeys(player.getUniqueId(), keyId);
        if (amount < 1) {
            return false;
        }
        repository.setVirtualKeys(player.getUniqueId(), keyId, amount - 1);
        return true;
    }

    public void giveVirtualKey(Player player, String keyId, PlayerDataRepository repository, int amount) {
        repository.setVirtualKeys(player.getUniqueId(), keyId,
                repository.getVirtualKeys(player.getUniqueId(), keyId) + Math.max(1, amount));
    }

    public void giveVirtualKey(Player player, String keyId, PlayerDataRepository repository) {
        giveVirtualKey(player, keyId, repository, 1);
    }

    private record KeyStyle(String material, String name, List<String> lore, int customModelData) {
    }
}
