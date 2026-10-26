package com.irathinker.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Minimal Yahoo Finance chart client (no API key). Parses the v8 chart
 *  JSON into plain arrays. Missing bars come through as NaN. */
public final class YahooClient {

    public static final class Chart {
        public long[] ts = new long[0];
        public double[] open = new double[0];
        public double[] high = new double[0];
        public double[] low = new double[0];
        public double[] close = new double[0];
        public long[] volume = new long[0];
        public double metaPrice = Double.NaN;
        public double prevClose = Double.NaN;
    }

    // ---- short-TTL response cache (v48) ----
    // Within one refresh tick the batch scan, the Time Machine
    // odds pass, the alert tickets and the ledger all ask for the
    // same charts. They now share ONE fetch per symbol+range; the
    // TTLs sit below the app's own refresh cadence (30-60s in the
    // hot window) so nothing served is ever a tick old. Charts are
    // treated as read-only by every caller.
    private static final int CACHE_MAX = 40;
    private static final java.util.Map<String, Object[]> CACHE =
            java.util.Collections.synchronizedMap(
                    new java.util.LinkedHashMap<String, Object[]>(64, 0.75f, true) {
                        @Override
                        protected boolean removeEldestEntry(java.util.Map.Entry<String, Object[]> e) {
                            return size() > CACHE_MAX;
                        }
                    });

    private static long ttlMs(String interval) {
        if ("1m".equals(interval)) return 25_000;
        if ("1d".equals(interval)) return 180_000;
        return 45_000;
    }

    public static Chart fetch(String symbol, String range, String interval) throws Exception {
        String key = symbol + "|" + range + "|" + interval;
        Object[] hit = CACHE.get(key);
        if (hit != null && System.currentTimeMillis() - (Long) hit[0] < ttlMs(interval)) {
            return (Chart) hit[1];
        }
        // v52: XAU/USD gold is not a Yahoo symbol at all (Yahoo delisted
        // spot metals) - BinanceClient serves it in this same Chart
        // shape, through this same cache, so every caller downstream
        // (scan, Detail, Lab, Ledger, Predict) is source-blind.
        if (BinanceClient.isGold(symbol)) {
            Chart ch = BinanceClient.chart(range, interval);
            if (ch.ts.length > 0) {
                CACHE.put(key, new Object[]{System.currentTimeMillis(), ch});
            }
            return ch;
        }
        // retries alternate Yahoo hosts (query1 -> query2): a dropped
        // packet or a single sick host should not cost a symbol its
        // scan (or its buy alert)
        Exception last = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            try {
                Chart ch = fetchOnce(symbol, range, interval, attempt % 2 == 0 ? "query1" : "query2");
                if (ch.ts.length > 0) {
                    CACHE.put(key, new Object[]{System.currentTimeMillis(), ch});
                }
                return ch;
            } catch (Exception e) {
                last = e;
                if (attempt < 3) {
                    try { Thread.sleep(700); } catch (InterruptedException ignored) { }
                }
            }
        }
        throw last;
    }

    private static Chart fetchOnce(String symbol, String range, String interval, String host) throws Exception {
        String u = "https://" + host + ".finance.yahoo.com/v8/finance/chart/"
                + URLEncoder.encode(symbol, "UTF-8")
                + "?range=" + range + "&interval=" + interval + "&includePrePost=false";
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) IraThinker/1.0");
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        int code = c.getResponseCode();
        if (code != 200) throw new Exception("HTTP " + code + " for " + symbol);
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
        }
        return parse(sb.toString());
    }

    static Chart parse(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        JSONObject result = root.getJSONObject("chart").getJSONArray("result").getJSONObject(0);
        Chart ch = new Chart();
        JSONObject meta = result.optJSONObject("meta");
        if (meta != null) {
            ch.metaPrice = meta.optDouble("regularMarketPrice", Double.NaN);
            ch.prevClose = meta.optDouble("chartPreviousClose", Double.NaN);
        }
        JSONArray tsa = result.optJSONArray("timestamp");
        if (tsa == null) return ch;
        int n = tsa.length();
        ch.ts = new long[n];
        for (int i = 0; i < n; i++) ch.ts[i] = tsa.getLong(i);
        JSONObject quote = result.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0);
        ch.open = arr(quote, "open", n);
        ch.high = arr(quote, "high", n);
        ch.low = arr(quote, "low", n);
        ch.close = arr(quote, "close", n);
        ch.volume = new long[n];
        JSONArray vol = quote.optJSONArray("volume");
        for (int i = 0; i < n; i++) {
            ch.volume[i] = (vol == null || vol.isNull(i)) ? 0 : vol.optLong(i, 0);
        }
        return ch;
    }

    private static double[] arr(JSONObject quote, String key, int n) {
        double[] out = new double[n];
        JSONArray a = quote.optJSONArray(key);
        for (int i = 0; i < n; i++) {
            out[i] = (a == null || a.isNull(i)) ? Double.NaN : a.optDouble(i, Double.NaN);
        }
        return out;
    }

    /** Intraday 5-minute bars, last 60 days (time machine). */
    public static Chart intraday(String symbol) throws Exception {
        return fetch(symbol, "60d", "5m");
    }

    /** Intraday 5-minute bars, last 5 days (list scanner - light). */
    public static Chart intradayShort(String symbol) throws Exception {
        return fetch(symbol, "5d", "5m");
    }

    /** Daily bars, 6 months (moving averages + RSI). */
    public static Chart daily(String symbol) throws Exception {
        return fetch(symbol, "6mo", "1d");
    }

    /** Keep only the bars of one IST date (yyyy-MM-dd via epoch day). */
    public static List<Integer> indicesOfDay(Chart ch, java.time.LocalDate day) {
        List<Integer> out = new ArrayList<>();
        java.time.ZoneId ist = java.time.ZoneId.of("Asia/Kolkata");
        for (int i = 0; i < ch.ts.length; i++) {
            java.time.LocalDate d = java.time.Instant.ofEpochSecond(ch.ts[i])
                    .atZone(ist).toLocalDate();
            if (d.equals(day)) out.add(i);
        }
        return out;
    }
}
