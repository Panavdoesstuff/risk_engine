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
import riskengine.events.Fill;
import riskengine.events.LoggedEvent;
import riskengine.events.OrderRequest;
import riskengine.events.SquareOffResult;
import riskengine.events.Tick;
import riskengine.margin.AccountBook;
import riskengine.margin.AccountVector;
import riskengine.margin.MarginCalculator;
import riskengine.margin.MarginConfig;
import riskengine.margin.MarginEvent;
import riskengine.margin.MarginLevel;
import riskengine.margin.MarginResult;
import riskengine.margin.MarginStatusTracker;
import riskengine.margin.MarketState;
import riskengine.margin.Position;
import riskengine.margin.PositionAccounting;
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
 * from positions except every K ticks, when the shard rebuilds everything, checks the incremental numbers
 * against the rebuild, and carries on from the rebuilt values.
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
        final MarginStatusTracker tracker;
        MarginResult latest;

        Account(AccountBook b, MarginConfig cfg) {
            this.id = b.id();
            this.cash = b.cash();
            this.collateral = b.collateral();
            this.tracker = new MarginStatusTracker(cfg.thresholds());
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
    private final TreeMap<String, TreeMap<String, Holding>> holders = new TreeMap<>();
    private final TreeMap<String, TreeSet<String>> byUnderlying = new TreeMap<>();
    private final TreeMap<String, TreeMap<String, Account>> accountsByUnderlying = new TreeMap<>();
    private final TreeMap<String, RiskArray> arrays = new TreeMap<>();
    private final TreeMap<String, MarketState> markets = new TreeMap<>();
    private final TreeMap<String, ScenarioGrid> grids = new TreeMap<>();
    private final TreeSet<String> seenFillIds = new TreeSet<>();

    private long nextOffset;
    private long lastTickSeq = Long.MIN_VALUE;
    private int ticksSinceFullRecompute;
    // Diagnostics, not part of the hashed state.
    private double maxDriftSeen;
    private long fullRecomputes;
    private long duplicateFills;

    /** @param books only the accounts this shard owns (see {@link Partitioner}) */
    public ShardState(int shardId, MarginConfig cfg, ShardConfig shardCfg, Collection<Instrument> instruments, Collection<AccountBook> books) {
        this.shardId = shardId;
        this.cfg = cfg;
        this.shardCfg = shardCfg;
        for (Instrument i : instruments) this.instruments.put(i.id(), i);
        cfg.underlyings().forEach((u, p) -> grids.put(u, new ScenarioGrid(p)));
        for (AccountBook b : new TreeMap<>(byId(books)).values()) {
            Account a = new Account(b, cfg);
            accounts.put(a.id, a);
            for (var e : b.positions().entrySet()) {
                if (e.getValue().qty() == 0) continue;
                Instrument i = instrument(e.getKey(), a.id);
                a.positions.put(i.id(), e.getValue());
                link(a, i, e.getValue());
            }
        }
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

    /** Applies the next event. Offsets must arrive in order with no gaps. Returns level changes (ticks only). */
    public List<MarginEvent> apply(LoggedEvent le) {
        if (le.offset() != nextOffset) {
            throw new IllegalStateException("shard " + shardId + " expected offset " + nextOffset + " but got " + le.offset());
        }
        List<MarginEvent> out = switch (le.event()) {
            case Tick t -> onTick(t);
            case Fill f -> {
                onFill(f);
                yield List.of();
            }
            case OrderRequest o -> List.of();    // pre-trade check arrives in M4
            case SquareOffResult s -> List.of(); // square-off pipeline arrives in M5
        };
        nextOffset++;
        return out;
    }

    private List<MarginEvent> onTick(Tick t) {
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
        if (++ticksSinceFullRecompute >= shardCfg.fullRecomputeEveryTicks()) {
            fullRecomputeAndCheck();
            ticksSinceFullRecompute = 0;
        }

        List<MarginEvent> events = new ArrayList<>();
        if (affected != null) {
            for (Account a : affected.values()) {
                if (!fullyPriced(a)) continue;
                MarginResult r = margin(a);
                a.latest = r;
                MarginLevel before = a.tracker.level(), after = a.tracker.onReading(r.utilisation());
                if (after != before) events.add(new MarginEvent(a.id, t.tickSeq(), t.logTimeMillis(), r, before, after));
            }
        }
        return events;
    }

    private void onFill(Fill f) {
        if (!seenFillIds.add(f.fillId())) {
            duplicateFills++;
            return;
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
        if (fullyPriced(a)) a.latest = margin(a);
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

    private void fullRecomputeAndCheck() {
        for (Account a : accounts.values()) {
            for (AccountVector incremental : a.vectors.values()) {
                AccountVector full = rebuild(a, incremental.underlying());
                double drift = incremental.maxDifference(full);
                if (drift > maxDriftSeen) maxDriftSeen = drift;
                if (!(drift <= shardCfg.driftToleranceRupees())) {
                    throw new IllegalStateException("shard " + shardId + " account " + a.id + " drifted by " + drift
                            + " rupees (tolerance " + shardCfg.driftToleranceRupees() + ") at tick " + lastTickSeq);
                }
                incremental.copyFrom(full);
            }
        }
        fullRecomputes++;
    }

    // ---------------------------------------------------------------- state hash

    /**
     * SHA-256 over the whole state in sorted order, doubles by their exact bits. Two shards that applied the
     * same log have the same hash; one differing bit anywhere changes it.
     */
    public String stateHash() {
        Hasher h = new Hasher();
        h.num(shardId).num(nextOffset).num(lastTickSeq).num(ticksSinceFullRecompute);
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
            h.num(a.tracker.level().ordinal()).num(a.tracker.callStreak()).num(a.tracker.squareOffStreak());
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
        return accounts.get(accountId).tracker.level();
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

    public long fullRecomputes() {
        return fullRecomputes;
    }

    public long duplicateFills() {
        return duplicateFills;
    }
}
