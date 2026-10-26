package com.irathinker.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/** System Feed (v51): the machine-readable mirror of the live
 *  strategy alerts. IraThinker stays the support system - the user's
 *  separate order application places the trades and watches a
 *  machine ntfy topic; every live ORB / gap-fade alert that fires is
 *  ALSO posted there as one flat JSON object, so the order system
 *  can parse and act without scraping the human message.
 *
 *  This class is deliberately pure (no Android, no network): it
 *  builds payloads and decides gating only, so the JVM harness can
 *  pin the wire format. Sending lives in NtfyClient.postRaw via
 *  the one shared path, BuyAlerts.postFeedNow.
 *
 *  Manual shares (v56, user decision 2026-10-25): a plan the user
 *  hand-shares from the Detail screen ALSO mirrors here as a
 *  MANUAL signal - same flat shape, so the order system can act on
 *  a shared plan while telling it apart from a fired auto alert.
 *  The share stays user-initiated: nothing in this class fires on
 *  its own, and a share with no plan never reaches the feed.
 *
 *  Paper strategies and price alerts (v57, user decision
 *  2026-10-25, reversing the v53-v55 paper-stage exclusion):
 *  mom60 on all three indices, the BANKNIFTY strangle paper
 *  track, and the user's own one-shot price alerts now mirror
 *  here too - the user's standing rule is that EVERY alert the
 *  app sends goes to ALL topics. The paper strategies carry
 *  "paper": true on every payload, so the receiving system can
 *  tell a paper signal from a live one at a glance; price alerts
 *  are the user's own and carry no paper flag. The BuyAlerts
 *  market-flush context message mirrors as MARKET_FLUSH.
 *
 *  Richer fields (v59, user "implement all" 2026-10-25): ORB /
 *  GAP_FADE / MANUAL payloads gain the Risk Gate's own sizing -
 *  "qty" (per-share units the user's max-loss budget buys at the
 *  ticket), "riskRs" (the planned rupee risk at entry, stop
 *  distance x qty) and "lots" (qty in whole lots, ONLY where the
 *  app itself knows the symbol's lot size - the three mom60
 *  indices; the app carries no equity lot table, so no lot figure
 *  is ever invented for a stock). MOM60 payloads gain "dte"
 *  (calendar days, signal date to contract expiry) and "riskRs"
 *  (the 30% premium stop x the index's lot). STRANGLE gains
 *  "dte" (entry Monday to expiry). PRICE_ALERT gains "recurring"
 *  (v59's re-arming price alerts). Every new field is optional:
 *  a plan the risk gate cannot size omits them. VERSION stays 51 -
 *  same flat shape, new optional fields only. */
public final class SystemFeed {

    /** Payload schema version (the app version that introduced it). */
    public static final int VERSION = 51;

    public static final String DEFAULT_SERVER = "ntfy.sh";

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    /** The feed sends only when the switch is ON and a machine topic
     *  is set. An empty topic can never be a valid feed destination. */
    public static boolean shouldSend(boolean enabled, String topic) {
        return enabled && topic != null && !topic.trim().isEmpty();
    }

    /** Bare host for the feed server field: strips any scheme and
     *  trailing slashes the user may paste, defaults to ntfy.sh. */
    public static String normalizeServer(String server) {
        if (server == null) return DEFAULT_SERVER;
        String s = server.trim();
        s = s.replaceFirst("(?i)^https?://", "").replaceAll("/+$", "").trim();
        return s.isEmpty() ? DEFAULT_SERVER : s;
    }

    /** Current IST time as ISO-8601 with the +05:30 offset, e.g.
     *  2026-10-23T22:45:00+05:30 - the payload's tsIST field. */
    public static String nowIst() {
        return ZonedDateTime.now(IST).format(TS_FMT);
    }

    /** JSON number at paise precision (the same precision the alert
     *  dedupe key uses), or JSON null for a missing value. */
    private static Object num(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return JSONObject.NULL;
        return Math.round(v * 100.0) / 100.0;
    }

