package com.irathinker.app;

import android.content.Context;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Fires ntfy alerts for the day's TOP 3 actionable plans only (user
 *  rule, 2026-10-23): ORB breakout up = BUY, ORB breakdown = BUY PUT,
 *  ranked by IraScore, each signal exactly once - no repeat messages,
 *  and outcomes are tracked silently (no follow-up posts). Only
 *  breakdowns (BUY PUT) with IraScore above 65, at least 10 points
 *  to the target (the user's floor - a smaller target is untradeable
 *  in a real option), a narrow opening range (<= 0.8% of the price),
 *  and India VIX already confirmed (broken up first) qualify at all,
 *  alerts carry both exits (the 2R book target and the 3R runner -
 *  v50: the user kept both, live data judges them head-to-head),
 *  and the same entry/target is never sent twice.
 *  Alerts fire only once the break bar has closed (user rule), so the
 *  entry in the key is final and live firing matches the backtest.
 *  Also owns the live score cache. */
public final class BuyAlerts {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Object LOCK = new Object();

    /** Latest IraScore per symbol (fed by the Time Machine board). */
    private static final Map<String, Integer> SCORES = new ConcurrentHashMap<>();
    public static void noteScore(String yahoo, int score) { SCORES.put(yahoo, score); }
    public static Integer cachedScore(String yahoo) { return SCORES.get(yahoo); }

    /** The user's cap: at most this many signal notifications per day. */
    public static final int DAILY_TOP_N = 3;

    /** The user's quality bar: only big movers with real confidence notify. */
    public static final int MIN_CONFIDENCE = Analysis.ALERT_MIN_SCORE;

    /** Batch entry point (background pass + in-app scan): rank every
     *  eligible new plan by IraScore and fire only the day's top
     *  DAILY_TOP_N. Signals beyond the cap stay visible in the app;
     *  they just don't buzz. */
    public static void fireNewPlans(Context ctx, Collection<Analysis.Scan> scans) {
        Prefs prefs = new Prefs(ctx);
        if (!prefs.alertsOn()) return;
        LocalDate today = LocalDate.now(IST);
        if (today.getDayOfWeek().getValue() > 5) return;
        LocalTime now = LocalTime.now(IST);
        if (now.isBefore(LocalTime.of(9, 30)) || !now.isBefore(LocalTime.of(15, 15))) return;
        String dateKey = today.format(DateTimeFormatter.ISO_LOCAL_DATE);
        // The batch carries the India VIX scan: the confirmation gate
        // needs its setup and break time (same day only).
        Analysis.Setup vixSetup = Analysis.Setup.NONE;
        long vixBreakTs = 0;
        long nowSec = System.currentTimeMillis() / 1000;
        for (Analysis.Scan sc : scans) {
            if (sc != null && "^INDIAVIX".equals(sc.sym.yahoo) && sc.breakTs > 0
                    && Analysis.istDate(sc.breakTs).equals(today)
                    && Analysis.breakBarClosed(sc, nowSec)) {
                vixSetup = sc.setup;
                vixBreakTs = sc.breakTs;
            }
        }
        // Market-flush context (v41): once a day, when the selling is
        // market-wide, one extra message says so - it changes how any
        // single stock's breakdown that day should be read.
        int eqTotal = 0, eqDown = 0;
        for (Analysis.Scan sc : scans) {
            if (sc == null || sc.sym == null) continue;
            if (!Universe.G_NIFTY.equals(sc.sym.group) && !Universe.G_BANK.equals(sc.sym.group)) continue;
            eqTotal++;
            if (sc.setup == Analysis.Setup.ORB_DOWN && sc.breakTs > 0
                    && Analysis.istDate(sc.breakTs).equals(today)) eqDown++;
        }
        if (eqTotal >= 40 && eqDown >= 40 && eqDown * 10 >= eqTotal * 7 && !prefs.flushSent(dateKey)) {
            if (NtfyClient.postToAll(prefs, prefs.activeTopics(), "IraThinker - market flush",
                    String.format("Market flush — %d of %d stocks have broken down today. "
                            + "This is broad selling, not one stock's story.", eqDown, eqTotal),
                    "rotating_light")) {
                prefs.markFlush(dateKey);
                feedFlush(prefs, eqDown, eqTotal, dateKey);
            }
        }
        synchronized (LOCK) {
            int slots = DAILY_TOP_N - prefs.firedCount(dateKey);
            if (slots <= 0) return;
            List<Analysis.Scan> cands = new ArrayList<>();
            for (Analysis.Scan sc : scans) if (eligible(prefs, sc, today, dateKey, vixSetup, vixBreakTs)) cands.add(sc);
            for (Analysis.Scan sc : Analysis.topPlans(cands, slots, BuyAlerts::scoreFor)) {
                fireOne(ctx, prefs, sc, dateKey);
            }
            int left = DAILY_TOP_N - prefs.firedCount(dateKey);
            if (left > 0) fireFades(ctx, prefs, scans, today, dateKey, left);
        }
    }

