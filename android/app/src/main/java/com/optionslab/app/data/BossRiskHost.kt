package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.SecurePrefs
import com.optionslab.ira.AccountStop
import com.optionslab.ira.AutoChange
import com.optionslab.ira.BossRisk
import com.optionslab.ira.LiquidityStopRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.Locale

/**
 * 25 Oct 2026 (Boss: "Do whatever you think better for application", on the app audit's open decisions; the rules are ira-core's
 * [BossRisk]): the switches and figures of the new risk rules, and their record.
 *
 *  PAPER (CLAUDE.md's PAPER rule: each is logged once in the Autopilot change timeline as Boss's decision, undone in one tap,
 *  shown on the Account guard page; paper changes never carry over to live by themselves):
 *   - the account stop rule's paper copy (-Rs 40,000 from the best, -Rs 13,000 a calendar month): no new automatic paper entry
 *     once reached, until Boss restarts the count with the PIN; counted from the day it came on;
 *   - the weekly loss stop (Rs 20,000 a week, [AppSettings.paperWeeklyLoss]): no new automatic paper entry for the rest of the week;
 *   - the 2% per-trade risk cap: a paper entry over it is sized down, or skipped when one lot is over it;
 *   - opposite bets between arms on the same index: the later entry is skipped;
 *   - research h14's capped limit on Liquidity's paper entries ([OrbArms]) and Liquidity's expiry-day skip ([OrbArms]);
 *   - the bid-ask spread check: SHADOW on paper too (logged only; not researched yet).
 *  Open positions always keep their exits: these rules only ever refuse or size down a NEW entry, never an exit.
 *
 *  LIVE (CLAUDE.md's LIVE rule): every one of them is SHADOW inside Zerodha's entry door ([Broker.entryRecheck]): what it would
 *  refuse is logged, nothing changes, until Boss switches its live switch on (off by default). The account stop rule's live copy
 *  is [AccountStopHost] (already in shadow behind its own switch). Switching a live rule ON only ever refuses.
 *
 *  While a paper entry could also be mirrored to Zerodha ([ExposureCapHost.liveMirrorPossible]), the paper rules only LOG: a refusal
 *  there would skip the live trade too, and a new rule that skips live trades starts in shadow.
 *
 * The order path ([Paper.place], [Broker.entryRecheck]) reads only memory here (no settings read, no disk, no network:
 * OrderPathRound2Test stays 0). The figures are read in the market watch's pass ([refresh], from [LossBreaker]); the switches once
 * in the background at start; writes go in the background.
 */
object BossRiskHost {
    enum class Rule(val key: String, val words: String, val target: String?, val pinToLoosen: Boolean) {
        ACCOUNT("acct", "account stop rule", AutoChange.ACCOUNT_STOP_PAPER_TARGET, true),
        WEEKLY("week", "weekly loss stop", AutoChange.WEEKLY_STOP_PAPER_TARGET, true),
        RISK("risk", "2% risk cap", AutoChange.RISK_CAP_PAPER_TARGET, true),
        OPPOSITE("opp", "opposite bets", AutoChange.OPPOSITE_PAPER_TARGET, true),
        CAPPED("cap", "capped limit (h14)", AutoChange.CAPPED_LIMIT_PAPER_TARGET, false),
        EXPIRY("exp", "Liquidity expiry-day skip", AutoChange.EXPIRY_SKIP_PAPER_TARGET, false),
        SPREAD("spread", "spread check", null, false);

        val paperKey: String get() = "g.boss.$key.paper"
        val liveKey: String get() = "g.boss.$key.live"

        companion object { fun ofTarget(t: String): Rule? = entries.firstOrNull { it.target == t } }
    }

    /**
     * The rules with a live switch here, off by default (shadow). The account stop rule's live copy is [AccountStopHost]; the expiry
     * skip is paper only; h14's capped limit and the spread check are logged on live with no switch: switching the live order type
     * to a capped LIMIT is a change to the live order path itself, for its own reviewed round.
     */
    val LIVE_RULES = listOf(Rule.WEEKLY, Rule.RISK, Rule.OPPOSITE)
    /** The rules on paper by default (Boss's decisions); the spread check is shadow everywhere and has no switch. */
    val PAPER_RULES = listOf(Rule.ACCOUNT, Rule.WEEKLY, Rule.RISK, Rule.OPPOSITE, Rule.CAPPED, Rule.EXPIRY)

