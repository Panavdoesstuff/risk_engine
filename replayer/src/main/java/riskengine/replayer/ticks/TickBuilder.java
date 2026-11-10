package riskengine.replayer.ticks;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import riskengine.events.Tick;
import riskengine.replayer.marketdata.Bar;
import riskengine.replayer.marketdata.MarketDataLoader;
import riskengine.replayer.marketdata.MarketDataLoader.Resolution;

/**
 * Turns index minute bars and a VIX series into ticks, using only what was known at the time.
 *
 * <ul>
 *   <li>A minute bar's close is known when the minute ends, so the bar starting 09:15 becomes a tick at
 *       09:16 with spot = that bar's close.</li>
 *   <li>VIX is joined "as of": each tick takes the latest VIX value already known at the tick's time.
 *       A minute VIX bar is known one minute after it starts. A daily value is known at 15:30, so every
 *       tick during 4 June uses 3 June's close. That is deliberate: using 4 June's close all day would be
 *       reading the future.</li>
 * </ul>
 */
public final class TickBuilder {
    private TickBuilder() {}

    /** @param from inclusive, nullable; @param to inclusive, nullable; filter on the index bar's date */
    public static List<Tick> build(String underlying, NormalisedBars.Series index, NormalisedBars.Series vix,
                                   LocalDate from, LocalDate to, long firstTickSeq) {
        if (index.resolution() != Resolution.MINUTE) throw new IllegalArgumentException("index bars must be minute bars");
        long[] vixKnownAt = new long[vix.bars().size()];
        for (int i = 0; i < vixKnownAt.length; i++) {
            long ts = vix.bars().get(i).ts().toInstant().toEpochMilli();
            vixKnownAt[i] = vix.resolution() == Resolution.MINUTE ? ts + 60_000 : ts;
        }
        List<Tick> out = new ArrayList<>();
        long seq = firstTickSeq;
        int v = -1; // index of the latest VIX value known so far
        for (Bar b : index.bars()) {
            LocalDate d = b.ts().withOffsetSameInstant(MarketDataLoader.IST).toLocalDate();
            if ((from != null && d.isBefore(from)) || (to != null && d.isAfter(to))) continue;
            long time = b.ts().toInstant().toEpochMilli() + 60_000;
            while (v + 1 < vixKnownAt.length && vixKnownAt[v + 1] <= time) v++;
            if (v < 0) throw new IllegalArgumentException("no VIX value is known yet at " + b.ts().plusMinutes(1)
                    + "; the VIX file must start before the index bars (for daily VIX, include the previous trading day)");
            BigDecimal level = vix.bars().get(v).close();
            out.add(new Tick(seq++, time, underlying, b.close(), level));
        }
        if (out.isEmpty()) throw new IllegalArgumentException("no index bars in range " + from + ".." + to);
        return out;
    }
}
