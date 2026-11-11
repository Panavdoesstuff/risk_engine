package riskengine.report;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import riskengine.events.Fill;
import riskengine.events.InMemoryEventLog;
import riskengine.events.LoggedEvent;
import riskengine.events.Tick;
import riskengine.margin.AccountBook;
import riskengine.margin.AccountRisk;
import riskengine.margin.AccountStatusChange;
import riskengine.margin.EngineOutput;
import riskengine.margin.EpisodeChange;
import riskengine.margin.MarginConfig;
import riskengine.margin.MarginEvent;
import riskengine.margin.MarginLevel;
import riskengine.margin.MarginResult;
import riskengine.margin.SquareOffLeg;
import riskengine.ref.Instrument;
import riskengine.shard.Partitioner;
import riskengine.shard.ShardConfig;
import riskengine.shard.ShardState;

/**
 * Replays a day of ticks against a book with the production shard code, one {@link ShardState} per
 * partition, and records what happened to every account and the book minute by minute.
 *
 * <p><b>The broker.</b> There is no network here. Every square-off leg is filled in full at the mark the
 * engine planned it at, stamped with the same tick's log time, and written to the shard's log straight
 * after the event that produced it, so it is applied before the next tick. That stands for a broker that
 * fills a market order within seconds when ticks are a minute apart. It is the same broker the stress
 * harness uses, without rejects or duplicates. The shard re-plans after each fill, so an account's legs
 * all go out within the minute that started its square-off.
 *
 * <p>Deterministic: same ticks, same book, same config give the same counts and the same state hashes.
 * Only the timings differ between runs.
 */
public final class CrashDay {
    private CrashDay() {}

    /** One minute of the book: what the market did, where the accounts stood after it, how long it took. */
    public static final class Minute {
        public long tickSeq, logTime;
        public BigDecimal spot, vix;
        /** Accounts at each level / with each status after the tick and its fills. */
        public final long[] levels = new long[MarginLevel.values().length];
        public final long[] statuses = new long[AccountRisk.Status.values().length];
        /** Accounts reaching WARNING / MARGIN_CALL / SQUARE_OFF, and squared off, for the first time this minute. */
        public long warned, marginCalled, squareOffLevel, squaredOff;
        public long episodesOpened, episodesResolved, episodesExhausted, legs, lotsClosed;
        /** Sum over accounts, rupees. */
        public double required, available;
        /** Accounts whose liquidation value (available + net option value) is below zero, and by how much in total. */
        public long negativeEquity;
        public double negativeEquityRupees;
        /** Slowest shard this minute: applying the tick, and applying the fills that followed it. */
        public long tickNanos, fillNanos;

        void add(Minute o) {
            tickSeq = o.tickSeq;
            logTime = o.logTime;
            spot = o.spot;
            vix = o.vix;
            for (int i = 0; i < levels.length; i++) levels[i] += o.levels[i];
            for (int i = 0; i < statuses.length; i++) statuses[i] += o.statuses[i];
            warned += o.warned;
            marginCalled += o.marginCalled;
            squareOffLevel += o.squareOffLevel;
            squaredOff += o.squaredOff;
            episodesOpened += o.episodesOpened;
            episodesResolved += o.episodesResolved;
            episodesExhausted += o.episodesExhausted;
            legs += o.legs;
            lotsClosed += o.lotsClosed;
            required += o.required;
            available += o.available;
            negativeEquity += o.negativeEquity;
            negativeEquityRupees += o.negativeEquityRupees;
            tickNanos = Math.max(tickNanos, o.tickNanos);
            fillNanos = Math.max(fillNanos, o.fillNanos);
        }
    }

    /** What happened to one account during the day. Times are log times (epoch millis); -1 = never. */
    public static final class Account {
        public final String id;
        public long firstWarning = -1, firstMarginCall = -1, firstSquareOffLevel = -1;
        public long squareOffStarted = -1, squareOffEnded = -1;
        public int episodes, legs;
        public long lotsClosed;
        public String lastEpisodeEnd = "";
        public boolean blocked, flatAtClose;
        public double peakUtilisation;
        public long peakUtilisationTime = -1;
        /** Largest (required - available), rupees: how far short of margin it got. */
        public double maxShortfall;
        public long maxShortfallTime = -1;
        /** Lowest liquidation value (available + net option value) seen, and at the first and last tick. */
        public double minEquity = Double.MAX_VALUE, firstEquity = Double.NaN, closeEquity = Double.NaN;
        /** Equity at the 3 June close: cash plus options at the settlement prices they were opened at (futures start at zero profit). */
        public double startEquity;
        public long minEquityTime = -1;
        public final List<String> timeline = new ArrayList<>();

