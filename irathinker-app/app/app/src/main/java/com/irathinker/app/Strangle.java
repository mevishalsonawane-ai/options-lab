package com.irathinker.app;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** BANKNIFTY positional short strangle - PAPER TRACK (v54). The
 *  study (full_audit phase5 E2, the project's one REAL-CANDIDATE):
 *  every Monday, sell the monthly strangle - CE at ATM+200 and PE
 *  at ATM-200, strikes off the 09:30 decision bar - and hold to
 *  Thursday's close; when the monthly expiry (last Tuesday) falls
 *  inside the Mon-Thu hold, the position instead settles at expiry
 *  at intrinsic value. Study record (chain prints, flat 1.0 cost
 *  in % of premium): pooled +16.42% of premium, win 77.8%, worst
 *  week -285% of premium (expiry-settled). The strategy needs
 *  ~Rs 2.14L of margin - far beyond the user's Rs 1L - so v54 does
 *  NOT trade it and does NOT alert it as a live signal: it paper-
 *  tracks it, exactly mom60's paper-stage pattern, so the forward
 *  record accrues week by week while the strategy stays parked.
 *
 *  This class is the pure core - no Android, no I/O - so the JVM
 *  harness judges it directly. The live differences from the study
 *  are deliberate and stated, mirroring mom60's: the app has no
 *  live BANKNIFTY chain, so entry and exit legs are priced with
 *  Black-Scholes at BANKNIFTY's 20-day realised vol (frozen at
 *  entry, time to expiry shrinking to the exit) instead of chain
 *  prints; the graded result still charges the study's flat 1.0pp
 *  cost. The paper log therefore grades the RULE on a BS tape.
 *
 *  Firing discipline: a Monday entry is valid only inside a narrow
 *  window - the 09:30 decision bar has closed (09:31) and the pass
 *  runs no later than 09:45. A late start skips the week silently;
 *  a strangle entered at 11:00 spot is not the studied trade. One
 *  entry per week, deduped on the Monday date. */
