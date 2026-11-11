package riskengine.shard;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import riskengine.events.AccountControl;
import riskengine.events.Fill;
import riskengine.events.OrderClosed;
import riskengine.events.OrderRequest.Side;
import riskengine.margin.AccountBook;
import riskengine.margin.AccountRisk;
import riskengine.margin.AccountRisk.Status;
import riskengine.margin.AccountStatusChange;
import riskengine.margin.AccountView;
import riskengine.margin.EngineOutput;
import riskengine.margin.EpisodeChange;
import riskengine.margin.LegPlanner;
import riskengine.margin.MarginEvent;
import riskengine.margin.MarginLevel;
import riskengine.margin.MarginResult;
import riskengine.margin.OrderClosure;
import riskengine.margin.OrderDecision;
import riskengine.margin.OrderDecision.Reason;
import riskengine.margin.Position;
import riskengine.margin.PositionChange;
import riskengine.margin.SquareOffLeg;
import riskengine.margin.Thresholds;
import riskengine.ref.Instrument;
import riskengine.shard.PreTradeCheckTest.Both;

/**
 * The status state machine and the square-off episode. As in PreTradeCheckTest, every event goes through
 * the shard and the reference engine, and the helper insists that they say exactly the same thing.
 */
class SquareOffTest {
    static final Instrument FUT = PreTradeCheckTest.FUT;
    static final Instrument CALL = PreTradeCheckTest.CALL;

    static AccountBook account(String id, String cash, Object... positions) {
        TreeMap<String, Position> p = new TreeMap<>();
        for (int i = 0; i < positions.length; i += 3) p.put(((Instrument) positions[i]).id(), Position.of((Long) positions[i + 1], (String) positions[i + 2]));
        return new AccountBook(id, new BigDecimal(cash), BigDecimal.ZERO, p);
    }

    static <T extends EngineOutput> List<T> only(List<EngineOutput> out, Class<T> type) {
        List<T> r = new ArrayList<>();
        for (EngineOutput o : out) if (type.isInstance(o)) r.add(type.cast(o));
        return r;
    }

    static List<EngineOutput> tick(Both b, long seq, String spot) {
        return b.apply(new riskengine.events.Tick(seq, Fixtures.T0 + seq * 60_000, "NIFTY", new BigDecimal(spot), new BigDecimal("20")));
    }

    static List<EngineOutput> fill(Both b, String fillId, SquareOffLeg leg, long qty) {
        return b.apply(new Fill(fillId, leg.legKey(), leg.accountId(), leg.instrumentId(), qty, leg.mark(), Fixtures.T0));
    }

    /**
     * Short 10 lots of futures on 8 lakh. The index rises: 83% at 23,300 (warning), about 101% at 23,800
     * (margin call after three ticks), about 127% at 24,300 (square-off after three more).
     */
    static Both shortFuturesIntoSquareOff() {
        Both b = new Both(account("A", "800000", FUT, -250L, "23300"));
        assertEquals(MarginLevel.WARNING, only(tick(b, 1, "23300"), MarginEvent.class).get(0).to());
        tick(b, 2, "23800");
        tick(b, 3, "23800");
        List<EngineOutput> call = tick(b, 4, "23800");
        assertEquals(MarginLevel.MARGIN_CALL, only(call, MarginEvent.class).get(0).to());
        assertEquals(Status.MARGIN_CALL, only(call, AccountStatusChange.class).get(0).to());
        assertTrue(tick(b, 5, "24300").isEmpty(), "over 120%, but one tick is not enough");
        assertTrue(tick(b, 6, "24300").isEmpty());
        return b;
    }

