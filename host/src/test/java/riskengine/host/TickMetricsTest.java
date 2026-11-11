package riskengine.host;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import riskengine.events.LoggedEvent;
import riskengine.events.Tick;
import riskengine.margin.AccountRisk;
import riskengine.margin.EpisodeChange;
import riskengine.margin.MarginEvent;
import riskengine.margin.MarginLevel;
import riskengine.margin.MarginResult;
import riskengine.margin.SquareOffLeg;

class TickMetricsTest {
    static final MarginResult R = new MarginResult(0, 0, 0, 0, 0, 0, 0, 1, 0, 1, 1);

    static MarginEvent level(MarginLevel from, MarginLevel to) {
        return new MarginEvent("A1", 1, 0, R, from, to, 1);
    }

    @Test
    void alertsSquareOffsAndTheLatestTickAreServed() {
        TickMetrics m = new TickMetrics(2, (shard, tick) -> TickMetrics.Source.UNKNOWN);
        m.activate(0);
        m.activate(1);
        m.output(0, level(MarginLevel.OK, MarginLevel.WARNING));
        m.output(0, level(MarginLevel.WARNING, MarginLevel.MARGIN_CALL));
        m.output(1, level(MarginLevel.OK, MarginLevel.WARNING));
        m.output(1, level(MarginLevel.MARGIN_CALL, MarginLevel.WARNING)); // a step down is not an alert
        m.output(1, level(MarginLevel.MARGIN_CALL, MarginLevel.SQUARE_OFF));
        m.output(1, new EpisodeChange("A1", "E1", AccountRisk.Mode.AUTO, EpisodeChange.State.OPENED, "engine", 0, 1, 0));
        m.output(1, new SquareOffLeg("A1:E1:X:1", "A1", "E1", "X", 1, 25, BigDecimal.TEN, 1, 1, 0));
        m.applied(0, new LoggedEvent(0, new Tick(2, 1_717_475_400_000L, "NIFTY", new BigDecimal("21294.10"), new BigDecimal("31.53"))));
        m.applied(1, new LoggedEvent(0, new Tick(1, 1_717_475_340_000L, "NIFTY", new BigDecimal("21400.00"), new BigDecimal("30.00"))));

        StringBuilder sb = new StringBuilder();
        m.write(sb);
        String text = sb.toString();
        assertTrue(text.contains("risk_margin_alerts_total{shard=\"0\",level=\"WARNING\"} 1"), text);
        assertTrue(text.contains("risk_margin_alerts_total{shard=\"0\",level=\"MARGIN_CALL\"} 1"));
        assertTrue(text.contains("risk_margin_alerts_total{shard=\"1\",level=\"WARNING\"} 1"));
        assertTrue(text.contains("risk_margin_alerts_total{shard=\"1\",level=\"SQUARE_OFF\"} 1"));
        assertTrue(text.contains("risk_square_off_episodes_total{shard=\"1\",state=\"OPENED\"} 1"));
        assertTrue(text.contains("risk_square_off_legs_total{shard=\"1\"} 1"));
        assertTrue(text.contains("risk_market_spot{underlying=\"NIFTY\"} 21294.10"), "the newest tick wins, whichever shard saw it last");
        assertTrue(text.contains("risk_market_vix{underlying=\"NIFTY\"} 31.53"));
        assertTrue(text.contains("risk_market_log_time_seconds 1717475400"));
    }
}
