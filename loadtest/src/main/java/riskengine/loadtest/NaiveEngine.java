package riskengine.loadtest;

import java.util.List;
import java.util.TreeMap;
import riskengine.events.Tick;
import riskengine.margin.AccountBook;
import riskengine.margin.AccountVector;
import riskengine.margin.MarginCalculator;
import riskengine.margin.MarginConfig;
import riskengine.margin.MarginResult;
import riskengine.margin.MarginStatusTracker;
import riskengine.margin.MarketState;
import riskengine.margin.RiskArray;
import riskengine.margin.ScenarioGrid;
import riskengine.margin.UnderlyingParams;
import riskengine.ref.Instrument;

/**
 * The first thing you would write: on every tick, walk every account and reprice every one of its
 * positions in all 16 scenarios. Nothing is shared between accounts. This is the "naive" baseline.
 */
final class NaiveEngine {
    private final MarginConfig cfg;
    private final TreeMap<String, Instrument> instruments = new TreeMap<>();
    private final List<AccountBook> books;
    private final TreeMap<String, MarginStatusTracker> trackers = new TreeMap<>();

    NaiveEngine(MarginConfig cfg, List<Instrument> instruments, List<AccountBook> books) {
        this.cfg = cfg;
        instruments.forEach(i -> this.instruments.put(i.id(), i));
        this.books = books;
        books.forEach(b -> trackers.put(b.id(), new MarginStatusTracker(cfg.thresholds())));
    }

    /** @return sum of required margin, so the JIT cannot discard the work */
    double onTick(Tick t) {
        UnderlyingParams p = cfg.params(t.underlying());
        MarketState m = new MarketState(t.underlying(), t.spot().doubleValue(), t.vix().doubleValue() * p.volScale(), t.logTimeMillis());
        ScenarioGrid grid = new ScenarioGrid(p);
        double total = 0;
        for (AccountBook b : books) {
            AccountVector v = new AccountVector(t.underlying());
            for (var pos : b.positions().entrySet()) {
                RiskArray a = RiskArray.compute(instruments.get(pos.getKey()), m, grid, cfg.riskFreeRate());
                v.add(pos.getValue().qty(), pos.getValue().avgPrice().doubleValue(), a);
            }
            MarginResult r = MarginCalculator.compute(v, cfg, b.cash().doubleValue(), b.collateral().doubleValue());
            trackers.get(b.id()).onReading(r.utilisation());
            total += r.required();
        }
        return total;
    }
}