    private const val K_LOG = "bossRisk.log"
    private const val K_ACCT_SINCE = "g.boss.acct.since"
    private const val K_ACCT_REACHED = "g.boss.acct.reached"
    private const val K_WEEK_REACHED = "g.boss.week.reached"
    private const val REFRESH_DAYS_MS = 60_000L
    private const val LOG_KEPT = 400
    private const val IV_KEPT = 400

    private val paperOn = java.util.concurrent.ConcurrentHashMap<Rule, Boolean>().apply { PAPER_RULES.forEach { put(it, true) } }
    private val liveOn = java.util.concurrent.ConcurrentHashMap<Rule, Boolean>().apply { LIVE_RULES.forEach { put(it, false) } }
    @Volatile private var loaded = false

    // ---- the paper figures (the watch pass) ----
    private val lock = Any()
    private var paperDays: List<AccountStop.Day> = emptyList()
    @Volatile private var daysReadAt = 0L
    @Volatile private var acctSince: LocalDate? = null
    @Volatile private var acctReached: Pair<LocalDate, String>? = null
    @Volatile private var acctStatus: LiquidityStopRule.Status = AccountStop.status(emptyList(), LocalDate.of(2026, 10, 25))
    @Volatile private var weekLimit: Double = BossRisk.PAPER_WEEKLY_DEFAULT
    @Volatile private var weekReachedOn: LocalDate? = null
    @Volatile private var week: BossRisk.Week = BossRisk.weekly(emptyList(), LocalDate.of(2026, 10, 25), BossRisk.PAPER_WEEKLY_DEFAULT)
    /** Zerodha's capital as the live guard last read it (memory; the live risk cap's account). */
    @Volatile var liveCapital: Double? = null
        internal set

    /** One refusal (or, in shadow, a would-be refusal) of a rule. [applied] false: logged only. */
    data class Rec(val atMs: Long, val day: LocalDate, val rule: Rule, val owner: String, val symbol: String, val live: Boolean,
                   val applied: Boolean, val why: String) {
        fun encode(): String = listOf(atMs.toString(), day.toString(), rule.key, clean(owner), clean(symbol), if (live) "1" else "0",
            if (applied) "1" else "0", clean(why)).joinToString("|")
    }
    private fun clean(s: String) = s.replace('|', '/').replace('\n', ' ')
    private fun decode(l: String): Rec? = runCatching {
        val p = l.split('|')
        if (p.size < 8) return null
        Rec(p[0].toLong(), LocalDate.parse(p[1]), Rule.entries.first { it.key == p[2] }, p[3], p[4], p[5] == "1", p[6] == "1", p[7])
    }.getOrNull()
    private var log: List<Rec> = emptyList()

    /** One entry's implied volatility (record only): when, whose, the option, its premium, the index then and the IV (null: not solved). */
    data class Iv(val atMs: Long, val day: LocalDate, val owner: String, val symbol: String, val live: Boolean, val premium: Double,
                  val underlying: Double?, val iv: Double?)
    private var ivs: List<Iv> = emptyList()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class View(val paper: Map<Rule, Boolean> = emptyMap(), val live: Map<Rule, Boolean> = emptyMap(), val weekLimit: Double = BossRisk.PAPER_WEEKLY_DEFAULT,
                    val lines: List<String> = emptyList(), val accountReached: Boolean = false, val weekReached: Boolean = false)
    private val _view = MutableStateFlow(View())
    /** The Account guard page's card. */
    val view: StateFlow<View> = _view

    private fun today(): LocalDate = runCatching { Market.today() }.getOrElse { LocalDate.now() }

    /** Reads the switches and the record in the background; logs each paper rule that is on once in the Autopilot timeline. */
    fun init(@Suppress("UNUSED_PARAMETER") context: Context) {
        scope.launch {
            runCatching {
                load()
                for (r in PAPER_RULES) if (paperOn[r] == true) r.target?.let { t ->
                    AutoChange.bossPaperRule(t, true, weekLimit)?.let { runCatching { com.optionslab.app.ira.Autopilot.logBossRuleOnce(it) } }
                }
            }
        }
    }