    @Test
    void threeTicksOverTheThresholdOpenAnEpisodeAndSendExactlyOneLeg() {
        Both b = shortFuturesIntoSquareOff();
        List<EngineOutput> out = tick(b, 7, "24300");

        assertEquals(MarginLevel.SQUARE_OFF, only(out, MarginEvent.class).get(0).to());
        EpisodeChange opened = only(out, EpisodeChange.class).get(0);
        assertEquals(EpisodeChange.State.OPENED, opened.state());
        assertEquals(AccountRisk.Mode.AUTO, opened.mode());
        assertEquals("E1", opened.episodeId());
        SquareOffLeg leg = only(out, SquareOffLeg.class).get(0);
        assertEquals("A:E1:" + FUT.id() + ":1", leg.legKey(), "accountId:episodeId:instrumentId:seq");
        assertTrue(leg.qty() > 0 && leg.qty() % 25 == 0 && leg.qty() < 250, "buys back some whole lots, not the lot: " + leg);
        assertTrue(leg.expectedRelease() > 0);
        assertEquals(Status.SQUARING_OFF, only(out, AccountStatusChange.class).get(0).to());
        assertEquals(1, only(out, SquareOffLeg.class).size());
        // The leg is recorded as in flight in the same step that emits it.
        assertEquals(leg.legKey(), b.shard.inFlight("A").legKey());
        assertEquals(leg.qty(), b.shard.inFlight("A").remaining());

        // The market keeps going against the account, and nothing more is sent while that leg is out.
        for (int t = 8; t <= 20; t++) assertTrue(tick(b, t, "24" + (300 + 10 * t)).isEmpty(), "tick " + t + ": no second leg, no level flicker");
        assertEquals(Status.SQUARING_OFF, b.shard.status("A"));
        assertEquals(MarginLevel.SQUARE_OFF, b.shard.level("A"), "the level is frozen while the episode is open");
    }

    @Test
    void theLegIsSizedToGetBackUnderTheLimitAndItsFillEndsTheEpisode() {
        Both b = shortFuturesIntoSquareOff();
        SquareOffLeg leg = only(tick(b, 7, "24300"), SquareOffLeg.class).get(0);

        // Part of it fills: still in flight, nothing new.
        List<EngineOutput> part = fill(b, "BF-1", leg, 25);
        assertEquals(leg.qty() - 25, ((PositionChange) part.get(0)).orderRemaining());
        assertEquals(1, part.size(), "a partial fill does not plan another leg");
        assertEquals(leg.qty() - 25, b.shard.inFlight("A").remaining());
        // The broker calls back twice with the same fill: ignored, the position is not closed twice.
        assertTrue(fill(b, "BF-1", leg, 25).isEmpty());
        assertEquals(-225, b.shard.positions("A").get(FUT.id()).qty());

        // The rest fills. The account is back under 100%, so the episode ends there and then, on the
        // fill, without waiting for a tick; the level drops straight away.
        List<EngineOutput> done = fill(b, "BF-2", leg, leg.qty() - 25);
        assertEquals(0, ((PositionChange) done.get(0)).orderRemaining());
        EpisodeChange ended = only(done, EpisodeChange.class).get(0);
        assertEquals(EpisodeChange.State.RESOLVED, ended.state());
        assertEquals(1, ended.legs());
        assertTrue(only(done, SquareOffLeg.class).isEmpty(), "one leg was enough, as planned");
        MarginEvent lowered = only(done, MarginEvent.class).get(0);
        assertEquals(MarginLevel.SQUARE_OFF, lowered.from());
        assertTrue(lowered.to().compareTo(MarginLevel.MARGIN_CALL) < 0, "a fill may lower the level: " + lowered);
        assertTrue(lowered.result().utilisation() < 1.0);
        assertEquals(Status.ACTIVE, only(done, AccountStatusChange.class).get(0).to());
        assertNull(b.shard.inFlight("A"));
        assertEquals(-250 + leg.qty(), b.shard.positions("A").get(FUT.id()).qty(), "closed exactly what the leg asked for");

        // A late duplicate of the last fill, after the episode: still ignored.
        assertTrue(fill(b, "BF-2", leg, leg.qty() - 25).isEmpty());
        // And the account can trade again.
        tick(b, 21, "24300");
        assertNotEquals(Reason.SQUARING_OFF, b.order("A", FUT, Side.BUY, 25).reason());
    }

    @Test
    void aRejectedLegIsDroppedAndPlannedAgainWithTheNextSeq() {
        Both b = shortFuturesIntoSquareOff();
        SquareOffLeg first = only(tick(b, 7, "24300"), SquareOffLeg.class).get(0);

        List<EngineOutput> out = b.apply(new OrderClosed(first.legKey(), "A", OrderClosed.Reason.REJECTED, Fixtures.T0));
        assertEquals(first.qty(), ((OrderClosure) out.get(0)).released(), "what was in flight is reported as released");
        SquareOffLeg second = only(out, SquareOffLeg.class).get(0);
        assertEquals("A:E1:" + FUT.id() + ":2", second.legKey(), "same episode, seq + 1: a new key, so the broker treats it as a new order");
        assertEquals(first.qty(), second.qty());
        assertEquals(second.legKey(), b.shard.inFlight("A").legKey());

        // A fill that still turns up for the rejected leg changes the position (it happened) but is not
        // the in-flight leg's, so the episode does not count it as progress.
        List<EngineOutput> stray = fill(b, "BF-stray", first, 25);
        assertEquals(PositionChange.NOT_TRACKED, ((PositionChange) stray.get(0)).orderRemaining());
        assertEquals(second.qty(), b.shard.inFlight("A").remaining());
        // Rejecting the old key again does nothing.
        assertEquals(1, b.apply(new OrderClosed(first.legKey(), "A", OrderClosed.Reason.REJECTED, Fixtures.T0)).size());
    }

