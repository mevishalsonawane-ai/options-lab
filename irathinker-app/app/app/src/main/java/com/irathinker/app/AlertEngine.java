package com.irathinker.app;

import android.content.Context;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** The shared alert engine, used by the foreground WatchService (primary)
 *  and the JobScheduler chain (fallback). One pass = pre-market brief,
 *  opening bell, instant buy scan, square-off reminder. A pass never
 *  overlaps itself. */
public final class AlertEngine {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    // Daily charts fetched once per day for the background scans, so their
    // day % (and the big-move alert gate) uses yesterday's real close.
    private static final java.util.Map<String, YahooClient.Chart> DAILY_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile String dailyCacheDate = "";

    private static YahooClient.Chart dailyFor(String yahoo, String dateKey) {
        if (!dateKey.equals(dailyCacheDate)) {
            synchronized (DAILY_CACHE) {
                if (!dateKey.equals(dailyCacheDate)) {
                    DAILY_CACHE.clear();
                    dailyCacheDate = dateKey;
                }
            }
        }
        YahooClient.Chart ch = DAILY_CACHE.get(yahoo);
        if (ch == null) {
            try {
                ch = YahooClient.daily(yahoo);
                DAILY_CACHE.put(yahoo, ch);
            } catch (Exception e) {
                return null; // scan falls back to the feed's previous-close
            }
        }
        return ch;
    }

    public static void runChecks(Context ctx) {
        if (!RUNNING.compareAndSet(false, true)) return;
        try {
            doChecks(ctx.getApplicationContext());
        } catch (Throwable ignored) {
            // a failed pass (even an Error) must never kill the process:
            // the watchdog would restart into the same crash and loop
        } finally {
            RUNNING.set(false);
        }
    }

    /** Gold-only overnight pass (v52): outside the 08:30-24:00 engine
     *  window the WatchService wakes every 5 minutes for THIS pass and
     *  nothing else. Binance gold trades 24/7, so the NSE calendar and
     *  weekday checks of doChecks deliberately do not apply - the pass
     *  runs every night, weekends included. It refreshes gold's scan
     *  and checks the user's price alerts against it. That is ALL it
     *  can fire: a user price alert on gold. It cannot fire strategy
     *  alerts (the gate lives in doChecks' market hours, and BuyAlerts
     *  excludes gold regardless), cannot post to the System Feed (the
     *  feed mirrors fired strategy alerts only), and sends no brief,
     *  bell, EOD report or flush message. */
    public static void runGoldNightChecks(Context ctx) {
        if (!RUNNING.compareAndSet(false, true)) return;
        try {
            Context app = ctx.getApplicationContext();
            Prefs prefs = new Prefs(app);
            if (!prefs.alertsOn()) return;
            prefs.setLastPassAt(System.currentTimeMillis());
            Universe.Sym gold = Universe.gold();
            if (gold == null) return;
            YahooClient.Chart intra = YahooClient.intradayShort(gold.yahoo);
            YahooClient.Chart daily = null;
            try { daily = YahooClient.daily(gold.yahoo); } catch (Exception ignored) { }
            Analysis.Scan sc = Analysis.scan(gold, intra, daily);
            PriceAlerts.check(app, prefs,
                    java.util.Collections.singletonList(sc));
        } catch (Throwable ignored) {
            // same discipline as runChecks: a bad night pass must never
            // crash the watchdog into a restart loop
        } finally {
            RUNNING.set(false);
        }
    }