    /** The switches, the kept marks and the record from the settings (a worker thread). */
    internal fun load() {
        runCatching {
            for (r in PAPER_RULES) paperOn[r] = SecurePrefs.getBoolean(r.paperKey, true)
            for (r in LIVE_RULES) liveOn[r] = SecurePrefs.getBoolean(r.liveKey, false)
            acctSince = SecurePrefs.getString(K_ACCT_SINCE)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            if (acctSince == null) {
                // Counted from the day the paper copy came on (25 Oct's update): never from days before the rule existed.
                acctSince = today(); SecurePrefs.putAllSoon(mapOf(K_ACCT_SINCE to acctSince.toString()))
            }
            acctReached = AccountStop.decodeReached(SecurePrefs.getString(K_ACCT_REACHED))
            weekReachedOn = SecurePrefs.getString(K_WEEK_REACHED)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            weekLimit = AppSettings.load().paperWeeklyLoss
            val saved = SecurePrefs.getString(K_LOG).orEmpty().lines().mapNotNull { decode(it) }
            synchronized(lock) { log = (saved + log).distinct().sortedBy { it.atMs }.takeLast(LOG_KEPT); recompute(today()) }
        }
        loaded = true
        publish()
    }

    // ---- the figures (the market watch's pass, [LossBreaker.check]; off the order path) ----------------------------------------

    /**
     * The paper day as the loss limit just read it ([paperToday], after charges; null: not read) and the settings ([s]): the paper
     * days (the calendar's, after charges, read at most once a minute), the account stop rule's paper copy and the week. A rule
     * reached for the first time is said once (the diagnostics and a notice) and kept.
     */
    fun refresh(context: Context?, paperToday: Double?, s: AppSettings, nowMs: Long = System.currentTimeMillis()) {
        runCatching {
            if (!loaded) load()
            weekLimit = s.paperWeeklyLoss
            val d = today()
            if (nowMs - daysReadAt >= REFRESH_DAYS_MS) {
                val all = runCatching { DailyPnl.all(false) }.getOrDefault(emptyMap())
                synchronized(lock) { paperDays = all.values.filter { it.date != d }.map { AccountStop.Day(it.date, it.net) }.sortedBy { it.day } }
                daysReadAt = nowMs
            }
            if (paperToday != null && paperToday.isFinite() && Market.isTradingDay(d))
                synchronized(lock) { paperDays = AccountStop.record(paperDays, d, paperToday) }
            val wasAcct = acctStatus.stopped
            val wasWeek = week.reached
            synchronized(lock) { recompute(d) }
            if (acctStatus.stopped && !wasAcct) said(context, "Account stop rule reached on paper",
                "The paper account reached the account stop rule (${acctStatus.why}). " +
                    (if (paperOn[Rule.ACCOUNT] == true) "The bots open no new paper position until you restart the count with your PIN (Account guard page); open positions keep their exits."
                    else "Its paper switch is off: nothing changes."), 2071)
            if (week.reached && !wasWeek) said(context, "Weekly loss stop reached on paper",
                "This paper week is ${BossRisk.rs(week.net)}, past the ${BossRisk.rs(-week.limit)} weekly loss stop. " +
                    (if (paperOn[Rule.WEEKLY] == true) "The bots open no new paper position until Monday; open positions keep their exits."
                    else "Its paper switch is off: nothing changes."), 2072)
        }
        publish()
    }

    /** The statuses from the figures in memory, latched once reached (kept in the background). Called under [lock]. */
    private fun recompute(d: LocalDate) {
        val days = synchronized(lock) { paperDays }
        val st = AccountStop.status(days, d, acctSince)
        if (st.stopped && acctReached == null) {
            acctReached = (st.day ?: d) to (st.why ?: "reached")
            runCatching { SecurePrefs.putAllSoon(mapOf(K_ACCT_REACHED to AccountStop.encodeReached(acctReached))) }
        }
        acctStatus = AccountStop.latched(st, acctReached, acctSince)
        val w = BossRisk.weekly(days, d, weekLimit, weekReachedOn)
        if (w.reached && (weekReachedOn == null || BossRisk.weekStart(weekReachedOn!!) != w.start)) {
            weekReachedOn = d
            runCatching { SecurePrefs.putAllSoon(mapOf(K_WEEK_REACHED to d.toString())) }
        }
        week = w
    }

    private fun said(context: Context?, title: String, text: String, id: Int) {
        runCatching { Diag.record("risk", text) }
        if (context != null) runCatching { com.optionslab.app.work.Notifier.post(context, id, com.optionslab.app.work.Notifier.RISK, title, text, "almanac", setting = "risk.guards") }
    }

    // ---- the paper entry (memory only) ------------------------------------------------------------------------------------------

    /** What the paper book does with an automatic entry: [refusal] (nothing placed), else [lots] to place (maybe fewer). */
    data class Sized(val refusal: String?, val lots: Int, val rule: Rule? = null)

