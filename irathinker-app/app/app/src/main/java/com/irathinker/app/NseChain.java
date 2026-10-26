package com.irathinker.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Free NSE option-chain probe (user request, 2026-10-23): asks
 *  nseindia.com's public website feed - no API key, no account - for
 *  the live chain of one symbol. NSE blocks many networks (datacenter
 *  IPs get an empty reply), so every caller treats null as "no
 *  answer" and silently keeps the Black-Scholes estimates. When the
 *  phone's network is answered, this yields real open interest,
 *  real implied volatility, and a gamma computed from the real chain.
 *  Nothing here is ever fabricated: no data, no line. */
public final class NseChain {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String UA = "Mozilla/5.0 (Linux; Android 14; Pixel 9) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36";
    private static final DateTimeFormatter NSE_DATE =
            DateTimeFormatter.ofPattern("d-MMM-yyyy", Locale.ENGLISH);

    /** One chain snapshot: the ATM row of the nearest expiry, plus the
     *  put/call OI ratio across that whole expiry. OI in contracts. */
    public static final class Probe {
        public double underlying, strike;
        public double ceOi, peOi, ceOiChg, peOiChg;
        public double ceIv, peIv, ceLtp, peLtp;
        public double pcr;              // total PE OI / total CE OI, nearest expiry
        public double totCeOi, totPeOi; // chain totals, nearest expiry
        public double addCeOi, addPeOi; // gross OI added today (sum of positive changes)
        public LocalDate expiry;
    }

    /** "indices:NIFTY" / "equities:RELIANCE" for the NSE API, or null
     *  when the symbol has no NSE option chain (BSE, FX, commodities). */
    static String endpointFor(String yahoo) {
        switch (yahoo) {
            case "^NSEI": return "indices:NIFTY";
            case "^NSEBANK": return "indices:BANKNIFTY";
            case "^CNXFIN": return "indices:FINNIFTY";
            case "^NSEMDCP50": return "indices:MIDCPNIFTY";
            default: break;
        }
        if (yahoo.endsWith(".NS")) return "equities:" + yahoo.substring(0, yahoo.length() - 3);
        return null;
    }

    // ---- network ----------------------------------------------------

    private static String cookieHeader = "";
    private static long warmAttemptAt = 0;

