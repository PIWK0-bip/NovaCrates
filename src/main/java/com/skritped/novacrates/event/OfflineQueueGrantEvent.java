package com.skritped.novacrates.event;

import com.skritped.novacrates.model.CrateDefinition;
import com.skritped.novacrates.model.RewardDefinition;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

/**
 * Fired when an offline-queued open is granted to a player on join.
 */
public class OfflineQueueGrantEvent extends PlayerEvent {
    private static final HandlerList HANDLERS = new HandlerList();
    private final CrateDefinition crate;
    private final RewardDefinition reward;
    private final String queuedBy;
    private final boolean consumeKey;

    public OfflineQueueGrantEvent(Player player, CrateDefinition crate, RewardDefinition reward,
                                  String queuedBy, boolean consumeKey) {
        super(player);
        this.crate = crate;
        this.reward = reward;
        this.queuedBy = queuedBy;
        this.consumeKey = consumeKey;
    }

    public CrateDefinition getCrate() { return crate; }
    public RewardDefinition getReward() { return reward; }
    public String getQueuedBy() { return queuedBy; }
    public boolean isConsumeKey() { return consumeKey; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
