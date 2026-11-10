package riskengine.margin;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Consumer;
import riskengine.events.Event;
import riskengine.events.EventLog;
import riskengine.events.LoggedEvent;
import riskengine.events.Tick;
import riskengine.ref.Instrument;

/**
 * Single-threaded reference engine: reads ticks from an {@link EventLog} and recomputes every affected
 * account from scratch on every tick. Slow on purpose and easy to trust. M3's incremental shard is
 * checked against it, and M4's concurrency harness compares against it.
 *
 * <p>Determinism: accounts, positions and instruments are iterated in sorted order; time comes from the
 * tick; ticks must arrive with strictly increasing tickSeq.
 */
public final class ReferenceMarginEngine {

    /** Start-of-day state of one account. Positions: instrument id to signed qty (units, not lots). */
    public record AccountBook(String id, BigDecimal cash, BigDecimal collateral, SortedMap<String, Long> positions) {
        public AccountBook {
            Objects.requireNonNull(id);
            positions = new TreeMap<>(positions);
        }
    }

    /** Emitted when an account's level changes. Maps onto a margin_events row. */
    public record MarginEvent(String accountId, long tickSeq, long logTimeMillis, MarginResult result,
                              MarginLevel from, MarginLevel to) {
        public String action() {
            return to.action();
        }
    }

    private final MarginConfig cfg;
    private final SortedMap<String, Instrument> instruments = new TreeMap<>();
    private final SortedMap<String, AccountBook> accounts = new TreeMap<>();
    private final Map<String, MarginStatusTracker> trackers = new TreeMap<>();
    private final Map<String, MarginResult> latest = new TreeMap<>();
    private final Map<String, ScenarioGrid> grids = new TreeMap<>();
    private long lastTickSeq = Long.MIN_VALUE;

    public ReferenceMarginEngine(MarginConfig cfg, Collection<Instrument> instruments, Collection<AccountBook> books) {
        this.cfg = cfg;
        for (Instrument i : instruments) this.instruments.put(i.id(), i);
        for (AccountBook b : books) {
            for (String id : b.positions().keySet()) {
                Instrument i = this.instruments.get(id);
                if (i == null) throw new IllegalArgumentException(b.id() + " holds unknown instrument " + id);
                cfg.params(i.underlying()); // fails for unconfigured underlyings (Nifty only until M5)
            }
            if (accounts.put(b.id(), b) != null) throw new IllegalArgumentException("duplicate account " + b.id());
            trackers.put(b.id(), new MarginStatusTracker(cfg.thresholds()));
        }
        cfg.underlyings().forEach((u, p) -> grids.put(u, new ScenarioGrid(p)));
    }

    /** Applies one tick; returns level changes in account-id order. */
    public List<MarginEvent> onTick(Tick t) {
        if (t.tickSeq() <= lastTickSeq) {
            throw new IllegalStateException("tick " + t.tickSeq() + " after " + lastTickSeq + ": ticks must be strictly increasing");
        }
        lastTickSeq = t.tickSeq();
        UnderlyingParams p = cfg.params(t.underlying());
        MarketState m = new MarketState(t.underlying(), t.spot().doubleValue(), t.vix().doubleValue() * p.volScale(), t.logTimeMillis());
        ScenarioGrid grid = grids.get(t.underlying());

        Map<String, RiskArray> arrays = new TreeMap<>();
        List<MarginEvent> events = new ArrayList<>();
        for (AccountBook b : accounts.values()) {
            AccountVector v = new AccountVector(t.underlying());
            boolean holds = false;
            for (var pos : b.positions().entrySet()) {
                Instrument i = instruments.get(pos.getKey());
                if (!i.underlying().equals(t.underlying()) || pos.getValue() == 0) continue;
                holds = true;
                v.add(pos.getValue(), arrays.computeIfAbsent(i.id(), id -> RiskArray.compute(i, m, grid, cfg.riskFreeRate())));
            }
            if (!holds) continue;
            MarginResult r = MarginCalculator.compute(v, cfg, b.cash().doubleValue(), b.collateral().doubleValue());
            latest.put(b.id(), r);
            MarginStatusTracker tracker = trackers.get(b.id());
            MarginLevel before = tracker.level(), after = tracker.onReading(r.utilisation());
            if (after != before) events.add(new MarginEvent(b.id(), t.tickSeq(), t.logTimeMillis(), r, before, after));
        }
        return events;
    }

    /** Applies every event from {@code fromOffset} to the end of the log; returns the next offset to read. */
    public long run(EventLog log, long fromOffset, Consumer<MarginEvent> sink) {
        long next = fromOffset;
        while (true) {
            List<LoggedEvent> batch = log.read(next, 1_000);
            if (batch.isEmpty()) return next;
            for (LoggedEvent le : batch) {
                Event e = le.event();
                switch (e) {
                    case Tick t -> onTick(t).forEach(sink);
                }
                next = le.offset() + 1;
            }
        }
    }

    public MarginResult latest(String accountId) {
        return latest.get(accountId);
    }

    public MarginLevel level(String accountId) {
        return trackers.get(accountId).level();
    }
}
