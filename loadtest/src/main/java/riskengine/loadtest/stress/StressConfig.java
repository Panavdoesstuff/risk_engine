package riskengine.loadtest.stress;

/**
 * @param events          log records to write in total, counting a tick once per partition
 * @param ticks           market ticks (each is written to every partition)
 * @param accounts        accounts in the book
 * @param hotAccounts     how many of them are "hot"
 * @param hotSharePercent share of account events that go to the hot accounts
 * @param partitions      shards
 * @param writers         threads writing account events at the same time (all of them write to the hot accounts)
 * @param redpanda        bootstrap address to run through a real broker, or null for in-memory logs
 */
public record StressConfig(long events, int ticks, int accounts, int hotAccounts, int hotSharePercent, int partitions,
                           int writers, long seed, String redpanda) {
    public StressConfig {
        if (events < (long) ticks * partitions) throw new IllegalArgumentException("events must cover the ticks");
        if (hotAccounts < 1 || hotAccounts > accounts) throw new IllegalArgumentException("hotAccounts must be 1.." + accounts);
    }

    /** The M4 gate: one million records, 80% of account events on 20 of 2,000 accounts, 8 writers, 4 shards. */
    public static StressConfig gate() {
        return new StressConfig(1_000_000, 5_000, 2_000, 20, 80, 4, 8, 20240604L, null);
    }

    public StressConfig withEvents(long n, int t) {
        return new StressConfig(n, t, accounts, hotAccounts, hotSharePercent, partitions, writers, seed, redpanda);
    }

    public StressConfig withRedpanda(String bootstrap) {
        return new StressConfig(events, ticks, accounts, hotAccounts, hotSharePercent, partitions, writers, seed, bootstrap);
    }

    public StressConfig withSeed(long s) {
        return new StressConfig(events, ticks, accounts, hotAccounts, hotSharePercent, partitions, writers, s, redpanda);
    }
}
