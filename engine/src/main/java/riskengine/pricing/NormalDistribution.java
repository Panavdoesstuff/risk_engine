package riskengine.pricing;

/**
 * Standard normal pdf and cdf.
 *
 * <p>The cdf is Hart's 1968 rational approximation in the form given by Graeme West, "Better approximations
 * to cumulative normal functions" (2005). For |x| < 7.07 it is a ratio of two polynomials in |x| times
 * exp(-x²/2); further out it switches to a continued fraction. Accuracy is close to double precision
 * (~1e-15), which matters here: a 1e-7 error in N(d) on a 23,000 index is about a rupee on a few lots.
 * Tests compare it against an independent erfc at many points.
 *
 * <p>Uses StrictMath so results are bit-identical on every JVM and CPU (shard state hashes depend on it).
 */
public final class NormalDistribution {
    private static final double INV_SQRT_2PI = 0.3989422804014327;

    private NormalDistribution() {}

    public static double pdf(double x) {
        return INV_SQRT_2PI * StrictMath.exp(-0.5 * x * x);
    }

    public static double cdf(double x) {
        double z = Math.abs(x);
        double tail; // P(Z > z) for z >= 0
        if (z > 37) {
            tail = 0;
        } else {
            double e = StrictMath.exp(-z * z / 2);
            if (z < 7.07106781186547) {
                double num = 3.52624965998911e-02;
                num = num * z + 0.700383064443688;
                num = num * z + 6.37396220353165;
                num = num * z + 33.912866078383;
                num = num * z + 112.079291497871;
                num = num * z + 221.213596169931;
                num = num * z + 220.206867912376;
                double den = 8.83883476483184e-02;
                den = den * z + 1.75566716318264;
                den = den * z + 16.064177579207;
                den = den * z + 86.7807322029461;
                den = den * z + 296.564248779674;
                den = den * z + 637.333633378831;
                den = den * z + 793.826512519948;
                den = den * z + 440.413735824752;
                tail = e * num / den;
            } else {
                double cf = z + 0.65;
                cf = z + 4 / cf;
                cf = z + 3 / cf;
                cf = z + 2 / cf;
                cf = z + 1 / cf;
                tail = e / cf / 2.506628274631;
            }
        }
        return x > 0 ? 1 - tail : tail;
    }
}
