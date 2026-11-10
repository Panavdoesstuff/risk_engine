package riskengine.pricing;

/**
 * Sensitivities of one option unit.
 *
 * @param delta per 1 point move in the underlying
 * @param gamma change in delta per 1 point move
 * @param vega  per 1.00 change in volatility (i.e. per 100 vol points); see {@link #vegaPerVolPoint()}
 * @param theta per year of time passing (negative = the option loses value); see {@link #thetaPerDay()}
 */
public record Greeks(double delta, double gamma, double vega, double theta) {
    public double vegaPerVolPoint() {
        return vega / 100;
    }

    /** Per calendar day, matching the ACT/365 year used for time to expiry. */
    public double thetaPerDay() {
        return theta / 365;
    }
}
