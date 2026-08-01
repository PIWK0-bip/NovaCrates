package com.skritped.novacrates.model;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.List;

public class CrateDefinition {
    private final String id;
    private final String displayName;
    private final String keyId;
    private final String animation;
    private final List<RewardDefinition> rewards;
    private final int pityThreshold;
    private final String pityRewardId;
    private final double softPityStart;
    private final double softPityBoostPerOpen;
    private final CostDefinition cost;
    private final int cooldownSeconds;
    private final boolean hologramEnabled;
    private final List<String> hologramLines;
    private final String availableFrom;
    private final String availableUntil;
    private final int milestoneEvery;
    private final String milestoneRewardId;
    /** Crate id that must have N opens to unlock this crate (tier gate). */
    private final String unlockRequiresCrate;
    private final int unlockRequiresOpens;
    /** Permission that auto-unlocks this crate. */
    private final String unlockPermission;
    /** Battle-pass track points granted per open of this crate. */
    private final int passPoints;
    private final String passTrack;

    public CrateDefinition(String id, String displayName, String keyId, String animation,
                           List<RewardDefinition> rewards, int pityThreshold,
                           String pityRewardId, CostDefinition cost,
                           int cooldownSeconds, boolean hologramEnabled, List<String> hologramLines) {
        this(id, displayName, keyId, animation, rewards, pityThreshold, pityRewardId,
                0, 0, cost, cooldownSeconds, hologramEnabled, hologramLines,
                null, null, 0, null, null, 0, null, 0, null);
    }

    public CrateDefinition(String id, String displayName, String keyId, String animation,
                           List<RewardDefinition> rewards, int pityThreshold,
                           String pityRewardId, double softPityStart, double softPityBoostPerOpen,
                           CostDefinition cost, int cooldownSeconds, boolean hologramEnabled,
                           List<String> hologramLines, String availableFrom, String availableUntil,
                           int milestoneEvery, String milestoneRewardId) {
        this(id, displayName, keyId, animation, rewards, pityThreshold, pityRewardId,
                softPityStart, softPityBoostPerOpen, cost, cooldownSeconds, hologramEnabled,
                hologramLines, availableFrom, availableUntil, milestoneEvery, milestoneRewardId,
                null, 0, null, 0, null);
    }

    public CrateDefinition(String id, String displayName, String keyId, String animation,
                           List<RewardDefinition> rewards, int pityThreshold,
                           String pityRewardId, double softPityStart, double softPityBoostPerOpen,
                           CostDefinition cost, int cooldownSeconds, boolean hologramEnabled,
                           List<String> hologramLines, String availableFrom, String availableUntil,
                           int milestoneEvery, String milestoneRewardId,
                           String unlockRequiresCrate, int unlockRequiresOpens, String unlockPermission,
                           int passPoints, String passTrack) {
        this.id = id;
        this.displayName = displayName;
        this.keyId = keyId;
        this.animation = animation == null ? "CSGO" : animation;
        this.rewards = Collections.unmodifiableList(rewards);
        this.pityThreshold = pityThreshold;
        this.pityRewardId = pityRewardId;
        this.softPityStart = Math.max(0, softPityStart);
        this.softPityBoostPerOpen = Math.max(0, softPityBoostPerOpen);
        this.cost = cost;
        this.cooldownSeconds = Math.max(0, cooldownSeconds);
        this.hologramEnabled = hologramEnabled;
        this.hologramLines = hologramLines == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(hologramLines);
        this.availableFrom = availableFrom;
        this.availableUntil = availableUntil;
        this.milestoneEvery = Math.max(0, milestoneEvery);
        this.milestoneRewardId = milestoneRewardId;
        this.unlockRequiresCrate = unlockRequiresCrate;
        this.unlockRequiresOpens = Math.max(0, unlockRequiresOpens);
        this.unlockPermission = unlockPermission;
        this.passPoints = Math.max(0, passPoints);
        this.passTrack = passTrack;
    }

