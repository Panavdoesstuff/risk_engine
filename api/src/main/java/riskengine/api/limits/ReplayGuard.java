package riskengine.api.limits;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import riskengine.api.ApiProperties;

/**
 * Remembers every request id a user has sent, for a while, so the same request cannot be played twice:
 * not by a client that retries blindly, and not by someone who captured it off the wire.
 *
 * <p>{@code SET key value NX EX ttl} is a single atomic Redis command: of two identical requests arriving
 * at the same moment, on any two API instances, exactly one gets to set the key.
 *
 * <p>Keys are per user, so one user cannot use up another's request ids.
 */
@Component
public class ReplayGuard {
    private final StringRedisTemplate redis;
    private final ApiProperties props;

    public ReplayGuard(StringRedisTemplate redis, ApiProperties props) {
        this.redis = redis;
        this.props = props;
    }

    /**
     * Claims the request id for this order.
     *
     * @return null if the id was new and is now claimed; otherwise the order id it was first used for
     */
    public String claim(String userId, String requestId, String orderId) {
        String key = key(userId, requestId);
        if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, orderId, props.requestIdTtl()))) return null;
        String first = redis.opsForValue().get(key);
        return first != null ? first : orderId; // expired between the two calls: the id is still a repeat
    }

    /** Gives the id back after a failed write, so the client can retry the same request. */
    public void release(String userId, String requestId) {
        redis.delete(key(userId, requestId));
    }

    private static String key(String userId, String requestId) {
        return "request:{" + userId + "}:" + requestId;
    }
}
