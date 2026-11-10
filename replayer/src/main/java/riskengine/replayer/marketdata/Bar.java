package riskengine.replayer.marketdata;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** One OHLC bar. {@code ts} is the bar's start for minute bars and the session close for daily bars. */
public record Bar(OffsetDateTime ts, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close) {
    public Bar {
        if (low.compareTo(high) > 0
                || open.compareTo(low) < 0 || open.compareTo(high) > 0
                || close.compareTo(low) < 0 || close.compareTo(high) > 0) {
            throw new IllegalArgumentException("inconsistent OHLC at " + ts + ": " + open + "/" + high + "/" + low + "/" + close);
        }
        if (low.signum() <= 0) throw new IllegalArgumentException("non-positive price at " + ts);
    }
}
