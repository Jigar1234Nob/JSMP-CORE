package me.jsmp.core;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public final class TeamManager implements Listener, CommandExecutor, TabCompleter {
    private static final long INVITE_LIFETIME_MS = TimeUnit.MINUTES.toMillis(5);

    private final JavaPlugin plugin;
    private final File file;
    private FileConfiguration data;
    private final Map<String, Team> teams = new LinkedHashMap<>();
    private final Map<UUID, String> membership = new HashMap<>();
    private final Map<UUID, Invite> invitations = new HashMap<>();
    private final Set<UUID> teamChat = ConcurrentHashMap.newKeySet();

    private static final class Team {
        private final String name;
        private UUID owner;
        private String type;
        private final LinkedHashSet<UUID> members = new LinkedHashSet<>();

        private Team(String name, UUID owner, String type) {
            this.name = name;
            this.owner = owner;
            this.type = type;
            this.members.add(owner);
        }
    }

    private record Invite(String teamKey, long expiresAt) {}

    public TeamManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "teams.yml");
        load();
        if (plugin.getCommand("team") != null) {
            plugin.getCommand("team").setExecutor(this);
            plugin.getCommand("team").setTabCompleter(this);
        } else {
            plugin.getLogger().severe("The /team command is missing from plugin.yml.");
        }
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    private void load() {
        if (!file.exists()) {
            try {
                if (!file.getParentFile().exists() && !file.getParentFile().mkdirs()) {
                    throw new IOException("Could not create plugin data directory.");
                }
                if (!file.createNewFile()) throw new IOException("Could not create teams.yml.");
            } catch (IOException exception) {
                plugin.getLogger().severe("Could not create teams.yml: " + exception.getMessage());
            }
        }
        data = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = data.getConfigurationSection("teams");
        if (section == null) return;

        for (String key : section.getKeys(false)) {
            String path = "teams." + key;
            String rawOwner = data.getString(path + ".owner", "");
            UUID owner;
            try {
                owner = UUID.fromString(rawOwner);
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("Skipping team '" + key + "' because its owner UUID is invalid.");
                continue;
            }

            Team team = new Team(data.getString(path + ".name", key), owner,
                    data.getString(path + ".type", "invite").equalsIgnoreCase("open") ? "open" : "invite");
            for (String rawMember : data.getStringList(path + ".members")) {
                try {
                    team.members.add(UUID.fromString(rawMember));
                } catch (IllegalArgumentException ignored) {
                    plugin.getLogger().warning("Skipping an invalid member UUID in team '" + key + "'.");
                }
            }
            team.members.add(owner);
            String normalizedKey = normalize(team.name);
            if (teams.containsKey(normalizedKey)) continue;
            teams.put(normalizedKey, team);
            for (UUID member : new ArrayList<>(team.members)) {
                String existing = membership.putIfAbsent(member, normalizedKey);
                if (existing != null) {
                    team.members.remove(member);
                    plugin.getLogger().warning("A player was listed in multiple teams; keeping their first team.");
                }
            }
        }
    }

    private void save() {
        data.set("teams", null);
        for (Map.Entry<String, Team> entry : teams.entrySet()) {
            Team team = entry.getValue();
            String path = "teams." + entry.getKey();
            data.set(path + ".name", team.name);
            data.set(path + ".owner", team.owner.toString());
            data.set(path + ".type", team.type);
            data.set(path + ".members", team.members.stream().map(UUID::toString).toList());
        }
        try {
            data.save(file);
        } catch (IOException exception) {
            plugin.getLogger().severe("Could not save teams.yml: " + exception.getMessage());
        }
    }

    private String normalize(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private String colour(String message) {
        return ChatColor.translateAlternateColorCodes('&', "&6&lJSMP &8» &r" + message);
    }

    private void send(CommandSender sender, String message) {
        sender.sendMessage(colour(message));
    }

    private Team teamOf(UUID playerId) {
        String key = membership.get(playerId);
        return key == null ? null : teams.get(key);
    }

    private boolean isOwner(Player player, Team team) {
        return team.owner.equals(player.getUniqueId());
    }

    private String displayName(UUID uuid) {
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) return online.getName();
        OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
        return offline.getName() == null ? uuid.toString().substring(0, 8) : offline.getName();
    }

    private void notifyTeam(Team team, String message) {
        for (UUID member : team.members) {
            Player online = Bukkit.getPlayer(member);
            if (online != null) send(online, message);
        }
    }

    private void removeMember(Team team, UUID member) {
        team.members.remove(member);
        membership.remove(member);
        teamChat.remove(member);
    }

    private void disband(Team team) {
        for (UUID member : new ArrayList<>(team.members)) {
            membership.remove(member);
            teamChat.remove(member);
            Player online = Bukkit.getPlayer(member);
            if (online != null) send(online, "&cYour team &f" + team.name + " &chas been disbanded.");
        }
        teams.remove(normalize(team.name));
        invitations.entrySet().removeIf(entry -> entry.getValue().teamKey().equals(normalize(team.name)));
        save();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            send(sender, "&cOnly players can use team commands.");
            return true;
        }
        if (!player.hasPermission("jsmp.team")) {
            send(player, "&cYou do not have permission.");
            return true;
        }
        if (args.length == 0) {
            send(player, "&e/team create <name> &7- Create a team");
            send(player, "&e/team invite <player> &7- Invite a player");
            send(player, "&e/team accept &7- Accept an invitation");
            send(player, "&e/team join <team> &7- Join an open team");
            send(player, "&e/team type <open|invite> &7- Change who can join");
            send(player, "&e/team chat &7- Toggle team chat");
            send(player, "&e/team leave &7- Leave your team");
            send(player, "&e/team disband &7- Disband your team");
            send(player, "&e/team info &7- View your team");
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        Team current = teamOf(player.getUniqueId());
        switch (sub) {
            case "create" -> {
                if (args.length != 2 || !args[1].matches("[A-Za-z0-9_]{3,16}")) {
                    send(player, "&cUsage: /team create <name> &7(3-16 letters, numbers or underscores)");
                    return true;
                }
                if (current != null) {
                    send(player, "&cYou are already in a team. Leave it first.");
                    return true;
                }
                String key = normalize(args[1]);
                if (teams.containsKey(key)) {
                    send(player, "&cA team with that name already exists.");
                    return true;
                }
                Team created = new Team(args[1], player.getUniqueId(), "invite");
                teams.put(key, created);
                membership.put(player.getUniqueId(), key);
                save();
                send(player, "&aCreated team &f" + created.name + "&a. Use &f/team invite <player> &ato recruit members.");
                return true;
            }
            case "invite" -> {
                if (args.length != 2) {
                    send(player, "&cUsage: /team invite <player>");
                    return true;
                }
                if (current == null || !isOwner(player, current)) {
                    send(player, "&cOnly your team's owner can invite players.");
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    send(player, "&cThat player must be online.");
                    return true;
                }
                if (target.equals(player)) {
                    send(player, "&cYou are already in your own team.");
                    return true;
                }
                if (teamOf(target.getUniqueId()) != null) {
                    send(player, "&cThat player is already in a team.");
                    return true;
                }
                invitations.put(target.getUniqueId(), new Invite(normalize(current.name),
                        System.currentTimeMillis() + INVITE_LIFETIME_MS));
                send(player, "&aInvited &f" + target.getName() + " &ato your team.");
                send(target, "&e" + player.getName() + " &finvited you to team &6" + current.name
                        + "&e. Use &f/team accept &eto join. Invitation expires in 5 minutes.");
                return true;
            }
            case "accept" -> {
                if (args.length != 1) {
                    send(player, "&cUsage: /team accept");
                    return true;
                }
                if (current != null) {
                    send(player, "&cYou are already in a team.");
                    return true;
                }
                Invite invite = invitations.remove(player.getUniqueId());
                if (invite == null || invite.expiresAt() < System.currentTimeMillis()) {
                    send(player, "&cYou do not have a valid team invitation.");
                    return true;
                }
                Team invitedTeam = teams.get(invite.teamKey());
                if (invitedTeam == null || !invitedTeam.type.equals("invite")) {
                    send(player, "&cThat invitation is no longer valid.");
                    return true;
                }
                invitedTeam.members.add(player.getUniqueId());
                membership.put(player.getUniqueId(), invite.teamKey());
                save();
                notifyTeam(invitedTeam, "&a" + player.getName() + " joined the team.");
                return true;
            }
            case "join" -> {
                if (args.length != 2) {
                    send(player, "&cUsage: /team join <team>");
                    return true;
                }
                if (current != null) {
                    send(player, "&cLeave your current team first.");
                    return true;
                }
                String key = normalize(args[1]);
                Team target = teams.get(key);
                if (target == null) {
                    send(player, "&cThat team does not exist.");
                    return true;
                }
                if (!target.type.equals("open")) {
                    send(player, "&cThat team is invite-only.");
                    return true;
                }
                target.members.add(player.getUniqueId());
                membership.put(player.getUniqueId(), key);
                save();
                notifyTeam(target, "&a" + player.getName() + " joined the team.");
                return true;
            }
            case "type" -> {
                if (args.length != 2 || !(args[1].equalsIgnoreCase("open") || args[1].equalsIgnoreCase("invite"))) {
                    send(player, "&cUsage: /team type <open|invite>");
                    return true;
                }
                if (current == null || !isOwner(player, current)) {
                    send(player, "&cOnly your team's owner can change its join type.");
                    return true;
                }
                current.type = args[1].toLowerCase(Locale.ROOT);
                if (current.type.equals("open")) {
                    invitations.entrySet().removeIf(entry -> entry.getValue().teamKey().equals(normalize(current.name)));
                }
                save();
                notifyTeam(current, "&eTeam join type changed to &f" + current.type + "&e.");
                return true;
            }
            case "chat" -> {
                if (current == null) {
                    send(player, "&cYou are not in a team.");
                    return true;
                }
                if (teamChat.add(player.getUniqueId())) {
                    send(player, "&aTeam chat enabled. Your messages will only go to your team.");
                } else {
                    teamChat.remove(player.getUniqueId());
                    send(player, "&eTeam chat disabled. Your messages will go to global chat.");
                }
                return true;
            }
            case "leave" -> {
                if (args.length != 1) {
                    send(player, "&cUsage: /team leave");
                    return true;
                }
                if (current == null) {
                    send(player, "&cYou are not in a team.");
                    return true;
                }
                if (isOwner(player, current)) {
                    send(player, "&cYou are the owner. Use /team disband to remove the team.");
                    return true;
                }
                String teamName = current.name;
                removeMember(current, player.getUniqueId());
                save();
                notifyTeam(current, "&e" + player.getName() + " left the team.");
                send(player, "&aYou left team &f" + teamName + "&a.");
                return true;
            }
            case "disband" -> {
                if (args.length != 1) {
                    send(player, "&cUsage: /team disband");
                    return true;
                }
                if (current == null || !isOwner(player, current)) {
                    send(player, "&cOnly the team owner can disband the team.");
                    return true;
                }
                disband(current);
                return true;
            }
            case "info" -> {
                if (current == null) {
                    send(player, "&cYou are not in a team.");
                    return true;
                }
                send(player, "&6&lTeam: &f" + current.name + " &8| &eJoin type: &f" + current.type);
                send(player, "&eOwner: &f" + displayName(current.owner));
                send(player, "&eMembers (" + current.members.size() + "): &f"
                        + current.members.stream().map(this::displayName).collect(Collectors.joining("&7, &f")));
                return true;
            }
            default -> {
                send(player, "&cUnknown subcommand. Use /team for help.");
                return true;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onTeamChat(AsyncChatEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if (!teamChat.contains(uuid)) return;
        event.setCancelled(true);
        Component chatMessage = Component.text("[Team] " + event.getPlayer().getName() + ": ")
                .append(event.message());
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!teamChat.contains(uuid)) return;
            Team team = teamOf(uuid);
            if (team == null) {
                teamChat.remove(uuid);
                return;
            }
            for (UUID member : new ArrayList<>(team.members)) {
                Player online = Bukkit.getPlayer(member);
                if (online != null) online.sendMessage(chatMessage);
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        teamChat.remove(event.getPlayer().getUniqueId());
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("create", "invite", "accept", "join", "type", "chat", "leave", "disband", "info")
                    .stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("type")) return List.of("open", "invite");
        if (args.length == 2 && args[0].equalsIgnoreCase("invite")) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("join")) {
            return teams.values().stream().filter(team -> team.type.equals("open")).map(team -> team.name)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        return Collections.emptyList();
    }
}
