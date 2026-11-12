package me.jsmp.core;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerListPingEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;

public final class MotdManager implements CommandExecutor, Listener {
    private final JavaPlugin plugin;
    private final BooleanSupplier maintenanceEnabled;
    private final List<String> lines = new ArrayList<>();
    private int intervalSeconds;
    private int currentIndex;
    private BukkitTask rotationTask;

    public MotdManager(JavaPlugin plugin, BooleanSupplier maintenanceEnabled) {
        this.plugin = plugin;
        this.maintenanceEnabled = maintenanceEnabled;
        reload();
        if (plugin.getCommand("motd") != null) {
            plugin.getCommand("motd").setExecutor(this);
            plugin.getCommand("motd").setTabCompleter((sender, command, alias, args) -> {
                if (args.length == 1) return List.of("list", "add", "set", "remove", "interval", "next")
                        .stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
                return List.of();
            });
        }
        Bukkit.getPluginManager().registerEvents(this, plugin);
        applyCurrent();
        startRotation();
    }

    public void reload() {
        lines.clear();
        lines.addAll(plugin.getConfig().getStringList("motd.lines"));
        if (lines.isEmpty()) {
            lines.add("&6&lJSMP &8» &fSurvival, your way.");
            lines.add("&eWelcome to &6&lJSMP &8| &fHave fun and play fair.");
            lines.add("&bBuild. Trade. Survive. &8» &6JSMP");
            plugin.getConfig().set("motd.lines", lines);
        }
        intervalSeconds = Math.max(5, Math.min(3600, plugin.getConfig().getInt("motd.interval-seconds", 15)));
        currentIndex = Math.floorMod(currentIndex, lines.size());
        plugin.getConfig().set("motd.interval-seconds", intervalSeconds);
        plugin.saveConfig();
        startRotation();
        applyCurrent();
    }

    private void startRotation() {
        if (rotationTask != null) rotationTask.cancel();
        rotationTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (maintenanceEnabled.getAsBoolean() || lines.size() < 2) return;
            currentIndex = (currentIndex + 1) % lines.size();
            applyCurrent();
        }, intervalSeconds * 20L, intervalSeconds * 20L);
    }

    private String formatted(String value) {
        return ChatColor.translateAlternateColorCodes('&', value);
    }

    public void applyCurrent() {
        if (maintenanceEnabled.getAsBoolean()) {
            Bukkit.setMotd(formatted("&6Server under maintenance"));
            return;
        }
        if (!lines.isEmpty()) Bukkit.setMotd(formatted(lines.get(currentIndex)));
    }

    @EventHandler
    public void onServerPing(ServerListPingEvent event) {
        if (maintenanceEnabled.getAsBoolean()) {
            event.setMotd(formatted("&6Server under maintenance"));
        } else if (!lines.isEmpty()) {
            event.setMotd(formatted(lines.get(currentIndex)));
        }
    }

    private boolean admin(CommandSender sender) {
        if (sender.hasPermission("jsmp.motd.admin")) return true;
        sender.sendMessage(formatted("&6&lJSMP &8» &cYou do not have permission."));
        return false;
    }

    private void message(CommandSender sender, String text) {
        sender.sendMessage(formatted("&6&lJSMP &8» &r" + text));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!admin(sender)) return true;
        if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
            message(sender, "&eMOTD rotation (&f" + intervalSeconds + "&e seconds):");
            for (int i = 0; i < lines.size(); i++) {
                message(sender, "&7" + (i + 1) + ". &f" + lines.get(i));
            }
            message(sender, "&7Use /motd add, set, remove or interval to edit it.");
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "add" -> {
                if (args.length < 2) {
                    message(sender, "&cUsage: /motd add <text>");
                    return true;
                }
                lines.add(String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)));
                saveLines();
                message(sender, "&aAdded MOTD line. Rotation now has &f" + lines.size() + "&a lines.");
                return true;
            }
            case "set" -> {
                if (args.length < 3) {
                    message(sender, "&cUsage: /motd set <number> <text>");
                    return true;
                }
                int index = parseIndex(sender, args[1]);
                if (index < 0) return true;
                lines.set(index, String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)));
                saveLines();
                message(sender, "&aUpdated MOTD line &f" + (index + 1) + "&a.");
                return true;
            }
            case "remove" -> {
                if (args.length != 2) {
                    message(sender, "&cUsage: /motd remove <number>");
                    return true;
                }
                int index = parseIndex(sender, args[1]);
                if (index < 0) return true;
                if (lines.size() == 1) {
                    message(sender, "&cYou must keep at least one MOTD line.");
                    return true;
                }
                lines.remove(index);
                currentIndex = Math.floorMod(currentIndex, lines.size());
                saveLines();
                message(sender, "&aRemoved MOTD line &f" + (index + 1) + "&a.");
                return true;
            }
            case "interval" -> {
                if (args.length != 2) {
                    message(sender, "&cUsage: /motd interval <seconds>");
                    return true;
                }
                try {
                    int seconds = Integer.parseInt(args[1]);
                    if (seconds < 5 || seconds > 3600) throw new NumberFormatException();
                    intervalSeconds = seconds;
                    plugin.getConfig().set("motd.interval-seconds", intervalSeconds);
                    plugin.saveConfig();
                    startRotation();
                    message(sender, "&aMOTD rotation interval set to &f" + intervalSeconds + " seconds&a.");
                } catch (NumberFormatException exception) {
                    message(sender, "&cEnter a whole number between 5 and 3600.");
                }
                return true;
            }
            case "next" -> {
                currentIndex = (currentIndex + 1) % lines.size();
                applyCurrent();
                message(sender, "&aShowing MOTD line &f" + (currentIndex + 1) + "&a.");
                return true;
            }
            default -> {
                message(sender, "&cUsage: /motd <list|add|set|remove|interval|next>");
                return true;
            }
        }
    }

    private int parseIndex(CommandSender sender, String value) {
        try {
            int index = Integer.parseInt(value) - 1;
            if (index < 0 || index >= lines.size()) {
                message(sender, "&cThat MOTD number does not exist.");
                return -1;
            }
            return index;
        } catch (NumberFormatException exception) {
            message(sender, "&cEnter a valid MOTD number.");
            return -1;
        }
    }

    private void saveLines() {
        plugin.getConfig().set("motd.lines", new ArrayList<>(lines));
        plugin.saveConfig();
        currentIndex = Math.floorMod(currentIndex, lines.size());
        applyCurrent();
    }
}
