package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.SecurePrefs
import com.optionslab.engine.Right
import com.optionslab.engine.orb.BreadthDonchianArm
import com.optionslab.engine.orb.DonchianArm
import com.optionslab.engine.orb.LiquidityRules
import com.optionslab.engine.orb.LiveMirror
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale
import kotlin.math.abs

/**
 * 20 Nov 2026 (Boss's exception: "if its says insufficient balance for any trade, then try cheaper options increment it 1 by 1, eg, 1st we
 * try for nifty 25000 put if not present try nifty 24900, if still not sufficient try 24800"; their answers: live now with the switch on,
 * at most 3 strikes further out, lots first then strikes, R51 / R72 / Liquidity 15+5 only): the cheaper strike when funds are short.
 *
 *  - Which arms: R51 Donchian 20, R72 Breadth Donchian (their live copies, [ArmLive]) and every Liquidity 15+5 book (its own live entry,
 *    [OrbArms]) - told apart by source ([covers]), never by name. Only an option BUY.
 *  - When: the lots cut (4 Nov: 3 -> 2 -> 1 lots as the free cash fits) runs first. Then (a) before sending, the free cash the entry already
 *    read cannot pay even one lot of the arm's strike at the entry LIMIT plus the fit's room ([LiveMirror.shortOfOneLot]); or (b) Zerodha
 *    REJECTED the entry for funds or margin ([fundsRefused]: a definite answer only - never a lost or unknown reply, which may hide an
 *    order and must never lead to a second entry).
 *  - How: one strike further out at a time on the expiry's own strike grid ([ladder]: a put lower, a call higher; same expiry, side and
 *    exchange - SENSEX on BFO), at most [MAX_STEPS]. A strike that is not listed (or, for Liquidity, not in the app's own contract list), or
 *    has no live quote, is skipped and still counts as a step. The first that one lot fits ([choose]) is bought: ONE lot, never more than
 *    Boss's lots setting. None within three: it gives up and the entry goes on exactly as before (logged).
 *  - Gates: the chosen strike's entry runs the arm's whole live entry path again - PIN / Live / "AI trades go live" / the arm's Trade live,
 *    the guard, the exposure cap and the order door, Kite.refusals, the kill switch, the day lock, Stop for today, the arm's square-off
 *    time ([LiveMirror.tooLate]) - and never more than [CHASE_MS] after the entry began (one bar's decision, never chased later).
 *  - Paper vs live: R51 / R72's paper trade keeps its own strike; only the live copy holds the cheaper option (its book row keeps the
 *    paper contract and the strike it stepped from). Liquidity has no paper twin while it trades live: its live trade is simply on the
 *    cheaper contract.
 *  - Boss's exception, live at once: it ADDS live trades, so it is the one exception to "anything new that could add live trades starts in
 *    shadow" (CLAUDE.md). Its switch in Bot settings is ON by default; off is one tap (it lowers risk); on takes the PIN. Every flip is in
 *    the Autopilot change timeline with an Undo (an Undo that turns it on takes the PIN). Each stepped buy is said to Boss and logged;
 *    each give-up is logged; today's counts are on the card, the shared settings file and the daily diagnostics.
 *
 * Off the tick path: nothing here runs on a tick. On an entry it reads only memory ([isOn], the kept instrument lists, the kept free cash)
 * plus, only when the funds are short, ONE quote read for the (at most three) strikes - on the entry's own worker, as the entry's own
 * quote read is. The switch is read once in the background at the start; the record is written in the background.
 */
object CheaperStrike {
    internal const val K_ON = "g.cheaperStrike.live"
    private const val K_LOG = "cheaperStrike.log"
    private const val KEPT = 300

    /** At most this many strikes further out (Boss, 20 Nov). */
    const val MAX_STEPS = 3
    /** Never chased later than this after the entry began (one bar's decision, the 60 s the arms' entry waits are counted inside). */
    const val CHASE_MS = 60_000L
    /** Bot settings' card and the diagnostics' name. */
    const val LABEL = "Cheaper strike when funds are short (Boss's exception, 20 Nov)"
    /** The short name in lines. */
    const val SHORT = "Cheaper strike"

