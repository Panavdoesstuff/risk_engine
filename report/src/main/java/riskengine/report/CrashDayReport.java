package riskengine.report;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import riskengine.margin.AccountRisk;
import riskengine.margin.MarginLevel;
import riskengine.ref.Instrument;
import riskengine.report.CrashDay.Account;
import riskengine.report.CrashDay.Minute;

/** Turns a {@link CrashDay.Result} into docs/crash-day-2024-06-04.md, a minute-by-minute CSV and an SVG chart. */
public final class CrashDayReport {
    static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm").withZone(IST);
    private static final String[] TYPES = {"FUTURES_ONLY", "OPTION_BUYER", "SHORT_STRADDLE", "SHORT_STRANGLE", "HEDGED_SPREAD"};

    private final CrashDay.Result r;
    private final Map<String, String[]> kinds;
    private final Map<String, Instrument> instruments = new TreeMap<>();
    private final String bookMeta, vixMeta;
    private final int runs;
    private final Path out, assets;

    public CrashDayReport(CrashDay.Result r, Map<String, String[]> kinds, List<Instrument> instruments, String bookMeta, String vixMeta,
                          int runs, Path out, Path assets) {
        this.r = r;
        this.kinds = kinds;
        instruments.forEach(i -> this.instruments.put(i.id(), i));
        this.bookMeta = bookMeta;
        this.vixMeta = vixMeta;
        this.runs = runs;
        this.out = out;
        this.assets = assets;
    }

