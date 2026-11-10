package riskengine.replayer.seed;

import java.math.BigDecimal;
import java.util.List;
import java.util.SortedMap;

/** @param startSpots the start-of-day spot used to margin each underlying (given or implied from futures) */
public record SeededBook(SeedConfig config, List<String> underlyings, SortedMap<String, BigDecimal> startSpots,
                         List<Account> accounts, List<Position> positions) {

    public record Account(String accountId, Archetype archetype, boolean thinFunded, BigDecimal cashBalance) {}

    /** @param lots signed (negative = short); qty = lots * lotSize */
    public record Position(String accountId, String instrumentId, int lots, long qty, BigDecimal avgPrice) {}
}
