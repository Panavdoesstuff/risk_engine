package riskengine.api.orders;

import java.math.BigDecimal;

/**
 * What GET /orders/{id} returns: the order's row in Postgres.
 *
 * @param status         PENDING until the shard has decided; then ACCEPTED or REJECTED; then, as the broker
 *                       reports, SENT, PARTIALLY_FILLED, FILLED, CANCELLED or BROKER_REJECTED
 * @param reason         why it was rejected or closed (INSUFFICIENT_MARGIN, ACCOUNT_BLOCKED, CANCELLED, ...); null otherwise
 * @param requiredAfter  margin the account would need with this order (and its earlier open orders) filled,
 *                       as the pre-trade check computed it; null while pending
 * @param availableAfter margin the account would have available in that same what-if; null while pending
 */
public record OrderView(String orderId, String accountId, String instrumentId, String side, long qty, String status, String reason,
                        BigDecimal requiredAfter, BigDecimal availableAfter) {
    public static final String PENDING = "PENDING";
}
