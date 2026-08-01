package com.skritped.novacrates.gui;

import com.skritped.novacrates.util.SchedulerUtil;
import com.skritped.novacrates.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Anvil + chat input. Retries anvil once, then chat. Supports Paper AsyncChatEvent via reflection.
 */
public final class AnvilInputService implements Listener {
    public record Session(String title, String defaultValue, boolean numeric,
                          Consumer<String> onSuccess, Runnable onCancel) {}

    private final JavaPlugin plugin;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> chatMode = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> anvilAttempts = new ConcurrentHashMap<>();

    public AnvilInputService(JavaPlugin plugin) {
        this.plugin = plugin;
        // Paper AsyncChatEvent
        try {
            Class<?> asyncChat = Class.forName("io.papermc.paper.event.player.AsyncChatEvent");
            Bukkit.getPluginManager().registerEvent(
                    (Class) asyncChat,
                    this,
                    EventPriority.LOWEST,
                    (listener, event) -> handlePaperChat(event),
                    plugin,
                    true
            );
        } catch (Throwable ignored) {
        }
    }

    public void open(Player player, String title, String defaultValue, boolean numeric,
                     Consumer<String> onSuccess, Runnable onCancel) {
        sessions.put(player.getUniqueId(), new Session(title, defaultValue, numeric, onSuccess, onCancel));
        chatMode.remove(player.getUniqueId());
        anvilAttempts.put(player.getUniqueId(), 0);
        String mode = plugin.getConfig().getString("settings.input-mode", "chat");
        if ("anvil".equalsIgnoreCase(mode)) {
            SchedulerUtil.run(plugin, () -> tryOpenAnvil(player, title, defaultValue));
        } else {
            SchedulerUtil.run(plugin, () -> startChat(player));
        }
    }


    /** Always try anvil first (then chat fallback on close) — best for editor fields. */
    public void openNumberAnvil(Player player, String title, double current,
                                Consumer<Double> onSuccess, Runnable onCancel) {
        sessions.put(player.getUniqueId(), new Session(title, format(current), true, raw -> {
            try {
                double v = Double.parseDouble(raw.trim().replace(',', '.').replace("%", ""));
                onSuccess.accept(v);
            } catch (NumberFormatException e) {
                player.sendMessage(Text.legacy("&cNieprawidłowa liczba: &f" + raw));
                if (onCancel != null) onCancel.run();
            }
        }, onCancel));
        chatMode.remove(player.getUniqueId());
        anvilAttempts.put(player.getUniqueId(), 0);
        SchedulerUtil.run(plugin, () -> tryOpenAnvil(player, title, format(current)));
    }

    public void openIntegerAnvil(Player player, String title, int current,
                                 Consumer<Integer> onSuccess, Runnable onCancel) {
        openNumberAnvil(player, title, current, d -> onSuccess.accept((int) Math.round(d)), onCancel);
    }

    public void openNumber(Player player, String title, double current,
                           Consumer<Double> onSuccess, Runnable onCancel) {
        open(player, title, format(current), true, raw -> {
            try {
                double v = Double.parseDouble(raw.trim().replace(',', '.').replace("%", ""));
                onSuccess.accept(v);
            } catch (NumberFormatException e) {
                player.sendMessage(Text.legacy("&cNieprawidłowa liczba: &f" + raw));
                if (onCancel != null) onCancel.run();
            }
        }, onCancel);
    }

    public void openInteger(Player player, String title, int current,
                            Consumer<Integer> onSuccess, Runnable onCancel) {
        open(player, title, String.valueOf(current), true, raw -> {
            try {
                int v = Integer.parseInt(raw.trim().replaceAll("[^0-9\\-]", ""));
                onSuccess.accept(v);
            } catch (NumberFormatException e) {
                player.sendMessage(Text.legacy("&cNieprawidłowa liczba: &f" + raw));
                if (onCancel != null) onCancel.run();
            }
        }, onCancel);
    }

