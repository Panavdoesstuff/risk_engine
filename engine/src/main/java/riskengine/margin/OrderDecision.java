package riskengine.margin;

import riskengine.events.OrderRequest;

/**
 * The shard's answer to one {@link OrderRequest}: the pre-trade margin check.
 *
 * @param lastTickSeq      the tick whose prices the check used (Long.MIN_VALUE if none had arrived)
 * @param logTimeMillis    the order's own log time
 * @param requiredAfter    margin required if this order and every earlier accepted, unfilled order filled
 *                         at the current mark; 0 when the order was rejected before any margin was computed
 * @param availableAfter   available margin in that same what-if
 * @param utilisationAfter requiredAfter / availableAfter
 * @param mark             the price the check assumed for the instrument (0 if it never got as far as pricing)
 */
public record OrderDecision(String orderId, String accountId, String instrumentId, OrderRequest.Side side, long qty,
                            boolean accepted, Reason reason, long lastTickSeq, long logTimeMillis,
                            double requiredAfter, double availableAfter, double utilisationAfter, java.math.BigDecimal mark) implements EngineOutput {

    public enum Reason {
        /** Accepted. */
        OK,
        /** No such account in the shard that owns this partition. */
        UNKNOWN_ACCOUNT,
        /** No such instrument, or its underlying is not configured for margining. */
        UNKNOWN_INSTRUMENT,
        /** Quantity is not a whole number of lots. */
        BAD_LOT_SIZE,
        /** No tick yet for something the account holds or wants to trade, so nothing can be priced. */
        NO_MARKET,
        /** Risk-ops (or a square-off that ran out of things to close) has blocked the account. */
        ACCOUNT_BLOCKED,
        /** The account is being squared off; no new orders until that ends. */
        SQUARING_OFF,
        /** The trade would leave the account at or over 100% utilisation (or out of cash) and does not reduce its shortfall. */
        INSUFFICIENT_MARGIN
    }

    public static OrderDecision rejected(OrderRequest o, Reason reason, long lastTickSeq) {
        return new OrderDecision(o.orderId(), o.accountId(), o.instrumentId(), o.side(), o.qty(), false, reason,
                lastTickSeq, o.logTimeMillis(), 0, 0, 0, java.math.BigDecimal.ZERO);
    }

    public static OrderDecision checked(OrderRequest o, boolean accepted, long lastTickSeq, MarginResult after, java.math.BigDecimal mark) {
        return new OrderDecision(o.orderId(), o.accountId(), o.instrumentId(), o.side(), o.qty(), accepted,
                accepted ? Reason.OK : Reason.INSUFFICIENT_MARGIN, lastTickSeq, o.logTimeMillis(),
                after.required(), after.available(), after.utilisation(), mark);
    }
}
