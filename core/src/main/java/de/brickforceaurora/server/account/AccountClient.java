package de.brickforceaurora.server.account;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Client for the Aurora account service ({@code account} module).
 *
 * Game servers only ever act with the play ticket of the player, so a player hosted server can't do more than the player could
 * do himself.
 */
public final class AccountClient {

    public record Account(long id, String username, long xp, int forcePoints, int brickPoints, int tokens, int coins, int starDust) {}

    public record Item(long seq, String code, String usage, int toolSlot, int amount, long remainSeconds, boolean upgraded) {}

    public record Profile(Account account, List<Item> items) {}

    public record Purchase(Item item, Account account) {}

    public record LoadoutEntry(long seq, String usage, int toolSlot) {}

    public static final class AccountServiceException extends Exception {

        private static final long serialVersionUID = 1L;

        private final int status;
        private final String code;

        public AccountServiceException(final int status, final String code, final String message) {
            super(message);
            this.status = status;
            this.code = code;
        }

        public int status() {
            return status;
        }

        public String code() {
            return code;
        }

    }

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final URI base;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /**
     * @param baseUrl e.g. {@code http://127.0.0.1:18900}
     */
    public AccountClient(final String baseUrl) {
        final String trimmed = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.base = URI.create(trimmed + "/api/v1/");
    }

    public Profile verifyTicket(final String ticket) throws AccountServiceException {
        return request("POST", "ticket/verify", ticket, null, new TypeReference<Profile>() {});
    }

    public Purchase purchase(final String ticket, final String code, final int option, final int buyHow) throws AccountServiceException {
        return request("POST", "ticket/purchase", ticket, Map.of("code", code, "option", option, "buyHow", buyHow),
            new TypeReference<Purchase>() {});
    }

    public List<Item> updateLoadout(final String ticket, final List<LoadoutEntry> entries) throws AccountServiceException {
        return request("PUT", "ticket/loadout", ticket, Map.of("items", entries), new TypeReference<List<Item>>() {});
    }

    private <T> T request(final String method, final String path, final String ticket, final Object body, final TypeReference<T> type)
        throws AccountServiceException {
        try {
            final HttpRequest request = HttpRequest.newBuilder(base.resolve(path)).timeout(TIMEOUT)
                .header("Content-Type", "application/json").header("X-Play-Ticket", ticket == null ? "" : ticket)
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
            final HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                String code = "http_" + response.statusCode();
                String message = response.body();
                try {
                    final JsonNode error = mapper.readTree(response.body());
                    code = error.path("error").asText(code);
                    message = error.path("message").asText(message);
                } catch (IOException ignore) {
                    // Not a json error response
                }
                throw new AccountServiceException(response.statusCode(), code, message);
            }
            return mapper.readValue(response.body(), type);
        } catch (IOException e) {
            throw new AccountServiceException(0, "unreachable", "Account service unreachable: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AccountServiceException(0, "interrupted", "Interrupted");
        }
    }

}
