package de.brickforceaurora.account;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.brickforceaurora.account.AccountService.TokenKind;
import de.brickforceaurora.account.Model.Credentials;
import de.brickforceaurora.account.Model.ErrorResponse;
import de.brickforceaurora.account.Model.LegacyInventoryItem;
import de.brickforceaurora.account.Model.LoadoutRequest;
import de.brickforceaurora.account.Model.PurchaseRequest;
import de.brickforceaurora.account.Model.RewardRequest;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.json.JavalinJackson;

/**
 * REST API (all routes under {@code /api/v1}).
 *
 * <pre>
 * Launcher (Authorization: Bearer &lt;session&gt;)
 *   POST /auth/register        {username, password}
 *   POST /auth/login           {username, password}      -> {token, expiresAt, account}
 *   POST /auth/logout
 *   GET  /me                                             -> {account, items}
 *   POST /me/ticket                                      -> {ticket, expiresAt}
 *   POST /me/purchase          {code, option, buyHow}
 *   PUT  /me/loadout           {items:[{seq, usage, toolSlot}]}
 *   POST /me/import-inventory  [Config\Inventory.json]
 * Game server (X-Play-Ticket: &lt;ticket&gt;)
 *   POST /ticket/verify                                  -> {account, items}
 *   POST /ticket/purchase      {code, option, buyHow}
 *   PUT  /ticket/loadout       {items:[...]}
 * Trusted server (X-Server-Key: &lt;key&gt;)
 *   POST /trusted/reward       {accountId, xp, forcePoints}
 * Public
 *   GET  /health
 * </pre>
 */
public final class AccountHttpApi {

    private static final int MAX_FAILED_LOGINS = 10;
    private static final long FAILED_LOGIN_WINDOW_MILLIS = 5 * 60 * 1000;

    private final AccountService service;
    private final ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final Map<String, long[]> failedLogins = new ConcurrentHashMap<>();

    private Javalin app;

    public AccountHttpApi(final AccountService service) {
        this.service = service;
    }

    public Javalin start(final String host, final int port) {
        app = Javalin.create(config -> {
            config.showJavalinBanner = false;
            config.jsonMapper(new JavalinJackson(mapper, false));
            config.http.maxRequestSize = 256 * 1024;
        });
        app.exception(ApiException.class, (e, ctx) -> ctx.status(e.status()).json(new ErrorResponse(e.code(), e.getMessage())));
        app.exception(Exception.class, (e, ctx) -> {
            ctx.status(500).json(new ErrorResponse("internal_error", "Internal server error"));
            e.printStackTrace();
        });

        app.get("/api/v1/health", ctx -> ctx.json(Map.of("status", "ok")));

        app.post("/api/v1/auth/register", ctx -> {
            final Credentials credentials = body(ctx, Credentials.class);
            ctx.status(201).json(service.register(credentials.username(), credentials.password()));
        });
        app.post("/api/v1/auth/login", this::login);
        app.post("/api/v1/auth/logout", ctx -> {
            final String token = bearer(ctx);
            service.authenticate(token, TokenKind.SESSION);
            service.logout(token);
            ctx.status(204);
        });

        app.get("/api/v1/me", ctx -> ctx.json(service.profile(session(ctx))));
        app.post("/api/v1/me/ticket", ctx -> ctx.json(service.createTicket(session(ctx))));
        app.post("/api/v1/me/purchase", ctx -> purchase(ctx, session(ctx), "launcher"));
        app.put("/api/v1/me/loadout", ctx -> loadout(ctx, session(ctx)));
        app.post("/api/v1/me/import-inventory", ctx -> {
            final long accountId = session(ctx);
            final List<LegacyInventoryItem> items = mapper.readValue(ctx.body(), new TypeReference<List<LegacyInventoryItem>>() {});
            ctx.json(service.importLegacyInventory(accountId, items));
        });

        app.post("/api/v1/ticket/verify", ctx -> ctx.json(service.profile(ticket(ctx))));
        app.post("/api/v1/ticket/purchase", ctx -> purchase(ctx, ticket(ctx), "server"));
        app.put("/api/v1/ticket/loadout", ctx -> loadout(ctx, ticket(ctx)));

        app.post("/api/v1/trusted/reward", ctx -> {
            if (!service.isServerKey(ctx.header("X-Server-Key"))) {
                throw ApiException.forbidden("Invalid server key");
            }
            final RewardRequest request = body(ctx, RewardRequest.class);
            ctx.json(service.reward(request.accountId(), request.xp(), request.forcePoints()));
        });

        return app.start(host, port);
    }

    public void stop() {
        if (app != null) {
            app.stop();
        }
    }

    private void login(final Context ctx) {
        final Credentials credentials = body(ctx, Credentials.class);
        final String key = ctx.ip() + '|' + String.valueOf(credentials.username()).toLowerCase();
        final long now = System.currentTimeMillis();
        final long[] state = failedLogins.get(key);
        if (state != null && now - state[1] < FAILED_LOGIN_WINDOW_MILLIS && state[0] >= MAX_FAILED_LOGINS) {
            throw new ApiException(429, "too_many_attempts", "Too many failed logins, try again later");
        }
        try {
            ctx.json(service.login(credentials.username(), credentials.password()));
            failedLogins.remove(key);
        } catch (ApiException e) {
            failedLogins.compute(key, (_, old) -> old == null || now - old[1] >= FAILED_LOGIN_WINDOW_MILLIS ? new long[] {
                1,
                now
            } : new long[] {
                old[0] + 1,
                old[1]
            });
            throw e;
        }
    }

    private void purchase(final Context ctx, final long accountId, final String via) {
        final PurchaseRequest request = body(ctx, PurchaseRequest.class);
        ctx.json(service.purchase(accountId, request.code(), request.option(), request.buyHow(), via));
    }

    private void loadout(final Context ctx, final long accountId) {
        final LoadoutRequest request = body(ctx, LoadoutRequest.class);
        ctx.json(service.updateLoadout(accountId, request.items()));
    }

    private long session(final Context ctx) {
        return service.authenticate(bearer(ctx), TokenKind.SESSION);
    }

    private long ticket(final Context ctx) {
        return service.authenticate(ctx.header("X-Play-Ticket"), TokenKind.TICKET);
    }

    private static String bearer(final Context ctx) {
        final String header = ctx.header("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        return header.substring(7).trim();
    }

    private <T> T body(final Context ctx, final Class<T> type) {
        try {
            final T value = mapper.readValue(ctx.body(), type);
            if (value == null) {
                throw ApiException.badRequest("invalid_body", "Missing request body");
            }
            return value;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.badRequest("invalid_body", "Malformed JSON body");
        }
    }

}
