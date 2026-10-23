package de.brickforceaurora.account;

import java.util.List;

/**
 * JSON data exchanged with the launcher, the game client and game servers.
 */
public final class Model {

    private Model() {
        throw new UnsupportedOperationException();
    }

    public record AccountView(long id, String username, long xp, int forcePoints, int brickPoints, int tokens, int coins, int starDust) {}

    /**
     * @param remainSeconds remaining lifetime in seconds, {@code -1} = permanent
     */
    public record ItemView(long seq, String code, String usage, int toolSlot, int amount, long remainSeconds, boolean upgraded) {}

    public record Credentials(String username, String password) {}

    public record SessionResponse(String token, long expiresAt, AccountView account) {}

    public record TicketResponse(String ticket, long expiresAt) {}

    public record Profile(AccountView account, List<ItemView> items) {}

    public record PurchaseRequest(String code, int option, int buyHow) {}

    public record PurchaseResponse(ItemView item, AccountView account) {}

    public record LoadoutEntry(long seq, String usage, int toolSlot) {}

    public record LoadoutRequest(List<LoadoutEntry> items) {}

    public record RewardRequest(long accountId, long xp, int forcePoints) {}

    /**
     * One entry of the emulator's local {@code Config\Inventory.json}.
     */
    public record LegacyInventoryItem(String code, String usage, Integer toolslot, Integer amount, Integer remain, Boolean is_upgraded) {}

    public record ErrorResponse(String error, String message) {}

}
