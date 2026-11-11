package me.jsmp.core;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

public final class JSMPCoreExpansion extends PlaceholderExpansion {

    private final JSMPCore plugin;

    public JSMPCoreExpansion(JSMPCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "jsmp";
    }

    @Override
    public String getAuthor() {
        return "Jigar";
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (player == null) return "";
        if (params.equalsIgnoreCase("balance")) {
            return plugin.getBalancePlaceholder(player.getUniqueId());
        }
        return null;
    }
}
