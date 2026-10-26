package com.irathinker.app;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.PrintWriter;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** R110 (26 Oct 2026): runs IraThinker's OWN strategy code on historical bars and writes every signal it produces, so
 *  research/hunt/r110_irathinker/r110study.py can price them on real options. Nothing here re-implements a rule: each
 *  mode calls the app's engine exactly as the app does (Analysis.signalHistory for ORB / gap fade, Mom60.Watcher bar by
 *  bar for mom60, Strangle.buildEntry + grade for the strangle, Predict.forecast at 10:30 for the prediction engine).
 *  Research only, never shipped in the APK (it lives outside the app's source tree).
 *
 *  Modes (bar CSVs from r110prep.py: ts epoch-sec of the bar start, open, high, low, close, volume):
 *    orb     <bars_dir> <IDX> <out.csv>
 *    mom60   <bars_dir> <IDX> <thresholdPct> <strikeStep> <bseExpiry> <out.csv>
 *    strangle <bars_dir> <out.csv>                     (BANKNIFTY)
 *    predict <bars_dir> <IDX> <out.csv> */
public class R110Runner {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    static YahooClient.Chart load(String path) throws Exception {
        List<long[]> t = new ArrayList<>();
        List<double[]> p = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            String line = r.readLine();
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) continue;
                String[] s = line.split(",");
                t.add(new long[]{Long.parseLong(s[0])});
                p.add(new double[]{Double.parseDouble(s[1]), Double.parseDouble(s[2]),
                        Double.parseDouble(s[3]), Double.parseDouble(s[4])});
            }
        }
        return chart(t, p, 0, t.size());
    }

    static YahooClient.Chart chart(List<long[]> t, List<double[]> p, int from, int to) {
        YahooClient.Chart c = new YahooClient.Chart();
        int n = to - from;
        c.ts = new long[n]; c.open = new double[n]; c.high = new double[n];
        c.low = new double[n]; c.close = new double[n]; c.volume = new long[n];
        for (int i = 0; i < n; i++) {
            c.ts[i] = t.get(from + i)[0];
            double[] q = p.get(from + i);
            c.open[i] = q[0]; c.high[i] = q[1]; c.low[i] = q[2]; c.close[i] = q[3];
        }
        return c;
    }

    /** A sub-chart [from, to) of c. */
    static YahooClient.Chart slice(YahooClient.Chart c, int from, int to) {
        YahooClient.Chart s = new YahooClient.Chart();
        s.ts = java.util.Arrays.copyOfRange(c.ts, from, to);
        s.open = java.util.Arrays.copyOfRange(c.open, from, to);
        s.high = java.util.Arrays.copyOfRange(c.high, from, to);
        s.low = java.util.Arrays.copyOfRange(c.low, from, to);
        s.close = java.util.Arrays.copyOfRange(c.close, from, to);
        s.volume = java.util.Arrays.copyOfRange(c.volume, from, to);
        return s;
    }

    static LocalDate day(long ts) { return Instant.ofEpochSecond(ts).atZone(IST).toLocalDate(); }

    /** Each day's [first, last+1) index range in a chart, in date order. */
    static Map<LocalDate, int[]> days(YahooClient.Chart c) {
        Map<LocalDate, int[]> m = new LinkedHashMap<>();
        for (int i = 0; i < c.ts.length; i++) {
            LocalDate d = day(c.ts[i]);
            int[] r = m.get(d);
            if (r == null) m.put(d, new int[]{i, i + 1}); else r[1] = i + 1;
        }
        return m;
    }

    /** A daily close chart of the days strictly before `before` (IraThinker's realised-vol input). */
    static YahooClient.Chart dailyBefore(YahooClient.Chart m1, Map<LocalDate, int[]> dd, LocalDate before) {
        List<Double> closes = new ArrayList<>();
        for (Map.Entry<LocalDate, int[]> e : dd.entrySet()) {
            if (!e.getKey().isBefore(before)) break;
            closes.add(m1.close[e.getValue()[1] - 1]);
        }
        int from = Math.max(0, closes.size() - 60);
        YahooClient.Chart c = new YahooClient.Chart();
        int n = closes.size() - from;
        c.ts = new long[n]; c.open = new double[n]; c.high = new double[n];
        c.low = new double[n]; c.close = new double[n]; c.volume = new long[n];
        for (int i = 0; i < n; i++) {
            double v = closes.get(from + i);
            c.open[i] = c.high[i] = c.low[i] = c.close[i] = v;
        }
        return c;
    }

    /** IraThinker's live IV rule: 20-day realised vol, clamped 10..90. */
    static double ivFor(YahooClient.Chart daily) {
        double rv = OptionsMath.realizedVol(daily);
        return Double.isNaN(rv) ? Double.NaN : Math.max(10, Math.min(rv, 90));
    }

    static Universe.Sym sym(String idx) {
        String y = yahooOf(idx);
        for (Universe.Sym u : Universe.all()) if (u.yahoo.equals(y)) return u;
        throw new IllegalArgumentException(idx);
    }

    static String yahooOf(String idx) {
        switch (idx) {
            case "NIFTY": return "^NSEI";
            case "BANKNIFTY": return "^NSEBANK";
            case "FINNIFTY": return "^CNXFIN";
            case "SENSEX": return "^BSESN";
            default: throw new IllegalArgumentException(idx);
        }
    }

    static String f(double v) { return Double.isNaN(v) ? "" : String.format(java.util.Locale.ROOT, "%.4f", v); }

    // ---- ORB + gap fade: the Signal Test engine ----
    static void orb(String dir, String idx, String out) throws Exception {
        YahooClient.Chart m5 = load(dir + "/" + idx + "_5m.csv");
        YahooClient.Chart vix = load(dir + "/VIX_5m.csv");
        Universe.Sym s = sym(idx);
        List<Analysis.TestedSignal> hist = Analysis.signalHistory(m5, s, vix);
        try (PrintWriter w = new PrintWriter(out)) {
            w.println("day,kind,up,signal_ts,entry,stop,target1r,target2r,target3r,score,alert_grade,outcome,outcome2,outcome3");
            for (Analysis.TestedSignal t : hist) {
                Analysis.Scan sc = t.scan;
                if ("ORB".equals(t.kind)) {
                    w.println(t.day + ",ORB," + (sc.setup == Analysis.Setup.ORB_UP ? 1 : 0) + "," + sc.breakTs + ","
                            + f(sc.entry) + "," + f(sc.stop) + "," + f(sc.target) + "," + f(Analysis.alertTarget(sc))
                            + "," + f(Analysis.alertTarget3(sc)) + "," + t.score + "," + (t.alertGrade ? 1 : 0)
                            + "," + t.outcome + "," + t.outcome2 + "," + t.outcome3);
                } else {
                    w.println(t.day + ",FADE," + (sc.fadeUp ? 1 : 0) + "," + sc.fadeTs + "," + f(sc.fadeEntry) + ","
                            + f(sc.fadeStop) + "," + f(sc.fadeTarget) + ",,,0,1," + t.outcome + ",,");
                }
            }
        }
        System.out.println(idx + " orb/fade signals: " + hist.size());
    }

    // ---- mom60: the live watcher, fed one closed bar at a time ----
    static void mom60(String dir, String idx, double thr, int step, boolean bse, String out) throws Exception {
        YahooClient.Chart m1 = load(dir + "/" + idx + "_1m.csv");
        Map<LocalDate, int[]> dd = days(m1);
        Mom60.Cfg cfg;
        if ("BANKNIFTY".equals(idx)) cfg = Mom60.BN;
        else if ("FINNIFTY".equals(idx)) cfg = Mom60.FIN;
        else if ("SENSEX".equals(idx)) cfg = Mom60.SENSEX;
        else cfg = new Mom60.Cfg(idx, yahooOf(idx), thr, step, 1, bse, "R110 (no IraThinker track for this index)");
        if (Math.abs(cfg.thresholdPct - thr) > 1e-9 || cfg.strikeStep != step || cfg.bseExpiry != bse) {
            throw new IllegalStateException("config differs from IraThinker's: " + idx);
        }
        int fires = 0;
        try (PrintWriter w = new PrintWriter(out)) {
            w.println("day,bar_ts,entry_ts,exit_ts,call,ret60,spot,strike,expiry,iv,entry_prem,stop_prem,reason,exit_bar_ts,exit_prem,pnl_pct");
            for (Map.Entry<LocalDate, int[]> e : dd.entrySet()) {
                LocalDate d = e.getKey();
                double iv = ivFor(dailyBefore(m1, dd, d));
                YahooClient.Chart dc = slice(m1, e.getValue()[0], e.getValue()[1]);
                Mom60.Watcher wt = new Mom60.Watcher(cfg, null, 0, 0);
                Mom60.Signal open = null;
                for (int k = 0; k < dc.ts.length; k++) {
                    for (Mom60.Event ev : wt.process(dc, d, dc.ts[k] + 60, true, iv)) {
                        if (ev.fired != null) { open = ev.fired; fires++; }
                        if (ev.resolved != null) { row(w, ev.resolved); open = null; }
                    }
                }
                if (open != null) {
                    Mom60.Resolution r = wt.forceClose(dc, d);
                    if (r != null) row(w, r);
                }
            }
        }
        System.out.println(idx + " mom60 fires: " + fires);
    }

    static void row(PrintWriter w, Mom60.Resolution r) {
        Mom60.Signal s = r.signal;
        w.println(s.date + "," + s.barTs + "," + s.entryTs + "," + s.exitTs + "," + (s.call ? 1 : 0) + "," + f(s.ret60)
                + "," + f(s.spot) + "," + f(s.strike) + "," + s.expiry + "," + f(s.iv) + "," + f(s.entryPremium)
                + "," + f(s.stopPremium) + "," + r.reason + "," + r.exitBarTs + "," + f(r.exitPremium) + "," + f(r.pnlPct));
    }

    // ---- BANKNIFTY strangle paper track ----
    static void strangle(String dir, String out) throws Exception {
        YahooClient.Chart m1 = load(dir + "/BANKNIFTY_1m.csv");
        Map<LocalDate, int[]> dd = days(m1);
        List<LocalDate> all = new ArrayList<>(dd.keySet());
        int n = 0;
        try (PrintWriter w = new PrintWriter(out)) {
            w.println("monday,spot,atm,ce_strike,pe_strike,expiry,exit_date,expiry_week,iv,ce_prem,pe_prem,credit,exit_spot,exit_cost,net_pct,pnl_rs_lot30");
            for (LocalDate d : all) {
                if (d.getDayOfWeek() != DayOfWeek.MONDAY) continue;
                int[] r = dd.get(d);
                Strangle.Entry e = Strangle.buildEntry(slice(m1, r[0], r[1]), d, ivFor(dailyBefore(m1, dd, d)));
                if (e == null) continue;
                // the tape from Monday through the exit day (or the last day before it)
                int to = r[1];
                for (LocalDate x : all) if (!x.isBefore(d) && !x.isAfter(e.exitDate)) to = dd.get(x)[1];
                double xs = Strangle.exitSpotFor(slice(m1, r[0], to), e);
                Strangle.Grade g = Strangle.grade(e, xs);
                w.println(d + "," + f(e.spot) + "," + f(e.atmStrike) + "," + f(e.ceStrike) + "," + f(e.peStrike) + ","
                        + e.expiry + "," + e.exitDate + "," + (e.expiryWeek ? 1 : 0) + "," + f(e.iv) + ","
                        + f(e.cePremium) + "," + f(e.pePremium) + "," + f(e.credit) + "," + f(xs) + ","
                        + (g == null ? "," : f(g.exitCost) + "," + f(g.netPct)) + "," + (g == null ? "" : f(g.pnlRs)));
                n++;
            }
        }
        System.out.println("strangle weeks: " + n);
    }

    // ---- prediction engine: the 60-minute vote at 10:30, on the trailing 60 days of 5-minute bars ----
    static void predict(String dir, String idx, String out) throws Exception {
        YahooClient.Chart m5 = load(dir + "/" + idx + "_5m.csv");
        Map<LocalDate, int[]> dd = days(m5);
        List<LocalDate> all = new ArrayList<>(dd.keySet());
        try (PrintWriter w = new PrintWriter(out)) {
            w.println("day,asof_ts,dir60,consensus60,exp_pts60,combos_live,holdout_acc,holdout_n");
            for (int k = 60; k < all.size(); k++) {
                LocalDate d = all.get(k);
                long asof = Strangle.atEpoch(d, 10, 30);
                int from = dd.get(all.get(k - 60))[0];
                int to = dd.get(d)[0];
                while (to < m5.ts.length && m5.ts[to] + 300 <= asof && day(m5.ts[to]).equals(d)) to++;
                if (to == dd.get(d)[0]) continue; // no bar today before 10:30
                if (m5.ts[to - 1] + 300 != asof) continue; // the 10:25 bar is missing
                Predict.Forecast pf = Predict.forecast(slice(m5, from, to));
                if (pf == null) continue;
                w.println(d + "," + asof + "," + pf.dir[2] + "," + f(pf.consensus[2]) + "," + f(pf.expPts[2]) + ","
                        + pf.combosLive + "," + f(pf.holdoutAcc) + "," + pf.holdoutN);
                w.flush();
            }
        }
        System.out.println(idx + " predict days done");
    }

    public static void main(String[] a) throws Exception {
        // Predict.HORIZONS = {3, 6, 12, -1} five-minute bars: index 2 is the 60-minute vote.
        switch (a[0]) {
            case "orb": orb(a[1], a[2], a[3]); break;
            case "mom60": mom60(a[1], a[2], Double.parseDouble(a[3]), Integer.parseInt(a[4]),
                    Boolean.parseBoolean(a[5]), a[6]); break;
            case "strangle": strangle(a[1], a[2]); break;
            case "predict": predict(a[1], a[2], a[3]); break;
            default: throw new IllegalArgumentException(a[0]);
        }
    }
}
