package de.brickforceaurora.account;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;

import de.brickforceaurora.account.shop.ShopCatalog;

public final class Main {

    private Main() {
        throw new UnsupportedOperationException();
    }

    public static void main(final String[] args) throws Exception {
        final Path configFile = Path.of(args.length > 0 ? args[0] : "account.properties");
        final AccountConfig config = AccountConfig.load(configFile);

        final ShopCatalog catalog;
        if (Files.exists(config.shopFile())) {
            catalog = ShopCatalog.load(config.shopFile());
            System.out.println("Loaded " + catalog.size() + " shop items from " + config.shopFile());
        } else {
            catalog = ShopCatalog.empty();
            System.out.println("WARNING: Shop file '" + config.shopFile() + "' not found, the shop is empty");
        }
        if (config.serverKey() == null || config.serverKey().isBlank()) {
            System.out.println("No server.key configured, trusted reward endpoints are disabled");
        }

        final AccountService service = new AccountService(config, catalog, Clock.systemUTC());
        final AccountHttpApi api = new AccountHttpApi(service);
        api.start(config.host(), config.port());
        System.out.println("Account service listening on " + config.host() + ':' + config.port());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            api.stop();
            service.close();
        }, "account-shutdown"));
    }

}
