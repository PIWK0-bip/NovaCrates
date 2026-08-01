package com.skritped.novacrates.model;

import java.util.UUID;

public class OfflineOpen {
    private final long id;
    private final UUID playerId;
    private final String crateId;
    private final String queuedBy;
    private final long createdAtMillis;
    private final String forcedRewardId;
    private final boolean consumeKey;

    public OfflineOpen(long id, UUID playerId, String crateId, String queuedBy,
                       long createdAtMillis, String forcedRewardId, boolean consumeKey) {
        this.id = id;
        this.playerId = playerId;
        this.crateId = crateId;
        this.queuedBy = queuedBy;
        this.createdAtMillis = createdAtMillis;
        this.forcedRewardId = forcedRewardId;
        this.consumeKey = consumeKey;
    }

    public OfflineOpen(UUID playerId, String crateId, String queuedBy, String forcedRewardId, boolean consumeKey) {
        this(0, playerId, crateId, queuedBy, System.currentTimeMillis(), forcedRewardId, consumeKey);
    }

    public long getId() { return id; }
    public UUID getPlayerId() { return playerId; }
    public String getCrateId() { return crateId; }
    public String getQueuedBy() { return queuedBy; }
    public long getCreatedAtMillis() { return createdAtMillis; }
    public String getForcedRewardId() { return forcedRewardId; }
    public boolean isConsumeKey() { return consumeKey; }
}
