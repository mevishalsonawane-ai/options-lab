package com.irathinker.app;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** BANKNIFTY mom60 (v53) - the one BANKNIFTY options-buying rule that
 *  survived the full-history search and the stop refinement
 *  (bn_lab/bn_buy.py + bn_refine.py): when BANKNIFTY spot's trailing
 *  60-minute return reaches the top-decile size of its train history
 *  (|ret60| >= 0.50% - the study's q90, frozen here as a fixed live
 *  rule), buy the ATM option in the move's direction, hold 60 minutes,
 *  and exit early if the premium falls 30% from entry. Study record
 *  (monthly chain, entry at the next minute's open, 0.7% cost
 *  haircut): train +2.29% / val +0.99% / test +4.75% per trade after
 *  the 30% stop, win ~50%, ~1-2 trades a day.
 *
 *  This class is the pure core - no Android, no I/O - so the JVM
 *  harness judges it directly. The live differences from the study
 *  are deliberate and stated: the entry premium is a Black-Scholes
 *  estimate at the signal bar's close (no live chain feed), the ATM
 *  strike is spot rounded to the 100-point step (the study read the
 *  chain's own ATM strike), and repricing through the hold walks the
 *  same BS inputs as time to expiry shrinks. The paper log therefore
 *  grades the RULE on a BS tape, not real fills.
 *
 *  Firing discipline mirrors the equity gate: closed 1-minute bars
 *  only, one position at a time, entries spaced at least the hold
 *  apart (the study's non-overlap rule), and no entry whose full
 *  60-minute hold would run past the 15:30 close - the study's spot
 *  series itself ended at 14:29, so the last entry is 14:30.
 *
 *  v55 generalisation: the watcher is parameterised by a per-index
 *  Cfg (threshold, strike step, expiry rule, lot, study line). The
 *  BANKNIFTY config is the original constants verbatim and every
 *  code path it takes is unchanged - same threshold, same strike
 *  rounding, same expiry call, same alert text. FINNIFTY and
 *  Sensex run the identical mechanics under their own frozen
 *  study numbers (Mom60Idx carries their alert text). */
public final class Mom60 {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** The study's train q90 of |ret60|, frozen as the live threshold. */
    public static final double THRESHOLD_PCT = 0.50;
    /** Hold length: 60 minutes, as tested. */
    public static final int HOLD_SEC = 3600;
    /** Premium stop: exit when the premium falls to 70% of entry. */
    public static final double STOP_MULT = 0.70;
    /** BANKNIFTY strike step (OptionsMath's index table agrees). */
    public static final int STRIKE_STEP = 100;
    /** Entries at least one hold apart (the study's spacing rule). */
    public static final long ENTRY_SPACING_SEC = 3600;
    /** A live fire must be fresh: the signal bar closed within this
     *  many seconds of now. Older triggers found while catching up
     *  (service restart, bulk bar walk) are consumed silently - a
     *  stale momentum signal is not a signal. */
    public static final long MAX_SIGNAL_AGE_SEC = 180;

    /** Study footer carried on every mom60 alert. */
    public static final String STUDY_LINE =
            "Study (BANKNIFTY mom60 + 30% stop, full history): train +2.29% · val +0.99% · test +4.75% per trade · win ~50% · ~1–2 signals/day.";

    // ------------------------------------------------------------------
    /** Per-index mom60 configuration (v55). Only what genuinely
     *  differs between indices lives here - the threshold each
     *  study froze, the strike step, which expiry rule the
     *  exchange uses, the lot size behind the rupee display, and
     *  the study footer. Hold, stop, spacing, freshness and the
     *  firing discipline are shared constants above, identical
     *  for every index. */
    public static final class Cfg {
        public final String name;        // display name: BANKNIFTY
        public final String yahoo;       // spot symbol: ^NSEBANK
        public final double thresholdPct;
        public final int strikeStep;
        public final int lot;
        public final boolean bseExpiry;  // Sensex: last-Thursday rule
        public final String studyLine;

        Cfg(String name, String yahoo, double thresholdPct, int strikeStep,
            int lot, boolean bseExpiry, String studyLine) {
            this.name = name;
            this.yahoo = yahoo;
            this.thresholdPct = thresholdPct;
            this.strikeStep = strikeStep;
            this.lot = lot;
            this.bseExpiry = bseExpiry;
            this.studyLine = studyLine;
        }

        /** The nearest MONTHLY expiry under this index's rule. */
        public LocalDate expiry(LocalDate today) {
            return bseExpiry ? OptionsMath.monthlyExpiryBse(today)
                    : OptionsMath.monthlyExpiry(today);
        }
    }

    /** BANKNIFTY - the v53 original: the class constants above ARE
     *  this config (0.50% frozen q90, 100-point step, last-Tuesday
     *  monthly, lot 30 in the v53 display), so BN behaviour is the
     *  reference the other two are mirrored from. */
    public static final Cfg BN = new Cfg("BANKNIFTY", "^NSEBANK",
            THRESHOLD_PCT, STRIKE_STEP, 30, false, STUDY_LINE);

    /** FINNIFTY (v55 paper track): threshold frozen at the deep-dig
     *  study's train q90 0.5025% as the same 0.50% live rule BN
     *  uses; 50-point strike step (OptionsMath's index table
     *  agrees); NSE last-Tuesday monthly; lot 60. */
    public static final Cfg FIN = new Cfg("FINNIFTY", "^CNXFIN",
            0.50, 50, 60, false, Mom60Idx.STUDY_LINE_FIN);

    /** Sensex (v55 paper track): threshold frozen at the Sensex
     *  batch study's train q90 0.4735% as a 0.47% live rule;
     *  100-point strike step; BSE last-Thursday monthly (verified
     *  in OptionsMath.monthlyExpiryBse); lot 20. */
    public static final Cfg SENSEX = new Cfg("SENSEX", "^BSESN",
            0.47, 100, 20, true, Mom60Idx.STUDY_LINE_SENSEX);

    // ------------------------------------------------------------------
    public static final class Signal {
        public Cfg cfg = BN;      // the index this signal belongs to
        public long barTs;        // signal bar start (epoch sec)
        public long entryTs;      // barTs + 60: the bar has closed
        public long exitTs;       // entryTs + HOLD_SEC
        public LocalDate date;
        public boolean call;      // up move -> CE, down move -> PE
        public double ret60;      // signed triggering move, %
        public double spot;       // signal bar close
        public double strike;     // ATM, 100-point step
        public LocalDate expiry;  // nearest MONTHLY expiry (study used monthly contracts)
        public double iv;         // BANKNIFTY 20d realised vol, %, frozen for the hold
        public double entryPremium;
        public double stopPremium; // entryPremium * STOP_MULT
    }

    public static final class Resolution {
        public Signal signal;
        public String reason;     // STOP / TIME / CLOSE
        public long exitBarTs;
        public double exitPremium;
        public double pnlPct;     // % vs entry premium (no cost haircut - see class doc)
    }

    public static final class Event {
        public Signal fired;          // non-null on a FIRE event
        public Resolution resolved;   // non-null on a RESOLVE event
        static Event fire(Signal s) { Event e = new Event(); e.fired = s; return e; }
        static Event resolve(Resolution r) { Event e = new Event(); e.resolved = r; return e; }
    }

    // ------------------------------------------------------------------
    /** Trailing 60-minute return (%) at bar i of a 1-minute chart:
     *  close[i] vs the close of the bar that started exactly 3600s
     *  earlier. NaN when that bar is missing (day start, feed gap) or
     *  either close is missing - a gap in the tape is not a move. */
    public static double ret60At(YahooClient.Chart ch, int i) {
        if (ch == null || i < 0 || i >= ch.ts.length) return Double.NaN;
        double now = ch.close[i];
        if (Double.isNaN(now) || now <= 0) return Double.NaN;
        int j = indexOfTs(ch, ch.ts[i] - 3600);
        if (j < 0) return Double.NaN;
        double then = ch.close[j];
        if (Double.isNaN(then) || then <= 0) return Double.NaN;
        return (now / then - 1) * 100;
    }

    static int indexOfTs(YahooClient.Chart ch, long ts) {
        int lo = 0, hi = ch.ts.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (ch.ts[mid] < ts) lo = mid + 1;
            else if (ch.ts[mid] > ts) hi = mid - 1;
            else return mid;
        }
        return -1;
    }

    /** Fractional days from a moment to the expiry's 15:30 IST close. */
    public static double daysToExpiryFrac(long atSec, LocalDate expiry) {
        long expSec = ZonedDateTime.of(expiry, LocalTime.of(15, 30), IST).toEpochSecond();
        return Math.max(0, expSec - atSec) / 86400.0;
    }

    /** BS premium of a contract at a moment in its life. */
    public static double priceAt(double spot, double strike, boolean call, double ivPct,
                                 long atSec, LocalDate expiry) {
        return OptionsMath.bsPriceDays(spot, strike, daysToExpiryFrac(atSec, expiry), ivPct, call);
    }

    /** Build the signal for a closed bar: ATM strike off the bar's
     *  close, monthly expiry, BS entry premium at the entry moment
     *  (the bar's close time), stop at 70% of it. */
    public static Signal buildSignal(long barTs, boolean call, double ret60, double spot,
                                     LocalDate today, double ivPct) {
        return buildSignal(BN, barTs, call, ret60, spot, today, ivPct);
    }

    /** The same build under an index config (v55): the strike
     *  rounds at the config's step and the expiry follows the
     *  config's exchange rule; every other input is shared. For
     *  the BN config this is byte-identical to the original -
     *  BN.strikeStep is STRIKE_STEP and BN.expiry() is
     *  OptionsMath.monthlyExpiry. */
    public static Signal buildSignal(Cfg cfg, long barTs, boolean call, double ret60,
                                     double spot, LocalDate today, double ivPct) {
        Signal s = new Signal();
        s.cfg = cfg;
        s.barTs = barTs;
        s.entryTs = barTs + 60;
        s.exitTs = s.entryTs + HOLD_SEC;
        s.date = today;
        s.call = call;
        s.ret60 = ret60;
        s.spot = spot;
        s.strike = OptionsMath.atmStrike(spot, cfg.strikeStep);
        s.expiry = cfg.expiry(today);
        s.iv = ivPct;
        s.entryPremium = priceAt(spot, s.strike, call, ivPct, s.entryTs, s.expiry);
        s.stopPremium = s.entryPremium * STOP_MULT;
        return s;
    }

    /** Epoch second of a date's 15:30 IST close. */
    public static long closeEpoch(LocalDate date) {
        return ZonedDateTime.of(date, LocalTime.of(15, 30), IST).toEpochSecond();
    }

    /** Epoch second of a date's 14:30 IST - the last entry whose full
     *  hold still ends by the close. */
    public static long lastEntryEpoch(LocalDate date) {
        return ZonedDateTime.of(date, LocalTime.of(14, 30), IST).toEpochSecond();
    }

    // ------------------------------------------------------------------
    /** The stateful watcher for one trading day. State in, events
     *  out; the engine rebuilds it from the paper log on every pass:
     *  an open signal (or null), the last entry time (spacing), and
     *  the last bar already walked (dedupe - a bar is judged once). */
    public static final class Watcher {
        public final Cfg cfg;
        public Signal open;
        public long lastEntryTs;
        public long lastProcessedBarTs;

        public Watcher(Signal open, long lastEntryTs, long lastProcessedBarTs) {
            this(BN, open, lastEntryTs, lastProcessedBarTs);
        }

        public Watcher(Cfg cfg, Signal open, long lastEntryTs, long lastProcessedBarTs) {
            this.cfg = cfg;
            this.open = open;
            this.lastEntryTs = lastEntryTs;
            this.lastProcessedBarTs = lastProcessedBarTs;
        }

        /** Walk the day's closed bars in order. Bars at or before
         *  lastProcessedBarTs are skipped; the first unclosed bar
         *  stops the walk (nothing later can be closed either).
         *  canFire=false still resolves an open position but never
         *  opens a new one (alerts switched off, catch-up pass over
         *  a past day). ivPct is only consulted for a new fire; an
         *  open position reprices on its own frozen IV. */
        public List<Event> process(YahooClient.Chart ch, LocalDate date, long nowSec,
                                   boolean canFire, double ivPct) {
            List<Event> out = new ArrayList<>();
            if (ch == null) return out;
            boolean ivOk = !Double.isNaN(ivPct) && ivPct > 0;
            for (int idx : YahooClient.indicesOfDay(ch, date)) {
                long ts = ch.ts[idx];
                if (ts <= lastProcessedBarTs) continue;
                if (ts + 60 > nowSec) break; // bar not closed yet
                lastProcessedBarTs = ts;
                double close = ch.close[idx];
                if (open != null) {
                    if (Double.isNaN(close) || close <= 0) continue;
                    double prem = priceAt(close, open.strike, open.call, open.iv, ts + 60, open.expiry);
                    if (Double.isNaN(prem)) continue;
                    // Stop first: when the exit bar is also the stop
                    // bar, the study's walk books the stop.
                    if (prem <= open.stopPremium) {
                        out.add(Event.resolve(resolve(open, "STOP", ts, prem)));
                    } else if (ts + 60 >= open.exitTs) {
                        out.add(Event.resolve(resolve(open, "TIME", ts, prem)));
                    }
                    continue;
                }
                if (!canFire || !ivOk) continue;
                if (ts + 60 > lastEntryEpoch(date)) continue;         // full hold must fit
                if (nowSec - (ts + 60) > MAX_SIGNAL_AGE_SEC) continue; // stale trigger: consume only
                if (lastEntryTs > 0 && ts + 60 - lastEntryTs < ENTRY_SPACING_SEC) continue;
                double r = ret60At(ch, idx);
                if (Double.isNaN(r) || Math.abs(r) < cfg.thresholdPct) continue;
                Signal s = buildSignal(cfg, ts, r > 0, r, close, date, ivPct);
                if (Double.isNaN(s.entryPremium) || s.entryPremium <= 0) continue;
                open = s;
                lastEntryTs = s.entryTs;
                out.add(Event.fire(s));
            }
            return out;
        }

        /** Close a still-open position at the day's last usable bar -
         *  the data ended without a stop or a timed exit printing.
         *  Returns null when nothing is open or no bar can price it
         *  (the position then stays open on the record). */
        public Resolution forceClose(YahooClient.Chart ch, LocalDate date) {
            if (open == null || ch == null) return null;
            List<Integer> day = YahooClient.indicesOfDay(ch, date);
            for (int k = day.size() - 1; k >= 0; k--) {
                int idx = day.get(k);
                double close = ch.close[idx];
                if (Double.isNaN(close) || close <= 0) continue;
                double prem = priceAt(close, open.strike, open.call, open.iv, ch.ts[idx] + 60, open.expiry);
                if (Double.isNaN(prem)) continue;
                return resolve(open, "CLOSE", ch.ts[idx], prem);
            }
            return null;
        }

        private Resolution resolve(Signal s, String reason, long exitBarTs, double exitPremium) {
            Resolution r = new Resolution();
            r.signal = s;
            r.reason = reason;
            r.exitBarTs = exitBarTs;
            r.exitPremium = exitPremium;
            r.pnlPct = (exitPremium / s.entryPremium - 1) * 100;
            if (open == s) open = null;
            return r;
        }
    }

    // ------------------------------------------------------------------
    // Alert text (pure, so the harness can hold it to account).
    // The BN text below is the v53 original, unchanged; the v55
    // indices carry the PAPER-labelled text in Mom60Idx.
    public static String title(Signal s) {
        if (s.cfg != BN) return Mom60Idx.title(s);
        return "MOM60 " + (s.call ? "▲" : "▼") + " BANKNIFTY";
    }

    /** Calendar days from the signal date to the contract
     *  expiry - the DTE cell the mom60 DTE study split on. -1
     *  when either date is missing (never on a built signal). */
    public static long dteOf(Signal s) {
        if (s == null || s.date == null || s.expiry == null) return -1;
        return java.time.temporal.ChronoUnit.DAYS.between(s.date, s.expiry);
    }

    /** The standing warning for the low-DTE cell (v59): the DTE
     *  study found mom60's edge collapses at DTE 0-2 - the alert
     *  states it on the day, in the study's own framing. */
    public static final String LOW_DTE_WARNING =
            "Low-DTE cell — this is mom60's weakest cell in the study; paper evidence only.";

    /** The DTE line every mom60 alert carries (v59): the count,
     *  plus the standing warning when the signal sits in the
     *  0-2 cell. Empty only when the dates are missing. */
    public static String dteLine(Signal s) {
        long d = dteOf(s);
        if (d < 0) return "";
        return "DTE " + d + " to expiry." + (d <= 2 ? "\n" + LOW_DTE_WARNING : "");
    }

    public static String body(Signal s) {
        if (s.cfg != BN) return Mom60Idx.body(s);
        String t = DateTimeFormatter.ofPattern("HH:mm").withZone(IST)
                .format(java.time.Instant.ofEpochSecond(s.exitTs));
        String exp = s.expiry.format(DateTimeFormatter.ofPattern("EEE d MMM"));
        return String.format(Locale.US,
                "BANKNIFTY %s · 60-min move %+.2f%%%n"
                        + "BUY %.0f %s · ATM · monthly expiry %s%n"
                        + "%s%n"
                        + "Premium ~₹%.1f (Black-Scholes, IV ~%.0f%%) · stop ₹%.1f (−30%%)%n"
                        + "Exit by %s IST (60-min hold) — one message only, no follow-up; the trade is paper-logged in Strategies.%n%s%n"
                        + "Premium is a BS estimate — check the live chain before ordering.",
                fmt(s.spot), s.ret60,
                s.strike, s.call ? "CE" : "PE", exp,
                dteLine(s),
                s.entryPremium, s.iv, s.stopPremium,
                t, STUDY_LINE);
    }

    /** MainActivity.fmt's shape (%,.2f), kept local so this class
     *  stays Android-free for the harness. */
    static String fmt(double v) {
        return Double.isNaN(v) ? "-" : String.format(Locale.US, "%,.2f", v);
    }
}
