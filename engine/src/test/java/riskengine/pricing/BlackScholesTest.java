package riskengine.pricing;

import static org.junit.jupiter.api.Assertions.*;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.Scale;
import org.junit.jupiter.api.Test;
import riskengine.ref.InstrumentType;

class BlackScholesTest {

    // ---------------------------------------------------------------- normal cdf

    /** Reference values from Python's math.erfc: N(x) = erfc(-x/sqrt 2) / 2. */
    private static final double[][] CDF_REFERENCE = {
            {-38, 2.88542835e-316},
            {-37.5, 4.605353009582584e-308},
            {-10, 7.619853024160593e-24},
            {-8, 6.22096057427182e-16},
            {-7.1, 6.237844463331639e-13},
            {-7.0, 1.2798125438858348e-12},
            {-5, 2.866515718791945e-07},
            {-3, 0.0013498980316300957},
            {-2.5, 0.0062096653257761375},
            {-1.96, 0.02499789514822043},
            {-1, 0.15865525393145705},
            {-0.5, 0.30853753872598694},
            {-0.1, 0.460172162722971},
            {0, 0.5},
            {0.1, 0.539827837277029},
            {0.25, 0.5987063256829237},
            {0.5, 0.691462461274013},
            {1, 0.8413447460685429},
            {1.5, 0.9331927987311419},
            {1.96, 0.9750021048517795},
            {2, 0.9772498680518208},
            {3, 0.9986501019683699},
            {4, 0.9999683287581669},
            {6, 0.9999999990134123},
            {7.05, 0.9999999999991054},
            {7.1, 0.9999999999993762},
            {9, 1.0},
    };

    @Test
    void normalCdfMatchesIndependentErfcToDoublePrecision() {
        for (double[] p : CDF_REFERENCE) {
            double x = p[0], expected = p[1], got = NormalDistribution.cdf(x);
            if (expected < 1e-300) {
                assertTrue(got < 1e-300, "x=" + x);
            } else {
                assertEquals(expected, got, Math.max(1e-15, 1e-13 * expected), "x=" + x);
            }
        }
    }

    @Property
    void normalCdfIsSymmetricAndMonotone(@ForAll @DoubleRange(min = -12, max = 12) double x) {
        assertEquals(1.0, NormalDistribution.cdf(x) + NormalDistribution.cdf(-x), 1e-15);
        assertTrue(NormalDistribution.cdf(x + 1e-3) >= NormalDistribution.cdf(x));
    }

    // ---------------------------------------------------------------- textbook prices (Hull, Options, Futures and Other Derivatives)

    @Test
    void hullExample_noDividend_S42_K40() {
        // S=42, K=40, r=10%, sigma=20%, T=0.5: c = 4.76, p = 0.81
        assertEquals(4.76, BlackScholes.call(42, 40, 0.5, 0.2, 0.10, 0), 0.005);
        assertEquals(0.81, BlackScholes.put(42, 40, 0.5, 0.2, 0.10, 0), 0.005);
    }

    @Test
    void hullExample_indexWithDividendYield_S930_K900() {
        // European call on an index: S=930, K=900, r=8%, q=3%, sigma=20%, T=2/12: c = 51.83
        assertEquals(51.83, BlackScholes.call(930, 900, 2.0 / 12, 0.2, 0.08, 0.03), 0.005);
    }

    @Test
    void hullGreeksExample_S49_K50() {
        // S=49, K=50, r=5%, sigma=20%, T=20 weeks (0.3846): c = 2.40, delta 0.522, gamma 0.066,
        // vega 12.1 (per 1.00 vol), theta -4.31 per year (-0.0118 per day)
        double t = 0.3846;
        assertEquals(2.40, BlackScholes.call(49, 50, t, 0.2, 0.05, 0), 0.005);
        Greeks g = BlackScholes.greeks(InstrumentType.CE, 49, 50, t, 0.2, 0.05, 0);
        assertEquals(0.522, g.delta(), 0.0005);
        assertEquals(0.066, g.gamma(), 0.0005);
        assertEquals(12.1, g.vega(), 0.05);
        assertEquals(0.121, g.vegaPerVolPoint(), 0.0005);
        assertEquals(-4.31, g.theta(), 0.005);
        assertEquals(-0.0118, g.thetaPerDay(), 0.00005);
    }

    @Test
    void futuresIsCostOfCarry() {
        assertEquals(23000 * Math.exp((0.065 - 0.012) * 0.25), BlackScholes.futures(23000, 0.25, 0.065, 0.012), 1e-9);
        assertEquals(23000, BlackScholes.futures(23000, -0.01, 0.065, 0.012), 0, "expired future = spot");
    }

    @Test
    void atAndAfterExpiryOptionsAreWorthIntrinsic() {
        assertEquals(300, BlackScholes.call(23300, 23000, 0, 0.2, 0.065, 0.012), 1e-12);
        assertEquals(0, BlackScholes.put(23300, 23000, 0, 0.2, 0.065, 0.012), 1e-12);
        assertEquals(500, BlackScholes.put(22500, 23000, -1e-6, 0.2, 0.065, 0.012), 1e-12);
        assertEquals(1, BlackScholes.greeks(InstrumentType.CE, 23300, 23000, 0, 0.2, 0, 0).delta());
        assertEquals(0, BlackScholes.greeks(InstrumentType.PE, 23300, 23000, 0, 0.2, 0, 0).delta());
    }

