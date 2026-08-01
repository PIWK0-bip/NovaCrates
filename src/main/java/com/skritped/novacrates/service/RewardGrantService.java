package com.skritped.novacrates.service;

import com.skritped.novacrates.model.RewardDefinition;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class RewardGrantService {
    private static final Set<String> DEFAULT_ALLOWED = Set.of(
            "give", "eco", "economy", "money", "points", "lp", "luckperms",
            "pex", "say", "broadcast", "bc", "tell", "msg", "effect", "xp",
            "experience", "crate", "crates", "novacrates", "minecraft:give",
            "minecraft:effect", "minecraft:xp", "title", "bossbar",
            "particle", "playsound", "advancement"
    );

    private static final Set<String> ALWAYS_BLOCKED = Set.of(
            "op", "deop", "ban", "ban-ip", "pardon", "pardon-ip", "kick", "stop",
            "reload", "rl", "restart", "plugin", "plugins", "pl", "bukkit:plugins",
            "bukkit:reload", "bukkit:ban", "minecraft:ban", "minecraft:op",
            "minecraft:deop", "minecraft:kick", "minecraft:stop", "execute",
            "minecraft:execute", "function", "minecraft:function", "datapack",
            "whitelist", "timings", "spark"
    );

    private static final int DEFAULT_MAX_COMMAND_LENGTH = 256;

    private static final String[] DENIED_ARG_PATTERNS = {
            "permission set *", "permission set*", "parent add",
            " op", "deop", " ban", " kick"
    };

    private final JavaPlugin plugin;
    private final ItemFactory itemFactory;
    private final boolean strictCommands;
    private volatile Set<String> allowedPrefixes;
    private volatile Set<String> allowedExactCommands;
    private volatile boolean consoleOnly;
    private volatile int maxCommandLength;

    public RewardGrantService(JavaPlugin plugin, ItemFactory itemFactory, boolean strictCommands) {
        this.plugin = plugin;
        this.itemFactory = itemFactory;
        this.strictCommands = strictCommands;
        reloadConfig();
    }

    public void reloadConfig() {
        Set<String> allowed = new HashSet<>(DEFAULT_ALLOWED);
        for (String prefix : plugin.getConfig().getStringList("settings.allowed-command-prefixes")) {
            if (prefix != null && !prefix.isBlank()) {
                allowed.add(prefix.toLowerCase(Locale.ROOT).trim());
            }
        }
        this.allowedPrefixes = Set.copyOf(allowed);
        Set<String> exact = new HashSet<>();
        for (String e : plugin.getConfig().getStringList("settings.allowed-reward-commands")) {
            if (e != null && !e.isBlank()) {
                exact.add(e.toLowerCase(Locale.ROOT).trim());
            }
        }
        this.allowedExactCommands = Set.copyOf(exact);
        this.consoleOnly = plugin.getConfig().getBoolean("settings.reward-commands-console-only", false);
        this.maxCommandLength = Math.max(32, plugin.getConfig().getInt(
                "settings.max-reward-command-length", DEFAULT_MAX_COMMAND_LENGTH));
    }

    /**
     * @return true if inventory was full and items were dropped
     */
    public boolean grant(Player player, RewardDefinition reward) {
        if (player == null || reward == null) {
            return false;
        }
        boolean dropped = false;
        ItemStack item = itemFactory.create(reward);
        if (item != null && !item.getType().isAir()) {
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
            if (!leftover.isEmpty()) {
                leftover.values().forEach(stack ->
                        player.getWorld().dropItemNaturally(player.getLocation(), stack));
                dropped = true;
            }
        }
        if (reward.getCommands() != null) {
            for (String raw : reward.getCommands()) {
                if (raw == null || raw.isBlank()) {
                    continue;
                }
                runCommand(player, raw, reward.isCommandAsPlayer());
            }
        }
        return dropped;
    }

    private void runCommand(Player player, String raw, boolean asPlayer) {
        String parsed = raw
                .replace("%player%", player.getName())
                .replace("%uuid%", player.getUniqueId().toString())
                .replace("{player}", player.getName())
                .replace("{uuid}", player.getUniqueId().toString());
        if (parsed.startsWith("/")) {
            parsed = parsed.substring(1);
        }

        if (parsed.length() > maxCommandLength) {
            plugin.getLogger().warning("Blocked reward command (too long, max=" + maxCommandLength + "): "
                    + parsed.substring(0, Math.min(64, parsed.length())) + "...");
            return;
        }

        if (isAlwaysBlocked(parsed)) {
            plugin.getLogger().warning("Blocked reward command (dangerous): " + parsed);
            return;
        }

        if (containsDeniedArgs(parsed)) {
            plugin.getLogger().warning("Blocked reward command (denied args): " + parsed);
            return;
        }

        if (strictCommands && allowedExactCommands.isEmpty() && !isAllowed(parsed)) {
            plugin.getLogger().warning("Blocked reward command (not whitelisted): " + parsed);
            return;
        }

        if (!allowedExactCommands.isEmpty()) {
            String check = parsed.toLowerCase(Locale.ROOT);
            boolean ok = allowedExactCommands.stream().anyMatch(check::startsWith);
            if (!ok) {
                plugin.getLogger().warning("Blocked reward command (not in exact whitelist): " + parsed);
                return;
            }
        }

        if (plugin.getConfig().getBoolean("settings.debug", false)
                || plugin.getConfig().getBoolean("settings.log-reward-commands", true)) {
            plugin.getLogger().info("Reward command for " + player.getName()
                    + (asPlayer && !consoleOnly ? " (as player): " : " (console): ") + parsed);
        }

        boolean runAsPlayer = asPlayer && !consoleOnly;
        if (runAsPlayer) {
            player.performCommand(parsed);
        } else {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), parsed);
        }
    }

    private boolean containsDeniedArgs(String command) {
        String lower = command.toLowerCase(Locale.ROOT);
        for (String pat : DENIED_ARG_PATTERNS) {
            if (lower.contains(pat)) {
                return true;
            }
        }
        if ((lower.startsWith("lp ") || lower.startsWith("luckperms "))
                && lower.contains("permission") && lower.contains(" set")
                && (lower.contains(" *") || lower.endsWith("*"))) {
            return true;
        }
        return false;
    }

    private boolean isAlwaysBlocked(String command) {
        String lower = command.toLowerCase(Locale.ROOT);
        String head = lower.split("\\s+")[0];
        if (ALWAYS_BLOCKED.contains(head)) {
            return true;
        }
        if (head.contains(":")) {
            String withoutNs = head.substring(head.indexOf(':') + 1);
            return ALWAYS_BLOCKED.contains(withoutNs) || ALWAYS_BLOCKED.contains(head);
        }
        return false;
    }

    private boolean isAllowed(String command) {
        String head = command.split("\\s+")[0].toLowerCase(Locale.ROOT);
        Set<String> allowed = allowedPrefixes;
        if (allowed == null) {
            return false;
        }
        if (allowed.contains(head)) {
            return true;
        }
        if (head.contains(":")) {
            String withoutNs = head.substring(head.indexOf(':') + 1);
            return allowed.contains(withoutNs) || allowed.contains(head);
        }
        return false;
    }
}
