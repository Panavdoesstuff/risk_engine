package riskengine.margin;

import riskengine.ref.InstrumentType;

/**
 * One account's aggregated risk on one underlying: the sum over positions of qty x {@link RiskArray}.
 *
 * <p>Three ways to change it, all linear:
 * <ul>
 *   <li>{@link #add} / {@link #remove}: a position appears, disappears or changes size (fills).
 *       Short-option and exposure totals depend on the sign of qty, so remove must use the same qty and
 *       average price that were added.</li>
 *   <li>{@link #applyTick}: prices moved. {@code vector += qty x (newArray - oldArray)}. This is the M3
 *       incremental update; cost basis does not change on a tick.</li>
 * </ul>
 */
public final class AccountVector {
    /** Number of values in {@link #components()}. */
    public static final int COMPONENTS = ScenarioGrid.SIZE + 5;

    private final String underlying;
    private final double[] scenarioLoss = new double[ScenarioGrid.SIZE];
    private double netOptionValue;      // long option value - short option value
    private double shortOptionNotional; // sum |qty| x spot over short options
    private double exposureNotional;    // sum |qty| x notional over futures and short options
    private double futuresValue;        // sum qty x current futures price
    private double futuresCost;         // sum qty x average entry price of futures

    public AccountVector(String underlying) {
        this.underlying = underlying;
    }

    public String underlying() {
        return underlying;
    }

    /** Adds a position opened at the current mark (zero unrealised P&L). Handy for what-if and tests. */
    public AccountVector add(long qty, RiskArray a) {
        return add(qty, a.price(), a);
    }

    /** @param avgPrice average entry price; only used for futures (option premium is already in cash) */
    public AccountVector add(long qty, double avgPrice, RiskArray a) {
        apply(qty, avgPrice, a, 1);
        return this;
    }

    public AccountVector remove(long qty, double avgPrice, RiskArray a) {
        apply(qty, avgPrice, a, -1);
        return this;
    }

    private void apply(long qty, double avgPrice, RiskArray a, int sign) {
        check(a);
        if (qty == 0) return;
        double q = (double) qty * sign;
        for (int s = 0; s < ScenarioGrid.SIZE; s++) scenarioLoss[s] += q * a.loss(s);
        double absQ = Math.abs((double) qty) * sign;
        if (a.type() == InstrumentType.FUT) {
            exposureNotional += absQ * a.notional();
            futuresValue += q * a.price();
            futuresCost += q * avgPrice;
        } else {
            netOptionValue += q * a.price();
            if (qty < 0) {
                shortOptionNotional += absQ * a.notional();
                exposureNotional += absQ * a.notional();
            }
        }
    }

    /** {@code vector += qty x (newArray - oldArray)} for a position whose qty did not change. */
    public void applyTick(long qty, RiskArray oldArray, RiskArray newArray) {
        check(newArray);
        if (qty == 0) return;
        double q = qty;
        for (int s = 0; s < ScenarioGrid.SIZE; s++) scenarioLoss[s] += q * (newArray.loss(s) - oldArray.loss(s));
        double absQ = Math.abs(q);
        if (newArray.type() == InstrumentType.FUT) {
            exposureNotional += absQ * (newArray.notional() - oldArray.notional());
            futuresValue += q * (newArray.price() - oldArray.price());
        } else {
            netOptionValue += q * (newArray.price() - oldArray.price());
            if (qty < 0) {
                double d = absQ * (newArray.notional() - oldArray.notional());
                shortOptionNotional += d;
                exposureNotional += d;
            }
        }
    }

    private void check(RiskArray a) {
        if (!a.underlying().equals(underlying)) throw new IllegalArgumentException(a.instrumentId() + " is not on " + underlying);
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

    /** Unrealised futures profit (+) or loss (-): current value minus what was paid. */
    public double futuresMtm() {
        return futuresValue - futuresCost;
    }

    /** Every number in the vector, in a fixed order. For hashing state and for comparing two vectors. */
    public double[] components() {
        double[] c = new double[COMPONENTS];
        System.arraycopy(scenarioLoss, 0, c, 0, ScenarioGrid.SIZE);
        c[ScenarioGrid.SIZE] = netOptionValue;
        c[ScenarioGrid.SIZE + 1] = shortOptionNotional;
        c[ScenarioGrid.SIZE + 2] = exposureNotional;
        c[ScenarioGrid.SIZE + 3] = futuresValue;
        c[ScenarioGrid.SIZE + 4] = futuresCost;
        return c;
    }

    /** Overwrites this vector with {@code other}'s values (same underlying). */
    public void copyFrom(AccountVector other) {
        if (!other.underlying.equals(underlying)) throw new IllegalArgumentException("different underlying");
        System.arraycopy(other.scenarioLoss, 0, scenarioLoss, 0, ScenarioGrid.SIZE);
        netOptionValue = other.netOptionValue;
        shortOptionNotional = other.shortOptionNotional;
        exposureNotional = other.exposureNotional;
        futuresValue = other.futuresValue;
        futuresCost = other.futuresCost;
    }

    /** Largest absolute difference between any component of this vector and {@code other}, in rupees. */
    public double maxDifference(AccountVector other) {
        double[] a = components(), b = other.components();
        double max = 0;
        for (int i = 0; i < COMPONENTS; i++) max = Math.max(max, Math.abs(a[i] - b[i]));
        return max;
    }

    @Override
    public String toString() {
        return underlying + " " + java.util.Arrays.toString(components());
    }
}
