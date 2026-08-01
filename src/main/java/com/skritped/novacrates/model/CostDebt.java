package com.skritped.novacrates.model;

import java.util.UUID;

public class CostDebt {
    private final UUID playerId;
    private final String costType;
    private final double costAmount;
    private final String costMaterial;
    private final boolean virtualKey;
    private final String keyId;
    private final String crateId;

    public CostDebt(UUID playerId, String costType, double costAmount, String costMaterial,
                    boolean virtualKey, String keyId, String crateId) {
        this.playerId = playerId;
        this.costType = costType;
        this.costAmount = costAmount;
        this.costMaterial = costMaterial;
        this.virtualKey = virtualKey;
        this.keyId = keyId;
        this.crateId = crateId;
    }

    public UUID getPlayerId() { return playerId; }
    public String getCostType() { return costType; }
    public double getCostAmount() { return costAmount; }
    public String getCostMaterial() { return costMaterial; }
    public boolean isVirtualKey() { return virtualKey; }
    public String getKeyId() { return keyId; }
    public String getCrateId() { return crateId; }
}
