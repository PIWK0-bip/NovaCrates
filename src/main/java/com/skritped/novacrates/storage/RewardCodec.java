package com.skritped.novacrates.storage;

import com.skritped.novacrates.model.RewardDefinition;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** YAML ↔ RewardDefinition codec shared by repository and tools. */
public final class RewardCodec {
    private RewardCodec() {}

    public static List<RewardDefinition> loadSection(ConfigurationSection rewardSection) {
        List<RewardDefinition> rewards = new ArrayList<>();
        if (rewardSection == null) return rewards;
        for (String rewardId : rewardSection.getKeys(false)) {
            ConfigurationSection reward = rewardSection.getConfigurationSection(rewardId);
            if (reward == null) continue;
            rewards.add(fromSection(rewardId, reward));
        }
        return rewards;
    }

    public static RewardDefinition fromSection(String rewardId, ConfigurationSection reward) {
        Map<String, Integer> enchants = new LinkedHashMap<>();
        ConfigurationSection enchSec = reward.getConfigurationSection("enchantments");
        if (enchSec != null) {
            for (String ench : enchSec.getKeys(false)) {
                enchants.put(ench, enchSec.getInt(ench, 1));
            }
        }
        double weight = reward.contains("weight")
                ? reward.getDouble("weight")
                : reward.getDouble("chance", 1.0);
        Double displayChance = reward.contains("display-chance")
                ? reward.getDouble("display-chance")
                : null;
        return RewardDefinition.builder()
                .id(rewardId)
                .material(reward.getString("material", "STONE"))
                .amount(Math.max(1, reward.getInt("amount", 1)))
                .chance(Math.max(0, weight))
                .displayChance(displayChance)
                .displayName(reward.getString("name", rewardId))
                .lore(reward.getStringList("lore"))
                .commands(reward.getStringList("commands"))
                .commandAsPlayer(reward.getBoolean("command-as-player", false))
                .customModelData(reward.getInt("custom-model-data", 0))
                .texture(reward.getString("texture"))
                .rarity(reward.getString("rarity", "COMMON"))
                .broadcast(reward.getBoolean("broadcast", false))
                .enchantments(enchants)
                .permission(reward.getString("permission", ""))
                .build();
    }

    public static void write(YamlConfiguration yaml, String base, List<RewardDefinition> rewards) {
        for (RewardDefinition reward : rewards) {
            writeOne(yaml, base + "." + reward.getId(), reward);
        }
    }

    public static void writeOne(YamlConfiguration yaml, String path, RewardDefinition reward) {
        yaml.set(path + ".material", reward.getMaterial());
        yaml.set(path + ".amount", reward.getAmount());
        yaml.set(path + ".weight", reward.getChance());
        if (reward.getDisplayChance() != null) {
            yaml.set(path + ".display-chance", reward.getDisplayChance());
        }
        yaml.set(path + ".name", reward.getDisplayName());
        yaml.set(path + ".lore", reward.getLore());
        yaml.set(path + ".commands", reward.getCommands());
        yaml.set(path + ".command-as-player", reward.isCommandAsPlayer());
        yaml.set(path + ".custom-model-data", reward.getCustomModelData());
        yaml.set(path + ".rarity", reward.getRarity());
        yaml.set(path + ".broadcast", reward.isBroadcast());
        if (reward.getTexture() != null) {
            yaml.set(path + ".texture", reward.getTexture());
        }
        if (reward.hasPermissionRequirement()) {
            yaml.set(path + ".permission", reward.getPermission());
        }
        for (var e : reward.getEnchantments().entrySet()) {
            yaml.set(path + ".enchantments." + e.getKey(), e.getValue());
        }
    }
}
