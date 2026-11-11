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
import riskengine.events.OrderClosed;
import riskengine.events.OrderRequest;
import riskengine.events.OrderRequest.Side;
import riskengine.events.Tick;
import riskengine.margin.AccountBook;
import riskengine.margin.EngineOutput;
import riskengine.margin.EngineOutputCodec;
import riskengine.margin.MarginEvent;
import riskengine.margin.OrderClosure;
import riskengine.margin.OrderDecision;
import riskengine.margin.OrderDecision.Reason;
import riskengine.margin.Position;
import riskengine.margin.PreTradeCheck;
import riskengine.margin.ReferenceMarginEngine;
import riskengine.ref.Instrument;

/**
 * The pre-trade margin check, run through the shard AND the reference engine on every step. Each test
 * asserts the two agree, so a rule can't quietly exist in only one of them.
 */
class PreTradeCheckTest {
    static final List<Instrument> INS = Fixtures.instruments();
    static final Instrument FUT = INS.get(0);
    static final Instrument CALL = INS.stream().filter(i -> i.id().equals("NIFTY-20240613-23300-CE")).findFirst().orElseThrow();

    /** Feeds the same events to a shard and to the reference engine and insists they answer alike. */
    static final class Both {
        final ShardState shard;
        final ReferenceMarginEngine reference;
        long offset;
        long orderSeq;

        Both(AccountBook... books) {
            shard = new ShardState(0, Fixtures.CFG, ShardConfig.defaults(), INS, List.of(books));
            reference = new ReferenceMarginEngine(Fixtures.CFG, INS, List.of(books));
        }

        List<EngineOutput> apply(Event e) {
            List<EngineOutput> expected = reference.apply(e);
            List<EngineOutput> actual = shard.apply(new LoggedEvent(offset++, e));
            ShardStateTest.assertSameOutputs(expected, actual);
            return actual;
        }

        void tick(long seq, String spot) {
            apply(new Tick(seq, Fixtures.T0 + seq * 60_000, "NIFTY", new BigDecimal(spot), new BigDecimal("20")));
        }

        OrderDecision order(String id, String account, Instrument i, Side side, long qty) {
            List<EngineOutput> out = apply(new OrderRequest(id, account, i.id(), side, qty, OrderRequest.Origin.USER, Fixtures.T0));
            assertEquals(1, out.size(), "one decision per new order");
            return (OrderDecision) out.get(0);
        }

        OrderDecision order(String account, Instrument i, Side side, long qty) {
            return order("O" + (++orderSeq), account, i, side, qty);
        }

        void fill(String fillId, String orderId, String account, Instrument i, long qty, String price) {
            apply(new Fill(fillId, orderId, account, i.id(), qty, new BigDecimal(price), Fixtures.T0));
        }
    }

    static AccountBook account(String id, String cash) {
        return new AccountBook(id, new BigDecimal(cash), BigDecimal.ZERO, new TreeMap<>());
    }

    static AccountBook account(String id, String cash, Instrument i, long qty, String avg) {
        return new AccountBook(id, new BigDecimal(cash), BigDecimal.ZERO, new TreeMap<>(Map.of(i.id(), Position.of(qty, avg))));
    }

    @Test
    void anOrderTheAccountCanAffordIsAcceptedAndOneItCannotIsRejected() {
        Both b = new Both(account("RICH", "5000000"), account("POOR", "20000"));
        b.tick(1, "23300");

        OrderDecision ok = b.order("RICH", CALL, Side.SELL, 25);
        assertTrue(ok.accepted());
        assertEquals(Reason.OK, ok.reason());
        assertEquals(1, ok.lastTickSeq(), "says which tick's prices it used");
        assertTrue(ok.requiredAfter() > 0 && ok.utilisationAfter() < 1, ok.toString());

        // Selling one lot of an at-the-money call needs roughly a lakh of margin. 20,000 is not enough.
        OrderDecision no = b.order("POOR", CALL, Side.SELL, 25);
        assertFalse(no.accepted());
        assertEquals(Reason.INSUFFICIENT_MARGIN, no.reason());
        assertTrue(no.utilisationAfter() >= 1, no.toString());
        assertTrue(b.shard.pendingOrders("POOR").isEmpty(), "a rejected order reserves nothing");
        assertTrue(b.shard.positions("POOR").isEmpty(), "and a decision never changes positions; only fills do");
    }