    /**
     * Before any price read, inside [com.optionslab.app.data.Paper.place], for an automatic BUY that opens a position: the account
     * stop's paper copy, the weekly stop and opposite bets ([open]: the automatic entries open now, as the same-direction cap counts
     * them, with their index). [mirror]: the arm's live key (while its entry could go live, only logged). Memory only; never throws.
     */
    fun paperEarly(owner: String, symbol: String, underlying: String, dir: Int?, open: List<BossRisk.Bet>, mirror: String?): String? = runCatching {
        val applied = !ExposureCapHost.liveMirrorPossible(mirror)
        fun hit(rule: Rule, why: String?): String? {
            if (why == null || paperOn[rule] != true) return null
            note(rule, owner, symbol, live = false, applied = applied, why = if (applied) why else "$why (logged only: this entry may also go live)")
            return if (applied) "refused: $why" else null
        }
        hit(Rule.ACCOUNT, AccountStop.refusal(acctStatus)?.removePrefix("refused: ")?.let { "$it on paper" })?.let { return@runCatching it }
        // The week worked out now from the days in memory (review: on a Monday last week's "reached" never holds an entry made
        // before the first watch pass of the day).
        hit(Rule.WEEKLY, BossRisk.weeklyRefusal(BossRisk.weekly(synchronized(lock) { paperDays }, today(), weekLimit, weekReachedOn)))?.let { return@runCatching it }
        if (dir != null) hit(Rule.OPPOSITE, BossRisk.opposite(open, owner, underlying, dir))?.let { return@runCatching it }
        null
    }.getOrNull()

    /**
     * After the price is known: the 2% risk cap for [lots] of a lot of [lotSize] bought at [price] with [stop] (the order's own,
     * else the arm's known stop), on an account of [capital]. Memory only; never throws (a failure lets the entry go as asked).
     */
    fun paperSize(owner: String, symbol: String, lots: Int, lotSize: Int, price: Double?, stop: Double?, capital: Double, mirror: String?): Sized = runCatching {
        if (paperOn[Rule.RISK] != true || price == null) return@runCatching Sized(null, lots)
        val cap = BossRisk.riskCap(lots, lotSize, price, BossRisk.stopShare(owner, price, stop), capital)
        val why = cap.why ?: return@runCatching Sized(null, lots)
        val applied = !ExposureCapHost.liveMirrorPossible(mirror)
        note(Rule.RISK, owner, symbol, live = false, applied = applied, why = if (applied) why else "$why (logged only: this entry may also go live)")
        if (!applied) Sized(null, lots) else if (cap.skipped) Sized("refused: $why", 0, Rule.RISK) else Sized(null, cap.lots, Rule.RISK)
    }.getOrElse { Sized(null, lots) }

    /** The spread check on a paper entry's quote (shadow: logged only, never changes the entry). */
    fun spreadShadow(owner: String, symbol: String, bid: Double?, ask: Double?, live: Boolean) {
        runCatching { BossRisk.spread(bid, ask)?.let { note(Rule.SPREAD, owner, symbol, live, applied = false, why = "$it (shadow: logged only)") } }
    }

    /** An automatic entry that opens no position of an index option (MCX, a strategy's start): the account and weekly stops only. */
    fun paperStartRefusal(owner: String, symbol: String = ""): String? = paperEarly(owner, symbol, "", null, emptyList(), ExposureCapHost.NO_LIVE)

    /** Liquidity's paper entries use h14's capped limit ([OrbArms]). Memory only. */
    fun cappedPaper(): Boolean = paperOn[Rule.CAPPED] == true

    /** Liquidity's paper books skip their index's expiry day ([OrbArms]). Memory only. */
    fun expirySkipPaper(): Boolean = paperOn[Rule.EXPIRY] == true

    /** A capped-limit entry let go or filled ([OrbArms]): kept in the record. */
    fun cappedNote(owner: String, symbol: String, why: String, applied: Boolean = true, live: Boolean = false) =
        note(Rule.CAPPED, owner, symbol, live, applied, why)

    /** Liquidity's expiry-day skip refused a paper entry ([OrbArms]), or (a live entry: shadow) would have: kept in the record. */
    fun expiryNote(owner: String, symbol: String, why: String, live: Boolean = false) =
        note(Rule.EXPIRY, owner, symbol, live, applied = !live, why = if (live) "$why (live, shadow: logged only, nothing changed)" else why)

    // ---- the live entry door (memory only) -----------------------------------------------------------------------------------

