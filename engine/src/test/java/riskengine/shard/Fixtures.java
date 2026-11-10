package riskengine.shard;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.TreeMap;
import riskengine.events.Event;
import riskengine.events.Fill;
import riskengine.events.OrderRequest;
import riskengine.events.SquareOffResult;
import riskengine.events.Tick;
import riskengine.margin.AccountBook;
import riskengine.margin.MarginConfig;
import riskengine.margin.Position;
import riskengine.ref.Instrument;
import riskengine.ref.InstrumentType;

/** Random but reproducible books and event streams. (Randomness is fine in tests; it is banned in shard code.) */
final class Fixtures {
    static final MarginConfig CFG = MarginConfig.defaults();
    static final long T0 = OffsetDateTime.parse("2024-06-04T09:16:00+05:30").toInstant().toEpochMilli();

    private Fixtures() {}

    /** Two futures and two option expiries, strikes 21,000 to 25,000 in steps of 100: 166 instruments. */
    static List<Instrument> instruments() {
        List<Instrument> out = new ArrayList<>();
        out.add(Instrument.of("NIFTY", InstrumentType.FUT, LocalDate.of(2024, 6, 27), null, 25, BigDecimal.ONE, BigDecimal.ONE, 1));
        out.add(Instrument.of("NIFTY", InstrumentType.FUT, LocalDate.of(2024, 7, 25), null, 25, BigDecimal.ONE, BigDecimal.ONE, 1));
        for (LocalDate exp : List.of(LocalDate.of(2024, 6, 6), LocalDate.of(2024, 6, 13))) {
            for (int k = 21_000; k <= 25_000; k += 100) {
                for (InstrumentType t : List.of(InstrumentType.CE, InstrumentType.PE)) {
                    out.add(Instrument.of("NIFTY", t, exp, BigDecimal.valueOf(k), 25, BigDecimal.ONE, BigDecimal.ONE, 1));
                }
            }
        }
        return out;
    }

    static BigDecimal plausiblePrice(Instrument i, SplittableRandom r) {
        return i.type() == InstrumentType.FUT
                ? BigDecimal.valueOf(2_290_000 + r.nextInt(80_000), 2)   // 22,900.00 .. 23,700.00
                : BigDecimal.valueOf(500 + r.nextInt(60_000), 2);        // 5.00 .. 605.00
    }

    /** n accounts with 1-6 random positions each. */
    static List<AccountBook> books(int n, long seed) {
        SplittableRandom r = new SplittableRandom(seed);
        List<Instrument> ins = instruments();
        List<AccountBook> out = new ArrayList<>(n);
        for (int a = 1; a <= n; a++) {
            TreeMap<String, Position> pos = new TreeMap<>();
            int legs = 1 + r.nextInt(6);
            for (int l = 0; l < legs; l++) {
                Instrument i = ins.get(r.nextInt(ins.size()));
                int lots = 1 + r.nextInt(10);
                pos.put(i.id(), new Position(25L * (r.nextBoolean() ? lots : -lots), plausiblePrice(i, r)));
            }
            BigDecimal cash = BigDecimal.valueOf(10_000_000L + r.nextLong(500_000_000L), 2); // 1 lakh .. 51 lakh
            out.add(new AccountBook(String.format("A%07d", a), cash, BigDecimal.ZERO, pos));
        }
        return out;
    }

    /**
     * A choppy, falling market with fills mixed in: opens, partial closes, exact closes, flips, duplicates,
     * plus a few order and square-off events (which shards must accept and ignore for now).
     */
    static List<Event> events(List<AccountBook> books, int ticks, int fillsPerTick, long seed) {
        SplittableRandom r = new SplittableRandom(seed);
        List<Instrument> ins = instruments();
        List<Event> out = new ArrayList<>();
        // Track quantities so some fills close positions exactly.
        List<TreeMap<String, Long>> qty = new ArrayList<>();
        for (AccountBook b : books) {
            TreeMap<String, Long> m = new TreeMap<>();
            b.positions().forEach((k, v) -> m.put(k, v.qty()));
            qty.add(m);
        }
        double spot = 23_300, vix = 21;
        long fillSeq = 0;
        Fill last = null;
        for (int t = 1; t <= ticks; t++) {
            spot *= 1 + (r.nextDouble() - 0.58) * 0.012;      // drifts down, about -0.1% a tick
            vix = Math.max(11, Math.min(45, vix + (r.nextDouble() - 0.42) * 1.2));
            long time = T0 + t * 60_000L;
            out.add(new Tick(t, time, "NIFTY", BigDecimal.valueOf(Math.round(spot * 100), 2), BigDecimal.valueOf(Math.round(vix * 100), 2)));
            for (int f = 0; f < fillsPerTick; f++) {
                int a = r.nextInt(books.size());
                String accountId = books.get(a).id();
                TreeMap<String, Long> held = qty.get(a);
                int kind = r.nextInt(100);
                if (kind < 5 && last != null) {
                    out.add(last); // duplicate broker callback
                    continue;
                }
                if (kind < 8) {
                    out.add(new OrderRequest("O" + t + "-" + f, accountId, ins.get(r.nextInt(ins.size())).id(), OrderRequest.Side.BUY, 25,
                            OrderRequest.Origin.USER, time));
                    continue;
                }
                if (kind < 10) {
                    Instrument i = ins.get(r.nextInt(ins.size()));
                    out.add(new SquareOffResult(accountId + ":E1:" + i.id() + ":0", accountId, i.id(), SquareOffResult.Status.REJECTED, time));
                    continue;
                }
                Instrument i;
                long q;
                if (kind < 55 && !held.isEmpty()) {
                    // trade something already held: close exactly, reduce, or flip
                    String id = new ArrayList<>(held.keySet()).get(r.nextInt(held.size()));
                    i = ins.stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow();
                    long h = held.get(id);
                    int how = r.nextInt(3);
                    q = how == 0 ? -h : how == 1 ? -Long.signum(h) * 25L : -h - Long.signum(h) * 25L * (1 + r.nextInt(3));
                } else {
                    i = ins.get(r.nextInt(ins.size()));
                    q = 25L * (1 + r.nextInt(8)) * (r.nextBoolean() ? 1 : -1);
                }
                long now = held.getOrDefault(i.id(), 0L) + q;
                if (now == 0) held.remove(i.id());
                else held.put(i.id(), now);
                last = new Fill("F" + (++fillSeq), "O" + fillSeq, accountId, i.id(), q, plausiblePrice(i, r), time);
                out.add(last);
            }
        }
        return out;
    }
}
