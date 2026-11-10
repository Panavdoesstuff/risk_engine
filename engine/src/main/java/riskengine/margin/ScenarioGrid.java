package riskengine.margin;

/**
 * The 16 SPAN-style risk scenarios for one underlying.
 *
 * <pre>
 *   #   price move (x scan range)   volatility   loss counted
 *   1   0                           up           100%
 *   2   0                           down         100%
 *   3   +1/3                        up           100%
 *   4   +1/3                        down         100%
 *   5   -1/3                        up           100%
 *   6   -1/3                        down         100%
 *   7   +2/3                        up           100%
 *   8   +2/3                        down         100%
 *   9   -2/3                        up           100%
 *  10   -2/3                        down         100%
 *  11   +1                          up           100%
 *  12   +1                          down         100%
 *  13   -1                          up           100%
 *  14   -1                          down         100%
 *  15   +extremeMultiple            unchanged    extremeCoverage
 *  16   -extremeMultiple            unchanged    extremeCoverage
 * </pre>
 *
 * Scenarios 15-16 exist for deep out-of-the-money short options, which look safe inside the normal range
 * but blow up on a gap. Only a fraction of their loss counts because the move is unlikely.
 * No time decay is applied (scenarios revalue at the current time to expiry), which is slightly
 * conservative for short options.
 */
public final class ScenarioGrid {
    public static final int SIZE = 16;
    private static final double[] MOVE = {0, 0, 1 / 3.0, 1 / 3.0, -1 / 3.0, -1 / 3.0, 2 / 3.0, 2 / 3.0, -2 / 3.0, -2 / 3.0, 1, 1, -1, -1};
    private static final int[] VOL = {1, -1, 1, -1, 1, -1, 1, -1, 1, -1, 1, -1, 1, -1, 0, 0};

    private final double[] spotFactor = new double[SIZE];
    private final double[] weight = new double[SIZE];
    private final UnderlyingParams params;

    public ScenarioGrid(UnderlyingParams p) {
        this.params = p;
        for (int i = 0; i < 14; i++) {
            spotFactor[i] = 1 + MOVE[i] * p.priceScanRange();
            weight[i] = 1;
        }
        spotFactor[14] = 1 + p.extremeMultiple() * p.priceScanRange();
        spotFactor[15] = 1 - p.extremeMultiple() * p.priceScanRange();
        weight[14] = weight[15] = p.extremeCoverage();
    }

    public UnderlyingParams params() {
        return params;
    }

    /** Scenario index is 0-based here; scenario numbers in docs are 1-based. */
    public double spot(int i, double spot) {
        return spot * spotFactor[i];
    }

    public double vol(int i, double vol) {
        return Math.max(params.minVol(), vol + VOL[i] * params.volScanRange());
    }

    public double weight(int i) {
        return weight[i];
    }
}
