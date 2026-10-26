package com.irathinker.app;

import java.time.DayOfWeek;
import java.time.LocalDate;

/** Options math for the Detail screen's index card: Black-Scholes
 *  estimates using India VIX as the volatility input. Estimates only -
 *  live exchange premiums differ (skew, demand, time of day). */
public final class OptionsMath {

    private static final double R = 0.065; // risk-free approx (India 10Y ~6.5%)

    /** Strike step for index options, or -1 when not an optionable index here. */
    public static int strikeStep(String yahoo) {
        switch (yahoo) {
            case "^NSEI": return 50;    // NIFTY
            case "^NSEBANK": return 100; // BANKNIFTY
            case "^CNXFIN": return 50;  // FINNIFTY
            default: return -1;
        }
    }

    public static double atmStrike(double spot, int step) {
        return Math.round(spot / step) * (double) step;
    }

    /** Days until the next weekly expiry (Tuesday - NSE moved all
     *  expiries to Tuesday from Sep 2025). 0 if today is expiry. */
    public static int daysToExpiry(LocalDate today) {
        return (int) java.time.temporal.ChronoUnit.DAYS.between(today, weeklyExpiry(today));
    }

    /** Next Tuesday (today itself when today is Tuesday), moved back
     *  to the trading day before when that Tuesday is an exchange
     *  holiday (review 26 Oct 2026: Tue 10 Nov 2026 is Diwali
     *  Balipratipada, so that week's NIFTY expiry is Mon 9 Nov). */
    public static LocalDate weeklyExpiry(LocalDate today) {
        int add = (DayOfWeek.TUESDAY.getValue() - today.getDayOfWeek().getValue() + 7) % 7;
        LocalDate tue = today.plusDays(add);
        LocalDate e = holidayShift(tue);
        // Only on the holiday itself can the moved expiry be behind
        // today: the contract has gone, so next week's is the nearest.
        if (e.isBefore(today)) e = holidayShift(tue.plusWeeks(1));
        return e;
    }

    /** Last Tuesday of the month (next month's once it has passed) -
     *  the stock-options and non-NIFTY index expiry - moved back to
     *  the trading day before when it is an exchange holiday (review
     *  26 Oct 2026: Tue 24 Nov 2026 is Guru Nanak Jayanti, so the
     *  November monthly expiry is Mon 23 Nov; Tue 31 Mar 2026 was
     *  Mahavir Jayanti, so March's was Mon 30 Mar). */
    public static LocalDate monthlyExpiry(LocalDate today) {
        LocalDate e = holidayShift(lastTuesday(today.getYear(), today.getMonthValue()));
        if (today.isAfter(e)) {
            LocalDate nm = today.plusMonths(1);
            e = holidayShift(lastTuesday(nm.getYear(), nm.getMonthValue()));
        }
        return e;
    }

    /** An expiry that falls on an exchange holiday moves to the
     *  previous trading day (the exchanges' rule). Uses the NSE
     *  list in MarketCalendar (2026 only: a later year's holidays
     *  are not known, so its dates stay unshifted); BSE closes on
     *  the same days for these holidays. */
    static LocalDate holidayShift(LocalDate d) {
        while (MarketCalendar.isHoliday(d) || d.getDayOfWeek().getValue() > 5) d = d.minusDays(1);
        return d;
    }

    private static LocalDate lastTuesday(int year, int month) {
        LocalDate d = LocalDate.of(year, month, 1).plusMonths(1).minusDays(1);
        while (d.getDayOfWeek() != DayOfWeek.TUESDAY) d = d.minusDays(1);
        return d;
    }

