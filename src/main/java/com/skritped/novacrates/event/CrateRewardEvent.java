package com.skritped.novacrates.event;

import com.skritped.novacrates.model.CrateDefinition;
import com.skritped.novacrates.model.RewardDefinition;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

public class CrateRewardEvent extends PlayerEvent implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final CrateDefinition crate;
    private RewardDefinition reward;
    private final boolean pityTriggered;
    private boolean cancelled;

    public CrateRewardEvent(Player player, CrateDefinition crate, RewardDefinition reward) {
        this(player, crate, reward, false);
    }

    public CrateRewardEvent(Player player, CrateDefinition crate, RewardDefinition reward, boolean pityTriggered) {
        super(player);
        this.crate = crate;
        this.reward = reward;
        this.pityTriggered = pityTriggered;
    }

    public CrateDefinition getCrate() { return crate; }
    public RewardDefinition getReward() { return reward; }
    public void setReward(RewardDefinition reward) { this.reward = reward; }
    public boolean isPityTriggered() { return pityTriggered; }

    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancel) { this.cancelled = cancel; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
