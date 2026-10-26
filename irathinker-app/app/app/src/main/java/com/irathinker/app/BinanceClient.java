package com.irathinker.app;

import org.json.JSONArray;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Binance gold client (v52) - the XAU/USD feed, after Yahoo delisted
 *  spot metals (XAUUSD=X answers "symbol may be delisted"). No API key:
 *  public market data only.
 *
 *  PRIMARY: the XAUUSDT perpetual (USD-M futures) 1-minute klines from
 *  fapi.binance.com. The perp tracks spot gold via funding (basis
 *  measured within ~0.1% on like-for-like closes, Oct 2026) and trades
 *  24/7 - weekends included, when spot is closed.
 *
 *  FALLBACK: if fapi fails for any reason (HTTP 451 geo-block, 403,
 *  timeout, empty page), PAXGUSDT spot klines from Binance's official
 *  public mirror data-api.binance.vision (PAX Gold - a gold-backed
 *  token; a reference price, labelled as such in the UI, never passed
 *  off as the perp). If both fail, the last good chart for the same
 *  range/interval is served - the same honest-stale behaviour every
 *  other symbol gets from its caches - and only a cold start with no
 *  history at all throws.
 *
 *  Charts come back in the YahooClient.Chart shape the whole engine
 *  already consumes (epoch-second bar times, OHLCV arrays), so gold
 *  flows through scan / plans / Prediction / Lab / Ledger untouched.
 *  Binance kline open times are UTC milliseconds; the app is
 *  IST-anchored and converts by epoch arithmetic (IST = UTC+5:30, a
 *  whole number of 5-minute buckets, so epoch-aligned 5m resamples
 *  land exactly on IST bar boundaries).
 *
 *  Lookback policy: callers ask in Yahoo range language ("5d", "60d",
 *  "6mo", "1y"); gold fetches exactly that calendar lookback of
 *  1-minute bars (60d = ~86k bars, ~58 fapi pages at the max page of
 *  1500, weight 10/page - far inside the 2,400/min budget) and
 *  resamples to 5m when the caller asked for 5m. Results ride the
 *  YahooClient short-TTL cache like every other symbol. */
public final class BinanceClient {

    public static final String PERP = "XAUUSDT";
    public static final String FALLBACK = "PAXGUSDT";
    private static final String FAPI = "https://fapi.binance.com";
    private static final String VISION = "https://data-api.binance.vision";
    private static final int FAPI_PAGE = 1500;   // fapi klines max limit
    private static final int VISION_PAGE = 1000; // spot klines max limit

    public enum Feed { NONE, PERP, PAXG }

    /** Which Binance feed last answered - the UI labels gold with it,
     *  so a PAXG reference price is never mistaken for the perp. */
    private static volatile Feed lastFeed = Feed.NONE;
    private static volatile long lastFeedAt = 0;

    public static Feed lastFeed() { return lastFeed; }
    public static long lastFeedAt() { return lastFeedAt; }

    public static boolean isGold(String symbol) { return PERP.equals(symbol); }

    /** Feed selection, pure so the harness can pin it: the perp wins
     *  whenever it answered; PAXG only when the perp did not. */
    public static Feed chooseFeed(boolean perpOk, boolean paxgOk) {
        if (perpOk) return Feed.PERP;
        if (paxgOk) return Feed.PAXG;
        return Feed.NONE;
    }

    /** Short tag appended to gold's name on board rows. */
    public static String feedTag() {
        switch (lastFeed) {
            case PERP: return " · Binance";
            case PAXG: return " · PAXG ref";
            default: return "";
        }
    }

    /** Full feed line for the Detail header - says exactly which
     *  Binance price the screen is showing. */
    public static String feedLine() {
        switch (lastFeed) {
            case PERP: return "Feed: Binance · XAUUSDT perpetual (live)";
            case PAXG: return "Feed: PAXG reference (Binance spot gold token)"
                    + " — the perpetual feed did not answer; PAXG tracks spot within ~0.1%";
            default: return "Feed: Binance · no live answer yet — showing last data";
        }
    }

    // Last good chart per range|interval, served when both feeds fail.
    private static final java.util.Map<String, YahooClient.Chart> LAST_GOOD =
            java.util.Collections.synchronizedMap(
                    new java.util.LinkedHashMap<String, YahooClient.Chart>(16, 0.75f, true) {
                        @Override
                        protected boolean removeEldestEntry(
                                java.util.Map.Entry<String, YahooClient.Chart> e) {
                            return size() > 12;
                        }
                    });

