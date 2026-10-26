package com.irathinker.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToIntFunction;

/** The Live Ledger (v45): the app's own forward record. Every
 *  equity plan a scan produces - alert-grade or not - is written
 *  down with the situation around it (score, range width, VIX
 *  timing, market breadth, breakout volume) and its PRICE-ACTION
 *  fingerprint (entry-bar body strength, engulfing, the thrust
 *  into the entry, VWAP side, the day's swing structure). Each
 *  record is then judged against the real bars that followed.
 *  This is the memory the adaptive features grade against: no
 *  backfill, no hindsight - only what the app saw live, in order.
 *  Storage mirrors PaperStore: a small JSON file, app-private. */
public final class Ledger {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final int KEEP_DAYS = 120;
    private static final int MAX_RECORDS = 6000;

    public static final class Rec {
        public String yahoo, name, kind, date; // kind: ORB_UP / ORB_DOWN / FADE_UP / FADE_DN
        public long ts;
        public double entry, stop, target;
        public int score;          // IraScore at record time (ORB only; 0 on fades)
        public double widthPct;    // opening-range width, % of entry (ORB)
        public double gapPct;      // signed day-open gap, % (fades)
        public double rvol;        // break-bar volume multiple (ORB)
        public double breadth;     // % of the equity batch broken down in the same pass
        public int vixFirst;       // 1 VIX broke up before this entry, 0 it had not, -1 unknown
        public int hour;           // IST hour of the entry bar
        public boolean grade;      // would have passed the notification gates
        public boolean sent;       // actually sent to the user as an alert (v49)
        // price-action fingerprint (v45)
        public double paBody;      // entry-bar body strength in the trade direction, 0..1
        public boolean paEngulf;
        public double paThrust;    // signed 3-bar move into the entry, %, trade direction
        public int paVwap;         // entry vs the day's VWAP: +1 above / -1 below / 0 on it
        public int paStruct;       // day swing structure: +1 up / -1 down / 0 mixed
        // the run-up behind the plan (v46): the previous 30
        // one-minute candles before the entry, saved as closes,
        // plus their net move and choppiness - and the matching
        // 30-minute run-up measured on 5m bars (runMove), which is
        // what Time Machine similarity can compare across history.
        public double[] run = new double[0];
        public double runMove1 = Double.NaN;
        public double runVol1 = Double.NaN;
        public double runMove = Double.NaN;
        // resolution
        public int outcome;        // 0 open, +1 target, -1 stop, 2 flat (day ended neither)
        public int outcome3;       // v50: ORB records only - the 3R runner exit judged on the
                                   // same bars at resolution (+1/-1/2 same scale; 0 = not recorded,
                                   // e.g. resolved before v50). Grading never reads this field.
        public double movePct;     // direction-adjusted move entry -> that day's last close, %

        public boolean up() { return kind.endsWith("_UP"); }
        public boolean orb() { return kind.startsWith("ORB"); }
    }

    // ---------------- storage (PaperStore idiom) ----------------

    private static File file(Context ctx) {
        return new File(ctx.getFilesDir(), "live_ledger.json");
    }

    public static synchronized List<Rec> load(Context ctx) {
        List<Rec> out = new ArrayList<>();
        try {
            File f = file(ctx);
            if (!f.exists()) return out;
            byte[] buf = new byte[(int) f.length()];
            try (FileInputStream in = new FileInputStream(f)) {
                int read = 0;
                while (read < buf.length) {
                    int n = in.read(buf, read, buf.length - read);
                    if (n < 0) break;
                    read += n;
                }
            }
            JSONArray arr = new JSONArray(new String(buf, StandardCharsets.UTF_8));
            for (int i = 0; i < arr.length(); i++) out.add(fromJson(arr.getJSONObject(i)));
        } catch (Exception ignored) { }
        return out;
    }

