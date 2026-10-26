package com.irathinker.app;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Smart Money Concepts from raw bars - no feed required:
 *  market structure (BOS / CHoCH), trend from swing sequences,
 *  liquidity pools (clustered swing highs/lows = resting stops),
 *  liquidity sweeps (stop hunts), order blocks, fair value gaps,
 *  premium/discount position and relative volume.
 *  Open Interest and the true options chain arrive with the Dhan
 *  feed; everything here is computed from the bars themselves. */
public final class Smc {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    public static final class Report {
        public String structure = "RANGING";
        public String trend = "MIXED";
        public double liqAbove = Double.NaN, liqBelow = Double.NaN;
        public int liqAboveTouches, liqBelowTouches;
        public boolean sweepUp, sweepDown;
        public boolean hasOb, obBullish;
        public double obHigh, obLow;
        public boolean hasFvg, fvgBullish;
        public double fvgHigh, fvgLow;
        public double rangePosPct = Double.NaN;
        public double rvol = Double.NaN;
        public double dayHigh, dayLow, prevHigh, prevLow;
        public int swingCount;
    }

    private static final class Swing {
        final int idx; final boolean high; final double price;
        Swing(int idx, boolean high, double price) { this.idx = idx; this.high = high; this.price = price; }
    }

    public static Report analyze(YahooClient.Chart c) {
        Report r = new Report();
        if (c == null || c.ts.length < 40) return r;
        Map<LocalDate, List<Integer>> byDay = new TreeMap<>();
        for (int i = 0; i < c.ts.length; i++) {
            LocalDate d = Instant.ofEpochSecond(c.ts[i]).atZone(IST).toLocalDate();
            byDay.computeIfAbsent(d, k -> new ArrayList<>()).add(i);
        }
        List<LocalDate> days = new ArrayList<>(byDay.keySet());
        if (days.size() < 2) return r;
        List<Integer> today = byDay.get(days.get(days.size() - 1));
        List<Integer> prev = byDay.get(days.get(days.size() - 2));
        double ltp = lastClose(c, today);
        if (Double.isNaN(ltp)) return r;

        r.dayHigh = maxOf(c, today, true);
        r.dayLow = maxOf(c, today, false);
        r.prevHigh = maxOf(c, prev, true);
        r.prevLow = maxOf(c, prev, false);
        if (r.dayHigh > r.dayLow) r.rangePosPct = (ltp - r.dayLow) / (r.dayHigh - r.dayLow) * 100;

        // ---- swings over the last ~10 days (fractal 2/2) ----
        int from = Math.max(0, c.ts.length - 75 * 10);
        List<Swing> swings = new ArrayList<>();
        for (int i = from + 2; i < c.ts.length - 2; i++) {
            if (Double.isNaN(c.high[i]) || Double.isNaN(c.low[i])) continue;
            boolean isH = true, isL = true;
            for (int k = 1; k <= 2; k++) {
                if (!(c.high[i] > c.high[i - k] && c.high[i] > c.high[i + k])) isH = false;
                if (!(c.low[i] < c.low[i - k] && c.low[i] < c.low[i + k])) isL = false;
            }
            if (isH) swings.add(new Swing(i, true, c.high[i]));
            if (isL) swings.add(new Swing(i, false, c.low[i]));
        }
        r.swingCount = swings.size();

        // ---- trend from the last two swing highs/lows ----
        List<Swing> hs = new ArrayList<>(), ls = new ArrayList<>();
        for (Swing s : swings) (s.high ? hs : ls).add(s);
        boolean hh = hs.size() >= 2 && hs.get(hs.size() - 1).price > hs.get(hs.size() - 2).price;
        boolean hl = ls.size() >= 2 && ls.get(ls.size() - 1).price > ls.get(ls.size() - 2).price;
        boolean lh = hs.size() >= 2 && hs.get(hs.size() - 1).price < hs.get(hs.size() - 2).price;
        boolean ll = ls.size() >= 2 && ls.get(ls.size() - 1).price < ls.get(ls.size() - 2).price;
        if (hh && hl) r.trend = "UP (HH/HL)";
        else if (lh && ll) r.trend = "DOWN (LH/LL)";
        else r.trend = "MIXED";

        // ---- structure break on today's bars ----
        double lastSwingHigh = hs.isEmpty() ? Double.NaN : hs.get(hs.size() - 1).price;
        double lastSwingLow = ls.isEmpty() ? Double.NaN : ls.get(ls.size() - 1).price;
        for (int i : today) {
            double cl = c.close[i];
            if (Double.isNaN(cl)) continue;
            if (!Double.isNaN(lastSwingHigh) && cl > lastSwingHigh) {
                r.structure = r.trend.startsWith("DOWN") ? "CHOCH UP" : "BOS UP";
            }
            if (!Double.isNaN(lastSwingLow) && cl < lastSwingLow) {
                r.structure = r.trend.startsWith("UP") ? "CHOCH DOWN" : "BOS DOWN";
            }
        }

        // ---- liquidity pools: clustered swings (>=2 touches within 0.08%) ----
        findPools(swings, true, ltp, r, true);
        findPools(swings, false, ltp, r, false);
        // classic single-touch pools: yesterday's extremes
        if (Double.isNaN(r.liqAbove) && !Double.isNaN(r.prevHigh) && r.prevHigh > ltp) {
            r.liqAbove = r.prevHigh; r.liqAboveTouches = 1;
        }
        if (Double.isNaN(r.liqBelow) && !Double.isNaN(r.prevLow) && r.prevLow < ltp) {
            r.liqBelow = r.prevLow; r.liqBelowTouches = 1;
        }

        // ---- liquidity sweeps (stop hunts) against yesterday's extremes ----
        for (int i : today) {
            if (Double.isNaN(c.high[i]) || Double.isNaN(c.close[i])) continue;
            if (!Double.isNaN(r.prevHigh) && c.high[i] > r.prevHigh && c.close[i] < r.prevHigh) r.sweepUp = true;
            if (!Double.isNaN(c.low[i]) && !Double.isNaN(r.prevLow)
                    && c.low[i] < r.prevLow && c.close[i] > r.prevLow) r.sweepDown = true;
        }

        // ---- order block: last opposite candle before today's strongest displacement ----
        double avgBody = 0;
        int nb = 0;
        for (int i : today) {
            if (!Double.isNaN(c.open[i]) && !Double.isNaN(c.close[i])) {
                avgBody += Math.abs(c.close[i] - c.open[i]); nb++;
            }
        }
        avgBody = nb == 0 ? 0 : avgBody / nb;
        int dispIdx = -1;
        double dispBody = 0;
        for (int i : today) {
            if (Double.isNaN(c.open[i]) || Double.isNaN(c.close[i])) continue;
            double b = Math.abs(c.close[i] - c.open[i]);
            if (b > dispBody) { dispBody = b; dispIdx = i; }
        }
        if (dispIdx > 0 && avgBody > 0 && dispBody > 1.8 * avgBody) {
            boolean dispUp = c.close[dispIdx] > c.open[dispIdx];
            for (int j = dispIdx - 1; j >= Math.max(0, dispIdx - 6); j--) {
                if (Double.isNaN(c.open[j]) || Double.isNaN(c.close[j])) continue;
                boolean candleUp = c.close[j] >= c.open[j];
                if (candleUp != dispUp) {
                    r.hasOb = true;
                    r.obBullish = dispUp;
                    r.obHigh = c.high[j];
                    r.obLow = c.low[j];
                    break;
                }
            }
        }

        // ---- nearest unfilled fair value gap today ----
        for (int k = 1; k < today.size() - 1; k++) {
            int a = today.get(k - 1), b = today.get(k + 1);
            if (Double.isNaN(c.low[a]) || Double.isNaN(c.high[b])) continue;
            if (c.low[a] > c.high[b]) { // bullish gap between b.high and a.low
                if (ltp >= c.high[b]) { r.hasFvg = true; r.fvgBullish = true; r.fvgLow = c.high[b]; r.fvgHigh = c.low[a]; }
            } else if (Double.isNaN(c.high[a]) || Double.isNaN(c.low[b])) {
                continue;
            } else if (c.high[a] < c.low[b]) { // bearish gap
                if (ltp <= c.low[b]) { r.hasFvg = true; r.fvgBullish = false; r.fvgHigh = c.low[b]; r.fvgLow = c.high[a]; }
            }
        }

        // ---- relative volume: today's pace vs prior days at the same bar count ----
        r.rvol = relativeVolume(c, byDay, days, today.size());
        return r;
    }

