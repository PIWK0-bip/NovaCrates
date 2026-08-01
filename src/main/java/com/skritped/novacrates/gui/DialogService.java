package com.skritped.novacrates.gui;

import com.skritped.novacrates.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Native Minecraft dialog SLIDERS via Paper Dialog API (1.21.6+ / 1.21.11).
 * Uses DialogAction.customClick(callback) + PlayerCustomClickEvent fallback.
 * Chat fallback when dialogs unavailable.
 */
public final class DialogService implements Listener {
    private final JavaPlugin plugin;
    private final boolean paperDialogs;
    private final ConcurrentHashMap<UUID, Pending> pendingChat = new ConcurrentHashMap<>();
    /** Active dialog submissions waiting for OK click / callback. */
    private final ConcurrentHashMap<UUID, Pending> pendingDialog = new ConcurrentHashMap<>();

    private record Pending(Consumer<String> onSubmit, Runnable onCancel, boolean expectFloat, String defaultValue) {}

    public DialogService(JavaPlugin plugin) {
        this.plugin = plugin;
        boolean paper = false;
        try {
            Class.forName("io.papermc.paper.dialog.Dialog");
            Class.forName("io.papermc.paper.registry.data.dialog.input.DialogInput");
            Class.forName("io.papermc.paper.registry.data.dialog.action.DialogAction");
            paper = true;
        } catch (ClassNotFoundException ignored) {}
        this.paperDialogs = paper;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        if (paper) {
            registerCustomClickListener();
        }
        plugin.getLogger().info(paper
                ? "Input backend: Paper Dialog SLIDERS (callback + click event)"
                : "Input backend: Chat");
    }

    public boolean isDialogsAvailable() {
        return paperDialogs;
    }

    public void askText(Player player, String title, String defaultValue,
                        Consumer<String> onSubmit, Runnable onCancel) {
        if (paperDialogs && showSliderOrText(player, title, defaultValue == null ? "" : defaultValue,
                false, 0, 1, 1, onSubmit, onCancel)) {
            return;
        }
        startChat(player, title, defaultValue, onSubmit, onCancel);
    }

    public void askNumber(Player player, String title, double current,
                          Consumer<Double> onSubmit, Runnable onCancel) {
        askNumberRange(player, title, current, 0.5, 500.0, 0.5, onSubmit, onCancel);
    }

    public void askNumberRange(Player player, String title, double current,
                               double min, double max, double step,
                               Consumer<Double> onSubmit, Runnable onCancel) {
        double stepSafe = step <= 0 ? 1.0 : step;
        double clamped = snap(current, min, max, stepSafe);
        if (paperDialogs && showSliderOrText(player, title, formatNum(clamped),
                true, min, max, stepSafe, s -> {
                    try {
                        onSubmit.accept(snap(Double.parseDouble(s.replace(',', '.')), min, max, stepSafe));
                    } catch (NumberFormatException e) {
                        player.sendMessage(Text.legacy(msg("dialog-invalid-number").replace("%value%", s)));
                        if (onCancel != null) onCancel.run();
                    }
                }, onCancel)) {
            return;
        }
        startChat(player, title + " [" + formatNum(min) + "-" + formatNum(max) + "]", formatNum(clamped), s -> {
            try {
                onSubmit.accept(snap(Double.parseDouble(s.replace(',', '.')), min, max, stepSafe));
            } catch (NumberFormatException e) {
                player.sendMessage(Text.legacy(msg("dialog-invalid-number").replace("%value%", s)));
                if (onCancel != null) onCancel.run();
            }
        }, onCancel);
    }

    public void askInteger(Player player, String title, int current,
                           Consumer<Integer> onSubmit, Runnable onCancel) {
        askNumberRange(player, title, current, 1, 64, 1,
                d -> onSubmit.accept((int) Math.round(d)), onCancel);
    }

    public void askConfirm(Player player, String message, Runnable onYes, Runnable onNo) {
        pendingChat.put(player.getUniqueId(), new Pending(s -> {
            if (s.equalsIgnoreCase("tak") || s.equalsIgnoreCase("yes") || s.equalsIgnoreCase("y")) {
                onYes.run();
            } else if (onNo != null) onNo.run();
        }, onNo, false, null));
        player.sendMessage(Text.legacy("&e" + message + " &7(&atak&7/&cnie&7)"));
    }

