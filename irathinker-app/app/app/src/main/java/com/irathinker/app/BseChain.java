package com.irathinker.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;

/** Free BSE option-chain probe (v59) - the Sensex Detail card's
 *  mirror of the v29 NSE probe. It asks BSE's public website API
 *  (api.bseindia.com, the BseIndiaAPI family the bseindia.com
 *  pages themselves call - no API key, no account) for the Sensex
 *  chain, with the same discipline as NseChain: a cookie warm-up
 *  visit to the homepage first, browser headers, short timeouts,
 *  and null on ANY failure so the caller silently keeps the
 *  Black-Scholes estimates. Nothing here is ever fabricated: no
 *  answer, no line.
 *
 *  Honest status: BSE documents no stable public chain schema,
 *  and the sandbox could not verify this endpoint's reply shape
 *  (BSE refuses datacenter traffic much as NSE does). The request
 *  follows the documented BseIndiaAPI shape and the parser is
 *  deliberately defensive - it accepts the field spellings BSE's
 *  API family uses and returns null unless a coherent chain
 *  (an underlying, strikes with OI on both sides) actually
 *  parses. The on-device verdict is PENDING: the Sensex card will
 *  show "BSE live chain: ..." only when the phone's network is
 *  genuinely answered in a shape this parser recognises. */
public final class BseChain {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String UA = "Mozilla/5.0 (Linux; Android 14; Pixel 9) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36";

    /** The one Yahoo symbol this probe serves: the Sensex. */
    public static boolean appliesTo(String yahoo) {
        return "^BSESN".equals(yahoo);
    }

    /** The chain request URL, in the documented BseIndiaAPI shape
     *  (the same host + /api/<endpoint>/w pattern as BSE's public
     *  announcement and index endpoints). */
    static String chainUrl() {
        return "https://api.bseindia.com/BseIndiaAPI/api/getOptionChain/w?underlying=SENSEX";
    }

    // ---- network (mirrors NseChain) ------------------------------

    private static String cookieHeader = "";
    private static long warmAttemptAt = 0;

    /** Fetch + parse the live chain. Blocking - call off the UI
     *  thread. Returns null on ANY failure (blocked, empty,
     *  malformed, unrecognised shape). */
    public static NseChain.Probe fetch(String yahoo) {
        return fetch(yahoo, Double.NaN);
    }

