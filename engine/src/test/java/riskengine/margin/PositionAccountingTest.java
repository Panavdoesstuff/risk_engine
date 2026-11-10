package riskengine.margin;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import riskengine.ref.InstrumentType;

class PositionAccountingTest {
    static void assertBd(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    @Test
    void optionPremiumMovesInCashImmediately() {
        var buy = PositionAccounting.apply(InstrumentType.CE, Position.FLAT, 50, new BigDecimal("100"));
        assertEquals(50, buy.position().qty());
        assertBd("100", buy.position().avgPrice());
        assertBd("-5000", buy.cashDelta());

        var sell = PositionAccounting.apply(InstrumentType.PE, Position.FLAT, -50, new BigDecimal("80.5"));
        assertEquals(-50, sell.position().qty());
        assertBd("4025", sell.cashDelta());
    }

    @Test
    void addingAveragesReducingKeepsAverageFlippingRestarts() {
        Position p = PositionAccounting.apply(InstrumentType.FUT, Position.FLAT, 50, new BigDecimal("23000")).position();
        p = PositionAccounting.apply(InstrumentType.FUT, p, 25, new BigDecimal("23300")).position();
        assertEquals(75, p.qty());
        assertBd("23100", p.avgPrice()); // (50 x 23000 + 25 x 23300) / 75

        var reduce = PositionAccounting.apply(InstrumentType.FUT, p, -25, new BigDecimal("23400"));
        assertEquals(50, reduce.position().qty());
        assertBd("23100", reduce.position().avgPrice());
        assertBd("7500", reduce.cashDelta()); // 25 x (23400 - 23100) realised

        var flip = PositionAccounting.apply(InstrumentType.FUT, reduce.position(), -80, new BigDecimal("23000"));
        assertEquals(-30, flip.position().qty());
        assertBd("23000", flip.position().avgPrice());
        assertBd("-5000", flip.cashDelta()); // closed 50 at a 100-point loss
    }

    @Test
    void futuresMoveNoCashWhenOpeningAndShortsProfitWhenBoughtBackLower() {
        var open = PositionAccounting.apply(InstrumentType.FUT, Position.FLAT, -50, new BigDecimal("23000"));
        assertBd("0", open.cashDelta());
        var close = PositionAccounting.apply(InstrumentType.FUT, open.position(), 50, new BigDecimal("22000"));
        assertEquals(Position.FLAT, close.position());
        assertBd("50000", close.cashDelta());
    }

    @Test
    void closingAnOptionMovesOnlyThePremium() {
        Position shortCall = new Position(-50, new BigDecimal("100"));
        var buyBack = PositionAccounting.apply(InstrumentType.CE, shortCall, 50, new BigDecimal("300"));
        assertEquals(Position.FLAT, buyBack.position());
        assertBd("-15000", buyBack.cashDelta()); // received 5,000 when selling; net loss 10,000
    }

    @Test
    void zeroQtyAndNegativePriceAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> PositionAccounting.apply(InstrumentType.FUT, Position.FLAT, 0, BigDecimal.ONE));
        assertThrows(IllegalArgumentException.class, () -> PositionAccounting.apply(InstrumentType.FUT, Position.FLAT, 1, new BigDecimal("-1")));
    }
}
