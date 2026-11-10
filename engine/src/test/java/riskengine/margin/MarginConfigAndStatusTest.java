package riskengine.margin;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Properties;
import org.junit.jupiter.api.Test;

class MarginConfigAndStatusTest {

    @Test
    void checkedInDefaultsLoadAndAreNiftyOnly() {
        MarginConfig c = MarginConfig.defaults();
        assertEquals(java.util.Set.of("NIFTY"), c.underlyings().keySet());
        assertEquals(0.80, c.thresholds().warning());
        assertEquals(1.00, c.thresholds().marginCall());
        assertEquals(1.20, c.thresholds().squareOff());
        assertThrows(IllegalArgumentException.class, () -> c.params("BANKNIFTY"));
    }

    @Test
    void typosAndMissingKeysAreErrors() throws Exception {
        Properties p = new Properties();
        try (var in = MarginConfig.class.getResourceAsStream("/riskengine/margin.properties")) {
            p.load(in);
        }
        Properties typo = (Properties) p.clone();
        typo.setProperty("NIFTY.priceScanRnage", "0.1");
        var e = assertThrows(IllegalArgumentException.class, () -> MarginConfig.load(typo));
        assertTrue(e.getMessage().contains("priceScanRnage"));

        Properties missing = (Properties) p.clone();
        missing.remove("NIFTY.exposureMargin");
        assertThrows(IllegalArgumentException.class, () -> MarginConfig.load(missing));

        Properties badOrder = (Properties) p.clone();
        badOrder.setProperty("thresholds.warning", "1.5");
        assertThrows(IllegalArgumentException.class, () -> MarginConfig.load(badOrder));
    }

    @Test
    void warningIsImmediateButCallsNeedConfirmation() {
        MarginStatusTracker t = new MarginStatusTracker(new Thresholds(0.80, 1.00, 1.20, 3));
        assertEquals(MarginLevel.OK, t.onReading(0.79));
        assertEquals(MarginLevel.WARNING, t.onReading(0.80));
        assertEquals(MarginLevel.WARNING, t.onReading(1.05)); // 1 tick over 100%
        assertEquals(MarginLevel.WARNING, t.onReading(1.30)); // 2
        assertEquals(MarginLevel.MARGIN_CALL, t.onReading(1.25)); // 3 over 100%, only 2 over 120%
        assertEquals(MarginLevel.SQUARE_OFF, t.onReading(1.21)); // 3 over 120%
        assertEquals(MarginLevel.SQUARE_OFF, t.onReading(1.20)); // threshold is inclusive
    }

    @Test
    void oneBadPrintDoesNotEscalateAndRecoveryIsImmediate() {
        MarginStatusTracker t = new MarginStatusTracker(new Thresholds(0.80, 1.00, 1.20, 3));
        t.onReading(1.5);
        t.onReading(1.5);
        assertEquals(MarginLevel.WARNING, t.onReading(0.9), "streak broken before confirmation");
        assertEquals(MarginLevel.WARNING, t.onReading(1.5));
        assertEquals(MarginLevel.WARNING, t.onReading(1.5));
        assertEquals(MarginLevel.SQUARE_OFF, t.onReading(1.5));
        assertEquals(MarginLevel.OK, t.onReading(0.5), "de-escalation is immediate (M5 makes square-off sticky)");
        assertEquals(MarginLevel.WARNING, t.onReading(Double.POSITIVE_INFINITY));
    }

    @Test
    void levelsMapToV2StatusesAndActions() {
        assertEquals("ACTIVE", MarginLevel.WARNING.accountStatus());
        assertEquals("SQUARING_OFF", MarginLevel.SQUARE_OFF.accountStatus());
        assertEquals("WARN", MarginLevel.WARNING.action());
        assertEquals("CLEAR", MarginLevel.OK.action());
    }
}
