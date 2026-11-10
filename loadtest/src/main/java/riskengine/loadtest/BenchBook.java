package riskengine.loadtest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.TreeMap;
import riskengine.events.Tick;
import riskengine.margin.AccountBook;
import riskengine.margin.Position;
import riskengine.ref.Instrument;
import riskengine.ref.InstrumentType;

/** A reproducible synthetic book and tick stream for benchmarks. (Random is fine here; it is banned in shard code.) */
final class BenchBook {
    static final long T0 = OffsetDateTime.parse("2024-06-04T09:16:00+05:30").toInstant().toEpochMilli();

    private BenchBook() {}

    /** Two futures and two option expiries, strikes 21,000 to 25,000 by 100: 166 instruments. */
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

    /** {@code accounts} accounts, each with exactly {@code positionsEach} distinct positions. */
    static List<AccountBook> books(List<Instrument> ins, int accounts, int positionsEach, long seed) {
        SplittableRandom r = new SplittableRandom(seed);
        List<AccountBook> out = new ArrayList<>(accounts);
        for (int a = 1; a <= accounts; a++) {
            TreeMap<String, Position> pos = new TreeMap<>();
            while (pos.size() < positionsEach) {
                Instrument i = ins.get(r.nextInt(ins.size()));
                int lots = 1 + r.nextInt(10);
                BigDecimal avg = i.type() == InstrumentType.FUT ? new BigDecimal("23300") : new BigDecimal("150");
                pos.put(i.id(), new Position(25L * (r.nextBoolean() ? lots : -lots), avg));
            }
            out.add(new AccountBook(String.format("A%07d", a), BigDecimal.valueOf(2_000_000 + r.nextInt(8_000_000)), BigDecimal.ZERO, pos));
        }
        return out;
    }

    /** A sawtooth market: spot swings 23,000..23,600, VIX 18..26, 10 ms between ticks. Every tick reprices everything. */
    static Tick tick(long seq) {
        long phase = seq % 120;
        long up = phase < 60 ? phase : 120 - phase;                 // 0..60..0
        BigDecimal spot = BigDecimal.valueOf(2_300_000 + up * 1_000, 2);
        BigDecimal vix = BigDecimal.valueOf(1_800 + (seq % 80 < 40 ? seq % 80 : 80 - seq % 80) * 20, 2);
        return new Tick(seq, T0 + seq * 10, "NIFTY", spot, vix);
    }
}
