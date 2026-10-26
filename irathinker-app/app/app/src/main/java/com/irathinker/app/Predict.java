package com.irathinker.app;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Prediction Engine: 1,000+ combinations of classic candle rules -
 *  momentum, momentum-fade, mean reversion, trend state, RSI zones,
 *  channel breakouts, pattern analogs, opening-range persistence -
 *  crossed with lookbacks, thresholds and forecast horizons. Every
 *  combination is first graded on the older part of the chart (how
 *  often its calls were right, and how many points they made); only
 *  combinations that beat a coin flip earn a vote. The survivors then
 *  vote on the CURRENT candle state, weighted by their proven edge,
 *  producing a consensus direction and an expected move in points.
 *  The ensemble itself is graded on the newest days it never trained
 *  on (holdout), and that hit-rate is printed next to the prediction -
 *  the engine shows its own record, not just its opinion. */
public final class Predict {

    private Predict() { }

    private static final java.time.ZoneId IST = java.time.ZoneId.of("Asia/Kolkata");

    // families
    private static final int F_MOM = 0, F_MOMFADE = 1, F_MR = 2, F_MRFOLLOW = 3,
            F_TREND = 4, F_RSI = 5, F_CHAN = 6, F_ANALOG = 7, F_ORB = 8, F_ORBFADE = 9;

    public static final String[] FAMILY = {"Momentum", "Momentum fade", "Mean reversion",
            "Mean-reversion follow", "Trend (MA)", "RSI zones", "Channel breakout",
            "Pattern analogs", "ORB persistence", "ORB fade"};

    private static final int[] HORIZONS = {3, 6, 12, -1}; // bars; -1 = to day close
    public static final String[] HORIZON_LABEL = {"~15 MIN", "~30 MIN", "~60 MIN", "BY CLOSE"};

    static final class Combo {
        int fam, a, b, horizon;
        int wi = -1, wi2 = -1; // indicator window indices, resolved once (v48)
        boolean flag;
        int nUp, hitUp, nDn, hitDn;
        double sumUp, sumDn; // raw forward points when firing up / down
        double wUp, wDn;

        void grade() {
            wUp = nUp >= 8 ? Math.max(0, hitUp * 1.0 / nUp - 0.5) * Math.sqrt(nUp) : 0;
            wDn = nDn >= 8 ? Math.max(0, hitDn * 1.0 / nDn - 0.5) * Math.sqrt(nDn) : 0;
        }
        boolean live() { return wUp > 0 || wDn > 0; }
    }

    public static final class Forecast {
        public int combosTotal, combosLive, firingNow;
        public int[] dir = new int[HORIZONS.length];
        public double[] consensus = new double[HORIZONS.length];
        public double[] expPts = new double[HORIZONS.length];
        public int[] firing = new int[HORIZONS.length];
        public double holdoutAcc;   // ensemble accuracy on unseen newest days
        public int holdoutN;
        public double lastClose;
        /** Per-family net vote sign at the latest bar, 60-minute
         *  horizon (v47) - what the live track records and judges. */
        public int[] famDir60 = new int[10];
    }

    // ---- chart context with precomputed indicators ----
    private static final class Ctx {
        double[] c, vol;
        long[] ts;
        int[] day;          // day ordinal per bar
        int[] dayStart;     // first bar index of each bar's day (v48)
        double[] diff;      // close-to-close change per bar (v48)
        int n;
        double[][] ma;      // by window index into MAW
        double[][] rsi;     // by period index into RSIP
        double[][] chanHi, chanLo;
        double[] avgMove;   // mean |close step| over last 14 bars (ATR proxy)
        double[] volMa;
        int[] dayLast;      // last bar index of each bar's day
    }

    private static final int[] MAW = {6, 12, 24, 36, 48, 72, 96};
    private static final int[] RSIP = {7, 10, 14, 21};
    private static final int[] CHW = {8, 12, 18, 24, 36, 48, 72, 96};

    private static int idxOf(int[] arr, int v) {
        for (int i = 0; i < arr.length; i++) if (arr[i] == v) return i;
        return -1;
    }