    @Test
    void shortOptionsAreClosedBeforeFuturesAndTheEpisodeGoesLegByLegUntilItIsUnderTheLimit() {
        // Short calls and short futures together, on far too little cash.
        Both b = new Both(account("A", "150000", CALL, -100L, "250", FUT, -100L, "23300"));
        List<SquareOffLeg> legs = new ArrayList<>();
        List<EngineOutput> out = new ArrayList<>();
        for (int t = 1; t <= 3; t++) out = tick(b, t, "23300");
        assertEquals(EpisodeChange.State.OPENED, only(out, EpisodeChange.class).get(0).state(), "over 120% from the first tick, so tick 3 opens it");
        int fills = 0;
        while (!only(out, SquareOffLeg.class).isEmpty()) {
            SquareOffLeg leg = only(out, SquareOffLeg.class).get(0);
            legs.add(leg);
            assertEquals(legs.size(), leg.seq());
            out = fill(b, "BF-" + (++fills), leg, leg.qty());
        }
        assertEquals(CALL.id(), legs.get(0).instrumentId(), "the short option goes first");
        assertEquals(100, legs.get(0).qty(), "all of it, because even that is not enough");
        assertEquals(FUT.id(), legs.get(1).instrumentId(), "then the future");
        assertEquals(2, legs.size());
        EpisodeChange ended = only(out, EpisodeChange.class).get(0);
        assertEquals(EpisodeChange.State.RESOLVED, ended.state());
        assertEquals(2, ended.legs());
        assertTrue(b.shard.latest("A").utilisation() < 1.0, b.shard.latest("A").toString());
        assertFalse(b.shard.positions("A").containsKey(CALL.id()));
    }

    @Test
    void whileBeingSquaredOffAnAccountCannotOrderAndItsReservationsAreCancelled() {
        Both b = new Both(account("A", "800000", FUT, -250L, "23300"));
        tick(b, 1, "23300");
        assertTrue(b.order("O-1", "A", FUT, Side.BUY, 25).accepted(), "reducing the short is allowed");
        for (int t = 2; t <= 4; t++) tick(b, t, "23800");
        tick(b, 5, "24300");
        tick(b, 6, "24300");
        List<EngineOutput> out = tick(b, 7, "24300");
        OrderClosure cancelled = only(out, OrderClosure.class).get(0);
        assertEquals("O-1", cancelled.orderId());
        assertEquals(OrderClosed.Reason.CANCELLED, cancelled.reason());
        assertEquals(25, cancelled.released());
        assertTrue(b.shard.pendingOrders("A").isEmpty());
        OrderDecision refused = b.order("A", FUT, Side.BUY, 25);
        assertEquals(Reason.SQUARING_OFF, refused.reason(), "even a risk-reducing order: the engine is doing the reducing now");
    }

    @Test
    void aFillMayLowerALevelButNeverRaiseIt() {
        Both b = shortFuturesIntoSquareOff(); // at MARGIN_CALL, two ticks over 120%
        // A fill that makes things worse: sells five more lots. Utilisation is now far over 120%.
        List<EngineOutput> worse = b.apply(new Fill("X-1", "X", "A", FUT.id(), -125, new BigDecimal("24300"), Fixtures.T0));
        assertEquals(1, worse.size(), "no level change, no episode: raising takes ticks");
        assertEquals(MarginLevel.MARGIN_CALL, b.shard.level("A"));
        assertTrue(b.shard.latest("A").utilisation() > 1.5);
        // A fill that closes most of the short: under 80% at once.
        List<EngineOutput> better = b.apply(new Fill("X-2", "X", "A", FUT.id(), 325, new BigDecimal("24300"), Fixtures.T0));
        MarginEvent lowered = only(better, MarginEvent.class).get(0);
        assertEquals(MarginLevel.MARGIN_CALL, lowered.from());
        assertEquals(MarginLevel.OK, lowered.to());
        assertEquals(Status.ACTIVE, only(better, AccountStatusChange.class).get(0).to());
        // The streak towards square-off was reset too: one more tick over 120% would otherwise have fired.
        assertTrue(only(tick(b, 7, "24300"), EpisodeChange.class).isEmpty());
    }