    /** Engine entry point (called by YahooClient.fetch for XAUUSDT):
     *  a Yahoo-shaped chart for the Yahoo-style range/interval pair. */
    public static YahooClient.Chart chart(String range, String interval) throws Exception {
        String key = range + "|" + interval;
        YahooClient.Chart ch = null;
        boolean perpOk = false, paxgOk = false;
        try {
            ch = fetchFeed(FAPI, "/fapi/v1/klines", PERP, range, interval, FAPI_PAGE);
            perpOk = ch != null && ch.ts.length > 0;
        } catch (Exception ignored) { }
        if (!perpOk) {
            ch = null;
            try {
                ch = fetchFeed(VISION, "/api/v3/klines", FALLBACK, range, interval, VISION_PAGE);
                paxgOk = ch != null && ch.ts.length > 0;
            } catch (Exception ignored) { }
        }
        Feed f = chooseFeed(perpOk, paxgOk);
        if (f != Feed.NONE) {
            lastFeed = f;
            lastFeedAt = System.currentTimeMillis();
            LAST_GOOD.put(key, ch);
            return ch;
        }
        YahooClient.Chart stale = LAST_GOOD.get(key);
        if (stale != null) return stale;
        throw new Exception("Binance gold: both feeds unreachable");
    }

    /** One feed's chart for the requested window. 1m and 5m requests
     *  are served from the 1-minute series (5m resampled); 1d uses
     *  Binance's native daily klines. */
    private static YahooClient.Chart fetchFeed(String base, String path, String symbol,
                                               String range, String interval,
                                               int pageLimit) throws Exception {
        int days = lookbackDays(range);
        long nowMs = System.currentTimeMillis();
        long startMs = nowMs - days * 86_400_000L;
        if ("1d".equals(interval)) {
            YahooClient.Chart d = fetchKlines(base, path, symbol, "1d", startMs, nowMs, pageLimit);
            finishMeta(d, false);
            return d;
        }
        YahooClient.Chart m1 = fetchKlines(base, path, symbol, "1m", startMs, nowMs, pageLimit);
        YahooClient.Chart out = "1m".equals(interval) ? m1 : resample5m(m1);
        finishMeta(out, true);
        return out;
    }

    /** Yahoo range string -> calendar lookback in days. */
    public static int lookbackDays(String range) {
        if (range == null) return 5;
        try {
            if (range.endsWith("mo")) return Integer.parseInt(range.substring(0, range.length() - 2)) * 31;
            if (range.endsWith("y")) return Integer.parseInt(range.substring(0, range.length() - 1)) * 365;
            if (range.endsWith("d")) return Integer.parseInt(range.substring(0, range.length() - 1));
        } catch (Exception ignored) { }
        return 5;
    }

    /** Paginate klines from startMs to endMs, oldest first. The page
     *  cursor advances to the last bar's open + one step; a short page
     *  ends the walk (the connector's documented pagination). */
    private static YahooClient.Chart fetchKlines(String base, String path, String symbol,
                                                 String intervalParam, long startMs, long endMs,
                                                 int pageLimit) throws Exception {
        long stepMs = "1d".equals(intervalParam) ? 86_400_000L : 60_000L;
        List<Long> ts = new ArrayList<>();
        List<Double> o = new ArrayList<>(), h = new ArrayList<>(),
                l = new ArrayList<>(), c = new ArrayList<>();
        List<Long> v = new ArrayList<>();
        long cursor = startMs;
        for (int page = 0; page < 400; page++) {
            String u = base + path + "?symbol=" + symbol + "&interval=" + intervalParam
                    + "&limit=" + pageLimit + "&startTime=" + cursor + "&endTime=" + endMs;
            JSONArray arr = new JSONArray(httpGet(u));
            if (arr.length() == 0) break;
            long lastOpen = -1;
            for (int i = 0; i < arr.length(); i++) {
                JSONArray k = arr.getJSONArray(i);
                lastOpen = k.getLong(0);
                ts.add(lastOpen / 1000L);
                o.add(k.getDouble(1));
                h.add(k.getDouble(2));
                l.add(k.getDouble(3));
                c.add(k.getDouble(4));
                v.add(Math.round(k.getDouble(5)));
            }
            if (arr.length() < pageLimit || lastOpen < 0) break;
            cursor = lastOpen + stepMs;
            if (cursor > endMs) break;
        }
        YahooClient.Chart ch = new YahooClient.Chart();
        int n = ts.size();
        ch.ts = new long[n];
        ch.open = new double[n];
        ch.high = new double[n];
        ch.low = new double[n];
        ch.close = new double[n];
        ch.volume = new long[n];
        for (int i = 0; i < n; i++) {
            ch.ts[i] = ts.get(i);
            ch.open[i] = o.get(i);
            ch.high[i] = h.get(i);
            ch.low[i] = l.get(i);
            ch.close[i] = c.get(i);
            ch.volume[i] = v.get(i);
        }
        return ch;
    }

