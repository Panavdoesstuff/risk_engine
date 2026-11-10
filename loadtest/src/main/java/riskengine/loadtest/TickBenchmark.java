package riskengine.loadtest;

import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import riskengine.events.LoggedEvent;
import riskengine.margin.AccountBook;
import riskengine.margin.MarginConfig;
import riskengine.margin.ReferenceMarginEngine;
import riskengine.ref.Instrument;
import riskengine.shard.ShardConfig;
import riskengine.shard.ShardState;

/**
 * Time to process ONE tick for a whole book, three ways:
 *
 * <ul>
 *   <li>{@code naive}: every account reprices every one of its positions.</li>
 *   <li>{@code fullRecompute}: each instrument is priced once, then every account's vector is rebuilt
 *       from its positions (ReferenceMarginEngine).</li>
 *   <li>{@code incremental}: each instrument is priced once, then each holder's vector is adjusted by
 *       qty x (new - old) (ShardState, including its rolling rebuild: 1% of accounts rebuilt and checked per tick).</li>
 * </ul>
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 4, time = 2)
@Measurement(iterations = 6, time = 3)
@Fork(1)
public class TickBenchmark {
    @Param({"10000"})
    public int accounts;

    @Param({"5"})
    public int positionsPerAccount;

    private NaiveEngine naive;
    private ReferenceMarginEngine full;
    private ShardState shard;
    private long seq;
    private long offset;

    @Setup(Level.Trial)
    public void setup() {
        MarginConfig cfg = MarginConfig.defaults();
        List<Instrument> ins = BenchBook.instruments();
        List<AccountBook> books = BenchBook.books(ins, accounts, positionsPerAccount, 42);
        naive = new NaiveEngine(cfg, ins, books);
        full = new ReferenceMarginEngine(cfg, ins, books);
        shard = new ShardState(0, cfg, ShardConfig.defaults(), ins, books);
        // First tick builds every vector from scratch in all three; keep it out of the measurement.
        seq = 1;
        double naiveTotal = naive.onTick(BenchBook.tick(seq));
        full.onTick(BenchBook.tick(seq));
        shard.apply(new LoggedEvent(offset++, BenchBook.tick(seq)));
        // The comparison only means something if all three compute the same thing.
        double fullTotal = 0, shardTotal = 0;
        for (AccountBook b : books) {
            fullTotal += full.latest(b.id()).required();
            shardTotal += shard.latest(b.id()).required();
        }
        if (Math.abs(naiveTotal - fullTotal) > 1e-6 * fullTotal || Math.abs(shardTotal - fullTotal) > 1e-6 * fullTotal) {
            throw new IllegalStateException("engines disagree: naive " + naiveTotal + ", full " + fullTotal + ", incremental " + shardTotal);
        }
    }

    @Benchmark
    public double naive() {
        return naive.onTick(BenchBook.tick(++seq));
    }

    @Benchmark
    public Object fullRecompute() {
        return full.onTick(BenchBook.tick(++seq));
    }

    @Benchmark
    public Object incremental() {
        return shard.apply(new LoggedEvent(offset++, BenchBook.tick(++seq)));
    }
}