    private static Ctx build(YahooClient.Chart ch) {
        Ctx x = new Ctx();
        List<Integer> keep = new ArrayList<>();
        for (int i = 0; i < ch.ts.length; i++) if (!Double.isNaN(ch.close[i])) keep.add(i);
        x.n = keep.size();
        x.c = new double[x.n]; x.vol = new double[x.n]; x.ts = new long[x.n];
        x.day = new int[x.n]; x.dayLast = new int[x.n];
        java.util.Map<LocalDate, Integer> ord = new java.util.HashMap<>();
        for (int j = 0; j < x.n; j++) {
            int i = keep.get(j);
            x.c[j] = ch.close[i];
            x.vol[j] = i < ch.volume.length ? ch.volume[i] : 0;
            x.ts[j] = ch.ts[i];
            LocalDate d = Instant.ofEpochSecond(ch.ts[i]).atZone(IST).toLocalDate();
            Integer o = ord.get(d);
            if (o == null) { o = ord.size(); ord.put(d, o); }
            x.day[j] = o;
        }
        for (int j = 0; j < x.n; j++) {
            int k = j;
            while (k + 1 < x.n && x.day[k + 1] == x.day[j]) k++;
            x.dayLast[j] = k;
        }
        x.dayStart = new int[x.n];
        x.diff = new double[x.n];
        int start = 0;
        for (int j = 0; j < x.n; j++) {
            if (j > 0 && x.day[j] != x.day[j - 1]) start = j;
            x.dayStart[j] = start;
            x.diff[j] = j > 0 ? x.c[j] - x.c[j - 1] : 0;
        }
        x.ma = new double[MAW.length][];
        for (int w = 0; w < MAW.length; w++) x.ma[w] = sma(x.c, MAW[w]);
        x.rsi = new double[RSIP.length][];
        for (int p = 0; p < RSIP.length; p++) x.rsi[p] = rsi(x.c, RSIP[p]);
        x.chanHi = new double[CHW.length][]; x.chanLo = new double[CHW.length][];
        for (int w = 0; w < CHW.length; w++) {
            x.chanHi[w] = roll(x.c, CHW[w], true);
            x.chanLo[w] = roll(x.c, CHW[w], false);
        }
        x.avgMove = new double[x.n];
        double run = 0;
        for (int j = 0; j < x.n; j++) {
            double step = j > 0 ? Math.abs(x.c[j] - x.c[j - 1]) : 0;
            run += step;
            if (j > 14) run -= Math.abs(x.c[j - 14] - x.c[j - 15]);
            x.avgMove[j] = j >= 14 ? run / 14 : (j > 0 ? run / j : 0);
        }
        x.volMa = sma(x.vol, 20);
        return x;
    }

    private static double[] sma(double[] v, int w) {
        double[] out = new double[v.length];
        double run = 0;
        for (int i = 0; i < v.length; i++) {
            run += v[i];
            if (i >= w) run -= v[i - w];
            out[i] = i >= w - 1 ? run / w : Double.NaN;
        }
        return out;
    }

    private static double[] rsi(double[] c, int p) {
        double[] out = new double[c.length];
        double g = 0, l = 0;
        for (int i = 0; i < c.length; i++) {
            if (i == 0) { out[i] = 50; continue; }
            double d = c[i] - c[i - 1];
            double up = Math.max(d, 0), dn = Math.max(-d, 0);
            if (i <= p) { g += up / p; l += dn / p; }
            else { g = (g * (p - 1) + up) / p; l = (l * (p - 1) + dn) / p; }
            out[i] = l == 0 ? 100 : 100 - 100 / (1 + g / l);
        }
        return out;
    }

    private static double[] roll(double[] c, int w, boolean hi) {
        // rolling extreme of closes (the close series is NaN-free by construction)
        double[] out = new double[c.length];
        for (int i = 0; i < c.length; i++) {
            double best = hi ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
            for (int j = Math.max(0, i - w + 1); j <= i; j++) {
                best = hi ? Math.max(best, c[j]) : Math.min(best, c[j]);
            }
            out[i] = best;
        }
        return out;
    }

