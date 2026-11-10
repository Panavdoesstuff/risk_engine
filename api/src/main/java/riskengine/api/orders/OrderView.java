package riskengine.api.orders;

import java.math.BigDecimal;

/**
 * What GET /orders/{id} returns.
 *
 * @param status PENDING until the shard has decided, then ACCEPTED or REJECTED
 * @param reason the shard's reason code once decided (OK, INSUFFICIENT_MARGIN, BAD_LOT_SIZE, ...)
 * @param requiredAfter margin the account would need with this order filled (null while pending)
 */
public record OrderView(String orderId, String accountId, String instrumentId, String side, long qty, String status, String reason,
                        BigDecimal requiredAfter, BigDecimal availableAfter, String utilisationAfter) {
    public static final String PENDING = "PENDING";
    public static final String ACCEPTED = "ACCEPTED";
    public static final String REJECTED = "REJECTED";
}
