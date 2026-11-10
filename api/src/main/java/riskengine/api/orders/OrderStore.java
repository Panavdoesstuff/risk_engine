package riskengine.api.orders;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import riskengine.api.ApiProperties;
import riskengine.events.OrderRequest;
import riskengine.margin.OrderDecision;

/**
 * The status of recent orders, in Redis, for GET /orders/{id}. Two writers:
 *
 * <ul>
 *   <li>the API, when it accepts a request: status PENDING, but only if no status is there yet;</li>
 *   <li>the engine-out listener, when the shard has decided: the decision, always.</li>
 * </ul>
 *
 * So whichever order the two writes land in, a decision is never overwritten by PENDING. Decisions carry
 * every field of the order, so a decision alone is enough to rebuild the record.
 *
 * <p>This is a convenience view with an expiry, not a system of record. The log is the record; Postgres
 * gets the durable copy when the writers arrive.
 */
@Component
public class OrderStore {
    private final StringRedisTemplate redis;
    private final ApiProperties props;

    public OrderStore(StringRedisTemplate redis, ApiProperties props) {
        this.redis = redis;
        this.props = props;
    }

    public void pending(OrderRequest o) {
        String key = key(o.orderId());
        redis.opsForHash().putAll(key, order(o.accountId(), o.instrumentId(), o.side(), o.qty()));
        redis.opsForHash().putIfAbsent(key, "status", OrderView.PENDING);
        redis.expire(key, props.orderTtl());
    }

    public void decided(OrderDecision d) {
        String key = key(d.orderId());
        Map<String, String> fields = order(d.accountId(), d.instrumentId(), d.side(), d.qty());
        fields.put("status", d.accepted() ? OrderView.ACCEPTED : OrderView.REJECTED);
        fields.put("reason", d.reason().name());
        fields.put("requiredAfter", rupees(d.requiredAfter()));
        fields.put("availableAfter", rupees(d.availableAfter()));
        fields.put("utilisationAfter", Double.isInfinite(d.utilisationAfter()) ? "INF"
                : new BigDecimal(d.utilisationAfter()).setScale(4, RoundingMode.HALF_EVEN).toPlainString());
        redis.opsForHash().putAll(key, fields);
        redis.expire(key, props.orderTtl());
    }

    /** Removes an order that was never written to the log. */
    public void forget(String orderId) {
        redis.delete(key(orderId));
    }

    public Optional<OrderView> find(String orderId) {
        Map<Object, Object> f = redis.opsForHash().entries(key(orderId));
        if (f.isEmpty()) return Optional.empty();
        return Optional.of(new OrderView(orderId, (String) f.get("accountId"), (String) f.get("instrumentId"), (String) f.get("side"),
                Long.parseLong((String) f.get("qty")), (String) f.getOrDefault("status", OrderView.PENDING), (String) f.get("reason"),
                money(f.get("requiredAfter")), money(f.get("availableAfter")), (String) f.get("utilisationAfter")));
    }

    private static Map<String, String> order(String accountId, String instrumentId, OrderRequest.Side side, long qty) {
        Map<String, String> m = new TreeMap<>();
        m.put("accountId", accountId);
        m.put("instrumentId", instrumentId);
        m.put("side", side.name());
        m.put("qty", Long.toString(qty));
        return m;
    }

    private static String rupees(double v) {
        return new BigDecimal(v).setScale(2, RoundingMode.HALF_EVEN).toPlainString();
    }

    private static BigDecimal money(Object v) {
        return v == null ? null : new BigDecimal((String) v);
    }

    private static String key(String orderId) {
        return "order:" + orderId;
    }
}
