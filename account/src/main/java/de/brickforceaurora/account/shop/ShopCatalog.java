package de.brickforceaurora.account.shop;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Server-side price list, parsed from the game's {@code shop.txt}
 * ({@code itemCode, option, forcepoints, brickpoint, tokens, cashback}).
 *
 * The service never trusts prices sent by a client or a player hosted server.
 */
public final class ShopCatalog {

    /**
     * Mirrors {@code Good.BUY_HOW} of the client.
     */
    public enum BuyHow {
        GENERAL_POINT,
        BRICK_POINT,
        CASH_POINT;

        public static BuyHow byId(final int id) {
            final BuyHow[] values = values();
            if (id < 0 || id >= values.length) {
                return null;
            }
            return values[id];
        }
    }

    public record Price(String code, int option, int forcePoints, int brickPoints, int tokens) {

        public int price(final BuyHow how) {
            return switch (how) {
            case GENERAL_POINT -> forcePoints;
            case BRICK_POINT -> brickPoints;
            case CASH_POINT -> tokens;
            };
        }

        /**
         * Same rule as the emulator: option is the duration in days, everything above 30 days is permanent.
         *
         * @return remaining seconds or {@code -1} for permanent
         */
        public long durationSeconds() {
            return option > 30 ? -1 : option * 86400L;
        }

    }

    private final Map<String, List<Price>> prices;

    private ShopCatalog(final Map<String, List<Price>> prices) {
        this.prices = prices;
    }

    public static ShopCatalog empty() {
        return new ShopCatalog(Collections.emptyMap());
    }

    public static ShopCatalog load(final Path file) {
        try {
            return parse(Files.readAllLines(file));
        } catch (IOException e) {
            throw new UncheckedIOException("Couldn't read shop file '" + file + "'", e);
        }
    }

    public static ShopCatalog parse(final List<String> lines) {
        final Map<String, List<Price>> prices = new HashMap<>();
        for (int i = 1; i < lines.size(); i++) {
            final String line = lines.get(i).trim();
            if (line.isEmpty()) {
                continue;
            }
            final String[] parts = line.split(",");
            if (parts.length < 5) {
                continue;
            }
            final String code = parts[0].trim().toLowerCase(Locale.ROOT);
            final Price price = new Price(code, parseInt(parts[1]), parseInt(parts[2]), parseInt(parts[3]), parseInt(parts[4]));
            prices.computeIfAbsent(code, _ -> new ArrayList<>()).add(price);
        }
        return new ShopCatalog(prices);
    }

    private static int parseInt(final String value) {
        return Integer.parseInt(value.trim());
    }

    public Price find(final String code, final int option) {
        if (code == null) {
            return null;
        }
        final List<Price> list = prices.get(code.toLowerCase(Locale.ROOT));
        if (list == null) {
            return null;
        }
        for (final Price price : list) {
            if (price.option() == option) {
                return price;
            }
        }
        return null;
    }

    public boolean contains(final String code) {
        return code != null && prices.containsKey(code.toLowerCase(Locale.ROOT));
    }

    public Collection<List<Price>> all() {
        return Collections.unmodifiableCollection(prices.values());
    }

    public int size() {
        return prices.size();
    }

}