    private void tryOpenAnvil(Player player, String title, String defaultValue) {
        int attempts = anvilAttempts.getOrDefault(player.getUniqueId(), 0);
        try {
            InventoryView view = player.openAnvil(player.getLocation(), true);
            if (view == null) {
                if (attempts < 1) {
                    anvilAttempts.put(player.getUniqueId(), attempts + 1);
                    SchedulerUtil.runLater(plugin, () -> tryOpenAnvil(player, title, defaultValue), 3L);
                    return;
                }
                startChat(player);
                return;
            }
            ItemStack left = new ItemStack(Material.PAPER);
            ItemMeta meta = left.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(defaultValue == null || defaultValue.isBlank() ? "0" : defaultValue);
                meta.setLore(java.util.List.of(
                        Text.legacy("&7" + (title == null ? "Wpisz wartość" : title)),
                        Text.legacy("&aKliknij wynik (prawy slot) aby zatwierdzić"),
                        Text.legacy("&8Lub napisz na chat / cancel")
                ));
                left.setItemMeta(meta);
            }
            view.getTopInventory().setItem(0, left);
            player.sendMessage(Text.legacy("&e" + (title == null ? "Wpisz wartość" : title)
                    + " &7→ kowadło: zmień nazwę i kliknij wynik."));
        } catch (Throwable t) {
            if (attempts < 1) {
                anvilAttempts.put(player.getUniqueId(), attempts + 1);
                SchedulerUtil.runLater(plugin, () -> tryOpenAnvil(player, title, defaultValue), 3L);
            } else {
                startChat(player);
            }
        }
    }

    private void startChat(Player player) {
        Session s = sessions.get(player.getUniqueId());
        if (s == null) return;
        chatMode.put(player.getUniqueId(), true);
        player.closeInventory();
        player.sendMessage(Text.legacy("&e&l" + s.title()));
        player.sendMessage(Text.legacy("&7Wpisz wartość na chat. Aktualnie: &f" + s.defaultValue()));
        player.sendMessage(Text.legacy("&8Napisz &fcancel &8aby anulować."));
    }

    private void complete(Player player, String text) {
        Session session = sessions.remove(player.getUniqueId());
        chatMode.remove(player.getUniqueId());
        anvilAttempts.remove(player.getUniqueId());
        if (session == null) return;
        final String value = text == null ? "" : text.trim();
        // Close any open inventory, then apply callback on next tick (main thread)
        SchedulerUtil.run(plugin, () -> {
            try { player.setItemOnCursor(new ItemStack(Material.AIR)); } catch (Throwable ignored) {}
            try {
                if (player.getOpenInventory() != null
                        && player.getOpenInventory().getTopInventory().getType() == InventoryType.ANVIL) {
                    try {
                        var top = player.getOpenInventory().getTopInventory();
                        top.setItem(0, null);
                        top.setItem(1, null);
                        top.setItem(2, null);
                    } catch (Throwable ignored) {}
                    player.closeInventory();
                }
            } catch (Throwable ignored) {}
            SchedulerUtil.runLater(plugin, () -> {
                try { player.setItemOnCursor(new ItemStack(Material.AIR)); } catch (Throwable ignored) {}
                if (session.onSuccess() != null) {
                    session.onSuccess().accept(value);
                }
            }, 1L);
        });
    }

    private void cancel(Player player) {
        Session session = sessions.remove(player.getUniqueId());
        chatMode.remove(player.getUniqueId());
        anvilAttempts.remove(player.getUniqueId());
        if (session != null && session.onCancel() != null) {
            SchedulerUtil.run(plugin, session.onCancel());
        }
        player.sendMessage(Text.legacy("&cAnulowano."));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPrepare(PrepareAnvilEvent event) {
        if (!(event.getView().getPlayer() instanceof Player player)) return;
        if (!sessions.containsKey(player.getUniqueId())) return;
        ItemStack result = event.getResult();
        if (result == null) result = new ItemStack(Material.PAPER);
        ItemMeta meta = result.getItemMeta();
        if (meta != null) {
            String rename = event.getInventory().getRenameText();
            if (rename != null && !rename.isBlank()) meta.setDisplayName(rename);
            meta.setLore(java.util.List.of(Text.legacy("&a✔ Kliknij aby zatwierdzić")));
            result.setItemMeta(meta);
        }
        event.setResult(result);
        try { event.getInventory().setRepairCost(0); } catch (Throwable ignored) {}
        try { event.getInventory().setMaximumRepairCost(0); } catch (Throwable ignored) {}
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!sessions.containsKey(player.getUniqueId())) return;
        if (event.getView().getTopInventory().getType() != InventoryType.ANVIL) return;

        // Block ALL item movement out of our anvil session
        event.setCancelled(true);
        try { event.setResult(org.bukkit.event.Event.Result.DENY); } catch (Throwable ignored) {}

        // Only result slot (2) confirms
        if (event.getRawSlot() != 2) {
            return;
        }

        String text = null;
        AnvilInventory anvil = null;
        if (event.getView().getTopInventory() instanceof AnvilInventory a) {
            anvil = a;
            text = a.getRenameText();
        }
        if ((text == null || text.isBlank()) && event.getCurrentItem() != null
                && event.getCurrentItem().hasItemMeta()
                && event.getCurrentItem().getItemMeta().hasDisplayName()) {
            text = event.getCurrentItem().getItemMeta().getDisplayName();
        }
        // Fallback: left input rename
        if ((text == null || text.isBlank()) && anvil != null && anvil.getItem(0) != null
                && anvil.getItem(0).hasItemMeta() && anvil.getItem(0).getItemMeta().hasDisplayName()) {
            text = anvil.getItem(0).getItemMeta().getDisplayName();
        }

        // Never let paper enter player inventory / cursor
        try { player.setItemOnCursor(new ItemStack(Material.AIR)); } catch (Throwable ignored) {}
        if (anvil != null) {
            try {
                anvil.setItem(0, null);
                anvil.setItem(1, null);
                anvil.setItem(2, null);
            } catch (Throwable ignored) {}
        }

        complete(player, text);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        if (event.getInventory().getType() != InventoryType.ANVIL) return;
        if (!sessions.containsKey(player.getUniqueId())) return;
        SchedulerUtil.runLater(plugin, () -> {
            if (!sessions.containsKey(player.getUniqueId())) return;
            if (Boolean.TRUE.equals(chatMode.get(player.getUniqueId()))) return;
            startChat(player);
        }, 2L);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onLegacyChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (!sessions.containsKey(player.getUniqueId())) return;
        if (!Boolean.TRUE.equals(chatMode.get(player.getUniqueId()))
                && player.getOpenInventory().getTopInventory().getType() == InventoryType.ANVIL) {
            return;
        }
        event.setCancelled(true);
        String msg = event.getMessage().trim();
        SchedulerUtil.run(plugin, () -> {
            if (msg.equalsIgnoreCase("cancel") || msg.equalsIgnoreCase("anuluj")) {
                cancel(player);
            } else {
                complete(player, msg);
            }
        });
    }

    private void handlePaperChat(Object event) {
        try {
            Player player = (Player) event.getClass().getMethod("getPlayer").invoke(event);
            if (!sessions.containsKey(player.getUniqueId())) return;
            if (!Boolean.TRUE.equals(chatMode.get(player.getUniqueId()))) return;
            event.getClass().getMethod("setCancelled", boolean.class).invoke(event, true);
            Object message = event.getClass().getMethod("message").invoke(event);
            String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                    .serialize((net.kyori.adventure.text.Component) message).trim();
            SchedulerUtil.run(plugin, () -> {
                if (plain.equalsIgnoreCase("cancel") || plain.equalsIgnoreCase("anuluj")) {
                    cancel(player);
                } else {
                    complete(player, plain);
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private static String format(double v) {
        if (v == (long) v) return String.valueOf((long) v);
        return String.format(java.util.Locale.US, "%.2f", v);
    }
}