    private boolean showSliderOrText(Player player, String title, String defaultValue, boolean slider,
                                     double min, double max, double step,
                                     Consumer<String> onSubmit, Runnable onCancel) {
        try {
            Class<?> dialogCl = Class.forName("io.papermc.paper.dialog.Dialog");
            Class<?> dialogBaseCl = Class.forName("io.papermc.paper.registry.data.dialog.DialogBase");
            Class<?> dialogTypeCl = Class.forName("io.papermc.paper.registry.data.dialog.type.DialogType");
            Class<?> actionButtonCl = Class.forName("io.papermc.paper.registry.data.dialog.ActionButton");
            Class<?> dialogActionCl = Class.forName("io.papermc.paper.registry.data.dialog.action.DialogAction");
            Class<?> dialogInputCl = Class.forName("io.papermc.paper.registry.data.dialog.input.DialogInput");
            Class<?> componentCl = Class.forName("net.kyori.adventure.text.Component");
            Class<?> keyCl = Class.forName("net.kyori.adventure.key.Key");

            Object titleComp = componentCl.getMethod("text", String.class).invoke(null, title);
            Object okLabel = componentCl.getMethod("text", String.class).invoke(null, msg("dialog-ok"));
            Object cancelLabel = componentCl.getMethod("text", String.class).invoke(null, msg("dialog-cancel"));
            Object inputLabel = componentCl.getMethod("text", String.class).invoke(null, title);

            Object input = slider
                    ? buildNumberRange(dialogInputCl, componentCl, inputLabel, min, max, step, defaultValue)
                    : buildTextInput(dialogInputCl, componentCl, inputLabel, defaultValue);
            if (input == null) {
                plugin.getLogger().warning("Failed to build dialog input");
                return false;
            }

            // Register pending BEFORE showing — both callback and click-event use this
            UUID pid = player.getUniqueId();
            pendingDialog.put(pid, new Pending(onSubmit, onCancel, slider, defaultValue));

            // Unique key for PlayerCustomClickEvent fallback
            Object okKey = keyCl.getMethod("key", String.class, String.class)
                    .invoke(null, "novacrates", "dialog_ok_" + pid.toString().replace("-", ""));

            // Prefer KEY-based customClick → PlayerCustomClickEvent (carries DialogResponseView).
            // Callback proxy is unreliable across Paper builds.
            Object okAction = null;
            try {
                Class<?> binaryTag = Class.forName("net.kyori.adventure.nbt.api.BinaryTagHolder");
                okAction = dialogActionCl.getMethod("customClick", keyCl, binaryTag)
                        .invoke(null, okKey, null);
            } catch (Throwable ex) {
                for (Method m : dialogActionCl.getMethods()) {
                    if (!m.getName().equals("customClick")) continue;
                    Class<?>[] pt = m.getParameterTypes();
                    try {
                        if (pt.length >= 1 && pt[0].getName().contains("Key")) {
                            okAction = pt.length == 1 ? m.invoke(null, okKey) : m.invoke(null, okKey, null);
                            break;
                        }
                    } catch (Throwable ignored) {}
                }
            }
            if (okAction == null) {
                okAction = buildCallbackAction(dialogActionCl, player, slider);
            }
            if (okAction == null) {
                pendingDialog.remove(pid);
                plugin.getLogger().warning("Could not create DialogAction for OK button");
                return false;
            }

            Object okBtn = buildActionButton(actionButtonCl, componentCl, dialogActionCl, okLabel, okAction);

            // Cancel key so we can reopen the editor GUI (null action only closes dialog)
            Object cancelKey = keyCl.getMethod("key", String.class, String.class)
                    .invoke(null, "novacrates", "dialog_cancel_" + pid.toString().replace("-", ""));
            Object cancelAction = null;
            try {
                Class<?> binaryTag = Class.forName("net.kyori.adventure.nbt.api.BinaryTagHolder");
                cancelAction = dialogActionCl.getMethod("customClick", keyCl, binaryTag)
                        .invoke(null, cancelKey, null);
            } catch (Throwable t) {
                for (Method m : dialogActionCl.getMethods()) {
                    if (!m.getName().equals("customClick")) continue;
                    Class<?>[] pt = m.getParameterTypes();
                    try {
                        if (pt.length >= 1 && pt[0].getName().contains("Key")) {
                            cancelAction = pt.length == 1 ? m.invoke(null, cancelKey) : m.invoke(null, cancelKey, null);
                            break;
                        }
                    } catch (Throwable ignored) {}
                }
            }
            Object cancelBtn = buildActionButton(actionButtonCl, componentCl, dialogActionCl, cancelLabel, cancelAction);

            Object baseBuilder = dialogBaseCl.getMethod("builder", componentCl).invoke(null, titleComp);
            try {
                baseBuilder.getClass().getMethod("inputs", List.class).invoke(baseBuilder, List.of(input));
            } catch (NoSuchMethodException e) {
                baseBuilder.getClass().getMethod("inputs", java.util.Collection.class)
                        .invoke(baseBuilder, List.of(input));
            }
            // Keep dialog closeable with ESC → treat as cancel
            try {
                baseBuilder.getClass().getMethod("canCloseWithEscape", boolean.class).invoke(baseBuilder, true);
            } catch (Throwable ignored) {}
            Object base = baseBuilder.getClass().getMethod("build").invoke(baseBuilder);

            Object type = dialogTypeCl.getMethod("confirmation", actionButtonCl, actionButtonCl)
                    .invoke(null, okBtn, cancelBtn);

            Object dialog = dialogCl.getMethod("create", java.util.function.Consumer.class)
                    .invoke(null, (java.util.function.Consumer<Object>) b -> {
                        try {
                            Object built = b;
                            try {
                                built = b.getClass().getMethod("empty").invoke(b);
                            } catch (NoSuchMethodException ignored) {}
                            Object afterBase = built.getClass().getMethod("base", dialogBaseCl).invoke(built, base);
                            if (afterBase == null) afterBase = built;
                            afterBase.getClass().getMethod("type", dialogTypeCl).invoke(afterBase, type);
                        } catch (Throwable ex) {
                            throw new RuntimeException(ex);
                        }
                    });

            // Close inventory so dialog is not fighting the open editor GUI
            try {
                player.closeInventory();
            } catch (Throwable ignored) {}

            if (!showToPlayer(player, dialog)) {
                pendingDialog.remove(pid);
                throw new IllegalStateException("showDialog failed");
            }
            return true;
        } catch (Throwable t) {
            plugin.getLogger().warning("Dialog show failed: " + t.getMessage());
            if (plugin.getConfig().getBoolean("settings.debug", false)) {
                t.printStackTrace();
            }
            pendingDialog.remove(player.getUniqueId());
            return false;
        }
    }

