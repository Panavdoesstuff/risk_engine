package riskengine.events;

import java.math.BigDecimal;

/**
 * One market update for one underlying.
 *
 * @param tickSeq global sequence number, strictly increasing across the whole replay; the same tick is
 *                written to every partition with the same tickSeq
 * @param spot    index level
 * @param vix     India VIX level (20.5 means 20.5%); the margin config turns it into a volatility
 */
public record Tick(long tickSeq, long logTimeMillis, String underlying, BigDecimal spot, BigDecimal vix) implements Event {
    public Tick {
        Event.id("underlying", underlying);
        if (spot.signum() <= 0) throw new IllegalArgumentException("spot must be positive");
        if (vix.signum() <= 0) throw new IllegalArgumentException("vix must be positive");
    }
}
