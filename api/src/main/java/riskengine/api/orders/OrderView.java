package riskengine.api.orders;

/**
 * What GET /orders/{id} returns: the order's row in Postgres.
 *
 * @param status PENDING until the shard has decided, then ACCEPTED or REJECTED; later CANCELLED or
 *               BROKER_REJECTED if the order is closed without filling
 * @param reason why it was rejected or closed (INSUFFICIENT_MARGIN, BAD_LOT_SIZE, CANCELLED, ...); null otherwise
 */
public record OrderView(String orderId, String accountId, String instrumentId, String side, long qty, String status, String reason) {
    public static final String PENDING = "PENDING";
}
