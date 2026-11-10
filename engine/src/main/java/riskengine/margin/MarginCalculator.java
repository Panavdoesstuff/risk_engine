package riskengine.margin;

import java.util.Collection;
import java.util.List;

/** Turns account vectors into a {@link MarginResult}. Pure function, no state. See MarginResult for the formula. */
public final class MarginCalculator {
    private MarginCalculator() {}

    public static MarginResult compute(AccountVector v, MarginConfig cfg, double cash, double collateral) {
        return compute(List.of(v), cfg, cash, collateral);
    }

    /**
     * Each underlying is margined on its own and the requirements are added up: there is no offset between
     * different indices. With Nifty only this is a single vector. Pass vectors in a fixed order (sorted by
     * underlying) so the floating-point sums are reproducible.
     */
    public static MarginResult compute(Collection<AccountVector> vectors, MarginConfig cfg, double cash, double collateral) {
        double worstTotal = 0, somcTotal = 0, spanTotal = 0, novTotal = 0, riskTotal = 0, exposureTotal = 0, mtmTotal = 0;
        int worstScenario = 0;
        double worstSeen = 0;
        for (AccountVector v : vectors) {
            UnderlyingParams p = cfg.params(v.underlying());
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
        // Both parts are already >= 0; the clamp states the rule and guards against floating-point dust.
        double required = Math.max(0, riskTotal + exposureTotal);
        double available = cash + collateral + mtmTotal;
        return new MarginResult(worstTotal, worstScenario, somcTotal, spanTotal, novTotal, riskTotal, exposureTotal,
                required, mtmTotal, available, utilisation(required, available));
    }

    /** required / available; infinite if something is required and nothing is available; 0 if nothing is required. */
    public static double utilisation(double required, double available) {
        if (required <= 0) return 0;
        if (available <= 0) return Double.POSITIVE_INFINITY;
        return required / available;
    }
}