    @Volatile private var on = false
    @Volatile private var loaded = false
    /** Boss changed the switch in this process (the start's late read never overrides it). */
    @Volatile private var switched = false
    private val lock = Any()
    private var recs: List<Rec> = emptyList()
    /** Bumped by [wipe]: a save queued before an erase never writes after it ([FundsCheck]'s rule). */
    @Volatile private var wipeGen = 0
    private val writeLock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Why the step started: (a) the free cash before sending, (b) Zerodha's definite funds refusal. */
    enum class Trigger(val words: String) { PRE_SEND("free cash short before sending"), REJECTED("Zerodha refused it for funds") }

    /** One stepped buy ([bought] true, with [to] and its [steps]) or one give-up ([why]); no rupees (the diagnostics hide them). */
    data class Rec(val atMs: Long, val day: LocalDate, val owner: String, val source: String, val from: String, val to: String?, val steps: Int,
                   val bought: Boolean, val why: String) {
        fun encode(): String = listOf(atMs.toString(), day.toString(), clean(owner), clean(source), clean(from), to?.let(::clean) ?: "",
            steps.toString(), if (bought) "1" else "0", clean(why)).joinToString("|")
    }
    private fun clean(s: String) = s.replace('|', '/').replace('\n', ' ')
    private fun decode(l: String): Rec? = runCatching {
        val p = l.split('|')
        if (p.size < 9) return null
        Rec(p[0].toLong(), LocalDate.parse(p[1]), p[2], p[3], p[4], p[5].ifEmpty { null }, p[6].toInt(), p[7] == "1", p[8])
    }.getOrNull()

    /** Bot settings' card: the switch, whether it was read, today's counts ([line]). */
    data class View(val on: Boolean = false, val loaded: Boolean = false, val boughtToday: Int = 0, val gaveUpToday: Int = 0, val line: String = "")
    private val _view = MutableStateFlow(View())
    val view: StateFlow<View> = _view

    private fun marketDay(): LocalDate = runCatching { Market.today() }.getOrElse { LocalDate.now() }

    fun init(@Suppress("UNUSED_PARAMETER") context: Context) {
        if (com.optionslab.app.BuildConfig.GOLD) return
        scope.launch {
            // The update switched it on for Boss (his exception): in the change timeline once, with its Undo (one tap off). (The app's start
            // only, never a later read: Autopilot's own record keeps it to once.)
            if (load() && !switched) runCatching { com.optionslab.app.ira.Autopilot.logCheaperStrikeOnOnce() }
        }
    }

    /**
     * The switch (ON when never set: Boss's default) and the record from the settings vault (a worker thread only). True when the switch
     * was never set on this phone.
     */
    internal fun load(): Boolean {
        val never = runCatching { K_ON !in SecurePrefs.keys(K_ON) }.getOrNull()
        val saved = runCatching { SecurePrefs.getBoolean(K_ON, true) }.getOrNull()
        val kept = runCatching { SecurePrefs.getString(K_LOG).orEmpty().lines().mapNotNull { decode(it) } }.getOrDefault(emptyList())
        synchronized(lock) {
            if (!switched && saved != null) on = saved
            recs = (kept + recs).distinct().sortedBy { it.atMs }.takeLast(KEPT)
            loaded = true
        }
        publish()
        return never == true
    }

    /** Read once if the start's read has not landed (a worker thread only: Bot settings' card). */
    fun refresh() { if (!loaded) load() else publish() }

    /** The switch now, from memory (off until the start's read lands: the side that adds no live trade). */
    fun isOn(): Boolean = on

    /** Whether the arm [source] is one this exception covers: R51, R72, Liquidity 15+5 (every book) - by source, never by name. */
    fun covers(source: String): Boolean = source == DonchianArm.SOURCE || source == BreadthDonchianArm.SOURCE ||
        source == LiquidityRules.ARM.source || LiquidityRules.BOOKS.any { it.source == source }