    /** Gap-fade alerts (v42): the backtested setup in its tested form
     *  - no IraScore or VIX conditions (those belong to ORB); the
     *  09:45 bar must be closed, the day's shared top-3 cap applies,
     *  and bigger gaps rank first. Caller holds LOCK. */
    private static void fireFades(Context ctx, Prefs prefs, Collection<Analysis.Scan> scans,
                                  LocalDate today, String dateKey, int slots) {
        long nowSec = System.currentTimeMillis() / 1000;
        List<Analysis.Scan> cands = new ArrayList<>();
        for (Analysis.Scan sc : scans) {
            if (sc == null || !sc.hasFade()) continue;
            if (Universe.isGold(sc.sym)) continue; // v52: gold never alerts (see eligible)
            if (!Universe.alertable(sc.sym)) continue; // review 26 Oct: NSE F&O names only
            if (!Analysis.istDate(sc.fadeTs).equals(today)) continue;
            if (sc.fadeTs + 300 > nowSec) continue; // the 09:45 bar must have closed
            if (!Analysis.alertFresh(sc.fadeTs, nowSec)) continue; // review 26 Oct: never a stale fade
            if (prefs.setupFired(fadeKey(sc, dateKey))) continue;
            cands.add(sc);
        }
        cands.sort((a, b) -> Double.compare(Math.abs(b.fadeGap), Math.abs(a.fadeGap)));
        for (Analysis.Scan sc : cands) {
            if (slots-- <= 0) break;
            fireFade(ctx, prefs, sc, dateKey);
        }
    }

    private static String fadeKey(Analysis.Scan sc, String dateKey) {
        return sc.sym.yahoo + "|GAP_FADE|" + Math.round(sc.fadeEntry * 100)
                + "|" + Math.round(sc.fadeTarget * 100) + "|" + dateKey;
    }