    private static void findPools(List<Swing> swings, boolean highs, double ltp, Report r, boolean above) {
        List<Double> pts = new ArrayList<>();
        for (Swing s : swings) if (s.high == highs) pts.add(s.price);
        double best = Double.NaN;
        int bestTouches = 0;
        for (double base : pts) {
            int touches = 0;
            double sum = 0;
            for (double q : pts) {
                if (Math.abs(q - base) / base * 100 <= 0.08) { touches++; sum += q; }
            }
            if (touches >= 2) {
                double level = sum / touches;
                if (above && level > ltp && (Double.isNaN(best) || level < best)) { best = level; bestTouches = touches; }
                if (!above && level < ltp && (Double.isNaN(best) || level > best)) { best = level; bestTouches = touches; }
            }
        }
        if (above) { r.liqAbove = best; r.liqAboveTouches = bestTouches; }
        else { r.liqBelow = best; r.liqBelowTouches = bestTouches; }
    }

    private static double relativeVolume(YahooClient.Chart c, Map<LocalDate, List<Integer>> byDay,
                                         List<LocalDate> days, int todayBars) {
        double todayCum = 0;
        List<Integer> t = byDay.get(days.get(days.size() - 1));
        for (int i : t) todayCum += c.volume[i];
        if (todayCum <= 0) return Double.NaN;
        double sum = 0;
        int used = 0;
        for (int d = days.size() - 2; d >= 0 && used < 5; d--) {
            List<Integer> day = byDay.get(days.get(d));
            double cum = 0;
            int n = Math.min(todayBars, day.size());
            for (int k = 0; k < n; k++) cum += c.volume[day.get(k)];
            if (cum > 0) { sum += cum; used++; }
        }
        if (used == 0) return Double.NaN;
        return todayCum / (sum / used);
    }

    private static double lastClose(YahooClient.Chart c, List<Integer> idx) {
        for (int k = idx.size() - 1; k >= 0; k--) {
            double v = c.close[idx.get(k)];
            if (!Double.isNaN(v)) return v;
        }
        return Double.NaN;
    }

    private static double maxOf(YahooClient.Chart c, List<Integer> idx, boolean high) {
        double best = high ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        for (int i : idx) {
            double v = high ? c.high[i] : c.low[i];
            if (!Double.isNaN(v)) best = high ? Math.max(best, v) : Math.min(best, v);
        }
        return best == Double.NEGATIVE_INFINITY || best == Double.POSITIVE_INFINITY ? Double.NaN : best;
    }
}
