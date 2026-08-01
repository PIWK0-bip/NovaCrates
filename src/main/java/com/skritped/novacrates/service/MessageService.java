package com.skritped.novacrates.service;

import com.skritped.novacrates.util.Text;
import net.kyori.adventure.title.Title;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

public class MessageService {
    private final JavaPlugin plugin;
    private YamlConfiguration messages;
    private String currentLang = "en";

    public MessageService(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        String language = plugin.getConfig().getString("settings.language", "en");
        loadLanguage(language);
    }

    public void loadLanguage(String language) {
        if (language == null || language.isBlank()) language = "en";
        language = language.toLowerCase();
        this.currentLang = language;
        File langDir = new File(plugin.getDataFolder(), "lang");
        if (!langDir.exists()) {
            langDir.mkdirs();
        }
        // Always refresh defaults from jar (false = don't overwrite existing custom edits)
        // Ship latest GUI keys from jar (overwrite defaults; custom keys still merge if admin edits carefully)
        try {
            plugin.saveResource("lang/messages_en.yml", true);
            plugin.saveResource("lang/messages_pl.yml", true);
        } catch (Throwable t) {
            plugin.saveResource("lang/messages_en.yml", false);
            plugin.saveResource("lang/messages_pl.yml", false);
        }
        File file = new File(langDir, "messages_" + language + ".yml");
        if (!file.exists()) {
            file = new File(langDir, "messages_en.yml");
            this.currentLang = "en";
        }
        messages = YamlConfiguration.loadConfiguration(file);
    }

    public String getLanguage() {
        return currentLang;
    }

    /** Switch language and persist to config. */
    public void setLanguage(String language) {
        loadLanguage(language);
        plugin.getConfig().set("settings.language", currentLang);
        plugin.saveConfig();
    }

    public String raw(String key) {
        // Config override for chat prefix
        if ("prefix".equals(key)) {
            String cfgPrefix = plugin.getConfig().getString("settings.prefix");
            if (cfgPrefix != null && !cfgPrefix.isBlank()) {
                return cfgPrefix;
            }
        }
        if (messages == null) return key;
        return messages.getString(key, key);
    }

    /** GUI/text without prefix. */
    public String gui(String key) {
        return color(raw(key));
    }

    public String gui(String key, Map<String, String> placeholders) {
        return color(apply(raw(key), placeholders));
    }

    public String format(String key, Map<String, String> placeholders) {
        String text = raw("prefix") + raw(key);
        return apply(text, placeholders);
    }

    public void send(CommandSender sender, String key) {
        Text.send(sender, format(key, Map.of()));
    }

    public void send(CommandSender sender, String key, Map<String, String> placeholders) {
        Text.send(sender, format(key, placeholders));
    }

    public String color(String text) {
        return Text.legacy(text == null ? "" : text);
    }

    public void title(Player player, String titleKey, String subtitleKey, Map<String, String> placeholders) {
        if (player == null || !player.isOnline()) return;
        if (!plugin.getConfig().getBoolean("settings.win-title", true)) return;
        String titleText = apply(raw(titleKey), placeholders);
        String subText = apply(raw(subtitleKey), placeholders);
        // If key missing from lang, raw() returns the key itself — skip ugly keys
        if (titleText == null || titleText.equals(titleKey)) {
            titleText = "&a&lYOU WON!";
        }
        if (subText == null || subText.equals(subtitleKey)) {
            subText = placeholders != null && placeholders.containsKey("reward")
                    ? placeholders.get("reward") : "";
        }
        try {
            Title title = Title.title(
                    Text.component(titleText),
                    Text.component(subText),
                    Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(2), Duration.ofMillis(400))
            );
            player.showTitle(title);
        } catch (Throwable t) {
            // Fallback legacy
            try {
                player.sendTitle(color(titleText), color(subText), 10, 40, 10);
            } catch (Throwable ignored) {}
        }
    }

    private String apply(String text, Map<String, String> placeholders) {
        if (text == null) return "";
        if (placeholders != null) {
            for (Map.Entry<String, String> e : placeholders.entrySet()) {
                text = text.replace("%" + e.getKey() + "%", e.getValue() == null ? "" : e.getValue());
            }
        }
        return text;
    }
}
