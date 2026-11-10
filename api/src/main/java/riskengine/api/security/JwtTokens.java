package riskengine.api.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.regex.Pattern;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * The token format, in one place.
 *
 * <pre>
 *   header: alg = HS256
 *   claims: iss     the configured issuer
 *           sub     user id
 *           role    "trader" or "risk-ops"
 *           account the trader's account id (traders only)
 *           iat, exp
 * </pre>
 *
 * The API only verifies tokens. {@link #mint} exists for tests and for the local {@link DevToken} tool;
 * in a real deployment a login service would hold the key and issue them.
 */
public final class JwtTokens {
    public static final String ROLE = "role";
    public static final String ACCOUNT = "account";
    public static final Pattern USER_ID = Pattern.compile("[A-Za-z0-9._@-]{1,64}");
    public static final Pattern ACCOUNT_ID = Pattern.compile("[A-Za-z0-9_-]{1,32}");

    private JwtTokens() {}

    public static SecretKey key(String secret) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) throw new IllegalArgumentException("JWT secret must be at least 32 bytes for HS256");
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    /** @param account null for risk-ops */
    public static String mint(String secret, String issuer, String userId, Caller.Role role, String account, Instant issuedAt, Duration validFor) {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(userId)
                .claim(ROLE, role.claim())
                .issueTime(Date.from(issuedAt))
                .expirationTime(Date.from(issuedAt.plus(validFor)));
        if (account != null) claims.claim(ACCOUNT, account);
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims.build());
        try {
            jwt.sign(new MACSigner(key(secret)));
        } catch (JOSEException e) {
            throw new IllegalStateException("cannot sign token", e);
        }
        return jwt.serialize();
    }
}
