package riskengine.margin;

import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import riskengine.events.AccountControl;

/**
 * One account's risk state machine: its level, its status, and its square-off episode. Shared by the
 * shard and the reference engine, which differ only in how they compute margin ({@link AccountView}).
 *
 * <h2>Level</h2>
 * OK / WARNING / MARGIN_CALL / SQUARE_OFF, from {@link MarginStatusTracker}, fed once per tick with
 * <b>expected</b> utilisation: the account as it will be once the square-off leg that is in flight has
 * filled. Using actual utilisation would keep ordering more legs while one is still on its way.
 * A fill can lower the level at once (a leg that brings the account back under a threshold should show
 * immediately) but never raise it: raising takes consecutive ticks.
 *
 * <h2>Status</h2>
 * BLOCKED (risk-ops said so, or a square-off ran out of things to close) over SQUARING_OFF (an episode is
 * open) over MARGIN_CALL (level is MARGIN_CALL or above) over ACTIVE.
 *
 * <h2>Episode</h2>
 * Opens when the level reaches SQUARE_OFF (AUTO) or when risk-ops asks (MANUAL). While it is open:
 * <ul>
 *   <li>the level is frozen (square-off is sticky: it does not flicker off because a leg is in flight);</li>
 *   <li>user orders are refused and the account's reservations are cancelled;</li>
 *   <li>there is at most ONE leg in flight. The next leg is planned only when the previous one has fully
 *       filled or has been closed (rejected), and the leg is recorded here before it is emitted.</li>
 * </ul>
 * It ends RESOLVED when utilisation is back under the margin-call threshold (AUTO) or the account is flat
 * (MANUAL), and EXHAUSTED when it is still over and nothing is left whose closing would free margin; an
 * exhausted account is blocked until risk-ops unblocks it.
 */
public final class AccountRisk {
    public enum Status { ACTIVE, MARGIN_CALL, SQUARING_OFF, BLOCKED }

    public enum Mode { AUTO, MANUAL }

    /** The one leg that may be in flight. {@code remaining} is signed and never zero. */
    public record InFlight(String legKey, String instrumentId, long remaining) {}

    public static final String ENGINE = "engine";

    private final String accountId;
    private final Thresholds thresholds;
    private final MarginStatusTracker tracker;
    private boolean blocked;
    private boolean exhausted;       // an AUTO episode ended EXHAUSTED and positions have not changed since
    private long episodes;           // episodes opened so far; the next id is E<episodes + 1>
    private String episodeId;        // null when no episode is open
    private Mode mode;
    private String episodeActor;
    private int legSeq;              // legs planned in the open episode
    private InFlight inFlight;
    private Status status = Status.ACTIVE;

    public AccountRisk(String accountId, Thresholds thresholds) {
        this.accountId = accountId;
        this.thresholds = thresholds;
        this.tracker = new MarginStatusTracker(thresholds);
    }

    // ---------------------------------------------------------------- what the engines call

    /** A tick repriced the account. {@code actual} is its margin now. */
    public void onTick(AccountView account, MarginResult actual, long tickSeq, long time, List<EngineOutput> out) {
        if (episodeId == null) {
            MarginLevel before = tracker.level();
            double expected = expected(account, actual).utilisation();
            MarginLevel after = tracker.onReading(expected);
            if (after != before) out.add(new MarginEvent(accountId, tickSeq, time, actual, before, after, expected));
            if (after == MarginLevel.SQUARE_OFF && !exhausted) open(Mode.AUTO, ENGINE, account, tickSeq, time, out);
        }
        advance(account, tickSeq, time, out);
        publishStatus(ENGINE, tickSeq, time, out);
    }

    /**
     * A fill changed the account's positions.
     *
     * @return what is left of the in-flight leg if the fill belonged to it (0 = the leg is complete);
     *         Long.MIN_VALUE if the fill was not for the in-flight leg
     */
    public long onFill(AccountView account, String orderId, long fillQty, MarginResult actual, long tickSeq, long time, List<EngineOutput> out) {
        long legRemaining = Long.MIN_VALUE;
        if (inFlight != null && inFlight.legKey().equals(orderId)) {
            legRemaining = PreTradeCheck.remainingAfterFill(inFlight.remaining(), fillQty);
            inFlight = legRemaining == 0 ? null : new InFlight(inFlight.legKey(), inFlight.instrumentId(), legRemaining);
        }
        exhausted = false; // the positions changed, so there may be something to close again
        if (actual != null) {
            if (episodeId == null) {
                MarginLevel before = tracker.level();
                double expected = expected(account, actual).utilisation();
                MarginLevel after = tracker.lower(expected);
                if (after != before) out.add(new MarginEvent(accountId, tickSeq, time, actual, before, after, expected));
            }
            advance(account, tickSeq, time, out);
        }
        publishStatus(ENGINE, tickSeq, time, out);
        return legRemaining;
    }

    /**
     * An order was closed without (fully) filling. If it was the in-flight leg, the leg is dropped and the
     * episode plans again, with the next seq.
     *
     * @return the signed quantity that was still in flight, or 0 if the order was not the in-flight leg
     */
    public long onClosed(AccountView account, String orderId, long tickSeq, long time, List<EngineOutput> out) {
        if (inFlight == null || !inFlight.legKey().equals(orderId)) return 0;
        long dropped = inFlight.remaining();
        inFlight = null;
        advance(account, tickSeq, time, out);
        publishStatus(ENGINE, tickSeq, time, out);
        return dropped;
    }

