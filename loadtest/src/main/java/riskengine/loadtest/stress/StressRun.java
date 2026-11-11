package riskengine.loadtest.stress;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.UnaryOperator;
import riskengine.events.AccountControl;
import riskengine.events.AccountEvent;
import riskengine.events.Event;
import riskengine.events.EventLog;
import riskengine.events.Fill;
import riskengine.events.LoggedEvent;
import riskengine.events.OrderClosed;
import riskengine.events.OrderRequest;
import riskengine.events.SquareOffResult;
import riskengine.events.Tick;
import riskengine.host.RedpandaLogs;
import riskengine.host.Topics;
import riskengine.margin.AccountBook;
import riskengine.margin.EngineOutput;
import riskengine.margin.MarginConfig;
import riskengine.margin.MarginEvent;
import riskengine.margin.MarginResult;
import riskengine.margin.OrderClosure;
import riskengine.margin.OrderDecision;
import riskengine.margin.PositionChange;
import riskengine.margin.SquareOffLeg;
import riskengine.margin.EpisodeChange;
import riskengine.margin.ReferenceMarginEngine;
import riskengine.ref.Instrument;
import riskengine.ref.InstrumentType;
import riskengine.shard.Fixtures;
import riskengine.shard.Partitioner;
import riskengine.shard.ShardConfig;
import riskengine.shard.ShardRunner;
import riskengine.shard.ShardState;

/**
 * The M4 concurrency stress harness.
 *
 * <p><b>What runs.</b> Several writer threads append orders, fills, order-closes and duplicates for the
 * same small set of hot accounts, all at once, while another thread writes market ticks to every
 * partition and the shard threads consume live. Nobody coordinates: the order in which records reach a
 * partition is decided by the race, as in production.
 *
 * <p><b>What is checked.</b> Afterwards the harness reads back what each partition actually holds and
 * feeds exactly that to the single-threaded {@link ReferenceMarginEngine}: tick, then every partition's
 * account events up to the next tick, and so on. (Events in different partitions belong to different
 * accounts, so their relative order cannot matter; each account's own order, and where the ticks fall in
 * it, is preserved.) Then it compares:
 * <ol>
 *   <li>every output of every shard, one by one and in order, with the reference's: level changes, order
 *       decisions and their reasons, position changes, reservation releases, square-off legs, episode and
 *       account-status changes;</li>
 *   <li>every account's final positions, cash, reservations, margin, and its whole risk state (level,
 *       status, episode, leg in flight);</li>
 *   <li>each live shard's state hash with the hash from replaying its recorded partition on one thread.</li>
 * </ol>
 * Money is compared to a millionth of a rupee, because the shard updates incrementally and the reference
 * rebuilds from scratch (decision 38). Everything else must be identical.
 */
public final class StressRun {
    private StressRun() {}

    private record Recent(String orderId, String accountId, Instrument instrument, long signedQty) {}

    public static StressReport run(StressConfig cfg) throws InterruptedException {
        return run(cfg, null);
    }

