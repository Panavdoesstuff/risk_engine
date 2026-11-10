package riskengine.ref;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.Objects;

/**
 * One listed F&O contract as of a trade date.
 *
 * @param strike null for futures
 * @param close  last traded price on the trade date (may equal previous close if untraded)
 * @param settle exchange settlement price on the trade date; the official end-of-day mark
 */
public record Instrument(
        String id,
        String underlying,
        InstrumentType type,
        LocalDate expiry,
        BigDecimal strike,
        int lotSize,
        BigDecimal close,
        BigDecimal settle,
        long openInterest) {

    /** Canonical order: underlying, expiry, type, strike. Used everywhere output must be stable. */
    public static final Comparator<Instrument> CANONICAL = Comparator
            .comparing(Instrument::underlying)
            .thenComparing(Instrument::expiry)
            .thenComparing(Instrument::type)
            .thenComparing(Instrument::strike, Comparator.nullsFirst(Comparator.naturalOrder()));

    public Instrument {
        Objects.requireNonNull(underlying, "underlying");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(expiry, "expiry");
        Objects.requireNonNull(close, "close");
        Objects.requireNonNull(settle, "settle");
        if (type.isOption() == (strike == null)) {
            throw new IllegalArgumentException("strike must be set for options and null for futures: " + id);
        }
        if (lotSize <= 0) {
            throw new IllegalArgumentException("lotSize must be positive: " + id);
        }
        if (strike != null) {
            strike = strike.stripTrailingZeros();
        }
        String expected = idFor(underlying, type, expiry, strike);
        if (id == null) {
            id = expected;
        } else if (!id.equals(expected)) {
            throw new IllegalArgumentException("id " + id + " does not match fields, expected " + expected);
        }
    }

    public static Instrument of(String underlying, InstrumentType type, LocalDate expiry, BigDecimal strike,
                                int lotSize, BigDecimal close, BigDecimal settle, long openInterest) {
        return new Instrument(null, underlying, type, expiry, strike, lotSize, close, settle, openInterest);
    }

    /** NIFTY-20240627-FUT, NIFTY-20240606-22500-CE. */
    public static String idFor(String underlying, InstrumentType type, LocalDate expiry, BigDecimal strike) {
        String base = underlying + "-" + expiry.format(DateTimeFormatter.BASIC_ISO_DATE);
        return type == InstrumentType.FUT
                ? base + "-FUT"
                : base + "-" + strike.stripTrailingZeros().toPlainString() + "-" + type;
    }
}
