package com.skritped.novacrates.gui;

import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds working copy of editor inventory so Anvil re-open does not wipe unsaved slots.
 */
public final class EditorSession {
    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();

    public static final class Session {
        public final String crateId;
        public final List<ItemStack> slots; // size 45, nullable entries
        public List<ItemStack> undoSnapshot;

        public Session(String crateId) {
            this.crateId = crateId;
            this.slots = new ArrayList<>(45);
            for (int i = 0; i < 45; i++) slots.add(null);
        }

        public void captureFrom(org.bukkit.inventory.Inventory inv) {
            for (int i = 0; i < 45; i++) {
                ItemStack it = inv.getItem(i);
                slots.set(i, it == null || it.getType().isAir() ? null : it.clone());
            }
        }

        public void applyTo(org.bukkit.inventory.Inventory inv) {
            for (int i = 0; i < 45; i++) {
                ItemStack it = slots.get(i);
                inv.setItem(i, it == null ? null : it.clone());
            }
        }

        public void snapshotUndo() {
            undoSnapshot = new ArrayList<>(45);
            for (ItemStack it : slots) {
                undoSnapshot.add(it == null ? null : it.clone());
            }
        }

        public boolean restoreUndo() {
            if (undoSnapshot == null) return false;
            slots.clear();
            for (ItemStack it : undoSnapshot) {
                slots.add(it == null ? null : it.clone());
            }
            return true;
        }
    }

    public static Session getOrCreate(UUID player, String crateId) {
        Session s = SESSIONS.get(player);
        if (s == null || !s.crateId.equalsIgnoreCase(crateId)) {
            s = new Session(crateId);
            SESSIONS.put(player, s);
        }
        return s;
    }

    public static Session get(UUID player) {
        return SESSIONS.get(player);
    }

    public static void clear(UUID player) {
        SESSIONS.remove(player);
    }
}