    /** BSE Sensex monthly expiry: the LAST THURSDAY of the month
     *  (next month's once it has passed). BSE's expiry day has moved
     *  by era - last Friday to Dec 2024, last Tuesday Jan-Aug 2025,
     *  last Thursday from Sep 2025 - verified against the lake
     *  chain's detected expiries (sensex_lab): every liquid-era
     *  detection from Sep 2025 to Sep 2026 (2025-09-25, 2025-10-30,
     *  2026-01-29, 2026-04-30, 2026-07-30, 2026-08-27, 2026-09-24)
     *  is exactly its month's last Thursday. Not a Tuesday (the
     *  NSE rule above) and not the pre-2025 Friday. */
    public static LocalDate monthlyExpiryBse(LocalDate today) {
        LocalDate e = holidayShift(lastThursday(today.getYear(), today.getMonthValue()));
        if (today.isAfter(e)) {
            LocalDate nm = today.plusMonths(1);
            e = holidayShift(lastThursday(nm.getYear(), nm.getMonthValue()));
        }
        return e;
    }

    private static LocalDate lastThursday(int year, int month) {
        LocalDate d = LocalDate.of(year, month, 1).plusMonths(1).minusDays(1);
        while (d.getDayOfWeek() != DayOfWeek.THURSDAY) d = d.minusDays(1);
        return d;
    }

    public static int daysToMonthlyExpiry(LocalDate today) {
        return (int) java.time.temporal.ChronoUnit.DAYS.between(today, monthlyExpiry(today));
    }

    /** Strike step for a symbol: the exchange-listed interval where it
     *  has been verified from the chain, else the index table, else a
     *  price-band approximation. NSE sets the step per stock, so the
     *  nearest listed strike can still differ - verify before ordering. */
    public static int strikeStepFor(String yahoo, double spot) {
        switch (yahoo) {
            case "DRREDDY.NS": return 10;     // verified from chain snapshots
            case "LT.NS": return 20;          // verified
            case "BAJAJ-AUTO.NS": return 100; // verified
            default: break;
        }
        int idx = strikeStep(yahoo);
        return idx > 0 ? idx : strikeStepForPrice(spot);
    }

    /** Approximate strike step for stock options by price band. */
    public static int strikeStepForPrice(double spot) {
        if (spot < 250) return 5;
        if (spot < 2000) return 10;
        if (spot < 5000) return 20;
        if (spot < 8000) return 50;
        return 100;
    }

    /** Realized volatility (%) from a daily chart: stdev of the last 20
     *  daily log returns, annualised. The IV proxy for stock options. */
    public static double realizedVol(YahooClient.Chart daily) {
        if (daily == null || daily.close.length < 10) return Double.NaN;
        java.util.List<Double> closes = new java.util.ArrayList<>();
        for (double c : daily.close) if (!Double.isNaN(c) && c > 0) closes.add(c);
        int n = closes.size();
        if (n < 10) return Double.NaN;
        int from = Math.max(1, n - 20);
        int cnt = n - from;
        if (cnt < 5) return Double.NaN;
        double[] rets = new double[cnt];
        double mean = 0;
        for (int i = from; i < n; i++) { rets[i - from] = Math.log(closes.get(i) / closes.get(i - 1)); mean += rets[i - from]; }
        mean /= cnt;
        double var = 0;
        for (double r : rets) var += (r - mean) * (r - mean);
        return Math.sqrt(var / cnt) * Math.sqrt(252) * 100;
    }

    /** A ready-to-trade option ticket for a plan: the contract, and the
     *  premium at the plan's entry / target / stop stock levels. */
    public static final class Ticket {
        public double strike, premium, premTarget, premStop, delta, iv;
        public boolean call;
        public LocalDate expiry;
        public int dte;
    }

    public static Ticket ticket(Analysis.Scan sc, double ivPct, LocalDate today) {
        return ticket(sc, ivPct, today, sc.target);
    }

    /** Ticket whose target premium is priced at a given stock level
     *  (alerts exit at 2R while plans display 1R). */
    public static Ticket ticket(Analysis.Scan sc, double ivPct, LocalDate today, double targetLevel) {
        return ticket(sc.sym, sc.setup == Analysis.Setup.ORB_UP, sc.entry, sc.stop,
                targetLevel, ivPct, today);
    }

