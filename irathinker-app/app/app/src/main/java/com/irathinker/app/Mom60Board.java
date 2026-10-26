package com.irathinker.app;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** The combined mom60 board (v59): one aggregation over the
 *  three per-index paper logs - per-index totals (signals, win
 *  %, average %/trade, total rupees at the index's lot) plus the
 *  DTE-cell split the mom60 DTE study made the family's most
 *  portable fact (cells 0-2 / 3-7 / 8-15 / 16+). Pure: the
 *  Strategies screen feeds it the three logs as loaded and just
 *  renders what comes back.
 *
 *  A record's DTE is DERIVED from its stored entry date and
 *  expiry (both written into the log at fire time) - never from
 *  anything else. A record whose dates are missing or unreadable
 *  still counts in the totals but in no cell, and says so via
 *  cellUnknown so the card can note it when the fraction is
 *  material. Win % and the averages run over CLOSED records,
 *  exactly as the per-index sections compute them. */
public final class Mom60Board {

    /** Cell bounds: [0-2], [3-7], [8-15], [16+]. */
    public static final String[] CELL_LABELS = {"0–2", "3–7", "8–15", "16+"};

    public static final class Idx {
        public String name;
        public int lot;
        public int signals;       // every record in the log
        public int open;          // not yet resolved
        public int closed;
        public int wins;          // closed with pnlPct > 0
        public double avgPct;     // mean pnlPct over closed (0 when none)
        public double totalRs;    // sum of entryPremium x pnlPct% x lot over closed
        public final int[] cellN = new int[4];
        public final double[] cellAvg = new double[4]; // mean pnlPct per cell (0 when empty)
        public int cellUnknown;   // closed records whose DTE could not be derived
    }

    /** The cell index for a DTE, or -1 for a negative count. */
    public static int cellOf(long dte) {
        if (dte < 0) return -1;
        if (dte <= 2) return 0;
        if (dte <= 7) return 1;
        if (dte <= 15) return 2;
        return 3;
    }

    /** A record's DTE from its stored dates, or -1 when either is
     *  missing or unparsable. */
    public static long dteOf(Mom60Store.Rec r) {
        try {
            if (r == null || r.date == null || r.date.isEmpty()
                    || r.expiry == null || r.expiry.isEmpty()) return -1;
            return ChronoUnit.DAYS.between(LocalDate.parse(r.date), LocalDate.parse(r.expiry));
        } catch (Exception e) {
            return -1;
        }
    }

    /** Aggregate one index's log under its config. */
    public static Idx stats(Mom60.Cfg cfg, List<Mom60Store.Rec> recs) {
        Idx x = new Idx();
        x.name = cfg.name;
        x.lot = cfg.lot;
        double pnlSum = 0;
        double[] cellSum = new double[4];
        if (recs != null) {
            for (Mom60Store.Rec r : recs) {
                x.signals++;
                if (!r.closed) { x.open++; continue; }
                x.closed++;
                pnlSum += r.pnlPct;
                if (r.pnlPct > 0) x.wins++;
                x.totalRs += r.entryPremium * r.pnlPct / 100.0 * cfg.lot;
                int c = cellOf(dteOf(r));
                if (c < 0) x.cellUnknown++;
                else { x.cellN[c]++; cellSum[c] += r.pnlPct; }
            }
        }
        x.avgPct = x.closed == 0 ? 0 : pnlSum / x.closed;
        for (int c = 0; c < 4; c++) {
            x.cellAvg[c] = x.cellN[c] == 0 ? 0 : cellSum[c] / x.cellN[c];
        }
        return x;
    }

    /** Whether the DTE-unknown fraction is worth a note on the
     *  card: at least a quarter of the closed records across the
     *  three logs (below that, the split speaks for itself). */
    public static boolean unknownMaterial(Idx... idxs) {
        int closed = 0, unknown = 0;
        for (Idx x : idxs) { closed += x.closed; unknown += x.cellUnknown; }
        return closed > 0 && unknown * 4 >= closed;
    }
}