    @Test
    void buyingOptionsNeedsNoMarginButDoesNeedTheCashForThePremium() {
        Both b = new Both(account("A", "3000"));
        b.tick(1, "23300");
        // One lot of this call costs several thousand rupees. A long option needs no margin (utilisation 0),
        // so without the cash rule this account could buy any number of them.
        OrderDecision no = b.order("A", CALL, Side.BUY, 25);
        assertFalse(no.accepted());
        assertEquals(0.0, no.requiredAfter(), 1e-9);
        assertTrue(no.availableAfter() < 0, "the premium is more than the cash: " + no);
    }

    @Test
    void acceptedOrdersReserveMarginSoTwoQuickOrdersCannotSpendTheSameMoney() {
        Both b = new Both(account("A", "110000"));
        b.tick(1, "23300");
        OrderDecision first = b.order("A", CALL, Side.SELL, 25);
        assertTrue(first.accepted());
        assertTrue(first.utilisationAfter() > 0.5, "one lot uses more than half of this account: " + first);
        // Nothing has filled, so positions are unchanged. Checked against positions alone, an identical
        // second order would pass exactly like the first. It is rejected because the first is reserved.
        OrderDecision second = b.order("A", CALL, Side.SELL, 25);
        assertFalse(second.accepted());
        assertEquals(Reason.INSUFFICIENT_MARGIN, second.reason());
        assertEquals(1, b.shard.pendingOrders("A").size());
    }

    @Test
    void fillsReleaseTheReservationTheyBelongTo() {
        Both b = new Both(account("A", "5000000"));
        b.tick(1, "23300");
        assertTrue(b.order("O-1", "A", CALL, Side.SELL, 75).accepted());
        assertEquals(-75, b.shard.pendingOrders("A").get("O-1").remaining());

        b.fill("F1", "O-1", "A", CALL, -25, "250");
        assertEquals(-50, b.shard.pendingOrders("A").get("O-1").remaining(), "partial fill: 50 still reserved");
        b.fill("F1", "O-1", "A", CALL, -25, "250"); // duplicate broker callback
        assertEquals(-50, b.shard.pendingOrders("A").get("O-1").remaining(), "a duplicate fill releases nothing twice");
        b.fill("F2", "SOMETHING-ELSE", "A", CALL, -25, "250");
        assertEquals(-50, b.shard.pendingOrders("A").get("O-1").remaining(), "a fill for another order leaves it alone");
        b.fill("F3", "O-1", "A", CALL, -50, "250");
        assertTrue(b.shard.pendingOrders("A").isEmpty(), "fully filled: nothing reserved");
        assertEquals(-100, b.shard.positions("A").get(CALL.id()).qty());
        assertEquals(b.reference.pendingOrders("A"), b.shard.pendingOrders("A"));
    }

    @Test
    void closingAnOrderReleasesWhatItStillHadReserved() {
        Both b = new Both(account("A", "110000"));
        b.tick(1, "23300");
        assertTrue(b.order("O-1", "A", CALL, Side.SELL, 25).accepted());
        assertFalse(b.order("O-2", "A", CALL, Side.SELL, 25).accepted(), "O-1 holds the margin");

        // The broker rejects O-1. Its reservation goes, and the same order now fits again.
        OrderClosure closed = (OrderClosure) b.apply(new OrderClosed("O-1", "A", OrderClosed.Reason.REJECTED, Fixtures.T0)).get(0);
        assertEquals(-25, closed.released());
        assertTrue(b.shard.pendingOrders("A").isEmpty());
        assertTrue(b.order("O-3", "A", CALL, Side.SELL, 25).accepted(), "the margin is free again");

        // Closing again, closing a rejected order, an unknown order or an unknown account: nothing to release.
        for (OrderClosed again : List.of(
                new OrderClosed("O-1", "A", OrderClosed.Reason.REJECTED, Fixtures.T0),
                new OrderClosed("O-2", "A", OrderClosed.Reason.CANCELLED, Fixtures.T0),
                new OrderClosed("O-never", "A", OrderClosed.Reason.UNFILLED, Fixtures.T0),
                new OrderClosed("O-3", "NOBODY", OrderClosed.Reason.CANCELLED, Fixtures.T0))) {
            assertEquals(0, ((OrderClosure) b.apply(again).get(0)).released(), again.toString());
        }
        assertEquals(1, b.shard.pendingOrders("A").size(), "O-3 is untouched");
    }

