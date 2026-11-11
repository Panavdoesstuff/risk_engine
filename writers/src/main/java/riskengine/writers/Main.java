package riskengine.writers;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.TreeMap;
import riskengine.host.Topics;

/**
 * <pre>
 *   writers run        [--bootstrap localhost:19092] [--jdbc-url jdbc:postgresql://localhost:5432/risk]
 *   writers load-book  --instruments FILE --book DIR [--trade-date 2024-06-03] [--jdbc-url ...]
 * </pre>
 * Database user and password come from DB_USER / DB_PASSWORD (default risk / risk, the compose values).
 */
public final class Main {
    private Main() {}

    public static void main(String[] args) throws Exception {
        TreeMap<String, String> f = new TreeMap<>();
        boolean ok = args.length >= 1 && args.length % 2 == 1;
        for (int i = 1; ok && i + 1 < args.length; i += 2) {
            ok = args[i].startsWith("--");
            f.put(args[i].substring(Math.min(2, args[i].length())), args[i + 1]);
        }
        String command = args.length == 0 ? "" : args[0];
        String jdbcUrl = f.getOrDefault("jdbc-url", "jdbc:postgresql://localhost:5432/risk");
        String user = System.getenv().getOrDefault("DB_USER", "risk");
        String password = System.getenv().getOrDefault("DB_PASSWORD", "risk");
        if (ok && command.equals("load-book") && f.containsKey("instruments") && f.containsKey("book")) {
            BookLoader.Loaded n = BookLoader.load(jdbcUrl, user, password, Path.of(f.get("instruments")), Path.of(f.get("book")),
                    LocalDate.parse(f.getOrDefault("trade-date", "2024-06-03")));
            System.out.println("loaded " + n.instruments() + " instruments, " + n.accounts() + " accounts, " + n.positions() + " positions (existing rows left alone)");
        } else if (ok && command.equals("run")) {
            String bootstrap = f.getOrDefault("bootstrap", "localhost:19092");
            try (EngineOutWriter writer = new EngineOutWriter(bootstrap, Topics.ENGINE_OUT, jdbcUrl, user, password)) {
                Thread main = Thread.currentThread();
                Runtime.getRuntime().addShutdownHook(new Thread(main::interrupt));
                Thread t = writer.start();
                System.out.println("writers: engine-out at " + bootstrap + " -> " + jdbcUrl);
                long last = 0;
                try {
                    while (t.isAlive()) {
                        Thread.sleep(10_000);
                        if (writer.records() != last) {
                            last = writer.records();
                            System.out.println("writers: " + last + " records in " + writer.batches() + " batches, " + writer.skipped() + " skipped, "
                                    + writer.failedBatches() + " failed batches retried");
                        }
                    }
                } catch (InterruptedException stop) {
                    System.out.println("writers: shutting down");
                }
            }
        } else {
            System.err.println("usage: writers run [--bootstrap HOST:PORT] [--jdbc-url URL]\n"
                    + "       writers load-book --instruments FILE --book DIR [--trade-date YYYY-MM-DD] [--jdbc-url URL]");
            System.exit(2);
        }
    }
}