public final class Strangle {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** BANKNIFTY strike step (OptionsMath's index table agrees). */
    public static final int STRIKE_STEP = 100;
    /** The study's wing distance from ATM, points. */
    public static final int OFFSET = 200;
    /** Current BANKNIFTY lot size - the rupee figures' multiplier. */
    public static final int LOT = 30;
    /** The study's flat cost, percentage points of premium,
     *  charged on every graded week. */
    public static final double COST_PP = 1.0;

    /** Study footer carried on every strangle paper alert. */
    public static final String STUDY_LINE =
            "Study (BANKNIFTY monthly short strangle, Mon entry → Thu close, full history): pooled +16.42% of premium · win 77.8% · worst week −285% of premium.";
    /** The capital gate, stated on every alert: this is a paper
     *  track of a strategy the user's account cannot open. */
    public static final String CAPITAL_LINE =
            "Paper only — needs ≈₹2.14L capital to trade. Not a live signal.";

    // ------------------------------------------------------------------
    public static final class Entry {
        public LocalDate monday;
        public long barTs;          // the 09:30 decision bar (epoch sec)
        public long entryTs;        // barTs + 60 (09:31)
        public double spot;         // decision bar close
        public double atmStrike;
        public double ceStrike;     // atm + OFFSET
        public double peStrike;     // atm - OFFSET
        public LocalDate expiry;    // nearest MONTHLY expiry (last Tuesday)
        public LocalDate exitDate;  // Thursday, or the expiry in an expiry week
        public boolean expiryWeek;  // expiry falls inside the Mon-Thu hold
        public double iv;           // BANKNIFTY 20d realised vol, %, frozen at entry
        public double cePremium;    // BS at the entry moment, points
        public double pePremium;
        public double credit;       // cePremium + pePremium, points

        public double beUpper() { return ceStrike + credit; }
        public double beLower() { return peStrike - credit; }
        public double creditRs() { return credit * LOT; }
    }

    public static final class Grade {
        public Entry entry;
        public String reason;       // THU / EXPIRY
        public double exitSpot;
        public double exitCost;     // points to buy the strangle back (or settle)
        public double grossPct;     // % of premium kept, before cost
        public double netPct;       // grossPct - COST_PP
        public double pnlRs;        // netPct on the credit, at LOT
    }

    // ------------------------------------------------------------------
    // IST clock helpers (Mom60's shapes, kept local to this class).
    public static long atEpoch(LocalDate d, int h, int m) {
        return ZonedDateTime.of(d, LocalTime.of(h, m), IST).toEpochSecond();
    }

    /** The Monday 09:30 bar's start - the study's decision bar. */
    public static long decisionBarEpoch(LocalDate monday) { return atEpoch(monday, 9, 30); }

    /** 09:31: the decision bar has closed; the entry moment. */
    public static long entryEpoch(LocalDate monday) { return atEpoch(monday, 9, 31); }

    /** 09:45: the last moment a Monday entry is still the studied
     *  trade. A pass after this skips the week. */
    public static long lateEpoch(LocalDate monday) { return atEpoch(monday, 9, 45); }

    /** A date's 15:30 IST close. */
    public static long closeEpoch(LocalDate date) { return atEpoch(date, 15, 30); }

    /** The Monday entry window: the day is a Monday, the decision
     *  bar has closed, and it is not yet past 09:45. */
    public static boolean entryWindowOpen(LocalDate day, long nowSec) {
        return day != null && day.getDayOfWeek() == DayOfWeek.MONDAY
                && nowSec >= entryEpoch(day) && nowSec <= lateEpoch(day);
    }

    /** A held week is gradable once its exit day's close has printed. */
    public static boolean resolutionDue(Entry e, long nowSec) {
        return e != null && nowSec >= closeEpoch(e.exitDate);
    }

    // ------------------------------------------------------------------
    /** Build Monday's entry off the tape's 09:30 decision bar: ATM
     *  off that bar's close (100-point grid), legs at ±200, monthly
     *  expiry, both legs BS-priced at the 09:31 entry moment with
     *  the frozen IV. Null when the day is not a Monday, the bar is
     *  missing from the tape, or the credit cannot be priced - a
     *  week that cannot be entered exactly as studied is skipped,
     *  never approximated. */
    public static Entry buildEntry(YahooClient.Chart ch, LocalDate monday, double ivPct) {
        if (ch == null || monday == null || monday.getDayOfWeek() != DayOfWeek.MONDAY) return null;
        if (Double.isNaN(ivPct) || ivPct <= 0) return null;
        long barTs = decisionBarEpoch(monday);
        int idx = Mom60.indexOfTs(ch, barTs);
        if (idx < 0) return null;
        double spot = ch.close[idx];
        if (Double.isNaN(spot) || spot <= 0) return null;
        Entry e = new Entry();
        e.monday = monday;
        e.barTs = barTs;
        e.entryTs = barTs + 60;
        e.spot = spot;
        e.atmStrike = OptionsMath.atmStrike(spot, STRIKE_STEP);
        e.ceStrike = e.atmStrike + OFFSET;
        e.peStrike = e.atmStrike - OFFSET;
        e.expiry = OptionsMath.monthlyExpiry(monday);
        LocalDate thu = monday.plusDays(3);
        e.expiryWeek = !e.expiry.isAfter(thu);
        e.exitDate = e.expiryWeek ? e.expiry : thu;
        e.iv = ivPct;
        e.cePremium = Mom60.priceAt(spot, e.ceStrike, true, ivPct, e.entryTs, e.expiry);
        e.pePremium = Mom60.priceAt(spot, e.peStrike, false, ivPct, e.entryTs, e.expiry);
        if (Double.isNaN(e.cePremium) || Double.isNaN(e.pePremium)) return null;
        e.credit = e.cePremium + e.pePremium;
        if (e.credit <= 0) return null;
        return e;
    }

    /** The spot an exit is graded on: the exit day's last usable
     *  close on the tape; when the exit day itself has no bars (a
     *  holiday-shifted Thursday), the last usable close on or
     *  before the exit day's close. NaN when the tape holds nothing
     *  usable at or after the entry - the week then stays open
     *  until the tape can price it or ages out (the store's
     *  bookkeeping-flat rule). */
    public static double exitSpotFor(YahooClient.Chart ch, Entry e) {
        if (ch == null || e == null) return Double.NaN;
        double spot = Double.NaN;
        for (int idx : YahooClient.indicesOfDay(ch, e.exitDate)) {
            double c = ch.close[idx];
            if (!Double.isNaN(c) && c > 0) spot = c;
        }
        if (!Double.isNaN(spot)) return spot;
        long limit = closeEpoch(e.exitDate);
        for (int i = 0; i < ch.ts.length; i++) {
            if (ch.ts[i] > limit) break;
            if (ch.ts[i] < e.entryTs) continue;
            double c = ch.close[i];
            if (!Double.isNaN(c) && c > 0) spot = c;
        }
        return spot;
    }

    /** Grade a held week at its exit spot. Expiry weeks settle at
     *  intrinsic value against the expiry close; normal weeks buy
     *  the legs back at BS on the exit day's close (frozen IV, time
     *  to expiry shrunk to that moment). The graded result charges
     *  the study's flat 1.0pp cost on top of the gross. */
    public static Grade grade(Entry e, double exitSpot) {
        if (e == null || Double.isNaN(exitSpot) || exitSpot <= 0 || e.credit <= 0) return null;
        double cost;
        if (e.expiryWeek) {
            cost = Math.max(0, exitSpot - e.ceStrike) + Math.max(0, e.peStrike - exitSpot);
        } else {
            long at = closeEpoch(e.exitDate);
            double ce = Mom60.priceAt(exitSpot, e.ceStrike, true, e.iv, at, e.expiry);
            double pe = Mom60.priceAt(exitSpot, e.peStrike, false, e.iv, at, e.expiry);
            if (Double.isNaN(ce) || Double.isNaN(pe)) return null;
            cost = ce + pe;
        }
        Grade g = new Grade();
        g.entry = e;
        g.reason = e.expiryWeek ? "EXPIRY" : "THU";
        g.exitSpot = exitSpot;
        g.exitCost = cost;
        g.grossPct = (e.credit - cost) / e.credit * 100;
        g.netPct = g.grossPct - COST_PP;
        g.pnlRs = g.netPct / 100.0 * e.credit * LOT;
        return g;
    }

    // ------------------------------------------------------------------
    // Information flags (v59): two candidate-filter readings the
    // Monday alert carries AFTER its plan. They are information
    // only - paper-grade candidate filters (the PCR-OI Monday
    // ranker and the S9 aftershock stand-aside) - and they never
    // change whether or when the alert fires: the engine computes
    // them from data already in hand at fire time and posts the
    // alert regardless of what they say.

    /** 20-day stdev of daily log returns (a fraction, not %):
     *  the population stdev of the last 20 close-to-close log
     *  returns on the daily chart - realizedVol()'s window and
     *  convention, un-annualised. NaN when the chart holds fewer
     *  than 21 usable closes. */
    public static double sigma20(YahooClient.Chart daily) {
        if (daily == null) return Double.NaN;
        java.util.List<Double> closes = new java.util.ArrayList<>();
        for (double c : daily.close) if (!Double.isNaN(c) && c > 0) closes.add(c);
        int n = closes.size();
        if (n < 21) return Double.NaN;
        double[] rets = new double[20];
        double mean = 0;
        for (int i = 0; i < 20; i++) {
            rets[i] = Math.log(closes.get(n - 20 + i) / closes.get(n - 21 + i));
            mean += rets[i];
        }
        mean /= 20;
        double var = 0;
        for (double r : rets) var += (r - mean) * (r - mean);
        return Math.sqrt(var / 20);
    }

    /** The S9 aftershock reading: 1 when either of the prior two
     *  sessions' |daily return| reached 2 sigma (the 20d stdev
     *  above - the same window the flagged returns sit in), 0
     *  when neither did, -1 when the daily tape cannot judge. */
    public static int aftershockFlag(YahooClient.Chart daily) {
        double sig = sigma20(daily);
        if (Double.isNaN(sig) || sig <= 0) return -1;
        java.util.List<Double> closes = new java.util.ArrayList<>();
        for (double c : daily.close) if (!Double.isNaN(c) && c > 0) closes.add(c);
        int n = closes.size();
        double r1 = Math.log(closes.get(n - 1) / closes.get(n - 2));
        double r2 = Math.log(closes.get(n - 2) / closes.get(n - 3));
        return (Math.abs(r1) >= 2 * sig || Math.abs(r2) >= 2 * sig) ? 1 : 0;
    }

    /** The two flag lines plus their standing disclaimer, exactly
     *  as appended to the Monday alert. pcr is the morning's NSE
     *  chain probe for BANKNIFTY (null = the chain did not
     *  answer); daily is the daily chart the entry already
     *  fetched for its IV. */
    public static String infoLines(NseChain.Probe p, YahooClient.Chart daily) {
        String pcr = (p != null && !Double.isNaN(p.pcr) && p.pcr > 0)
                ? String.format(Locale.US, "PCR (chain): %.2f", p.pcr)
                : "PCR: no answer from the chain this morning";
        int af = aftershockFlag(daily);
        String watch = af > 0
                ? "Aftershock watch: YES — a ≥2σ BANKNIFTY day within the last 2 sessions"
                : af == 0 ? "Aftershock watch: no"
                : "Aftershock watch: no daily data to judge this morning";
        return pcr + "\n" + watch + "\n"
                + "Information only — candidate filters, paper grade; they never change this alert.";
    }

    // ------------------------------------------------------------------
    // Alert text (pure, so the harness can hold it to account).
    public static String title(Entry e) {
        return "PAPER STRANGLE · BANKNIFTY";
    }

    public static String body(Entry e) {
        DateTimeFormatter df = DateTimeFormatter.ofPattern("EEE d MMM");
        String exit = e.expiryWeek
                ? "settles at expiry " + e.expiry.format(df) + " (intrinsic value — expiry week)"
                : "Thu close " + e.exitDate.format(df) + " (repriced on the BS tape)";
        return String.format(Locale.US,
                "BANKNIFTY %s · Mon %s · monthly expiry %s%n"
                        + "SELL %.0f CE / SELL %.0f PE · ATM ±200%n"
                        + "Premium collected ~%.1f pts (₹%,.0f at 1 lot × %d) · IV ~%.0f%%%n"
                        + "Breakevens %s – %s%n"
                        + "Exit: %s — paper-logged in Strategies; one message only, no follow-up.%n%s%n%s%n"
                        + "Premium is a BS estimate — check the live chain before ordering.",
                Mom60.fmt(e.spot), e.monday.format(df), e.expiry.format(df),
                e.ceStrike, e.peStrike,
                e.credit, e.creditRs(), LOT, e.iv,
                Mom60.fmt(e.beLower()), Mom60.fmt(e.beUpper()),
                exit, STUDY_LINE, CAPITAL_LINE);
    }
}