    // ---------------------------------------------------------------- properties

    @Property(tries = 2000)
    void putCallParityHolds(@ForAll @DoubleRange(min = 5_000, max = 60_000) double s,
                            @ForAll @DoubleRange(min = 0.6, max = 1.4) double moneyness,
                            @ForAll @DoubleRange(min = 1e-4, max = 2) @Scale(6) double t,
                            @ForAll @DoubleRange(min = 0.05, max = 1.0) double sigma,
                            @ForAll @DoubleRange(min = 0, max = 0.12) double r,
                            @ForAll @DoubleRange(min = 0, max = 0.05) double q) {
        double k = s * moneyness;
        double lhs = BlackScholes.call(s, k, t, sigma, r, q) - BlackScholes.put(s, k, t, sigma, r, q);
        double rhs = s * Math.exp(-q * t) - k * Math.exp(-r * t);
        assertEquals(rhs, lhs, 1e-9 * s, "c - p = S e^-qT - K e^-rT");
    }

    @Property(tries = 1000)
    void pricesStayWithinNoArbitrageBounds(@ForAll @DoubleRange(min = 5_000, max = 60_000) double s,
                                           @ForAll @DoubleRange(min = 0.6, max = 1.4) double moneyness,
                                           @ForAll @DoubleRange(min = 1e-4, max = 2) @Scale(6) double t,
                                           @ForAll @DoubleRange(min = 0.05, max = 1.0) double sigma) {
        double k = s * moneyness, r = 0.065, q = 0.012, eps = 1e-9 * s;
        double c = BlackScholes.call(s, k, t, sigma, r, q), p = BlackScholes.put(s, k, t, sigma, r, q);
        double fwd = s * Math.exp(-q * t), pvk = k * Math.exp(-r * t);
        assertTrue(c >= Math.max(0, fwd - pvk) - eps && c <= fwd + eps, "call bounds");
        assertTrue(p >= Math.max(0, pvk - fwd) - eps && p <= pvk + eps, "put bounds");
    }

    @Property(tries = 1000)
    void analyticGreeksMatchFiniteDifferences(@ForAll @DoubleRange(min = 10_000, max = 50_000) double s,
                                              @ForAll @DoubleRange(min = 0.85, max = 1.15) double moneyness,
                                              @ForAll @DoubleRange(min = 0.01, max = 1) double t,
                                              @ForAll @DoubleRange(min = 0.08, max = 0.6) double sigma,
                                              @ForAll boolean isCall) {
        InstrumentType type = isCall ? InstrumentType.CE : InstrumentType.PE;
        double k = s * moneyness, r = 0.065, q = 0.012;
        Greeks g = BlackScholes.greeks(type, s, k, t, sigma, r, q);
        double hs = s * 1e-4, hv = 1e-4, ht = 1e-5;
        double up = BlackScholes.price(type, s + hs, k, t, sigma, r, q), dn = BlackScholes.price(type, s - hs, k, t, sigma, r, q);
        double mid = BlackScholes.price(type, s, k, t, sigma, r, q);
        // Delta uses a 10x smaller step: central-difference error shrinks with step², and near-expiry
        // ITM options have large third derivatives.
        double hd = s * 1e-5;
        double dUp = BlackScholes.price(type, s + hd, k, t, sigma, r, q), dDn = BlackScholes.price(type, s - hd, k, t, sigma, r, q);
        assertEquals((dUp - dDn) / (2 * hd), g.delta(), 1e-7, "delta");
        assertEquals((up - 2 * mid + dn) / (hs * hs), g.gamma(), 1e-4 * Math.max(g.gamma(), 1e-6) + 1e-8, "gamma");
        double vUp = BlackScholes.price(type, s, k, t, sigma + hv, r, q), vDn = BlackScholes.price(type, s, k, t, sigma - hv, r, q);
        assertEquals((vUp - vDn) / (2 * hv), g.vega(), 1e-4 * Math.max(1, g.vega()), "vega");
        // theta is value change as time passes, i.e. as T shrinks
        double tUp = BlackScholes.price(type, s, k, t + ht, sigma, r, q), tDn = BlackScholes.price(type, s, k, t - ht, sigma, r, q);
        assertEquals(-(tUp - tDn) / (2 * ht), g.theta(), 1e-4 * Math.max(1, Math.abs(g.theta())), "theta");
    }

    @Test
    void timeToExpiryIsMeasuredTo1530Ist() {
        long valuation = java.time.OffsetDateTime.parse("2024-06-06T15:30:00+05:30").toInstant().toEpochMilli();
        assertEquals(7.0 / 365, TimeToExpiry.years(java.time.LocalDate.of(2024, 6, 13), valuation), 1e-15);
        long open = java.time.OffsetDateTime.parse("2024-06-13T09:15:00+05:30").toInstant().toEpochMilli();
        assertEquals(375.0 / (365 * 24 * 60), TimeToExpiry.years(java.time.LocalDate.of(2024, 6, 13), open), 1e-15);
    }
}