    // ---- combination enumeration ----
    private static List<Combo> enumerate() {
        List<Combo> all = new ArrayList<>();
        int[] momL = {2, 3, 4, 6, 9, 12, 18, 24, 36, 48, 60, 72};
        double[] momT = {0, 0.2, 0.4, 0.7};
        int[] mrW = {12, 24, 36, 48, 72, 96};
        double[] mrZ = {0.5, 0.75, 1.0, 1.25, 1.5, 2.0};
        int[][] trendPairs = {{6, 24}, {12, 48}, {6, 48}, {12, 24}, {24, 48}, {24, 96}, {12, 96}, {36, 96}};
        for (int h : HORIZONS) {
            for (int L : momL) for (double t : momT) for (int v = 0; v < 2; v++) {
                all.add(mk(F_MOM, L, (int) (t * 100), v == 1, h));
                if (t > 0) all.add(mk(F_MOMFADE, L, (int) (t * 100), v == 1, h));
            }
            for (int w : mrW) for (double z : mrZ) {
                all.add(mk(F_MR, w, (int) (z * 100), false, h));
                all.add(mk(F_MRFOLLOW, w, (int) (z * 100), false, h));
            }
            for (int[] p : trendPairs) all.add(mk(F_TREND, p[0], p[1], false, h));
            for (int p : RSIP) { all.add(mk(F_RSI, p, 0, false, h)); all.add(mk(F_RSI, p, 1, true, h)); }
            for (int w : CHW) all.add(mk(F_CHAN, w, 0, false, h));
            for (int k : new int[]{3, 4, 5, 6, 8, 10}) for (int m : new int[]{10, 20, 30})
                all.add(mk(F_ANALOG, k, m, false, h));
            all.add(mk(F_ORB, 0, 0, false, h));
            all.add(mk(F_ORBFADE, 0, 0, false, h));
        }
        return all;
    }

    private static Combo mk(int fam, int a, int b, boolean flag, int h) {
        Combo c = new Combo();
        c.fam = fam; c.a = a; c.b = b; c.flag = flag; c.horizon = h;
        // v48: resolve the indicator window once at build time -
        // dirAt runs millions of times per forecast and used to
        // re-scan the window tables on every single call.
        if (fam == F_MR || fam == F_MRFOLLOW) c.wi = idxOf(MAW, a);
        else if (fam == F_TREND) { c.wi = idxOf(MAW, a); c.wi2 = idxOf(MAW, b); }
        else if (fam == F_RSI) c.wi = idxOf(RSIP, a);
        else if (fam == F_CHAN) c.wi = idxOf(CHW, a);
        return c;
    }

    /** Direction a combination calls at bar t: +1 up, -1 down, 0 abstain. */
    private static int dirAt(Ctx x, Combo cb, int t) {
        double close = x.c[t];
        switch (cb.fam) {
            case F_MOM:
            case F_MOMFADE: {
                int L = cb.a;
                if (t - L < 0 || x.day[t - L] != x.day[t]) return 0;
                double mv = close - x.c[t - L];
                double thr = cb.b / 100.0 * Math.max(x.avgMove[t], 1e-9) * L;
                if (Math.abs(mv) < thr) return 0;
                if (cb.flag && !(x.volMa[t] > 0 && x.vol[t] > x.volMa[t])) return 0;
                int d = mv > 0 ? 1 : -1;
                return cb.fam == F_MOM ? d : -d;
            }
            case F_MR:
            case F_MRFOLLOW: {
                int wi = cb.wi;
                if (wi < 0 || Double.isNaN(x.ma[wi][t]) || x.avgMove[t] <= 0) return 0;
                double z = (close - x.ma[wi][t]) / x.avgMove[t];
                if (Math.abs(z) < cb.b / 100.0) return 0;
                int d = z > 0 ? 1 : -1;
                return cb.fam == F_MR ? -d : d;
            }
            case F_TREND: {
                int fi = cb.wi, si = cb.wi2;
                if (fi < 0 || si < 0 || Double.isNaN(x.ma[fi][t]) || Double.isNaN(x.ma[si][t])) return 0;
                if (x.ma[fi][t] == x.ma[si][t]) return 0;
                return x.ma[fi][t] > x.ma[si][t] ? 1 : -1;
            }
            case F_RSI: {
                int pi = cb.wi;
                if (pi < 0) return 0;
                double r = x.rsi[pi][t];
                if (!cb.flag) { // reversal: oversold -> up, overbought -> down
                    if (r < 30) return 1;
                    if (r > 70) return -1;
                    return 0;
                }
                return r >= 50 ? 1 : -1; // trend mode
            }
            case F_CHAN: {
                int wi = cb.wi;
                if (wi < 0 || t < 1) return 0;
                if (close > x.chanHi[wi][t - 1]) return 1;
                if (close < x.chanLo[wi][t - 1]) return -1;
                return 0;
            }
            case F_ORB:
            case F_ORBFADE: {
                // today's opening range = first 3 bars of the day (09:15-09:30)
                int start = x.dayStart[t];
                if (t - start < 3) return 0;
                double hi = Math.max(x.c[start], Math.max(x.c[start + 1], x.c[start + 2]));
                double lo = Math.min(x.c[start], Math.min(x.c[start + 1], x.c[start + 2]));
                if (close > hi) return cb.fam == F_ORB ? 1 : -1;
                if (close < lo) return cb.fam == F_ORB ? -1 : 1;
                return 0;
            }
            case F_ANALOG: {
                double d = analogDir(x, cb.a, cb.b, t, cb.horizon)[0];
                return d > 0 ? 1 : d < 0 ? -1 : 0;
            }
            default:
                return 0;
        }
    }