    public void onControl(AccountControl c, AccountView account, long tickSeq, List<EngineOutput> out) {
        switch (c.action()) {
            case BLOCK -> blocked = true;
            case UNBLOCK -> {
                blocked = false;
                exhausted = false;
            }
            case SQUARE_OFF -> {
                if (episodeId == null) {
                    open(Mode.MANUAL, c.actor(), account, tickSeq, c.logTimeMillis(), out);
                } else {
                    mode = Mode.MANUAL; // an AUTO episode is already running: keep going until the account is flat
                    episodeActor = c.actor();
                }
                advance(account, tickSeq, c.logTimeMillis(), out);
            }
        }
        publishStatus(c.actor(), tickSeq, c.logTimeMillis(), out);
    }

    /** Why a user order may not even be checked right now, or null if it may. */
    public OrderDecision.Reason orderGate() {
        if (blocked) return OrderDecision.Reason.ACCOUNT_BLOCKED;
        if (episodeId != null) return OrderDecision.Reason.SQUARING_OFF;
        return null;
    }

    // ---------------------------------------------------------------- the episode

    private void open(Mode m, String actor, AccountView account, long tickSeq, long time, List<EngineOutput> out) {
        episodes++;
        episodeId = "E" + episodes;
        mode = m;
        episodeActor = actor;
        legSeq = 0;
        out.add(new EpisodeChange(accountId, episodeId, mode, EpisodeChange.State.OPENED, actor, 0, tickSeq, time));
        account.cancelReservations(out, time);
    }

    /** Ends the episode if it is done, or plans the next leg if none is in flight. */
    private void advance(AccountView account, long tickSeq, long time, List<EngineOutput> out) {
        if (episodeId == null || inFlight != null) return;
        MarginResult actual = account.margin(new TreeMap<>());
        if (actual == null) return;
        boolean done = mode == Mode.AUTO ? actual.utilisation() < thresholds.marginCall() : account.positions().isEmpty();
        LegPlanner.Leg leg = done ? null : LegPlanner.plan(account, mode, thresholds.marginCall());
        if (leg != null) {
            legSeq++;
            String key = SquareOffLeg.key(accountId, episodeId, leg.instrumentId(), legSeq);
            inFlight = new InFlight(key, leg.instrumentId(), leg.qty()); // recorded before it is emitted
            out.add(new SquareOffLeg(key, accountId, episodeId, leg.instrumentId(), legSeq, leg.qty(), account.mark(leg.instrumentId()),
                    leg.expectedRelease(), tickSeq, time));
            return;
        }
        EpisodeChange.State end = done ? EpisodeChange.State.RESOLVED : EpisodeChange.State.EXHAUSTED;
        out.add(new EpisodeChange(accountId, episodeId, mode, end, episodeActor, legSeq, tickSeq, time));
        episodeId = null;
        if (end == EpisodeChange.State.EXHAUSTED) {
            // Still over the limit with nothing useful left to close. No point opening another episode
            // every tick: block the account and wait for risk-ops (or for its positions to change).
            exhausted = true;
            blocked = true;
        }
        // The level was frozen during the episode. Bring it back in line, downwards only.
        MarginLevel before = tracker.level();
        MarginLevel after = tracker.lower(actual.utilisation());
        if (after != before) out.add(new MarginEvent(accountId, tickSeq, time, actual, before, after, actual.utilisation()));
    }

    /** The account as it will be once the in-flight leg has filled. */
    private MarginResult expected(AccountView account, MarginResult actual) {
        if (inFlight == null) return actual;
        SortedMap<String, Long> leg = new TreeMap<>();
        leg.put(inFlight.instrumentId(), inFlight.remaining());
        MarginResult r = account.margin(leg);
        return r == null ? actual : r;
    }

    private void publishStatus(String actor, long tickSeq, long time, List<EngineOutput> out) {
        Status now = blocked ? Status.BLOCKED
                : episodeId != null ? Status.SQUARING_OFF
                : tracker.level().compareTo(MarginLevel.MARGIN_CALL) >= 0 ? Status.MARGIN_CALL
                : Status.ACTIVE;
        if (now == status) return;
        String cause = switch (now) {
            case BLOCKED -> exhausted ? "SQUARE_OFF_EXHAUSTED" : "BLOCKED_BY_RISK_OPS";
            case SQUARING_OFF -> mode == Mode.MANUAL ? "MANUAL_SQUARE_OFF" : "SQUARE_OFF_STARTED";
            case MARGIN_CALL -> status == Status.BLOCKED ? "UNBLOCKED" : status == Status.SQUARING_OFF ? "SQUARE_OFF_ENDED" : "MARGIN_CALL";
            case ACTIVE -> status == Status.BLOCKED ? "UNBLOCKED" : status == Status.SQUARING_OFF ? "SQUARE_OFF_ENDED" : "CLEARED";
        };
        out.add(new AccountStatusChange(accountId, status, now, cause, actor, tickSeq, time));
        status = now;
    }

    // ---------------------------------------------------------------- read access

    public MarginLevel level() {
        return tracker.level();
    }

    public Status status() {
        return status;
    }

    /** The leg in flight, or null. */
    public InFlight inFlight() {
        return inFlight;
    }

    /** Id of the open episode, or null. */
    public String episodeId() {
        return episodeId;
    }

    public boolean blocked() {
        return blocked;
    }

    /** Everything in this state machine as one line, for the shard's state hash and for comparing engines. */
    public String fingerprint() {
        return tracker.level() + "|" + tracker.callStreak() + "|" + tracker.squareOffStreak() + "|" + blocked + "|" + exhausted + "|"
                + episodes + "|" + episodeId + "|" + mode + "|" + episodeActor + "|" + legSeq + "|" + status + "|"
                + (inFlight == null ? "-" : inFlight.legKey() + "/" + inFlight.remaining());
    }
}
