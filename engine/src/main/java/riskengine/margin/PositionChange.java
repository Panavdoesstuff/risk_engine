package riskengine.margin;

import java.math.BigDecimal;

/**
 * A fill was applied. Carries the fill itself (for the fills table and the ledger) and the position and
 * the account's cash as they are after it, so a consumer can simply store the latest one it has seen.
 *
 * @param qty            net signed units after the fill; 0 = closed
 * @param avgPrice       average entry price after the fill (0 when closed)
 * @param cash           the account's cash after the fill
 * @param fillQty        signed units this fill traded: + bought, - sold
 * @param cashDelta      what the fill did to cash: option premium paid (-) or received (+), or the futures
 *                       profit or loss it realised; 0 for a fill that only opens or adds to a future
 * @param orderRemaining signed units the fill's order still has open after it (0 = fully filled), for a
 *                       user order with a reservation or the square-off leg in flight; Long.MIN_VALUE if
 *                       the engine is not tracking that order
 */
public record PositionChange(String accountId, String instrumentId, long qty, BigDecimal avgPrice, BigDecimal cash,
                             String fillId, String orderId, long fillQty, BigDecimal fillPrice, BigDecimal cashDelta,
                             long orderRemaining, long logTimeMillis) implements EngineOutput {
    public static final long NOT_TRACKED = Long.MIN_VALUE;
}
