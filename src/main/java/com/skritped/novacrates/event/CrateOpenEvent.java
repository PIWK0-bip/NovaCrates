package com.skritped.novacrates.event;

import com.skritped.novacrates.model.CrateDefinition;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

public class CrateOpenEvent extends PlayerEvent implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final CrateDefinition crate;
    private final int times;
    private boolean cancelled;

    public CrateOpenEvent(Player player, CrateDefinition crate) {
        this(player, crate, 1);
    }

    public CrateOpenEvent(Player player, CrateDefinition crate, int times) {
        super(player);
        this.crate = crate;
        this.times = times;
    }

    public CrateDefinition getCrate() { return crate; }
    public int getTimes() { return times; }

    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancel) { this.cancelled = cancel; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
