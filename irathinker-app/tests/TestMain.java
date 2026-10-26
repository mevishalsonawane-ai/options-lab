package com.irathinker.app;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

/** Desktop JVM test harness for the IraThinker engine. Runs the real
 *  Analysis code against real Yahoo data (CSVs fetched beforehand) and
 *  hand-computed synthetic cases. Any failure throws AssertionError. */
public class TestMain {

    static int passed = 0;

    static void check(boolean cond, String label) {
        if (!cond) throw new AssertionError("FAIL: " + label);
        passed++;
        System.out.println("ok - " + label);
    }

    static YahooClient.Chart loadCsv(String path) throws Exception {
        List<Long> ts = new ArrayList<>();
        List<Double> o = new ArrayList<>(), h = new ArrayList<>(), l = new ArrayList<>(), c = new ArrayList<>();
        List<Long> v = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            String line = r.readLine(); // header
            while ((line = r.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                String[] p = line.split(",", -1);
                ts.add(Long.parseLong(p[0]));
                o.add(num(p[1])); h.add(num(p[2])); l.add(num(p[3])); c.add(num(p[4]));
                v.add(p[5] == null || p[5].equals("None") || p[5].isEmpty() ? 0 : (long) Double.parseDouble(p[5]));
            }
        }
        YahooClient.Chart ch = new YahooClient.Chart();
        int n = ts.size();
        ch.ts = new long[n]; ch.open = new double[n]; ch.high = new double[n];
        ch.low = new double[n]; ch.close = new double[n]; ch.volume = new long[n];
        for (int i = 0; i < n; i++) {
            ch.ts[i] = ts.get(i); ch.open[i] = o.get(i); ch.high[i] = h.get(i);
            ch.low[i] = l.get(i); ch.close[i] = c.get(i); ch.volume[i] = v.get(i);
        }
        return ch;
    }

