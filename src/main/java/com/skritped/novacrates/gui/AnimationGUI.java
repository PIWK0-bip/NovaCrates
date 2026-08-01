package com.skritped.novacrates.gui;

import com.skritped.novacrates.model.CrateDefinition;
import com.skritped.novacrates.model.RewardDefinition;
import com.skritped.novacrates.service.ItemFactory;
import com.cryptomorin.xseries.XMaterial;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * CSGO-style horizontal scroll. Center slot index in ROLL_SLOTS is the pointer (slot 13).
 * Target offset is computed so the winner is centered when the animation ends.
 */
public class AnimationGUI extends InventoryGUI {
    private static final int[] ROLL_SLOTS = {9, 10, 11, 12, 13, 14, 15, 16, 17};
    private static final int CENTER_INDEX = 4; // index within ROLL_SLOTS -> inventory slot 13

    private final JavaPlugin plugin;
    private final CrateDefinition crate;
    private final RewardDefinition winner;
    private final ItemFactory itemFactory;
    private final List<RewardDefinition> strip = new ArrayList<>();
    private int offset;
    private int targetOffset;
    private int bouncePhase; // 0=normal, 1=back, 2=forward
    private int age;
    private final int duration;
    private final String style;

    public AnimationGUI(JavaPlugin plugin, CrateDefinition crate, RewardDefinition winner,
                        ItemFactory itemFactory) {
        this.plugin = plugin;
        this.crate = crate;
        this.winner = winner;
        this.itemFactory = itemFactory;
        this.duration = plugin.getConfig().getInt("settings.animation-duration-ticks", 80);
        this.style = crate.getAnimation() == null ? "CSGO" : crate.getAnimation().toUpperCase();
        buildStrip();
    }

    private void buildStrip() {
        List<RewardDefinition> pool = crate.getRewards();
        if (pool.isEmpty()) {
            return;
        }
        int length = 48;
        for (int i = 0; i < length; i++) {
            strip.add(pool.get(ThreadLocalRandom.current().nextInt(pool.size())));
        }
        // Place winner so that with targetOffset, strip[targetOffset + CENTER_INDEX] == winner
        int winnerStripIndex = length - ROLL_SLOTS.length + CENTER_INDEX;
        // leave room for scroll: targetOffset = winnerStripIndex - CENTER_INDEX
        targetOffset = Math.max(0, winnerStripIndex - CENTER_INDEX);
        if (winnerStripIndex >= 0 && winnerStripIndex < strip.size()) {
            strip.set(winnerStripIndex, winner);
        }
        offset = 0;
    }

    @Override
    protected Inventory createInventory() {
        return Bukkit.createInventory(null, 27, ChatColor.translateAlternateColorCodes('&',
                crate.getDisplayName() + " &8- &b" + style));
    }

    @Override
    public void decorate(Player player) {
        for (int slot = 0; slot < 27; slot++) {
            getInventory().setItem(slot, filler());
        }
        getInventory().setItem(4, pointer());
        getInventory().setItem(22, pointer());
        renderStrip();
        if ("INSTANT".equals(style) || "NONE".equals(style)) {
            getInventory().setItem(13, itemFactory.create(winner));
        }
    }

    public void nextFrame(Player player) {
        age += Math.max(1, plugin.getConfig().getInt("settings.animation-frame-ticks", 2));
        if ("INSTANT".equals(style) || "NONE".equals(style)) {
            getInventory().setItem(13, itemFactory.create(winner));
            return;
        }

        double progress = Math.min(1.0, (double) age / Math.max(1, duration));
        if ("WHEEL".equals(style)) {
            // Faster start, stronger ease-out (full spin feel)
            int desired = (int) Math.round(targetOffset * easeOutQuint(progress));
            offset = Math.min(Math.max(desired, offset), targetOffset);
        } else if ("SLOTS".equals(style)) {
            // Stepper: discrete jumps every few frames
            int steps = Math.max(1, targetOffset);
            int desired = (int) Math.floor(steps * easeOutCubic(progress));
            offset = Math.min(desired, targetOffset);
        } else {
            // CSGO default
            int desired = (int) Math.round(targetOffset * easeOutCubic(progress));
            if (desired > offset) {
                offset = desired;
            } else if (offset < targetOffset && progress > 0.9) {
                offset = Math.min(offset + 1, targetOffset);
            }
            offset = Math.min(offset, targetOffset);
        }
        renderStrip();

        if (player != null && player.isOnline()) {
            try {
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.35f, 1.0f + (float) progress * 0.5f);
            } catch (Exception ignored) {
            }
        }

        if (age >= duration) {
            // Bounce: 1-2 steps back then settle on winner
            if (bouncePhase == 0 && targetOffset >= 2) {
                bouncePhase = 1;
                offset = Math.max(0, targetOffset - 2);
                renderStrip();
                return;
            }
            if (bouncePhase == 1) {
                bouncePhase = 2;
                offset = Math.max(0, targetOffset - 1);
                renderStrip();
                return;
            }
            offset = targetOffset;
            renderStrip();
            getInventory().setItem(13, itemFactory.create(winner));
            if (player != null && player.isOnline()) {
                try {
                    player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static double easeOutCubic(double t) {
        double u = 1 - t;
        return 1 - u * u * u;
    }

    private static double easeOutQuint(double t) {
        double u = 1 - t;
        return 1 - u * u * u * u * u;
    }

    private void renderStrip() {
        for (int i = 0; i < ROLL_SLOTS.length; i++) {
            int idx = Math.min(offset + i, strip.size() - 1);
            RewardDefinition def = strip.get(Math.max(0, idx));
            getInventory().setItem(ROLL_SLOTS[i], itemFactory.create(def));
        }
    }

    public int getAge() {
        return age;
    }

    public int getDuration() {
        return duration;
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
    }

    private ItemStack filler() {
        return XMaterial.matchXMaterial("BLACK_STAINED_GLASS_PANE")
                .map(XMaterial::parseItem)
                .orElse(new ItemStack(org.bukkit.Material.BLACK_STAINED_GLASS_PANE));
    }

    private ItemStack pointer() {
        ItemStack item = XMaterial.matchXMaterial("LIME_STAINED_GLASS_PANE")
                .map(XMaterial::parseItem)
                .orElse(new ItemStack(org.bukkit.Material.LIME_STAINED_GLASS_PANE));
        var meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GREEN + "▼");
            item.setItemMeta(meta);
        }
        return item;
    }
}