    /** The same fetch with the caller's own spot price as the
     *  parse hint (used only when the payload carries no
     *  underlying value of its own). */
    public static NseChain.Probe fetch(String yahoo, double spotHint) {
        try {
            if (!appliesTo(yahoo)) return null;
            String body = get(chainUrl());
            if (body == null && cookieHeader.isEmpty()) {
                warmUp();
                body = get(chainUrl());
            }
            return parse(body, spotHint);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Visit the BSE homepage once so it issues its session
     *  cookies. Retried at most every 10 min, as in NseChain. */
    private static synchronized void warmUp() {
        long now = System.currentTimeMillis();
        if (now - warmAttemptAt < 600_000 && !cookieHeader.isEmpty()) return;
        warmAttemptAt = now;
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("https://www.bseindia.com/").openConnection();
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

    private static String get(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", "application/json, text/plain, */*");
            c.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
            c.setRequestProperty("Referer", "https://www.bseindia.com/");
            c.setRequestProperty("Origin", "https://www.bseindia.com");
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

    // ---- parsing (pure - harness-tested) -------------------------

    /** First present key among spellings, as a double (BSE serves
     *  most numbers as strings). NaN when none parses. */
    private static double numOf(JSONObject o, String... keys) {
        for (String k : keys) {
            if (!o.has(k) || o.isNull(k)) continue;
            try {
                Object v = o.get(k);
                if (v instanceof Number) return ((Number) v).doubleValue();
                String s = String.valueOf(v).replace(",", "").trim();
                if (!s.isEmpty() && !"-".equals(s)) return Double.parseDouble(s);
            } catch (Exception ignored) { }
        }
        return Double.NaN;
    }

    private static String strOf(JSONObject o, String... keys) {
        for (String k : keys) {
            if (!o.has(k) || o.isNull(k)) continue;
            try {
                String s = String.valueOf(o.get(k)).trim();
                if (!s.isEmpty()) return s;
            } catch (Exception ignored) { }
        }
        return "";
    }

    /** Parse a BSE chain response into the shared Probe shape.
     *  Accepts a root array, or a root object carrying the rows
     *  under Table / data / Table1 (the envelopes BSE's API family
     *  uses). Rows are flat: a strike plus CE_/PE_-prefixed OI,
     *  OI-change and LTP fields, optionally nested CE/PE objects
     *  like NSE's. Only rows of the EARLIEST expiry present are
     *  read. spotHint (the card's own LTP) stands in when the
     *  payload carries no underlying value. Null unless a
     *  coherent chain parses: an underlying, at least one strike,
     *  and positive total CE OI - anything less is "no answer",
     *  never a partial invention. */
    static NseChain.Probe parse(String json, double spotHint) {
        try {
            if (json == null || json.length() < 30) return null;
            JSONArray rows = null;
            double und = Double.NaN;
            Object root = new org.json.JSONTokener(json).nextValue();
            if (root instanceof JSONArray) {
                rows = (JSONArray) root;
            } else if (root instanceof JSONObject) {
                JSONObject o = (JSONObject) root;
                und = numOf(o, "underlyingValue", "UnderlyingValue", "underlying", "spot");
                for (String k : new String[]{"Table", "data", "Table1", "rows"}) {
                    JSONArray a = o.optJSONArray(k);
                    if (a != null && a.length() > 0) { rows = a; break; }
                }
            }
            if (rows == null || rows.length() == 0) return null;
            // the earliest expiry present, when rows carry one
            String firstExp = null;
            for (int i = 0; i < rows.length(); i++) {
                JSONObject r = rows.optJSONObject(i);
                if (r == null) continue;
                String e = strOf(r, "expiryDate", "ExpiryDate", "expiry", "Expiry");
                if (!e.isEmpty() && (firstExp == null || e.compareTo(firstExp) < 0)) firstExp = e;
            }
            if (Double.isNaN(und) || und <= 0) {
                // some shapes carry the underlying per row
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject r = rows.optJSONObject(i);
                    if (r == null) continue;
                    double u = numOf(r, "underlyingValue", "UnderlyingValue", "underlying");
                    if (!Double.isNaN(u) && u > 0) { und = u; break; }
                }
            }
            if (Double.isNaN(und) || und <= 0) und = spotHint;
            if (Double.isNaN(und) || und <= 0) return null;
            NseChain.Probe p = new NseChain.Probe();
            p.underlying = und;
            double bestGap = Double.MAX_VALUE;
            double totCe = 0, totPe = 0, addCe = 0, addPe = 0;
            for (int i = 0; i < rows.length(); i++) {
                JSONObject r = rows.optJSONObject(i);
                if (r == null) continue;
                if (firstExp != null) {
                    String e = strOf(r, "expiryDate", "ExpiryDate", "expiry", "Expiry");
                    if (!e.isEmpty() && !firstExp.equals(e)) continue;
                }
                JSONObject ce = r.optJSONObject("CE");
                JSONObject pe = r.optJSONObject("PE");
                double k = numOf(r, "strikePrice", "StrikePrice", "strike", "Strike");
                double ceOi = ce != null ? numOf(ce, "openInterest", "OpenInterest", "oi")
                        : numOf(r, "CE_OI", "ceOi", "CeOi", "callOI", "CallOI");
                double peOi = pe != null ? numOf(pe, "openInterest", "OpenInterest", "oi")
                        : numOf(r, "PE_OI", "peOi", "PeOi", "putOI", "PutOI");
                if (!Double.isNaN(ceOi) && ceOi > 0) {
                    totCe += ceOi;
                    double chg = ce != null ? numOf(ce, "changeinOpenInterest", "changeInOpenInterest")
                            : numOf(r, "CE_OIChg", "ceOiChg", "CE_ChgOI");
                    if (!Double.isNaN(chg)) addCe += Math.max(0, chg);
                }
                if (!Double.isNaN(peOi) && peOi > 0) {
                    totPe += peOi;
                    double chg = pe != null ? numOf(pe, "changeinOpenInterest", "changeInOpenInterest")
                            : numOf(r, "PE_OIChg", "peOiChg", "PE_ChgOI");
                    if (!Double.isNaN(chg)) addPe += Math.max(0, chg);
                }
                if (Double.isNaN(k) || k <= 0) continue;
                double gap = Math.abs(k - und);
                if (gap < bestGap) {
                    bestGap = gap;
                    p.strike = k;
                    p.ceOi = Double.isNaN(ceOi) ? 0 : ceOi;
                    p.peOi = Double.isNaN(peOi) ? 0 : peOi;
                    double c1 = ce != null ? numOf(ce, "changeinOpenInterest", "changeInOpenInterest")
                            : numOf(r, "CE_OIChg", "ceOiChg", "CE_ChgOI");
                    double c2 = pe != null ? numOf(pe, "changeinOpenInterest", "changeInOpenInterest")
                            : numOf(r, "PE_OIChg", "peOiChg", "PE_ChgOI");
                    p.ceOiChg = Double.isNaN(c1) ? 0 : c1;
                    p.peOiChg = Double.isNaN(c2) ? 0 : c2;
                    double l1 = ce != null ? numOf(ce, "lastPrice", "ltp")
                            : numOf(r, "CE_LTP", "ceLtp", "CeLtp");
                    double l2 = pe != null ? numOf(pe, "lastPrice", "ltp")
                            : numOf(r, "PE_LTP", "peLtp", "PeLtp");
                    p.ceLtp = Double.isNaN(l1) ? 0 : l1;
                    p.peLtp = Double.isNaN(l2) ? 0 : l2;
                    double v1 = ce != null ? numOf(ce, "impliedVolatility", "iv")
                            : numOf(r, "CE_IV", "ceIv");
                    double v2 = pe != null ? numOf(pe, "impliedVolatility", "iv")
                            : numOf(r, "PE_IV", "peIv");
                    p.ceIv = Double.isNaN(v1) ? 0 : v1;
                    p.peIv = Double.isNaN(v2) ? 0 : v2;
                }
            }
            if (p.strike <= 0 || totCe <= 0) return null;
            p.pcr = totPe / totCe;
            p.totCeOi = totCe;
            p.totPeOi = totPe;
            p.addCeOi = addCe;
            p.addPeOi = addPe;
            if (firstExp != null) {
                // BSE serves dates in several spellings; parse
                // the common ones, else leave the expiry unset
                // (it only feeds the gamma day-count).
                for (String pat : new String[]{"yyyy-MM-dd", "dd-MMM-yyyy", "dd/MM/yyyy"}) {
                    try {
                        p.expiry = LocalDate.parse(firstExp,
                                java.time.format.DateTimeFormatter.ofPattern(pat, Locale.ENGLISH));
                        break;
                    } catch (Exception ignored) { }
                }
            }
            return p;
        } catch (Throwable t) {
            return null;
        }
    }

    // ---- formatting (pure - harness-tested) ----------------------

    private static String oi(double contracts) {
        if (contracts >= 100_000) return String.format(Locale.US, "%.1fL", contracts / 100_000.0);
        if (contracts >= 1_000) return String.format(Locale.US, "%.1fk", contracts / 1_000.0);
        return String.format(Locale.US, "%.0f", contracts);
    }

    /** One-line summary for a plan's direction - NseChain.line's
     *  shape under an honest "BSE live chain" label. IV and gamma
     *  ride only when the chain actually carried an IV (BSE's
     *  public chain often has none - an absent IV is omitted,
     *  never estimated into the line). */
    public static String line(NseChain.Probe p, boolean call) {
        if (p == null) return "";
        double oiSide = call ? p.ceOi : p.peOi;
        double oiChg = call ? p.ceOiChg : p.peOiChg;
        double iv = call ? p.ceIv : p.peIv;
        double ltp = call ? p.ceLtp : p.peLtp;
        String ivPart = "";
        if (iv > 0) {
            int dte = p.expiry == null ? 0
                    : (int) java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(IST), p.expiry);
            double gamma = OptionsMath.bsGamma(p.underlying, p.strike, Math.max(dte, 0), iv);
            ivPart = String.format(Locale.US, " · IV %.1f%%%s", iv,
                    Double.isNaN(gamma) ? "" : String.format(Locale.US, " · Γ %.4f", gamma));
        }
        return String.format(Locale.US,
                "BSE live chain: %.0f %s · OI %s (%s%s today) · LTP ₹%.1f%s · chain PCR %.2f",
                p.strike, call ? "CE" : "PE", oi(oiSide),
                oiChg >= 0 ? "+" : "−", oi(Math.abs(oiChg)),
                ltp, ivPart, p.pcr);
    }
}