    private static JSONObject base(boolean test, String strategy, String signalId, String tsIst)
            throws Exception {
        JSONObject o = new JSONObject();
        o.put("type", "signal");
        o.put("test", test);
        o.put("source", "IraThinker");
        o.put("version", VERSION);
        o.put("strategy", strategy);
        o.put("signalId", signalId == null ? JSONObject.NULL : signalId);
        o.put("tsIST", tsIst);
        return o;
    }

    /** The option block exactly as the alert's ticket computed it:
     *  kind follows the trade direction (CE long call / PE long put),
     *  strike / expiry / premium come from the ticket and are null
     *  when no ticket could be priced. */
    private static JSONObject optionBlock(boolean call, OptionsMath.Ticket tk) throws Exception {
        JSONObject op = new JSONObject();
        if (tk != null) op.put("strike", num(tk.strike));
        else op.put("strike", JSONObject.NULL);
        op.put("expiry", tk != null && tk.expiry != null ? tk.expiry.toString() : JSONObject.NULL);
        op.put("kind", call ? "CE" : "PE");
        if (tk != null) op.put("premium", num(tk.premium));
        else op.put("premium", JSONObject.NULL);
        return op;
    }

    /** The lot size the app itself knows for a symbol, or -1 when
     *  it knows none: only the three mom60 indices carry a lot in
     *  the app (their paper tracks trade in lots). Equity lot sizes
     *  are deliberately NOT tabled here - riskLine()'s standing
     *  instruction ("divide units by the lot size") exists because
     *  the app does not know them, and the feed never invents one. */
    public static int lotSizeFor(String yahoo) {
        if (yahoo == null) return -1;
        if (yahoo.equals(Mom60.BN.yahoo)) return Mom60.BN.lot;
        if (yahoo.equals(Mom60.FIN.yahoo)) return Mom60.FIN.lot;
        if (yahoo.equals(Mom60.SENSEX.yahoo)) return Mom60.SENSEX.lot;
        return -1;
    }

    /** The v59 sizing fields on an ORB / fade / manual payload:
     *  qty + riskRs whenever the Risk Gate sized the plan, lots
     *  only when a whole-lot count exists under a lot size the app
     *  knows. A null sizing (the gate could not size the plan)
     *  omits all three - never a fabricated figure. */
    private static void putSizing(JSONObject o, String yahoo, OptionsMath.Sizing sz)
            throws Exception {
        if (sz == null) return;
        o.put("qty", sz.units);
        o.put("riskRs", num(sz.riskRs));
        int lot = lotSizeFor(yahoo);
        if (lot > 0 && sz.units >= lot) o.put("lots", sz.units / lot);
    }

    /** Payload for a fired ORB alert. target1 is the 2R book target,
     *  target2 the 3R runner (v50: both exits ride on every alert) -
     *  the caller passes the very values the alert computed, so the
     *  feed can never drift from the human message. sz (v59) is the
     *  Risk Gate sizing for the same ticket, or null. */
    public static JSONObject orbPayload(Analysis.Scan sc, int score, String signalId,
                                        double target1, double target2,
                                        OptionsMath.Ticket tk, boolean test, String tsIst,
                                        OptionsMath.Sizing sz)
            throws Exception {
        boolean up = sc.setup == Analysis.Setup.ORB_UP;
        JSONObject o = base(test, up ? "ORB_BREAKOUT" : "ORB_BREAKDOWN", signalId, tsIst);
        o.put("symbol", sc.sym.name);
        o.put("yahooSymbol", sc.sym.yahoo);
        o.put("side", up ? "BUY_CALL" : "BUY_PUT");
        o.put("entry", num(sc.entry));
        o.put("stop", num(sc.stop));
        o.put("target1", num(target1));
        o.put("target2", num(target2));
        o.put("score", score);
        putSizing(o, sc.sym.yahoo, sz);
        o.put("option", optionBlock(up, tk));
        return o;
    }