    public static synchronized void save(Context ctx, List<Rec> recs) {
        try {
            JSONArray arr = new JSONArray();
            for (Rec r : recs) arr.put(toJson(r));
            try (FileOutputStream out = new FileOutputStream(file(ctx))) {
                out.write(arr.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) { }
    }

    /** org.json refuses NaN/Infinity - absent values are stored as
     *  JSON null and read back as NaN by fromJson's defaults. */
    private static void putNum(JSONObject o, String k, double v) throws Exception {
        o.put(k, Double.isNaN(v) || Double.isInfinite(v) ? JSONObject.NULL : Double.valueOf(v));
    }

    static JSONObject toJson(Rec r) throws Exception {
        JSONObject o = new JSONObject();
        o.put("y", r.yahoo); o.put("n", r.name); o.put("k", r.kind); o.put("d", r.date);
        o.put("ts", r.ts);
        putNum(o, "e", r.entry); putNum(o, "st", r.stop); putNum(o, "t", r.target);
        o.put("sc", r.score);
        putNum(o, "w", r.widthPct); putNum(o, "g", r.gapPct);
        putNum(o, "rv", r.rvol); putNum(o, "b", r.breadth);
        o.put("vf", r.vixFirst);
        o.put("h", r.hour); o.put("gr", r.grade ? 1 : 0); o.put("sn", r.sent ? 1 : 0);
        putNum(o, "pb", r.paBody); o.put("pe", r.paEngulf ? 1 : 0); putNum(o, "pt", r.paThrust);
        o.put("pv", r.paVwap); o.put("ps", r.paStruct);
        o.put("oc", r.outcome); o.put("oc3", r.outcome3); putNum(o, "mp", r.movePct);
        putNum(o, "rm", r.runMove); putNum(o, "rm1", r.runMove1); putNum(o, "rv1", r.runVol1);
        if (r.run != null && r.run.length > 0) {
            JSONArray a = new JSONArray();
            for (double v : r.run) a.put(v);
            o.put("run", a);
        }
        return o;
    }

    static Rec fromJson(JSONObject o) {
        Rec r = new Rec();
        r.yahoo = o.optString("y"); r.name = o.optString("n");
        r.kind = o.optString("k"); r.date = o.optString("d");
        r.ts = o.optLong("ts");
        r.entry = o.optDouble("e", Double.NaN); r.stop = o.optDouble("st", Double.NaN);
        r.target = o.optDouble("t", Double.NaN);
        r.score = o.optInt("sc");
        r.widthPct = o.optDouble("w", Double.NaN); r.gapPct = o.optDouble("g", Double.NaN);
        r.rvol = o.optDouble("rv", Double.NaN); r.breadth = o.optDouble("b", Double.NaN);
        r.vixFirst = o.optInt("vf", -1); r.hour = o.optInt("h");
        r.grade = o.optInt("gr") == 1; r.sent = o.optInt("sn") == 1;
        r.paBody = o.optDouble("pb", Double.NaN); r.paEngulf = o.optInt("pe") == 1;
        r.paThrust = o.optDouble("pt", Double.NaN);
        r.paVwap = o.optInt("pv"); r.paStruct = o.optInt("ps");
        r.outcome = o.optInt("oc"); r.outcome3 = o.optInt("oc3"); r.movePct = o.optDouble("mp", Double.NaN);
        r.runMove = o.optDouble("rm", Double.NaN);
        r.runMove1 = o.optDouble("rm1", Double.NaN); r.runVol1 = o.optDouble("rv1", Double.NaN);
        JSONArray a = o.optJSONArray("run");
        if (a != null) {
            r.run = new double[a.length()];
            for (int i = 0; i < a.length(); i++) r.run[i] = a.optDouble(i, Double.NaN);
        }
        return r;
    }

    // ---------------- recording ----------------

    /** Dedupe identity of a plan: symbol + kind + day + entry. */
    static String key(Rec r) {
        return r.yahoo + "|" + r.kind + "|" + r.date + "|" + Math.round(r.entry * 100);
    }

    /** Build a record from a live scan, or null when the scan is not
     *  a recordable plan of TODAY (equities only - the strategies
     *  this record grades are the equity ones). */
    static Rec fromScan(Analysis.Scan sc, int score, boolean grade, int vixFirst,
                        double breadth, LocalDate today) {
        if (sc == null || sc.sym == null) return null;
        if (!Universe.G_NIFTY.equals(sc.sym.group) && !Universe.G_BANK.equals(sc.sym.group)) return null;
        Rec r = new Rec();
        r.yahoo = sc.sym.yahoo; r.name = sc.sym.name;
        r.breadth = breadth; r.vixFirst = vixFirst; r.grade = grade;
        if (sc.hasPlan() && (sc.setup == Analysis.Setup.ORB_UP || sc.setup == Analysis.Setup.ORB_DOWN)) {
            r.kind = sc.setup == Analysis.Setup.ORB_UP ? "ORB_UP" : "ORB_DOWN";
            r.ts = sc.breakTs; r.entry = sc.entry; r.stop = sc.stop; r.target = sc.target;
            r.score = score; r.rvol = sc.rvol;
            r.widthPct = (sc.orHigh - sc.orLow) / sc.entry * 100;
            r.gapPct = Double.NaN;
        } else if (sc.hasFade()) {
            r.kind = sc.fadeUp ? "FADE_UP" : "FADE_DN";
            r.ts = sc.fadeTs; r.entry = sc.fadeEntry; r.stop = sc.fadeStop; r.target = sc.fadeTarget;
            r.score = 0; r.rvol = Double.NaN;
            r.widthPct = Double.NaN; r.gapPct = sc.fadeGap;
        } else {
            return null;
        }
        if (r.ts <= 0) return null;
        java.time.ZonedDateTime z = java.time.Instant.ofEpochSecond(r.ts).atZone(IST);
        r.date = z.toLocalDate().toString();
        if (!z.toLocalDate().equals(today)) return null; // never record a stale day's plan
        r.hour = z.getHour();
        r.paBody = sc.paBody; r.paEngulf = sc.paEngulf; r.paThrust = sc.paThrust;
        r.paVwap = sc.paVwap; r.paStruct = sc.paStruct;
        r.runMove = sc.runMove;
        r.outcome = 0; r.movePct = Double.NaN;
        return r;
    }

    /** The previous 30 one-minute candles before an entry, as
     *  closes, oldest first - same IST day, strictly before the
     *  entry bar. Fewer when the feed holds fewer; empty when the
     *  1-minute feed is unavailable. */
    static double[] last30Closes(YahooClient.Chart m1, long entryTs) {
        if (m1 == null || m1.ts.length == 0) return new double[0];
        java.util.List<Double> closes = new ArrayList<>();
        String day = Analysis.istDate(entryTs).toString();
        for (int i = 0; i < m1.ts.length; i++) {
            if (m1.ts[i] >= entryTs) break;
            if (!Analysis.istDate(m1.ts[i]).toString().equals(day)) continue;
            double c = m1.close[i];
            if (!Double.isNaN(c) && c > 0) closes.add(c);
        }
        int from = Math.max(0, closes.size() - 30);
        double[] out = new double[closes.size() - from];
        for (int i = from; i < closes.size(); i++) out[i - from] = closes.get(i);
        return out;
    }

    /** Net % move across a saved run of 1-minute closes. */
    static double runMove1Of(double[] closes) {
        if (closes == null || closes.length < 2 || closes[0] <= 0) return Double.NaN;
        return (closes[closes.length - 1] / closes[0] - 1) * 100;
    }

    /** Choppiness of a run of 1-minute closes: stdev of its log returns, %. */
    static double runVol1Of(double[] closes) {
        if (closes == null || closes.length < 3) return Double.NaN;
        double[] rets = new double[closes.length - 1];
        double mean = 0;
        for (int i = 1; i < closes.length; i++) {
            if (closes[i] <= 0 || closes[i - 1] <= 0) return Double.NaN;
            rets[i - 1] = Math.log(closes[i] / closes[i - 1]);
            mean += rets[i - 1];
        }
        mean /= rets.length;
        double var = 0;
        for (double r : rets) var += (r - mean) * (r - mean);
        return Math.sqrt(var / rets.length) * 100;
    }

    /** The feature tuple Time Machine similarity matches on, from a record. */
    static Analysis.Feat featOf(Rec r) {
        Analysis.Feat f = new Analysis.Feat();
        f.width = r.widthPct; f.score = r.score; f.body = r.paBody; f.thrust = r.paThrust;
        f.run = r.runMove; f.hour = r.hour + 0.5;
        f.struct = r.paStruct; f.vwap = r.paVwap; f.engulf = r.paEngulf ? 1 : 0;
        return f;
    }

    /** The join key between a record and the day's fired-alert
     *  keys: symbol + entry (paise) + date. */
    static String sentKeyOf(Rec r) {
        return r.yahoo + "|" + Math.round(r.entry * 100) + "|" + r.date;
    }

    /** Record every recordable plan in a scan batch. Returns how
     *  many NEW records were added. sentKeys (v49) marks the plans
     *  that were actually sent as alerts - including plans recorded
     *  on an earlier pass than the one that fired them. */
    public static synchronized int recordBatch(Context ctx, java.util.Collection<Analysis.Scan> scans,
                                               ToIntFunction<Analysis.Scan> scoreFn,
                                               Analysis.Setup vixSetup, long vixBreakTs,
                                               double breadth, int gateScore,
                                               Set<String> sentKeys) {
        if (scans == null || scans.isEmpty()) return 0;
        LocalDate today = LocalDate.now(IST);
        List<Rec> all = load(ctx);
        Set<String> keys = new HashSet<>();
        for (Rec r : all) keys.add(key(r));
        int upgraded = 0;
        if (sentKeys != null && !sentKeys.isEmpty()) {
            for (Rec r : all) {
                if (!r.sent && sentKeys.contains(sentKeyOf(r))) { r.sent = true; upgraded++; }
            }
        }
        Map<String, YahooClient.Chart> m1Cache = new HashMap<>();
        int added = 0;
        for (Analysis.Scan sc : scans) {
            if (sc == null || sc.sym == null) continue;
            boolean orb = sc.hasPlan()
                    && (sc.setup == Analysis.Setup.ORB_UP || sc.setup == Analysis.Setup.ORB_DOWN);
            boolean fade = sc.hasFade();
            if (!orb && !fade) continue;
            long entryTs = orb ? sc.breakTs : sc.fadeTs;
            int vixFirst = vixBreakTs <= 0 ? -1
                    : (vixSetup == Analysis.Setup.ORB_UP && vixBreakTs <= entryTs) ? 1 : 0;
            int score = orb ? scoreFn.applyAsInt(sc) : 0;
            boolean grade;
            if (fade) grade = true; // the fade's own tested form is its gate
            else if (sc.setup == Analysis.Setup.ORB_DOWN) {
                grade = Analysis.alertWorthy(sc, score, gateScore)
                        && Analysis.vixConfirms(vixSetup, vixBreakTs, sc);
            } else grade = false; // the live gate is breakdown-only
            Rec r = fromScan(sc, score, grade, vixFirst, breadth, today);
            if (r == null) continue;
            if (!keys.add(key(r))) continue; // already recorded
            r.sent = sentKeys != null && sentKeys.contains(sentKeyOf(r));
            // Save the previous 30 one-minute candles behind the
            // plan (the run-up it was born from) - fetched once per
            // symbol per batch; a failed fetch leaves the record
            // standing without them.
            try {
                YahooClient.Chart m1 = m1Cache.get(r.yahoo);
                if (m1 == null) {
                    m1 = YahooClient.fetch(r.yahoo, "1d", "1m");
                    m1Cache.put(r.yahoo, m1);
                }
                r.run = last30Closes(m1, r.ts);
                r.runMove1 = runMove1Of(r.run);
                r.runVol1 = runVol1Of(r.run);
            } catch (Exception ignored) { }
            all.add(r);
            added++;
        }
        if (added > 0 || upgraded > 0) save(ctx, prune(all, today));
        return added;
    }

    /** Drop resolved records older than KEEP_DAYS and cap the file:
     *  oldest resolved go first, then oldest overall. */
    static List<Rec> prune(List<Rec> recs, LocalDate today) {
        String cutoff = today.minusDays(KEEP_DAYS).toString();
        List<Rec> out = new ArrayList<>();
        for (Rec r : recs) {
            if (r.outcome != 0 && r.date.compareTo(cutoff) < 0) continue;
            out.add(r);
        }
        while (out.size() > MAX_RECORDS) {
            int idx = -1;
            for (int i = 0; i < out.size(); i++) if (out.get(i).outcome != 0) { idx = i; break; }
            if (idx < 0) idx = 0;
            out.remove(idx);
        }
        return out;
    }

    // ---------------- resolution ----------------

    private static volatile long lastResolveAt = 0;

    /** Judge every still-open record against the bars that followed
     *  it - the same walker the live tracker uses. A record whose
     *  day has ended with neither level hit closes FLAT, carrying
     *  the day's actual move. Throttled: passes inside 5 minutes
     *  are skipped. Blocking - call off the UI thread. */
    public static synchronized int resolveAll(Context ctx) {
        long nowMs = System.currentTimeMillis();
        if (nowMs - lastResolveAt < 5 * 60_000L) return 0;
        lastResolveAt = nowMs;
        List<Rec> all = load(ctx);
        LocalDate today = LocalDate.now(IST);
        Map<String, YahooClient.Chart> charts = new HashMap<>();
        int closed = 0;
        boolean dirty = false;
        for (Rec r : all) {
            if (r.outcome != 0) continue;
            YahooClient.Chart intra = charts.get(r.yahoo);
            if (intra == null) {
                try {
                    intra = YahooClient.fetch(r.yahoo, "5d", "5m");
                    charts.put(r.yahoo, intra);
                } catch (Exception e) {
                    continue; // try again on a later pass
                }
            }
            try {
                int o = Analysis.outcomeOf(intra, r.ts, r.stop, r.target, r.up());
                boolean past = r.date.compareTo(today.toString()) < 0;
                if (o != 0) {
                    r.outcome = o;
                } else if (past) {
                    r.outcome = 2;
                } else {
                    continue; // still live today
                }
                // v50: judge the 3R runner exit on the same bars, for the
                // head-to-head record. ORB records only; summaries and
                // grading read r.outcome alone and are untouched by this.
                if (r.orb() && r.entry > 0 && !Double.isNaN(r.entry) && !Double.isNaN(r.stop)) {
                    double risk = Math.abs(r.stop - r.entry);
                    if (risk > 0) {
                        double t3 = r.up() ? r.entry + 3 * risk : r.entry - 3 * risk;
                        int o3 = Analysis.outcomeOf(intra, r.ts, r.stop, t3, r.up());
                        r.outcome3 = o3 != 0 ? o3 : 2;
                    }
                }
                double lc = lastCloseOfDay(intra, r.date);
                if (!Double.isNaN(lc) && r.entry > 0) {
                    r.movePct = (lc / r.entry - 1) * 100 * (r.up() ? 1 : -1);
                }
                closed++;
                dirty = true;
            } catch (Exception ignored) { }
        }
        if (dirty) save(ctx, prune(all, today));
        return closed;
    }

    private static double lastCloseOfDay(YahooClient.Chart intra, String date) {
        double c = Double.NaN;
        for (int i = 0; i < intra.ts.length; i++) {
            if (Analysis.istDate(intra.ts[i]).toString().equals(date)
                    && !Double.isNaN(intra.close[i])) c = intra.close[i];
        }
        return c;
    }

    // ---------------- summary (the learning, made visible) ----------------

    public static final class Stats {
        public int total, open;
        public String since = "";
        /** bucket -> {resolved, targets, stops, flats} */
        public final Map<String, int[]> buckets = new HashMap<>();
    }

    private static void bump(Map<String, int[]> m, String k, Rec r) {
        int[] a = m.computeIfAbsent(k, x -> new int[4]);
        a[0]++;
        if (r.outcome > 0) a[1]++; else if (r.outcome < 0) a[2]++; else a[3]++;
    }

    public static Stats summarize(List<Rec> recs) {
        Stats st = new Stats();
        st.total = recs.size();
        for (Rec r : recs) {
            if (st.since.isEmpty() || r.date.compareTo(st.since) < 0) st.since = r.date;
            if (r.outcome == 0) { st.open++; continue; }
            bump(st.buckets, "ALL", r);
            bump(st.buckets, r.kind, r);
            if (!r.orb()) bump(st.buckets, "FADE", r);
            bump(st.buckets, r.grade ? "GRADE" : "NOGRADE", r);
            if (r.orb()) {
                if ("ORB_DOWN".equals(r.kind)) {
                    if (r.vixFirst == 1) bump(st.buckets, "VIXFIRST", r);
                    else if (r.vixFirst == 0) bump(st.buckets, "NOVIX", r);
                }
                if (!Double.isNaN(r.widthPct)) {
                    bump(st.buckets, r.widthPct <= 0.8 ? "NARROW" : "WIDE", r);
                }
                bump(st.buckets, r.score > Analysis.ALERT_MIN_SCORE ? "HISCORE" : "LOSCORE", r);
                if (!Double.isNaN(r.paBody)) {
                    if (r.paBody >= 0.6) bump(st.buckets, "PA_STRONG", r);
                    else if (r.paBody < 0.35) bump(st.buckets, "PA_WEAK", r);
                }
                if (r.paEngulf) bump(st.buckets, "PA_ENGULF", r);
                if (r.paStruct != 0) {
                    boolean with = (r.paStruct > 0) == r.up();
                    bump(st.buckets, with ? "PA_WITH" : "PA_AGAINST", r);
                }
                if (r.paVwap != 0) {
                    boolean with = (r.paVwap > 0) == r.up();
                    bump(st.buckets, with ? "PA_VWAPWITH" : "PA_VWAPAGAINST", r);
                }
                if (!Double.isNaN(r.runVol1)) {
                    if (r.runVol1 <= 0.10) bump(st.buckets, "RUN_CALM", r);
                    else if (r.runVol1 >= 0.20) bump(st.buckets, "RUN_WILD", r);
                }
            }
        }
        return st;
    }

    // ---------------- live verdicts (v59) ----------------

    /** One alert family's forward verdict: how many of its plans
     *  the ledger has judged, how many won, and the average result
     *  in R (the record's own entry/stop geometry - a win pays the
     *  exit's multiple, a stop costs 1R, a flat day contributes
     *  the move it actually made, in R). Display-only evidence:
     *  nothing anywhere gates on this. */
    public static final class Verdict {
        public String family = "";
        public String label = "";
        public int n, wins;
        public double avgR;
    }

    /** A judged record's result in R under one exit reading:
     *  outcome is the exit's own verdict in the ledger's encoding
     *  (+1 target / -1 stop / 2 flat - tested by equality: a flat
     *  is NOT a small win), winMult the R a target pays on that
     *  exit. NaN when the record's geometry cannot express an R. */
    private static double rOf(Rec r, int outcome, double winMult) {
        if (r.entry <= 0 || Double.isNaN(r.entry) || Double.isNaN(r.stop)) return Double.NaN;
        double risk = Math.abs(r.entry - r.stop);
        if (risk <= 0) return Double.NaN;
        if (outcome == 1) return winMult;
        if (outcome == -1) return -1.0;
        // flat: the day ended neither way - its result is the
        // move it actually made, expressed in R.
        if (Double.isNaN(r.movePct)) return 0.0;
        return r.movePct / (risk / r.entry * 100.0);
    }

    /** The three families the Signal Test verdicts card reads:
     *  the ORB gate's alert-grade plans under their two live
     *  exits (the plan target the primary outcome judges, and
     *  the 3R runner outcome3 judges on the same bars - the same
     *  trades both ways, so the exits compare head-to-head), and
     *  the gap fade (whose own tested form is its gate, so every
     *  judged fade record counts). Pre-v50 ORB records carry no
     *  outcome3 and simply do not enter the 3R family. */
    public static List<Verdict> verdicts(List<Rec> recs) {
        Verdict orb = new Verdict();
        orb.family = "ORB_PLAN";
        orb.label = "ORB gate — plan target";
        Verdict orb3 = new Verdict();
        orb3.family = "ORB_3R";
        orb3.label = "ORB gate — 3R runner";
        Verdict fade = new Verdict();
        fade.family = "FADE";
        fade.label = "Gap fade";
        double sumOrb = 0, sum3 = 0, sumFade = 0;
        for (Rec r : recs) {
            if (r.orb()) {
                if (!r.grade || r.outcome == 0) continue;
                double risk = Math.abs(r.entry - r.stop);
                double mult = risk > 0 && !Double.isNaN(r.target)
                        ? Math.abs(r.target - r.entry) / risk : Double.NaN;
                if (!Double.isNaN(mult)) {
                    double v = rOf(r, r.outcome, mult);
                    if (!Double.isNaN(v)) {
                        orb.n++;
                        if (r.outcome == 1) orb.wins++;
                        sumOrb += v;
                    }
                }
                if (r.outcome3 != 0) {
                    double v3 = rOf(r, r.outcome3, 3.0);
                    if (!Double.isNaN(v3)) {
                        orb3.n++;
                        if (r.outcome3 == 1) orb3.wins++;
                        sum3 += v3;
                    }
                }
            } else if (r.outcome != 0) {
                double risk = Math.abs(r.entry - r.stop);
                double mult = risk > 0 && !Double.isNaN(r.target)
                        ? Math.abs(r.target - r.entry) / risk : Double.NaN;
                if (Double.isNaN(mult)) continue;
                double v = rOf(r, r.outcome, mult);
                if (Double.isNaN(v)) continue;
                fade.n++;
                if (r.outcome == 1) fade.wins++;
                sumFade += v;
            }
        }
        orb.avgR = orb.n == 0 ? 0 : sumOrb / orb.n;
        orb3.avgR = orb3.n == 0 ? 0 : sum3 / orb3.n;
        fade.avgR = fade.n == 0 ? 0 : sumFade / fade.n;
        List<Verdict> out = new ArrayList<>();
        out.add(orb);
        out.add(orb3);
        out.add(fade);
        return out;
    }

    /** The verdict card's one plain line per family: collecting
     *  until the Douglas 20-judged sample exists, then a verdict
     *  in words. An untouched family says where its data comes
     *  from instead of dressing up an empty record. */
    public static String verdictLine(Verdict v) {
        if (v == null || v.n == 0) return "No judged plans yet — fills from live sessions.";
        if (v.n < 20) return "Collecting — " + v.n + " of 20 judged.";
        if (v.avgR >= 0.05) {
            return String.format(java.util.Locale.US,
                    "Verdict: ahead so far — %+.2fR a plan over %d judged.", v.avgR, v.n);
        }
        if (v.avgR <= -0.05) {
            return String.format(java.util.Locale.US,
                    "Verdict: behind so far — %+.2fR a plan over %d judged.", v.avgR, v.n);
        }
        return String.format(java.util.Locale.US,
                "Verdict: too close to call — %+.2fR a plan over %d judged.", v.avgR, v.n);
    }

    // ---------------- self-grading (v47) ----------------

    /** What the live record has learned so far: an overall decided
     *  win rate (the baseline) and each context's own rate, kept
     *  only when the sample can speak (baseline needs 20 judged
     *  plans, a context needs 10 decided). */
    public static final class Grade {
        public double baseline = -1; // -1 = not enough live evidence yet
        public int baselineN;
        /** bucket -> {decided, winRate} */
        public final Map<String, double[]> ctx = new HashMap<>();
        public String bestKey = "";
        public double bestWin;
        public int bestN;
    }

    public static final class Adj {
        public int points;
        public int matched;
        public String note = "";
    }

    public static Grade gradeOf(List<Rec> recs) {
        Grade g = new Grade();
        Stats st = summarize(recs);
        int[] all = st.buckets.get("ALL");
        if (all == null || all[1] + all[2] < 20) return g;
        g.baseline = all[1] * 1.0 / (all[1] + all[2]);
        g.baselineN = all[1] + all[2];
        for (Map.Entry<String, int[]> e : st.buckets.entrySet()) {
            String k = e.getKey();
            if ("ALL".equals(k) || k.startsWith("ORB_") || k.startsWith("FADE")) continue;
            int[] a = e.getValue();
            int decided = a[1] + a[2];
            if (decided < 10) continue;
            double win = a[1] * 1.0 / decided;
            g.ctx.put(k, new double[]{decided, win});
            if (win > g.bestWin) { g.bestWin = win; g.bestKey = k; g.bestN = decided; }
        }
        return g;
    }

    static String ctxLabel(String k) {
        switch (k) {
            case "NARROW": return "narrow ranges";
            case "WIDE": return "wide ranges";
            case "PA_STRONG": return "strong break bars";
            case "PA_WEAK": return "weak pokes";
            case "PA_WITH": return "breaks with the day's structure";
            case "PA_AGAINST": return "breaks against the structure";
            case "PA_VWAPWITH": return "breaks on their VWAP side";
            case "PA_VWAPAGAINST": return "breaks fighting VWAP";
            case "HISCORE": return "high scores";
            case "LOSCORE": return "low scores";
            case "VIXFIRST": return "VIX-first breakdowns";
            case "NOVIX": return "breakdowns without VIX first";
            case "RUN_CALM": return "calm 1-minute run-ups";
            case "RUN_WILD": return "wild 1-minute run-ups";
            case "GRADE": return "alert-grade plans";
            case "NOGRADE": return "non-grade plans";
            default: return k;
        }
    }

    /** The live adjustment for one plan: each situation it sits in
     *  contributes its live win-rate edge over the baseline, shrunk
     *  toward zero while its sample is thin; the average becomes
     *  score points, capped at +/-12. Display-only in v47 - the
     *  alert gate itself is untouched. */
    public static Adj adjustFor(Grade g, Analysis.Scan sc, int score) {
        Adj adj = new Adj();
        if (g == null || g.baseline < 0 || sc == null || !sc.hasPlan()) return adj;
        boolean up = sc.setup == Analysis.Setup.ORB_UP;
        java.util.List<String> keys = new ArrayList<>();
        if (sc.entry > 0) {
            double width = (sc.orHigh - sc.orLow) / sc.entry * 100;
            keys.add(width <= 0.8 ? "NARROW" : "WIDE");
        }
        if (!Double.isNaN(sc.paBody)) {
            if (sc.paBody >= 0.6) keys.add("PA_STRONG");
            else if (sc.paBody < 0.35) keys.add("PA_WEAK");
        }
        if (sc.paStruct != 0) keys.add((sc.paStruct > 0) == up ? "PA_WITH" : "PA_AGAINST");
        if (sc.paVwap != 0) keys.add((sc.paVwap > 0) == up ? "PA_VWAPWITH" : "PA_VWAPAGAINST");
        keys.add(score > Analysis.ALERT_MIN_SCORE ? "HISCORE" : "LOSCORE");
        double sum = 0, bestAbs = 0;
        int used = 0;
        for (String k : keys) {
            double[] c = g.ctx.get(k);
            if (c == null) continue;
            double n = c[0];
            double a = (c[1] - g.baseline) * (n / (n + 20));
            sum += a;
            used++;
            if (Math.abs(a) > bestAbs) {
                bestAbs = Math.abs(a);
                adj.note = ctxLabel(k) + " winning " + Math.round(c[1] * 100) + "% live (n=" + (int) n + ")";
            }
        }
        if (used == 0) { adj.note = ""; return adj; }
        adj.points = (int) Math.max(-12, Math.min(12, Math.round(sum / used * 40)));
        adj.matched = used;
        return adj;
    }

    // ---------------- post-mortems (v49) ----------------

    /** What actually happened to one judged plan after its entry:
     *  how far it ran for/against (in R), how long the outcome
     *  took, whether the market moved with it, and a plain class
     *  for the story. This is the "why did we fail" evidence. */
    public static final class Diag {
        public Rec rec;
        public String klass = "CHART_GONE";
        public double mfeR = Double.NaN, maeR = Double.NaN; // best / worst excursion, in R
        public int mins = -1;                                // minutes entry -> outcome (or day end)
        public double mkt = Double.NaN;                      // NIFTY move during the trade, % signed WITH the trade
    }

    public static Diag diagnose(YahooClient.Chart intra, Rec r, YahooClient.Chart nifty) {
        Diag d = new Diag();
        d.rec = r;
        double risk = Math.abs(r.entry - r.stop);
        if (intra == null || risk <= 0 || r.outcome == 0) return d;
        int ei = -1;
        for (int i = 0; i < intra.ts.length; i++) if (intra.ts[i] == r.ts) { ei = i; break; }
        if (ei < 0) return d;
        boolean up = r.up();
        double mfe = 0, mae = 0;
        int oi = -1, lastIdx = ei;
        for (int j = ei + 1; j < intra.ts.length; j++) {
            if (!Analysis.istDate(intra.ts[j]).toString().equals(r.date)) break;
            lastIdx = j;
            double hi = intra.high[j], lo = intra.low[j];
            if (!Double.isNaN(hi) && !Double.isNaN(lo)) {
                double fav = up ? hi - r.entry : r.entry - lo;
                double adv = up ? r.entry - lo : hi - r.entry;
                if (fav > mfe) mfe = fav;
                if (adv > mae) mae = adv;
            }
            if (oi < 0) {
                boolean hitT = up ? (!Double.isNaN(hi) && hi >= r.target) : (!Double.isNaN(lo) && lo <= r.target);
                boolean hitS = up ? (!Double.isNaN(lo) && lo <= r.stop) : (!Double.isNaN(hi) && hi >= r.stop);
                if ((r.outcome > 0 && hitT) || (r.outcome < 0 && hitS)) oi = j;
            }
        }
        int endIdx = r.outcome == 2 ? lastIdx : (oi >= 0 ? oi : lastIdx);
        d.mfeR = mfe / risk;
        d.maeR = mae / risk;
        d.mins = (int) ((intra.ts[endIdx] - r.ts) / 60);
        if (nifty != null) {
            double c0 = closeAtOrBefore(nifty, r.ts);
            double c1 = closeAtOrBefore(nifty, intra.ts[endIdx]);
            if (!Double.isNaN(c0) && !Double.isNaN(c1) && c0 > 0) {
                d.mkt = (c1 / c0 - 1) * 100 * (up ? 1 : -1);
            }
        }
        switch (r.outcome) {
            case 1:
                d.klass = d.maeR < 0.3 ? "CLEAN_TARGET" : d.maeR < 0.7 ? "SCARE_TARGET" : "DEEP_TARGET";
                break;
            case -1:
                d.klass = d.mfeR >= 0.7 ? "TEASED_STOP" : d.mfeR >= 0.35 ? "SOME_LIFE_STOP" : "DEAD_STOP";
                break;
            default:
                d.klass = d.mfeR >= 0.7 ? "FLAT_RAN" : d.mfeR >= 0.35 ? "FLAT_DRIFT" : "FLAT_DEAD";
        }
        return d;
    }

    private static double closeAtOrBefore(YahooClient.Chart ch, long ts) {
        double c = Double.NaN;
        for (int i = 0; i < ch.ts.length; i++) {
            if (ch.ts[i] > ts) break;
            if (!Double.isNaN(ch.close[i])) c = ch.close[i];
        }
        return c;
    }

    /** The one-line story of a post-mortem class. */
    public static String classLine(String klass) {
        switch (klass) {
            case "CLEAN_TARGET": return "Clean run - straight to the target";
            case "SCARE_TARGET": return "Target, after a pullback against it first";
            case "DEEP_TARGET": return "Target, but only after a deep scare";
            case "TEASED_STOP": return "Teased: ran toward the target, then reversed into the stop";
            case "SOME_LIFE_STOP": return "Moved a little, then failed into the stop";
            case "DEAD_STOP": return "Dead break: never moved toward the target, then stopped";
            case "FLAT_RAN": return "Ran well but the target never came - flat by close";
            case "FLAT_DRIFT": return "Drifted without reaching either level - flat";
            case "FLAT_DEAD": return "Nothing happened - flat";
            default: return "Outcome only (the day's chart has left the feed)";
        }
    }

    public static Map<String, Integer> classCounts(List<Diag> diags) {
        Map<String, Integer> out = new HashMap<>();
        for (Diag d : diags) out.merge(d.klass, 1, Integer::sum);
        return out;
    }
}
