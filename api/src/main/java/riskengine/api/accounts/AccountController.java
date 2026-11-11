package riskengine.api.accounts;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import riskengine.api.ApiError;
import riskengine.api.ApiException;
import riskengine.api.limits.ReplayGuard;
import riskengine.api.security.Caller;
import riskengine.api.security.JwtTokens;
import riskengine.events.AccountControl;
import riskengine.host.RedpandaLogs;

/**
 * Account status, and the three things risk-ops can do to an account.
 *
 * <pre>
 *   GET  /accounts/{id}              status and cash (a trader: own account only; risk-ops: any)
 *   POST /accounts/{id}/block        risk-ops: no new user orders
 *   POST /accounts/{id}/unblock      risk-ops: lift a block (also one the engine set when a square-off ran dry)
 *   POST /accounts/{id}/square-off   risk-ops: close every position, one leg at a time
 * </pre>
 *
 * The POSTs work exactly like an order: the API does not change anything itself. It writes an
 * AccountControl event to the account's partition and answers 202. The shard applies it in line with the
 * account's ticks, fills and orders, so "blocked" takes effect at a definite point in the log, and the
 * audit log gets the risk-ops user as the actor. Each needs an X-Request-Id, and a repeat is refused, so
 * a retried or replayed "square-off" cannot fire twice.
 */
@RestController
class AccountController {
    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{8,64}");

    private final JdbcTemplate jdbc;
    private final ReplayGuard replayGuard;
    private final RedpandaLogs engineEvents;
    private final Clock clock;

    AccountController(JdbcTemplate jdbc, ReplayGuard replayGuard, RedpandaLogs engineEvents, Clock clock) {
        this.jdbc = jdbc;
        this.replayGuard = replayGuard;
        this.engineEvents = engineEvents;
        this.clock = clock;
    }

    /** @param status ACTIVE, MARGIN_CALL, SQUARING_OFF or BLOCKED, as last written by the engine */
    record AccountView(String accountId, String status, BigDecimal cashBalance) {}

    @GetMapping("/accounts/{accountId}")
    AccountView account(@AuthenticationPrincipal Jwt token, @PathVariable String accountId) {
        Caller caller = Caller.of(token);
        ApiException notFound = new ApiException(HttpStatus.NOT_FOUND, "not_found", "no such account");
        // A trader asking about someone else's account gets the same answer as for one that does not exist.
        if (!JwtTokens.ACCOUNT_ID.matcher(accountId).matches() || !caller.mayRead(accountId)) throw notFound;
        List<AccountView> rows = jdbc.query("SELECT id, status, cash_balance FROM accounts WHERE id = ?",
                (rs, n) -> new AccountView(rs.getString(1), rs.getString(2), rs.getBigDecimal(3)), accountId);
        if (rows.isEmpty()) throw notFound;
        return rows.get(0);
    }

    @PostMapping("/accounts/{accountId}/block")
    ResponseEntity<Map<String, String>> block(@AuthenticationPrincipal Jwt token, @PathVariable String accountId,
                                              @RequestHeader(name = "X-Request-Id", required = false) String requestId) {
        return control(token, accountId, requestId, AccountControl.Action.BLOCK);
    }

    @PostMapping("/accounts/{accountId}/unblock")
    ResponseEntity<Map<String, String>> unblock(@AuthenticationPrincipal Jwt token, @PathVariable String accountId,
                                                @RequestHeader(name = "X-Request-Id", required = false) String requestId) {
        return control(token, accountId, requestId, AccountControl.Action.UNBLOCK);
    }

    @PostMapping("/accounts/{accountId}/square-off")
    ResponseEntity<Map<String, String>> squareOff(@AuthenticationPrincipal Jwt token, @PathVariable String accountId,
                                                  @RequestHeader(name = "X-Request-Id", required = false) String requestId) {
        return control(token, accountId, requestId, AccountControl.Action.SQUARE_OFF);
    }

    private ResponseEntity<Map<String, String>> control(Jwt token, String accountId, String requestId, AccountControl.Action action) {
        Caller caller = Caller.of(token); // the security configuration has already checked the role is risk-ops
        if (requestId == null || !REQUEST_ID.matcher(requestId).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_request", "X-Request-Id header is required: 8-64 letters, digits, '.', '_' or '-'");
        }
        if (!JwtTokens.ACCOUNT_ID.matcher(accountId).matches()
                || Boolean.FALSE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM accounts WHERE id = ?)", Boolean.class, accountId))) {
            throw new ApiException(HttpStatus.NOT_FOUND, "not_found", "no such account");
        }
        String firstUse = replayGuard.claim(caller.userId(), requestId, action + ":" + accountId);
        if (firstUse != null) {
            throw new ApiException(HttpStatus.CONFLICT, new ApiError("replayed_request", "this X-Request-Id was already used (" + firstUse + ")", null, null));
        }
        try {
            engineEvents.append(new AccountControl(accountId, action, "risk-ops:" + caller.userId(), requestId, clock.millis()));
        } catch (RuntimeException e) {
            replayGuard.release(caller.userId(), requestId); // nothing was written: the same request may be sent again
            throw e;
        }
        return ResponseEntity.accepted().body(Map.of("accountId", accountId, "action", action.name(), "status", "REQUESTED"));
    }
}
