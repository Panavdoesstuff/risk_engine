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
import riskengine.events.AccountControl;
import riskengine.events.Event;
import riskengine.events.EventLog;
import riskengine.events.Fill;
import riskengine.events.LoggedEvent;
import riskengine.events.OrderClosed;
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
 *   <li>OrderClosed releases what is left of that order's reservation.</li>
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
        final AccountRisk risk;
        AccountView view;
        MarginResult latest;

        Account(AccountBook b, Thresholds t) {
            this.id = b.id();
            this.cash = b.cash();
            this.collateral = b.collateral();
            this.positions = new TreeMap<>(b.positions());
            this.risk = new AccountRisk(b.id(), t);
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
    /** Risk arrays at the current market, filled in as they are needed and thrown away on every tick. */
    private SortedMap<String, RiskArray> arrays = new TreeMap<>();

    public ReferenceMarginEngine(MarginConfig cfg, Collection<Instrument> instruments, Collection<AccountBook> books) {
        this.cfg = cfg;
        for (Instrument i : instruments) this.instruments.put(i.id(), i);
        for (AccountBook b : books) {
            for (String id : b.positions().keySet()) instrument(id, b.id());
            Account a = new Account(b, cfg.thresholds());
            a.view = new View(a);
            if (accounts.put(b.id(), a) != null) throw new IllegalArgumentException("duplicate account " + b.id());
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
            case Tick t -> onTick(t);
            case Fill f -> onFill(f);
            case OrderRequest o -> onOrder(o);
            case OrderClosed c -> onClosed(c);
            case AccountControl c -> onControl(c);
            case SquareOffResult s -> List.of();
        };
    }

    public List<EngineOutput> onOrder(OrderRequest o) {
        if (o.origin() != OrderRequest.Origin.USER) return List.of(); // the engine's own square-off orders: M5
        if (!seenOrderIds.add(o.orderId())) return List.of();
        Account a = accounts.get(o.accountId());
        if (a == null) return List.of(OrderDecision.rejected(o, OrderDecision.Reason.UNKNOWN_ACCOUNT, lastTickSeq));
        OrderDecision.Reason gate = a.risk.orderGate();
        if (gate != null) return List.of(OrderDecision.rejected(o, gate, lastTickSeq));
        Instrument i = instruments.get(o.instrumentId());
        OrderDecision.Reason bad = PreTradeCheck.structuralReject(i, cfg, o);
        if (bad != null) return List.of(OrderDecision.rejected(o, bad, lastTickSeq));
        long qty = PreTradeCheck.signedQty(o);
        MarginResult before = whatIf(a, null, 0, arrays);
        MarginResult after = markets.containsKey(i.underlying()) ? whatIf(a, i.id(), qty, arrays) : null;
        if (before == null || after == null) return List.of(OrderDecision.rejected(o, OrderDecision.Reason.NO_MARKET, lastTickSeq));
        boolean ok = PreTradeCheck.passes(before, after, cfg.thresholds());
        if (ok) a.pending.put(o.orderId(), new PendingOrder(i.id(), qty));
        return List.of(OrderDecision.checked(o, ok, lastTickSeq, after, PreTradeCheck.mark(array(i).price())));
    }

    /** Margin if every reserved order (plus one extra, if given) filled now at the mark. Null if unpriceable. */
    private MarginResult whatIf(Account a, String extraInstrumentId, long extraQty, SortedMap<String, RiskArray> arrays) {
        return marginWith(a, PreTradeCheck.netDeltas(a.pending.values(), extraInstrumentId, extraQty));
    }

    private RiskArray array(Instrument i) {
        MarketState m = markets.get(i.underlying());
        return arrays.computeIfAbsent(i.id(), id -> RiskArray.compute(i, m, grids.get(i.underlying()), cfg.riskFreeRate()));
    }

    /** Margin if the given signed quantities filled now at the mark: rebuilt from the hypothetical positions. Null if unpriceable. */
    private MarginResult marginWith(Account a, SortedMap<String, Long> deltas) {
        SortedMap<String, Position> positions = new TreeMap<>(a.positions);
        BigDecimal cash = a.cash;
        for (var d : deltas.entrySet()) {
            if (d.getValue() == 0) continue;
            Instrument i = instruments.get(d.getKey());
            if (!markets.containsKey(i.underlying())) return null;
            RiskArray array = array(i);
            PositionAccounting.Outcome o = PositionAccounting.apply(i.type(), positions.getOrDefault(i.id(), Position.FLAT),
                    d.getValue(), PreTradeCheck.mark(array.price()));
            if (o.position().qty() == 0) positions.remove(i.id());
            else positions.put(i.id(), o.position());
            cash = cash.add(o.cashDelta());
        }
        return margin(positions, cash, a.collateral, arrays);
    }

    public List<EngineOutput> onTick(Tick t) {
        if (t.tickSeq() <= lastTickSeq) {
            throw new IllegalStateException("tick " + t.tickSeq() + " after " + lastTickSeq + ": ticks must be strictly increasing");
        }
        lastTickSeq = t.tickSeq();
        UnderlyingParams p = cfg.params(t.underlying());
        markets.put(t.underlying(), new MarketState(t.underlying(), t.spot().doubleValue(), t.vix().doubleValue() * p.volScale(), t.logTimeMillis()));

        arrays = new TreeMap<>(); // priced once per instrument per tick
        List<EngineOutput> events = new ArrayList<>();
        for (Account a : accounts.values()) {
            if (!holds(a, t.underlying())) continue;
            MarginResult r = margin(a.positions, a.cash, a.collateral, arrays);
            if (r == null) continue;
            a.latest = r;
            a.risk.onTick(a.view, r, t.tickSeq(), t.logTimeMillis(), events);
        }
        return events;
    }

    /** Releases whatever the order still had reserved. */
    public List<EngineOutput> onClosed(OrderClosed c) {
        Account a = accounts.get(c.accountId());
        if (a == null) return List.of(new OrderClosure(c.orderId(), c.accountId(), c.reason(), 0, c.logTimeMillis()));
        PendingOrder p = a.pending.remove(c.orderId());
        List<EngineOutput> after = new ArrayList<>();
        long leg = a.risk.onClosed(a.view, c.orderId(), lastTickSeq, c.logTimeMillis(), after);
        List<EngineOutput> out = new ArrayList<>();
        out.add(new OrderClosure(c.orderId(), c.accountId(), c.reason(), p != null ? p.remaining() : leg, c.logTimeMillis()));
        out.addAll(after);
        return out;
    }

    public List<EngineOutput> onControl(AccountControl c) {
        Account a = accounts.get(c.accountId());
        if (a == null) return List.of();
        List<EngineOutput> out = new ArrayList<>();
        a.risk.onControl(c, a.view, lastTickSeq, out);
        return out;
    }

    /** How AccountRisk and the leg planner see an account here: every answer rebuilt from positions. */
    private final class View implements AccountView {
        private final Account a;

        View(Account a) {
            this.a = a;
        }

        @Override
        public SortedMap<String, Position> positions() {
            return a.positions;
        }

        @Override
        public Instrument instrument(String instrumentId) {
            return instruments.get(instrumentId);
        }

        @Override
        public MarginResult margin(SortedMap<String, Long> deltas) {
            return marginWith(a, deltas);
        }

        @Override
        public BigDecimal mark(String instrumentId) {
            return PreTradeCheck.mark(array(instruments.get(instrumentId)).price());
        }

        @Override
        public void cancelReservations(List<EngineOutput> out, long logTimeMillis) {
            for (var e : a.pending.entrySet()) {
                out.add(new OrderClosure(e.getKey(), a.id, OrderClosed.Reason.CANCELLED, e.getValue().remaining(), logTimeMillis));
            }
            a.pending.clear();
        }
    }

    public List<EngineOutput> onFill(Fill f) {
        if (!seenFillIds.add(f.fillId())) return List.of();
        Account a = accounts.get(f.accountId());
        if (a == null) throw new IllegalStateException("fill " + f.fillId() + " for unknown account " + f.accountId());
        Instrument i = instrument(f.instrumentId(), f.accountId());
        PositionAccounting.Outcome o = PositionAccounting.apply(i.type(), a.positions.getOrDefault(i.id(), Position.FLAT), f.qty(), f.price());
        if (o.position().qty() == 0) a.positions.remove(i.id());
        else a.positions.put(i.id(), o.position());
        a.cash = a.cash.add(o.cashDelta());
        long orderRemaining = PositionChange.NOT_TRACKED;
        PendingOrder p = a.pending.get(f.orderId());
        if (p != null && p.instrumentId().equals(i.id())) {
            orderRemaining = PreTradeCheck.remainingAfterFill(p.remaining(), f.qty());
            if (orderRemaining == 0) a.pending.remove(f.orderId());
            else a.pending.put(f.orderId(), new PendingOrder(p.instrumentId(), orderRemaining));
        }
        MarginResult r = margin(a.positions, a.cash, a.collateral, arrays);
        if (r != null) a.latest = r;
        List<EngineOutput> after = new ArrayList<>();
        long legRemaining = a.risk.onFill(a.view, f.orderId(), f.qty(), r, lastTickSeq, f.logTimeMillis(), after);
        if (legRemaining != PositionChange.NOT_TRACKED) orderRemaining = legRemaining;
        List<EngineOutput> out = new ArrayList<>();
        out.add(new PositionChange(a.id, i.id(), o.position().qty(), o.position().avgPrice(), a.cash, f.fillId(), f.orderId(), f.qty(), f.price(),
                o.cashDelta(), orderRemaining, f.logTimeMillis()));
        out.addAll(after);
        return out;
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
        return accounts.get(accountId).risk.level();
    }

    public String riskFingerprint(String accountId) {
        return accounts.get(accountId).risk.fingerprint();
    }

    public AccountRisk.Status status(String accountId) {
        return accounts.get(accountId).risk.status();
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
