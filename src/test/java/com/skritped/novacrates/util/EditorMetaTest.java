package com.skritped.novacrates.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EditorMetaTest {
    @Test
    void detectsEditorLines() {
        assertTrue(EditorMeta.isEditorMetaLine("&7Waga: &e1"));
        assertTrue(EditorMeta.isEditorMetaLine("Weight: 5"));
        assertTrue(EditorMeta.isEditorMetaLine("ID: rare_1"));
        assertTrue(EditorMeta.isEditorMetaLine(null));
        assertFalse(EditorMeta.isEditorMetaLine("&aDiamond Sword"));
        assertFalse(EditorMeta.isEditorMetaLine("A legendary prize"));
    }
}
