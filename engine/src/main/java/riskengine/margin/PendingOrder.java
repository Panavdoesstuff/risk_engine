package riskengine.margin;

/**
 * An accepted order that has not (fully) filled yet. Its margin is reserved: later pre-trade checks count
 * it as if it had already filled.
 *
 * @param remaining signed units still to fill: + to buy, - to sell; never zero
 */
public record PendingOrder(String instrumentId, long remaining) {}