    /** The same ticket from raw plan levels - used by the gap-fade
     *  plan, whose levels live outside the ORB fields (v42). */
    public static Ticket ticket(Universe.Sym sym, boolean call, double entry, double stop,
                                double targetLevel, double ivPct, LocalDate today) {
        boolean weekly = "^NSEI".equals(sym.yahoo);
        LocalDate exp = weekly ? weeklyExpiry(today) : monthlyExpiry(today);
        int step = strikeStepFor(sym.yahoo, entry);
        Ticket t = new Ticket();
        t.call = call;
        t.strike = atmStrike(entry, step);
        t.iv = ivPct;
        t.expiry = exp;
        t.dte = (int) java.time.temporal.ChronoUnit.DAYS.between(today, exp);
        t.premium = bsPrice(entry, t.strike, t.dte, ivPct, call);
        t.premTarget = bsPrice(targetLevel, t.strike, t.dte, ivPct, call);
        t.premStop = bsPrice(stop, t.strike, t.dte, ivPct, call);
        t.delta = bsDelta(entry, t.strike, t.dte, ivPct, call);
        return t;
    }

    /** Alert-ready ticket text (premiums per share, Black-Scholes). */
    public static String ticketText(Analysis.Scan sc, double ivPct, LocalDate today) {
        return ticketText(sc, ivPct, today, sc.target);
    }

    public static String ticketText(Analysis.Scan sc, double ivPct, LocalDate today, double targetLevel) {
        return formatTicket(ticket(sc, ivPct, today, targetLevel));
    }

    public static String formatTicket(Ticket t) {
        if (t == null || Double.isNaN(t.premium)) return "";
        return String.format("\nOPTION: BUY %.0f %s · ATM · expiry %s\nPremium ~₹%.1f → target ~₹%.1f · stop ~₹%.1f (Δ %.2f, IV ~%.0f%%)",
                t.strike, t.call ? "CE" : "PE",
                t.expiry.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM")),
                t.premium, t.premTarget, t.premStop, t.delta, t.iv);
    }

    /** One plain line sizing the trade by the user's own max-loss
     *  rule: how many units the risk budget buys at the entry premium,
     *  what they cost, and what is lost if the stop premium prints.
     *  Units are per-share units - the user divides by the exchange
     *  lot size. Empty when the ticket cannot support the framing. */
    public static String riskLine(Ticket t, double capital, double maxLossPct) {
        if (t == null || Double.isNaN(t.premium) || Double.isNaN(t.premStop)) return "";
        double perUnit = t.premium - t.premStop;
        double budget = capital * maxLossPct / 100.0;
        if (budget <= 0) return "";
        if (perUnit <= 0.05) return "";
        long units = (long) Math.floor(budget / perUnit);
        if (units < 1) {
            return String.format("%nRisk check: your max-loss rule is ₹%,.0f, but even 1 unit of this option risks ~₹%,.0f at the stop — too big for the rule.",
                    budget, perUnit);
        }
        return String.format("%nRisk check (your ₹%,.0f max-loss rule): ~%,d units for ~₹%,.0f premium — if the stop hits, you lose ~₹%,.0f. Divide units by the lot size for lots.",
                budget, units, units * t.premium, units * perUnit);
    }

    /** The Risk Gate's sizing for a ticket, as numbers (v59): how
     *  many per-share units the user's max-loss budget buys at the
     *  entry premium, and the planned rupee risk at entry - the
     *  stop distance per unit (premium - premium at the stop)
     *  times the units. This is riskLine()'s arithmetic exactly,
     *  in numeric form, so the System Feed can carry the same
     *  sizing the alert text states. */
    public static final class Sizing {
        public long units;      // per-share units the budget buys
        public double riskRs;   // planned risk in Rs at entry = units x (premium - premStop)
    }