    /** Payload for a fired gap-fade alert (v42 setup, 1R exit: no
     *  runner, so target2 is null; fades carry no IraScore in the
     *  alert, so score is null too). sz (v59) as on orbPayload. */
    public static JSONObject fadePayload(Analysis.Scan sc, String signalId,
                                         OptionsMath.Ticket tk, boolean test, String tsIst,
                                         OptionsMath.Sizing sz)
            throws Exception {
        boolean up = sc.fadeUp;
        JSONObject o = base(test, "GAP_FADE", signalId, tsIst);
        o.put("symbol", sc.sym.name);
        o.put("yahooSymbol", sc.sym.yahoo);
        o.put("side", up ? "BUY_CALL" : "BUY_PUT");
        o.put("entry", num(sc.fadeEntry));
        o.put("stop", num(sc.fadeStop));
        o.put("target1", num(sc.fadeTarget));
        o.put("target2", JSONObject.NULL);
        o.put("score", JSONObject.NULL);
        putSizing(o, sc.sym.yahoo, sz);
        o.put("option", optionBlock(up, tk));
        return o;
    }

    /** Whether a Detail-screen manual share (v56) may mirror to
     *  the feed: only a real ORB plan on a symbol with an options
     *  ticket can fill the payload honestly. No plan, a non-ORB
     *  setup, gold (v52: no options ticket exists for it) or an
     *  unpriced ticket all skip the feed leg - the human share
     *  still goes out exactly as before. */
    public static boolean manualFeedable(Analysis.Scan sc, OptionsMath.Ticket tk) {
        if (sc == null || !sc.hasPlan()) return false;
        if (sc.setup != Analysis.Setup.ORB_UP && sc.setup != Analysis.Setup.ORB_DOWN) return false;
        if (sc.sym == null || Universe.isGoldYahoo(sc.sym.yahoo)) return false;
        return tk != null && !Double.isNaN(tk.premium) && tk.expiry != null;
    }

    /** The full manual-share feed decision (v56): the feed switch
     *  and topic gate (shouldSend) AND an honestly derivable plan
     *  payload (manualFeedable). */
    public static boolean shouldSendManual(boolean enabled, String topic,
                                             Analysis.Scan sc, OptionsMath.Ticket tk) {
        return shouldSend(enabled, topic) && manualFeedable(sc, tk);
    }

    /** Dedupe-style id for a manual share: the auto ORB signal
     *  key's fields (BuyAlerts.signalKey) under a MANUAL prefix, so
     *  a hand share can never collide with the fired alert whose
     *  plan it mirrors. */
    public static String manualSignalId(Analysis.Scan sc, double target1, String dateKey) {
        return "MANUAL|" + sc.sym.yahoo + "|" + sc.setup + "|" + Math.round(sc.entry * 100)
                + "|" + Math.round(target1 * 100) + "|" + dateKey;
    }

    /** Payload for a hand-shared plan (v56): the ORB payload's flat
     *  shape - target1 is the 2R book target, target2 the 3R
     *  runner, the caller passing the very values it computed -
     *  under strategy MANUAL, so the order system can distinguish
     *  a user-shared plan from a fired ORB / GAP_FADE signal.
     *  sz (v59) as on orbPayload. */
    public static JSONObject manualPayload(Analysis.Scan sc, int score, String signalId,
                                           double target1, double target2,
                                           OptionsMath.Ticket tk, String tsIst,
                                           OptionsMath.Sizing sz)
            throws Exception {
        boolean up = sc.setup == Analysis.Setup.ORB_UP;
        JSONObject o = base(false, "MANUAL", signalId, tsIst);
        o.put("symbol", sc.sym.name);
        o.put("yahooSymbol", sc.sym.yahoo);
        o.put("side", up ? "BUY_CALL" : "BUY_PUT");
        o.put("entry", num(sc.entry));
        o.put("stop", num(sc.stop));
        o.put("target1", num(target1));
        o.put("target2", num(target2));
        o.put("score", score);
        putSizing(o, sc.sym.yahoo, sz);
        o.put("option", optionBlock(up, tk));
        return o;
    }

