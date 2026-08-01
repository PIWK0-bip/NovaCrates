package com.skritped.novacrates.service;

import com.skritped.novacrates.model.CrateDefinition;
import com.skritped.novacrates.storage.PlayerDataRepository;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class BattlePassService {
    private static final Set<String> ALLOWED = Set.of(
            "give", "eco", "economy", "money", "points", "lp", "luckperms",
            "pex", "say", "broadcast", "bc", "tell", "msg", "effect", "xp",
            "experience", "crate", "crates", "novacrates", "title", "particle",
            "playsound", "advancement"
    );

    private final JavaPlugin plugin;
    private final PlayerDataRepository playerData;
    private final KeyService keyService;
    private final MessageService messages;

    public BattlePassService(JavaPlugin plugin, PlayerDataRepository playerData,
                             KeyService keyService, MessageService messages) {
        this.plugin = plugin;
        this.playerData = playerData;
        this.keyService = keyService;
        this.messages = messages;
    }

    public boolean isEnabled() {
        return plugin.getConfig().getBoolean("battle-pass.enabled", false);
    }

    public void onCrateOpened(Player player, CrateDefinition crate) {
        if (!isEnabled() || player == null || crate == null) return;
        String track = crate.getPassTrack();
        if (track == null || track.isBlank()) {
            track = plugin.getConfig().getString("battle-pass.default-track", "default");
        }
        int points = crate.getPassPoints();
        if (points <= 0) {
            points = plugin.getConfig().getInt("battle-pass.points-per-open", 1);
        }
        if (points <= 0) return;
        playerData.addPassPoints(player.getUniqueId(), track, points);
        autoClaim(player, track);
    }

    public int getPoints(UUID playerId, String track) {
        return playerData.getPassPoints(playerId, track == null ? "default" : track);
    }

    public List<String> statusLines(Player player, String track) {
        List<String> lines = new ArrayList<>();
        if (!isEnabled()) {
            lines.add("Battle pass disabled.");
            return lines;
        }
        String t = track == null || track.isBlank() ? "default" : track.toLowerCase(Locale.ROOT);
        int points = getPoints(player.getUniqueId(), t);
        lines.add("Track: " + t + " | Points: " + points);
        ConfigurationSection tiers = plugin.getConfig().getConfigurationSection("battle-pass.tracks." + t + ".tiers");
        if (tiers == null) {
            lines.add("No tiers configured.");
            return lines;
        }
        for (String key : tiers.getKeys(false)) {
            int tier;
            try { tier = Integer.parseInt(key); } catch (NumberFormatException e) { continue; }
            ConfigurationSection sec = tiers.getConfigurationSection(key);
            if (sec == null) continue;
            int need = sec.getInt("points", 0);
            boolean claimed = playerData.isPassTierClaimed(player.getUniqueId(), t, tier);
            String state = claimed ? "CLAIMED" : (points >= need ? "READY" : "LOCKED");
            lines.add("Tier " + tier + " (" + need + " pts): " + state);
        }
        return lines;
    }

    public List<TierInfo> getTiers(Player player, String track) {
        List<TierInfo> list = new ArrayList<>();
        String t = track == null || track.isBlank() ? "default" : track.toLowerCase(Locale.ROOT);
        int points = getPoints(player.getUniqueId(), t);
        ConfigurationSection tiers = plugin.getConfig().getConfigurationSection("battle-pass.tracks." + t + ".tiers");
        if (tiers == null) return list;
        for (String key : tiers.getKeys(false)) {
            int tier;
            try { tier = Integer.parseInt(key); } catch (NumberFormatException e) { continue; }
            ConfigurationSection sec = tiers.getConfigurationSection(key);
            if (sec == null) continue;
            int need = sec.getInt("points", 0);
            boolean claimed = playerData.isPassTierClaimed(player.getUniqueId(), t, tier);
            String state = claimed ? "CLAIMED" : (points >= need ? "READY" : "LOCKED");
            list.add(new TierInfo(tier, need, state, sec.getStringList("commands"),
                    sec.getString("virtual-key"), sec.getInt("virtual-key-amount", 1)));
        }
        return list;
    }

    public int claimReady(Player player, String track) {
        if (!isEnabled()) return 0;
        String t = track == null || track.isBlank() ? "default" : track.toLowerCase(Locale.ROOT);
        return autoClaim(player, t);
    }

    private int autoClaim(Player player, String track) {
        ConfigurationSection tiers = plugin.getConfig().getConfigurationSection("battle-pass.tracks." + track + ".tiers");
        if (tiers == null) return 0;
        int points = getPoints(player.getUniqueId(), track);
        int claimed = 0;
        for (String key : tiers.getKeys(false)) {
            int tier;
            try { tier = Integer.parseInt(key); } catch (NumberFormatException e) { continue; }
            if (playerData.isPassTierClaimed(player.getUniqueId(), track, tier)) continue;
            ConfigurationSection sec = tiers.getConfigurationSection(key);
            if (sec == null) continue;
            if (points < sec.getInt("points", 0)) continue;
            grantTier(player, track, tier, sec);
            playerData.claimPassTier(player.getUniqueId(), track, tier);
            claimed++;
            messages.send(player, "pass-tier-claimed", Map.of("tier", String.valueOf(tier), "track", track));
        }
        return claimed;
    }

    private void grantTier(Player player, String track, int tier, ConfigurationSection sec) {
        boolean strict = plugin.getConfig().getBoolean("settings.strict-reward-commands", true);
        for (String cmd : sec.getStringList("commands")) {
            if (cmd == null || cmd.isBlank()) continue;
            String parsed = cmd.replace("%player%", player.getName())
                    .replace("%uuid%", player.getUniqueId().toString())
                    .replace("%tier%", String.valueOf(tier))
                    .replace("%track%", track);
            if (parsed.startsWith("/")) parsed = parsed.substring(1);
            if (strict && !isAllowed(parsed)) {
                plugin.getLogger().warning("Blocked battle-pass command: " + parsed);
                continue;
            }
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), parsed);
        }
        String key = sec.getString("virtual-key");
        int amount = sec.getInt("virtual-key-amount", 1);
        if (key != null && !key.isBlank()) {
            keyService.giveVirtualKey(player, key, playerData, amount);
        }
    }

    private boolean isAllowed(String command) {
        String head = command.split("\\s+")[0].toLowerCase(Locale.ROOT);
        if (ALLOWED.contains(head)) return true;
        if (head.contains(":")) {
            return ALLOWED.contains(head.substring(head.indexOf(':') + 1));
        }
        return false;
    }

    public record TierInfo(int tier, int pointsRequired, String state,
                           List<String> commands, String virtualKey, int virtualKeyAmount) {}
}
