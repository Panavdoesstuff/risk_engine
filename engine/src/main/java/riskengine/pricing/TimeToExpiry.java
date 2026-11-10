package riskengine.pricing;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;

/**
 * Years from a log timestamp to an NSE expiry (15:30 IST on the expiry date), ACT/365 to the millisecond.
 * Time comes from the event, never the wall clock, so replays are deterministic.
 */
public final class TimeToExpiry {
    public static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
    public static final LocalTime EXPIRY_TIME = LocalTime.of(15, 30);
    private static final double MILLIS_PER_YEAR = 365.0 * 24 * 60 * 60 * 1000;

    private TimeToExpiry() {}

    public static long expiryMillis(LocalDate expiry) {
        return expiry.atTime(EXPIRY_TIME).toInstant(IST).toEpochMilli();
    }

    public static double years(LocalDate expiry, long logTimeMillis) {
        return (expiryMillis(expiry) - logTimeMillis) / MILLIS_PER_YEAR;
    }
}
