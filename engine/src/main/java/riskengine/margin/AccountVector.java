package riskengine.margin;

import java.util.Arrays;
import riskengine.ref.InstrumentType;

/**
 * One account's aggregated risk on one underlying: the sum over positions of qty x {@link RiskArray}.
 *
 * <p>Short-option and exposure totals depend on the sign of qty, so {@link #remove} must be called with
 * the same qty that was added. In M3 a tick becomes {@code remove(qty, old); add(qty, new)} per position;
 * a fill becomes {@code remove(oldQty, a); add(newQty, a)}.
 */
public final class AccountVector {
    private final String underlying;
    private final double[] scenarioLoss = new double[ScenarioGrid.SIZE];
    private double netOptionValue;      // long option value - short option value
    private double shortOptionNotional; // sum |qty| x spot over short options
    private double exposureNotional;    // sum |qty| x notional over futures and short options

    public AccountVector(String underlying) {
        this.underlying = underlying;
    }

    public String underlying() {
        return underlying;
    }

    public AccountVector add(long qty, RiskArray a) {
        apply(qty, a, 1);
        return this;
    }

    public AccountVector remove(long qty, RiskArray a) {
        apply(qty, a, -1);
        return this;
    }

    private void apply(long qty, RiskArray a, int sign) {
        if (!a.underlying().equals(underlying)) throw new IllegalArgumentException(a.instrumentId() + " is not on " + underlying);
        if (qty == 0) return;
        double q = (double) qty * sign;
        for (int s = 0; s < ScenarioGrid.SIZE; s++) scenarioLoss[s] += q * a.loss(s);
        double absQ = Math.abs((double) qty) * sign;
        if (a.type() == InstrumentType.FUT) {
            exposureNotional += absQ * a.notional();
        } else {
            netOptionValue += q * a.price();
            if (qty < 0) {
                shortOptionNotional += absQ * a.notional();
                exposureNotional += absQ * a.notional();
            }
        }
    }

    /** Portfolio loss in a scenario (0-based); positive = the account loses money. */
    public double scenarioLoss(int scenario) {
        return scenarioLoss[scenario];
    }

    public double netOptionValue() {
        return netOptionValue;
    }

    public double shortOptionNotional() {
        return shortOptionNotional;
    }

    public double exposureNotional() {
        return exposureNotional;
    }

    @Override
    public String toString() {
        return underlying + " loss=" + Arrays.toString(scenarioLoss) + " nov=" + netOptionValue
                + " shortOptNotional=" + shortOptionNotional + " exposureNotional=" + exposureNotional;
    }
}
