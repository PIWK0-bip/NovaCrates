package com.skritped.novacrates.service;

import com.skritped.novacrates.api.NovaCratesAPI;
import com.skritped.novacrates.event.CratePreSelectEvent;
import com.skritped.novacrates.model.CrateDefinition;
import com.skritped.novacrates.model.RewardDefinition;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public final class RewardSelector {
    private RewardSelector() {
    }

    public static List<RewardDefinition> eligible(CrateDefinition crate, Player player) {
        List<RewardDefinition> list = new ArrayList<>();
        for (RewardDefinition reward : crate.getRewards()) {
            if (!reward.hasPermissionRequirement()
                    || player == null
                    || player.hasPermission(reward.getPermission())) {
                list.add(reward);
            }
        }
        return list;
    }

    public static RewardDefinition select(CrateDefinition crate, int pity) {
        return select(crate, pity, null);
    }

    public static RewardDefinition select(CrateDefinition crate, int pity, Player player) {
        List<RewardDefinition> rewards = eligible(crate, player);
        if (rewards.isEmpty()) {
            rewards = new ArrayList<>(crate.getRewards());
        }

        // Allow plugins to mutate pool (must be called on main thread when player present)
        if (player != null && Bukkit.isPrimaryThread()) {
            CratePreSelectEvent event = new CratePreSelectEvent(player, crate, pity, rewards);
            Bukkit.getPluginManager().callEvent(event);
            rewards = event.getEligible();
            if (rewards == null || rewards.isEmpty()) {
                rewards = eligible(crate, player);
                if (rewards.isEmpty()) {
                    rewards = new ArrayList<>(crate.getRewards());
                }
            }
            // API reward filters
            var filters = NovaCratesAPI.getRewardFilters();
            if (!filters.isEmpty()) {
                rewards = rewards.stream()
                        .filter(r -> filters.stream().allMatch(f -> f.test(player, r)))
                        .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
                if (rewards.isEmpty()) {
                    rewards = eligible(crate, player);
                    if (rewards.isEmpty()) {
                        rewards = new ArrayList<>(crate.getRewards());
                    }
                }
            }
        }

        if (rewards.isEmpty()) {
            throw new IllegalStateException("Crate " + crate.getId() + " has no rewards");
        }

        // Hard pity
        if (crate.getPityThreshold() > 0
                && pity >= crate.getPityThreshold()
                && crate.getPityRewardId() != null) {
            for (RewardDefinition reward : rewards) {
                if (reward.getId().equals(crate.getPityRewardId())) {
                    return reward;
                }
            }
            for (RewardDefinition reward : crate.getRewards()) {
                if (reward.getId().equals(crate.getPityRewardId())) {
                    return reward;
                }
            }
        }

        // Guaranteed sequence: every N opens force reward-id (pity used as open counter since last guarantee reset)
        // softPityStart==0 path: when pity>0 and pity % every == 0 — handled in CrateManager before select

        // Soft pity: boost weight of pity reward (and optionally high rarities)
        double softStart = crate.getSoftPityStart();
        double softBoost = crate.getSoftPityBoostPerOpen();
        boolean softActive = softStart > 0 && softBoost > 0 && pity >= softStart
                && crate.getPityRewardId() != null;

        double total = 0;
        double[] weights = new double[rewards.size()];
        for (int i = 0; i < rewards.size(); i++) {
            RewardDefinition r = rewards.get(i);
            double w = Math.max(0, r.getChance());
            if (softActive && r.getId().equals(crate.getPityRewardId())) {
                w += (pity - softStart + 1) * softBoost;
            }
            weights[i] = w;
            total += w;
        }

        if (total <= 0) {
            return rewards.get(0);
        }

        double roll = ThreadLocalRandom.current().nextDouble(total);
        for (int i = 0; i < rewards.size(); i++) {
            roll -= weights[i];
            if (roll <= 0) {
                return rewards.get(i);
            }
        }
        return rewards.get(rewards.size() - 1);
    }

    public static double normalizedPercent(RewardDefinition reward, CrateDefinition crate) {
        return normalizedPercent(reward, crate, null);
    }

    public static double normalizedPercent(RewardDefinition reward, CrateDefinition crate, Player player) {
        List<RewardDefinition> rewards = eligible(crate, player);
        if (rewards.isEmpty()) {
            rewards = crate.getRewards();
        }
        double total = rewards.stream().mapToDouble(RewardDefinition::getChance).sum();
        if (total <= 0) {
            return 0;
        }
        return (reward.getChance() / total) * 100.0;
    }
}
