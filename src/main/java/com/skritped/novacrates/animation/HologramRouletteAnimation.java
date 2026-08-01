package com.skritped.novacrates.animation;

import com.skritped.novacrates.model.CrateDefinition;
import com.skritped.novacrates.model.RewardDefinition;
import com.skritped.novacrates.service.ItemFactory;
import com.skritped.novacrates.util.SchedulerUtil;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Face-anchored hologram roulette with:
 * <ul>
 *   <li>Intro: chest rises from below, shakes harder ~2s, explodes</li>
 *   <li>Smooth continuous strip scroll (slower ease-out)</li>
 *   <li>Center item glowing</li>
 *   <li>Camera lag — strip follows look with soft delay</li>
 * </ul>
 */
public final class HologramRouletteAnimation {
    private final JavaPlugin plugin;
    private final ItemFactory itemFactory;
    private final Map<UUID, ActiveAnim> activeAnims = new ConcurrentHashMap<>();

    private record ActiveAnim(SchedulerUtil.Cancellable task, List<Entity> displays,
                              List<Entity> introEntities,
                              Consumer<RewardDefinition> onDone, RewardDefinition winner) {}

    /** Smoothed camera for laggy follow. */
    private static final class CamLag {
        Vector look;
        Location eye;
        double smooth; // 0..1 per tick toward target (lower = more lag)

        CamLag(Player player, double smooth) {
            // 1.0 = realtime; 0.35–0.6 = very light smoothing only (NOT multi-second lag)
            this.smooth = Math.max(0.2, Math.min(1.0, smooth));
            this.eye = player.getEyeLocation().clone();
            this.look = eye.getDirection().clone().normalize();
        }

        void tick(Player player) {
            Location targetEye = player.getEyeLocation();
            Vector targetLook = targetEye.getDirection().clone();
            if (targetLook.lengthSquared() < 1e-6) targetLook = new Vector(0, 0, 1);
            targetLook.normalize();

            if (smooth >= 0.999) {
                // Realtime — stick to camera exactly
                eye = targetEye.clone();
                look = targetLook;
                return;
            }

            // Light smoothing only
            look = lerpVec(look, targetLook, smooth);
            if (look.lengthSquared() < 1e-6) look = targetLook.clone();
            look.normalize();
            eye = lerpLoc(eye, targetEye, smooth);
            eye.setWorld(targetEye.getWorld());
        }

        FacePose pose(double distance, double heightOffset) {
            Location center = eye.clone().add(look.clone().multiply(distance));
            center.add(0, heightOffset, 0);
            Vector up = new Vector(0, 1, 0);
            Vector right = look.clone().crossProduct(up);
            if (right.lengthSquared() < 0.0001) {
                right = new Vector(1, 0, 0);
            }
            right.normalize();
            return new FacePose(center, right, look.clone());
        }
    }

    private record FacePose(Location center, Vector right, Vector forward) {}

    public HologramRouletteAnimation(JavaPlugin plugin, ItemFactory itemFactory) {
        this.plugin = plugin;
        this.itemFactory = itemFactory;
    }

