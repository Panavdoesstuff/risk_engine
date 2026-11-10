package riskengine.margin;

import java.util.Arrays;
import riskengine.pricing.BlackScholes;
import riskengine.pricing.TimeToExpiry;
import riskengine.ref.Instrument;
import riskengine.ref.InstrumentType;

/**
 * Everything the margin model needs about ONE unit (one contract unit, not one lot) of ONE instrument,
 * held long, at the current market state. Positions are qty x this, so account totals are linear in qty
 * (which is what makes the M3 incremental update {@code vector += qty * (new - old)} exact).
 *
 * <ul>
 *   <li>{@code price}: theoretical value now</li>
 *   <li>{@code loss[s]}: (price now - price in scenario s) x scenario weight. Positive = the long unit loses.
 *       A long option can never lose more than {@code price}, because option prices can't go below zero.</li>
 *   <li>{@code notional}: spot for options, futures price for futures. Base for the short option minimum
 *       and exposure margin.</li>
 * </ul>
 */
public final class RiskArray {
    private final String instrumentId;
    private final String underlying;
    private final InstrumentType type;
    private final double price;
    private final double notional;
    private final double[] loss;

    RiskArray(String instrumentId, String underlying, InstrumentType type, double price, double notional, double[] loss) {
        if (loss.length != ScenarioGrid.SIZE) throw new IllegalArgumentException("need " + ScenarioGrid.SIZE + " scenarios");
        this.instrumentId = instrumentId;
        this.underlying = underlying;
        this.type = type;
        this.price = price;
        this.notional = notional;
        this.loss = loss.clone();
    }

    public static RiskArray compute(Instrument i, MarketState m, ScenarioGrid grid, double riskFreeRate) {
        if (!i.underlying().equals(m.underlying())) {
            throw new IllegalArgumentException(i.id() + " is not on " + m.underlying());
        }
        double t = TimeToExpiry.years(i.expiry(), m.logTimeMillis());
        double q = grid.params().dividendYield(), r = riskFreeRate;
        double k = i.strike() == null ? 0 : i.strike().doubleValue();
        double price = BlackScholes.price(i.type(), m.spot(), k, t, m.vol(), r, q);
        double[] loss = new double[ScenarioGrid.SIZE];
        for (int s = 0; s < ScenarioGrid.SIZE; s++) {
            double p = BlackScholes.price(i.type(), grid.spot(s, m.spot()), k, t, grid.vol(s, m.vol()), r, q);
            loss[s] = (price - p) * grid.weight(s);
        }
        double notional = i.type() == InstrumentType.FUT ? price : m.spot();
        return new RiskArray(i.id(), i.underlying(), i.type(), price, notional, loss);
    }

    public String instrumentId() {
        return instrumentId;
    }

    public String underlying() {
        return underlying;
    }

    public InstrumentType type() {
        return type;
    }

    public double price() {
        return price;
    }

    public double notional() {
        return notional;
    }

    /** 0-based scenario index. */
    public double loss(int scenario) {
        return loss[scenario];
    }

    @Override
    public String toString() {
        return instrumentId + " price=" + price + " loss=" + Arrays.toString(loss);
    }
}
