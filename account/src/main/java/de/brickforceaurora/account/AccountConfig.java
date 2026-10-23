package de.brickforceaurora.account;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * Configuration of the account service.
 *
 * Values are read from a properties file and can be overridden by environment variables (e.g. {@code AURORA_ACCOUNT_PORT} overrides
 * {@code port}).
 */
public record AccountConfig(String host, int port, Path databaseFile, Path shopFile, String serverKey, long sessionHours,
    long ticketHours, int startForcePoints, int startTokens, List<String> starterItems) {

    public static final List<String> DEFAULT_STARTER_ITEMS = List.of("wau:EQUIP", "wax:EQUIP", "wba:EQUIP", "wap:EQUIP", "aac:EQUIP",
        "aad:EQUIP", "s07:EQUIP");

    public static AccountConfig defaults() {
        return new AccountConfig("0.0.0.0", 18900, Path.of("data", "accounts.db"), Path.of("data", "shop.txt"), null, 24 * 30, 6,
            100000, 1000, DEFAULT_STARTER_ITEMS);
    }

    public static AccountConfig load(final Path file) throws IOException {
        final Properties properties = new Properties();
        if (file != null && Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file)) {
                properties.load(reader);
            }
        }
        final AccountConfig def = defaults();
        return new AccountConfig(value(properties, "host", def.host()), Integer.parseInt(value(properties, "port", def.port())),
            Path.of(value(properties, "database.file", def.databaseFile())), Path.of(value(properties, "shop.file", def.shopFile())),
            value(properties, "server.key", null), Long.parseLong(value(properties, "session.hours", def.sessionHours())),
            Long.parseLong(value(properties, "ticket.hours", def.ticketHours())),
            Integer.parseInt(value(properties, "start.force_points", def.startForcePoints())),
            Integer.parseInt(value(properties, "start.tokens", def.startTokens())),
            Arrays.stream(value(properties, "start.items", String.join(",", def.starterItems())).split(",")).map(String::trim)
                .filter(s -> !s.isEmpty()).toList());
    }

    private static String value(final Properties properties, final String key, final Object fallback) {
        final String env = System.getenv("AURORA_ACCOUNT_" + key.toUpperCase().replace('.', '_'));
        if (env != null && !env.isBlank()) {
            return env;
        }
        final String value = properties.getProperty(key);
        if (value != null && !value.isBlank()) {
            return value.trim();
        }
        return fallback == null ? null : fallback.toString();
    }

}
