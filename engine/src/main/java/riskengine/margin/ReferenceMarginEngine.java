package riskengine.margin;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import riskengine.events.Event;
import riskengine.events.EventLog;
import riskengine.events.Fill;
import riskengine.events.LoggedEvent;
import riskengine.events.OrderRequest;
import riskengine.events.SquareOffResult;
import riskengine.events.Tick;
import riskengine.ref.Instrument;

/**
 * Single-threaded reference engine: rebuilds every affected account from its positions on every tick.
 * Slow on purpose and easy to trust. The incremental shard (riskengine.shard) must produce the same
 * results, and M4's concurrency harness compares against it.
 *
 * <p>Rules shared with the shard:
 * <ul>
 *   <li>Ticks must arrive with strictly increasing tickSeq.</li>
 *   <li>Only ticks feed the level tracker ("N consecutive ticks"). A fill updates positions, cash and the
 *       latest margin figure, but is not a reading.</li>
 *   <li>Duplicate fills (same fillId) are ignored.</li>
 *   <li>An account is only evaluated once every underlying it holds has had a tick.</li>
 *   <li>A USER OrderRequest gets the pre-trade margin check ({@link PreTradeCheck}); an accepted order
 *       reserves margin until it fills. Repeats of an order id are ignored.</li>
 *   <li>SquareOffResult, and orders the engine itself would originate (SQUARE_OFF), do nothing until M5.</li>
 * </ul>
 */
public final class ReferenceMarginEngine {

    private static final class Account {
        final String id;
        BigDecimal cash;
        final BigDecimal collateral;
        final SortedMap<String, Position> positions;
        final SortedMap<String, PendingOrder> pending = new TreeMap<>();
        final MarginStatusTracker tracker;
        MarginResult latest;

        Account(AccountBook b, Thresholds t) {
            this.id = b.id();
            this.cash = b.cash();
            this.collateral = b.collateral();
            this.positions = new TreeMap<>(b.positions());
            this.tracker = new MarginStatusTracker(t);
        }
    }

    private final MarginConfig cfg;
    private final SortedMap<String, Instrument> instruments = new TreeMap<>();
    private final SortedMap<String, Account> accounts = new TreeMap<>();
    private final SortedMap<String, ScenarioGrid> grids = new TreeMap<>();
    private final SortedMap<String, MarketState> markets = new TreeMap<>();
    private final SortedSet<String> seenFillIds = new TreeSet<>();
    private final SortedSet<String> seenOrderIds = new TreeSet<>();
    private long lastTickSeq = Long.MIN_VALUE;

    public ReferenceMarginEngine(MarginConfig cfg, Collection<Instrument> instruments, Collection<AccountBook> books) {
        this.cfg = cfg;
        for (Instrument i : instruments) this.instruments.put(i.id(), i);
        for (AccountBook b : books) {
            for (String id : b.positions().keySet()) instrument(id, b.id());
            if (accounts.put(b.id(), new Account(b, cfg.thresholds())) != null) {
                throw new IllegalArgumentException("duplicate account " + b.id());
            }
        }
        cfg.underlyings().forEach((u, p) -> grids.put(u, new ScenarioGrid(p)));
    }

    private Instrument instrument(String id, String accountId) {
        Instrument i = instruments.get(id);
        if (i == null) throw new IllegalArgumentException(accountId + " refers to unknown instrument " + id);
        cfg.params(i.underlying()); // fails for unconfigured underlyings (Nifty only until M5)
        return i;
    }

    /** Applies one event; returns level changes in account-id order (ticks) or the order's decision. */
    public List<EngineOutput> apply(Event e) {
        return switch (e) {
            case Tick t -> List.copyOf(onTick(t));
            case Fill f -> {
                onFill(f);
                yield List.of();
            }
            case OrderRequest o -> onOrder(o);
            case SquareOffResult s -> List.of();
        };
    }

    public List<EngineOutput> onOrder(OrderRequest o) {
        if (o.origin() != OrderRequest.Origin.USER) return List.of(); // the engine's own square-off orders: M5
        if (!seenOrderIds.add(o.orderId())) return List.of();
        Account a = accounts.get(o.accountId());
        if (a == null) return List.of(OrderDecision.rejected(o, OrderDecision.Reason.UNKNOWN_ACCOUNT, lastTickSeq));
        Instrument i = instruments.get(o.instrumentId());
        OrderDecision.Reason bad = PreTradeCheck.structuralReject(i, cfg, o);
        if (bad != null) return List.of(OrderDecision.rejected(o, bad, lastTickSeq));
        long qty = PreTradeCheck.signedQty(o);
        SortedMap<String, RiskArray> arrays = new TreeMap<>();
        MarginResult before = whatIf(a, null, 0, arrays);
        MarginResult after = markets.containsKey(i.underlying()) ? whatIf(a, i.id(), qty, arrays) : null;
        if (before == null || after == null) return List.of(OrderDecision.rejected(o, OrderDecision.Reason.NO_MARKET, lastTickSeq));
        boolean ok = PreTradeCheck.passes(before, after, cfg.thresholds());
        if (ok) a.pending.put(o.orderId(), new PendingOrder(i.id(), qty));
        return List.of(OrderDecision.checked(o, ok, lastTickSeq, after));
    }