    /** Zerodha's words for a refusal for funds or margin ("Insufficient funds. Required margin is ...", "RMS:Margin Exceeds ..."). */
    fun fundsRefused(message: String?): Boolean = message != null &&
        listOf("insufficient funds", "insufficient fund", "margin required", "margin exceeds", "insufficient margin").any { message.contains(it, ignoreCase = true) }

    // ---- the stepping itself (pure) -----------------------------------------------------------------------------------------------

    /**
     * The strikes [MAX_STEPS] (or [max]) steps further out from [from] on the expiry's grid: a put lower, a call higher. The grid is the
     * commonest gap among the ten [listed] strikes nearest [from] (50 for NIFTY near the money, 100 for BANKNIFTY), so a strike missing
     * from the list still takes its step ("if not present try the next"). Empty for anything but CE / PE, or with no grid.
     */
    fun ladder(listed: Collection<Double>, from: Double, right: Right, max: Int = MAX_STEPS): List<Double> {
        if (right != Right.CE && right != Right.PE) return emptyList()
        if (!(from.isFinite() && from > 0) || max <= 0) return emptyList()
        val s = listed.filter { it.isFinite() && it > 0 }.map(::r2).distinct().sorted()
        if (s.size < 2) return emptyList()
        val near = s.sortedBy { abs(it - from) }.take(10).sorted()
        val gap = near.zipWithNext { a, b -> r2(b - a) }.filter { it > 0 }.groupingBy { it }.eachCount()
            .entries.sortedWith(compareByDescending<Map.Entry<Double, Int>> { it.value }.thenBy { it.key }).firstOrNull()?.key ?: return emptyList()
        val dir = if (right == Right.PE) -1 else 1
        return (1..max).map { r2(from + dir * it * gap) }.filter { it > 0 }
    }

    private fun r2(x: Double) = Math.round(x * 100) / 100.0

    /** One strike tried: listed at Zerodha ([symbol]; null: not present) and one lot's price at its entry LIMIT ([perLot]; null: no live quote). */
    data class Try(val strike: Double, val symbol: String?, val perLot: Double?)

    sealed class Outcome {
        /** [tries]'s [index] fits one lot, [steps] strikes out (1-based). */
        data class Picked(val index: Int, val steps: Int) : Outcome()
        /** None fits ([why]), after [steps] tried. */
        data class GaveUp(val why: String, val steps: Int) : Outcome()
    }

    /**
     * The first of [tries] (in step order) that one lot fits in [free] (the fit's room on top: [LiveMirror.shortOfOneLot]); a strike not
     * present or with no live quote is skipped and still counts. [free] unknown: no step (never a buy on a guess).
     */
    fun choose(tries: List<Try>, free: Double?): Outcome {
        if (free == null || !free.isFinite()) return Outcome.GaveUp("the free cash is not known", 0)
        if (tries.isEmpty()) return Outcome.GaveUp("no strike grid for this expiry", 0)
        val missing = ArrayList<String>()
        for ((i, t) in tries.take(MAX_STEPS).withIndex()) {
            when {
                t.symbol == null -> missing += "${fmt(t.strike)} not listed"
                t.perLot == null || !(t.perLot > 0) || !t.perLot.isFinite() -> missing += "${fmt(t.strike)} no live price"
                !LiveMirror.shortOfOneLot(t.perLot, free) -> return Outcome.Picked(i, i + 1)
                else -> missing += "${fmt(t.strike)} still short"
            }
        }
        return Outcome.GaveUp("none of the ${tries.take(MAX_STEPS).size} strikes further out fits one lot (${missing.joinToString(", ")})",
            tries.take(MAX_STEPS).size)
    }

    internal fun fmt(k: Double): String = if (k == Math.floor(k)) k.toLong().toString() else String.format(Locale.ENGLISH, "%.2f", k).trimEnd('0').trimEnd('.')

