package de.brickforceaurora.account;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import de.brickforceaurora.account.Model.AccountView;
import de.brickforceaurora.account.Model.ItemView;
import de.brickforceaurora.account.Model.LegacyInventoryItem;
import de.brickforceaurora.account.Model.LoadoutEntry;
import de.brickforceaurora.account.Model.Profile;
import de.brickforceaurora.account.Model.PurchaseResponse;
import de.brickforceaurora.account.Model.SessionResponse;
import de.brickforceaurora.account.Model.TicketResponse;
import de.brickforceaurora.account.security.PasswordHasher;
import de.brickforceaurora.account.security.Tokens;
import de.brickforceaurora.account.shop.ShopCatalog;
import de.brickforceaurora.account.shop.ShopCatalog.BuyHow;
import de.brickforceaurora.account.shop.ShopCatalog.Price;

/**
 * Accounts, sessions, play tickets, wallet and inventory.
 *
 * Trust model:
 * <ul>
 * <li>A <b>session token</b> belongs to the launcher of the player and allows everything on the own account.</li>
 * <li>A <b>play ticket</b> is handed to the game server the player joins. Because those servers can be hosted by any player, a
 * ticket only allows reading the profile, buying shop items <i>for that same player</i> at catalog prices and changing the
 * loadout. It can never create currency or items for free.</li>
 * <li>The <b>server key</b> is only given to trusted (official) servers and allows granting match rewards.</li>
 * </ul>
 */
public final class AccountService implements AutoCloseable {

