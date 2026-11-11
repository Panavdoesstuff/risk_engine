package riskengine.replayer.ticks;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.Locale;
import java.util.SplittableRandom;
import java.util.concurrent.locks.LockSupport;
import riskengine.events.Tick;
import riskengine.host.RedpandaLogs;
import riskengine.host.ReplayClock;
import riskengine.host.Topics;

/**
 * SYNTHETIC live ticks at a fixed rate, for load and for watching the dashboard. It carries on from the
 * latest tick already in the log, so tick_seq keeps increasing and the shards accept it: next seq, the
 * same spot and VIX to start from, log time advancing by 1/rate seconds per tick. Spot takes a small
 * random walk (about 0.02% a tick) and VIX a smaller one. Prices are made up; do not quote them.
 */
public final class SyntheticTicks {
    private SyntheticTicks() {}

    public static void run(String bootstrap, int partitions, String underlying, double rate, int seconds, long seed) {
        if (!(rate > 0) || seconds < 1) throw new IllegalArgumentException("need --rate > 0 and --seconds >= 1");
        Tick last = ReplayClock.latestTick(bootstrap, Topics.ENGINE_EVENTS);
        if (last == null) throw new IllegalArgumentException("no tick in " + Topics.ENGINE_EVENTS + " yet: write the replay's ticks first, then synth carries on from them");
        SplittableRandom r = new SplittableRandom(seed);
        double spot = last.spot().doubleValue(), vix = last.vix().doubleValue();
        long seq = last.tickSeq(), time = last.logTimeMillis();
        long stepMillis = Math.max(1, Math.round(1000 / rate));
        long total = (long) Math.ceil(rate * seconds);
        try (RedpandaLogs logs = new RedpandaLogs(bootstrap, Topics.ENGINE_EVENTS, partitions, Duration.ofMillis(100))) {
            long start = System.nanoTime();
            for (long k = 0; k < total; k++) {
                long due = start + (long) (k * 1e9 / rate);
                while (System.nanoTime() < due) LockSupport.parkNanos(due - System.nanoTime());
                spot *= 1 + (r.nextDouble() - 0.5) * 0.0004;
                vix = Math.max(10, Math.min(60, vix + (r.nextDouble() - 0.5) * 0.04));
                Tick t = new Tick(++seq, time += stepMillis, underlying, BigDecimal.valueOf(spot).setScale(2, RoundingMode.HALF_EVEN),
                        BigDecimal.valueOf(vix).setScale(2, RoundingMode.HALF_EVEN));
                for (int p = 0; p < partitions; p++) logs.appendAsync(p, t);
            }
            logs.flush();
            double took = (System.nanoTime() - start) / 1e9;
            System.out.printf(Locale.ROOT, "wrote %,d SYNTHETIC ticks (tick_seq %d..%d) to each of %d partitions in %.1f s: %.1f ticks/s%n",
                    total, last.tickSeq() + 1, seq, partitions, took, total / took);
        }
    }
}