    /** Margin if every reserved order (plus one extra, if given) filled now at the mark. Null if unpriceable. */
    private MarginResult whatIf(Account a, String extraInstrumentId, long extraQty, SortedMap<String, RiskArray> arrays) {
        SortedMap<String, Position> positions = new TreeMap<>(a.positions);
        BigDecimal cash = a.cash;
        for (var d : PreTradeCheck.netDeltas(a.pending.values(), extraInstrumentId, extraQty).entrySet()) {
            if (d.getValue() == 0) continue;
            Instrument i = instruments.get(d.getKey());
            MarketState m = markets.get(i.underlying());
            if (m == null) return null;
            RiskArray array = arrays.computeIfAbsent(i.id(), id -> RiskArray.compute(i, m, grids.get(i.underlying()), cfg.riskFreeRate()));
            PositionAccounting.Outcome o = PositionAccounting.apply(i.type(), positions.getOrDefault(i.id(), Position.FLAT),
                    d.getValue(), PreTradeCheck.mark(array.price()));
            if (o.position().qty() == 0) positions.remove(i.id());
            else positions.put(i.id(), o.position());
            cash = cash.add(o.cashDelta());
        }
        return margin(positions, cash, a.collateral, arrays);
    }

    public List<MarginEvent> onTick(Tick t) {
        if (t.tickSeq() <= lastTickSeq) {
            throw new IllegalStateException("tick " + t.tickSeq() + " after " + lastTickSeq + ": ticks must be strictly increasing");
        }
        lastTickSeq = t.tickSeq();
        UnderlyingParams p = cfg.params(t.underlying());
        markets.put(t.underlying(), new MarketState(t.underlying(), t.spot().doubleValue(), t.vix().doubleValue() * p.volScale(), t.logTimeMillis()));

        SortedMap<String, RiskArray> arrays = new TreeMap<>(); // priced once per instrument per tick
        List<MarginEvent> events = new ArrayList<>();
        for (Account a : accounts.values()) {
            if (!holds(a, t.underlying())) continue;
            MarginResult r = margin(a.positions, a.cash, a.collateral, arrays);
            if (r == null) continue;
            a.latest = r;
            MarginLevel before = a.tracker.level(), after = a.tracker.onReading(r.utilisation());
            if (after != before) events.add(new MarginEvent(a.id, t.tickSeq(), t.logTimeMillis(), r, before, after));
        }
        return events;
    }

    public void onFill(Fill f) {
        if (!seenFillIds.add(f.fillId())) return;
        Account a = accounts.get(f.accountId());
        if (a == null) throw new IllegalStateException("fill " + f.fillId() + " for unknown account " + f.accountId());
        Instrument i = instrument(f.instrumentId(), f.accountId());
        PositionAccounting.Outcome o = PositionAccounting.apply(i.type(), a.positions.getOrDefault(i.id(), Position.FLAT), f.qty(), f.price());
        if (o.position().qty() == 0) a.positions.remove(i.id());
        else a.positions.put(i.id(), o.position());
        a.cash = a.cash.add(o.cashDelta());
        PendingOrder p = a.pending.get(f.orderId());
        if (p != null && p.instrumentId().equals(i.id())) {
            long left = PreTradeCheck.remainingAfterFill(p.remaining(), f.qty());
            if (left == 0) a.pending.remove(f.orderId());
            else a.pending.put(f.orderId(), new PendingOrder(p.instrumentId(), left));
        }
        MarginResult r = margin(a.positions, a.cash, a.collateral, new TreeMap<>());
        if (r != null) a.latest = r;
    }

    private boolean holds(Account a, String underlying) {
        for (String id : a.positions.keySet()) {
            if (instruments.get(id).underlying().equals(underlying)) return true;
        }
        return false;
    }

    /** Full rebuild from positions. Null if some underlying the account holds has not ticked yet. */
    private MarginResult margin(SortedMap<String, Position> positions, BigDecimal cash, BigDecimal collateral,
                                SortedMap<String, RiskArray> arrays) {
        SortedMap<String, AccountVector> vectors = new TreeMap<>();
        for (var pos : positions.entrySet()) {
            Instrument i = instruments.get(pos.getKey());
            MarketState m = markets.get(i.underlying());
            if (m == null) return null;
            RiskArray array = arrays.computeIfAbsent(i.id(), id -> RiskArray.compute(i, m, grids.get(i.underlying()), cfg.riskFreeRate()));
            vectors.computeIfAbsent(i.underlying(), AccountVector::new)
                    .add(pos.getValue().qty(), pos.getValue().avgPrice().doubleValue(), array);
        }
        return MarginCalculator.compute(vectors.values(), cfg, cash.doubleValue(), collateral.doubleValue());
    }

    /** Applies every event from {@code fromOffset} to the end of the log; returns the next offset to read. */
    public long run(EventLog log, long fromOffset, Consumer<EngineOutput> sink) {
        long next = fromOffset;
        while (true) {
            List<LoggedEvent> batch = log.read(next, 1_000);
            if (batch.isEmpty()) return next;
            for (LoggedEvent le : batch) {
                apply(le.event()).forEach(sink);
                next = le.offset() + 1;
            }
        }
    }

    public MarginResult latest(String accountId) {
        return accounts.get(accountId).latest;
    }

    public MarginLevel level(String accountId) {
        return accounts.get(accountId).tracker.level();
    }

    public BigDecimal cash(String accountId) {
        return accounts.get(accountId).cash;
    }

    public SortedMap<String, Position> positions(String accountId) {
        return new TreeMap<>(accounts.get(accountId).positions);
    }

    /** Accepted orders still waiting for fills, by order id. */
    public SortedMap<String, PendingOrder> pendingOrders(String accountId) {
        return new TreeMap<>(accounts.get(accountId).pending);
    }
}
