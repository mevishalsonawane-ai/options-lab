package com.irathinker.app;

import android.content.Context;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** One-shot user price alerts (watchlist feature). When a watched
 *  level is crossed, fire once on ntfy and remove the alert.
 *  Since v57 (user decision 2026-10-25: every alert goes to ALL
 *  topics) a landed fire ALSO mirrors to the System Feed as a
 *  PRICE_ALERT JSON signal through the shared
 *  BuyAlerts.postFeedNow path - the removal stays keyed to the
 *  human post exactly as before, and a failed feed post changes
 *  nothing here.
 *
 *  v59 adds RECURRING price alerts (the Detail box's Repeat
 *  toggle): an entry carrying the "|R" field never removes
 *  itself. It fires on a FRESH cross only - the price must be
 *  seen back on the near side before it can fire again - so a
 *  price parked beyond the level does not buzz every minute.
 *  The cross state is per-entry, in memory (the watchdog process
 *  is resident; a process restart simply re-arms every recurring
 *  alert, which is the honest reading of "still set"). The
 *  firing message and the PRICE_ALERT payload both state the
 *  alert is recurring. */
public final class PriceAlerts {

    /** Recurring entries' last-seen side: true = price was beyond
     *  the level at the last pass that saw the symbol. */
    private static final java.util.Map<String, Boolean> BEYOND =
            new java.util.concurrent.ConcurrentHashMap<>();

    public static void check(Context ctx, Prefs prefs, Collection<Analysis.Scan> scans) {
        if (!prefs.alertsOn()) return;
        List<String> entries = new ArrayList<>(prefs.priceAlerts());
        if (entries.isEmpty()) return;
        BEYOND.keySet().retainAll(entries); // forget removed alerts' state
        for (Analysis.Scan sc : scans) {
            if (sc == null || Double.isNaN(sc.ltp)) continue;
            // Skip stale feed days (market holidays): the price is not live.
            if (sc.lastTs > 0 && !Analysis.istDate(sc.lastTs).equals(
                    java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")))) continue;
            for (String e : entries) {
                String[] p = e.split("\\|");
                if (p.length < 3 || !p[0].equals(sc.sym.yahoo)) continue;
                double level;
                try { level = Double.parseDouble(p[2]); } catch (Exception ex) { continue; }
                boolean hit = "ABOVE".equals(p[1]) ? sc.ltp >= level : sc.ltp <= level;
                boolean recurring = Prefs.priceAlertRecurring(e);
                if (recurring) {
                    // Fire only on the transition into the cross;
                    // the state advances on a landed post (a failed
                    // post retries on the next pass), and resets
                    // whenever the price is back on the near side.
                    boolean wasBeyond = Boolean.TRUE.equals(BEYOND.get(e));
                    if (!hit) { BEYOND.put(e, false); continue; }
                    if (wasBeyond) continue;
                    boolean ok = NtfyClient.postToAll(prefs, prefs.activeTopics(),
                            "IraThinker price alert: " + sc.sym.name,
                            sc.sym.name + " is now " + MainActivity.fmt(sc.ltp)
                                    + " - crossed your " + p[1].toLowerCase() + " " + MainActivity.fmt(level)
                                    + " level. (Recurring alert - stays armed; it fires again on the next fresh cross.)",
                            "bell");
                    if (ok) {
                        BEYOND.put(e, true);
                        feedMirror(prefs, sc, level, "ABOVE".equals(p[1]), true);
                    }
                    continue;
                }
                if (!hit) continue;
                boolean ok = NtfyClient.postToAll(prefs, prefs.activeTopics(),
                        "IraThinker price alert: " + sc.sym.name,
                        sc.sym.name + " is now " + MainActivity.fmt(sc.ltp)
                                + " - crossed your " + p[1].toLowerCase() + " " + MainActivity.fmt(level)
                                + " level. (Alert removed.)",
                        "bell");
                if (ok) {
                    prefs.removePriceAlert(e);
                    feedMirror(prefs, sc, level, "ABOVE".equals(p[1]), false);
                }
            }
        }
    }

    /** System Feed mirror (v57): a landed price-alert fire is
     *  also posted to the machine topic as a PRICE_ALERT JSON
     *  signal - the user's standing all-topics rule. Gated on the
     *  feed switch + a configured topic, sent fire-and-forget on
     *  its own thread through the shared BuyAlerts.postFeedNow
     *  path, and fully fail-isolated: it runs only after the
     *  human post landed and can never block, delay or alter the
     *  alert or its one-shot removal. v59: the payload states
     *  whether the alert is a recurring one. */
    private static void feedMirror(Prefs prefs, Analysis.Scan sc, double level, boolean above,
                                   boolean recurring) {
        try {
            if (!SystemFeed.shouldSend(prefs.feedOn(), prefs.feedTopic())) return;
            final String body = SystemFeed.priceAlertPayload(sc.sym.name, sc.sym.yahoo,
                    level, above, sc.ltp,
                    SystemFeed.priceAlertSignalId(sc.sym.yahoo, above, level,
                            System.currentTimeMillis() / 1000),
                    SystemFeed.nowIst(), recurring).toString();
            new Thread(() -> BuyAlerts.postFeedNow(prefs, body)).start();
        } catch (Throwable ignored) { }
    }
}