    /** Fetch + parse the live chain. Blocking - call off the UI thread.
     *  Returns null on ANY failure (blocked, empty, malformed). */
    public static Probe fetch(String yahoo) {
        try {
            String ep = endpointFor(yahoo);
            if (ep == null) return null;
            int cut = ep.indexOf(':');
            String url = "https://www.nseindia.com/api/option-chain-" + ep.substring(0, cut)
                    + "?symbol=" + URLEncoder.encode(ep.substring(cut + 1), "UTF-8");
            String body = get(url, true);
            if (body == null && cookieHeader.isEmpty()) {
                warmUp();
                body = get(url, true);
            }
            return parse(body);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Visit the homepage once so NSE issues its session cookies (its
     *  API answers empty without them). Retried at most every 10 min. */
    private static synchronized void warmUp() {
        long now = System.currentTimeMillis();
        if (now - warmAttemptAt < 600_000 && !cookieHeader.isEmpty()) return;
        warmAttemptAt = now;
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("https://www.nseindia.com/").openConnection();
            c.setRequestProperty("User-Agent", UA);
            c.setConnectTimeout(5000);
            c.setReadTimeout(7000);
            c.getResponseCode();
            StringBuilder sb = new StringBuilder();
            for (int i = 1; ; i++) {
                String k = c.getHeaderFieldKey(i);
                if (k == null) break;
                if ("Set-Cookie".equalsIgnoreCase(k)) {
                    String v = c.getHeaderField(i);
                    int semi = v.indexOf(';');
                    if (semi > 0) v = v.substring(0, semi);
                    if (sb.length() > 0) sb.append("; ");
                    sb.append(v);
                }
            }
            if (sb.length() > 0) cookieHeader = sb.toString();
            c.disconnect();
        } catch (Throwable ignored) { }
    }

    private static String get(String url, boolean api) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", api ? "application/json, text/plain, */*" : "*/*");
            c.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
            c.setRequestProperty("Referer", "https://www.nseindia.com/option-chain");
            if (!cookieHeader.isEmpty()) c.setRequestProperty("Cookie", cookieHeader);
            c.setConnectTimeout(5000);
            c.setReadTimeout(7000);
            if (c.getResponseCode() != 200) return null;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    // ---- parsing (pure - harness-tested) ----------------------------

    /** Parse an NSE chain response. Null for the empty/blocked replies
     *  NSE serves to networks it refuses ("" and "{}" included). */
    static Probe parse(String json) {
        try {
            if (json == null || json.length() < 50) return null;
            JSONObject root = new JSONObject(json);
            JSONObject rec = root.optJSONObject("records");
            if (rec == null) return null;
            JSONArray exps = rec.optJSONArray("expiryDates");
            JSONArray data = rec.optJSONArray("data");
            if (exps == null || exps.length() == 0 || data == null || data.length() == 0) return null;
            String firstExp = exps.getString(0);
            double und = rec.optDouble("underlyingValue", Double.NaN);
            if (Double.isNaN(und) || und <= 0) return null;
            Probe p = new Probe();
            p.underlying = und;
            try { p.expiry = LocalDate.parse(firstExp, NSE_DATE); } catch (Exception ignored) { }
            double bestGap = Double.MAX_VALUE;
            double totCe = 0, totPe = 0, addCe = 0, addPe = 0;
            for (int i = 0; i < data.length(); i++) {
                JSONObject row = data.getJSONObject(i);
                if (!firstExp.equals(row.optString("expiryDate"))) continue;
                JSONObject ce = row.optJSONObject("CE");
                JSONObject pe = row.optJSONObject("PE");
                if (ce != null) {
                    totCe += ce.optDouble("openInterest", 0);
                    addCe += Math.max(0, ce.optDouble("changeinOpenInterest", 0));
                }
                if (pe != null) {
                    totPe += pe.optDouble("openInterest", 0);
                    addPe += Math.max(0, pe.optDouble("changeinOpenInterest", 0));
                }
                double k = row.optDouble("strikePrice", Double.NaN);
                if (Double.isNaN(k)) continue;
                double gap = Math.abs(k - und);
                if (gap < bestGap) {
                    bestGap = gap;
                    p.strike = k;
                    if (ce != null) {
                        p.ceOi = ce.optDouble("openInterest", 0);
                        p.ceOiChg = ce.optDouble("changeinOpenInterest", 0);
                        p.ceIv = ce.optDouble("impliedVolatility", 0);
                        p.ceLtp = ce.optDouble("lastPrice", 0);
                    }
                    if (pe != null) {
                        p.peOi = pe.optDouble("openInterest", 0);
                        p.peOiChg = pe.optDouble("changeinOpenInterest", 0);
                        p.peIv = pe.optDouble("impliedVolatility", 0);
                        p.peLtp = pe.optDouble("lastPrice", 0);
                    }
                }
            }
            if (p.strike <= 0 || totCe <= 0) return null;
            p.pcr = totPe / totCe;
            p.totCeOi = totCe;
            p.totPeOi = totPe;
            p.addCeOi = addCe;
            p.addPeOi = addPe;
            return p;
        } catch (Throwable t) {
            return null;
        }
    }

    // ---- formatting (pure - harness-tested) --------------------------

    private static String oi(double contracts) {
        if (contracts >= 100_000) return String.format(Locale.US, "%.1fL", contracts / 100_000.0);
        if (contracts >= 1_000) return String.format(Locale.US, "%.1fk", contracts / 1_000.0);
        return String.format(Locale.US, "%.0f", contracts);
    }

    /** One-line summary for a plan's direction, for the Detail card and
     *  the alert body. Gamma is Black-Scholes at the CHAIN's own IV. */
    public static String line(Probe p, boolean call) {
        if (p == null) return "";
        double oiSide = call ? p.ceOi : p.peOi;
        double oiChg = call ? p.ceOiChg : p.peOiChg;
        double iv = call ? p.ceIv : p.peIv;
        double ltp = call ? p.ceLtp : p.peLtp;
        int dte = p.expiry == null ? 0
                : (int) java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(IST), p.expiry);
        double gamma = iv > 0 ? OptionsMath.bsGamma(p.underlying, p.strike, Math.max(dte, 0), iv) : Double.NaN;
        return String.format(Locale.US,
                "NSE live chain: %.0f %s · OI %s (%s%s today) · LTP ₹%.1f · IV %.1f%%%s · chain PCR %.2f",
                p.strike, call ? "CE" : "PE", oi(oiSide),
                oiChg >= 0 ? "+" : "−", oi(Math.abs(oiChg)),
                ltp, iv,
                Double.isNaN(gamma) ? "" : String.format(Locale.US, " · Γ %.4f", gamma),
                p.pcr);
    }
}