    /**
     * Handle dialog OK: read value from view and invoke pending onSubmit.
     * Safe to call from callback or PlayerCustomClickEvent.
     */
    private void completeDialog(Player player, Object view) {
        Pending p = pendingDialog.remove(player.getUniqueId());
        if (p == null) return;
        String value = readViewValue(view, p.expectFloat);
        if ((value == null || value.isBlank()) && p.defaultValue() != null && !p.defaultValue().isBlank()) {
            // OK clicked but view unreadable — keep previous value so GUI still reopens/saves
            value = p.defaultValue();
        }
        plugin.getLogger().info("[Dialog] OK from " + player.getName()
                + " value=" + value
                + " view=" + (view == null ? "null" : view.getClass().getSimpleName()));
        final String finalValue = value;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (finalValue == null || finalValue.isBlank()) {
                if (p.onCancel != null) p.onCancel.run();
            } else {
                p.onSubmit.accept(finalValue.trim());
            }
        });
    }

    private void cancelDialog(UUID playerId) {
        Pending p = pendingDialog.remove(playerId);
        if (p != null && p.onCancel != null) {
            Bukkit.getScheduler().runTask(plugin, p.onCancel);
        }
    }

    /** Prefer DialogAction.customClick(DialogActionCallback, Options). */
    private Object buildCallbackAction(Class<?> dialogActionCl, Player player, boolean expectFloat) {
        try {
            Class<?> callbackCl = null;
            for (Class<?> nested : dialogActionCl.getClasses()) {
                if (nested.getSimpleName().contains("Callback") || nested.isInterface()) {
                    if (nested.getSimpleName().toLowerCase().contains("callback")) {
                        callbackCl = nested;
                        break;
                    }
                }
            }
            if (callbackCl == null) {
                try {
                    callbackCl = Class.forName(
                            "io.papermc.paper.registry.data.dialog.action.DialogAction$DialogActionCallback");
                } catch (ClassNotFoundException e) {
                    callbackCl = Class.forName(
                            "io.papermc.paper.registry.data.dialog.action.DialogActionCallback");
                }
            }

            Class<?> optionsCl = Class.forName("net.kyori.adventure.text.event.ClickCallback$Options");
            Object optionsBuilder = optionsCl.getMethod("builder").invoke(null);
            // allow multiple uses in case of retries; 1 is enough for confirmation
            try {
                optionsBuilder = optionsBuilder.getClass().getMethod("uses", int.class)
                        .invoke(optionsBuilder, 1);
            } catch (Throwable ignored) {}
            Object options = optionsBuilder.getClass().getMethod("build").invoke(optionsBuilder);

            final UUID pid = player.getUniqueId();
            Object callback = Proxy.newProxyInstance(
                    callbackCl.getClassLoader(),
                    new Class<?>[]{callbackCl},
                    (proxy, method, args) -> {
                        String name = method.getName();
                        // Object methods — required for Proxy correctness
                        if (name.equals("equals")) {
                            return proxy == (args != null && args.length > 0 ? args[0] : null);
                        }
                        if (name.equals("hashCode")) {
                            return System.identityHashCode(proxy);
                        }
                        if (name.equals("toString")) {
                            return "NovaCratesDialogCallback#" + pid.toString().substring(0, 8);
                        }
                        // Functional method: accept(DialogResponseView, Audience) or similar
                        if (name.equals("accept") || name.equals("apply") || name.equals("run")
                                || (method.getReturnType() == void.class && method.getParameterCount() >= 1)) {
                            try {
                                Object view = args != null && args.length > 0 ? args[0] : null;
                                // Prefer Player from Audience if present
                                Player pl = Bukkit.getPlayer(pid);
                                if (pl == null && args != null) {
                                    for (Object a : args) {
                                        if (a instanceof Player p2) {
                                            pl = p2;
                                            break;
                                        }
                                    }
                                }
                                if (pl != null) {
                                    completeDialog(pl, view);
                                } else {
                                    cancelDialog(pid);
                                }
                            } catch (Throwable t) {
                                plugin.getLogger().warning("Dialog callback error: " + t.getMessage());
                                if (plugin.getConfig().getBoolean("settings.debug", false)) {
                                    t.printStackTrace();
                                }
                                cancelDialog(pid);
                            }
                            return null;
                        }
                        return null;
                    }
            );

            try {
                return dialogActionCl.getMethod("customClick", callbackCl, optionsCl)
                        .invoke(null, callback, options);
            } catch (NoSuchMethodException e) {
                for (Method m : dialogActionCl.getMethods()) {
                    if (!m.getName().equals("customClick") || m.getParameterCount() != 2) continue;
                    try {
                        return m.invoke(null, callback, options);
                    } catch (Throwable ignored) {}
                }
                // try single-arg callback
                for (Method m : dialogActionCl.getMethods()) {
                    if (!m.getName().equals("customClick") || m.getParameterCount() != 1) continue;
                    try {
                        return m.invoke(null, callback);
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("DialogAction callback setup failed: " + t.getMessage());
            if (plugin.getConfig().getBoolean("settings.debug", false)) t.printStackTrace();
        }
        return null;
    }

    /**
     * Register Paper PlayerCustomClickEvent so key-based DialogAction.customClick(Key) works.
     */
    private void registerCustomClickListener() {
        String[] classes = {
                "io.papermc.paper.event.player.PlayerCustomClickEvent",
                "io.papermc.paper.event.player.AsyncPlayerCustomClickEvent",
                "org.bukkit.event.player.PlayerCustomClickEvent"
        };
        boolean any = false;
        for (String cn : classes) {
            try {
                Class<?> eventCl = Class.forName(cn);
                Bukkit.getPluginManager().registerEvent(
                        (Class<? extends org.bukkit.event.Event>) eventCl,
                        new org.bukkit.event.Listener() {},
                        org.bukkit.event.EventPriority.NORMAL,
                        (listener, event) -> {
                            if (eventCl.isInstance(event)) {
                                handleCustomClick(event);
                            }
                        },
                        plugin,
                        false
                );
                plugin.getLogger().info("[Dialog] Listening: " + cn);
                any = true;
            } catch (Throwable t) {
                // try next
            }
        }
        if (!any) {
            plugin.getLogger().warning("[Dialog] No PlayerCustomClickEvent found — dialog OK may not apply values");
        }
    }

    private void handleCustomClick(Object event) {
        try {
            Player player = null;
            try {
                Object p = event.getClass().getMethod("getPlayer").invoke(event);
                if (p instanceof Player pl) player = pl;
            } catch (Throwable ignored) {}
            if (player == null) {
                for (Method m : event.getClass().getMethods()) {
                    if (m.getParameterCount() == 0 && Player.class.isAssignableFrom(m.getReturnType())) {
                        try {
                            Object p = m.invoke(event);
                            if (p instanceof Player pl) { player = pl; break; }
                        } catch (Throwable ignored) {}
                    }
                }
            }
            if (player == null) {
                plugin.getLogger().info("[Dialog] customClick without player");
                return;
            }

            if (!pendingDialog.containsKey(player.getUniqueId())) {
                return;
            }

            String keyStr = "";
            try {
                Object id = event.getClass().getMethod("getIdentifier").invoke(event);
                if (id != null) keyStr = id.toString();
            } catch (Throwable ignored) {
                try {
                    Object id = event.getClass().getMethod("identifier").invoke(event);
                    if (id != null) keyStr = id.toString();
                } catch (Throwable ignored2) {}
            }

            plugin.getLogger().info("[Dialog] customClick key=" + keyStr + " player=" + player.getName());

            if (keyStr.toLowerCase().contains("cancel")) {
                cancelDialog(player.getUniqueId());
                return;
            }

            Object view = null;
            for (String mn : new String[]{"getDialogResponseView", "dialogResponseView", "getView", "view"}) {
                try {
                    Object v = event.getClass().getMethod(mn).invoke(event);
                    if (v instanceof java.util.Optional<?> opt) view = opt.orElse(null);
                    else view = v;
                    if (view != null) break;
                } catch (Throwable ignored) {}
            }
            completeDialog(player, view);
        } catch (Throwable t) {
            plugin.getLogger().warning("[Dialog] handleCustomClick error: " + t.getMessage());
            t.printStackTrace();
        }
    }

    private String readViewValue(Object view, boolean expectFloat) {
        if (view == null) return null;

        String[] keys = new String[]{"value", "input", "number", "amount", "chance", "weight"};

        if (expectFloat) {
            for (String key : keys) {
                String f = invokeStringValue(view, "getFloat", key);
                if (f != null) return f;
            }
        }
        for (String key : keys) {
            String tx = invokeStringValue(view, "getText", key);
            if (tx != null) return tx;
        }
        for (String key : keys) {
            String f2 = invokeStringValue(view, "getFloat", key);
            if (f2 != null) return f2;
        }

        for (String methodName : new String[]{"get", "getValue", "getString", "getNumber"}) {
            for (String key : keys) {
                try {
                    Object v = view.getClass().getMethod(methodName, String.class).invoke(view, key);
                    String s = stringify(v);
                    if (s != null && !s.isBlank()) return s;
                } catch (Throwable ignored) {}
            }
        }

        // Last resort: scan payload / toString for a number
        try {
            Object payload = view.getClass().getMethod("payload").invoke(view);
            if (payload != null) {
                String raw = String.valueOf(payload);
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("([0-9]+(?:[.,][0-9]+)?)").matcher(raw);
                if (m.find()) return m.group(1).replace(',', '.');
            }
        } catch (Throwable ignored) {}

        // Always log methods once when unreadable (helps fix Paper API drift)
        plugin.getLogger().info("[Dialog] Could not read value from view="
                + view.getClass().getName() + " expectFloat=" + expectFloat);
        for (Method m : view.getClass().getMethods()) {
            if (m.getName().startsWith("get") && m.getParameterCount() <= 1) {
                plugin.getLogger().info("  [Dialog] method " + m.getName()
                        + " " + java.util.Arrays.toString(m.getParameterTypes()));
            }
        }
        return null;
    }

    private static String invokeStringValue(Object view, String method, String key) {
        try {
            Object v = view.getClass().getMethod(method, String.class).invoke(view, key);
            return stringify(v);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String stringify(Object v) {
        if (v == null) return null;
        if (v instanceof java.util.Optional<?> opt) {
            return opt.map(o -> {
                if (o instanceof Number n) {
                    double d = n.doubleValue();
                    if (Math.abs(d - Math.rint(d)) < 1e-6) return String.valueOf((long) Math.rint(d));
                    return String.valueOf(d);
                }
                return String.valueOf(o);
            }).orElse(null);
        }
        if (v instanceof Number n) {
            double d = n.doubleValue();
            if (Math.abs(d - Math.rint(d)) < 1e-6) return String.valueOf((long) Math.rint(d));
            return String.valueOf(d);
        }
        String s = String.valueOf(v);
        return s.isBlank() || s.equals("null") ? null : s;
    }

    private Object buildNumberRange(Class<?> dialogInputCl, Class<?> componentCl,
                                    Object label, double min, double max, double step,
                                    String defaultValue) throws Exception {
        float fMin = (float) min;
        float fMax = (float) max;
        float fStep = (float) step;
        float fInit;
        try {
            fInit = (float) snap(Double.parseDouble(defaultValue.replace(',', '.')), min, max, step);
        } catch (Exception e) {
            fInit = fMin;
        }

        Object builder = null;
        try {
            builder = dialogInputCl.getMethod("numberRange", String.class, componentCl, float.class, float.class)
                    .invoke(null, "value", label, fMin, fMax);
        } catch (NoSuchMethodException e) {
            for (Method m : dialogInputCl.getMethods()) {
                if (!m.getName().equals("numberRange")) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 4 && p[0] == String.class) {
                    try {
                        builder = m.invoke(null, "value", label, fMin, fMax);
                        break;
                    } catch (Throwable ignored) {
                        try {
                            builder = m.invoke(null, "value", fMin, fMax, label);
                            break;
                        } catch (Throwable ignored2) {}
                    }
                }
            }
        }
        if (builder == null) return null;

        builder = applyBuilder(builder, "step", fStep);
        builder = applyBuilder(builder, "initial", fInit);
        builder = applyBuilder(builder, "width", 300);
        try {
            Method build = builder.getClass().getMethod("build");
            return build.invoke(builder);
        } catch (NoSuchMethodException e) {
            return builder;
        }
    }

    private Object buildTextInput(Class<?> dialogInputCl, Class<?> componentCl,
                                  Object label, String def) throws Exception {
        Object builder = dialogInputCl.getMethod("text", String.class, componentCl)
                .invoke(null, "value", label);
        builder = applyBuilder(builder, "initial", def == null ? "" : def);
        builder = applyBuilder(builder, "width", 300);
        try {
            return builder.getClass().getMethod("build").invoke(builder);
        } catch (NoSuchMethodException e) {
            return builder;
        }
    }

    private Object buildActionButton(Class<?> actionButtonCl, Class<?> componentCl,
                                     Class<?> dialogActionCl, Object label, Object action) throws Exception {
        // ActionButton.create(Component label, Component tooltip, int width, DialogAction action)
        // or builder pattern
        try {
            return actionButtonCl.getMethod("create", componentCl, componentCl, int.class, dialogActionCl)
                    .invoke(null, label, null, 100, action);
        } catch (NoSuchMethodException ignored) {}
        try {
            return actionButtonCl.getMethod("create", componentCl, dialogActionCl)
                    .invoke(null, label, action);
        } catch (NoSuchMethodException ignored) {}
        // builder
        Object b = actionButtonCl.getMethod("builder", componentCl).invoke(null, label);
        if (action != null) {
            try {
                Object next = b.getClass().getMethod("action", dialogActionCl).invoke(b, action);
                if (next != null) b = next;
            } catch (Throwable ignored) {}
        }
        try {
            return b.getClass().getMethod("build").invoke(b);
        } catch (NoSuchMethodException e) {
            return b;
        }
    }

    private static Object applyBuilder(Object builder, String method, Object arg) {
        Class<?>[] types = arg instanceof Float ? new Class<?>[]{float.class, Float.class, double.class, Double.class}
                : arg instanceof Integer ? new Class<?>[]{int.class, Integer.class}
                : arg instanceof String ? new Class<?>[]{String.class}
                : new Class<?>[]{arg.getClass()};
        for (Class<?> type : types) {
            try {
                Object next = builder.getClass().getMethod(method, type).invoke(builder, arg);
                return next != null ? next : builder;
            } catch (Throwable ignored) {}
        }
        for (Method m : builder.getClass().getMethods()) {
            if (!m.getName().equals(method) || m.getParameterCount() != 1) continue;
            try {
                Object converted = arg;
                Class<?> p = m.getParameterTypes()[0];
                if (p == float.class || p == Float.class) converted = ((Number) arg).floatValue();
                else if (p == double.class || p == Double.class) converted = ((Number) arg).doubleValue();
                else if (p == int.class || p == Integer.class) converted = ((Number) arg).intValue();
                Object next = m.invoke(builder, converted);
                return next != null ? next : builder;
            } catch (Throwable ignored) {}
        }
        return builder;
    }

    private boolean showToPlayer(Player player, Object dialog) {
        for (Method m : player.getClass().getMethods()) {
            if (!m.getName().equals("showDialog") || m.getParameterCount() != 1) continue;
            try {
                m.invoke(player, dialog);
                return true;
            } catch (Throwable ignored) {}
        }
        try {
            Class<?> audienceCl = Class.forName("net.kyori.adventure.audience.Audience");
            for (Method m : audienceCl.getMethods()) {
                if (!m.getName().equals("showDialog") || m.getParameterCount() != 1) continue;
                m.invoke(player, dialog);
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private String msg(String key) {
        try {
            return ((com.skritped.novacrates.NovaCratesPlugin) plugin).getCrateManager()
                    .getMessages().raw(key);
        } catch (Throwable e) {
            return key;
        }
    }

    private static double snap(double v, double min, double max, double step) {
        double snapped = Math.round(v / step) * step;
        snapped = Math.round(snapped * 1000.0) / 1000.0;
        if (snapped < min) snapped = min;
        if (snapped > max) snapped = max;
        return snapped;
    }

    private static String formatNum(double v) {
        if (Math.abs(v - Math.rint(v)) < 1e-6) return String.valueOf((long) Math.rint(v));
        return String.format(java.util.Locale.US, "%.1f", v);
    }

    private void startChat(Player player, String title, String def,
                           Consumer<String> onSubmit, Runnable onCancel) {
        pendingChat.put(player.getUniqueId(), new Pending(onSubmit, onCancel, false, def));
        player.sendMessage(Text.legacy("&8&m────────────"));
        player.sendMessage(Text.legacy("&e" + title));
        if (def != null && !def.isEmpty()) player.sendMessage(Text.legacy("&7Aktualnie: &f" + def));
        player.sendMessage(Text.legacy("&7Wpisz na czacie &8| &ccancel"));
        player.sendMessage(Text.legacy("&8&m────────────"));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent event) {
        Pending p = pendingChat.remove(event.getPlayer().getUniqueId());
        if (p == null) return;
        event.setCancelled(true);
        String msg = event.getMessage().trim();
        if (msg.equalsIgnoreCase("cancel") || msg.equalsIgnoreCase("anuluj")) {
            if (p.onCancel != null) Bukkit.getScheduler().runTask(plugin, p.onCancel);
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> p.onSubmit.accept(msg));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        pendingChat.remove(event.getPlayer().getUniqueId());
        pendingDialog.remove(event.getPlayer().getUniqueId());
    }
}
