package com.irathinker.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Paper-trading journal storage: a small JSON file in app-private
 *  storage. Trades are plans the user chose to track - no real money,
 *  no broker. Outcomes resolve against real bars. */
public final class PaperStore {

    public static final class Trade {
        public long id;
        public String yahoo, name;
        public boolean up;
        public double entry, stop, target;
        public int qty;
        public long breakTs;
        public String date;
        public boolean closed;
        public double exit;
        public String exitReason = ""; // TARGET / STOP / EOD / MANUAL

        public double pnlRs() {
            if (!closed) return 0;
            return qty * (exit - entry) * (up ? 1 : -1);
        }
        public double retPct() {
            if (!closed || entry <= 0) return 0;
            return (exit - entry) / entry * 100 * (up ? 1 : -1);
        }
    }

    private static File file(Context ctx) {
        return new File(ctx.getFilesDir(), "paper_trades.json");
    }

    public static synchronized List<Trade> load(Context ctx) {
        List<Trade> out = new ArrayList<>();
        try {
            File f = file(ctx);
            if (!f.exists()) return out;
            byte[] buf = new byte[(int) f.length()];
            try (FileInputStream in = new FileInputStream(f)) {
                int read = 0;
                while (read < buf.length) {
                    int n = in.read(buf, read, buf.length - read);
                    if (n < 0) break;
                    read += n;
                }
            }
            JSONArray arr = new JSONArray(new String(buf, StandardCharsets.UTF_8));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Trade t = new Trade();
                t.id = o.optLong("id");
                t.yahoo = o.optString("yahoo");
                t.name = o.optString("name");
                t.up = o.optBoolean("up", true);
                t.entry = o.optDouble("entry");
                t.stop = o.optDouble("stop");
                t.target = o.optDouble("target");
                t.qty = o.optInt("qty", 1);
                t.breakTs = o.optLong("breakTs");
                t.date = o.optString("date");
                t.closed = o.optBoolean("closed");
                t.exit = o.optDouble("exit");
                t.exitReason = o.optString("exitReason");
                out.add(t);
            }
        } catch (Exception ignored) { }
        return out;
    }

    public static synchronized void save(Context ctx, List<Trade> trades) {
        try {
            JSONArray arr = new JSONArray();
            for (Trade t : trades) {
                JSONObject o = new JSONObject();
                o.put("id", t.id);
                o.put("yahoo", t.yahoo);
                o.put("name", t.name);
                o.put("up", t.up);
                o.put("entry", t.entry);
                o.put("stop", t.stop);
                o.put("target", t.target);
                o.put("qty", t.qty);
                o.put("breakTs", t.breakTs);
                o.put("date", t.date);
                o.put("closed", t.closed);
                o.put("exit", t.exit);
                o.put("exitReason", t.exitReason);
                arr.put(o);
            }
            try (FileOutputStream out = new FileOutputStream(file(ctx))) {
                out.write(arr.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) { }
    }

    public static synchronized Trade add(Context ctx, Analysis.Scan s, int qty) {
        List<Trade> all = load(ctx);
        Trade t = new Trade();
        t.id = System.currentTimeMillis();
        t.yahoo = s.sym.yahoo;
        t.name = s.sym.name;
        t.up = s.setup == Analysis.Setup.ORB_UP;
        t.entry = s.entry;
        t.stop = s.stop;
        t.target = s.target;
        t.qty = Math.max(1, qty);
        t.breakTs = s.breakTs;
        t.date = java.time.Instant.ofEpochSecond(s.breakTs)
                .atZone(java.time.ZoneId.of("Asia/Kolkata")).toLocalDate().toString();
        all.add(t);
        save(ctx, all);
        return t;
    }

    /** Resolve every open trade against the latest bars. Returns how
     *  many trades this call closed. Blocking - call off the UI thread. */
    public static int resolveAll(Context ctx) {
        List<Trade> all = load(ctx);
        int closedNow = 0;
        boolean dirty = false;
        String today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).toString();
        for (Trade t : all) {
            if (t.closed) continue;
            try {
                YahooClient.Chart intra = YahooClient.fetch(t.yahoo, "5d", "5m");
                int o = Analysis.outcomeOf(intra, t.breakTs, t.stop, t.target, t.up);
                if (o > 0) {
                    t.closed = true; t.exit = t.target; t.exitReason = "TARGET";
                } else if (o < 0) {
                    t.closed = true; t.exit = t.stop; t.exitReason = "STOP";
                } else if (t.date.compareTo(today) < 0) {
                    // day is over and neither hit: close at that day's last close
                    double c = lastCloseOfDay(intra, t.date);
                    if (!Double.isNaN(c)) {
                        t.closed = true; t.exit = c; t.exitReason = "EOD";
                    }
                }
                if (t.closed) { closedNow++; dirty = true; }
            } catch (Exception ignored) { }
        }
        if (dirty) save(ctx, all);
        return closedNow;
    }

    private static double lastCloseOfDay(YahooClient.Chart intra, String date) {
        java.time.ZoneId ist = java.time.ZoneId.of("Asia/Kolkata");
        double c = Double.NaN;
        for (int i = 0; i < intra.ts.length; i++) {
            String d = java.time.Instant.ofEpochSecond(intra.ts[i]).atZone(ist).toLocalDate().toString();
            if (d.equals(date) && !Double.isNaN(intra.close[i])) c = intra.close[i];
        }
        return c;
    }
}
