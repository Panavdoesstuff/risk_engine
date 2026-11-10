package riskengine.margin;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;

/** Margin model configuration, loaded from a properties file. Unknown or missing keys are errors. */
public record MarginConfig(double riskFreeRate, SortedMap<String, UnderlyingParams> underlyings, Thresholds thresholds) {

    public MarginConfig {
        underlyings = new TreeMap<>(underlyings);
    }

    public UnderlyingParams params(String underlying) {
        UnderlyingParams p = underlyings.get(underlying);
        if (p == null) throw new IllegalArgumentException("underlying " + underlying + " is not configured (configured: " + underlyings.keySet() + ")");
        return p;
    }

    /**
     * SHA-256 of every parameter value in a fixed order. Two configs with the same fingerprint produce the
     * same margins; comments and key order in the properties file don't affect it. The seeded book records
     * this, because its cash balances are sized with the margin engine.
     */
    public String fingerprint() {
        StringBuilder sb = new StringBuilder("margin-config-v1\n").append("riskFree=").append(riskFreeRate).append('\n');
        for (UnderlyingParams u : underlyings.values()) {
            sb.append(u.underlying()).append('|').append(u.dividendYield()).append('|').append(u.volScale()).append('|')
              .append(u.priceScanRange()).append('|').append(u.volScanRange()).append('|').append(u.extremeMultiple()).append('|')
              .append(u.extremeCoverage()).append('|').append(u.shortOptionMinimum()).append('|').append(u.exposureMargin()).append('|')
              .append(u.minVol()).append('\n');
        }
        sb.append("thresholds=").append(thresholds.warning()).append('|').append(thresholds.marginCall()).append('|')
          .append(thresholds.squareOff()).append('|').append(thresholds.confirmTicks()).append('\n');
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(d);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The checked-in defaults: {@code riskengine/margin.properties} on the classpath. */
    public static MarginConfig defaults() {
        try (InputStream in = MarginConfig.class.getResourceAsStream("/riskengine/margin.properties")) {
            if (in == null) throw new IllegalStateException("riskengine/margin.properties not on classpath");
            Properties p = new Properties();
            p.load(in);
            return load(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static MarginConfig load(Properties p) {
        Set<String> used = new TreeSet<>();
        Reader r = new Reader(p, used);
        SortedMap<String, UnderlyingParams> und = new TreeMap<>();
        for (String u : r.string("underlyings").split(",")) {
            u = u.trim();
            und.put(u, new UnderlyingParams(u,
                    r.num(u + ".dividendYield"), r.num(u + ".volScale"), r.num(u + ".priceScanRange"),
                    r.num(u + ".volScanRange"), r.num(u + ".extremeMultiple"), r.num(u + ".extremeCoverage"),
                    r.num(u + ".shortOptionMinimum"), r.num(u + ".exposureMargin"), r.num(u + ".minVol")));
        }
        Thresholds t = new Thresholds(r.num("thresholds.warning"), r.num("thresholds.marginCall"),
                r.num("thresholds.squareOff"), (int) r.num("thresholds.confirmTicks"));
        MarginConfig cfg = new MarginConfig(r.num("rates.riskFree"), und, t);
        Set<String> unknown = new TreeSet<>(p.stringPropertyNames());
        unknown.removeAll(used);
        if (!unknown.isEmpty()) throw new IllegalArgumentException("unknown margin config keys: " + unknown);
        return cfg;
    }

    private record Reader(Properties p, Set<String> used) {
        String string(String key) {
            String v = p.getProperty(key);
            if (v == null || v.isBlank()) throw new IllegalArgumentException("missing margin config key " + key);
            used.add(key);
            return v.trim();
        }

        double num(String key) {
            try {
                return Double.parseDouble(string(key));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("margin config key " + key + " is not a number", e);
            }
        }
    }
}