        Account(String id) {
            this.id = id;
        }

        void note(long logTime, String what) {
            timeline.add(logTime + "|" + what);
        }
    }

    public record ShardResult(int shard, String stateHash, long offsets, List<Minute> minutes, SortedMap<String, Account> accounts) {}

    public record Result(List<Minute> minutes, SortedMap<String, Account> accounts, List<ShardResult> shards) {
        public List<String> stateHashes() {
            return shards.stream().map(ShardResult::stateHash).toList();
        }
    }

    /**
     * @param ticksByPartition each partition's ticks, as the replayer wrote them (the same ticks, every partition)
     */
    public static Result run(MarginConfig cfg, ShardConfig shardCfg, Collection<Instrument> instruments, List<AccountBook> books,
                             List<List<Tick>> ticksByPartition) throws Exception {
        int n = ticksByPartition.size();
        List<List<AccountBook>> owned = new ArrayList<>();
        for (int p = 0; p < n; p++) owned.add(new ArrayList<>());
        for (AccountBook b : books) owned.get(Partitioner.partition(b.id(), n)).add(b);

        ExecutorService pool = Executors.newFixedThreadPool(n, r -> {
            Thread t = new Thread(r, "crash-day-shard");
            t.setDaemon(true);
            return t;
        });
        try {
            List<Future<ShardResult>> futures = new ArrayList<>();
            for (int p = 0; p < n; p++) {
                int shard = p;
                futures.add(pool.submit(() -> runShard(shard, cfg, shardCfg, instruments, owned.get(shard), ticksByPartition.get(shard))));
            }
            List<ShardResult> shards = new ArrayList<>();
            for (Future<ShardResult> f : futures) shards.add(f.get());

            int minutes = shards.get(0).minutes().size();
            List<Minute> total = new ArrayList<>();
            for (int i = 0; i < minutes; i++) {
                Minute m = new Minute();
                for (ShardResult s : shards) {
                    if (s.minutes().get(i).tickSeq != shards.get(0).minutes().get(i).tickSeq) throw new IllegalStateException("partitions disagree on ticks");
                    m.add(s.minutes().get(i));
                }
                total.add(m);
            }
            SortedMap<String, Account> accounts = new TreeMap<>();
            shards.forEach(s -> accounts.putAll(s.accounts()));
            return new Result(total, accounts, shards);
        } finally {
            pool.shutdownNow();
        }
    }

    private static ShardResult runShard(int shard, MarginConfig cfg, ShardConfig shardCfg, Collection<Instrument> instruments,
                                        List<AccountBook> books, List<Tick> ticks) {
        ShardState s = new ShardState(shard, cfg, shardCfg, instruments, books);
        InMemoryEventLog log = new InMemoryEventLog();
        SortedMap<String, Account> accounts = new TreeMap<>();
        Map<String, Instrument> byId = new TreeMap<>();
        for (Instrument i : instruments) byId.put(i.id(), i);
        for (AccountBook b : books) {
            Account a = new Account(b.id());
            double equity = b.cash().doubleValue() + b.collateral().doubleValue();
            for (var e : b.positions().entrySet()) {
                if (byId.get(e.getKey()).type() != riskengine.ref.InstrumentType.FUT) equity += e.getValue().qty() * e.getValue().avgPrice().doubleValue();
            }
            a.startEquity = equity;
            accounts.put(b.id(), a);
        }
        List<Minute> minutes = new ArrayList<>();

        for (Tick tick : ticks) {
            Minute m = new Minute();
            m.tickSeq = tick.tickSeq();
            m.logTime = tick.logTimeMillis();
            m.spot = tick.spot();
            m.vix = tick.vix();

            long offset = log.append(tick);
            long t0 = System.nanoTime();
            List<EngineOutput> out = s.apply(new LoggedEvent(offset, tick));
            m.tickNanos = System.nanoTime() - t0;

            // The broker: each leg is filled at its mark before the next tick. A fill can produce the next leg.
            ArrayList<EngineOutput> pending = new ArrayList<>(out);
            long fillNanos = 0;
            for (int i = 0; i < pending.size(); i++) {
                EngineOutput o = pending.get(i);
                record(o, accounts, m, byId);
                if (o instanceof SquareOffLeg leg) {
                    Fill fill = new Fill("F:" + leg.legKey(), leg.legKey(), leg.accountId(), leg.instrumentId(), leg.qty(), leg.mark(), tick.logTimeMillis());
                    long at = log.append(fill);
                    long f0 = System.nanoTime();
                    pending.addAll(s.apply(new LoggedEvent(at, fill)));
                    fillNanos += System.nanoTime() - f0;
                }
            }
            m.fillNanos = fillNanos;

            // Where every account stands now (outside the timed part).
            for (String id : s.accountIds()) {
                Account a = accounts.get(id);
                m.levels[s.level(id).ordinal()]++;
                m.statuses[s.status(id).ordinal()]++;
                MarginResult r = s.latest(id);
                if (r == null) continue;
                m.required += r.required();
                m.available += r.available();
                double equity = r.available() + r.netOptionValue();
                if (equity < 0) {
                    m.negativeEquity++;
                    m.negativeEquityRupees += equity;
                }
                if (Double.isNaN(a.firstEquity)) a.firstEquity = equity;
                a.closeEquity = equity;
                if (equity < a.minEquity) {
                    a.minEquity = equity;
                    a.minEquityTime = tick.logTimeMillis();
                }
                double u = r.utilisation();
                if (u > a.peakUtilisation) {
                    a.peakUtilisation = u;
                    a.peakUtilisationTime = tick.logTimeMillis();
                }
                double shortfall = r.required() - r.available();
                if (shortfall > a.maxShortfall) {
                    a.maxShortfall = shortfall;
                    a.maxShortfallTime = tick.logTimeMillis();
                }
            }
            minutes.add(m);
        }
        for (Account a : accounts.values()) a.flatAtClose = s.positions(a.id).values().stream().allMatch(p -> p.qty() == 0);
        return new ShardResult(shard, s.stateHash(), log.endOffset(), minutes, accounts);
    }

