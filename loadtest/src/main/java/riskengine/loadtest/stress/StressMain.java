package riskengine.loadtest.stress;

import java.util.TreeMap;

/**
 * <pre>
 *   ./gradlew :loadtest:stress
 *   ./gradlew :loadtest:stress --args="--redpanda localhost:19092"
 *   options: --events 1000000 --ticks 5000 --seed 20240604 --redpanda HOST:PORT
 * </pre>
 * Exits with status 1 if there is any mismatch.
 */
public final class StressMain {
    private StressMain() {}

    public static void main(String[] args) throws InterruptedException {
        TreeMap<String, String> f = new TreeMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) f.put(args[i].replaceFirst("^--", ""), args[i + 1]);
        StressConfig cfg = StressConfig.gate();
        if (f.containsKey("events") || f.containsKey("ticks")) {
            cfg = cfg.withEvents(Long.parseLong(f.getOrDefault("events", "1000000")), Integer.parseInt(f.getOrDefault("ticks", "5000")));
        }
        if (f.containsKey("seed")) cfg = cfg.withSeed(Long.parseLong(f.get("seed")));
        if (f.containsKey("redpanda")) cfg = cfg.withRedpanda(f.get("redpanda"));
        System.out.println("stress: " + cfg);
        StressReport report = StressRun.run(cfg);
        System.out.println(report.summary());
        if (report.mismatches() != 0) System.exit(1);
    }
}
