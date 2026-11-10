package riskengine.api.orders;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Order ids are derived from (user, request id), not drawn at random: "O-" plus the first 128 bits of
 * SHA-256(user, newline, request id).
 *
 * <p>Why: if a write to the log times out, the API cannot know whether it landed. The client retries with
 * the same request id and gets the same order id. If both writes did land, the shard sees one order id
 * twice and decides it once. A random id would have made that two orders.
 */
public final class OrderIds {
    private OrderIds() {}

    public static String forRequest(String userId, String requestId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest((userId + "\n" + requestId).getBytes(StandardCharsets.UTF_8));
            return "O-" + HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean looksLikeOne(String s) {
        return s.matches("O-[0-9a-f]{32}");
    }
}
