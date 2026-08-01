package com.skritped.novacrates.model;

public class DropRecord {
    private final String crateId;
    private final String rewardId;
    private final String rewardName;
    private final long timeMillis;

    public DropRecord(String crateId, String rewardId, String rewardName, long timeMillis) {
        this.crateId = crateId;
        this.rewardId = rewardId;
        this.rewardName = rewardName;
        this.timeMillis = timeMillis;
    }

    public String getCrateId() { return crateId; }
    public String getRewardId() { return rewardId; }
    public String getRewardName() { return rewardName; }
    public long getTimeMillis() { return timeMillis; }
    public long getTimestamp() { return timeMillis; }
}
