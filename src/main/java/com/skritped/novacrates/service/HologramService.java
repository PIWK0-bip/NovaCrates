package com.skritped.novacrates.service;

import com.skritped.novacrates.model.CrateDefinition;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * ArmorStand holograms with optional PlaceholderAPI line refresh.
 */
public class HologramService {
    private final JavaPlugin plugin;
    private final NamespacedKey holoKey;
    private final Map<String, List<ArmorStand>> holograms = new ConcurrentHashMap<>();
    private final Map<String, String> blockCrates = new ConcurrentHashMap<>();
    private Function<String, CrateDefinition> crateLookup = id -> null;
    private BukkitTask particleTask;
    private BukkitTask refreshTask;
    private boolean particlesEnabled = true;
    private double particleRange = 24.0;
    private int refreshIntervalTicks = 40;
    private int syncCounter = 0;
    private boolean useDecentHolograms;
    private boolean useFancyHolograms;
    private final Map<String, Object> externalHolograms = new ConcurrentHashMap<>();
    private boolean papiRefreshEnabled = true;
    /** crateId, lines — persist when Fancy/Decent hologram text is edited in-world */
    private BiConsumer<String, java.util.List<String>> onHologramLinesChanged;

    public HologramService(JavaPlugin plugin) {
        this.plugin = plugin;
        this.holoKey = new NamespacedKey(plugin, "novacrates_holo");
        detectHologramBackend();
    }

    public void setParticlesEnabled(boolean enabled) {
        this.particlesEnabled = enabled;
    }

    public void setParticleRange(double range) {
        this.particleRange = Math.max(8.0, range);
    }

    public void setPapiRefreshEnabled(boolean enabled) {
        this.papiRefreshEnabled = enabled;
    }

    public void setOnHologramLinesChanged(BiConsumer<String, java.util.List<String>> callback) {
        this.onHologramLinesChanged = callback;
    }

    public void setRefreshIntervalTicks(int ticks) {
        this.refreshIntervalTicks = Math.max(20, ticks);
        detectHologramBackend();
    }

    private void detectHologramBackend() {
        String prefer = plugin.getConfig().getString("settings.hologram-backend", "auto");
        // auto: FancyHolograms > DecentHolograms > ArmorStand
        boolean fancyAvail = Bukkit.getPluginManager().getPlugin("FancyHolograms") != null;
        boolean decentAvail = Bukkit.getPluginManager().getPlugin("DecentHolograms") != null;
        useFancyHolograms = false;
        useDecentHolograms = false;
        if ("fancy".equalsIgnoreCase(prefer) || "fancyholograms".equalsIgnoreCase(prefer)) {
            useFancyHolograms = fancyAvail;
        } else if ("decent".equalsIgnoreCase(prefer) || "decentholograms".equalsIgnoreCase(prefer)) {
            useDecentHolograms = decentAvail;
        } else if ("armorstand".equalsIgnoreCase(prefer) || "none".equalsIgnoreCase(prefer)) {
            // force armor stands
        } else {
            // auto
            if (fancyAvail && plugin.getConfig().getBoolean("settings.fancy-holograms", true)) {
                useFancyHolograms = true;
            } else if (decentAvail && plugin.getConfig().getBoolean("settings.decent-holograms", true)) {
                useDecentHolograms = true;
            }
        }
        if (useFancyHolograms) {
            plugin.getLogger().info("Hologram backend: FancyHolograms");
        } else if (useDecentHolograms) {
            plugin.getLogger().info("Hologram backend: DecentHolograms");
        } else {
            plugin.getLogger().info("Hologram backend: ArmorStand");
        }
    }