    /** @param tamper applied to the recorded logs before the reference sees them; only the harness's own test uses it */
    static StressReport run(StressConfig cfg, UnaryOperator<List<List<Event>>> tamper) throws InterruptedException {
        int n = cfg.partitions();
        MarginConfig margin = Fixtures.CFG;
        List<Instrument> instruments = Fixtures.instruments();
        List<AccountBook> books = new ArrayList<>(Fixtures.books(cfg.accounts(), cfg.seed()));
        // Hot accounts are spread through the book (so they land on every shard) and are well funded, so
        // their thousands of orders are a mix of accepted and rejected rather than all rejected.
        String[] hot = new String[cfg.hotAccounts()];
        for (int h = 0; h < hot.length; h++) {
            int i = h * (cfg.accounts() / hot.length);
            AccountBook b = books.get(i);
            books.set(i, new AccountBook(b.id(), b.cash().multiply(BigDecimal.valueOf(20)), b.collateral(), b.positions()));
            hot[h] = b.id();
        }
        String[] all = books.stream().map(AccountBook::id).toArray(String[]::new);
        // Hot accounts trade a dozen instruments around the money, so their orders and fills keep
        // meeting on the same positions: partial closes, exact closes and flips.
        List<Instrument> busy = new ArrayList<>(instruments.subList(0, 2));
        for (Instrument i : instruments) {
            if (i.type() != InstrumentType.FUT && i.strike().intValue() >= 23_100 && i.strike().intValue() <= 23_500 && busy.size() < 12) busy.add(i);
        }

        // ------------------------------------------------------------ logs and shards
        EventLog[] logs = new EventLog[n];
        RedpandaLogs redpanda = null;
        if (cfg.redpanda() != null) {
            String topic = "stress-events-" + System.currentTimeMillis();
            Topics.ensure(cfg.redpanda(), topic, n);
            redpanda = new RedpandaLogs(cfg.redpanda(), topic, n, Duration.ofMillis(50));
            for (int p = 0; p < n; p++) logs[p] = redpanda.partition(p);
        } else {
            for (int p = 0; p < n; p++) logs[p] = new ConcurrentEventLog();
        }
        RedpandaLogs broker = redpanda;
        AtomicLongArray appended = new AtomicLongArray(n);
        java.util.concurrent.ConcurrentLinkedQueue<SquareOffLeg> legsToFill = new java.util.concurrent.ConcurrentLinkedQueue<>();

        List<List<AccountBook>> owned = new ArrayList<>();
        for (int p = 0; p < n; p++) owned.add(new ArrayList<>());
        for (AccountBook b : books) owned.get(Partitioner.partition(b.id(), n)).add(b);
        ShardState[] states = new ShardState[n];
        ShardRunner[] runners = new ShardRunner[n];
        List<List<EngineOutput>> shardOut = new ArrayList<>();
        for (int p = 0; p < n; p++) {
            states[p] = new ShardState(p, margin, ShardConfig.defaults(), instruments, owned.get(p));
            List<EngineOutput> sink = new ArrayList<>(); // written only by that shard's thread
            shardOut.add(sink);
            runners[p] = new ShardRunner(states[p], logs[p], (EngineOutput o) -> {
                sink.add(o);
                if (o instanceof SquareOffLeg leg) legsToFill.add(leg); // handed to the harness's little broker
            });
        }

        // ------------------------------------------------------------ the race
        long accountEvents = cfg.events() - (long) cfg.ticks() * n;
        AtomicLong claimed = new AtomicLong();   // account events handed out to writers
        AtomicLong written = new AtomicLong();   // account events appended
        long[] tickTime = {Fixtures.T0};         // read by writers; a stale value only changes a timestamp
        AtomicLong maxLag = new AtomicLong();
        boolean[] writing = {true};

        long start = System.nanoTime();
        List<Thread> shardThreads = new ArrayList<>();
        for (ShardRunner r : runners) shardThreads.add(r.startLive());

        Thread tickWriter = new Thread(() -> {
            SplittableRandom r = new SplittableRandom(cfg.seed() ^ 0x7153L);
            double spot = 23_300, vix = 21;
            for (int k = 1; k <= cfg.ticks(); k++) {
                // Spread the ticks evenly through the account events, whatever speed the writers manage.
                long due = (k - 1) * accountEvents / cfg.ticks();
                while (written.get() < due) Thread.onSpinWait();
                spot *= 1 + (r.nextDouble() - 0.5) * 0.004;
                vix = Math.max(11, Math.min(45, vix + (r.nextDouble() - 0.5) * 0.8));
                long time = Fixtures.T0 + k * 1_000L;
                tickTime[0] = time;
                Tick t = new Tick(k, time, "NIFTY", BigDecimal.valueOf(Math.round(spot * 100), 2), BigDecimal.valueOf(Math.round(vix * 100), 2));
                for (int p = 0; p < n; p++) {
                    if (broker != null) broker.appendAsync(p, t);
                    else logs[p].append(t);
                    appended.incrementAndGet(p);
                }
            }
        }, "tick-writer");

        List<Thread> writers = new ArrayList<>();
        for (int w = 0; w < cfg.writers(); w++) {
            int id = w;
            writers.add(new Thread(() -> {
                SplittableRandom r = new SplittableRandom(cfg.seed() * 31 + id);
                List<Recent> recent = new ArrayList<>();
                Fill lastFill = null;
                long seq = 0;
                while (claimed.getAndIncrement() < accountEvents) {
                    seq++;
                    long time = tickTime[0];
                    boolean isHot = r.nextInt(100) < cfg.hotSharePercent();
                    String account = isHot ? hot[r.nextInt(hot.length)] : all[r.nextInt(all.length)];
                    Instrument instrument = isHot ? busy.get(r.nextInt(busy.size())) : instruments.get(r.nextInt(instruments.size()));
                    AccountEvent e;
                    int kind = r.nextInt(100);
                    if (kind < 45) {
                        String orderId = "O-" + id + "-" + seq;
                        int odd = r.nextInt(100);
                        if (odd < 45 && !recent.isEmpty()) {
                            // Reverse an earlier order: same account and instrument, other side.
                            Recent o = recent.get(r.nextInt(recent.size()));
                            e = new OrderRequest(orderId, o.accountId(), o.instrument().id(), o.signedQty() > 0 ? OrderRequest.Side.SELL : OrderRequest.Side.BUY,
                                    Math.abs(o.signedQty()), OrderRequest.Origin.USER, time);
                            remember(recent, r, new Recent(orderId, o.accountId(), o.instrument(), -o.signedQty()));
                        } else if (odd < 47) {
                            e = new OrderRequest(orderId, account, instrument.id(), OrderRequest.Side.BUY, 30, OrderRequest.Origin.USER, time); // not whole lots
                        } else if (odd < 48) {
                            e = new OrderRequest(orderId, "GHOST-" + id, instrument.id(), OrderRequest.Side.BUY, 25, OrderRequest.Origin.USER, time);
                        } else if (odd < 49) {
                            e = new OrderRequest(orderId, account, "NIFTY-20240613-99900-CE", OrderRequest.Side.BUY, 25, OrderRequest.Origin.USER, time);
                        } else {
                            long qty = 25L * (1 + r.nextInt(6));
                            boolean buy = r.nextBoolean();
                            e = new OrderRequest(orderId, account, instrument.id(), buy ? OrderRequest.Side.BUY : OrderRequest.Side.SELL, qty, OrderRequest.Origin.USER, time);
                            remember(recent, r, new Recent(orderId, account, instrument, buy ? qty : -qty));
                        }
                    } else if (kind < 80) {
                        String fillId = "F-" + id + "-" + seq;
                        if (r.nextInt(100) < 75 && !recent.isEmpty()) {
                            // A fill for one of this writer's orders: part of it or all of it, same direction.
                            Recent o = recent.get(r.nextInt(recent.size()));
                            long lots = 1 + r.nextInt((int) (Math.abs(o.signedQty()) / 25));
                            lastFill = new Fill(fillId, o.orderId(), o.accountId(), o.instrument().id(), Long.signum(o.signedQty()) * 25L * lots,
                                    Fixtures.plausiblePrice(o.instrument(), r), time);
                        } else {
                            lastFill = new Fill(fillId, "X-" + id + "-" + seq, account, instrument.id(), 25L * (1 + r.nextInt(4)) * (r.nextBoolean() ? 1 : -1),
                                    Fixtures.plausiblePrice(instrument, r), time);
                        }
                        e = lastFill;
                    } else if (kind < 92 && !recent.isEmpty()) {
                        Recent o = recent.get(r.nextInt(recent.size()));
                        e = new OrderClosed(o.orderId(), o.accountId(), OrderClosed.Reason.values()[r.nextInt(3)], time);
                    } else if (kind < 97 && lastFill != null) {
                        // A duplicate: the broker calling back twice, or the API writing a request twice.
                        Recent o = recent.isEmpty() ? null : recent.get(r.nextInt(recent.size()));
                        e = o == null || r.nextBoolean() ? lastFill
                                : new OrderRequest(o.orderId(), o.accountId(), o.instrument().id(), o.signedQty() > 0 ? OrderRequest.Side.BUY : OrderRequest.Side.SELL,
                                        Math.abs(o.signedQty()), OrderRequest.Origin.USER, time);
                    } else if (kind < 99) {
                        e = new SquareOffResult(account + ":E1:" + instrument.id() + ":0", account, instrument.id(), SquareOffResult.Status.REJECTED, time);
                    } else {
                        // Risk-ops, now and then: block, unblock (most often, so accounts do not stay blocked), or square off by hand.
                        int what = r.nextInt(8);
                        e = new AccountControl(account, what == 0 ? AccountControl.Action.BLOCK : what == 1 ? AccountControl.Action.SQUARE_OFF : AccountControl.Action.UNBLOCK,
                                "risk-ops:stress", "C-" + id + "-" + seq, time);
                    }
                    int p = Partitioner.partition(e.accountId(), n);
                    if (broker != null) broker.appendAsync(p, (Event) e);
                    else logs[p].append((Event) e);
                    appended.incrementAndGet(p);
                    written.incrementAndGet();
                }
            }, "writer-" + w));
        }

        // A minimal broker: every leg a shard plans comes back as a fill for the whole leg (now and then
        // twice, and now and then as a reject instead), written into the same partitions the writers are
        // racing on. Without it every square-off would hang on its first leg.
        Thread legFiller = new Thread(() -> {
            SplittableRandom r = new SplittableRandom(cfg.seed() ^ 0xB0CEL);
            while (writing[0] || !legsToFill.isEmpty()) {
                SquareOffLeg leg = legsToFill.poll();
                if (leg == null) {
                    Thread.onSpinWait();
                    continue;
                }
                int p = Partitioner.partition(leg.accountId(), n);
                int roll = r.nextInt(100);
                Event e = roll < 4 ? new OrderClosed(leg.legKey(), leg.accountId(), OrderClosed.Reason.REJECTED, leg.logTimeMillis())
                        : new Fill("LF-" + leg.legKey(), leg.legKey(), leg.accountId(), leg.instrumentId(), leg.qty(), leg.mark(), leg.logTimeMillis());
                for (int copies = roll >= 90 ? 2 : 1; copies > 0; copies--) {
                    if (broker != null) broker.appendAsync(p, e);
                    else logs[p].append(e);
                    appended.incrementAndGet(p);
                }
            }
        }, "leg-filler");

        Thread lagSampler = new Thread(() -> {
            while (writing[0]) {
                for (int p = 0; p < n; p++) maxLag.accumulateAndGet(appended.get(p) - runners[p].appliedOffset(), Math::max);
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "lag-sampler");
        lagSampler.setDaemon(true);

        tickWriter.start();
        writers.forEach(Thread::start);
        lagSampler.start();
        legFiller.start();
        for (Thread t : writers) t.join();
        tickWriter.join();
        // Legs chain: a fill makes the shard plan the next leg, which the filler fills, and so on. Wait until
        // every shard has applied everything and no leg is waiting, several times in a row.
        for (int quiet = 0; quiet < 5; ) {
            if (broker != null) broker.flush();
            boolean still = legsToFill.isEmpty();
            for (int p = 0; p < n; p++) {
                if (runners[p].failure() != null) throw new IllegalStateException("shard " + p + " stopped", runners[p].failure());
                still &= runners[p].appliedOffset() >= appended.get(p);
            }
            quiet = still ? quiet + 1 : 0;
            Thread.sleep(still ? 20 : 2);
        }
        writing[0] = false;
        legFiller.join();
        double liveSeconds = (System.nanoTime() - start) / 1e9;
        for (ShardRunner r : runners) r.stop();
        for (Thread t : shardThreads) t.join();

        // ------------------------------------------------------------ what the logs really hold
        List<List<Event>> recorded = new ArrayList<>();
        SortedMap<String, Long> byType = new TreeMap<>();
        long records = 0;
        for (int p = 0; p < n; p++) {
            List<Event> events = new ArrayList<>((int) appended.get(p));
            while (events.size() < appended.get(p)) {
                for (LoggedEvent le : logs[p].read(events.size(), 10_000)) events.add(le.event());
            }
            recorded.add(events);
            records += events.size();
            for (Event e : events) byType.merge(e.getClass().getSimpleName(), 1L, Long::sum);
        }
        for (EventLog log : logs) log.close();
        if (broker != null) broker.close();

        // ------------------------------------------------------------ the reference, on one thread
        long refStart = System.nanoTime();
        List<List<Event>> forReference = tamper == null ? recorded : tamper.apply(recorded);
        ReferenceMarginEngine reference = new ReferenceMarginEngine(margin, instruments, books);
        List<List<EngineOutput>> refOut = new ArrayList<>();
        for (int p = 0; p < n; p++) refOut.add(new ArrayList<>());
        int[] at = new int[n];
        while (true) {
            Tick tick = null;
            for (int p = 0; p < n; p++) {
                List<Event> events = forReference.get(p);
                while (at[p] < events.size() && !(events.get(at[p]) instanceof Tick)) {
                    for (EngineOutput o : reference.apply(events.get(at[p]++))) refOut.get(Partitioner.partition(o.accountId(), n)).add(o);
                }
                if (at[p] < events.size()) {
                    Tick here = (Tick) events.get(at[p]++);
                    if (tick != null && !tick.equals(here)) throw new IllegalStateException("partitions disagree on the tick sequence: " + tick + " vs " + here);
                    tick = here;
                } else if (tick != null) {
                    throw new IllegalStateException("partition " + p + " is missing tick " + tick.tickSeq());
                }
            }
            if (tick == null) break;
            for (EngineOutput o : reference.apply(tick)) refOut.get(Partitioner.partition(o.accountId(), n)).add(o);
        }
        double referenceSeconds = (System.nanoTime() - refStart) / 1e9;

        // ------------------------------------------------------------ compare
        List<String> examples = new ArrayList<>();
        long outputMismatches = 0, outputs = 0, levelChanges = 0, releases = 0, legs = 0, episodes = 0;
        SortedMap<String, Long> decisions = new TreeMap<>();
        for (int p = 0; p < n; p++) {
            List<EngineOutput> expected = refOut.get(p), actual = shardOut.get(p);
            outputs += actual.size();
            int common = Math.min(expected.size(), actual.size());
            for (int i = 0; i < common; i++) {
                String diff = difference(expected.get(i), actual.get(i));
                if (diff != null) {
                    outputMismatches++;
                    if (examples.size() < 8) examples.add("shard " + p + " output " + i + ": " + diff);
                }
            }
            if (expected.size() != actual.size()) {
                outputMismatches += Math.abs(expected.size() - actual.size());
                if (examples.size() < 8) examples.add("shard " + p + " produced " + actual.size() + " outputs, the reference " + expected.size());
            }
            for (EngineOutput o : actual) {
                switch (o) {
                    case OrderDecision d -> decisions.merge(d.reason().name(), 1L, Long::sum);
                    case MarginEvent m -> levelChanges++;
                    case OrderClosure c -> releases += c.released() != 0 ? 1 : 0;
                    case SquareOffLeg l -> legs++;
                    case EpisodeChange e -> episodes += e.state() == EpisodeChange.State.OPENED ? 1 : 0;
                    default -> { }
                }
            }
        }
        long stateMismatches = 0;
        for (AccountBook b : books) {
            ShardState s = states[Partitioner.partition(b.id(), n)];
            String id = b.id();
            String diff = !reference.positions(id).equals(s.positions(id)) ? "positions " + reference.positions(id) + " vs " + s.positions(id)
                    : reference.cash(id).compareTo(s.cash(id)) != 0 ? "cash " + reference.cash(id) + " vs " + s.cash(id)
                    : !reference.riskFingerprint(id).equals(s.riskFingerprint(id)) ? "risk state " + reference.riskFingerprint(id) + " vs " + s.riskFingerprint(id)
                    : !reference.pendingOrders(id).equals(s.pendingOrders(id)) ? "reservations differ"
                    : difference(reference.latest(id), s.latest(id));
            if (diff != null) {
                stateMismatches++;
                if (examples.size() < 8) examples.add("account " + id + " final state: " + diff);
            }
        }
        long replayMismatches = 0, duplicateFills = 0, duplicateOrders = 0;
        for (int p = 0; p < n; p++) {
            ShardState replay = new ShardState(p, margin, ShardConfig.defaults(), instruments, owned.get(p));
            long offset = 0;
            for (Event e : recorded.get(p)) replay.apply(new LoggedEvent(offset++, e));
            if (!replay.stateHash().equals(states[p].stateHash())) {
                replayMismatches++;
                if (examples.size() < 8) examples.add("shard " + p + ": live hash " + states[p].stateHash() + " but replay hash " + replay.stateHash());
            }
            duplicateFills += states[p].duplicateFills();
            duplicateOrders += states[p].duplicateOrders();
        }
        return new StressReport(records, byType, outputs, decisions, levelChanges, releases, episodes, legs, duplicateFills, duplicateOrders,
                outputMismatches, stateMismatches, replayMismatches, maxLag.get(), liveSeconds, referenceSeconds, examples);
    }

    private static void remember(List<Recent> recent, SplittableRandom r, Recent o) {
        if (recent.size() < 64) recent.add(o);
        else recent.set(r.nextInt(64), o);
    }

    // ---------------------------------------------------------------- comparison rules

    private static boolean close(double a, double b) {
        if (Double.isInfinite(a) || Double.isInfinite(b)) return a == b;
        return Math.abs(a - b) <= 1e-6 + 1e-9 * Math.abs(a);
    }

    private static String difference(MarginResult a, MarginResult b) {
        if (a == null || b == null) return a == b ? null : "margin " + a + " vs " + b;
        // The worst scenario NUMBER is not compared: see decision 42 (ties are broken by rounding dust).
        boolean same = close(a.worstScenarioLoss(), b.worstScenarioLoss()) && close(a.shortOptionMinimum(), b.shortOptionMinimum())
                && close(a.netOptionValue(), b.netOptionValue()) && close(a.exposureMargin(), b.exposureMargin()) && close(a.required(), b.required())
                && close(a.futuresMtm(), b.futuresMtm()) && close(a.available(), b.available()) && close(a.utilisation(), b.utilisation());
        return same ? null : "margin " + a + " vs " + b;
    }

    /** Null if the shard's output matches the reference's; otherwise what differs. */
    static String difference(EngineOutput expected, EngineOutput actual) {
        if (expected.getClass() != actual.getClass()) return "reference " + expected + " but shard " + actual;
        boolean same = switch (expected) {
            case MarginEvent e -> {
                MarginEvent a = (MarginEvent) actual;
                yield e.accountId().equals(a.accountId()) && e.tickSeq() == a.tickSeq() && e.from() == a.from() && e.to() == a.to()
                        && difference(e.result(), a.result()) == null && close(e.expectedUtilisation(), a.expectedUtilisation());
            }
            case OrderDecision e -> {
                OrderDecision a = (OrderDecision) actual;
                yield e.orderId().equals(a.orderId()) && e.accountId().equals(a.accountId()) && e.accepted() == a.accepted() && e.reason() == a.reason()
                        && e.lastTickSeq() == a.lastTickSeq() && close(e.requiredAfter(), a.requiredAfter()) && close(e.availableAfter(), a.availableAfter())
                        && close(e.utilisationAfter(), a.utilisationAfter()) && e.mark().compareTo(a.mark()) == 0;
            }
            case PositionChange e -> {
                PositionChange a = (PositionChange) actual;
                yield e.fillId().equals(a.fillId()) && e.accountId().equals(a.accountId()) && e.instrumentId().equals(a.instrumentId())
                        && e.qty() == a.qty() && e.avgPrice().compareTo(a.avgPrice()) == 0 && e.cash().compareTo(a.cash()) == 0
                        && e.orderId().equals(a.orderId()) && e.fillQty() == a.fillQty() && e.cashDelta().compareTo(a.cashDelta()) == 0
                        && e.orderRemaining() == a.orderRemaining();
            }
            case SquareOffLeg e -> {
                SquareOffLeg a = (SquareOffLeg) actual;
                yield e.legKey().equals(a.legKey()) && e.qty() == a.qty() && e.mark().compareTo(a.mark()) == 0 && e.tickSeq() == a.tickSeq()
                        && close(e.expectedRelease(), a.expectedRelease());
            }
            // Closures, episode changes and status changes carry no computed money: they must be equal.
            default -> expected.equals(actual);
        };
        return same ? null : "reference " + expected + " but shard " + actual;
    }
}