    public void play(Player player, Location origin, CrateDefinition crate, RewardDefinition winner,
                     Consumer<RewardDefinition> onDone) {
        cancel(player.getUniqueId());

        boolean faceMode = isFaceMode();
        double faceDistance = plugin.getConfig().getDouble("settings.roulette-face-distance", 1.65);
        double faceHeight = plugin.getConfig().getDouble("settings.roulette-face-height", -0.05);
        float faceScaleMul = (float) plugin.getConfig().getDouble("settings.roulette-face-scale", 0.95);
        int teleportDuration = Math.max(1, plugin.getConfig().getInt("settings.roulette-teleport-duration", 3));
        int transformInterp = Math.max(1, plugin.getConfig().getInt("settings.roulette-transform-interp", 3));
        double camLag = plugin.getConfig().getDouble("settings.roulette-camera-lag", 1.0);
        int introTicks = Math.max(20, plugin.getConfig().getInt("settings.roulette-intro-ticks", 40));
        boolean introEnabled = plugin.getConfig().getBoolean("settings.roulette-intro", true);

        // ---- Scale mode: many concurrent opens → cheaper animation ----
        int load = activeAnims.size();
        int softCap = plugin.getConfig().getInt("settings.scale-soft-cap", 12);
        int hardVisualCap = plugin.getConfig().getInt("settings.scale-hard-visual-cap", 24);
        boolean heavyLoad = load >= softCap;
        boolean extremeLoad = load >= hardVisualCap;
        if (heavyLoad) {
            introEnabled = false; // skip chest intro under load
            teleportDuration = Math.min(teleportDuration, 2);
            transformInterp = Math.min(transformInterp, 2);
        }

        List<RewardDefinition> pool = new ArrayList<>(crate.getRewards());
        if (pool.isEmpty()) pool.add(winner);

        int visible = Math.max(5, Math.min(11, plugin.getConfig().getInt("settings.roulette-visible", 7)));
        if (heavyLoad) visible = Math.min(visible, 5);
        if (extremeLoad) visible = 3;
        if (visible % 2 == 0) visible++;
        int centerIdx = visible / 2;

        int durationTicks = crateDuration(crate);
        if (heavyLoad) {
            durationTicks = Math.min(durationTicks, plugin.getConfig().getInt("settings.scale-anim-duration-ticks", 60));
        }
        if (extremeLoad) {
            durationTicks = Math.min(durationTicks, 40);
        }
        int totalSteps = Math.max(visible + 16, (int) (durationTicks * 0.9));
        List<RewardDefinition> strip = new ArrayList<>();
        for (int i = 0; i < totalSteps + visible + 4; i++) {
            strip.add(pool.get(ThreadLocalRandom.current().nextInt(pool.size())));
        }
        double finalScroll = totalSteps - 1;
        int winnerIndex = (int) Math.round(finalScroll) + centerIdx;
        if (winnerIndex >= 0 && winnerIndex < strip.size()) {
            strip.set(winnerIndex, winner);
        }

        double spacing = plugin.getConfig().getDouble("settings.roulette-spacing",
                faceMode ? 0.38 : 0.55);

        final UUID playerId = player.getUniqueId();
        final CamLag cam = faceMode ? new CamLag(player, camLag) : null;

        // Crate-mode static base
        final Location[] crateBase = {null};
        final Vector[] crateRight = {null};
        if (!faceMode) {
            Location o = origin;
            if (o == null || o.getWorld() == null) {
                o = player.getLocation().add(0, 1.2, 0);
            } else {
                o = o.clone().add(0.5, 1.8, 0.5);
            }
            Vector right = player.getLocation().getDirection().clone().setY(0);
            if (right.lengthSquared() < 0.01) right = new Vector(1, 0, 0);
            right.normalize();
            Vector forward = right.clone().crossProduct(new Vector(0, 1, 0)).normalize().multiply(-1);
            crateBase[0] = o.clone().add(forward.clone().multiply(0.3));
            crateRight[0] = right;
        }

        World world = player.getWorld();
        if (world == null) {
            if (onDone != null) onDone.accept(winner);
            return;
        }

        // --- INTRO: chest rises + spins, then explode into roulette ---
        if (introEnabled && faceMode) {
            playIntroThenRoulette(player, playerId, cam, world, origin, faceDistance, faceHeight,
                    faceScaleMul, teleportDuration, transformInterp, spacing, visible, centerIdx,
                    strip, finalScroll, durationTicks, introTicks, crate, winner, onDone);
            return;
        }

        // No intro / crate mode — start roulette directly
        startRoulette(player, playerId, cam, world, faceMode, faceDistance, faceHeight,
                faceScaleMul, teleportDuration, transformInterp, spacing, visible, centerIdx,
                strip, finalScroll, durationTicks, crateBase[0], crateRight[0],
                List.of(), winner, onDone);
    }

