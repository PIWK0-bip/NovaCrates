package com.skritped.novacrates.storage;

import com.skritped.novacrates.model.CostDefinition;
import com.skritped.novacrates.model.CrateDefinition;
import com.skritped.novacrates.model.RewardDefinition;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;


public class CrateRepository {
    private final JavaPlugin plugin;
    private final File file;

    public CrateRepository(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "crates.yml");
    }

    public Map<String, CrateDefinition> load() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yaml.getConfigurationSection("crates");
        Map<String, CrateDefinition> crates = new LinkedHashMap<>();
        if (section == null) {
            plugin.getLogger().warning("No crates section in crates.yml");
            return crates;
        }
        int minRewards = plugin.getConfig().getInt("validation.min-rewards", 1);
        int maxRewards = plugin.getConfig().getInt("validation.max-rewards", 100);
        boolean warnZero = plugin.getConfig().getBoolean("validation.warn-zero-chance", true);

        for (String id : section.getKeys(false)) {
            ConfigurationSection crate = section.getConfigurationSection(id);
            if (crate == null) {
                continue;
            }
            List<RewardDefinition> rewards = loadRewards(crate.getConfigurationSection("rewards"));
            if (crate.isList("reward-pools")) {
                for (String poolId : crate.getStringList("reward-pools")) {
                    rewards.addAll(loadRewards(yaml.getConfigurationSection("pools." + poolId)));
                }
            }
            if (rewards.size() < minRewards) {
                plugin.getLogger().severe("Crate '" + id + "' has fewer than " + minRewards
                        + " rewards (" + rewards.size() + ") — skipped.");
                continue;
            }
            if (rewards.size() > maxRewards) {
                plugin.getLogger().warning("Crate '" + id + "' has " + rewards.size()
                        + " rewards (max " + maxRewards + ") — truncated.");
                rewards = new ArrayList<>(rewards.subList(0, maxRewards));
            }
            if (warnZero) {
                for (RewardDefinition r : rewards) {
                    if (r.getChance() <= 0) {
                        plugin.getLogger().warning("Crate '" + id + "' reward '" + r.getId()
                                + "' has chance <= 0");
                    }
                }
            }
            ConfigurationSection costSection = crate.getConfigurationSection("cost");
            CostDefinition cost = costSection == null
                    ? new CostDefinition("NONE", 0, null)
                    : new CostDefinition(
                    costSection.getString("type", "NONE"),
                    costSection.getDouble("amount", 0),
                    costSection.getString("material"));
            ConfigurationSection pity = crate.getConfigurationSection("pity");
            int threshold = pity == null ? 0 : pity.getInt("threshold", 0);
            String pityReward = pity == null ? null : pity.getString("reward");
            double softPityStart = pity == null ? 0 : pity.getDouble("soft-start", 0);
            double softPityBoost = pity == null ? 0 : pity.getDouble("soft-boost-per-open", 0);
            boolean hologram = crate.getBoolean("hologram.enabled", true);
            List<String> holoLines = crate.getStringList("hologram.lines");
            String availableFrom = crate.getString("available-from");
            String availableUntil = crate.getString("available-until");
            int milestoneEvery = crate.getInt("milestone.every", 0);
            String milestoneRewardId = crate.getString("milestone.reward");
            String unlockRequiresCrate = crate.getString("unlock.requires-crate");
            int unlockRequiresOpens = crate.getInt("unlock.requires-opens", 0);
            String unlockPermission = crate.getString("unlock.permission");
            int passPoints = crate.getInt("pass.points", 0);
            String passTrack = crate.getString("pass.track");
            crates.put(id.toLowerCase(), new CrateDefinition(
                    id,
                    crate.getString("display-name", id),
                    crate.getString("key-id", id),
                    crate.getString("animation", "CSGO"),
                    rewards,
                    threshold,
                    pityReward,
                    softPityStart,
                    softPityBoost,
                    cost,
                    crate.getInt("cooldown-seconds", 0),
                    hologram,
                    holoLines,
                    availableFrom,
                    availableUntil,
                    milestoneEvery,
                    milestoneRewardId,
                    unlockRequiresCrate,
                    unlockRequiresOpens,
                    unlockPermission,
                    passPoints,
                    passTrack
            ));
        }
        return crates;
    }

    private List<RewardDefinition> loadRewards(ConfigurationSection rewardSection) {
        return RewardCodec.loadSection(rewardSection);
    }

    public void saveRewards(String crateId, List<RewardDefinition> rewards) {
        Runnable task = () -> {
            try {
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
                String base = "crates." + crateId + ".rewards";
                yaml.set(base, null);
                writeRewards(yaml, base, rewards);
                yaml.save(file);
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Could not save crates.yml", e);
            }
        };
        if (plugin.isEnabled()) {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, task);
        } else {
            task.run();
        }
    }

    /**
     * Merge-save: preserves pools and any top-level keys other than crates,
     * only rewrites the crates section from the in-memory map.
     */
    public void saveFull(Map<String, CrateDefinition> crates) {
        Runnable task = () -> {
            try {
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
                // Preserve pools (and any other non-crates roots) by only clearing crates
                yaml.set("crates", null);
                for (CrateDefinition crate : crates.values()) {
                    String base = "crates." + crate.getId();
                    yaml.set(base + ".display-name", crate.getDisplayName());
                    yaml.set(base + ".key-id", crate.getKeyId());
                    yaml.set(base + ".animation", crate.getAnimation());
                    yaml.set(base + ".cooldown-seconds", crate.getCooldownSeconds());
                    yaml.set(base + ".pity.threshold", crate.getPityThreshold());
                    yaml.set(base + ".pity.reward", crate.getPityRewardId());
                    if (crate.getSoftPityStart() > 0) {
                        yaml.set(base + ".pity.soft-start", crate.getSoftPityStart());
                    }
                    if (crate.getSoftPityBoostPerOpen() > 0) {
                        yaml.set(base + ".pity.soft-boost-per-open", crate.getSoftPityBoostPerOpen());
                    }
                    yaml.set(base + ".cost.type", crate.getCost().getType());
                    yaml.set(base + ".cost.amount", crate.getCost().getAmount());
                    if (crate.getCost().getMaterial() != null) {
                        yaml.set(base + ".cost.material", crate.getCost().getMaterial());
                    }
                    yaml.set(base + ".hologram.enabled", crate.isHologramEnabled());
                    yaml.set(base + ".hologram.lines", crate.getHologramLines());
                    if (crate.getAvailableFrom() != null) {
                        yaml.set(base + ".available-from", crate.getAvailableFrom());
                    }
                    if (crate.getAvailableUntil() != null) {
                        yaml.set(base + ".available-until", crate.getAvailableUntil());
                    }
                    if (crate.getMilestoneEvery() > 0) {
                        yaml.set(base + ".milestone.every", crate.getMilestoneEvery());
                        yaml.set(base + ".milestone.reward", crate.getMilestoneRewardId());
                    }
                    if (crate.getUnlockRequiresCrate() != null) {
                        yaml.set(base + ".unlock.requires-crate", crate.getUnlockRequiresCrate());
                        yaml.set(base + ".unlock.requires-opens", crate.getUnlockRequiresOpens());
                    }
                    if (crate.getUnlockPermission() != null) {
                        yaml.set(base + ".unlock.permission", crate.getUnlockPermission());
                    }
                    if (crate.getPassPoints() > 0) {
                        yaml.set(base + ".pass.points", crate.getPassPoints());
                    }
                    if (crate.getPassTrack() != null) {
                        yaml.set(base + ".pass.track", crate.getPassTrack());
                    }
                    writeRewards(yaml, base + ".rewards", crate.getRewards());
                }
                yaml.save(file);
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Could not save crates.yml", e);
            }
        };
        if (plugin.isEnabled()) {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, task);
        } else {
            task.run();
        }
    }

    private void writeRewards(YamlConfiguration yaml, String base, List<RewardDefinition> rewards) {
        RewardCodec.write(yaml, base, rewards);
    }

    public void backupBeforeSave() {
        try {
            java.nio.file.Path src = file.toPath();
            if (!java.nio.file.Files.exists(src)) {
                return;
            }
            java.nio.file.Path bak = new java.io.File(plugin.getDataFolder(), "crates.yml.bak").toPath();
            java.nio.file.Files.copy(src, bak, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            plugin.getLogger().warning("Could not backup crates.yml: " + e.getMessage());
        }
    }

    /** Export a single crate (and referenced pools) to a standalone YAML string/file. */
    public boolean exportCrate(String crateId, File target) {
        try {
            YamlConfiguration source = YamlConfiguration.loadConfiguration(file);
            ConfigurationSection crateSec = source.getConfigurationSection("crates." + crateId);
            if (crateSec == null) {
                return false;
            }
            YamlConfiguration out = new YamlConfiguration();
            out.set("schema_version", 3);
            out.set("exported-at", java.time.Instant.now().toString());
            out.set("crates." + crateId, crateSec);
            // copy pools referenced by reward-pools
            List<String> pools = crateSec.getStringList("reward-pools");
            for (String poolId : pools) {
                ConfigurationSection pool = source.getConfigurationSection("pools." + poolId);
                if (pool != null) {
                    out.set("pools." + poolId, pool);
                }
            }
            out.save(target);
            return true;
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Export failed", e);
            return false;
        }
    }

    public boolean importCrate(File sourceFile, boolean overwrite) {
        try {
            YamlConfiguration incoming = YamlConfiguration.loadConfiguration(sourceFile);
            ConfigurationSection cratesSec = incoming.getConfigurationSection("crates");
            if (cratesSec == null || cratesSec.getKeys(false).isEmpty()) {
                return false;
            }
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            for (String id : cratesSec.getKeys(false)) {
                if (!overwrite && yaml.getConfigurationSection("crates." + id) != null) {
                    continue;
                }
                yaml.set("crates." + id, cratesSec.getConfigurationSection(id));
            }
            ConfigurationSection poolsSec = incoming.getConfigurationSection("pools");
            if (poolsSec != null) {
                for (String poolId : poolsSec.getKeys(false)) {
                    yaml.set("pools." + poolId, poolsSec.getConfigurationSection(poolId));
                }
            }
            yaml.save(file);
            return true;
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Import failed", e);
            return false;
        }
    }
}
