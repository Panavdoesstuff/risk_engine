package riskengine.margin;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashSet;
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
        Set<String> used = new HashSet<>();
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
