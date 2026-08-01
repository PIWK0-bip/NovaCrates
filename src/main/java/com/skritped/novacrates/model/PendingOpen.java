package com.skritped.novacrates.model;

import java.util.UUID;

public class PendingOpen {
    private UUID playerId;
    private String crateId;
    private String keyId;
    private boolean virtualKey;
    private String costType;
    private double costAmount;
    private String costMaterial;
    private long createdAtMillis;

    public PendingOpen(UUID playerId, String crateId, String keyId, boolean virtualKey,
                       String costType, double costAmount, String costMaterial, long createdAtMillis) {
        this.playerId = playerId;
        this.crateId = crateId;
        this.keyId = keyId;
        this.virtualKey = virtualKey;
        this.costType = costType;
        this.costAmount = costAmount;
        this.costMaterial = costMaterial;
        this.createdAtMillis = createdAtMillis;
    }

    public UUID getPlayerId() { return playerId; }
    public void setPlayerId(UUID playerId) { this.playerId = playerId; }
    public String getCrateId() { return crateId; }
    public void setCrateId(String crateId) { this.crateId = crateId; }
    public String getKeyId() { return keyId; }
    public void setKeyId(String keyId) { this.keyId = keyId; }
    public boolean isVirtualKey() { return virtualKey; }
    public void setVirtualKey(boolean virtualKey) { this.virtualKey = virtualKey; }
    public String getCostType() { return costType; }
    public void setCostType(String costType) { this.costType = costType; }
    public double getCostAmount() { return costAmount; }
    public void setCostAmount(double costAmount) { this.costAmount = costAmount; }
    public String getCostMaterial() { return costMaterial; }
    public void setCostMaterial(String costMaterial) { this.costMaterial = costMaterial; }
    public long getCreatedAtMillis() { return createdAtMillis; }
    public long getCreatedAt() { return createdAtMillis; }
    public void setCreatedAtMillis(long createdAtMillis) { this.createdAtMillis = createdAtMillis; }

    public boolean isExpired(long timeoutMillis) {
        return System.currentTimeMillis() - createdAtMillis > timeoutMillis;
    }
}
