package riskengine.margin;

import java.math.BigDecimal;

/**
 * A fill changed a position. Carries the position and the account's cash as they are after the fill, so
 * a consumer can simply store the latest one it has seen.
 *
 * @param qty      net signed units after the fill; 0 = closed
 * @param avgPrice average entry price after the fill (0 when closed)
 * @param cash     the account's cash after the fill
 */
public record PositionChange(String accountId, String instrumentId, long qty, BigDecimal avgPrice, BigDecimal cash,
                             String fillId, long logTimeMillis) implements EngineOutput {}
