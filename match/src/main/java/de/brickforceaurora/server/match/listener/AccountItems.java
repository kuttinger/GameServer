package de.brickforceaurora.server.match.listener;

import de.brickforceaurora.server.account.AccountClient.Item;
import de.brickforceaurora.server.net.protocol.clientbound.original.ClientboundItemListPacket;
import de.brickforceaurora.server.net.protocol.data.ItemUsage;

/**
 * Conversion of account service items to protocol data.
 *
 * Lives outside of the {@code *_} listener classes because those are renamed during the build and can't be referenced.
 */
public final class AccountItems {

    public static final int DEFAULT_DURABILITY = 1000;

    private AccountItems() {
        throw new UnsupportedOperationException();
    }

    public static ClientboundItemListPacket.ItemEntry toItemEntry(final Item item) {
        ItemUsage usage = "EQUIP".equals(item.usage()) ? ItemUsage.EQUIP : ItemUsage.UNEQUIP;
        return new ClientboundItemListPacket.ItemEntry(item.seq(), item.code(), usage, remain(item), (byte) 0, DEFAULT_DURABILITY);
    }

    /**
     * @return remaining seconds as the client expects them, {@code -1} = permanent
     */
    public static int remain(final Item item) {
        return item.remainSeconds() < 0 ? -1 : (int) Math.min(Integer.MAX_VALUE, item.remainSeconds());
    }

}
