package riskengine.writers;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.TreeMap;
import riskengine.host.Topics;

/**
 * <pre>
 *   writers run        [--bootstrap localhost:19092] [--jdbc-url jdbc:postgresql://localhost:5432/risk]
 *   writers load-book  --instruments FILE --book DIR [--trade-date 2024-06-03] [--jdbc-url ...]
 *   writers relay      --broker-url http://localhost:9300 [--listen-port 9200] [--partitions 4] [--name relay-1] [--bootstrap ...] [--jdbc-url ...]
 *                      several may run at once (each locks the outbox rows it sends)
 *                      outbox -> broker, and the broker's callbacks -> the log (fill ingester)
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
        } else if (ok && command.equals("relay") && f.containsKey("broker-url")) {
            String bootstrap = f.getOrDefault("bootstrap", "localhost:19092");
            int partitions = Integer.parseInt(f.getOrDefault("partitions", "4"));
            try (riskengine.host.RedpandaLogs log = new riskengine.host.RedpandaLogs(bootstrap, Topics.ENGINE_EVENTS, partitions, java.time.Duration.ofMillis(100));
                 riskengine.host.ReplayClock clock = new riskengine.host.ReplayClock(bootstrap, Topics.ENGINE_EVENTS);
                 FillIngester ingester = new FillIngester(Integer.parseInt(f.getOrDefault("listen-port", "9200")), jdbcUrl, user, password, log, clock).start();
                 OutboxRelay relay = new OutboxRelay(jdbcUrl, user, password, f.get("broker-url"), log, java.time.Duration.ofSeconds(1), clock)
                         .named(f.getOrDefault("name", System.getenv().getOrDefault("HOSTNAME", "relay")))) {
                Thread main = Thread.currentThread();
                Runtime.getRuntime().addShutdownHook(new Thread(main::interrupt));
                Thread t = relay.start();
                System.out.println("relay: outbox -> " + f.get("broker-url") + "; broker callbacks on :" + ingester.port() + "/broker/callbacks");
                long last = -1;
                try {
                    while (t.isAlive()) {
                        Thread.sleep(10_000);
                        long now = relay.sent() + ingester.fills();
                        if (now != last) {
                            last = now;
                            System.out.println("relay: " + relay.sent() + " orders sent in " + relay.requests() + " requests (" + relay.timeouts() + " timeouts, "
                                    + relay.confirmedByQuery() + " confirmed by query); " + ingester.fills() + " fills and " + ingester.rejects()
                                    + " rejects written to the log, " + ingester.refused() + " callbacks refused, "
                                    + ingester.alreadyBooked() + " fills refused as already booked");
                        }
                    }
                } catch (InterruptedException stop) {
                    System.out.println("relay: shutting down");
                }
            }
        } else {
            System.err.println("usage: writers run [--bootstrap HOST:PORT] [--jdbc-url URL]\n"
                    + "       writers load-book --instruments FILE --book DIR [--trade-date YYYY-MM-DD] [--jdbc-url URL]\n"
                    + "       writers relay --broker-url URL [--listen-port 9200] [--partitions 4] [--bootstrap HOST:PORT] [--jdbc-url URL]");
            System.exit(2);
        }
    }
}