    /** Parse one klines response page (pure; the harness pins the
     *  field order: openTime ms, open, high, low, close, volume). */
    public static YahooClient.Chart parseKlines(String json) throws Exception {
        JSONArray arr = new JSONArray(json);
        YahooClient.Chart ch = new YahooClient.Chart();
        int n = arr.length();
        ch.ts = new long[n];
        ch.open = new double[n];
        ch.high = new double[n];
        ch.low = new double[n];
        ch.close = new double[n];
        ch.volume = new long[n];
        for (int i = 0; i < n; i++) {
            JSONArray k = arr.getJSONArray(i);
            ch.ts[i] = k.getLong(0) / 1000L;
            ch.open[i] = k.getDouble(1);
            ch.high[i] = k.getDouble(2);
            ch.low[i] = k.getDouble(3);
            ch.close[i] = k.getDouble(4);
            ch.volume[i] = Math.round(k.getDouble(5));
        }
        return ch;
    }

    /** Aggregate 1-minute bars into 5-minute bars. Buckets are aligned
     *  to the epoch (ts - ts%300); IST is UTC+5:30 - a whole number of
     *  5-minute buckets - so an epoch bucket IS an IST bucket (a bar
     *  opening 09:15 IST is the 03:45 UTC bucket). */
    public static YahooClient.Chart resample5m(YahooClient.Chart m1) {
        YahooClient.Chart out = new YahooClient.Chart();
        if (m1 == null || m1.ts.length == 0) return out;
        List<Long> ts = new ArrayList<>();
        List<Double> o = new ArrayList<>(), h = new ArrayList<>(),
                l = new ArrayList<>(), c = new ArrayList<>();
        List<Long> v = new ArrayList<>();
        long bucket = Long.MIN_VALUE;
        for (int i = 0; i < m1.ts.length; i++) {
            long b = m1.ts[i] - Math.floorMod(m1.ts[i], 300L);
            if (b != bucket) {
                bucket = b;
                ts.add(b);
                o.add(m1.open[i]);
                h.add(m1.high[i]);
                l.add(m1.low[i]);
                c.add(m1.close[i]);
                v.add(m1.volume[i]);
            } else {
                int j = ts.size() - 1;
                if (!Double.isNaN(m1.high[i])) h.set(j, Double.isNaN(h.get(j)) ? m1.high[i] : Math.max(h.get(j), m1.high[i]));
                if (!Double.isNaN(m1.low[i])) l.set(j, Double.isNaN(l.get(j)) ? m1.low[i] : Math.min(l.get(j), m1.low[i]));
                if (!Double.isNaN(m1.close[i])) c.set(j, m1.close[i]);
                v.set(j, v.get(j) + m1.volume[i]);
            }
        }
        int n = ts.size();
        out.ts = new long[n];
        out.open = new double[n];
        out.high = new double[n];
        out.low = new double[n];
        out.close = new double[n];
        out.volume = new long[n];
        for (int i = 0; i < n; i++) {
            out.ts[i] = ts.get(i);
            out.open[i] = o.get(i);
            out.high[i] = h.get(i);
            out.low[i] = l.get(i);
            out.close[i] = c.get(i);
            out.volume[i] = v.get(i);
        }
        out.metaPrice = m1.metaPrice;
        out.prevClose = m1.prevClose;
        return out;
    }

    /** metaPrice = last traded close; for intraday charts prevClose =
     *  the close of the last bar of the previous IST day in the series
     *  (what Yahoo's chartPreviousClose means to the scan's fallback). */
    private static void finishMeta(YahooClient.Chart ch, boolean intraday) {
        if (ch.ts.length == 0) return;
        for (int i = ch.ts.length - 1; i >= 0; i--) {
            if (!Double.isNaN(ch.close[i])) { ch.metaPrice = ch.close[i]; break; }
        }
        if (!intraday) return;
        long lastDay = Math.floorDiv(ch.ts[ch.ts.length - 1] + 19800L, 86400L);
        for (int i = ch.ts.length - 1; i >= 0; i--) {
            long d = Math.floorDiv(ch.ts[i] + 19800L, 86400L);
            if (d < lastDay && !Double.isNaN(ch.close[i])) { ch.prevClose = ch.close[i]; break; }
        }
    }

    private static String httpGet(String u) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) IraThinker/1.0");
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        int code = c.getResponseCode();
        if (code != 200) throw new Exception("HTTP " + code + " for Binance klines");
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }
}