    private void playIntroThenRoulette(Player player, UUID playerId, CamLag cam, World world,
                                       Location crateOrigin,
                                       double faceDistance, double faceHeight, float faceScaleMul,
                                       int teleportDuration, int transformInterp, double spacing,
                                       int visible, int centerIdx, List<RewardDefinition> strip,
                                       double finalScroll, int durationTicks, int introTicks,
                                       CrateDefinition crate, RewardDefinition winner,
                                       Consumer<RewardDefinition> onDone) {
        List<Entity> introEntities = new ArrayList<>();
        // Use the actual crate block the player opened (fallback: config / CHEST)
        ItemStack chestItem = new ItemStack(resolveIntroMaterial(crateOrigin, crate));

        cam.tick(player);
        FacePose startPose = cam.pose(faceDistance, faceHeight - 0.9); // below center
        Entity chest = spawnItemDisplay(world, startPose.center, chestItem, player, 1, false);
        if (chest instanceof ItemDisplay id) {
            try { id.setBillboard(ItemDisplay.Billboard.FIXED); } catch (Throwable ignored) {}
        }
        if (chest != null) introEntities.add(chest);

        final AtomicInteger tick = new AtomicInteger(0);
        SchedulerUtil.Cancellable[] taskRef = new SchedulerUtil.Cancellable[1];
        taskRef[0] = SchedulerUtil.runTimer(plugin, () -> {
            if (!player.isOnline()) {
                if (taskRef[0] != null) taskRef[0].cancel();
                cleanupEntities(introEntities);
                activeAnims.remove(playerId);
                if (onDone != null) onDone.accept(winner);
                return;
            }
            int t = tick.getAndIncrement();
            double p = Math.min(1.0, t / (double) Math.max(1, introTicks));
            cam.tick(player);

            // Rise from below to center (first 30%), then SHAKE in place (no spin)
            double rise = easeOutCubic(Math.min(1.0, p / 0.30));
            double heightOff = faceHeight - 0.85 * (1.0 - rise);
            FacePose pose = cam.pose(faceDistance, heightOff);
            Location loc = pose.center.clone();

            // Side shake locked to center — amplitude grows, frequency accelerates
            if (p > 0.28) {
                double shakeT = (p - 0.28) / 0.72; // 0..1
                double freq = 1.2 + shakeT * shakeT * 9.0;   // increasingly fast
                double amp = 0.025 + shakeT * 0.09;          // max ~0.11 block — stays on screen
                double xShake = Math.sin(t * freq) * amp;
                loc.add(pose.right.clone().multiply(xShake));
            }

            if (chest != null && chest.isValid()) {
                try { chest.teleport(loc); } catch (Throwable ignored) {}
                float scale = 0.55f + 0.28f * (float) rise;
                if (p > 0.82) {
                    // final tension pulse
                    scale *= (float) (1.0 + 0.12 * Math.sin(t * 1.2));
                }
                // Face chest FRONT toward the player (not the back)
                Vector toPlayer = player.getEyeLocation().toVector().subtract(loc.toVector());
                setScaleFacing(chest, scale, toPlayer, 1);
            }

            // Rattle sounds — faster near the end
            int soundEvery = p > 0.7 ? 2 : (p > 0.4 ? 3 : 5);
            if (t % soundEvery == 0) {
                try {
                    float pitch = 0.75f + (float) p * 0.9f;
                    player.playSound(player.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.12f, pitch);
                    if (p > 0.55) {
                        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 0.15f, pitch);
                    }
                } catch (Throwable ignored) {}
            }

            if (p >= 1.0) {
                if (taskRef[0] != null) taskRef[0].cancel();
                // EXPLODE
                Location burst = pose.center.clone();
                try {
                    player.playSound(player.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 0.55f, 1.25f);
                    player.playSound(player.getLocation(), Sound.BLOCK_CHEST_CLOSE, 0.7f, 0.9f);
                    if (burst.getWorld() != null) {
                        burst.getWorld().spawnParticle(Particle.EXPLOSION, burst, 1, 0, 0, 0, 0);
                        burst.getWorld().spawnParticle(Particle.FLASH, burst, 1, 0, 0, 0, 0);
                        burst.getWorld().spawnParticle(Particle.FIREWORK, burst, 40, 0.35, 0.25, 0.35, 0.08);
                        burst.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, burst, 25, 0.3, 0.2, 0.3, 0.05);
                    }
                } catch (Throwable ignored) {}
                cleanupEntities(introEntities);
                activeAnims.remove(playerId);

                // Short beat then roulette
                SchedulerUtil.runLater(plugin, () -> {
                    if (!player.isOnline()) {
                        if (onDone != null) onDone.accept(winner);
                        return;
                    }
                    startRoulette(player, playerId, cam, world, true, faceDistance, faceHeight,
                            faceScaleMul, teleportDuration, transformInterp, spacing, visible, centerIdx,
                            strip, finalScroll, durationTicks, null, null,
                            List.of(), winner, onDone);
                }, 4L);
            }
        }, 1L, 1L);

        activeAnims.put(playerId, new ActiveAnim(taskRef[0], List.of(), introEntities, onDone, winner));
    }

    private void startRoulette(Player player, UUID playerId, CamLag cam, World world,
                               boolean faceMode, double faceDistance, double faceHeight,
                               float faceScaleMul, int teleportDuration, int transformInterp,
                               double spacing, int visible, int centerIdx,
                               List<RewardDefinition> strip, double finalScroll, int durationTicks,
                               Location crateBase, Vector crateRight,
                               List<Entity> priorIntro, RewardDefinition winner,
                               Consumer<RewardDefinition> onDone) {
        List<Entity> displays = new ArrayList<>();
        final long framePeriod = activeAnims.size() >= plugin.getConfig().getInt("settings.scale-soft-cap", 12) ? 2L : 1L;

        // Initial pose
        Location spawnBase;
        Vector right;
        if (faceMode && cam != null) {
            cam.tick(player);
            FacePose pose = cam.pose(faceDistance, faceHeight);
            spawnBase = pose.center;
            right = pose.right;
        } else {
            spawnBase = crateBase != null ? crateBase : player.getLocation().add(0, 1.2, 0);
            right = crateRight != null ? crateRight : new Vector(1, 0, 0);
        }

        for (int i = 0; i < visible; i++) {
            double xOff = (i - centerIdx) * spacing;
            Location loc = spawnBase.clone().add(right.clone().multiply(xOff));
            Entity display = spawnItemDisplay(world, loc, itemFactory.create(strip.get(Math.min(i, strip.size() - 1))),
                    player, teleportDuration, i == centerIdx);
            if (display != null) displays.add(display);
        }

        final int[] lastStripIndex = new int[visible];
        for (int i = 0; i < visible; i++) lastStripIndex[i] = -1;
        final AtomicInteger tickCounter = new AtomicInteger(0);
        final int durationF = durationTicks;
        final double finalScrollF = finalScroll;

        SchedulerUtil.Cancellable[] taskRef = new SchedulerUtil.Cancellable[1];
        taskRef[0] = SchedulerUtil.runTimer(plugin, () -> {
            int tick = tickCounter.getAndIncrement();
            double progress = Math.min(1.0, tick / (double) Math.max(1, durationF));
            double scroll = finalScrollF * easeOutExpo(progress);
            if (progress >= 0.98) scroll = finalScrollF;

            if (!player.isOnline() || progress >= 1.0) {
                if (taskRef[0] != null) taskRef[0].cancel();
                applyStripSmooth(player, cam, displays, strip, finalScrollF, visible, centerIdx,
                        faceMode, faceDistance, faceHeight, crateBase, crateRight, spacing, faceScaleMul,
                        lastStripIndex, transformInterp, true);
                try {
                    player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.05f);
                    Location burst = faceMode && cam != null
                            ? cam.pose(faceDistance, faceHeight).center
                            : (crateBase != null ? crateBase : player.getLocation());
                    if (burst.getWorld() != null) {
                        burst.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, burst, 35, 0.35, 0.25, 0.35, 0.08);
                        burst.getWorld().spawnParticle(Particle.END_ROD, burst, 18, 0.25, 0.2, 0.25, 0.02);
                    }
                } catch (Throwable ignored) {}
                activeAnims.remove(playerId);
                SchedulerUtil.runLater(plugin, () -> {
                    cleanupEntities(displays);
                    if (onDone != null) onDone.accept(winner);
                }, Math.max(18, plugin.getConfig().getInt("settings.animation-end-hold-ticks", 35)));
                return;
            }

            applyStripSmooth(player, cam, displays, strip, scroll, visible, centerIdx,
                    faceMode, faceDistance, faceHeight, crateBase, crateRight, spacing, faceScaleMul,
                    lastStripIndex, transformInterp, false);

            if (tick > 0) {
                double prevScroll = finalScrollF * easeOutExpo(
                        Math.min(1.0, (tick - 1) / (double) Math.max(1, durationF)));
                if ((int) Math.floor(scroll) != (int) Math.floor(prevScroll)) {
                    try {
                        float pitch = 0.7f + (float) progress * 0.75f;
                        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.25f, pitch);
                    } catch (Throwable ignored) {}
                }
            }
        }, framePeriod, framePeriod);

        activeAnims.put(playerId, new ActiveAnim(taskRef[0], displays, priorIntro, onDone, winner));
    }

    public void cancel(UUID playerId) {
        finishAnim(playerId, false);
    }

    public void forceFinish(UUID playerId) {
        finishAnim(playerId, true);
    }

    private void finishAnim(UUID playerId, boolean invokeDone) {
        ActiveAnim anim = activeAnims.remove(playerId);
        if (anim == null) return;
        try {
            anim.task().cancel();
        } catch (Throwable ignored) {}
        cleanupEntities(anim.displays());
        cleanupEntities(anim.introEntities());
        if (invokeDone && anim.onDone() != null) {
            SchedulerUtil.runLater(plugin, () -> anim.onDone().accept(anim.winner()), 1L);
        }
    }

    private static void cleanupEntities(List<Entity> list) {
        if (list == null) return;
        for (Entity e : list) {
            try { e.remove(); } catch (Throwable ignored) {}
        }
    }

    private boolean isFaceMode() {
        String mode = plugin.getConfig().getString("settings.roulette-anchor", "face");
        if (mode == null) return true;
        mode = mode.trim().toLowerCase();
        return !(mode.equals("crate") || mode.equals("block") || mode.equals("world") || mode.equals("origin"));
    }

    private int crateDuration(CrateDefinition crate) {
        String style = crate.getAnimation() == null ? "HOLOGRAM" : crate.getAnimation().toUpperCase();
        int styleDuration = plugin.getConfig().getInt("settings.animation-styles." + style + ".duration-ticks", -1);
        if (styleDuration > 0) return styleDuration;
        // Default slower than before (was 80)
        return Math.max(60, plugin.getConfig().getInt("settings.animation-duration-ticks", 110));
    }

    private static double easeOutCubic(double t) {
        double u = 1.0 - Math.min(1.0, Math.max(0.0, t));
        return 1.0 - u * u * u;
    }

    /** Extra-soft landing for roulette scroll. */
    private static double easeOutExpo(double t) {
        t = Math.min(1.0, Math.max(0.0, t));
        if (t >= 1.0) return 1.0;
        return 1.0 - Math.pow(2, -10 * t);
    }

    private void applyStripSmooth(Player player, CamLag cam, List<Entity> displays,
                                  List<RewardDefinition> strip, double scroll,
                                  int visible, int centerIdx,
                                  boolean faceMode, double faceDist, double faceHeight,
                                  Location crateBase, Vector crateRight, double spacing, float scaleMul,
                                  int[] lastStripIndex, int transformInterp, boolean finalFrame) {
        Location base;
        Vector right;
        Vector forward = null;
        if (faceMode && cam != null && player != null && player.isOnline()) {
            cam.tick(player);
            FacePose pose = cam.pose(faceDist, faceHeight);
            base = pose.center;
            right = pose.right;
            forward = pose.forward;
        } else {
            base = crateBase;
            right = crateRight;
            if (base == null || right == null) return;
        }

        int floorScroll = (int) Math.floor(scroll);
        double frac = scroll - floorScroll;

        // Update sides first, center LAST so it stays visually on top
        int[] order = new int[Math.min(visible, displays.size())];
        int n = 0;
        for (int i = 0; i < visible && i < displays.size(); i++) {
            if (i != centerIdx) order[n++] = i;
        }
        if (centerIdx < displays.size() && centerIdx < visible) {
            order[n++] = centerIdx;
        }

        for (int oi = 0; oi < n; oi++) {
            int i = order[oi];
            int stripIndex = floorScroll + i;
            if (stripIndex < 0 || stripIndex >= strip.size()) continue;

            Entity ent = displays.get(i);
            double xOff = (i - centerIdx - frac) * spacing;
            Location loc = base.clone().add(right.clone().multiply(xOff));

            // Depth: center toward camera (in front), sides further back — never overlapping center
            // `forward` points from eyes toward the strip (away from player)
            if (forward != null) {
                double dist = Math.abs((i - centerIdx) - frac);
                // Mild depth only — center slightly in front so sides don't cover it
                double depth;
                if (dist < 0.55) {
                    depth = -0.08;
                } else {
                    depth = 0.04 * Math.min(dist, 3.0);
                }
                loc.add(forward.clone().multiply(depth));
            }

            try {
                ent.teleport(loc);
            } catch (Throwable ignored) {}

            if (lastStripIndex[i] != stripIndex) {
                lastStripIndex[i] = stripIndex;
                RewardDefinition def = strip.get(stripIndex);
                ItemStack stack = itemFactory.create(def);
                if (ent instanceof ItemDisplay display) {
                    display.setItemStack(stack);
                } else if (ent instanceof org.bukkit.entity.ArmorStand as) {
                    as.setHelmet(stack);
                }
            }

            // Glow + full brightness only on center
            try {
                ent.setGlowing(i == centerIdx);
            } catch (Throwable ignored) {}
            if (ent instanceof ItemDisplay id) {
                try {
                    id.setBrightness(new org.bukkit.entity.Display.Brightness(15, 15));
                } catch (Throwable ignored) {}
            }

            float scale = scaleForIndex(i, centerIdx, frac) * scaleMul;
            // Final frame: only a tiny emphasis, no pulse
            if (finalFrame && i == centerIdx) {
                scale *= 1.08f;
            }
            updateDisplay(ent, scale, transformInterp);
        }
    }

    private static float scaleForIndex(int i, int center, double frac) {
        double effective = (i - center) - frac;
        double dist = Math.abs(effective);
        // Gentle falloff — center only slightly larger
        if (dist < 0.5) return 1.0f;
        return (float) Math.max(0.28, 0.92 - dist * 0.16);
    }

    private Entity spawnItemDisplay(World world, Location loc, ItemStack item,
                                    Player viewer, int teleportDuration, boolean glowing) {
        try {
            ItemDisplay display = world.spawn(loc, ItemDisplay.class, d -> {
                d.setItemStack(item);
                d.setBillboard(ItemDisplay.Billboard.CENTER);
                d.setBrightness(new org.bukkit.entity.Display.Brightness(15, 15));
                try {
                    d.setVisibleByDefault(false);
                } catch (Throwable ignored) {}
                try {
                    d.setTeleportDuration(teleportDuration);
                } catch (Throwable ignored) {}
                try {
                    d.setInterpolationDuration(teleportDuration);
                    d.setInterpolationDelay(0);
                } catch (Throwable ignored) {}
                try {
                    d.setGlowing(glowing);
                } catch (Throwable ignored) {}
                setScale(d, 0.55f, teleportDuration);
            });
            try {
                viewer.showEntity(plugin, display);
            } catch (Throwable ignored) {
                try { display.setVisibleByDefault(true); } catch (Throwable ignored2) {}
            }
            return display;
        } catch (Throwable t) {
            try {
                return world.spawn(loc, org.bukkit.entity.ArmorStand.class, as -> {
                    as.setVisible(false);
                    as.setGravity(false);
                    as.setMarker(true);
                    as.setSmall(true);
                    as.setHelmet(item);
                    as.setInvulnerable(true);
                    as.setCollidable(false);
                    try { as.setGlowing(glowing); } catch (Throwable ignored) {}
                });
            } catch (Throwable t2) {
                return null;
            }
        }
    }

    private void updateDisplay(Entity ent, float scale, int transformInterp) {
        if (ent instanceof ItemDisplay display) {
            setScale(display, scale, transformInterp);
        } else if (ent instanceof org.bukkit.entity.ArmorStand as) {
            as.setSmall(scale < 0.7f);
        }
    }

    private static void setScale(ItemDisplay display, float scale, int interpTicks) {
        try {
            Transformation tr = new Transformation(
                    new Vector3f(0, 0, 0),
                    new AxisAngle4f(0, 0, 1, 0),
                    new Vector3f(scale, scale, scale),
                    new AxisAngle4f(0, 0, 1, 0)
            );
            display.setInterpolationDuration(Math.max(1, interpTicks));
            display.setInterpolationDelay(0);
            display.setTransformation(tr);
        } catch (Throwable ignored) {
        }
    }


    /**
     * Material for intro block: physical crate block at origin, else config, else CHEST.
     */
    private Material resolveIntroMaterial(Location origin, CrateDefinition crate) {
        // 1) Block the player opened
        if (origin != null && origin.getWorld() != null) {
            try {
                Location check = origin.clone();
                Material mat = check.getBlock().getType();
                if (mat != null && mat.isBlock() && !mat.isAir() && mat != Material.BARRIER
                        && mat != Material.LIGHT && mat != Material.STRUCTURE_VOID) {
                    return mat;
                }
            } catch (Throwable ignored) {}
        }
        // 2) Per-crate override: settings.roulette-intro-material.<crateId>
        if (crate != null && crate.getId() != null) {
            String per = plugin.getConfig().getString("settings.roulette-intro-material." + crate.getId());
            Material m = matchMat(per);
            if (m != null) return m;
        }
        // 3) Global config
        String matName = plugin.getConfig().getString("settings.roulette-intro-material", "CHEST");
        Material m = matchMat(matName);
        return m != null ? m : Material.CHEST;
    }

    private static Material matchMat(String name) {
        if (name == null || name.isBlank()) return null;
        try {
            Material m = Material.matchMaterial(name.trim());
            if (m != null && m.isBlock()) return m;
        } catch (Throwable ignored) {}
        return null;
    }

    private static void setScaleFixed(Entity ent, float scale, int interp) {
        if (ent instanceof ItemDisplay display) {
            setScale(display, scale, interp);
        }
    }

    /**
     * Orient ItemDisplay so the chest front faces the player.
     * Chest item model default faces roughly +Z; we rotate Y to look at the player.
     */
    private static void setScaleFacing(Entity ent, float scale, Vector towardPlayer, int interp) {
        if (!(ent instanceof ItemDisplay display)) return;
        try {
            try {
                display.setBillboard(ItemDisplay.Billboard.FIXED);
            } catch (Throwable ignored) {}
            Vector dir = towardPlayer == null ? new Vector(0, 0, 1) : towardPlayer.clone().setY(0);
            if (dir.lengthSquared() < 1e-6) {
                dir = new Vector(0, 0, 1);
            } else {
                dir.normalize();
            }
            // yaw: 0 = +Z; Minecraft Y-rotation
            float yaw = (float) Math.atan2(-dir.getX(), dir.getZ());
            // Model front offset — chest lock faces player (tune with +PI if still inverted)
            float modelOffset = (float) Math.PI;
            Quaternionf left = new Quaternionf().rotateY(yaw + modelOffset);
            Transformation tr = new Transformation(
                    new Vector3f(0, 0, 0),
                    left,
                    new Vector3f(scale, scale, scale),
                    new Quaternionf()
            );
            display.setInterpolationDuration(Math.max(1, interp));
            display.setInterpolationDelay(0);
            display.setTransformation(tr);
        } catch (Throwable t) {
            setScaleFixed(ent, scale, interp);
        }
    }

    private static void setSpinScale(Entity ent, float scale, float yawRad, int interp) {
        if (!(ent instanceof ItemDisplay display)) return;
        try {
            Quaternionf left = new Quaternionf().rotateY(yawRad);
            Transformation tr = new Transformation(
                    new Vector3f(0, 0, 0),
                    left,
                    new Vector3f(scale, scale, scale),
                    new Quaternionf()
            );
            display.setInterpolationDuration(Math.max(1, interp));
            display.setInterpolationDelay(0);
            display.setTransformation(tr);
        } catch (Throwable t) {
            setScale(display, scale, interp);
        }
    }

    private static Vector lerpVec(Vector a, Vector b, double t) {
        return new Vector(
                a.getX() + (b.getX() - a.getX()) * t,
                a.getY() + (b.getY() - a.getY()) * t,
                a.getZ() + (b.getZ() - a.getZ()) * t
        );
    }

    private static Location lerpLoc(Location a, Location b, double t) {
        return new Location(
                b.getWorld() != null ? b.getWorld() : a.getWorld(),
                a.getX() + (b.getX() - a.getX()) * t,
                a.getY() + (b.getY() - a.getY()) * t,
                a.getZ() + (b.getZ() - a.getZ()) * t
        );
    }
}