    /** ISO-8601 IST rendering of an epoch second, same format as
     *  tsIST - used for the mom60 payload's exitBy field. */
    private static String isoOfEpochSec(long sec) {
        return java.time.Instant.ofEpochSecond(sec).atZone(IST).format(TS_FMT);
    }

    /** Dedupe-style id for a mom60 fire (v57): one signal per index
     *  per entry bar - the engines already fire at most one open
     *  position per index, and the entry timestamp is the bar's
     *  identity. */
    public static String mom60SignalId(Mom60.Signal s) {
        return "MOM60|" + s.cfg.yahoo + "|" + s.entryTs;
    }

    /** Payload for a fired mom60 paper alert (v57) - BANKNIFTY,
     *  FINNIFTY and Sensex share this builder; the signal's own
     *  Cfg supplies the display name, Yahoo symbol, strike and
     *  expiry. mom60's stop is on the PREMIUM (entry x 0.70), so
     *  the payload carries premiumStop and the option block's
     *  premium - and deliberately NO spot targets: target1 /
     *  target2 keys are omitted entirely, never nulled, because
     *  the strategy has no spot target to report. */
    public static JSONObject mom60Payload(Mom60.Signal s, String tsIst) throws Exception {
        JSONObject o = base(false, "MOM60", mom60SignalId(s), tsIst);
        o.put("paper", true);
        o.put("symbol", s.cfg.name);
        o.put("yahooSymbol", s.cfg.yahoo);
        o.put("side", s.call ? "BUY_CALL" : "BUY_PUT");
        o.put("entry", num(s.spot));
        JSONObject op = new JSONObject();
        op.put("strike", num(s.strike));
        op.put("expiry", s.expiry != null ? s.expiry.toString() : JSONObject.NULL);
        op.put("kind", s.call ? "CE" : "PE");
        op.put("premium", num(s.entryPremium));
        o.put("option", op);
        o.put("premiumStop", num(s.stopPremium));
        o.put("exitBy", isoOfEpochSec(s.exitTs));
        // v59: days to expiry from the signal's own dates, and the
        // planned rupee risk of the paper position - the 30%
        // premium stop (entry - stop premium) at the index's lot.
        if (s.date != null && s.expiry != null) {
            o.put("dte", java.time.temporal.ChronoUnit.DAYS.between(s.date, s.expiry));
        }
        if (!Double.isNaN(s.entryPremium) && !Double.isNaN(s.stopPremium)) {
            o.put("riskRs", num((s.entryPremium - s.stopPremium) * s.cfg.lot));
        }
        return o;
    }

    /** Dedupe-style id for a strangle week (v57): one entry per
     *  Monday, so the week itself is the identity. */
    public static String strangleSignalId(Strangle.Entry e) {
        return "STRANGLE|^NSEBANK|" + e.monday;
    }

