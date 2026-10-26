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
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** The Prediction Engine's live track (v47). Every prediction the
 *  app actually makes - on a Detail page, or inside an alert as it
 *  fires - is written down with the direction each combo FAMILY
 *  voted (60-minute horizon), then judged against the real bars
 *  that followed. Families that keep being wrong live have their
 *  vote softened (x0.5); families that keep working live get a
 *  small boost (x1.25). Backtest grades never change - this is a
 *  forward, on-device record only, stored like the Live Ledger.
 *  One prediction per symbol per day: the first one stands. */
public final class PredictLive {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final int HORIZON_BARS = 12; // 60 minutes of 5-minute bars

    public static final class Pending {
        public String yahoo, date;
        public long ts;
        public double px;
        public int cons; // consensus direction when recorded (0 = no consensus)
        public final Map<String, Integer> fams = new HashMap<>(); // family -> direction
    }

    public static final class Store {
        /** family name -> {judged, correct} */
        public final Map<String, int[]> fams = new HashMap<>();
        /** {judged, correct} for the consensus itself */
        public final int[] cons = new int[2];
        public final List<Pending> pend = new ArrayList<>();
    }

    // ---------------- pure helpers ----------------

    /** Was a call right? A flat forward move judges nobody correct. */
    public static boolean correct(int dir, double from, double to) {
        double m = to - from;
        return m != 0 && (m > 0) == (dir > 0);
    }

    /** The live vote weight for a family: untouched until 30 judged
     *  calls, halved when it keeps losing live, boosted a little
     *  when it keeps winning live. */
    public static double familyWeight(int n, int correct) {
        if (n < 30) return 1.0;
        double acc = correct * 1.0 / n;
        if (acc < 0.45) return 0.5;
        if (acc >= 0.58) return 1.25;
        return 1.0;
    }

    /** Weights for every family, from the live aggregates. */
    public static Map<String, Double> weightsOf(Map<String, int[]> fams) {
        Map<String, Double> out = new HashMap<>();
        for (String name : Predict.FAMILY) {
            int[] a = fams.get(name);
            out.put(name, a == null ? 1.0 : familyWeight(a[0], a[1]));
        }
        return out;
    }

    /** The close HORIZON_BARS after ts on the same day - or the
     *  day's last close when the day ends sooner. NaN when fewer
     *  than 3 bars follow the recorded bar on that day. */
    public static double forwardClose(YahooClient.Chart ch, long ts) {
        if (ch == null) return Double.NaN;
        int idx = -1;
        for (int i = 0; i < ch.ts.length; i++) if (ch.ts[i] == ts) { idx = i; break; }
        if (idx < 0) return Double.NaN;
        String day = Analysis.istDate(ts).toString();
        int target = idx;
        for (int j = idx + 1; j < ch.ts.length; j++) {
            if (!Analysis.istDate(ch.ts[j]).toString().equals(day)) break;
            target = j;
            if (j - idx >= HORIZON_BARS) break;
        }
        if (target - idx < 3) return Double.NaN;
        for (int j = target; j > idx; j--) {
            if (!Double.isNaN(ch.close[j]) && ch.close[j] > 0) return ch.close[j];
        }
        return Double.NaN;
    }

    // ---------------- storage (Ledger idiom) ----------------

    private static File file(Context ctx) {
        return new File(ctx.getFilesDir(), "predict_live.json");
    }

