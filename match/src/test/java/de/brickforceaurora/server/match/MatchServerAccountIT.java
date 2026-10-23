package de.brickforceaurora.server.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.brickforceaurora.account.AccountConfig;
import de.brickforceaurora.account.AccountHttpApi;
import de.brickforceaurora.account.AccountService;
import de.brickforceaurora.account.shop.ShopCatalog;
import de.brickforceaurora.server.match.AuroraTestClient.Body;

/**
 * End to end: account service + the packaged match server jar (as a real process) + protocol client.
 */
class MatchServerAccountIT {

    @TempDir
    Path dir;

    @Test
    void ticketLoginSendsAccountInventoryAndPurchasesArePersisted() throws Exception {
        final AccountConfig def = AccountConfig.defaults();
        final AccountConfig config = new AccountConfig("127.0.0.1", 0, dir.resolve("accounts.db"), null, null, def.sessionHours(),
            def.ticketHours(), 5000, 0, List.of("wau:EQUIP", "s07:UNEQUIP"));
        final ShopCatalog catalog = ShopCatalog.parse(List.of("header", "aac,1000000,3000,0,4000,0"));
        try (AccountService accounts = new AccountService(config, catalog, Clock.systemUTC())) {
            final AccountHttpApi api = new AccountHttpApi(accounts);
            final int accountPort = api.start("127.0.0.1", 0).port();
            final long accountId = accounts.register("Hoster", "hunter2hunter2").id();
            final String ticket = accounts.createTicket(accountId).ticket();

            final int port = freePort();
            final Process server = startMatchServer(port, "http://127.0.0.1:" + accountPort);
            try (AuroraTestClient client = new AuroraTestClient("127.0.0.1", port)) {
                assertEquals(0x8, client.handshake());
                client.loginWithTicket(ticket);
                client.expect(1010);

                final ByteBuffer itemList = client.expect(464).buffer();
                final int count = itemList.getInt();
                final List<String> codes = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    itemList.getLong();
                    codes.add(AuroraTestClient.readString(itemList));
                    itemList.get();
                    itemList.getInt();
                    itemList.get();
                    itemList.getInt();
                }
                assertEquals(List.of("wau", "s07"), codes);

                // code, buyHow = GENERAL_POINT, option, byte val, bool needEquip
                final byte[] body = new Body().string("aac").integer(0).integer(1000000).toArray();
                final byte[] withFlags = new byte[body.length + 2];
                System.arraycopy(body, 0, withFlags, 0, body.length);
                client.send(121, withFlags);

                final ByteBuffer asset = client.expect(102).buffer();
                assertEquals(2000, asset.getInt());
                final ByteBuffer bought = client.expect(122).buffer();
                assertTrue(bought.getLong() > 0);
                assertEquals("aac", AuroraTestClient.readString(bought));
            } finally {
                server.destroy();
                server.waitFor();
                api.stop();
            }
            assertEquals(3, accounts.inventory(accountId).size());
            assertEquals(2000, accounts.account(accountId).forcePoints());
        }
    }

    private Process startMatchServer(final int port, final String accountUrl) throws Exception {
        final String jar = System.getProperty("match.jar");
        assertTrue(jar != null && Files.exists(Path.of(jar)), "Packaged match server jar not found: " + jar);
        final Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        // Log next to the jar so it survives the test for debugging
        final Path log = Path.of(jar).resolveSibling("match-it.log");
        final Process process = new ProcessBuilder(java.toString(), "-jar", jar, "--debug", "--port=" + port,
            "--account-url=" + accountUrl).directory(dir.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        final long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive()) {
                throw new IllegalStateException("Match server exited:\n" + Files.readString(log));
            }
            try (Socket socket = new Socket("127.0.0.1", port)) {
                return process;
            } catch (IOException notYet) {
                Thread.sleep(200);
            }
        }
        process.destroy();
        throw new IllegalStateException("Match server didn't start:\n" + Files.readString(log));
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

}