    @Test
    void closingAPartlyFilledOrderReleasesOnlyTheUnfilledPart() {
        Both b = new Both(account("A", "5000000"));
        b.tick(1, "23300");
        assertTrue(b.order("O-1", "A", CALL, Side.SELL, 75).accepted());
        b.fill("F1", "O-1", "A", CALL, -25, "250");
        OrderClosure closed = (OrderClosure) b.apply(new OrderClosed("O-1", "A", OrderClosed.Reason.UNFILLED, Fixtures.T0)).get(0);
        assertEquals(-50, closed.released());
        assertEquals(-25, b.shard.positions("A").get(CALL.id()).qty(), "what filled stays filled");
        // A late fill for the closed order still changes the position (the trade happened) and releases nothing.
        b.fill("F2", "O-1", "A", CALL, -25, "250");
        assertEquals(-50, b.shard.positions("A").get(CALL.id()).qty());
        assertTrue(b.shard.pendingOrders("A").isEmpty());
    }

    @Test
    void anAccountOverItsLimitMayStillReduceRiskButNotAddToIt() {
        // Short 10 lots of futures with far too little cash: utilisation is well over 100%.
        Both b = new Both(account("A", "100000", FUT, -250, "23300"));
        b.tick(1, "23300");
        assertTrue(b.shard.latest("A").utilisation() > 1.2, "starts over the limit: " + b.shard.latest("A"));

        OrderDecision add = b.order("A", FUT, Side.SELL, 25);
        assertFalse(add.accepted(), "selling more makes it worse");
        OrderDecision reduce = b.order("A", FUT, Side.BUY, 100);
        assertTrue(reduce.accepted(), "buying back 4 of the 10 lots reduces the shortfall, though it is still over 100%: " + reduce);
        assertTrue(reduce.utilisationAfter() >= 1);
        OrderDecision close = b.order("A", FUT, Side.BUY, 150);
        assertTrue(close.accepted(), "and it can close the rest");
        assertEquals(0.0, close.requiredAfter(), 1e-6, "with both reserved orders filled the account is flat");
        // Flat on paper, so buying yet another lot would open a new long position it can't afford.
        assertFalse(b.order("A", FUT, Side.BUY, 250).accepted());
    }

    @Test
    void ordersThatCannotBeCheckedAreRejectedWithAReasonAndNeverStopTheShard() {
        Both b = new Both(account("A", "5000000"));
        assertEquals(Reason.NO_MARKET, b.order("A", CALL, Side.BUY, 25).reason(), "no tick yet, so no price");
        b.tick(1, "23300");
        assertEquals(Reason.UNKNOWN_ACCOUNT, b.order("NOBODY", CALL, Side.BUY, 25).reason());
        assertEquals(Reason.BAD_LOT_SIZE, b.order("A", CALL, Side.BUY, 30).reason(), "lot size is 25");
        List<EngineOutput> out = b.apply(new OrderRequest("O-X", "A", "NIFTY-20240613-99999-CE", Side.BUY, 25, OrderRequest.Origin.USER, Fixtures.T0));
        assertEquals(Reason.UNKNOWN_INSTRUMENT, ((OrderDecision) out.get(0)).reason());
        assertTrue(b.order("A", CALL, Side.BUY, 25).accepted(), "the shard is still running and still deciding");
    }

