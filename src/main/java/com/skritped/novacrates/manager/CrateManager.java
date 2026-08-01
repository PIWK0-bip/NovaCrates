package com.skritped.novacrates.manager;

import com.skritped.novacrates.animation.AnimationController;
import com.skritped.novacrates.event.CrateMultiOpenEvent;
import com.skritped.novacrates.event.CrateOpenEvent;
import com.skritped.novacrates.event.CrateRewardEvent;
import com.skritped.novacrates.event.OfflineQueueGrantEvent;
import com.skritped.novacrates.model.CostDebt;
import com.skritped.novacrates.model.CostDefinition;
import com.skritped.novacrates.model.CrateDefinition;
import com.skritped.novacrates.model.DropRecord;
import com.skritped.novacrates.model.OfflineOpen;
import com.skritped.novacrates.model.PendingOpen;
import com.skritped.novacrates.model.RewardDefinition;
import com.skritped.novacrates.service.BattlePassService;
import com.skritped.novacrates.service.CostService;
import com.skritped.novacrates.service.DiscordWebhookService;
import com.skritped.novacrates.service.HologramService;
import com.skritped.novacrates.service.ItemFactory;
import com.skritped.novacrates.service.KeyService;
import com.skritped.novacrates.service.MessageService;
import com.skritped.novacrates.service.RewardGrantService;
import com.skritped.novacrates.service.RewardSelector;
import com.skritped.novacrates.storage.CrateRepository;
import com.skritped.novacrates.storage.PlayerDataRepository;
import com.skritped.novacrates.util.Text;
import com.cryptomorin.xseries.XMaterial;
import org.bukkit.Bukkit;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class CrateManager {
    private final JavaPlugin plugin;
    private final PlayerDataRepository playerData;
    private final CrateRepository crateRepository;
    private final KeyService keyService;
    private final AnimationController animationController;
    private final ItemFactory itemFactory;
    private final CostService costService;
    private final RewardGrantService grantService;
    private final HologramService hologramService;
    private final MessageService messages;
    private final DiscordWebhookService discordWebhook;
    private final BattlePassService battlePassService;
    private final Map<String, CrateDefinition> crates = new LinkedHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<UUID, Long> lastAnimAt = new java.util.concurrent.ConcurrentHashMap<>();
    private final Set<UUID> openingPlayers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, java.util.concurrent.CompletableFuture<RewardDefinition>> asyncOpenFutures = new ConcurrentHashMap<>();
    private final ThreadLocal<Boolean> multiGuaranteeUsed = ThreadLocal.withInitial(() -> false);
    private final ThreadLocal<Boolean> multiPityUsed = ThreadLocal.withInitial(() -> false);
    private final ThreadLocal<java.util.List<DropRecord>> multiHistoryBuffer = new ThreadLocal<>();
    private final ConcurrentHashMap<UUID, long[]> playerOpenRate = new ConcurrentHashMap<>(); // [windowStartMs, count]
    private final java.util.concurrent.atomic.AtomicInteger opensThisSecond = new java.util.concurrent.atomic.AtomicInteger();
    private volatile long opensSecondEpoch = System.currentTimeMillis() / 1000L;
    private final long pendingTimeoutMillis;

    public CrateManager(JavaPlugin plugin,
                        PlayerDataRepository playerData,
                        CrateRepository crateRepository,
                        KeyService keyService,
                        AnimationController animationController,
                        ItemFactory itemFactory,
                        CostService costService,
                        RewardGrantService grantService,
                        HologramService hologramService,
                        MessageService messages,
                        DiscordWebhookService discordWebhook,
                        BattlePassService battlePassService) {
        this.plugin = plugin;
        this.playerData = playerData;
        this.crateRepository = crateRepository;
        this.keyService = keyService;
        this.animationController = animationController;
        this.itemFactory = itemFactory;
        this.costService = costService;
        this.grantService = grantService;
        this.hologramService = hologramService;
        this.messages = messages;
        this.discordWebhook = discordWebhook;
        this.battlePassService = battlePassService;
        this.pendingTimeoutMillis = plugin.getConfig().getLong("settings.pending-timeout-seconds", 300) * 1000L;
        reload();
    }

    public void reload() {
        crates.clear();
        crates.putAll(crateRepository.load());
        cleanupRewardLore();
        validateCrates();
        hologramService.setOnHologramLinesChanged(this::setHologramLines);
        hologramService.respawnAll(playerData.getBlocks(), this::getCrate);
        plugin.getLogger().info("Loaded " + crates.size() + " crate(s).");
    }

    /** Startup/reload validation: duplicates, missing pity, unlock cycles, zero weights. */
    private void validateCrates() {
        for (CrateDefinition c : crates.values()) {
            if (c.getRewards().isEmpty()) {
                plugin.getLogger().warning("Crate '" + c.getId() + "' has no rewards");
            }
            double sum = 0;
            java.util.Set<String> ids = new java.util.HashSet<>();
            for (RewardDefinition r : c.getRewards()) {
                sum += Math.max(0, r.getChance());
                if (!ids.add(r.getId().toLowerCase(Locale.ROOT))) {
                    plugin.getLogger().warning("Crate '" + c.getId() + "' duplicate reward id: " + r.getId());
                }
            }
            if (sum <= 0 && !c.getRewards().isEmpty()) {
                plugin.getLogger().warning("Crate '" + c.getId() + "' has total weight 0");
            }
            if (c.getPityThreshold() > 0 && c.getPityRewardId() != null) {
                boolean found = c.getRewards().stream().anyMatch(r -> r.getId().equals(c.getPityRewardId()));
                if (!found) {
                    plugin.getLogger().warning("Crate '" + c.getId() + "' pity reward missing: " + c.getPityRewardId());
                }
            }
            if (c.getUnlockRequiresCrate() != null) {
                CrateDefinition req = crates.get(c.getUnlockRequiresCrate().toLowerCase(Locale.ROOT));
                if (req == null) {
                    plugin.getLogger().warning("Crate '" + c.getId() + "' unlock requires missing crate: " + c.getUnlockRequiresCrate());
                } else if (req.getUnlockRequiresCrate() != null
                        && req.getUnlockRequiresCrate().equalsIgnoreCase(c.getId())) {
                    plugin.getLogger().warning("Unlock cycle between '" + c.getId() + "' and '" + req.getId() + "'");
                }
            }
        }
    }

    /** Reload crates.yml + messages only; optional hologram refresh. */
    public void reloadLight(boolean refreshHolograms) {
        crates.clear();
        crates.putAll(crateRepository.load());
        cleanupRewardLore();
        messages.reload();
        if (refreshHolograms) {
            hologramService.respawnAll(playerData.getBlocks(), this::getCrate);
        }
        plugin.getLogger().info("Light reload: " + crates.size() + " crate(s).");
    }

    public Map<String, CrateDefinition> getCrates() {
        return Collections.unmodifiableMap(crates);
    }

    public CrateDefinition getCrate(String id) {
        if (id == null) {
            return null;
        }
        return crates.get(id.toLowerCase(Locale.ROOT));
    }

    public ItemFactory getItemFactory() {
        return itemFactory;
    }

    public java.util.Map<String, Integer> getAllVirtualKeys(java.util.UUID playerId) {
        return playerData.getAllVirtualKeys(playerId);
    }

    public PlayerDataRepository getPlayerData() {
        return playerData;
    }

    public CrateRepository getCrateRepository() {
        return crateRepository;
    }

    public HologramService getHologramService() {
        return hologramService;
    }

    public MessageService getMessages() {
        return messages;
    }

    public boolean isOpening(UUID uuid) {
        return openingPlayers.contains(uuid);
    }


    /** Opens a single crate and completes the future when the reward is granted. */
    public java.util.concurrent.CompletableFuture<RewardDefinition> openCrateAsync(Player player, String id) {
        java.util.concurrent.CompletableFuture<RewardDefinition> fut = new java.util.concurrent.CompletableFuture<>();
        asyncOpenFutures.put(player.getUniqueId(), fut);
        openCrate(player, id, 1);
        // If never entered opening (rejected), clear after 1 tick
        long timeoutTicks = Math.max(40, plugin.getConfig().getLong("settings.async-open-timeout-ticks", 200));
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            java.util.concurrent.CompletableFuture<RewardDefinition> f = asyncOpenFutures.get(player.getUniqueId());
            if (f == fut && !fut.isDone()) {
                // Still pending after timeout — only fail if not animating/opening
                if (!openingPlayers.contains(player.getUniqueId()) && !animationController.isAnimating(player.getUniqueId())) {
                    asyncOpenFutures.remove(player.getUniqueId(), fut);
                    fut.completeExceptionally(new IllegalStateException("Open rejected or timed out"));
                }
            }
        }, timeoutTicks);
        return fut;
    }

    public void openCrate(Player player, String id) {
        openCrate(player, id, 1);
    }

    public boolean needsCostConfirm(CrateDefinition crate, int times) {
        if (crate == null || crate.getCost().isFree()) return false;
        double threshold = plugin.getConfig().getDouble("settings.cost-confirm-threshold", 10000);
        if (threshold <= 0) return false;
        return crate.getCost().getAmount() * times >= threshold;
    }

    public void openCrateConfirmed(Player player, String id, int times) {
        openCrateInternal(player, id, times, true, false);
    }

    public void openCrate(Player player, String id, int times) {
        openCrate(player, id, times, false);
    }

    /** @param skipAnimation true = instant grant, no animation */
    public void openCrate(Player player, String id, int times, boolean skipAnimation) {
        openCrateInternal(player, id, times, true, skipAnimation);
    }

    private void openCrateInternal(Player player, String id, int times, boolean confirmed) {
        openCrateInternal(player, id, times, confirmed, false);
    }

    private void openCrateInternal(Player player, String id, int times, boolean confirmed, boolean skipAnimation) {
        CrateDefinition crate = getCrate(id);
        if (crate == null || crate.getRewards().isEmpty()) {
            messages.send(player, "crate-not-found");
            return;
        }
        if (!crate.isAvailableNow()) {
            messages.send(player, "crate-unavailable");
            return;
        }
        if (!isUnlocked(player, crate)) {
            messages.send(player, "crate-locked", Map.of(
                    "crate", messages.color(crate.getDisplayName()),
                    "requires", crate.getUnlockRequiresCrate() == null ? "?" : crate.getUnlockRequiresCrate(),
                    "opens", String.valueOf(crate.getUnlockRequiresOpens())));
            return;
        }
        if (isWorldBlocked(player)) {
            messages.send(player, "world-blocked");
            return;
        }
        if (openingPlayers.contains(player.getUniqueId())) {
            messages.send(player, "already-opening");
            return;
        }
        if (!tryConsumePlayerRate(player.getUniqueId())) {
            messages.send(player, "rate-limited");
            return;
        }
        if (!tryConsumeGlobalRate()) {
            messages.send(player, "rate-limited");
            return;
        }

        int maxMulti = plugin.getConfig().getInt("settings.multi-open-max", 50);
        times = Math.max(1, Math.min(times, maxMulti));

        if (crate.getCooldownSeconds() > 0) {
            long last = playerData.getLastOpen(player.getUniqueId(), crate.getId());
            long remain = (last + crate.getCooldownSeconds() * 1000L) - System.currentTimeMillis();
            if (remain > 0) {
                messages.send(player, "cooldown", Map.of("seconds", String.valueOf((remain + 999) / 1000)));
                return;
            }
        }
        if (!checkDailyLimit(player)) {
            messages.send(player, "daily-limit", Map.of(
                    "limit", String.valueOf(resolveDailyLimit(player)),
                    "used", String.valueOf(playerData.getDailyOpens(player.getUniqueId()))));
            return;
        }
        if (!checkCrateDailyLimit(player, crate)) {
            messages.send(player, "crate-daily-limit", Map.of(
                    "crate", messages.color(crate.getDisplayName()),
                    "limit", String.valueOf(resolveCrateDailyLimit(crate)),
                    "used", String.valueOf(playerData.getDailyCrateOpens(player.getUniqueId(), crate.getId()))));
            return;
        }

        if (times == 1) {
            openOnce(player, crate, skipAnimation);
            return;
        }

        // Multi-open: respect multi-open-skip-animation (default true = no animation)
        boolean skipAnim = plugin.getConfig().getBoolean("settings.multi-open-skip-animation", true);
        openingPlayers.add(player.getUniqueId());
        int opened = 0;
        multiGuaranteeUsed.set(false);
        multiPityUsed.set(false);
        multiHistoryBuffer.set(new ArrayList<>());
        List<RewardDefinition> multiRewards = new ArrayList<>();
        try {
            for (int i = 0; i < times; i++) {
                RewardDefinition r = tryConsumeAndRoll(player, crate);
                if (r == null) {
                    break;
                }
                multiRewards.add(r);
                opened++;
            }
        } finally {
            List<DropRecord> buf = multiHistoryBuffer.get();
            multiHistoryBuffer.remove();
            if (buf != null && !buf.isEmpty()) {
                playerData.addHistoryBatch(player.getUniqueId(), buf);
            }
            multiGuaranteeUsed.remove();
            multiPityUsed.remove();
            openingPlayers.remove(player.getUniqueId());
        }
        if (opened > 0) {
            messages.send(player, "multi-open-done", Map.of(
                    "count", String.valueOf(opened),
                    "crate", messages.color(crate.getDisplayName())));
            try {
                Bukkit.getPluginManager().callEvent(
                        new CrateMultiOpenEvent(player, crate, opened, multiRewards));
            } catch (Throwable ignored) {
            }
        }
        // skipAnim is intentional: multi path never uses AnimationController
        if (!skipAnim && opened == 1) {
            // reserved for future single-with-anim multi hybrid
        }
    }

    private void openOnce(Player player, CrateDefinition crate, boolean skipAnimation) {
        CrateOpenEvent openEvent = new CrateOpenEvent(player, crate);
        Bukkit.getPluginManager().callEvent(openEvent);
        if (openEvent.isCancelled()) {
            return;
        }
        if (!costService.canPay(player, crate.getCost()) || !hasKey(player, crate.getKeyId())) {
            messages.send(player, !hasKey(player, crate.getKeyId()) ? "key-required" : "cost-required");
            return;
        }
        if (!openingPlayers.add(player.getUniqueId())) {
            messages.send(player, "already-opening");
            return;
        }

        boolean usedVirtual = playerData.getVirtualKeys(player.getUniqueId(), crate.getKeyId()) > 0;
        if (!costService.pay(player, crate.getCost()) || !consumeKey(player, crate.getKeyId())) {
            openingPlayers.remove(player.getUniqueId());
            messages.send(player, "cost-required");
            return;
        }

        CostDefinition cost = crate.getCost();
        PendingOpen pending = new PendingOpen(
                player.getUniqueId(), crate.getId(), crate.getKeyId(), usedVirtual,
                cost.getType(), cost.getAmount(), cost.getMaterial(),
                System.currentTimeMillis());
        playerData.setPending(pending);
        messages.send(player, "opening", Map.of("crate", messages.color(crate.getDisplayName())));

        int pity = playerData.getPity(player.getUniqueId(), crate.getId());
        if (skipAnimation) {
            try {
                int opensNow2 = getPlayerCrateOpens(player.getUniqueId(), crate.getId()) + 1;
        RewardDefinition g2 = pickGuaranteed(player, crate, opensNow2);
        completeOpen(player, pending, crate, g2 != null ? g2 : RewardSelector.select(crate, pity, player));
            } finally {
                openingPlayers.remove(player.getUniqueId());
            }
            return;
        }

        // Select on main thread so CratePreSelectEvent works; wrap in completed future
        int opensNow = getPlayerCrateOpens(player.getUniqueId(), crate.getId()) + 1;
                RewardDefinition guaranteed = pickGuaranteed(player, crate, opensNow);
                RewardDefinition selected = guaranteed != null ? guaranteed : RewardSelector.select(crate, pity, player);
        CompletableFuture<RewardDefinition> selection = CompletableFuture.completedFuture(selected);
        org.bukkit.Location animLoc = findNearestCrateLocation(player, crate.getId());
        animationController.start(player, crate, selection, reward -> {
            try {
                completeOpen(player, pending, crate, reward);
            } finally {
                openingPlayers.remove(player.getUniqueId());
            }
        }, animLoc);
    }

    /** @return selected reward, or null if open failed */
    private RewardDefinition tryConsumeAndRoll(Player player, CrateDefinition crate) {
        CrateOpenEvent openEvent = new CrateOpenEvent(player, crate);
        Bukkit.getPluginManager().callEvent(openEvent);
        if (openEvent.isCancelled()) {
            return null;
        }
        if (!checkDailyLimit(player)) {
            messages.send(player, "daily-limit", Map.of(
                    "limit", String.valueOf(resolveDailyLimit(player)),
                    "used", String.valueOf(playerData.getDailyOpens(player.getUniqueId()))));
            return null;
        }
        if (!costService.canPay(player, crate.getCost()) || !hasKey(player, crate.getKeyId())) {
            messages.send(player, !hasKey(player, crate.getKeyId()) ? "key-required" : "cost-required");
            return null;
        }
        boolean usedVirtual = playerData.getVirtualKeys(player.getUniqueId(), crate.getKeyId()) > 0;
        if (!costService.pay(player, crate.getCost()) || !consumeKey(player, crate.getKeyId())) {
            messages.send(player, "cost-required");
            return null;
        }
        CostDefinition cost = crate.getCost();
        PendingOpen pending = new PendingOpen(
                player.getUniqueId(), crate.getId(), crate.getKeyId(), usedVirtual,
                cost.getType(), cost.getAmount(), cost.getMaterial(),
                System.currentTimeMillis());
        playerData.setPending(pending);
        int pity = playerData.getPity(player.getUniqueId(), crate.getId());
        // pity-once-per-multi: if hard pity already used in this multi, skip forcing
        if (plugin.getConfig().getBoolean("settings.pity-once-per-multi", true)
                && Boolean.TRUE.equals(multiPityUsed.get())
                && crate.getPityThreshold() > 0
                && pity >= crate.getPityThreshold()) {
            pity = Math.max(0, crate.getPityThreshold() - 1);
        }
        int opensNow2 = getPlayerCrateOpens(player.getUniqueId(), crate.getId()) + 1;
        RewardDefinition g2 = pickGuaranteed(player, crate, opensNow2);
        RewardDefinition selected = g2 != null ? g2 : RewardSelector.select(crate, pity, player);
        if (crate.getPityThreshold() > 0 && selected.getId().equals(crate.getPityRewardId())
                && pity >= crate.getPityThreshold()) {
            multiPityUsed.set(true);
        }
        completeOpen(player, pending, crate, selected);
        return selected;
    }

    private boolean isWorldBlocked(Player player) {
        List<String> blocked = plugin.getConfig().getStringList("settings.blocked-worlds");
        if (blocked != null) {
            for (String w : blocked) {
                if (player.getWorld().getName().equalsIgnoreCase(w)) {
                    return true;
                }
            }
        }
        List<String> regions = plugin.getConfig().getStringList("settings.blocked-regions");
        boolean checkWg = Bukkit.getPluginManager().getPlugin("WorldGuard") != null;
        if (checkWg) {
            try {
                Class<?> wg = Class.forName("com.sk89q.worldguard.WorldGuard");
                Object instance = wg.getMethod("getInstance").invoke(null);
                Object platform = instance.getClass().getMethod("getPlatform").invoke(instance);
                Object regionContainer = platform.getClass().getMethod("getRegionContainer").invoke(platform);
                Object query = regionContainer.getClass().getMethod("createQuery").invoke(regionContainer);
                Class<?> bukkitAdapter = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter");
                Object loc = bukkitAdapter.getMethod("adapt", org.bukkit.Location.class)
                        .invoke(null, player.getLocation());
                Object set = query.getClass().getMethod("getApplicableRegions", loc.getClass()).invoke(query, loc);
                for (Object region : (Iterable<?>) set) {
                    String id = (String) region.getClass().getMethod("getId").invoke(region);
                    if (regions != null) {
                        for (String blockedRegion : regions) {
                            if (blockedRegion.equalsIgnoreCase(id)) {
                                return true;
                            }
                        }
                    }
                    // Custom flag novacrates-open = DENY blocks opening
                    try {
                        Object flags = region.getClass().getMethod("getFlags").invoke(region);
                        if (flags instanceof java.util.Map<?, ?> map) {
                            for (var e : map.entrySet()) {
                                String fname = String.valueOf(e.getKey()).toLowerCase();
                                if (fname.contains("novacrates") && String.valueOf(e.getValue()).toUpperCase().contains("DENY")) {
                                    return true;
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private boolean hasKey(Player player, String keyId) {
        boolean virtualOk = plugin.getConfig().getBoolean("settings.virtual-keys-enabled", true)
                && playerData.getVirtualKeys(player.getUniqueId(), keyId) > 0;
        String mode = plugin.getConfig().getString("settings.keys-mode", "both"); // both|virtual-only|physical-only
        // Per-crate override: crates.<id>.keys-mode in definition would need model field — use global + optional list
        if (plugin.getConfig().getStringList("settings.virtual-only-crates").stream()
                .anyMatch(id -> id.equalsIgnoreCase(keyId))) {
            mode = "virtual-only";
        }
        if ("virtual-only".equalsIgnoreCase(mode)) {
            return virtualOk;
        }
        boolean physicalOk = plugin.getConfig().getBoolean("settings.physical-keys-enabled", true)
                && keyService.hasPhysicalKey(player, keyId);
        if ("physical-only".equalsIgnoreCase(mode)) {
            return physicalOk;
        }
        return virtualOk || physicalOk;
    }

    private boolean consumeKey(Player player, String keyId) {
        if (plugin.getConfig().getBoolean("settings.virtual-keys-enabled", true)
                && playerData.getVirtualKeys(player.getUniqueId(), keyId) > 0) {
            return keyService.consumeVirtualKey(player, keyId, playerData);
        }
        if (plugin.getConfig().getBoolean("settings.physical-keys-enabled", true)) {
            return keyService.consumePhysicalKey(player, keyId);
        }
        return false;
    }

    private void completeOpen(Player player, PendingOpen pending, CrateDefinition crate, RewardDefinition reward) {
        if (!player.isOnline()) {
            recover(pending);
            return;
        }
        // Event BEFORE grant — cancel refunds key/cost via recover
        boolean pityFlag = reward.getId().equals(crate.getPityRewardId());
        CrateRewardEvent rewEv = new CrateRewardEvent(player, crate, reward, pityFlag);
        Bukkit.getPluginManager().callEvent(rewEv);
        if (rewEv.isCancelled()) {
            recover(pending);
            releaseOpening(player.getUniqueId());
            messages.send(player, "crate-unavailable");
            return;
        }
        reward = rewEv.getReward() != null ? rewEv.getReward() : reward;

        boolean dropped = grantService.grant(player, reward);
        if (dropped) {
            messages.send(player, "inventory-full");
        }
        java.util.concurrent.CompletableFuture<RewardDefinition> asyncFut = asyncOpenFutures.remove(player.getUniqueId());
        if (asyncFut != null && !asyncFut.isDone()) {
            asyncFut.complete(reward);
        }

        int pity = playerData.getPity(player.getUniqueId(), crate.getId());
        boolean wasPity = reward.getId().equals(crate.getPityRewardId());
        int newPity = wasPity ? 0 : pity + 1;
        playerData.setPity(player.getUniqueId(), crate.getId(), newPity);
        showPityBossBar(player, crate, newPity);
        // Actionbar pity always
        if (plugin.getConfig().getBoolean("settings.pity-actionbar", true) && crate.getPityThreshold() > 0) {
            try {
                player.sendActionBar(Text.component("&dPity &f" + newPity + "&7/&f" + crate.getPityThreshold()));
            } catch (Throwable ignored) {
            }
        }
        if (lastAnimAt != null) {
            lastAnimAt.put(player.getUniqueId(), System.currentTimeMillis());
        }
        playerData.setLastOpen(player.getUniqueId(), crate.getId(), System.currentTimeMillis());
        playerData.removePending(player.getUniqueId());
        DropRecord dropRec = new DropRecord(
                crate.getId(), reward.getId(), reward.getDisplayName(), System.currentTimeMillis());
        List<DropRecord> histBuf = multiHistoryBuffer.get();
        if (histBuf != null) {
            histBuf.add(dropRec);
        } else {
            playerData.addHistory(player.getUniqueId(), dropRec);
        }
        playerData.logDrop(player.getUniqueId(), player.getName(), crate.getId(),
                reward.getId(), reward.getDisplayName());
        if (plugin.getConfig().getBoolean("settings.metrics-enabled", true)) {
            playerData.incrementStat("opens.total");
            playerData.incrementStat("opens." + crate.getId());
        }
        incrementPlayerCrateOpens(player.getUniqueId(), crate.getId());

        playerData.incrementDailyOpens(player.getUniqueId());
        playerData.incrementDailyCrateOpens(player.getUniqueId(), crate.getId());

        Map<String, String> ph = Map.of(
                "reward", messages.color(reward.getDisplayName()),
                "crate", messages.color(crate.getDisplayName()),
                "player", player.getName());
        messages.send(player, "won", ph);
        messages.title(player, "won-title", "won-subtitle", ph);

        int animLoad = 0;
        try { animLoad = animationController.getConcurrentHologramAnims(); } catch (Throwable ignored) {}
        boolean scaleQuiet = animLoad >= plugin.getConfig().getInt("settings.scale-soft-cap", 12);

        playRaritySound(player, reward.getRarity());
        if (!scaleQuiet) {
            spawnWinParticles(player, reward.getRarity());
        }

        if (wasPity) {
            messages.send(player, "pity-triggered", ph);
        }

        if (shouldBroadcast(reward)) {
            org.bukkit.Bukkit.getServer().sendMessage(
                    com.skritped.novacrates.util.Text.component(messages.format("broadcast", ph)));
        }

        if (discordWebhook != null && !scaleQuiet) {
            discordWebhook.maybeBroadcast(player, crate, reward);
        }

        maybeMilestone(player);
        maybeCrateMilestone(player, crate);
        if (battlePassService != null) {
            battlePassService.onCrateOpened(player, crate);
        }
        checkAutoUnlocks(player);
        if (plugin.getConfig().getBoolean("settings.debug", false)) {
            plugin.getLogger().info(player.getName() + " won " + reward.getId() + " from " + crate.getId());
        }
    }

    private void maybeCrateMilestone(Player player, CrateDefinition crate) {
        if (crate.getMilestoneEvery() <= 0 || crate.getMilestoneRewardId() == null) {
            return;
        }
        long opens = playerData.getStat("opens." + crate.getId());
        if (opens > 0 && opens % crate.getMilestoneEvery() == 0) {
            for (RewardDefinition r : crate.getRewards()) {
                if (r.getId().equals(crate.getMilestoneRewardId())) {
                    grantService.grant(player, r);
                    messages.send(player, "crate-milestone", Map.of(
                            "count", String.valueOf(opens),
                            "crate", messages.color(crate.getDisplayName()),
                            "reward", messages.color(r.getDisplayName())));
                    break;
                }
            }
        }
    }

    private void spawnWinParticles(Player player, String rarity) {
        if (!plugin.getConfig().getBoolean("settings.win-particles", true)) {
            return;
        }
        try {
            var loc = player.getLocation().add(0, 1.2, 0);
            String r = rarity == null ? "COMMON" : rarity.toUpperCase(Locale.ROOT);
            Particle particle = switch (r) {
                case "MYTHIC", "LEGENDARY" -> Particle.TOTEM_OF_UNDYING;
                case "EPIC" -> Particle.END_ROD;
                case "RARE" -> Particle.HAPPY_VILLAGER;
                default -> Particle.CRIT;
            };
            player.getWorld().spawnParticle(particle, loc, 25, 0.4, 0.5, 0.4, 0.05);
        } catch (Throwable ignored) {
        }
    }

    private boolean shouldBroadcast(RewardDefinition reward) {
        if (reward.isBroadcast()) {
            return true;
        }
        java.util.List<String> rarities = plugin.getConfig().getStringList("settings.broadcast-rarities");
        if (rarities == null || rarities.isEmpty()) {
            return false;
        }
        for (String r : rarities) {
            if (r.equalsIgnoreCase(reward.getRarity())) {
                return true;
            }
        }
        return false;
    }

    private void playRaritySound(Player player, String rarity) {
        String path = "settings.sounds." + (rarity == null ? "COMMON" : rarity.toUpperCase(Locale.ROOT));
        String soundName = plugin.getConfig().getString(path, "ENTITY_PLAYER_LEVELUP");
        try {
            org.bukkit.Sound sound = org.bukkit.Sound.valueOf(soundName);
            player.playSound(player.getLocation(), sound, 1f, 1f);
        } catch (Exception ignored) {
        }
    }

    private void maybeMilestone(Player player) {
        if (!plugin.getConfig().getBoolean("milestones.enabled", true)) {
            return;
        }
        int every = plugin.getConfig().getInt("milestones.every", 100);
        if (every <= 0) {
            return;
        }
        long total = playerData.getStat("opens.total");
        if (total > 0 && total % every == 0) {
            Map<String, String> ph = Map.of(
                    "player", player.getName(),
                    "count", String.valueOf(total));
            for (String cmd : plugin.getConfig().getStringList("milestones.commands")) {
                String parsed = cmd.replace("%player%", player.getName()).replace("%count%", String.valueOf(total));
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), parsed.startsWith("/") ? parsed.substring(1) : parsed);
            }
            String key = plugin.getConfig().getString("milestones.give-virtual-key");
            int amount = plugin.getConfig().getInt("milestones.give-virtual-key-amount", 1);
            if (key != null && !key.isBlank()) {
                keyService.giveVirtualKey(player, key, playerData, amount);
            }
            messages.send(player, "milestone", ph);
        }
    }

    
    private boolean tryConsumePlayerRate(UUID playerId) {
        int limit = plugin.getConfig().getInt("settings.player-open-rate-limit", 5); // per second
        if (limit <= 0) return true;
        long now = System.currentTimeMillis();
        long[] arr = playerOpenRate.compute(playerId, (k, v) -> {
            if (v == null || now - v[0] >= 1000L) {
                return new long[]{now, 1};
            }
            v[1]++;
            return v;
        });
        return arr[1] <= limit;
    }

private boolean tryConsumeGlobalRate() {
        int limit = plugin.getConfig().getInt("settings.global-open-rate-limit", 80);
        if (limit <= 0) {
            return true;
        }
        long epoch = System.currentTimeMillis() / 1000L;
        if (epoch != opensSecondEpoch) {
            opensSecondEpoch = epoch;
            opensThisSecond.set(0);
        }
        return opensThisSecond.incrementAndGet() <= limit;
    }

    
    
    public boolean duplicateReward(String crateId, String rewardId) {
        CrateDefinition existing = getCrate(crateId);
        if (existing == null) return false;
        RewardDefinition src = existing.getRewards().stream().filter(r -> r.getId().equals(rewardId)).findFirst().orElse(null);
        if (src == null) return false;
        String newId = rewardId + "_copy";
        int n = 2;
        while (true) {
            final String candidate = newId;
            boolean exists = false;
            for (RewardDefinition r : existing.getRewards()) {
                if (r.getId().equals(candidate)) {
                    exists = true;
                    break;
                }
            }
            if (!exists) break;
            newId = rewardId + "_copy" + n++;
        }
        List<RewardDefinition> updated = new ArrayList<>(existing.getRewards());
        updated.add(RewardDefinition.builder()
                .id(newId)
                .material(src.getMaterial())
                .amount(src.getAmount())
                .chance(src.getChance())
                .displayChance(src.getDisplayChance())
                .displayName(src.getDisplayName())
                .lore(src.getLore())
                .commands(src.getCommands())
                .commandAsPlayer(src.isCommandAsPlayer())
                .customModelData(src.getCustomModelData())
                .texture(src.getTexture())
                .rarity(src.getRarity())
                .broadcast(src.isBroadcast())
                .enchantments(src.getEnchantments())
                .permission(src.getPermission())
                .build());
        crates.put(crateId.toLowerCase(Locale.ROOT), existing.withRewards(updated));
        crateRepository.backupBeforeSave();
        crateRepository.saveRewards(existing.getId(), updated);
        return true;
    }

    
    public void updateRewardLore(String crateId, String rewardId, java.util.List<String> lore) {
        CrateDefinition existing = getCrate(crateId);
        if (existing == null) return;
        List<RewardDefinition> updated = new ArrayList<>();
        for (RewardDefinition r : existing.getRewards()) {
            if (!r.getId().equals(rewardId)) {
                updated.add(r);
                continue;
            }
            updated.add(RewardDefinition.builder()
                    .id(r.getId()).material(r.getMaterial()).amount(r.getAmount())
                    .chance(r.getChance()).displayChance(r.getDisplayChance())
                    .displayName(r.getDisplayName()).lore(lore == null ? List.of() : lore)
                    .commands(r.getCommands()).commandAsPlayer(r.isCommandAsPlayer())
                    .customModelData(r.getCustomModelData()).texture(r.getTexture())
                    .rarity(r.getRarity()).broadcast(r.isBroadcast())
                    .enchantments(r.getEnchantments()).permission(r.getPermission())
                    .build());
        }
        crates.put(crateId.toLowerCase(Locale.ROOT), existing.withRewards(updated));
        crateRepository.backupBeforeSave();
        crateRepository.saveRewards(existing.getId(), updated);
    }

public void updateRewardCommands(String crateId, String rewardId, java.util.List<String> commands) {
        CrateDefinition existing = getCrate(crateId);
        if (existing == null) return;
        List<RewardDefinition> updated = new ArrayList<>();
        for (RewardDefinition r : existing.getRewards()) {
            if (!r.getId().equals(rewardId)) {
                updated.add(r);
                continue;
            }
            updated.add(RewardDefinition.builder()
                    .id(r.getId())
                    .material(r.getMaterial())
                    .amount(r.getAmount())
                    .chance(r.getChance())
                    .displayChance(r.getDisplayChance())
                    .displayName(r.getDisplayName())
                    .lore(r.getLore())
                    .commands(commands == null ? List.of() : new ArrayList<>(commands))
                    .commandAsPlayer(r.isCommandAsPlayer())
                    .customModelData(r.getCustomModelData())
                    .texture(r.getTexture())
                    .rarity(r.getRarity())
                    .broadcast(r.isBroadcast())
                    .enchantments(r.getEnchantments())
                    .permission(r.getPermission())
                    .build());
        }
        crates.put(crateId.toLowerCase(Locale.ROOT), existing.withRewards(updated));
        crateRepository.backupBeforeSave();
        crateRepository.saveRewards(existing.getId(), updated);
    }

    public void updateRewardAmount(String crateId, String rewardId, int amount) {
        CrateDefinition existing = getCrate(crateId);
        if (existing == null) return;
        java.util.List<RewardDefinition> updated = new java.util.ArrayList<>();
        for (RewardDefinition r : existing.getRewards()) {
            if (!r.getId().equals(rewardId)) {
                updated.add(r);
                continue;
            }
            updated.add(RewardDefinition.builder()
                    .id(r.getId()).material(r.getMaterial()).amount(Math.max(1, amount))
                    .chance(r.getChance()).displayChance(r.getDisplayChance())
                    .displayName(r.getDisplayName()).lore(r.getLore())
                    .commands(r.getCommands()).commandAsPlayer(r.isCommandAsPlayer())
                    .customModelData(r.getCustomModelData()).texture(r.getTexture())
                    .rarity(r.getRarity()).broadcast(r.isBroadcast())
                    .enchantments(r.getEnchantments()).permission(r.getPermission())
                    .build());
        }
        crates.put(crateId.toLowerCase(java.util.Locale.ROOT), existing.withRewards(updated));
        crateRepository.backupBeforeSave();
        crateRepository.saveRewards(existing.getId(), updated);
    }

    public void updateRewardFields(String crateId, String rewardId, double weight,
                                   Double displayChance, String rarity, boolean broadcast) {
        updateRewardFields(crateId, rewardId, weight, displayChance, rarity, broadcast, null);
    }

    public void updateRewardFields(String crateId, String rewardId, double weight,
                                   Double displayChance, String rarity, boolean broadcast, String permission) {
        CrateDefinition existing = getCrate(crateId);
        if (existing == null) {
            return;
        }
        List<RewardDefinition> updated = new ArrayList<>();
        for (RewardDefinition r : existing.getRewards()) {
            if (!r.getId().equals(rewardId)) {
                updated.add(r);
                continue;
            }
            updated.add(RewardDefinition.builder()
                    .id(r.getId())
                    .material(r.getMaterial())
                    .amount(r.getAmount())
                    .chance(Math.max(0, weight))
                    .displayChance(displayChance)
                    .displayName(r.getDisplayName())
                    .lore(r.getLore())
                    .commands(r.getCommands())
                    .commandAsPlayer(r.isCommandAsPlayer())
                    .customModelData(r.getCustomModelData())
                    .texture(r.getTexture())
                    .rarity(rarity == null ? r.getRarity() : rarity)
                    .broadcast(broadcast)
                    .enchantments(r.getEnchantments())
                    .permission(permission != null ? permission : r.getPermission())
                    .build());
        }
        crates.put(crateId.toLowerCase(Locale.ROOT), existing.withRewards(updated));
        crateRepository.saveRewards(existing.getId(), updated);
        plugin.getLogger().info("Updated reward " + rewardId + " weight=" + weight + " rarity=" + rarity);
    }

    /**
     * Change GUI display name + key name only (supports spaces & capitals).
     * Does NOT touch holograms — those are managed separately (FancyHolograms / hologram.lines).
     */
    public boolean setDisplayName(String crateId, String displayName) {
        if (crateId == null || displayName == null || displayName.isBlank()) return false;
        CrateDefinition existing = getCrate(crateId);
        if (existing == null) return false;
        String cleaned = displayName.trim().replace('§', '&');
        CrateDefinition updated = existing.withDisplayName(cleaned);
        crates.put(crateId.toLowerCase(java.util.Locale.ROOT), updated);
        crateRepository.backupBeforeSave();
        crateRepository.saveFull(crates);
        // Key style display name only
        try {
            if (plugin instanceof com.skritped.novacrates.NovaCratesPlugin nc) {
                nc.getKeyService().setKeyDisplayName(existing.getKeyId(), cleaned);
            }
        } catch (Throwable ignored) {}
        return true;
    }

    public boolean setHologramLines(String crateId, java.util.List<String> lines) {
        CrateDefinition existing = getCrate(crateId);
        if (existing == null) return false;
        java.util.List<String> copy = lines == null ? java.util.List.of() : new java.util.ArrayList<>(lines);
        for (int i = 0; i < copy.size(); i++) {
            if (copy.get(i) != null) copy.set(i, copy.get(i).replace('§', '&'));
        }
        crates.put(crateId.toLowerCase(java.util.Locale.ROOT), existing.withHologramLines(copy));
        crateRepository.backupBeforeSave();
        crateRepository.saveFull(crates);
        // Do not respawn/overwrite FancyHolograms — /fholo edits stay intact.
        // Only refresh ArmorStand backend when external holograms are not respected.
        if (!plugin.getConfig().getBoolean("settings.hologram-respect-external", true)) {
            refreshHologramsForCrate(crateId);
        }
        return true;
    }

    public void refreshHologramsForCrate(String crateId) {
        if (crateId == null) return;
        String id = crateId.toLowerCase(java.util.Locale.ROOT);
        for (var e : playerData.getBlocks().entrySet()) {
            if (e.getValue() != null && e.getValue().equalsIgnoreCase(id)) {
                hologramService.spawnHologram(e.getKey(), getCrate(id));
            }
        }
    }

    private static String stripColor(String s) {
        if (s == null) return "";
        return s.replaceAll("(?i)[§&][0-9a-fk-or]", "");
    }

    public ItemStack createRewardItem(RewardDefinition reward) {
        return itemFactory.create(reward);
    }

    public void replaceEditorRewards(String crateId, List<ItemStack> items) {
        CrateDefinition existing = getCrate(crateId);
        if (existing == null) {
            return;
        }
        List<RewardDefinition> old = existing.getRewards();
        List<RewardDefinition> merged = new ArrayList<>();
        for (ItemStack source : items) {
            if (source == null || source.getType().isAir()) {
                continue;
            }
            String materialName = materialNameOf(source);
            String display = source.hasItemMeta() && source.getItemMeta() != null
                    && source.getItemMeta().hasDisplayName()
                    ? source.getItemMeta().getDisplayName() : null;
            RewardDefinition match = itemFactory.readRewardId(source)
                    .flatMap(id -> old.stream().filter(r -> r.getId().equals(id)).findFirst())
                    .orElseGet(() -> findMatch(old, materialName, display, source.getAmount()));
            Map<String, Integer> enchants = new LinkedHashMap<>();
            source.getEnchantments().forEach((e, lvl) -> enchants.put(e.getKey().getKey(), lvl));

            if (match != null) {
                merged.add(RewardDefinition.builder()
                        .id(match.getId())
                        .material(materialName)
                        .amount(source.getAmount())
                        .chance(match.getChance())
                        .displayName(display != null ? display : match.getDisplayName())
                        .lore(source.hasItemMeta() && source.getItemMeta() != null
                                && source.getItemMeta().hasLore()
                                ? source.getItemMeta().getLore() : match.getLore())
                        .commands(match.getCommands())
                        .commandAsPlayer(match.isCommandAsPlayer())
                        .customModelData(source.hasItemMeta() && source.getItemMeta() != null
                                && source.getItemMeta().hasCustomModelData()
                                ? source.getItemMeta().getCustomModelData() : match.getCustomModelData())
                        .texture(match.getTexture())
                        .rarity(match.getRarity())
                        .broadcast(match.isBroadcast())
                        .enchantments(enchants.isEmpty() ? match.getEnchantments() : enchants)
                        .permission(match.getPermission())
                        .build());
            } else {
                String rewardId = "editor-" + UUID.randomUUID().toString().substring(0, 8);
                merged.add(RewardDefinition.builder()
                        .id(rewardId)
                        .material(materialName)
                        .amount(source.getAmount())
                        .chance(1.0)
                        .displayName(display != null ? display : rewardId)
                        .lore(source.hasItemMeta() && source.getItemMeta() != null
                                && source.getItemMeta().hasLore()
                                ? source.getItemMeta().getLore() : List.of())
                        .commands(List.of())
                        .commandAsPlayer(false)
                        .customModelData(source.hasItemMeta() && source.getItemMeta() != null
                                && source.getItemMeta().hasCustomModelData()
                                ? source.getItemMeta().getCustomModelData() : 0)
                        .rarity("COMMON")
                        .broadcast(false)
                        .enchantments(enchants)
                        .permission("")
                        .build());
            }
        }
        crates.put(crateId.toLowerCase(Locale.ROOT), existing.withRewards(merged));
        crateRepository.backupBeforeSave();
        crateRepository.saveRewards(existing.getId(), merged);
        plugin.getLogger().info("Editor saved " + merged.size() + " reward(s) for " + crateId);
    }

    private static String materialNameOf(ItemStack source) {
        XMaterial matched = XMaterial.matchXMaterial(source.getType());
        if (matched != null) {
            return matched.name();
        }
        return source.getType().name();
    }

    private RewardDefinition findMatch(List<RewardDefinition> old, String material, String display, int amount) {
        for (RewardDefinition r : old) {
            if (!r.getMaterial().equalsIgnoreCase(material)) {
                continue;
            }
            if (display != null && r.getDisplayName() != null
                    && stripColors(display).equalsIgnoreCase(stripColors(r.getDisplayName()))) {
                return r;
            }
        }
        for (RewardDefinition r : old) {
            if (r.getMaterial().equalsIgnoreCase(material) && r.getAmount() == amount) {
                return r;
            }
        }
        return null;
    }

    private static String stripColors(String s) {
        return Text.strip(s);
    }

    public boolean createCrate(String id) {
        if (getCrate(id) != null) {
            return false;
        }
        List<RewardDefinition> rewards = List.of(RewardDefinition.builder()
                .id("placeholder")
                .material("STONE")
                .amount(1)
                .chance(100)
                .displayName("&7Placeholder")
                .lore(List.of("&7Replace me in the editor"))
                .commands(List.of())
                .commandAsPlayer(false)
                .customModelData(0)
                .rarity("COMMON")
                .broadcast(false)
                .enchantments(Map.of())
                .permission("")
                .build());
        CrateDefinition def = new CrateDefinition(id, "&f" + id, id, "CSGO", rewards,
                0, null, new CostDefinition("NONE", 0, null), 0, true,
                List.of("&f" + id, "&7Right-click to open"));
        crates.put(id.toLowerCase(Locale.ROOT), def);
        crateRepository.saveFull(crates);
        return true;
    }

    public boolean deleteCrate(String id) {
        CrateDefinition removed = crates.remove(id.toLowerCase(Locale.ROOT));
        if (removed == null) {
            return false;
        }
        crateRepository.saveFull(crates);
        return true;
    }

    public boolean cloneCrate(String fromId, String toId) {
        CrateDefinition from = getCrate(fromId);
        if (from == null || getCrate(toId) != null) {
            return false;
        }
        CrateDefinition clone = new CrateDefinition(toId, from.getDisplayName(), toId,
                from.getAnimation(), new ArrayList<>(from.getRewards()),
                from.getPityThreshold(), from.getPityRewardId(),
                from.getSoftPityStart(), from.getSoftPityBoostPerOpen(),
                from.getCost(), from.getCooldownSeconds(), from.isHologramEnabled(),
                from.getHologramLines(), from.getAvailableFrom(), from.getAvailableUntil(),
                from.getMilestoneEvery(), from.getMilestoneRewardId(),
                from.getUnlockRequiresCrate(), from.getUnlockRequiresOpens(), from.getUnlockPermission(),
                from.getPassPoints(), from.getPassTrack());
        crates.put(toId.toLowerCase(Locale.ROOT), clone);
        crateRepository.saveFull(crates);
        return true;
    }

    public boolean exportCrate(String id, File target) {
        return crateRepository.exportCrate(id, target);
    }

    public boolean importCrate(File source, boolean overwrite) {
        boolean ok = crateRepository.importCrate(source, overwrite);
        if (ok) {
            reloadLight(true);
        }
        return ok;
    }

    public void recoverPendingTransactions() {
        for (PendingOpen pending : playerData.getPending()) {
            if (pending.isExpired(pendingTimeoutMillis)) {
                plugin.getLogger().log(Level.WARNING,
                        "Expired pending open for " + pending.getPlayerId() + " crate=" + pending.getCrateId());
            }
            recover(pending);
        }
    }

    private void recover(PendingOpen pending) {
        Player player = plugin.getServer().getPlayer(pending.getPlayerId());
        if (player != null && player.isOnline()) {
            refundOnline(player, pending);
            plugin.getLogger().info("Refunded pending open for " + player.getName());
        } else {
            if (pending.isVirtualKey()) {
                playerData.setVirtualKeys(pending.getPlayerId(), pending.getKeyId(),
                        playerData.getVirtualKeys(pending.getPlayerId(), pending.getKeyId()) + 1);
            }
            playerData.addDebt(new CostDebt(
                    pending.getPlayerId(),
                    pending.getCostType(),
                    pending.getCostAmount(),
                    pending.getCostMaterial(),
                    pending.isVirtualKey(),
                    pending.getKeyId(),
                    pending.getCrateId()
            ));
            plugin.getLogger().warning("Queued offline debt for " + pending.getPlayerId());
        }
        playerData.removePending(pending.getPlayerId());
        openingPlayers.remove(pending.getPlayerId());
    }

    private void refundOnline(Player player, PendingOpen pending) {
        if (pending.isVirtualKey()) {
            keyService.giveVirtualKey(player, pending.getKeyId(), playerData);
        } else {
            keyService.givePhysicalKey(player, pending.getKeyId());
        }
        costService.refund(player, new CostDefinition(
                pending.getCostType(), pending.getCostAmount(), pending.getCostMaterial()));
    }

    public void settleDebts(Player player) {
        List<CostDebt> debts = playerData.takeDebts(player.getUniqueId());
        if (debts.isEmpty()) {
            return;
        }
        int settled = 0;
        for (CostDebt debt : debts) {
            if (!debt.isVirtualKey() && debt.getKeyId() != null) {
                keyService.givePhysicalKey(player, debt.getKeyId());
            }
            boolean ok = costService.refund(player, new CostDefinition(
                    debt.getCostType(), debt.getCostAmount(), debt.getCostMaterial()));
            if (!ok) {
                playerData.addDebt(debt);
                plugin.getLogger().warning("Could not refund MONEY debt for " + player.getName()
                        + " — economy unavailable, debt kept");
            } else {
                settled++;
            }
        }
        if (settled > 0) {
            messages.send(player, "debt-refunded");
            plugin.getLogger().info("Settled " + settled + " debt(s) for " + player.getName());
        }
    }

    public void releaseOpening(UUID playerId) {
        openingPlayers.remove(playerId);
        java.util.concurrent.CompletableFuture<RewardDefinition> f = asyncOpenFutures.remove(playerId);
        if (f != null && !f.isDone()) {
            f.completeExceptionally(new IllegalStateException("Open cancelled"));
        }
    }

    public int resolveDailyLimit(Player player) {
        int base = plugin.getConfig().getInt("settings.daily-open-limit", 0);
        if (base <= 0) {
            return 0;
        }
        int extra = 0;
        var section = plugin.getConfig().getConfigurationSection("settings.daily-open-limit-permissions");
        if (section != null) {
            for (String perm : section.getKeys(false)) {
                if (player.hasPermission(perm)) {
                    extra = Math.max(extra, section.getInt(perm, 0));
                }
            }
        }
        return base + extra;
    }

    public boolean checkDailyLimit(Player player) {
        int limit = resolveDailyLimit(player);
        if (limit <= 0) {
            return true;
        }
        return playerData.getDailyOpens(player.getUniqueId()) < limit;
    }

    
    public org.bukkit.Location findNearestCrateLocation(Player player, String crateId) {
        org.bukkit.Location best = null;
        double bestDist = 64;
        for (var e : playerData.getBlocks().entrySet()) {
            if (!e.getValue().equalsIgnoreCase(crateId)) continue;
            String[] p = e.getKey().split(":");
            if (p.length < 4) continue;
            try {
                org.bukkit.World w = org.bukkit.Bukkit.getWorld(p[0]);
                if (w == null) continue;
                org.bukkit.Location loc = new org.bukkit.Location(w,
                        Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
                if (player.getWorld() != w) continue;
                double d = loc.distanceSquared(player.getLocation());
                if (d < bestDist * bestDist) {
                    bestDist = Math.sqrt(d);
                    best = loc;
                }
            } catch (Exception ignored) {}
        }
        return best != null ? best : player.getLocation();
    }

public void setBlock(String locationKey, String crateId) {
        playerData.setBlock(locationKey, crateId);
        CrateDefinition crate = getCrate(crateId);
        hologramService.spawnHologram(locationKey, crate);

    }

    public void removeBlock(String locationKey) {
        playerData.removeBlock(locationKey);
        hologramService.removeHologram(locationKey);
    }

    public String getBlockCrate(String locationKey) {
        return playerData.getBlockCrate(locationKey);
    }

    /** Scale all reward weights in a crate by factor. */
    
    public void normalizeWeights(String crateId) {
        CrateDefinition existing = getCrate(crateId);
        if (existing == null || existing.getRewards().isEmpty()) return;
        double total = 0;
        for (RewardDefinition r : existing.getRewards()) total += Math.max(0, r.getChance());
        if (total <= 0) return;
        List<RewardDefinition> updated = new ArrayList<>();
        for (RewardDefinition r : existing.getRewards()) {
            double w = Math.max(0, r.getChance());
            double norm = (w / total) * 100.0;
            updated.add(RewardDefinition.builder()
                    .id(r.getId()).material(r.getMaterial()).amount(r.getAmount())
                    .chance(Math.round(norm * 100.0) / 100.0)
                    .displayChance(r.getDisplayChance()).displayName(r.getDisplayName())
                    .lore(r.getLore()).commands(r.getCommands()).commandAsPlayer(r.isCommandAsPlayer())
                    .customModelData(r.getCustomModelData()).texture(r.getTexture())
                    .rarity(r.getRarity()).broadcast(r.isBroadcast())
                    .enchantments(r.getEnchantments()).permission(r.getPermission())
                    .build());
        }
        crates.put(crateId.toLowerCase(Locale.ROOT), existing.withRewards(updated));
        crateRepository.backupBeforeSave();
        crateRepository.saveRewards(existing.getId(), updated);
    }

    public void cleanupRewardLore() {
        for (CrateDefinition c : new ArrayList<>(crates.values())) {
            List<RewardDefinition> updated = new ArrayList<>();
            boolean changed = false;
            for (RewardDefinition r : c.getRewards()) {
                List<String> lore = r.getLore() == null ? List.of() : r.getLore();
                List<String> cleaned = new ArrayList<>();
                for (String line : lore) {
                    String s = Text.strip(line);
                    if (s.startsWith("Waga:") || s.startsWith("Szansa:") || s.startsWith("Rarity:")
                            || s.startsWith("Weight:") || s.startsWith("Chance:")
                            || s.contains("Prawy klik") || s.startsWith("──") || s.startsWith("ID:")) {
                        changed = true;
                        continue;
                    }
                    cleaned.add(line);
                }
                if (cleaned.size() != lore.size()) {
                    changed = true;
                    updated.add(RewardDefinition.builder()
                            .id(r.getId()).material(r.getMaterial()).amount(r.getAmount())
                            .chance(r.getChance()).displayChance(r.getDisplayChance())
                            .displayName(r.getDisplayName()).lore(cleaned).commands(r.getCommands())
                            .commandAsPlayer(r.isCommandAsPlayer()).customModelData(r.getCustomModelData())
                            .texture(r.getTexture()).rarity(r.getRarity()).broadcast(r.isBroadcast())
                            .enchantments(r.getEnchantments()).permission(r.getPermission())
                            .build());
                } else {
                    updated.add(r);
                }
            }
            if (changed) {
                crates.put(c.getId().toLowerCase(Locale.ROOT), c.withRewards(updated));
                crateRepository.saveRewards(c.getId(), updated);
            }
        }
        crateRepository.saveFull(crates);
    }

public void scaleWeights(String crateId, double factor) {
        CrateDefinition existing = getCrate(crateId);
        if (existing == null || factor <= 0) {
            return;
        }
        List<RewardDefinition> updated = new ArrayList<>();
        for (RewardDefinition r : existing.getRewards()) {
            updated.add(RewardDefinition.builder()
                    .id(r.getId())
                    .material(r.getMaterial())
                    .amount(r.getAmount())
                    .chance(Math.max(0, r.getChance() * factor))
                    .displayChance(r.getDisplayChance())
                    .displayName(r.getDisplayName())
                    .lore(r.getLore())
                    .commands(r.getCommands())
                    .commandAsPlayer(r.isCommandAsPlayer())
                    .customModelData(r.getCustomModelData())
                    .texture(r.getTexture())
                    .rarity(r.getRarity())
                    .broadcast(r.isBroadcast())
                    .enchantments(r.getEnchantments())
                    .permission(r.getPermission())
                    .build());
        }
        crates.put(crateId.toLowerCase(Locale.ROOT), existing.withRewards(updated));
        crateRepository.backupBeforeSave();
        crateRepository.saveRewards(existing.getId(), updated);
    }

    public boolean isUnlocked(Player player, CrateDefinition crate) {
        if (crate == null || !crate.hasUnlockRequirement()) {
            return true;
        }
        if (crate.getUnlockPermission() != null && !crate.getUnlockPermission().isBlank()
                && player.hasPermission(crate.getUnlockPermission())) {
            return true;
        }
        if (playerData.isCrateUnlocked(player.getUniqueId(), crate.getId())) {
            return true;
        }
        if (crate.getUnlockRequiresCrate() != null && crate.getUnlockRequiresOpens() > 0) {
            int opens = getPlayerCrateOpens(player.getUniqueId(), crate.getUnlockRequiresCrate());
            if (opens >= crate.getUnlockRequiresOpens()) {
                playerData.unlockCrate(player.getUniqueId(), crate.getId());
                return true;
            }
        }
        return false;
    }

    /** After each open, unlock any crates whose requires-opens is satisfied for this player. */
    public void checkAutoUnlocks(Player player) {
        UUID id = player.getUniqueId();
        for (CrateDefinition c : crates.values()) {
            if (!c.hasUnlockRequirement()) continue;
            if (playerData.isCrateUnlocked(id, c.getId())) continue;
            if (c.getUnlockPermission() != null && !c.getUnlockPermission().isBlank()
                    && player.hasPermission(c.getUnlockPermission())) {
                playerData.unlockCrate(id, c.getId());
                messages.send(player, "crate-unlocked", Map.of("crate", messages.color(c.getDisplayName())));
                continue;
            }
            String req = c.getUnlockRequiresCrate();
            if (req == null) continue;
            int need = c.getUnlockRequiresOpens();
            if (getPlayerCrateOpens(id, req) >= need) {
                playerData.unlockCrate(id, c.getId());
                messages.send(player, "crate-unlocked", Map.of("crate", messages.color(c.getDisplayName())));
            }
        }
    }

    public int getPlayerCrateOpens(UUID playerId, String crateId) {
        return playerData.getPlayerCrateOpens(playerId, crateId);
    }

    public void incrementPlayerCrateOpens(UUID playerId, String crateId) {
        playerData.incrementPlayerCrateOpens(playerId, crateId);
    }

    public long queueOfflineOpen(UUID playerId, String crateId, String queuedBy, String forcedRewardId, boolean consumeKey) {
        if (getCrate(crateId) == null) return -1;
        int maxQ = plugin.getConfig().getInt("settings.offline-queue-max", 20);
        if (playerData.countOfflineQueue(playerId) >= maxQ) return -2;
        return playerData.enqueueOfflineOpen(new OfflineOpen(playerId, crateId, queuedBy, forcedRewardId, consumeKey));
    }

    public long queueOfflineOpen(UUID playerId, String crateId, String queuedBy, String forcedRewardId) {
        return queueOfflineOpen(playerId, crateId, queuedBy, forcedRewardId, false);
    }


    public void processGiftQueue(Player player) {
        var gifts = playerData.takeGifts(player.getUniqueId());
        for (var g : gifts) {
            playerData.setVirtualKeys(player.getUniqueId(), g.keyId(),
                    playerData.getVirtualKeys(player.getUniqueId(), g.keyId()) + g.amount());
            messages.send(player, "key-received", Map.of(
                    "amount", String.valueOf(g.amount()),
                    "key", g.keyId(),
                    "player", g.fromPlayer() == null ? "?" : g.fromPlayer()));
        }
    }

    public void enqueueGift(java.util.UUID target, String keyId, int amount, String from) {
        playerData.enqueueGift(target, keyId, amount, from);
    }

    public void processOfflineQueue(Player player) {
        List<OfflineOpen> queue = playerData.takeOfflineQueue(player.getUniqueId());
        if (queue.isEmpty()) {
            return;
        }
        for (OfflineOpen item : queue) {
            CrateDefinition crate = getCrate(item.getCrateId());
            if (crate == null || crate.getRewards().isEmpty()) {
                messages.send(player, "crate-not-found");
                continue;
            }
            RewardDefinition reward = null;
            if (item.getForcedRewardId() != null) {
                for (RewardDefinition r : crate.getRewards()) {
                    if (r.getId().equals(item.getForcedRewardId())) {
                        reward = r;
                        break;
                    }
                }
            }
            if (item.isConsumeKey()) {
                if (!hasKey(player, crate.getKeyId()) || !consumeKey(player, crate.getKeyId())) {
                    messages.send(player, "key-required");
                    continue;
                }
            }
            if (reward == null) {
                int pity = playerData.getPity(player.getUniqueId(), crate.getId());
                reward = RewardSelector.select(crate, pity, player);
            }
            grantService.grant(player, reward);
            try {
                Bukkit.getPluginManager().callEvent(new OfflineQueueGrantEvent(
                        player, crate, reward, item.getQueuedBy(), item.isConsumeKey()));
            } catch (Throwable ignored) {
            }
            boolean wasPity = reward.getId().equals(crate.getPityRewardId());
            int pity = playerData.getPity(player.getUniqueId(), crate.getId());
            playerData.setPity(player.getUniqueId(), crate.getId(), wasPity ? 0 : pity + 1);
            playerData.addHistory(player.getUniqueId(), new DropRecord(
                    crate.getId(), reward.getId(), reward.getDisplayName(), System.currentTimeMillis()));
            playerData.logDrop(player.getUniqueId(), player.getName(), crate.getId(),
                    reward.getId(), reward.getDisplayName());
            if (plugin.getConfig().getBoolean("settings.metrics-enabled", true)) {
                playerData.incrementStat("opens.total");
                playerData.incrementStat("opens." + crate.getId());
            }
            incrementPlayerCrateOpens(player.getUniqueId(), crate.getId());
            Map<String, String> ph = Map.of(
                    "reward", messages.color(reward.getDisplayName()),
                    "crate", messages.color(crate.getDisplayName()),
                    "player", player.getName());
            messages.send(player, "offline-open-granted", ph);
            if (battlePassService != null) {
                battlePassService.onCrateOpened(player, crate);
            }
            checkAutoUnlocks(player);
            CrateRewardEvent rewEv = new CrateRewardEvent(player, crate, reward, reward.getId().equals(crate.getPityRewardId()));
        Bukkit.getPluginManager().callEvent(rewEv);
        if (rewEv.isCancelled()) {
            return;
        }
        reward = rewEv.getReward();
        }
    }

    public BattlePassService getBattlePassService() {
        return battlePassService;
    }

    public void adminUnlock(UUID playerId, String crateId) {
        playerData.unlockCrate(playerId, crateId);
    }


    public int resolveCrateDailyLimit(CrateDefinition crate) {
        // per-crate from config settings.crate-daily-limits.<id> or crate yaml future
        int v = plugin.getConfig().getInt("settings.crate-daily-limits." + crate.getId(), 0);
        return Math.max(0, v);
    }

    public boolean checkCrateDailyLimit(Player player, CrateDefinition crate) {
        int limit = resolveCrateDailyLimit(crate);
        if (limit <= 0) return true;
        return playerData.getDailyCrateOpens(player.getUniqueId(), crate.getId()) < limit;
    }


    
    private RewardDefinition pickGuaranteed(Player player, CrateDefinition crate, int opensAfterThis) {
        int every = plugin.getConfig().getInt("guaranteed-sequence." + crate.getId() + ".every", 0);
        if (every <= 0) {
            every = plugin.getConfig().getInt("settings.guaranteed-every-default", 0);
        }
        if (every <= 0 || opensAfterThis < every || opensAfterThis % every != 0) {
            return null;
        }
        if (plugin.getConfig().getBoolean("settings.guarantee-once-per-multi", true)
                && Boolean.TRUE.equals(multiGuaranteeUsed.get())) {
            return null;
        }
        String rid = plugin.getConfig().getString("guaranteed-sequence." + crate.getId() + ".reward-id",
                crate.getPityRewardId());
        RewardDefinition found = null;
        if (rid != null) {
            for (RewardDefinition r : crate.getRewards()) {
                if (r.getId().equalsIgnoreCase(rid)) {
                    found = r;
                    break;
                }
            }
        }
        if (found == null) {
            String rarity = plugin.getConfig().getString("guaranteed-sequence." + crate.getId() + ".rarity", "");
            if (rarity != null && !rarity.isBlank()) {
                for (RewardDefinition r : crate.getRewards()) {
                    if (r.getRarity() != null && r.getRarity().equalsIgnoreCase(rarity)) {
                        found = r;
                        break;
                    }
                }
            }
        }
        if (found != null) {
            multiGuaranteeUsed.set(true);
        }
        return found;
    }


private void showPityBossBar(Player player, CrateDefinition crate, int pity) {
        if (!plugin.getConfig().getBoolean("settings.pity-bossbar", true)) {
            return;
        }
        if (crate.getPityThreshold() <= 0) {
            return;
        }
        try {
            org.bukkit.boss.BossBar bar = Bukkit.createBossBar(
                    org.bukkit.ChatColor.translateAlternateColorCodes('&',
                            "&dPity &f" + pity + "&7/&f" + crate.getPityThreshold()),
                    org.bukkit.boss.BarColor.PURPLE,
                    org.bukkit.boss.BarStyle.SEGMENTED_10);
            double prog = Math.min(1.0, (double) pity / Math.max(1, crate.getPityThreshold()));
            bar.setProgress(Math.max(0.01, prog));
            bar.addPlayer(player);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                bar.removeAll();
                bar.setVisible(false);
            }, 60L);
        } catch (Throwable ignored) {
        }
    }


    public java.util.List<java.util.Map.Entry<java.util.UUID, Integer>> getTopOpeners(String crateId, int limit) {
        return playerData.getTopOpeners(crateId, limit);
    }

}
