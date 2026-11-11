package riskengine.report;

import java.util.List;
import java.util.function.ToDoubleFunction;
import riskengine.report.CrashDay.Minute;

/**
 * The crash-day chart as a standalone SVG: three panels on one time axis (Nifty 50, India VIX, accounts
 * margin-called and squared off so far), never two scales on one axis. Colours are CSS variables with a
 * dark-mode block, so the image follows the reader's theme where SVG-in-img honours it (GitHub does).
 */
final class CrashDayChart {
    private CrashDayChart() {}

    private static final int W = 960, LEFT = 72, RIGHT = 150, TOP = 78, PANEL = 150, GAP = 44, BOTTOM = 40;

    static String svg(List<Minute> minutes, Minute marker) {
        int h = TOP + 3 * PANEL + 2 * GAP + BOTTOM;
        long t0 = minutes.get(0).logTime - 60_000, t1 = minutes.get(minutes.size() - 1).logTime;
        StringBuilder s = new StringBuilder();
        s.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ").append(W).append(' ').append(h)
         .append("\" width=\"").append(W).append("\" height=\"").append(h).append("\" role=\"img\" aria-labelledby=\"title desc\" font-family=\"-apple-system, 'Segoe UI', Helvetica, Arial, sans-serif\">\n");
        s.append("<title id=\"title\">Crash day, 4 June 2024</title>\n<desc id=\"desc\">Nifty 50, India VIX, and the number of accounts margin-called and squared off so far, each minute from 09:16 to 15:30 IST.</desc>\n");
        s.append("""
                <style>
                  svg { --surface:#fcfcfb; --ink:#0b0b0b; --ink2:#52514e; --muted:#8a8984; --grid:#e6e5e1; --s1:#2a78d6; --s2:#eb6834; }
                  @media (prefers-color-scheme: dark) {
                    svg { --surface:#1a1a19; --ink:#f0efec; --ink2:#c3c2b7; --muted:#8f8e88; --grid:#33332f; --s1:#3987e5; --s2:#d95926; }
                  }
                  .bg { fill: var(--surface); }
                  .t { fill: var(--ink); font-size: 15px; font-weight: 600; }
                  .pt { fill: var(--ink); font-size: 13px; font-weight: 600; }
                  .ax { fill: var(--ink2); font-size: 11px; }
                  .lab { font-size: 12px; font-weight: 600; fill: var(--ink); }
                  .grid { stroke: var(--grid); stroke-width: 1; }
                  .line { fill: none; stroke: var(--ink); stroke-width: 2; stroke-linejoin: round; stroke-linecap: round; }
                  .s1 { fill: none; stroke: var(--s1); stroke-width: 2; stroke-linejoin: round; }
                  .s2 { fill: none; stroke: var(--s2); stroke-width: 2; stroke-linejoin: round; }
                  .k1 { fill: var(--s1); } .k2 { fill: var(--s2); }
                  .mark { stroke: var(--muted); stroke-width: 1; stroke-dasharray: 4 4; }
                </style>
                """);
        s.append("<rect class=\"bg\" width=\"100%\" height=\"100%\"/>\n");
        s.append("<text class=\"t\" x=\"").append(LEFT).append("\" y=\"26\">4 June 2024, minute by minute (IST)</text>\n");

        int y0 = TOP;
        panel(s, minutes, t0, t1, y0, "Nifty 50", m -> m.spot.doubleValue(), "%,.0f");
        panel(s, minutes, t0, t1, y0 + PANEL + GAP, "India VIX", m -> m.vix.doubleValue(), "%.0f");
        // Panel 3: cumulative margin calls and square-offs.
        double[] called = new double[minutes.size()], squared = new double[minutes.size()];
        double c = 0, q = 0;
        for (int i = 0; i < minutes.size(); i++) {
            c += minutes.get(i).marginCalled;
            q += minutes.get(i).squaredOff;
            called[i] = c;
            squared[i] = q;
        }
        int y3 = y0 + 2 * (PANEL + GAP);
        double max = niceMax(Math.max(c, 1));
        axes(s, t0, t1, y3, "Accounts so far", 0, max, "%,.0f");
        s.append(path(minutes, t0, t1, y3, 0, max, called, "s1"));
        s.append(path(minutes, t0, t1, y3, 0, max, squared, "s2"));
        // Direct labels at the right end, plus a legend row (identity never by colour alone).
        double ly1 = yOf(called[called.length - 1], 0, max, y3), ly2 = yOf(squared[squared.length - 1], 0, max, y3);
        if (Math.abs(ly1 - ly2) < 16) ly2 = ly1 + 16;
        s.append(label(W - RIGHT + 8, ly1, CrashDayReport.fmt("Margin-called %,.0f", c)));
        s.append(label(W - RIGHT + 8, ly2, CrashDayReport.fmt("Squared off %,.0f", q)));
        int lx = LEFT + 130;
        s.append("<rect class=\"k1\" x=\"").append(lx).append("\" y=\"").append(y3 - 14).append("\" width=\"14\" height=\"3\" rx=\"1.5\"/>")
         .append("<text class=\"ax\" x=\"").append(lx + 20).append("\" y=\"").append(y3 - 9).append("\">margin-called</text>\n");
        s.append("<rect class=\"k2\" x=\"").append(lx + 120).append("\" y=\"").append(y3 - 14).append("\" width=\"14\" height=\"3\" rx=\"1.5\"/>")
         .append("<text class=\"ax\" x=\"").append(lx + 140).append("\" y=\"").append(y3 - 9).append("\">squared off</text>\n");

        // The busiest square-off minute, across all panels.
        if (marker != null && marker.legs > 0) {
            double x = xOf(marker.logTime, t0, t1);
            s.append(CrashDayReport.fmt("<line class=\"mark\" x1=\"%.1f\" x2=\"%.1f\" y1=\"%d\" y2=\"%d\"/>\n", x, x, TOP - 20, y3 + PANEL));
            s.append(CrashDayReport.fmt("<text class=\"ax\" x=\"%.1f\" y=\"%d\" text-anchor=\"middle\">busiest: %s, %,d legs</text>\n",
                    x, TOP - 26, CrashDayReport.time(marker.logTime), marker.legs));
        }
        // Time axis.
        for (int hh = 9; hh <= 15; hh++) {
            for (int mm : new int[] {0, 30}) {
                long t = t0 - (t0 % 86_400_000L) + ((hh * 60L + mm) - 330) * 60_000L; // IST wall time to epoch millis on the same day
                if (t < t0 || t > t1) continue;
                double x = xOf(t, t0, t1);
                s.append(CrashDayReport.fmt("<text class=\"ax\" x=\"%.1f\" y=\"%d\" text-anchor=\"middle\">%02d:%02d</text>\n", x, y3 + PANEL + 18, hh, mm));
            }
        }
        s.append("</svg>\n");
        return s.toString();
    }

