package de.brickforceaurora.server.match.listener;

import java.util.List;

import de.brickforceaurora.server.account.AccountClient;
import de.brickforceaurora.server.account.AccountClient.AccountServiceException;
import de.brickforceaurora.server.account.AccountClient.Item;
import de.brickforceaurora.server.account.AccountClient.LoadoutEntry;
import de.brickforceaurora.server.account.AccountClient.Purchase;
import de.brickforceaurora.server.match.MatchServerApp;
import de.brickforceaurora.server.match.room.Room;
import de.brickforceaurora.server.net.BFClient;
import de.brickforceaurora.server.net.INetListener;
import de.brickforceaurora.server.net.NetContext;
import de.brickforceaurora.server.net.PacketHandler;
import de.brickforceaurora.server.net.login.AccountLoginHandler;
import de.brickforceaurora.server.net.protocol.IClientboundPacket;
import de.brickforceaurora.server.net.protocol.clientbound.aurora.ClientboundAuroraNotificationPacket;
import de.brickforceaurora.server.net.protocol.clientbound.original.ClientboundAssetPacket;
import de.brickforceaurora.server.net.protocol.clientbound.original.ClientboundBuyItemPacket;
import de.brickforceaurora.server.net.protocol.clientbound.original.ClientboundEquipPacket;
import de.brickforceaurora.server.net.protocol.clientbound.original.ClientboundUnequipPacket;
import de.brickforceaurora.server.net.protocol.serverbound.original.ServerboundBuyItemPacket;
import de.brickforceaurora.server.net.protocol.serverbound.original.ServerboundEquipPacket;
import de.brickforceaurora.server.net.protocol.serverbound.original.ServerboundUnequipPacket;
import me.lauriichan.snowframe.SnowFrame;
import me.lauriichan.snowframe.extension.Extension;

/**
 * Shop purchases and equipment changes, persisted through the account service.
 *
 * Account service calls are done on virtual threads so the network threads are never blocked.
 */
@Extension
public class ShopListener_ implements INetListener {

    /**
     * Error seq the client interprets as "purchase failed" (same value the emulator uses).
     */
    private static final long BUY_FAILED = -3;

    private final SnowFrame<MatchServerApp> snowFrame;

    public ShopListener_(final SnowFrame<MatchServerApp> snowFrame) {
        this.snowFrame = snowFrame;
    }

    @PacketHandler
    public void onBuyItem(final NetContext<ServerboundBuyItemPacket> context) {
        BFClient client = context.client();
        ServerboundBuyItemPacket packet = context.packet();
        AccountClient accountClient = snowFrame.app().accountClient();
        String ticket = client.attr(AccountLoginHandler.ATTR_TICKET, String.class);
        if (accountClient == null || ticket == null) {
            client.send(new ClientboundAuroraNotificationPacket().message("The shop needs an account server."),
                failedPurchase(packet.code()));
            return;
        }
        // TODO: needEqup (directly equip after purchase) is ignored like in the emulator
        Thread.ofVirtual().name("purchase-", client.id()).start(() -> {
            try {
                Purchase purchase = accountClient.purchase(ticket, packet.code(), packet.option(), packet.buyHow());
                Item item = purchase.item();
                client.send(
                    new ClientboundAssetPacket().val(purchase.account().forcePoints()).val2(purchase.account().brickPoints())
                        .val3(purchase.account().tokens()).val4(purchase.account().coins()).val5(purchase.account().starDust()),
                    new ClientboundBuyItemPacket().val(item.seq()).val2(item.code()).val3(AccountItems.remain(item)).val4((byte) 0)
                        .val5(AccountItems.DEFAULT_DURABILITY));
            } catch (AccountServiceException e) {
                client.send(new ClientboundAuroraNotificationPacket().message(purchaseError(e)), failedPurchase(packet.code()));
            }
        });
    }

    private static IClientboundPacket failedPurchase(final String code) {
        return new ClientboundBuyItemPacket().val(BUY_FAILED).val2(code).val3(0).val4((byte) 0).val5(0);
    }

    private static String purchaseError(final AccountServiceException e) {
        return switch (e.code()) {
        case "insufficient_funds" -> "You don't have enough currency.";
        case "inventory_full" -> "Your inventory is full.";
        case "not_found", "not_purchasable" -> "This item can't be bought this way.";
        case "unauthorized" -> "Your session expired, please restart the game from the launcher.";
        default -> "Purchase failed: " + e.getMessage();
        };
    }

    @PacketHandler
    public void onEquip(final NetContext<ServerboundEquipPacket> context) {
        changeUsage(context.client(), context.packet().seq(), true);
    }

    @PacketHandler
    public void onUnequip(final NetContext<ServerboundUnequipPacket> context) {
        changeUsage(context.client(), context.packet().seq(), false);
    }

    private void changeUsage(final BFClient client, final long seq, final boolean equip) {
        AccountClient accountClient = snowFrame.app().accountClient();
        String ticket = client.attr(AccountLoginHandler.ATTR_TICKET, String.class);
        if (accountClient == null || ticket == null) {
            return;
        }
        // TODO: Slot conflicts (equipping a weapon into an occupied slot) need the item template table, the client resolves them
        // for now by sending an unequip for the old item first
        Thread.ofVirtual().name("loadout-", client.id()).start(() -> {
            try {
                List<Item> items = accountClient.updateLoadout(ticket, List.of(new LoadoutEntry(seq, equip ? "EQUIP" : "UNEQUIP", -1)));
                Item item = items.stream().filter(it -> it.seq() == seq).findFirst().orElse(null);
                if (item == null) {
                    return;
                }
                IClientboundPacket packet = equip ? new ClientboundEquipPacket().val(client.id()).val2(seq).val3(item.code())
                    : new ClientboundUnequipPacket().val(client.id()).val2(seq).val3(item.code());
                Room room = client.attr(Room.ATTR_ROOM, Room.class);
                if (room != null) {
                    room.broadcast(packet);
                } else {
                    client.send(packet);
                }
            } catch (AccountServiceException e) {
                client.send(new ClientboundAuroraNotificationPacket().message("Couldn't change equipment: " + e.getMessage()));
            }
        });
    }

}
