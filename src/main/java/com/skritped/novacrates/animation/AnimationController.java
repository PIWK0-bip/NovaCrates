package com.skritped.novacrates.animation;

import com.skritped.novacrates.gui.AnimationGUI;
import com.skritped.novacrates.gui.ChestAnimationGUI;
import com.skritped.novacrates.gui.GUIManager;
import com.skritped.novacrates.gui.WinGUI;
import com.skritped.novacrates.model.CrateDefinition;
import com.skritped.novacrates.model.RewardDefinition;
import com.skritped.novacrates.service.ItemFactory;
import com.skritped.novacrates.storage.PlayerDataRepository;
import com.skritped.novacrates.service.RewardSelector;
import com.skritped.novacrates.util.SchedulerUtil;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class AnimationController {
    private final JavaPlugin plugin;
    private final GUIManager guiManager;
    private final ItemFactory itemFactory;
    private final PlayerDataRepository playerData;
    private final HologramRouletteAnimation hologramRoulette;
    private final Map<UUID, SchedulerUtil.Cancellable> active = new ConcurrentHashMap<>();
    private final Map<UUID, Consumer<RewardDefinition>> completions = new ConcurrentHashMap<>();
    private final Map<UUID, RewardDefinition> winners = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<RewardDefinition>> pendingSelections = new ConcurrentHashMap<>();
    private final Map<UUID, Location> animOrigins = new ConcurrentHashMap<>();
    private final AtomicInteger concurrentHologramAnims = new AtomicInteger(0);

    public AnimationController(JavaPlugin plugin, GUIManager guiManager, ItemFactory itemFactory) {
        this(plugin, guiManager, itemFactory, null);
    }

    public AnimationController(JavaPlugin plugin, GUIManager guiManager, ItemFactory itemFactory,
                               PlayerDataRepository playerData) {
        this.plugin = plugin;
        this.guiManager = guiManager;
        this.itemFactory = itemFactory;
        this.playerData = playerData;
        this.hologramRoulette = new HologramRouletteAnimation(plugin, itemFactory);
    }

    public void start(Player player, CrateDefinition crate, CompletableFuture<RewardDefinition> selection,
                      Consumer<RewardDefinition> completion) {
        start(player, crate, selection, completion, null);
    }

    public void start(Player player, CrateDefinition crate, CompletableFuture<RewardDefinition> selection,
                      Consumer<RewardDefinition> completion, Location origin) {
        if (origin != null) {
            animOrigins.put(player.getUniqueId(), origin);
        }
        UUID id = player.getUniqueId();
        pendingSelections.put(id, selection);

        selection.whenComplete((winner, err) -> {
            pendingSelections.remove(id);
            if (err != null || winner == null) {
                SchedulerUtil.run(plugin, () -> completion.accept(winner));
                return;
            }
            SchedulerUtil.run(plugin, () -> {
                if (!player.isOnline()) {
                    completion.accept(winner);
                    return;
                }
                String style = crate.getAnimation() == null || crate.getAnimation().isBlank()
                        ? plugin.getConfig().getString("settings.default-animation", "CHEST").toUpperCase()
                        : crate.getAnimation().toUpperCase();
                if ("NONE".equals(style) || "INSTANT".equals(style)) {
                    finishWithOptionalWin(player, crate, winner, completion);
                    return;
                }
                if ("HOLOGRAM".equals(style) || "ROULETTE".equals(style) || "DISPLAY".equals(style)) {
                    int maxConcurrent = plugin.getConfig().getInt("settings.max-concurrent-animations", 32);
                    int load = concurrentHologramAnims.get();
                    // Over capacity → instant reward (no entity spam)
                    if (load >= maxConcurrent) {
                        finishWithOptionalWin(player, crate, winner, completion);
                        return;
                    }
                    completions.put(id, completion);
                    winners.put(id, winner);
                    Location animOrigin = resolveCrateLocation(player, crate);
                    concurrentHologramAnims.incrementAndGet();
                    hologramRoulette.play(player, animOrigin, crate, winner, w -> {
                        concurrentHologramAnims.decrementAndGet();
                        Consumer<RewardDefinition> done = completions.remove(id);
                        winners.remove(id);
                        finishWithOptionalWin(player, crate, w, done);
                    });
                    return;
                }

                String anim = style;
                if ("CHEST".equals(anim) || "GRID".equals(anim) || "DEFAULT".equals(anim)) {
                    ChestAnimationGUI gui = new ChestAnimationGUI(plugin, crate, winner, itemFactory);
                    guiManager.openGUI(gui, player);
                    completions.put(id, completion);
                    winners.put(id, winner);
                    int interval = Math.max(1, plugin.getConfig().getInt("settings.animation-frame-ticks", 2));
                    SchedulerUtil.Cancellable task = SchedulerUtil.runTimer(plugin, () -> {
                        if (!player.isOnline()) {
                            forceFinish(id);
                            return;
                        }
                        gui.nextFrame(player);
                        if (gui.getAge() >= gui.getDuration()) {
                            cancel(id);
                            int hold = Math.max(0, plugin.getConfig().getInt("settings.animation-end-hold-ticks", 30));
                            SchedulerUtil.runLater(plugin, () -> {
                                if (player.isOnline()) player.closeInventory();
                                Consumer<RewardDefinition> done = completions.remove(id);
                                winners.remove(id);
                                finishWithOptionalWin(player, crate, winner, done);
                            }, hold);
                        }
                    }, interval, interval);
                    active.put(id, task);
                    return;
                }

                AnimationGUI gui = new AnimationGUI(plugin, crate, winner, itemFactory);
                guiManager.openGUI(gui, player);
                completions.put(id, completion);
                winners.put(id, winner);

                int interval = Math.max(1, plugin.getConfig().getInt("settings.animation-frame-ticks", 2));
                SchedulerUtil.Cancellable task = SchedulerUtil.runTimer(plugin, () -> {
                    if (!player.isOnline()) {
                        forceFinish(id);
                        return;
                    }
                    gui.nextFrame(player);
                    if (gui.getAge() >= gui.getDuration()) {
                        cancel(id);
                        int hold = Math.max(0, plugin.getConfig().getInt("settings.animation-end-hold-ticks", 30));
                        SchedulerUtil.runLater(plugin, () -> {
                            if (player.isOnline()) {
                                player.closeInventory();
                            }
                            Consumer<RewardDefinition> done = completions.remove(id);
                            winners.remove(id);
                            finishWithOptionalWin(player, crate, winner, done);
                        }, hold);
                    }
                }, interval, interval);
                active.put(id, task);
            });
        });
    }

    public void forceFinish(UUID playerId) {
        CompletableFuture<RewardDefinition> fut = pendingSelections.remove(playerId);
        if (fut != null && !fut.isDone()) {
            fut.cancel(true);
        }
        cancel(playerId);
        hologramRoulette.cancel(playerId);
        RewardDefinition winner = winners.remove(playerId);
        Consumer<RewardDefinition> done = completions.remove(playerId);
        if (done != null && winner != null) {
            done.accept(winner);
        }
    }

    public boolean isAnimating(UUID playerId) {
        return active.containsKey(playerId)
                || pendingSelections.containsKey(playerId)
                || completions.containsKey(playerId);
    }

    public void cancel(UUID playerId) {
        SchedulerUtil.Cancellable task = active.remove(playerId);
        if (task != null) {
            task.cancel();
        }
    }

    public int getConcurrentHologramAnims() {
        return concurrentHologramAnims.get();
    }

    public void cancelAll() {
        pendingSelections.values().forEach(f -> {
            if (!f.isDone()) {
                f.cancel(true);
            }
        });
        pendingSelections.clear();
        active.values().forEach(SchedulerUtil.Cancellable::cancel);
        active.clear();
        for (UUID id : new java.util.ArrayList<>(completions.keySet())) {
            hologramRoulette.cancel(id);
        }
        completions.clear();
        winners.clear();
        concurrentHologramAnims.set(0);
    }

    private Location resolveCrateLocation(Player player, CrateDefinition crate) {
        Location stored = animOrigins.remove(player.getUniqueId());
        if (stored != null) {
            return stored;
        }
        return player.getLocation();
    }

    private void finishWithOptionalWin(Player player, CrateDefinition crate, RewardDefinition winner,
                                       Consumer<RewardDefinition> done) {
        if (player != null && player.isOnline()
                && plugin.getConfig().getBoolean("settings.win-screen", true)
                && winner != null) {
            try {
                int hold = plugin.getConfig().getInt("settings.win-screen-ticks", 40);
                int pity = 0;
                if (playerData != null && crate != null) {
                    pity = playerData.getPity(player.getUniqueId(), crate.getId());
                }
                double pct = 0;
                try {
                    pct = RewardSelector.normalizedPercent(winner, crate, player);
                } catch (Throwable ignored) {
                }
                WinGUI.show(plugin, guiManager, player, winner, itemFactory,
                        crate != null ? crate.getDisplayName() : "?", hold, pity, pct);
                SchedulerUtil.runLater(plugin, () -> {
                    if (done != null) {
                        done.accept(winner);
                    }
                }, hold + 5L);
                return;
            } catch (Throwable ignored) {
            }
        }
        if (done != null) {
            done.accept(winner);
        }
    }
}
