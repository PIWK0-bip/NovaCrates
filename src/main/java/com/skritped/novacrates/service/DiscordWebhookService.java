package com.skritped.novacrates.service;

import com.skritped.novacrates.model.CrateDefinition;
import com.skritped.novacrates.model.RewardDefinition;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Optional async Discord webhook for rare+ drops.
 * Supports plain content or rich embeds.
 * Config: settings.discord-webhook.url / enabled / rarities / use-embed
 */
public class DiscordWebhookService {
    private final JavaPlugin plugin;

    public DiscordWebhookService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void maybeBroadcast(Player player, CrateDefinition crate, RewardDefinition reward) {
        if (!plugin.getConfig().getBoolean("settings.discord-webhook.enabled", false)) {
            return;
        }
        String url = plugin.getConfig().getString("settings.discord-webhook.url", "");
        if (url == null || url.isBlank()) {
            return;
        }
        List<String> rarities = plugin.getConfig().getStringList("settings.discord-webhook.rarities");
        if (rarities != null && !rarities.isEmpty()) {
            boolean match = false;
            String r = reward.getRarity() == null ? "COMMON" : reward.getRarity().toUpperCase(Locale.ROOT);
            for (String allowed : rarities) {
                if (allowed.equalsIgnoreCase(r)) {
                    match = true;
                    break;
                }
            }
            if (!match && !reward.isBroadcast()) {
                return;
            }
        } else if (!reward.isBroadcast()) {
            return;
        }

        boolean useEmbed = plugin.getConfig().getBoolean("settings.discord-webhook.use-embed", true);
        final String payload;
        if (useEmbed) {
            payload = buildEmbedPayload(player, crate, reward);
        } else {
            String content = plugin.getConfig().getString("settings.discord-webhook.message",
                    "**%player%** won **%reward%** (%rarity%) from **%crate%**!");
            content = content
                    .replace("%player%", player.getName())
                    .replace("%reward%", strip(reward.getDisplayName()))
                    .replace("%crate%", strip(crate.getDisplayName()))
                    .replace("%rarity%", reward.getRarity() == null ? "COMMON" : reward.getRarity());
            payload = "{\"content\":" + jsonString(content) + "}";
        }

        final String webhookUrl = url;
        CompletableFuture.runAsync(() -> post(webhookUrl, payload));
    }

    private String buildEmbedPayload(Player player, CrateDefinition crate, RewardDefinition reward) {
        String rarity = reward.getRarity() == null ? "COMMON" : reward.getRarity().toUpperCase(Locale.ROOT);
        int color = rarityColor(rarity);
        String title = strip(reward.getDisplayName());
        String description = "**" + player.getName() + "** won from **" + strip(crate.getDisplayName()) + "**";
        String material = reward.getMaterial() == null ? "STONE" : reward.getMaterial();

        StringBuilder sb = new StringBuilder();
        sb.append("{\"embeds\":[{");
        sb.append("\"title\":").append(jsonString(title)).append(',');
        sb.append("\"description\":").append(jsonString(description)).append(',');
        sb.append("\"color\":").append(color).append(',');
        sb.append("\"fields\":[");
        sb.append("{\"name\":\"Rarity\",\"value\":").append(jsonString(rarity)).append(",\"inline\":true},");
        sb.append("{\"name\":\"Material\",\"value\":").append(jsonString(material)).append(",\"inline\":true},");
        sb.append("{\"name\":\"Amount\",\"value\":").append(jsonString(String.valueOf(reward.getAmount()))).append(",\"inline\":true}");
        sb.append("],");
        sb.append("\"footer\":{\"text\":").append(jsonString("NovaCrates")).append('}');
        sb.append("}]}");
        return sb.toString();
    }

    private static int rarityColor(String rarity) {
        return switch (rarity) {
            case "MYTHIC" -> 0xE74C3C;
            case "LEGENDARY" -> 0xF39C12;
            case "EPIC" -> 0x9B59B6;
            case "RARE" -> 0x3498DB;
            case "UNCOMMON" -> 0x2ECC71;
            default -> 0x95A5A6;
        };
    }

    private void post(String url, String json) {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("User-Agent", "NovaCrates");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(body.length);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
            }
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                plugin.getLogger().warning("Discord webhook HTTP " + code);
            }
            conn.disconnect();
        } catch (Exception e) {
            plugin.getLogger().warning("Discord webhook failed: " + e.getMessage());
        }
    }

    private static String strip(String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("(?i)&[0-9a-fk-or]", "").replaceAll("§[0-9a-fk-or]", "");
    }

    private static String jsonString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
