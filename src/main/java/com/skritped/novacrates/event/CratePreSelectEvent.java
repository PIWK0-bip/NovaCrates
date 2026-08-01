package com.skritped.novacrates.event;

import com.skritped.novacrates.model.CrateDefinition;
import com.skritped.novacrates.model.RewardDefinition;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Fired before weighted selection. Listeners may modify the eligible reward list.
 */
public class CratePreSelectEvent extends PlayerEvent {
    private static final HandlerList HANDLERS = new HandlerList();
    private final CrateDefinition crate;
    private final int pity;
    private List<RewardDefinition> eligible;

    public CratePreSelectEvent(Player player, CrateDefinition crate, int pity, List<RewardDefinition> eligible) {
        super(player);
        this.crate = crate;
        this.pity = pity;
        this.eligible = new ArrayList<>(eligible);
    }

    public CrateDefinition getCrate() {
        return crate;
    }

    public int getPity() {
        return pity;
    }

    public List<RewardDefinition> getEligible() {
        return eligible;
    }

    public void setEligible(List<RewardDefinition> eligible) {
        this.eligible = eligible == null ? new ArrayList<>() : new ArrayList<>(eligible);
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
