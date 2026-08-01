package com.skritped.novacrates.model;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class RewardDefinition {
    private final String id;
    private final String material;
    private final int amount;
    private final double chance;
    private final Double displayChance;
    private final String displayName;
    private final List<String> lore;
    private final List<String> commands;
    private final boolean commandAsPlayer;
    private final int customModelData;
    private final String texture;
    private final String rarity;
    private final boolean broadcast;
    private final Map<String, Integer> enchantments;
    private final String permission;

    private RewardDefinition(Builder b) {
        this.id = b.id;
        this.material = b.material;
        this.amount = b.amount;
        this.chance = b.chance;
        this.displayChance = b.displayChance;
        this.displayName = b.displayName;
        this.lore = b.lore;
        this.commands = b.commands;
        this.commandAsPlayer = b.commandAsPlayer;
        this.customModelData = b.customModelData;
        this.texture = b.texture;
        this.rarity = b.rarity;
        this.broadcast = b.broadcast;
        this.enchantments = b.enchantments;
        this.permission = b.permission;
    }

    public static Builder builder() { return new Builder(); }

    public String getId() { return id; }
    public String getMaterial() { return material; }
    public int getAmount() { return amount; }
    /** Weight used for rolls (alias: weight in YAML maps to chance). */
    public double getChance() { return chance; }
    public double getWeight() { return chance; }
    public Double getDisplayChance() { return displayChance; }
    public String getDisplayName() { return displayName; }
    public List<String> getLore() { return lore == null ? Collections.emptyList() : lore; }
    public List<String> getCommands() { return commands == null ? Collections.emptyList() : commands; }
    public boolean isCommandAsPlayer() { return commandAsPlayer; }
    public int getCustomModelData() { return customModelData; }
    public String getTexture() { return texture; }
    public String getRarity() { return rarity == null || rarity.isBlank() ? "COMMON" : rarity; }
    public boolean isBroadcast() { return broadcast; }
    public Map<String, Integer> getEnchantments() {
        return enchantments == null ? Collections.emptyMap() : enchantments;
    }
    public String getPermission() { return permission == null ? "" : permission; }
    public boolean hasPermissionRequirement() {
        return permission != null && !permission.isBlank();
    }

    public static final class Builder {
        private String id;
        private String material = "STONE";
        private int amount = 1;
        private double chance = 1.0;
        private Double displayChance;
        private String displayName;
        private List<String> lore = List.of();
        private List<String> commands = List.of();
        private boolean commandAsPlayer;
        private int customModelData;
        private String texture;
        private String rarity = "COMMON";
        private boolean broadcast;
        private Map<String, Integer> enchantments = Map.of();
        private String permission = "";

        public Builder id(String id) { this.id = id; return this; }
        public Builder material(String material) { this.material = material; return this; }
        public Builder amount(int amount) { this.amount = amount; return this; }
        public Builder chance(double chance) { this.chance = chance; return this; }
        public Builder weight(double weight) { this.chance = weight; return this; }
        public Builder displayChance(Double displayChance) { this.displayChance = displayChance; return this; }
        public Builder displayName(String displayName) { this.displayName = displayName; return this; }
        public Builder lore(List<String> lore) { this.lore = lore; return this; }
        public Builder commands(List<String> commands) { this.commands = commands; return this; }
        public Builder commandAsPlayer(boolean v) { this.commandAsPlayer = v; return this; }
        public Builder customModelData(int v) { this.customModelData = v; return this; }
        public Builder texture(String texture) { this.texture = texture; return this; }
        public Builder rarity(String rarity) { this.rarity = rarity; return this; }
        public Builder broadcast(boolean broadcast) { this.broadcast = broadcast; return this; }
        public Builder enchantments(Map<String, Integer> e) { this.enchantments = e; return this; }
        public Builder permission(String permission) { this.permission = permission; return this; }
        public RewardDefinition build() { return new RewardDefinition(this); }
    }
}