    private static void doChecks(Context ctx) {
        Prefs prefs = new Prefs(ctx);
        if (!prefs.alertsOn()) return;
        prefs.setLastPassAt(System.currentTimeMillis()); // Settings shows the checker is alive
        LocalDate today = LocalDate.now(IST);
        if (today.getDayOfWeek().getValue() > 5) return;
        if (MarketCalendar.isHoliday(today)) return;
        LocalTime now = LocalTime.now(IST);
        String dateKey = today.format(DateTimeFormatter.ISO_LOCAL_DATE);
        // all engine posts go through Prefs.activeTopics() - the
        // private-channel move dual-sends until the user confirms

        // pre-market brief (08:40-09:02)
        if (!now.isBefore(LocalTime.of(8, 40)) && now.isBefore(LocalTime.of(9, 3))
                && !dateKey.equals(prefs.briefDate())) {
            if (sendBrief(ctx, prefs)) prefs.setBriefDate(dateKey);
        }

        // opening bell - suppressed only on positive evidence of a
        // non-trading day: the feed answered and holds no bar from today
        // (a market holiday). A failed fetch proves nothing, so the bell
        // still rings then.
        if (!now.isBefore(LocalTime.of(9, 14)) && now.isBefore(LocalTime.of(9, 25))
                && !prefs.bellSent(dateKey)) {
            boolean staleDay = false;
            try {
                YahooClient.Chart nifty = YahooClient.intradayShort("^NSEI");
                staleDay = nifty.ts.length > 0
                        && YahooClient.indicesOfDay(nifty, today).isEmpty();
            } catch (Exception ignored) { }
            if (!staleDay && NtfyClient.postToAll(prefs, prefs.activeTopics(), "IraThinker - market open",
                    "Good morning. The scanner is live for today's session.", "bell")) {
                prefs.markBell(dateKey);
            }
        }

        // breakout scan (instant buy alerts + user price alerts)
        if (!now.isBefore(LocalTime.of(9, 30)) && now.isBefore(LocalTime.of(15, 15))) {
            ExecutorService pool = Executors.newFixedThreadPool(6);
            List<Universe.Sym> all = Universe.all();
            final List<Analysis.Scan> scans = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
            final Object lock = new Object();
            final int[] remaining = {all.size()};
            for (Universe.Sym s : all) {
                pool.execute(() -> {
                    try {
                        YahooClient.Chart intra = YahooClient.intradayShort(s.yahoo);
                        Analysis.Scan sc = Analysis.scan(s, intra, dailyFor(s.yahoo, dateKey));
                        scans.add(sc);
                    } catch (Exception ignored) { }
                    synchronized (lock) {
                        if (--remaining[0] == 0) pool.shutdown();
                    }
                });
            }
            try {
                if (!pool.awaitTermination(90, java.util.concurrent.TimeUnit.SECONDS)) {
                    pool.shutdownNow(); // don't leave zombie fetches piling up on a bad-network day
                }
            } catch (InterruptedException ignored) { }
            PriceAlerts.check(ctx, prefs, scans);
            BuyAlerts.fireNewPlans(ctx, scans); // ranked batch: the day's top 3 only, each once
            BuyAlerts.recordLedger(ctx, scans); // v45: every plan joins the live record
        }

        // resolve fired signals whose target/stop got hit (follow-up alerts)
        if (!now.isBefore(LocalTime.of(9, 35)) && now.isBefore(LocalTime.of(15, 45))) {
            BuyAlerts.resolveOutcomes(ctx, prefs);
            Ledger.resolveAll(ctx); // v45: judge the ledger's open records too
        }

        // BANKNIFTY mom60 (v53): its own watcher over the 1-minute
        // tape - entries can occur 10:16-14:30 and a hold can run to
        // the 15:30 close, so its window is the full session plus a
        // few minutes to settle the last position. Human alert plus
        // the v57 paper:true System Feed mirror; its own paper log
        // only, no equity Ledger.
        if (!now.isBefore(LocalTime.of(9, 15)) && now.isBefore(LocalTime.of(15, 45))) {
            Mom60Engine.check(ctx);
        }

        // BANKNIFTY positional short strangle (v54): PAPER TRACK
        // only - a Monday entry alert inside 09:31-09:45 and the
        // paper log's Thursday/expiry grading, in the same session
        // window as mom60 (grading also lands on later days' passes
        // and on the Strategies screen). Human alert plus the v57
        // paper:true System Feed mirror; its own paper log only,
        // no equity Ledger, no mom60 log. The strategy itself
        // stays parked - it needs ~Rs 2.14L of margin the user's
        // Rs 1L account cannot post.
        if (!now.isBefore(LocalTime.of(9, 15)) && now.isBefore(LocalTime.of(15, 45))) {
            StrangleEngine.check(ctx);
        }

        // FINNIFTY + Sensex mom60 paper tracks (v55): the same
        // watcher, thresholds, hold and stop as BANKNIFTY mom60,
        // each under its own frozen study numbers, switch and
        // paper log, in the same session window. Indices are
        // equities-side: the 15:30 commodities-only rule leaves
        // this window untouched and no signal fires after it.
        // Human alerts plus the v57 paper:true System Feed
        // mirrors; their own paper logs only, no equity Ledger,
        // no BANKNIFTY mom60 log.
        if (!now.isBefore(LocalTime.of(9, 15)) && now.isBefore(LocalTime.of(15, 45))) {
            Mom60IdxEngine.check(ctx);
        }

        // end-of-day report (after the close)
        if (!now.isBefore(LocalTime.of(15, 35)) && !prefs.eodSent(dateKey)) {
            if (sendEod(ctx, prefs, dateKey)) prefs.markEod(dateKey);
        }

        // square-off reminder - review 26 Oct 2026: only 15:20-15:35, so
        // a late pass (phone off, Doze) never sends it in the evening
        if (!now.isBefore(LocalTime.of(15, 20)) && now.isBefore(LocalTime.of(15, 35))
                && !prefs.squareOffSent(dateKey)) {
            if (NtfyClient.postToAll(prefs, prefs.activeTopics(), "IraThinker - square off",
                    "15:20 - close all intraday positions. No overnight heroics.", "warning")) {
                prefs.markSquareOff(dateKey);
            }
        }

    }