    @Test
    void riskOpsCanBlockUnblockAndSquareOffByHand() {
        Both b = new Both(account("A", "5000000", CALL, -50L, "250", FUT, 25L, "23300"), account("B", "5000000"));
        tick(b, 1, "23300");
        assertTrue(b.order("A", CALL, Side.SELL, 25).accepted());

        List<EngineOutput> blocked = b.apply(new AccountControl("A", AccountControl.Action.BLOCK, "risk-ops:bob", "req-1", Fixtures.T0));
        AccountStatusChange s = (AccountStatusChange) blocked.get(0);
        assertEquals(Status.BLOCKED, s.to());
        assertEquals("BLOCKED_BY_RISK_OPS", s.cause());
        assertEquals("risk-ops:bob", s.actor(), "who did it travels with the event, for the audit log");
        assertEquals(Reason.ACCOUNT_BLOCKED, b.order("A", CALL, Side.BUY, 25).reason(), "no orders at all while blocked");
        assertTrue(b.apply(new AccountControl("A", AccountControl.Action.BLOCK, "risk-ops:bob", "req-2", Fixtures.T0)).isEmpty(), "already blocked: nothing changes");
        assertTrue(tick(b, 2, "23300").isEmpty(), "a blocked account is still margined, quietly");

        List<EngineOutput> unblocked = b.apply(new AccountControl("A", AccountControl.Action.UNBLOCK, "risk-ops:bob", "req-3", Fixtures.T0));
        assertEquals(Status.ACTIVE, ((AccountStatusChange) unblocked.get(0)).to());
        assertTrue(b.order("A", CALL, Side.BUY, 25).accepted());

        // Manual square-off of a perfectly healthy account: every position, one leg at a time, until flat.
        List<EngineOutput> out = b.apply(new AccountControl("A", AccountControl.Action.SQUARE_OFF, "risk-ops:bob", "req-4", Fixtures.T0));
        EpisodeChange opened = only(out, EpisodeChange.class).get(0);
        assertEquals(AccountRisk.Mode.MANUAL, opened.mode());
        assertEquals("risk-ops:bob", opened.actor());
        assertEquals(2, only(out, OrderClosure.class).size(), "both reservations cancelled");
        assertEquals("MANUAL_SQUARE_OFF", only(out, AccountStatusChange.class).get(0).cause());
        List<String> closed = new ArrayList<>();
        int n = 0;
        while (!only(out, SquareOffLeg.class).isEmpty()) {
            SquareOffLeg leg = only(out, SquareOffLeg.class).get(0);
            closed.add(leg.instrumentId() + " " + leg.qty());
            out = fill(b, "BF-" + (++n), leg, leg.qty());
        }
        assertEquals(List.of(CALL.id() + " 50", FUT.id() + " -25"), closed, "short option first, whole positions");
        assertEquals(EpisodeChange.State.RESOLVED, only(out, EpisodeChange.class).get(0).state());
        assertTrue(b.shard.positions("A").isEmpty());
        assertEquals(Status.ACTIVE, b.shard.status("A"));

        // Squaring off an account with nothing in it opens and resolves in one step; an unknown account is ignored.
        List<EngineOutput> flat = b.apply(new AccountControl("B", AccountControl.Action.SQUARE_OFF, "risk-ops:bob", "req-5", Fixtures.T0));
        assertEquals(List.of(EpisodeChange.State.OPENED, EpisodeChange.State.RESOLVED), only(flat, EpisodeChange.class).stream().map(EpisodeChange::state).toList());
        assertTrue(b.apply(new AccountControl("NOBODY", AccountControl.Action.BLOCK, "risk-ops:bob", "req-6", Fixtures.T0)).isEmpty());
    }