    public String getId() { return id; }
    public String getDisplayName() { return displayName; }
    public String getKeyId() { return keyId; }
    public String getAnimation() { return animation; }
    public List<RewardDefinition> getRewards() { return rewards; }
    public int getPityThreshold() { return pityThreshold; }
    public String getPityRewardId() { return pityRewardId; }
    public double getSoftPityStart() { return softPityStart; }
    public double getSoftPityBoostPerOpen() { return softPityBoostPerOpen; }
    public CostDefinition getCost() { return cost; }
    public int getCooldownSeconds() { return cooldownSeconds; }
    public boolean isHologramEnabled() { return hologramEnabled; }
    public List<String> getHologramLines() { return hologramLines; }
    public String getAvailableFrom() { return availableFrom; }
    public String getAvailableUntil() { return availableUntil; }
    public int getMilestoneEvery() { return milestoneEvery; }
    public String getMilestoneRewardId() { return milestoneRewardId; }
    public String getUnlockRequiresCrate() { return unlockRequiresCrate; }
    public int getUnlockRequiresOpens() { return unlockRequiresOpens; }
    public String getUnlockPermission() { return unlockPermission; }
    public int getPassPoints() { return passPoints; }
    public String getPassTrack() { return passTrack; }

    public boolean hasUnlockRequirement() {
        return (unlockRequiresCrate != null && !unlockRequiresCrate.isBlank() && unlockRequiresOpens > 0)
                || (unlockPermission != null && !unlockPermission.isBlank());
    }

    public boolean isAvailableNow() {
        long now = System.currentTimeMillis();
        if (availableFrom != null && !availableFrom.isBlank()) {
            Long from = parseInstant(availableFrom);
            if (from != null && now < from) {
                return false;
            }
        }
        if (availableUntil != null && !availableUntil.isBlank()) {
            Long until = parseInstant(availableUntil);
            if (until != null && now > until) {
                return false;
            }
        }
        return true;
    }

    private static Long parseInstant(String s) {
        try {
            return Instant.parse(s).toEpochMilli();
        } catch (DateTimeParseException e) {
            try {
                return Instant.parse(s + "T00:00:00Z").toEpochMilli();
            } catch (DateTimeParseException e2) {
                return null;
            }
        }
    }

    public CrateDefinition withRewards(List<RewardDefinition> newRewards) {
        return new CrateDefinition(id, displayName, keyId, animation, newRewards,
                pityThreshold, pityRewardId, softPityStart, softPityBoostPerOpen,
                cost, cooldownSeconds, hologramEnabled, hologramLines,
                availableFrom, availableUntil, milestoneEvery, milestoneRewardId,
                unlockRequiresCrate, unlockRequiresOpens, unlockPermission,
                passPoints, passTrack);
    }

    public CrateDefinition withDisplayName(String newDisplayName) {
        return new CrateDefinition(id, newDisplayName == null ? displayName : newDisplayName, keyId, animation, rewards,
                pityThreshold, pityRewardId, softPityStart, softPityBoostPerOpen,
                cost, cooldownSeconds, hologramEnabled, hologramLines,
                availableFrom, availableUntil, milestoneEvery, milestoneRewardId,
                unlockRequiresCrate, unlockRequiresOpens, unlockPermission,
                passPoints, passTrack);
    }

    public CrateDefinition withHologramLines(List<String> newLines) {
        return new CrateDefinition(id, displayName, keyId, animation, rewards,
                pityThreshold, pityRewardId, softPityStart, softPityBoostPerOpen,
                cost, cooldownSeconds, hologramEnabled,
                newLines == null ? hologramLines : newLines,
                availableFrom, availableUntil, milestoneEvery, milestoneRewardId,
                unlockRequiresCrate, unlockRequiresOpens, unlockPermission,
                passPoints, passTrack);
    }

    public CrateDefinition withDisplayNameAndHologram(String newDisplayName, List<String> newLines) {
        return new CrateDefinition(id,
                newDisplayName == null ? displayName : newDisplayName,
                keyId, animation, rewards,
                pityThreshold, pityRewardId, softPityStart, softPityBoostPerOpen,
                cost, cooldownSeconds, hologramEnabled,
                newLines == null ? hologramLines : newLines,
                availableFrom, availableUntil, milestoneEvery, milestoneRewardId,
                unlockRequiresCrate, unlockRequiresOpens, unlockPermission,
                passPoints, passTrack);
    }
}
