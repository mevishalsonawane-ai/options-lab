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

/** The strangle paper log (v54): every Monday paper entry the
 *  watcher fired, resolved automatically at its exit - Thursday's
 *  close repriced through Black-Scholes, or expiry settlement at
 *  intrinsic value in an expiry week (Strangle's walk). Its own
 *  store on purpose, like mom60's - the equity Paper Journal, the
 *  Live Ledger and the mom60 log keep their exact meanings; this
 *  is the strangle's forward record while the strategy stays
 *  parked behind its ~Rs 2.14L capital gate. Resolution reasons:
 *  THU (bought back at the Thu close), EXPIRY (settled at the
 *  expiry close), CLOSE (bookkeeping-flat: the tape aged out
 *  before the week could be priced). */
public final class StrangleStore {

    public static final class Rec {
        public long id;
        public String week = "";        // IST yyyy-MM-dd of the entry Monday
        public long barTs, entryTs;
        public double spot, atmStrike, ceStrike, peStrike, iv;
        public double cePremium, pePremium, credit;   // points
        public String expiry = "";      // ISO date
        public String exitDate = "";    // ISO date (Thu, or the expiry)
        public boolean expiryWeek;
        public boolean closed;
        public double exitSpot, exitCost;
        public String exitReason = "";  // THU / EXPIRY / CLOSE
        public double grossPct, netPct, pnlRs;

        public Strangle.Entry toEntry() {
            Strangle.Entry e = new Strangle.Entry();
            e.monday = LocalDate.parse(week);
            e.barTs = barTs;
            e.entryTs = entryTs;
            e.spot = spot;
            e.atmStrike = atmStrike;
            e.ceStrike = ceStrike;
            e.peStrike = peStrike;
            e.expiry = LocalDate.parse(expiry);
            e.exitDate = LocalDate.parse(exitDate);
            e.expiryWeek = expiryWeek;
            e.iv = iv;
            e.cePremium = cePremium;
            e.pePremium = pePremium;
            e.credit = credit;
            return e;
        }

        static Rec fromEntry(Strangle.Entry e) {
            Rec r = new Rec();
            r.id = e.entryTs;
            r.week = e.monday.toString();
            r.barTs = e.barTs;
            r.entryTs = e.entryTs;
            r.spot = e.spot;
            r.atmStrike = e.atmStrike;
            r.ceStrike = e.ceStrike;
            r.peStrike = e.peStrike;
            r.expiry = e.expiry.toString();
            r.exitDate = e.exitDate.toString();
            r.expiryWeek = e.expiryWeek;
            r.iv = e.iv;
            r.cePremium = e.cePremium;
            r.pePremium = e.pePremium;
            r.credit = e.credit;
            return r;
        }

        void apply(Strangle.Grade g) {
            closed = true;
            exitSpot = g.exitSpot;
            exitCost = g.exitCost;
            exitReason = g.reason;
            grossPct = g.grossPct;
            netPct = g.netPct;
            pnlRs = g.pnlRs;
        }

        /** The tape no longer reaches back to this week's exit:
         *  it can never be priced. Close it flat on the books
         *  rather than leaving it open forever (mom60's rule). */
        void applyFlat() {
            closed = true;
            exitSpot = 0;
            exitCost = credit;
            exitReason = "CLOSE";
            grossPct = 0;
            netPct = 0;
            pnlRs = 0;
        }
    }

    private static File file(Context ctx) {
        return new File(ctx.getFilesDir(), "strangle_log.json");
    }

    public static synchronized List<Rec> load(Context ctx) {
        List<Rec> out = new ArrayList<>();
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
                Rec r = new Rec();
                r.id = o.optLong("id");
                r.week = o.optString("week");
                r.barTs = o.optLong("barTs");
                r.entryTs = o.optLong("entryTs");
                r.spot = o.optDouble("spot");
                r.atmStrike = o.optDouble("atmStrike");
                r.ceStrike = o.optDouble("ceStrike");
                r.peStrike = o.optDouble("peStrike");
                r.expiry = o.optString("expiry");
                r.exitDate = o.optString("exitDate");
                r.expiryWeek = o.optBoolean("expiryWeek");
                r.iv = o.optDouble("iv");
                r.cePremium = o.optDouble("cePremium");
                r.pePremium = o.optDouble("pePremium");
                r.credit = o.optDouble("credit");
                r.closed = o.optBoolean("closed");
                r.exitSpot = o.optDouble("exitSpot");
                r.exitCost = o.optDouble("exitCost");
                r.exitReason = o.optString("exitReason");
                r.grossPct = o.optDouble("grossPct");
                r.netPct = o.optDouble("netPct");
                r.pnlRs = o.optDouble("pnlRs");
                out.add(r);
            }
        } catch (Exception ignored) { }
        return out;
    }

    public static synchronized void save(Context ctx, List<Rec> recs) {
        try {
            JSONArray arr = new JSONArray();
            for (Rec r : recs) {
                JSONObject o = new JSONObject();
                o.put("id", r.id);
                o.put("week", r.week);
                o.put("barTs", r.barTs);
                o.put("entryTs", r.entryTs);
                o.put("spot", r.spot);
                o.put("atmStrike", r.atmStrike);
                o.put("ceStrike", r.ceStrike);
                o.put("peStrike", r.peStrike);
                o.put("expiry", r.expiry);
                o.put("exitDate", r.exitDate);
                o.put("expiryWeek", r.expiryWeek);
                o.put("iv", r.iv);
                o.put("cePremium", r.cePremium);
                o.put("pePremium", r.pePremium);
                o.put("credit", r.credit);
                o.put("closed", r.closed);
                o.put("exitSpot", r.exitSpot);
                o.put("exitCost", r.exitCost);
                o.put("exitReason", r.exitReason);
                o.put("grossPct", r.grossPct);
                o.put("netPct", r.netPct);
                o.put("pnlRs", r.pnlRs);
                arr.put(o);
            }
            try (FileOutputStream out = new FileOutputStream(file(ctx))) {
                out.write(arr.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) { }
    }

    /** The still-open record, or null. Weeks are independent -
     *  more than one can be open only when an old week's tape went
     *  missing; resolution walks them oldest first. */
    public static Rec openRec(List<Rec> all) {
        for (Rec r : all) if (!r.closed) return r;
        return null;
    }

    /** The record entered on a given Monday, or null - the
     *  one-entry-per-week dedupe. */
    public static Rec recForWeek(List<Rec> all, LocalDate monday) {
        String wk = monday.toString();
        for (Rec r : all) if (wk.equals(r.week)) return r;
        return null;
    }
}

