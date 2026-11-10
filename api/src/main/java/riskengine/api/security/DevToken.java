package riskengine.api.security;

import java.time.Duration;
import java.time.Instant;

/**
 * Prints a token for local use. Reads the signing key from JWT_SECRET, the same variable the API reads.
 *
 * <pre>
 *   JWT_SECRET=... ./gradlew -q :api:devToken --args="trader alice A0000001"
 *   JWT_SECRET=... ./gradlew -q :api:devToken --args="risk-ops bob"
 * </pre>
 */
public final class DevToken {
    private DevToken() {}

    public static void main(String[] args) {
        String secret = System.getenv("JWT_SECRET");
        Caller.Role role = args.length >= 2 ? Caller.Role.fromClaim(args[0]) : null;
        if (secret == null || role == null || (role == Caller.Role.TRADER) != (args.length == 3)) {
            System.err.println("usage: JWT_SECRET=<at least 32 chars> devToken trader <user> <account> | risk-ops <user>");
            System.exit(2);
            return;
        }
        String issuer = System.getenv().getOrDefault("JWT_ISSUER", "risk-engine");
        System.out.println(JwtTokens.mint(secret, issuer, args[1], role, args.length == 3 ? args[2] : null, Instant.now(), Duration.ofHours(8)));
    }
}