    /** Post one gap-fade notification (1R exit, the tested form) and
     *  record it as fired. Caller holds LOCK. */
    private static void fireFade(Context ctx, Prefs prefs, Analysis.Scan sc, String dateKey) {
        boolean up = sc.fadeUp;
        String title = (Math.abs(sc.fadeGap) >= 1.5 ? "★★ " : "★ ")
                + "IraThinker GAP FADE " + (up ? "CALL: " : "PUT: ") + sc.sym.name;
        String ticket = "";
        OptionsMath.Ticket tk = null;
        try {
            YahooClient.Chart daily = YahooClient.fetch(sc.sym.yahoo, "3mo", "1d");
            double iv = OptionsMath.realizedVol(daily);
            if (!Double.isNaN(iv)) {
                double ivC = Math.max(10, Math.min(iv, 90));
                tk = OptionsMath.ticket(sc.sym, up, sc.fadeEntry, sc.fadeStop, sc.fadeTarget,
                        ivC, LocalDate.now(IST));
                ticket = OptionsMath.formatTicket(tk);
            }
        } catch (Exception ignored) { }
        try {
            NseChain.Probe p = NseChain.fetch(sc.sym.yahoo);
            if (p != null) ticket += "\n" + NseChain.line(p, up)
                    + "\n" + BullBear.line(p.addCeOi, p.addPeOi, p.pcr, sc.dayPct);
        } catch (Throwable ignored) { }
        if (tk != null) ticket += OptionsMath.riskLine(tk, prefs.capital(), prefs.maxLossPct());
        String body = String.format(
                "Gap fade — gapped %+.1f%% at the open; by 09:45 the gap was already filling back.%s%nStock levels — entry %s · stop %s · target %s (1R exit). Square off by 15:30.%s",
                sc.fadeGap, ticket,
                MainActivity.fmt(sc.fadeEntry), MainActivity.fmt(sc.fadeStop), MainActivity.fmt(sc.fadeTarget),
                ticket.isEmpty() ? "" : "\nPremiums are estimates — check the live chain before ordering.");
        boolean ok = NtfyClient.postToAll(prefs, prefs.activeTopics(), title, body,
                up ? "chart_with_upwards_trend" : "chart_with_downwards_trend");
        if (ok) {
            prefs.markSetupFired(fadeKey(sc, dateKey));
            prefs.addOpenSignal(sc.sym.yahoo + "|" + (up ? "GAP_FADE_UP" : "GAP_FADE_DN") + "|" + dateKey + "|"
                    + sc.fadeTs + "|" + sc.fadeStop + "|" + sc.fadeTarget);
            feedFade(prefs, sc, fadeKey(sc, dateKey), tk);
        }
    }

    /** True when a scan is a fresh, in-window, score-passing ORB plan
     *  that has not already notified today. */
    private static boolean eligible(Prefs prefs, Analysis.Scan sc, LocalDate today, String dateKey,
                                    Analysis.Setup vixSetup, long vixBreakTs) {
        if (sc == null || !sc.hasPlan()) return false;
        // v52: XAU/USD gold is a scan/plan symbol, never a strategy
        // alert - the gate is the NSE-equity score/VIX machine, gold
        // has no NSE option to ticket, and the System Feed's scope is
        // ORB + gap fades on that gate's symbols. (An explicit guard,
        // not a hope that its score never clears the bar.)
        if (Universe.isGold(sc.sym)) return false;
        // Review 26 Oct 2026: only symbols with an NSE option the
        // ticket can name (VIX, USD/INR, MIDCAP 50, SENSEX and the
        // global commodities never alert).
        if (!Universe.alertable(sc.sym)) return false;
        if (sc.setup != Analysis.Setup.ORB_UP && sc.setup != Analysis.Setup.ORB_DOWN) return false;
        // Stale-bar guard: on a market holiday the feed serves the previous
        // session and scan() falls back to it - never replay its breakout
        // as today's alert.
        if (sc.breakTs <= 0 || !Analysis.istDate(sc.breakTs).equals(today)) return false;
        // Closed-bar firing (user rule): the break bar must have closed,
        // so the entry in the dedupe key is the bar's final close and a
        // break that closes back inside the range never notifies.
        long nowSec = System.currentTimeMillis() / 1000;
        if (!Analysis.breakBarClosed(sc, nowSec)) return false;
        // Review 26 Oct 2026: a break found late (Doze, offline, a
        // restart) is skipped, never sent with old levels as fresh.
        if (!Analysis.alertFresh(sc.breakTs, nowSec)) return false;
        if (prefs.setupFired(signalKey(sc, dateKey))) return false;
        int gate = Math.max(MIN_CONFIDENCE, prefs.minAlertScore());
        return Analysis.alertWorthy(sc, scoreFor(sc), gate)
                && Analysis.vixConfirms(vixSetup, vixBreakTs, sc);
    }

