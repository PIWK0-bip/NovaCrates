package com.skritped.novacrates.util;

/** Detects editor-only lore lines that must not be persisted into rewards. */
public final class EditorMeta {
    private EditorMeta() {}

    public static boolean isEditorMetaLine(String line) {
        if (line == null) return true;
        String s = Text.strip(line);
        if (s.isEmpty() || s.startsWith("──") || s.startsWith("---")) return true;
        return s.startsWith("Weight:") || s.startsWith("Chance:") || s.startsWith("Rarity:")
                || s.startsWith("Waga:") || s.startsWith("Szansa:")
                || s.startsWith("Broadcast:") || s.startsWith("Komendy:")
                || s.startsWith("Commands:") || s.startsWith("ID:")
                || s.contains("Prawy klik") || s.contains("Right-click")
                || s.contains("edytuj szans") || s.contains("ŚPM") || s.contains("Shift+PPM");
    }
}