    @Test
    void aSecondEpisodeGetsANewIdSoItsLegKeysCanNeverCollideWithTheFirst() {
        Both b = shortFuturesIntoSquareOff();
        SquareOffLeg first = only(tick(b, 7, "24300"), SquareOffLeg.class).get(0);
        fill(b, "BF-1", first, first.qty());
        assertNull(b.shard.inFlight("A"));
        // The index keeps climbing; the account goes over again.
        List<EngineOutput> out = new ArrayList<>();
        for (int t = 8; t <= 30 && only(out, SquareOffLeg.class).isEmpty(); t++) out = tick(b, t, "25" + (100 + 40 * t));
        SquareOffLeg second = only(out, SquareOffLeg.class).get(0);
        assertEquals("A:E2:" + FUT.id() + ":1", second.legKey());
    }

    @Test
    void theStateHashCoversTheEpisodeAndTheLegInFlight() {
        Both a = shortFuturesIntoSquareOff(), b = shortFuturesIntoSquareOff();
        SquareOffLeg leg = only(tick(a, 7, "24300"), SquareOffLeg.class).get(0);
        tick(b, 7, "24300");
        assertEquals(a.shard.stateHash(), b.shard.stateHash());
        // Same positions and cash afterwards either way; what differs is whether the leg is still in flight.
        a.apply(new OrderClosed(leg.legKey(), "A", OrderClosed.Reason.REJECTED, Fixtures.T0));
        b.apply(new OrderClosed("something-else", "A", OrderClosed.Reason.REJECTED, Fixtures.T0));
        assertNotEquals(a.shard.stateHash(), b.shard.stateHash());
    }

    @Test
    void dustLeftBehindByAClosedShortIsNotAMarginRequirement() {
        // Short calls plus a long call, on negative cash. Buy the shorts back: what is left needs no margin.
        // The shard's vector is adjusted incrementally and may hold 1e-10 where the short used to be;
        // "required > 0, available < 0" would then read as infinite utilisation.
        Instrument longCall = Fixtures.instruments().stream().filter(i -> i.id().equals("NIFTY-20240613-24000-CE")).findFirst().orElseThrow();
        Both b = new Both(account("A", "-50000", CALL, -175L, "250.37", longCall, 75L, "41.13"));
        tick(b, 1, "23317.35");
        tick(b, 2, "23291.80");
        // (Two ticks only: a third over the limit would start a square-off, which is not what this is about.)
        b.apply(new Fill("X-1", "X", "A", CALL.id(), 75, new BigDecimal("251.05"), Fixtures.T0));
        b.apply(new Fill("X-2", "X", "A", CALL.id(), 100, new BigDecimal("249.95"), Fixtures.T0));
        assertEquals(0.0, b.shard.latest("A").required(), 0.0, "exactly zero, not dust: " + b.shard.latest("A"));
        assertEquals(0.0, b.shard.latest("A").utilisation(), 0.0);
        assertTrue(b.shard.latest("A").available() < 0);
        for (int t = 3; t <= 8; t++) {
            tick(b, t, "233" + (10 + 7 * t) + ".45");
            assertEquals(0.0, b.shard.latest("A").utilisation(), 0.0, "tick " + t + ": " + b.shard.latest("A"));
        }
        assertEquals(MarginLevel.OK, b.shard.level("A"), "a long-only account is never called, whatever its cash");
    }

    // ---------------------------------------------------------------- the planner and the state machine on their own

    /** An account whose margin is whatever the test says: required = sum of |qty| x a per-unit figure. */
    static class FakeAccount implements AccountView {
        final TreeMap<String, Position> positions = new TreeMap<>();
        final TreeMap<String, Double> requiredPerUnit = new TreeMap<>();
        double available;

        @Override
        public SortedMap<String, Position> positions() {
            return positions;
        }

        @Override
        public Instrument instrument(String id) {
            return Fixtures.instruments().stream().filter(i -> i.id().equals(id)).findFirst().orElseThrow();
        }

        @Override
        public MarginResult margin(SortedMap<String, Long> deltas) {
            double required = 0;
            for (var e : positions.entrySet()) {
                long qty = e.getValue().qty() + deltas.getOrDefault(e.getKey(), 0L);
                required += Math.abs(qty) * requiredPerUnit.get(e.getKey());
            }
            double util = required <= 0 ? 0 : available <= 0 ? Double.POSITIVE_INFINITY : required / available;
            return new MarginResult(0, 0, 0, 0, 0, 0, 0, required, 0, available, util);
        }

        @Override
        public BigDecimal mark(String id) {
            return BigDecimal.TEN;
        }

        @Override
        public void cancelReservations(List<EngineOutput> out, long time) {}
    }

