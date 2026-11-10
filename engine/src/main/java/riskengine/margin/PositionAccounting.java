package riskengine.margin;

import java.math.BigDecimal;
import java.math.RoundingMode;
import riskengine.ref.InstrumentType;

/**
 * What a fill does to a position and to cash. One implementation, used by both the reference engine and
 * the shards, so the two can only disagree about margin maths, never about bookkeeping.
 *
 * <ul>
 *   <li><b>Options:</b> premium moves in cash at once. Buying 50 at 100 costs 5,000; selling credits it.</li>
 *   <li><b>Futures:</b> no cash moves when opening. When a fill closes part of a position, the profit or
 *       loss on the closed part is realised into cash. The still-open part's unrealised P&L is counted by
 *       the margin calculator (futures MTM).</li>
 *   <li><b>Average price:</b> adding to a position gives a quantity-weighted average. Reducing leaves it
 *       unchanged. Flipping through zero restarts it at the fill price.</li>
 * </ul>
 */
public final class PositionAccounting {
    /** Average prices keep 6 decimals; rounding mode is fixed so replays are identical. */
    static final int AVG_SCALE = 6;

    private PositionAccounting() {}

    public record Outcome(Position position, BigDecimal cashDelta) {}

    /** @param fillQty signed: + bought, - sold; never zero */
    public static Outcome apply(InstrumentType type, Position old, long fillQty, BigDecimal price) {
        if (fillQty == 0) throw new IllegalArgumentException("fill qty must not be zero");
        if (price.signum() < 0) throw new IllegalArgumentException("negative fill price");
        long oldQty = old.qty(), newQty = Math.addExact(oldQty, fillQty);
        boolean increases = oldQty == 0 || Long.signum(oldQty) == Long.signum(fillQty);

        BigDecimal avg;
        BigDecimal realised = BigDecimal.ZERO;
        if (increases) {
            BigDecimal cost = old.avgPrice().multiply(BigDecimal.valueOf(Math.abs(oldQty)))
                    .add(price.multiply(BigDecimal.valueOf(Math.abs(fillQty))));
            avg = cost.divide(BigDecimal.valueOf(Math.abs(newQty)), AVG_SCALE, RoundingMode.HALF_EVEN);
        } else {
            long closed = Math.min(Math.abs(fillQty), Math.abs(oldQty));
            // long position closed by selling above avg = profit; short closed by buying below avg = profit
            realised = price.subtract(old.avgPrice()).multiply(BigDecimal.valueOf(closed * Long.signum(oldQty)));
            if (newQty == 0) avg = BigDecimal.ZERO;
            else if (Long.signum(newQty) == Long.signum(oldQty)) avg = old.avgPrice();
            else avg = price.setScale(AVG_SCALE, RoundingMode.HALF_EVEN);
        }
        BigDecimal cashDelta = type == InstrumentType.FUT
                ? realised
                : price.multiply(BigDecimal.valueOf(fillQty)).negate();
        return new Outcome(newQty == 0 ? Position.FLAT : new Position(newQty, avg), cashDelta);
    }
}
