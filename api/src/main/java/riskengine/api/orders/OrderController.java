package riskengine.api.orders;

import jakarta.validation.Valid;
import java.net.URI;
import java.time.Clock;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import riskengine.api.ApiError;
import riskengine.api.ApiException;
import riskengine.api.ApiProperties;
import riskengine.api.limits.ReplayGuard;
import riskengine.api.security.Caller;
import riskengine.events.OrderRequest;
import riskengine.host.RedpandaLogs;
import riskengine.host.RedpandaLogs.EventLogUnavailableException;

/**
 * POST /orders is asynchronous. By the time it runs, the caller is authenticated and within their rate
 * limit (see SecurityConfig). It then:
 *
 * <ol>
 *   <li>validates the body and the X-Request-Id header (400);</li>
 *   <li>checks the trader owns the account (403);</li>
 *   <li>claims the request id (409 if it was used before);</li>
 *   <li>writes an OrderRequest to the account's partition of engine-events and waits for the broker to
 *       acknowledge it (503 if it does not);</li>
 *   <li>answers 202 Accepted with the order id.</li>
 * </ol>
 *
 * 202 means "written to the log", not "accepted by risk". The shard that owns the account runs the
 * pre-trade margin check when it reaches the order, in line with that account's fills and the market's
 * ticks, and publishes the decision; GET /orders/{id} shows it. No lock is held anywhere: the partition's
 * order is the only ordering there is.
 */
@RestController
class OrderController {
    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{8,64}");

    private final ApiProperties props;
    private final ReplayGuard replayGuard;
    private final OrderStore store;
    private final RedpandaLogs engineEvents;
    private final Clock clock;

    OrderController(ApiProperties props, ReplayGuard replayGuard, OrderStore store, RedpandaLogs engineEvents, Clock clock) {
        this.props = props;
        this.replayGuard = replayGuard;
        this.store = store;
        this.engineEvents = engineEvents;
        this.clock = clock;
    }

    @PostMapping(path = "/orders", consumes = "application/json")
    ResponseEntity<Map<String, String>> submit(@AuthenticationPrincipal Jwt token,
                                               @RequestHeader(name = "X-Request-Id", required = false) String requestId,
                                               @Valid @RequestBody OrderBody body) {
        Caller caller = Caller.of(token);
        if (requestId == null || !REQUEST_ID.matcher(requestId).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_request",
                    "X-Request-Id header is required: 8-64 letters, digits, '.', '_' or '-', unique per order");
        }
        if (body.qty() > props.maxOrderQty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_request", "qty must be at most " + props.maxOrderQty());
        }
        if (!caller.mayActOn(body.accountId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "you may only place orders on your own account");
        }

        String orderId = OrderIds.forRequest(caller.userId(), requestId);
        String firstUse = replayGuard.claim(caller.userId(), requestId, orderId);
        if (firstUse != null) {
            throw new ApiException(HttpStatus.CONFLICT, new ApiError("replayed_request",
                    "this X-Request-Id was already used; the order it created is given in orderId", firstUse, null));
        }

        OrderRequest order = new OrderRequest(orderId, body.accountId(), body.instrumentId(), body.side(), body.qty(),
                OrderRequest.Origin.USER, clock.millis());
        store.pending(order);
        try {
            engineEvents.append(order);
        } catch (EventLogUnavailableException e) {
            // Unknown whether it landed. Give the request id back so the client can retry; the retry gets
            // the same order id, and the shard decides an order id once however many times it was written.
            replayGuard.release(caller.userId(), requestId);
            store.forget(orderId);
            throw e;
        }
        return ResponseEntity.accepted().location(URI.create("/orders/" + orderId))
                .body(Map.of("orderId", orderId, "status", OrderView.PENDING));
    }

    /**
     * A trader sees their own account's orders; risk-ops sees any. Someone else's order looks exactly like
     * one that does not exist (404), so order ids cannot be probed.
     */
    @GetMapping("/orders/{orderId}")
    OrderView status(@AuthenticationPrincipal Jwt token, @PathVariable String orderId) {
        Caller caller = Caller.of(token);
        ApiException notFound = new ApiException(HttpStatus.NOT_FOUND, "not_found", "no such order");
        if (!OrderIds.looksLikeOne(orderId)) throw notFound;
        OrderView view = store.find(orderId).orElseThrow(() -> notFound);
        if (!caller.mayRead(view.accountId())) throw notFound;
        return view;
    }
}
