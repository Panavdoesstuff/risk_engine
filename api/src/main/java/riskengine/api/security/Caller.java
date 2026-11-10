package riskengine.api.security;

import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Who is calling, taken from a verified token.
 *
 * @param accountId the one account a trader may act on; null for risk-ops
 */
public record Caller(String userId, Role role, String accountId) {

    public enum Role {
        /** May place orders on, and read orders of, their own account only. */
        TRADER("trader"),
        /** May read any account's orders. Places no user orders. (Block/unblock and manual square-off: M5.) */
        RISK_OPS("risk-ops");

        private final String claim;

        Role(String claim) {
            this.claim = claim;
        }

        public String claim() {
            return claim;
        }

        public String authority() {
            return "ROLE_" + name();
        }

        /** Null if the claim is not a known role. */
        public static Role fromClaim(String claim) {
            for (Role r : values()) if (r.claim.equals(claim)) return r;
            return null;
        }
    }

    public static Caller of(Jwt jwt) {
        return new Caller(jwt.getSubject(), Role.fromClaim(jwt.getClaimAsString(JwtTokens.ROLE)), jwt.getClaimAsString(JwtTokens.ACCOUNT));
    }

    public boolean mayActOn(String account) {
        return role == Role.TRADER && account.equals(accountId);
    }

    public boolean mayRead(String account) {
        return role == Role.RISK_OPS || account.equals(accountId);
    }
}
