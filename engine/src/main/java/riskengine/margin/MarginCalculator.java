package riskengine.margin;

import java.util.Collection;

/** Turns account vectors into a {@link MarginResult}. Pure function, no state. See MarginResult for the formula. */
public final class MarginCalculator {
    /** A requirement smaller than this (a millionth of a rupee) is rounding dust and counts as zero. */
    public static final double REQUIRED_DUST_RUPEES = 1e-6;

    private MarginCalculator() {}

    public static MarginResult compute(AccountVector v, MarginConfig cfg, double cash, double collateral) {
        Totals t = new Totals();
        t.add(v, params(v, cfg));
        return t.result(cash, collateral);
    }

    /**
     * Each underlying is margined on its own and the requirements are added up: there is no offset between
     * different indices. With Nifty only this is a single vector. Pass vectors in a fixed order (sorted by
     * underlying) so the floating-point sums are reproducible. A single vector gives exactly what the
     * one-vector method gives: both start from zero and add the same terms in the same order.
     */
    public static MarginResult compute(Collection<AccountVector> vectors, MarginConfig cfg, double cash, double collateral) {
        Totals t = new Totals();
        for (AccountVector v : vectors) t.add(v, params(v, cfg));
        return t.result(cash, collateral);
    }

    /** The vector's underlying's parameters, looked up once per vector and config rather than on every tick. */
    private static UnderlyingParams params(AccountVector v, MarginConfig cfg) {
        if (v.paramsFor != cfg) {
            v.params = cfg.params(v.underlying());
            v.paramsFor = cfg;
        }
        return v.params;
    }

    private static final class Totals {
        double worstTotal, somcTotal, spanTotal, novTotal, riskTotal, exposureTotal, mtmTotal;
        int worstScenario;
        double worstSeen;

        void add(AccountVector v, UnderlyingParams p) {
            double worst = 0;
            int scenario = 0;
            for (int s = 0; s < ScenarioGrid.SIZE; s++) {
                if (v.scenarioLoss(s) > worst) {
                    worst = v.scenarioLoss(s);
                    scenario = s + 1;
                }
            }
            double somc = p.shortOptionMinimum() * v.shortOptionNotional();
            double span = Math.max(somc, worst);
            double risk = Math.max(0, span - v.netOptionValue());
            double exposure = p.exposureMargin() * v.exposureNotional();
            worstTotal += worst;
            somcTotal += somc;
            spanTotal += span;
            novTotal += v.netOptionValue();
            riskTotal += risk;
            exposureTotal += exposure;
            mtmTotal += v.futuresMtm();
            if (worst > worstSeen) {
                worstSeen = worst;
                worstScenario = scenario;
            }
        }

        MarginResult result(double cash, double collateral) {
            // Both parts are already >= 0; the clamp states the rule and guards against floating-point dust.
            double required = Math.max(0, riskTotal + exposureTotal);
            // Dust is not a requirement. A shard's vector is updated incrementally, so after a short position
            // is closed its totals can be left at 1e-10 instead of 0 until the next rebuild. Without this, an
            // account with nothing at risk and negative cash would show "something required, nothing
            // available" = infinite utilisation, and be squared off for a ten-billionth of a rupee.
            if (required < REQUIRED_DUST_RUPEES) required = 0;
            double available = cash + collateral + mtmTotal;
            return new MarginResult(worstTotal, worstScenario, somcTotal, spanTotal, novTotal, riskTotal, exposureTotal,
                    required, mtmTotal, available, utilisation(required, available));
        }
    }

    /** required / available; infinite if something is required and nothing is available; 0 if nothing is required. */
    public static double utilisation(double required, double available) {
        if (required <= 0) return 0;
        if (available <= 0) return Double.POSITIVE_INFINITY;
        return required / available;
    }
}
