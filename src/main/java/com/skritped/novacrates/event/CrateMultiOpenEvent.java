package com.skritped.novacrates.event;

import com.skritped.novacrates.model.CrateDefinition;
import com.skritped.novacrates.model.RewardDefinition;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Fired after a multi-open completes (all rewards selected and granted).
 */
public class CrateMultiOpenEvent extends PlayerEvent {
    private static final HandlerList HANDLERS = new HandlerList();
    private final CrateDefinition crate;
    private final int times;
    private final List<RewardDefinition> rewards;

    public CrateMultiOpenEvent(Player player, CrateDefinition crate, int times, List<RewardDefinition> rewards) {
        super(player);
        this.crate = crate;
        this.times = times;
        this.rewards = rewards == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(rewards));
    }

    public CrateDefinition getCrate() { return crate; }
    public int getTimes() { return times; }
    public List<RewardDefinition> getRewards() { return rewards; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