    /** Dedupe identity of a signal: symbol + setup + entry + target + day.
     *  The same entry/target is never notified twice. */
    private static String signalKey(Analysis.Scan sc, String dateKey) {
        return sc.sym.yahoo + "|" + sc.setup + "|" + Math.round(sc.entry * 100)
                + "|" + Math.round(Analysis.alertTarget(sc) * 100) + "|" + dateKey;
    }

    /** Post one signal notification and record it as fired. Caller holds LOCK. */
    private static void fireOne(Context ctx, Prefs prefs, Analysis.Scan sc, String dateKey) {
        int score = scoreFor(sc);
        boolean up = sc.setup == Analysis.Setup.ORB_UP;
        double aTarget = Analysis.alertTarget(sc); // the 2R book target: dedupe key + primary record
        double aTarget3 = Analysis.alertTarget3(sc); // the 3R runner target (v50: both exits ride)
        // Strength stars (v41): a rare 85+ signal carries three, so
        // the day's strongest alert is visible at a glance.
        String title = (score >= 85 ? "★★★ " : "★ ") + (up ? "IraThinker BUY: " : "IraThinker BUY PUT: ") + sc.sym.name;
        // The options ticket leads (the user trades options only): which
        // contract, and the premium at entry / target / stop. Premiums are
        // Black-Scholes estimates at the stock's realized volatility.
        String ticket = "";
        OptionsMath.Ticket tk = null;
        try {
            YahooClient.Chart daily = YahooClient.fetch(sc.sym.yahoo, "3mo", "1d");
            double iv = OptionsMath.realizedVol(daily);
            if (!Double.isNaN(iv)) {
                double ivC = Math.max(10, Math.min(iv, 90));
                ticket = OptionsMath.ticketText(sc, ivC, LocalDate.now(IST), aTarget);
                tk = OptionsMath.ticket(sc, ivC, LocalDate.now(IST), aTarget);
            }
        } catch (Exception ignored) { }
        // Free NSE probe: when this network is answered, the ticket
        // gains the real chain OI/IV/gamma line. Silent when blocked.
        try {
            NseChain.Probe p = NseChain.fetch(sc.sym.yahoo);
            if (p != null) ticket += "\n" + NseChain.line(p, up)
                    + "\n" + BullBear.line(p.addCeOi, p.addPeOi, p.pcr, sc.dayPct);
        } catch (Throwable ignored) { }
        // Prediction-engine context: information only - the agreement
        // test proved it must never gate an alert, so its 60-minute
        // vote simply rides along for the user to weigh.
        try {
            YahooClient.Chart c60 = YahooClient.intraday(sc.sym.yahoo);
            Predict.Forecast pf = Predict.forecast(c60, PredictLive.weights(ctx));
            // Live track (v47): judge this symbol's older recorded
            // calls against these bars, then write down today's.
            PredictLive.resolve(ctx, sc.sym.yahoo, c60);
            if (pf != null && pf.combosLive > 0) {
                int last = c60.ts.length - 1;
                PredictLive.record(ctx, sc.sym.yahoo, c60.ts[last], c60.close[last], pf.dir[2], pf.famDir60);
            }
            if (pf != null && pf.combosLive > 0 && pf.dir[2] != 0) {
                ticket += String.format("\nPrediction engine: %.0f%% of %d tested rules vote %s · expect %s%.1f pts (60-min).",
                        pf.consensus[2], pf.combosLive, pf.dir[2] > 0 ? "UP" : "DOWN",
                        pf.expPts[2] >= 0 ? "+" : "−", Math.abs(pf.expPts[2]));
            }
        } catch (Throwable ignored) { }
        // Breakout volume (v41, information only - the volume rule is
        // a study candidate, not a gate) and the rupee risk framing
        // by the user's own max-loss rule.
        if (!Double.isNaN(sc.rvol)) {
            ticket += String.format("%nBreakout volume: %s (%.1f× the day's normal).",
                    sc.rvol >= 1.5 ? "STRONG" : sc.rvol >= 0.8 ? "normal" : "weak", sc.rvol);
        }
        if (tk != null) ticket += OptionsMath.riskLine(tk, prefs.capital(), prefs.maxLossPct());
        String body = String.format(
                "%s at %s%s%nStock levels — entry %s · stop %s · target 1 %s (2R — book/partial) · target 2 %s (3R — runner). IraScore %d/100. Square off by 15:30.%s",
                up ? "Breakout above the opening range." : "Breakdown below the opening range.",
                MainActivity.fmt(sc.ltp),
                ticket,
                MainActivity.fmt(sc.entry), MainActivity.fmt(sc.stop), MainActivity.fmt(aTarget),
                MainActivity.fmt(aTarget3),
                score,
                ticket.isEmpty() ? "" : "\nPremiums are estimates — check the live chain before ordering.");
        boolean ok = NtfyClient.postToAll(prefs, prefs.activeTopics(), title, body,
                up ? "chart_with_upwards_trend" : "chart_with_downwards_trend");
        if (ok) {
            prefs.markSetupFired(signalKey(sc, dateKey));
            prefs.addOpenSignal(sc.sym.yahoo + "|" + sc.setup + "|" + dateKey + "|"
                    + sc.breakTs + "|" + sc.stop + "|" + aTarget + "|" + aTarget3);
            feedOrb(prefs, sc, score, signalKey(sc, dateKey), aTarget, aTarget3, tk);
        }
    }

