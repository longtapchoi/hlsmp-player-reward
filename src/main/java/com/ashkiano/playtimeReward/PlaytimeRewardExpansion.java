package com.ashkiano.playtimeReward;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Placeholders:
 *   %playtimereward_remaining%          → "14:32" (mm:ss)
 *   %playtimereward_remaining_seconds%  → "872"
 *   %playtimereward_remaining_mm%       → "14"
 *   %playtimereward_remaining_ss%       → "32"
 */
public class PlaytimeRewardExpansion extends PlaceholderExpansion {

    private final PlaytimeReward plugin;

    public PlaytimeRewardExpansion(PlaytimeReward plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "playtimereward";
    }

    @Override
    public @NotNull String getAuthor() {
        return "AshKiano";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true; // không bị unregister khi PAPI reload
    }

    @Override
    public String onPlaceholderRequest(Player player, @NotNull String identifier) {
        if (player == null) return "";

        int remaining = plugin.getRemainingSeconds(player.getUniqueId());

        return switch (identifier) {
            case "remaining" -> formatMmSs(remaining);
            case "remaining_seconds" -> String.valueOf(remaining);
            case "remaining_mm" -> String.valueOf(remaining / 60);
            case "remaining_ss" -> String.format("%02d", remaining % 60);
            default -> null;
        };
    }

    private String formatMmSs(int totalSeconds) {
        int mm = totalSeconds / 60;
        int ss = totalSeconds % 60;
        return String.format("%d:%02d", mm, ss);
    }
}