    private static void panel(StringBuilder s, List<Minute> minutes, long t0, long t1, int y, String title, ToDoubleFunction<Minute> f, String tickFmt) {
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        double[] v = new double[minutes.size()];
        for (int i = 0; i < v.length; i++) {
            v[i] = f.applyAsDouble(minutes.get(i));
            lo = Math.min(lo, v[i]);
            hi = Math.max(hi, v[i]);
        }
        double step = niceStep((hi - lo) / 4);
        lo = Math.floor(lo / step) * step;
        hi = Math.ceil(hi / step) * step;
        axes(s, t0, t1, y, title, lo, hi, tickFmt);
        s.append(path(minutes, t0, t1, y, lo, hi, v, "line"));
        double end = v[v.length - 1];
        s.append(label(W - RIGHT + 8, yOf(end, lo, hi, y), CrashDayReport.fmt(tickFmt.equals("%.0f") ? "%.2f" : "%,.2f", end)));
    }

    private static void axes(StringBuilder s, long t0, long t1, int y, String title, double lo, double hi, String tickFmt) {
        s.append("<text class=\"pt\" x=\"").append(LEFT).append("\" y=\"").append(y - 8).append("\">").append(title).append("</text>\n");
        double step = niceStep((hi - lo) / 4);
        for (double v = lo; v <= hi + step / 2; v += step) {
            double yy = yOf(v, lo, hi, y);
            s.append(CrashDayReport.fmt("<line class=\"grid\" x1=\"%d\" x2=\"%d\" y1=\"%.1f\" y2=\"%.1f\"/>", LEFT, W - RIGHT, yy, yy));
            s.append(CrashDayReport.fmt("<text class=\"ax\" x=\"%d\" y=\"%.1f\" text-anchor=\"end\">%s</text>\n", LEFT - 8, yy + 4, CrashDayReport.fmt(tickFmt, v)));
        }
    }

    private static String path(List<Minute> minutes, long t0, long t1, int y, double lo, double hi, double[] v, String cls) {
        StringBuilder p = new StringBuilder("<path class=\"").append(cls).append("\" d=\"");
        for (int i = 0; i < v.length; i++) {
            p.append(i == 0 ? 'M' : 'L').append(CrashDayReport.fmt("%.1f,%.1f", xOf(minutes.get(i).logTime, t0, t1), yOf(v[i], lo, hi, y)));
        }
        return p.append("\"/>\n").toString();
    }

    private static String label(double x, double y, String text) {
        return CrashDayReport.fmt("<text class=\"lab\" x=\"%.1f\" y=\"%.1f\">%s</text>\n", x, y + 4, text);
    }

    private static double xOf(long t, long t0, long t1) {
        return LEFT + (double) (t - t0) / (t1 - t0) * (W - LEFT - RIGHT);
    }

    private static double yOf(double v, double lo, double hi, int top) {
        return top + PANEL - (v - lo) / (hi - lo) * PANEL;
    }

    private static double niceStep(double raw) {
        double mag = Math.pow(10, Math.floor(Math.log10(raw)));
        double n = raw / mag;
        return (n <= 1 ? 1 : n <= 2 ? 2 : n <= 2.5 ? 2.5 : n <= 5 ? 5 : 10) * mag;
    }

    private static double niceMax(double v) {
        double step = niceStep(v / 4);
        return Math.ceil(v / step) * step;
    }
}
