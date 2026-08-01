package com.skritped.novacrates.hook;

import com.skritped.novacrates.NovaCratesPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;

/**
 * Soft-hooks SuperbVote / Votifier-style events via reflection.
 * Config: settings.vote-reward-key / vote-reward-amount
 */
public final class VoteKeyListener {
    private VoteKeyListener() {}

    public static void tryRegister(NovaCratesPlugin plugin) {
        String keyId = plugin.getConfig().getString("settings.vote-reward-key", "");
        if (keyId == null || keyId.isBlank()) return;
        int amount = plugin.getConfig().getInt("settings.vote-reward-amount", 1);
        // SuperbVote: SuperbVoteEvent
        tryRegisterEvent(plugin, "io.minimum.minecraft.superbvote.event.SuperbVoteEvent", keyId, amount);
        // NuVotifier: VotifierEvent
        tryRegisterEvent(plugin, "com.vexsoftware.votifier.model.VotifierEvent", keyId, amount);
    }

    private static void tryRegisterEvent(NovaCratesPlugin plugin, String className, String keyId, int amount) {
        try {
            @SuppressWarnings("unchecked")
            Class<? extends Event> cls = (Class<? extends Event>) Class.forName(className);
            EventExecutor exec = (listener, event) -> {
                try {
                    Player player = null;
                    try {
                        Object vote = event.getClass().getMethod("getVote").invoke(event);
                        String name = (String) vote.getClass().getMethod("getUsername").invoke(vote);
                        player = Bukkit.getPlayerExact(name);
                    } catch (NoSuchMethodException e) {
                        try {
                            player = (Player) event.getClass().getMethod("getPlayer").invoke(event);
                        } catch (Exception ignored) {}
                    }
                    if (player != null && player.isOnline()) {
                        plugin.getKeyService().giveVirtualKey(player, keyId, plugin.getRepository(), amount);
                        player.sendMessage(plugin.getMessageService().color(
                                "&aOtrzymałeś &f" + amount + "x &a" + keyId + " &aza głos!"));
                    }
                } catch (Throwable t) {
                    if (plugin.getConfig().getBoolean("settings.debug", false)) {
                        plugin.getLogger().warning("Vote hook error: " + t.getMessage());
                    }
                }
            };
            Bukkit.getPluginManager().registerEvent(cls, new Listener() {}, EventPriority.NORMAL, exec, plugin, true);
            plugin.getLogger().info("Hooked vote event: " + className);
        } catch (ClassNotFoundException ignored) {
        } catch (Throwable t) {
            plugin.getLogger().info("Vote hook skipped for " + className + ": " + t.getMessage());
        }
    }
}