    @Test
    void thePlannerPicksTheMostMarginPerLotWithinAClassAndTheFewestLotsThatAreEnough() {
        Instrument put = Fixtures.instruments().stream().filter(i -> i.id().equals("NIFTY-20240613-23300-PE")).findFirst().orElseThrow();
        Instrument longCall = Fixtures.instruments().stream().filter(i -> i.id().equals("NIFTY-20240613-24000-CE")).findFirst().orElseThrow();
        FakeAccount a = new FakeAccount();
        a.positions.put(CALL.id(), Position.of(-250, "1"));
        a.requiredPerUnit.put(CALL.id(), 100.0);
        a.positions.put(put.id(), Position.of(-250, "1"));
        a.requiredPerUnit.put(put.id(), 300.0);
        a.positions.put(FUT.id(), Position.of(250, "1"));
        a.requiredPerUnit.put(FUT.id(), 900.0); // frees the most per lot, but it is a future: after the short options
        a.positions.put(longCall.id(), Position.of(250, "1"));
        a.requiredPerUnit.put(longCall.id(), 0.0);
        a.available = 280_000; // required 325,000: 116%

        LegPlanner.Leg leg = LegPlanner.plan(a, AccountRisk.Mode.AUTO, 1.0);
        assertEquals(put.id(), leg.instrumentId(), "of the two short options, the one that frees more per lot");
        // Each lot of the put frees 7,500. 325,000 - k x 7,500 must be under 280,000: k = 7 lots.
        assertEquals(175, leg.qty());
        assertEquals(52_500, leg.expectedRelease(), 1e-6);

        assertEquals(250, LegPlanner.plan(a, AccountRisk.Mode.MANUAL, 1.0).qty(), "by hand: the whole position");
        // Only the long call left: closing it frees nothing, so there is no leg in AUTO, and there is one in MANUAL.
        a.positions.keySet().retainAll(List.of(longCall.id()));
        assertNull(LegPlanner.plan(a, AccountRisk.Mode.AUTO, 1.0));
        assertEquals(-250, LegPlanner.plan(a, AccountRisk.Mode.MANUAL, 1.0).qty());
    }

    @Test
    void anEpisodeWithNothingUsefulLeftToCloseEndsExhaustedAndBlocksTheAccount() {
        // Over the limit, and the only position frees no margin when closed.
        Instrument longCall = Fixtures.instruments().stream().filter(i -> i.id().equals("NIFTY-20240613-24000-CE")).findFirst().orElseThrow();
        FakeAccount a = new FakeAccount() {
            @Override
            public MarginResult margin(SortedMap<String, Long> deltas) {
                return new MarginResult(0, 0, 0, 0, 0, 0, 0, 150_000, 0, 100_000, 1.5); // a debt that closing cannot cure
            }
        };
        a.positions.put(longCall.id(), Position.of(250, "1"));
        AccountRisk risk = new AccountRisk("A", new Thresholds(0.8, 1.0, 1.2, 3));
        List<EngineOutput> out = new ArrayList<>();
        for (int t = 1; t <= 3; t++) risk.onTick(a, a.margin(new TreeMap<>()), t, t, out);
        assertEquals(List.of(EpisodeChange.State.OPENED, EpisodeChange.State.EXHAUSTED), only(out, EpisodeChange.class).stream().map(EpisodeChange::state).toList());
        assertTrue(only(out, SquareOffLeg.class).isEmpty());
        AccountStatusChange last = only(out, AccountStatusChange.class).get(only(out, AccountStatusChange.class).size() - 1);
        assertEquals(Status.BLOCKED, last.to());
        assertEquals("SQUARE_OFF_EXHAUSTED", last.cause());
        assertEquals(Reason.ACCOUNT_BLOCKED, risk.orderGate());

        // It does not open a new episode on every following tick.
        out.clear();
        for (int t = 4; t <= 10; t++) risk.onTick(a, a.margin(new TreeMap<>()), t, t, out);
        assertTrue(out.isEmpty(), out.toString());
        // Risk-ops unblocks: the account is still over the limit, so it is a margin call again, and the
        // engine will try once more.
        risk.onControl(new AccountControl("A", AccountControl.Action.UNBLOCK, "risk-ops:bob", "r", 11), a, 10, out);
        assertEquals(Status.MARGIN_CALL, only(out, AccountStatusChange.class).get(0).to());
    }
}
