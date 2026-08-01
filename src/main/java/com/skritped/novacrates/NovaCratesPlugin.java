package com.skritped.novacrates;

import com.skritped.novacrates.animation.AnimationController;
import com.skritped.novacrates.command.CrateCommand;
import com.skritped.novacrates.gui.AnvilInputService;
import com.skritped.novacrates.gui.DialogService;
import com.skritped.novacrates.gui.GUIListener;
import com.skritped.novacrates.gui.GUIManager;
import com.skritped.novacrates.hook.PlaceholderHook;
import com.skritped.novacrates.listener.CrateBlockListener;
import com.skritped.novacrates.listener.PlayerConnectionListener;
import com.skritped.novacrates.manager.CrateManager;
import com.skritped.novacrates.service.BattlePassService;
import com.skritped.novacrates.service.CostService;
import com.skritped.novacrates.service.DiscordWebhookService;
import com.skritped.novacrates.service.EconomyService;
import com.skritped.novacrates.service.HologramService;
import com.skritped.novacrates.service.ItemFactory;
import com.skritped.novacrates.service.KeyService;
import com.skritped.novacrates.service.MessageService;
import com.skritped.novacrates.service.RewardGrantService;
import com.skritped.novacrates.storage.CrateRepository;
import com.skritped.novacrates.storage.Database;
import com.skritped.novacrates.storage.PlayerDataRepository;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class NovaCratesPlugin extends JavaPlugin {
    private PlayerDataRepository playerData;
    private KeyService keyService;
    private GUIManager guiManager;
    private AnvilInputService anvilInput;
    private DialogService dialogService;
    private AnimationController animationController;
    private CrateManager crateManager;
    private HologramService hologramService;
    private ItemFactory itemFactory;
    private Database database;
    private MessageService messageService;
    private CostService costService;
    private DiscordWebhookService discordWebhook;
    private BattlePassService battlePassService;
    private RewardGrantService grantService;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResource("crates.yml", false);
        saveResource("keys.yml", false);

        messageService = new MessageService(this);
        database = new Database(this);
        playerData = new PlayerDataRepository(this, database);
        playerData.setHistorySize(getConfig().getInt("settings.history-size", 20));

        CrateRepository crateRepository = new CrateRepository(this);
        keyService = new KeyService(this);
        itemFactory = new ItemFactory(this);
        guiManager = new GUIManager();
        anvilInput = new AnvilInputService(this);
        dialogService = new DialogService(this);
        hologramService = new HologramService(this);
        hologramService.setParticlesEnabled(getConfig().getBoolean("settings.particles-enabled", true));
        hologramService.setParticleRange(getConfig().getDouble("settings.particle-range", 24));
        hologramService.setPapiRefreshEnabled(getConfig().getBoolean("settings.hologram-papi-refresh", true));
        hologramService.setRefreshIntervalTicks(getConfig().getInt("settings.hologram-refresh-ticks", 40));

        discordWebhook = new DiscordWebhookService(this);

        EconomyService economyService = new EconomyService(this);
        costService = new CostService(economyService);
        boolean strictCommands = getConfig().getBoolean("settings.strict-reward-commands", true);
        grantService = new RewardGrantService(this, itemFactory, strictCommands);

        animationController = new AnimationController(this, guiManager, itemFactory, playerData);
        battlePassService = new BattlePassService(this, playerData, keyService, messageService);
        crateManager = new CrateManager(this, playerData, crateRepository, keyService,
                animationController, itemFactory, costService, grantService, hologramService, messageService,
                discordWebhook, battlePassService);

        Bukkit.getPluginManager().registerEvents(new GUIListener(guiManager), this);
        Bukkit.getPluginManager().registerEvents(anvilInput, this);
        Bukkit.getPluginManager().registerEvents(new CrateBlockListener(this, crateManager, guiManager), this);
        Bukkit.getPluginManager().registerEvents(new PlayerConnectionListener(this, crateManager, animationController), this);

        CrateCommand command = new CrateCommand(this, crateManager, guiManager);
        if (getCommand("crates") != null) {
            getCommand("crates").setExecutor(command);
            getCommand("crates").setTabCompleter(command);
        }

        int autosave = getConfig().getInt("settings.autosave-interval-ticks", 100);
        playerData.startAutosave(Math.max(40, autosave));

        crateManager.recoverPendingTransactions();
        PlaceholderHook.tryRegister(this, crateManager);
        com.skritped.novacrates.hook.VoteKeyListener.tryRegister(this);

        if (getConfig().getBoolean("settings.metrics-enabled", true)) {
            getLogger().info("Opens total: " + playerData.getStat("opens.total"));
        }
        getLogger().info("NovaCrates " + getPluginMeta().getVersion() + " enabled (SQLite scale mode).");
    }

    @Override
    public void onDisable() {
        if (animationController != null) {
            animationController.cancelAll();
        }
        if (hologramService != null) {
            hologramService.clearAll();
        }
        if (playerData != null) {
            playerData.stopAutosave();
        }
        if (database != null) {
            database.close();
        }
    }

    public CrateManager getCrateManager() {
        return crateManager;
    }

    public KeyService getKeyService() {
        return keyService;
    }

    public AnvilInputService getAnvilInput() {
        return anvilInput;
    }

    public DialogService getDialogService() {
        return dialogService;
    }

    public GUIManager getGuiManager() {
        return guiManager;
    }

    public PlayerDataRepository getRepository() {
        return playerData;
    }

    public ItemFactory getItemFactory() {
        return itemFactory;
    }

    public MessageService getMessageService() {
        return messageService;
    }

    public CostService getCostService() {
        return costService;
    }

    public RewardGrantService getGrantService() {
        return grantService;
    }
}
