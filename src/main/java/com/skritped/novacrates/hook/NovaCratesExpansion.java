package com.skritped.novacrates.hook;

import com.skritped.novacrates.manager.CrateManager;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class NovaCratesExpansion extends PlaceholderExpansion {
    private final JavaPlugin plugin;
    private final CrateManager manager;

    public NovaCratesExpansion(JavaPlugin plugin, CrateManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "novacrates";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Skritped";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onPlaceholderRequest(Player player, @NotNull String params) {
        if (player == null) {
            return "";
        }
        if (params.startsWith("keys_")) {
            return String.valueOf(manager.getPlayerData()
                    .getVirtualKeys(player.getUniqueId(), params.substring(5)));
        }
        if (params.startsWith("pity_")) {
            return String.valueOf(manager.getPlayerData()
                    .getPity(player.getUniqueId(), params.substring(5)));
        }
        if (params.equals("opens")) {
            return String.valueOf(manager.getPlayerData().getStat("opens.total"));
        }
        if (params.startsWith("opens_")) {
            return String.valueOf(manager.getPlayerData().getStat("opens." + params.substring(6)));
        }
        if (params.equals("daily")) {
            return String.valueOf(manager.getPlayerData().getDailyOpens(player.getUniqueId()));
        }
        if (params.startsWith("pass_")) {
            String track = params.substring(5);
            if (manager.getBattlePassService() != null) {
                return String.valueOf(manager.getBattlePassService().getPoints(player.getUniqueId(), track));
            }
            return "0";
        }
        if (params.startsWith("unlocked_")) {
            String crate = params.substring(9);
            return manager.getPlayerData().isCrateUnlocked(player.getUniqueId(), crate) ? "true" : "false";
        }
        if (params.startsWith("opens_player_")) {
            String crate = params.substring(13);
            return String.valueOf(manager.getPlayerCrateOpens(player.getUniqueId(), crate));
        }
        // top_name_<rank>[_crate], top_opens_<rank>[_crate], top_<rank>[_crate]
        if (params.startsWith("top_name_") || params.startsWith("top_opens_") || params.startsWith("top_")) {
            boolean nameOnly = params.startsWith("top_name_");
            boolean opensOnly = params.startsWith("top_opens_");
            String rest = nameOnly ? params.substring(9) : opensOnly ? params.substring(10) : params.substring(4);
            String crate = null;
            int rank = 1;
            int us = rest.indexOf('_');
            try {
                if (us > 0) {
                    rank = Integer.parseInt(rest.substring(0, us));
                    crate = rest.substring(us + 1);
                } else {
                    rank = Integer.parseInt(rest);
                }
            } catch (NumberFormatException e) {
                return "";
            }
            var top = manager.getTopOpeners(crate, Math.max(rank, 1));
            if (rank < 1 || rank > top.size()) return "";
            var entry = top.get(rank - 1);
            String name = org.bukkit.Bukkit.getOfflinePlayer(entry.getKey()).getName();
            if (name == null) name = entry.getKey().toString().substring(0, 8);
            if (nameOnly) return name;
            if (opensOnly) return String.valueOf(entry.getValue());
            return name + ":" + entry.getValue();
        }
        return null;
    }
}
