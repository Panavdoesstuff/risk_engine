package riskengine.shard;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import riskengine.events.AccountControl;
import riskengine.events.Event;
import riskengine.events.LoggedEvent;
import riskengine.margin.AccountBook;
import riskengine.margin.EngineOutput;
import riskengine.margin.EngineOutputCodec;

import riskengine.margin.SquareOffLeg;
import riskengine.ref.Instrument;
import riskengine.shard.ShardState.SnapshotException;

class SnapshotTest {
    static final List<Instrument> INS = Fixtures.instruments();

    /** Thinly funded random books, so the stream squares accounts off and leaves legs in flight. */
    static List<AccountBook> books() {
        return Fixtures.books(600, 71).stream()
                .map(b -> new AccountBook(b.id(), b.cash().divide(BigDecimal.valueOf(4), 2, RoundingMode.DOWN), b.collateral(), b.positions())).toList();
    }

    static List<Event> events(List<AccountBook> books) {
        List<Event> events = new ArrayList<>(Fixtures.events(books, 40, 25, 72));
        // Risk-ops too: a block, an unblock and a manual square-off somewhere in the middle.
        events.add(events.size() / 3, new AccountControl(books.get(5).id(), AccountControl.Action.BLOCK, "risk-ops:t", "r1", Fixtures.T0));
        events.add(events.size() / 2, new AccountControl(books.get(9).id(), AccountControl.Action.SQUARE_OFF, "risk-ops:t", "r2", Fixtures.T0));
        events.add(2 * events.size() / 3, new AccountControl(books.get(5).id(), AccountControl.Action.UNBLOCK, "risk-ops:t", "r3", Fixtures.T0));
        return events;
    }

    static List<String> apply(ShardState s, List<Event> events, int from, int to) {
        List<String> out = new ArrayList<>();
        for (int i = from; i < to; i++) for (EngineOutput o : s.apply(new LoggedEvent(i, events.get(i)))) out.add(EngineOutputCodec.encode(o));
        return out;
    }

    static ShardState fresh(List<AccountBook> books) {
        return new ShardState(0, Fixtures.CFG, ShardConfig.defaults(), INS, books);
    }

    @Test
    void aRestoredShardCarriesOnExactlyLikeOneThatNeverStopped() throws Exception {
        List<AccountBook> books = books();
        List<Event> events = events(books);
        ShardState uninterrupted = fresh(books);
        List<String> expected = apply(uninterrupted, events, 0, events.size());
        assertTrue(expected.stream().anyMatch(l -> l.startsWith("LEG|")) && expected.stream().anyMatch(l -> l.startsWith("EPISODE|")),
                "the stream squares accounts off, so episodes and legs in flight are part of what is snapshotted");

        int cuts = 0;
        for (int cut : new int[] {0, 1, events.size() / 5, events.size() / 2, events.size() * 4 / 5, events.size() - 1, events.size()}) {
            ShardState before = fresh(books);
            List<String> head = apply(before, events, 0, cut);
            byte[] snap = before.snapshot();
            ShardState.SnapshotInfo info = ShardState.snapshotInfo(snap);
            assertEquals(cut, info.nextOffset());

            ShardState restored = ShardState.restore(snap, Fixtures.CFG, ShardConfig.defaults(), INS, books);
            assertEquals(before.stateHash(), restored.stateHash(), "cut at " + cut + ": same state, bit for bit");
            assertArrayEquals(snap, restored.snapshot(), "and it snapshots to the same bytes");
            List<String> tail = apply(restored, events, cut, events.size());
            List<String> all = new ArrayList<>(head);
            all.addAll(tail);
            assertEquals(expected, all, "cut at " + cut + ": every output after the restore is what the uninterrupted shard said");
            assertEquals(uninterrupted.stateHash(), restored.stateHash(), "cut at " + cut);
            cuts++;
        }
        assertEquals(7, cuts);
    }

    @Test
    void theSameStateAlwaysGivesTheSameBytes() {
        List<AccountBook> books = books();
        List<Event> events = events(books);
        ShardState a = fresh(books), b = fresh(books);
        apply(a, events, 0, events.size() / 2);
        apply(b, events, 0, events.size() / 2);
        assertArrayEquals(a.snapshot(), b.snapshot());
    }

    @Test
    void aSnapshotThatIsDamagedOrDoesNotFitIsRefused() throws Exception {
        List<AccountBook> books = books();
        List<Event> events = events(books);
        ShardState s = fresh(books);
        apply(s, events, 0, events.size() / 2);
        byte[] snap = s.snapshot();

        byte[] flipped = snap.clone();
        flipped[flipped.length / 2] ^= 1;
        assertTrue(refused(flipped, ShardConfig.defaults(), books).contains("checksum"));
        assertTrue(refused(Arrays.copyOf(snap, snap.length - 100), ShardConfig.defaults(), books).contains("checksum"), "a file cut short (crash while writing)");
        assertTrue(refused(new byte[10], ShardConfig.defaults(), books).contains("too short"));
        assertTrue(refused(snap, new ShardConfig(50, 0.01), books).contains("drift-check"), "taken with another K");
        assertTrue(refused(snap, ShardConfig.defaults(), books.subList(1, books.size())).contains("book has changed"));
        List<Instrument> fewer = INS.subList(0, 10);
        SnapshotException e = assertThrows(SnapshotException.class, () -> ShardState.restore(snap, Fixtures.CFG, ShardConfig.defaults(), fewer, books));
        assertTrue(e.getMessage().contains("not in the instruments file"), e.getMessage());
    }

    static String refused(byte[] snap, ShardConfig cfg, List<AccountBook> books) {
        return assertThrows(SnapshotException.class, () -> ShardState.restore(snap, Fixtures.CFG, cfg, INS, books)).getMessage();
    }

    @Test
    void episodesAndLegsInFlightSurviveARestore() throws Exception {
        List<AccountBook> books = books();
        List<Event> events = events(books);
        ShardState s = fresh(books);
        int i = 0;
        String accountWithLeg = null;
        for (; i < events.size() && accountWithLeg == null; i++) {
            for (EngineOutput o : s.apply(new LoggedEvent(i, events.get(i)))) {
                if (o instanceof SquareOffLeg l) accountWithLeg = l.accountId();
            }
        }
        assertNotNull(accountWithLeg);
        ShardState restored = ShardState.restore(s.snapshot(), Fixtures.CFG, ShardConfig.defaults(), INS, books);
        assertEquals(s.inFlight(accountWithLeg), restored.inFlight(accountWithLeg));
        assertNotNull(restored.inFlight(accountWithLeg));
        assertEquals(s.riskFingerprint(accountWithLeg), restored.riskFingerprint(accountWithLeg));
        assertEquals(s.pendingOrders(accountWithLeg), restored.pendingOrders(accountWithLeg));
    }
}
