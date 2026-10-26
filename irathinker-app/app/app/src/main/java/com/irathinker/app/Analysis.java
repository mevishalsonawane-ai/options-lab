package com.irathinker.app;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** The day-trader method, India edition:
 *  scanner (ORB / oversold bounce / trend) + exit plan (stop at the range
 *  midpoint, target 1R, flat by the close) + Time Machine (Monte Carlo
 *  replays from the symbol's own bars + the real record of past firings). */
public final class Analysis {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime OR_END = LocalTime.of(9, 30);   // 15-min opening range
    private static final LocalTime MARKET_OPEN = LocalTime.of(9, 15);

    public enum Setup { NONE, ORB_UP, ORB_DOWN, OVERSOLD_BOUNCE, ABOVE_20DMA, BELOW_20DMA }

    public static final class Scan {
        public Universe.Sym sym;
        public double ltp = Double.NaN;
        public double dayPct = Double.NaN;
        public double prevClose = Double.NaN; // yesterday's close (day-move base)
        public double orHigh = Double.NaN, orLow = Double.NaN;
        public Setup setup = Setup.NONE;
        public boolean orReady = false;
        public long breakTs = 0;          // when the range broke (epoch s)
        public long lastTs = 0;           // ts of the last bar in the scanned day (epoch s)
        // exit plan (only for ORB setups)
        public double entry = Double.NaN, stop = Double.NaN, target = Double.NaN;
        // gap-fade plan (v42, the one new setup that passed the same
        // backtest bar as ORB): a >= 0.5% gap already filling by the
        // 09:45 bar - trade against the gap. Parallel to the ORB
        // plan; a day can carry both.
        public double fadeEntry = Double.NaN, fadeStop = Double.NaN, fadeTarget = Double.NaN;
        public double fadeGap = Double.NaN; // signed day-open gap, %
        public long fadeTs = 0;             // the 09:45 bar (epoch s)
        public boolean fadeUp = false;      // true: long fade (gap-down) · false: put fade (gap-up)
        public boolean hasFade() { return !Double.isNaN(fadeEntry) && fadeTs > 0; }
        public double rsi = Double.NaN;
        public double rvol = Double.NaN; // break-bar volume vs the day's earlier bars (x times)
        // price-action fingerprint of the entry (v45): the entry
        // bar's body strength in the trade's direction (0..1),
        // whether it engulfed the prior bar, the signed 3-bar thrust
        // into the entry (%), the entry's side of the day's VWAP
        // (+1 above / -1 below), and the day's swing structure
        // (+1 up / -1 down / 0 mixed). The Live Ledger records these
        // with every plan so the app learns which price action pays.
        public double paBody = Double.NaN;
        public boolean paEngulf = false;
        public double paThrust = Double.NaN;
        public int paVwap = 0;
        public int paStruct = 0;
        // signed 30-minute run-up into the entry (v46): net % move
        // of the six 5m bars before the entry bar, in the trade's
        // direction. The Live Ledger additionally saves the actual
        // previous 30 one-minute candles behind each plan.
        public double runMove = Double.NaN;
        public double dma20 = Double.NaN;
        public String error = null;
        public final java.util.List<String> why = new java.util.ArrayList<>();

        public boolean hasPlan() { return !Double.isNaN(entry) && !Double.isNaN(stop); }
    }

    /** IST calendar date of an epoch-second timestamp. */
    public static LocalDate istDate(long epochSec) {
        // v48: IST is a fixed UTC+5:30 offset (no DST in the modern
        // era), so the date is pure arithmetic - identical results
        // to the zoned conversion, without building a ZonedDateTime
        // for every bar in every loop that groups by day.
        return LocalDate.ofEpochDay(Math.floorDiv(epochSec + 19800L, 86400L));
    }

    /** Feed freshness during market hours, from the newest bar seen.
     *  Returns "" when there is nothing to judge (market closed or no
     *  bars yet) so callers can simply append the label when present. */
    public static String feedState(long lastTsEpoch, long nowEpoch, boolean marketOpen) {
        if (!marketOpen || lastTsEpoch <= 0) return "";
        long age = nowEpoch - lastTsEpoch;
        if (age <= 900) return "FEED OK";
        if (age <= 1800) return "FEED DELAYED";
        return "FEED STALE";
    }

    /** Scan one symbol from its 5m intraday + daily charts. */
    public static Scan scan(Universe.Sym sym, YahooClient.Chart intra, YahooClient.Chart daily) {
        Scan s = new Scan();
        s.sym = sym;
        LocalDate today = LocalDate.now(IST);
        List<Integer> t = YahooClient.indicesOfDay(intra, today);
        if (t.isEmpty()) { // fall back to the most recent day present in the feed
            LocalDate last = Instant.ofEpochSecond(intra.ts[intra.ts.length - 1]).atZone(IST).toLocalDate();
            t = YahooClient.indicesOfDay(intra, last);
        }
        if (t.isEmpty()) { s.error = "no bars"; return s; }
        s.lastTs = intra.ts[t.get(t.size() - 1)];

        // last traded price = last non-NaN close today
        for (int i = t.size() - 1; i >= 0; i--) {
            double c = intra.close[t.get(i)];
            if (!Double.isNaN(c)) { s.ltp = c; break; }
        }
        // Day change vs the last daily close BEFORE the scanned day. The
        // intraday feed's chartPreviousClose is the close before its whole
        // 5-day window, not yesterday's - using it printed 5-day moves as
        // day moves (caught live 2026-10-23: NIFTY showed -1.93% on a flat open).
        double prevClose = Double.NaN;
        if (daily != null && daily.ts != null) {
            LocalDate scanDay = istDate(s.lastTs);
            for (int i = daily.ts.length - 1; i >= 0; i--) {
                if (istDate(daily.ts[i]).isBefore(scanDay)
                        && !Double.isNaN(daily.close[i]) && daily.close[i] > 0) {
                    prevClose = daily.close[i];
                    break;
                }
            }
        }
        if (Double.isNaN(prevClose)) prevClose = intra.prevClose;
        s.prevClose = prevClose;
        if (!Double.isNaN(prevClose) && prevClose > 0 && !Double.isNaN(s.ltp)) {
            s.dayPct = (s.ltp / prevClose - 1) * 100;
        }

        // opening range: bars starting before OR_END
        double hi = Double.NEGATIVE_INFINITY, lo = Double.POSITIVE_INFINITY;
        int orBars = 0;
        for (int i : t) {
            LocalTime bt = Instant.ofEpochSecond(intra.ts[i]).atZone(IST).toLocalTime();
            if (bt.isBefore(OR_END)) {
                if (!Double.isNaN(intra.high[i])) { hi = Math.max(hi, intra.high[i]); orBars++; }
                if (!Double.isNaN(intra.low[i])) lo = Math.min(lo, intra.low[i]);
            }
        }
        if (orBars >= 2 && hi > lo) {
            s.orHigh = hi; s.orLow = lo; s.orReady = true;
        }

        // first close beyond the range -> plan
        if (s.orReady) {
            long[] dayVols = new long[t.size()];
            for (int k = 0; k < t.size(); k++) dayVols[k] = intra.volume[t.get(k)];
            int pos = 0;
            for (int i : t) {
                LocalTime bt = Instant.ofEpochSecond(intra.ts[i]).atZone(IST).toLocalTime();
                if (!bt.isBefore(OR_END)) {
                    double c = intra.close[i];
                    if (Double.isNaN(c)) { pos++; continue; }
                    if (c > s.orHigh) { s.setup = Setup.ORB_UP; s.entry = c; s.breakTs = intra.ts[i]; s.rvol = rvolOf(dayVols, pos); break; }
                    if (c < s.orLow) { s.setup = Setup.ORB_DOWN; s.entry = c; s.breakTs = intra.ts[i]; s.rvol = rvolOf(dayVols, pos); break; }
                }
                pos++;
            }
            if (!Double.isNaN(s.entry)) {
                double mid = (s.orHigh + s.orLow) / 2;
                double risk = Math.abs(s.entry - mid);
                s.stop = mid;
                s.target = (s.setup == Setup.ORB_UP) ? s.entry + risk : s.entry - risk;
                if (risk <= 0) { s.entry = s.stop = s.target = Double.NaN; s.setup = Setup.NONE; }
            }
            if (s.hasPlan()) {
                computePa(s, intra, t, pos, s.setup == Setup.ORB_UP);
                s.runMove = runMoveOf(intra, t, pos, s.setup == Setup.ORB_UP);
            }
        }

        computeFade(s, intra, t, prevClose);

        // daily context: 20DMA + RSI(14)
        if (daily != null && daily.close.length > 20) {
            double[] cl = lastFinite(daily.close, 40);
            if (cl.length > 20) {
                double sum = 0;
                for (int i = cl.length - 20; i < cl.length; i++) sum += cl[i];
                s.dma20 = sum / 20;
                s.rsi = rsi14(cl);
                if (s.setup == Setup.NONE) {
                    if (!Double.isNaN(s.rsi) && s.rsi < 35 && !Double.isNaN(s.dayPct) && s.dayPct > 0.5) {
                        s.setup = Setup.OVERSOLD_BOUNCE;
                    } else if (!Double.isNaN(s.ltp) && !Double.isNaN(s.dma20)) {
                        s.setup = s.ltp > s.dma20 ? Setup.ABOVE_20DMA : Setup.BELOW_20DMA;
                    }
                }
            }
        }
        return s;
    }

    private static double[] lastFinite(double[] a, int max) {
        List<Double> v = new ArrayList<>();
        for (double x : a) if (!Double.isNaN(x) && x > 0) v.add(x);
        int from = Math.max(0, v.size() - max);
        double[] out = new double[v.size() - from];
        for (int i = from; i < v.size(); i++) out[i - from] = v.get(i);
        return out;
    }

    static double rsi14(double[] closes) {
        if (closes.length < 15) return Double.NaN;
        double gain = 0, loss = 0;
        for (int i = closes.length - 14; i < closes.length; i++) {
            double d = closes[i] - closes[i - 1];
            if (d > 0) gain += d; else loss -= d;
        }
        if (loss == 0) return 100;
        double rs = (gain / 14) / (loss / 14);
        return 100 - 100 / (1 + rs);
    }

    // ---------------- Time Machine ----------------

    /** The feature tuple Time Machine 2.0 (v46) matches setups on:
     *  range width, score, the entry bar's price action (body,
     *  engulfing, thrust), the 30-minute run-up, the time of day,
     *  and the day's structure / VWAP side. Two setups are "similar"
     *  when their tuples are close - those are the only past cases
     *  whose outcomes should colour today's odds. */
    public static final class Feat {
        public double width = Double.NaN, body = Double.NaN, thrust = Double.NaN;
        public double run = Double.NaN, hour = Double.NaN;
        public int score, struct, vwap, engulf;
    }

    static Feat featOf(Scan s, int score) {
        Feat f = new Feat();
        if (s.entry > 0) f.width = (s.orHigh - s.orLow) / s.entry * 100;
        f.score = score;
        f.body = s.paBody;
        f.thrust = s.paThrust;
        f.run = s.runMove;
        if (s.breakTs > 0) {
            java.time.ZonedDateTime z = Instant.ofEpochSecond(s.breakTs).atZone(IST);
            f.hour = z.getHour() + z.getMinute() / 60.0;
        }
        f.struct = s.paStruct;
        f.vwap = s.paVwap;
        f.engulf = s.paEngulf ? 1 : 0;
        return f;
    }

    private static double term(java.util.List<Double> terms, double a, double b, double scale) {
        if (Double.isNaN(a) || Double.isNaN(b)) return 0;
        terms.add(Math.abs(a - b) / scale);
        return 1;
    }

    /** Scaled distance between two setup tuples; +infinity when
     *  too few features are shared to judge similarity at all. */
    static double featDistance(Feat a, Feat b) {
        java.util.List<Double> terms = new java.util.ArrayList<>();
        double n = 0;
        n += term(terms, a.width, b.width, 1.0);
        n += term(terms, a.score, b.score, 25.0);
        n += term(terms, a.body, b.body, 0.4);
        n += term(terms, a.thrust, b.thrust, 1.0);
        n += term(terms, a.run, b.run, 1.5);
        n += term(terms, a.hour, b.hour, 2.0);
        if (a.struct == b.struct) { terms.add(0.0); n++; }
        else if (a.struct == 0 || b.struct == 0) { terms.add(0.7); n++; }
        else { terms.add(1.4); n++; }
        if (a.vwap == b.vwap) { terms.add(0.0); n++; }
        else if (a.vwap == 0 || b.vwap == 0) { terms.add(0.5); n++; }
        else { terms.add(1.0); n++; }
        terms.add(a.engulf == b.engulf ? 0.0 : 0.5); n++;
        if (n < 5) return Double.POSITIVE_INFINITY;
        double ss = 0;
        for (double t : terms) ss += t * t;
        return Math.sqrt(ss);
    }

    /** Similarity weight of a distance: 1 for identical, fading smoothly. */
    static double kernelW(double d) {
        return Math.exp(-(d * d) / 2.0);
    }

    public static final class Odds {
        public int paths;
        public double targetFirst, stopFirst, neither;
        public int analogFires, analogTarget, analogStop, analogNeither;
        // Time Machine 2.0: odds over the CLOSEST-matching past
        // setups only (kernel-weighted). simOk when the effective
        // match count is large enough to mean anything.
        public double simTarget, simStop, simNeither, simEffN;
        public int simRaw;
        public boolean simOk;
    }

    /** Replay the rest of the session from the trigger bar: Monte Carlo over
     *  the symbol's own 5m log-returns (last 60 sessions), plus the real
     *  record of every past day the same ORB setup fired. */
    public static Odds timeMachine(YahooClient.Chart intra, Scan s, int paths) {
        return timeMachine(intra, s, paths, null);
    }

    /** Time Machine 2.0 (v46): alongside the plain record, odds are
     *  computed over the CLOSEST-matching past setups only - the
     *  same-direction plans (from this symbol's chart history AND
     *  the app's own Live Ledger) whose context + price-action
     *  tuple sits near today's, kernel-weighted by similarity.
     *  liveRecs may be null. */
    public static Odds timeMachine(YahooClient.Chart intra, Scan s, int paths,
                                   java.util.List<Ledger.Rec> liveRecs) {
        Odds o = new Odds();
        o.paths = paths;
        if (!s.hasPlan()) return o;
        boolean up = s.setup == Setup.ORB_UP;
        double risk = Math.abs(s.entry - s.stop);
        if (risk <= 0) return o;

        Feat liveFeat = featOf(s, liteScore(s));
        double wT = 0, wS = 0, wN = 0, wSum = 0, wSq = 0;
        int rawNear = 0;

        // historical analog: replay past days with the same raw bars
        LocalDate today = Instant.ofEpochSecond(s.breakTs).atZone(IST).toLocalDate();
        java.util.Set<LocalDate> days = new java.util.TreeSet<>();
        for (long ts : intra.ts) days.add(Instant.ofEpochSecond(ts).atZone(IST).toLocalDate());
        for (LocalDate d : days) {
            if (d.equals(today)) continue;
            Scan past = scanDayOnly(intra, d, s.sym);
            if (past == null || !past.hasPlan()) continue;
            if ((past.setup == Setup.ORB_UP) != up) continue;
            o.analogFires++;
            int res = walkToClose(intra, d, past);
            if (res > 0) o.analogTarget++; else if (res < 0) o.analogStop++; else o.analogNeither++;
            past.ltp = past.entry; // score the past plan at its break bar, as live
            double dist = featDistance(liveFeat, featOf(past, liteScore(past)));
            double w = kernelW(dist);
            if (w > 0) {
                wSum += w; wSq += w * w;
                if (res > 0) wT += w; else if (res < 0) wS += w; else wN += w;
                if (dist <= 2.0) rawNear++;
            }
        }

        // the app's own live record joins the analog pool: same
        // symbol, same direction, already judged against real bars
        if (liveRecs != null) {
            for (Ledger.Rec r : liveRecs) {
                if (r.outcome == 0 || !r.orb()) continue;
                if (!s.sym.yahoo.equals(r.yahoo)) continue;
                if (r.up() != up) continue;
                double dist = featDistance(liveFeat, Ledger.featOf(r));
                double w = kernelW(dist);
                if (w <= 0) continue;
                wSum += w; wSq += w * w;
                if (r.outcome > 0) wT += w; else if (r.outcome < 0) wS += w; else wN += w;
                if (dist <= 2.0) rawNear++;
            }
        }

        if (wSum > 0) {
            o.simTarget = wT / wSum * 100;
            o.simStop = wS / wSum * 100;
            o.simNeither = wN / wSum * 100;
            o.simEffN = wSum * wSum / wSq;
            o.simRaw = rawNear;
            o.simOk = o.simEffN >= 8 && rawNear >= 5;
        }

        // collect 5m log returns from all sessions in the feed
        List<Double> rets = new ArrayList<>();
        LocalDate cur = null;
        double prev = Double.NaN;
        for (int i = 0; i < intra.ts.length; i++) {
            LocalDate d = Instant.ofEpochSecond(intra.ts[i]).atZone(IST).toLocalDate();
            double c = intra.close[i];
            if (!d.equals(cur)) { cur = d; prev = Double.NaN; }
            if (!Double.isNaN(c) && !Double.isNaN(prev) && prev > 0) {
                rets.add(Math.log(c / prev));
            }
            if (!Double.isNaN(c)) prev = c;
        }
        if (rets.size() < 100) return o;
        double[] pool = new double[rets.size()];
        for (int i = 0; i < pool.length; i++) pool[i] = rets.get(i);

        // bars remaining in a session after the trigger
        LocalTime trigTime = Instant.ofEpochSecond(s.breakTs).atZone(IST).toLocalTime();
        int barsLeft = Math.max(1, (int) ((LocalTime.of(15, 30).toSecondOfDay() - trigTime.toSecondOfDay()) / 300));
        Random rnd = new Random(19);
        int tf = 0, sf = 0, nn = 0;
        for (int p = 0; p < paths; p++) {
            double price = s.entry;
            int outcome = 0;
            for (int b = 0; b < barsLeft; b++) {
                price *= Math.exp(pool[rnd.nextInt(pool.length)]);
                if (up ? price >= s.target : price <= s.target) { outcome = 1; break; }
                if (up ? price <= s.stop : price >= s.stop) { outcome = -1; break; }
            }
            if (outcome > 0) tf++; else if (outcome < 0) sf++; else nn++;
        }
        o.targetFirst = tf * 100.0 / paths;
        o.stopFirst = sf * 100.0 / paths;
        o.neither = nn * 100.0 / paths;
        return o;
    }

    /** ORB plan for one past day, without daily context (fast, bars only). */
    private static Scan scanDayOnly(YahooClient.Chart intra, LocalDate day, Universe.Sym sym) {
        List<Integer> t = YahooClient.indicesOfDay(intra, day);
        if (t.size() < 10) return null;
        Scan s = new Scan();
        s.sym = sym;
        double hi = Double.NEGATIVE_INFINITY, lo = Double.POSITIVE_INFINITY;
        int orBars = 0;
        for (int i : t) {
            LocalTime bt = Instant.ofEpochSecond(intra.ts[i]).atZone(IST).toLocalTime();
            if (bt.isBefore(OR_END)) {
                if (!Double.isNaN(intra.high[i])) { hi = Math.max(hi, intra.high[i]); orBars++; }
                if (!Double.isNaN(intra.low[i])) lo = Math.min(lo, intra.low[i]);
            }
        }
        if (orBars < 2 || hi <= lo) return null;
        s.orHigh = hi; s.orLow = lo; s.orReady = true;
        int pos = -1, p = 0;
        for (int i : t) {
            LocalTime bt = Instant.ofEpochSecond(intra.ts[i]).atZone(IST).toLocalTime();
            if (!bt.isBefore(OR_END)) {
                double c = intra.close[i];
                if (!Double.isNaN(c)) {
                    if (c > hi) { s.setup = Setup.ORB_UP; s.entry = c; s.breakTs = intra.ts[i]; pos = p; break; }
                    if (c < lo) { s.setup = Setup.ORB_DOWN; s.entry = c; s.breakTs = intra.ts[i]; pos = p; break; }
                }
            }
            p++;
        }
        if (Double.isNaN(s.entry)) return null;
        double mid = (hi + lo) / 2, risk = Math.abs(s.entry - mid);
        if (risk <= 0) return null;
        s.stop = mid;
        s.target = s.setup == Setup.ORB_UP ? s.entry + risk : s.entry - risk;
        if (pos >= 0) {
            boolean up = s.setup == Setup.ORB_UP;
            computePa(s, intra, t, pos, up);
            s.runMove = runMoveOf(intra, t, pos, up);
        }
        return s;
    }

    /** The gap-fade test, exactly as backtested (study_strats2, the
     *  only candidate of eight that was positive in both halves):
     *  |gap| >= 0.5% at the open, and by the 09:45 bar the price has
     *  crossed back through the open but not through yesterday's
     *  close - the gap is filling. Entry = the 09:45 close, stop =
     *  the day's extreme so far, target = 1R, risk capped at 2%. */
    static void computeFade(Scan s, YahooClient.Chart intra, List<Integer> t, double prevClose) {
        if (t == null || t.size() < 10 || Double.isNaN(prevClose) || prevClose <= 0) return;
        double dayOpen = intra.open[t.get(0)];
        if (Double.isNaN(dayOpen) || dayOpen <= 0) return;
        double gap = (dayOpen / prevClose - 1) * 100;
        if (Math.abs(gap) < 0.5) return;
        int p = -1;
        for (int k = 0; k < t.size(); k++) {
            if (Instant.ofEpochSecond(intra.ts[t.get(k)]).atZone(IST).toLocalTime()
                    .equals(LocalTime.of(9, 45))) { p = k; break; }
        }
        if (p < 0) return;
        double dHi = Double.NEGATIVE_INFINITY, dLo = Double.POSITIVE_INFINITY;
        for (int k = 0; k <= p; k++) {
            double h = intra.high[t.get(k)], l = intra.low[t.get(k)];
            if (!Double.isNaN(h)) dHi = Math.max(dHi, h);
            if (!Double.isNaN(l)) dLo = Math.min(dLo, l);
        }
        double cl = intra.close[t.get(p)];
        if (Double.isNaN(cl) || dHi == Double.NEGATIVE_INFINITY || dLo == Double.POSITIVE_INFINITY) return;
        if (gap > 0 && cl < dayOpen && cl > prevClose) {
            double risk = dHi - cl;
            if (risk > 0 && risk <= 0.02 * cl) {
                s.fadeUp = false; s.fadeEntry = cl; s.fadeStop = dHi; s.fadeTarget = cl - risk;
                s.fadeTs = intra.ts[t.get(p)]; s.fadeGap = gap;
            }
        } else if (gap < 0 && cl > dayOpen && cl < prevClose) {
            double risk = cl - dLo;
            if (risk > 0 && risk <= 0.02 * cl) {
                s.fadeUp = true; s.fadeEntry = cl; s.fadeStop = dLo; s.fadeTarget = cl + risk;
                s.fadeTs = intra.ts[t.get(p)]; s.fadeGap = gap;
            }
        }
        if (s.hasFade()) {
            computePa(s, intra, t, p, s.fadeUp);
            s.runMove = runMoveOf(intra, t, p, s.fadeUp);
        }
    }

    /** The 30-minute run-up into an entry bar: net % move across
     *  the six bars before it, signed in the trade's direction
     *  (positive = momentum already running with the trade). */
    static double runMoveOf(YahooClient.Chart intra, List<Integer> t, int pos, boolean up) {
        if (t == null || pos <= 0 || pos >= t.size()) return Double.NaN;
        double cEntry = intra.close[t.get(pos)];
        double cFrom = intra.close[t.get(Math.max(0, pos - 6))];
        if (Double.isNaN(cEntry) || Double.isNaN(cFrom) || cFrom <= 0) return Double.NaN;
        return (cEntry / cFrom - 1) * 100 * (up ? 1 : -1);
    }

    /** Price-action fingerprint of an entry bar (v45): body
     *  strength in the trade's direction, engulfing of the prior
     *  bar, the 3-bar thrust into the entry, VWAP side, and the
     *  day's swing structure. Recorded by the Live Ledger with
     *  every plan; graded there against what actually happened. */
    static void computePa(Scan s, YahooClient.Chart intra, List<Integer> t, int pos, boolean up) {
        if (t == null || pos < 0 || pos >= t.size()) return;
        int i = t.get(pos);
        double o = intra.open[i], h = intra.high[i], l = intra.low[i], c = intra.close[i];
        if (Double.isNaN(o) || Double.isNaN(h) || Double.isNaN(l) || Double.isNaN(c) || h <= l) return;
        s.paBody = (up ? (c - o) : (o - c)) / (h - l);
        if (pos > 0) {
            int j = t.get(pos - 1);
            double po = intra.open[j], pc = intra.close[j];
            if (!Double.isNaN(po) && !Double.isNaN(pc)) {
                s.paEngulf = up ? (c > po && o < pc && c > o) : (c < po && o > pc && c < o);
            }
            int k = Math.max(0, pos - 3);
            double c0 = intra.close[t.get(k)];
            if (!Double.isNaN(c0) && c0 > 0) s.paThrust = (c / c0 - 1) * 100 * (up ? 1 : -1);
        }
        double pv = 0, vv = 0;
        for (int p = 0; p <= pos; p++) {
            int j = t.get(p);
            double tp = (intra.high[j] + intra.low[j] + intra.close[j]) / 3;
            long v = intra.volume[j];
            if (Double.isNaN(tp) || v <= 0) continue;
            pv += tp * v; vv += v;
        }
        if (vv > 0) {
            double vwap = pv / vv;
            s.paVwap = c > vwap ? 1 : c < vwap ? -1 : 0;
        }
        s.paStruct = dayStruct(intra, t, pos);
    }

    /** Swing structure of the day up to a bar: +1 when the last two
     *  pivot highs AND pivot lows both step up, -1 when both step
     *  down, 0 otherwise (3-bar pivots). */
    static int dayStruct(YahooClient.Chart intra, List<Integer> t, int pos) {
        java.util.List<Double> ph = new java.util.ArrayList<>(), pl = new java.util.ArrayList<>();
        for (int p = 1; p < pos; p++) {
            double h0 = intra.high[t.get(p - 1)], h1 = intra.high[t.get(p)], h2 = intra.high[t.get(p + 1)];
            double l0 = intra.low[t.get(p - 1)], l1 = intra.low[t.get(p)], l2 = intra.low[t.get(p + 1)];
            if (!Double.isNaN(h1) && !Double.isNaN(h0) && !Double.isNaN(h2) && h1 >= h0 && h1 >= h2) ph.add(h1);
            if (!Double.isNaN(l1) && !Double.isNaN(l0) && !Double.isNaN(l2) && l1 <= l0 && l1 <= l2) pl.add(l1);
        }
        if (ph.size() < 2 || pl.size() < 2) return 0;
        double hA = ph.get(ph.size() - 2), hB = ph.get(ph.size() - 1);
        double lA = pl.get(pl.size() - 2), lB = pl.get(pl.size() - 1);
        if (hB > hA && lB > lA) return 1;
        if (hB < hA && lB < lA) return -1;
        return 0;
    }

    /** A day-scan carrying only the gap-fade plan (scanDayOnly is
     *  ORB-only and returns null on fade-only days). */
    private static Scan fadeDayOnly(YahooClient.Chart intra, LocalDate day, Universe.Sym sym, double prevClose) {
        List<Integer> t = YahooClient.indicesOfDay(intra, day);
        if (t.size() < 10) return null;
        Scan s = new Scan();
        s.sym = sym;
        s.prevClose = prevClose;
        computeFade(s, intra, t, prevClose);
        return s.hasFade() ? s : null;
    }

    /** Walk a past plan bar by bar to the close. +1 target, -1 stop, 0 neither. */
    private static int walkToClose(YahooClient.Chart intra, LocalDate day, Scan past) {
        List<Integer> t = YahooClient.indicesOfDay(intra, day);
        boolean up = past.setup == Setup.ORB_UP;
        boolean started = false;
        for (int i : t) {
            if (intra.ts[i] < past.breakTs) continue;
            started = true;
            double h = intra.high[i], l = intra.low[i];
            if (Double.isNaN(h) || Double.isNaN(l)) continue;
            if (up) {
                if (l <= past.stop) return -1;
                if (h >= past.target) return 1;
            } else {
                if (h >= past.stop) return -1;
                if (l <= past.target) return 1;
            }
        }
        return started ? 0 : 0;
    }

    // ---------------- Budget + risk ----------------

    public static final class Plan {
        public int qty;
        public double riskPerShare, maxLoss, positionValue;
        public List<String> riskChecks = new ArrayList<>();
        public boolean pass;
    }

    public static Plan budget(Scan s, double capital, double maxLossPct) {
        Plan p = new Plan();
        p.maxLoss = capital * maxLossPct / 100.0;
        if (!s.hasPlan()) return p;
        p.riskPerShare = Math.abs(s.entry - s.stop);
        if (p.riskPerShare <= 0) return p;
        p.qty = (int) Math.floor(p.maxLoss / p.riskPerShare);
        p.positionValue = p.qty * s.entry;
        double rr = Math.abs(s.target - s.entry) / p.riskPerShare;
        p.riskChecks.add(String.format("Reward:risk %.2f : 1 %s", rr, rr >= 0.9 ? "(ok)" : "(below 1R - FAIL)"));
        p.riskChecks.add(String.format("Max loss at stop Rs %.0f of Rs %.0f budget %s",
                p.qty * p.riskPerShare, p.maxLoss, p.qty > 0 ? "(ok)" : "(qty 0 - FAIL)"));
        p.riskChecks.add(String.format("Position Rs %.0f vs capital Rs %.0f %s",
                p.positionValue, capital, p.positionValue <= capital ? "(ok)" : "(exceeds capital - needs margin/F&O)"));
        LocalTime now = LocalTime.now(IST);
        boolean window = now.isAfter(LocalTime.of(9, 30)) && now.isBefore(LocalTime.of(15, 0));
        p.riskChecks.add("Entry window 09:30-15:00 IST " + (window ? "(ok)" : "(outside - FAIL)"));
        p.pass = p.qty > 0 && rr >= 0.9;
        if (!window) p.pass = false;
        return p;
    }

    // ---------------- backtest (Strategy Lab) ----------------

    public static final class Trade {
        public String date;
        public long breakTs;
        public boolean up;
        public double entry, stop, target, exit;
        public String reason;      // TARGET / STOP / EOD
        public double retPct;      // direction-adjusted, after 0.05% cost
    }

    /** Replay the ORB rule day by day over a 5-min chart.
     *  side: +1 long only, -1 short only, 0 both. One trade per day max. */
    public static java.util.List<Trade> orbBacktest(YahooClient.Chart intra, int orMinutes, double rMult, int side) {
        java.util.List<Trade> out = new java.util.ArrayList<>();
        java.util.Map<LocalDate, java.util.List<Integer>> byDay = new java.util.TreeMap<>();
        for (int i = 0; i < intra.ts.length; i++) {
            LocalDate d = Instant.ofEpochSecond(intra.ts[i]).atZone(IST).toLocalDate();
            byDay.computeIfAbsent(d, k -> new java.util.ArrayList<>()).add(i);
        }
        LocalTime orEnd = LocalTime.of(9, 15).plusMinutes(orMinutes);
        for (java.util.Map.Entry<LocalDate, java.util.List<Integer>> e : byDay.entrySet()) {
            java.util.List<Integer> t = e.getValue();
            if (t.size() < 20) continue;
            double hi = Double.NEGATIVE_INFINITY, lo = Double.POSITIVE_INFINITY;
            int orBars = 0;
            for (int i : t) {
                LocalTime bt = Instant.ofEpochSecond(intra.ts[i]).atZone(IST).toLocalTime();
                if (bt.isBefore(orEnd)) {
                    if (!Double.isNaN(intra.high[i])) { hi = Math.max(hi, intra.high[i]); orBars++; }
                    if (!Double.isNaN(intra.low[i])) lo = Math.min(lo, intra.low[i]);
                }
            }
            if (orBars < 2 || hi <= lo) continue;
            int breakIdx = -1;
            boolean up = false;
            double entry = Double.NaN;
            for (int i : t) {
                LocalTime bt = Instant.ofEpochSecond(intra.ts[i]).atZone(IST).toLocalTime();
                if (!bt.isBefore(orEnd)) {
                    double c = intra.close[i];
                    if (Double.isNaN(c)) continue;
                    if (c > hi) { breakIdx = i; up = true; entry = c; break; }
                    if (c < lo) { breakIdx = i; up = false; entry = c; break; }
                }
            }
            if (breakIdx < 0) continue;
            if (side == 1 && !up) continue;
            if (side == -1 && up) continue;
            double mid = (hi + lo) / 2, risk = Math.abs(entry - mid);
            if (risk <= 0) continue;
            double stop = mid;
            double target = up ? entry + rMult * risk : entry - rMult * risk;
            double exit = Double.NaN;
            String reason = "EOD";
            for (int i : t) {
                if (intra.ts[i] <= intra.ts[breakIdx]) continue;
                double h = intra.high[i], l = intra.low[i];
                if (Double.isNaN(h) || Double.isNaN(l)) continue;
                if (up) {
                    if (l <= stop) { exit = stop; reason = "STOP"; break; }
                    if (h >= target) { exit = target; reason = "TARGET"; break; }
                } else {
                    if (h >= stop) { exit = stop; reason = "STOP"; break; }
                    if (l <= target) { exit = target; reason = "TARGET"; break; }
                }
            }
            if (Double.isNaN(exit)) {
                for (int k = t.size() - 1; k >= 0; k--) {
                    double c = intra.close[t.get(k)];
                    if (!Double.isNaN(c)) { exit = c; break; }
                }
            }
            if (Double.isNaN(exit) || entry <= 0) continue;
            Trade tr = new Trade();
            tr.date = e.getKey().toString();
            tr.breakTs = intra.ts[breakIdx];
            tr.up = up;
            tr.entry = entry; tr.stop = stop; tr.target = target; tr.exit = exit;
            tr.reason = reason;
            tr.retPct = (exit - entry) / entry * 100.0 * (up ? 1 : -1) - 0.05;
            out.add(tr);
        }
        return out;
    }

    public static final class Stats {
        public int n, wins;
        public double winPct, pf, expectancy, total, maxDd;
        public double[] equity = new double[0];
    }

    public static Stats summarize(java.util.List<Trade> trades) {
        Stats s = new Stats();
        java.util.List<Trade> sorted = new java.util.ArrayList<>(trades);
        sorted.sort((a, b) -> Long.compare(a.breakTs, b.breakTs));
        s.n = sorted.size();
        double gw = 0, gl = 0, cum = 0, peak = 0;
        s.equity = new double[s.n];
        for (int i = 0; i < s.n; i++) {
            double r = sorted.get(i).retPct;
            if (r > 0) { s.wins++; gw += r; } else gl -= r;
            cum += r;
            s.equity[i] = cum;
            peak = Math.max(peak, cum);
            s.maxDd = Math.min(s.maxDd, cum - peak);
        }
        s.total = cum;
        s.winPct = s.n == 0 ? 0 : s.wins * 100.0 / s.n;
        s.pf = gl == 0 ? (gw > 0 ? 99 : 0) : gw / gl;
        s.expectancy = s.n == 0 ? 0 : cum / s.n;
        return s;
    }

    /** Resolve a live/past plan against later bars: +1 target, -1 stop, 0 neither by the close.
     *  Walking starts at the bar AFTER the entry bar - the entry bar's own range
     *  mostly happened before the entry existed. */
    public static int outcomeOf(YahooClient.Chart intra, long breakTs, double stop, double target, boolean up) {
        long startTs = breakTs;
        for (int i = 0; i < intra.ts.length; i++) {
            if (intra.ts[i] > breakTs) { startTs = intra.ts[i]; break; }
        }
        Scan tmp = new Scan();
        tmp.breakTs = startTs;
        tmp.stop = stop;
        tmp.target = target;
        tmp.setup = up ? Setup.ORB_UP : Setup.ORB_DOWN;
        LocalDate d = Instant.ofEpochSecond(breakTs).atZone(IST).toLocalDate();
        return walkToClose(intra, d, tmp);
    }

    // ---------------- top-N plan picker ----------------

    /** The actionable ORB plans in a batch, best first by the given score,
     *  capped at n. BuyAlerts uses this so a day's notifications are the
     *  top 3 signals, not every signal. */
    public static java.util.List<Scan> topPlans(java.util.Collection<Scan> scans, int n,
                                                java.util.function.ToIntFunction<Scan> scoreFn) {
        java.util.List<Scan> out = new ArrayList<>();
        if (scans != null) {
            for (Scan s : scans) {
                if (s != null && s.hasPlan()
                        && (s.setup == Setup.ORB_UP || s.setup == Setup.ORB_DOWN)) out.add(s);
            }
        }
        out.sort((a, b) -> Integer.compare(scoreFn.applyAsInt(b), scoreFn.applyAsInt(a)));
        return out.size() > n ? new java.util.ArrayList<>(out.subList(0, Math.max(0, n))) : out;
    }

    /** The live notification gate (user rule): confidence above 65.
     *  BuyAlerts applies the same value. */
    public static final int ALERT_MIN_SCORE = 65;

    /** The user's points floor (v25 rule, restored in v35 after a
     *  one-build retirement in v33): a plan must offer at least this
     *  many points to its target. The accuracy study's stock-%
     *  expectancy never priced option spreads - a 2-point target is
     *  untradeable in a real option whatever the backtest says, so
     *  the floor stands alongside the width rule. */
    public static final double ALERT_MIN_TARGET_POINTS = 10.0;

    /** The width rule (accuracy study, 2026-10-23): only a NARROW
     *  opening range - at most this % of the price - leaves the break
     *  room to run. At 2R exits the threshold is mostly a frequency
     *  dial (60 days: 0.6% -> 1.6/day, PF 1.59; 0.8% -> 3.2/day,
     *  PF 1.45; expectancy ~+0.09% either way, both halves positive).
     *  The user chose 0.8% - his standing target is 2-3 trades/day. */
    public static final double ALERT_MAX_RANGE_WIDTH_PCT = 0.8;

    public static boolean rangeNarrow(Scan s) {
        if (s == null || s.entry <= 0 || s.orHigh <= s.orLow) return false;
        return (s.orHigh - s.orLow) / s.entry * 100.0 <= ALERT_MAX_RANGE_WIDTH_PCT;
    }

    /** The exit target an alert actually trades: 2R from the entry
     *  (accuracy study, 2026-10-23: 2R exits beat the plan's 1R in
     *  both halves - expectancy +0.083%, PF 1.34 alone; stacked with
     *  the width rule +0.098%, PF 1.59). Plans keep their 1R geometry
     *  everywhere they are displayed; only the alert trades 2R. */
    public static double alertTarget(Scan s) {
        double risk = Math.abs(s.stop - s.entry);
        return s.setup == Setup.ORB_UP ? s.entry + 2 * risk : s.entry - 2 * risk;
    }

    /** The runner target (v50, user decision 2026-10-23: "keep both"):
     *  3R from the entry. The deep-cell study found 3R the sole
     *  survivor among exit variants (positive in both halves capped
     *  and uncapped, plateau to 3.5R, best cost cushion) but the user
     *  chose not to replace 2R with it - both targets now ride on
     *  every ORB alert, both are judged live, and the forward record
     *  decides. The gate, its dedupe key and the primary outcome
     *  record all still key on the 2R target above; 3R is tracked
     *  alongside, never instead. */
    public static double alertTarget3(Scan s) {
        double risk = Math.abs(s.stop - s.entry);
        return s.setup == Setup.ORB_UP ? s.entry + 3 * risk : s.entry - 3 * risk;
    }

    /** Notification-worthiness, part 1: an ORB plan whose confidence
     *  (IraScore) is strictly above minScore, which offers at least
     *  ALERT_MIN_TARGET_POINTS to its target (the user's floor), and
     *  whose opening range is narrow (rangeNarrow). Part 2 is the
     *  VIX confirmation, vixConfirms() below. */
    public static boolean alertWorthy(Scan s, int score, int minScore) {
        if (s == null || !s.hasPlan()) return false;
        if (s.setup != Setup.ORB_UP && s.setup != Setup.ORB_DOWN) return false;
        if (Math.abs(s.target - s.entry) < ALERT_MIN_TARGET_POINTS) return false;
        if (!rangeNarrow(s)) return false;
        return score > minScore;
    }

    /** Notification-worthiness, part 2: the fear gauge must have confirmed
     *  BEFORE the stock moved. Validated 2026-10-23 on 60 days of bars:
     *  only breakdowns (BUY PUT) with India VIX already broken UP clear
     *  the bar - breakout buys won under 46% under every setting tested,
     *  so they never notify. vixBreakTs is the VIX break's epoch second
     *  (same day); it must be > 0 and no later than the stock's break. */
    public static boolean vixConfirms(Setup vixSetup, long vixBreakTs, Scan s) {
        if (s == null || s.setup != Setup.ORB_DOWN) return false;
        return vixSetup == Setup.ORB_UP && vixBreakTs > 0 && vixBreakTs <= s.breakTs;
    }

    /** Closed-bar firing (user rule, 2026-10-23): alerts fire only once
     *  the break bar has CLOSED. Scan bars are 5 minutes; while the bar
     *  is still forming, its close (and so the plan's entry and the
     *  dedupe key built from it) moves tick to tick, which could buzz
     *  the same break repeatedly and fire on breaks that close back
     *  inside the range - neither happens in the backtest. Requiring
     *  the closed bar makes live alerts identical to the backtest. */
    /** Relative volume of the break bar: its volume divided by the
     *  mean volume of the day's earlier bars (the study8 definition).
     *  NaN when it cannot be judged - no earlier bars, or the feed
     *  carries no volume (indices). */
    static double rvolOf(long[] dayVols, int breakIdx) {
        if (dayVols == null || breakIdx <= 0 || breakIdx >= dayVols.length) return Double.NaN;
        long sum = 0;
        for (int k = 0; k < breakIdx; k++) sum += dayVols[k];
        if (sum <= 0) return Double.NaN;
        double mean = sum / (double) breakIdx;
        return dayVols[breakIdx] / mean;
    }

    public static boolean breakBarClosed(Scan s, long nowSec) {
        return s != null && s.breakTs > 0 && nowSec >= s.breakTs + 300;
    }

    /** After the 15:30 equity close, only commodities keep working
     *  (user rule, 2026-10-23): the MCX/global-benchmark session runs
     *  into the night while every stock and index feed is frozen. */
    public static boolean commodityOnlyHours(java.time.LocalTime now) {
        return !now.isBefore(java.time.LocalTime.of(15, 30));
    }

    /** Watchdog pass split (v52): the full engine window is
     *  08:30-24:00 IST; before 08:30 only the gold pass runs. Binance
     *  gold trades 24/7, so the split is by the clock alone - the day
     *  of the week never turns the gold pass off (the NSE calendar
     *  does not apply to it). */
    public static boolean goldOnlyHours(java.time.LocalTime now) {
        return now.isBefore(java.time.LocalTime.of(8, 30));
    }

    /** Where the live price stands against a plan's levels, so a
     *  shared plan is labelled honestly: a breakdown whose LTP is
     *  already under its target is finished, not a fresh signal. */
    public static String planFreshness(Scan s) {
        if (s == null || !s.hasPlan() || Double.isNaN(s.ltp)) return "UNKNOWN";
        if (s.setup == Setup.ORB_DOWN) {
            if (s.ltp <= s.target) return "TARGET CROSSED";
            if (s.ltp >= s.stop) return "STOP CROSSED";
        } else if (s.setup == Setup.ORB_UP) {
            if (s.ltp >= s.target) return "TARGET CROSSED";
            if (s.ltp <= s.stop) return "STOP CROSSED";
        }
        return "LIVE";
    }

    /** One backtested signal: the plan, its day, and how it ended. */
    public static final class TestedSignal {
        public Scan scan;
        public LocalDate day;
        public int outcome;        // +1 target first, -1 stop first, 0 neither (at the plan's 1R target)
        public int outcome2;       // ORB rows only (v50): the same walk judged at the 2R alert exit
        public int outcome3;       // ORB rows only (v50): the same walk judged at the 3R runner exit
        public int score;          // lite IraScore of the plan (ORB rows; 0 on fade rows)
        public double movePts;     // best move available after the entry, points, signal direction
        public boolean alertGrade; // would have passed the notification gates
        public String kind = "ORB"; // "ORB" or "FADE" (gap fade, v42)
    }

    /** Every ORB signal a symbol produced across the chart's history, each
     *  resolved with the same outcome function the live tracker uses - the
     *  Signal Test page's engine. The move is the best excursion available
     *  after the entry (what the trade could actually capture, in points),
     *  and alertGrade applies the live notification gates (confidence
     *  above 65 + the 10-point floor + range width + India VIX already
     *  broken up, breakdowns only; the top-3 daily cap is a notification
     *  rule, not a signal property, so it is not applied). */
    public static java.util.List<TestedSignal> signalHistory(YahooClient.Chart intra, Universe.Sym sym) {
        return signalHistory(intra, sym, null);
    }

    /** vixIntra: the India VIX chart for the confirmation gate; when it is
     *  null nothing is alert-grade. */
    public static java.util.List<TestedSignal> signalHistory(YahooClient.Chart intra, Universe.Sym sym,
                                                             YahooClient.Chart vixIntra) {
        Universe.Sym vixSym = null;
        if (vixIntra != null) {
            for (Universe.Sym u : Universe.all()) if ("^INDIAVIX".equals(u.yahoo)) { vixSym = u; break; }
        }
        java.util.List<TestedSignal> out = new ArrayList<>();
        java.util.Set<LocalDate> days = new java.util.TreeSet<>();
        for (long ts : intra.ts) days.add(Instant.ofEpochSecond(ts).atZone(IST).toLocalDate());
        double prevClose = Double.NaN;
        for (LocalDate d : days) {
            Scan past = scanDayOnly(intra, d, sym);
            if (past != null && past.hasPlan()) {
                TestedSignal t = new TestedSignal();
                t.scan = past;
                t.day = d;
                t.outcome = outcomeOf(intra, past.breakTs, past.stop, past.target,
                        past.setup == Setup.ORB_UP);
                // v50: judge the two alert exits on the same walk, so the
                // Signal Test compares 2R vs 3R over the whole window.
                t.outcome2 = outcomeOf(intra, past.breakTs, past.stop, alertTarget(past),
                        past.setup == Setup.ORB_UP);
                t.outcome3 = outcomeOf(intra, past.breakTs, past.stop, alertTarget3(past),
                        past.setup == Setup.ORB_UP);
                past.prevClose = prevClose;
                past.ltp = past.entry; // scores judge the plan at its break bar
                t.movePts = maxFavorableMove(intra, past);
                t.score = liteScore(past);
                Scan vixDay = vixSym != null ? scanDayOnly(vixIntra, d, vixSym) : null;
                t.alertGrade = alertWorthy(past, t.score, ALERT_MIN_SCORE)
                        && vixDay != null && vixConfirms(vixDay.setup, vixDay.breakTs, past);
                out.add(t);
            }
            Scan fade = fadeDayOnly(intra, d, sym, prevClose);
            if (fade != null) {
                TestedSignal tf = new TestedSignal();
                tf.scan = fade;
                tf.day = d;
                tf.kind = "FADE";
                tf.outcome = outcomeOf(intra, fade.fadeTs, fade.fadeStop, fade.fadeTarget, fade.fadeUp);
                tf.movePts = maxFavorableMoveFade(intra, fade);
                tf.score = 0;
                tf.alertGrade = true; // the fade's own tested form is its gate
                out.add(tf);
            }
            List<Integer> idx = YahooClient.indicesOfDay(intra, d);
            for (int i = idx.size() - 1; i >= 0; i--) {
                double c = intra.close[idx.get(i)];
                if (!Double.isNaN(c)) { prevClose = c; break; }
            }
        }
        return out;
    }

    /** Best move available after the entry, in points, in the signal's
     *  direction: the deepest excursion across the bars after the break
     *  bar, through the day's end. Never negative. */
    static double maxFavorableMove(YahooClient.Chart intra, Scan past) {
        boolean up = past.setup == Setup.ORB_UP;
        double best = 0;
        for (int i = 0; i < intra.ts.length; i++) {
            if (intra.ts[i] <= past.breakTs) continue;
            if (!istDate(intra.ts[i]).equals(istDate(past.breakTs))) continue;
            if (up) {
                if (!Double.isNaN(intra.high[i])) best = Math.max(best, intra.high[i] - past.entry);
            } else {
                if (!Double.isNaN(intra.low[i])) best = Math.max(best, past.entry - intra.low[i]);
            }
        }
        return best;
    }

    /** Best move available after a gap-fade entry, in points, in the
     *  fade's direction (bars after the 09:45 bar, same day). */
    static double maxFavorableMoveFade(YahooClient.Chart intra, Scan f) {
        double best = 0;
        for (int i = 0; i < intra.ts.length; i++) {
            if (intra.ts[i] <= f.fadeTs) continue;
            if (!istDate(intra.ts[i]).equals(istDate(f.fadeTs))) continue;
            if (f.fadeUp) {
                if (!Double.isNaN(intra.high[i])) best = Math.max(best, intra.high[i] - f.fadeEntry);
            } else {
                if (!Double.isNaN(intra.low[i])) best = Math.max(best, f.fadeEntry - intra.low[i]);
            }
        }
        return best;
    }

    // ---------------- IraScore ----------------

    /** Fast 0-100 quality score from price/volume-free factors (fills s.why). */
    public static int liteScore(Scan s) {
        s.why.clear();
        if (s.setup == Setup.OVERSOLD_BOUNCE) {
            s.why.add("Oversold bounce, RSI " + String.format("%.0f", s.rsi));
            return (int) Math.max(35, Math.min(68, 75 - s.rsi));
        }
        if (!s.hasPlan()) {
            switch (s.setup) {
                case ABOVE_20DMA: s.why.add("Uptrend, above 20DMA"); return 45;
                case BELOW_20DMA: s.why.add("Downtrend, below 20DMA"); return 35;
                default: s.why.add("No setup"); return 25;
            }
        }
        boolean up = s.setup == Setup.ORB_UP;
        int score = 25;
        double width = s.orHigh - s.orLow;
        double pen = up ? s.entry - s.orHigh : s.orLow - s.entry;
        double ratio = width > 0 ? pen / width : 0;
        int strength = (int) Math.max(0, Math.min(20, 5 + ratio * 20));
        score += strength;
        if (strength >= 14) s.why.add("Strong break, " + String.format("%.1f", ratio) + "x the range beyond it");
        if (!Double.isNaN(s.dma20) && !Double.isNaN(s.ltp)) {
            boolean aligned = up ? s.ltp > s.dma20 : s.ltp < s.dma20;
            score += aligned ? 18 : 4;
            s.why.add(aligned ? "Trend agrees (20DMA)" : "Against the 20DMA trend");
        } else score += 10;
        if (!Double.isNaN(s.rsi)) {
            double r = up ? s.rsi : 100 - s.rsi;
            if (r >= 50 && r <= 75) { score += 15; s.why.add("RSI in the power zone"); }
            else if (r > 85) { score += 3; s.why.add("RSI stretched - chasing risk"); }
            else if (r > 75) score += 8;
            else score += 9;
        } else score += 8;
        if (!Double.isNaN(s.ltp) && s.ltp > 0 && width > 0) {
            double wPct = width / s.ltp * 100;
            score += (wPct >= 0.2 && wPct <= 1.0) ? 12 : (wPct < 0.2 ? 5 : 7);
        }
        LocalTime bt = Instant.ofEpochSecond(s.breakTs).atZone(IST).toLocalTime();
        if (bt.isBefore(LocalTime.of(10, 30))) { score += 8; s.why.add("Early break - full session ahead"); }
        else if (bt.isBefore(LocalTime.of(13, 0))) score += 5;
        else score += 1;
        return Math.max(0, Math.min(100, score));
    }

    /** Full score once Time Machine odds exist: half lite, half odds quality. */
    public static int fullScore(Scan s, Odds o) {
        int lite = liteScore(s);
        if (o == null) return lite;
        double tm = o.targetFirst * 0.7;
        if (o.analogFires >= 3) {
            int decided = o.analogTarget + o.analogStop;
            tm += decided > 0 ? (o.analogTarget * 30.0 / decided) : 12;
        } else if (o.analogFires > 0) {
            int decided = o.analogTarget + o.analogStop;
            tm += decided > 0 ? (o.analogTarget * 15.0 / decided) + 5 : 10;
        } else tm += 12;
        return Math.max(0, Math.min(100, (int) Math.round(0.5 * lite + 0.5 * tm)));
    }

    // ---------------- market regime ----------------

    /** Returns {label, hint} from the index scans + breadth (% advancing). */
    public static String[] regime(Scan nifty, Scan vix, double breadthPct) {
        if (vix != null && ((!Double.isNaN(vix.ltp) && vix.ltp >= 24)
                || (!Double.isNaN(vix.dayPct) && vix.dayPct >= 10))) {
            return new String[]{"VOLATILE", "Fear is high - smaller size, demand top scores only"};
        }
        if (nifty != null && !Double.isNaN(nifty.dma20) && !Double.isNaN(nifty.ltp)) {
            if (nifty.ltp > nifty.dma20 && breadthPct >= 55) {
                return new String[]{"TREND UP", "Momentum-friendly - breakouts tend to follow through"};
            }
            if (nifty.ltp < nifty.dma20 && breadthPct <= 45) {
                return new String[]{"TREND DOWN", "Weak tape - breakdowns have the wind, longs must earn it"};
            }
        }
        return new String[]{"RANGE / MIXED", "Breakouts fade more here - be picky, prefer score 70+"};
    }
}
