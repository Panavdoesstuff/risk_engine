package riskengine.pricing;

import riskengine.ref.InstrumentType;

/**
 * Black-Scholes-Merton for European options on an index paying a continuous dividend yield q.
 * Nifty options are European and cash-settled on the spot index, so this is the right model.
 *
 * <pre>
 *   d1 = [ln(S/K) + (r - q + σ²/2) T] / (σ √T)        d2 = d1 - σ √T
 *   call = S e^(-qT) N(d1) - K e^(-rT) N(d2)
 *   put  = K e^(-rT) N(-d2) - S e^(-qT) N(-d1)
 *   futures = S e^((r - q) T)                          (cost of carry)
 * </pre>
 *
 * S spot, K strike, T years to expiry, σ annual volatility, r risk-free rate, q dividend yield (all
 * continuously compounded). At or after expiry (T <= 0) options are worth intrinsic value. All maths uses
 * StrictMath for cross-platform bit-identical results.
 */
public final class BlackScholes {
    private BlackScholes() {}

    public static double call(double s, double k, double t, double sigma, double r, double q) {
        if (t <= 0 || sigma <= 0) return Math.max(0, forwardIntrinsic(s, k, Math.max(t, 0), r, q));
        double sqrtT = StrictMath.sqrt(t);
        double d1 = d1(s, k, t, sigma, r, q, sqrtT), d2 = d1 - sigma * sqrtT;
        return s * StrictMath.exp(-q * t) * NormalDistribution.cdf(d1) - k * StrictMath.exp(-r * t) * NormalDistribution.cdf(d2);
    }

    public static double put(double s, double k, double t, double sigma, double r, double q) {
        if (t <= 0 || sigma <= 0) return Math.max(0, -forwardIntrinsic(s, k, Math.max(t, 0), r, q));
        double sqrtT = StrictMath.sqrt(t);
        double d1 = d1(s, k, t, sigma, r, q, sqrtT), d2 = d1 - sigma * sqrtT;
        return k * StrictMath.exp(-r * t) * NormalDistribution.cdf(-d2) - s * StrictMath.exp(-q * t) * NormalDistribution.cdf(-d1);
    }

    public static double futures(double s, double t, double r, double q) {
        return s * StrictMath.exp((r - q) * Math.max(t, 0));
    }

    /** Price of one unit of any instrument type. {@code k} and {@code sigma} are ignored for futures. */
    public static double price(InstrumentType type, double s, double k, double t, double sigma, double r, double q) {
        return switch (type) {
            case FUT -> futures(s, t, r, q);
            case CE -> call(s, k, t, sigma, r, q);
            case PE -> put(s, k, t, sigma, r, q);
        };
    }

    public static Greeks greeks(InstrumentType type, double s, double k, double t, double sigma, double r, double q) {
        if (type == InstrumentType.FUT) {
            return new Greeks(StrictMath.exp((r - q) * Math.max(t, 0)), 0, 0, -(r - q) * futures(s, t, r, q));
        }
        boolean call = type == InstrumentType.CE;
        if (t <= 0 || sigma <= 0) {
            boolean itm = call ? s > k : s < k;
            return new Greeks(itm ? (call ? 1 : -1) : 0, 0, 0, 0);
        }
        double sqrtT = StrictMath.sqrt(t);
        double d1 = d1(s, k, t, sigma, r, q, sqrtT), d2 = d1 - sigma * sqrtT;
        double dq = StrictMath.exp(-q * t), dr = StrictMath.exp(-r * t), nd1 = NormalDistribution.pdf(d1);
        double gamma = dq * nd1 / (s * sigma * sqrtT);
        double vega = s * dq * nd1 * sqrtT;
        double decay = -s * dq * nd1 * sigma / (2 * sqrtT);
        if (call) {
            double delta = dq * NormalDistribution.cdf(d1);
            double theta = decay + q * s * dq * NormalDistribution.cdf(d1) - r * k * dr * NormalDistribution.cdf(d2);
            return new Greeks(delta, gamma, vega, theta);
        }
        double delta = dq * (NormalDistribution.cdf(d1) - 1);
        double theta = decay - q * s * dq * NormalDistribution.cdf(-d1) + r * k * dr * NormalDistribution.cdf(-d2);
        return new Greeks(delta, gamma, vega, theta);
    }

    static double d1(double s, double k, double t, double sigma, double r, double q, double sqrtT) {
        return (StrictMath.log(s / k) + (r - q + 0.5 * sigma * sigma) * t) / (sigma * sqrtT);
    }

    /** S e^(-qT) - K e^(-rT): what the option is worth if volatility were zero. */
    private static double forwardIntrinsic(double s, double k, double t, double r, double q) {
        return s * StrictMath.exp(-q * t) - k * StrictMath.exp(-r * t);
    }
}
