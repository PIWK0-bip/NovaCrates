package com.skritped.novacrates.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TextStripTest {
    @Test
    void stripsSectionAndAmpersand() {
        assertEquals("Waga: 1", Text.strip("§7Waga: §e1"));
        assertEquals("Waga: 1", Text.strip("&7Waga: &e1"));
    }
}