    /** Everything in the result that must not change between runs (no timings). */
    static String counts(CrashDay.Result r) {
        StringBuilder sb = new StringBuilder();
        for (Minute m : r.minutes()) {
            sb.append(m.tickSeq).append(java.util.Arrays.toString(m.levels)).append(java.util.Arrays.toString(m.statuses))
              .append(m.warned).append(',').append(m.marginCalled).append(',').append(m.episodesOpened).append(',').append(m.legs).append(',')
              .append(m.episodesResolved).append(',').append(m.episodesExhausted).append(',').append(Double.doubleToLongBits(m.required)).append('\n');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ numbers

    private long count(java.util.function.Predicate<Account> p) {
        return r.accounts().values().stream().filter(p).count();
    }

    private String type(Account a) {
        return kinds.get(a.id)[0];
    }

    private boolean thin(Account a) {
        return Boolean.parseBoolean(kinds.get(a.id)[1]);
    }

    private Minute first() {
        return r.minutes().get(0);
    }

    private Minute last() {
        return r.minutes().get(r.minutes().size() - 1);
    }

    long firstSquareOff() {
        return r.accounts().values().stream().mapToLong(a -> a.squareOffStarted).filter(t -> t >= 0).min().orElse(-1);
    }

    long lastEpisodeEnd() {
        return r.accounts().values().stream().mapToLong(a -> a.squareOffEnded).max().orElse(-1);
    }

    private Minute busiest() {
        return r.minutes().stream().max(Comparator.comparingLong((Minute m) -> m.legs).thenComparingLong(m -> m.episodesOpened)).orElseThrow();
    }

    private Minute lowest() {
        return r.minutes().stream().min(Comparator.comparing((Minute m) -> m.spot)).orElseThrow();
    }

    private List<Account> worst(int n) {
        return r.accounts().values().stream()
                .sorted(Comparator.comparingDouble((Account a) -> -a.maxShortfall).thenComparing(a -> a.id))
                .limit(n).toList();
    }

    public String summary() {
        long accounts = r.accounts().size();
        long warned = count(a -> a.firstWarning >= 0), called = count(a -> a.firstMarginCall >= 0), squared = count(a -> a.squareOffStarted >= 0);
        long legs = r.accounts().values().stream().mapToLong(a -> a.legs).sum();
        Account w = worst(1).get(0);
        return String.format(Locale.ROOT, """
                accounts %,d: warned %,d, margin-called %,d, squared off %,d (%,d legs, %,d lots); exhausted %,d, blocked at close %,d
                first square-off %s, last episode ended %s; time to clear %s
                negative equity: worst minute %,d accounts (%s), at close %,d
                worst account %s (%s): shortfall %s at %s, peak utilisation %s, lowest equity %s
                busiest minute %s: %,d legs, slowest shard %.2f ms for the tick + %.2f ms for its fills
                """,
                accounts, warned, called, squared, legs, r.accounts().values().stream().mapToLong(a -> a.lotsClosed).sum(),
                count(a -> "EXHAUSTED".equals(a.lastEpisodeEnd)), count(a -> a.blocked),
                time(firstSquareOff()), time(lastEpisodeEnd()), duration(firstSquareOff(), lastEpisodeEnd()),
                r.minutes().stream().mapToLong(m -> m.negativeEquity).max().orElse(0), rupees(r.minutes().stream().mapToDouble(m -> m.negativeEquityRupees).min().orElse(0)),
                last().negativeEquity,
                w.id, type(w), rupees(w.maxShortfall), time(w.maxShortfallTime), CrashDay.pct(w.peakUtilisation), rupees(w.minEquity),
                time(busiest().logTime), busiest().legs, busiest().tickNanos / 1e6, busiest().fillNanos / 1e6);
    }

    // ------------------------------------------------------------------ files

    public void write() throws IOException {
        Files.createDirectories(assets);
        Files.writeString(assets.resolve("curve.csv"), curveCsv(), StandardCharsets.UTF_8);
        Files.writeString(assets.resolve("chart.svg"), CrashDayChart.svg(r.minutes(), busiest()), StandardCharsets.UTF_8);
        Files.writeString(out, markdown(), StandardCharsets.UTF_8);
    }

    String curveCsv() {
        StringBuilder sb = new StringBuilder("time_ist,tick_seq,nifty,vix,ok,warning,margin_call,square_off,status_active,status_margin_call,status_squaring_off,"
                + "status_blocked,new_warnings,new_margin_calls,accounts_squared_off_first_time,square_offs_started,legs,lots_closed,episodes_resolved,episodes_exhausted,"
                + "required_crore,available_crore,negative_equity_accounts,negative_equity_crore,tick_ms_slowest_shard,fills_ms_slowest_shard\n");
        for (Minute m : r.minutes()) {
            sb.append(time(m.logTime)).append(',').append(m.tickSeq).append(',').append(m.spot.toPlainString()).append(',').append(m.vix.toPlainString());
            for (long v : m.levels) sb.append(',').append(v);
            for (long v : m.statuses) sb.append(',').append(v);
            sb.append(',').append(m.warned).append(',').append(m.marginCalled).append(',').append(m.squaredOff).append(',').append(m.episodesOpened).append(',').append(m.legs)
              .append(',').append(m.lotsClosed).append(',').append(m.episodesResolved).append(',').append(m.episodesExhausted)
              .append(',').append(fmt("%.2f", m.required / 1e7)).append(',').append(fmt("%.2f", m.available / 1e7))
              .append(',').append(m.negativeEquity).append(',').append(fmt("%.4f", m.negativeEquityRupees / 1e7))
              .append(',').append(fmt("%.3f", m.tickNanos / 1e6)).append(',').append(fmt("%.3f", m.fillNanos / 1e6)).append('\n');
        }
        return sb.toString();
    }

    String markdown() {
        long accounts = r.accounts().size();
        long warned = count(a -> a.firstWarning >= 0), called = count(a -> a.firstMarginCall >= 0), squared = count(a -> a.squareOffStarted >= 0);
        long legs = r.accounts().values().stream().mapToLong(a -> a.legs).sum();
        long lots = r.accounts().values().stream().mapToLong(a -> a.lotsClosed).sum();
        long episodes = r.minutes().stream().mapToLong(m -> m.episodesOpened).sum();
        long resolved = r.minutes().stream().mapToLong(m -> m.episodesResolved).sum(), exhausted = r.minutes().stream().mapToLong(m -> m.episodesExhausted).sum();
        long flatAfterSquareOff = count(a -> a.squareOffStarted >= 0 && a.flatAtClose);
        long negWorst = r.minutes().stream().mapToLong(m -> m.negativeEquity).max().orElse(0);
        Minute negWorstMinute = r.minutes().stream().max(Comparator.comparingLong((Minute m) -> m.negativeEquity)).orElseThrow();
        Minute busiest = busiest(), low = lowest();
        double[] tickMs = r.minutes().stream().mapToDouble(m -> m.tickNanos / 1e6).sorted().toArray();
        double[] fillMs = r.minutes().stream().mapToDouble(m -> m.fillNanos / 1e6).sorted().toArray();
        long[] durations = r.accounts().values().stream().filter(a -> a.squareOffStarted >= 0 && a.squareOffEnded >= 0)
                .mapToLong(a -> (a.squareOffEnded - a.squareOffStarted) / 60_000).sorted().toArray();
        long stillOpen = count(a -> a.squareOffStarted >= 0 && a.squareOffEnded < a.squareOffStarted);

        StringBuilder md = new StringBuilder();
        md.append("""
                # Crash day: 4 June 2024

                *Generated by `./gradlew :report:crashDay` from the files listed under [Inputs](#inputs). Do not edit by hand: \
                change the code or the inputs and run it again.*

                On 4 June 2024 India's general-election results came in well short of the exit polls. The Nifty 50 opened \
                0.4%% down and then fell almost 9%% from the open by 12:29 before recovering part of the loss; India VIX went from \
                about 21 to 31.7. This is that day replayed, minute by minute, against a seeded book of %s accounts opened at \
                the 3 June close, through the same shard code the live engine runs.

                """.formatted(fmt("%,d", accounts)));

        md.append("## Headline numbers\n\n| | |\n| --- | --- |\n");
        row(md, "Accounts", fmt("%,d", accounts));
        row(md, "Warned (utilisation reached 80%)", fmt("%,d (%.1f%%)", warned, 100.0 * warned / accounts));
        row(md, "Margin-called (100%, three ticks running)", fmt("%,d (%.1f%%)", called, 100.0 * called / accounts));
        row(md, "Squared off (120%, three ticks running)", fmt("%,d (%.1f%%)", squared, 100.0 * squared / accounts));
        row(md, "Square-off legs / lots closed", fmt("%,d legs, %,d lots", legs, lots));
        row(md, "Square-off episodes", fmt("%,d (%,d accounts were squared off twice or more)", episodes, count(a -> a.episodes > 1)));
        row(md, "Episodes resolved (back under 100%) / exhausted (still over, nothing left that frees margin)", fmt("%,d / %,d", resolved, exhausted));
        row(md, "Squared-off accounts flat at the close", fmt("%,d", flatAfterSquareOff));
        row(md, "First square-off started", time(firstSquareOff()) + " IST");
        row(md, "Last square-off episode ended", time(lastEpisodeEnd()) + " IST");
        row(md, "**Time to clear** (log time, first square-off started to last episode ended)", "**" + duration(firstSquareOff(), lastEpisodeEnd()) + "**");
        row(md, "Accounts below zero equity (lost more than they had), worst minute",
                negWorst == 0 ? "none at any minute" : fmt("%,d at %s IST, %s in total", negWorst, time(negWorstMinute.logTime), rupees(negWorstMinute.negativeEquityRupees)));
        row(md, "Accounts below zero equity at the close", fmt("%,d, %s in total", last().negativeEquity, rupees(last().negativeEquityRupees)));
        row(md, "Nifty low in the replay", fmt("%,.2f at %s IST (tick for the %s bar)", low.spot, time(low.logTime), time(low.logTime - 60_000)));
        row(md, "Busiest minute", fmt("%s IST: %,d square-offs started, %,d legs", time(busiest.logTime), busiest.episodesOpened, busiest.legs));
        md.append('\n');

        md.append("""
                **Time to clear** is measured in the replay's log time, the market's clock, not the laptop's. The broker in this \
                replay fills every leg before the next minute's tick (see [What the replay assumes](#what-the-replay-assumes)), so \
                an account's square-off takes less than a minute once started; the spread comes from accounts crossing 120%% at \
                different times of the day. %s

                """.formatted(durations.length == 0 ? "" : fmt("Per account, start to end: median %d min, longest %d min (minute granularity).", durations[durations.length / 2], durations[durations.length - 1])
                        + (stillOpen > 0 ? fmt(" %,d episodes were still open at the close.", stillOpen) : "")));

        md.append(waves());
        md.append("![Nifty 50, India VIX and accounts margin-called and squared off, 4 June 2024, minute by minute](crash-day/chart.svg)\n\n");
        md.append("*Three panels on one time axis: Nifty 50, India VIX, and how many accounts had been margin-called and squared off so far. "
                + "The dashed line marks the busiest square-off minute. Every number in the chart is in [crash-day/curve.csv](crash-day/curve.csv).*\n\n");

        // By account type
        md.append("## By account type\n\n");
        md.append("| Type | Accounts | Warned | Margin-called | Squared off | Exhausted | Below zero equity at worst | Legs |\n| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n");
        for (String t : TYPES) {
            for (int thinPass = 0; thinPass < 2; thinPass++) {
                boolean wantThin = thinPass == 1;
                java.util.function.Predicate<Account> in = a -> type(a).equals(t) && thin(a) == wantThin;
                long n = count(in);
                if (n == 0) continue;
                md.append("| ").append(label(t)).append(wantThin ? ", thinly funded" : "").append(" | ").append(fmt("%,d", n))
                  .append(" | ").append(share(count(in.and(a -> a.firstWarning >= 0)), n))
                  .append(" | ").append(share(count(in.and(a -> a.firstMarginCall >= 0)), n))
                  .append(" | ").append(share(count(in.and(a -> a.squareOffStarted >= 0)), n))
                  .append(" | ").append(fmt("%,d", count(in.and(a -> "EXHAUSTED".equals(a.lastEpisodeEnd)))))
                  .append(" | ").append(fmt("%,d", count(in.and(a -> a.minEquity < 0))))
                  .append(" | ").append(fmt("%,d", r.accounts().values().stream().filter(in).mapToLong(a -> a.legs).sum())).append(" |\n");
            }
        }
        md.append("""

                Thinly funded short sellers started the day at 80–99%% utilisation by design (decision 30); everyone else at \
                20–70%%. Option buyers need no margin (decision 23) and cannot be margin-called; their losses stop at the \
                premium they paid.

                """.formatted());

        // Worst accounts
        md.append("## The five worst accounts\n\nRanked by the largest margin shortfall reached during the day: required margin minus available "
                + "margin, the money the account would have had to add to be covered. Equity is what the account would be worth if closed out: "
                + "cash, futures profit and loss, and the value of options held net of options written. Below zero, the account has lost more "
                + "than it had, and the difference is the broker's loss if the client does not pay.\n\n");
        int rank = 1;
        for (Account a : worst(5)) {
            md.append(fmt("### %d. %s: %s%s\n\n", rank++, a.id, label(type(a)), thin(a) ? ", thinly funded" : ""));
            md.append(fmt("Equity at the 3 June close %s; lowest %s at %s IST (a loss of %s, %.0f%%); at the close %s. Peak utilisation %s at %s IST; "
                            + "largest shortfall %s at %s IST. %s\n\n",
                    rupees(a.startEquity), rupees(a.minEquity), time(a.minEquityTime), rupees(a.startEquity - a.minEquity),
                    a.startEquity > 0 ? 100 * (a.startEquity - a.minEquity) / a.startEquity : 0.0, rupees(a.closeEquity),
                    CrashDay.pct(a.peakUtilisation), time(a.peakUtilisationTime), rupees(a.maxShortfall), time(a.maxShortfallTime),
                    a.squareOffStarted < 0 ? "Never squared off." : fmt("Squared off from %s IST: %d leg%s, %d lots; %s%s.", time(a.squareOffStarted), a.legs, a.legs == 1 ? "" : "s", a.lotsClosed,
                            a.lastEpisodeEnd.isEmpty() ? "still open at the close" : a.lastEpisodeEnd.toLowerCase(Locale.ROOT) + " at " + time(a.squareOffEnded) + " IST",
                            a.blocked ? ", then blocked" : "")));
            md.append("| Time (IST) | What happened |\n| --- | --- |\n");
            int shown = 0;
            for (String e : a.timeline) {
                if (shown++ == 14) {
                    md.append(fmt("| | … %d more |\n", a.timeline.size() - 14));
                    break;
                }
                String[] p = e.split("\\|", 2);
                md.append("| ").append(time(Long.parseLong(p[0]))).append(" | ").append(p[1].replace("->", " → ")).append(" |\n");
            }
            if (a.timeline.isEmpty()) md.append("| | no level changes |\n");
            md.append('\n');
        }

        // Minute by minute
        md.append("## Minute by minute\n\nEvery 15 minutes, plus the busiest minute and the low. The full curve, all 375 minutes, is [crash-day/curve.csv](crash-day/curve.csv).\n\n");
        md.append("| Time (IST) | Nifty | VIX | Warning | Margin call | Square-off level | Squaring off | Blocked | Square-offs started | Legs | Required (₹ cr) | Below zero equity |\n");
        md.append("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n");
        for (int i = 0; i < r.minutes().size(); i++) {
            Minute m = r.minutes().get(i);
            boolean quarter = (m.logTime / 60_000) % 15 == 0;
            if (!(i == 0 || quarter || m == busiest || m == low || i == r.minutes().size() - 1)) continue;
            md.append("| ").append(time(m.logTime)).append(m == busiest ? " (busiest)" : m == low ? " (low)" : "")
              .append(" | ").append(m.spot.toPlainString()).append(" | ").append(m.vix.toPlainString())
              .append(" | ").append(fmt("%,d", m.levels[MarginLevel.WARNING.ordinal()]))
              .append(" | ").append(fmt("%,d", m.levels[MarginLevel.MARGIN_CALL.ordinal()]))
              .append(" | ").append(fmt("%,d", m.levels[MarginLevel.SQUARE_OFF.ordinal()]))
              .append(" | ").append(fmt("%,d", m.statuses[AccountRisk.Status.SQUARING_OFF.ordinal()]))
              .append(" | ").append(fmt("%,d", m.statuses[AccountRisk.Status.BLOCKED.ordinal()]))
              .append(" | ").append(fmt("%,d", m.episodesOpened)).append(" | ").append(fmt("%,d", m.legs))
              .append(" | ").append(fmt("%,.0f", m.required / 1e7)).append(" | ").append(fmt("%,d", m.negativeEquity)).append(" |\n");
        }
        md.append("\nTimes are when the minute's tick happened: the 09:15 bar closes, and becomes a tick, at 09:16 (decision 43). "
                + "Level and status counts are after the tick and any square-off fills it caused.\n\n");

        // Latency
        md.append("## How long the engine took\n\n");
        md.append(fmt("""
                Four shards, one thread each, in one process on the development laptop (Apple M4 MacBook Air), timed with \
                `System.nanoTime()` around applying each event. This is shard compute time: there is no broker hop in this \
                replay, so it is not the tick-to-margin figure in [results.md](results.md), which includes Redpanda.

                | | Slowest shard, applying the tick | Slowest shard, applying that minute's square-off fills |
                | --- | ---: | ---: |
                | Median minute | %.2f ms | %.2f ms |
                | p99 minute | %.2f ms | %.2f ms |
                | Busiest minute (%s, %,d legs) | %.2f ms | %.2f ms |
                | Slowest minute | %.2f ms | %.2f ms |

                The first tick includes the JIT warming up. Timings vary between runs; the counts above do not.

                """, pctl(tickMs, 0.5), pctl(fillMs, 0.5), pctl(tickMs, 0.99), pctl(fillMs, 0.99), time(busiest.logTime), busiest.legs,
                busiest.tickNanos / 1e6, busiest.fillNanos / 1e6, tickMs[tickMs.length - 1], fillMs[fillMs.length - 1]));

        // Determinism
        md.append("## Same input, same answer\n\n");
        md.append(fmt("The replay ran %d times; every run ended with the same state hash on every shard and the same counts, minute by minute "
                + "(the generator refuses to write this file otherwise).\n\n| Shard | Events applied | State hash |\n| --- | ---: | --- |\n", runs));
        for (CrashDay.ShardResult s : r.shards()) md.append(fmt("| %d | %,d | `%s` |\n", s.shard(), s.offsets(), s.stateHash()));
        md.append('\n');

        md.append(INPUTS.formatted(meta(bookMeta, "accounts"), meta(bookMeta, "seed"), meta(bookMeta, "start_spot"), meta(bookMeta, "start_vix"),
                meta(bookMeta, "margin_config_sha256").substring(0, 12), meta(vixMeta, "resolution").isEmpty() ? "unknown" : meta(vixMeta, "resolution"),
                meta(vixMeta, "rows"), meta(vixMeta, "missing_session_minutes")));
        md.append(ASSUMES.formatted());
        return md.toString();
    }

    private static final String INPUTS = """
            ## Inputs

            | Input | File | Notes |
            | --- | --- | --- |
            | Instruments and 3 June closing prices | `fo03JUN2024bhav.csv` (NSE F&O bhavcopy, legacy format) | 1,530 Nifty contracts. Positions open at the 3 June settlement price (decision 3). |
            | Lot sizes | [`data/reference/nifty_lots_2024-06-03.csv`](../data/reference/nifty_lots_2024-06-03.csv) | 25 for every Nifty contract, from NSE circular NSE/FAOP/61415 (2 April 2024), cross-checked against the bhavcopy. |
            | Nifty 50 minute bars, 3–4 June | `nifty_1min_2024-06-03_04.json` (Upstox v3 historical candles) | 375 bars a day, none missing. |
            | India VIX | `vix_1min_2024-06-03_04.json` (Upstox v3 historical candles) | **Intraday, one-minute** (resolution %6$s, %7$s bars, %8$s session minutes missing), joined "as of": each tick uses the latest VIX bar that had closed (decisions 43, 113). |
            | Book | `replayer seed --accounts %1$s --seed %2$s` | Start spot %3$s, VIX %4$s, margin config `%5$s…`. Cash is sized with the margin engine (decision 30). |
            | Margin parameters | [`margin.properties`](../engine/src/main/resources/riskengine/margin.properties) | Price scan 9.3%%, vol scan 4 points, extremes 2× at 35%%, no short-option minimum, exposure 2%%: checked against NSE Clearing's published rules (decision 115). Rate, dividend yield, VIX-as-vol and the 80/100/120%% thresholds are guesses. |

            ### Checked against published figures

            | | This replay's data | Published | Agrees? |
            | --- | --- | --- | --- |
            | Nifty 50 open, 4 June | 23,179.50 (09:15 bar open) | 23,179.50 | Yes |
            | Nifty 50 intraday low, 4 June | 21,281.45 (12:29 bar low) | 21,281.45 | Yes |
            | Nifty 50 close, 4 June | 21,996.45 (15:29 bar close) | 21,884.50, −5.93%% | No: NSE's official close is a weighted average of the last 30 minutes, not the last trade. The replay never uses the official close. |
            | India VIX high, 4 June | 31.71 (12:30 bar high) | 31.71 | Yes |
            | India VIX, 3 June close | 21.13 (15:29 bar close); 20.94 (4 June 09:15 bar open) | about 20.9 (reported −14.9%% from 24.60) | The seed uses 20.94, the official close carried into the next day's first bar. |

            Published figures are from press reports of NSE data (see decision 116), not from NSE's own download, which \
            refuses scripted access. Treat the right-hand column as corroborated, not as an official extract.

            The seeded book's start spot (23,320.33) is implied from the June future's settlement price by removing cost of \
            carry (decision 30), about 56 points above the official 3 June close of 23,263.90. The first tick (09:16, \
            22,755.90) therefore looks 2.4%% below the start, where the market had gapped 2.2%%.

            """;

    private static final String ASSUMES = """
            ## What the replay assumes

            - **The broker fills every square-off leg in full, at the engine's mark, before the next minute's tick.** No \
            rejects, timeouts or duplicates (the chaos broker is tested separately: M5 gate). Real fills on 4 June came with \
            slippage, wide spreads and some rejected orders; this replay closes positions at model prices.
            - **One volatility for every strike and expiry:** VIX ÷ 100 (decision 20). Out-of-the-money options carry a \
            higher implied volatility in the market (the smile), so short strangles look cheaper here than they were.
            - **No calendar spread charge** (decision 26) and a flat 2%% exposure margin: NSE adds 1.75%% on calendar spreads, \
            3%% on options more than 10%% out of the money and 5%% beyond nine months.
            - **One price scan range for all expiries:** NSE uses 17.7%% for index options more than nine months out.
            - **Futures are priced from spot by cost of carry** (decision 15); the real basis on the day is ignored.
            - **Margin thresholds (80 / 100 / 120%%, three ticks) are a broker's policy**, guessed, not an NSE rule.
            - **No user orders.** Nobody adds money, hedges or closes on their own during the day.
            - **The book is synthetic.** Accounts and their positions are generated (decision 2, 30), not real clients: the \
            counts say how this engine behaves on this book on this day, not how many Indian traders were margin-called.
            - Timings are from one fanless laptop.

            ## Reproduce

            ```bash
            ./gradlew -q :replayer:installDist && R=replayer/build/install/replayer/bin/replayer
            $R instruments --bhavcopy data/raw/fo03JUN2024bhav.csv --lots data/reference/nifty_lots_2024-06-03.csv \\
                           --date 2024-06-03 --out data/processed/instruments-2024-06-03.csv
            $R bars --in data/raw/nifty_1min_2024-06-03_04.json --from 2024-06-03 --to 2024-06-04 --out data/processed/NIFTY-1m.csv
            $R bars --in data/raw/vix_1min_2024-06-03_04.json   --from 2024-06-03 --to 2024-06-04 --out data/processed/INDIAVIX-1m.csv
            $R seed --instruments data/processed/instruments-2024-06-03.csv --vix 20.94 --out data/seed --seed 42 --accounts 100000
            $R ticks --bars data/processed/NIFTY-1m.csv --vix data/processed/INDIAVIX-1m.csv --from 2024-06-04 --to 2024-06-04 \\
                     --partitions 4 --out data/processed/logs
            ./gradlew :report:crashDay
            ```
            """;

    /** The minutes with the most square-offs, and which kinds of account they were. */
    private String waves() {
        TreeMap<Long, TreeMap<String, Long>> byMinute = new TreeMap<>();
        for (Account a : r.accounts().values()) {
            if (a.squareOffStarted < 0) continue;
            byMinute.computeIfAbsent(a.squareOffStarted, k -> new TreeMap<>()).merge(label(type(a)) + (thin(a) ? " (thin)" : ""), 1L, Long::sum);
        }
        List<Minute> top = r.minutes().stream().filter(m -> m.episodesOpened > 0)
                .sorted(Comparator.comparingLong((Minute m) -> -m.episodesOpened).thenComparingLong(m -> m.logTime)).limit(6)
                .sorted(Comparator.comparingLong(m -> m.logTime)).toList();
        long total = r.minutes().stream().mapToLong(m -> m.episodesOpened).sum();
        long inTop = top.stream().mapToLong(m -> m.episodesOpened).sum();
        StringBuilder sb = new StringBuilder(fmt("""
                ### When the square-offs happened

                Square-offs came in waves: the six busiest minutes hold %,d of the %,d. An account is squared off on its third \
                tick in a row at or above 120%%, and the seeded thin sellers hold similar at-the-money positions at similar \
                utilisation, so they cross together when the market takes another leg down. A real book is more varied and \
                would spread out more.

                | Tick (IST) | Nifty | VIX | Square-offs started | Who |
                | --- | ---: | ---: | ---: | --- |
                """, inTop, total));
        for (Minute m : top) {
            StringBuilder who = new StringBuilder();
            byMinute.getOrDefault(m.logTime, new TreeMap<>()).entrySet().stream()
                    .sorted((x, y) -> Long.compare(y.getValue(), x.getValue()))
                    .forEach(e -> who.append(who.length() == 0 ? "" : ", ").append(fmt("%,d %s", e.getValue(), e.getKey())));
            sb.append(fmt("| %s | %,.2f | %s | %,d | %s |\n", time(m.logTime), m.spot, m.vix.toPlainString(), m.episodesOpened, who));
        }
        return sb.append('\n').toString();
    }

    // ------------------------------------------------------------------ formatting

    private static void row(StringBuilder md, String k, String v) {
        md.append("| ").append(k).append(" | ").append(v).append(" |\n");
    }

    private static String label(String type) {
        return switch (type) {
            case "FUTURES_ONLY" -> "Futures only";
            case "OPTION_BUYER" -> "Option buyer";
            case "SHORT_STRADDLE" -> "Short straddle";
            case "SHORT_STRANGLE" -> "Short strangle";
            case "HEDGED_SPREAD" -> "Hedged spread";
            default -> type;
        };
    }

    private static String share(long k, long n) {
        return fmt("%,d (%.1f%%)", k, 100.0 * k / n);
    }

    static String time(long millis) {
        return millis < 0 ? "never" : HHMM.format(Instant.ofEpochMilli(millis));
    }

    static String duration(long from, long to) {
        if (from < 0 || to < 0) return "n/a";
        long min = (to - from) / 60_000;
        return min >= 60 ? fmt("%d h %02d min", min / 60, min % 60) : fmt("%d min", min);
    }

    static String rupees(double v) {
        double a = Math.abs(v);
        String s = a >= 1e7 ? fmt("₹%.2f crore", a / 1e7) : a >= 1e5 ? fmt("₹%.2f lakh", a / 1e5) : fmt("₹%,.0f", a);
        return (v < 0 ? "−" : "") + s;
    }

    private static double pctl(double[] sorted, double q) {
        return sorted[Math.min(sorted.length - 1, (int) Math.ceil(q * sorted.length) - 1)];
    }

    private static String meta(String meta, String key) {
        for (String line : meta.split("\n")) if (line.startsWith(key + "=")) return line.substring(key.length() + 1);
        return "";
    }

    static String fmt(String f, Object... a) {
        return String.format(Locale.ROOT, f, a);
    }

    static List<String> lines(String s) {
        return new ArrayList<>(List.of(s.split("\n")));
    }
}