    /** Payload for a fired strangle PAPER alert (v57): a two-leg
     *  short strangle has no single option block, so the legs ride
     *  as an array in the alert's own order (CE leg, PE leg) with
     *  the collected credit in points and rupees at the study lot
     *  (30), both breakevens, and the exit plan stated exactly as
     *  the human alert states it - Thursday close on the BS tape,
     *  or settlement at expiry in an expiry week. */
    public static JSONObject stranglePayload(Strangle.Entry e, String tsIst) throws Exception {
        JSONObject o = base(false, "STRANGLE", strangleSignalId(e), tsIst);
        o.put("paper", true);
        o.put("symbol", "BANKNIFTY");
        o.put("yahooSymbol", "^NSEBANK");
        o.put("side", "SELL_STRANGLE");
        o.put("entry", num(e.spot));
        JSONArray legs = new JSONArray();
        JSONObject ce = new JSONObject();
        ce.put("kind", "CE");
        ce.put("strike", num(e.ceStrike));
        ce.put("premium", num(e.cePremium));
        legs.put(ce);
        JSONObject pe = new JSONObject();
        pe.put("kind", "PE");
        pe.put("strike", num(e.peStrike));
        pe.put("premium", num(e.pePremium));
        legs.put(pe);
        o.put("legs", legs);
        o.put("creditPts", num(e.credit));
        o.put("creditRs", num(e.creditRs()));
        o.put("breakevenUpper", num(e.beUpper()));
        o.put("breakevenLower", num(e.beLower()));
        o.put("expiry", e.expiry != null ? e.expiry.toString() : JSONObject.NULL);
        // v59: days to expiry from the entry Monday - the same
        // dates the payload already carries, as a count.
        if (e.monday != null && e.expiry != null) {
            o.put("dte", java.time.temporal.ChronoUnit.DAYS.between(e.monday, e.expiry));
        }
        o.put("exitDate", e.exitDate != null ? e.exitDate.toString() : JSONObject.NULL);
        o.put("exitPlan", e.expiryWeek
                ? "SETTLE_AT_EXPIRY " + e.expiry
                : "THU_CLOSE " + e.exitDate);
        return o;
    }

    /** Dedupe-style id for a price-alert fire (v57): the alert is
     *  one-shot and removed when it fires, so the id carries the
     *  configured cross plus the fire second - two alerts on the
     *  same level set at different times can never collide. */
    public static String priceAlertSignalId(String yahoo, boolean above, double level, long fireSec) {
        return yahoo + "|PRICE_ALERT|" + (above ? "ABOVE" : "BELOW")
                + "|" + Math.round(level * 100) + "|" + fireSec;
    }

    /** Payload for a fired user price alert (v57): the user's own
     *  level cross - NOT a paper strategy, so no paper flag, and
     *  no option block: just the symbol, his trigger level, the
     *  cross direction he configured, and the price that fired
     *  it. v59 adds "recurring": true when the alert re-arms
     *  after firing instead of removing itself (one-shot alerts
     *  carry false - the field always rides, so the order system
     *  can tell a repeat fire from a fresh one-shot). */
    public static JSONObject priceAlertPayload(String name, String yahoo, double level,
                                               boolean above, double ltp,
                                               String signalId, String tsIst,
                                               boolean recurring) throws Exception {
        JSONObject o = base(false, "PRICE_ALERT", signalId, tsIst);
        o.put("symbol", name);
        o.put("yahooSymbol", yahoo);
        o.put("level", num(level));
        o.put("direction", above ? "ABOVE" : "BELOW");
        o.put("ltp", num(ltp));
        o.put("recurring", recurring);
        return o;
    }

    /** Payload for the BuyAlerts market-flush context message
     *  (v57): the day's breakdown breadth that made the flush
     *  fire - a market-context mirror, not a trade: no symbol, no
     *  levels, no option block. */
    public static JSONObject marketFlushPayload(int down, int total, String dateKey, String tsIst)
            throws Exception {
        JSONObject o = base(false, "MARKET_FLUSH", "MARKET_FLUSH|" + dateKey, tsIst);
        o.put("date", dateKey);
        o.put("down", down);
        o.put("total", total);
        return o;
    }

    /** The Settings "Send test signal" payload: the same flat shape,
     *  marked test:true with symbol TEST and no trade levels, so the
     *  receiving system can verify its parser end-to-end safely. */
    public static JSONObject testPayload(String tsIst) throws Exception {
        JSONObject o = base(true, "TEST", "TEST", tsIst);
        o.put("symbol", "TEST");
        o.put("yahooSymbol", "TEST");
        o.put("side", "BUY_PUT");
        o.put("entry", JSONObject.NULL);
        o.put("stop", JSONObject.NULL);
        o.put("target1", JSONObject.NULL);
        o.put("target2", JSONObject.NULL);
        o.put("score", JSONObject.NULL);
        o.put("option", optionBlock(false, null));
        return o;
    }
}
