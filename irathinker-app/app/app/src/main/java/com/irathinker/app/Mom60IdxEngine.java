package com.irathinker.app;

import android.content.Context;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/** Live mom60 paper tracks for FINNIFTY and Sensex (v55): the
 *  BANKNIFTY watcher's exact pass (Mom60Engine, v53), run once
 *  per added index under its own Mom60.Cfg, its own switch and
 *  its own paper log. Since v57 (user decision 2026-10-25:
 *  every alert goes to ALL topics) each landed alert ALSO
 *  mirrors to the System Feed as a paper:true MOM60 JSON signal
 *  through the shared BuyAlerts.postFeedNow path - the signal's
 *  Cfg names the index, so one builder serves all three. The
 *  paper logs stay keyed to the human post exactly as before,
 *  and a failed feed post changes nothing here. They never
 *  write to the equity Live Ledger; Mom60Store (per-index file)
 *  is their record. Never throws - a bad pass must not disturb
 *  the engine around it. */
public final class Mom60IdxEngine {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    public static void check(Context ctx) {
        Prefs prefs = new Prefs(ctx);
        checkOne(ctx, prefs, Mom60.FIN, Mom60Store.FIN_FILE, prefs.mom60FinOn());
        checkOne(ctx, prefs, Mom60.SENSEX, Mom60Store.SENSEX_FILE, prefs.mom60SensexOn());
    }

    private static void checkOne(Context ctx, Prefs prefs, Mom60.Cfg cfg, String logFile, boolean alertsOn) {
        try {
            LocalDate today = LocalDate.now(IST);
            long nowSec = System.currentTimeMillis() / 1000;

            // One multi-day 1m fetch serves the live day and any
            // stale open position still waiting for its resolution.
            YahooClient.Chart m1;
            try {
                m1 = YahooClient.fetch(cfg.yahoo, "5d", "1m");
            } catch (Exception e) {
                return; // no tape, no judgements this pass
            }
            if (m1 == null || m1.ts.length == 0) return;

            List<Mom60Store.Rec> all = Mom60Store.load(ctx, logFile);
            boolean dirty = false;

            // 1) Stale open positions (an earlier date's): walk that
            //    day's bars to their end, then force-close on the
            //    last bar if the path never resolved them.
            for (Mom60Store.Rec r : all) {
                if (r.closed) continue;
                LocalDate d = LocalDate.parse(r.date);
                if (!d.isBefore(today)) continue;
                Mom60.Watcher w = Mom60Store.watcherFor(all, d, cfg);
                List<Mom60.Event> evs = w.process(m1, d, Mom60.closeEpoch(d) + 3600, false, Double.NaN);
                dirty |= applyResolutions(all, evs);
                if (!r.closed) {
                    Mom60.Resolution res = w.forceClose(m1, d);
                    if (res != null) {
                        r.apply(res);
                        dirty = true;
                    } else if (m1.ts.length > 0
                            && LocalDate.parse(r.date).isBefore(
                                    java.time.Instant.ofEpochSecond(m1.ts[0]).atZone(IST).toLocalDate())) {
                        // The tape no longer reaches back to this
                        // position's day (app off beyond the 1m
                        // window): it can never be repriced. Close
                        // it flat on the books rather than letting it
                        // block the watcher forever.
                        r.closed = true;
                        r.exitReason = "CLOSE";
                        r.exitPremium = r.entryPremium;
                        r.exitBarTs = 0;
                        r.pnlPct = 0;
                        dirty = true;
                    }
                }
            }
            // A stale position that still cannot be priced blocks a
            // new one today: one position at a time is the rule.
            boolean blocked = false;
            for (Mom60Store.Rec r : all) {
                if (!r.closed && LocalDate.parse(r.date).isBefore(today)) blocked = true;
            }

            // 2) Today: resolve the open position on every pass;
            //    fire only when the switch is on and nothing blocks.
            double iv = Double.NaN;
            try {
                double rv = OptionsMath.realizedVol(YahooClient.daily(cfg.yahoo));
                if (!Double.isNaN(rv)) iv = Math.max(10, Math.min(rv, 90));
            } catch (Exception ignored) { }
            boolean canFire = alertsOn && !blocked;
            Mom60.Watcher w = Mom60Store.watcherFor(all, today, cfg);
            List<Mom60.Event> evs = w.process(m1, today, nowSec, canFire, iv);
            for (Mom60.Event e : evs) {
                if (e.resolved != null) {
                    dirty |= applyResolution(all, e.resolved);
                } else if (e.fired != null) {
                    // The record is written only when the alert
                    // lands (the equity gate's discipline): a failed
                    // post leaves the bar unconsumed in the store's
                    // terms, so the next pass retries inside the
                    // freshness window instead of logging a signal
                    // the user never saw.
                    boolean ok = NtfyClient.postToAll(prefs, prefs.activeTopics(),
                            Mom60.title(e.fired), Mom60.body(e.fired),
                            e.fired.call ? "chart_with_upwards_trend" : "chart_with_downwards_trend");
                    if (ok) {
                        all.add(Mom60Store.Rec.fromSignal(e.fired));
                        dirty = true;
                        feedMirror(prefs, e.fired);
                    }
                }
            }
            // 3) Past the close, an unresolved position closes on
            //    the day's last bar.
            Mom60Store.Rec openToday = null;
            for (Mom60Store.Rec r : all) {
                if (!r.closed && today.toString().equals(r.date)) openToday = r;
            }
            if (openToday != null && nowSec >= Mom60.closeEpoch(today)) {
                Mom60.Watcher w2 = Mom60Store.watcherFor(all, today, cfg);
                Mom60.Resolution res = w2.forceClose(m1, today);
                if (res != null) { openToday.apply(res); dirty = true; }
            }
            if (dirty) Mom60Store.save(ctx, logFile, all);
        } catch (Throwable ignored) {
            // engine discipline: never take the pass down with us
        }
    }

    /** System Feed mirror (v57): a landed FINNIFTY / Sensex
     *  mom60 alert is also posted to the machine topic as a
     *  paper:true MOM60 JSON signal - the user's standing
     *  all-topics rule. Same gate, same shared send path and the
     *  same fail isolation as the BANKNIFTY engine's mirror: it
     *  runs only after the human post landed and can never
     *  block, delay or alter the alert or the paper log. */
    private static void feedMirror(Prefs prefs, Mom60.Signal s) {
        try {
            if (!SystemFeed.shouldSend(prefs.feedOn(), prefs.feedTopic())) return;
            final String body = SystemFeed.mom60Payload(s, SystemFeed.nowIst()).toString();
            new Thread(() -> BuyAlerts.postFeedNow(prefs, body)).start();
        } catch (Throwable ignored) { }
    }

    private static boolean applyResolutions(List<Mom60Store.Rec> all, List<Mom60.Event> evs) {
        boolean dirty = false;
        for (Mom60.Event e : evs) if (e.resolved != null) dirty |= applyResolution(all, e.resolved);
        return dirty;
    }

    private static boolean applyResolution(List<Mom60Store.Rec> all, Mom60.Resolution res) {
        for (Mom60Store.Rec r : all) {
            if (!r.closed && r.entryTs == res.signal.entryTs) {
                r.apply(res);
                return true;
            }
        }
        return false;
    }
}