    // ---- the entry's hook ---------------------------------------------------------------------------------------------------------

    /** What an entry asks: [owner] (the arm's label, for words only) and [source] (which arm), the arm's own contract and the free cash. */
    data class Req(val owner: String, val source: String, val underlying: String, val expiry: LocalDate, val right: Right, val fromStrike: Double,
                   val fromSymbol: String, val exchange: String, val product: String, val free: Double?, val trigger: Trigger,
                   /** [System.nanoTime] when the entry began. */
                   val t0Nanos: Long, val now: LocalDateTime,
                   /** Liquidity: the cheaper strike must be in the app's own contract list too (its trade is booked on it). */
                   val needContract: Boolean = false)

    /**
     * The cheaper strike chosen: Zerodha's [symbol] on [exchange] ([lot], [tick]), its quote now ([last], [ask]), [steps] strikes out from the
     * arm's [req]; [contract]: the app's own contract for it (null: not in the app's list - R51 / R72 then read no option highs for it).
     * [sent]: set by the entry when the order really went (a gate that refused it before then lets the arm's own entry go on as before).
     */
    class Pick(val req: Req, val strike: Double, val symbol: String, val exchange: String, val lot: Int, val tick: Double, val last: Double,
               val ask: Double?, val steps: Int, val contract: Paper.Contract?) {
        @Volatile var sent: Boolean = false
        /** "NIFTY 25000 PE". */
        val fromWords: String get() = "${req.underlying} ${fmt(req.fromStrike)} ${req.right.name}"
        /** "24900 PE". */
        val toWords: String get() = "${fmt(strike)} ${req.right.name}"
    }

    /**
     * The cheaper strike for [r], or null (the switch off, not a covered arm, a give-up - logged). The entry's worker only: memory, plus one
     * quote read of the (at most three) strikes. Never throws.
     */
    suspend fun find(r: Req): Pick? = try { findNow(r) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
        gaveUp(r, "could not look (${e.javaClass.simpleName})", 0); null
    }

    private suspend fun findNow(r: Req): Pick? {
        if (!on || !covers(r.source) || (r.right != Right.CE && r.right != Right.PE)) return null
        if ((System.nanoTime() - r.t0Nanos) / 1_000_000 > CHASE_MS) return gaveUp(r, "more than ${CHASE_MS / 1000} s after the signal", 0)
        LiveMirror.tooLate(r.now, r.exchange, r.product, r.expiry)?.let { return gaveUp(r, it, 0) }
        if (r.free == null || !r.free.isFinite()) return gaveUp(r, "the free cash is not known", 0)
        val list = (if (r.exchange == "BFO") Broker.cachedBfoOptions() else Broker.cachedInstruments())
            ?: return gaveUp(r, "Zerodha's instrument list is not read", 0)
        val same = list.filter { it.name == r.underlying && it.expiry == r.expiry && it.right == r.right }
        val steps = ladder(same.map { it.strike }, r.fromStrike, r.right)
        if (steps.isEmpty()) return gaveUp(r, "no strike grid for this expiry", 0)
        val ins = steps.map { k -> same.firstOrNull { abs(it.strike - k) < 1e-6 } }
        val contracts = steps.map { k -> contractFor(r.underlying, r.expiry, k, r.right, r.exchange) }
        // (Liquidity books its trade on the app's own contract: a strike not in that list is "not present" too.)
        val usable = ins.indices.map { i -> ins[i]?.takeIf { !r.needContract || contracts[i] != null } }
        val keys = usable.mapNotNull { it?.let { i -> "${r.exchange}:${i.tradingSymbol}" } }
        // One read: the stream's fresh ticks, else Zerodha's quote (a stale tick is never a price here: [Broker.stopQuotes]).
        val q = if (keys.isEmpty()) emptyMap() else runCatching { Broker.stopQuotes(keys) }.getOrDefault(emptyMap())
        val quotes = usable.map { i -> i?.let { q["${r.exchange}:${it.tradingSymbol}"] }?.takeIf { it.last > 0 && it.last.isFinite() } }
        val tries = steps.indices.map { i ->
            val inst = usable[i]
            val px = inst?.let { n -> quotes[i]?.let { LiveMirror.entryLimit(it.ask, it.last, n.tickSize, null) } }
            Try(steps[i], inst?.tradingSymbol, px?.let { it * (inst.lotSize.coerceAtLeast(1)) })
        }
        return when (val o = choose(tries, r.free)) {
            is Outcome.GaveUp -> gaveUp(r, o.why, o.steps)
            is Outcome.Picked -> {
                val inst = usable[o.index]!!
                val qt = quotes[o.index]!!
                val c = contracts[o.index]
                // (Remembered in the paper book's contracts, memory at once: the live trade's expiry, strike and option highs read from it.)
                c?.let { runCatching { Paper.rememberContract(it) } }
                Pick(r, steps[o.index], inst.tradingSymbol, r.exchange, inst.lotSize, inst.tickSize, qt.last, qt.ask, o.steps, c).also {
                    runCatching { Diag.record("cheaper", "${r.owner}: ${it.fromWords} short of one lot (${r.trigger.words}); trying ${it.toWords}, ${o.steps} " +
                        "strike${if (o.steps == 1) "" else "s"} out (1 lot)") }
                }
            }
        }
    }

