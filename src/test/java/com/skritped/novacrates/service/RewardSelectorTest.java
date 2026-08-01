package com.skritped.novacrates.service;

import com.skritped.novacrates.model.CostDefinition;
import com.skritped.novacrates.model.CrateDefinition;
import com.skritped.novacrates.model.RewardDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RewardSelectorTest {

    private CrateDefinition crate(List<RewardDefinition> rewards, int pityThreshold, String pityId) {
        return new CrateDefinition("test", "Test", "test", "CSGO", rewards,
                pityThreshold, pityId, new CostDefinition("NONE", 0, null),
                0, false, List.of());
    }

    private RewardDefinition reward(String id, double weight) {
        return RewardDefinition.builder().id(id).material("STONE").amount(1).chance(weight)
                .displayName(id).lore(List.of()).commands(List.of()).commandAsPlayer(false)
                .customModelData(0).rarity("COMMON").broadcast(false)
                .enchantments(Map.of()).permission("").build();
    }

    @Test
    void selectsOnlyRewardWhenSingle() {
        assertEquals("only", RewardSelector.select(crate(List.of(reward("only", 1)), 0, null), 0).getId());
    }

    @Test
    void pityForcesReward() {
        assertEquals("rare", RewardSelector.select(
                crate(List.of(reward("common", 100), reward("rare", 0.001)), 10, "rare"), 10).getId());
    }

    @Test
    void normalizedPercentSumsToHundred() {
        var a = reward("a", 25);
        var b = reward("b", 75);
        var c = crate(List.of(a, b), 0, null);
        assertEquals(25.0, RewardSelector.normalizedPercent(a, c), 0.01);
        assertEquals(75.0, RewardSelector.normalizedPercent(b, c), 0.01);
    }

    @Test
    void zeroTotalFallsBackToFirst() {
        assertEquals("a", RewardSelector.select(crate(List.of(reward("a", 0), reward("b", 0)), 0, null), 0).getId());
    }

    @Test
    void softPityBoostsPityReward() {
        var c = new CrateDefinition("test", "Test", "test", "CSGO",
                List.of(reward("common", 100), reward("rare", 1)),
                999, "rare", 5, 50.0,
                new CostDefinition("NONE", 0, null), 0, false, List.of(),
                null, null, 0, null);
        int rare = 0;
        for (int i = 0; i < 300; i++) {
            if (RewardSelector.select(c, 20).getId().equals("rare")) rare++;
        }
        assertTrue(rare > 30, "soft pity rare count=" + rare);
    }

    @Test
    void weightedBiasTowardHigherChance() {
        var c = crate(List.of(reward("low", 1), reward("high", 99)), 0, null);
        int highCount = 0;
        for (int i = 0; i < 500; i++) {
            if (RewardSelector.select(c, 0).getId().equals("high")) highCount++;
        }
        assertTrue(highCount > 400, "got " + highCount);
    }
}
