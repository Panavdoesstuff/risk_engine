package riskengine.shard;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import riskengine.events.Event;
import riskengine.events.Fill;
import riskengine.events.LoggedEvent;
import riskengine.events.OrderRequest;
import riskengine.events.Tick;
import riskengine.margin.AccountBook;
import riskengine.margin.EngineOutput;
import riskengine.margin.SeenIds;
import riskengine.shard.PreTradeCheckTest.Both;

/** Seen fill and order ids are forgotten after two days of log time: deterministically, identically in both engines. */
class DedupeWindowTest {
    static final long HOUR = 60 * 60 * 1000L, DAY = 24 * HOUR;
    static final String FUT = Fixtures.instruments().get(0).id();

    static Tick tick(long seq, long time) {
        return new Tick(seq, time, "NIFTY", new BigDecimal("23300"), new BigDecimal("20"));
    }

    static Fill fill(String id, long time) {
        return new Fill(id, "O-" + id, "A", FUT, 25, new BigDecimal("23300"), time);
    }

    static Both both() {
        return new Both(new AccountBook("A", new BigDecimal("50000000"), BigDecimal.ZERO, new TreeMap<>()));
    }

    @Test
    void aDuplicateInsideTheWindowIsIgnoredAndTheSameIdAfterItIsAppliedAgain() {
        Both b = both();
        long t0 = Fixtures.T0;
        b.apply(tick(1, t0));
        assertEquals(1, b.apply(fill("F1", t0)).size(), "applied");
        assertEquals(Map.of("F1", t0), b.shard.seenFillIds(), "remembered at the latest tick's log time");

        b.apply(tick(2, t0 + DAY + 23 * HOUR));                       // 1 day 23 hours later
        assertTrue(b.apply(fill("F1", t0)).isEmpty(), "inside the window: still a duplicate");
        assertEquals(25, b.shard.positions("A").get(FUT).qty());

        b.apply(tick(3, t0 + 2 * DAY + 1));                           // just past two days
        assertTrue(b.shard.seenFillIds().isEmpty(), "forgotten on the tick that moved the window past it");
        assertEquals(b.reference.seenFillIds(), b.shard.seenFillIds(), "and the reference engine forgot it at the same tick");
        List<EngineOutput> again = b.apply(fill("F1", t0));
        assertEquals(1, again.size(), "a 'duplicate' after the window is applied again: the window has to be longer than any real duplicate");
        assertEquals(50, b.shard.positions("A").get(FUT).qty());
    }

    @Test
    void orderIdsAreWindowedTooAndPruningIsExactlyAtTheCutoff() {
        Both b = both();
        long t0 = Fixtures.T0;
        b.apply(tick(1, t0));
        b.order("O-early", "A", Fixtures.instruments().get(0), OrderRequest.Side.BUY, 25);
        b.apply(tick(2, t0 + HOUR));
        b.order("O-later", "A", Fixtures.instruments().get(0), OrderRequest.Side.BUY, 25);
        // Exactly two days after the first: an id seen at t0 is kept while cutoff == t0, gone once cutoff > t0.
        b.apply(tick(3, t0 + 2 * DAY));
        assertEquals(List.of("O-early", "O-later"), List.copyOf(b.shard.seenOrderIds().keySet()));
        b.apply(tick(4, t0 + 2 * DAY + 1));
        assertEquals(List.of("O-later"), List.copyOf(b.shard.seenOrderIds().keySet()));
        b.apply(tick(5, t0 + 2 * DAY + HOUR + 1));
        assertTrue(b.shard.seenOrderIds().isEmpty());
    }

    @Test
    void theSeenTimesArePartOfTheStateHashAndOfSnapshots() throws Exception {
        long t0 = Fixtures.T0;
        // Same ids, different times seen: different state, because they will be forgotten at different times.
        Both early = both(), late = both();
        early.apply(tick(1, t0));
        early.apply(fill("F1", t0));
        late.apply(tick(1, t0));
        late.apply(fill("F1", t0)); // same as early so far
        assertEquals(early.shard.stateHash(), late.shard.stateHash());
        Both other = both();
        other.apply(tick(1, t0 + HOUR));
        other.apply(fill("F1", t0));
        assertNotEquals(early.shard.seenFillIds(), other.shard.seenFillIds());

        // A snapshot keeps the times: the restored shard forgets on the same tick as the original.
        ShardState restored = ShardState.restore(early.shard.snapshot(), Fixtures.CFG, ShardConfig.defaults(), Fixtures.instruments(),
                List.of(new AccountBook("A", new BigDecimal("50000000"), BigDecimal.ZERO, new TreeMap<>())));
        assertEquals(early.shard.seenFillIds(), restored.seenFillIds());
        Tick later = tick(2, t0 + 2 * DAY + 1);
        early.shard.apply(new LoggedEvent(early.offset, later));
        restored.apply(new LoggedEvent(early.offset, later));
        assertTrue(restored.seenFillIds().isEmpty());
        assertEquals(early.shard.stateHash(), restored.stateHash());

        // And a snapshot taken with a different window is refused.
        assertThrows(ShardState.SnapshotException.class, () -> ShardState.restore(early.shard.snapshot(), Fixtures.CFG,
                new ShardConfig(100, 0.01, DAY), Fixtures.instruments(), List.of(new AccountBook("A", new BigDecimal("50000000"), BigDecimal.ZERO, new TreeMap<>()))));
    }

    @Test
    void overDaysOfLogTheSetStaysBoundedAndReplaysAgree() {
        // Five days, a tick an hour, a fill and its duplicate every tick: without pruning 120 ids, with it ~49.
        List<Event> events = new ArrayList<>();
        long t0 = Fixtures.T0;
        for (int h = 0; h < 120; h++) {
            events.add(tick(h + 1, t0 + h * HOUR));
            events.add(fill("F" + h, t0 + h * HOUR));
            events.add(fill("F" + h, t0 + h * HOUR));
        }
        AccountBook book = new AccountBook("A", new BigDecimal("5000000000"), BigDecimal.ZERO, new TreeMap<>());
        String first = null;
        for (int run = 0; run < 2; run++) {
            ShardState s = new ShardState(0, Fixtures.CFG, ShardConfig.defaults(), Fixtures.instruments(), List.of(book));
            long o = 0;
            for (Event e : events) s.apply(new LoggedEvent(o++, e));
            assertEquals(49, s.seenFillIds().size(), "two days of hourly ids, plus the one on the cutoff");
            assertEquals(120, s.duplicateFills(), "every duplicate inside the window was still caught");
            if (first == null) first = s.stateHash();
            else assertEquals(first, s.stateHash());
        }
        assertEquals(2L * 24 * 60 * 60 * 1000, SeenIds.DEFAULT_WINDOW_MILLIS);
    }
}