    /** The app's own contract for a strike (today's kept list only: never a download on the entry). */
    private fun contractFor(und: String, expiry: LocalDate, strike: Double, right: Right, exchange: String): Paper.Contract? = runCatching {
        val list = (if (exchange == "BFO") Market.cachedBfoContracts() else Market.cachedContracts())?.takeIf { it.first == Market.today() }?.second
            ?: return null
        list.firstOrNull { it.underlying == und && it.expiry == expiry && it.right == right && abs(it.strike - strike) < 1e-6 }?.let { c ->
            Paper.Contract(Paper.symbolOf(c), c.underlying, c.expiry, c.strike, c.right, c.lotSize, c.instrumentKey, exchange = exchange)
        }
    }.getOrNull()

    /** [r] gave up ([why], after [steps]): logged and counted; the entry goes on as before. Null. */
    private fun gaveUp(r: Req, why: String, steps: Int): Pick? {
        val from = "${r.underlying} ${fmt(r.fromStrike)} ${r.right.name}"
        record(Rec(System.currentTimeMillis(), marketDay(), r.owner, r.source, from, null, steps, false, why))
        runCatching { Diag.record("cheaper", "${r.owner}: funds short for $from (${r.trigger.words}) - no cheaper strike bought: $why") }
        return null
    }

    /** The stepped order went but bought nothing ([why]: refused, unfilled): logged and counted as a give-up. */
    fun notBought(p: Pick, why: String) {
        record(Rec(System.currentTimeMillis(), marketDay(), p.req.owner, p.req.source, p.fromWords, p.toWords, p.steps, false, why))
        runCatching { Diag.record("cheaper", "${p.req.owner}: ${p.toWords} for ${p.fromWords} bought nothing: $why") }
    }

    /** The words said for a stepped buy: "R72 Breadth Donchian: funds short for NIFTY 25000 PE, bought 24900 PE (1 lot)". */
    fun boughtWords(p: Pick, lots: Int): String = "${p.req.owner}: funds short for ${p.fromWords}, bought ${p.toWords} ($lots lot${if (lots == 1) "" else "s"})"

    /** The stepped buy filled ([lots]): said to Boss, logged and counted. Memory and a queued write; never throws. */
    fun bought(p: Pick, lots: Int) {
        val words = boughtWords(p, lots)
        record(Rec(System.currentTimeMillis(), marketDay(), p.req.owner, p.req.source, p.fromWords, p.toWords, p.steps, true, p.req.trigger.words))
        runCatching { Diag.record("cheaper", "$words - ${p.steps} strike${if (p.steps == 1) "" else "s"} out (${p.req.trigger.words})") }
        runCatching { com.optionslab.app.work.Alerts.success(words, "Live") }
    }

