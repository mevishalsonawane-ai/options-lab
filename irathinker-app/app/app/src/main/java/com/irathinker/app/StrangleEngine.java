package com.irathinker.app;

import android.content.Context;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/** Live strangle paper track (v54): watches for the Monday entry
 *  inside the AlertEngine pass and keeps the paper log resolved.
 *  Since v57 (user decision 2026-10-25: every alert goes to ALL
 *  topics) each landed entry alert ALSO mirrors to the System
 *  Feed as a paper:true STRANGLE JSON signal through the shared
 *  BuyAlerts.postFeedNow path - the paper log and week mark stay
 *  keyed to the human post exactly as before, and a failed feed
 *  post changes nothing here. It never writes to the equity Live
 *  Ledger or the mom60 log; StrangleStore is its record. Entries are recorded only
 *  when the alert lands (the equity gate's discipline): a failed
 *  post leaves the week unentered, so a later pass inside the
 *  09:31-09:45 window retries instead of logging a paper entry
 *  the user never saw. Resolution runs whether or not the alerts
 *  switch is on (mom60's rule) - the switch gates new entries,
 *  never the grading of weeks already on the books. Never throws -
 *  a bad pass must not disturb the engine around it. */
public final class StrangleEngine {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String BANK = "^NSEBANK";

    public static void check(Context ctx) {
        try {
            Prefs prefs = new Prefs(ctx);
            LocalDate today = LocalDate.now(IST);
            long nowSec = System.currentTimeMillis() / 1000;

            // One multi-day 1m fetch serves the Monday entry and
            // the exit walks of any week still open.
            YahooClient.Chart m1;
            try {
                m1 = YahooClient.fetch(BANK, "5d", "1m");
            } catch (Exception e) {
                return; // no tape, no judgements this pass
            }
            if (m1 == null || m1.ts.length == 0) return;

            List<StrangleStore.Rec> all = StrangleStore.load(ctx);
            boolean dirty = false;
            LocalDate tapeStart = java.time.Instant.ofEpochSecond(m1.ts[0])
                    .atZone(IST).toLocalDate();

            // 1) Resolve every open week whose exit has printed.
            //    Weeks are independent: an old week that cannot be
            //    priced yet never blocks this week's entry.
            for (StrangleStore.Rec r : all) {
                if (r.closed) continue;
                Strangle.Entry e = r.toEntry();
                if (!Strangle.resolutionDue(e, nowSec)) continue;
                double spot = Strangle.exitSpotFor(m1, e);
                if (!Double.isNaN(spot)) {
                    Strangle.Grade g = Strangle.grade(e, spot);
                    if (g != null) {
                        r.apply(g);
                        dirty = true;
                    }
                } else if (e.exitDate.isBefore(tapeStart)) {
                    // The tape no longer reaches back to this
                    // week's exit (app off beyond the 1m window):
                    // it can never be priced. Close it flat on
                    // the books rather than leaving it open forever.
                    r.applyFlat();
                    dirty = true;
                }
            }

            // 2) The Monday entry, inside its narrow window only.
            //    Deduped on the Monday date twice over: the paper
            //    log's own record and the Prefs week mark - either
            //    one saying "entered" is final for the week.
            if (prefs.strangleOn()
                    && Strangle.entryWindowOpen(today, nowSec)
                    && !today.toString().equals(prefs.strangleWeek())
                    && StrangleStore.recForWeek(all, today) == null) {
                double iv = Double.NaN;
                YahooClient.Chart daily = null;
                try {
                    daily = YahooClient.daily(BANK);
                    double rv = OptionsMath.realizedVol(daily);
                    if (!Double.isNaN(rv)) iv = Math.max(10, Math.min(rv, 90));
                } catch (Exception ignored) { }
                if (!Double.isNaN(iv)) {
                    Strangle.Entry e = Strangle.buildEntry(m1, today, iv);
                    if (e != null) {
                        // v59 information flags: the morning's
                        // chain PCR (the v29 probe, its normal
                        // timeout - an unanswered probe just
                        // prints its no-answer line) and the
                        // aftershock reading off the daily chart
                        // already fetched for the IV. Information
                        // only: computed here, appended after the
                        // plan, and the alert posts regardless.
                        String body = Strangle.body(e);
                        try {
                            NseChain.Probe probe = null;
                            try { probe = NseChain.fetch(BANK); } catch (Throwable ignored) { }
                            body += "\n" + Strangle.infoLines(probe, daily);
                        } catch (Throwable ignored) { }
                        boolean ok = NtfyClient.postToAll(prefs, prefs.activeTopics(),
                                Strangle.title(e), body, "clipboard");
                        if (ok) {
                            all.add(StrangleStore.Rec.fromEntry(e));
                            prefs.markStrangleWeek(today.toString());
                            dirty = true;
                            feedMirror(prefs, e);
                        }
                    }
                }
            }
            if (dirty) StrangleStore.save(ctx, all);
        } catch (Throwable ignored) {
            // engine discipline: never take the pass down with us
        }
    }

    /** System Feed mirror (v57): a landed strangle entry alert is
     *  also posted to the machine topic as a paper:true STRANGLE
     *  JSON signal - the user's standing all-topics rule. Gated
     *  on the feed switch + a configured topic, sent
     *  fire-and-forget on its own thread through the shared
     *  BuyAlerts.postFeedNow path, and fully fail-isolated: it
     *  runs only after the human post landed and can never block,
     *  delay or alter the alert, the week mark or the paper log. */
    private static void feedMirror(Prefs prefs, Strangle.Entry e) {
        try {
            if (!SystemFeed.shouldSend(prefs.feedOn(), prefs.feedTopic())) return;
            final String body = SystemFeed.stranglePayload(e, SystemFeed.nowIst()).toString();
            new Thread(() -> BuyAlerts.postFeedNow(prefs, body)).start();
        } catch (Throwable ignored) { }
    }
}

