package com.skritped.novacrates.gui;

import com.skritped.novacrates.model.CrateDefinition;
import com.skritped.novacrates.model.RewardDefinition;
import com.skritped.novacrates.service.ItemFactory;
import com.skritped.novacrates.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

public class ChestAnimationGUI extends InventoryGUI {
    private final JavaPlugin plugin;
    private final CrateDefinition crate;
    private final RewardDefinition winner;
    private final ItemFactory itemFactory;
    private final List<RewardDefinition> layout = new ArrayList<>();
    private final int duration;
    private final int winSlot;
    private int age;
    private int highlightSlot = -1;
    private int framesSinceSwitch;
    private boolean finished;

    public ChestAnimationGUI(JavaPlugin plugin, CrateDefinition crate, RewardDefinition winner, ItemFactory itemFactory) {
        this.plugin = plugin;
        this.crate = crate;
        this.winner = winner;
        this.itemFactory = itemFactory;
        this.duration = Math.max(30, plugin.getConfig().getInt("settings.animation-duration-ticks", 80));
        List<RewardDefinition> pool = crate.getRewards().isEmpty()
                ? List.of(winner) : new ArrayList<>(crate.getRewards());
        for (int i = 0; i < 45; i++) {
            layout.add(pool.get(i % pool.size()));
        }
        this.winSlot = ThreadLocalRandom.current().nextInt(45);
        layout.set(winSlot, winner);
        this.highlightSlot = winSlot;
    }

    public int getAge() { return age; }
    public int getDuration() { return duration; }

    @Override
    protected Inventory createInventory() {
        String title = Text.strip(crate.getDisplayName());
        if (title.length() > 30) title = title.substring(0, 30);
        return Bukkit.createInventory(null, 54, Text.legacy("&8" + title));
    }

    @Override
    public void decorate(Player player) {
        clearButtons();
        getInventory().clear();
        render(false);
        ItemStack pane = glass(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int s = 45; s < 54; s++) getInventory().setItem(s, pane);
        getInventory().setItem(49, glass(Material.YELLOW_STAINED_GLASS_PANE, "&eLosowanie..."));
        super.decorate(player);
    }

    public void nextFrame(Player player) {
        int frameTicks = Math.max(1, plugin.getConfig().getInt("settings.animation-frame-ticks", 2));
        age += frameTicks;
        double progress = Math.min(1.0, (double) age / duration);
        // Slow down switches toward end (ease-out)
        int switchEvery = progress < 0.5 ? 1 : (progress < 0.75 ? 2 : (progress < 0.9 ? 4 : 8));
        framesSinceSwitch++;
        if (!finished && progress < 0.92) {
            if (framesSinceSwitch >= switchEvery) {
                framesSinceSwitch = 0;
                highlightSlot = ThreadLocalRandom.current().nextInt(45);
            }
        } else {
            highlightSlot = winSlot;
        }
        render(progress >= 1.0);
        if (player != null && player.isOnline()) {
            try {
                Sound sound = raritySound(layout.get(Math.max(0, highlightSlot)).getRarity());
                float pitch = 0.7f + (float) progress * 1.0f;
                player.playSound(player.getLocation(), sound, 0.35f, pitch);
            } catch (Throwable ignored) {}
        }
        if (age >= duration && !finished) {
            finished = true;
            highlightSlot = winSlot;
            render(true);
            getInventory().setItem(49, glass(Material.LIME_STAINED_GLASS_PANE, "&a&lWYGRANA!"));
            if (player != null && player.isOnline()) {
                try {
                    player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
                    var loc = player.getLocation().add(0, 1.2, 0);
                    player.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, loc, 25, 0.4, 0.5, 0.4, 0.05);
                    player.getWorld().spawnParticle(Particle.END_ROD, loc, 15, 0.3, 0.4, 0.3, 0.02);
                } catch (Throwable ignored) {}
            }
        }
    }

    private void render(boolean finalFrame) {
        for (int i = 0; i < 45; i++) {
            RewardDefinition def = layout.get(i);
            ItemStack item = itemFactory.create(def);
            // rarity-tinted glass under? can't dual-stack easily - use name color
            if (i == highlightSlot) {
                ItemMeta meta = item.getItemMeta();
                if (meta != null) {
                    String dn = def.getDisplayName() == null ? def.getId() : def.getDisplayName();
                    meta.setDisplayName(Text.legacy("&a▶ " + dn));
                    item.setItemMeta(meta);
                }
                try {
                    item.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.LUCK_OF_THE_SEA, 1);
                    ItemMeta m = item.getItemMeta();
                    if (m != null) {
                        m.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
                        item.setItemMeta(m);
                    }
                } catch (Throwable ignored) {}
            }
            getInventory().setItem(i, item);
        }
        if (finalFrame) {
            getInventory().setItem(winSlot, itemFactory.create(winner));
        }
    }

    private Sound raritySound(String rarity) {
        String key = rarity == null ? "COMMON" : rarity.toUpperCase(Locale.ROOT);
        String cfg = plugin.getConfig().getString("settings.sounds." + key, null);
        if (cfg != null && !cfg.isBlank()) {
            try {
                return Sound.valueOf(cfg);
            } catch (Exception ignored) {}
        }
        return switch (key) {
            case "MYTHIC", "LEGENDARY" -> Sound.UI_TOAST_CHALLENGE_COMPLETE;
            case "EPIC" -> Sound.ENTITY_PLAYER_LEVELUP;
            case "RARE" -> Sound.ENTITY_EXPERIENCE_ORB_PICKUP;
            default -> Sound.UI_BUTTON_CLICK;
        };
    }

    private static ItemStack glass(Material mat, String name) {
        ItemStack i = new ItemStack(mat);
        ItemMeta m = i.getItemMeta();
        if (m != null) {
            m.setDisplayName(Text.legacy(name));
            i.setItemMeta(m);
        }
        return i;
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
    }
}