    /** Two-day synthetic chart: day1 waves between 100 and 110; day2 pokes to 111 and closes back at 104. */
    static YahooClient.Chart synthSweepChart() {
        java.time.ZoneId ist = java.time.ZoneId.of("Asia/Kolkata");
        long d1 = java.time.ZonedDateTime.of(2026, 10, 21, 9, 15, 0, 0, ist).toEpochSecond();
        long d2 = java.time.ZonedDateTime.of(2026, 10, 22, 9, 15, 0, 0, ist).toEpochSecond();
        java.util.List<double[]> bars = new java.util.ArrayList<>();
        java.util.List<Long> times = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) {
            double mid = 105 + Math.sin(i / 3.0) * 4;
            bars.add(new double[]{mid, Math.min(110, mid + 2.5), Math.max(100, mid - 2.5), mid, 1000});
            if (i == 14) bars.set(i, new double[]{108, 110, 106, 108, 1000}); // day high = 110
            if (i == 20) bars.set(i, new double[]{102, 103, 100, 102, 1000}); // day low = 100
            times.add(d1 + i * 300L);
        }
        for (int i = 0; i < 15; i++) {
            double[] b = {104, 105, 103, 104, 1200};
            if (i == 5) b = new double[]{106, 111, 105, 104, 3000}; // sweep: wick 111 > 110, close back 104
            bars.add(b);
            times.add(d2 + i * 300L);
        }
        return toChart(bars, times);
    }

    /** Day2 contains a clean bullish FVG: bar A low=100, bar C high=99. */
    static YahooClient.Chart synthFvgChart() {
        java.time.ZoneId ist = java.time.ZoneId.of("Asia/Kolkata");
        long d1 = java.time.ZonedDateTime.of(2026, 10, 21, 9, 15, 0, 0, ist).toEpochSecond();
        long d2 = java.time.ZonedDateTime.of(2026, 10, 22, 9, 15, 0, 0, ist).toEpochSecond();
        java.util.List<double[]> bars = new java.util.ArrayList<>();
        java.util.List<Long> times = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) {
            bars.add(new double[]{105, 106, 104, 105, 1000});
            times.add(d1 + i * 300L);
        }
        double[][] day2 = {
                {100.5, 101, 100, 100.8, 1000},  // A: low 100
                {100.8, 102.5, 100.7, 102.2, 1000}, // B: impulse up
                {102.2, 102.4, 99 + 3.2, 102.1, 1000}, // C: high must be < 100 for a gap... placeholder fixed below
                {102.1, 102.6, 101.8, 102.4, 1000},
                {102.4, 102.8, 102.0, 102.5, 1000},
        };
        // rebuild C so its high is 99? price can't jump that way after B at 102.
        // Instead make the gap at the open: A low = 103, B impulse, C high = 102.5 -> bullish FVG 102.5..103
        day2[0] = new double[]{102.8, 103.4, 103, 103.2, 1000};   // A low = 103
        day2[1] = new double[]{103.2, 105.5, 103.1, 105.2, 1000}; // B impulse
        day2[2] = new double[]{105.2, 105.6, 102.5, 105.0, 1000}; // C: low dips to 102.5 < 103 -> gap filled, no
        // C high is what matters for bullish FVG (high of bar after impulse is irrelevant);
        // bullish FVG: low of A (103) > high of C? No - FVG uses A.low vs C.high where C is 2 bars after A.
        // Set C high = 102.5 by making C a down bar from a gap-down open:
        day2[2] = new double[]{102.4, 102.5, 101.9, 102.2, 1000}; // C high = 102.5 < A low 103 -> FVG 102.5..103
        day2[3] = new double[]{102.2, 103.4, 102.1, 103.1, 1000};
        day2[4] = new double[]{103.1, 103.6, 102.9, 103.4, 1000}; // ltp 103.4 >= C.high region
        for (int i = 0; i < day2.length; i++) {
            bars.add(day2[i]);
            times.add(d2 + i * 300L);
        }
        // pad day2 to reach 40 total bars with flat bars after (ltp stays 103.4 zone)
        for (int i = 5; i < 12; i++) {
            bars.add(new double[]{103.4, 103.6, 103.2, 103.4, 900});
            times.add(d2 + i * 300L);
        }
        return toChart(bars, times);
    }

    static YahooClient.Chart toChart(java.util.List<double[]> bars, java.util.List<Long> times) {
        YahooClient.Chart ch = new YahooClient.Chart();
        int n = bars.size();
        ch.ts = new long[n]; ch.open = new double[n]; ch.high = new double[n];
        ch.low = new double[n]; ch.close = new double[n]; ch.volume = new long[n];
        for (int i = 0; i < n; i++) {
            ch.ts[i] = times.get(i);
            ch.open[i] = bars.get(i)[0]; ch.high[i] = bars.get(i)[1];
            ch.low[i] = bars.get(i)[2]; ch.close[i] = bars.get(i)[3];
            ch.volume[i] = (long) bars.get(i)[4];
        }
        return ch;
    }

    static double num(String s) {
        return (s == null || s.equals("None") || s.isEmpty()) ? Double.NaN : Double.parseDouble(s);
    }

    /** One IST day of 1-minute bars from 09:15 with the given closes (O/H/L = close). */
    static YahooClient.Chart momDay(java.time.LocalDate d, double[] closes) {
        java.time.ZoneId ist = java.time.ZoneId.of("Asia/Kolkata");
        long t0 = java.time.ZonedDateTime.of(d, java.time.LocalTime.of(9, 15), ist).toEpochSecond();
        YahooClient.Chart ch = new YahooClient.Chart();
        int n = closes.length;
        ch.ts = new long[n]; ch.open = new double[n]; ch.high = new double[n];
        ch.low = new double[n]; ch.close = new double[n]; ch.volume = new long[n];
        for (int i = 0; i < n; i++) {
            ch.ts[i] = t0 + i * 60L;
            ch.open[i] = ch.high[i] = ch.low[i] = ch.close[i] = closes[i];
            ch.volume[i] = 1000;
        }
        return ch;
    }

    /** Concatenate single-day charts into one multi-day tape (v54:
     *  the strangle's exit walk spans the entry and exit days). */
    static YahooClient.Chart concatDays(YahooClient.Chart... days) {
        int n = 0;
        for (YahooClient.Chart d : days) n += d.ts.length;
        YahooClient.Chart ch = new YahooClient.Chart();
        ch.ts = new long[n]; ch.open = new double[n]; ch.high = new double[n];
        ch.low = new double[n]; ch.close = new double[n]; ch.volume = new long[n];
        int k = 0;
        for (YahooClient.Chart d : days) {
            for (int i = 0; i < d.ts.length; i++) {
                ch.ts[k] = d.ts[i]; ch.open[k] = d.open[i]; ch.high[k] = d.high[i];
                ch.low[k] = d.low[i]; ch.close[k] = d.close[i]; ch.volume[k] = d.volume[i];
                k++;
            }
        }
        return ch;
    }

    public static void main(String[] args) throws Exception {
        String dir = args[0];
        YahooClient.Chart rel5 = loadCsv(dir + "/reliance_5m.csv");
        YahooClient.Chart nif5 = loadCsv(dir + "/nifty_5m.csv");
        YahooClient.Chart rel1d = loadCsv(dir + "/reliance_1d.csv");
        check(rel5.ts.length > 4000, "reliance 5m bars loaded: " + rel5.ts.length);

        // ---- scan() on the latest session ----
        Universe.Sym rel = new Universe.Sym("Reliance", "RELIANCE.NS", "NIFTY 50 companies");
        Analysis.Scan s = Analysis.scan(rel, rel5, rel1d);
        check(!Double.isNaN(s.ltp) && s.ltp > 1000 && s.ltp < 4000, "scan ltp sane: " + s.ltp);
        check(s.orReady, "opening range ready on latest session");
        check(s.orHigh > s.orLow, "OR high > low");
        System.out.println("  scan: setup=" + s.setup + " ltp=" + s.ltp + " dayPct=" + s.dayPct
                + " rsi=" + s.rsi + " dma20=" + s.dma20);
        if (s.hasPlan()) {
            check(s.stop == (s.orHigh + s.orLow) / 2, "stop is the range midpoint");
            double risk = Math.abs(s.entry - s.stop);
            check(Math.abs(Math.abs(s.target - s.entry) - risk) < 1e-6, "target is exactly 1R");
        }

        // ---- v41: break-bar relative volume + rupee risk line ----
        check(Math.abs(Analysis.rvolOf(new long[]{100, 100, 100, 300}, 3) - 3.0) < 1e-9,
                "rvolOf: 300 vs mean 100 = 3x");
        check(Math.abs(Analysis.rvolOf(new long[]{200, 100, 50}, 2) - 50.0 / 150.0) < 1e-9,
                "rvolOf: mean of earlier bars only");
        check(Double.isNaN(Analysis.rvolOf(new long[]{100, 100}, 0)), "rvolOf: no earlier bars = NaN");
        check(Double.isNaN(Analysis.rvolOf(new long[]{0, 0, 50}, 2)), "rvolOf: zero-volume feed = NaN");
        OptionsMath.Ticket tk41 = new OptionsMath.Ticket();
        tk41.premium = 50; tk41.premStop = 30;
        String rl = OptionsMath.riskLine(tk41, 100000, 1.0);
        check(rl.contains("50 units") && rl.contains("2,500") && rl.contains("1,000"),
                "riskLine: 1% of 1L over 20/unit risk = 50 units, 2500 cost: " + rl);
        OptionsMath.Ticket tkBig = new OptionsMath.Ticket();
        tkBig.premium = 3000; tkBig.premStop = 500;
        check(OptionsMath.riskLine(tkBig, 100000, 1.0).contains("too big for the rule"),
                "riskLine: unit risk above budget is flagged");
        OptionsMath.Ticket tkFlat = new OptionsMath.Ticket();
        tkFlat.premium = 40; tkFlat.premStop = 40;
        check(OptionsMath.riskLine(tkFlat, 100000, 1.0).isEmpty(),
                "riskLine: no premium drop at stop = no line");

        // ---- v42: gap fade, exactly the backtested form ----
        {
            java.time.ZoneId ISTz = java.time.ZoneId.of("Asia/Kolkata");
            long base = java.time.ZonedDateTime.of(2026, 10, 22, 9, 15, 0, 0, ISTz).toEpochSecond();
            YahooClient.Chart fc = new YahooClient.Chart();
            int nb = 10;
            fc.ts = new long[nb]; fc.open = new double[nb]; fc.high = new double[nb];
            fc.low = new double[nb]; fc.close = new double[nb]; fc.volume = new long[nb];
            double[][] bars = {
                {1010, 1012, 1008, 1011}, {1011, 1012, 1009, 1010}, {1010, 1011, 1008, 1009},
                {1009, 1010, 1007, 1008}, {1008, 1009, 1006, 1007}, {1007, 1008, 1005, 1006},
                {1006, 1007, 1004, 1005}, {1005, 1006, 1003, 1004}, {1004, 1005, 1002, 1003},
                {1003, 1004, 1001, 1002}};
            for (int i = 0; i < nb; i++) {
                fc.ts[i] = base + i * 300L;
                fc.open[i] = bars[i][0]; fc.high[i] = bars[i][1];
                fc.low[i] = bars[i][2]; fc.close[i] = bars[i][3];
                fc.volume[i] = 1000;
            }
            java.util.List<Integer> tidx = new java.util.ArrayList<>();
            for (int i = 0; i < nb; i++) tidx.add(i);
            Analysis.Scan fs = new Analysis.Scan();
            Analysis.computeFade(fs, fc, tidx, 1000);
            check(fs.hasFade(), "gap fade: +1% gap filling by 09:45 fires");
            check(!fs.fadeUp && fs.fadeEntry == 1005 && fs.fadeStop == 1012 && fs.fadeTarget == 998,
                    "gap fade: put fade levels entry 1005 stop 1012 target 998");
            check(Math.abs(fs.fadeGap - 1.0) < 1e-9 && fs.fadeTs == fc.ts[6],
                    "gap fade: gap +1.0% at the 09:45 bar");
            fc.close[6] = 1011; // 09:45 closes back ABOVE the open: gap not filling
            Analysis.Scan fs2 = new Analysis.Scan();
            Analysis.computeFade(fs2, fc, tidx, 1000);
            check(!fs2.hasFade(), "gap fade: no fade when the gap holds");
            fc.close[6] = 1005;
            Analysis.Scan fs3 = new Analysis.Scan();
            Analysis.computeFade(fs3, fc, tidx, 1009); // gap only +0.1%: below the 0.5% floor
            check(!fs3.hasFade(), "gap fade: sub-0.5% gaps are ignored");
        }

        // ---- v45: price-action fingerprint + Live Ledger ----
        {
            java.time.ZoneId ISTz = java.time.ZoneId.of("Asia/Kolkata");
            long base = java.time.ZonedDateTime.of(2026, 10, 22, 9, 15, 0, 0, ISTz).toEpochSecond();
            YahooClient.Chart pc = new YahooClient.Chart();
            int nb = 6;
            pc.ts = new long[nb]; pc.open = new double[nb]; pc.high = new double[nb];
            pc.low = new double[nb]; pc.close = new double[nb]; pc.volume = new long[nb];
            double[][] bars = {
                {101, 102, 100, 101}, {101, 102, 100, 101}, {101, 102, 100, 101},
                {100.9, 101.3, 100.7, 101.2},
                {101.0, 103.5, 100.9, 103.2},
                {103.2, 103.6, 102.8, 103.4}};
            for (int i = 0; i < nb; i++) {
                pc.ts[i] = base + i * 300L;
                pc.open[i] = bars[i][0]; pc.high[i] = bars[i][1];
                pc.low[i] = bars[i][2]; pc.close[i] = bars[i][3];
                pc.volume[i] = 1000;
            }
            Universe.Sym relSym2 = null;
            for (Universe.Sym u : Universe.all()) if ("RELIANCE.NS".equals(u.yahoo)) relSym2 = u;
            Analysis.Scan ps = Analysis.scan(relSym2, pc, null);
            check(ps.hasPlan() && ps.setup == Analysis.Setup.ORB_UP, "PA: synthetic breakout forms a plan");
            check(ps.paBody > 0.6, "PA: strong break bar body (" + ps.paBody + ")");
            check(ps.paEngulf, "PA: break bar engulfs the prior bar");
            check(ps.paThrust > 0, "PA: thrust into the break is positive");
            check(ps.paVwap == 1, "PA: break closes above the day VWAP");
            Ledger.Rec rec = Ledger.fromScan(ps, 72, false, 1, 40.0, java.time.LocalDate.of(2026, 10, 22));
            check(rec != null && "ORB_UP".equals(rec.kind) && rec.score == 72 && !rec.grade,
                    "ledger: ORB_UP record built with score + no grade");
            check(rec != null && Math.abs(rec.widthPct - (2.0 / 103.2 * 100)) < 0.01,
                    "ledger: range width recorded");
            check(rec != null && rec.paEngulf && rec.vixFirst == 1 && rec.hour == 9,
                    "ledger: price action + context ride along");
            check(Ledger.fromScan(ps, 72, false, 1, 40.0, java.time.LocalDate.of(2026, 10, 23)) == null,
                    "ledger: a stale day's plan is never recorded");
            Ledger.Rec back = Ledger.fromJson(Ledger.toJson(rec));
            check(back.entry == rec.entry && back.kind.equals(rec.kind) && back.paEngulf == rec.paEngulf
                    && back.outcome == 0, "ledger: JSON roundtrip keeps the record");
            java.util.List<Ledger.Rec> rs = new java.util.ArrayList<>();
            for (int i = 0; i < 4; i++) {
                Ledger.Rec q = new Ledger.Rec();
                q.yahoo = "X.NS"; q.kind = "ORB_DOWN"; q.date = "2026-10-2" + i;
                q.outcome = i < 3 ? 1 : -1; q.vixFirst = 1; q.widthPct = 0.5; q.score = 80;
                q.paBody = 0.8; q.grade = true;
                rs.add(q);
            }
            Ledger.Rec weak = new Ledger.Rec();
            weak.yahoo = "Y.NS"; weak.kind = "ORB_DOWN"; weak.date = "2026-10-20";
            weak.outcome = -1; weak.vixFirst = 0; weak.widthPct = 1.5; weak.score = 40; weak.paBody = 0.2;
            rs.add(weak);
            Ledger.Stats st = Ledger.summarize(rs);
            check(st.total == 5 && st.open == 0 && st.buckets.get("ALL")[0] == 5
                    && st.buckets.get("ALL")[1] == 3 && st.buckets.get("ALL")[2] == 2,
                    "ledger stats: totals + outcomes counted");
            check(st.buckets.get("VIXFIRST")[0] == 4 && st.buckets.get("NOVIX")[0] == 1,
                    "ledger stats: VIX buckets split");
            check(st.buckets.get("PA_STRONG")[0] == 4 && st.buckets.get("PA_WEAK")[0] == 1,
                    "ledger stats: price-action buckets split");
            check(st.buckets.get("NARROW")[0] == 4 && st.buckets.get("WIDE")[0] == 1
                    && st.buckets.get("GRADE")[0] == 4,
                    "ledger stats: width + grade buckets split");
            Ledger.Rec old = new Ledger.Rec();
            old.yahoo = "Z.NS"; old.kind = "ORB_UP"; old.date = "2026-01-01"; old.outcome = 1;
            java.util.List<Ledger.Rec> pr = Ledger.prune(java.util.Arrays.asList(old, weak),
                    java.time.LocalDate.of(2026, 10, 22));
            check(pr.size() == 1 && pr.get(0) == weak, "ledger prune: resolved records older than 120d drop");
        }

        if (s.hasPlan()) {
            OptionsMath.Ticket a = OptionsMath.ticket(s, 20, java.time.LocalDate.of(2026, 10, 22));
            OptionsMath.Ticket b = OptionsMath.ticket(s.sym, s.setup == Analysis.Setup.ORB_UP,
                    s.entry, s.stop, s.target, 20, java.time.LocalDate.of(2026, 10, 22));
            check(a.strike == b.strike && Math.abs(a.premium - b.premium) < 1e-9
                    && Math.abs(a.premStop - b.premStop) < 1e-9,
                    "ticket refactor: scan and primitive forms agree");
        }

        // ---- time machine odds sum to 100 ----
        if (s.hasPlan()) {
            Analysis.Odds o = Analysis.timeMachine(rel5, s, 2000);
            double sum = o.targetFirst + o.stopFirst + o.neither;
            check(Math.abs(sum - 100) < 0.5, "TM odds sum to 100: " + sum);
            check(o.paths == 2000, "TM paths honored");
            System.out.println("  TM: target " + o.targetFirst + " stop " + o.stopFirst
                    + " neither " + o.neither + " analogFires " + o.analogFires);
            int fs = Analysis.fullScore(s, o);
            check(fs >= 0 && fs <= 100, "fullScore in range: " + fs);
        }

        // ---- v46: Time Machine 2.0 similarity + previous-30-1m saving ----
        {
            check(Analysis.kernelW(0) == 1.0, "TM2: kernel is 1 at zero distance");
            check(Analysis.kernelW(2) < Analysis.kernelW(1) && Analysis.kernelW(1) < 1,
                    "TM2: kernel fades with distance");
            Analysis.Feat fa = new Analysis.Feat();
            fa.width = 0.5; fa.score = 80; fa.body = 0.8; fa.thrust = 1.0; fa.run = 1.2;
            fa.hour = 10.0; fa.struct = 1; fa.vwap = 1; fa.engulf = 1;
            Analysis.Feat fb = new Analysis.Feat();
            fb.width = 0.5; fb.score = 80; fb.body = 0.8; fb.thrust = 1.0; fb.run = 1.2;
            fb.hour = 10.0; fb.struct = 1; fb.vwap = 1; fb.engulf = 1;
            check(Analysis.featDistance(fa, fb) == 0, "TM2: identical setups have zero distance");
            fb.width = 1.5;
            check(Analysis.featDistance(fa, fb) == 1.0, "TM2: a 1pt width gap = distance 1");
            fb.width = 0.5; fb.struct = -1;
            check(Math.abs(Analysis.featDistance(fa, fb) - 1.4) < 1e-9, "TM2: opposite structure costs 1.4");

            // a one-day breakout chart: past-plan fingerprints ride through signalHistory
            java.time.ZoneId ISTz = java.time.ZoneId.of("Asia/Kolkata");
            long base = java.time.ZonedDateTime.of(2026, 10, 22, 9, 15, 0, 0, ISTz).toEpochSecond();
            YahooClient.Chart pc = new YahooClient.Chart();
            int nb = 12;
            pc.ts = new long[nb]; pc.open = new double[nb]; pc.high = new double[nb];
            pc.low = new double[nb]; pc.close = new double[nb]; pc.volume = new long[nb];
            double[][] bars = {
                {101, 102, 100, 101}, {101, 102, 100, 101}, {101, 102, 100, 101},
                {100.9, 101.3, 100.7, 101.2},
                {101.0, 101.4, 100.8, 101.1},
                {101.05, 101.3, 100.9, 101.15},
                {101.0, 103.5, 100.9, 103.2},
                {103.2, 103.6, 102.8, 103.4}, {103.4, 103.8, 103.0, 103.5},
                {103.5, 103.9, 103.1, 103.6}, {103.6, 104.0, 103.2, 103.7},
                {103.7, 104.1, 103.3, 103.8}};
            for (int i = 0; i < nb; i++) {
                pc.ts[i] = base + i * 300L;
                pc.open[i] = bars[i][0]; pc.high[i] = bars[i][1];
                pc.low[i] = bars[i][2]; pc.close[i] = bars[i][3];
                pc.volume[i] = 1000;
            }
            Universe.Sym relSym3 = null;
            for (Universe.Sym u : Universe.all()) if ("RELIANCE.NS".equals(u.yahoo)) relSym3 = u;
            java.util.List<Analysis.TestedSignal> hist = Analysis.signalHistory(pc, relSym3);
            check(hist.size() == 1 && hist.get(0).scan.paBody > 0.6,
                    "TM2: past plans carry their price-action fingerprint");
            check(hist.get(0).scan.runMove > 0, "TM2: past plans carry the 30m run-up");

            // previous 30 one-minute candles: extraction + features
            YahooClient.Chart m1 = new YahooClient.Chart();
            int nm = 45;
            m1.ts = new long[nm]; m1.open = new double[nm]; m1.high = new double[nm];
            m1.low = new double[nm]; m1.close = new double[nm]; m1.volume = new long[nm];
            long mBase = java.time.ZonedDateTime.of(2026, 10, 22, 9, 0, 0, 0, ISTz).toEpochSecond();
            for (int i = 0; i < nm; i++) {
                m1.ts[i] = mBase + i * 60L;
                m1.close[i] = 100 + i * 0.1; m1.open[i] = m1.close[i];
                m1.high[i] = m1.close[i]; m1.low[i] = m1.close[i]; m1.volume[i] = 10;
            }
            double[] run30 = Ledger.last30Closes(m1, m1.ts[40]);
            check(run30.length == 30 && run30[0] == 101.0 && Math.abs(run30[29] - 103.9) < 1e-9,
                    "ledger 1m: the 30 candles before the entry are saved");
            check(Ledger.last30Closes(m1, m1.ts[20]).length == 20,
                    "ledger 1m: fewer early in the day, taken as-is");
            check(Ledger.runMove1Of(run30) > 2.8 && Ledger.runMove1Of(run30) < 2.95,
                    "ledger 1m: run-up move computed");
            check(Ledger.runVol1Of(run30) >= 0 && !Double.isNaN(Ledger.runVol1Of(run30)),
                    "ledger 1m: run-up choppiness computed");
            Ledger.Rec rr = new Ledger.Rec();
            rr.yahoo = "X.NS"; rr.kind = "ORB_UP"; rr.date = "2026-10-22";
            rr.run = new double[]{100, 100.5, 101}; rr.runMove = 0.9;
            Ledger.Rec rb = Ledger.fromJson(Ledger.toJson(rr));
            check(rb.run.length == 3 && rb.run[2] == 101 && rb.runMove == 0.9,
                    "ledger: the saved 1m run survives JSON");

            // similar odds: identical live records dominate; distant ones earn nothing
            Analysis.Scan ps = Analysis.scan(relSym3, pc, null);
            Analysis.Feat lf = Analysis.featOf(ps, Analysis.liteScore(ps));
            java.util.List<Ledger.Rec> recs = new java.util.ArrayList<>();
            for (int i = 0; i < 12; i++) {
                Ledger.Rec q = new Ledger.Rec();
                q.yahoo = ps.sym.yahoo; q.kind = "ORB_UP"; q.outcome = 1;
                q.widthPct = lf.width; q.score = lf.score; q.paBody = lf.body;
                q.paThrust = lf.thrust; q.runMove = lf.run; q.hour = (int) lf.hour;
                q.paStruct = lf.struct; q.paVwap = lf.vwap; q.paEngulf = lf.engulf == 1;
                recs.add(q);
            }
            Analysis.Odds o2 = Analysis.timeMachine(pc, ps, 500, recs);
            check(o2.simOk && o2.simTarget > 99.0,
                    "TM2: 12 identical live records dominate the similar odds (" + o2.simTarget + ")");
            for (Ledger.Rec q : recs) {
                q.widthPct = lf.width + 30; q.score = 5; q.paBody = 0.0; q.paThrust = -9; q.runMove = -12;
            }
            Analysis.Odds o3 = Analysis.timeMachine(pc, ps, 500, recs);
            check(!o3.simOk, "TM2: dissimilar records cannot fake a match");
        }
        int lite = Analysis.liteScore(s);
        check(lite >= 0 && lite <= 100, "liteScore in range: " + lite + " why=" + s.why);

        // ---- backtest: combined universe sample ----
        List<Analysis.Trade> both = new ArrayList<>();
        both.addAll(Analysis.orbBacktest(rel5, 15, 1.0, 0));
        both.addAll(Analysis.orbBacktest(nif5, 15, 1.0, 0));
        check(both.size() > 40, "backtest produced trades: " + both.size());
        List<Analysis.Trade> longs = Analysis.orbBacktest(rel5, 15, 1.0, 1);
        List<Analysis.Trade> shorts = Analysis.orbBacktest(rel5, 15, 1.0, -1);
        check(longs.size() + shorts.size() == Analysis.orbBacktest(rel5, 15, 1.0, 0).size(),
                "long+short == both for reliance");
        for (Analysis.Trade t : longs) check(t.up, "long side contains only up trades");
        for (Analysis.Trade t : shorts) check(!t.up, "short side contains only down trades");
        for (Analysis.Trade t : both) {
            check(t.exit > 0 && t.entry > 0, "trade prices positive");
            check(Math.abs(t.retPct) < 30, "trade retPct sane: " + t.retPct);
            if ("TARGET".equals(t.reason)) {
                double expect = Math.abs(t.target - t.entry) / t.entry * 100 - 0.05;
                check(Math.abs(t.retPct - expect) < 0.01, "TARGET trade retPct = R distance - cost");
            }
            if ("STOP".equals(t.reason)) check(t.retPct < 0, "STOP trade loses");
        }
        Analysis.Stats st = Analysis.summarize(both);
        check(st.n == both.size(), "stats n matches");
        check(st.winPct >= 0 && st.winPct <= 100, "winPct in range: " + st.winPct);
        check(st.equity.length == st.n, "equity curve length == n");
        check(st.maxDd <= 0, "maxDd <= 0: " + st.maxDd);
        check(Math.abs(st.equity[st.n - 1] - st.total) < 1e-9, "equity ends at total");
        System.out.printf("  BACKTEST 60d ORB15 1R (REL+NIFTY): n=%d win=%.1f%% PF=%.2f exp=%.3f%% total=%.2f%% maxDD=%.2f%%%n",
                st.n, st.winPct, st.pf, st.expectancy, st.total, st.maxDd);

        // R multiple changes outcomes (2R should have fewer TARGET hits, bigger wins)
        Analysis.Stats st2 = Analysis.summarize(Analysis.orbBacktest(rel5, 15, 2.0, 0));
        check(st2.n == Analysis.orbBacktest(rel5, 15, 1.0, 0).size(), "2R keeps same trade count");

        // OR30 produces a different (valid) result set
        List<Analysis.Trade> or30 = Analysis.orbBacktest(rel5, 30, 1.0, 0);
        check(or30.size() > 0, "OR30 trades exist: " + or30.size());

        // ---- summarize() hand-computed ----
        List<Analysis.Trade> syn = new ArrayList<>();
        double[] rets = {2, -1, 3, -1.5};
        for (int i = 0; i < rets.length; i++) {
            Analysis.Trade t = new Analysis.Trade();
            t.retPct = rets[i]; t.breakTs = 1000 + i; t.up = true; t.entry = 100; t.exit = 100;
            syn.add(t);
        }
        Analysis.Stats ss = Analysis.summarize(syn);
        check(Math.abs(ss.total - 2.5) < 1e-9, "synthetic total 2.5");
        check(ss.wins == 2 && Math.abs(ss.winPct - 50) < 1e-9, "synthetic win% 50");
        check(Math.abs(ss.pf - 2.0) < 1e-9, "synthetic PF 2.0");
        check(Math.abs(ss.expectancy - 0.625) < 1e-9, "synthetic expectancy 0.625");
        check(Math.abs(ss.maxDd - (-1.5)) < 1e-9, "synthetic maxDd -1.5");

        // ---- outcomeOf agrees with backtest reasons on most trades ----
        List<Analysis.Trade> relTrades = Analysis.orbBacktest(rel5, 15, 1.0, 0);
        int agree = 0, total = 0;
        for (Analysis.Trade t : relTrades) {
            int o = Analysis.outcomeOf(rel5, t.breakTs, t.stop, t.target, t.up);
            int expect = "TARGET".equals(t.reason) ? 1 : "STOP".equals(t.reason) ? -1 : 0;
            total++;
            if (o == expect) agree++;
        }
        check(total == 0 || agree * 1.0 / total > 0.8,
                "outcomeOf agrees with backtest on " + agree + "/" + total);

        // ---- regime classifier ----
        Analysis.Scan vixHot = new Analysis.Scan();
        vixHot.ltp = 26; vixHot.dayPct = 3;
        String[] r1 = Analysis.regime(null, vixHot, 50);
        check("VOLATILE".equals(r1[0]), "regime VOLATILE on high VIX");
        Analysis.Scan nif = new Analysis.Scan();
        nif.ltp = 100; nif.dma20 = 95;
        Analysis.Scan vixCalm = new Analysis.Scan();
        vixCalm.ltp = 13; vixCalm.dayPct = 1;
        check("TREND UP".equals(Analysis.regime(nif, vixCalm, 60)[0]), "regime TREND UP");
        nif.ltp = 90;
        check("TREND DOWN".equals(Analysis.regime(nif, vixCalm, 40)[0]), "regime TREND DOWN");
        check("RANGE / MIXED".equals(Analysis.regime(nif, vixCalm, 50)[0]), "regime RANGE on mixed breadth");

        // ---- budget() unchanged behavior ----
        Analysis.Scan bs = new Analysis.Scan();
        bs.sym = rel; bs.setup = Analysis.Setup.ORB_UP;
        bs.entry = 1000; bs.stop = 990; bs.target = 1010;
        Analysis.Plan p = Analysis.budget(bs, 100000, 1.0);
        check(p.qty == 100, "budget qty = maxLoss/risk = 1000/10 = 100, got " + p.qty);
        check(Math.abs(p.positionValue - 100000) < 1e-6, "budget position value");

        // ---- options math (Black-Scholes) ----
        double cKnown = OptionsMath.bsPrice(100, 100, 365, 20, true);
        // hand-computed for r=6.5%: d1=0.425, d2=0.225 -> C = 66.46 - 55.20 = 11.26
        check(Math.abs(cKnown - 11.26) < 0.15, "BS known value C(100,100,1y,20%,r6.5) ~ 11.26, got " + cKnown);
        double pKnown = OptionsMath.bsPrice(100, 100, 365, 20, false);
        check(Math.abs((cKnown - pKnown) - (100 - 100 * Math.exp(-0.065))) < 0.05,
                "put-call parity holds: C-P=" + (cKnown - pKnown));
        double cItm = OptionsMath.bsPrice(26000, 25800, 3, 12, true);
        check(cItm > 200 && cItm < 320, "ITM call near intrinsic+time: " + cItm);
        double pOtm = OptionsMath.bsPrice(26000, 25000, 3, 12, false);
        check(pOtm > 0 && pOtm < 40, "far OTM put is cheap: " + pOtm);
        check(OptionsMath.atmStrike(25986, 50) == 26000, "ATM rounds to step");
        check(OptionsMath.atmStrike(25974, 50) == 25950, "ATM rounds down below half");
        check(OptionsMath.daysToExpiry(java.time.LocalDate.of(2026, 10, 27)) == 0, "Tue 27 Oct 2026 is expiry day");
        check(OptionsMath.daysToExpiry(java.time.LocalDate.of(2026, 10, 23)) == 4, "Fri -> next Tue is 4 days");
        check(OptionsMath.daysToExpiry(java.time.LocalDate.of(2026, 10, 22)) == 5, "Thu -> Tue is 5 days");

        // ---- news tone lexicon ----
        check(NewsClient.toneOf("Stocks surge to record high as profits beat estimates") > 0, "tone bullish");
        check(NewsClient.toneOf("Shares plunge after fraud probe widens") < 0, "tone bearish");
        check(NewsClient.toneOf("Company holds annual general meeting on Tuesday") == 0, "tone neutral");

        // ---- sector map covers the equity universe ----
        int covered = 0, equities = 0;
        for (Universe.Sym s2 : Universe.all()) {
            if (s2.yahoo.endsWith(".NS")) {
                equities++;
                if (SectorMap.sectorOf(s2.yahoo) != null) covered++;
            }
        }
        check(covered == equities, "sector map covers all " + equities + " equities (" + covered + ")");

        // ---- Greeks ----
        double dCall = OptionsMath.bsDelta(26000, 26000, 3, 12, true);
        double dPut = OptionsMath.bsDelta(26000, 26000, 3, 12, false);
        check(dCall > 0.35 && dCall < 0.75, "ATM call delta sane: " + dCall);
        check(Math.abs(dPut - (dCall - 1)) < 1e-9, "put delta = call delta - 1");
        double gExpiry = OptionsMath.bsGamma(26000, 26000, 0, 12);
        double gMonth = OptionsMath.bsGamma(26000, 26000, 30, 12);
        check(gExpiry > gMonth * 3, "gamma explodes into expiry: " + gExpiry + " vs " + gMonth);

        // ---- Smart Money Concepts on synthetic bars ----
        YahooClient.Chart synSweep = synthSweepChart();
        Smc.Report sr = Smc.analyze(synSweep);
        check(sr.sweepUp, "SMC detects the stop-hunt sweep above yesterday's high");
        check(!sr.sweepDown, "SMC no false sweep down");
        check(Math.abs(sr.prevHigh - 110) < 0.01, "SMC prev day high = 110: " + sr.prevHigh);
        check(sr.rangePosPct >= 0 && sr.rangePosPct <= 100, "SMC range position in bounds: " + sr.rangePosPct);

        YahooClient.Chart fvgChart = synthFvgChart();
        Smc.Report fr = Smc.analyze(fvgChart);
        check(fr.hasFvg && fr.fvgBullish, "SMC detects the bullish FVG");
        check(Math.abs(fr.fvgLow - 102.5) < 0.01 && Math.abs(fr.fvgHigh - 103) < 0.01,
                "SMC FVG zone is 102.5-103: " + fr.fvgLow + "-" + fr.fvgHigh);

        // real data sanity
        Smc.Report rr = Smc.analyze(rel5);
        check(rr.swingCount > 20, "SMC finds swings in real RELIANCE bars: " + rr.swingCount);
        if (!Double.isNaN(rr.liqAbove) && !Double.isNaN(rr.liqBelow)) {
            check(rr.liqAbove > rr.liqBelow, "SMC liquidity pools ordered");
        }
        check(Double.isNaN(rr.rvol) || rr.rvol > 0, "SMC rvol positive when present: " + rr.rvol);
        System.out.println("  SMC real: trend=" + rr.trend + " structure=" + rr.structure
                + " liqAbove=" + rr.liqAbove + " (" + rr.liqAboveTouches + " touches)"
                + " liqBelow=" + rr.liqBelow + " rvol=" + rr.rvol);

        // ---- istDate (stale-bar alert guard) ----
        java.time.ZoneId istZ = java.time.ZoneId.of("Asia/Kolkata");
        long e1 = java.time.ZonedDateTime.of(2026, 10, 22, 9, 15, 0, 0, istZ).toEpochSecond();
        check(Analysis.istDate(e1).equals(java.time.LocalDate.of(2026, 10, 22)),
                "istDate maps a morning IST epoch to the same IST date");
        long e2 = java.time.ZonedDateTime.of(2026, 10, 21, 20, 0, 0, 0,
                java.time.ZoneId.of("UTC")).toEpochSecond(); // 01:30 IST on Oct 22
        check(Analysis.istDate(e2).equals(java.time.LocalDate.of(2026, 10, 22)),
                "istDate rolls a late UTC evening into the next IST date");

        // ---- feedState (Yahoo freshness label) ----
        long fNow = java.time.ZonedDateTime.of(2026, 10, 23, 10, 0, 0, 0, istZ).toEpochSecond();
        check(Analysis.feedState(fNow - 300, fNow, true).equals("FEED OK"), "fresh bar = FEED OK");
        check(Analysis.feedState(fNow - 1500, fNow, true).equals("FEED DELAYED"), "25m old bar = FEED DELAYED");
        check(Analysis.feedState(fNow - 3600, fNow, true).equals("FEED STALE"), "1h old bar = FEED STALE");
        check(Analysis.feedState(fNow - 300, fNow, false).isEmpty(), "no label when market closed");
        check(Analysis.feedState(0, fNow, true).isEmpty(), "no label without bars");

        // ---- MarketCalendar (NSE 2026 holidays) ----
        check(MarketCalendar.isHoliday(java.time.LocalDate.of(2026, 10, 20)), "Dussehra Oct 20 2026 is a holiday");
        check(!MarketCalendar.isHoliday(java.time.LocalDate.of(2026, 10, 23)), "Oct 23 2026 is a trading day");
        check(!MarketCalendar.isHoliday(java.time.LocalDate.of(2026, 10, 22)), "Oct 22 2026 traded - not a holiday");
        check(MarketCalendar.isHoliday(java.time.LocalDate.of(2026, 11, 10)), "Diwali-Balipratipada Nov 10 2026 is a holiday");
        check(MarketCalendar.isHoliday(java.time.LocalDate.of(2026, 12, 25)), "Christmas Dec 25 2026 is a holiday");
        check(!MarketCalendar.isHoliday(null), "null date is not a holiday");

        // ---- topPlans (the top-3 notification picker) ----
        {
            java.util.List<Analysis.Scan> batch = new java.util.ArrayList<>();
            int[] sc5 = {50, 90, 70, 99, 10};
            Analysis.Setup[] st5 = {Analysis.Setup.ORB_UP, Analysis.Setup.ORB_DOWN,
                    Analysis.Setup.ORB_UP, Analysis.Setup.NONE, Analysis.Setup.ORB_DOWN};
            for (int i = 0; i < sc5.length; i++) {
                Analysis.Scan fs = new Analysis.Scan();
                fs.setup = st5[i];
                fs.entry = 100; fs.stop = 99; fs.target = 101;
                fs.dayPct = sc5[i]; // carries the fake score
                batch.add(fs);
            }
            java.util.List<Analysis.Scan> top = Analysis.topPlans(batch, 3, x -> (int) x.dayPct);
            check(top.size() == 3, "topPlans caps at 3");
            check((int) top.get(0).dayPct == 90 && (int) top.get(1).dayPct == 70
                    && (int) top.get(2).dayPct == 50,
                    "topPlans ranks by score and drops the no-plan NONE scan");
            Analysis.Scan noPlan = new Analysis.Scan();
            noPlan.setup = Analysis.Setup.ORB_UP; // but no entry/stop -> no plan
            check(Analysis.topPlans(java.util.Collections.singletonList(noPlan), 3, x -> 80).isEmpty(),
                    "topPlans excludes ORB setups without a plan");
            check(Analysis.topPlans(batch, 0, x -> (int) x.dayPct).isEmpty(),
                    "topPlans with 0 slots is empty");
        }

        // ---- dayPct vs yesterday's daily close (not the 5d window close) ----
        {
            java.time.ZoneId ist = java.time.ZoneId.of("Asia/Kolkata");
            java.time.LocalDate d1 = java.time.LocalDate.of(2026, 10, 22);
            java.time.LocalDate d2 = java.time.LocalDate.of(2026, 10, 23);
            java.util.List<Long> it = new java.util.ArrayList<>();
            java.util.List<double[]> ib = new java.util.ArrayList<>();
            for (int m = 0; m < 4; m++) {
                it.add(d2.atTime(9, 15 + m * 5).atZone(ist).toEpochSecond());
                double c = m == 3 ? 102 : 100 + m;
                ib.add(new double[]{c, c, c, c, 1000});
            }
            YahooClient.Chart intraD = toChart(ib, it);
            intraD.prevClose = 999; // the bogus 5d-window value scan() must NOT use
            java.util.List<Long> dt = new java.util.ArrayList<>();
            java.util.List<double[]> db = new java.util.ArrayList<>();
            dt.add(d1.atTime(9, 15).atZone(ist).toEpochSecond());
            db.add(new double[]{100, 100, 100, 100, 1000});
            dt.add(d2.atTime(9, 15).atZone(ist).toEpochSecond());
            db.add(new double[]{102, 102, 102, 102, 1000});
            YahooClient.Chart dailyD = toChart(db, dt);
            Analysis.Scan ds = Analysis.scan(rel, intraD, dailyD);
            check(Math.abs(ds.dayPct - 2.0) < 1e-9,
                    "dayPct uses yesterday's daily close (+2%), not chartPreviousClose");
        }

        // ---- alertWorthy + vixConfirms (the notification gates) ----
        {
            Analysis.Scan g = new Analysis.Scan();
            g.setup = Analysis.Setup.ORB_DOWN; g.entry = 1160; g.stop = 1170; g.target = 1150;
            g.orHigh = 1168; g.orLow = 1162; // 0.52% range - narrow under the width rule
            check(Analysis.alertWorthy(g, 70, 65), "alertWorthy: breakdown at score 70 passes");
            check(!Analysis.alertWorthy(g, 65, 65), "alertWorthy: score 65 is not above 65");
            g.breakTs = 2000;
            check(Analysis.vixConfirms(Analysis.Setup.ORB_UP, 1500, g),
                    "vixConfirms: VIX broken up first confirms a breakdown");
            check(!Analysis.vixConfirms(Analysis.Setup.ORB_UP, 2500, g),
                    "vixConfirms: VIX breaking later does not confirm");
            check(!Analysis.vixConfirms(Analysis.Setup.ORB_DOWN, 1500, g),
                    "vixConfirms: VIX breaking down does not confirm a breakdown");
            g.setup = Analysis.Setup.ORB_UP;
            check(!Analysis.vixConfirms(Analysis.Setup.ORB_DOWN, 1500, g),
                    "vixConfirms: breakouts never notify (validated under 46%)");
        }

        // ---- signalHistory (the Signal Test engine) on real Reliance bars ----
        {
            java.util.List<Analysis.TestedSignal> hist = Analysis.signalHistory(rel5, rel);
            check(!hist.isEmpty(), "signalHistory finds ORB signals in 60d of Reliance bars");
            boolean consistent = true, ordered = true;
            for (int i = 0; i < hist.size(); i++) {
                Analysis.TestedSignal t = hist.get(i);
                if (t.outcome < -1 || t.outcome > 1) consistent = false;
                if ("FADE".equals(t.kind)) {
                    // gap-fade rows resolve on their own levels; their
                    // tested form is their gate, so alertGrade is theirs
                    if (!t.scan.hasFade() || !t.alertGrade) consistent = false;
                    int oc = Analysis.outcomeOf(rel5, t.scan.fadeTs, t.scan.fadeStop, t.scan.fadeTarget,
                            t.scan.fadeUp);
                    if (oc != t.outcome) consistent = false;
                } else {
                    int oc = Analysis.outcomeOf(rel5, t.scan.breakTs, t.scan.stop, t.scan.target,
                            t.scan.setup == Analysis.Setup.ORB_UP);
                    if (oc != t.outcome) consistent = false;
                    if (t.alertGrade) consistent = false; // ORB: no VIX chart passed -> nothing is alert-grade
                }
                if (i > 0 && hist.get(i).day.isBefore(hist.get(i - 1).day)) ordered = false;
            }
            check(consistent, "signalHistory outcomes agree with outcomeOf; ORB needs a VIX chart for alert-grade, fades carry their own");
            check(ordered, "signalHistory is in day order");
            boolean mfeOk = true;
            for (Analysis.TestedSignal t : hist) {
                if (Double.isNaN(t.movePts) || t.movePts < 0) mfeOk = false;
                if ("FADE".equals(t.kind)) {
                    if (t.outcome > 0 && t.movePts < Math.abs(t.scan.fadeTarget - t.scan.fadeEntry) - 0.01) mfeOk = false;
                } else if (t.outcome > 0 && t.movePts < Math.abs(t.scan.target - t.scan.entry) - 0.01) mfeOk = false;
            }
            check(mfeOk, "move column: best post-entry move always reaches the target on target hits");
        }

        // ---- v50: dual exits - both targets computed, both judged ----
        {
            Analysis.Scan dt = new Analysis.Scan();
            dt.setup = Analysis.Setup.ORB_DOWN; dt.entry = 1000; dt.stop = 1010; dt.target = 990;
            check(Analysis.alertTarget(dt) == 980 && Analysis.alertTarget3(dt) == 970,
                    "dual targets: a breakdown alert carries 2R 980 and 3R 970 (2R unchanged)");
            dt.setup = Analysis.Setup.ORB_UP; dt.stop = 990; dt.target = 1010;
            check(Analysis.alertTarget(dt) == 1020 && Analysis.alertTarget3(dt) == 1030,
                    "dual targets: a breakout alert carries 2R 1020 and 3R 1030");

            java.time.ZoneId istD = java.time.ZoneId.of("Asia/Kolkata");
            long baseD = java.time.ZonedDateTime.of(2026, 10, 22, 9, 15, 0, 0, istD).toEpochSecond();
            long breakTsD = baseD + 600; // the 09:25 bar breaks; the walk starts at 09:30
            java.util.function.Function<double[][], YahooClient.Chart> day = walk -> {
                java.util.List<double[]> bars = new java.util.ArrayList<>();
                java.util.List<Long> times = new java.util.ArrayList<>();
                for (int i = 0; i < 3; i++) { bars.add(new double[]{1000, 1002, 998, 1000, 1000}); times.add(baseD + i * 300L); }
                for (int i = 0; i < walk.length; i++) { bars.add(walk[i]); times.add(baseD + (3 + i) * 300L); }
                return toChart(bars, times);
            };
            YahooClient.Chart bookThenStop = day.apply(new double[][]{
                    {995, 998, 975, 990, 1000}, {990, 1015, 988, 1012, 1000}, {1005, 1008, 1002, 1005, 1000}});
            check(Analysis.outcomeOf(bookThenStop, breakTsD, 1010, 980, false) == 1
                    && Analysis.outcomeOf(bookThenStop, breakTsD, 1010, 970, false) == -1,
                    "dual judging: 2R booked, then the stop before 3R -> (+1,-1)");
            YahooClient.Chart straight = day.apply(new double[][]{{1000, 1000, 965, 970, 1000}});
            check(Analysis.outcomeOf(straight, breakTsD, 1010, 980, false) == 1
                    && Analysis.outcomeOf(straight, breakTsD, 1010, 970, false) == 1,
                    "dual judging: one run through both targets -> (+1,+1)");
            YahooClient.Chart sameBar = day.apply(new double[][]{{1000, 1012, 965, 990, 1000}});
            check(Analysis.outcomeOf(sameBar, breakTsD, 1010, 980, false) == -1
                    && Analysis.outcomeOf(sameBar, breakTsD, 1010, 970, false) == -1,
                    "dual judging: a bar crossing stop and 3R resolves as stop on both walks (stop first)");
            YahooClient.Chart flatD = day.apply(new double[][]{
                    {1000, 1005, 985, 995, 1000}, {995, 1004, 986, 990, 1000}});
            check(Analysis.outcomeOf(flatD, breakTsD, 1010, 980, false) == 0
                    && Analysis.outcomeOf(flatD, breakTsD, 1010, 970, false) == 0,
                    "dual judging: neither target nor stop reached -> (0,0)");
            YahooClient.Chart upRun = day.apply(new double[][]{{1000, 1035, 1005, 1030, 1000}});
            check(Analysis.outcomeOf(upRun, breakTsD, 990, 1020, true) == 1
                    && Analysis.outcomeOf(upRun, breakTsD, 990, 1030, true) == 1,
                    "dual judging: breakout runs through 2R and 3R -> (+1,+1)");
        }

        // ---- v50: signalHistory judges both alert exits on real bars ----
        {
            java.util.List<Analysis.TestedSignal> hist = Analysis.signalHistory(rel5, rel);
            boolean dualOk = true;
            int judged = 0;
            for (Analysis.TestedSignal t : hist) {
                if ("FADE".equals(t.kind)) {
                    if (t.outcome2 != 0 || t.outcome3 != 0) dualOk = false; // fades carry no dual record
                    continue;
                }
                judged++;
                boolean up = t.scan.setup == Analysis.Setup.ORB_UP;
                int o2 = Analysis.outcomeOf(rel5, t.scan.breakTs, t.scan.stop, Analysis.alertTarget(t.scan), up);
                int o3 = Analysis.outcomeOf(rel5, t.scan.breakTs, t.scan.stop, Analysis.alertTarget3(t.scan), up);
                if (o2 != t.outcome2 || o3 != t.outcome3) dualOk = false;
                if (t.outcome3 == 1 && t.outcome2 != 1) dualOk = false; // 3R hit implies 2R was passed
                if (t.outcome2 == -1 && t.outcome3 != -1) dualOk = false; // same first crossing: stop on both
            }
            check(judged > 0 && dualOk,
                    "signalHistory: every ORB row judges 2R and 3R consistently; fades carry neither");
        }

        // ---- v50: the ledger's 3R field rides along, grading untouched ----
        {
            Ledger.Rec r3 = new Ledger.Rec();
            r3.yahoo = "TEST.NS"; r3.kind = "ORB_DOWN"; r3.date = "2026-10-22";
            r3.entry = 1000; r3.stop = 1010; r3.target = 990; r3.outcome = 1; r3.outcome3 = -1;
            Ledger.Rec back3 = Ledger.fromJson(Ledger.toJson(r3));
            check(back3.outcome == 1 && back3.outcome3 == -1,
                    "ledger: outcome3 survives the JSON round trip and outcome is untouched");
            Ledger.Rec legacy = Ledger.fromJson(new org.json.JSONObject(
                    "{\"y\":\"TEST.NS\",\"k\":\"ORB_DOWN\",\"d\":\"2026-10-22\",\"oc\":-1}"));
            check(legacy.outcome == -1 && legacy.outcome3 == 0,
                    "ledger: pre-v50 records load with outcome3 unset (0)");
        }

        // ---- Prediction Engine ----
        {
            Predict.Forecast pf = Predict.forecast(rel5);
            check(pf.combosTotal >= 1000, "Predict runs 1000+ combinations (got " + pf.combosTotal + ")");
            boolean sane = pf.lastClose > 0;
            for (int hi = 0; hi < 4; hi++) {
                if (pf.dir[hi] < -1 || pf.dir[hi] > 1) sane = false;
                if (pf.dir[hi] != 0 && (pf.consensus[hi] < 50 || pf.consensus[hi] > 100)) sane = false;
                if (Double.isNaN(pf.expPts[hi])) sane = false;
            }
            check(sane, "Predict forecast on Reliance is well-formed");
            check(pf.holdoutN > 0, "Predict judges itself on holdout days");
            Predict.Forecast pf2 = Predict.forecast(rel5);
            check(java.util.Arrays.equals(pf.dir, pf2.dir), "Predict is deterministic");
        }

        // ---- v47: self-grading + Prediction live track ----
        {
            check(PredictLive.familyWeight(10, 9) == 1.0, "live track: no weight change before 30 judged");
            check(PredictLive.familyWeight(40, 16) == 0.5, "live track: a family losing live votes softer");
            check(PredictLive.familyWeight(40, 24) == 1.25, "live track: a family winning live votes louder");
            check(PredictLive.familyWeight(40, 20) == 1.0, "live track: a coin-flip family stays neutral");
            check(PredictLive.correct(1, 100, 101) && !PredictLive.correct(1, 100, 99)
                    && PredictLive.correct(-1, 100, 99) && !PredictLive.correct(1, 100, 100),
                    "live track: direction judging is honest, flat judges nobody");
            java.util.Map<String, int[]> fams = new java.util.HashMap<>();
            fams.put("Momentum", new int[]{40, 16});
            java.util.Map<String, Double> wts = PredictLive.weightsOf(fams);
            check(wts.get("Momentum") == 0.5 && wts.get("RSI zones") == 1.0,
                    "live track: weights map covers every family");
            YahooClient.Chart fc25 = new YahooClient.Chart();
            int n25 = 25;
            fc25.ts = new long[n25]; fc25.open = new double[n25]; fc25.high = new double[n25];
            fc25.low = new double[n25]; fc25.close = new double[n25]; fc25.volume = new long[n25];
            long b25 = java.time.ZonedDateTime.of(2026, 10, 22, 9, 15, 0, 0,
                    java.time.ZoneId.of("Asia/Kolkata")).toEpochSecond();
            for (int i = 0; i < n25; i++) {
                fc25.ts[i] = b25 + i * 300L; fc25.close[i] = 100 + i;
                fc25.open[i] = fc25.close[i]; fc25.high[i] = fc25.close[i]; fc25.low[i] = fc25.close[i];
            }
            check(PredictLive.forwardClose(fc25, fc25.ts[0]) == 112.0, "live track: +60m close found");
            check(PredictLive.forwardClose(fc25, fc25.ts[20]) == 124.0,
                    "live track: day-end close used when the day runs out");
            check(Double.isNaN(PredictLive.forwardClose(fc25, fc25.ts[24])),
                    "live track: too few bars forward stays unjudged");
            Predict.Forecast pw0 = Predict.forecast(rel5, null);
            Predict.Forecast pfRef = Predict.forecast(rel5);
            check(java.util.Arrays.equals(pw0.dir, pfRef.dir), "live track: null weights change nothing");
            check(pw0.famDir60.length == 10, "live track: per-family votes are exposed");
            java.util.Map<String, Double> zero = new java.util.HashMap<>();
            for (String fn : Predict.FAMILY) zero.put(fn, 0.0);
            Predict.Forecast pwz = Predict.forecast(rel5, zero);
            boolean allZero = true;
            for (int d : pwz.dir) if (d != 0) allZero = false;
            for (int d : pwz.famDir60) if (d != 0) allZero = false;
            check(allZero, "live track: zero-weighted families cast no vote");

            java.util.List<Ledger.Rec> grec = new java.util.ArrayList<>();
            for (int i = 0; i < 40; i++) {
                Ledger.Rec q = new Ledger.Rec();
                q.yahoo = "X.NS"; q.date = "2026-10-2" + (i % 10); q.kind = "ORB_DOWN";
                boolean narrow = i < 20;
                q.widthPct = narrow ? 0.5 : 1.5;
                q.paBody = narrow ? 0.8 : 0.2;
                q.runVol1 = narrow ? 0.05 : 0.25;
                q.score = 40;
                q.outcome = narrow ? (i % 5 == 4 ? -1 : 1) : (i % 5 == 4 ? 1 : -1);
                grec.add(q);
            }
            Ledger.Stats gst = Ledger.summarize(grec);
            check(gst.buckets.get("RUN_CALM")[0] == 20 && gst.buckets.get("RUN_WILD")[0] == 20,
                    "grading: 1-minute run-up buckets split calm vs wild");
            Ledger.Grade grd = Ledger.gradeOf(grec);
            check(grd.baseline == 0.5 && grd.bestWin == 0.8, "grading: baseline + best context learned");
            Analysis.Scan good = new Analysis.Scan();
            good.setup = Analysis.Setup.ORB_DOWN; good.entry = 100; good.orHigh = 100.3; good.orLow = 99.8;
            good.stop = 100.05; good.target = 99.95;
            good.paBody = 0.8;
            Ledger.Adj aGood = Ledger.adjustFor(grd, good, 40);
            check(aGood.points == 4 && aGood.matched == 3,
                    "grading: a narrow strong plan earns +4 (got " + aGood.points + ")");
            Analysis.Scan bad = new Analysis.Scan();
            bad.setup = Analysis.Setup.ORB_DOWN; bad.entry = 100; bad.orHigh = 101.2; bad.orLow = 99.7;
            bad.stop = 100.45; bad.target = 99.55;
            bad.paBody = 0.2;
            check(Ledger.adjustFor(grd, bad, 40).points == -4, "grading: a wide weak plan is marked down");
            check(Ledger.gradeOf(grec.subList(0, 10)).baseline < 0, "grading: silent until 20 judged plans");
        }

        // ---- v48: speed pass - every number must be IDENTICAL ----
        {
            // golden values captured from the v47 engine on this fixture
            Predict.Forecast gf = Predict.forecast(rel5);
            check(java.util.Arrays.equals(gf.dir, new int[]{-1, -1, -1, 1}), "v48: forecast directions unchanged");
            check(Math.abs(gf.consensus[0] - 98.15124357060863) < 1e-9
                    && Math.abs(gf.consensus[3] - 87.80942786910465) < 1e-9,
                    "v48: forecast consensus unchanged");
            check(Math.abs(gf.expPts[2] - (-0.35224003094391665)) < 1e-9,
                    "v48: forecast expected points unchanged");
            check(Math.abs(gf.holdoutAcc - 55.74387947269303) < 1e-9 && gf.holdoutN == 531,
                    "v48: holdout judging unchanged");
            check(java.util.Arrays.equals(gf.famDir60, new int[]{-1, 0, 0, -1, -1, -1, -1, -1, -1, 0}),
                    "v48: per-family votes unchanged");
            check(gf.combosLive == 798, "v48: live combo count unchanged");
            check(Predict.forecast(rel5) == gf, "v48: a repeat forecast is the memo object itself");
            Analysis.Odds go = Analysis.timeMachine(rel5, s, 3000);
            check(go.analogTarget == 18 && go.analogStop == 15 && go.analogNeither == 2,
                    "v48: analog record unchanged");
            check(Math.abs(go.simTarget - 57.5954) < 0.001 && Math.abs(go.simEffN - 30.794) < 0.01,
                    "v48: similar-setup odds unchanged");
            check(Math.abs(s.entry - 1191.30) < 0.01 && Math.abs(s.stop - 1198.70) < 0.01
                    && Math.abs(s.target - 1183.90) < 0.01 && Analysis.liteScore(s) == 80,
                    "v48: scan levels + lite score unchanged");
            // IST date arithmetic == the zoned conversion, incl. IST midnights
            boolean datesOk = true;
            java.time.ZoneId istz = java.time.ZoneId.of("Asia/Kolkata");
            long probe = java.time.ZonedDateTime.of(2026, 9, 1, 0, 0, 0, 0, istz).toEpochSecond();
            for (int i = 0; i < 3000 && datesOk; i++) {
                long ts = probe + i * 1800L;
                java.time.LocalDate want = java.time.Instant.ofEpochSecond(ts).atZone(istz).toLocalDate();
                if (!Analysis.istDate(ts).equals(want)) datesOk = false;
            }
            for (long k = 20000; k < 21000 && datesOk; k++) {
                long ts = k * 86400L - 19800L; // exactly IST midnight
                java.time.LocalDate want = java.time.Instant.ofEpochSecond(ts).atZone(istz).toLocalDate();
                if (!Analysis.istDate(ts).equals(want)) datesOk = false;
            }
            check(datesOk, "v48: IST date arithmetic matches the zoned conversion everywhere probed");
        }

        // ---- v49: alert post-mortems ----
        {
            long pBase = java.time.ZonedDateTime.of(2026, 10, 22, 9, 15, 0, 0,
                    java.time.ZoneId.of("Asia/Kolkata")).toEpochSecond();
            java.util.function.BiFunction<double[], double[], YahooClient.Chart> mkDay = (his, los) -> {
                YahooClient.Chart c = new YahooClient.Chart();
                int n = 6;
                c.ts = new long[n]; c.open = new double[n]; c.high = new double[n];
                c.low = new double[n]; c.close = new double[n]; c.volume = new long[n];
                for (int i = 0; i < n; i++) {
                    c.ts[i] = pBase + i * 300L;
                    c.high[i] = his[i]; c.low[i] = los[i];
                    c.open[i] = 100; c.close[i] = 100; c.volume[i] = 10;
                }
                return c;
            };
            YahooClient.Chart nifty = mkDay.apply(
                    new double[]{0, 0, 0, 0, 0, 0}, new double[]{0, 0, 0, 0, 0, 0});
            for (int i = 0; i < 6; i++) nifty.close[i] = 100 + i * 0.25;
            java.util.function.IntFunction<Ledger.Rec> mkRec = oc -> {
                Ledger.Rec r = new Ledger.Rec();
                r.yahoo = "X.NS"; r.name = "X"; r.kind = "ORB_UP"; r.date = "2026-10-22";
                r.ts = pBase + 3 * 300L; r.entry = 100; r.stop = 99; r.target = 101; r.outcome = oc;
                return r;
            };
            // clean run: small dip, then target at bar 5 (10 minutes in)
            YahooClient.Chart clean = mkDay.apply(
                    new double[]{100, 100, 100, 100, 100.6, 101.2}, new double[]{100, 100, 100, 100, 99.8, 100.4});
            Ledger.Diag dA = Ledger.diagnose(clean, mkRec.apply(1), nifty);
            check("CLEAN_TARGET".equals(dA.klass) && dA.mins == 10 && Math.abs(dA.maeR - 0.2) < 1e-9,
                    "post-mortem: clean target run classified (" + dA.klass + ")");
            check(dA.mkt > 0.3, "post-mortem: market alignment signed with the trade");
            // teased: +0.9R first, then the stop
            YahooClient.Chart tease = mkDay.apply(
                    new double[]{100, 100, 100, 100, 100.9, 100.2}, new double[]{100, 100, 100, 100, 99.9, 98.9});
            Ledger.Diag dB = Ledger.diagnose(tease, mkRec.apply(-1), nifty);
            check("TEASED_STOP".equals(dB.klass) && Math.abs(dB.mfeR - 0.9) < 1e-9,
                    "post-mortem: teased-then-stopped classified");
            // dead: never moved, straight to the stop
            YahooClient.Chart dead = mkDay.apply(
                    new double[]{100, 100, 100, 100, 100.2, 100.1}, new double[]{100, 100, 100, 100, 99.5, 98.8});
            check("DEAD_STOP".equals(Ledger.diagnose(dead, mkRec.apply(-1), nifty).klass),
                    "post-mortem: dead break classified");
            // flat after a good run
            YahooClient.Chart flat = mkDay.apply(
                    new double[]{100, 100, 100, 100, 100.8, 100.5}, new double[]{100, 100, 100, 100, 99.7, 99.9});
            Ledger.Diag dD = Ledger.diagnose(flat, mkRec.apply(2), nifty);
            check("FLAT_RAN".equals(dD.klass) && dD.mins == 10, "post-mortem: ran-but-flat classified");
            java.util.Map<String, Integer> cc = Ledger.classCounts(java.util.Arrays.asList(dA, dB, dD));
            check(cc.size() == 3 && cc.get("TEASED_STOP") == 1, "post-mortem: class counts add up");
            Ledger.Rec sentRec = mkRec.apply(1);
            sentRec.sent = true;
            check(Ledger.fromJson(Ledger.toJson(sentRec)).sent, "post-mortem: the sent flag survives storage");
            check(Ledger.sentKeyOf(sentRec).equals("X.NS|10000|2026-10-22"),
                    "post-mortem: sent join key is symbol|entry|date");
        }

        // ---- options tickets + Tuesday expiries + 10-point alert gate ----
        {
            java.time.LocalDate fri = java.time.LocalDate.of(2026, 10, 23);
            check(OptionsMath.weeklyExpiry(fri).equals(java.time.LocalDate.of(2026, 10, 27)),
                    "weekly expiry is the next Tuesday (27 Oct 2026)");
            check(OptionsMath.monthlyExpiry(fri).equals(java.time.LocalDate.of(2026, 10, 27)),
                    "monthly expiry is the last Tuesday (27 Oct 2026)");
            // Review 26 Oct 2026: November's last Tuesday (24 Nov) is Guru Nanak
            // Jayanti, an NSE holiday, so the listed monthly expiry is Mon 23 Nov.
            check(OptionsMath.monthlyExpiry(java.time.LocalDate.of(2026, 10, 28))
                    .equals(java.time.LocalDate.of(2026, 11, 23)),
                    "monthly expiry rolls to November's (holiday-shifted) Mon 23 Nov");
            Analysis.Scan ps = new Analysis.Scan();
            ps.sym = rel; ps.setup = Analysis.Setup.ORB_DOWN;
            ps.entry = 1000; ps.stop = 1015; ps.target = 970;
            OptionsMath.Ticket tk = OptionsMath.ticket(ps, 25, fri);
            check(tk.premium > 0 && tk.premTarget > tk.premium && tk.premStop < tk.premium && tk.delta < 0,
                    "put ticket: target premium > entry premium > stop premium, delta negative");
            check(OptionsMath.strikeStepForPrice(10062) == 100, "strike step band for a ~10k stock is 100");
            check(OptionsMath.strikeStepFor("LT.NS", 3631) == 20, "LT strike grid is 20 (verified)");
            check(OptionsMath.strikeStepFor("BAJAJ-AUTO.NS", 10062) == 100, "BAJAJ-AUTO strike grid is 100 (verified)");
            check(OptionsMath.strikeStepFor("DRREDDY.NS", 1229) == 10, "DRREDDY strike grid is 10 (verified)");
            Analysis.Scan lt = new Analysis.Scan();
            lt.sym = new Universe.Sym("Larsen & Toubro", "LT.NS", "");
            lt.setup = Analysis.Setup.ORB_DOWN; lt.entry = 3631.70; lt.stop = 3651.95; lt.target = 3611.45;
            check(OptionsMath.ticket(lt, 21, fri).strike == 3640, "LT ATM strike lands on the listed 3640");
            Analysis.Scan small = new Analysis.Scan();
            small.setup = Analysis.Setup.ORB_DOWN; small.entry = 100; small.stop = 103; small.target = 97;
            check(!Analysis.alertWorthy(small, 90, 65), "alertWorthy: a plan with no measured range never notifies");
            Analysis.Scan narrow = new Analysis.Scan();
            narrow.setup = Analysis.Setup.ORB_DOWN;
            narrow.orHigh = 1004; narrow.orLow = 999; narrow.entry = 999; narrow.stop = 1001.5; narrow.target = 996.5;
            check(!Analysis.alertWorthy(narrow, 90, 65),
                    "alertWorthy: narrow range but a 2.5-point target never notifies (10-point floor)");
            narrow.target = 989;
            check(Analysis.alertWorthy(narrow, 90, 65),
                    "alertWorthy: narrow range (0.5%) with a 10-point target notifies");
            narrow.orHigh = 1010; narrow.orLow = 990;
            check(!Analysis.alertWorthy(narrow, 90, 65), "alertWorthy: a wide opening range (2%) never notifies");
            Analysis.Scan edge = new Analysis.Scan();
            edge.entry = 1000; edge.orHigh = 1008; edge.orLow = 1000;
            check(Analysis.rangeNarrow(edge), "width rule: a range of exactly 0.8% of price passes");
            edge.orHigh = 1009;
            check(!Analysis.rangeNarrow(edge), "width rule: a range of 0.9% of price fails");
            Analysis.Scan at = new Analysis.Scan();
            at.setup = Analysis.Setup.ORB_DOWN; at.entry = 1000; at.stop = 1010; at.target = 990;
            check(Analysis.alertTarget(at) == 980, "alertTarget: a breakdown alert exits at 2R");
            at.setup = Analysis.Setup.ORB_UP; at.stop = 990; at.target = 1010;
            check(Analysis.alertTarget(at) == 1020, "alertTarget: a breakout alert exits at 2R");
            OptionsMath.Ticket tkDeep = OptionsMath.ticket(ps, 25, fri, 940);
            check(tkDeep.premTarget > tk.premTarget, "ticket priced at the deeper 2R level is worth more (put)");
            Analysis.Scan fb = new Analysis.Scan();
            fb.breakTs = 1761193800L;
            check(!Analysis.breakBarClosed(fb, 1761193800L + 299), "break bar still forming 1s before close: no fire");
            check(Analysis.breakBarClosed(fb, 1761193800L + 300), "break bar closed at +300s: can fire");
            check(!Analysis.breakBarClosed(new Analysis.Scan(), 1761193800L + 9999), "no break: never closed");
        }

        // ---- after 15:30 only commodities work (user rule) ----
        {
            check(!Analysis.commodityOnlyHours(java.time.LocalTime.of(15, 29)),
                    "15:29 - stocks still working");
            check(Analysis.commodityOnlyHours(java.time.LocalTime.of(15, 30)),
                    "15:30 - commodities-only hours begin");
            check(Analysis.commodityOnlyHours(java.time.LocalTime.of(23, 59)),
                    "late evening - still commodities only");
            check(!Analysis.commodityOnlyHours(java.time.LocalTime.of(9, 0)),
                    "morning - everything works");
            check(Universe.isCommodity(new Universe.Sym("Gold (COMEX)", "GC=F", Universe.G_COMM)),
                    "GC=F is a commodity");
            check(!Universe.isCommodity(rel) && !Universe.isCommodity(null),
                    "a stock (and null) is not a commodity");
        }

        // ---- plan freshness labels for shared plans ----
        {
            Analysis.Scan bf = new Analysis.Scan();
            bf.setup = Analysis.Setup.ORB_DOWN;
            bf.entry = 1665.50; bf.stop = 1675.00; bf.target = 1656.00; bf.ltp = 1652.20;
            check("TARGET CROSSED".equals(Analysis.planFreshness(bf)),
                    "breakdown with LTP already under target is finished, not fresh");
            bf.ltp = 1660.00;
            check("LIVE".equals(Analysis.planFreshness(bf)), "breakdown between stop and target is live");
            bf.ltp = 1680.00;
            check("STOP CROSSED".equals(Analysis.planFreshness(bf)), "breakdown back above the stop is resolved");
            Analysis.Scan bu = new Analysis.Scan();
            bu.setup = Analysis.Setup.ORB_UP;
            bu.entry = 100; bu.stop = 95; bu.target = 105; bu.ltp = 106;
            check("TARGET CROSSED".equals(Analysis.planFreshness(bu)), "breakout past its target is finished");
            check("UNKNOWN".equals(Analysis.planFreshness(new Analysis.Scan())), "no plan - freshness unknown");
        }

        // ---- NSE free chain probe: mapping + parsing (no network) ----
        {
            check("equities:RELIANCE".equals(NseChain.endpointFor("RELIANCE.NS")),
                    "NSE probe: a stock maps to the equities endpoint");
            check("indices:NIFTY".equals(NseChain.endpointFor("^NSEI")),
                    "NSE probe: NIFTY maps to the indices endpoint");
            check("indices:BANKNIFTY".equals(NseChain.endpointFor("^NSEBANK")),
                    "NSE probe: BANKNIFTY maps to the indices endpoint");
            check(NseChain.endpointFor("GC=F") == null, "NSE probe: commodities have no NSE chain");
            check(NseChain.endpointFor("^INDIAVIX") == null, "NSE probe: VIX itself has no chain");
            check(NseChain.parse(null) == null && NseChain.parse("") == null && NseChain.parse("{}") == null,
                    "NSE probe: blocked/empty replies parse to null - never fabricated");
            String chain = "{\"records\":{\"expiryDates\":[\"27-Oct-2026\"],\"underlyingValue\":1000.0,\"data\":["
                    + "{\"strikePrice\":990,\"expiryDate\":\"27-Oct-2026\",\"CE\":{\"openInterest\":5000,\"changeinOpenInterest\":500,\"impliedVolatility\":18.0,\"lastPrice\":22.0},\"PE\":{\"openInterest\":9000,\"changeinOpenInterest\":-300,\"impliedVolatility\":19.0,\"lastPrice\":12.0}},"
                    + "{\"strikePrice\":1000,\"expiryDate\":\"27-Oct-2026\",\"CE\":{\"openInterest\":12000,\"changeinOpenInterest\":2000,\"impliedVolatility\":17.5,\"lastPrice\":14.0},\"PE\":{\"openInterest\":20000,\"changeinOpenInterest\":4500,\"impliedVolatility\":18.5,\"lastPrice\":13.5}},"
                    + "{\"strikePrice\":1010,\"expiryDate\":\"27-Oct-2026\",\"CE\":{\"openInterest\":7000,\"changeinOpenInterest\":100,\"impliedVolatility\":18.2,\"lastPrice\":9.0},\"PE\":{\"openInterest\":11000,\"changeinOpenInterest\":800,\"impliedVolatility\":19.1,\"lastPrice\":19.0}}]}}";
            NseChain.Probe pr = NseChain.parse(chain);
            check(pr != null && pr.strike == 1000 && pr.peOi == 20000 && pr.ceOi == 12000,
                    "NSE probe: ATM row picked at the underlying, OI read from the chain");
            check(pr != null && Math.abs(pr.pcr - (40000.0 / 24000.0)) < 0.01,
                    "NSE probe: PCR = total PE OI / total CE OI");
            check(pr != null && java.time.LocalDate.of(2026, 10, 27).equals(pr.expiry),
                    "NSE probe: expiry parsed from the chain");
            String ln = NseChain.line(pr, false);
            check(ln.contains("1000 PE") && ln.contains("OI 20.0k") && ln.contains("PCR"),
                    "NSE probe: summary line carries the real OI for the plan's side");
            check(pr != null && pr.addCeOi == 2600 && pr.addPeOi == 5300,
                    "NSE probe: gross OI added today summed per side (positives only)");
        }

        // ---- BullBear gauge (our own bull/bear power, pure math) ----
        {
            check(Math.abs(BullBear.oiScore(100, 300) - 0.75) < 1e-9,
                    "BullBear: OI additions share - put-side additions score bullish");
            check(BullBear.oiScore(0, 0) == 0.5, "BullBear: no OI added is neutral");
            check(Math.abs(BullBear.pcrScore(1.0) - 0.5) < 1e-9
                    && Math.abs(BullBear.pcrScore(3.0) - 0.75) < 1e-9,
                    "BullBear: PCR squash 1.0 -> 0.5, 3.0 -> 0.75");
            check(BullBear.momScore(0) == 0.5 && Math.abs(BullBear.momScore(1.0) - 0.7) < 1e-9
                    && BullBear.momScore(-10) == 0.03 && BullBear.momScore(Double.NaN) == 0.5,
                    "BullBear: momentum score centred, clamped, NaN-neutral");
            check(BullBear.bullPower(0, 100, 1.0, 0) == 75,
                    "BullBear: all-put additions + neutral rest = 75% bull power");
            check(BullBear.bullPower(2600, 5300, 40000.0 / 24000.0, 0) == 62
                    && "Bulls in control".equals(BullBear.verdict(62)),
                    "BullBear: fixture chain reads 62% bull - bulls in control");
            check(BullBear.line(2600, 5300, 40000.0 / 24000.0, 0).contains("Bull power 62%"),
                    "BullBear: gauge line leads with the bull percentage");
        }

        // ---- Market Meter factor engine (pure math) ----
        {
            check(Math.abs(Meter.vixLevelScore(12) - 0.3) < 1e-9 && Meter.vixLevelScore(5) == 1
                    && Meter.vixLevelScore(25) == -1 && Double.isNaN(Meter.vixLevelScore(Double.NaN)),
                    "Meter: VIX level - calm bullish, fear bearish, NaN absent");
            check(Meter.vixTrendScore(-10) == 1 && Meter.vixTrendScore(10) == -1 && Meter.vixTrendScore(37) == -1,
                    "Meter: VIX trend - a falling VIX scores bullish, a spike clamps at -1");
            check(Math.abs(Meter.vixFactor(12, -5) - 0.4) < 1e-9,
                    "Meter: VIX factor blends level and trend");
            check(Meter.momFactor(1.5, Double.NaN, Double.NaN) == 1
                    && Math.abs(Meter.momFactor(0, 102, 100) - 0.5) < 1e-9
                    && Double.isNaN(Meter.momFactor(Double.NaN, 100, 100)),
                    "Meter: momentum blends day move with position vs the 20DMA");
            check(Meter.rsiFactor(75) == 1 && Meter.rsiFactor(50) == 0 && Meter.rsiFactor(25) == -1,
                    "Meter: RSI centred on 50, saturating at 25/75");
            check(Math.abs(Meter.breadthFactor(30, 10) - 0.5) < 1e-9 && Double.isNaN(Meter.breadthFactor(0, 0)),
                    "Meter: breadth is the advance share spread; empty is absent");
            Meter.Result all = Meter.combine(1, 1, 1, 1, 1, 1);
            check(all.score == 100 && all.factorsUsed == 6, "Meter: all factors bullish = +100");
            Meter.Result one = Meter.combine(1, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
            check(one.score == 100 && one.factorsUsed == 1,
                    "Meter: a lone factor renormalizes to full weight");
            check(Meter.combine(1, -1, 0, 0, 0, 0).score == 15,
                    "Meter: OI +1 against PCR -1 at printed weights = +15");
            check("Strongly bullish".equals(Meter.zone(40)) && "Neutral".equals(Meter.zone(0))
                    && "Strongly bearish".equals(Meter.zone(-40)),
                    "Meter: zone labels at their boundaries");
        }

        // ---- v51: System Feed payloads + gating (pure, no network) ----
        {
            Universe.Sym relS = new Universe.Sym("Reliance", "RELIANCE.NS", "NIFTY 50 companies");
            String ts51 = "2026-10-23T22:45:00+05:30";

            Analysis.Scan orb = new Analysis.Scan();
            orb.sym = relS;
            orb.setup = Analysis.Setup.ORB_DOWN;
            orb.entry = 2900; orb.stop = 2910; orb.target = 2890;
            OptionsMath.Ticket tk51 = new OptionsMath.Ticket();
            tk51.call = false; tk51.strike = 2900; tk51.premium = 42.5;
            tk51.expiry = java.time.LocalDate.of(2026, 10, 27);
            org.json.JSONObject op = SystemFeed.orbPayload(orb, 82,
                    "RELIANCE.NS|ORB_DOWN|290000|288000|2026-10-23",
                    Analysis.alertTarget(orb), Analysis.alertTarget3(orb), tk51, false, ts51, null);
            check("signal".equals(op.getString("type")) && !op.getBoolean("test")
                    && "IraThinker".equals(op.getString("source")) && op.getInt("version") == 51,
                    "Feed ORB: envelope type/test/source/version");
            check("ORB_BREAKDOWN".equals(op.getString("strategy")), "Feed ORB: strategy name");
            check("Reliance".equals(op.getString("symbol")) && "RELIANCE.NS".equals(op.getString("yahooSymbol")),
                    "Feed ORB: display + yahoo symbols");
            check("BUY_PUT".equals(op.getString("side")), "Feed ORB: breakdown side is BUY_PUT");
            check(op.getDouble("entry") == 2900 && op.getDouble("stop") == 2910,
                    "Feed ORB: entry/stop from the plan");
            check(op.getDouble("target1") == 2880 && op.getDouble("target1") == Analysis.alertTarget(orb),
                    "Feed ORB: target1 is the 2R alert target");
            check(op.getDouble("target2") == 2870 && op.getDouble("target2") == Analysis.alertTarget3(orb),
                    "Feed ORB: target2 is the 3R runner target");
            check(op.getInt("score") == 82, "Feed ORB: IraScore rides along");
            check("RELIANCE.NS|ORB_DOWN|290000|288000|2026-10-23".equals(op.getString("signalId"))
                    && ts51.equals(op.getString("tsIST")),
                    "Feed ORB: signalId is the dedupe key, tsIST passes through");
            org.json.JSONObject oo = op.getJSONObject("option");
            check(oo.getDouble("strike") == 2900 && oo.getDouble("premium") == 42.5,
                    "Feed ORB: option strike/premium from the ticket");
            check("2026-10-27".equals(oo.getString("expiry")) && "PE".equals(oo.getString("kind")),
                    "Feed ORB: option expiry ISO + kind PE");

            Analysis.Scan orbUp = new Analysis.Scan();
            orbUp.sym = relS;
            orbUp.setup = Analysis.Setup.ORB_UP;
            orbUp.entry = 2900; orbUp.stop = 2890; orbUp.target = 2910;
            org.json.JSONObject up = SystemFeed.orbPayload(orbUp, 70, "K",
                    Analysis.alertTarget(orbUp), Analysis.alertTarget3(orbUp), null, false, ts51, null);
            check("BUY_CALL".equals(up.getString("side")) && "ORB_BREAKOUT".equals(up.getString("strategy"))
                    && "CE".equals(up.getJSONObject("option").getString("kind")),
                    "Feed ORB up: BUY_CALL / ORB_BREAKOUT / CE");
            check(up.getJSONObject("option").isNull("strike") && up.getJSONObject("option").isNull("premium")
                    && up.getJSONObject("option").isNull("expiry")
                    && up.has("entry") && up.has("target2") && up.has("score"),
                    "Feed ORB: no ticket -> option numbers null, keys all present");

            Analysis.Scan fd = new Analysis.Scan();
            fd.sym = relS;
            fd.fadeEntry = 2880; fd.fadeStop = 2905; fd.fadeTarget = 2855;
            fd.fadeUp = false; fd.fadeTs = 1761279300L;
            OptionsMath.Ticket ftk = new OptionsMath.Ticket();
            ftk.call = false; ftk.strike = 2880; ftk.premium = 30.25;
            ftk.expiry = java.time.LocalDate.of(2026, 10, 27);
            org.json.JSONObject fp = SystemFeed.fadePayload(fd,
                    "RELIANCE.NS|GAP_FADE|288000|285500|2026-10-23", ftk, false, ts51, null);
            check("GAP_FADE".equals(fp.getString("strategy")) && "BUY_PUT".equals(fp.getString("side"))
                    && fp.getDouble("entry") == 2880 && fp.getDouble("stop") == 2905
                    && fp.getDouble("target1") == 2855,
                    "Feed fade: strategy/side/levels from the fade plan");
            check(fp.isNull("target2") && fp.isNull("score"),
                    "Feed fade: target2 and score are null (1R exit, no IraScore)");
            check("PE".equals(fp.getJSONObject("option").getString("kind"))
                    && fp.getJSONObject("option").getDouble("strike") == 2880,
                    "Feed fade: option block as computed for fades");
            fd.fadeUp = true;
            org.json.JSONObject fu = SystemFeed.fadePayload(fd, "K2", null, false, ts51, null);
            check("BUY_CALL".equals(fu.getString("side")), "Feed fade up: side is BUY_CALL");

            check(SystemFeed.shouldSend(true, "machine-topic")
                    && !SystemFeed.shouldSend(false, "machine-topic")
                    && !SystemFeed.shouldSend(true, "")
                    && !SystemFeed.shouldSend(true, "   ")
                    && !SystemFeed.shouldSend(true, null),
                    "Feed gating: sends only when ON with a non-empty topic");
            check("ntfy.sh".equals(SystemFeed.normalizeServer(""))
                    && "ntfy.sh".equals(SystemFeed.normalizeServer(null))
                    && "Example.com".equals(SystemFeed.normalizeServer("https://Example.com/"))
                    && "ntfy.sh".equals(SystemFeed.normalizeServer("ntfy.sh")),
                    "Feed server: default ntfy.sh, scheme/slashes stripped");

            org.json.JSONObject tp = SystemFeed.testPayload(ts51);
            check(tp.getBoolean("test") && "TEST".equals(tp.getString("symbol"))
                    && "TEST".equals(tp.getString("strategy")) && "TEST".equals(tp.getString("signalId")),
                    "Feed test payload: test:true, symbol TEST");
            String now = SystemFeed.nowIst();
            check(now.length() == 25 && now.endsWith("+05:30") && now.charAt(10) == 'T',
                    "Feed tsIST: ISO-8601 with +05:30 offset");
        }

        // ---------- v52: XAU/USD gold on Binance ----------
        {
            // Universe: gold is present once, as a full commodity-group
            // symbol whose id is the Binance pair (not a Yahoo ticker).
            Universe.Sym gold = Universe.gold();
            check(gold != null && "GOLD (XAU/USD)".equals(gold.name)
                    && BinanceClient.PERP.equals(gold.yahoo)
                    && Universe.G_COMM.equals(gold.group),
                    "Gold universe: GOLD (XAU/USD) present, Binance id, commodity group");
            int goldCount = 0;
            for (Universe.Sym gs : Universe.all()) if (Universe.isGold(gs)) goldCount++;
            check(goldCount == 1, "Gold universe: exactly one gold symbol");
            check(Universe.isGold(gold) && Universe.isGoldYahoo("XAUUSDT")
                    && !Universe.isGoldYahoo("GC=F") && !Universe.isGold(null),
                    "Gold universe: isGold helpers pin XAUUSDT only");
            check(BinanceClient.isGold("XAUUSDT") && !BinanceClient.isGold("GC=F")
                    && !BinanceClient.isGold("^NSEI"),
                    "Binance routing: only XAUUSDT goes to Binance");

            // Feed selection: perp wins, PAXG only as fallback.
            check(BinanceClient.chooseFeed(true, true) == BinanceClient.Feed.PERP
                    && BinanceClient.chooseFeed(true, false) == BinanceClient.Feed.PERP
                    && BinanceClient.chooseFeed(false, true) == BinanceClient.Feed.PAXG
                    && BinanceClient.chooseFeed(false, false) == BinanceClient.Feed.NONE,
                    "Binance feed choice: perp > PAXG > none");
            // No network in the harness: the state stays NONE and the
            // labels must say so honestly (never a fake "live" tag).
            check(BinanceClient.lastFeed() == BinanceClient.Feed.NONE
                    && BinanceClient.feedTag().isEmpty()
                    && BinanceClient.feedLine().contains("no live answer"),
                    "Binance labels: cold state shows no feed tag, honest Detail line");

            // Kline parsing: Binance field order (openTime ms, O, H, L,
            // C, volume) -> epoch-second Chart arrays.
            String kj = "[[1792727100000,\"4010.0\",\"4012.5\",\"4009.0\",\"4011.0\",\"120.4\","
                    + "1792727159999,\"0\",10,\"60.0\",\"0\",\"0\"],"
                    + "[1792727160000,\"4011.0\",\"4013.0\",\"4010.5\",\"4012.0\",\"30.6\","
                    + "1792727219999,\"0\",8,\"15.0\",\"0\",\"0\"]]";
            YahooClient.Chart kc = BinanceClient.parseKlines(kj);
            check(kc.ts.length == 2 && kc.ts[0] == 1792727100L && kc.ts[1] == 1792727160L,
                    "Binance klines: open times land as epoch seconds");
            check(kc.open[0] == 4010.0 && kc.high[0] == 4012.5 && kc.low[0] == 4009.0
                    && kc.close[1] == 4012.0 && kc.volume[0] == 120 && kc.volume[1] == 31,
                    "Binance klines: OHLC + rounded volume parsed");

            // IST conversion of a known bar: 03:45 UTC = 09:15 IST,
            // and the IST date boundary sits at 18:30 UTC.
            java.time.ZoneId ist = java.time.ZoneId.of("Asia/Kolkata");
            check(java.time.LocalDate.of(2026, 10, 23).equals(Analysis.istDate(1792727100L))
                    && java.time.LocalTime.of(9, 15).equals(java.time.Instant
                            .ofEpochSecond(1792727100L).atZone(ist).toLocalTime()),
                    "Binance IST: 03:45 UTC bar is 09:15 IST, Fri 2026-10-23");
            String bj = "[[1792780140000,\"4010.0\",\"4011.0\",\"4009.0\",\"4010.0\",\"10.0\","
                    + "1792780199999,\"0\",1,\"0\",\"0\",\"0\"],"
                    + "[1792780200000,\"4010.0\",\"4011.0\",\"4009.0\",\"4010.0\",\"10.0\","
                    + "1792780259999,\"0\",1,\"0\",\"0\",\"0\"]]";
            YahooClient.Chart bc = BinanceClient.parseKlines(bj);
            check(YahooClient.indicesOfDay(bc, java.time.LocalDate.of(2026, 10, 23)).size() == 1
                    && YahooClient.indicesOfDay(bc, java.time.LocalDate.of(2026, 10, 24)).size() == 1
                    && YahooClient.indicesOfDay(bc, java.time.LocalDate.of(2026, 10, 24)).get(0) == 1,
                    "Binance IST: 18:29 UTC bar is Fri IST, 18:30 UTC bar is Sat IST");

            // 1m -> 5m resample: epoch buckets are IST buckets (the
            // +5:30 offset is a whole number of 5-minute buckets).
            YahooClient.Chart m1 = new YahooClient.Chart();
            m1.ts = new long[]{1792727100L, 1792727160L, 1792727220L, 1792727280L, 1792727340L, 1792727400L};
            m1.open = new double[]{4010, 4011, 4012, 4010, 4014, 4013};
            m1.high = new double[]{4012.5, 4013, 4012, 4015, 4014, 4021};
            m1.low = new double[]{4009, 4010.5, 4008, 4010, 4009.5, 4012};
            m1.close = new double[]{4011, 4012, 4010, 4014, 4013, 4020};
            m1.volume = new long[]{100, 200, 300, 400, 500, 600};
            YahooClient.Chart m5 = BinanceClient.resample5m(m1);
            check(m5.ts.length == 2 && m5.ts[0] == 1792727100L && m5.ts[1] == 1792727400L,
                    "Binance resample: six 1m bars -> two 5m bars on IST boundaries");
            check(m5.open[0] == 4010 && m5.high[0] == 4015 && m5.low[0] == 4008
                    && m5.close[0] == 4013 && m5.volume[0] == 1500
                    && m5.open[1] == 4013 && m5.close[1] == 4020 && m5.volume[1] == 600,
                    "Binance resample: OHLCV aggregated correctly");

            // Range language -> lookback days (what the engine asks for).
            check(BinanceClient.lookbackDays("1d") == 1 && BinanceClient.lookbackDays("5d") == 5
                    && BinanceClient.lookbackDays("60d") == 60 && BinanceClient.lookbackDays("3mo") == 93
                    && BinanceClient.lookbackDays("6mo") == 186 && BinanceClient.lookbackDays("1y") == 365
                    && BinanceClient.lookbackDays("junk") == 5,
                    "Binance lookback: Yahoo ranges map to calendar days");

            // Overnight window split: before 08:30 IST is gold-only,
            // every day - the predicate is clock-only, so a Saturday
            // 03:00 is gold-only too (Binance never closes for the NSE
            // weekend); the main window keeps its 08:30-24:00 shape.
            check(Analysis.goldOnlyHours(java.time.LocalTime.of(0, 0))
                    && Analysis.goldOnlyHours(java.time.LocalTime.of(3, 0))
                    && Analysis.goldOnlyHours(java.time.LocalTime.of(8, 29))
                    && !Analysis.goldOnlyHours(java.time.LocalTime.of(8, 30))
                    && !Analysis.goldOnlyHours(java.time.LocalTime.of(15, 30))
                    && !Analysis.goldOnlyHours(java.time.LocalTime.of(23, 59)),
                    "Gold overnight: 00:00-08:30 gold-only, 08:30-24:00 full engine");
        }

        // ---------- v53: BANKNIFTY mom60 watcher ----------
        {
            java.time.LocalDate mon = java.time.LocalDate.of(2026, 10, 26); // Monday
            double iv = 12.0;

            // ret60: exact two-point value; undefined before 61 bars exist.
            double[] c1 = new double[375];
            java.util.Arrays.fill(c1, 58000);
            c1[60] = 58348; // +0.60% vs bar 0
            YahooClient.Chart ch1 = momDay(mon, c1);
            check(Math.abs(Mom60.ret60At(ch1, 60) - 0.60) < 1e-9,
                    "mom60 ret60: close[60] vs close[0] is +0.60% exactly");
            check(Double.isNaN(Mom60.ret60At(ch1, 59)),
                    "mom60 ret60: no bar 60 minutes before bar 59 -> NaN");

            // Fires up at the first qualifying bar; resolves TIME 60m later.
            double[] c2 = new double[375];
            java.util.Arrays.fill(c2, 58000);
            for (int i = 70; i < c2.length; i++) c2[i] = 58340; // +0.586% step at bar 70
            YahooClient.Chart ch2 = momDay(mon, c2);
            Mom60.Watcher w2 = new Mom60.Watcher(null, 0, Long.MIN_VALUE);
            java.util.List<Mom60.Event> ev = w2.process(ch2, mon, ch2.ts[70] + 90, true, iv);
            check(ev.size() == 1 && ev.get(0).fired != null && ev.get(0).fired.call,
                    "mom60 fire: +0.586% step fires a CE");
            check(ev.get(0).fired.strike == 58300 && ev.get(0).fired.spot == 58340,
                    "mom60 fire: ATM strike 58300 off spot 58340 (step 100)");
            check(ev.get(0).fired.entryTs == ch2.ts[70] + 60
                            && ev.get(0).fired.exitTs == ev.get(0).fired.entryTs + 3600,
                    "mom60 fire: entry at the bar close, exit 60 minutes later");
            check(w2.process(ch2, mon, ch2.ts[70] + 90, true, iv).isEmpty(),
                    "mom60 dedupe: reprocessing the same bars fires nothing twice");
            ev = w2.process(ch2, mon, ch2.ts[130] + 60, true, iv);
            check(ev.size() == 1 && ev.get(0).resolved != null
                            && "TIME".equals(ev.get(0).resolved.reason)
                            && ev.get(0).resolved.exitBarTs == ch2.ts[130],
                    "mom60 resolve: flat path exits TIME at the +60m bar");
            check(Math.abs(ev.get(0).resolved.pnlPct
                            - (ev.get(0).resolved.exitPremium
                            / ev.get(0).resolved.signal.entryPremium - 1) * 100) < 1e-9,
                    "mom60 resolve: P&L% is exit/entry premium - 1");

            // A watcher rebuilt from the log (open signal, walked to
            // its signal bar) does not re-fire the same bar.
            Mom60.Signal s2 = Mom60.buildSignal(ch2.ts[70], true, 0.586, 58340, mon, iv);
            Mom60.Watcher w2b = new Mom60.Watcher(s2, s2.entryTs, s2.barTs);
            check(w2b.process(ch2, mon, ch2.ts[70] + 90, true, iv).isEmpty(),
                    "mom60 dedupe: rebuilt watcher never re-fires its open signal's bar");

            // Fires down as a PE; a sub-threshold move never fires;
            // alerts-off never fires.
            double[] c3 = new double[375];
            java.util.Arrays.fill(c3, 58000);
            for (int i = 70; i < c3.length; i++) c3[i] = 57660; // -0.586%
            YahooClient.Chart ch3 = momDay(mon, c3);
            Mom60.Watcher w3 = new Mom60.Watcher(null, 0, Long.MIN_VALUE);
            ev = w3.process(ch3, mon, ch3.ts[70] + 90, true, iv);
            check(ev.size() == 1 && ev.get(0).fired != null && !ev.get(0).fired.call
                            && ev.get(0).fired.strike == 57700,
                    "mom60 fire: -0.586% step fires a PE at strike 57700");
            double[] c4 = new double[375];
            java.util.Arrays.fill(c4, 58000);
            for (int i = 70; i < c4.length; i++) c4[i] = 58232; // +0.40% only
            Mom60.Watcher w4 = new Mom60.Watcher(null, 0, Long.MIN_VALUE);
            check(w4.process(momDay(mon, c4), mon, ch3.ts[200] + 90, true, iv).isEmpty(),
                    "mom60 threshold: +0.40% step stays under the 0.50% bar - no fire");
            Mom60.Watcher w4b = new Mom60.Watcher(null, 0, Long.MIN_VALUE);
            check(w4b.process(ch2, mon, ch2.ts[70] + 90, false, iv).isEmpty(),
                    "mom60 switch: canFire=false consumes bars but never fires");
            double[] c4c = new double[375];
            java.util.Arrays.fill(c4c, 58000);
            c4c[70] = 58340; // one-bar spike: only bar 70 ever qualifies
            Mom60.Watcher w4c = new Mom60.Watcher(null, 0, Long.MIN_VALUE);
            check(w4c.process(momDay(mon, c4c), mon, ch2.ts[70] + 60 + 600, true, iv).isEmpty(),
                    "mom60 freshness: a one-bar trigger first seen 10 minutes late is not fired");

            // Fewer than 61 bars: nothing can be judged, nothing fires.
            double[] c5 = new double[60];
            java.util.Arrays.fill(c5, 58000);
            c5[59] = 59000;
            Mom60.Watcher w5 = new Mom60.Watcher(null, 0, Long.MIN_VALUE);
            YahooClient.Chart ch5 = momDay(mon, c5);
            check(w5.process(ch5, mon, ch5.ts[59] + 90, true, iv).isEmpty(),
                    "mom60 warm-up: 60 bars of tape cannot fire (needs 61)");

            // Last entry: the bar closing 14:30 fires; 14:31 does not.
            double[] c6 = new double[375];
            java.util.Arrays.fill(c6, 58000);
            for (int i = 314; i < c6.length; i++) c6[i] = 58340;
            YahooClient.Chart ch6 = momDay(mon, c6);
            Mom60.Watcher w6 = new Mom60.Watcher(null, 0, Long.MIN_VALUE);
            ev = w6.process(ch6, mon, ch6.ts[314] + 90, true, iv);
            check(ev.size() == 1 && ev.get(0).fired != null
                            && ev.get(0).fired.exitTs == Mom60.closeEpoch(mon),
                    "mom60 window: bar closing 14:30 fires, hold ends at the 15:30 close");
            double[] c7 = new double[375];
            java.util.Arrays.fill(c7, 58000);
            for (int i = 315; i < c7.length; i++) c7[i] = 58340;
            Mom60.Watcher w7 = new Mom60.Watcher(null, 0, Long.MIN_VALUE);
            YahooClient.Chart ch7 = momDay(mon, c7);
            check(w7.process(ch7, mon, ch7.ts[315] + 90, true, iv).isEmpty(),
                    "mom60 window: bar closing 14:31 cannot fit the hold - no fire");

            // One at a time, the stop, entry spacing, re-arming.
            double[] c8 = new double[375];
            java.util.Arrays.fill(c8, 58000);
            for (int i = 70; i < 80; i++) c8[i] = 58340;   // fire #1 (CE 58300)
            for (int i = 80; i < 85; i++) c8[i] = 58680;   // qualifies again - but a position is open
            c8[85] = 57900;                                 // crash: premium through the stop
            for (int i = 86; i < c8.length; i++) c8[i] = 58680;
            YahooClient.Chart ch8 = momDay(mon, c8);
            Mom60.Watcher w8 = new Mom60.Watcher(null, 0, Long.MIN_VALUE);
            ev = w8.process(ch8, mon, ch8.ts[70] + 90, true, iv);
            check(ev.size() == 1 && ev.get(0).fired != null && ev.get(0).fired.barTs == ch8.ts[70],
                    "mom60 sequence: first fire at bar 70");
            ev = w8.process(ch8, mon, ch8.ts[100] + 90, true, iv);
            check(ev.size() == 1 && ev.get(0).resolved != null
                            && "STOP".equals(ev.get(0).resolved.reason)
                            && ev.get(0).resolved.exitBarTs == ch8.ts[85],
                    "mom60 sequence: crash bar resolves STOP; the +30m trigger is blocked (open, then spacing)");
            ev = w8.process(ch8, mon, ch8.ts[130] + 90, true, iv);
            check(ev.size() == 1 && ev.get(0).fired != null && ev.get(0).fired.barTs == ch8.ts[130],
                    "mom60 sequence: re-arms - next fire exactly one spacing (60m) after the first entry");
            ev = w8.process(ch8, mon, ch8.ts[190] + 60, true, iv);
            check(ev.size() == 1 && ev.get(0).resolved != null
                            && "TIME".equals(ev.get(0).resolved.reason)
                            && ev.get(0).resolved.exitBarTs == ch8.ts[190],
                    "mom60 sequence: second position exits TIME at its +60m bar");

            // Stop beats time when both land on the exit bar.
            double[] c9 = new double[375];
            java.util.Arrays.fill(c9, 58000);
            for (int i = 70; i < 130; i++) c9[i] = 58340;
            c9[130] = 57900; // the exit bar itself crashes through the stop
            for (int i = 131; i < c9.length; i++) c9[i] = 57900;
            YahooClient.Chart ch9 = momDay(mon, c9);
            Mom60.Watcher w9 = new Mom60.Watcher(null, 0, Long.MIN_VALUE);
            w9.process(ch9, mon, ch9.ts[70] + 90, true, iv);
            ev = w9.process(ch9, mon, ch9.ts[130] + 60, true, iv);
            check(ev.size() == 1 && ev.get(0).resolved != null
                            && "STOP".equals(ev.get(0).resolved.reason)
                            && ev.get(0).resolved.exitBarTs == ch9.ts[130],
                    "mom60 ordering: stop-first when the exit bar is also the stop bar");

            // Strike rounding at the 100-point step.
            check(Mom60.buildSignal(0, true, 0.6, 58249, mon, iv).strike == 58200
                            && Mom60.buildSignal(0, true, 0.6, 58251, mon, iv).strike == 58300
                            && Mom60.buildSignal(0, true, 0.6, 58000, mon, iv).strike == 58000,
                    "mom60 strike: ATM rounds to the nearest 100");

            // Entry premium is the fractional-days BS at the entry
            // moment; the stop is exactly 70% of it; the int-days
            // bsPrice and the fractional overload agree.
            Mom60.Signal sb = Mom60.buildSignal(ch2.ts[70], true, 0.586, 58340, mon, iv);
            double expect = OptionsMath.bsPriceDays(58340, 58300,
                    Mom60.daysToExpiryFrac(sb.entryTs, sb.expiry), iv, true);
            check(Math.abs(sb.entryPremium - expect) < 1e-9 && sb.entryPremium > 0,
                    "mom60 premium: entry premium is BS at the entry moment (fractional days)");
            check(Math.abs(sb.stopPremium - sb.entryPremium * 0.70) < 1e-9,
                    "mom60 premium: stop level is entry x 0.70 (the 30% premium stop)");
            check(OptionsMath.bsPrice(58340, 58300, 5, 12, true)
                            == OptionsMath.bsPriceDays(58340, 58300, 5.0, 12, true),
                    "mom60 premium: bsPrice(int days) == bsPriceDays(fractional) at whole days");

            // Monthly expiry - the study traded the MONTH contract:
            // late October's signal takes November's monthly, not next
            // week's weekly. (Review 26 Oct 2026: November's last Tuesday,
            // 24 Nov, is Guru Nanak Jayanti, so the contract expires Mon 23 Nov.)
            Mom60.Signal sm = Mom60.buildSignal(0, true, 0.6, 58340,
                    java.time.LocalDate.of(2026, 10, 28), iv);
            check(sm.expiry.equals(java.time.LocalDate.of(2026, 11, 23))
                            && !sm.expiry.equals(OptionsMath.weeklyExpiry(java.time.LocalDate.of(2026, 10, 28))),
                    "mom60 expiry: nearest MONTHLY expiry (last Tuesday), never the weekly");
            check(sb.expiry.equals(java.time.LocalDate.of(2026, 10, 27)),
                    "mom60 expiry: Mon 26 Oct takes the Tue 27 Oct monthly");

            // A missing minute in the tape: ret60 is undefined at the
            // bar whose partner is gone, and that bar cannot fire.
            double[] cg = new double[130];
            java.util.Arrays.fill(cg, 58000);
            for (int i = 70; i < cg.length; i++) cg[i] = 58340;
            YahooClient.Chart full = momDay(mon, cg);
            YahooClient.Chart gap = new YahooClient.Chart();
            int gn = full.ts.length - 1;
            gap.ts = new long[gn]; gap.open = new double[gn]; gap.high = new double[gn];
            gap.low = new double[gn]; gap.close = new double[gn]; gap.volume = new long[gn];
            int gi = 0;
            for (int i = 0; i < full.ts.length; i++) {
                if (i == 10) continue; // drop the 09:25 bar
                gap.ts[gi] = full.ts[i]; gap.open[gi] = full.open[i]; gap.high[gi] = full.high[i];
                gap.low[gi] = full.low[i]; gap.close[gi] = full.close[i]; gap.volume[gi] = full.volume[i];
                gi++;
            }
            int gapIdx = Mom60.indexOfTs(gap, full.ts[70]);
            check(gapIdx > 0 && Double.isNaN(Mom60.ret60At(gap, gapIdx)),
                    "mom60 gaps: ret60 undefined when the bar 60 minutes earlier is missing");
            Mom60.Watcher wg = new Mom60.Watcher(null, 0, Long.MIN_VALUE);
            check(wg.process(gap, mon, full.ts[70] + 90, true, iv).isEmpty(),
                    "mom60 gaps: the bar with the missing partner cannot fire");

            // Alert text carries the contract, the stop and the exit.
            String body = Mom60.body(sb);
            check(Mom60.title(sb).equals("MOM60 ▲ BANKNIFTY")
                            && body.contains("58300 CE") && body.contains("monthly expiry")
                            && body.contains("stop ₹") && body.contains("Exit by"),
                    "mom60 alert: title arrow, contract, monthly expiry, stop and exit time");
        }

        // ---------- v54: BANKNIFTY strangle paper track ----------
        {
            java.time.LocalDate monN = java.time.LocalDate.of(2026, 10, 5);  // normal week (expiry Oct 27)
            java.time.LocalDate monE = java.time.LocalDate.of(2026, 10, 26); // expiry week (expiry Oct 27)
            double iv = 12.0;

            // The entry reads the 09:30 decision bar - and only it:
            // bars before it sit at 58000, bars after at 58100, the
            // decision bar itself closes 58340.
            double[] cD = new double[375];
            java.util.Arrays.fill(cD, 58000);
            cD[15] = 58340;
            for (int i = 16; i < cD.length; i++) cD[i] = 58100;
            YahooClient.Chart chD = momDay(monN, cD);
            Strangle.Entry e = Strangle.buildEntry(chD, monN, iv);
            check(e != null && e.spot == 58340 && e.barTs == Strangle.decisionBarEpoch(monN)
                            && e.entryTs == e.barTs + 60,
                    "strangle entry: spot off the 09:30 decision bar's close, entry at 09:31");
            check(e.atmStrike == 58300 && e.ceStrike == 58500 && e.peStrike == 58100,
                    "strangle strikes: ATM 58300 off spot 58340 (step 100), legs at ATM ±200");
            check(e.credit > 0 && Math.abs(e.credit - (e.cePremium + e.pePremium)) < 1e-9,
                    "strangle credit: BS credit is the sum of both legs and positive");
            double expectCe = OptionsMath.bsPriceDays(58340, 58500,
                    Mom60.daysToExpiryFrac(e.entryTs, e.expiry), iv, true);
            check(Math.abs(e.cePremium - expectCe) < 1e-9,
                    "strangle premium: legs priced by fractional-days BS at the entry moment");
            check(Math.abs(e.beUpper() - (58500 + e.credit)) < 1e-9
                            && Math.abs(e.beLower() - (58100 - e.credit)) < 1e-9
                            && Math.abs(e.creditRs() - e.credit * 30) < 1e-6,
                    "strangle breakevens: CE strike + credit / PE strike - credit; credit x lot 30 in rupees");
            check(e.expiry.equals(java.time.LocalDate.of(2026, 10, 27)) && !e.expiryWeek
                            && e.exitDate.equals(java.time.LocalDate.of(2026, 10, 8)),
                    "strangle exit: normal week exits Thu Oct 8, the Oct 27 monthly still open");

            // The entry window: Monday 09:31-09:45 only. Early,
            // late and Tuesday starts all skip the week silently.
            check(Strangle.entryWindowOpen(monN, Strangle.entryEpoch(monN))
                            && Strangle.entryWindowOpen(monN, Strangle.lateEpoch(monN))
                            && !Strangle.entryWindowOpen(monN, Strangle.lateEpoch(monN) + 60)
                            && !Strangle.entryWindowOpen(monN, Strangle.entryEpoch(monN) - 60)
                            && !Strangle.entryWindowOpen(monN.plusDays(1), Strangle.entryEpoch(monN.plusDays(1))),
                    "strangle window: Monday 09:31-09:45 only - early, late and Tuesday never enter");
            check(Strangle.buildEntry(chD, monN.plusDays(1), iv) == null,
                    "strangle entry: buildEntry refuses a non-Monday");
            check(Strangle.buildEntry(chD, monN, Double.NaN) == null,
                    "strangle entry: no IV, no entry - the week is skipped, never approximated");

            // Normal Thursday grading: unchanged spot leaves a
            // decay profit; the graded result charges exactly the
            // study's 1.0pp; a move through the wing loses.
            Strangle.Grade gFlat = Strangle.grade(e, 58340);
            check(gFlat != null && "THU".equals(gFlat.reason) && gFlat.grossPct > 0
                            && Math.abs(gFlat.netPct - (gFlat.grossPct - 1.0)) < 1e-9
                            && Math.abs(gFlat.pnlRs - gFlat.netPct / 100.0 * e.credit * 30) < 1e-6,
                    "strangle grade: flat Thu exit profits on decay; net = gross - 1.0pp cost; rupees at lot 30");
            Strangle.Grade gMove = Strangle.grade(e, 58340 + 1600);
            check(gMove != null && gMove.netPct < 0,
                    "strangle grade: a 1,600-point move through the CE wing grades negative");

            // Expiry week: Mon Oct 26 holds the Tue Oct 27 monthly
            // inside the Mon-Thu hold, so the week settles at expiry
            // at intrinsic value.
            double[] cF = new double[375];
            java.util.Arrays.fill(cF, 58340);
            Strangle.Entry ee = Strangle.buildEntry(momDay(monE, cF), monE, iv);
            check(ee != null && ee.expiryWeek
                            && ee.expiry.equals(java.time.LocalDate.of(2026, 10, 27))
                            && ee.exitDate.equals(ee.expiry),
                    "strangle expiry week: Mon Oct 26 - the Tue Oct 27 expiry is inside the hold, exit is the expiry");
            Strangle.Grade gIn = Strangle.grade(ee, 58340);
            check(gIn != null && "EXPIRY".equals(gIn.reason) && gIn.exitCost == 0
                            && Math.abs(gIn.netPct - 99.0) < 1e-9,
                    "strangle expiry: close inside the strikes settles worthless - net is +100% less the 1.0 cost");
            Strangle.Grade gUp = Strangle.grade(ee, ee.ceStrike + 300);
            check(gUp != null && Math.abs(gUp.exitCost - 300) < 1e-9,
                    "strangle expiry: close 300 above the CE strike settles at 300 points intrinsic");
            Strangle.Grade gDn = Strangle.grade(ee, ee.peStrike - 250);
            check(gDn != null && Math.abs(gDn.exitCost - 250) < 1e-9,
                    "strangle expiry: close 250 below the PE strike settles at 250 points intrinsic");

            // The exit walk: the exit day's LAST close grades the
            // week; a tape with no exit-day bars falls back to the
            // last close on or before the exit day (holiday tape).
            double[] cT = new double[375];
            java.util.Arrays.fill(cT, 58400);
            cT[374] = 59000;
            YahooClient.Chart twoDays = concatDays(momDay(monN, cF), momDay(e.exitDate, cT));
            check(Strangle.exitSpotFor(twoDays, e) == 59000,
                    "strangle exit spot: the exit day's last close");
            check(Strangle.exitSpotFor(momDay(monN, cF), e) == 58340,
                    "strangle exit spot: no exit-day bars - last close on or before the exit day");
            check(!Strangle.resolutionDue(e, Strangle.closeEpoch(e.exitDate) - 60)
                            && Strangle.resolutionDue(e, Strangle.closeEpoch(e.exitDate)),
                    "strangle resolution: due at the exit day's 15:30 close, not before");

            // Feed wiring, updated for v57 (user decision
            // 2026-10-25 reversing the v53-v55 paper-stage
            // exclusion): the System Feed DOES carry payload
            // builders for both paper strategies now - the
            // detailed shape pins live in the v57 block at the
            // end of this harness.
            int paperBuilders54 = 0;
            for (java.lang.reflect.Method m : SystemFeed.class.getDeclaredMethods()) {
                String n = m.getName().toLowerCase(java.util.Locale.US);
                if (n.equals("stranglepayload") || n.equals("mom60payload")) paperBuilders54++;
            }
            check(paperBuilders54 == 2,
                    "strangle feed (v57): SystemFeed carries both paper strategies' payload builders (all-topics rule)");

            // Alert text: PAPER in the title, both SELL legs, the
            // breakevens, the study footer, the capital line, the
            // BS honesty line - and the right exit plan per week.
            String t54 = Strangle.title(e), b54 = Strangle.body(e);
            check(t54.contains("PAPER") && b54.contains("SELL 58500 CE") && b54.contains("SELL 58100 PE")
                            && b54.contains("Breakevens") && b54.contains("+16.42%") && b54.contains("77.8%")
                            && b54.contains("-285%".replace('-', '−')) && b54.contains("2.14L")
                            && b54.contains("Not a live signal") && b54.contains("BS estimate")
                            && b54.contains("Thu close"),
                    "strangle alert: PAPER title, both SELL legs, breakevens, study footer, capital line, BS honesty, Thu exit plan");
            check(Strangle.body(ee).contains("settles at expiry"),
                    "strangle alert: expiry-week body states expiry settlement as the exit plan");
        }

        // ---------- v55: FINNIFTY + Sensex mom60 paper tracks ----------
        {
            java.time.LocalDate mon = java.time.LocalDate.of(2026, 10, 26); // Monday
            double iv = 12.0;

            // Config facts: each index's frozen study numbers. BN's
            // config IS the v53 constants - the reference the other
            // two mirror - and its study line is byte-identical.
            check(Mom60.BN.thresholdPct == Mom60.THRESHOLD_PCT && Mom60.BN.strikeStep == 100
                            && Mom60.BN.lot == 30 && !Mom60.BN.bseExpiry
                            && "^NSEBANK".equals(Mom60.BN.yahoo)
                            && Mom60.STUDY_LINE.equals("Study (BANKNIFTY mom60 + 30% stop, full history): train +2.29% · val +0.99% · test +4.75% per trade · win ~50% · ~1–2 signals/day."),
                    "mom60x BN config: the v53 constants and study line, unchanged");
            check(Mom60.FIN.thresholdPct == 0.50 && Mom60.FIN.strikeStep == 50
                            && Mom60.FIN.lot == 60 && !Mom60.FIN.bseExpiry
                            && "^CNXFIN".equals(Mom60.FIN.yahoo)
                            && Mom60.FIN.studyLine == Mom60Idx.STUDY_LINE_FIN,
                    "mom60x FIN config: 0.50% (q90 0.5025 frozen), step 50, lot 60, NSE expiry");
            check(Mom60.SENSEX.thresholdPct == 0.47 && Mom60.SENSEX.strikeStep == 100
                            && Mom60.SENSEX.lot == 20 && Mom60.SENSEX.bseExpiry
                            && "^BSESN".equals(Mom60.SENSEX.yahoo)
                            && Mom60.SENSEX.studyLine == Mom60Idx.STUDY_LINE_SENSEX,
                    "mom60x Sensex config: 0.47% (q90 0.4735 frozen), step 100, lot 20, BSE expiry");
            check(Mom60Idx.STUDY_LINE_FIN.contains("+2.33%") && Mom60Idx.STUDY_LINE_FIN.contains("PF 1.40")
                            && Mom60Idx.STUDY_LINE_FIN.contains("713 trades")
                            && Mom60Idx.STUDY_LINE_FIN.contains("DTE 3–7 +6.42%"),
                    "mom60x FIN footer: deep-dig numbers (pooled +2.33%, PF 1.40, n=713, DTE 3-7 +6.42%)");
            check(Mom60Idx.STUDY_LINE_SENSEX.contains("+2.03%") && Mom60Idx.STUDY_LINE_SENSEX.contains("PF 1.31")
                            && Mom60Idx.STUDY_LINE_SENSEX.contains("593 trades"),
                    "mom60x Sensex footer: batch numbers (pooled +2.03%, PF 1.31, n=593)");

            // FINNIFTY fires at its own step: +0.53% step at bar 70,
            // ATM rounds to the nearest 50, expiry is the NSE
            // last-Tuesday monthly - the same date BN takes.
            double[] f1 = new double[375];
            java.util.Arrays.fill(f1, 24500);
            for (int i = 70; i < f1.length; i++) f1[i] = 24630; // +0.531%
            YahooClient.Chart chF1 = momDay(mon, f1);
            Mom60.Watcher wf = new Mom60.Watcher(Mom60.FIN, null, 0, Long.MIN_VALUE);
            java.util.List<Mom60.Event> evf = wf.process(chF1, mon, chF1.ts[70] + 90, true, iv);
            check(evf.size() == 1 && evf.get(0).fired != null && evf.get(0).fired.call
                            && evf.get(0).fired.cfg == Mom60.FIN,
                    "mom60x FIN fire: +0.531% step fires a CE under the FIN config");
            Mom60.Signal finFired = evf.get(0).fired;
            check(evf.get(0).fired.strike == 24650 && evf.get(0).fired.spot == 24630,
                    "mom60x FIN strike: ATM 24650 off spot 24630 (step 50)");
            check(evf.get(0).fired.expiry.equals(java.time.LocalDate.of(2026, 10, 27))
                            && evf.get(0).fired.expiry.equals(Mom60.buildSignal(chF1.ts[70], true, 0.531, 24630, mon, iv).expiry),
                    "mom60x FIN expiry: last-Tuesday monthly, the same date BANKNIFTY takes");
            evf = wf.process(chF1, mon, chF1.ts[130] + 60, true, iv);
            check(evf.size() == 1 && evf.get(0).resolved != null
                            && "TIME".equals(evf.get(0).resolved.reason)
                            && evf.get(0).resolved.exitBarTs == chF1.ts[130],
                    "mom60x FIN resolve: flat path exits TIME at the +60m bar");
            check(Mom60.buildSignal(Mom60.FIN, 0, true, 0.53, 24630,
                            java.time.LocalDate.of(2026, 10, 28), iv).expiry
                            .equals(java.time.LocalDate.of(2026, 11, 23)),
                    "mom60x FIN expiry: Wed 28 Oct takes November's monthly (holiday-shifted Mon 23 Nov)");

            // FINNIFTY down fires a PE at the 50-step; +0.40% stays
            // under its 0.50% bar.
            double[] f2 = new double[375];
            java.util.Arrays.fill(f2, 24500);
            for (int i = 70; i < f2.length; i++) f2[i] = 24370; // -0.531%
            Mom60.Watcher wf2 = new Mom60.Watcher(Mom60.FIN, null, 0, Long.MIN_VALUE);
            java.util.List<Mom60.Event> evf2 = wf2.process(momDay(mon, f2), mon, chF1.ts[70] + 90, true, iv);
            check(evf2.size() == 1 && evf2.get(0).fired != null && !evf2.get(0).fired.call
                            && evf2.get(0).fired.strike == 24350,
                    "mom60x FIN fire: -0.531% step fires a PE at strike 24350 (step 50)");
            double[] f3 = new double[375];
            java.util.Arrays.fill(f3, 24500);
            for (int i = 70; i < f3.length; i++) f3[i] = 24598; // +0.40% only
            Mom60.Watcher wf3 = new Mom60.Watcher(Mom60.FIN, null, 0, Long.MIN_VALUE);
            check(wf3.process(momDay(mon, f3), mon, chF1.ts[200] + 90, true, iv).isEmpty(),
                    "mom60x FIN threshold: +0.40% step stays under the 0.50% bar - no fire");

            // Strike rounding at the 50-point step (half rounds up).
            check(Mom60.buildSignal(Mom60.FIN, 0, true, 0.53, 24624, mon, iv).strike == 24600
                            && Mom60.buildSignal(Mom60.FIN, 0, true, 0.53, 24625, mon, iv).strike == 24650
                            && Mom60.buildSignal(Mom60.FIN, 0, true, 0.53, 24626, mon, iv).strike == 24650,
                    "mom60x FIN strike: ATM rounds to the nearest 50");

            // Sensex fires at 0.47% where BANKNIFTY's 0.50% bar
            // stays silent on the very same tape - the thresholds
            // are per-index, and BN's is untouched.
            double[] x1 = new double[375];
            java.util.Arrays.fill(x1, 85000);
            for (int i = 70; i < x1.length; i++) x1[i] = 85408; // +0.480% exactly
            YahooClient.Chart chX1 = momDay(mon, x1);
            Mom60.Watcher wx = new Mom60.Watcher(Mom60.SENSEX, null, 0, Long.MIN_VALUE);
            java.util.List<Mom60.Event> evx = wx.process(chX1, mon, chX1.ts[70] + 90, true, iv);
            check(evx.size() == 1 && evx.get(0).fired != null && evx.get(0).fired.call
                            && evx.get(0).fired.cfg == Mom60.SENSEX,
                    "mom60x Sensex fire: +0.480% step clears the 0.47% bar and fires a CE");
            check(evx.get(0).fired.strike == 85400,
                    "mom60x Sensex strike: ATM 85400 off spot 85408 (step 100)");
            Mom60.Watcher wxBn = new Mom60.Watcher(null, 0, Long.MIN_VALUE);
            check(wxBn.process(chX1, mon, chX1.ts[200] + 90, true, iv).isEmpty(),
                    "mom60x thresholds: the same +0.480% tape never fires BANKNIFTY (0.50% bar)");
            double[] x2 = new double[375];
            java.util.Arrays.fill(x2, 85000);
            for (int i = 70; i < x2.length; i++) x2[i] = 85340; // +0.40% only
            Mom60.Watcher wx2 = new Mom60.Watcher(Mom60.SENSEX, null, 0, Long.MIN_VALUE);
            check(wx2.process(momDay(mon, x2), mon, chX1.ts[200] + 90, true, iv).isEmpty(),
                    "mom60x Sensex threshold: +0.40% step stays under the 0.47% bar - no fire");

            // Sensex freshness gate, shared with BN: a one-bar
            // trigger first seen 10 minutes late is consumed only.
            double[] x3 = new double[375];
            java.util.Arrays.fill(x3, 85000);
            x3[70] = 85408;
            Mom60.Watcher wx3 = new Mom60.Watcher(Mom60.SENSEX, null, 0, Long.MIN_VALUE);
            check(wx3.process(momDay(mon, x3), mon, chX1.ts[70] + 60 + 600, true, iv).isEmpty(),
                    "mom60x Sensex freshness: a stale one-bar trigger is not fired");

            // Sensex STOP through the store: a fired record rebuilt
            // by watcherFor(cfg) resolves on the crash path, and the
            // grading matches the live watcher's own walk.
            double[] x4 = new double[375];
            java.util.Arrays.fill(x4, 85000);
            for (int i = 70; i < 130; i++) x4[i] = 85408;
            x4[130] = 84500; // the exit bar crashes through the stop
            for (int i = 131; i < x4.length; i++) x4[i] = 84500;
            YahooClient.Chart chX4 = momDay(mon, x4);
            Mom60.Watcher wx4 = new Mom60.Watcher(Mom60.SENSEX, null, 0, Long.MIN_VALUE);
            java.util.List<Mom60.Event> evx4 = wx4.process(chX4, mon, chX4.ts[70] + 90, true, iv);
            Mom60Store.Rec rx = Mom60Store.Rec.fromSignal(evx4.get(0).fired);
            java.util.List<Mom60Store.Rec> logX = new java.util.ArrayList<>();
            logX.add(rx);
            Mom60.Watcher wx4b = Mom60Store.watcherFor(logX, mon, Mom60.SENSEX);
            check(wx4b.cfg == Mom60.SENSEX && wx4b.open != null && wx4b.open.cfg == Mom60.SENSEX
                            && wx4b.open.strike == 85400,
                    "mom60x Sensex log: watcherFor(cfg) rebuilds the open position under its config");
            java.util.List<Mom60.Event> evx4b = wx4b.process(chX4, mon, chX4.ts[130] + 60, true, iv);
            check(evx4b.size() == 1 && evx4b.get(0).resolved != null
                            && "STOP".equals(evx4b.get(0).resolved.reason)
                            && evx4b.get(0).resolved.exitBarTs == chX4.ts[130],
                    "mom60x Sensex log resolve: the rebuilt watcher books STOP on the crash bar");
            rx.apply(evx4b.get(0).resolved);
            check(rx.closed && "STOP".equals(rx.exitReason) && rx.pnlPct < 0
                            && Math.abs(rx.pnlPct - evx4b.get(0).resolved.pnlPct) < 1e-9,
                    "mom60x Sensex log resolve: the record takes the resolution verbatim");

            // FIN TIME through the store, and the BN default rebuild
            // still yields the BN config on both watcher and signal.
            Mom60Store.Rec rf = Mom60Store.Rec.fromSignal(finFired);
            java.util.List<Mom60Store.Rec> logF = new java.util.ArrayList<>();
            logF.add(rf);
            Mom60.Watcher wfB = Mom60Store.watcherFor(logF, mon, Mom60.FIN);
            java.util.List<Mom60.Event> evfB = wfB.process(chF1, mon, chF1.ts[130] + 60, true, iv);
            check(evfB.size() == 1 && evfB.get(0).resolved != null
                            && "TIME".equals(evfB.get(0).resolved.reason),
                    "mom60x FIN log resolve: the rebuilt watcher books TIME on the flat path");
            Mom60Store.Rec rb = Mom60Store.Rec.fromSignal(
                    Mom60.buildSignal(chF1.ts[70], true, 0.6, 58340, mon, iv));
            java.util.List<Mom60Store.Rec> logB = new java.util.ArrayList<>();
            logB.add(rb);
            Mom60.Watcher wbD = Mom60Store.watcherFor(logB, mon);
            check(wbD.cfg == Mom60.BN && wbD.open != null && wbD.open.cfg == Mom60.BN,
                    "mom60x BN log: the two-arg watcherFor still rebuilds under the BN config");

            // Sensex monthly expiry - the LAST THURSDAY (BSE's
            // current era, chain-verified in sensex_lab): the
            // function's answers on the study's own detected dates,
            // then the live month boundaries.
            check(OptionsMath.monthlyExpiryBse(java.time.LocalDate.of(2025, 9, 1))
                            .equals(java.time.LocalDate.of(2025, 9, 25))
                            && OptionsMath.monthlyExpiryBse(java.time.LocalDate.of(2025, 10, 1))
                            .equals(java.time.LocalDate.of(2025, 10, 30))
                            && OptionsMath.monthlyExpiryBse(java.time.LocalDate.of(2026, 1, 5))
                            .equals(java.time.LocalDate.of(2026, 1, 29))
                            && OptionsMath.monthlyExpiryBse(java.time.LocalDate.of(2026, 4, 1))
                            .equals(java.time.LocalDate.of(2026, 4, 30)),
                    "mom60x Sensex expiry: matches the chain-detected Sep/Oct 2025 and Jan/Apr 2026 expiries");
            check(OptionsMath.monthlyExpiryBse(java.time.LocalDate.of(2026, 7, 1))
                            .equals(java.time.LocalDate.of(2026, 7, 30))
                            && OptionsMath.monthlyExpiryBse(java.time.LocalDate.of(2026, 8, 3))
                            .equals(java.time.LocalDate.of(2026, 8, 27))
                            && OptionsMath.monthlyExpiryBse(java.time.LocalDate.of(2026, 9, 1))
                            .equals(java.time.LocalDate.of(2026, 9, 24)),
                    "mom60x Sensex expiry: matches the chain-detected Jul/Aug/Sep 2026 expiries");
            check(OptionsMath.monthlyExpiryBse(mon).equals(java.time.LocalDate.of(2026, 10, 29))
                            && OptionsMath.monthlyExpiryBse(java.time.LocalDate.of(2026, 10, 29))
                            .equals(java.time.LocalDate.of(2026, 10, 29))
                            && OptionsMath.monthlyExpiryBse(java.time.LocalDate.of(2026, 10, 30))
                            .equals(java.time.LocalDate.of(2026, 11, 26)),
                    "mom60x Sensex expiry: Mon 26 Oct -> Thu 29 Oct; the day after rolls to Thu 26 Nov");
            check(OptionsMath.monthlyExpiryBse(java.time.LocalDate.of(2026, 12, 31))
                            .equals(java.time.LocalDate.of(2026, 12, 31))
                            && OptionsMath.monthlyExpiryBse(java.time.LocalDate.of(2027, 1, 1))
                            .equals(java.time.LocalDate.of(2027, 1, 28)),
                    "mom60x Sensex expiry: year boundary - 31 Dec 2026 is itself the last Thursday; 1 Jan takes 28 Jan");
            Mom60.Signal sx = Mom60.buildSignal(Mom60.SENSEX, chX1.ts[70], true, 0.48, 85408, mon, iv);
            check(sx.expiry.equals(java.time.LocalDate.of(2026, 10, 29))
                            && !sx.expiry.equals(java.time.LocalDate.of(2026, 10, 30))
                            && !sx.expiry.equals(OptionsMath.monthlyExpiry(mon)),
                    "mom60x Sensex expiry: a live signal takes the last Thursday - not the last Friday, not the NSE Tuesday");

            // Rupees at one lot: the premium move times the config
            // lot (FIN 60, Sensex 20) - the figures the alert and
            // the Strategies cards display.
            Mom60.Signal sf = Mom60.buildSignal(Mom60.FIN, chF1.ts[70], true, 0.531, 24630, mon, iv);
            check(Math.abs(Mom60Idx.paperRs(sf, 10.0) - sf.entryPremium * 0.10 * 60) < 1e-6
                            && Math.abs(Mom60Idx.paperRs(sf, -30.0) + sf.entryPremium * 0.30 * 60) < 1e-6
                            && Math.abs(Mom60Idx.paperRs(sx, 10.0) - sx.entryPremium * 0.10 * 20) < 1e-6,
                    "mom60x rupees: P&L rupees are premium x P&L% x lot (60 FIN / 20 Sensex), stop is -30% of premium in rupees");

            // Alert text: PAPER titles with the index name; bodies
            // carry the contract, the expiry weekday the index
            // really uses, its footer, the paper line and the lot.
            String tF = Mom60.title(sf), bF = Mom60.body(sf);
            check(tF.equals("PAPER MOM60 ▲ FINNIFTY")
                            && bF.contains("FINNIFTY 24,630.00") && bF.contains("BUY 24650 CE")
                            && bF.contains("monthly expiry Tue 27 Oct") && bF.contains("× 60")
                            && bF.contains("+2.33%") && bF.contains("Paper track")
                            && bF.contains("Not a live signal") && bF.contains("stop ₹")
                            && bF.contains("Exit by"),
                    "mom60x FIN alert: PAPER title, contract, Tue expiry, lot x60, footer, paper line, stop and exit");
            Mom60.Signal sxd = Mom60.buildSignal(Mom60.SENSEX, chX1.ts[70], false, -0.48, 84592, mon, iv);
            String tX = Mom60.title(sxd), bX = Mom60.body(sxd);
            check(tX.equals("PAPER MOM60 ▼ SENSEX")
                            && bX.contains("BUY 84600 PE") && bX.contains("monthly expiry Thu 29 Oct")
                            && bX.contains("× 20") && bX.contains("+2.03%")
                            && bX.contains("Paper track") && bX.contains("BS estimate"),
                    "mom60x Sensex alert: PAPER title, PE contract, Thu expiry, lot x20, footer, paper line");

            // Feed wiring, updated for v57 (user decision
            // 2026-10-25: every alert goes to ALL topics): ONE
            // mom60 builder in SystemFeed serves all three indices
            // (the signal's Cfg names it) - 8 payload builders in
            // total, schema VERSION still 51 (same flat shape,
            // new strategy values). The detailed pins live in
            // the v57 block at the end of this harness. What does
            // not change: Mom60Idx (the pure text class) still
            // declares no payload or raw-post method of its own -
            // builders live in SystemFeed only, and the engines
            // post through the shared BuyAlerts.postFeedNow path
            // (grep-verified in the v57 ship review).
            int payloadBuilders = 0;
            boolean mom60Builder = false;
            for (java.lang.reflect.Method m : SystemFeed.class.getDeclaredMethods()) {
                String n = m.getName().toLowerCase(java.util.Locale.US);
                if (n.endsWith("payload")) payloadBuilders++;
                if (n.equals("mom60payload")) mom60Builder = true;
            }
            check(mom60Builder && payloadBuilders == 8 && SystemFeed.VERSION == 51,
                    "mom60x feed (v57): one MOM60 payload builder for all three indices, version 51 (builder count 8 incl. v57 paper/price/flush)");
            boolean idxClean = true;
            for (java.lang.reflect.Method m : Mom60Idx.class.getDeclaredMethods()) {
                String n = m.getName().toLowerCase(java.util.Locale.US);
                if (n.contains("payload") || n.contains("postraw")) idxClean = false;
            }
            check(idxClean,
                    "mom60x feed (v57): Mom60Idx stays pure text - no payload or raw-post method of its own");
        }

        // ---- v56: manual shares mirror to the System Feed ----
        {
            Universe.Sym relS = new Universe.Sym("Reliance", "RELIANCE.NS", "NIFTY 50 companies");
            String ts56 = "2026-10-25T13:45:00+05:30";

            Analysis.Scan mDown = new Analysis.Scan();
            mDown.sym = relS;
            mDown.setup = Analysis.Setup.ORB_DOWN;
            mDown.entry = 2900; mDown.stop = 2910; mDown.target = 2890;
            OptionsMath.Ticket mTk = new OptionsMath.Ticket();
            mTk.call = false; mTk.strike = 2900; mTk.premium = 42.5;
            mTk.expiry = java.time.LocalDate.of(2026, 10, 27);

            String mId = SystemFeed.manualSignalId(mDown, Analysis.alertTarget(mDown), "2026-10-25");
            check("MANUAL|RELIANCE.NS|ORB_DOWN|290000|288000|2026-10-25".equals(mId),
                    "Feed MANUAL: signalId is the ORB dedupe fields under a MANUAL prefix");
            org.json.JSONObject mp = SystemFeed.manualPayload(mDown, 82, mId,
                    Analysis.alertTarget(mDown), Analysis.alertTarget3(mDown), mTk, ts56, null);
            check("signal".equals(mp.getString("type")) && !mp.getBoolean("test")
                    && "IraThinker".equals(mp.getString("source")) && mp.getInt("version") == 51,
                    "Feed MANUAL: envelope type/test/source/version matches the v51 shape");
            check("MANUAL".equals(mp.getString("strategy")), "Feed MANUAL: strategy is MANUAL");
            check("Reliance".equals(mp.getString("symbol")) && "RELIANCE.NS".equals(mp.getString("yahooSymbol")),
                    "Feed MANUAL: display + yahoo symbols from the plan");
            check("BUY_PUT".equals(mp.getString("side")), "Feed MANUAL: breakdown plan side is BUY_PUT");
            check(mp.getDouble("entry") == 2900 && mp.getDouble("stop") == 2910,
                    "Feed MANUAL: entry/stop from the plan");
            check(mp.getDouble("target1") == 2880 && mp.getDouble("target1") == Analysis.alertTarget(mDown),
                    "Feed MANUAL: target1 is the 2R alert target");
            check(mp.getDouble("target2") == 2870 && mp.getDouble("target2") == Analysis.alertTarget3(mDown),
                    "Feed MANUAL: target2 is the 3R runner target");
            check(mp.getInt("score") == 82 && mId.equals(mp.getString("signalId"))
                    && ts56.equals(mp.getString("tsIST")),
                    "Feed MANUAL: score / signalId / tsIST ride along");
            org.json.JSONObject mo = mp.getJSONObject("option");
            check(mo.getDouble("strike") == 2900 && mo.getDouble("premium") == 42.5
                    && "2026-10-27".equals(mo.getString("expiry")) && "PE".equals(mo.getString("kind")),
                    "Feed MANUAL: option strike/premium/expiry/kind from the ticket");

            Analysis.Scan mUp = new Analysis.Scan();
            mUp.sym = relS;
            mUp.setup = Analysis.Setup.ORB_UP;
            mUp.entry = 2900; mUp.stop = 2890; mUp.target = 2910;
            OptionsMath.Ticket mTkUp = new OptionsMath.Ticket();
            mTkUp.call = true; mTkUp.strike = 2900; mTkUp.premium = 40.0;
            mTkUp.expiry = java.time.LocalDate.of(2026, 10, 27);
            org.json.JSONObject mu = SystemFeed.manualPayload(mUp, 70, "M2",
                    Analysis.alertTarget(mUp), Analysis.alertTarget3(mUp), mTkUp, ts56, null);
            check("BUY_CALL".equals(mu.getString("side"))
                    && "CE".equals(mu.getJSONObject("option").getString("kind")),
                    "Feed MANUAL up: BUY_CALL / CE");

            // Skip rules: the payload is derivable only from a real
            // ORB plan with a priced ticket on a ticketed symbol.
            check(SystemFeed.manualFeedable(mDown, mTk) && SystemFeed.manualFeedable(mUp, mTkUp),
                    "Feed MANUAL gate: ORB plans with priced tickets are feedable");
            Analysis.Scan noPlan = new Analysis.Scan();
            noPlan.sym = relS;
            check(!SystemFeed.manualFeedable(noPlan, mTk) && !SystemFeed.manualFeedable(null, mTk),
                    "Feed MANUAL gate: no plan (or no scan) -> no payload");
            Analysis.Scan noSetup = new Analysis.Scan();
            noSetup.sym = relS;
            noSetup.entry = 2900; noSetup.stop = 2910;
            check(!SystemFeed.manualFeedable(noSetup, mTk),
                    "Feed MANUAL gate: plan levels without an ORB setup -> no payload");
            Analysis.Scan goldPlan = new Analysis.Scan();
            goldPlan.sym = Universe.gold();
            goldPlan.setup = Analysis.Setup.ORB_DOWN;
            goldPlan.entry = 4000; goldPlan.stop = 4010; goldPlan.target = 3990;
            check(!SystemFeed.manualFeedable(goldPlan, mTk),
                    "Feed MANUAL gate: gold has no options ticket -> no payload");
            OptionsMath.Ticket nanTk = new OptionsMath.Ticket();
            nanTk.premium = Double.NaN; nanTk.expiry = java.time.LocalDate.of(2026, 10, 27);
            OptionsMath.Ticket noExpTk = new OptionsMath.Ticket();
            noExpTk.premium = 42.5;
            check(!SystemFeed.manualFeedable(mDown, null)
                    && !SystemFeed.manualFeedable(mDown, nanTk)
                    && !SystemFeed.manualFeedable(mDown, noExpTk),
                    "Feed MANUAL gate: missing / unpriced / expiry-less ticket -> no payload");

            // The full send decision composes the feed switch/topic
            // gate with the derivability gate.
            check(SystemFeed.shouldSendManual(true, "machine-topic", mDown, mTk)
                    && !SystemFeed.shouldSendManual(false, "machine-topic", mDown, mTk)
                    && !SystemFeed.shouldSendManual(true, "", mDown, mTk)
                    && !SystemFeed.shouldSendManual(true, "machine-topic", noPlan, mTk)
                    && !SystemFeed.shouldSendManual(true, "machine-topic", goldPlan, mTk),
                    "Feed MANUAL decision: sends only when feed ON + topic set + plan feedable");

            // The shared send path every feed mirror routes through
            // (v56: BuyAlerts.postFeedNow - auto alerts delegate to
            // it, manual shares call it on their send thread).
            boolean sharedPath = false;
            try {
                java.lang.reflect.Method m = BuyAlerts.class.getDeclaredMethod(
                        "postFeedNow", Prefs.class, String.class);
                sharedPath = m.getReturnType() == boolean.class
                        && java.lang.reflect.Modifier.isStatic(m.getModifiers())
                        && java.lang.reflect.Modifier.isPublic(m.getModifiers());
            } catch (Throwable ignored) { }
            check(sharedPath,
                    "Feed MANUAL: BuyAlerts.postFeedNow(Prefs, String) is the shared public send path");

            // Reflection pin, updated for v57: SystemFeed now has
            // exactly EIGHT payload builders (ORB / fade / test /
            // MANUAL + the v57 MOM60 / STRANGLE / PRICE_ALERT /
            // MARKET_FLUSH); the schema VERSION stays 51 (same
            // flat shape, new strategy values), and the paper
            // builders the v53-v56 pins excluded are present by
            // the user's 2026-10-25 all-topics decision.
            int builders56 = 0;
            boolean manualPresent = false, paperBuilder = false;
            for (java.lang.reflect.Method m : SystemFeed.class.getDeclaredMethods()) {
                String n = m.getName().toLowerCase(java.util.Locale.US);
                if (n.endsWith("payload")) builders56++;
                if (n.equals("manualpayload")) manualPresent = true;
                if (n.contains("strangle") || n.contains("mom60")) paperBuilder = true;
            }
            check(builders56 == 8 && manualPresent && paperBuilder && SystemFeed.VERSION == 51,
                    "Feed MANUAL: SystemFeed has 8 payload builders (v51 ORB/fade/test + v56 MANUAL + v57 paper/price/flush), version 51");
        }

        // ---- v57: paper strategies + price alerts mirror to the feed ----
        {
            String ts57 = "2026-10-26T10:16:00+05:30";
            java.time.LocalDate mon57 = java.time.LocalDate.of(2026, 10, 26); // Monday
            java.time.ZoneId ist57 = java.time.ZoneId.of("Asia/Kolkata");
            long bar1015 = java.time.ZonedDateTime.of(mon57, java.time.LocalTime.of(10, 15), ist57)
                    .toEpochSecond();

            // MOM60 BANKNIFTY call: every field derives from the
            // fired signal - entry is the signal spot, the option
            // block is the signal's own strike/expiry/premium, the
            // stop is on the premium (x0.70), exitBy is entry+60m,
            // and there are NO spot target keys at all.
            Mom60.Signal mBn = Mom60.buildSignal(Mom60.BN, bar1015, true, 0.62, 58340, mon57, 14.0);
            org.json.JSONObject mp57 = SystemFeed.mom60Payload(mBn, ts57);
            check("signal".equals(mp57.getString("type")) && !mp57.getBoolean("test")
                    && mp57.getInt("version") == 51 && "MOM60".equals(mp57.getString("strategy"))
                    && mp57.getBoolean("paper"),
                    "Feed MOM60: envelope + strategy MOM60 + paper:true");
            check("BANKNIFTY".equals(mp57.getString("symbol")) && "^NSEBANK".equals(mp57.getString("yahooSymbol"))
                    && "BUY_CALL".equals(mp57.getString("side")) && mp57.getDouble("entry") == 58340,
                    "Feed MOM60: index names from the Cfg, BUY_CALL, entry is the signal spot");
            check(("MOM60|^NSEBANK|" + mBn.entryTs).equals(mp57.getString("signalId"))
                    && mp57.getString("signalId").equals(SystemFeed.mom60SignalId(mBn))
                    && ts57.equals(mp57.getString("tsIST")),
                    "Feed MOM60: signalId is MOM60|yahoo|entryTs, tsIST passes through");
            org.json.JSONObject mo57 = mp57.getJSONObject("option");
            check(mo57.getDouble("strike") == 58300 && "2026-10-27".equals(mo57.getString("expiry"))
                    && "CE".equals(mo57.getString("kind"))
                    && Math.abs(mo57.getDouble("premium") - Math.round(mBn.entryPremium * 100) / 100.0) < 1e-9,
                    "Feed MOM60: option strike/expiry/kind/premium are the signal's own");
            check(Math.abs(mBn.stopPremium - mBn.entryPremium * 0.70) < 1e-9
                    && Math.abs(mp57.getDouble("premiumStop") - Math.round(mBn.stopPremium * 100) / 100.0) < 1e-9,
                    "Feed MOM60: premiumStop is the entry premium x 0.70 (the stop is on the premium)");
            String exitByExp = java.time.Instant.ofEpochSecond(mBn.exitTs).atZone(ist57)
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX"));
            check(exitByExp.equals(mp57.getString("exitBy")) && exitByExp.contains("T11:16:00+05:30")
                    && mBn.exitTs - mBn.entryTs == 3600,
                    "Feed MOM60: exitBy is the entry + 60m moment, ISO IST");
            check(!mp57.has("target1") && !mp57.has("target2") && !mp57.has("stop"),
                    "Feed MOM60: no spot targets, no spot stop - keys omitted, not nulled");

            // The same builder under the other two Cfgs: FINNIFTY
            // call at its 50-step, Sensex put at its BSE expiry.
            Mom60.Signal mFin = Mom60.buildSignal(Mom60.FIN, bar1015, true, 0.55, 24630, mon57, 12.0);
            org.json.JSONObject mf57 = SystemFeed.mom60Payload(mFin, ts57);
            check("FINNIFTY".equals(mf57.getString("symbol")) && "^CNXFIN".equals(mf57.getString("yahooSymbol"))
                    && mf57.getJSONObject("option").getDouble("strike") == 24650
                    && "2026-10-27".equals(mf57.getJSONObject("option").getString("expiry"))
                    && mf57.getString("signalId").startsWith("MOM60|^CNXFIN|"),
                    "Feed MOM60 FIN: FINNIFTY names, strike at the 50-step, last-Tuesday expiry");
            Mom60.Signal mSx = Mom60.buildSignal(Mom60.SENSEX, bar1015, false, -0.55, 84700, mon57, 12.0);
            org.json.JSONObject mx57 = SystemFeed.mom60Payload(mSx, ts57);
            check("SENSEX".equals(mx57.getString("symbol")) && "^BSESN".equals(mx57.getString("yahooSymbol"))
                    && "BUY_PUT".equals(mx57.getString("side"))
                    && "PE".equals(mx57.getJSONObject("option").getString("kind"))
                    && mx57.getJSONObject("option").getDouble("strike") == 84700
                    && "2026-10-29".equals(mx57.getJSONObject("option").getString("expiry"))
                    && mx57.getBoolean("paper"),
                    "Feed MOM60 Sensex: SENSEX names, BUY_PUT/PE, last-Thursday (BSE) expiry, paper:true");
            check(!SystemFeed.mom60SignalId(mBn).equals(SystemFeed.mom60SignalId(mSx))
                    && !SystemFeed.mom60SignalId(mBn).equals(SystemFeed.mom60SignalId(mFin)),
                    "Feed MOM60: signalIds are unique per index");

            // STRANGLE, normal week: two legs in alert order, the
            // credit in points and rupees at lot 30, both
            // breakevens, and the Thu-close exit plan.
            java.time.LocalDate monN57 = java.time.LocalDate.of(2026, 10, 5);
            double[] cS57 = new double[375];
            java.util.Arrays.fill(cS57, 58340);
            Strangle.Entry eN57 = Strangle.buildEntry(momDay(monN57, cS57), monN57, 12.0);
            org.json.JSONObject sp57 = SystemFeed.stranglePayload(eN57, ts57);
            check("STRANGLE".equals(sp57.getString("strategy")) && sp57.getBoolean("paper")
                    && "SELL_STRANGLE".equals(sp57.getString("side"))
                    && "BANKNIFTY".equals(sp57.getString("symbol"))
                    && "^NSEBANK".equals(sp57.getString("yahooSymbol"))
                    && sp57.getDouble("entry") == 58340,
                    "Feed STRANGLE: strategy + paper:true + SELL_STRANGLE + index names + entry spot");
            org.json.JSONArray legs57 = sp57.getJSONArray("legs");
            check(legs57.length() == 2
                    && "CE".equals(legs57.getJSONObject(0).getString("kind"))
                    && legs57.getJSONObject(0).getDouble("strike") == 58500
                    && Math.abs(legs57.getJSONObject(0).getDouble("premium") - Math.round(eN57.cePremium * 100) / 100.0) < 1e-9
                    && "PE".equals(legs57.getJSONObject(1).getString("kind"))
                    && legs57.getJSONObject(1).getDouble("strike") == 58100
                    && Math.abs(legs57.getJSONObject(1).getDouble("premium") - Math.round(eN57.pePremium * 100) / 100.0) < 1e-9,
                    "Feed STRANGLE: legs are CE 58500 then PE 58100 at the entry's own BS premiums");
            check(Math.abs(sp57.getDouble("creditPts") - Math.round(eN57.credit * 100) / 100.0) < 1e-9
                    && Math.abs(sp57.getDouble("creditRs") - Math.round(eN57.creditRs() * 100) / 100.0) < 1e-9
                    && Math.abs(eN57.creditRs() - eN57.credit * 30) < 1e-6,
                    "Feed STRANGLE: creditPts is the legs' sum, creditRs is credit x lot 30");
            check(Math.abs(sp57.getDouble("breakevenUpper") - Math.round(eN57.beUpper() * 100) / 100.0) < 1e-9
                    && Math.abs(sp57.getDouble("breakevenLower") - Math.round(eN57.beLower() * 100) / 100.0) < 1e-9
                    && Math.abs(eN57.beUpper() - (58500 + eN57.credit)) < 1e-9
                    && Math.abs(eN57.beLower() - (58100 - eN57.credit)) < 1e-9,
                    "Feed STRANGLE: breakevens are wing strike +/- the credit");
            check("2026-10-27".equals(sp57.getString("expiry")) && "2026-10-08".equals(sp57.getString("exitDate"))
                    && "THU_CLOSE 2026-10-08".equals(sp57.getString("exitPlan"))
                    && "STRANGLE|^NSEBANK|2026-10-05".equals(sp57.getString("signalId")),
                    "Feed STRANGLE: expiry, Thu exit date/plan, per-week signalId");
            Strangle.Entry eE57 = Strangle.buildEntry(momDay(mon57, cS57), mon57, 12.0);
            org.json.JSONObject se57 = SystemFeed.stranglePayload(eE57, ts57);
            check("SETTLE_AT_EXPIRY 2026-10-27".equals(se57.getString("exitPlan"))
                    && "2026-10-27".equals(se57.getString("exitDate")),
                    "Feed STRANGLE: expiry week states expiry settlement as the exit plan");

            // PRICE_ALERT: the user's own alert - level, the
            // configured cross direction, the firing price; no
            // paper flag (not a paper strategy), no option block.
            String paId = SystemFeed.priceAlertSignalId("RELIANCE.NS", true, 2950.0, 1790000000L);
            check("RELIANCE.NS|PRICE_ALERT|ABOVE|295000|1790000000".equals(paId),
                    "Feed PRICE_ALERT: signalId carries symbol, direction, level in paise, fire second");
            org.json.JSONObject pa57 = SystemFeed.priceAlertPayload(
                    "Reliance", "RELIANCE.NS", 2950.0, true, 2951.25, paId, ts57, false);
            check("PRICE_ALERT".equals(pa57.getString("strategy")) && !pa57.has("paper")
                    && !pa57.has("option") && !pa57.getBoolean("test"),
                    "Feed PRICE_ALERT: strategy set, NO paper flag, NO option block");
            check("Reliance".equals(pa57.getString("symbol")) && "RELIANCE.NS".equals(pa57.getString("yahooSymbol"))
                    && pa57.getDouble("level") == 2950 && "ABOVE".equals(pa57.getString("direction"))
                    && pa57.getDouble("ltp") == 2951.25 && paId.equals(pa57.getString("signalId")),
                    "Feed PRICE_ALERT: symbol, level, ABOVE direction, firing ltp, signalId");
            org.json.JSONObject pb57 = SystemFeed.priceAlertPayload(
                    "Reliance", "RELIANCE.NS", 2800.0, false, 2799.5, "X", ts57, false);
            check("BELOW".equals(pb57.getString("direction")) && pb57.getDouble("level") == 2800,
                    "Feed PRICE_ALERT: a below-level alert reports direction BELOW");

            // MARKET_FLUSH: the context mirror - breadth only.
            org.json.JSONObject fl57 = SystemFeed.marketFlushPayload(61, 63, "2026-10-26", ts57);
            check("MARKET_FLUSH".equals(fl57.getString("strategy")) && fl57.getInt("down") == 61
                    && fl57.getInt("total") == 63 && "2026-10-26".equals(fl57.getString("date"))
                    && "MARKET_FLUSH|2026-10-26".equals(fl57.getString("signalId"))
                    && !fl57.has("paper") && !fl57.has("option"),
                    "Feed FLUSH: strategy, down/total breadth, date, per-day signalId, no paper/option");

            // The gate the engines apply is the same pure gate
            // every feed mirror uses: switch ON + topic set.
            check(SystemFeed.shouldSend(true, "machine-topic")
                    && !SystemFeed.shouldSend(false, "machine-topic")
                    && !SystemFeed.shouldSend(true, "  "),
                    "Feed v57 gate: the paper/price mirrors send only when the feed is ON with a topic set");

            // Reflection pin for v57: exactly the four new
            // builders join the v51/v56 four; VERSION stays 51.
            int builders57 = 0;
            boolean m57 = false, s57 = false, p57 = false, f57 = false;
            for (java.lang.reflect.Method m : SystemFeed.class.getDeclaredMethods()) {
                String n = m.getName().toLowerCase(java.util.Locale.US);
                if (n.endsWith("payload")) builders57++;
                if (n.equals("mom60payload")) m57 = true;
                if (n.equals("stranglepayload")) s57 = true;
                if (n.equals("pricealertpayload")) p57 = true;
                if (n.equals("marketflushpayload")) f57 = true;
            }
            check(builders57 == 8 && m57 && s57 && p57 && f57 && SystemFeed.VERSION == 51,
                    "Feed v57: builders are ORB/fade/test/MANUAL + MOM60/STRANGLE/PRICE_ALERT/MARKET_FLUSH (8), version 51");
        }

        // ---- v58: a manual share goes to ALL human topics ----
        {
            // The manual human leg's list is the deduped union
            // {stored, private} - computed by a pure Prefs helper
            // that takes no privateLive flag, so the list is the
            // same in both privateLive worlds (DetailActivity's
            // send thread posts the share to exactly this list).
            check(Prefs.manualShareTopics("stored-topic", "private-topic")
                    .equals(java.util.Arrays.asList("stored-topic", "private-topic")),
                    "v58 manual topics: distinct stored + private -> both, stored first");
            check(Prefs.manualShareTopics("same-topic", "same-topic")
                    .equals(java.util.Collections.singletonList("same-topic")),
                    "v58 manual topics: identical topics dedupe to one");
            check(Prefs.manualShareTopics("", "private-topic")
                    .equals(java.util.Collections.singletonList("private-topic"))
                    && Prefs.manualShareTopics("stored-topic", "")
                    .equals(java.util.Collections.singletonList("stored-topic"))
                    && Prefs.manualShareTopics("", "").isEmpty()
                    && Prefs.manualShareTopics(null, "private-topic")
                    .equals(java.util.Collections.singletonList("private-topic")),
                    "v58 manual topics: empty/null topics are skipped");
            // Flag-independence, pinned structurally: no overload
            // of the helper accepts a boolean, so nothing can
            // narrow the manual list by privateLive.
            boolean noFlagOverload = true;
            for (java.lang.reflect.Method m : Prefs.class.getDeclaredMethods()) {
                if (!m.getName().equals("manualShareTopics")) continue;
                for (Class<?> pt : m.getParameterTypes()) {
                    if (pt == boolean.class) noFlagOverload = false;
                }
            }
            check(noFlagOverload,
                    "v58 manual topics: no privateLive-flagged overload - the manual list cannot be narrowed");
            // The seam DetailActivity uses: a public no-arg
            // instance getter returning the List.
            boolean seamOk = false, activeIntact = false;
            try {
                java.lang.reflect.Method mm = Prefs.class.getDeclaredMethod("manualShareTopics");
                seamOk = mm.getReturnType() == java.util.List.class
                        && java.lang.reflect.Modifier.isPublic(mm.getModifiers());
                java.lang.reflect.Method am = Prefs.class.getDeclaredMethod("activeTopics");
                activeIntact = am.getReturnType() == java.util.List.class
                        && java.lang.reflect.Modifier.isPublic(am.getModifiers())
                        && Prefs.class.getDeclaredMethod("privateLive").getReturnType() == boolean.class;
            } catch (Throwable ignored) { }
            check(seamOk,
                    "v58 manual topics: Prefs.manualShareTopics() is the manual leg's topic list");
            check(activeIntact,
                    "v58 manual topics: Prefs.activeTopics() + privateLive() unchanged - auto alerts keep the narrowing");
            // The both-sent Toast names the topics (plural) since
            // v58 - shareToast is private, so invoke it by
            // reflection with FEED_SEND = 1 (DetailActivity's own
            // constant, mirrored here).
            String toast58 = null;
            try {
                java.lang.reflect.Method sm = DetailActivity.class.getDeclaredMethod(
                        "shareToast", boolean.class, int.class, boolean.class, String.class);
                sm.setAccessible(true);
                toast58 = (String) sm.invoke(null, true, 1, true, null);
            } catch (Throwable ignored) { }
            check("Sent to your ntfy topics + order-system feed".equals(toast58),
                    "v58 manual topics: both-sent Toast is 'Sent to your ntfy topics + order-system feed'");
        }

        // ---- v59: feed health, rich feed JSON, combined mom60 board,
        //      ledger verdicts, strangle info flags, polish trio ----
        {
            String ts59 = "2026-10-26T09:31:00+05:30";
            java.time.LocalDate mon59 = java.time.LocalDate.of(2026, 10, 26); // Monday
            java.time.ZoneId ist59 = java.time.ZoneId.of("Asia/Kolkata");
            long bar59 = java.time.ZonedDateTime.of(mon59, java.time.LocalTime.of(10, 15), ist59)
                    .toEpochSecond();

            // -- (2a) Risk Gate sizing: OptionsMath.sizingFor is
            // riskLine()'s arithmetic in numeric form.
            OptionsMath.Ticket szTk = new OptionsMath.Ticket();
            szTk.premium = 42.5; szTk.premStop = 30.0; // per-unit risk 12.5
            OptionsMath.Sizing sz59 = OptionsMath.sizingFor(szTk, 100000, 1.0); // budget Rs 1,000
            check(sz59 != null && sz59.units == 80 && Math.abs(sz59.riskRs - 1000.0) < 1e-9,
                    "v59 sizing: units = floor(max-loss budget / per-unit risk), riskRs = units x per-unit risk");
            check(OptionsMath.riskLine(szTk, 100000, 1.0).contains("80 units")
                            && OptionsMath.riskLine(szTk, 100000, 1.0).contains("1,000"),
                    "v59 sizing: the same ticket through riskLine states the same 80 units / Rs 1,000");
            OptionsMath.Ticket szNan = new OptionsMath.Ticket();
            szNan.premium = Double.NaN; szNan.premStop = 1;
            OptionsMath.Ticket szThin = new OptionsMath.Ticket();
            szThin.premium = 10.0; szThin.premStop = 9.99; // per-unit risk 0.01 <= 0.05
            check(OptionsMath.sizingFor(null, 100000, 1.0) == null
                            && OptionsMath.sizingFor(szNan, 100000, 1.0) == null
                            && OptionsMath.sizingFor(szThin, 100000, 1.0) == null
                            && OptionsMath.sizingFor(szTk, 1000, 1.0) == null,
                    "v59 sizing: no ticket / NaN premium / dust per-unit risk / unaffordable unit -> no sizing (fields omitted, never invented)");

            // -- (2a) payloads carry the sizing; lots only where
            // the app itself knows the lot size.
            check(SystemFeed.lotSizeFor("^NSEBANK") == 30 && SystemFeed.lotSizeFor("^CNXFIN") == 60
                            && SystemFeed.lotSizeFor("^BSESN") == 20
                            && SystemFeed.lotSizeFor("RELIANCE.NS") == -1
                            && SystemFeed.lotSizeFor("^NSEI") == -1 && SystemFeed.lotSizeFor(null) == -1,
                    "v59 lots: lot sizes known only for the three mom60 indices - no equity/NIFTY lot is invented");
            Universe.Sym rel59 = new Universe.Sym("Reliance", "RELIANCE.NS", "NIFTY 50 companies");
            Analysis.Scan o59 = new Analysis.Scan();
            o59.sym = rel59; o59.setup = Analysis.Setup.ORB_DOWN;
            o59.entry = 2900; o59.stop = 2910; o59.target = 2890;
            OptionsMath.Ticket tk59 = new OptionsMath.Ticket();
            tk59.call = false; tk59.strike = 2900; tk59.premium = 42.5; tk59.premStop = 30.0;
            tk59.expiry = java.time.LocalDate.of(2026, 10, 27);
            org.json.JSONObject op59 = SystemFeed.orbPayload(o59, 82, "K59",
                    Analysis.alertTarget(o59), Analysis.alertTarget3(o59), tk59, false, ts59,
                    OptionsMath.sizingFor(tk59, 100000, 1.0));
            check(op59.getLong("qty") == 80 && Math.abs(op59.getDouble("riskRs") - 1000.0) < 1e-9
                            && !op59.has("lots"),
                    "v59 ORB payload: qty + riskRs from the risk gate; no lots key for a stock");
            Universe.Sym bn59 = new Universe.Sym("BANKNIFTY", "^NSEBANK", "");
            Analysis.Scan oB = new Analysis.Scan();
            oB.sym = bn59; oB.setup = Analysis.Setup.ORB_DOWN;
            oB.entry = 58000; oB.stop = 58100; oB.target = 57900;
            org.json.JSONObject opB = SystemFeed.orbPayload(oB, 82, "KB",
                    Analysis.alertTarget(oB), Analysis.alertTarget3(oB), tk59, false, ts59,
                    OptionsMath.sizingFor(tk59, 100000, 1.0));
            check(opB.getLong("qty") == 80 && opB.getLong("lots") == 2,
                    "v59 ORB payload (index): lots = whole lots of qty at the known lot (80 units / 30 = 2)");
            org.json.JSONObject opSmall = SystemFeed.orbPayload(oB, 82, "KB2",
                    Analysis.alertTarget(oB), Analysis.alertTarget3(oB), tk59, false, ts59,
                    OptionsMath.sizingFor(tk59, 25000, 1.0)); // 20 units < one lot of 30
            check(opSmall.getLong("qty") == 20 && !opSmall.has("lots"),
                    "v59 ORB payload: qty under one lot carries qty/riskRs but no lots key");
            org.json.JSONObject opNull = SystemFeed.orbPayload(o59, 82, "K0",
                    Analysis.alertTarget(o59), Analysis.alertTarget3(o59), tk59, false, ts59, null);
            check(!opNull.has("qty") && !opNull.has("riskRs") && !opNull.has("lots"),
                    "v59 ORB payload: null sizing omits all three fields");
            Analysis.Scan f59 = new Analysis.Scan();
            f59.sym = rel59; f59.fadeEntry = 2880; f59.fadeStop = 2905; f59.fadeTarget = 2855;
            f59.fadeUp = false;
            org.json.JSONObject fp59 = SystemFeed.fadePayload(f59, "F59", tk59, false, ts59,
                    OptionsMath.sizingFor(tk59, 100000, 1.0));
            check(fp59.getLong("qty") == 80 && Math.abs(fp59.getDouble("riskRs") - 1000.0) < 1e-9,
                    "v59 fade payload: qty + riskRs ride along");
            org.json.JSONObject mp59 = SystemFeed.manualPayload(o59, 82, "M59",
                    Analysis.alertTarget(o59), Analysis.alertTarget3(o59), tk59, ts59,
                    OptionsMath.sizingFor(tk59, 100000, 1.0));
            check(mp59.getLong("qty") == 80 && Math.abs(mp59.getDouble("riskRs") - 1000.0) < 1e-9,
                    "v59 MANUAL payload: qty + riskRs ride along");

            // -- (2b) MOM60 payloads: dte + riskRs from the signal.
            Mom60.Signal mBn59 = Mom60.buildSignal(Mom60.BN, bar59, true, 0.62, 58340, mon59, 14.0);
            org.json.JSONObject mm59 = SystemFeed.mom60Payload(mBn59, ts59);
            check(mm59.getInt("dte") == 1,
                    "v59 MOM60 payload: dte 1 for Mon Oct 26 -> Tue Oct 27 (BN monthly)");
            check(Math.abs(mm59.getDouble("riskRs")
                            - Math.round((mBn59.entryPremium - mBn59.stopPremium) * 30 * 100) / 100.0) < 1e-9
                            && Math.abs((mBn59.entryPremium - mBn59.stopPremium)
                                    - mBn59.entryPremium * 0.30) < 1e-9,
                    "v59 MOM60 payload: riskRs = the 30% premium stop x lot 30");
            Mom60.Signal mSx59 = Mom60.buildSignal(Mom60.SENSEX, bar59, false, -0.55, 84700, mon59, 12.0);
            org.json.JSONObject mx59 = SystemFeed.mom60Payload(mSx59, ts59);
            check(mx59.getInt("dte") == 3 && Math.abs(mx59.getDouble("riskRs")
                            - Math.round((mSx59.entryPremium - mSx59.stopPremium) * 20 * 100) / 100.0) < 1e-9,
                    "v59 MOM60 Sensex payload: dte 3 (Mon -> Thu BSE expiry), riskRs at lot 20");

            // -- (2c) STRANGLE payload: dte from entry Monday.
            double[] c59 = new double[375];
            java.util.Arrays.fill(c59, 58340);
            Strangle.Entry e59a = Strangle.buildEntry(momDay(java.time.LocalDate.of(2026, 10, 5), c59),
                    java.time.LocalDate.of(2026, 10, 5), 12.0);
            Strangle.Entry e59b = Strangle.buildEntry(momDay(mon59, c59), mon59, 12.0);
            check(SystemFeed.stranglePayload(e59a, ts59).getInt("dte") == 22
                            && SystemFeed.stranglePayload(e59b, ts59).getInt("dte") == 1,
                    "v59 STRANGLE payload: dte counts entry Monday -> expiry (22 normal week, 1 expiry week)");

            // -- (6a) recurring price alerts: the stored-format flag
            // and the payload field.
            check(Prefs.priceAlertRecurring("RELIANCE.NS|ABOVE|2950.0|R")
                            && !Prefs.priceAlertRecurring("RELIANCE.NS|ABOVE|2950.0")
                            && !Prefs.priceAlertRecurring("RELIANCE.NS|ABOVE|2950.0|X")
                            && !Prefs.priceAlertRecurring(null) && !Prefs.priceAlertRecurring(""),
                    "v59 price alerts: only the |R 4th field marks an entry recurring; legacy entries stay one-shot");
            check(SystemFeed.priceAlertPayload("Reliance", "RELIANCE.NS", 2950.0, true, 2951.0,
                            "P1", ts59, true).getBoolean("recurring")
                            && !SystemFeed.priceAlertPayload("Reliance", "RELIANCE.NS", 2950.0, true, 2951.0,
                            "P2", ts59, false).getBoolean("recurring"),
                    "v59 PRICE_ALERT payload: recurring field states the alert's own kind");

            // -- (1) feed health card text, from a fixture state.
            long now59 = java.time.ZonedDateTime.of(2026, 10, 26, 9, 41, 0, 0, ist59)
                    .toInstant().toEpochMilli();
            long t940 = java.time.ZonedDateTime.of(2026, 10, 26, 9, 40, 0, 0, ist59)
                    .toInstant().toEpochMilli();
            long t815 = java.time.ZonedDateTime.of(2026, 10, 25, 20, 15, 0, 0, ist59)
                    .toInstant().toEpochMilli();
            FeedHealth.Chan chOk = new FeedHealth.Chan("Human · stored topic", "my-topic", t940, t940, true);
            FeedHealth.Chan chNone = new FeedHealth.Chan("Human · private topic", "priv-topic", 0, 0, false);
            FeedHealth.Chan chFail = new FeedHealth.Chan("Machine feed", "feed-topic", t940, t815, false);
            check(FeedHealth.chanLine(chOk, now59).equals(
                            "Human · stored topic · my-topic: last post 09:40 — delivered ✓"),
                    "v59 health: a delivered channel line (same-day HH:mm)");
            check(FeedHealth.chanLine(chNone, now59).equals(
                            "Human · private topic · priv-topic: no posts yet this install"),
                    "v59 health: an unstamped channel says so honestly");
            check(FeedHealth.chanLine(chFail, now59).equals(
                            "Machine feed · feed-topic: last post 09:40 — FAILED ✗ · last success 25 Oct 20:15"),
                    "v59 health: a failed last attempt still shows the earlier success (older date gets d MMM)");
            FeedHealth.Chan chFail0 = new FeedHealth.Chan("Machine feed", "feed-topic", t940, 0, false);
            check(FeedHealth.chanLine(chFail0, now59).endsWith("· no success yet"),
                    "v59 health: failed with no success yet says exactly that");
            java.util.List<String> hl = FeedHealth.lines(true, "ntfy.sh", chOk, chNone, chFail, now59);
            check(hl.size() == 4 && hl.get(0).equals("System Feed switch: ON · feed server ntfy.sh"),
                    "v59 health: card opens with the feed switch state, one line per channel");
            java.util.List<String> hlOff = FeedHealth.lines(false, "ntfy.sh", chOk, chNone, chFail, now59);
            check(hlOff.get(0).startsWith("System Feed switch: OFF") && hlOff.get(0).contains("gets nothing"),
                    "v59 health: switch OFF is stated with its consequence for the machine topic");
            check(FeedHealth.legLine("Stored topic", true, "my-topic").equals("Stored topic: ✓ sent — my-topic")
                            && FeedHealth.legLine("Machine feed", false, "no topic set")
                                    .equals("Machine feed: ✗ FAILED — no topic set"),
                    "v59 health: the test-all button's per-leg report lines");
            boolean stampSeam = false;
            try {
                stampSeam = Prefs.class.getDeclaredMethod("stampChannel", String.class, boolean.class) != null
                        && Prefs.class.getDeclaredMethod("chanAttemptAt", String.class).getReturnType() == long.class
                        && Prefs.class.getDeclaredMethod("chanSuccessAt", String.class).getReturnType() == long.class
                        && Prefs.class.getDeclaredMethod("chanLastOk", String.class).getReturnType() == boolean.class
                        && Prefs.CH_STORED.equals("hs") && Prefs.CH_PRIVATE.equals("hp") && Prefs.CH_FEED.equals("fd");
            } catch (Throwable ignored) { }
            check(stampSeam, "v59 health: Prefs carries the per-channel stamp seam the send paths write");

            // -- (3) combined mom60 board aggregation.
            java.util.function.BiFunction<String, String, Mom60Store.Rec> mkRec = (d, e) -> {
                Mom60Store.Rec r = new Mom60Store.Rec();
                r.date = d; r.expiry = e; r.closed = true; r.entryPremium = 100;
                return r;
            };
            Mom60Store.Rec b1 = mkRec.apply("2026-10-19", "2026-10-27"); b1.pnlPct = 10;  // DTE 8 -> cell 2
            Mom60Store.Rec b2 = mkRec.apply("2026-10-26", "2026-10-27"); b2.pnlPct = -30; b2.entryPremium = 50; // DTE 1 -> cell 0
            Mom60Store.Rec b3 = mkRec.apply("2026-10-26", "2026-10-27"); b3.closed = false; // open: totals only
            Mom60Store.Rec b4 = mkRec.apply("", ""); b4.pnlPct = 5; b4.entryPremium = 40;   // no DTE derivable
            Mom60Board.Idx bx = Mom60Board.stats(Mom60.BN, java.util.Arrays.asList(b1, b2, b3, b4));
            check(bx.signals == 4 && bx.closed == 3 && bx.open == 1 && bx.wins == 2,
                    "v59 board: totals count every record; wins are closed with pnl > 0");
            check(Math.abs(bx.avgPct - (-5.0)) < 1e-9 && Math.abs(bx.totalRs - (300 - 450 + 60)) < 1e-9,
                    "v59 board: avg %/trade and total Rs at the index lot (30)");
            check(bx.cellN[0] == 1 && Math.abs(bx.cellAvg[0] + 30) < 1e-9
                            && bx.cellN[2] == 1 && Math.abs(bx.cellAvg[2] - 10) < 1e-9
                            && bx.cellN[1] == 0 && bx.cellN[3] == 0 && bx.cellUnknown == 1,
                    "v59 board: DTE cells from the stored dates; an undatable record counts in totals, in no cell");
            check(Mom60Board.cellOf(0) == 0 && Mom60Board.cellOf(2) == 0 && Mom60Board.cellOf(3) == 1
                            && Mom60Board.cellOf(7) == 1 && Mom60Board.cellOf(8) == 2 && Mom60Board.cellOf(15) == 2
                            && Mom60Board.cellOf(16) == 3 && Mom60Board.cellOf(-1) == -1,
                    "v59 board: cell bounds are 0-2 / 3-7 / 8-15 / 16+");
            check(Mom60Board.dteOf(b1) == 8 && Mom60Board.dteOf(b4) == -1
                            && Mom60Board.unknownMaterial(bx)
                            && !Mom60Board.unknownMaterial(Mom60Board.stats(Mom60.BN,
                                    java.util.Collections.singletonList(b1))),
                    "v59 board: DTE derives from stored dates; the unknown-fraction note fires only when material (>= 1/4)");

            // -- (4) ledger verdicts: same ORB trades under both
            // exits, fades on their own; Douglas 20 before a verdict.
            java.util.function.Supplier<Ledger.Rec> mkL = () -> {
                Ledger.Rec r = new Ledger.Rec();
                r.yahoo = "X.NS"; r.entry = 1000; r.stop = 1010; r.target = 990; r.grade = true;
                return r;
            };
            Ledger.Rec lw = mkL.get(); lw.kind = "ORB_DOWN"; lw.outcome = 1; lw.outcome3 = -1;
            Ledger.Rec ll = mkL.get(); ll.kind = "ORB_DOWN"; ll.outcome = -1; ll.outcome3 = -1;
            Ledger.Rec lf = mkL.get(); lf.kind = "ORB_DOWN"; lf.outcome = 2; lf.outcome3 = 2; lf.movePct = 0.5;
            Ledger.Rec lng = mkL.get(); lng.kind = "ORB_DOWN"; lng.grade = false; lng.outcome = 1; lng.outcome3 = 1;
            Ledger.Rec lpre = mkL.get(); lpre.kind = "ORB_DOWN"; lpre.outcome = 1; lpre.outcome3 = 0; // pre-v50
            Ledger.Rec lfd = mkL.get(); lfd.kind = "FADE_DN"; lfd.outcome = 1;
            java.util.List<Ledger.Verdict> vs = Ledger.verdicts(
                    java.util.Arrays.asList(lw, ll, lf, lng, lpre, lfd));
            Ledger.Verdict vOrb = vs.get(0), v3 = vs.get(1), vFade = vs.get(2);
            check(vOrb.n == 4 && vOrb.wins == 2 && Math.abs(vOrb.avgR - (1 - 1 + 0.5 + 1) / 4.0) < 1e-9,
                    "v59 verdicts: ORB plan-target family = alert-grade judged records (pre-v50 included), flats pay their actual move in R");
            check(v3.n == 3 && v3.wins == 0 && Math.abs(v3.avgR - (-1 - 1 + 0.5) / 3.0) < 1e-9,
                    "v59 verdicts: the 3R family judges the SAME trades on outcome3; pre-v50 records (outcome3 unset) never enter it");
            check(vFade.n == 1 && vFade.wins == 1 && Math.abs(vFade.avgR - 1.0) < 1e-9,
                    "v59 verdicts: gap fade family on its own records, a win pays its 1R");
            check(Ledger.verdictLine(vOrb).equals("Collecting — 4 of 20 judged."),
                    "v59 verdicts: under 20 judged the line counts, never verdicts");
            Ledger.Verdict vBig = new Ledger.Verdict(); vBig.n = 25; vBig.avgR = 0.42;
            Ledger.Verdict vNeg = new Ledger.Verdict(); vNeg.n = 25; vNeg.avgR = -0.30;
            Ledger.Verdict vFlat = new Ledger.Verdict(); vFlat.n = 25; vFlat.avgR = 0.01;
            Ledger.Verdict vEmpty = new Ledger.Verdict();
            check(Ledger.verdictLine(vBig).contains("ahead so far") && Ledger.verdictLine(vBig).contains("+0.42R")
                            && Ledger.verdictLine(vNeg).contains("behind so far")
                            && Ledger.verdictLine(vFlat).contains("too close to call")
                            && Ledger.verdictLine(vEmpty).equals("No judged plans yet — fills from live sessions."),
                    "v59 verdicts: at n >= 20 the plain verdict speaks (ahead / behind / too close), empty is honest");

            // -- (5) strangle information flags: pure readings, the
            // alert body itself unchanged.
            java.util.function.Function<double[], YahooClient.Chart> mkDaily = closes -> {
                YahooClient.Chart c = new YahooClient.Chart();
                int n = closes.length;
                c.ts = new long[n]; c.open = closes.clone(); c.high = closes.clone();
                c.low = closes.clone(); c.close = closes.clone(); c.volume = new long[n];
                for (int i = 0; i < n; i++) c.ts[i] = 1760000000L + i * 86400L;
                return c;
            };
            double[] calm = new double[30];
            for (int i = 0; i < 30; i++) calm[i] = 100 + (i % 2) * 0.1;
            double[] shock = calm.clone(); shock[29] = 103.0; // last session +2.9%
            check(Math.abs(Strangle.sigma20(mkDaily.apply(calm)) - 0.001) < 0.0005
                            && Double.isNaN(Strangle.sigma20(mkDaily.apply(new double[]{100, 101, 102}))),
                    "v59 strangle flags: sigma is the 20d stdev of daily log returns; too little tape -> NaN");
            check(Strangle.aftershockFlag(mkDaily.apply(calm)) == 0
                            && Strangle.aftershockFlag(mkDaily.apply(shock)) == 1,
                    "v59 strangle flags: aftershock fires when a prior-2-session |return| reaches 2 sigma");
            NseChain.Probe probe59 = new NseChain.Probe();
            probe59.pcr = 1.24;
            String ilOk = Strangle.infoLines(probe59, mkDaily.apply(shock));
            check(ilOk.contains("PCR (chain): 1.24") && ilOk.contains("Aftershock watch: YES")
                            && ilOk.contains("≥2σ") && ilOk.contains("Information only")
                            && ilOk.contains("paper grade"),
                    "v59 strangle flags: answered probe + shock day -> both flag lines + the information-only statement");
            String ilNo = Strangle.infoLines(null, mkDaily.apply(calm));
            check(ilNo.contains("PCR: no answer from the chain this morning")
                            && ilNo.contains("Aftershock watch: no"),
                    "v59 strangle flags: unanswered probe prints its no-answer line; a calm tape prints watch: no");
            check(!Strangle.body(e59b).contains("PCR") && !Strangle.body(e59b).contains("Aftershock"),
                    "v59 strangle flags: Strangle.body itself is unchanged - the engine appends the flags after the plan");

            // -- (6b) mom60 DTE lines on all three indices' alerts.
            check(Mom60.body(mBn59).contains("DTE 1 to expiry.")
                            && Mom60.body(mBn59).contains(Mom60.LOW_DTE_WARNING)
                            && Mom60.LOW_DTE_WARNING.equals(
                                    "Low-DTE cell — this is mom60's weakest cell in the study; paper evidence only."),
                    "v59 mom60 BN alert: DTE line + the standing low-DTE warning verbatim at DTE 1");
            Mom60.Signal mOld = Mom60.buildSignal(Mom60.BN, bar59, true, 0.62, 58340,
                    java.time.LocalDate.of(2026, 10, 19), 14.0); // expiry Oct 27 -> DTE 8
            check(Mom60.body(mOld).contains("DTE 8 to expiry.")
                            && !Mom60.body(mOld).contains("Low-DTE cell"),
                    "v59 mom60 alert: DTE 8 carries the count with no warning");
            check(Mom60.body(mSx59).contains("DTE 3 to expiry.")
                            && !Mom60.body(mSx59).contains("Low-DTE cell"),
                    "v59 mom60 Sensex alert: DTE 3 line, no warning (its collapse cell is BN's finding; the count still prints)");
            Mom60.Signal mFin59 = Mom60.buildSignal(Mom60.FIN, bar59, true, 0.55, 24630, mon59, 12.0);
            check(Mom60.body(mFin59).contains("DTE 1 to expiry.")
                            && Mom60.body(mFin59).contains("Low-DTE cell"),
                    "v59 mom60 FINNIFTY alert: DTE line + warning at DTE 1");

            // -- (6c) BSE chain probe: defensive parse, honest line.
            check(BseChain.appliesTo("^BSESN") && !BseChain.appliesTo("^NSEI")
                            && !BseChain.appliesTo("RELIANCE.NS") && !BseChain.appliesTo(null),
                    "v59 BSE probe: serves the Sensex only");
            check(BseChain.chainUrl().contains("api.bseindia.com") && BseChain.chainUrl().contains("BseIndiaAPI"),
                    "v59 BSE probe: the request follows the documented BseIndiaAPI shape");
            String bseJson = "{\"Table\":["
                    + "{\"StrikePrice\":\"80000\",\"CE_OI\":\"12000\",\"PE_OI\":\"15000\",\"CE_LTP\":\"210.5\",\"PE_LTP\":\"180.0\",\"ExpiryDate\":\"2026-10-29\"},"
                    + "{\"StrikePrice\":\"80100\",\"CE_OI\":\"8000\",\"PE_OI\":\"9000\",\"CE_LTP\":\"160.0\",\"PE_LTP\":\"230.0\",\"ExpiryDate\":\"2026-10-29\"},"
                    + "{\"StrikePrice\":\"79900\",\"CE_OI\":\"5000\",\"PE_OI\":\"7000\",\"CE_LTP\":\"260.0\",\"PE_LTP\":\"140.0\",\"ExpiryDate\":\"2026-10-29\"}]}";
            NseChain.Probe bp = BseChain.parse(bseJson, 80050);
            check(bp != null && bp.strike == 80000 && bp.totCeOi == 25000 && bp.totPeOi == 31000
                            && Math.abs(bp.pcr - 1.24) < 1e-9
                            && java.time.LocalDate.of(2026, 10, 29).equals(bp.expiry),
                    "v59 BSE probe: a coherent chain parses - ATM row, OI totals, PCR, expiry");
            check(BseChain.line(bp, true).startsWith("BSE live chain:")
                            && BseChain.line(bp, true).contains("chain PCR 1.24")
                            && !BseChain.line(bp, true).contains("IV"),
                    "v59 BSE probe: the line is labelled BSE and carries no IV the chain did not give");
            check(BseChain.parse("", Double.NaN) == null && BseChain.parse("{}", Double.NaN) == null
                            && BseChain.parse("{\"Table\":[]}", 80050) == null
                            && BseChain.parse("not json at all........", 80050) == null
                            && BseChain.parse("{\"Table\":[{\"StrikePrice\":\"80000\"}]}", 80050) == null,
                    "v59 BSE probe: empty / blocked / incoherent replies parse to null - no answer, never a fabrication");
            NseChain.Probe bpArr = BseChain.parse("[{\"strikePrice\":80000,\"CE\":{\"openInterest\":100},"
                    + "\"PE\":{\"openInterest\":200}}]", 80010);
            check(bpArr != null && Math.abs(bpArr.pcr - 2.0) < 1e-9,
                    "v59 BSE probe: the root-array shape with nested CE/PE objects also parses");

            // -- reflection pin, unchanged by v59: field additions
            // are not new builders.
            int builders59 = 0;
            for (java.lang.reflect.Method m : SystemFeed.class.getDeclaredMethods()) {
                String n = m.getName().toLowerCase(java.util.Locale.US);
                if (n.endsWith("payload")) builders59++;
            }
            check(builders59 == 8 && SystemFeed.VERSION == 51,
                    "v59 feed: still exactly 8 payload builders, VERSION 51 - new optional fields only");
        }

        // ---- review 26 Oct 2026: holiday-shifted expiries ----
        // An expiry on an exchange holiday moves to the trading day before;
        // IraAlgo refuses a feed expiry that is not listed.
        {
            java.time.LocalDate d = java.time.LocalDate.of(2026, 11, 2);
            check(OptionsMath.monthlyExpiry(d).equals(java.time.LocalDate.of(2026, 11, 23)),
                    "review: Nov 2026 monthly (24 Nov is Guru Nanak Jayanti) is Mon 23 Nov");
            check(OptionsMath.monthlyExpiry(java.time.LocalDate.of(2026, 11, 23)).equals(java.time.LocalDate.of(2026, 11, 23)),
                    "review: on Mon 23 Nov the monthly expires today");
            check(OptionsMath.monthlyExpiry(java.time.LocalDate.of(2026, 11, 24)).equals(java.time.LocalDate.of(2026, 12, 29)),
                    "review: after the shifted Nov expiry the monthly rolls to Tue 29 Dec");
            check(OptionsMath.monthlyExpiry(java.time.LocalDate.of(2026, 3, 16)).equals(java.time.LocalDate.of(2026, 3, 30)),
                    "review: Mar 2026 monthly (31 Mar Mahavir Jayanti) is Mon 30 Mar");
            check(OptionsMath.monthlyExpiry(java.time.LocalDate.of(2026, 3, 31)).equals(java.time.LocalDate.of(2026, 4, 28)),
                    "review: on the 31 Mar holiday the monthly is April's");
            check(OptionsMath.monthlyExpiry(java.time.LocalDate.of(2026, 12, 1)).equals(java.time.LocalDate.of(2026, 12, 29)),
                    "review: a non-holiday last Tuesday is unchanged (29 Dec)");
            check(OptionsMath.weeklyExpiry(java.time.LocalDate.of(2026, 11, 5)).equals(java.time.LocalDate.of(2026, 11, 9)),
                    "review: weekly expiry of the Diwali week (10 Nov holiday) is Mon 9 Nov");
            check(OptionsMath.weeklyExpiry(java.time.LocalDate.of(2026, 11, 9)).equals(java.time.LocalDate.of(2026, 11, 9))
                            && OptionsMath.daysToExpiry(java.time.LocalDate.of(2026, 11, 9)) == 0,
                    "review: Mon 9 Nov is that week's expiry day (0 days)");
            check(OptionsMath.weeklyExpiry(java.time.LocalDate.of(2026, 11, 10)).equals(java.time.LocalDate.of(2026, 11, 17)),
                    "review: on the 10 Nov holiday the nearest weekly is Tue 17 Nov");
            check(OptionsMath.weeklyExpiry(java.time.LocalDate.of(2026, 11, 18)).equals(java.time.LocalDate.of(2026, 11, 23)),
                    "review: weekly expiry in the Guru Nanak week is Mon 23 Nov");
            check(OptionsMath.weeklyExpiry(java.time.LocalDate.of(2027, 1, 1)).equals(java.time.LocalDate.of(2027, 1, 5)),
                    "review: a 2027 Tuesday with no listed holiday is unchanged");
            check(OptionsMath.monthlyExpiry(java.time.LocalDate.of(2026, 12, 30)).equals(java.time.LocalDate.of(2027, 1, 25))
                            && OptionsMath.weeklyExpiry(java.time.LocalDate.of(2027, 1, 21)).equals(java.time.LocalDate.of(2027, 1, 25)),
                    "review: Republic Day 2027 (Tue 26 Jan) moves the Jan expiry to Mon 25 Jan");
            check(OptionsMath.monthlyExpiryBse(java.time.LocalDate.of(2026, 11, 2)).equals(java.time.LocalDate.of(2026, 11, 26)),
                    "review: BSE Nov monthly (Thu 26 Nov, not a holiday) is unchanged");
            Mom60.Cfg bn = Mom60.BN;
            check(bn.expiry(java.time.LocalDate.of(2026, 11, 2)).equals(java.time.LocalDate.of(2026, 11, 23)),
                    "review: mom60 BANKNIFTY's November contract is the 23 Nov expiry");
            double[] flat = new double[375];
            java.util.Arrays.fill(flat, 52000);
            java.time.LocalDate m23 = java.time.LocalDate.of(2026, 11, 23), m16 = java.time.LocalDate.of(2026, 11, 16);
            Strangle.Entry sn = Strangle.buildEntry(momDay(m23, flat), m23, 15.0);
            check(sn != null && sn.expiry.equals(m23) && sn.expiryWeek && sn.exitDate.equals(m23),
                    "review: a strangle entered Mon 23 Nov settles at that day's expiry");
            Strangle.Entry s16 = Strangle.buildEntry(momDay(m16, flat), m16, 15.0);
            check(s16 != null && s16.expiry.equals(m23) && !s16.expiryWeek
                            && s16.exitDate.equals(java.time.LocalDate.of(2026, 11, 19)),
                    "review: a strangle entered Mon 16 Nov is not an expiry week (expiry 23 Nov after Thu 19 Nov)");
            Universe.Sym infy = new Universe.Sym("Infosys", "INFY.NS", "NIFTY 50 companies");
            OptionsMath.Ticket tn = OptionsMath.ticket(infy, true, 1500, 1490, 1520, 25, java.time.LocalDate.of(2026, 11, 2));
            check(tn.expiry.equals(java.time.LocalDate.of(2026, 11, 23)) && tn.dte == 21,
                    "review: a stock ticket on 2 Nov carries the 23 Nov expiry (21 days)");
        }

        // ---- review 26 Oct 2026: which symbols alert, and only fresh ----
        {
            Universe.Sym relA = null, vixA = null, usdA = null, mid50 = null, sensexA = null, crude = null,
                    niftyA = null, bnA = null, finA = null, comexGold = null;
            for (Universe.Sym u : Universe.all()) {
                if ("RELIANCE.NS".equals(u.yahoo)) relA = u;
                if ("^INDIAVIX".equals(u.yahoo)) vixA = u;
                if ("USDINR=X".equals(u.yahoo)) usdA = u;
                if ("^NSEMDCP50".equals(u.yahoo)) mid50 = u;
                if ("^BSESN".equals(u.yahoo)) sensexA = u;
                if ("CL=F".equals(u.yahoo)) crude = u;
                if ("GC=F".equals(u.yahoo)) comexGold = u;
                if ("^NSEI".equals(u.yahoo)) niftyA = u;
                if ("^NSEBANK".equals(u.yahoo)) bnA = u;
                if ("^CNXFIN".equals(u.yahoo)) finA = u;
            }
            check(Universe.alertable(relA) && Universe.alertable(niftyA) && Universe.alertable(bnA)
                            && Universe.alertable(finA),
                    "review: NSE F&O stocks and NIFTY / BANKNIFTY / FINNIFTY may alert");
            check(!Universe.alertable(vixA) && !Universe.alertable(usdA) && !Universe.alertable(mid50)
                            && !Universe.alertable(sensexA) && !Universe.alertable(crude)
                            && !Universe.alertable(comexGold) && !Universe.alertable(Universe.gold())
                            && !Universe.alertable(null),
                    "review: VIX, USD/INR, MIDCAP 50, SENSEX and commodities never strategy-alert");
            int stocksA = 0, stocksAll = 0;
            for (Universe.Sym u : Universe.all()) {
                if (Universe.G_NIFTY.equals(u.group) || Universe.G_BANK.equals(u.group)) {
                    stocksAll++;
                    if (Universe.alertable(u)) stocksA++;
                }
            }
            check(stocksAll > 40 && stocksA == stocksAll, "review: every listed stock stays alertable (" + stocksA + ")");
            long bar = java.time.ZonedDateTime.of(2026, 10, 27, 9, 35, 0, 0,
                    java.time.ZoneId.of("Asia/Kolkata")).toEpochSecond();
            check(Analysis.alertFresh(bar, bar + 300) && Analysis.alertFresh(bar, bar + 300 + 600),
                    "review: an alert is fresh from bar close to 10 minutes after it");
            check(!Analysis.alertFresh(bar, bar + 300 + 601) && !Analysis.alertFresh(bar, bar + 3 * 3600)
                            && !Analysis.alertFresh(0, bar),
                    "review: a break found 10+ minutes late (or no bar) never alerts");
            check(NseChain.endpointFor("^NSEMDCP50") == null && "indices:FINNIFTY".equals(NseChain.endpointFor("^CNXFIN")),
                    "review: MIDCAP 50 is not mapped to MIDCPNIFTY's chain");
        }

        System.out.println("\nALL TESTS PASSED: " + passed + " checks");
    }
}
