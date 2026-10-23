package de.brickforceaurora.server.match.listener;

import de.brickforceaurora.server.ServerOptions;
import de.brickforceaurora.server.account.AccountClient.Account;
import de.brickforceaurora.server.account.AccountClient.Profile;
import de.brickforceaurora.server.match.MatchServerApp;
import de.brickforceaurora.server.net.BFClient;
import de.brickforceaurora.server.net.NetSignal;
import de.brickforceaurora.server.net.login.AccountLoginHandler;
import de.brickforceaurora.server.net.protocol.IClientboundPacket;
import de.brickforceaurora.server.net.protocol.clientbound.original.*;
import de.brickforceaurora.server.net.protocol.data.*;
import de.brickforceaurora.server.util.flag.IFlags;
import me.lauriichan.snowframe.extension.Extension;
import me.lauriichan.snowframe.signal.ISignalHandler;
import me.lauriichan.snowframe.signal.SignalContext;
import me.lauriichan.snowframe.signal.SignalHandler;

import java.util.ArrayList;
import java.util.List;

@Extension
public class ClientListener_ implements ISignalHandler {

    private static final IFlags<GameMode> NO_GAMEMODES = GameMode.MANAGER.newImmutable(0);
    private static final IFlags<CommonOption> DEFAULT_COMMON_OPTIONS = CommonOption.MANAGER.newMutable().setAll(true, new CommonOption[] {
        CommonOption.DONOT_NEWBIE_CHANNEL_MSG,
        CommonOption.DONOT_BUNGEE_GUIDE,
        CommonOption.DONOT_MAPEDIT_GUIDE,
        CommonOption.DONOT_BND_GUIDE,
        CommonOption.DONOT_EXPLOSION_ATTACK_GUIDE,
        CommonOption.DONOT_EXPLOSION_DEFENCE_GUIDE,
        CommonOption.DONOT_BATTLE_GUIDE,
        CommonOption.DONOT_ZOMBIE_GUIDE,
        CommonOption.DONOT_FLAG_GUIDE,
        CommonOption.DONOT_DEFENSE_GUIDE,
        CommonOption.DONOT_ESCAPE_GUIDE
    }).immutable();
    private static final IFlags<Tutorial> TUTORIALS_DONE = Tutorial.MANAGER.newMutable().setAll(true, Tutorial.BATTLE, Tutorial.BUILD)
        .immutable();

