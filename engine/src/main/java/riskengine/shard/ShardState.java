package riskengine.shard;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import riskengine.events.AccountControl;
import riskengine.events.Fill;
import riskengine.events.LoggedEvent;
import riskengine.events.OrderClosed;
import riskengine.events.OrderRequest;
import riskengine.events.SquareOffResult;
import riskengine.events.Tick;
import riskengine.margin.AccountBook;
import riskengine.margin.AccountRisk;
import riskengine.margin.AccountView;
import riskengine.margin.AccountVector;
import riskengine.margin.MarginCalculator;
import riskengine.margin.EngineOutput;
import riskengine.margin.MarginConfig;
import riskengine.margin.MarginEvent;
import riskengine.margin.MarginLevel;
import riskengine.margin.MarginResult;
import riskengine.margin.MarketState;
import riskengine.margin.OrderClosure;
import riskengine.margin.OrderDecision;
import riskengine.margin.PendingOrder;
import riskengine.margin.Position;
import riskengine.margin.PositionChange;
import riskengine.margin.PositionAccounting;
import riskengine.margin.PreTradeCheck;
import riskengine.margin.RiskArray;
import riskengine.margin.ScenarioGrid;
import riskengine.margin.UnderlyingParams;
import riskengine.ref.Instrument;

/**
 * Everything one shard knows, and the only code that changes it. Single-threaded by design: exactly one
 * {@link ShardRunner} thread calls {@link #apply}, in log order.
 *
 * <p><b>Incremental update.</b> On a tick, each held instrument is repriced once (shard-local, no shared
 * pricer) and each holder's vector is adjusted by {@code qty x (newArray - oldArray)}. Nothing is rebuilt
 * from positions except by the rolling rebuild: on every tick, the accounts whose index (position in this
 * shard's sorted account list) satisfies {@code index % K == tick_seq % K} are rebuilt, checked against
 * their incremental numbers, and carry on from the rebuilt values. Every account is rebuilt once per K
 * ticks, and no single tick pays for the whole shard.
 *
 * <p><b>Determinism rules for this package</b> (enforced by DeterminismRulesTest): no wall clock, no
 * random numbers, no hash-ordered collections, StrictMath only. Same log in, same state out, bit for bit.
 *
 * <p>Indexes:
 * <ul>
 *   <li>{@code holders}: instrument to the accounts holding it. This is who to update when it reprices.</li>
 *   <li>{@code byUnderlying}: underlying to the instruments held in this shard. This is what to reprice
 *       on a tick. Instruments nobody in the shard holds are never priced.</li>
 * </ul>
 */
public final class ShardState {

    private static final class Account {
        final String id;
        BigDecimal cash;
        final BigDecimal collateral;
        final TreeMap<String, Position> positions = new TreeMap<>();
        /** By underlying. Present only when the account holds that underlying AND it has ticked. */
        final TreeMap<String, AccountVector> vectors = new TreeMap<>();
        final TreeMap<String, Integer> heldPerUnderlying = new TreeMap<>();
        /** Accepted orders waiting for fills, by order id. Their margin is reserved (see PreTradeCheck). */
        final TreeMap<String, PendingOrder> pending = new TreeMap<>();
        /**
         * Net reserved quantity per instrument: the sum of {@code pending}, kept up to date as orders are
         * accepted, filled and closed, so a pre-trade check does not have to walk every open order.
         * Derived from {@code pending} (which is hashed), so it is not hashed itself.
         */
        final TreeMap<String, Long> reservedNet = new TreeMap<>();

        void reserve(String orderId, PendingOrder p) {
            pending.put(orderId, p);
            addReserved(p.instrumentId(), p.remaining());
        }

        /** Sets what is left of an order's reservation; 0 removes it. */
        void setRemaining(String orderId, PendingOrder p, long left) {
            addReserved(p.instrumentId(), left - p.remaining());
            if (left == 0) pending.remove(orderId);
            else pending.put(orderId, new PendingOrder(p.instrumentId(), left));
        }

        private void addReserved(String instrumentId, long delta) {
            long now = reservedNet.getOrDefault(instrumentId, 0L) + delta;
            if (now == 0) reservedNet.remove(instrumentId);
            else reservedNet.put(instrumentId, now);
        }
        /** Level, status and square-off episode: the state machine shared with the reference engine. */
        final AccountRisk risk;
        AccountView view;
        MarginResult latest;