    /** System Feed (v51): mirror a fired ORB alert to the machine
     *  topic as raw JSON for the user's order system. Runs only from
     *  inside the fired branch, so the feed inherits the alert's
     *  dedupe exactly - one signal, one feed message, no outcome
     *  follow-ups. Payload values are the ones this alert computed. */
    private static void feedOrb(Prefs prefs, Analysis.Scan sc, int score, String signalId,
                                double target1, double target2, OptionsMath.Ticket tk) {
        try {
            if (!SystemFeed.shouldSend(prefs.feedOn(), prefs.feedTopic())) return;
            // v59: the payload carries the Risk Gate's sizing for
            // this very ticket - the same numbers riskLine() put
            // in the human alert (null when the gate can't size).
            String body = SystemFeed.orbPayload(sc, score, signalId, target1, target2, tk,
                    false, SystemFeed.nowIst(),
                    OptionsMath.sizingFor(tk, prefs.capital(), prefs.maxLossPct())).toString();
            postFeed(prefs, body);
        } catch (Throwable ignored) { }
    }

    /** System Feed mirror of a fired gap-fade alert - see feedOrb. */
    private static void feedFade(Prefs prefs, Analysis.Scan sc, String signalId,
                                 OptionsMath.Ticket tk) {
        try {
            if (!SystemFeed.shouldSend(prefs.feedOn(), prefs.feedTopic())) return;
            String body = SystemFeed.fadePayload(sc, signalId, tk,
                    false, SystemFeed.nowIst(),
                    OptionsMath.sizingFor(tk, prefs.capital(), prefs.maxLossPct())).toString();
            postFeed(prefs, body);
        } catch (Throwable ignored) { }
    }

    /** System Feed mirror of the market-flush context message
     *  (v57): the day's breakdown breadth as JSON, fired only from
     *  inside the flush's sent branch - see feedOrb. */
    private static void feedFlush(Prefs prefs, int down, int total, String dateKey) {
        try {
            if (!SystemFeed.shouldSend(prefs.feedOn(), prefs.feedTopic())) return;
            String body = SystemFeed.marketFlushPayload(down, total, dateKey,
                    SystemFeed.nowIst()).toString();
            postFeed(prefs, body);
        } catch (Throwable ignored) { }
    }

    /** Fire-and-forget feed publish on its own thread: a slow or
     *  dead machine topic can never block, delay-fail or duplicate
     *  the human alert. The outcome lands in Prefs for the Settings
     *  status line. Never throws. */
    private static void postFeed(Prefs prefs, String body) {
        new Thread(() -> postFeedNow(prefs, body)).start();
    }

