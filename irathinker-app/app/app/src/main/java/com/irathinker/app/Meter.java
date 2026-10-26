package com.irathinker.app;

/** Market Meter (user request, 2026-10-23): a market-wide sentiment
 *  score in the spirit of GOC Technology's GOC Meter - but ours,
 *  computed on-device from Yahoo prices and the free NSE chain probe,
 *  with every factor and weight printed on the screen. Six factors,
 *  each normalized to -1 (bearish) .. +1 (bullish):
 *    OI additions 25 - gross OI added today per side (BullBear.oiScore)
 *    PCR level    10 - total PE/CE open interest (BullBear.pcrScore)
 *    India VIX    20 - level (calm is bullish) + today's VIX trend
 *    Momentum     20 - the index's day move + position vs its 20DMA
 *    RSI          10 - the index's intraday RSI, centred on 50
 *    Breadth     15 - advancing vs declining stocks in the universe
 *  A factor with no data (NSE probe blocked, breadth still warming)
 *  drops out and the remaining weights renormalize - the screen says
 *  which factors are in. Pure and harness-tested. Display only: a
 *  sentiment gauge is information, never an alert gate. */
public final class Meter {

    public static final double W_OI = 25, W_PCR = 10, W_VIX = 20,
                               W_MOM = 20, W_RSI = 10, W_BREADTH = 15;

    public static final class Result {
        public int score = 0;                 // -100..+100
        public double oi, pcr, vix, mom, rsi, breadth; // factor scores, NaN = absent
        public int factorsUsed = 0;
        public String zone() { return Meter.zone(score); }
    }

    private static double clamp(double v) {
        return Math.max(-1, Math.min(1, v));
    }

    public static double oiFactor(double addCe, double addPe) {
        return 2 * BullBear.oiScore(addCe, addPe) - 1;
    }

    public static double pcrFactor(double pcr) {
        return 2 * BullBear.pcrScore(pcr) - 1;
    }

    /** VIX level: 5 or below is +1 (calm), 15 is 0, 25 or above -1. */
    public static double vixLevelScore(double vix) {
        if (Double.isNaN(vix) || vix <= 0) return Double.NaN;
        return clamp((15 - vix) / 10);
    }

    /** VIX trend: a VIX down 10% on the day is +1; up 10% is -1. */
    public static double vixTrendScore(double vixDayPct) {
        if (Double.isNaN(vixDayPct)) return Double.NaN;
        return clamp(-vixDayPct / 10);
    }

    public static double vixFactor(double vix, double vixDayPct) {
        double a = vixLevelScore(vix), b = vixTrendScore(vixDayPct);
        if (Double.isNaN(a)) return b;
        if (Double.isNaN(b)) return a;
        return (a + b) / 2;
    }

    /** Momentum: day move (+/-1.5% saturates) blended with the
     *  position vs the 20-day average (+/-2% away saturates). */
    public static double momFactor(double dayPct, double ltp, double dma20) {
        if (Double.isNaN(dayPct)) return Double.NaN;
        double m = clamp(dayPct / 1.5);
        if (Double.isNaN(ltp) || Double.isNaN(dma20) || dma20 <= 0) return m;
        double pos = clamp(((ltp - dma20) / dma20 * 100) / 2);
        return (m + pos) / 2;
    }

    public static double rsiFactor(double rsi) {
        if (Double.isNaN(rsi)) return Double.NaN;
        return clamp((rsi - 50) / 25);
    }

    public static double breadthFactor(int up, int down) {
        if (up + down <= 0) return Double.NaN;
        return (up - down) / (double) (up + down);
    }

    /** Weighted mean of the present factors, renormalized. NaN factor
     *  = absent. All absent -> a neutral 0 with factorsUsed 0. */
    public static Result combine(double oi, double pcr, double vix,
                                 double mom, double rsi, double breadth) {
        Result r = new Result();
        r.oi = oi; r.pcr = pcr; r.vix = vix; r.mom = mom; r.rsi = rsi; r.breadth = breadth;
        double[] f = {oi, pcr, vix, mom, rsi, breadth};
        double[] w = {W_OI, W_PCR, W_VIX, W_MOM, W_RSI, W_BREADTH};
        double num = 0, den = 0;
        for (int i = 0; i < f.length; i++) {
            if (!Double.isNaN(f[i])) { num += w[i] * f[i]; den += w[i]; r.factorsUsed++; }
        }
        r.score = den == 0 ? 0 : (int) Math.round(100 * num / den);
        r.score = Math.max(-100, Math.min(100, r.score));
        return r;
    }

    public static String zone(int score) {
        if (score >= 40) return "Strongly bullish";
        if (score >= 15) return "Bullish";
        if (score > -15) return "Neutral";
        if (score > -40) return "Bearish";
        return "Strongly bearish";
    }
}
