package riskengine.margin;

/** Per-underlying margin parameters. See margin.properties for meanings and units. */
public record UnderlyingParams(
        String underlying,
        double dividendYield,
        double volScale,
        double priceScanRange,
        double volScanRange,
        double extremeMultiple,
        double extremeCoverage,
        double shortOptionMinimum,
        double exposureMargin,
        double minVol) {

    public UnderlyingParams {
        requireRange("priceScanRange", priceScanRange, 0, 1);
        requireRange("volScanRange", volScanRange, 0, 2);
        requireRange("extremeCoverage", extremeCoverage, 0, 1);
        requireRange("shortOptionMinimum", shortOptionMinimum, 0, 1);
        requireRange("exposureMargin", exposureMargin, 0, 1);
        requireRange("extremeMultiple", extremeMultiple, 1, 10);
        requireRange("volScale", volScale, 0, 1);
        requireRange("minVol", minVol, 0, 1);
        if (priceScanRange * extremeMultiple >= 1) {
            throw new IllegalArgumentException(underlying + ": extreme move would take the index to zero or below");
        }
    }

    private static void requireRange(String name, double v, double lo, double hi) {
        if (!(v >= lo && v <= hi)) throw new IllegalArgumentException(name + " = " + v + " outside [" + lo + ", " + hi + "]");
    }
}
