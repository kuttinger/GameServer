package de.brickforceaurora.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.brickforceaurora.account.security.PasswordHasher;
import de.brickforceaurora.account.shop.ShopCatalog;

class AccountHttpApiTest {

    private static final List<String> SHOP = List.of("itemCode, duration, forcepoints, brickpoint, tokens, discount",
        "aac,1000000,3000,0,4000,0", "s80,1,0,0,10,0", "s80,10,0,0,100,0");

    @TempDir
    Path dir;

    private AccountService service;
    private AccountHttpApi api;
    private String base;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        final AccountConfig def = AccountConfig.defaults();
        final AccountConfig config = new AccountConfig("127.0.0.1", 0, dir.resolve("test.db"), null, "secret-server-key",
            def.sessionHours(), def.ticketHours(), 5000, 50, List.of("wau:EQUIP"));
        service = new AccountService(config, ShopCatalog.parse(SHOP), Clock.systemUTC());
        api = new AccountHttpApi(service);
        base = "http://127.0.0.1:" + api.start("127.0.0.1", 0).port() + "/api/v1";
    }

    @AfterEach
    void tearDown() {
        api.stop();
        service.close();
    }

    private HttpResponse<String> send(final String method, final String path, final String body, final String... headers)
        throws Exception {
        final HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path)).method(method,
            body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (headers.length > 0) {
            builder.headers(headers);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(final HttpResponse<String> response) throws Exception {
        return mapper.readTree(response.body());
    }

    private String registerAndLogin(final String name) throws Exception {
        assertEquals(201, send("POST", "/auth/register", "{\"username\":\"" + name + "\",\"password\":\"hunter2hunter2\"}").statusCode());
        final HttpResponse<String> login = send("POST", "/auth/login", "{\"username\":\"" + name + "\",\"password\":\"hunter2hunter2\"}");
        assertEquals(200, login.statusCode());
        return json(login).get("token").asText();
    }

    @Test
    void passwordHashRoundTrip() {
        final String hash = PasswordHasher.hash("correct horse");
        assertTrue(PasswordHasher.verify("correct horse", hash));
        assertFalse(PasswordHasher.verify("wrong horse", hash));
    }

    @Test
    void registerLoginAndProfile() throws Exception {
        final String token = registerAndLogin("Tester");
        final JsonNode me = json(send("GET", "/me", null, "Authorization", "Bearer " + token));
        assertEquals("Tester", me.get("account").get("username").asText());
        assertEquals(5000, me.get("account").get("forcePoints").asInt());
        assertEquals("wau", me.get("items").get(0).get("code").asText());

        assertEquals(409, send("POST", "/auth/register", "{\"username\":\"tester\",\"password\":\"hunter2hunter2\"}").statusCode());
        assertEquals(401, send("POST", "/auth/login", "{\"username\":\"Tester\",\"password\":\"nope-nope\"}").statusCode());
        assertEquals(401, send("GET", "/me", null, "Authorization", "Bearer invalid").statusCode());
    }

    @Test
    void purchaseUsesCatalogPricesAndChecksFunds() throws Exception {
        final String token = registerAndLogin("Buyer");
        final HttpResponse<String> buy = send("POST", "/me/purchase", "{\"code\":\"aac\",\"option\":1000000,\"buyHow\":0}",
            "Authorization", "Bearer " + token);
        assertEquals(200, buy.statusCode());
        assertEquals(2000, json(buy).get("account").get("forcePoints").asInt());
        assertEquals(-1, json(buy).get("item").get("remainSeconds").asLong());

        final HttpResponse<String> rental = send("POST", "/me/purchase", "{\"code\":\"s80\",\"option\":1,\"buyHow\":2}", "Authorization",
            "Bearer " + token);
        assertEquals(200, rental.statusCode());
        assertEquals(86400, json(rental).get("item").get("remainSeconds").asLong());

        assertEquals(409, send("POST", "/me/purchase", "{\"code\":\"s80\",\"option\":10,\"buyHow\":2}", "Authorization", "Bearer " + token)
            .statusCode());
        assertEquals(404, send("POST", "/me/purchase", "{\"code\":\"zzz\",\"option\":1,\"buyHow\":0}", "Authorization", "Bearer " + token)
            .statusCode());
        assertEquals(400, send("POST", "/me/purchase", "{\"code\":\"s80\",\"option\":1,\"buyHow\":0}", "Authorization", "Bearer " + token)
            .statusCode());
    }

    @Test
    void ticketIsScopedToGameServerRoutes() throws Exception {
        final String token = registerAndLogin("Player");
        final String ticket = json(send("POST", "/me/ticket", null, "Authorization", "Bearer " + token)).get("ticket").asText();

        final HttpResponse<String> verify = send("POST", "/ticket/verify", null, "X-Play-Ticket", ticket);
        assertEquals(200, verify.statusCode());
        assertEquals("Player", json(verify).get("account").get("username").asText());

        // A ticket is not a session
        assertEquals(401, send("GET", "/me", null, "Authorization", "Bearer " + ticket).statusCode());
        // and a session is not a ticket
        assertEquals(401, send("POST", "/ticket/verify", null, "X-Play-Ticket", token).statusCode());

        final long seq = json(verify).get("items").get(0).get("seq").asLong();
        final HttpResponse<String> loadout = send("PUT", "/ticket/loadout",
            "{\"items\":[{\"seq\":" + seq + ",\"usage\":\"UNEQUIP\",\"toolSlot\":-1}]}", "X-Play-Ticket", ticket);
        assertEquals(200, loadout.statusCode());
        assertEquals("UNEQUIP", json(loadout).get(0).get("usage").asText());
    }

    @Test
    void rewardsNeedServerKey() throws Exception {
        final String token = registerAndLogin("Winner");
        final long id = json(send("GET", "/me", null, "Authorization", "Bearer " + token)).get("account").get("id").asLong();
        final String body = "{\"accountId\":" + id + ",\"xp\":100,\"forcePoints\":50}";
        assertEquals(403, send("POST", "/trusted/reward", body, "X-Server-Key", "guess").statusCode());
        final HttpResponse<String> ok = send("POST", "/trusted/reward", body, "X-Server-Key", "secret-server-key");
        assertEquals(200, ok.statusCode());
        assertEquals(5050, json(ok).get("forcePoints").asInt());
    }

    @Test
    void legacyInventoryImportOnlyOnceAndOnlyKnownItems() throws Exception {
        final String token = registerAndLogin("Veteran");
        final String inventory = "[{\"name\":\"x\",\"code\":\"aac\",\"usage\":\"Equip\",\"slot\":\"WEAPON\",\"toolslot\":2,\"amount\":1,\"remain\":-1,\"is_upgraded\":false},"
            + "{\"code\":\"hacked\",\"usage\":\"Equip\"}]";
        final HttpResponse<String> first = send("POST", "/me/import-inventory", inventory, "Authorization", "Bearer " + token);
        assertEquals(200, first.statusCode());
        final JsonNode items = json(first);
        assertEquals(2, items.size()); // starter item + aac, "hacked" is dropped
        assertEquals("aac", items.get(1).get("code").asText());
        assertEquals(409, send("POST", "/me/import-inventory", inventory, "Authorization", "Bearer " + token).statusCode());
    }

}