    /**
     * Inside Zerodha's entry door, for a bot's live BUY ([owner]: its arm, else its tag) of [symbol] on [exchange] ([qty] units,
     * [lotSize] a lot) at [price] (the intended or limit price, else the stream's last) with [stop]: each live rule's refusal when
     * its live switch is on, else (the default) the refusal it would have given is logged and null returned. Memory only; never throws.
     */
    fun liveDoor(symbol: String, exchange: String, owner: String, qty: Int, lotSize: Int, price: Double?, stop: Double?): String? = runCatching {
        val token = Broker.tokenKnown("$exchange:$symbol")
        val tick = token?.let { runCatching { KiteStream.tick(it) }.getOrNull() }
        val px = price?.takeIf { it > 0 } ?: tick?.last?.takeIf { it > 0 }
        // The spread check: shadow only, live too (on the stream's book; with no tick there is no book to judge).
        if (tick != null) spreadShadow(owner, symbol, tick.bid, tick.ask, live = true)
        fun gate(rule: Rule, why: String?): String? {
            if (why == null) return null
            val on = liveOn[rule] == true
            note(rule, owner, symbol, live = true, applied = on, why = if (on) why else "$why (live, shadow: logged only, nothing changed)")
            return if (on) "refused: $why" else null
        }
        val days = runCatching { AccountStopHost.daysNow() }.getOrDefault(emptyList())
        // Live's own limit (review: never the paper setting, so switching the paper stop off never switches live's off).
        gate(Rule.WEEKLY, BossRisk.weeklyRefusal(BossRisk.weekly(days, today(), BossRisk.LIVE_WEEKLY)))?.let { return@runCatching it }
        val u = com.optionslab.engine.Kite.underlyingOf(symbol)
        val dir = u?.let { com.optionslab.ira.SameDirectionCap.direction(it, exchange, symbol.takeLast(2)) }
        if (u != null && dir != null) {
            val open = runCatching { ExposureCapHost.liveOpen() }.getOrDefault(emptyList()).mapNotNull { o ->
                com.optionslab.engine.Kite.underlyingOf(o.symbol)?.let { BossRisk.Bet(o.owner, it, o.dir, o.symbol) }
            }
            gate(Rule.OPPOSITE, BossRisk.opposite(open, owner, u, dir))?.let { return@runCatching it }
        }
        val lots = if (lotSize > 0) qty / lotSize else 0
        val capital = liveCapital
        if (px != null && capital != null && lots > 0) {
            val cap = BossRisk.riskCap(lots, lotSize, px, BossRisk.stopShare(owner, px, stop), capital)
            // Live never sizes an order inside the door (it was priced and stamped before): over the cap, on, it is refused whole.
            val why = if (cap.lots >= lots) null else "2% risk cap: $lots ${if (lots == 1) "lot risks" else "lots risk"} " +
                "${BossRisk.rs(cap.riskPerLot * lots)} to the stop, over 2% of the account (${BossRisk.rs(cap.cap)})" +
                (if (cap.lots > 0) "; ${cap.lots} would fit" else "")
            gate(Rule.RISK, why)?.let { return@runCatching it }
        }
        // h14's capped limit, live: shadow only (the order type is not changed inside the door) - what the cap would have been.
        if (px != null) BossRisk.cap(px)?.let { c ->
            note(Rule.CAPPED, owner, symbol, live = true, applied = false,
                why = "capped limit: would buy at most %.2f (+0.5%% over %.2f), worked 3 minutes, never moved (live, shadow: logged only)".format(Locale.ENGLISH, c, px))
        }
        null
    }.getOrNull()

    // ---- IV at entry (record only) --------------------------------------------------------------------------------------------

    /** The index's last price from the stream (memory only; null when it has no fresh tick, e.g. the market is closed). */
    private fun spot(underlying: String): Double? = runCatching {
        val key = when (underlying.uppercase(Locale.ENGLISH)) {
            "NIFTY" -> "NSE:NIFTY 50"; "BANKNIFTY" -> "NSE:NIFTY BANK"; "FINNIFTY" -> "NSE:NIFTY FIN SERVICE"
            "MIDCPNIFTY" -> "NSE:NIFTY MID SELECT"; "SENSEX" -> "BSE:SENSEX"; else -> return@runCatching null
        }
        Broker.tokenKnown(key)?.let { KiteStream.tick(it, 60_000L) }?.last?.takeIf { it > 0 }
    }.getOrNull()