        Account(AccountBook b, MarginConfig cfg) {
            this.id = b.id();
            this.cash = b.cash();
            this.collateral = b.collateral();
            this.risk = new AccountRisk(b.id(), cfg.thresholds());
        }
    }

    /** How {@link AccountRisk} and the leg planner see one of this shard's accounts. */
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
            return fullyPriced(a) ? marginWith(a, deltas) : null;
        }

        @Override
        public BigDecimal mark(String instrumentId) {
            return PreTradeCheck.mark(arrayFor(instruments.get(instrumentId)).price());
        }

        @Override
        public void cancelReservations(List<EngineOutput> out, long logTimeMillis) {
            for (var e : a.pending.entrySet()) {
                out.add(new OrderClosure(e.getKey(), a.id, OrderClosed.Reason.CANCELLED, e.getValue().remaining(), logTimeMillis));
            }
            a.pending.clear();
            a.reservedNet.clear();
        }
    }

    /** One (account, instrument) pair, with direct references so the tick loop does no lookups. */
    private static final class Holding {
        final Account account;
        long qty;
        double avgPrice;
        AccountVector vector; // null until the underlying has ticked

        Holding(Account account, Position p, AccountVector vector) {
            this.account = account;
            this.qty = p.qty();
            this.avgPrice = p.avgPrice().doubleValue();
            this.vector = vector;
        }
    }

    private final int shardId;
    private final MarginConfig cfg;
    private final ShardConfig shardCfg;
    private final SortedMap<String, Instrument> instruments = new TreeMap<>();
    private final TreeMap<String, Account> accounts = new TreeMap<>();
    /** Accounts in id order; an account's position here is its index for the rolling rebuild. */
    private final Account[] byIndex;
    private final TreeMap<String, TreeMap<String, Holding>> holders = new TreeMap<>();
    private final TreeMap<String, TreeSet<String>> byUnderlying = new TreeMap<>();
    private final TreeMap<String, TreeMap<String, Account>> accountsByUnderlying = new TreeMap<>();
    private final TreeMap<String, RiskArray> arrays = new TreeMap<>();
    private final TreeMap<String, MarketState> markets = new TreeMap<>();
    private final TreeMap<String, ScenarioGrid> grids = new TreeMap<>();
    private final TreeSet<String> seenFillIds = new TreeSet<>();
    private final TreeSet<String> seenOrderIds = new TreeSet<>();

    private long nextOffset;
    private long lastTickSeq = Long.MIN_VALUE;
    // Diagnostics, not part of the hashed state.
    private double maxDriftSeen;
    private long accountRebuilds;
    private int maxRebuildsInOneTick;
    private long duplicateFills;
    private long duplicateOrders;

    /** @param books only the accounts this shard owns (see {@link Partitioner}) */
    public ShardState(int shardId, MarginConfig cfg, ShardConfig shardCfg, Collection<Instrument> instruments, Collection<AccountBook> books) {
        this.shardId = shardId;
        this.cfg = cfg;
        this.shardCfg = shardCfg;
        for (Instrument i : instruments) this.instruments.put(i.id(), i);
        cfg.underlyings().forEach((u, p) -> grids.put(u, new ScenarioGrid(p)));
        for (AccountBook b : new TreeMap<>(byId(books)).values()) {
            Account a = new Account(b, cfg);
            a.view = new View(a);
            accounts.put(a.id, a);
            for (var e : b.positions().entrySet()) {
                if (e.getValue().qty() == 0) continue;
                Instrument i = instrument(e.getKey(), a.id);
                a.positions.put(i.id(), e.getValue());
                link(a, i, e.getValue());
            }
        }
        byIndex = accounts.values().toArray(new Account[0]);
    }

    private static SortedMap<String, AccountBook> byId(Collection<AccountBook> books) {
        SortedMap<String, AccountBook> m = new TreeMap<>();
        for (AccountBook b : books) {
            if (m.put(b.id(), b) != null) throw new IllegalArgumentException("duplicate account " + b.id());
        }
        return m;
    }

    private Instrument instrument(String id, String accountId) {
        Instrument i = instruments.get(id);
        if (i == null) throw new IllegalArgumentException(accountId + " refers to unknown instrument " + id);
        cfg.params(i.underlying()); // fails for unconfigured underlyings (Nifty only until M5)
        return i;
    }

    // ---------------------------------------------------------------- applying events

    /**
     * Applies the next event. Offsets must arrive in order with no gaps. Returns what the shard has to say
     * about it: level changes for a tick, the pre-trade decision for an order, the new position for a
     * fill, the release for a closed order.
     */
    public List<EngineOutput> apply(LoggedEvent le) {
        if (le.offset() != nextOffset) {
            throw new IllegalStateException("shard " + shardId + " expected offset " + nextOffset + " but got " + le.offset());
        }
        List<EngineOutput> out = switch (le.event()) {
            case Tick t -> onTick(t);
            case Fill f -> onFill(f);
            case OrderRequest o -> onOrder(o);
            case OrderClosed c -> onClosed(c);
            case AccountControl c -> onControl(c);
            case SquareOffResult s -> List.of(); // not used: a leg's outcome arrives as Fill or OrderClosed
        };
        nextOffset++;
        return out;
    }

    private List<EngineOutput> onTick(Tick t) {
        if (t.tickSeq() <= lastTickSeq) {
            throw new IllegalStateException("tick " + t.tickSeq() + " after " + lastTickSeq + ": ticks must be strictly increasing");
        }
        lastTickSeq = t.tickSeq();
        String u = t.underlying();
        UnderlyingParams p = cfg.params(u);
        MarketState m = new MarketState(u, t.spot().doubleValue(), t.vix().doubleValue() * p.volScale(), t.logTimeMillis());
        boolean firstTick = markets.put(u, m) == null;
        ScenarioGrid grid = grids.get(u);

        TreeSet<String> held = byUnderlying.get(u);
        TreeMap<String, Account> affected = accountsByUnderlying.get(u);
        if (held != null) {
            if (firstTick) {
                for (String id : held) arrays.put(id, RiskArray.compute(instruments.get(id), m, grid, cfg.riskFreeRate()));
                for (Account a : affected.values()) attachVector(a, u, rebuild(a, u));
            } else {
                for (String id : held) {
                    RiskArray fresh = RiskArray.compute(instruments.get(id), m, grid, cfg.riskFreeRate());
                    RiskArray old = arrays.put(id, fresh);
                    // The incremental step: vector += qty x (new - old), for every holder of this instrument.
                    for (Holding h : holders.get(id).values()) h.vector.applyTick(h.qty, old, fresh);
                }
            }
        }
        rollingRebuildAndCheck(t.tickSeq());

        List<EngineOutput> events = new ArrayList<>();
        if (affected != null) {
            for (Account a : affected.values()) {
                if (!fullyPriced(a)) continue;
                MarginResult r = margin(a);
                a.latest = r;
                // Level, status and square-off: may emit a level change, open or end an episode, plan a leg.
                a.risk.onTick(a.view, r, t.tickSeq(), t.logTimeMillis(), events);
            }
        }
        return events;
    }

    private List<EngineOutput> onFill(Fill f) {
        if (!seenFillIds.add(f.fillId())) {
            duplicateFills++;
            return List.of();
        }
        Account a = accounts.get(f.accountId());
        if (a == null) throw new IllegalStateException("fill " + f.fillId() + " for account " + f.accountId() + " which shard " + shardId + " does not own");
        Instrument i = instrument(f.instrumentId(), a.id);
        String u = i.underlying();
        Position old = a.positions.getOrDefault(i.id(), Position.FLAT);
        PositionAccounting.Outcome o = PositionAccounting.apply(i.type(), old, f.qty(), f.price());
        Position now = o.position();
        MarketState m = markets.get(u);

        if (old.qty() == 0) {
            a.positions.put(i.id(), now);
            if (m != null) {
                AccountVector v = a.vectors.computeIfAbsent(u, AccountVector::new);
                RiskArray array = arrays.computeIfAbsent(i.id(), id -> RiskArray.compute(i, m, grids.get(u), cfg.riskFreeRate()));
                v.add(now.qty(), now.avgPrice().doubleValue(), array);
            }
            link(a, i, now);
        } else if (now.qty() == 0) {
            if (m != null) a.vectors.get(u).remove(old.qty(), old.avgPrice().doubleValue(), arrays.get(i.id()));
            a.positions.remove(i.id());
            unlink(a, i);
        } else {
            if (m != null) {
                RiskArray array = arrays.get(i.id());
                a.vectors.get(u).remove(old.qty(), old.avgPrice().doubleValue(), array)
                        .add(now.qty(), now.avgPrice().doubleValue(), array);
            }
            a.positions.put(i.id(), now);
            Holding h = holders.get(i.id()).get(a.id);
            h.qty = now.qty();
            h.avgPrice = now.avgPrice().doubleValue();
        }
        a.cash = a.cash.add(o.cashDelta());
        long orderRemaining = PositionChange.NOT_TRACKED;
        PendingOrder p = a.pending.get(f.orderId());
        if (p != null && p.instrumentId().equals(i.id())) {
            // This fill belongs to an accepted order: release that much of its reservation.
            orderRemaining = PreTradeCheck.remainingAfterFill(p.remaining(), f.qty());
            a.setRemaining(f.orderId(), p, orderRemaining);
        }
        MarginResult actual = fullyPriced(a) ? margin(a) : null;
        if (actual != null) a.latest = actual;
        // If this was the square-off leg in flight, the episode moves on: ends, or plans the next leg.
        List<EngineOutput> after = new ArrayList<>();
        long legRemaining = a.risk.onFill(a.view, f.orderId(), f.qty(), actual, lastTickSeq, f.logTimeMillis(), after);
        if (legRemaining != PositionChange.NOT_TRACKED) orderRemaining = legRemaining;
        List<EngineOutput> out = new ArrayList<>();
        out.add(new PositionChange(a.id, i.id(), now.qty(), now.avgPrice(), a.cash, f.fillId(), f.orderId(), f.qty(), f.price(),
                o.cashDelta(), orderRemaining, f.logTimeMillis()));
        out.addAll(after);
        return out;
    }

    /** A risk-ops instruction: block, unblock, or square the account off. Unknown accounts are ignored. */
    private List<EngineOutput> onControl(AccountControl c) {
        Account a = accounts.get(c.accountId());
        if (a == null) return List.of();
        List<EngineOutput> out = new ArrayList<>();
        a.risk.onControl(c, a.view, lastTickSeq, out);
        return out;
    }

    /**
     * The order is finished without (fully) filling: release what it still had reserved. Closing an order
     * with nothing reserved is not an error; it is acknowledged with released = 0.
     */
    private List<EngineOutput> onClosed(OrderClosed c) {
        Account a = accounts.get(c.accountId());
        if (a == null) return List.of(new OrderClosure(c.orderId(), c.accountId(), c.reason(), 0, c.logTimeMillis()));
        PendingOrder p = a.pending.get(c.orderId());
        if (p != null) a.setRemaining(c.orderId(), p, 0);
        // Or it was the square-off leg in flight (the broker rejected it): drop it and plan again.
        List<EngineOutput> after = new ArrayList<>();
        long leg = a.risk.onClosed(a.view, c.orderId(), lastTickSeq, c.logTimeMillis(), after);
        List<EngineOutput> out = new ArrayList<>();
        out.add(new OrderClosure(c.orderId(), c.accountId(), c.reason(), p != null ? p.remaining() : leg, c.logTimeMillis()));
        out.addAll(after);
        return out;
    }

    // ---------------------------------------------------------------- pre-trade check

    /**
     * The pre-trade margin check. It needs no lock: this order sits in the account's partition, in line
     * with that account's fills and with every tick, and this thread is the only one that ever touches the
     * account. Whatever the shard has applied so far IS the account's state at this point in the log.
     *
     * <p>A bad order must never stop the shard, so everything a user could get wrong is a rejection, not
     * an exception.
     */
    private List<EngineOutput> onOrder(OrderRequest o) {
        if (o.origin() != OrderRequest.Origin.USER) return List.of(); // square-off legs never enter as order events
        if (!seenOrderIds.add(o.orderId())) {
            duplicateOrders++; // the same request written twice (an API retry): decided once
            return List.of();
        }
        Account a = accounts.get(o.accountId());
        if (a == null) return List.of(OrderDecision.rejected(o, OrderDecision.Reason.UNKNOWN_ACCOUNT, lastTickSeq));
        OrderDecision.Reason gate = a.risk.orderGate(); // blocked, or being squared off
        if (gate != null) return List.of(OrderDecision.rejected(o, gate, lastTickSeq));
        Instrument i = instruments.get(o.instrumentId());
        OrderDecision.Reason bad = PreTradeCheck.structuralReject(i, cfg, o);
        if (bad != null) return List.of(OrderDecision.rejected(o, bad, lastTickSeq));
        if (!markets.containsKey(i.underlying()) || !fullyPriced(a)) {
            return List.of(OrderDecision.rejected(o, OrderDecision.Reason.NO_MARKET, lastTickSeq));
        }
        long qty = PreTradeCheck.signedQty(o);
        MarginResult before = whatIf(a, null, 0);
        MarginResult after = whatIf(a, i.id(), qty);
        boolean ok = PreTradeCheck.passes(before, after, cfg.thresholds());
        if (ok) a.reserve(o.orderId(), new PendingOrder(i.id(), qty));
        return List.of(OrderDecision.checked(o, ok, lastTickSeq, after, PreTradeCheck.mark(arrayFor(i).price())));
    }

    /** Margin if every reserved order (plus one extra, if given) filled now at the mark. */
    private MarginResult whatIf(Account a, String extraInstrumentId, long extraQty) {
        // The reserved quantities per instrument, plus the extra order. (The reference engine gets the same
        // numbers by adding up every open order each time.)
        TreeMap<String, Long> deltas = a.reservedNet;
        if (extraInstrumentId != null) {
            deltas = new TreeMap<>(a.reservedNet);
            deltas.merge(extraInstrumentId, extraQty, Long::sum);
        }
        return marginWith(a, deltas);
    }

    /** The risk array for an instrument at the current market: stored if anyone in the shard holds it, else computed. */
    private RiskArray arrayFor(Instrument i) {
        RiskArray array = arrays.get(i.id());
        return array != null ? array : RiskArray.compute(i, markets.get(i.underlying()), grids.get(i.underlying()), cfg.riskFreeRate());
    }

    /**
     * Margin if the given signed quantities filled now at the mark, on top of the real positions. Works on
     * copies of the account's vectors: the same remove-old / add-new steps a real fill takes, without
     * touching state.
     */
    private MarginResult marginWith(Account a, SortedMap<String, Long> deltas) {
        TreeMap<String, AccountVector> vectors = new TreeMap<>();
        for (AccountVector v : a.vectors.values()) vectors.put(v.underlying(), v.copy());
        BigDecimal cash = a.cash;
        for (var d : deltas.entrySet()) {
            if (d.getValue() == 0) continue;
            Instrument i = instruments.get(d.getKey());
            String u = i.underlying();
            RiskArray array = arrayFor(i);
            Position old = a.positions.getOrDefault(i.id(), Position.FLAT);
            PositionAccounting.Outcome o = PositionAccounting.apply(i.type(), old, d.getValue(), PreTradeCheck.mark(array.price()));
            AccountVector v = vectors.get(u);
            if (v == null) {
                v = new AccountVector(u);
                vectors.put(u, v);
            }
            if (old.qty() != 0) v.remove(old.qty(), old.avgPrice().doubleValue(), array);
            if (o.position().qty() != 0) v.add(o.position().qty(), o.position().avgPrice().doubleValue(), array);
            cash = cash.add(o.cashDelta());
        }
        return MarginCalculator.compute(vectors.values(), cfg, cash.doubleValue(), a.collateral.doubleValue());
    }

    // ---------------------------------------------------------------- indexes

    private void link(Account a, Instrument i, Position p) {
        String u = i.underlying();
        holders.computeIfAbsent(i.id(), k -> new TreeMap<>()).put(a.id, new Holding(a, p, a.vectors.get(u)));
        byUnderlying.computeIfAbsent(u, k -> new TreeSet<>()).add(i.id());
        accountsByUnderlying.computeIfAbsent(u, k -> new TreeMap<>()).put(a.id, a);
        a.heldPerUnderlying.merge(u, 1, Integer::sum);
    }

    private void unlink(Account a, Instrument i) {
        String u = i.underlying();
        TreeMap<String, Holding> hs = holders.get(i.id());
        hs.remove(a.id);
        if (hs.isEmpty()) {
            // Nobody in this shard holds it any more: stop pricing it.
            holders.remove(i.id());
            arrays.remove(i.id());
            TreeSet<String> set = byUnderlying.get(u);
            set.remove(i.id());
            if (set.isEmpty()) byUnderlying.remove(u);
        }
        if (a.heldPerUnderlying.merge(u, -1, Integer::sum) == 0) {
            a.heldPerUnderlying.remove(u);
            a.vectors.remove(u); // drop the vector rather than keep floating-point dust around zero
            TreeMap<String, Account> m = accountsByUnderlying.get(u);
            m.remove(a.id);
            if (m.isEmpty()) accountsByUnderlying.remove(u);
        }
    }

    private void attachVector(Account a, String underlying, AccountVector v) {
        a.vectors.put(underlying, v);
        for (String id : a.positions.keySet()) {
            if (instruments.get(id).underlying().equals(underlying)) holders.get(id).get(a.id).vector = v;
        }
    }

    // ---------------------------------------------------------------- margin + drift check

    private boolean fullyPriced(Account a) {
        for (String u : a.heldPerUnderlying.keySet()) {
            if (!markets.containsKey(u)) return false;
        }
        return true;
    }

    private MarginResult margin(Account a) {
        return MarginCalculator.compute(a.vectors.values(), cfg, a.cash.doubleValue(), a.collateral.doubleValue());
    }

    /** The account's vector for one underlying, rebuilt from positions and current arrays. */
    private AccountVector rebuild(Account a, String underlying) {
        AccountVector v = new AccountVector(underlying);
        for (var e : a.positions.entrySet()) {
            if (!instruments.get(e.getKey()).underlying().equals(underlying)) continue;
            v.add(e.getValue().qty(), e.getValue().avgPrice().doubleValue(), arrays.get(e.getKey()));
        }
        return v;
    }

    /**
     * Rebuilds and checks the accounts whose turn it is on this tick: index % K == tick_seq % K. Which
     * accounts are rebuilt depends only on the log (tick_seq) and the shard's fixed account list, so two
     * replays of the same log rebuild the same accounts on the same ticks.
     */
    private void rollingRebuildAndCheck(long tickSeq) {
        int k = shardCfg.rebuildCycleTicks();
        int rebuilt = 0;
        for (int i = (int) Math.floorMod(tickSeq, (long) k); i < byIndex.length; i += k) {
            Account a = byIndex[i];
            rebuilt++;
            for (AccountVector incremental : a.vectors.values()) {
                AccountVector full = rebuild(a, incremental.underlying());
                double drift = incremental.maxDifference(full);
                if (drift > maxDriftSeen) maxDriftSeen = drift;
                if (!(drift <= shardCfg.driftToleranceRupees())) {
                    throw new IllegalStateException("shard " + shardId + " account " + a.id + " drifted by " + drift
                            + " rupees (tolerance " + shardCfg.driftToleranceRupees() + ") at tick " + tickSeq);
                }
                incremental.copyFrom(full);
            }
        }
        accountRebuilds += rebuilt;
        if (rebuilt > maxRebuildsInOneTick) maxRebuildsInOneTick = rebuilt;
    }

    // ---------------------------------------------------------------- state hash

    /**
     * SHA-256 over the whole state in sorted order, doubles by their exact bits. Two shards that applied the
     * same log have the same hash; one differing bit anywhere changes it.
     */
    public String stateHash() {
        Hasher h = new Hasher();
        h.num(shardId).num(nextOffset).num(lastTickSeq);
        h.num(markets.size());
        for (MarketState m : markets.values()) h.str(m.underlying()).dbl(m.spot()).dbl(m.vol()).num(m.logTimeMillis());
        h.num(arrays.size());
        for (RiskArray a : arrays.values()) {
            h.str(a.instrumentId()).dbl(a.price()).dbl(a.notional());
            for (int s = 0; s < ScenarioGrid.SIZE; s++) h.dbl(a.loss(s));
        }
        h.num(accounts.size());
        for (Account a : accounts.values()) {
            h.str(a.id).dec(a.cash).dec(a.collateral);
            h.num(a.positions.size());
            for (var e : a.positions.entrySet()) h.str(e.getKey()).num(e.getValue().qty()).dec(e.getValue().avgPrice());
            h.num(a.vectors.size());
            for (AccountVector v : a.vectors.values()) {
                h.str(v.underlying());
                for (double c : v.components()) h.dbl(c);
            }
            h.num(a.pending.size());
            for (var e : a.pending.entrySet()) h.str(e.getKey()).str(e.getValue().instrumentId()).num(e.getValue().remaining());
            h.str(a.risk.fingerprint());
            MarginResult r = a.latest;
            h.num(r == null ? 0 : 1);
            if (r != null) {
                h.dbl(r.worstScenarioLoss()).num(r.worstScenario()).dbl(r.shortOptionMinimum()).dbl(r.spanRequirement())
                 .dbl(r.netOptionValue()).dbl(r.riskRequirement()).dbl(r.exposureMargin()).dbl(r.required())
                 .dbl(r.futuresMtm()).dbl(r.available()).dbl(r.utilisation());
            }
        }
        h.num(seenFillIds.size());
        for (String id : seenFillIds) h.str(id);
        h.num(seenOrderIds.size());
        for (String id : seenOrderIds) h.str(id);
        return h.hex();
    }

    private static final class Hasher {
        private final MessageDigest md;
        private final byte[] buf = new byte[8];

        Hasher() {
            try {
                md = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }

        Hasher num(long v) {
            for (int i = 7; i >= 0; i--) {
                buf[i] = (byte) v;
                v >>>= 8;
            }
            md.update(buf);
            return this;
        }

        Hasher dbl(double v) {
            return num(Double.doubleToLongBits(v));
        }

        /** Length-prefixed, so "ab" + "c" can never hash like "a" + "bc". */
        Hasher str(String s) {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            num(b.length);
            md.update(b);
            return this;
        }

        /** By value, not representation: 2.50 and 2.5 hash the same. */
        Hasher dec(BigDecimal d) {
            return str(d.signum() == 0 ? "0" : d.stripTrailingZeros().toPlainString());
        }

        String hex() {
            return HexFormat.of().formatHex(md.digest());
        }
    }

    // ---------------------------------------------------------------- read access

    public int shardId() {
        return shardId;
    }

    /** Offset of the next event this shard will apply. */
    public long nextOffset() {
        return nextOffset;
    }

    public long lastTickSeq() {
        return lastTickSeq;
    }

    public SortedSet<String> accountIds() {
        return new TreeSet<>(accounts.keySet());
    }

    public MarginResult latest(String accountId) {
        return accounts.get(accountId).latest;
    }

    public MarginLevel level(String accountId) {
        return accounts.get(accountId).risk.level();
    }

    /** The account's level, status, episode and in-flight leg, as one comparable line. */
    public String riskFingerprint(String accountId) {
        return accounts.get(accountId).risk.fingerprint();
    }

    public AccountRisk.Status status(String accountId) {
        return accounts.get(accountId).risk.status();
    }

    /** The square-off leg in flight for the account, or null. */
    public AccountRisk.InFlight inFlight(String accountId) {
        return accounts.get(accountId).risk.inFlight();
    }

    public BigDecimal cash(String accountId) {
        return accounts.get(accountId).cash;
    }

    public SortedMap<String, Position> positions(String accountId) {
        return new TreeMap<>(accounts.get(accountId).positions);
    }

    /** Accounts in this shard holding the instrument, sorted. */
    public SortedSet<String> holders(String instrumentId) {
        TreeMap<String, Holding> h = holders.get(instrumentId);
        return h == null ? new TreeSet<>() : new TreeSet<>(h.keySet());
    }

    /** Instruments on the underlying held by anyone in this shard, sorted. */
    public SortedSet<String> heldInstruments(String underlying) {
        TreeSet<String> s = byUnderlying.get(underlying);
        return s == null ? new TreeSet<>() : new TreeSet<>(s);
    }

    /** Largest incremental-vs-rebuild difference seen at any drift check so far, in rupees. */
    public double maxDriftSeen() {
        return maxDriftSeen;
    }

    /** Total account rebuilds done by the rolling rebuild so far. */
    public long accountRebuilds() {
        return accountRebuilds;
    }

    /** Most accounts rebuilt on any single tick: at most ceil(accounts / K), so there is no spike tick. */
    public int maxRebuildsInOneTick() {
        return maxRebuildsInOneTick;
    }

    public long duplicateFills() {
        return duplicateFills;
    }

    public long duplicateOrders() {
        return duplicateOrders;
    }

    /** Accepted orders still waiting for fills, by order id. */
    public SortedMap<String, PendingOrder> pendingOrders(String accountId) {
        return new TreeMap<>(accounts.get(accountId).pending);
    }

    public boolean owns(String accountId) {
        return accounts.containsKey(accountId);
    }
}