    private static void record(EngineOutput o, Map<String, Account> accounts, Minute m, Map<String, Instrument> instruments) {
        Account a = accounts.get(o.accountId());
        switch (o) {
            case MarginEvent e -> {
                a.note(e.logTimeMillis(), e.from() + "->" + e.to() + " at " + pct(e.result().utilisation()));
                if (e.to().compareTo(e.from()) <= 0) return;
                if (e.to().compareTo(MarginLevel.WARNING) >= 0 && a.firstWarning < 0) {
                    a.firstWarning = e.logTimeMillis();
                    m.warned++;
                }
                if (e.to().compareTo(MarginLevel.MARGIN_CALL) >= 0 && a.firstMarginCall < 0) {
                    a.firstMarginCall = e.logTimeMillis();
                    m.marginCalled++;
                }
                if (e.to() == MarginLevel.SQUARE_OFF && a.firstSquareOffLevel < 0) {
                    a.firstSquareOffLevel = e.logTimeMillis();
                    m.squareOffLevel++;
                }
            }
            case EpisodeChange e -> {
                a.note(e.logTimeMillis(), "episode " + e.episodeId() + " " + e.state() + (e.state() == EpisodeChange.State.OPENED ? "" : " after " + e.legs() + " legs"));
                switch (e.state()) {
                    case OPENED -> {
                        a.episodes++;
                        if (a.squareOffStarted < 0) {
                            a.squareOffStarted = e.logTimeMillis();
                            m.squaredOff++;
                        }
                        m.episodesOpened++;
                    }
                    case RESOLVED -> {
                        a.squareOffEnded = e.logTimeMillis();
                        a.lastEpisodeEnd = "RESOLVED";
                        m.episodesResolved++;
                    }
                    case EXHAUSTED -> {
                        a.squareOffEnded = e.logTimeMillis();
                        a.lastEpisodeEnd = "EXHAUSTED";
                        m.episodesExhausted++;
                    }
                }
            }
            case SquareOffLeg l -> {
                a.legs++;
                long lot = instruments.get(l.instrumentId()).lotSize();
                a.lotsClosed += Math.abs(l.qty()) / lot;
                m.legs++;
                m.lotsClosed += Math.abs(l.qty()) / lot;
                a.note(l.logTimeMillis(), "leg " + (l.qty() > 0 ? "buy " : "sell ") + Math.abs(l.qty()) + " " + l.instrumentId() + " at " + l.mark().toPlainString());
            }
            case AccountStatusChange c -> {
                if (c.to() == AccountRisk.Status.BLOCKED) a.blocked = true;
                if (c.from() == AccountRisk.Status.BLOCKED) a.blocked = false;
            }
            default -> { }
        }
    }

    static String pct(double u) {
        return Double.isInfinite(u) ? "inf" : String.format(java.util.Locale.ROOT, "%.1f%%", u * 100);
    }
}