    /**
     * A new entry filled ([owner]'s [premium] for [contract]; [live]): its implied volatility worked out in the background from the
     * index's price in memory and kept with the trade's record (V1: measurement only, never a filter). Never on the caller's time.
     */
    fun entryIv(owner: String, contract: com.optionslab.app.data.Paper.Contract, premium: Double, live: Boolean, nowMs: Long = System.currentTimeMillis()) {
        if (contract.isMcx || !(premium > 0)) return
        scope.launch {
            runCatching {
                val s = spot(contract.underlying)
                val years = runCatching { com.optionslab.engine.options.OptionMath.timeToExpiryYears(Market.now(), contract.expiry) }.getOrNull()
                val iv = BossRisk.entryIv(premium, s, contract.strike, years, contract.right.name)
                val rec = Iv(nowMs, today(), owner, contract.symbol, live, premium, s, iv)
                synchronized(lock) { ivs = (ivs + rec).takeLast(IV_KEPT) }
                runCatching {
                    Diag.record("iv", "$owner ${contract.symbol} @ %.2f: IV at entry %s (index %s; record only)".format(Locale.ENGLISH, premium,
                        iv?.let { "%.1f%%".format(Locale.ENGLISH, it * 100) } ?: "not known", s?.let { "%.2f".format(Locale.ENGLISH, it) } ?: "no fresh price"))
                }
            }
        }
    }

    /** Resting automatic entries (a LIMIT not filled at once) by order id: their IV is recorded when they fill ([ivFills]). */
    private val ivWaiting = java.util.concurrent.ConcurrentHashMap<String, Pair<String, com.optionslab.app.data.Paper.Contract>>()

    /** A resting automatic entry ([orderId], [owner], [contract]): its IV is recorded when it fills on a later pass. Memory only. */
    fun ivRest(orderId: String, owner: String, contract: com.optionslab.app.data.Paper.Contract) {
        if (contract.isMcx) return
        if (ivWaiting.size > 200) ivWaiting.clear()
        ivWaiting[orderId] = owner to contract
    }

    /** The paper book's fills on a pass ([com.optionslab.app.data.Paper.tick]): a resting automatic entry that filled has its IV recorded. */
    fun ivFills(events: List<com.optionslab.engine.sandbox.SandboxEvent>) {
        if (ivWaiting.isEmpty()) return
        for (f in events.filterIsInstance<com.optionslab.engine.sandbox.SandboxEvent.Fill>()) {
            if (!f.action.equals("BUY", true)) continue
            ivWaiting.remove(f.orderId)?.let { (owner, c) -> entryIv(owner, c, f.price, live = false) }
        }
    }

    /**
     * A bot's live BUY of [symbol] went through Zerodha's door at about [price]: its IV recorded (record only), read from the symbol
     * and the day's contract list in memory. Never on the caller's time; never throws.
     */
    fun liveEntryIv(symbol: String, owner: String, price: Double?) {
        if (price == null || !(price > 0)) return
        // All of it in the background: the day's contract list may be read from its file the first time (never on the order path).
        scope.launch {
            runCatching {
                val k = BossRisk.kiteOption(symbol) { u, y, m ->
                    runCatching { Market.contracts() }.getOrDefault(emptyList()).filter { it.underlying == u && it.expiry.year == y && it.expiry.monthValue == m }
                        .maxOfOrNull { it.expiry }
                } ?: return@runCatching
                val c = com.optionslab.app.data.Paper.Contract(symbol, k.underlying, k.expiry, k.strike,
                    if (k.right == "CE") com.optionslab.engine.Right.CE else com.optionslab.engine.Right.PE, 1, "", "NFO")
                entryIv(owner, c, price, live = true)
            }
        }
    }

    /** Today's IVs at entry (the diagnostics' line). */
    fun ivLine(): String {
        val d = today()
        val t = synchronized(lock) { ivs.filter { it.day == d } }
        if (t.isEmpty()) return "IV at entry (record only): no entry today."
        val known = t.mapNotNull { it.iv }.sorted()
        val med = if (known.isEmpty()) null else known[known.size / 2]
        return "IV at entry (record only): ${t.size} ${if (t.size == 1) "entry" else "entries"} today, ${known.size} solved" +
            (med?.let { ", median %.1f%%".format(Locale.ENGLISH, it * 100) } ?: "") + "."
    }

    /** Today's IV records (tests, Jarvis). */
    fun ivToday(): List<Iv> { val d = today(); return synchronized(lock) { ivs.filter { it.day == d } } }

    // ---- the record ----------------------------------------------------------------------------------------------------------

