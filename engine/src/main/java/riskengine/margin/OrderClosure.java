package riskengine.margin;

import riskengine.events.OrderClosed;

/**
 * The shard's acknowledgement of an {@link OrderClosed} event.
 *
 * @param released signed units of reservation that were released; 0 if the order had nothing reserved
 */
public record OrderClosure(String orderId, String accountId, OrderClosed.Reason reason, long released,
                           long logTimeMillis) implements EngineOutput {}