    public void startParticles(Map<String, String> blocks) {
        stopParticles();
        blockCrates.clear();
        blockCrates.putAll(blocks);
        particleTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!particlesEnabled || blockCrates.isEmpty()) {
                return;
            }
            double rangeSq = particleRange * particleRange;
            for (String key : blockCrates.keySet()) {
                Location loc = parseLocation(key);
                if (loc == null || loc.getWorld() == null) {
                    continue;
                }
                if (!isChunkLoaded(loc)) {
                    continue;
                }
                if (!hasPlayerNearby(loc, rangeSq)) {
                    continue;
                }
                Location p = loc.clone().add(0.5, 1.2, 0.5);
                try {
                    p.getWorld().spawnParticle(Particle.END_ROD, p, 2, 0.25, 0.15, 0.25, 0.01);
                } catch (Throwable ignored) {
                }
                try {
                    p.getWorld().spawnParticle(Particle.DUST, p, 3, 0.3, 0.2, 0.3, 0,
                            new Particle.DustOptions(Color.AQUA, 1.0f));
                } catch (Throwable ignored) {
                    try {
                        p.getWorld().spawnParticle(Particle.valueOf("REDSTONE"), p, 3, 0.3, 0.2, 0.3, 0,
                                new Particle.DustOptions(Color.AQUA, 1.0f));
                    } catch (Throwable ignored2) {
                    }
                }
            }
        }, 30L, 30L);
        startPapiRefresh();
    }

    /**
     * Periodically re-apply hologram lines with PlaceholderAPI (nearest player context).
     */
    public void startPapiRefresh() {
        stopPapiRefresh();
        if (!papiRefreshEnabled) {
            return;
        }
        refreshTask = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshAllHologramTexts,
                refreshIntervalTicks, refreshIntervalTicks);
    }

    public void stopPapiRefresh() {
        if (refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }
    }

    private void refreshAllHologramTexts() {
        // Fancy/Decent sync is relatively expensive — every 4th refresh only
        if ((++syncCounter % 4) == 0) {
            syncExternalHologramEdits();
        }
        if (holograms.isEmpty()) {
            return;
        }
        for (Map.Entry<String, List<ArmorStand>> e : holograms.entrySet()) {
            String locationKey = e.getKey();
            String crateId = blockCrates.get(locationKey);
            CrateDefinition crate = crateId == null ? null : crateLookup.apply(crateId);
            if (crate == null || !crate.isHologramEnabled()) {
                continue;
            }
            Location base = parseLocation(locationKey);
            if (base == null || base.getWorld() == null || !isChunkLoaded(base)) {
                continue;
            }
            // Skip refresh when no players are in range (performance)
            if (nearestPlayer(base, particleRange) == null) {
                continue;
            }
            Player nearest = null;
            // global-only: avoid leaking nearest player placeholders to everyone
            if (!plugin.getConfig().getBoolean("settings.hologram-papi-global-only", true)) {
                nearest = nearestPlayer(base, particleRange);
            }
            List<String> lines = resolveHologramLines(crate);
            List<ArmorStand> stands = e.getValue();
            for (int i = 0; i < stands.size() && i < lines.size(); i++) {
                ArmorStand stand = stands.get(i);
                if (stand == null || stand.isDead()) {
                    continue;
                }
                String raw = lines.get(i);
                String parsed = applyPlaceholders(nearest, raw);
                String colored = ChatColor.translateAlternateColorCodes('&', parsed);
                if (!colored.equals(stand.getCustomName())) {
                    stand.setCustomName(colored);
                }
            }
        }
    }

    private Player nearestPlayer(Location loc, double range) {
        World w = loc.getWorld();
        if (w == null) {
            return null;
        }
        double best = range * range;
        Player found = null;
        for (Player p : w.getPlayers()) {
            double d = p.getLocation().distanceSquared(loc);
            if (d <= best) {
                best = d;
                found = p;
            }
        }
        return found;
    }

    /**
     * Apply PlaceholderAPI if present; otherwise return input.
     * Uses reflection to avoid hard dependency.
     */
    public static String applyPlaceholders(Player player, String input) {
        if (input == null) {
            return "";
        }
        if (player == null || Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
            return input;
        }
        try {
            Class<?> papi = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
            Object result = papi.getMethod("setPlaceholders", org.bukkit.OfflinePlayer.class, String.class)
                    .invoke(null, player, input);
            return result == null ? input : result.toString();
        } catch (Throwable t) {
            return input;
        }
    }

    private boolean isChunkLoaded(Location loc) {
        World w = loc.getWorld();
        return w != null && w.isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4);
    }

    private boolean hasPlayerNearby(Location loc, double rangeSq) {
        World w = loc.getWorld();
        if (w == null) {
            return false;
        }
        for (Player p : w.getPlayers()) {
            if (p.getLocation().distanceSquared(loc) <= rangeSq) {
                return true;
            }
        }
        return false;
    }

    public void stopParticles() {
        if (particleTask != null) {
            particleTask.cancel();
            particleTask = null;
        }
        stopPapiRefresh();
    }

    public void cleanupOrphans(Map<String, String> blocks) {
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntitiesByClass(ArmorStand.class)) {
                if (!(entity instanceof ArmorStand stand)) {
                    continue;
                }
                if (!stand.getPersistentDataContainer().has(holoKey, PersistentDataType.STRING)) {
                    continue;
                }
                String tag = stand.getPersistentDataContainer().get(holoKey, PersistentDataType.STRING);
                if (tag == null || !blocks.containsKey(tag)) {
                    stand.remove();
                }
            }
        }
    }

    public void spawnHologram(String locationKey, CrateDefinition crate) {
        // When respecting external (/fholo) edits: do NOT delete Fancy/Decent first.
        // Only clear our ArmorStand backend; Fancy is left intact for spawnFancy to keep.
        boolean respect = plugin.getConfig().getBoolean("settings.hologram-respect-external", true);
        if (respect && (useFancyHolograms || useDecentHolograms)) {
            // Remove only ArmorStand holograms for this location
            List<ArmorStand> stands = holograms.remove(locationKey);
            if (stands != null) {
                stands.forEach(Entity::remove);
            }
            // Keep externalHolograms entry if present — spawnFancy will re-attach
        } else {
            removeHologram(locationKey);
        }
        if (crate == null || !crate.isHologramEnabled()) {
            return;
        }
        Location base = parseLocation(locationKey);
        if (base == null || base.getWorld() == null) {
            return;
        }
        List<String> lines = resolveHologramLines(crate);

        if (useFancyHolograms && spawnFancyHologram(locationKey, base, lines)) {
            if (crate.getId() != null) {
                blockCrates.put(locationKey, crate.getId());
            }
            return;
        }
        if (useDecentHolograms && spawnDecentHologram(locationKey, base, lines)) {
            if (crate.getId() != null) {
                blockCrates.put(locationKey, crate.getId());
            }
            return;
        }

        Player nearest = null;
        if (!plugin.getConfig().getBoolean("settings.hologram-papi-global-only", true)) {
            nearest = nearestPlayer(base, particleRange);
        }
        List<ArmorStand> stands = new ArrayList<>();
        double y = 1.6 + (lines.size() - 1) * 0.25;
        for (String line : lines) {
            Location standLoc = base.clone().add(0.5, y, 0.5);
            ArmorStand stand = (ArmorStand) base.getWorld().spawnEntity(standLoc, EntityType.ARMOR_STAND);
            stand.setVisible(false);
            stand.setGravity(false);
            stand.setCustomNameVisible(true);
            String parsed = applyPlaceholders(nearest, line);
            stand.setCustomName(ChatColor.translateAlternateColorCodes('&', parsed));
            stand.setMarker(true);
            stand.setSmall(true);
            stand.setInvulnerable(true);
            stand.setCollidable(false);
            stand.getPersistentDataContainer().set(holoKey, PersistentDataType.STRING, locationKey);
            stands.add(stand);
            y -= 0.25;
        }
        holograms.put(locationKey, stands);
        if (crate.getId() != null) {
            blockCrates.put(locationKey, crate.getId());
        }
    }


    private boolean spawnDecentHologram(String locationKey, Location base, List<String> lines) {
        try {
            Class<?> api = Class.forName("eu.decentsoftware.holograms.api.DHAPI");
            String id = hologramId(locationKey);
            // Remove existing
            try {
                api.getMethod("removeHologram", String.class).invoke(null, id);
            } catch (Throwable ignored) {}
            Location loc = base.clone().add(0.5, 1.8, 0.5);
            List<String> colored = new ArrayList<>();
            for (String line : lines) {
                colored.add(ChatColor.translateAlternateColorCodes('&', line));
            }
            Object holo = api.getMethod("createHologram", String.class, Location.class, java.util.Collection.class)
                    .invoke(null, id, loc, colored);
            if (holo == null) {
                // alternate signature: createHologram(String, Location, List)
                holo = api.getMethod("createHologram", String.class, Location.class, List.class)
                        .invoke(null, id, loc, colored);
            }
            externalHolograms.put(locationKey, holo != null ? holo : id);
            return true;
        } catch (Throwable t) {
            if (plugin.getConfig().getBoolean("settings.debug", false)) {
                plugin.getLogger().warning("DecentHolograms spawn failed: " + t.getMessage());
            }
            return false;
        }
    }


    private boolean spawnFancyHologram(String locationKey, Location base, List<String> lines) {
        try {
            String id = hologramId(locationKey);
            Location loc = base.clone().add(0.5, 2.0, 0.5);
            List<String> colored = new ArrayList<>();
            for (String line : lines) {
                colored.add(ChatColor.translateAlternateColorCodes('&', line == null ? "" : line));
            }

            Class<?> pluginCl = Class.forName("de.oliver.fancyholograms.api.FancyHologramsPlugin");
            Object fhPlugin = pluginCl.getMethod("get").invoke(null);
            Object manager = fhPlugin.getClass().getMethod("getHologramManager").invoke(fhPlugin);

            // If hologram already exists (e.g. edited via /fholo) — do NOT wipe it
            boolean respectExternal = plugin.getConfig().getBoolean("settings.hologram-respect-external", true);
            Object existing = null;
            try {
                Object opt = manager.getClass().getMethod("getHologram", String.class).invoke(manager, id);
                if (opt instanceof java.util.Optional<?> o) {
                    existing = o.orElse(null);
                } else {
                    existing = opt;
                }
            } catch (Throwable ignored) {}

            if (existing != null && respectExternal) {
                externalHolograms.put(locationKey, existing);
                if (plugin.getConfig().getBoolean("settings.debug", false)) {
                    plugin.getLogger().info("[Hologram] Keeping external Fancy hologram " + id);
                }
                return true;
            }

            // Force recreate only when respect-external is false or hologram missing
            if (existing != null) {
                try {
                    manager.getClass().getMethod("removeHologram", String.class).invoke(manager, id);
                } catch (NoSuchMethodException e) {
                    try {
                        manager.getClass().getMethod("removeHologram", existing.getClass()).invoke(manager, existing);
                    } catch (Throwable ignored) {}
                } catch (Throwable ignored) {}
            }

            Class<?> textDataCl = Class.forName("de.oliver.fancyholograms.api.data.TextHologramData");
            Object data = textDataCl.getConstructor(String.class, Location.class).newInstance(id, loc);

            // setText — List<String> or List<Component>
            boolean textSet = false;
            for (String mname : new String[]{"setText", "setLines"}) {
                if (textSet) break;
                for (Class<?> arg : new Class<?>[]{List.class, java.util.Collection.class}) {
                    try {
                        textDataCl.getMethod(mname, arg).invoke(data, colored);
                        textSet = true;
                        break;
                    } catch (NoSuchMethodException ignored) {
                    }
                }
            }
            if (!textSet) {
                // try Adventure components
                try {
                    Class<?> comp = Class.forName("net.kyori.adventure.text.Component");
                    Class<?> serializer = Class.forName("net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer");
                    Object ser = serializer.getMethod("legacySection").invoke(null);
                    List<Object> comps = new ArrayList<>();
                    for (String s : colored) {
                        comps.add(ser.getClass().getMethod("deserialize", String.class).invoke(ser, s));
                    }
                    textDataCl.getMethod("setText", List.class).invoke(data, comps);
                    textSet = true;
                } catch (Throwable ignored) {}
            }

            // billboard CENTER so always readable
            try {
                Class<?> billboard = Class.forName("org.bukkit.entity.Display$Billboard");
                Object center = Enum.valueOf((Class<Enum>) billboard.asSubclass(Enum.class), "CENTER");
                Class<?> hologramDataCl = Class.forName("de.oliver.fancyholograms.api.data.HologramData");
                hologramDataCl.getMethod("setBillboard", billboard).invoke(data, center);
            } catch (Throwable ignored) {}

            Object hologram;
            try {
                Class<?> hologramDataCl = Class.forName("de.oliver.fancyholograms.api.data.HologramData");
                hologram = manager.getClass().getMethod("create", hologramDataCl).invoke(manager, data);
            } catch (NoSuchMethodException e) {
                hologram = manager.getClass().getMethod("create", textDataCl).invoke(manager, data);
            }
            if (hologram == null) {
                plugin.getLogger().warning("FancyHolograms create() returned null for " + id);
                return false;
            }

            // addHologram
            try {
                manager.getClass().getMethod("addHologram", hologram.getClass()).invoke(manager, hologram);
            } catch (NoSuchMethodException e) {
                Class<?> holoIface = Class.forName("de.oliver.fancyholograms.api.hologram.Hologram");
                manager.getClass().getMethod("addHologram", holoIface).invoke(manager, hologram);
            }

            try { hologram.getClass().getMethod("forceUpdate").invoke(hologram); } catch (Throwable ignored) {}
            try { hologram.getClass().getMethod("queueUpdate").invoke(hologram); } catch (Throwable ignored) {}
            try { hologram.getClass().getMethod("showAll").invoke(hologram); } catch (Throwable ignored) {}

            externalHolograms.put(locationKey, id);
            plugin.getLogger().info("FancyHologram spawned: " + id + " at " + locationKey);
            return true;
        } catch (Throwable t) {
            plugin.getLogger().warning("FancyHolograms spawn failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            if (plugin.getConfig().getBoolean("settings.debug", false)) {
                t.printStackTrace();
            }
            return false;
        }
    }

    public void removeHologram(String locationKey) {
        removeHologram(locationKey, true);
    }

    /**
     * @param deleteExternal when false, only clears ArmorStand + tracking (keeps /fholo hologram)
     */
    public void removeHologram(String locationKey, boolean deleteExternal) {
        Object ext = externalHolograms.remove(locationKey);
        String fancyId = ext != null ? String.valueOf(ext) : hologramId(locationKey);
        if (deleteExternal) {
            // FancyHolograms
            try {
                Class<?> pluginCl = Class.forName("de.oliver.fancyholograms.api.FancyHologramsPlugin");
                Object fhPlugin = pluginCl.getMethod("get").invoke(null);
                Object manager = fhPlugin.getClass().getMethod("getHologramManager").invoke(fhPlugin);
                manager.getClass().getMethod("removeHologram", String.class).invoke(manager, fancyId);
            } catch (Throwable ignored) {}
            // DecentHolograms
            try {
                Class<?> api = Class.forName("eu.decentsoftware.holograms.api.DHAPI");
                String dhId = hologramId(locationKey);
                api.getMethod("removeHologram", String.class).invoke(null, dhId);
            } catch (Throwable ignored) {}
        }

        List<ArmorStand> stands = holograms.remove(locationKey);
        if (stands != null) {
            stands.forEach(Entity::remove);
        }
        blockCrates.remove(locationKey);
        Location base = parseLocation(locationKey);
        if (base != null && base.getWorld() != null) {
            for (Entity entity : base.getWorld().getNearbyEntities(base.clone().add(0.5, 1.5, 0.5), 2, 3, 2)) {
                if (entity instanceof ArmorStand stand
                        && stand.getPersistentDataContainer().has(holoKey, PersistentDataType.STRING)) {
                    String tag = stand.getPersistentDataContainer().get(holoKey, PersistentDataType.STRING);
                    if (locationKey.equals(tag)) {
                        stand.remove();
                    }
                }
            }
        }
    }

    public void clearAll() {
        stopParticles();
        for (String key : new ArrayList<>(externalHolograms.keySet())) {
            removeHologram(key);
        }
        externalHolograms.clear();
        holograms.values().forEach(list -> list.forEach(Entity::remove));
        holograms.clear();
        blockCrates.clear();
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntitiesByClass(ArmorStand.class)) {
                if (entity.getPersistentDataContainer().has(holoKey, PersistentDataType.STRING)) {
                    entity.remove();
                }
            }
        }
    }

    public void respawnAll(Map<String, String> blocks, Function<String, CrateDefinition> lookup) {
        this.crateLookup = lookup == null ? id -> null : lookup;
        boolean respect = plugin.getConfig().getBoolean("settings.hologram-respect-external", true);
        if (respect && (useFancyHolograms || useDecentHolograms)) {
            // Keep Fancy/Decent holograms edited via /fholo — only clear ArmorStand backend
            stopParticles();
            holograms.values().forEach(list -> list.forEach(Entity::remove));
            holograms.clear();
            // Drop tracking only; do not delete external hologram entities
            externalHolograms.clear();
            blockCrates.clear();
            blockCrates.putAll(blocks);
            for (Map.Entry<String, String> entry : blocks.entrySet()) {
                CrateDefinition crate = crateLookup.apply(entry.getValue());
                // spawnHologram will attach to existing Fancy hologram without overwriting text
                spawnHologram(entry.getKey(), crate);
            }
            startParticles(blocks);
            return;
        }
        clearAll();
        cleanupOrphans(blocks);
        for (Map.Entry<String, String> entry : blocks.entrySet()) {
            CrateDefinition crate = crateLookup.apply(entry.getValue());
            spawnHologram(entry.getKey(), crate);
        }
        startParticles(blocks);
    }


    /**
     * Read FancyHolograms / DecentHolograms text for each spawned hologram.
     * If lines differ from crate config, persist via callback and apply to all
     * blocks of the same crate id.
     */
    private void syncExternalHologramEdits() {
        if (onHologramLinesChanged == null || blockCrates.isEmpty()) return;
        if (!useFancyHolograms && !useDecentHolograms) return;

        // crateId -> first observed new lines (from any of its holograms)
        java.util.Map<String, java.util.List<String>> changed = new java.util.HashMap<>();

        for (java.util.Map.Entry<String, String> e : blockCrates.entrySet()) {
            String locationKey = e.getKey();
            String crateId = e.getValue();
            if (crateId == null) continue;
            java.util.List<String> live = readExternalLines(locationKey);
            if (live == null || live.isEmpty()) continue;

            CrateDefinition crate = crateLookup.apply(crateId);
            if (crate == null) continue;
            java.util.List<String> stored = crate.getHologramLines();
            if (stored == null) stored = java.util.List.of();
            // Compare normalized (& form, no section signs)
            java.util.List<String> liveNorm = normalizeLines(live);
            java.util.List<String> storedNorm = normalizeLines(stored);
            if (storedNorm.isEmpty()) {
                // default template — treat first line as display name
                storedNorm = normalizeLines(java.util.List.of(
                        crate.getDisplayName() == null ? crateId : crate.getDisplayName(),
                        "&7Right-click to open"));
            }
            if (liveNorm.equals(storedNorm)) continue;
            changed.putIfAbsent(crateId.toLowerCase(java.util.Locale.ROOT), liveNorm);
        }

        for (java.util.Map.Entry<String, java.util.List<String>> ch : changed.entrySet()) {
            String crateId = ch.getKey();
            java.util.List<String> lines = ch.getValue();
            try {
                onHologramLinesChanged.accept(crateId, lines);
                plugin.getLogger().info("Saved hologram lines for crate '" + crateId + "' (" + lines.size() + " lines) from in-world edit");
            } catch (Throwable t) {
                plugin.getLogger().warning("Hologram line save failed for " + crateId + ": " + t.getMessage());
            }
            // Respawn all holograms of this crate so they match
            for (java.util.Map.Entry<String, String> e : blockCrates.entrySet()) {
                if (e.getValue() != null && e.getValue().equalsIgnoreCase(crateId)) {
                    CrateDefinition fresh = crateLookup.apply(crateId);
                    spawnHologram(e.getKey(), fresh);
                }
            }
        }
    }

    private static java.util.List<String> normalizeLines(java.util.List<String> in) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (in == null) return out;
        for (String s : in) {
            if (s == null) continue;
            String n = s.replace('§', '&').trim();
            out.add(n);
        }
        return out;
    }

    /** Best-effort read of FancyHolograms or DecentHolograms lines for a location key. */
    private java.util.List<String> readExternalLines(String locationKey) {
        String id = hologramId(locationKey);
        if (useFancyHolograms) {
            java.util.List<String> fancy = readFancyLines(id);
            if (fancy != null) return fancy;
        }
        if (useDecentHolograms) {
            java.util.List<String> decent = readDecentLines(id);
            if (decent != null) return decent;
        }
        return null;
    }

    private java.util.List<String> readFancyLines(String id) {
        try {
            Class<?> pluginCl = Class.forName("de.oliver.fancyholograms.FancyHolograms");
            Object fh = pluginCl.getMethod("get").invoke(null);
            Object manager = fh.getClass().getMethod("getHologramManager").invoke(fh);
            Object hologram = null;
            // getHologram(String) -> Optional or Hologram
            try {
                Object opt = manager.getClass().getMethod("getHologram", String.class).invoke(manager, id);
                if (opt instanceof java.util.Optional<?> o) {
                    hologram = o.orElse(null);
                } else {
                    hologram = opt;
                }
            } catch (NoSuchMethodException e) {
                // try getHolograms map
                try {
                    Object map = manager.getClass().getMethod("getHolograms").invoke(manager);
                    if (map instanceof java.util.Map<?, ?> m) {
                        hologram = m.get(id);
                    }
                } catch (Throwable ignored) {}
            }
            if (hologram == null) return null;
            Object data = null;
            for (String mn : new String[]{"getData", "data"}) {
                try {
                    data = hologram.getClass().getMethod(mn).invoke(hologram);
                    if (data != null) break;
                } catch (Throwable ignored) {}
            }
            if (data == null) return null;
            Object text = null;
            for (String mn : new String[]{"getText", "getLines", "text", "lines"}) {
                try {
                    text = data.getClass().getMethod(mn).invoke(data);
                    if (text != null) break;
                } catch (Throwable ignored) {}
            }
            if (!(text instanceof java.util.List<?> list)) return null;
            java.util.List<String> out = new java.util.ArrayList<>();
            for (Object line : list) {
                if (line == null) continue;
                if (line instanceof String s) {
                    out.add(s);
                    continue;
                }
                // Adventure Component
                try {
                    Class<?> serializer = Class.forName("net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer");
                    Object ser = serializer.getMethod("legacyAmpersand").invoke(null);
                    String s = (String) ser.getClass().getMethod("serialize", Class.forName("net.kyori.adventure.text.Component"))
                            .invoke(ser, line);
                    out.add(s);
                } catch (Throwable t) {
                    out.add(String.valueOf(line).replace('§', '&'));
                }
            }
            return out.isEmpty() ? null : out;
        } catch (Throwable t) {
            if (plugin.getConfig().getBoolean("settings.debug", false)) {
                plugin.getLogger().info("readFancyLines: " + t.getMessage());
            }
            return null;
        }
    }

    private java.util.List<String> readDecentLines(String id) {
        try {
            Class<?> api = Class.forName("eu.decentsoftware.holograms.api.DHAPI");
            Object holo = api.getMethod("getHologram", String.class).invoke(null, id);
            if (holo == null) return null;
            // getPage(0).getLines() or getLines()
            java.util.List<?> lines = null;
            try {
                Object page = holo.getClass().getMethod("getPage", int.class).invoke(holo, 0);
                lines = (java.util.List<?>) page.getClass().getMethod("getLines").invoke(page);
            } catch (Throwable ignored) {
                try {
                    lines = (java.util.List<?>) holo.getClass().getMethod("getLines").invoke(holo);
                } catch (Throwable ignored2) {}
            }
            if (lines == null) return null;
            java.util.List<String> out = new java.util.ArrayList<>();
            for (Object line : lines) {
                if (line == null) continue;
                try {
                    String content = (String) line.getClass().getMethod("getContent").invoke(line);
                    out.add(content == null ? "" : content.replace('§', '&'));
                } catch (Throwable t) {
                    out.add(String.valueOf(line).replace('§', '&'));
                }
            }
            return out.isEmpty() ? null : out;
        } catch (Throwable t) {
            return null;
        }
    }



    /** Stable external hologram id for a block location. */
    static String hologramId(String locationKey) {
        String raw = "nc_" + locationKey.replace(':', '_').replace(',', '_').replace('.', '_');
        if (raw.length() > 48) {
            raw = "nc_" + Integer.toHexString(locationKey.hashCode());
        }
        return raw;
    }

    /**
     * Prefer configured hologram.lines; otherwise display-name (with colors).
     * Never show bare crate id when a proper display-name exists.
     */
    static java.util.List<String> resolveHologramLines(CrateDefinition crate) {
        if (crate == null) return java.util.List.of("Crate");
        java.util.List<String> configured = crate.getHologramLines();
        String display = crate.getDisplayName();
        String id = crate.getId() == null ? "" : crate.getId();
        if (display == null || display.isBlank()) display = id;

        if (configured != null && !configured.isEmpty()) {
            java.util.List<String> out = new java.util.ArrayList<>();
            for (int i = 0; i < configured.size(); i++) {
                String line = configured.get(i);
                if (line == null) line = "";
                line = line.replace('§', '&');
                if (i == 0) {
                    String stripped = line.replaceAll("(?i)[&§][0-9a-fk-or]", "").trim();
                    if (stripped.equalsIgnoreCase(id) || stripped.isEmpty()) {
                        line = display.replace('§', '&');
                    }
                }
                out.add(line);
            }
            return out;
        }
        return java.util.List.of(display.replace('§', '&'), "&7Right-click to open");
    }

    private Location parseLocation(String key) {
        String[] parts = key.split(":");
        if (parts.length != 4) {
            return null;
        }
        World world = Bukkit.getWorld(parts[0]);
        if (world == null) {
            return null;
        }
        try {
            return new Location(world,
                    Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2]),
                    Integer.parseInt(parts[3]));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