    @SignalHandler
    public void onLogin(SignalContext<NetSignal.ClientLoggedIn> context) {
        BFClient client = context.signal().client();
        ServerOptions options = ((MatchServerApp) context.signal().netManager().snowFrame().app()).options();
        ChannelInfo channel = new ChannelInfo(1, ChannelMode.BATTLE, options.name(), options.publicHost(), options.port(), 1, 16, 1, 0, 66,
            0, 0, 0);
        Profile profile = client.attr(AccountLoginHandler.ATTR_PROFILE, Profile.class);
        if (profile != null) {
            sendAccountLogin(client, channel, profile);
            return;
        }
        //TODO: Hardcoded info needs to change
        client.send(new IClientboundPacket[] {
            new ClientboundChannelPacket().channels(new ChannelInfo[] {
                channel
            }),
            new ClientboundCurChannelPacket().curChannelId(1),
            new ClientboundPlayerInitInfoPacket().xp(9400000).tutorials(TUTORIALS_DONE).tosAccepted(true).extraSlots(9).firstLoginFp(0)
                .countryFilter(CountryFilter.NONE).rank(65),
            new ClientboundPlayerOptPacket().modeOptions(NO_GAMEMODES).quickPlayOption(QuickPlayOption.ALL_MAPS)
                .commonOptions(DEFAULT_COMMON_OPTIONS),
            new ClientboundPlayerInfoPacket().nickname(client.name()).xp(9400000).forcePoints(100000000).brickPoints(0).tokens(100000000)
                .coins(100000000).starDust(0).gm(0).clanLv(0).clanMark(0).clanSeq(0).clanName("Clan").assault(0).heavy(0).handGun(0)
                .sniper(0).melee(0).special(0).subMachine(0).apsType(6).apsLevel(5).rank(65),
            new ClientboundLoginPacket().clientId(client.id()).channelId(1),
            new ClientboundItemListPacket().items(List.of(
                    new ClientboundItemListPacket.ItemEntry(0, "wau", ItemUsage.EQUIP, -1, (byte) 0, 1000),
                    new ClientboundItemListPacket.ItemEntry(1, "wax", ItemUsage.EQUIP, -1, (byte) 0, 1000),
                    new ClientboundItemListPacket.ItemEntry(2, "wba", ItemUsage.EQUIP, -1, (byte) 0, 1000),
                    new ClientboundItemListPacket.ItemEntry(3, "wap", ItemUsage.EQUIP, -1, (byte) 0, 1000),
                    new ClientboundItemListPacket.ItemEntry(4, "aac", ItemUsage.EQUIP, -1, (byte) 0, 1000),
                    new ClientboundItemListPacket.ItemEntry(5, "aad", ItemUsage.EQUIP, -1, (byte) 0, 1000),
                    new ClientboundItemListPacket.ItemEntry(6, "s07", ItemUsage.EQUIP, -1, (byte) 0, 1000),
                    new ClientboundItemListPacket.ItemEntry(7, "sb7", ItemUsage.UNEQUIP, -1, (byte) 0, 1000),
                    new ClientboundItemListPacket.ItemEntry(8, "sb8", ItemUsage.UNEQUIP, -1, (byte) 0, 1000),
                    new ClientboundItemListPacket.ItemEntry(9, "sb9", ItemUsage.UNEQUIP, -1, (byte) 0, 1000),
                    new ClientboundItemListPacket.ItemEntry(10, "sc0", ItemUsage.UNEQUIP, -1, (byte) 0, 1000),
                    new ClientboundItemListPacket.ItemEntry(11, "sc1", ItemUsage.UNEQUIP, -1, (byte) 0, 1000),
                    new ClientboundItemListPacket.ItemEntry(12, "sc3", ItemUsage.UNEQUIP, -1, (byte) 0, 1000),
                    new ClientboundItemListPacket.ItemEntry(13, "sc4", ItemUsage.UNEQUIP, -1, (byte) 0, 1000),
                    new ClientboundItemListPacket.ItemEntry(14, "s34", ItemUsage.UNEQUIP, -1, (byte) 0, 1000),
                    new ClientboundItemListPacket.ItemEntry(15, "s43", ItemUsage.UNEQUIP, -1, (byte) 0, 1000),
                    new ClientboundItemListPacket.ItemEntry(16, "s78", ItemUsage.UNEQUIP, -1, (byte) 0, 1000),
                    new ClientboundItemListPacket.ItemEntry(17, "s79", ItemUsage.UNEQUIP, -1, (byte) 0, 1000),
                    new ClientboundItemListPacket.ItemEntry(18, "s22", ItemUsage.UNEQUIP, -1, (byte) 0, 1000),
                    new ClientboundItemListPacket.ItemEntry(19, "s06", ItemUsage.UNEQUIP, -1, (byte) 0, 1000),
                    new ClientboundItemListPacket.ItemEntry(20, "s05", ItemUsage.UNEQUIP, -1, (byte) 0, 1000),
                    new ClientboundItemListPacket.ItemEntry(21, "s27", ItemUsage.UNEQUIP, -1, (byte) 0, 1000)))
        });
    }

    private void sendAccountLogin(BFClient client, ChannelInfo channel, Profile profile) {
        Account account = profile.account();
        // TODO: rank and per weapon stats are not tracked by the account service yet
        client.send(new IClientboundPacket[] {
            new ClientboundChannelPacket().channels(new ChannelInfo[] {
                channel
            }),
            new ClientboundCurChannelPacket().curChannelId(channel.id()),
            new ClientboundPlayerInitInfoPacket().xp((int) Math.min(Integer.MAX_VALUE, account.xp())).tutorials(TUTORIALS_DONE)
                .tosAccepted(true).extraSlots(9).firstLoginFp(0).countryFilter(CountryFilter.NONE).rank(0),
            new ClientboundPlayerOptPacket().modeOptions(NO_GAMEMODES).quickPlayOption(QuickPlayOption.ALL_MAPS)
                .commonOptions(DEFAULT_COMMON_OPTIONS),
            new ClientboundPlayerInfoPacket().nickname(client.name()).xp((int) Math.min(Integer.MAX_VALUE, account.xp()))
                .forcePoints(account.forcePoints()).brickPoints(account.brickPoints()).tokens(account.tokens()).coins(account.coins())
                .starDust(account.starDust()).gm(0).clanLv(0).clanMark(0).clanSeq(0).clanName("").assault(0).heavy(0).handGun(0)
                .sniper(0).melee(0).special(0).subMachine(0).apsType(6).apsLevel(5).rank(0),
            new ClientboundLoginPacket().clientId(client.id()).channelId(channel.id()),
            new ClientboundItemListPacket().items(profile.items().stream().map(AccountItems::toItemEntry).toList())
        });
    }

}
