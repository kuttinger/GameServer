package de.brickforceaurora.server.net.login;

import de.brickforceaurora.server.account.AccountClient;
import de.brickforceaurora.server.account.AccountClient.AccountServiceException;
import de.brickforceaurora.server.account.AccountClient.Profile;
import de.brickforceaurora.server.net.BFClient;
import de.brickforceaurora.server.net.NetManager;
import de.brickforceaurora.server.util.flag.IFlags;
import me.lauriichan.snowframe.util.Version;

/**
 * Logs players in with a play ticket of the Aurora account service.
 *
 * The verified profile is stored on the client ({@link #ATTR_PROFILE}) together with the ticket ({@link #ATTR_TICKET}) so listeners
 * can send the real inventory and route shop purchases through the account service.
 */
public final class AccountLoginHandler implements ILoginHandler {

    public static final String ATTR_PROFILE = "account.profile";
    public static final String ATTR_TICKET = "account.ticket";

    private final NetManager<?> netManager;
    private final AccountClient accountClient;
    private final IFlags<LoginType> supportedTypes = LoginType.MANAGER.newMutable().set(LoginType.ACCOUNT_TICKET, true).immutable();

    public AccountLoginHandler(final NetManager<?> netManager, final AccountClient accountClient) {
        this.netManager = netManager;
        this.accountClient = accountClient;
    }

    public AccountClient accountClient() {
        return accountClient;
    }

    @Override
    public IFlags<LoginType> supportedTypes() {
        return supportedTypes;
    }

    @Override
    public void login(final BFClient client, final Version version, final LoginType loginType, final String userName,
        final ILoginData loginData) {
        if (!(loginData instanceof ILoginData.AccountTicket(String ticket))) {
            return;
        }
        // TODO: This blocks the network thread for up to 5 seconds, should become async once the handshake supports it
        final Profile profile;
        try {
            profile = accountClient.verifyTicket(ticket);
        } catch (AccountServiceException e) {
            netManager.logger().warning("Account login of {0} failed: {1} ({2})", client, e.getMessage(), e.code());
            return;
        }
        client.attrSet(ATTR_PROFILE, profile);
        client.attrSet(ATTR_TICKET, ticket);
        client.init(version, profile.account().username(), netManager.nextClientId());
    }

}