    public enum TokenKind {
        SESSION,
        TICKET
    }

    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_\\-]{3,16}");
    private static final Set<String> USAGES = Set.of("EQUIP", "UNEQUIP");
    private static final int MAX_ITEMS = 400;

    private final AccountConfig config;
    private final ShopCatalog catalog;
    private final Clock clock;
    private final Connection connection;

    public AccountService(final AccountConfig config, final ShopCatalog catalog, final Clock clock) {
        this.config = config;
        this.catalog = catalog;
        this.clock = clock;
        try {
            final Path parent = config.databaseFile().toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            this.connection = DriverManager.getConnection("jdbc:sqlite:" + config.databaseFile().toAbsolutePath());
            initSchema();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (SQLException e) {
            throw new IllegalStateException("Couldn't open account database", e);
        }
    }

    private void initSchema() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("""
                CREATE TABLE IF NOT EXISTS account (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    username TEXT NOT NULL UNIQUE COLLATE NOCASE,
                    password_hash TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    inventory_imported INTEGER NOT NULL DEFAULT 0,
                    xp INTEGER NOT NULL DEFAULT 0,
                    force_points INTEGER NOT NULL DEFAULT 0,
                    brick_points INTEGER NOT NULL DEFAULT 0,
                    tokens INTEGER NOT NULL DEFAULT 0,
                    coins INTEGER NOT NULL DEFAULT 0,
                    star_dust INTEGER NOT NULL DEFAULT 0
                )""");
            statement.execute("""
                CREATE TABLE IF NOT EXISTS token (
                    hash TEXT PRIMARY KEY,
                    account_id INTEGER NOT NULL REFERENCES account(id) ON DELETE CASCADE,
                    kind TEXT NOT NULL,
                    expires_at INTEGER NOT NULL
                )""");
            statement.execute("""
                CREATE TABLE IF NOT EXISTS inventory_item (
                    seq INTEGER PRIMARY KEY AUTOINCREMENT,
                    account_id INTEGER NOT NULL REFERENCES account(id) ON DELETE CASCADE,
                    code TEXT NOT NULL,
                    usage TEXT NOT NULL,
                    tool_slot INTEGER NOT NULL DEFAULT -1,
                    amount INTEGER NOT NULL DEFAULT 1,
                    expires_at INTEGER NOT NULL DEFAULT -1,
                    upgraded INTEGER NOT NULL DEFAULT 0,
                    acquired_at INTEGER NOT NULL
                )""");
            statement.execute("""
                CREATE TABLE IF NOT EXISTS purchase_log (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    account_id INTEGER NOT NULL REFERENCES account(id) ON DELETE CASCADE,
                    code TEXT NOT NULL,
                    option INTEGER NOT NULL,
                    buy_how INTEGER NOT NULL,
                    price INTEGER NOT NULL,
                    via TEXT NOT NULL,
                    created_at INTEGER NOT NULL
                )""");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_item_account ON inventory_item(account_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_token_account ON token(account_id)");
        }
    }

    private long now() {
        return clock.instant().getEpochSecond();
    }

    /*
     * Accounts
     */

    public synchronized AccountView register(final String username, final String password) {
        if (username == null || !USERNAME.matcher(username).matches()) {
            throw ApiException.badRequest("invalid_username", "Username must be 3-16 characters (letters, digits, _ and -)");
        }
        if (password == null || password.length() < 8 || password.length() > 128) {
            throw ApiException.badRequest("invalid_password", "Password must be 8-128 characters");
        }
        final String hash = PasswordHasher.hash(password);
        return transaction(() -> {
            final long id;
            try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO account (username, password_hash, created_at, force_points, tokens) VALUES (?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, username);
                statement.setString(2, hash);
                statement.setLong(3, now());
                statement.setInt(4, config.startForcePoints());
                statement.setInt(5, config.startTokens());
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    keys.next();
                    id = keys.getLong(1);
                }
            } catch (SQLException e) {
                if (e.getMessage() != null && e.getMessage().contains("UNIQUE")) {
                    throw ApiException.conflict("username_taken", "Username is already taken");
                }
                throw e;
            }
            for (final String entry : config.starterItems()) {
                final String[] parts = entry.split(":");
                final String usage = parts.length > 1 ? parts[1].toUpperCase(Locale.ROOT) : "UNEQUIP";
                insertItem(id, parts[0].toLowerCase(Locale.ROOT), USAGES.contains(usage) ? usage : "UNEQUIP", -1, 1, -1, false);
            }
            return account(id);
        });
    }

    public synchronized SessionResponse login(final String username, final String password) {
        final long id;
        final String hash;
        try (PreparedStatement statement = connection.prepareStatement("SELECT id, password_hash FROM account WHERE username = ?")) {
            statement.setString(1, username);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    // Hash anyway so response time doesn't reveal which usernames exist
                    PasswordHasher.hash(password == null ? "" : password);
                    throw ApiException.unauthorized();
                }
                id = result.getLong(1);
                hash = result.getString(2);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        if (!PasswordHasher.verify(password, hash)) {
            throw ApiException.unauthorized();
        }
        final long expiresAt = now() + config.sessionHours() * 3600;
        final String token = issueToken(id, TokenKind.SESSION, expiresAt);
        return new SessionResponse(token, expiresAt, account(id));
    }

    public synchronized void logout(final String sessionToken) {
        execute("DELETE FROM token WHERE hash = ?", Tokens.hash(sessionToken));
    }

    public synchronized TicketResponse createTicket(final long accountId) {
        final long expiresAt = now() + config.ticketHours() * 3600;
        return new TicketResponse(issueToken(accountId, TokenKind.TICKET, expiresAt), expiresAt);
    }

    /**
     * @return the account id the token belongs to
     *
     * @throws ApiException if the token is unknown, expired or of another kind
     */
    public synchronized long authenticate(final String token, final TokenKind kind) {
        if (token == null || token.isBlank()) {
            throw ApiException.unauthorized();
        }
        try (PreparedStatement statement = connection.prepareStatement("SELECT account_id, kind, expires_at FROM token WHERE hash = ?")) {
            statement.setString(1, Tokens.hash(token));
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || !kind.name().equals(result.getString(2)) || result.getLong(3) < now()) {
                    throw ApiException.unauthorized();
                }
                return result.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public boolean isServerKey(final String key) {
        return config.serverKey() != null && !config.serverKey().isBlank() && Tokens.constantTimeEquals(config.serverKey(), key);
    }

    private String issueToken(final long accountId, final TokenKind kind, final long expiresAt) {
        execute("DELETE FROM token WHERE expires_at < ?", now());
        final String token = Tokens.generate();
        execute("INSERT INTO token (hash, account_id, kind, expires_at) VALUES (?, ?, ?, ?)", Tokens.hash(token), accountId, kind.name(),
            expiresAt);
        return token;
    }

    public synchronized AccountView account(final long id) {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT id, username, xp, force_points, brick_points, tokens, coins, star_dust FROM account WHERE id = ?")) {
            statement.setLong(1, id);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw ApiException.notFound("Account not found");
                }
                return new AccountView(result.getLong(1), result.getString(2), result.getLong(3), result.getInt(4), result.getInt(5),
                    result.getInt(6), result.getInt(7), result.getInt(8));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public synchronized Profile profile(final long id) {
        return new Profile(account(id), inventory(id));
    }

    /*
     * Inventory
     */

    public synchronized List<ItemView> inventory(final long accountId) {
        final long now = now();
        execute("DELETE FROM inventory_item WHERE account_id = ? AND expires_at >= 0 AND expires_at < ?", accountId, now);
        final List<ItemView> items = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT seq, code, usage, tool_slot, amount, expires_at, upgraded FROM inventory_item WHERE account_id = ? ORDER BY seq")) {
            statement.setLong(1, accountId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    items.add(itemView(result, now));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return items;
    }

    private static ItemView itemView(final ResultSet result, final long now) throws SQLException {
        final long expiresAt = result.getLong(6);
        return new ItemView(result.getLong(1), result.getString(2), result.getString(3), result.getInt(4), result.getInt(5),
            expiresAt < 0 ? -1 : Math.max(0, expiresAt - now), result.getInt(7) != 0);
    }

    private ItemView item(final long seq) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT seq, code, usage, tool_slot, amount, expires_at, upgraded FROM inventory_item WHERE seq = ?")) {
            statement.setLong(1, seq);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return itemView(result, now());
            }
        }
    }

    private long insertItem(final long accountId, final String code, final String usage, final int toolSlot, final int amount,
        final long durationSeconds, final boolean upgraded) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "INSERT INTO inventory_item (account_id, code, usage, tool_slot, amount, expires_at, upgraded, acquired_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            Statement.RETURN_GENERATED_KEYS)) {
            final long now = now();
            statement.setLong(1, accountId);
            statement.setString(2, code);
            statement.setString(3, usage);
            statement.setInt(4, toolSlot);
            statement.setInt(5, amount);
            statement.setLong(6, durationSeconds < 0 ? -1 : now + durationSeconds);
            statement.setInt(7, upgraded ? 1 : 0);
            statement.setLong(8, now);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private int itemCount(final long accountId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM inventory_item WHERE account_id = ?")) {
            statement.setLong(1, accountId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    public synchronized List<ItemView> updateLoadout(final long accountId, final List<LoadoutEntry> entries) {
        if (entries == null) {
            throw ApiException.badRequest("invalid_loadout", "Missing items");
        }
        return transaction(() -> {
            for (final LoadoutEntry entry : entries) {
                final String usage = entry.usage() == null ? "" : entry.usage().toUpperCase(Locale.ROOT);
                if (!USAGES.contains(usage)) {
                    throw ApiException.badRequest("invalid_usage", "Usage must be EQUIP or UNEQUIP");
                }
                try (PreparedStatement statement = connection
                    .prepareStatement("UPDATE inventory_item SET usage = ?, tool_slot = ? WHERE seq = ? AND account_id = ?")) {
                    statement.setString(1, usage);
                    statement.setInt(2, entry.toolSlot());
                    statement.setLong(3, entry.seq());
                    statement.setLong(4, accountId);
                    if (statement.executeUpdate() == 0) {
                        throw ApiException.notFound("Item " + entry.seq() + " not found");
                    }
                }
            }
            return inventory(accountId);
        });
    }

    /**
     * One-time import of the inventory the old emulator stored locally ({@code Config\Inventory.json}).
     *
     * Only item codes that exist in the shop catalog are accepted so the import can't be used to create unobtainable items.
     */
    public synchronized List<ItemView> importLegacyInventory(final long accountId, final List<LegacyInventoryItem> items) {
        if (items == null) {
            throw ApiException.badRequest("invalid_inventory", "Missing items");
        }
        return transaction(() -> {
            try (PreparedStatement statement = connection
                .prepareStatement("UPDATE account SET inventory_imported = 1 WHERE id = ? AND inventory_imported = 0")) {
                statement.setLong(1, accountId);
                if (statement.executeUpdate() == 0) {
                    throw ApiException.conflict("already_imported", "The local inventory was already imported");
                }
            }
            int count = itemCount(accountId);
            for (final LegacyInventoryItem item : items) {
                if (count >= MAX_ITEMS) {
                    break;
                }
                if (!catalog.contains(item.code())) {
                    continue;
                }
                final String usage = item.usage() == null ? "UNEQUIP" : item.usage().toUpperCase(Locale.ROOT);
                final int remain = item.remain() == null ? -1 : item.remain();
                insertItem(accountId, item.code().toLowerCase(Locale.ROOT), USAGES.contains(usage) ? usage : "UNEQUIP",
                    item.toolslot() == null ? -1 : item.toolslot(), item.amount() == null ? 1 : Math.max(1, item.amount()),
                    remain < 0 ? -1 : remain, Boolean.TRUE.equals(item.is_upgraded()));
                count++;
            }
            return inventory(accountId);
        });
    }

    /*
     * Shop
     */

    public synchronized PurchaseResponse purchase(final long accountId, final String code, final int option, final int buyHowId,
        final String via) {
        final BuyHow buyHow = BuyHow.byId(buyHowId);
        if (buyHow == null) {
            throw ApiException.badRequest("invalid_buy_how", "Unknown payment type");
        }
        final Price price = catalog.find(code, option);
        if (price == null) {
            throw ApiException.notFound("Item is not sold with this option");
        }
        final int cost = price.price(buyHow);
        if (cost <= 0) {
            throw ApiException.badRequest("not_purchasable", "Item can't be bought with this currency");
        }
        final String column = switch (buyHow) {
        case GENERAL_POINT -> "force_points";
        case BRICK_POINT -> "brick_points";
        case CASH_POINT -> "tokens";
        };
        return transaction(() -> {
            if (itemCount(accountId) >= MAX_ITEMS) {
                throw ApiException.conflict("inventory_full", "Inventory is full");
            }
            try (PreparedStatement statement = connection
                .prepareStatement("UPDATE account SET " + column + " = " + column + " - ? WHERE id = ? AND " + column + " >= ?")) {
                statement.setInt(1, cost);
                statement.setLong(2, accountId);
                statement.setInt(3, cost);
                if (statement.executeUpdate() == 0) {
                    throw ApiException.conflict("insufficient_funds", "Not enough currency");
                }
            }
            final long seq = insertItem(accountId, price.code(), "UNEQUIP", -1, 1, price.durationSeconds(), false);
            execute("INSERT INTO purchase_log (account_id, code, option, buy_how, price, via, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                accountId, price.code(), option, buyHowId, cost, via, now());
            return new PurchaseResponse(item(seq), account(accountId));
        });
    }

    /*
     * Rewards (trusted servers only)
     */

    public synchronized AccountView reward(final long accountId, final long xp, final int forcePoints) {
        if (xp < 0 || forcePoints < 0 || xp > 1_000_000 || forcePoints > 1_000_000) {
            throw ApiException.badRequest("invalid_reward", "Reward out of range");
        }
        execute("UPDATE account SET xp = xp + ?, force_points = force_points + ? WHERE id = ?", xp, forcePoints, accountId);
        return account(accountId);
    }

    /*
     * Helpers
     */

    @FunctionalInterface
    private interface SqlSupplier<T> {
        T get() throws SQLException;
    }

    private <T> T transaction(final SqlSupplier<T> body) {
        try {
            connection.setAutoCommit(false);
            try {
                final T value = body.get();
                connection.commit();
                return value;
            } catch (RuntimeException | SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private void execute(final String sql, final Object... args) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public synchronized void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

}
