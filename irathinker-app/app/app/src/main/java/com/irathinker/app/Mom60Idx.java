package com.irathinker.app;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** mom60 paper tracks for FINNIFTY and Sensex (v55) - the alert
 *  text and study footers for the two indices the user added to
 *  the mom60 family on Oct 25, 2026 ("Both FINNIFTY + Sensex
 *  mom60"). The mechanics are Mom60's, parameterised by
 *  Mom60.Cfg; this class is deliberately pure (no Android, no
 *  I/O, no payload builders of any kind) so the harness can pin
 *  the text and hold the System-Feed exclusion to account.
 *
 *  Both are PAPER tracks in the v53/v54 sense: a human ntfy
 *  alert and a per-index paper log - plus, since v57 (the user's
 *  standing all-topics rule, decided 2026-10-25), a paper:true
 *  MOM60 JSON mirror on the System Feed. The payload builder
 *  lives in SystemFeed; this class stays pure text, exactly as
 *  before.
 *
 *  The studies behind the footers: FINNIFTY - the deep dig's
 *  verbatim mom60 replication on the FINNIFTY monthly chain
 *  (q90 0.5025% on train), pooled +2.33%/trade, PF 1.40, n=713,
 *  all three splits positive, the BANKNIFTY DTE 3-7 sweet spot
 *  replicating at +6.42% (workspace/deep_dig/ws1). Sensex - the
 *  Sensex batch's verbatim run on the BSE monthly chain (q90
 *  0.4735% on train), pooled +2.03%/trade, PF 1.31, n=593, all
 *  three splits positive (workspace/sensex_lab). Both price off
 *  a Black-Scholes tape at the index's own 20d realised vol,
 *  exactly as the BANKNIFTY original does - the paper log grades
 *  the rule, not real fills. */
public final class Mom60Idx {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** Study footer carried on every FINNIFTY mom60 paper alert. */
    public static final String STUDY_LINE_FIN =
            "Study (FINNIFTY mom60 + 30% stop, full history): pooled +2.33% per trade · PF 1.40 · 713 trades · win ~50% · DTE 3–7 +6.42%.";

    /** Study footer carried on every Sensex mom60 paper alert. */
    public static final String STUDY_LINE_SENSEX =
            "Study (Sensex mom60 + 30% stop, full history): pooled +2.03% per trade · PF 1.31 · 593 trades · win ~44%.";

    /** The paper-stage line, stated on every v55 alert (the
     *  strangle's capital line is its own; these two strategies
     *  are paper because they are at the paper stage, not because
     *  of a margin gate). */
    public static final String PAPER_LINE =
            "Paper track — paper-logged only. Not a live signal.";

    // ------------------------------------------------------------------
    /** Rupees of one paper trade: the premium move (entry premium
     *  x P&L%) at one lot of the signal's index. The display
     *  multiplier lives on the config (FIN 60, Sensex 20). */
    public static double paperRs(Mom60.Signal s, double pnlPct) {
        return s.entryPremium * pnlPct / 100.0 * s.cfg.lot;
    }

    // ------------------------------------------------------------------
    // Alert text (pure, so the harness can hold it to account).
    // Mirrors the BANKNIFTY mom60 format field for field, with the
    // index name, its own study footer, the paper line and the
    // per-lot rupee figure the v53 BN text predates.
    public static String title(Mom60.Signal s) {
        return "PAPER MOM60 " + (s.call ? "▲" : "▼") + " " + s.cfg.name;
    }

    public static String body(Mom60.Signal s) {
        String t = DateTimeFormatter.ofPattern("HH:mm").withZone(IST)
                .format(java.time.Instant.ofEpochSecond(s.exitTs));
        String exp = s.expiry.format(DateTimeFormatter.ofPattern("EEE d MMM"));
        return String.format(Locale.US,
                "%s %s · 60-min move %+.2f%%%n"
                        + "BUY %.0f %s · ATM · monthly expiry %s%n"
                        + "%s%n"
                        + "Premium ~₹%.1f (Black-Scholes, IV ~%.0f%%) · stop ₹%.1f (−30%%) · ₹%,.0f per lot × %d at entry%n"
                        + "Exit by %s IST (60-min hold) — one message only, no follow-up; the trade is paper-logged in Strategies.%n%s%n%s%n"
                        + "Premium is a BS estimate — check the live chain before ordering.",
                s.cfg.name, Mom60.fmt(s.spot), s.ret60,
                s.strike, s.call ? "CE" : "PE", exp,
                Mom60.dteLine(s),
                s.entryPremium, s.iv, s.stopPremium,
                s.entryPremium * s.cfg.lot, s.cfg.lot,
                t, s.cfg.studyLine, PAPER_LINE);
    }
}
