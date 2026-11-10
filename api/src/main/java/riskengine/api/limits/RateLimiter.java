package riskengine.api.limits;

import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import riskengine.api.ApiProperties;

/**
 * A token bucket per user, kept in Redis so every API instance shares it.
 *
 * <p>Each user has a bucket of {@code capacity} tokens that refills at {@code refillPerSecond}. A request
 * takes one token; with none left it is refused and told how long until the next token. So a user can
 * burst up to the capacity and sustain the refill rate.
 *
 * <p>The whole read-refill-take-write step is one Lua script, which Redis runs atomically: two requests
 * arriving together cannot both take the last token. The clock is Redis's own ({@code TIME}), so API
 * instances with slightly different clocks still agree.
 */
@Component
public class RateLimiter {
    private static final String SCRIPT = """
            local capacity = tonumber(ARGV[1])
            local refill = tonumber(ARGV[2])
            local t = redis.call('TIME')
            local now = t[1] * 1000 + math.floor(t[2] / 1000)
            local bucket = redis.call('HMGET', KEYS[1], 'tokens', 'at')
            local tokens = tonumber(bucket[1])
            local at = tonumber(bucket[2])
            if tokens == nil or at == nil then
              tokens = capacity
              at = now
            end
            tokens = math.min(capacity, tokens + math.max(0, now - at) * refill / 1000)
            local wait = 0
            if tokens >= 1 then
              tokens = tokens - 1
            else
              wait = math.ceil((1 - tokens) * 1000 / refill)
            end
            redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'at', tostring(now))
            redis.call('PEXPIRE', KEYS[1], math.ceil(capacity * 1000 / refill) + 1000)
            return wait
            """;

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> script = new DefaultRedisScript<>(SCRIPT, Long.class);
    private final String capacity;
    private final String refillPerSecond;

    public RateLimiter(StringRedisTemplate redis, ApiProperties props) {
        this.redis = redis;
        this.capacity = Integer.toString(props.rateLimit().capacity());
        this.refillPerSecond = Double.toString(props.rateLimit().refillPerSecond());
    }

    /**
     * Takes one token from the user's bucket.
     *
     * @return 0 if the request may go ahead; otherwise milliseconds until a token will be available
     */
    public long tryAcquire(String userId) {
        Long wait = redis.execute(script, List.of("ratelimit:{" + userId + "}"), capacity, refillPerSecond);
        if (wait == null) throw new IllegalStateException("rate limit script returned nothing");
        return wait;
    }
}