    /** The day's honest scoreboard, once after the close. */
    private static boolean sendEod(Context ctx, Prefs prefs, String dateKey) {
        int fired = prefs.firedCount(dateKey);
        int[] oc = prefs.outcomeCounts();
        int open = prefs.openSignals().size();
        java.util.List<PaperStore.Trade> trades = PaperStore.load(ctx);
        int pOpen = 0, pClosed = 0;
        double pnl = 0;
        for (PaperStore.Trade t : trades) {
            if (t.closed) { pClosed++; pnl += t.pnlRs(); } else pOpen++;
        }
        String body = String.format(
                "Day over. %d buy alerts fired today.%nSignal record so far: %d targets · %d stops · %d flat%s.%nPaper journal: %d open · %d closed · P&L %s₹%,.0f.",
                fired, oc[0], oc[1], oc[2], open > 0 ? " · " + open + " signals unresolved" : "",
                pOpen, pClosed, pnl >= 0 ? "+" : "-", Math.abs(pnl));
        // v50: the two exits, judged head-to-head on today's ORB alerts.
        int[] ocDay = prefs.outcomeCountsOn(dateKey);
        int[] oc3Day = prefs.outcome3CountsOn(dateKey);
        if (oc3Day[0] + oc3Day[1] + oc3Day[2] > 0) {
            body += String.format("%nExits today (ORB alerts) — 2R: %d target · %d stop · %d flat | 3R runner: %d target · %d stop · %d flat.",
                    ocDay[0], ocDay[1], ocDay[2], oc3Day[0], oc3Day[1], oc3Day[2]);
        }
        return NtfyClient.postToAll(prefs, prefs.activeTopics(), "IraThinker - end of day", body, "clipboard");
    }

    /** Overnight global cues + yesterday's tape, once each morning. */
    private static boolean sendBrief(Context ctx, Prefs prefs) {
        StringBuilder sb = new StringBuilder("Pre-market brief\n");
        boolean any = false;
        String[][] globals = {{"S&P 500", "^GSPC"}, {"Nasdaq", "^IXIC"}, {"USD/INR", "USDINR=X"}};
        for (String[] g : globals) {
            try {
                YahooClient.Chart d = YahooClient.fetch(g[1], "5d", "1d");
                if (!Double.isNaN(d.metaPrice) && !Double.isNaN(d.prevClose) && d.prevClose > 0) {
                    sb.append(String.format("%s %+.2f%%  ", g[0], (d.metaPrice / d.prevClose - 1) * 100));
                    any = true;
                }
            } catch (Exception ignored) { }
        }
        if (any) sb.append("\n");
        try {
            YahooClient.Chart intra = YahooClient.intradayShort("^NSEI");
            Analysis.Scan nifty = Analysis.scan(
                    new Universe.Sym("NIFTY 50", "^NSEI", Universe.G_INDEX), intra, null);
            if (!Double.isNaN(nifty.ltp)) {
                sb.append(String.format("NIFTY last close %s (%+.2f%%). ", MainActivity.fmt(nifty.ltp), nifty.dayPct));
                any = true;
            }
        } catch (Exception ignored) { }
        sb.append("Scanner arms at 9:15; first buy alerts can fire from 9:30.");
        if (!any) return false;
        return NtfyClient.postToAll(prefs, prefs.activeTopics(), "IraThinker - pre-market brief",
                sb.toString(), "sunrise");
    }
}
