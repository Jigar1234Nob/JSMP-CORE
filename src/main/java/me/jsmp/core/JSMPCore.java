package me.jsmp.core;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
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
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public final class JSMPCore extends JavaPlugin implements Listener, CommandExecutor {

    private File worthFile;
    private File playersFile;
    private FileConfiguration worth;
    private FileConfiguration playerData;
    private final Map<UUID, Double> balances = new LinkedHashMap<>();
    private final Map<UUID, String> playerNames = new LinkedHashMap<>();
    private NamespacedKey worthLoreKey;

    private enum MenuType { CATEGORIES, SHOP_ITEMS, SELL }

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
        ARSENAL("Arsenal", Material.IRON_SWORD, "Weapons, armour, tools and consumables"),
        ALCHEMY("Alchemy", Material.POTION, "Drinkable potions only"),
        REDSTONE("Redstone Lab", Material.REDSTONE, "Redstone components and machines"),
        WORKSTATIONS("Workstations", Material.CRAFTING_TABLE, "Crafting and utility stations"),
        GENERAL("General Supplies", Material.BUNDLE, "Everything else");

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
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            getLogger().severe("Could not create plugin data directory.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        worth = YamlConfiguration.loadConfiguration(worthFile);
        playerData = YamlConfiguration.loadConfiguration(playersFile);
        worthLoreKey = new NamespacedKey(this, "worth_lore");
        loadBalances();
        generateWorthCatalogue();

        getServer().getPluginManager().registerEvents(this, this);
        for (String command : List.of("shop", "sell", "balance", "baltop", "pay", "money")) {
            if (getCommand(command) != null) getCommand(command).setExecutor(this);
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            remember(player);
            decorateInventory(player);
        }
        getLogger().info("JSMPCore enabled. Loaded " + worth.getConfigurationSection("items").getKeys(false).size()
                + " item worth entries and " + balances.size() + " player balances.");
    }

    @Override
    public void onDisable() {
        saveBalances();
        saveWorth();
    }

    private void loadBalances() {
        ConfigurationSection section = playerData.getConfigurationSection("players");
        if (section == null) return;
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

    private void saveBalances() {
        playerData.set("players", null);
        for (UUID uuid : balances.keySet()) {
            playerData.set("players." + uuid + ".balance", roundMoney(balances.getOrDefault(uuid, 0.0)));
            playerData.set("players." + uuid + ".name", playerNames.getOrDefault(uuid, "Unknown"));
        }
        try {
            playerData.save(playersFile);
        } catch (IOException exception) {
            getLogger().severe("Could not save players.yml: " + exception.getMessage());
        }
    }

    private void generateWorthCatalogue() {
        if (!worth.isConfigurationSection("items")) worth.createSection("items");
        int added = 0;
        for (Material material : Material.values()) {
            if (!material.isItem() || material.isAir()) continue;
            String path = "items." + material.name();
            if (!worth.contains(path + ".price")) {
                double price = defaultWorth(material);
                worth.set(path + ".price", price);
                worth.set(path + ".sellable", price > 0);
                added++;
            } else if (!worth.contains(path + ".sellable")) {
                worth.set(path + ".sellable", worth.getDouble(path + ".price", 0) > 0);
            }
        }
        if (added > 0 || !worthFile.exists()) saveWorth();
        getLogger().info("Worth catalogue ready; added " + added + " new materials.");
    }

    private double defaultWorth(Material material) {
        String n = material.name();
        if (List.of("BEDROCK", "BARRIER", "COMMAND_BLOCK", "CHAIN_COMMAND_BLOCK", "REPEATING_COMMAND_BLOCK",
                "STRUCTURE_BLOCK", "STRUCTURE_VOID", "JIGSAW", "DEBUG_STICK", "KNOWLEDGE_BOOK",
                "LIGHT", "END_PORTAL_FRAME", "SPAWNER").contains(n)) return 0.0;
        if (n.contains("NETHERITE")) return 250.0;
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

    private double worth(Material material) {
        String path = "items." + material.name();
        if (!worth.getBoolean(path + ".sellable", false)) return 0;
        return Math.max(0, worth.getDouble(path + ".price", 0));
    }

    private boolean isSellable(ItemStack item) {
        return item != null && !item.getType().isAir() && worth(item.getType()) > 0;
    }

    private ShopCategory categoryFor(Material material) {
        String n = material.name();
        if (n.equals("POTION")) return ShopCategory.ALCHEMY;
        if (n.contains("REDSTONE") || List.of("REPEATER", "COMPARATOR", "PISTON", "STICKY_PISTON",
                "OBSERVER", "HOPPER", "DISPENSER", "DROPPER", "TARGET", "LEVER", "TRIPWIRE_HOOK",
                "DAYLIGHT_DETECTOR", "NOTE_BLOCK", "SCULK_SENSOR", "CALIBRATED_SCULK_SENSOR").contains(n)
                || n.endsWith("_PRESSURE_PLATE")) return ShopCategory.REDSTONE;
        if (n.endsWith("_SWORD") || n.endsWith("_PICKAXE") || n.endsWith("_AXE") || n.endsWith("_SHOVEL")
                || n.endsWith("_HOE") || n.endsWith("_HELMET") || n.endsWith("_CHESTPLATE")
                || n.endsWith("_LEGGINGS") || n.endsWith("_BOOTS") || n.endsWith("_BOW")
                || n.endsWith("_CROSSBOW") || n.equals("TRIDENT") || n.equals("MACE") || n.equals("SHIELD")
                || n.equals("WOLF_ARMOR") || n.equals("FISHING_ROD") || n.contains("ARROW")
                || n.contains("TNT") || n.contains("FIREWORK") || n.contains("TOTEM")
                || n.contains("CHARGE") || n.contains("SHEARS") || n.equals("FLINT_AND_STEEL"))
            return ShopCategory.ARSENAL;
        if (n.contains("DIAMOND") || n.contains("EMERALD") || n.contains("AMETHYST")
                || n.contains("LAPIS") || n.contains("RAW_IRON") || n.contains("RAW_GOLD")
                || n.contains("RAW_COPPER") || n.equals("GOLD_INGOT") || n.equals("IRON_INGOT")
                || n.equals("COPPER_INGOT") || n.equals("NETHERITE_INGOT") || n.equals("NETHERITE_SCRAP"))
            return ShopCategory.GEMS;
        if ((!n.contains("NETHERITE") && n.contains("NETHER")) || n.contains("CRIMSON") || n.contains("WARPED")
                || n.contains("BASALT") || n.contains("BLACKSTONE") || n.contains("SOUL_")
                || n.contains("GLOWSTONE") || n.contains("MAGMA") || n.contains("QUARTZ"))
            return ShopCategory.NETHER;
        if (n.contains("SEEDS") || n.contains("SAPLING") || n.contains("WHEAT") || n.contains("CARROT")
                || n.contains("POTATO") || n.contains("BEETROOT") || n.contains("MELON") || n.contains("PUMPKIN")
                || n.contains("SUGAR_CANE") || n.contains("BAMBOO") || n.contains("COCOA")
                || n.contains("COMPOST") || n.contains("BONE_MEAL") || n.contains("HAY_BLOCK")
                || n.contains("MOSS") || n.contains("FLOWER") || n.contains("TULIP") || n.contains("LILY"))
            return ShopCategory.FARMING;
        if (material.isEdible()) return ShopCategory.FOOD;
        if (n.endsWith("_SWORD") || n.endsWith("_PICKAXE") || n.endsWith("_AXE") || n.endsWith("_SHOVEL")
                || n.endsWith("_HOE") || n.endsWith("_HELMET") || n.endsWith("_CHESTPLATE")
                || n.endsWith("_LEGGINGS") || n.endsWith("_BOOTS") || n.endsWith("_BOW")
                || n.endsWith("_CROSSBOW") || n.equals("TRIDENT") || n.equals("MACE") || n.equals("SHIELD")
                || n.contains("ARROW") || n.contains("TNT") || n.contains("FIREWORK") || n.contains("TOTEM")
                || n.contains("CHARGE") || n.contains("SHEARS") || n.equals("FLINT_AND_STEEL"))
            return ShopCategory.ARSENAL;
        if (List.of("CRAFTING_TABLE", "FURNACE", "BLAST_FURNACE", "SMOKER", "ANVIL", "CHIPPED_ANVIL",
                "DAMAGED_ANVIL", "ENCHANTING_TABLE", "BREWING_STAND", "SMITHING_TABLE", "GRINDSTONE",
                "STONECUTTER", "CARTOGRAPHY_TABLE", "FLETCHING_TABLE", "LOOM", "LECTERN", "BARREL",
                "COMPOSTER", "ENDER_CHEST", "BEACON").contains(n)) return ShopCategory.WORKSTATIONS;
        if (material.isBlock()) return ShopCategory.BLOCKS;
        return ShopCategory.GENERAL;
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
            return stripped != null && stripped.startsWith("Worth: ");
        });
        double value = worth(stack.getType());
        if (value > 0) {
            String worthLine = color("&7Worth: &a" + money(value) + " &8each");
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
            if (!material.isItem() || material.isAir() || material == Material.SPLASH_POTION
                    || material == Material.LINGERING_POTION || categoryFor(material) != category) continue;
            if (worth(material) <= 0) continue;
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
        double unitPrice = worth(material);
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
        for (int slot = 0; slot < 45; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.getType().isAir()) continue;
            double unit = worth(item.getType());
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
        remember(event.getPlayer());
        Bukkit.getScheduler().runTask(this, () -> decorateInventory(event.getPlayer()));
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