    /** Sizing for a ticket under the user's max-loss rule, or null
     *  when the rule cannot size it - the same branches riskLine()
     *  bows out on (no ticket, NaN premiums, no budget, a per-unit
     *  risk of 0.05 or less, or not even one unit affordable: the
     *  "too big for the rule" case riskLine warns about in words -
     *  the feed omits the fields instead of inventing a size). */
    public static Sizing sizingFor(Ticket t, double capital, double maxLossPct) {
        if (t == null || Double.isNaN(t.premium) || Double.isNaN(t.premStop)) return null;
        double perUnit = t.premium - t.premStop;
        double budget = capital * maxLossPct / 100.0;
        if (budget <= 0 || perUnit <= 0.05) return null;
        long units = (long) Math.floor(budget / perUnit);
        if (units < 1) return null;
        Sizing s = new Sizing();
        s.units = units;
        s.riskRs = units * perUnit;
        return s;
    }

    /** Black-Scholes price per share. ivPct is volatility in percent (e.g. 13.5). */
    public static double bsPrice(double spot, double strike, int days, double ivPct, boolean call) {
        return bsPriceDays(spot, strike, days, ivPct, call);
    }

    /** Black-Scholes with a fractional day count (v53: the mom60
     *  paper log reprices a held contract minute by minute as its
     *  time to expiry shrinks inside the day). Same 0.35-day floor
     *  and constants as bsPrice, so an integral day count prices
     *  identically through either entry point. */
    public static double bsPriceDays(double spot, double strike, double days, double ivPct, boolean call) {
        if (spot <= 0 || strike <= 0) return Double.NaN;
        double t = Math.max(days, 0.35) / 365.0; // intraday floor: expiry day still has hours
        double sigma = Math.max(ivPct, 1) / 100.0;
        double d1 = (Math.log(spot / strike) + (R + sigma * sigma / 2) * t) / (sigma * Math.sqrt(t));
        double d2 = d1 - sigma * Math.sqrt(t);
        double disc = Math.exp(-R * t);
        return call
                ? spot * normCdf(d1) - strike * disc * normCdf(d2)
                : strike * disc * normCdf(-d2) - spot * normCdf(-d1);
    }

    private static double d1(double spot, double strike, int days, double ivPct) {
        double t = Math.max(days, 0.35) / 365.0;
        double sigma = Math.max(ivPct, 1) / 100.0;
        return (Math.log(spot / strike) + (R + sigma * sigma / 2) * t) / (sigma * Math.sqrt(t));
    }

    /** Delta of the contract: how much the premium moves per 1 point of spot. */
    public static double bsDelta(double spot, double strike, int days, double ivPct, boolean call) {
        if (spot <= 0 || strike <= 0) return Double.NaN;
        double d = normCdf(d1(spot, strike, days, ivPct));
        return call ? d : d - 1;
    }

    /** Gamma: how fast delta itself changes per point - the expiry-week accelerant. */
    public static double bsGamma(double spot, double strike, int days, double ivPct) {
        if (spot <= 0 || strike <= 0) return Double.NaN;
        double t = Math.max(days, 0.35) / 365.0;
        double sigma = Math.max(ivPct, 1) / 100.0;
        double d = d1(spot, strike, days, ivPct);
        double pdf = Math.exp(-d * d / 2) / Math.sqrt(2 * Math.PI);
        return pdf / (spot * sigma * Math.sqrt(t));
    }

    /** Abramowitz-Stegun cumulative normal. */
    static double normCdf(double x) {
        double t = 1 / (1 + 0.2316419 * Math.abs(x));
        double poly = t * (0.319381530 + t * (-0.356563782 + t * (1.781477937
                + t * (-1.821255978 + t * 1.330274429))));
        double pdf = Math.exp(-x * x / 2) / Math.sqrt(2 * Math.PI);
        double cdf = 1 - pdf * poly;
        return x >= 0 ? cdf : 1 - cdf;
    }
}
