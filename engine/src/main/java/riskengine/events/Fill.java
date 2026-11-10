package riskengine.events;

import java.math.BigDecimal;

/**
 * The broker executed (part of) an order.
 *
 * @param fillId broker_fill_id; the broker may deliver the same fill more than once, so shards dedupe on it
 * @param qty    signed units: + bought, - sold
 */
public record Fill(String fillId, String orderId, String accountId, String instrumentId, long qty,
                   BigDecimal price, long logTimeMillis) implements Event, AccountEvent {
    public Fill {
        Event.id("fillId", fillId);
        Event.id("orderId", orderId);
        Event.id("accountId", accountId);
        Event.id("instrumentId", instrumentId);
        if (qty == 0) throw new IllegalArgumentException("fill qty must not be zero");
        if (price.signum() < 0) throw new IllegalArgumentException("fill price must not be negative");
    }
}