    @Test
    void theSameOrderIdIsDecidedOnce() {
        Both b = new Both(account("A", "5000000"));
        b.tick(1, "23300");
        OrderRequest o = new OrderRequest("O-1", "A", CALL.id(), Side.SELL, 25, OrderRequest.Origin.USER, Fixtures.T0);
        assertEquals(1, b.apply(o).size());
        assertTrue(b.apply(o).isEmpty(), "an API retry that wrote the request twice gets no second decision");
        assertEquals(1, b.shard.duplicateOrders());
        assertEquals(1, b.shard.pendingOrders("A").size(), "and no second reservation");
        // The engine's own square-off orders are not user orders; they wait for M5.
        assertTrue(b.apply(new OrderRequest("SQ-1", "A", CALL.id(), Side.BUY, 25, OrderRequest.Origin.SQUARE_OFF, Fixtures.T0)).isEmpty());
    }

    @Test
    void theCheckUsesTheLatestTickBeforeTheOrderInTheLog() {
        // Same account, same order; the only difference is whether the crash tick comes before or after
        // the order in the log. Log order alone decides, which is why no lock is needed.
        AccountBook book = account("A", "150000", CALL, -25, "250");
        Both before = new Both(book);
        before.tick(1, "23300");
        OrderDecision calm = before.order("A", CALL, Side.SELL, 25);
        before.tick(2, "24300");

        Both after = new Both(book);
        after.tick(1, "23300");
        after.tick(2, "24300");
        OrderDecision crashed = after.order("A", CALL, Side.SELL, 25);

        assertTrue(calm.accepted(), calm.toString());
        assertEquals(1, calm.lastTickSeq());
        assertFalse(crashed.accepted(), "after the index jumps 1,000 points the short call is deep in the money: " + crashed);
        assertEquals(2, crashed.lastTickSeq());
    }

    @Test
    void reservationsAndSeenOrdersArePartOfTheStateHash() {
        AccountBook book = account("A", "5000000");
        List<Event> base = List.of(
                new Tick(1, Fixtures.T0, "NIFTY", new BigDecimal("23300"), new BigDecimal("20")),
                new OrderRequest("O-1", "A", CALL.id(), Side.SELL, 25, OrderRequest.Origin.USER, Fixtures.T0));
        List<Event> withFill = new ArrayList<>(base);
        withFill.add(new Fill("F1", "O-1", "A", CALL.id(), -25, new BigDecimal("250"), Fixtures.T0));
        List<Event> fillForAnotherOrder = new ArrayList<>(base);
        fillForAnotherOrder.add(new Fill("F1", "O-2", "A", CALL.id(), -25, new BigDecimal("250"), Fixtures.T0));
        // Identical positions and cash; the only difference is whether O-1's reservation was released.
        assertNotEquals(ShardStateTest.replay(List.of(book), withFill, ShardConfig.defaults()),
                ShardStateTest.replay(List.of(book), fillForAnotherOrder, ShardConfig.defaults()));
        assertEquals(ShardStateTest.replay(List.of(book), withFill, ShardConfig.defaults()),
                ShardStateTest.replay(List.of(book), withFill, ShardConfig.defaults()));
    }

    @Test
    void theWallClockReceiptTimeChangesNothing() {
        // Two logs identical except for when the API says it received each order. The shard must produce
        // the same outputs, byte for byte, and the same state hash: receivedAt is metadata, not input.
        List<AccountBook> books = Fixtures.books(300, 61);
        List<riskengine.events.Event> base = Fixtures.events(books, 60, 50, 62);
        List<riskengine.events.Event> stamped = new ArrayList<>();
        long wall = 1_799_000_000_000L;
        int orders = 0;
        for (riskengine.events.Event e : base) {
            if (e instanceof OrderRequest o) {
                orders++;
                stamped.add(new OrderRequest(o.orderId(), o.accountId(), o.instrumentId(), o.side(), o.qty(), o.origin(), o.logTimeMillis(), wall += 7_919));
            } else {
                stamped.add(e);
            }
        }
        assertTrue(orders > 20, "the stream has orders: " + orders);
        ShardState a = new ShardState(0, Fixtures.CFG, ShardConfig.defaults(), INS, books), b = new ShardState(0, Fixtures.CFG, ShardConfig.defaults(), INS, books);
        List<String> outA = new ArrayList<>(), outB = new ArrayList<>();
        long offset = 0;
        for (int i = 0; i < base.size(); i++, offset++) {
            for (EngineOutput o : a.apply(new LoggedEvent(offset, base.get(i)))) outA.add(EngineOutputCodec.encode(o));
            for (EngineOutput o : b.apply(new LoggedEvent(offset, stamped.get(i)))) outB.add(EngineOutputCodec.encode(o));
        }
        assertNotEquals(base, stamped, "the inputs really differ");
        assertEquals(outA, outB);
        assertEquals(a.stateHash(), b.stateHash());
    }

