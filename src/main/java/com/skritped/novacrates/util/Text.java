package com.skritped.novacrates.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;

public final class Text {
    private static final LegacyComponentSerializer AMPERSAND =
            LegacyComponentSerializer.legacyAmpersand();
    private static final LegacyComponentSerializer SECTION =
            LegacyComponentSerializer.legacySection();
    private static final PlainTextComponentSerializer PLAIN =
            PlainTextComponentSerializer.plainText();

    private Text() {
    }

    public static Component component(String input) {
        if (input == null || input.isEmpty()) {
            return Component.empty();
        }
        return AMPERSAND.deserialize(input);
    }

    public static String legacy(String input) {
        if (input == null) {
            return "";
        }
        return SECTION.serialize(AMPERSAND.deserialize(input));
    }

    /** Strip all color/formatting codes for comparison (§ and &). */
    public static String strip(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        // Bukkit lore uses §; config/messages often use &
        Component c;
        if (input.indexOf('§') >= 0 || input.indexOf('\u00a7') >= 0) {
            c = SECTION.deserialize(input);
        } else {
            c = AMPERSAND.deserialize(input);
        }
        return PLAIN.serialize(c).trim();
    }

    public static void send(CommandSender sender, String ampersandMessage) {
        sender.sendMessage(component(ampersandMessage));
    }
}