    /** The same words of the same rule on the same arm and option, paper or live, within 10 minutes are recorded once. */
    private const val DEDUPE_MS = 10 * 60_000L

    private fun note(rule: Rule, owner: String, symbol: String, live: Boolean, applied: Boolean, why: String) {
        val r = Rec(System.currentTimeMillis(), today(), rule, owner, symbol, live, applied, why)
        val added = synchronized(lock) {
            if (log.any { it.rule == r.rule && it.owner == r.owner && it.symbol == r.symbol && it.live == r.live && it.why == r.why && r.atMs - it.atMs in 0 until DEDUPE_MS }) false
            else { log = (log + r).takeLast(LOG_KEPT); true }
        }
        if (!added) return
        val tag = if (!live && applied) "paper, applied" else if (!live) "paper, logged only" else if (applied) "live, refused" else "live, shadow"
        runCatching { Diag.record("risk", "$owner $symbol: $why ($tag)") }
        scope.launch {
            runCatching { SecurePrefs.putAllLazy(mapOf(K_LOG to synchronized(lock) { log }.joinToString("\n") { it.encode() })) }
            publish()
        }
    }

    /** Today's records (the card, the diagnostics, tests). */
    fun recordsToday(rule: Rule? = null): List<Rec> { val d = today(); return synchronized(lock) { log.filter { it.day == d && (rule == null || it.rule == rule) } } }

    /** The diagnostics' and the card's lines. */
    fun lines(): List<String> {
        val d = today()
        val t = synchronized(lock) { log.filter { it.day == d } }
        fun count(r: Rule, live: Boolean, applied: Boolean) = t.count { it.rule == r && it.live == live && it.applied == applied }
        val a = acctStatus
        val acct = "Account stop rule on paper (${if (paperOn[Rule.ACCOUNT] == true) "on" else "off"}; counted from ${acctSince ?: "today"}): " +
            (if (a.trades == 0) "no paper day recorded yet" else "drawdown ${BossRisk.rs(a.drawdown)} of -Rs 40,000, this month ${BossRisk.rs(a.month)} of -Rs 13,000 (${a.trades} day${if (a.trades == 1) "" else "s"})") +
            (if (a.stopped) "; REACHED on ${a.day} (${a.why}): no new paper entries until you restart it with your PIN" else "") + "."
        val w = week
        val wk = "Weekly loss stop on paper (${if (paperOn[Rule.WEEKLY] == true && w.limit > 0) "on, ${BossRisk.rs(-w.limit)}" else "off"}): this week ${BossRisk.rs(w.net)}" +
            (if (w.reached) "; REACHED: no new paper entries until Monday" else "") + "; live in shadow (${if (liveOn[Rule.WEEKLY] == true) "on" else "off"})."
        val rest = listOf(Rule.RISK, Rule.OPPOSITE, Rule.CAPPED, Rule.EXPIRY, Rule.SPREAD).joinToString("; ") { r ->
            val p = if (r == Rule.SPREAD) "shadow" else if (paperOn[r] == true) "on" else "off"
            val l = if (r == Rule.EXPIRY) "" else if (r == Rule.SPREAD || r == Rule.CAPPED) ", live shadow" else ", live ${if (liveOn[r] == true) "on" else "shadow"}"
            "${r.words} (paper $p$l): today ${count(r, false, true)} paper applied, ${count(r, false, false)} logged, ${count(r, true, false)} live would-be"
        }
        return listOf(acct, wk, "New risk rules today: $rest.", ivLine())
    }

    private fun publish() {
        runCatching {
            _view.value = View(PAPER_RULES.associateWith { paperOn[it] == true }, LIVE_RULES.associateWith { liveOn[it] == true }, weekLimit,
                lines(), acctStatus.stopped, week.reached)
        }
    }

    /** Re-reads what the card shows (the screen opening; a worker thread). */
    fun refreshView() { if (!loaded) load() else publish() }

    // ---- Boss's switches ---------------------------------------------------------------------------------------------------

    /** The PIN's words for loosening one of these paper rules (as the paper loss limit's). */
    const val PIN_LOOSEN = "Switching a paper risk limit off loosens it: enter your app PIN on the Account guard page. Nothing changed."

