package com.skritped.novacrates.command;

import com.skritped.novacrates.NovaCratesPlugin;
import com.skritped.novacrates.gui.EditorGUI;
import com.skritped.novacrates.gui.GUIManager;
import com.skritped.novacrates.gui.ManageGUI;
import com.skritped.novacrates.gui.PreviewGUI;
import com.skritped.novacrates.listener.CrateBlockListener;
import com.skritped.novacrates.manager.CrateManager;
import com.skritped.novacrates.model.CrateDefinition;
import com.skritped.novacrates.model.DropRecord;
import com.skritped.novacrates.service.MessageService;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public class CrateCommand implements CommandExecutor, TabCompleter {
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final NovaCratesPlugin plugin;
    private final CrateManager manager;
    private final GUIManager guiManager;

    public CrateCommand(NovaCratesPlugin plugin, CrateManager manager, GUIManager guiManager) {
        this.plugin = plugin;
        this.manager = manager;
        this.guiManager = guiManager;
    }

    private MessageService msg() {
        return manager.getMessages();
    }

    private boolean hasAdmin(CommandSender s, String specific) {
        return s.hasPermission("novacrates.admin") || s.hasPermission(specific);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
            sender.sendMessage(msg().color("&bCrates: &f" + String.join(", ", manager.getCrates().keySet())));
            return true;
        }

        if (args[0].equalsIgnoreCase("help")) {
            return handleHelp(sender);
        }

        if (args[0].equalsIgnoreCase("changename") || args[0].equalsIgnoreCase("setname")
                || args[0].equalsIgnoreCase("displayname")) {
            return handleChangeName(sender, args);
        }

        if (args[0].equalsIgnoreCase("settings") || args[0].equalsIgnoreCase("config")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Players only.");
                return true;
            }
            if (!hasAdmin(player, "novacrates.admin.settings")) {
                msg().send(player, "no-permission");
                return true;
            }
            guiManager.openGUI(new com.skritped.novacrates.gui.SettingsGUI(plugin, guiManager, manager), player);
            return true;
        }

        if (args[0].equalsIgnoreCase("lang") || args[0].equalsIgnoreCase("language")) {
            if (args.length < 2) {
                sender.sendMessage(msg().color("&e/crates lang <pl|en> &7(current: &f"
                        + manager.getMessages().getLanguage() + "&7)"));
                return true;
            }
            if (!hasAdmin(sender, "novacrates.admin.settings") && !hasAdmin(sender, "novacrates.admin.reload")) {
                msg().send(sender, "no-permission");
                return true;
            }
            String lang = args[1].toLowerCase();
            if (!lang.equals("pl") && !lang.equals("en")) {
                sender.sendMessage(msg().color("&cUse: pl or en"));
                return true;
            }
            manager.getMessages().setLanguage(lang);
            msg().send(sender, "language-changed", java.util.Map.of("lang", lang));
            return true;
        }

        if (args[0].equalsIgnoreCase("keys")) {
            return handleKeys(sender, args);
        }

        if (args[0].equalsIgnoreCase("history")) {
            return handleHistory(sender, args);
        }

        if (args[0].equalsIgnoreCase("top")) {
            return handleTop(sender, args);
        }

        if (args[0].equalsIgnoreCase("doctor")) {
            return handleDoctor(sender, args);
        }

        if (args[0].equalsIgnoreCase("audit")) {
            return handleAudit(sender, args);
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this subcommand.");
            return true;
        }

        if (args[0].equalsIgnoreCase("preview") && args.length > 1) {
            CrateDefinition crate = manager.getCrate(args[1]);
            if (crate == null) {
                msg().send(player, "crate-not-found");
                return true;
            }
            guiManager.openGUI(new PreviewGUI(plugin, guiManager, manager, crate, 0), player);
            return true;
        }

        if (args[0].equalsIgnoreCase("editor") && args.length > 1 && hasAdmin(player, "novacrates.admin.editor")) {
            CrateDefinition crate = manager.getCrate(args[1]);
            if (crate != null) {
                guiManager.openGUI(new EditorGUI(plugin, guiManager, manager, crate), player);
            } else {
                msg().send(player, "crate-not-found");
            }
            return true;
        }


        if (args[0].equalsIgnoreCase("shop")) {
            if (!plugin.getConfig().getBoolean("settings.key-shop.enabled", true)) {
                player.sendMessage(msg().color("&cKey shop disabled."));
                return true;
            }
            if (!player.hasPermission("novacrates.shop")) {
                msg().send(player, "no-permission");
                return true;
            }
            guiManager.openGUI(new com.skritped.novacrates.gui.ShopGUI(plugin, manager, plugin.getCostService()), player);
            return true;
        }

        if (args[0].equalsIgnoreCase("stats")) {
            return handleStats(sender, args);
        }

        if (args[0].equalsIgnoreCase("manage") && player.hasPermission("novacrates.admin")) {
            guiManager.openGUI(new ManageGUI(plugin, guiManager, manager), player);
            return true;
        }

        if (args[0].equalsIgnoreCase("create") && args.length > 1 && player.hasPermission("novacrates.admin")) {
            if (manager.createCrate(args[1].toLowerCase(Locale.ROOT))) {
                msg().send(player, "manage-created", Map.of("crate", args[1]));
            } else {
                player.sendMessage(msg().color("&cCrate already exists."));
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("delete") && args.length > 1 && player.hasPermission("novacrates.admin")) {
            if (manager.deleteCrate(args[1])) {
                msg().send(player, "manage-deleted", Map.of("crate", args[1]));
            } else {
                msg().send(player, "crate-not-found");
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("clone") && args.length > 2 && player.hasPermission("novacrates.admin")) {
            if (manager.cloneCrate(args[1], args[2].toLowerCase(Locale.ROOT))) {
                msg().send(player, "manage-cloned", Map.of("from", args[1], "to", args[2]));
            } else {
                player.sendMessage(msg().color("&cClone failed (missing source or target exists)."));
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("open") && args.length > 1) {
            int times = 1;
            if (args.length > 2) {
                try {
                    times = Integer.parseInt(args[2]);
                } catch (NumberFormatException ignored) {
                }
            }
            CrateDefinition openCrate = manager.getCrate(args[1]);
            boolean needConfirm = (times > 1 && plugin.getConfig().getBoolean("settings.multi-open-confirm", true))
                    || (openCrate != null && manager.needsCostConfirm(openCrate, times));
            if (needConfirm) {
                guiManager.openGUI(new com.skritped.novacrates.gui.MultiOpenConfirmGUI(plugin, manager, args[1], times), player);
            } else {
                manager.openCrate(player, args[1], times);
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("progress")) {
            guiManager.openGUI(new com.skritped.novacrates.gui.ProgressGUI(manager), player);
            return true;
        }

        if (args[0].equalsIgnoreCase("pass") && args.length == 1) {
            guiManager.openGUI(new com.skritped.novacrates.gui.BattlePassGUI(plugin, manager, "default"), player);
            return true;
        }

        if (args[0].equalsIgnoreCase("gift") && args.length > 2) {
            return handleGift(player, args);
        }

        if (args[0].equalsIgnoreCase("givekey") && args.length > 2 && hasAdmin(player, "novacrates.admin.givekey")) {
            return handleGiveKey(player, args);
        }

        if (args[0].equalsIgnoreCase("setblock") && args.length > 1 && player.hasPermission("novacrates.admin")) {
            CrateDefinition crate = manager.getCrate(args[1]);
            Block target = player.getTargetBlockExact(6);
            if (crate != null && target != null) {
                manager.setBlock(CrateBlockListener.key(target.getLocation()), crate.getId());
                player.sendMessage(msg().color("&aCrate block assigned."));
                try {
                    target.getWorld().spawnParticle(org.bukkit.Particle.HAPPY_VILLAGER,
                            target.getLocation().add(0.5, 1, 0.5), 20, 0.4, 0.4, 0.4, 0.01);
                    player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.4f);
                } catch (Exception ignored) {}
            } else {
                player.sendMessage(msg().color("&cLook at a block and use a valid crate id."));
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("removeblock") && player.hasPermission("novacrates.admin")) {
            Block target = player.getTargetBlockExact(6);
            if (target != null) {
                String key = CrateBlockListener.key(target.getLocation());
                if (manager.getBlockCrate(key) != null) {
                    manager.removeBlock(key);
                    player.sendMessage(msg().color("&aCrate block removed."));
                } else {
                    player.sendMessage(msg().color("&cNo crate on that block."));
                }
            }
            return true;
        }

        if ((args[0].equalsIgnoreCase("cleanup-lore") || args[0].equalsIgnoreCase("cleanuplore"))
                && player.hasPermission("novacrates.admin")) {
            manager.cleanupRewardLore();
            player.sendMessage(msg().color("&aWyczyszczono zanieczyszczone lore nagród."));
            return true;
        }

        if (args[0].equalsIgnoreCase("reload") && hasAdmin(player, "novacrates.admin.reload")) {
            plugin.reloadConfig();
            msg().reload();
            plugin.getKeyService().reloadStyles();
            plugin.getKeyService().reloadSecret();
            manager.getMessages().reload();
            if (plugin.getGrantService() != null) {
                plugin.getGrantService().reloadConfig();
            }
            boolean full = args.length > 1 && args[1].equalsIgnoreCase("full");
            if (full) {
                manager.reload();
            } else {
                manager.reloadLight(false);
            }
            msg().send(player, "reloaded");
            return true;
        }

        if (args[0].equalsIgnoreCase("exportall") && player.hasPermission("novacrates.admin")) {
            try {
                plugin.saveDefaultConfig();
                for (String res : new String[]{"crates.yml", "keys.yml", "lang/messages_en.yml", "lang/messages_pl.yml"}) {
                    java.io.File out = new java.io.File(plugin.getDataFolder(), res);
                    if (!out.exists()) plugin.saveResource(res, false);
                }
                java.io.File zipFile = new java.io.File(plugin.getDataFolder(), "novacrates-backup.zip");
                try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(new java.io.FileOutputStream(zipFile))) {
                    for (String name : new String[]{"config.yml", "crates.yml", "keys.yml", "plugin.yml"}) {
                        java.io.File f = new java.io.File(plugin.getDataFolder(), name);
                        if (!f.exists()) f = new java.io.File(plugin.getDataFolder().getParentFile(), name);
                        // from data folder only
                        f = new java.io.File(plugin.getDataFolder(), name);
                        if (!f.exists()) continue;
                        zos.putNextEntry(new java.util.zip.ZipEntry(name));
                        zos.write(java.nio.file.Files.readAllBytes(f.toPath()));
                        zos.closeEntry();
                    }
                    java.io.File lang = new java.io.File(plugin.getDataFolder(), "lang");
                    if (lang.isDirectory()) {
                        for (java.io.File f : lang.listFiles()) {
                            zos.putNextEntry(new java.util.zip.ZipEntry("lang/" + f.getName()));
                            zos.write(java.nio.file.Files.readAllBytes(f.toPath()));
                            zos.closeEntry();
                        }
                    }
                }
                player.sendMessage(msg().color("&aBackup: &f" + zipFile.getName()));
            } catch (Exception ex) {
                player.sendMessage(msg().color("&cExport failed: " + ex.getMessage()));
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("exportcsv") && player.hasPermission("novacrates.admin")) {
            int limit = 500;
            if (args.length > 1) {
                try { limit = Integer.parseInt(args[1]); } catch (NumberFormatException ignored) {}
            }
            var lines = manager.getPlayerData().exportDropsCsv(null, limit);
            java.io.File out = new java.io.File(plugin.getDataFolder(), "drops-export.csv");
            try {
                java.nio.file.Files.write(out.toPath(), lines);
                player.sendMessage(msg().color("&aExported &f" + (lines.size()-1) + " &arows to drops-export.csv"));
            } catch (Exception ex) {
                player.sendMessage(msg().color("&cExport failed: " + ex.getMessage()));
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("export") && player.hasPermission("novacrates.admin")) {
            return handleExport(player, args);
        }

        if (args[0].equalsIgnoreCase("import") && player.hasPermission("novacrates.admin")) {
            return handleImport(player, args);
        }

        if (args[0].equalsIgnoreCase("queueopen") && player.hasPermission("novacrates.admin")) {
            return handleQueueOpen(player, args);
        }

        if (args[0].equalsIgnoreCase("unlock") && player.hasPermission("novacrates.admin")) {
            return handleUnlock(player, args);
        }

        if (args[0].equalsIgnoreCase("pass")) {
            return handlePass(player, args);
        }

        player.sendMessage(msg().color(
                "&cUsage: /crates list|open|preview|editor|manage|create|delete|clone|givekey|keys|history|audit|export|import|queueopen|unlock|pass|setblock|removeblock|reload"));
        return true;
    }


    private boolean handleStats(CommandSender sender, String[] args) {
        if (args.length > 1) {
            String crateId = args[1];
            long opens = manager.getPlayerData().getStat("opens." + crateId);
            sender.sendMessage(msg().color("&bStats for &f" + crateId + "&b:"));
            sender.sendMessage(msg().color("&7  Opens: &f" + opens));
            sender.sendMessage(msg().color("&7  Pity leaders:"));
            for (String line : manager.getPlayerData().getPityLeaderboard(crateId, 5)) {
                String[] parts = line.split(":");
                sender.sendMessage(msg().color("&8  - &7" + parts[0].substring(0, 8) + "... &f" + parts[1]));
            }
            return true;
        }
        long total = manager.getPlayerData().getStat("opens.total");
        sender.sendMessage(msg().color("&bGlobal opens: &f" + total));
        for (String id : manager.getCrates().keySet()) {
            sender.sendMessage(msg().color("&7  " + id + ": &f" + manager.getPlayerData().getStat("opens." + id)));
        }
        return true;
    }

    private boolean handleKeys(CommandSender sender, String[] args) {
        Player target;
        if (args.length > 1 && sender.hasPermission("novacrates.admin")) {
            target = plugin.getServer().getPlayer(args[1]);
            if (target == null) {
                sender.sendMessage(msg().color("&cPlayer offline."));
                return true;
            }
        } else if (sender instanceof Player p) {
            target = p;
        } else {
            sender.sendMessage("Specify a player.");
            return true;
        }
        Map<String, Integer> keys = manager.getPlayerData().getAllVirtualKeys(target.getUniqueId());
        sender.sendMessage(msg().color("&bVirtual keys for &f" + target.getName() + "&b:"));
        if (keys.isEmpty()) {
            sender.sendMessage(msg().color("&7  (none)"));
        } else {
            keys.forEach((k, v) -> sender.sendMessage(msg().color("&7  " + k + ": &f" + v)));
        }
        return true;
    }

    private boolean handleHistory(CommandSender sender, String[] args) {
        Player target;
        int argIdx = 1;
        if (args.length > 1 && sender.hasPermission("novacrates.admin")
                && plugin.getServer().getPlayer(args[1]) != null) {
            target = plugin.getServer().getPlayer(args[1]);
            argIdx = 2;
        } else if (sender instanceof Player p) {
            target = p;
        } else {
            sender.sendMessage("Specify a player.");
            return true;
        }
        String filter = null;
        int page = 1;
        for (int i = argIdx; i < args.length; i++) {
            if (args[i].startsWith("crate:")) {
                filter = args[i].substring(6).toLowerCase(Locale.ROOT);
            } else {
                try {
                    page = Math.max(1, Integer.parseInt(args[i]));
                } catch (NumberFormatException ignored) {
                    if (manager.getCrate(args[i]) != null) {
                        filter = args[i].toLowerCase(Locale.ROOT);
                    }
                }
            }
        }
        // GUI mode for self with no filter/page
        if (sender instanceof Player self
                && self.getUniqueId().equals(target.getUniqueId())
                && filter == null
                && page == 1
                && args.length <= 1) {
            guiManager.openGUI(new com.skritped.novacrates.gui.HistoryGUI(manager, target.getUniqueId()), self);
            return true;
        }
        List<DropRecord> history = manager.getPlayerData().getHistory(target.getUniqueId());
        if (filter != null) {
            final String f = filter;
            history = history.stream()
                    .filter(r -> r.getCrateId() != null && r.getCrateId().equalsIgnoreCase(f))
                    .collect(Collectors.toList());
        }
        msg().send(sender, "history-header", Map.of("player", target.getName()));
        if (history.isEmpty()) {
            msg().send(sender, "history-empty");
            return true;
        }
        int perPage = 8;
        int pages = Math.max(1, (history.size() + perPage - 1) / perPage);
        page = Math.min(page, pages);
        int start = (page - 1) * perPage;
        msg().send(sender, "history-page", Map.of(
                "page", String.valueOf(page),
                "pages", String.valueOf(pages),
                "filter", filter == null ? "all" : filter));
        int i = start + 1;
        for (int idx = start; idx < Math.min(start + perPage, history.size()); idx++) {
            DropRecord record = history.get(idx);
            msg().send(sender, "history-line", Map.of(
                    "index", String.valueOf(i++),
                    "crate", record.getCrateId() == null ? "?" : record.getCrateId(),
                    "reward", msg().color(record.getRewardName() == null ? record.getRewardId() : record.getRewardName()),
                    "time", TIME_FMT.format(Instant.ofEpochMilli(record.getTimeMillis()))
            ));
        }
        return true;
    }

    private boolean handleAudit(CommandSender sender, String[] args) {
        if (!sender.hasPermission("novacrates.admin")) {
            msg().send(sender, "no-permission");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(msg().color("&cUsage: /crates audit <player> [limit]"));
            return true;
        }
        Player target = plugin.getServer().getPlayer(args[1]);
        UUID uuid;
        String name;
        if (target != null) {
            uuid = target.getUniqueId();
            name = target.getName();
        } else {
            sender.sendMessage(msg().color("&cPlayer offline — showing UUID lookup not available, use online player."));
            return true;
        }
        int limit = 20;
        if (args.length > 2) {
            try {
                limit = Math.max(1, Math.min(100, Integer.parseInt(args[2])));
            } catch (NumberFormatException ignored) {
            }
        }
        List<DropRecord> history = manager.getPlayerData().getHistory(uuid);
        msg().send(sender, "audit-header", Map.of("player", name, "limit", String.valueOf(limit)));
        if (history.isEmpty()) {
            msg().send(sender, "audit-empty");
            return true;
        }
        int i = 1;
        for (DropRecord record : history) {
            if (i > limit) break;
            msg().send(sender, "audit-line", Map.of(
                    "index", String.valueOf(i++),
                    "crate", record.getCrateId() == null ? "?" : record.getCrateId(),
                    "reward", msg().color(record.getRewardName() == null ? record.getRewardId() : record.getRewardName()),
                    "time", TIME_FMT.format(Instant.ofEpochMilli(record.getTimeMillis()))
            ));
        }
        return true;
    }

    private boolean handleExport(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(msg().color("&cUsage: /crates export <crateId>"));
            return true;
        }
        CrateDefinition crate = manager.getCrate(args[1]);
        if (crate == null) {
            msg().send(player, "crate-not-found");
            return true;
        }
        java.io.File out = new java.io.File(plugin.getDataFolder(), "export-" + crate.getId() + ".yml");
        if (manager.exportCrate(crate.getId(), out)) {
            msg().send(player, "export-ok", Map.of("crate", crate.getId(), "file", out.getName()));
        } else {
            msg().send(player, "export-fail");
        }
        return true;
    }

    private boolean handleImport(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(msg().color("&cUsage: /crates import <filename> [overwrite]"));
            return true;
        }
        java.io.File file = new java.io.File(plugin.getDataFolder(), args[1]);
        if (!file.exists()) {
            player.sendMessage(msg().color("&cFile not found in plugin folder: " + args[1]));
            return true;
        }
        boolean overwrite = args.length > 2 && args[2].equalsIgnoreCase("overwrite");
        if (manager.importCrate(file, overwrite)) {
            msg().send(player, "import-ok", Map.of("file", file.getName()));
        } else {
            msg().send(player, "import-fail");
        }
        return true;
    }

    private boolean handleGiveKey(Player player, String[] args) {
        Player target = plugin.getServer().getPlayer(args[1]);
        CrateDefinition crate = manager.getCrate(args[2]);
        if (target == null || crate == null) {
            msg().send(player, "crate-not-found");
            return true;
        }
        int amount = 1;
        boolean virtual = false;
        for (int i = 3; i < args.length; i++) {
            if (args[i].equalsIgnoreCase("virtual") || args[i].equalsIgnoreCase("v")) {
                virtual = true;
            } else {
                try {
                    amount = Math.max(1, Integer.parseInt(args[i]));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        if (virtual) {
            plugin.getKeyService().giveVirtualKey(target, crate.getKeyId(), manager.getPlayerData(), amount);
        } else {
            plugin.getKeyService().givePhysicalKey(target, crate.getKeyId(), amount);
        }
        Map<String, String> ph = Map.of(
                "amount", String.valueOf(amount),
                "key", crate.getKeyId(),
                "player", target.getName());
        msg().send(player, "key-given", ph);
        msg().send(target, "key-received", ph);
        return true;
    }


    private boolean handleQueueOpen(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage(msg().color("&cUsage: /crates queueopen <player|uuid> <crateId> [rewardId] [consumekey]"));
            return true;
        }
        org.bukkit.OfflinePlayer off = resolveOffline(args[1]);
        if (off == null || off.getUniqueId() == null) {
            player.sendMessage(msg().color("&cUnknown player / UUID."));
            return true;
        }
        CrateDefinition crate = manager.getCrate(args[2]);
        if (crate == null) {
            msg().send(player, "crate-not-found");
            return true;
        }
        String forced = null;
        boolean consumeKey = false;
        for (int i = 3; i < args.length; i++) {
            if (args[i].equalsIgnoreCase("consumekey") || args[i].equalsIgnoreCase("key")) {
                consumeKey = true;
            } else {
                forced = args[i];
            }
        }
        long id = manager.queueOfflineOpen(off.getUniqueId(), crate.getId(), player.getName(), forced, consumeKey);
        if (id == -2) {
            player.sendMessage(msg().color("&cOffline queue full for that player."));
            return true;
        }
        if (id < 0) {
            player.sendMessage(msg().color("&cCould not queue open."));
            return true;
        }
        manager.getPlayerData().logAdminAudit(player.getName(), "queueopen",
                off.getUniqueId().toString(), crate.getId() + " consumeKey=" + consumeKey);
        if (off.isOnline() && off.getPlayer() != null) {
            manager.processOfflineQueue(off.getPlayer());
            player.sendMessage(msg().color("&aGranted open of &f" + crate.getId() + " &ato &f" + args[1]));
        } else {
            msg().send(player, "offline-queued", Map.of("player", args[1], "crate", crate.getId()));
        }
        return true;
    }

    private org.bukkit.OfflinePlayer resolveOffline(String token) {
        try {
            java.util.UUID uuid = java.util.UUID.fromString(token);
            return plugin.getServer().getOfflinePlayer(uuid);
        } catch (IllegalArgumentException ignored) {
        }
        Player online = plugin.getServer().getPlayerExact(token);
        if (online != null) return online;
        try {
            var m = plugin.getServer().getClass().getMethod("getOfflinePlayerIfCached", String.class);
            Object r = m.invoke(plugin.getServer(), token);
            if (r instanceof org.bukkit.OfflinePlayer op && op.hasPlayedBefore()) return op;
        } catch (Throwable ignored) {
        }
        return plugin.getServer().getOfflinePlayer(token);
    }

    private boolean handleUnlock(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage(msg().color("&cUsage: /crates unlock <player|uuid> <crateId>"));
            return true;
        }
        org.bukkit.OfflinePlayer off = resolveOffline(args[1]);
        CrateDefinition crate = manager.getCrate(args[2]);
        if (crate == null || off == null) {
            msg().send(player, "crate-not-found");
            return true;
        }
        manager.adminUnlock(off.getUniqueId(), crate.getId());
        manager.getPlayerData().logAdminAudit(player.getName(), "unlock", off.getUniqueId().toString(), crate.getId());
        player.sendMessage(msg().color("&aUnlocked &f" + crate.getId() + " &afor &f" + args[1]));
        if (off.isOnline() && off.getPlayer() != null) {
            msg().send(off.getPlayer(), "crate-unlocked", Map.of("crate", msg().color(crate.getDisplayName())));
        }
        return true;
    }

    private boolean handlePass(Player player, String[] args) {
        var bp = manager.getBattlePassService();
        if (bp == null || !bp.isEnabled()) {
            player.sendMessage(msg().color("&cBattle pass is disabled."));
            return true;
        }
        if (args.length == 1) {
            // GUI opened earlier; fallback chat
            for (String line : bp.statusLines(player, "default")) {
                player.sendMessage(msg().color("&7" + line));
            }
            return true;
        }
        String track = args[1];
        if (args[1].equalsIgnoreCase("claim")) {
            track = args.length > 2 ? args[2] : "default";
            int n = bp.claimReady(player, track);
            player.sendMessage(msg().color("&aClaimed &f" + n + " &atier(s)."));
            return true;
        }
        for (String line : bp.statusLines(player, track)) {
            player.sendMessage(msg().color("&7" + line));
        }
        return true;
    }

    private boolean handleGift(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage(msg().color("&cUsage: /crates gift <player> <keyId> [amount]"));
            return true;
        }
        String keyId = args[2];
        int amount = 1;
        if (args.length > 3) {
            try { amount = Math.max(1, Integer.parseInt(args[3])); } catch (NumberFormatException ignored) {}
        }
        // Check sender has keys
        int have = manager.getPlayerData().getVirtualKeys(player.getUniqueId(), keyId);
        if (have < amount) {
            player.sendMessage(msg().color("&cNie masz wystarczająco kluczy virtual (&f" + have + "&c/&f" + amount + "&c)."));
            return true;
        }
        Player target = plugin.getServer().getPlayerExact(args[1]);
        if (target != null) {
            guiManager.openGUI(new com.skritped.novacrates.gui.GiftConfirmGUI(plugin, manager, target.getName(), keyId, amount), player);
            return true;
        }
        // Offline gift queue
        org.bukkit.OfflinePlayer off = plugin.getServer().getOfflinePlayer(args[1]);
        if (off == null || (!off.hasPlayedBefore() && !off.isOnline())) {
            player.sendMessage(msg().color("&cNieznany gracz."));
            return true;
        }
        manager.getPlayerData().setVirtualKeys(player.getUniqueId(), keyId, have - amount);
        manager.enqueueGift(off.getUniqueId(), keyId, amount, player.getName());
        player.sendMessage(msg().color("&aGift &f" + amount + "x " + keyId + " &azakolejkowany dla offline &f" + args[1]));
        return true;
    }


    private boolean handleTop(CommandSender sender, String[] args) {
        String crateId = args.length > 1 ? args[1] : null;
        if (crateId != null && manager.getCrate(crateId) == null && !"all".equalsIgnoreCase(crateId)) {
            msg().send(sender, "crate-not-found");
            return true;
        }
        if ("all".equalsIgnoreCase(crateId)) crateId = null;
        if (sender instanceof Player player) {
            guiManager.openGUI(new com.skritped.novacrates.gui.LeaderboardGUI(manager, crateId), player);
        } else {
            var top = manager.getTopOpeners(crateId, 10);
            sender.sendMessage(msg().color("&bTop openers" + (crateId == null ? "" : " (" + crateId + ")") + ":"));
            int rank = 1;
            for (var e : top) {
                String name = plugin.getServer().getOfflinePlayer(e.getKey()).getName();
                sender.sendMessage(msg().color("&8" + rank++ + ". &f" + (name == null ? e.getKey().toString().substring(0, 8) : name) + " &7- &a" + e.getValue()));
            }
        }
        return true;
    }

    private boolean handleDoctor(CommandSender sender, String[] args) {
        if (!sender.hasPermission("novacrates.admin")) {
            msg().send(sender, "no-permission");
            return true;
        }
        sender.sendMessage(msg().color("&b&lNovaCrates Doctor"));
        int issues = 0;
        for (var entry : manager.getCrates().entrySet()) {
            var c = entry.getValue();
            if (c.getRewards().isEmpty()) {
                sender.sendMessage(msg().color("&c[!] Crate &f" + c.getId() + " &chas no rewards"));
                issues++;
            }
            double sum = c.getRewards().stream().mapToDouble(r -> Math.max(0, r.getChance())).sum();
            if (sum <= 0 && !c.getRewards().isEmpty()) {
                sender.sendMessage(msg().color("&c[!] Crate &f" + c.getId() + " &chas total weight 0"));
                issues++;
            }
            var ids = new java.util.HashSet<String>();
            for (var r : c.getRewards()) {
                if (!ids.add(r.getId().toLowerCase(java.util.Locale.ROOT))) {
                    sender.sendMessage(msg().color("&c[!] Crate &f" + c.getId() + " &cduplicate reward id: &f" + r.getId()));
                    issues++;
                }
            }
            if (c.getPityThreshold() > 0 && c.getPityRewardId() != null) {
                boolean found = c.getRewards().stream().anyMatch(r -> r.getId().equals(c.getPityRewardId()));
                if (!found) {
                    sender.sendMessage(msg().color("&c[!] Crate &f" + c.getId() + " &cpity reward missing: &f" + c.getPityRewardId()));
                    issues++;
                }
            }
            if (c.getUnlockRequiresCrate() != null) {
                var req = manager.getCrate(c.getUnlockRequiresCrate());
                if (req == null) {
                    sender.sendMessage(msg().color("&c[!] Crate &f" + c.getId() + " &crequires missing crate: &f" + c.getUnlockRequiresCrate()));
                    issues++;
                } else if (req.getUnlockRequiresCrate() != null
                        && req.getUnlockRequiresCrate().equalsIgnoreCase(c.getId())) {
                    sender.sendMessage(msg().color("&e[!] Unlock cycle between &f" + c.getId() + " &eand &f" + req.getId()));
                    issues++;
                }
            }
        }
        for (var e : manager.getPlayerData().getBlocks().entrySet()) {
            if (manager.getCrate(e.getValue()) == null) {
                sender.sendMessage(msg().color("&e[!] Orphan block &f" + e.getKey() + " &e→ missing crate &f" + e.getValue()));
                issues++;
            }
        }
        if (plugin.getServer().getPluginManager().getPlugin("PlaceholderAPI") == null) {
            sender.sendMessage(msg().color("&7[~] PlaceholderAPI not installed (optional)"));
        } else {
            sender.sendMessage(msg().color("&a[OK] PlaceholderAPI present"));
        }
        if (plugin.getServer().getPluginManager().getPlugin("Vault") == null) {
            sender.sendMessage(msg().color("&7[~] Vault not installed (MONEY costs disabled)"));
        } else {
            sender.sendMessage(msg().color("&a[OK] Vault present"));
        }
        if (plugin.getServer().getPluginManager().getPlugin("PlayerPoints") == null) {
            sender.sendMessage(msg().color("&7[~] PlayerPoints not installed (POINTS costs optional)"));
        } else {
            sender.sendMessage(msg().color("&a[OK] PlayerPoints present"));
        }
        try {
            String type = plugin.getConfig().getString("database.type", "sqlite");
            sender.sendMessage(msg().color("&a[OK] Database type: &f" + type));
        } catch (Exception e) {
            sender.sendMessage(msg().color("&c[!] Database config error: " + e.getMessage()));
            issues++;
        }
        if (args.length > 1 && (args[1].equalsIgnoreCase("fix") || args[1].equalsIgnoreCase("autofix"))
                && sender.hasPermission("novacrates.admin")) {
            int fixed = 0;
            for (var e : new java.util.ArrayList<>(manager.getPlayerData().getBlocks().entrySet())) {
                if (manager.getCrate(e.getValue()) == null) {
                    manager.getPlayerData().removeBlock(e.getKey());
                    fixed++;
                }
            }
            sender.sendMessage(msg().color("&aAuto-fix: removed &f" + fixed + " &aorphan block(s)."));
        }
        if (issues == 0) {
            sender.sendMessage(msg().color("&aNo critical issues found. Crates loaded: &f" + manager.getCrates().size()));
        } else {
            sender.sendMessage(msg().color("&eFound &f" + issues + " &eissue(s). Use &f/crates doctor fix &efor orphan blocks."));
        }
        return true;
    }


    private boolean handleChangeName(CommandSender sender, String[] args) {
        if (!hasAdmin(sender, "novacrates.admin") && !hasAdmin(sender, "novacrates.admin.editor")) {
            msg().send(sender, "no-permission");
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage(msg().color("&cUsage: /nv changename <crateId> <Display Name...>"));
            sender.sendMessage(msg().color("&7Example: &f/nv changename super_rare Super Rare"));
            sender.sendMessage(msg().color("&7Supports spaces, capitals and & color codes."));
            return true;
        }
        String crateId = args[1];
        if (manager.getCrate(crateId) == null) {
            msg().send(sender, "crate-not-found");
            return true;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 2; i < args.length; i++) {
            if (i > 2) sb.append(' ');
            sb.append(args[i]);
        }
        String display = sb.toString().trim();
        if (display.isEmpty()) {
            sender.sendMessage(msg().color("&cDisplay name cannot be empty."));
            return true;
        }
        if (manager.setDisplayName(crateId, display)) {
            sender.sendMessage(msg().color("&aDisplay name set for &f" + crateId + " &a→ &r"
                    + msg().color(display)));
            sender.sendMessage(msg().color("&7Zaktualizowano nazwę GUI i klucza (hologram bez zmian)."));
        } else {
            sender.sendMessage(msg().color("&cCould not change name."));
        }
        return true;
    }

    private boolean handleHelp(CommandSender sender) {
        sender.sendMessage(msg().color("&b&lNovaCrates — komendy gracza"));
        sender.sendMessage(msg().color("&e/crates list &7— lista skrzynek"));
        sender.sendMessage(msg().color("&e/crates open <id> [x] &7— otwórz"));
        sender.sendMessage(msg().color("&e/crates preview <id> &7— podgląd"));
        sender.sendMessage(msg().color("&e/crates keys &7— klucze"));
        sender.sendMessage(msg().color("&e/crates shop &7— sklep"));
        sender.sendMessage(msg().color("&e/crates history &7— historia GUI"));
        sender.sendMessage(msg().color("&e/crates progress &7— postęp / pity"));
        sender.sendMessage(msg().color("&e/crates pass &7— battle pass"));
        sender.sendMessage(msg().color("&e/crates top [crate] &7— ranking"));
        sender.sendMessage(msg().color("&e/crates gift <gracz> <key> [ile] &7— gift"));
        if (sender.hasPermission("novacrates.admin")) {
            sender.sendMessage(msg().color("&b&lAdmin"));
            sender.sendMessage(msg().color("&e/crates settings|lang|editor|manage|givekey|reload|doctor|audit|export|import|queueopen|unlock|setblock"));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 0) {
            return playerSubs(sender);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 1) {
            return filter(playerSubs(sender), args[0]);
        }
        // args.length >= 2
        if (List.of("open", "preview", "editor", "setblock", "delete", "clone", "stats", "export", "top", "changename", "setname", "displayname").contains(sub)) {
            if (args.length == 2) {
                List<String> crates = new ArrayList<>(manager.getCrates().keySet());
                if (sub.equals("top")) crates.add(0, "all");
                return filter(crates, args[1]);
            }
            if (args.length == 3 && sub.equals("open")) {
                return filter(Arrays.asList("1", "5", "10", "25", "50"), args[2]);
            }
            if (args.length == 3 && sub.equals("clone")) {
                return filter(List.of("<newId>"), args[2]);
            }
        }
        if (sub.equals("givekey") || sub.equals("queueopen") || sub.equals("unlock")
                || sub.equals("gift")
                || ((sub.equals("keys") || sub.equals("history") || sub.equals("audit"))
                && sender.hasPermission("novacrates.admin"))) {
            if (args.length == 2) {
                List<String> names = plugin.getServer().getOnlinePlayers().stream()
                        .map(Player::getName).collect(Collectors.toList());
                return filter(names, args[1]);
            }
            if (args.length == 3 && (sub.equals("givekey") || sub.equals("queueopen") || sub.equals("unlock") || sub.equals("gift"))) {
                if (sub.equals("gift")) {
                    // key ids from styles + crate key ids
                    List<String> keys = new ArrayList<>(manager.getCrates().values().stream()
                            .map(c -> c.getKeyId()).distinct().toList());
                    return filter(keys, args[2]);
                }
                return filter(new ArrayList<>(manager.getCrates().keySet()), args[2]);
            }
            if (args.length >= 4 && sub.equals("givekey")) {
                return filter(Arrays.asList("1", "5", "10", "25", "virtual"), args[args.length - 1]);
            }
            if (args.length >= 4 && sub.equals("gift")) {
                return filter(Arrays.asList("1", "5", "10"), args[args.length - 1]);
            }
            if (args.length >= 4 && sub.equals("queueopen")) {
                List<String> opts = new ArrayList<>(List.of("consumekey"));
                CrateDefinition c = manager.getCrate(args[2]);
                if (c != null) {
                    c.getRewards().forEach(r -> opts.add(r.getId()));
                }
                return filter(opts, args[args.length - 1]);
            }
            if (args.length == 3 && sub.equals("audit")) {
                return filter(Arrays.asList("10", "20", "50", "100"), args[2]);
            }
            if (args.length >= 3 && sub.equals("history")) {
                List<String> opts = new ArrayList<>(manager.getCrates().keySet());
                opts.addAll(List.of("1", "2", "3", "crate:"));
                return filter(opts, args[args.length - 1]);
            }
        }
        if (sub.equals("pass")) {
            if (args.length == 2) {
                return filter(Arrays.asList("default", "claim"), args[1]);
            }
            if (args.length == 3 && args[1].equalsIgnoreCase("claim")) {
                return filter(List.of("default"), args[2]);
            }
        }
        if (sub.equals("reload") && args.length == 2) {
            return filter(List.of("full"), args[1]);
        }
        if (sub.equals("doctor") && args.length == 2) {
            return filter(List.of("fix", "autofix"), args[1]);
        }
        if ((sub.equals("lang") || sub.equals("language")) && args.length == 2) {
            return filter(List.of("pl", "en"), args[1]);
        }
        if (sub.equals("import") && args.length == 3) {
            return filter(List.of("overwrite"), args[2]);
        }
        if (sub.equals("create") && args.length == 2) {
            return filter(List.of("<crateId>"), args[1]);
        }
        // Nothing more to complete
        return List.of();
    }

    private List<String> playerSubs(CommandSender sender) {
        List<String> subs = new ArrayList<>(Arrays.asList(
                "list", "open", "preview", "keys", "history", "shop", "stats",
                "progress", "pass", "gift", "help", "top"));
        if (sender.hasPermission("novacrates.admin")
                || sender.hasPermission("novacrates.admin.reload")
                || sender.hasPermission("novacrates.admin.givekey")) {
            subs.addAll(Arrays.asList("changename", "setname", "displayname", "editor", "manage", "create", "delete", "clone",
                    "givekey", "setblock", "removeblock", "reload", "export", "import", "audit",
                    "queueopen", "unlock", "exportcsv", "exportall", "cleanup-lore", "doctor",
                    "settings", "lang", "config"));
        }
        return subs;
    }

    private List<String> filter(List<String> options, String token) {
        String t = token.toLowerCase(Locale.ROOT);
        return options.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(t)).collect(Collectors.toList());
    }
}