    /** The one shared System Feed send path (v56).
     *
     *  STANDING RULE (user, 2026-10-25): EVERY alert IraThinker
     *  sends goes to ALL topics - the readable alert to the human
     *  topic(s) AND a JSON signal to the machine feed topic. No
     *  per-strategy exceptions: the families wired today are the
     *  auto ORB / gap-fade alerts and the market-flush context
     *  (this class), MANUAL shares (DetailActivity, v56), MOM60
     *  on all three indices, the STRANGLE paper track and
     *  PRICE_ALERT (v57). A future alert family MUST route its
     *  human leg through Prefs.activeTopics() and its feed leg
     *  through postFeedNow below - never a private post path -
     *  fire the feed leg only after the human post lands, and
     *  never let a feed failure block, delay or alter the human
     *  alert or its records. (Settings test buttons are tests,
     *  not alerts, and stay outside this rule.)
     *
     *  Every feed mirror posts through here, so server/topic
     *  handling and the Settings status line can never drift
     *  between alert paths. Synchronous: call from a background
     *  thread (postFeed's worker, DetailActivity's send thread,
     *  an engine's mirror thread). Never throws; returns whether
     *  the post landed, and records the attempt in Prefs for the
     *  Settings status line. */
    public static boolean postFeedNow(Prefs prefs, String body) {
        boolean ok = false;
        try {
            ok = NtfyClient.postRaw(SystemFeed.normalizeServer(prefs.feedServer()),
                    prefs.feedTopic().trim(), body);
        } catch (Throwable ignored) { }
        try { prefs.setFeedStatus(System.currentTimeMillis(), ok); } catch (Throwable ignored) { }
        // v59: the same attempt also stamps the feed channel for
        // the Settings feed-health card (both records, one truth).
        try { prefs.stampChannel(Prefs.CH_FEED, ok); } catch (Throwable ignored) { }
        return ok;
    }

    /** Feed the Live Ledger (v45): every equity plan in the batch
     *  is recorded with its context and price-action fingerprint,
     *  alert-grade or not - the app's own forward record, which the
     *  adaptive features grade against. Never throws. */
    public static void recordLedger(Context ctx, Collection<Analysis.Scan> scans) {
        try {
            Analysis.Setup vixSetup = Analysis.Setup.NONE;
            long vixBreakTs = 0;
            int eqTotal = 0, eqDown = 0;
            for (Analysis.Scan sc : scans) {
                if (sc == null || sc.sym == null) continue;
                if ("^INDIAVIX".equals(sc.sym.yahoo) && sc.breakTs > 0) {
                    vixSetup = sc.setup;
                    vixBreakTs = sc.breakTs;
                }
                if (!Universe.G_NIFTY.equals(sc.sym.group) && !Universe.G_BANK.equals(sc.sym.group)) continue;
                eqTotal++;
                if (sc.setup == Analysis.Setup.ORB_DOWN && sc.breakTs > 0) eqDown++;
            }
            double breadth = eqTotal > 0 ? eqDown * 100.0 / eqTotal : Double.NaN;
            Prefs prefs = new Prefs(ctx);
            int gate = Math.max(MIN_CONFIDENCE, prefs.minAlertScore());
            // Which of today's plans were actually SENT as alerts?
            // Fired keys are yahoo|token|entryPaise|targetPaise|date;
            // the ledger joins on yahoo|entryPaise|date.
            java.util.Set<String> sentKeys = new java.util.HashSet<>();
            for (String k : prefs.firedKeys()) {
                String[] p = k.split("\\|");
                if (p.length >= 5) sentKeys.add(p[0] + "|" + p[2] + "|" + p[4]);
            }
            Ledger.recordBatch(ctx, scans, BuyAlerts::scoreFor, vixSetup, vixBreakTs, breadth, gate, sentKeys);
        } catch (Throwable ignored) { }
    }