    private fun record(r: Rec) {
        val gen = wipeGen
        synchronized(lock) { recs = (recs + r).takeLast(KEPT) }
        scope.launch { persist(gen); publish() }
        publish()
    }

    /** Writes the record (a worker thread) unless an erase came since [gen]. True when written. */
    internal fun persist(gen: Int): Boolean = synchronized(writeLock) {
        if (gen != wipeGen) return@synchronized false
        val text = synchronized(lock) { recs }.joinToString("\n") { it.encode() }
        runCatching { SecurePrefs.putAllLazy(mapOf(K_LOG to text)) }.isSuccess
    }

    /** TEST ONLY: the erase generation now. */
    internal fun genForTest(): Int = wipeGen

    /** Today's records. Memory only. */
    fun recsOf(day: LocalDate = marketDay()): List<Rec> = synchronized(lock) { recs.filter { it.day == day } }

    /** The diagnostics' and the card's line: the switch and today's counts. Memory only; no rupees. */
    fun line(): String {
        val t = recsOf()
        val b = t.count { it.bought }
        val g = t.count { !it.bought }
        return "$SHORT: " + when {
            !loaded -> "not read yet (counts as off)"
            on -> "on - an R51 / R72 / Liquidity live buy the cash cannot pay for steps up to $MAX_STEPS strikes out, 1 lot"
            else -> "off - such buys go (or are skipped) as before"
        } + ". Today: $b bought at a cheaper strike, $g gave up."
    }

    private fun publish() {
        runCatching {
            val t = recsOf()
            _view.value = View(on, loaded, t.count { it.bought }, t.count { !it.bought }, line())
        }
    }

    /** What [set] says when on is asked without the PIN. */
    const val PIN_ON = "Turning the cheaper strike on lets the arms buy a cheaper option live when the cash is short: it takes your PIN. Nothing changed."

    /**
     * Boss's switch (Bot settings, or the change timeline's Undo with [log] false). Off lowers risk: at once, one tap. On adds live trades:
     * only with [pinConfirmed]. What to say. A worker thread only.
     */
    fun set(on: Boolean, pinConfirmed: Boolean = false, log: Boolean = true): String {
        if (on && !pinConfirmed) return PIN_ON
        if (!loaded) load()
        synchronized(lock) {
            // (Boss's own choice either way: a late start read never overrides it.)
            if (this.on == on) { switched = true; return "The cheaper strike is already ${if (on) "on" else "off"}. Nothing changed." }
            this.on = on
            loaded = true
            switched = true
        }
        runCatching { SecurePrefs.putAllSoon(mapOf(K_ON to on)) }
        runCatching { Diag.record("cheaper", "$SHORT: ${if (on) "off → on (by you, with your PIN)" else "on → off (by you)"}") }
        if (log) runCatching { com.optionslab.app.ira.Autopilot.logCheaperStrike(on) }
        publish()
        return if (on) "On: an R51, R72 or Liquidity live buy the cash cannot pay for steps to a cheaper strike (up to $MAX_STEPS out, 1 lot)."
        else "Off: such buys are no longer moved to a cheaper strike; they go (or are skipped) as before."
    }

    /** Erase everything ([PersonalStores]): as on a fresh phone - its default (on), no record; a late start read never brings an old choice back. */
    fun wipe() {
        synchronized(writeLock) {
            wipeGen++
            synchronized(lock) { on = true; loaded = true; switched = true; recs = emptyList() }
        }
        publish()
    }

    /** Whether Boss's choice (off) or a record is still in memory (the erase's check). Memory only. */
    internal fun holdsForErase(): Boolean = !on || synchronized(lock) { recs.isNotEmpty() }

    /** TEST ONLY: as a fresh start would have it (read, [on]), nothing switched, no record. */
    internal fun resetForTest(on: Boolean = false, loaded: Boolean = true) {
        synchronized(lock) { this.on = on; this.loaded = loaded; switched = false; recs = emptyList() }
        publish()
    }
}