    /** Pattern analog: shape of the last k bar-to-bar moves vs history.
     *  Returns {direction (-1/0/+1), mean forward points of the matches}.
     *  v48: same math, faster hands - bar diffs are precomputed, the
     *  worst-of-best slot is maintained instead of re-scanned per
     *  candidate, and a candidate whose partial distance already
     *  exceeds the worst kept match is abandoned early (distance
     *  terms are non-negative, so it could never have entered). */
    private static double[] analogDir(Ctx x, int k, int m, int t, int horizon) {
        if (t - k < 0) return new double[]{0, 0};
        double[] pat = new double[k];
        double scale = 0;
        for (int i = 0; i < k; i++) {
            pat[i] = x.diff[t - k + 1 + i];
            scale += Math.abs(pat[i]);
        }
        if (scale == 0) return new double[]{0, 0};
        // candidate past positions: recent history only, stride 3 for speed
        double[][] best = new double[m][2]; // {distance, fwdPts}
        for (int i = 0; i < m; i++) best[i][0] = Double.MAX_VALUE;
        int worst = 0;
        double worstVal = Double.MAX_VALUE;
        for (int s = Math.max(k, t - 1200); s + 1 < t - k; s += 3) {
            int e = horizon < 0 ? x.dayLast[s] : s + horizon;
            if (e <= s || e >= t) continue;
            if (x.day[e] != x.day[s]) continue;
            double dist = 0;
            boolean dead = false;
            for (int i = 0; i < k; i++) {
                dist += Math.abs(x.diff[s - k + 1 + i] - pat[i]);
                if (dist / scale >= worstVal) { dead = true; break; }
            }
            if (dead) continue;
            dist /= scale;
            if (dist < worstVal) {
                best[worst][0] = dist;
                best[worst][1] = x.c[e] - x.c[s];
                worst = 0;
                worstVal = best[0][0];
                for (int i = 1; i < m; i++) {
                    if (best[i][0] > worstVal) { worst = i; worstVal = best[i][0]; }
                }
            }
        }
        int up = 0, dn = 0;
        double sum = 0;
        int cnt = 0;
        for (double[] b : best) {
            if (b[0] == Double.MAX_VALUE) continue;
            cnt++;
            sum += b[1];
            if (b[1] > 0) up++; else if (b[1] < 0) dn++;
        }
        if (cnt < m / 2) return new double[]{0, 0};
        int dir = up > dn ? 1 : dn > up ? -1 : 0;
        return new double[]{dir, cnt > 0 ? sum / cnt : 0};
    }

    private static int fwdIndex(Ctx x, int t, int horizon) {
        if (horizon < 0) return x.dayLast[t];
        int e = t + horizon;
        return e < x.n && x.day[e] == x.day[t] ? e : -1;
    }

    /** Aggregate the graded combinations at bar t for one horizon.
     *  Returns {dir, consensusPct, expPts, firingCount}. */
    private static double[] aggregate(Ctx x, List<Combo> combos, int t, int horizon) {
        return aggregate(x, combos, t, horizon, null, null);
    }