    @Test
    void theRuleItself() {
        assertEquals(new BigDecimal("123.46"), PreTradeCheck.mark(123.456));
        assertEquals(-50, PreTradeCheck.remainingAfterFill(-75, -25));
        assertEquals(0, PreTradeCheck.remainingAfterFill(-75, -75));
        assertEquals(0, PreTradeCheck.remainingAfterFill(-75, -100), "over-fill ends the reservation");
        assertEquals(-75, PreTradeCheck.remainingAfterFill(-75, 25), "a fill the other way is not this order's");
    }

    @Test
    void engineOutputLinesAreFixedAndRoundTrip() {
        Both b = new Both(account("RICH", "5000000"), account("THIN", "120000", CALL, -25, "250"));
        List<String> lines = new ArrayList<>();
        for (long seq = 1; seq <= 4; seq++) {
            for (EngineOutput o : b.apply(new Tick(seq, Fixtures.T0 + seq, "NIFTY", new BigDecimal(23300 + 400 * seq), new BigDecimal("20")))) {
                lines.add(EngineOutputCodec.encode(o));
            }
        }
        lines.add(EngineOutputCodec.encode(b.order("O-1", "RICH", CALL, Side.SELL, 25)));
        lines.add(EngineOutputCodec.encode(b.apply(new Fill("F1", "O-1", "RICH", CALL.id(), -25, new BigDecimal("250.50"), 9)).get(0)));
        assertEquals("POSITION|v1|RICH|NIFTY-20240613-23300-CE|-25|250.500000|5006262.50|F1|O-1|-25|250.50|6262.50|0|9", lines.get(lines.size() - 1));
        lines.add(EngineOutputCodec.encode(b.apply(new OrderClosed("O-1", "RICH", OrderClosed.Reason.UNFILLED, 10)).get(0)));
        assertEquals("RELEASED|v1|O-1|RICH|UNFILLED|0|10", lines.get(lines.size() - 1), "fully filled, so nothing was left to release");
        lines.add(EngineOutputCodec.encode(b.order("O-0", "RICH", CALL, Side.SELL, 25)));
        lines.add(EngineOutputCodec.encode(b.order("O-2", "NOBODY", CALL, Side.SELL, 25)));
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("MARGIN|v1|THIN|")), lines.toString());
        assertTrue(lines.get(lines.size() - 5).startsWith("DECISION|v1|O-1|RICH|NIFTY-20240613-23300-CE|SELL|25|ACCEPTED|OK|4|"), lines.toString());
        assertEquals("DECISION|v1|O-2|NOBODY|NIFTY-20240613-23300-CE|SELL|25|REJECTED|UNKNOWN_ACCOUNT|4|" + Fixtures.T0 + "|0.0000|0.0000|0.000000|0",
                lines.get(lines.size() - 1));
        for (String line : lines) {
            EngineOutput decoded = EngineOutputCodec.decode(line);
            assertEquals(line, EngineOutputCodec.encode(decoded), "decode then encode gives the same line");
        }
        assertThrows(IllegalArgumentException.class, () -> EngineOutputCodec.decode("DECISION|v1|O|A|I|BUY|25|MAYBE|OK|1|1|0|0|0|0"));
        assertThrows(IllegalArgumentException.class, () -> EngineOutputCodec.decode("MARGIN|v2|x"));
    }
}