    public static synchronized Store load(Context ctx) {
        Store st = new Store();
        try {
            File f = file(ctx);
            if (!f.exists()) return st;
            byte[] buf = new byte[(int) f.length()];
            try (FileInputStream in = new FileInputStream(f)) {
                int read = 0;
                while (read < buf.length) {
                    int n = in.read(buf, read, buf.length - read);
                    if (n < 0) break;
                    read += n;
                }
            }
            JSONObject o = new JSONObject(new String(buf, StandardCharsets.UTF_8));
            JSONObject fj = o.optJSONObject("fams");
            if (fj != null) {
                Iterator<String> it = fj.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    JSONArray a = fj.optJSONArray(k);
                    if (a != null && a.length() >= 2) st.fams.put(k, new int[]{a.optInt(0), a.optInt(1)});
                }
            }
            JSONArray cj = o.optJSONArray("cons");
            if (cj != null && cj.length() >= 2) { st.cons[0] = cj.optInt(0); st.cons[1] = cj.optInt(1); }
            JSONArray pj = o.optJSONArray("pend");
            if (pj != null) {
                for (int i = 0; i < pj.length(); i++) {
                    JSONObject p = pj.getJSONObject(i);
                    Pending q = new Pending();
                    q.yahoo = p.optString("y"); q.date = p.optString("d");
                    q.ts = p.optLong("ts"); q.px = p.optDouble("px", Double.NaN);
                    q.cons = p.optInt("c");
                    JSONObject fm = p.optJSONObject("f");
                    if (fm != null) {
                        Iterator<String> it = fm.keys();
                        while (it.hasNext()) {
                            String k = it.next();
                            q.fams.put(k, fm.optInt(k));
                        }
                    }
                    st.pend.add(q);
                }
            }
        } catch (Exception ignored) { }
        return st;
    }

    public static synchronized void save(Context ctx, Store st) {
        try {
            JSONObject o = new JSONObject();
            JSONObject fj = new JSONObject();
            for (Map.Entry<String, int[]> e : st.fams.entrySet()) {
                fj.put(e.getKey(), new JSONArray().put(e.getValue()[0]).put(e.getValue()[1]));
            }
            o.put("fams", fj);
            o.put("cons", new JSONArray().put(st.cons[0]).put(st.cons[1]));
            JSONArray pj = new JSONArray();
            for (Pending p : st.pend) {
                JSONObject q = new JSONObject();
                q.put("y", p.yahoo); q.put("d", p.date); q.put("ts", p.ts);
                q.put("px", p.px); q.put("c", p.cons);
                JSONObject fm = new JSONObject();
                for (Map.Entry<String, Integer> e : p.fams.entrySet()) fm.put(e.getKey(), (int) e.getValue());
                q.put("f", fm);
                pj.put(q);
            }
            o.put("pend", pj);
            try (FileOutputStream out = new FileOutputStream(file(ctx))) {
                out.write(o.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) { }
    }

    // ---------------- record + resolve ----------------

    /** Write down a prediction made now - one per symbol per day,
     *  the first stands. famDirs is indexed by Predict.FAMILY. */
    public static synchronized void record(Context ctx, String yahoo, long ts, double px,
                                           int cons, int[] famDirs) {
        if (yahoo == null || ts <= 0 || Double.isNaN(px) || px <= 0) return;
        Store st = load(ctx);
        String date = Analysis.istDate(ts).toString();
        for (Pending p : st.pend) {
            if (p.yahoo.equals(yahoo) && p.date.equals(date)) return; // already on record
        }
        Pending p = new Pending();
        p.yahoo = yahoo; p.date = date; p.ts = ts; p.px = px; p.cons = cons;
        if (famDirs != null) {
            for (int i = 0; i < famDirs.length && i < Predict.FAMILY.length; i++) {
                if (famDirs[i] != 0) p.fams.put(Predict.FAMILY[i], famDirs[i]);
            }
        }
        if (p.cons == 0 && p.fams.isEmpty()) return; // nothing was predicted
        st.pend.add(p);
        save(ctx, st);
    }

    /** Judge this symbol's pending predictions against a fresh
     *  chart. Returns how many were judged. */
    public static synchronized int resolve(Context ctx, String yahoo, YahooClient.Chart ch) {
        Store st = load(ctx);
        String staleBefore = LocalDate.now(IST).minusDays(70).toString();
        boolean dirty = false;
        int judged = 0;
        Iterator<Pending> it = st.pend.iterator();
        while (it.hasNext()) {
            Pending p = it.next();
            if (!p.yahoo.equals(yahoo)) continue;
            double fwd = forwardClose(ch, p.ts);
            if (Double.isNaN(fwd)) {
                if (p.date.compareTo(staleBefore) < 0) { it.remove(); dirty = true; }
                continue;
            }
            if (p.cons != 0) {
                st.cons[0]++;
                if (correct(p.cons, p.px, fwd)) st.cons[1]++;
            }
            for (Map.Entry<String, Integer> e : p.fams.entrySet()) {
                int[] a = st.fams.computeIfAbsent(e.getKey(), k -> new int[2]);
                a[0]++;
                if (correct(e.getValue(), p.px, fwd)) a[1]++;
            }
            it.remove();
            judged++;
            dirty = true;
        }
        if (dirty) save(ctx, st);
        return judged;
    }

    /** The live vote weights to hand the engine. */
    public static Map<String, Double> weights(Context ctx) {
        return weightsOf(load(ctx).fams);
    }

    /** One honest line for the Detail card - empty until the track
     *  has judged its first call. */
    public static String summaryLine(Context ctx) {
        Store st = load(ctx);
        if (st.cons[0] == 0) return "";
        StringBuilder sb = new StringBuilder(String.format(
                "Live track (forward only, this phone): the consensus has been right %.0f%% of %d judged calls.",
                st.cons[1] * 100.0 / st.cons[0], st.cons[0]));
        String bestK = null, worstK = null;
        double bestA = -1, worstA = 2;
        int bestN = 0, worstN = 0;
        for (Map.Entry<String, int[]> e : st.fams.entrySet()) {
            int[] a = e.getValue();
            if (a[0] < 10) continue;
            double acc = a[1] * 1.0 / a[0];
            if (acc > bestA) { bestA = acc; bestK = e.getKey(); bestN = a[0]; }
            if (acc < worstA) { worstA = acc; worstK = e.getKey(); worstN = a[0]; }
        }
        if (bestK != null) {
            sb.append(String.format(" Best family live: %s, %.0f%% (n=%d).", bestK, bestA * 100, bestN));
        }
        if (worstK != null && !worstK.equals(bestK)) {
            sb.append(String.format(" Weakest: %s, %.0f%% (n=%d)%s.", worstK, worstA * 100, worstN,
                    familyWeight(worstN, (int) Math.round(worstA * worstN)) < 1.0
                            ? " - its vote is softened" : ""));
        }
        if (!st.pend.isEmpty()) sb.append(String.format(" %d call(s) still being judged.", st.pend.size()));
        return sb.toString();
    }
}
