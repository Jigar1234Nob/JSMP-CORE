package me.jsmp.core;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.entity.LivingEntity;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.Location;
import org.bukkit.HeightMap;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import net.kyori.adventure.text.Component;
import org.bukkit.block.Block;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.server.ServerListPingEvent;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;
import io.papermc.paper.event.player.PlayerInventorySlotChangeEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Locale;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public final class JSMPCore extends JavaPlugin implements Listener, CommandExecutor {

    private File worthFile;
    private File playersFile;
    private File economyDatabaseFile;
    private Connection economyDatabase;
    private FileConfiguration worth;
    private FileConfiguration playerData;
    private final Map<UUID, Double> balances = new LinkedHashMap<>();
    private final Map<UUID, String> playerNames = new LinkedHashMap<>();
    private final Map<UUID, Map<String, Location>> homes = new LinkedHashMap<>();
    private final Set<UUID> vanishedPlayers = new HashSet<>();
    private final Set<UUID> godPlayers = new HashSet<>();
    private final Map<UUID, AfkSession> afkPlayers = new HashMap<>();
    private final Map<UUID, BukkitTask> dailyTasks = new HashMap<>();
    private final Map<UUID, Integer> dailyUnsavedSeconds = new HashMap<>();
    private final Map<UUID, Boolean> previousInvulnerability = new HashMap<>();
    private final Map<UUID, Integer> antiCheatFlags = new HashMap<>();
    private final Map<UUID, Long> lastAntiCheatFlag = new HashMap<>();
    private final Map<UUID, Long> breakWindowStart = new HashMap<>();
    private final Map<UUID, Integer> breakWindowCount = new HashMap<>();
    private boolean maintenanceEnabled;
    private String originalMotd;
    private NamespacedKey worthLoreKey;

    private static final class AfkSession {
        private final BossBar bossBar;
        private final BukkitTask task;
        private int secondsRemaining = 60;

        private AfkSession(BossBar bossBar, BukkitTask task) {
            this.bossBar = bossBar;
            this.task = task;
        }
    }

    private enum MenuType { CATEGORIES, SHOP_ITEMS, SELL, DAILY }

    private static final class MenuHolder implements InventoryHolder {
        private final MenuType type;
        private final ShopCategory category;
        private final int page;
        private Inventory inventory;

        private MenuHolder(MenuType type, ShopCategory category, int page) {
            this.type = type;
            this.category = category;
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private enum ShopCategory {
        BLOCKS("Blocks", Material.BRICKS, "Building blocks"),
        GEMS("Gems", Material.DIAMOND, "Valuable minerals and gems"),
        FARMING("Farming", Material.WHEAT, "Crops, seeds and farming supplies"),
        FOOD("Food", Material.COOKED_BEEF, "Edible items"),
        NETHER("Nether", Material.NETHERRACK, "Nether blocks and materials"),
        REDSTONE("Redstone Lab", Material.REDSTONE, "Redstone components and machines"),
        WORKSTATIONS("Workstations", Material.CRAFTING_TABLE, "Crafting and utility stations");

        final String label;
        final Material icon;
        final String description;

        ShopCategory(String label, Material icon, String description) {
            this.label = label;
            this.icon = icon;
            this.description = description;
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        worthFile = new File(getDataFolder(), "worth.yml");
        playersFile = new File(getDataFolder(), "players.yml");
        if (!worthFile.exists()) saveResource("worth.yml", false);
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            getLogger().severe("Could not create plugin data directory.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        worth = YamlConfiguration.loadConfiguration(worthFile);
        playerData = YamlConfiguration.loadConfiguration(playersFile);
        economyDatabaseFile = new File(getDataFolder(), "economy.db");
        openEconomyDatabase();
        if (economyDatabase == null) return;
        worthLoreKey = new NamespacedKey(this, "worth_lore");
        loadBalances();
        loadHomes();
        generateWorthCatalogue();
        originalMotd = Bukkit.getMotd();
        maintenanceEnabled = getConfig().getBoolean("maintenance.enabled", false);
        applyMaintenanceMotd();

        getServer().getPluginManager().registerEvents(this, this);
        for (String command : List.of("shop", "sell", "balance", "baltop", "pay", "money", "vanish", "god", "ungod", "rtp", "maintenance", "sethome", "home", "jsmp", "afk", "daily")) {
            if (getCommand(command) != null) getCommand(command).setExecutor(this);
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            remember(player);
            decorateInventory(player);
            startDailyTracking(player);
        }
        getLogger().info("JSMPCore enabled. Loaded " + worth.getConfigurationSection("items").getKeys(false).size()
                + " item worth entries and " + balances.size() + " player balances.");
    }

    @Override
    public void onDisable() {
        for (UUID uuid : new ArrayList<>(afkPlayers.keySet())) stopAfk(uuid, false, false);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (vanishedPlayers.contains(player.getUniqueId())) {
                for (Player viewer : Bukkit.getOnlinePlayers()) viewer.showPlayer(this, player);
            }
            if (godPlayers.contains(player.getUniqueId())) {
                player.setInvulnerable(previousInvulnerability.getOrDefault(player.getUniqueId(), false));
            }
        }
        saveBalances();
        saveWorth();
        for (UUID uuid : new ArrayList<>(dailyTasks.keySet())) stopDailyTracking(uuid);
        closeEconomyDatabase();
        if (originalMotd != null) Bukkit.setMotd(originalMotd);
    }

    private void openEconomyDatabase() {
        try {
            if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
                throw new IOException("Could not create plugin data directory");
            }
            economyDatabase = DriverManager.getConnection("jdbc:sqlite:" + economyDatabaseFile.getAbsolutePath());
            try (Statement statement = economyDatabase.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA synchronous=FULL");
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS balances (
                            uuid TEXT PRIMARY KEY NOT NULL,
                            player_name TEXT NOT NULL,
                            balance REAL NOT NULL CHECK(balance >= 0)
                        )
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS homes (
                            uuid TEXT NOT NULL,
                            name TEXT NOT NULL,
                            world TEXT NOT NULL,
                            x REAL NOT NULL,
                            y REAL NOT NULL,
                            z REAL NOT NULL,
                            yaw REAL NOT NULL,
                            pitch REAL NOT NULL,
                            PRIMARY KEY (uuid, name)
                        )
                        """);
            }
            getLogger().info("Economy database opened: " + economyDatabaseFile.getName());
        } catch (SQLException | IOException exception) {
            getLogger().severe("Could not open economy.db: " + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private void loadBalances() {
        if (economyDatabase == null) return;
        try {
            boolean databaseHasBalances;
            try (Statement statement = economyDatabase.createStatement();
                 ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM balances")) {
                databaseHasBalances = result.next() && result.getInt(1) > 0;
            }

            if (databaseHasBalances) {
                try (Statement statement = economyDatabase.createStatement();
                     ResultSet result = statement.executeQuery("SELECT uuid, player_name, balance FROM balances")) {
                    while (result.next()) {
                        try {
                            UUID uuid = UUID.fromString(result.getString("uuid"));
                            balances.put(uuid, Math.max(0, result.getDouble("balance")));
                            playerNames.put(uuid, result.getString("player_name"));
                        } catch (IllegalArgumentException ignored) {
                            getLogger().warning("Skipping invalid player UUID in economy.db.");
                        }
                    }
                }
                getLogger().info("Loaded " + balances.size() + " balances from economy.db.");
                return;
            }

            // One-time migration: retain balances from the old players.yml file.
            ConfigurationSection section = playerData.getConfigurationSection("players");
            if (section != null) {
                for (String id : section.getKeys(false)) {
                    try {
                        UUID uuid = UUID.fromString(id);
                        balances.put(uuid, Math.max(0, section.getDouble(id + ".balance", 0)));
                        playerNames.put(uuid, section.getString(id + ".name", "Unknown"));
                    } catch (IllegalArgumentException ignored) {
                        getLogger().warning("Skipping invalid player UUID in players.yml: " + id);
                    }
                }
            }
            saveBalances();
            getLogger().info("Imported " + balances.size() + " existing balances into economy.db.");
        } catch (SQLException exception) {
            getLogger().severe("Could not load balances from economy.db: " + exception.getMessage());
        }
    }

    private void saveBalances() {
        if (economyDatabase == null) {
            getLogger().severe("Economy database is unavailable; balances were not saved.");
            return;
        }
        try {
            economyDatabase.setAutoCommit(false);
            try (PreparedStatement statement = economyDatabase.prepareStatement(
                    "INSERT INTO balances (uuid, player_name, balance) VALUES (?, ?, ?) "
                            + "ON CONFLICT(uuid) DO UPDATE SET player_name=excluded.player_name, balance=excluded.balance")) {
                for (UUID uuid : balances.keySet()) {
                    statement.setString(1, uuid.toString());
                    statement.setString(2, playerNames.getOrDefault(uuid, "Unknown"));
                    statement.setDouble(3, roundMoney(balances.getOrDefault(uuid, 0.0)));
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            economyDatabase.commit();
            economyDatabase.setAutoCommit(true);

            // Keep the old YAML balance mirror for compatibility, without touching homes.
            for (UUID uuid : balances.keySet()) {
                playerData.set("players." + uuid + ".balance", roundMoney(balances.getOrDefault(uuid, 0.0)));
                playerData.set("players." + uuid + ".name", playerNames.getOrDefault(uuid, "Unknown"));
            }
            playerData.save(playersFile);
        } catch (SQLException | IOException exception) {
            try {
                economyDatabase.rollback();
                economyDatabase.setAutoCommit(true);
            } catch (SQLException rollbackException) {
                getLogger().severe("Could not roll back economy database transaction: " + rollbackException.getMessage());
            }
            getLogger().severe("Could not save player balances: " + exception.getMessage());
        }
    }

    private void closeEconomyDatabase() {
        if (economyDatabase == null) return;
        try {
            economyDatabase.close();
            economyDatabase = null;
        } catch (SQLException exception) {
            getLogger().severe("Could not close economy.db cleanly: " + exception.getMessage());
        }
    }

    private void generateWorthCatalogue() {
        if (!worth.isConfigurationSection("items")) worth.createSection("items");
        int added = 0;
        boolean removed = false;
        boolean applyLowValuePriceUpdate = !getConfig().getBoolean("economy.low-value-price-migration-v2", false);
        boolean applyBuySellPriceMigration = !getConfig().getBoolean("economy.buy-sell-price-migration-v1", false);
        double minimumPrice = 1001.0;
        ConfigurationSection existing = worth.getConfigurationSection("items");
        if (existing != null) {
            for (String key : new ArrayList<>(existing.getKeys(false))) {
                Material material = Material.matchMaterial(key);
                if (!isShopCatalogueItem(material)) {
                    worth.set("items." + key, null);
                    removed = true;
                }
            }
        }
        for (Material material : Material.values()) {
            if (!isShopCatalogueItem(material)) continue;
            String path = "items." + material.name();
            if (isRestrictedShopItem(material)) {
                worth.set(path + ".buy-price", minimumPrice);
                worth.set(path + ".sell-price", 0.0);
                worth.set(path + ".sellable", false);
                worth.set(path + ".price", null);
                continue;
            }

            double oldPrice = worth.getDouble(path + ".price", -1.0);
            double buyPrice;
            if (worth.contains(path + ".buy-price")) {
                buyPrice = worth.getDouble(path + ".buy-price");
            } else if (oldPrice >= 0) {
                buyPrice = oldPrice;
            } else {
                buyPrice = defaultWorth(material) <= 5.0
                        ? lowValuePrice(material) : Math.max(minimumPrice, defaultWorth(material) * 1000.0);
                added++;
            }

            if (applyLowValuePriceUpdate && defaultWorth(material) <= 5.0) {
                buyPrice = lowValuePrice(material);
            }
            buyPrice = Math.max(0.01, buyPrice);

            double sellPrice;
            if (worth.contains(path + ".sell-price")) {
                sellPrice = worth.getDouble(path + ".sell-price");
            } else if (applyBuySellPriceMigration) {
                sellPrice = buyPrice * Math.max(0.0, getConfig().getDouble("economy.default-sell-rate", 0.5));
            } else {
                sellPrice = buyPrice * 0.5;
            }

            worth.set(path + ".buy-price", roundMoney(buyPrice));
            worth.set(path + ".sell-price", roundMoney(Math.max(0.0, Math.min(sellPrice, buyPrice))));
            worth.set(path + ".price", null);
            if (!worth.contains(path + ".sellable")) worth.set(path + ".sellable", true);
        }

        if (applyLowValuePriceUpdate) {
            getConfig().set("economy.low-value-price-migration-v2", true);
        }
        if (applyBuySellPriceMigration) {
            getConfig().set("economy.buy-sell-price-migration-v1", true);
        }
        if (applyLowValuePriceUpdate || applyBuySellPriceMigration) saveConfig();
        if (added > 0 || removed || applyLowValuePriceUpdate || applyBuySellPriceMigration || !worthFile.exists()) saveWorth();
        getLogger().info("Worth catalogue ready with separate buy and sell prices.");
    }

    private double lowValuePrice(Material material) {
        // Everyday blocks, basic resources and ordinary food should be affordable.
        return defaultWorth(material) <= 2.0 ? 40.0 : 60.0;
    }

    private double defaultWorth(Material material) {
        String n = material.name();
        if (List.of("BEDROCK", "BARRIER", "COMMAND_BLOCK", "CHAIN_COMMAND_BLOCK", "REPEATING_COMMAND_BLOCK",
                "STRUCTURE_BLOCK", "STRUCTURE_VOID", "JIGSAW", "DEBUG_STICK", "KNOWLEDGE_BOOK",
                "LIGHT", "END_PORTAL_FRAME", "SPAWNER").contains(n)) return 0.0;
        if (n.contains("NETHERITE")) return 0.0;
        if (n.contains("ANCIENT_DEBRIS")) return 180.0;
        if (n.equals("DIAMOND") || n.equals("DIAMOND_BLOCK")) return n.endsWith("_BLOCK") ? 900.0 : 90.0;
        if (n.equals("EMERALD") || n.equals("EMERALD_BLOCK")) return n.endsWith("_BLOCK") ? 600.0 : 60.0;
        if (n.contains("AMETHYST")) return 8.0;
        if (n.contains("GOLD")) return n.endsWith("_INGOT") ? 14.0 : 3.0;
        if (n.contains("IRON")) return n.endsWith("_INGOT") ? 8.0 : 2.0;
        if (n.contains("COPPER")) return n.endsWith("_INGOT") ? 4.0 : 1.0;
        if (n.contains("COAL")) return 3.0;
        if (n.contains("REDSTONE")) return 2.5;
        if (n.contains("LAPIS")) return 4.0;
        if (n.contains("QUARTZ")) return 5.0;
        if (n.contains("BOOK") || n.contains("ENCHANT")) return 12.0;
        if (n.contains("TOTEM")) return 100.0;
        if (n.contains("ELYTRA")) return 300.0;
        if (n.contains("SHULKER")) return 45.0;
        if (n.endsWith("_SWORD") || n.endsWith("_PICKAXE") || n.endsWith("_AXE")
                || n.endsWith("_SHOVEL") || n.endsWith("_HOE")) return 12.0;
        if (n.endsWith("_HELMET") || n.endsWith("_CHESTPLATE") || n.endsWith("_LEGGINGS")
                || n.endsWith("_BOOTS")) return 15.0;
        if (material.isEdible()) return 2.0;
        if (n.endsWith("_LOG") || n.endsWith("_WOOD") || n.endsWith("_PLANKS")) return 1.5;
        if (n.contains("POTION")) return 8.0;
        if (material.isBlock()) return 1.0;
        return 1.0;
    }

    private void saveWorth() {
        try {
            worth.save(worthFile);
        } catch (IOException exception) {
            getLogger().severe("Could not save worth.yml: " + exception.getMessage());
        }
    }

    private void remember(Player player) {
        UUID uuid = player.getUniqueId();
        playerNames.put(uuid, player.getName());
        if (!balances.containsKey(uuid)) {
            balances.put(uuid, Math.max(0, getConfig().getDouble("currency.starting-balance", 100.0)));
        }
        saveBalances();
    }

    private double balance(UUID uuid) {
        return balances.getOrDefault(uuid, 0.0);
    }

    private void setBalance(UUID uuid, double amount) {
        balances.put(uuid, roundMoney(Math.max(0, amount)));
        saveBalances();
    }

    private double roundMoney(double amount) {
        return Math.round((amount + 1.0e-9) * 100.0) / 100.0;
    }

    private String money(double amount) {
        String symbol = getConfig().getString("currency.symbol", "$");
        return symbol + new DecimalFormat("#,##0.00").format(roundMoney(amount));
    }

    private String prefix() {
        return color(getConfig().getString("messages.prefix", "&8[&6JSMP&8] &r"));
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }

    private void msg(CommandSender sender, String text) {
        sender.sendMessage(prefix() + color(text));
    }

    private UUID findPlayer(String name) {
        for (Map.Entry<UUID, String> entry : playerNames.entrySet()) {
            if (entry.getValue().equalsIgnoreCase(name)) return entry.getKey();
        }
        Player online = Bukkit.getPlayerExact(name);
        return online == null ? null : online.getUniqueId();
    }

    private String displayName(UUID uuid) {
        return playerNames.getOrDefault(uuid, uuid.toString().substring(0, 8));
    }

    private boolean isRestrictedShopItem(Material material) {
        String n = material.name();
        if (n.contains("NETHERITE")) return true;
        return n.equals("ELYTRA") || n.equals("DRAGON_EGG") || n.equals("DRAGON_HEAD")
                || n.equals("DRAGON_WALL_HEAD") || n.equals("NETHER_STAR") || n.equals("BEACON")
                || n.equals("TOTEM_OF_UNDYING") || n.equals("SHULKER_SHELL") || n.startsWith("SHULKER_BOX")
                || n.equals("END_CRYSTAL") || n.equals("SPAWNER") || n.equals("ENCHANTED_GOLDEN_APPLE")
                || n.equals("TRIDENT") || n.equals("MACE") || n.equals("WITHER_SKELETON_SKULL")
                || n.equals("WITHER_SKELETON_WALL_SKULL");
    }

    private boolean isShopCatalogueItem(Material material) {
        if (material == null || !material.isItem() || material.isAir() || isRestrictedShopItem(material)) return false;
        if (material.isBlock()) return true;

        // Keep useful resources, farming supplies and food. Gear, weapons, armour,
        // potions and novelty items are intentionally not part of this shop.
        return List.of(
                "DIAMOND", "EMERALD", "RAW_IRON", "RAW_GOLD", "RAW_COPPER",
                "IRON_INGOT", "GOLD_INGOT", "COPPER_INGOT", "COAL", "CHARCOAL",
                "REDSTONE", "LAPIS_LAZULI", "QUARTZ", "AMETHYST_SHARD",
                "WHEAT", "WHEAT_SEEDS", "CARROT", "POTATO", "BEETROOT",
                "BEETROOT_SEEDS", "MELON_SLICE", "MELON_SEEDS", "PUMPKIN_SEEDS",
                "SUGAR_CANE", "BAMBOO", "COCOA_BEANS",
                "APPLE", "BREAD", "COOKED_BEEF", "COOKED_PORKCHOP",
                "COOKED_CHICKEN", "COOKED_MUTTON", "COOKED_RABBIT",
                "BAKED_POTATO", "GOLDEN_CARROT"
        ).contains(material.name());
    }

    private double buyPrice(Material material) {
        if (isRestrictedShopItem(material)) return 0;
        String path = "items." + material.name();
        if (!worth.getBoolean(path + ".sellable", false)) return 0;
        return roundMoney(Math.max(0, worth.getDouble(path + ".buy-price", 0)));
    }

    private double sellPrice(Material material) {
        if (isRestrictedShopItem(material)) return 0;
        String path = "items." + material.name();
        if (!worth.getBoolean(path + ".sellable", false)) return 0;
        return roundMoney(Math.max(0, worth.getDouble(path + ".sell-price", 0)));
    }

    private double worth(Material material) {
        return sellPrice(material);
    }

    private boolean isSellable(ItemStack item) {
        return item != null && !item.getType().isAir() && sellPrice(item.getType()) > 0;
    }

    private ShopCategory categoryFor(Material material) {
        String n = material.name();
        if (n.contains("REDSTONE") || List.of("REPEATER", "COMPARATOR", "PISTON", "STICKY_PISTON",
                "OBSERVER", "HOPPER", "DISPENSER", "DROPPER", "TARGET", "LEVER", "TRIPWIRE_HOOK",
                "DAYLIGHT_DETECTOR", "NOTE_BLOCK", "SCULK_SENSOR", "CALIBRATED_SCULK_SENSOR").contains(n)
                || n.endsWith("_PRESSURE_PLATE")) return ShopCategory.REDSTONE;
        if (List.of("DIAMOND", "EMERALD", "RAW_IRON", "RAW_GOLD", "RAW_COPPER",
                "IRON_INGOT", "GOLD_INGOT", "COPPER_INGOT", "COAL", "CHARCOAL",
                "LAPIS_LAZULI", "QUARTZ", "AMETHYST_SHARD").contains(n)
                || n.contains("DIAMOND_ORE") || n.contains("EMERALD_ORE")) return ShopCategory.GEMS;
        if (n.contains("NETHER") || n.contains("CRIMSON") || n.contains("WARPED")
                || n.contains("BASALT") || n.contains("BLACKSTONE") || n.contains("SOUL_")
                || n.contains("GLOWSTONE") || n.contains("MAGMA")) return ShopCategory.NETHER;
        if (n.contains("SEEDS") || n.contains("SAPLING") || n.contains("WHEAT") || n.contains("CARROT")
                || n.contains("POTATO") || n.contains("BEETROOT") || n.contains("MELON") || n.contains("PUMPKIN")
                || n.contains("SUGAR_CANE") || n.contains("BAMBOO") || n.contains("COCOA")
                || n.contains("COMPOST") || n.contains("BONE_MEAL") || n.contains("HAY_BLOCK")
                || n.contains("MOSS") || n.contains("FLOWER") || n.contains("TULIP") || n.contains("LILY"))
            return ShopCategory.FARMING;
        if (material.isEdible()) return ShopCategory.FOOD;
        if (List.of("CRAFTING_TABLE", "FURNACE", "BLAST_FURNACE", "SMOKER", "ANVIL", "CHIPPED_ANVIL",
                "DAMAGED_ANVIL", "ENCHANTING_TABLE", "BREWING_STAND", "SMITHING_TABLE", "GRINDSTONE",
                "STONECUTTER", "CARTOGRAPHY_TABLE", "FLETCHING_TABLE", "LOOM", "LECTERN", "BARREL",
                "COMPOSTER", "ENDER_CHEST").contains(n)) return ShopCategory.WORKSTATIONS;
        return ShopCategory.BLOCKS;
    }

    private ItemStack icon(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(name));
            if (lore != null) meta.setLore(lore.stream().map(this::color).collect(Collectors.toList()));
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private void decorateWorth(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) return;
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return;
        List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        lore.removeIf(line -> {
            String stripped = ChatColor.stripColor(line);
            return stripped != null && (stripped.startsWith("Worth: ")
                    || stripped.startsWith("Buy: ") || stripped.startsWith("Sell: "));
        });
        double buyValue = buyPrice(stack.getType());
        double sellValue = sellPrice(stack.getType());
        if (buyValue > 0 || sellValue > 0) {
            String worthLine = color("&7Buy: &e" + money(buyValue) + " &8| &7Sell: &a" + money(sellValue) + " &8each");
            if (lore.contains(worthLine)) return;
            lore.add(worthLine);
            meta.setLore(lore);
            meta.getPersistentDataContainer().set(worthLoreKey, PersistentDataType.BYTE, (byte) 1);
            stack.setItemMeta(meta);
        } else if (meta.getPersistentDataContainer().has(worthLoreKey, PersistentDataType.BYTE)) {
            meta.setLore(lore.isEmpty() ? null : lore);
            meta.getPersistentDataContainer().remove(worthLoreKey);
            stack.setItemMeta(meta);
        }
    }

    private void decorateInventory(Player player) {
        for (ItemStack item : player.getInventory().getContents()) decorateWorth(item);
        decorateWorth(player.getItemOnCursor());
    }

    private void openCategories(Player player) {
        MenuHolder holder = new MenuHolder(MenuType.CATEGORIES, null, 0);
        Inventory inventory = Bukkit.createInventory(holder, 54, color(getConfig().getString("shop.title", "&8JSMP Shop")));
        holder.inventory = inventory;
        int slot = 10;
        for (ShopCategory category : ShopCategory.values()) {
            inventory.setItem(slot, icon(category.icon, "&6" + category.label,
                    List.of("&7" + category.description, "&eClick to browse")));
            slot++;
            if (slot == 17) slot = 19;
            if (slot == 26) slot = 28;
        }
        inventory.setItem(49, icon(Material.BARRIER, "&cClose", List.of("&7Close this menu")));
        player.openInventory(inventory);
    }

    private void openShopItems(Player player, ShopCategory category, int page) {
        List<Material> items = new ArrayList<>();
        for (Material material : Material.values()) {
            if (!isShopCatalogueItem(material) || categoryFor(material) != category) continue;
            if (buyPrice(material) <= 0) continue;
            items.add(material);
        }
        items.sort(Comparator.comparing(Material::name));
        int pageSize = Math.max(9, Math.min(45, getConfig().getInt("shop.items-per-page", 45)));
        int maxPage = Math.max(0, (items.size() - 1) / pageSize);
        page = Math.max(0, Math.min(page, maxPage));

        MenuHolder holder = new MenuHolder(MenuType.SHOP_ITEMS, category, page);
        Inventory inventory = Bukkit.createInventory(holder, 54,
                color("&8" + category.label + " &7(" + (page + 1) + "/" + (maxPage + 1) + ")"));
        holder.inventory = inventory;

        int start = page * pageSize;
        for (int i = 0; i < pageSize && start + i < items.size(); i++) {
            Material material = items.get(start + i);
            ItemStack stack = new ItemStack(material);
            decorateWorth(stack);
            ItemMeta meta = stack.getItemMeta();
            if (meta != null) {
                List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
                lore.add(color("&7Buy: &e" + money(buyPrice(material)) + " &8each"));
                lore.add(color("&7Sell: &a" + money(sellPrice(material)) + " &8each"));
                lore.add(color("&eClick to buy 1"));
                lore.add(color("&7Shift-click to buy a stack"));
                meta.setLore(lore);
                stack.setItemMeta(meta);
            }
            inventory.setItem(i, stack);
        }

        inventory.setItem(45, icon(Material.ARROW, "&ePrevious page", List.of("&7Go back one page")));
        inventory.setItem(49, icon(Material.BOOK, "&6Categories", List.of("&7Return to shop categories")));
        inventory.setItem(53, icon(Material.ARROW, "&eNext page", List.of("&7Go forward one page")));
        player.openInventory(inventory);
    }

    private void openSell(Player player) {
        MenuHolder holder = new MenuHolder(MenuType.SELL, null, 0);
        Inventory inventory = Bukkit.createInventory(holder, 54, color(getConfig().getString("sell.title", "&8Sell Items")));
        holder.inventory = inventory;
        inventory.setItem(49, icon(Material.EMERALD, "&aSell all items",
                List.of("&7Sell every supported item in this menu.", "&eClick to confirm")));
        inventory.setItem(53, icon(Material.BARRIER, "&cClose", List.of("&7Return deposited items")));
        player.openInventory(inventory);
    }

    private void buy(Player player, Material material, int amount) {
        double unitPrice = buyPrice(material);
        if (unitPrice <= 0) {
            msg(player, "&cThis item is not currently available.");
            return;
        }
        amount = Math.max(1, Math.min(amount, material.getMaxStackSize()));
        double total = roundMoney(unitPrice * amount);
        UUID uuid = player.getUniqueId();
        if (balance(uuid) + 0.0001 < total) {
            msg(player, "&cYou need " + money(total) + " but only have " + money(balance(uuid)) + ".");
            return;
        }
        setBalance(uuid, balance(uuid) - total);
        ItemStack bought = new ItemStack(material, amount);
        decorateWorth(bought);
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(bought);
        for (ItemStack extra : overflow.values()) player.getWorld().dropItemNaturally(player.getLocation(), extra);
        msg(player, "&aBought &f" + amount + "x " + pretty(material) + " &afor &e" + money(total) + "&a.");
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.3f);
    }

    private String pretty(Material material) {
        String[] words = material.name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder out = new StringBuilder();
        for (String word : words) {
            if (out.length() > 0) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }

    private void sellContents(Player player, Inventory inventory) {
        double total = 0;
        int soldStacks = 0;
        double multiplier = Math.max(0, getConfig().getDouble("sell.sell-multiplier", 1.0));
        if (multiplier <= 0) {
            msg(player, "&cSelling is currently disabled by the server configuration.");
            return;
        }
        for (int slot = 0; slot < 45; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.getType().isAir()) continue;
            double unit = sellPrice(item.getType());
            if (unit <= 0 || !worth.getBoolean("items." + item.getType().name() + ".sellable", false)) continue;
            total += unit * item.getAmount() * multiplier;
            soldStacks++;
            inventory.setItem(slot, null);
        }
        total = roundMoney(total);
        if (total <= 0) {
            msg(player, "&cThere are no sellable items in the sell menu.");
            return;
        }
        setBalance(player.getUniqueId(), balance(player.getUniqueId()) + total);
        msg(player, "&aSold " + soldStacks + " stack(s) for &e" + money(total) + "&a.");
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
    }

    private boolean addToSellMenu(Inventory inventory, ItemStack source) {
        if (source == null || source.getType().isAir()) return false;
        ItemStack remaining = source.clone();
        for (int slot = 0; slot < 45 && remaining.getAmount() > 0; slot++) {
            ItemStack existing = inventory.getItem(slot);
            if (existing == null || existing.getType().isAir()) {
                int moved = Math.min(remaining.getAmount(), remaining.getMaxStackSize());
                ItemStack placed = remaining.clone();
                placed.setAmount(moved);
                decorateWorth(placed);
                inventory.setItem(slot, placed);
                remaining.setAmount(remaining.getAmount() - moved);
            } else if (existing.isSimilar(remaining) && existing.getAmount() < existing.getMaxStackSize()) {
                int moved = Math.min(remaining.getAmount(), existing.getMaxStackSize() - existing.getAmount());
                existing.setAmount(existing.getAmount() + moved);
                remaining.setAmount(remaining.getAmount() - moved);
            }
        }
        source.setAmount(remaining.getAmount());
        return remaining.getAmount() == 0;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player joining = event.getPlayer();
        remember(joining);
        startDailyTracking(joining);
        if (!joining.hasPlayedBefore() && !playerData.getBoolean("rtp.first-join-complete." + joining.getUniqueId(), false)) {
            Bukkit.getScheduler().runTask(this, () -> {
                if (!joining.isOnline()) return;
                startTeleportCountdown(joining, "a random location", () -> {
                    boolean success = randomTeleport(joining);
                    if (success) {
                        playerData.set("rtp.first-join-complete." + joining.getUniqueId(), true);
                        saveBalances();
                    }
                    return success;
                });
            });
        }
        for (UUID vanishedId : vanishedPlayers) {
            Player vanished = Bukkit.getPlayer(vanishedId);
            if (vanished != null && !vanished.getUniqueId().equals(joining.getUniqueId())) joining.hidePlayer(this, vanished);
        }
        if (vanishedPlayers.contains(joining.getUniqueId())) {
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                if (!viewer.getUniqueId().equals(joining.getUniqueId())) viewer.hidePlayer(this, joining);
            }
        }
        Bukkit.getScheduler().runTask(this, () -> decorateInventory(joining));
    }

    private String dailyPath(UUID uuid) {
        return "daily.players." + uuid;
    }

    private void ensureDailyProgress(UUID uuid) {
        String path = dailyPath(uuid);
        String today = LocalDate.now().toString();
        if (!today.equals(playerData.getString(path + ".progress-date", ""))) {
            playerData.set(path + ".progress-date", today);
            playerData.set(path + ".play-seconds", 0);
        }
    }

    private int dailyStreak(UUID uuid) {
        String lastClaim = playerData.getString(dailyPath(uuid) + ".last-claim", "");
        if (lastClaim.isBlank()) return 0;
        try {
            LocalDate last = LocalDate.parse(lastClaim);
            LocalDate today = LocalDate.now();
            if (last.isBefore(today.minusDays(1))) return 0;
            return Math.max(0, playerData.getInt(dailyPath(uuid) + ".streak", 0));
        } catch (RuntimeException exception) {
            return 0;
        }
    }

    private int dailyPlaySeconds(UUID uuid) {
        ensureDailyProgress(uuid);
        return Math.max(0, playerData.getInt(dailyPath(uuid) + ".play-seconds", 0));
    }

    private int dailyRequiredSeconds(UUID uuid) {
        int nextDay = dailyStreak(uuid) + 1;
        int base = Math.max(1, getConfig().getInt("daily.base-play-minutes", 10));
        int increase = Math.max(0, getConfig().getInt("daily.extra-minutes-per-streak-day", 5));
        int maximum = Math.max(base, getConfig().getInt("daily.maximum-play-minutes", 120));
        return Math.min(maximum, base + (nextDay - 1) * increase) * 60;
    }

    private List<Map<?, ?>> dailyRewards() {
        return getConfig().getMapList("daily.rewards");
    }

    private Object dailyRewardValue(Map<?, ?> reward, String key, Object fallback) {
        Object value = reward.get(key);
        return value == null ? fallback : value;
    }

    private void startDailyTracking(Player player) {
        UUID uuid = player.getUniqueId();
        ensureDailyProgress(uuid);
        if (dailyTasks.containsKey(uuid)) return;
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (!player.isOnline()) {
                stopDailyTracking(uuid);
                return;
            }
            // AFK farming does not count toward the daily active-play requirement.
            if (afkPlayers.containsKey(uuid)) return;
            int seconds = dailyPlaySeconds(uuid) + 1;
            playerData.set(dailyPath(uuid) + ".play-seconds", seconds);
            int unsaved = dailyUnsavedSeconds.getOrDefault(uuid, 0) + 1;
            if (unsaved >= 60) {
                saveDailyData();
                unsaved = 0;
            }
            dailyUnsavedSeconds.put(uuid, unsaved);
        }, 20L, 20L);
        dailyTasks.put(uuid, task);
    }

    private void stopDailyTracking(UUID uuid) {
        BukkitTask task = dailyTasks.remove(uuid);
        if (task != null) task.cancel();
        dailyUnsavedSeconds.remove(uuid);
        saveDailyData();
    }

    private void saveDailyData() {
        try {
            playerData.save(playersFile);
        } catch (IOException exception) {
            getLogger().severe("Could not save daily reward progress: " + exception.getMessage());
        }
    }

    private String dailyTier(int day) {
        if (day <= 5) return "&7COMMON";
        if (day <= 10) return "&aUNCOMMON";
        if (day <= 15) return "&9RARE";
        if (day <= 20) return "&5EPIC";
        if (day <= 25) return "&6LEGENDARY";
        return "&cMYTHIC";
    }

    private void openDaily(Player player) {
        List<Map<?, ?>> rewards = dailyRewards();
        if (rewards.isEmpty()) {
            msg(player, "&cDaily rewards are not configured.");
            return;
        }
        MenuHolder holder = new MenuHolder(MenuType.DAILY, null, 0);
        Inventory inventory = Bukkit.createInventory(holder, 54, color("&8Daily Rewards"));
        holder.inventory = inventory;
        int streak = dailyStreak(player.getUniqueId());
        String today = LocalDate.now().toString();
        boolean claimed = today.equals(playerData.getString(dailyPath(player.getUniqueId()) + ".last-claim", ""));
        int rewardIndex = claimed ? Math.max(0, streak - 1) % rewards.size() : streak % rewards.size();
        int required = dailyRequiredSeconds(player.getUniqueId());
        int played = dailyPlaySeconds(player.getUniqueId());

        for (int i = 0; i < rewards.size() && i < 45; i++) {
            Map<?, ?> reward = rewards.get(i);
            Material material = Material.matchMaterial(String.valueOf(dailyRewardValue(reward, "material", "CHEST")));
            if (material == null || material.isAir()) material = Material.CHEST;
            int amount;
            try { amount = Integer.parseInt(String.valueOf(dailyRewardValue(reward, "amount", 1))); }
            catch (NumberFormatException exception) { amount = 1; }
            amount = Math.max(1, Math.min(material.getMaxStackSize(), amount));
            int displayDay = i + 1;
            String rewardName = String.valueOf(dailyRewardValue(reward, "name", material.name().replace('_', ' ')));
            List<String> lore = new ArrayList<>();
            lore.add("&7Tier: " + dailyTier(displayDay));
            lore.add("&7Reward: &f" + rewardName + " x" + amount);
            if (i == rewardIndex) lore.add(claimed ? "&aClaimed today" : "&eYour next reward");
            else if (claimed && i == (streak % rewards.size())) lore.add("&eTomorrow's reward");
            else if (i < rewardIndex) lore.add("&aPrevious streak reward");
            else lore.add("&8Upcoming reward");
            ItemStack icon = icon(material, (i == rewardIndex ? "&e" : "&f") + "Day " + displayDay, lore);
            inventory.setItem(i, icon);
        }

        int remaining = Math.max(0, required - played);
        inventory.setItem(49, icon(Material.CLOCK, "&6Daily Progress", List.of(
                "&7Streak: &f" + streak + " day(s)",
                "&7Played today: &f" + played / 60 + "m " + played % 60 + "s",
                "&7Required: &f" + required / 60 + " minute(s)",
                "&7Remaining: &e" + remaining / 60 + "m " + remaining % 60 + "s",
                claimed ? "&aToday's reward has been claimed." : "&ePlay to unlock your reward."
        )));
        inventory.setItem(50, icon(claimed ? Material.MINECART : (played >= required ? Material.EMERALD : Material.BARRIER),
                claimed ? "&aAlready Claimed" : "&eClaim Daily Reward",
                List.of(claimed ? "&7Come back tomorrow." : played >= required
                        ? "&aClick to collect your reward." : "&cYou have not played long enough yet.")));
        inventory.setItem(53, icon(Material.BARRIER, "&cClose", List.of("&7Close this menu")));
        player.openInventory(inventory);
    }

    private void claimDaily(Player player) {
        UUID uuid = player.getUniqueId();
        String today = LocalDate.now().toString();
        String path = dailyPath(uuid);
        if (today.equals(playerData.getString(path + ".last-claim", ""))) {
            msg(player, "&eYou have already claimed today's reward.");
            return;
        }
        int required = dailyRequiredSeconds(uuid);
        int played = dailyPlaySeconds(uuid);
        if (played < required) {
            int remaining = required - played;
            msg(player, "&cPlay for another " + remaining / 60 + "m " + remaining % 60 + "s to unlock your daily reward.");
            return;
        }
        List<Map<?, ?>> rewards = dailyRewards();
        if (rewards.isEmpty()) {
            msg(player, "&cDaily rewards are not configured.");
            return;
        }
        int streak = dailyStreak(uuid);
        int rewardIndex = streak % rewards.size();
        Map<?, ?> reward = rewards.get(rewardIndex);
        Material material = Material.matchMaterial(String.valueOf(reward.getOrDefault("material", "")));
        if (material == null || material.isAir()) {
            msg(player, "&cThe configured daily reward is invalid. Please contact an administrator.");
            return;
        }
        int amount;
        try { amount = Integer.parseInt(String.valueOf(reward.getOrDefault("amount", 1))); }
        catch (NumberFormatException exception) { amount = 1; }
        amount = Math.max(1, Math.min(material.getMaxStackSize(), amount));
        ItemStack prize = new ItemStack(material, amount);
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(prize);
        for (ItemStack extra : overflow.values()) player.getWorld().dropItemNaturally(player.getLocation(), extra);
        int newStreak = streak + 1;
        playerData.set(path + ".streak", newStreak);
        playerData.set(path + ".last-claim", today);
        saveDailyData();
        String rewardName = String.valueOf(reward.getOrDefault("name", material.name().replace('_', ' ')));
        msg(player, "&aDaily reward claimed: &e" + rewardName + " x" + amount + "&a. Streak: &e" + newStreak + " day(s)&a.");
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
        player.closeInventory();
    }

    private void applyMaintenanceMotd() {
        Bukkit.setMotd(maintenanceEnabled ? color("&6Server under maintenance") : originalMotd);
    }

    @EventHandler
    public void onDailyQuit(PlayerQuitEvent event) {
        stopDailyTracking(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onMaintenanceLogin(PlayerLoginEvent event) {
        if (!maintenanceEnabled) return;
        Player player = event.getPlayer();
        if (player.isOp() || player.isWhitelisted()) return;
        event.disallow(PlayerLoginEvent.Result.KICK_OTHER,
                color("&6Server under maintenance\n&7Please try again later."));
    }

    @EventHandler
    public void onMaintenancePing(ServerListPingEvent event) {
        if (maintenanceEnabled) event.setMotd(color("&6Server under maintenance"));
    }

    private void startTeleportCountdown(Player player, String destination, BooleanSupplier teleportAction) {
        final int[] seconds = {3};
        final BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (!player.isOnline()) { task[0].cancel(); return; }
            if (seconds[0] > 0) {
                String number = String.valueOf(seconds[0]);
                msg(player, "&eTeleporting to &f" + destination + " &ein &6" + number + "&e...");
                player.sendTitle(color("&6Teleporting"), color("&e" + number), 0, 20, 0);
                seconds[0]--;
                return;
            }
            task[0].cancel();
            if (teleportAction.getAsBoolean()) {
                msg(player, "&aTeleported to &f" + destination + "&a.");
                player.sendTitle(color("&aTeleported!"), color("&f" + destination), 0, 35, 10);
            } else {
                msg(player, "&cTeleport failed. No safe destination was found.");
                player.sendTitle(color("&cTeleport failed"), color("&7Try again."), 0, 35, 10);
            }
        }, 0L, 20L);
    }

    private void showCoordinatesWhileBlind(Player player) {
        int duration = Math.max(20, getConfig().getInt("rtp.blindness-duration-ticks", 100));
        player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, duration, 0, false, false, false));
        final BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (!player.isOnline() || !player.hasPotionEffect(PotionEffectType.BLINDNESS)) {
                task[0].cancel();
                return;
            }
            Location location = player.getLocation();
            player.sendActionBar(Component.text(color("&eX: &f" + location.getBlockX()
                    + " &eY: &f" + location.getBlockY() + " &eZ: &f" + location.getBlockZ())));
        }, 0L, 5L);
    }

    private void loadHomes() {
        if (economyDatabase == null) return;
        try {
            // Import legacy YAML homes without overwriting any homes already in SQLite.
            ConfigurationSection allHomes = playerData.getConfigurationSection("homes");
            if (allHomes != null) {
                try (PreparedStatement insert = economyDatabase.prepareStatement(
                        "INSERT OR IGNORE INTO homes (uuid, name, world, x, y, z, yaw, pitch) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                    for (String uuidText : allHomes.getKeys(false)) {
                        UUID uuid;
                        try { uuid = UUID.fromString(uuidText); }
                        catch (IllegalArgumentException ignored) { continue; }
                        ConfigurationSection playerHomes = allHomes.getConfigurationSection(uuidText);
                        if (playerHomes == null) continue;
                        for (String homeName : playerHomes.getKeys(false)) {
                            String path = "homes." + uuidText + "." + homeName;
                            String worldName = playerData.getString(path + ".world");
                            if (worldName == null || worldName.isBlank()) continue;
                            insert.setString(1, uuid.toString());
                            insert.setString(2, homeName.toLowerCase(Locale.ROOT));
                            insert.setString(3, worldName);
                            insert.setDouble(4, playerData.getDouble(path + ".x"));
                            insert.setDouble(5, playerData.getDouble(path + ".y"));
                            insert.setDouble(6, playerData.getDouble(path + ".z"));
                            insert.setFloat(7, (float) playerData.getDouble(path + ".yaw"));
                            insert.setFloat(8, (float) playerData.getDouble(path + ".pitch"));
                            insert.addBatch();
                        }
                    }
                    insert.executeBatch();
                }
            }
            try (Statement statement = economyDatabase.createStatement();
                 ResultSet result = statement.executeQuery("SELECT uuid, name, world, x, y, z, yaw, pitch FROM homes")) {
                while (result.next()) {
                    try {
                        UUID uuid = UUID.fromString(result.getString("uuid"));
                        World world = Bukkit.getWorld(result.getString("world"));
                        if (world == null) continue;
                        Location location = new Location(world, result.getDouble("x"), result.getDouble("y"),
                                result.getDouble("z"), result.getFloat("yaw"), result.getFloat("pitch"));
                        homes.computeIfAbsent(uuid, ignored -> new LinkedHashMap<>())
                                .put(result.getString("name").toLowerCase(Locale.ROOT), location);
                    } catch (IllegalArgumentException ignored) {
                        getLogger().warning("Skipping a home with an invalid player UUID in economy.db.");
                    }
                }
            }
            getLogger().info("Loaded " + homes.values().stream().mapToInt(Map::size).sum() + " homes from economy.db.");
        } catch (SQLException exception) {
            getLogger().severe("Could not load homes from economy.db: " + exception.getMessage());
        }
    }

    private void setHome(Player player, String name) {
        String homeName = name.toLowerCase(Locale.ROOT);
        Location location = player.getLocation();
        try (PreparedStatement statement = economyDatabase.prepareStatement(
                "INSERT INTO homes (uuid, name, world, x, y, z, yaw, pitch) VALUES (?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT(uuid, name) DO UPDATE SET world=excluded.world, x=excluded.x, y=excluded.y, "
                        + "z=excluded.z, yaw=excluded.yaw, pitch=excluded.pitch")) {
            statement.setString(1, player.getUniqueId().toString());
            statement.setString(2, homeName);
            statement.setString(3, location.getWorld().getName());
            statement.setDouble(4, location.getX());
            statement.setDouble(5, location.getY());
            statement.setDouble(6, location.getZ());
            statement.setFloat(7, location.getYaw());
            statement.setFloat(8, location.getPitch());
            statement.executeUpdate();
            homes.computeIfAbsent(player.getUniqueId(), ignored -> new LinkedHashMap<>())
                    .put(homeName, location.clone());
            msg(player, "&aHome &f" + homeName + " &asaved to the database.");
        } catch (SQLException exception) {
            getLogger().severe("Could not save home for " + player.getName() + ": " + exception.getMessage());
            msg(player, "&cCould not save that home. Check the server console.");
        }
    }

    private Location getHome(Player player, String name) {
        Map<String, Location> playerHomes = homes.get(player.getUniqueId());
        if (playerHomes == null) return null;
        Location location = playerHomes.get(name.toLowerCase(Locale.ROOT));
        return location == null ? null : location.clone();
    }

    private boolean validHomeName(String name) {
        return name != null && name.matches("[A-Za-z0-9_-]{1,16}");
    }

    private boolean randomTeleport(Player player) {
        String worldName = getConfig().getString("rtp.world", "world");
        World world = Bukkit.getWorld(worldName);
        if (world == null) world = player.getWorld();

        WorldBorder border = world.getWorldBorder();
        Location center = border.getCenter();
        double borderLimit = Math.max(0, border.getSize() / 2.0 - getConfig().getDouble("rtp.border-margin", 16.0));
        double configuredMaxRadius = getConfig().getDouble("rtp.max-radius", 0.0);
        double radius = configuredMaxRadius > 0 ? Math.min(borderLimit, configuredMaxRadius) : borderLimit;
        if (radius < 1) return false;
        double minRadius = Math.max(0, Math.min(radius, getConfig().getDouble("rtp.min-radius", 500.0)));
        int attempts = Math.max(1, getConfig().getInt("rtp.attempts", 32));
        ThreadLocalRandom random = ThreadLocalRandom.current();

        for (int attempt = 0; attempt < attempts; attempt++) {
            double distance = Math.sqrt(random.nextDouble(minRadius * minRadius, radius * radius + 1));
            double angle = random.nextDouble(0, Math.PI * 2);
            int x = (int) Math.round(center.getX() + Math.cos(angle) * distance);
            int z = (int) Math.round(center.getZ() + Math.sin(angle) * distance);
            Location probe = new Location(world, x + 0.5, world.getMinHeight(), z + 0.5);
            if (!border.isInside(probe)) continue;

            Block ground = world.getHighestBlockAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            Material groundType = ground.getType();
            if (!groundType.isSolid() || ground.isLiquid()
                    || List.of(Material.MAGMA_BLOCK, Material.CAMPFIRE, Material.SOUL_CAMPFIRE,
                    Material.CACTUS, Material.FIRE, Material.SOUL_FIRE, Material.POWDER_SNOW).contains(groundType)) continue;

            int y = ground.getY() + 1;
            if (y + 1 >= world.getMaxHeight()) continue;
            Block feet = world.getBlockAt(x, y, z);
            Block head = world.getBlockAt(x, y + 1, z);
            if (!feet.isPassable() || !head.isPassable() || feet.isLiquid() || head.isLiquid()) continue;

            Location destination = new Location(world, x + 0.5, y, z + 0.5,
                    random.nextFloat() * 360.0f, 0.0f);
            if (player.teleport(destination)) {
                showCoordinatesWhileBlind(player);
                return true;
            }
        }
        return false;
    }

    private boolean bypassAntiCheat(Player player) {
        return player.hasPermission("jsmp.anticheat.bypass") || player.getGameMode() == GameMode.CREATIVE
                || player.getGameMode() == GameMode.SPECTATOR || vanishedPlayers.contains(player.getUniqueId());
    }

    private void flagAntiCheat(Player player, String reason) {
        if (!getConfig().getBoolean("anti-cheat.enabled", true) || bypassAntiCheat(player)) return;
        long now = System.currentTimeMillis();
        UUID uuid = player.getUniqueId();
        long last = lastAntiCheatFlag.getOrDefault(uuid, 0L);
        int count = now - last > 60_000L ? 1 : antiCheatFlags.getOrDefault(uuid, 0) + 1;
        antiCheatFlags.put(uuid, count);
        lastAntiCheatFlag.put(uuid, now);
        String line = "[JSMPCore AntiCheat] " + player.getName() + " flagged: " + reason + " ("
                + count + "/" + getConfig().getInt("anti-cheat.punish-threshold", 5) + ")";
        getLogger().warning(line);
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.hasPermission("jsmp.anticheat.alert")) online.sendMessage(color("&8[&cAntiCheat&8] &f" + line.substring(line.indexOf("] ") + 2)));
        }
        int threshold = getConfig().getInt("anti-cheat.punish-threshold", 5);
        if (threshold > 0 && count >= threshold) {
            antiCheatFlags.remove(uuid);
            lastAntiCheatFlag.remove(uuid);
            player.kickPlayer(color("&cJSMPCore AntiCheat\n&7Repeated suspicious activity detected."));
        }
    }

    @EventHandler
    public void onAntiCheatReach(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player) || !(event.getEntity() instanceof LivingEntity target)) return;
        if (!getConfig().getBoolean("anti-cheat.check-reach", true) || bypassAntiCheat(player)) return;
        if (player.isInsideVehicle() || player.isGliding()) return;
        double maxReach = getConfig().getDouble("anti-cheat.max-reach", 4.2);
        double distance = player.getEyeLocation().distance(target.getLocation().add(0, Math.min(1.0, target.getHeight() * 0.5), 0));
        if (distance > maxReach) flagAntiCheat(player, "reach (" + String.format(Locale.US, "%.2f", distance) + " blocks)");
    }

    @EventHandler
    public void onAntiCheatBlockBreak(org.bukkit.event.block.BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (!getConfig().getBoolean("anti-cheat.check-fast-break", true) || bypassAntiCheat(player)) return;
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        long start = breakWindowStart.getOrDefault(uuid, now);
        int count = breakWindowCount.getOrDefault(uuid, 0);
        if (now - start > 1000L) {
            start = now;
            count = 0;
        }
        count++;
        breakWindowStart.put(uuid, start);
        breakWindowCount.put(uuid, count);
        if (count > getConfig().getInt("anti-cheat.max-block-breaks-per-second", 28)) {
            flagAntiCheat(player, "unusually fast block breaking (" + count + "/s)");
            breakWindowStart.put(uuid, now);
            breakWindowCount.put(uuid, 0);
        }
    }

    @EventHandler
    public void onAfkMove(PlayerMoveEvent event) {
        AfkSession session = afkPlayers.get(event.getPlayer().getUniqueId());
        if (session == null || event.getTo() == null) return;

        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getWorld() == to.getWorld()
                && from.getX() == to.getX()
                && from.getY() == to.getY()
                && from.getZ() == to.getZ()) return;

        // Freeze position while still allowing the player to look around.
        event.setTo(new Location(from.getWorld(), from.getX(), from.getY(), from.getZ(),
                to.getYaw(), to.getPitch()));
    }

    @EventHandler
    public void onAfkChat(AsyncChatEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if (!afkPlayers.containsKey(uuid)) return;
        Bukkit.getScheduler().runTask(this, () -> stopAfk(uuid, true, true));
    }

    @EventHandler
    public void onAfkQuit(PlayerQuitEvent event) {
        stopAfk(event.getPlayer().getUniqueId(), false, false);
    }

    private void toggleAfk(Player player) {
        UUID uuid = player.getUniqueId();
        if (afkPlayers.containsKey(uuid)) {
            stopAfk(uuid, true, true);
            return;
        }

        BossBar bar = Bukkit.createBossBar("", BarColor.GREEN, BarStyle.SOLID);
        bar.setVisible(true);
        bar.addPlayer(player);
        AfkSession[] holder = new AfkSession[1];
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(this, () -> {
            AfkSession session = holder[0];
            if (session == null || !player.isOnline()) {
                stopAfk(uuid, false, false);
                return;
            }

            session.secondsRemaining--;
            if (session.secondsRemaining <= 0) {
                setBalance(uuid, balance(uuid) + 1000.0);
                msg(player, "&aAFK reward received: &e$1,000&a.");
                session.secondsRemaining = 60;
            }
            updateAfkBossBar(session);
        }, 20L, 20L);

        AfkSession session = new AfkSession(bar, task);
        holder[0] = session;
        afkPlayers.put(uuid, session);
        updateAfkBossBar(session);
        msg(player, "&aAFK mode enabled. Stay still to earn &e$1,000 &aper minute.");
    }

    private void updateAfkBossBar(AfkSession session) {
        session.bossBar.setTitle(color("&aAFK Reward: &e$1,000 &7in &f" + session.secondsRemaining + "s"));
        session.bossBar.setProgress(Math.max(0.0, Math.min(1.0, session.secondsRemaining / 60.0)));
    }

    private void stopAfk(UUID uuid, boolean notify, boolean onlineOnly) {
        AfkSession session = afkPlayers.remove(uuid);
        if (session == null) return;
        session.task.cancel();
        session.bossBar.removeAll();
        session.bossBar.setVisible(false);
        Player player = Bukkit.getPlayer(uuid);
        if (notify && player != null && (!onlineOnly || player.isOnline())) {
            msg(player, "&eAFK mode disabled. You did not receive a partial-minute reward.");
        }
    }

    @EventHandler
    public void onAntiCheatMove(PlayerMoveEvent event) {
        if (!getConfig().getBoolean("anti-cheat.check-movement", false)) return;
        Player player = event.getPlayer();
        if (bypassAntiCheat(player) || player.isFlying() || player.getAllowFlight() || player.isGliding()
                || player.isInsideVehicle() || player.isSwimming() || player.isInWater() || player.isClimbing()
                || player.getVelocity().length() > 0.75) return;
        if (event.getTo() == null || !event.getFrom().getWorld().equals(event.getTo().getWorld())) return;
        double horizontal = Math.hypot(event.getTo().getX() - event.getFrom().getX(),
                event.getTo().getZ() - event.getFrom().getZ());
        if (horizontal > getConfig().getDouble("anti-cheat.max-horizontal-move-per-event", 1.25)) {
            flagAntiCheat(player, "abnormal horizontal movement (" + String.format(Locale.US, "%.2f", horizontal) + ")");
        }
    }

    @EventHandler
    public void onHeld(PlayerItemHeldEvent event) {
        Bukkit.getScheduler().runTask(this, () -> decorateInventory(event.getPlayer()));
    }

    @EventHandler
    public void onInventorySlotChange(PlayerInventorySlotChangeEvent event) {
        Bukkit.getScheduler().runTask(this, () -> {
            ItemStack current = event.getPlayer().getInventory().getItem(event.getSlot());
            decorateWorth(current);
        });
    }

    @EventHandler
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player) decorateWorth(event.getItem().getItemStack());
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof MenuHolder holder)) {
            Bukkit.getScheduler().runTask(this, () -> decorateInventory(player));
            return;
        }

        int raw = event.getRawSlot();
        if (holder.type == MenuType.CATEGORIES) {
            event.setCancelled(true);
            if (raw == 49) {
                player.closeInventory();
                return;
            }
            ShopCategory[] categories = ShopCategory.values();
            int index = categorySlotIndex(raw);
            if (index >= 0 && index < categories.length) openShopItems(player, categories[index], 0);
            return;
        }

        if (holder.type == MenuType.DAILY) {
            event.setCancelled(true);
            if (raw == 50) claimDaily(player);
            else if (raw == 53) player.closeInventory();
            return;
        }

        if (holder.type == MenuType.SHOP_ITEMS) {
            event.setCancelled(true);
            if (raw == 45) {
                openShopItems(player, holder.category, holder.page - 1);
                return;
            }
            if (raw == 49) {
                openCategories(player);
                return;
            }
            if (raw == 53) {
                openShopItems(player, holder.category, holder.page + 1);
                return;
            }
            if (raw >= 0 && raw < 45) {
                ItemStack clicked = top.getItem(raw);
                if (clicked != null && !clicked.getType().isAir()) {
                    int amount = event.isShiftClick() && getConfig().getBoolean("shop.allow-shift-stack-buy", true)
                            ? clicked.getType().getMaxStackSize() : 1;
                    buy(player, clicked.getType(), amount);
                }
            }
            return;
        }

        if (holder.type == MenuType.SELL) {
            if (raw >= 0 && raw < 45) {
                decorateWorth(event.getCursor());
                decorateWorth(event.getCurrentItem());
                return;
            }
            if (raw >= 45 && raw < top.getSize()) {
                event.setCancelled(true);
                if (raw == 49) sellContents(player, top);
                else if (raw == 53) player.closeInventory();
                return;
            }
            if (raw >= top.getSize() && event.isShiftClick()) {
                event.setCancelled(true);
                ItemStack source = event.getCurrentItem();
                if (source == null || source.getType().isAir()) return;
                ItemStack moving = source.clone();
                addToSellMenu(top, moving);
                int moved = source.getAmount() - moving.getAmount();
                if (moving.getAmount() <= 0) event.setCurrentItem(null);
                else if (moved > 0) source.setAmount(moving.getAmount());
                return;
            }
            Bukkit.getScheduler().runTask(this, () -> decorateInventory(player));
        }
        Bukkit.getScheduler().runTask(this, () -> decorateInventory(player));
    }

    private int categorySlotIndex(int raw) {
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21};
        for (int i = 0; i < slots.length; i++) if (slots[i] == raw) return i;
        return -1;
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof MenuHolder holder)) return;
        if (holder.type == MenuType.DAILY) {
            event.setCancelled(true);
            return;
        }
        if (holder.type == MenuType.SELL) {
            for (int raw : event.getRawSlots()) {
                if (raw >= 45 && raw < top.getSize()) {
                    event.setCancelled(true);
                    return;
                }
            }
            Bukkit.getScheduler().runTask(this, () -> decorateInventory(player));
        } else {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        Inventory top = event.getInventory();
        if (!(top.getHolder() instanceof MenuHolder holder) || holder.type != MenuType.SELL) return;
        for (int slot = 0; slot < 45; slot++) {
            ItemStack item = top.getItem(slot);
            if (item == null || item.getType().isAir()) continue;
            Map<Integer, ItemStack> overflow = player.getInventory().addItem(item);
            for (ItemStack extra : overflow.values()) player.getWorld().dropItemNaturally(player.getLocation(), extra);
            top.setItem(slot, null);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        switch (name) {
            case "shop" -> {
                if (!sender.hasPermission("jsmp.shop")) { msg(sender, "&cYou do not have permission."); return true; }
                if (!(sender instanceof Player player)) { msg(sender, "&cOnly players can open the shop."); return true; }
                openCategories(player);
                return true;
            }
            case "daily" -> {
                if (!(sender instanceof Player player)) { msg(sender, "&cOnly players can use /daily."); return true; }
                if (args.length != 0) { msg(player, "&cUsage: /daily"); return true; }
                openDaily(player);
                return true;
            }
            case "afk" -> {
                if (!(sender instanceof Player player)) { msg(sender, "&cOnly players can use /afk."); return true; }
                if (args.length != 0) { msg(player, "&cUsage: /afk"); return true; }
                toggleAfk(player);
                return true;
            }
            case "rtp" -> {
                if (!(sender instanceof Player player)) { msg(sender, "&cOnly players can use /rtp."); return true; }
                if (!sender.hasPermission("jsmp.rtp")) { msg(sender, "&cYou do not have permission."); return true; }
                startTeleportCountdown(player, "a random location", () -> randomTeleport(player));
                return true;
            }
            case "sethome" -> {
                if (!(sender instanceof Player player)) { msg(sender, "&cOnly players can set homes."); return true; }
                if (!sender.hasPermission("jsmp.home")) { msg(sender, "&cYou do not have permission."); return true; }
                if (args.length != 1 || !validHomeName(args[0])) {
                    msg(sender, "&cUsage: /sethome <name> (1-16 letters, numbers, _ or -)");
                    return true;
                }
                setHome(player, args[0]);
                return true;
            }
            case "home" -> {
                if (!(sender instanceof Player player)) { msg(sender, "&cOnly players can use homes."); return true; }
                if (!sender.hasPermission("jsmp.home")) { msg(sender, "&cYou do not have permission."); return true; }
                if (args.length == 0) {
                    Map<String, Location> playerHomes = homes.get(player.getUniqueId());
                    if (playerHomes == null || playerHomes.isEmpty()) {
                        msg(player, "&eYou have no homes. Set one with /sethome <name>.");
                    } else {
                        msg(player, "&6Your homes: &f" + String.join("&7, &f", playerHomes.keySet()));
                    }
                    return true;
                }
                if (args.length != 1 || !validHomeName(args[0])) {
                    msg(player, "&cUsage: /home <name>");
                    return true;
                }
                if (getHome(player, args[0]) == null) {
                    msg(player, "&cThat home does not exist or its world is not loaded.");
                    return true;
                }
                startTeleportCountdown(player, "home " + args[0].toLowerCase(Locale.ROOT), () -> {
                    Location destination = getHome(player, args[0]);
                    return destination != null && player.teleport(destination);
                });
                return true;
            }
            case "jsmp" -> {
                if (!sender.hasPermission("jsmp.admin")) { msg(sender, "&cYou do not have permission."); return true; }
                if (args.length != 1 || !args[0].equalsIgnoreCase("reload")) {
                    msg(sender, "&cUsage: /jsmp reload");
                    return true;
                }
                reloadConfig();
                worth = YamlConfiguration.loadConfiguration(worthFile);
                maintenanceEnabled = getConfig().getBoolean("maintenance.enabled", false);
                generateWorthCatalogue();
                applyMaintenanceMotd();
                for (Player online : Bukkit.getOnlinePlayers()) decorateInventory(online);
                msg(sender, "&aJSMPCore configuration and worth.yml reloaded.");
                return true;
            }
            case "maintenance" -> {
                if (sender instanceof Player player && !player.isOp()) {
                    msg(sender, "&cOnly operators can change maintenance mode."); return true;
                }
                if (args.length > 1) { msg(sender, "&cUsage: /maintenance [on|off|toggle]"); return true; }
                String action = args.length == 0 ? "toggle" : args[0].toLowerCase(Locale.ROOT);
                if (action.equals("toggle")) maintenanceEnabled = !maintenanceEnabled;
                else if (action.equals("on")) maintenanceEnabled = true;
                else if (action.equals("off")) maintenanceEnabled = false;
                else { msg(sender, "&cUsage: /maintenance [on|off|toggle]"); return true; }
                getConfig().set("maintenance.enabled", maintenanceEnabled);
                saveConfig();
                applyMaintenanceMotd();
                if (maintenanceEnabled) {
                    for (Player online : Bukkit.getOnlinePlayers()) {
                        if (!online.isOp() && !online.isWhitelisted()) {
                            online.kickPlayer(color("&6Server under maintenance\n&7Please try again later."));
                        }
                    }
                }
                msg(sender, maintenanceEnabled ? "&6Maintenance mode enabled. Only operators and whitelisted players can join."
                        : "&aMaintenance mode disabled. The server is open to everyone.");
                return true;
            }
            case "sell" -> {
                if (!sender.hasPermission("jsmp.sell")) { msg(sender, "&cYou do not have permission."); return true; }
                if (!(sender instanceof Player player)) { msg(sender, "&cOnly players can use this GUI."); return true; }
                openSell(player);
                return true;
            }
            case "balance" -> {
                if (!sender.hasPermission("jsmp.balance")) { msg(sender, "&cYou do not have permission."); return true; }
                if (args.length == 0 && sender instanceof Player player) {
                    msg(sender, "&fYour balance: &a" + money(balance(player.getUniqueId())));
                    return true;
                }
                if (args.length != 1) { msg(sender, "&cUsage: /balance [player]"); return true; }
                UUID target = findPlayer(args[0]);
                if (target == null) { msg(sender, "&cThat player has not joined JSMP before."); return true; }
                msg(sender, "&f" + displayName(target) + "'s balance: &a" + money(balance(target)));
                return true;
            }
            case "baltop" -> {
                if (!sender.hasPermission("jsmp.baltop")) { msg(sender, "&cYou do not have permission."); return true; }
                int limit = Math.max(1, getConfig().getInt("economy.baltop-size", 10));
                List<Map.Entry<UUID, Double>> top = balances.entrySet().stream()
                        .sorted(Map.Entry.<UUID, Double>comparingByValue().reversed()).limit(limit).toList();
                msg(sender, "&6&lJSMP Balance Top");
                int rank = 1;
                for (Map.Entry<UUID, Double> entry : top) {
                    sender.sendMessage(color("&e#" + rank++ + " &f" + displayName(entry.getKey())
                            + " &8» &a" + money(entry.getValue())));
                }
                if (top.isEmpty()) msg(sender, "&7No balances have been recorded yet.");
                return true;
            }
            case "pay" -> {
                if (!sender.hasPermission("jsmp.pay")) { msg(sender, "&cYou do not have permission."); return true; }
                if (!(sender instanceof Player player)) { msg(sender, "&cOnly players can pay other players."); return true; }
                if (args.length != 2) { msg(sender, "&cUsage: /pay <player> <amount>"); return true; }
                Player target = Bukkit.getPlayerExact(args[0]);
                if (target == null) { msg(sender, "&cThat player must be online."); return true; }
                if (target.getUniqueId().equals(player.getUniqueId())) { msg(sender, "&cYou cannot pay yourself."); return true; }
                double amount;
                try { amount = Double.parseDouble(args[1]); }
                catch (NumberFormatException exception) { msg(sender, "&cEnter a valid amount."); return true; }
                double minimum = getConfig().getDouble("economy.minimum-payment", 0.01);
                if (!Double.isFinite(amount) || amount < minimum) { msg(sender, "&cMinimum payment is " + money(minimum) + "."); return true; }
                amount = roundMoney(amount);
                if (balance(player.getUniqueId()) < amount) { msg(sender, "&cYou do not have enough money."); return true; }
                setBalance(player.getUniqueId(), balance(player.getUniqueId()) - amount);
                setBalance(target.getUniqueId(), balance(target.getUniqueId()) + amount);
                msg(player, "&aYou paid &f" + target.getName() + " &e" + money(amount) + "&a.");
                msg(target, "&aYou received &e" + money(amount) + " &afrom &f" + player.getName() + "&a.");
                return true;
            }
            case "vanish" -> {
                if (!sender.hasPermission("jsmp.admin") || !sender.hasPermission("jsmp.vanish")) {
                    msg(sender, "&cYou do not have permission."); return true;
                }
                if (!(sender instanceof Player player)) { msg(sender, "&cOnly players can vanish."); return true; }
                UUID uuid = player.getUniqueId();
                if (vanishedPlayers.remove(uuid)) {
                    for (Player viewer : Bukkit.getOnlinePlayers()) viewer.showPlayer(this, player);
                    msg(player, "&aYou are now visible.");
                } else {
                    vanishedPlayers.add(uuid);
                    for (Player viewer : Bukkit.getOnlinePlayers()) {
                        if (!viewer.getUniqueId().equals(uuid)) viewer.hidePlayer(this, player);
                    }
                    msg(player, "&aYou are now vanished.");
                }
                return true;
            }
            case "god" -> {
                if (!sender.hasPermission("jsmp.admin") || !sender.hasPermission("jsmp.god")) {
                    msg(sender, "&cYou do not have permission."); return true;
                }
                if (!(sender instanceof Player player)) { msg(sender, "&cOnly players can use god mode."); return true; }
                UUID uuid = player.getUniqueId();
                if (godPlayers.add(uuid)) {
                    previousInvulnerability.put(uuid, player.isInvulnerable());
                    player.setInvulnerable(true);
                    msg(player, "&aGod mode enabled.");
                } else {
                    msg(player, "&eGod mode is already enabled. Use /ungod to disable it.");
                }
                return true;
            }
            case "ungod" -> {
                if (!sender.hasPermission("jsmp.admin") || !sender.hasPermission("jsmp.god")) {
                    msg(sender, "&cYou do not have permission."); return true;
                }
                if (!(sender instanceof Player player)) { msg(sender, "&cOnly players can use god mode."); return true; }
                UUID uuid = player.getUniqueId();
                if (godPlayers.remove(uuid)) {
                    player.setInvulnerable(previousInvulnerability.getOrDefault(uuid, false));
                    previousInvulnerability.remove(uuid);
                    msg(player, "&cGod mode disabled.");
                } else {
                    msg(player, "&eGod mode is not enabled.");
                }
                return true;
            }
            case "money" -> {
                if (!sender.hasPermission("jsmp.admin")) { msg(sender, "&cYou do not have permission."); return true; }
                if (args.length != 3) { msg(sender, "&cUsage: /jsmpmoney <give|take|set> <player> <amount>"); return true; }
                UUID target = findPlayer(args[1]);
                if (target == null) { msg(sender, "&cThat player has not joined JSMP before."); return true; }
                double amount;
                try { amount = Double.parseDouble(args[2]); }
                catch (NumberFormatException exception) { msg(sender, "&cEnter a valid amount."); return true; }
                if (!Double.isFinite(amount) || amount < 0) { msg(sender, "&cAmount must be zero or higher."); return true; }
                amount = roundMoney(amount);
                double current = balance(target);
                switch (args[0].toLowerCase(Locale.ROOT)) {
                    case "give", "add" -> setBalance(target, current + amount);
                    case "take", "remove" -> setBalance(target, Math.max(0, current - amount));
                    case "set" -> setBalance(target, amount);
                    default -> { msg(sender, "&cUse give, take or set."); return true; }
                }
                msg(sender, "&aUpdated &f" + displayName(target) + "&a's balance to &e" + money(balance(target)) + "&a.");
                Player online = Bukkit.getPlayer(target);
                if (online != null) msg(online, "&aYour balance is now &e" + money(balance(target)) + "&a.");
                return true;
            }
            default -> { return false; }
        }
    }
}