    /** Score used for the alert gate: cached full score if the board
     *  computed one, else the fast lite score. */
    public static int scoreFor(Analysis.Scan sc) {
        Integer cached = SCORES.get(sc.sym.yahoo);
        return cached != null ? cached : Analysis.liteScore(sc);
    }

    /** Single-scan entry point - retired as a firing path: a notification
     *  needs the batch's India VIX scan to confirm the signal, so signals
     *  only ever fire through fireNewPlans (background pass + in-app scan,
     *  both of which carry the full universe including VIX). Kept so old
     *  callers compile; it deliberately sends nothing on its own. */
    public static void consider(Context ctx, Prefs prefs, Analysis.Scan sc) {
        // See fireNewPlans - confirmation requires the batch context.
    }

    /** Check every still-open fired signal against today's bars and
     *  record the outcome silently (no follow-up message). ORB
     *  alerts fired under v50 carry a second target (the 3R runner);
     *  both exits are judged on the same walk, and the signal stays
     *  open until BOTH are on record - a 2R hit early in the day no
     *  longer ends the runner's story before it is told. Records
     *  written before v50 have no second target and resolve exactly
     *  as they always did, as do gap fades (1R, unchanged). */
    public static void resolveOutcomes(Context ctx, Prefs prefs) {
        if (!prefs.alertsOn()) return;
        LocalDate today = LocalDate.now(IST);
        String dateKey = today.format(DateTimeFormatter.ISO_LOCAL_DATE);
        LocalTime now = LocalTime.now(IST);
        boolean pastClose = !now.isBefore(LocalTime.of(15, 32));
        for (String entry : prefs.openSignals()) {
            String[] p = entry.split("\\|");
            if (p.length < 6) { prefs.removeOpenSignal(entry); continue; }
            String yahoo = p[0];
            boolean up = p[1].endsWith("_UP"); // ORB_UP and GAP_FADE_UP are the long trades
            String sigDate = p[2];
            String key = yahoo + "|" + p[1] + "|" + sigDate;
            boolean has3 = p[1].startsWith("ORB") && p.length >= 7; // v50 ORB record: 7th field is target3
            // stale entries from earlier days: close them as 'neither' without a fetch
            if (!sigDate.equals(dateKey)) {
                if (!prefs.outcomeRecorded(key)) prefs.recordOutcome(key, 'N');
                if (has3 && !prefs.outcome3Recorded(key)) prefs.recordOutcome3(key, 'N');
                prefs.removeOpenSignal(entry);
                continue;
            }
            long breakTs;
            double stop, target, target3 = Double.NaN;
            try {
                breakTs = Long.parseLong(p[3]);
                stop = Double.parseDouble(p[4]);
                target = Double.parseDouble(p[5]);
                if (has3) target3 = Double.parseDouble(p[6]);
            } catch (Exception e) { prefs.removeOpenSignal(entry); continue; }
            YahooClient.Chart intra;
            try {
                intra = YahooClient.fetch(yahoo, "5d", "5m");
            } catch (Exception e) {
                continue; // try again on the next pass
            }
            boolean done2 = prefs.outcomeRecorded(key);
            if (!done2) {
                int outcome = Analysis.outcomeOf(intra, breakTs, stop, target, up);
                if (outcome != 0 || pastClose) {
                    prefs.recordOutcome(key, outcome > 0 ? 'T' : outcome < 0 ? 'S' : 'N');
                    done2 = true;
                }
            }
            boolean done3 = !has3 || prefs.outcome3Recorded(key);
            if (has3 && !done3) {
                int outcome3 = Analysis.outcomeOf(intra, breakTs, stop, target3, up);
                if (outcome3 != 0 || pastClose) {
                    prefs.recordOutcome3(key, outcome3 > 0 ? 'T' : outcome3 < 0 ? 'S' : 'N');
                    done3 = true;
                }
            }
            if (done2 && done3) prefs.removeOpenSignal(entry);
            // No follow-up post: the user's rule is one message per signal.
            // Outcomes still feed the scoreboard and the EOD report.
        }
    }
}
