package com.irathinker.app;

import java.util.Locale;

/** Our own bull/bear power gauge (user request, 2026-10-23: a
 *  GOC-style meter inside the app). NOT GOC Technology's meter -
 *  their formula is proprietary and their readings have no feed.
 *  This is the standard chain-based reconstruction, ingredients
 *  printed on the line so it can be judged:
 *    50% OI added today - gross changeinOpenInterest per side over
 *        the nearest expiry; put-side additions score bullish (put
 *        writers underwrite the fall), call-side bearish.
 *    25% PCR level - total PE OI / total CE OI, squashed pcr/(1+pcr).
 *    25% day momentum - the underlying's move today, percent.
 *  Pure and harness-tested. Display only, never an alert gate: the
 *  2026-10-23 backtest of this direction family on expiry afternoons
 *  found no edge - it informs, it does not decide. */
public final class BullBear {

    /** Share of today's gross OI additions sitting on the put side. */
    public static double oiScore(double addCe, double addPe) {
        double a = Math.max(addCe, 0), b = Math.max(addPe, 0);
        return a + b <= 0 ? 0.5 : b / (a + b);
    }

    /** PCR squashed to 0..1; 1.0 maps to 0.5. */
    public static double pcrScore(double pcr) {
        if (Double.isNaN(pcr) || pcr < 0) return 0.5;
        return pcr / (1 + pcr);
    }

    /** Day move in percent mapped to 0..1; +/-2.5% saturates. */
    public static double momScore(double dayMovePct) {
        if (Double.isNaN(dayMovePct)) return 0.5;
        double v = 0.5 + 0.20 * dayMovePct;
        return Math.max(0.03, Math.min(0.97, v));
    }

    /** Bull power, 1..99 (bear power is 100 - bull). */
    public static int bullPower(double addCe, double addPe, double pcr, double dayMovePct) {
        double b = 0.50 * oiScore(addCe, addPe)
                 + 0.25 * pcrScore(pcr)
                 + 0.25 * momScore(dayMovePct);
        int pct = (int) Math.round(b * 100);
        return Math.max(1, Math.min(99, pct));
    }

    public static String verdict(int bull) {
        if (bull >= 60) return "Bulls in control";
        if (bull <= 40) return "Bears in control";
        return "Balanced fight";
    }

    private static String oi(double contracts) {
        if (contracts >= 100_000) return String.format(Locale.US, "%.1fL", contracts / 100_000.0);
        if (contracts >= 1_000) return String.format(Locale.US, "%.1fk", contracts / 1_000.0);
        return String.format(Locale.US, "%.0f", contracts);
    }

    /** The one-line gauge, with its ingredients, for the Detail card
     *  and the alert ticket. */
    public static String line(double addCe, double addPe, double pcr, double dayMovePct) {
        int bull = bullPower(addCe, addPe, pcr, dayMovePct);
        String day = Double.isNaN(dayMovePct) ? "day n/a"
                : String.format(Locale.US, "day %+.1f%%", dayMovePct);
        return String.format(Locale.US,
                "Bull power %d%% · Bear power %d%% — %s (our gauge: OI added PE %s vs CE %s · PCR %.2f · %s)",
                bull, 100 - bull, verdict(bull), oi(addPe), oi(addCe), pcr, day);
    }
}
