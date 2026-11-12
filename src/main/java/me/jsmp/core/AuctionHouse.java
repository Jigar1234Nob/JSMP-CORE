package me.jsmp.core;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public final class AuctionHouse implements CommandExecutor, Listener {
    private static final int PAGE_SIZE = 45;
    private static final int MENU_SIZE = 54;
    private final JSMPCore plugin;
    private final Connection database;
    private final Set<UUID> searching = ConcurrentHashMap.newKeySet();
    private final Set<UUID> pendingSell = ConcurrentHashMap.newKeySet();

    private enum MenuType { BROWSE, MY_LISTINGS, CLAIMS, CONFIRM, SELL }

    private static final class MenuHolder implements InventoryHolder {
        private final MenuType type;
        private final int page;
        private final String query;
        private final String sort;
        private final long listingId;
        private Inventory inventory;

        private MenuHolder(MenuType type, int page, String query, String sort, long listingId) {
            this.type = type;
            this.page = page;
            this.query = query;
            this.sort = sort;
            this.listingId = listingId;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private record Listing(long id, UUID sellerId, String sellerName, ItemStack item,
                           double price, long createdAt, long expiresAt) {}

    public AuctionHouse(JSMPCore plugin) throws SQLException {
        this.plugin = plugin;
        File file = new File(plugin.getDataFolder(), "auction-house.db");
        this.database = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        try (Statement statement = database.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=FULL");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS auction_listings (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        seller_uuid TEXT NOT NULL,
                        seller_name TEXT NOT NULL,
                        item BLOB NOT NULL,
                        price REAL NOT NULL CHECK(price > 0),
                        created_at INTEGER NOT NULL,
                        expires_at INTEGER NOT NULL,
                        status TEXT NOT NULL DEFAULT 'ACTIVE'
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_auction_status_expiry ON auction_listings(status, expires_at)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_auction_seller_status ON auction_listings(seller_uuid, status)");
        }
    }

    public void register() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        if (plugin.getCommand("ah") != null) plugin.getCommand("ah").setExecutor(this);
    }

    public void close() {
        try {
            if (!database.isClosed()) database.close();
        } catch (SQLException exception) {
            plugin.getLogger().warning("Could not close auction-house.db: " + exception.getMessage());
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use the Auction House.");
            return true;
        }
        if (!player.hasPermission("jsmp.auctionhouse")) {
            message(player, "&cYou do not have permission to use the Auction House.");
            return true;
        }

        if (args.length == 0) {
            openBrowse(player, 0, "", "NEWEST");
            return true;
        }

        String subcommand = args[0].toLowerCase(Locale.ROOT);
        switch (subcommand) {
            case "sell" -> {
                if (args.length != 2) {
                    message(player, "&cUsage: /ah sell <price>");
                    return true;
                }
                double price;
                try {
                    price = Double.parseDouble(args[1]);
                } catch (NumberFormatException exception) {
                    message(player, "&cEnter a valid price. Example: /ah sell 2500");
                    return true;
                }
                createListing(player, price);
            }
            case "my", "listings" -> openMyListings(player, 0);
            case "claims", "expired" -> openClaims(player, 0);
            case "search" -> {
                if (args.length < 2) {
                    message(player, "&cUsage: /ah search <item>");
                    return true;
                }
                openBrowse(player, 0, String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)), "NEWEST");
            }
            case "help" -> sendHelp(player);
            default -> openBrowse(player, 0, String.join(" ", args), "NEWEST");
        }
        return true;
    }

    private void sendHelp(Player player) {
        message(player, "&f&lJSMP AUCTION HOUSE");
        message(player, "&7/ah &8- &fBrowse the marketplace");
        message(player, "&7/ah sell <price> &8- &fList the item in your hand");
        message(player, "&7/ah my &8- &fManage your active listings");
        message(player, "&7/ah claims &8- &fCollect expired listings");
        message(player, "&7/ah search <item> &8- &fSearch for an item");
    }

    private void createListing(Player player, double price) {
        if (!Double.isFinite(price) || price <= 0 || price > 1_000_000_000_000.0) {
            message(player, "&cPrice must be greater than zero and no more than 1 trillion.");
            return;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) {
            message(player, "&cHold the item you want to list in your main hand.");
            return;
        }
        expireListings();
        int limit = Math.max(1, plugin.getConfig().getInt("auction-house.max-active-listings", 5));
        if (activeCount(player.getUniqueId()) >= limit) {
            message(player, "&cYou have reached your active listing limit of &f" + limit + "&c.");
            return;
        }

        ItemStack item = hand.clone();
        long now = System.currentTimeMillis();
        long expiryHours = Math.max(1, plugin.getConfig().getLong("auction-house.listing-expiry-hours", 48));
        long expires = now + expiryHours * 60L * 60L * 1000L;
        try (PreparedStatement statement = database.prepareStatement(
                "INSERT INTO auction_listings (seller_uuid, seller_name, item, price, created_at, expires_at, status) VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE')")) {
            statement.setString(1, player.getUniqueId().toString());
            statement.setString(2, player.getName());
            statement.setBytes(3, item.serializeAsBytes());
            statement.setDouble(4, price);
            statement.setLong(5, now);
            statement.setLong(6, expires);
            statement.executeUpdate();
            if (hand.getAmount() == item.getAmount()) {
                player.getInventory().setItemInMainHand(null);
            } else {
                hand.setAmount(hand.getAmount() - item.getAmount());
            }
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.2f);
            message(player, "&aListed &f" + item.getAmount() + "x " + itemName(item)
                    + " &afor &f" + money(price) + "&a.");
            message(player, "&7Your listing expires in " + expiryHours + " hours.");
        } catch (SQLException exception) {
            plugin.getLogger().severe("Could not create auction listing: " + exception.getMessage());
            message(player, "&cCould not create your listing. Your item has not been taken.");
        }
    }

    private void openBrowse(Player player, int page, String query, String sort) {
        expireListings();
        MenuHolder holder = new MenuHolder(MenuType.BROWSE, page, query, sort, -1);
        Inventory inventory = Bukkit.createInventory(holder, MENU_SIZE, color("&8JSMP &7» &fAuction House"));
        holder.inventory = inventory;
        fill(inventory);

        List<Listing> listings = loadListings("ACTIVE", null);
        String normalized = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (!normalized.isEmpty()) {
            listings.removeIf(listing -> !(itemName(listing.item()).toLowerCase(Locale.ROOT).contains(normalized)
                    || listing.item().getType().name().toLowerCase(Locale.ROOT).contains(normalized)
                    || listing.sellerName().toLowerCase(Locale.ROOT).contains(normalized)));
        }
        sortListings(listings, sort);

        int start = Math.max(0, page) * PAGE_SIZE;
        for (int slot = 0; slot < PAGE_SIZE && start + slot < listings.size(); slot++) {
            Listing listing = listings.get(start + slot);
            inventory.setItem(slot, listingDisplay(listing, false));
        }

        inventory.setItem(45, button(Material.ARROW, "&fPrevious page", "&7Go to the previous page."));
        inventory.setItem(46, button(Material.HOPPER, "&fSort: " + sortLabel(sort), "&7Click to change sorting."));
        inventory.setItem(47, button(Material.OAK_SIGN, "&fSearch items", "&7Click, then type your search in chat.",
                "&7Or use &f/ah search <item>&7."));
        inventory.setItem(48, button(Material.CHEST, "&fMy listings", "&7Manage your active auctions."));
        inventory.setItem(49, button(Material.BUNDLE, "&fExpired claims", "&7Collect items from expired listings."));
        inventory.setItem(50, button(Material.EMERALD, "&fSell an item", "&7Hold an item and use &f/ah sell <price>&7."));
        inventory.setItem(51, button(Material.SUNFLOWER, "&fRefresh", "&7Refresh the marketplace."));
        inventory.setItem(52, button(Material.PAPER, "&fPage " + (page + 1),
                "&7Listings: &f" + listings.size(), "&7Search: &f" + (normalized.isEmpty() ? "None" : query)));
        inventory.setItem(53, button(Material.ARROW, "&fNext page", "&7Go to the next page."));
        player.openInventory(inventory);
    }

    private void openMyListings(Player player, int page) {
        expireListings();
        MenuHolder holder = new MenuHolder(MenuType.MY_LISTINGS, page, "", "NEWEST", -1);
        Inventory inventory = Bukkit.createInventory(holder, MENU_SIZE, color("&8JSMP &7» &fMy Listings"));
        holder.inventory = inventory;
        fill(inventory);
        List<Listing> listings = loadListings("ACTIVE", player.getUniqueId());
        sortListings(listings, "NEWEST");
        int start = Math.max(0, page) * PAGE_SIZE;
        for (int slot = 0; slot < PAGE_SIZE && start + slot < listings.size(); slot++) {
            inventory.setItem(slot, listingDisplay(listings.get(start + slot), true));
        }
        inventory.setItem(45, button(Material.ARROW, "&fBack", "&7Return to the marketplace."));
        inventory.setItem(49, button(Material.PAPER, "&fYour listings: " + listings.size(),
                "&7Click one of your listings to cancel it."));
        inventory.setItem(53, button(Material.ARROW, "&fNext page", "&7Next page."));
        player.openInventory(inventory);
    }

    private void openClaims(Player player, int page) {
        expireListings();
        MenuHolder holder = new MenuHolder(MenuType.CLAIMS, page, "", "NEWEST", -1);
        Inventory inventory = Bukkit.createInventory(holder, MENU_SIZE, color("&8JSMP &7» &fExpired Claims"));
        holder.inventory = inventory;
        fill(inventory);
        List<Listing> listings = loadListings("EXPIRED", player.getUniqueId());
        sortListings(listings, "NEWEST");
        int start = Math.max(0, page) * PAGE_SIZE;
        for (int slot = 0; slot < PAGE_SIZE && start + slot < listings.size(); slot++) {
            ItemStack display = listingDisplay(listings.get(start + slot), false);
            ItemMeta meta = display.getItemMeta();
            List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
            lore.add(color("&aClick to claim this item."));
            meta.setLore(lore);
            display.setItemMeta(meta);
            inventory.setItem(slot, display);
        }
        inventory.setItem(45, button(Material.ARROW, "&fBack", "&7Return to the marketplace."));
        inventory.setItem(49, button(Material.PAPER, "&fItems to claim: " + listings.size(),
                "&7Click an expired listing to collect it."));
        inventory.setItem(53, button(Material.ARROW, "&fNext page", "&7Next page."));
        player.openInventory(inventory);
    }

    private void openSellMenu(Player player) {
        MenuHolder holder = new MenuHolder(MenuType.SELL, 0, "", "NEWEST", -1);
        Inventory inventory = Bukkit.createInventory(holder, 27, color("&8JSMP &7» &fCreate Listing"));
        holder.inventory = inventory;
        fill(inventory);
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (!hand.getType().isAir()) {
            ItemStack preview = hand.clone();
            ItemMeta meta = preview.getItemMeta();
            List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
            lore.add("");
            lore.add(color("&7This item will be listed."));
            meta.setLore(lore);
            preview.setItemMeta(meta);
            inventory.setItem(13, preview);
        } else {
            inventory.setItem(13, button(Material.BARRIER, "&cNo item held",
                    "&7Hold the item you want to sell"));
        }
        inventory.setItem(11, button(Material.LIME_CONCRETE, "&aSet price and list",
                "&7You will enter the price in chat.",
                "&7The item in your main hand will be listed."));
        inventory.setItem(15, button(Material.RED_CONCRETE, "&cCancel",
                "&7Return to the Auction House."));
        player.openInventory(inventory);
    }

    private void openConfirmation(Player player, Listing listing) {
        MenuHolder holder = new MenuHolder(MenuType.CONFIRM, 0, "", "NEWEST", listing.id());
        Inventory inventory = Bukkit.createInventory(holder, 27, color("&8JSMP &7» &fConfirm Purchase"));
        fill(inventory);
        inventory.setItem(13, listingDisplay(listing, false));
        inventory.setItem(11, button(Material.LIME_CONCRETE, "&aConfirm purchase",
                "&7Price: &f" + money(listing.price()), "&7Seller: &f" + listing.sellerName()));
        inventory.setItem(15, button(Material.RED_CONCRETE, "&cCancel", "&7Return to the marketplace."));
        holder.inventory = inventory;
        player.openInventory(inventory);
    }

    private void handleClick(Player player, MenuHolder holder, int slot, ItemStack clicked) {
        if (holder.type == MenuType.SELL) {
            if (slot == 11) {
                if (player.getInventory().getItemInMainHand().getType().isAir()) {
                    message(player, "&cHold an item in your main hand first.");
                    return;
                }
                pendingSell.add(player.getUniqueId());
                player.closeInventory();
                message(player, "&fEnter the listing price in chat, or type &ccancel&f.");
            } else if (slot == 15) {
                openBrowse(player, 0, "", "NEWEST");
            }
            return;
        }
        if (holder.type == MenuType.CONFIRM) {
            if (slot == 11) purchase(player, holder.listingId);
            else if (slot == 15) openBrowse(player, 0, "", "NEWEST");
            return;
        }
        if (slot < 0 || slot >= PAGE_SIZE) {
            if (holder.type == MenuType.BROWSE) {
                switch (slot) {
                    case 45 -> openBrowse(player, Math.max(0, holder.page - 1), holder.query, holder.sort);
                    case 46 -> openBrowse(player, holder.page, holder.query, nextSort(holder.sort));
                    case 47 -> {
                        searching.add(player.getUniqueId());
                        player.closeInventory();
                        message(player, "&fType an item name to search, or type &ccancel&f.");
                    }
                    case 48 -> openMyListings(player, 0);
                    case 49 -> openClaims(player, 0);
                    case 50 -> openSellMenu(player);
                    case 51 -> openBrowse(player, holder.page, holder.query, holder.sort);
                    case 53 -> openBrowse(player, holder.page + 1, holder.query, holder.sort);
                    default -> { }
                }
            } else if (slot == 45) {
                openBrowse(player, 0, "", "NEWEST");
            } else if (slot == 53) {
                if (holder.type == MenuType.MY_LISTINGS) openMyListings(player, holder.page + 1);
                if (holder.type == MenuType.CLAIMS) openClaims(player, holder.page + 1);
            }
            return;
        }

        if (clicked == null || clicked.getType().isAir()) return;
        long id = listingId(clicked);
        if (id < 0) return;
        if (holder.type == MenuType.BROWSE) {
            Listing listing = findListing(id);
            if (listing == null) {
                message(player, "&cThat listing is no longer available.");
                openBrowse(player, holder.page, holder.query, holder.sort);
            } else if (listing.sellerId().equals(player.getUniqueId())) {
                message(player, "&cYou cannot purchase your own listing.");
            } else {
                openConfirmation(player, listing);
            }
        } else if (holder.type == MenuType.MY_LISTINGS) {
            cancelListing(player, id);
            openMyListings(player, holder.page);
        } else if (holder.type == MenuType.CLAIMS) {
            claimListing(player, id);
            openClaims(player, holder.page);
        }
    }

    private void purchase(Player buyer, long id) {
        Listing listing = findListing(id);
        if (listing == null) {
            message(buyer, "&cThat listing is no longer available.");
            openBrowse(buyer, 0, "", "NEWEST");
            return;
        }
        if (listing.sellerId().equals(buyer.getUniqueId())) {
            message(buyer, "&cYou cannot purchase your own listing.");
            return;
        }
        if (plugin.getBalance(buyer.getUniqueId()) < listing.price()) {
            message(buyer, "&cYou do not have enough money. You need &f" + money(listing.price()) + "&c.");
            return;
        }

        try (PreparedStatement statement = database.prepareStatement(
                "UPDATE auction_listings SET status='SOLD' WHERE id=? AND status='ACTIVE' AND expires_at>?")) {
            statement.setLong(1, id);
            statement.setLong(2, System.currentTimeMillis());
            if (statement.executeUpdate() != 1) {
                message(buyer, "&cThat listing has already sold or expired.");
                openBrowse(buyer, 0, "", "NEWEST");
                return;
            }
            plugin.setBalance(buyer.getUniqueId(), plugin.getBalance(buyer.getUniqueId()) - listing.price());
            plugin.setBalance(listing.sellerId(), plugin.getBalance(listing.sellerId()) + listing.price());
            giveItem(buyer, listing.item());
            buyer.playSound(buyer.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.1f);
            message(buyer, "&aPurchased &f" + itemName(listing.item()) + " &afor &f" + money(listing.price()) + "&a.");
            Player seller = Bukkit.getPlayer(listing.sellerId());
            if (seller != null) message(seller, "&aYour &f" + itemName(listing.item()) + " &asold to &f"
                    + buyer.getName() + " &afor &f" + money(listing.price()) + "&a.");
            else {
                OfflinePlayer offlineSeller = Bukkit.getOfflinePlayer(listing.sellerId());
                plugin.getLogger().info("Auction sale paid " + offlineSeller.getName() + " " + money(listing.price()));
            }
            openBrowse(buyer, 0, "", "NEWEST");
        } catch (SQLException exception) {
            plugin.getLogger().severe("Could not complete auction purchase: " + exception.getMessage());
            message(buyer, "&cThe purchase failed. Please try again.");
        }
    }

    private void cancelListing(Player player, long id) {
        Listing listing = findListing(id);
        if (listing == null || !listing.sellerId().equals(player.getUniqueId())) {
            message(player, "&cThat is not your active listing.");
            return;
        }
        try (PreparedStatement statement = database.prepareStatement(
                "UPDATE auction_listings SET status='CANCELLED' WHERE id=? AND seller_uuid=? AND status='ACTIVE'")) {
            statement.setLong(1, id);
            statement.setString(2, player.getUniqueId().toString());
            if (statement.executeUpdate() == 1) {
                giveItem(player, listing.item());
                message(player, "&aListing cancelled. Your item has been returned.");
            } else {
                message(player, "&cThat listing is no longer active.");
            }
        } catch (SQLException exception) {
            plugin.getLogger().severe("Could not cancel auction listing: " + exception.getMessage());
            message(player, "&cCould not cancel that listing.");
        }
    }

    private void claimListing(Player player, long id) {
        Listing listing = findListing(id);
        if (listing == null || !listing.sellerId().equals(player.getUniqueId())) {
            message(player, "&cThat claim does not belong to you.");
            return;
        }
        try (PreparedStatement statement = database.prepareStatement(
                "UPDATE auction_listings SET status='CLAIMED' WHERE id=? AND seller_uuid=? AND status='EXPIRED'")) {
            statement.setLong(1, id);
            statement.setString(2, player.getUniqueId().toString());
            if (statement.executeUpdate() == 1) {
                giveItem(player, listing.item());
                message(player, "&aExpired item claimed.");
            } else {
                message(player, "&cThat item has already been claimed.");
            }
        } catch (SQLException exception) {
            plugin.getLogger().severe("Could not claim expired auction item: " + exception.getMessage());
            message(player, "&cCould not claim that item.");
        }
    }

    private void expireListings() {
        try (PreparedStatement statement = database.prepareStatement(
                "UPDATE auction_listings SET status='EXPIRED' WHERE status='ACTIVE' AND expires_at<=?")) {
            statement.setLong(1, System.currentTimeMillis());
            statement.executeUpdate();
        } catch (SQLException exception) {
            plugin.getLogger().warning("Could not expire auction listings: " + exception.getMessage());
        }
    }

    private int activeCount(UUID seller) {
        try (PreparedStatement statement = database.prepareStatement(
                "SELECT COUNT(*) FROM auction_listings WHERE seller_uuid=? AND status='ACTIVE' AND expires_at>?")) {
            statement.setString(1, seller.toString());
            statement.setLong(2, System.currentTimeMillis());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getInt(1) : 0;
            }
        } catch (SQLException exception) {
            plugin.getLogger().warning("Could not count auction listings: " + exception.getMessage());
            return Integer.MAX_VALUE;
        }
    }

    private List<Listing> loadListings(String status, UUID seller) {
        List<Listing> listings = new ArrayList<>();
        String sql = "SELECT id,seller_uuid,seller_name,item,price,created_at,expires_at FROM auction_listings WHERE status=?"
                + (seller == null ? "" : " AND seller_uuid=?");
        try (PreparedStatement statement = database.prepareStatement(sql)) {
            statement.setString(1, status);
            if (seller != null) statement.setString(2, seller.toString());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    try {
                        listings.add(new Listing(result.getLong("id"), UUID.fromString(result.getString("seller_uuid")),
                                result.getString("seller_name"), ItemStack.deserializeBytes(result.getBytes("item")),
                                result.getDouble("price"), result.getLong("created_at"), result.getLong("expires_at")));
                    } catch (RuntimeException exception) {
                        plugin.getLogger().warning("Skipping unreadable auction listing #" + result.getLong("id"));
                    }
                }
            }
        } catch (SQLException exception) {
            plugin.getLogger().severe("Could not load auction listings: " + exception.getMessage());
        }
        return listings;
    }

    private Listing findListing(long id) {
        try (PreparedStatement statement = database.prepareStatement(
                "SELECT id,seller_uuid,seller_name,item,price,created_at,expires_at FROM auction_listings WHERE id=?")) {
            statement.setLong(1, id);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return null;
                return new Listing(result.getLong("id"), UUID.fromString(result.getString("seller_uuid")),
                        result.getString("seller_name"), ItemStack.deserializeBytes(result.getBytes("item")),
                        result.getDouble("price"), result.getLong("created_at"), result.getLong("expires_at"));
            }
        } catch (SQLException | RuntimeException exception) {
            plugin.getLogger().warning("Could not read auction listing #" + id + ": " + exception.getMessage());
            return null;
        }
    }

    private ItemStack listingDisplay(Listing listing, boolean own) {
        ItemStack display = listing.item().clone();
        ItemMeta meta = display.getItemMeta();
        List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        lore.add("");
        lore.add(color("&8&m-------------------------"));
        lore.add(color("&fPrice: &a" + money(listing.price())));
        lore.add(color("&fSeller: &7" + listing.sellerName()));
        lore.add(color("&fListed: &7" + timeAgo(listing.createdAt())));
        lore.add(color("&fExpires: &7" + timeLeft(listing.expiresAt())));
        lore.add(color("&8&m-------------------------"));
        lore.add(color(own ? "&cClick to cancel this listing." : "&aClick to view purchase options."));
        meta.setLore(lore);
        meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "auction_listing_id"),
                PersistentDataType.LONG, listing.id());
        display.setItemMeta(meta);
        return display;
    }

    private long listingId(ItemStack item) {
        if (!item.hasItemMeta()) return -1;
        Long id = item.getItemMeta().getPersistentDataContainer().get(
                new NamespacedKey(plugin, "auction_listing_id"), PersistentDataType.LONG);
        return id == null ? -1 : id;
    }

    private ItemStack button(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(color(name));
        List<String> lines = new ArrayList<>();
        for (String line : lore) lines.add(color(line));
        meta.setLore(lines);
        item.setItemMeta(meta);
        return item;
    }

    private void fill(Inventory inventory) {
        ItemStack pane = button(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int slot = Math.max(0, inventory.getSize() - 9); slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, pane);
        }
    }

    private void giveItem(Player player, ItemStack item) {
        for (ItemStack leftover : player.getInventory().addItem(item.clone()).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
    }

    private void sortListings(List<Listing> listings, String sort) {
        Comparator<Listing> comparator = switch (sort) {
            case "PRICE_LOW" -> Comparator.comparingDouble(Listing::price);
            case "PRICE_HIGH" -> Comparator.comparingDouble(Listing::price).reversed();
            case "NAME" -> Comparator.comparing(listing -> itemName(listing.item()).toLowerCase(Locale.ROOT));
            default -> Comparator.comparingLong(Listing::createdAt).reversed();
        };
        listings.sort(comparator);
    }

    private String nextSort(String sort) {
        return switch (sort) {
            case "NEWEST" -> "PRICE_LOW";
            case "PRICE_LOW" -> "PRICE_HIGH";
            case "PRICE_HIGH" -> "NAME";
            default -> "NEWEST";
        };
    }

    private String sortLabel(String sort) {
        return switch (sort) {
            case "PRICE_LOW" -> "Lowest price";
            case "PRICE_HIGH" -> "Highest price";
            case "NAME" -> "Item name";
            default -> "Newest";
        };
    }

    private String itemName(ItemStack item) {
        if (item.hasItemMeta() && item.getItemMeta().hasDisplayName()) return ChatColor.stripColor(item.getItemMeta().getDisplayName());
        String name = item.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1);
    }

    private String money(double amount) {
        String symbol = plugin.getConfig().getString("currency.symbol", "$");
        return symbol + new DecimalFormat("#,##0.00").format(amount);
    }

    private String timeAgo(long timestamp) {
        long minutes = Math.max(0, (System.currentTimeMillis() - timestamp) / 60_000L);
        if (minutes < 1) return "just now";
        if (minutes < 60) return minutes + "m ago";
        long hours = minutes / 60;
        if (hours < 24) return hours + "h ago";
        return (hours / 24) + "d ago";
    }

    private String timeLeft(long timestamp) {
        long hours = Math.max(0, (timestamp - System.currentTimeMillis()) / 3_600_000L);
        if (hours < 1) return "less than 1h";
        if (hours < 24) return hours + "h";
        return (hours / 24) + "d " + (hours % 24) + "h";
    }

    private void message(Player player, String text) {
        player.sendMessage(color("&8[&fJSMP&8] &r" + text));
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!(event.getView().getTopInventory().getHolder(false) instanceof MenuHolder holder)) return;
        event.setCancelled(true);
        if (event.getClickedInventory() != event.getView().getTopInventory()) return;
        handleClick(player, holder, event.getRawSlot(), event.getCurrentItem());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof MenuHolder) event.setCancelled(true);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player && searching.contains(player.getUniqueId())) {
            // Keep the search prompt active after the menu closes.
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChatSearch(AsyncChatEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        boolean sellPrice = pendingSell.remove(uuid);
        boolean searchQuery = !sellPrice && searching.remove(uuid);
        if (!sellPrice && !searchQuery) return;
        event.setCancelled(true);
        String input = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null) return;
            if (input.equalsIgnoreCase("cancel") || input.isBlank()) {
                message(player, "&7Action cancelled.");
                openBrowse(player, 0, "", "NEWEST");
                return;
            }
            if (sellPrice) {
                try {
                    createListing(player, Double.parseDouble(input));
                } catch (NumberFormatException exception) {
                    message(player, "&cInvalid price. Open the sell menu and try again.");
                    openSellMenu(player);
                }
            } else {
                openBrowse(player, 0, input, "NEWEST");
            }
        });
    }
}
