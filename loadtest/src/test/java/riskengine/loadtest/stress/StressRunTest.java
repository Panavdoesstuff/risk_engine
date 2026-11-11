package riskengine.loadtest.stress;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import riskengine.events.Event;
import riskengine.events.Fill;

/** A small version of the M4 gate run on every build. The full 1M-event run is `./gradlew :loadtest:stress`. */
class StressRunTest {
    static final StressConfig SMALL = new StressConfig(80_000, 600, 400, 8, 80, 4, 6, 7L, null);

    @Test
    void concurrentWritersOnHotAccountsMatchTheSingleThreadedReference() throws Exception {
        StressReport r = StressRun.run(SMALL);
        System.out.println(r.summary());
        assertEquals(0, r.mismatches(), r.summary());
        assertTrue(r.records() >= 80_000, "80,000 written by the writers, plus the leg fills: " + r.records());
        assertTrue(r.episodes() > 20 && r.legs() > r.episodes(), "square-off under the same race: " + r.episodes() + " episodes, " + r.legs() + " legs");
        assertTrue(r.decisions().containsKey("SQUARING_OFF") && r.decisions().containsKey("ACCOUNT_BLOCKED"), r.decisions().toString());
        // The comparison only means something if the run exercised everything.
        assertTrue(r.decisions().getOrDefault("OK", 0L) > 1_000, r.decisions().toString());
        assertTrue(r.decisions().getOrDefault("INSUFFICIENT_MARGIN", 0L) > 100, r.decisions().toString());
        assertTrue(r.decisions().containsKey("BAD_LOT_SIZE") && r.decisions().containsKey("UNKNOWN_ACCOUNT") && r.decisions().containsKey("UNKNOWN_INSTRUMENT"),
                r.decisions().toString());
        assertTrue(r.levelChanges() > 100, "level changes: " + r.levelChanges());
        assertTrue(r.releases() > 100, "reservations released by a close: " + r.releases());
        assertTrue(r.duplicateFills() > 100 && r.duplicateOrders() > 100, r.duplicateFills() + " / " + r.duplicateOrders());
        assertTrue(r.byType().keySet().containsAll(List.of("Tick", "OrderRequest", "Fill", "OrderClosed", "AccountControl", "SquareOffResult")), r.byType().toString());
    }

    @Test
    void theHarnessNoticesWhenTheReferenceSeesSomethingDifferent() throws Exception {
        // Hide one fill from the reference (and nothing else). If the comparison is real, it must show up
        // both in the outputs and in that account's final state.
        StressReport r = StressRun.run(SMALL.withEvents(20_000, 150), recorded -> {
            List<List<Event>> copy = new ArrayList<>();
            boolean dropped = false;
            for (List<Event> partition : recorded) {
                List<Event> events = new ArrayList<>(partition);
                for (int i = events.size() / 2; i < events.size() && !dropped; i++) {
                    if (events.get(i) instanceof Fill f && f.orderId().startsWith("X-")) {
                        events.remove(i);
                        dropped = true;
                    }
                }
                copy.add(events);
            }
            assertTrue(dropped);
            return copy;
        });
        assertTrue(r.outputMismatches() > 0 && r.stateMismatches() > 0, r.summary());
        assertEquals(0, r.replayMismatches(), "the shards themselves were not tampered with");
    }
}
