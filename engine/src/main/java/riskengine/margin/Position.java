package riskengine.margin;

import java.math.BigDecimal;

/**
 * @param qty      signed units (lots x lot size); negative = short
 * @param avgPrice average entry price of the open quantity
 */
public record Position(long qty, BigDecimal avgPrice) {
    public static final Position FLAT = new Position(0, BigDecimal.ZERO);

    public static Position of(long qty, String avgPrice) {
        return new Position(qty, new BigDecimal(avgPrice));
    }
}
