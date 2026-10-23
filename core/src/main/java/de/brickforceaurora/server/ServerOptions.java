package de.brickforceaurora.server;

/**
 * Command line / environment options shared by all server apps.
 *
 * <pre>
 * --port=&lt;port&gt;             (env AURORA_PORT, default 18890)
 * --account-url=&lt;url&gt;       (env AURORA_ACCOUNT_URL, e.g. http://127.0.0.1:18900; without it the dev login is used)
 * --public-host=&lt;host&gt;      (env AURORA_PUBLIC_HOST, address announced in the channel list, default 127.0.0.1)
 * --name=&lt;name&gt;             (env AURORA_SERVER_NAME, channel name, default "Aurora")
 * --bind=&lt;address&gt;          (env AURORA_BIND, default 0.0.0.0; player hosted lobbies use 127.0.0.1 so the server is only
 *                            reachable from the own pc / the steam relay and the hosts ip and port are never exposed)
 * </pre>
 */
public record ServerOptions(int port, String accountUrl, String publicHost, String name, String bindAddress) {

    public static ServerOptions parse(final String[] args) {
        return new ServerOptions(Integer.parseInt(option(args, "port", "AURORA_PORT", "18890")),
            option(args, "account-url", "AURORA_ACCOUNT_URL", null), option(args, "public-host", "AURORA_PUBLIC_HOST", "127.0.0.1"),
            option(args, "name", "AURORA_SERVER_NAME", "Aurora"), option(args, "bind", "AURORA_BIND", "0.0.0.0"));
    }

    public boolean hasAccountService() {
        return accountUrl != null && !accountUrl.isBlank();
    }

    private static String option(final String[] args, final String name, final String env, final String fallback) {
        final String prefix = "--" + name + "=";
        for (final String arg : args) {
            if (arg.startsWith(prefix)) {
                return arg.substring(prefix.length());
            }
        }
        final String value = System.getenv(env);
        if (value != null && !value.isBlank()) {
            return value;
        }
        return fallback;
    }

}
