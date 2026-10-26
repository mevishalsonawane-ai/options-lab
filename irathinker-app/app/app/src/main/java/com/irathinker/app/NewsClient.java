package com.irathinker.app;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Headlines from Google News RSS (free, no API key) - the catalyst
 *  stage of the pipeline. Fails soft: an empty list means "no headlines
 *  fetched", never a crash. */
public final class NewsClient {

    public static final class Item {
        public String title = "";
        public String source = "";
        public String when = "";
    }

    private static final String[] BULL_WORDS = {
            "surge", "rally", "gain", "jump", "soar", "record high", "beat", "upgrade",
            "bullish", "outperform", "wins", "order win", "profit rises", "growth", "buy"};
    private static final String[] BEAR_WORDS = {
            "fall", "drop", "plunge", "crash", "slump", "miss", "downgrade", "bearish",
            "fraud", "probe", "loss", "decline", "sell-off", "selloff", "warning", "ban"};

    /** Crude headline tone from a keyword lexicon: +1 bullish, -1 bearish, 0 mixed/none.
     *  A context hint only - never a signal by itself. */
    public static int toneOf(String title) {
        if (title == null) return 0;
        String t = " " + title.toLowerCase() + " ";
        int score = 0;
        for (String w : BULL_WORDS) if (t.contains(w)) score++;
        for (String w : BEAR_WORDS) if (t.contains(w)) score--;
        return Integer.signum(score);
    }

    public static String toneLabel(int tone) {
        return tone > 0 ? "BULLISH TONE" : tone < 0 ? "BEARISH TONE" : "MIXED TONE";
    }

    public static List<Item> fetch(String query, int max) {
        List<Item> out = new ArrayList<>();
        HttpURLConnection c = null;
        try {
            String u = "https://news.google.com/rss/search?q="
                    + URLEncoder.encode(query, "UTF-8")
                    + "&hl=en-IN&gl=IN&ceid=IN:en";
            c = (HttpURLConnection) new URL(u).openConnection();
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) IraThinker/4.0");
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            if (c.getResponseCode() != 200) return out;
            try (InputStream in = c.getInputStream()) {
                XmlPullParser p = Xml.newPullParser();
                p.setInput(in, null);
                Item cur = null;
                String tag = null;
                int ev = p.getEventType();
                while (ev != XmlPullParser.END_DOCUMENT && out.size() < max) {
                    if (ev == XmlPullParser.START_TAG) {
                        tag = p.getName();
                        if ("item".equals(tag)) cur = new Item();
                    } else if (ev == XmlPullParser.TEXT && cur != null && tag != null) {
                        String text = p.getText() == null ? "" : p.getText().trim();
                        if ("title".equals(tag) && cur.title.isEmpty()) cur.title = text;
                        else if ("source".equals(tag)) cur.source = text;
                        else if ("pubDate".equals(tag)) cur.when = text;
                    } else if (ev == XmlPullParser.END_TAG) {
                        if ("item".equals(p.getName()) && cur != null) {
                            if (!cur.title.isEmpty()) out.add(cur);
                            cur = null;
                        }
                        tag = null;
                    }
                    ev = p.next();
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.disconnect();
        }
        return out;
    }
}
