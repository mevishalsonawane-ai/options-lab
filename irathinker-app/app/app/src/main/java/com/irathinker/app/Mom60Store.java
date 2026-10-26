package com.irathinker.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** The mom60 paper log (v53): every fired BANKNIFTY mom60 signal,
 *  recorded at fire time and resolved automatically against the
 *  1-minute spot path repriced through Black-Scholes (Mom60's walk).
 *  Its own store on purpose - the equity Paper Journal and the Live
 *  Ledger keep their exact meanings; this is the strategy's record.
 *  Resolution reasons: STOP (premium touched -30% first), TIME (the
 *  60-minute hold ended), CLOSE (the day's data ended first).
 *
 *  v55: the same record shape serves the FINNIFTY and Sensex paper
 *  tracks, one JSON file per index (the file-name overloads
 *  below). The BANKNIFTY log keeps its original file and its
 *  original methods - they delegate with (BN_FILE, Mom60.BN). */
public final class Mom60Store {

    /** The BANKNIFTY log's file (v53 original). */
    public static final String BN_FILE = "mom60_log.json";
    /** The FINNIFTY paper track's file (v55). */
    public static final String FIN_FILE = "mom60_fin_log.json";
    /** The Sensex paper track's file (v55). */
    public static final String SENSEX_FILE = "mom60_sensex_log.json";

    public static final class Rec {
        public long id;
        public String date = "";        // IST yyyy-MM-dd of the signal
        public long barTs, entryTs, exitTs;
        public boolean call;
        public double ret60, spot, strike, iv, entryPremium, stopPremium;
        public String expiry = "";      // ISO date
        public boolean closed;
        public long exitBarTs;
        public double exitPremium;
        public String exitReason = "";  // STOP / TIME / CLOSE
        public double pnlPct;

        public Mom60.Signal toSignal() {
            Mom60.Signal s = new Mom60.Signal();
            s.barTs = barTs;
            s.entryTs = entryTs;
            s.exitTs = exitTs;
            s.date = LocalDate.parse(date);
            s.call = call;
            s.ret60 = ret60;
            s.spot = spot;
            s.strike = strike;
            s.expiry = LocalDate.parse(expiry);
            s.iv = iv;
            s.entryPremium = entryPremium;
            s.stopPremium = stopPremium;
            return s;
        }

        static Rec fromSignal(Mom60.Signal s) {
            Rec r = new Rec();
            r.id = s.entryTs;
            r.date = s.date.toString();
            r.barTs = s.barTs;
            r.entryTs = s.entryTs;
            r.exitTs = s.exitTs;
            r.call = s.call;
            r.ret60 = s.ret60;
            r.spot = s.spot;
            r.strike = s.strike;
            r.expiry = s.expiry.toString();
            r.iv = s.iv;
            r.entryPremium = s.entryPremium;
            r.stopPremium = s.stopPremium;
            return r;
        }

        void apply(Mom60.Resolution res) {
            closed = true;
            exitBarTs = res.exitBarTs;
            exitPremium = res.exitPremium;
            exitReason = res.reason;
            pnlPct = res.pnlPct;
        }
    }

    private static File file(Context ctx) {
        return new File(ctx.getFilesDir(), BN_FILE);
    }

    private static File file(Context ctx, String name) {
        return new File(ctx.getFilesDir(), name);
    }

    public static synchronized List<Rec> load(Context ctx) {
        return load(ctx, BN_FILE);
    }

    public static synchronized List<Rec> load(Context ctx, String name) {
        List<Rec> out = new ArrayList<>();
        try {
            File f = file(ctx, name);
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
                Rec r = new Rec();
                r.id = o.optLong("id");
                r.date = o.optString("date");
                r.barTs = o.optLong("barTs");
                r.entryTs = o.optLong("entryTs");
                r.exitTs = o.optLong("exitTs");
                r.call = o.optBoolean("call", true);
                r.ret60 = o.optDouble("ret60");
                r.spot = o.optDouble("spot");
                r.strike = o.optDouble("strike");
                r.expiry = o.optString("expiry");
                r.iv = o.optDouble("iv");
                r.entryPremium = o.optDouble("entryPremium");
                r.stopPremium = o.optDouble("stopPremium");
                r.closed = o.optBoolean("closed");
                r.exitBarTs = o.optLong("exitBarTs");
                r.exitPremium = o.optDouble("exitPremium");
                r.exitReason = o.optString("exitReason");
                r.pnlPct = o.optDouble("pnlPct");
                out.add(r);
            }
        } catch (Exception ignored) { }
        return out;
    }

    public static synchronized void save(Context ctx, List<Rec> recs) {
        save(ctx, BN_FILE, recs);
    }

    public static synchronized void save(Context ctx, String name, List<Rec> recs) {
        try {
            JSONArray arr = new JSONArray();
            for (Rec r : recs) {
                JSONObject o = new JSONObject();
                o.put("id", r.id);
                o.put("date", r.date);
                o.put("barTs", r.barTs);
                o.put("entryTs", r.entryTs);
                o.put("exitTs", r.exitTs);
                o.put("call", r.call);
                o.put("ret60", r.ret60);
                o.put("spot", r.spot);
                o.put("strike", r.strike);
                o.put("expiry", r.expiry);
                o.put("iv", r.iv);
                o.put("entryPremium", r.entryPremium);
                o.put("stopPremium", r.stopPremium);
                o.put("closed", r.closed);
                o.put("exitBarTs", r.exitBarTs);
                o.put("exitPremium", r.exitPremium);
                o.put("exitReason", r.exitReason);
                o.put("pnlPct", r.pnlPct);
                arr.put(o);
            }
            try (FileOutputStream out = new FileOutputStream(file(ctx, name))) {
                out.write(arr.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) { }
    }

    /** The still-open record, or null. At most one can exist - the
     *  watcher never holds two positions. */
    public static Rec openRec(List<Rec> all) {
        for (Rec r : all) if (!r.closed) return r;
        return null;
    }

    /** Rebuild the watcher for one date from the log: the open
     *  position (if it belongs to that date), the last entry time
     *  (the spacing rule), and the last bar already walked - an open
     *  position re-walks from its signal bar (its resolution is
     *  deterministic on the path), a closed day resumes after its
     *  last exit bar, a fresh day from the first bar. */
    public static Mom60.Watcher watcherFor(List<Rec> all, LocalDate date) {
        return watcherFor(all, date, Mom60.BN);
    }

    /** The same rebuild for an index config (v55): the watcher and
     *  its open signal carry the config, so a re-walked position
     *  reprices and any new fire uses that index's threshold,
     *  strike step and expiry rule. Resolution itself reads only
     *  the signal's stored strike/expiry/IV, exactly as for BN. */
    public static Mom60.Watcher watcherFor(List<Rec> all, LocalDate date, Mom60.Cfg cfg) {
        Mom60.Signal open = null;
        long lastEntry = 0;
        long lastBar = Long.MIN_VALUE;
        String dk = date.toString();
        for (Rec r : all) {
            if (!dk.equals(r.date)) continue;
            lastEntry = Math.max(lastEntry, r.entryTs);
            if (!r.closed) {
                open = r.toSignal();
                open.cfg = cfg;
                lastBar = Math.max(lastBar, r.barTs);
            } else {
                lastBar = Math.max(lastBar, r.exitBarTs);
            }
        }
        return new Mom60.Watcher(cfg, open, lastEntry, lastBar);
    }
}