    /**
     * Boss's paper switch for [rule]. Switching a limit OFF loosens it: it takes [pinConfirmed] (the caller asks the PIN, as for the
     * paper loss limit). [log]: into the Autopilot timeline (false for its own Undo). Returns what to say.
     */
    fun setPaper(rule: Rule, on: Boolean, pinConfirmed: Boolean = false, log: Boolean = true): String {
        if (rule !in PAPER_RULES) return "That rule has no paper switch."
        if (!on && rule.pinToLoosen && !pinConfirmed && pinAsked()) return PIN_LOOSEN
        paperOn[rule] = on
        runCatching { SecurePrefs.putAllSoon(mapOf(rule.paperKey to on)) }
        if (log) rule.target?.let { t -> AutoChange.bossPaperRule(t, on, weekLimit)?.let { runCatching { com.optionslab.app.ira.Autopilot.logBossRule(it) } } }
        runCatching { Diag.record("risk", "${rule.words} on paper ${if (on) "on" else "off"} (by you)") }
        publish()
        return "${rule.words.replaceFirstChar { it.uppercase() }} on paper: ${if (on) "on" else "off"}."
    }

    /** Whether loosening asks the PIN now (the guard's own rule, [com.optionslab.app.security.GuardPin]; unknown counts as yes). */
    private fun pinAsked(): Boolean = runCatching {
        com.optionslab.app.security.GuardPin.asks(true, runCatching { AppSettings.load().live }.getOrDefault(true))
    }.getOrDefault(true)

    /** Boss's live switch for [rule] (off by default: shadow, logged only). On only ever refuses the bots' live entries. */
    fun setLive(rule: Rule, on: Boolean): String {
        if (rule !in LIVE_RULES) return "That rule has no live switch."
        liveOn[rule] = on
        runCatching { SecurePrefs.putAllSoon(mapOf(rule.liveKey to on)) }
        runCatching { Diag.record("risk", "${rule.words} on live ${if (on) "on: the bots' live entries past it are refused" else "in shadow: logged only"} (by you)") }
        publish()
        return if (on) "${rule.words.replaceFirstChar { it.uppercase() }} on live: the bots' live entries past it are refused (your own orders never)."
        else "${rule.words.replaceFirstChar { it.uppercase() }} on live: in shadow - what it would refuse is logged, nothing changes."
    }

    /** Boss restarts the paper account stop's count after it was reached (the Account guard page, with the PIN). */
    fun restartAccount(pinConfirmed: Boolean): String {
        if (!pinConfirmed) return "Restarting the paper account stop rule's count takes your PIN."
        if (!acctStatus.stopped) return "The paper account stop rule is not reached: nothing to restart."
        val from = AccountStop.restartFrom(today())
        // Under the figures' lock, so a watch pass recomputing at the same moment never latches it again (review).
        synchronized(lock) {
            acctSince = from; acctReached = null
            runCatching { SecurePrefs.putAllSoon(mapOf(K_ACCT_SINCE to from.toString(), K_ACCT_REACHED to null)) }
            recompute(today())
        }
        runCatching { Diag.record("risk", "Paper account stop rule: count restarted by you with your PIN (from $from)") }
        publish()
        return "The paper account stop rule counts again from $from."
    }

    /** Paper switch state (tests, the card). */
    fun paperOn(rule: Rule): Boolean = paperOn[rule] == true
    fun liveOn(rule: Rule): Boolean = liveOn[rule] == true

    // ---- tests -------------------------------------------------------------------------------------------------------------

    /**
     * TEST ONLY: the defaults ([paperOn] for the paper rules: false for every other test, so their arms' entries are not what they
     * test; this rule's own tests switch it on), no record, no figures.
     */
    internal fun resetForTest(paperOn: Boolean = true) {
        PAPER_RULES.forEach { this.paperOn[it] = paperOn }
        LIVE_RULES.forEach { liveOn[it] = false }
        loaded = true; daysReadAt = Long.MAX_VALUE / 2
        acctSince = null; acctReached = null; weekReachedOn = null; weekLimit = BossRisk.PAPER_WEEKLY_DEFAULT; liveCapital = null
        synchronized(lock) { paperDays = emptyList(); log = emptyList(); ivs = emptyList() }
        acctStatus = AccountStop.status(emptyList(), today()); week = BossRisk.weekly(emptyList(), today(), weekLimit)
        _view.value = View()
    }

    /** TEST ONLY: the paper days the rules read (as the watch pass would have read them), and the figures recomputed. */
    internal fun setPaperDaysForTest(days: List<AccountStop.Day>, since: LocalDate? = null) {
        synchronized(lock) { paperDays = days.sortedBy { it.day } }
        acctSince = since; daysReadAt = Long.MAX_VALUE / 2
        synchronized(lock) { recompute(today()) }; publish()
    }
}