    /** famW (v47): per-family live-track weight multipliers - a
     *  family whose forward live record has died votes softer, one
     *  that keeps working live votes a little louder. Backtest
     *  grades never change; this only re-weights today's vote.
     *  famDirOut, when given, receives each family's net vote sign. */
    private static double[] aggregate(Ctx x, List<Combo> combos, int t, int horizon,
                                      double[] famW, int[] famDirOut) {
        double wUp = 0, wDn = 0, ptsUp = 0, ptsDn = 0;
        int firing = 0;
        double[] famNet = famDirOut != null ? new double[10] : null;
        for (Combo cb : combos) {
            if (cb.horizon != horizon) continue;
            int d = dirAt(x, cb, t);
            if (d == 0) continue;
            double fw = famW != null && cb.fam < famW.length ? famW[cb.fam] : 1.0;
            if (d > 0 && cb.wUp > 0) {
                double w = cb.wUp * fw;
                wUp += w;
                ptsUp += w * (cb.nUp > 0 ? cb.sumUp / cb.nUp : 0);
                firing++;
                if (famNet != null) famNet[cb.fam] += w;
            } else if (d < 0 && cb.wDn > 0) {
                double w = cb.wDn * fw;
                wDn += w;
                ptsDn += w * (cb.nDn > 0 ? -cb.sumDn / cb.nDn : 0);
                firing++;
                if (famNet != null) famNet[cb.fam] -= w;
            }
        }
        if (famDirOut != null) {
            for (int i = 0; i < famDirOut.length && i < famNet.length; i++) {
                famDirOut[i] = famNet[i] > 0 ? 1 : famNet[i] < 0 ? -1 : 0;
            }
        }
        double tot = wUp + wDn;
        if (tot <= 0) return new double[]{0, 0, 0, 0};
        if (wUp >= wDn) return new double[]{1, wUp / tot * 100, ptsUp / Math.max(wUp, 1e-9), firing};
        return new double[]{-1, wDn / tot * 100, -ptsDn / Math.max(wDn, 1e-9), firing};
    }

    /** How many combinations the engine runs (for display). */
    public static int comboCount() { return enumerate().size(); }

    /** A trained ensemble: combinations graded on the first 60% of days,
     *  ready to vote at any bar. */
    public static final class Model {
        private final Ctx x;
        private final List<Combo> combos;

        private Model(Ctx x, List<Combo> combos) { this.x = x; this.combos = combos; }

        /** {dir, consensusPct, expPts, firing} at the bar with this epoch
         *  second, for a horizon in bars (-1 = to day close). */
        public double[] at(long epochSec, int horizonBars) {
            int t = -1;
            for (int i = 0; i < x.n; i++) if (x.ts[i] == epochSec) { t = i; break; }
            if (t < 0) return new double[]{0, 0, 0, 0};
            return aggregate(x, combos, t, horizonBars);
        }
    }

    /** Build + grade the ensemble (training window = first 60% of days). */
    public static Model train(YahooClient.Chart ch) {
        Ctx x = build(ch);
        List<Combo> combos = enumerate();
        int maxDay = x.day[x.n - 1];
        int splitDay = (int) (maxDay * 0.6);
        for (Combo cb : combos) {
            int stride = cb.fam == F_ANALOG ? 3 : 1;
            for (int t = 100; t < x.n; t += stride) {
                if (x.day[t] > splitDay) break;
                int e = fwdIndex(x, t, cb.horizon);
                if (e < 0 || e <= t) continue;
                int d = dirAt(x, cb, t);
                if (d == 0) continue;
                double fwd = x.c[e] - x.c[t];
                if (d > 0) { cb.nUp++; cb.sumUp += fwd; if (fwd > 0) cb.hitUp++; }
                else { cb.nDn++; cb.sumDn += fwd; if (fwd < 0) cb.hitDn++; }
            }
            cb.grade();
        }
        return new Model(x, combos);
    }

    /** Full forecast for the chart's latest bar. */
    public static Forecast forecast(YahooClient.Chart ch) {
        return forecast(ch, null);
    }

