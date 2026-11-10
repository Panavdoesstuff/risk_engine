package riskengine.margin;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

/** Start-of-day state of one account. Positions are keyed by instrument id. */
public record AccountBook(String id, BigDecimal cash, BigDecimal collateral, SortedMap<String, Position> positions) {
    public AccountBook {
        Objects.requireNonNull(id);
        Objects.requireNonNull(cash);
        Objects.requireNonNull(collateral);
        positions = new TreeMap<>(positions);
    }
}