    // ---- forecast memo (v48) ----
    // A forecast is the app's heaviest computation (~2s of training
    // over 1,136 combinations), and the same chart is re-asked all
    // day: Detail opens, alert tickets, re-renders. Identical asks -
    // same bars, same live weights - are answered from this small
    // memo instead. Callers only read a Forecast, never mutate it.
    private static final java.util.Map<String, Forecast> MEMO =
            java.util.Collections.synchronizedMap(
                    new java.util.LinkedHashMap<String, Forecast>(16, 0.75f, true) {
                        @Override
                        protected boolean removeEldestEntry(java.util.Map.Entry<String, Forecast> e) {
                            return size() > 6;
                        }
                    });

    private static String memoKey(YahooClient.Chart ch, java.util.Map<String, Double> w) {
        int n = ch.ts.length;
        int mid = n / 2;
        String wk = "0";
        if (w != null) wk = Integer.toHexString(new java.util.TreeMap<>(w).hashCode());
        return n + "|" + ch.ts[0] + "|" + ch.ts[n - 1] + "|" + ch.close[0]
                + "|" + ch.close[mid] + "|" + ch.close[n - 1] + "|" + wk;
    }

    /** famWByFamily (v47): live-track vote weights keyed by FAMILY
     *  name, from PredictLive - applied to the latest bar's vote
     *  only. The holdout judging always measures the engine exactly
     *  as backtested. Null = no live weighting. */
    public static Forecast forecast(YahooClient.Chart ch, java.util.Map<String, Double> famWByFamily) {
        if (ch == null || ch.ts.length < 200) return forecastInner(ch, famWByFamily);
        String key = memoKey(ch, famWByFamily);
        Forecast hit = MEMO.get(key);
        if (hit != null) return hit;
        Forecast f = forecastInner(ch, famWByFamily);
        if (f.combosTotal > 0) MEMO.put(key, f);
        return f;
    }

    private static Forecast forecastInner(YahooClient.Chart ch, java.util.Map<String, Double> famWByFamily) {
        Forecast f = new Forecast();
        if (ch == null || ch.ts.length < 200) return f;
        Model model = train(ch);
        Ctx x = model.x;
        List<Combo> combos = model.combos;
        if (x.n < 200) return f;
        f.lastClose = x.c[x.n - 1];
        f.combosTotal = combos.size();
        for (Combo cb : combos) if (cb.live()) f.combosLive++;

        // split days: first 60% graded the combos, newest 40% judge the ensemble
        int maxDay = x.day[x.n - 1];
        int splitDay = (int) (maxDay * 0.6);

        // ---- judge the frozen ensemble on the holdout window ----
        int hits = 0, judged = 0;
        for (int t = 100; t < x.n; t += 3) {
            if (x.day[t] <= splitDay) continue;
            for (int hi = 0; hi < HORIZONS.length; hi++) {
                if (HORIZONS[hi] != 6) continue; // judge on the 30-min horizon
                double[] agg = aggregate(x, combos, t, HORIZONS[hi]);
                if (agg[0] == 0) continue;
                int e = fwdIndex(x, t, HORIZONS[hi]);
                if (e < 0 || e <= t) continue;
                double fwd = x.c[e] - x.c[t];
                if (fwd == 0) continue;
                judged++;
                if ((fwd > 0) == (agg[0] > 0)) hits++;
            }
        }
        f.holdoutN = judged;
        f.holdoutAcc = judged > 0 ? hits * 100.0 / judged : 0;

        // ---- predict from the latest bar ----
        double[] famW = null;
        if (famWByFamily != null) {
            famW = new double[10];
            for (int i = 0; i < 10; i++) {
                Double w = famWByFamily.get(FAMILY[i]);
                famW[i] = w != null ? w : 1.0;
            }
        }
        int now = x.n - 1;
        for (int hi = 0; hi < HORIZONS.length; hi++) {
            double[] agg = aggregate(x, combos, now, HORIZONS[hi], famW,
                    hi == 2 ? f.famDir60 : null);
            f.dir[hi] = (int) agg[0];
            f.consensus[hi] = agg[1];
            f.expPts[hi] = agg[2];
            f.firing[hi] = (int) agg[3];
            f.firingNow += (int) agg[3];
        }
        return f;
    }
}
