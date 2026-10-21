package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.Vault
import com.optionslab.engine.IST
import com.optionslab.engine.Right
import com.optionslab.engine.Upstox
import com.optionslab.engine.mcx.McxArmRules
import com.optionslab.engine.mcx.McxContract
import com.optionslab.engine.mcx.McxCosts
import com.optionslab.engine.mcx.McxInstruments
import com.optionslab.engine.mcx.McxNr7Rules
import com.optionslab.engine.mcx.McxSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

/**
 * NR7 breakout on crude, RECORD ONLY (21 Oct 2026, Boss: "Implement nr7, current exit"; the rules are the engine's
 * [McxNr7Rules], research R96 R3A). The idea has NOT passed its research gates, so under the PAPER rule it stays a note: this
 * host decides every signal exactly as tested and books a "would-be" trade at the prices it sees (the option's next minute
 * open, its minute low / close for the exits), with real MCX charges and the research's half-spreads, and NEVER places an
 * order - not on paper, not live. Nothing here calls [Paper], [ArmLive], the trade manager or any order path, and nothing
 * here is an open position of the account (the position watch, the expiry exit and the guards never see it).
 *
 * OFF by default (Boss's switch on Home's MCX group). Its own file (no backup). Runs with the MCX arms' pass (after them:
 * real positions first), at most once a minute, off the main thread; a failed read decides nothing that minute.
 */
object McxNr7Record {
    /** One would-be trade (record only). [qty] units of 1 lot; [exit] null while open. */
    data class Trade(
        val underlying: String, val symbol: String, val side: Int, val strike: Double, val qty: Int, val expiry: LocalDate,
        val optionKey: String, val futureKey: String, val fill: Double, val entryTime: LocalDateTime, val nr7Day: LocalDate,
        val futureStop: Double, val breakeven: Double, val lock: Double = Double.NaN, val peak: Double = fill,
        val lastEpoch: Long = 0L, val exit: Double? = null, val exitTime: LocalDateTime? = null, val why: String? = null,
        val cost: Double = 0.0,
        /** The option's last printed close seen (kept: a later pass with no print still has a price). */
        val lastOpt: Double = fill,
        /** An exit decided on a minute with no option print ([pendingWhy] at [pendingAt]): sold at the next print's open. */
        val pendingWhy: String? = null, val pendingAt: Long = 0L,
    ) {
        val open: Boolean get() = exit == null
        /** Rupees after charges and spreads, once closed. */
        val net: Double? get() = exit?.let { (it - fill) * qty - cost }
        fun held(): McxNr7Rules.Held = McxNr7Rules.Held(side, strike, fill, breakeven, futureStop, lock, peak)
    }

    /** What the row shows. */
    data class View(val on: Boolean = false, val status: Map<String, String> = emptyMap(), val open: List<Trade> = emptyList(),
                    val closed: List<Trade> = emptyList(), val log: List<String> = emptyList()) {
        val closedNet: Double get() = closed.sumOf { it.net ?: 0.0 }
    }

    /** Where prices and contracts come from (tests swap it). */
    internal interface Feed {
        suspend fun contracts(): List<McxContract>
        /** Finished 1-minute bars of [key] on [from]..[to] (IST days), oldest first. */
        suspend fun bars(key: String, from: LocalDate, to: LocalDate, now: LocalDateTime): List<Upstox.Bar>
        fun calendar(): McxSession.Calendar
        fun now(): LocalDateTime
        fun isOpen(): Boolean
    }

    private object RealFeed : Feed {
        override suspend fun contracts() = McxMarket.contracts()
        override suspend fun bars(key: String, from: LocalDate, to: LocalDate, now: LocalDateTime): List<Upstox.Bar> {
            val nowSec = now.atZone(IST).toEpochSecond()
            val today = now.toLocalDate()
            val past = if (from.isBefore(today)) Net.history(key, from, minOf(to, today.minusDays(1))) else emptyList()
            val live = if (!to.isBefore(today)) Net.intraday(key).filter { it.istDate == today } else emptyList()
            return (past + live).filter { it.epochSecond + 60 <= nowSec }.associateBy { it.epochSecond }.toSortedMap().values.toList()
        }
        override fun calendar() = McxMarket.calendar()
        override fun now() = McxMarket.now().withNano(0)
        override fun isOpen() = McxMarket.isOpen()
    }

    @Volatile internal var feed: Feed = RealFeed

    private class Book(
        var on: Boolean = false,
        val trades: MutableList<Trade> = ArrayList(),
        /** "day|underlying" -> the NR7 day's levels for that day ("none" when the last session was not an NR7 day). */
        val nr7: MutableMap<String, String> = HashMap(),
        val decided: MutableSet<String> = HashSet(),
        val status: MutableMap<String, String> = HashMap(),
        val log: ArrayList<String> = ArrayList(),
    )

    private lateinit var file: File
    private val lock = Mutex()
    @Volatile private var cache: Book? = null
    @Volatile private var lastMinute = -1L
    private val written = Vault.LastWrite()
    private const val LOG_KEPT = 200
    private const val CLOSED_KEPT = 400

    private val _view = MutableStateFlow(View())
    val view: StateFlow<View> = _view

    fun init(context: Context) {
        file = File(context.applicationContext.noBackupFilesDir, "mcx_nr7_record.vault")
        // Read once in the background, so the MCX watch knows at once (after a restart) that it records ([onQuick]).
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch { runCatching { refresh() } }
    }

    /** A pass's whole budget: the network reads never hold the MCX pass (and the steps after it) longer than this. */
    private const val PASS_BUDGET_MS = 20_000L

    /** When a history read last failed, per underlying (memory): tried again after half an hour, not every minute. */
    private val historyTried = HashMap<String, Long>()

    private fun ready() = ::file.isInitialized

    /** Switched on (memory only: the MCX watch's scheduling reads it; false before the book is read). */
    fun onQuick(): Boolean = _view.value.on

    private fun book(): Book {
        cache?.let { return it }
        val b = Book()
        runCatching {
            val o = JSONObject(String(Vault.readFileSteady(file) ?: return@runCatching, Charsets.UTF_8))
            b.on = o.optBoolean("on", false)
            o.optJSONArray("trades")?.let { a -> for (i in 0 until a.length()) readTrade(a.getJSONObject(i))?.let { b.trades += it } }
            o.optJSONObject("nr7")?.let { m -> m.keys().forEach { b.nr7[it] = m.getString(it) } }
            o.optJSONArray("decided")?.let { a -> for (i in 0 until a.length()) b.decided += a.getString(i) }
            o.optJSONObject("status")?.let { m -> m.keys().forEach { b.status[it] = m.getString(it) } }
            o.optJSONArray("log")?.let { a -> for (i in 0 until a.length()) b.log += a.getString(i) }
        }.onFailure { if (file.exists()) Vault.setAside(file) }
        cache = b
        publish(b)
        return b
    }

    suspend fun refresh(): View { if (ready()) lock.withLock { book() }; return _view.value }

    /** Boss's switch: record (never trade) or stop recording. What to say. */
    suspend fun setOn(on: Boolean): String {
        if (!ready()) return "The NR7 record is not ready yet."
        val said = lock.withLock {
            val b = book()
            if (b.on == on) return@withLock "${McxNr7Rules.LABEL} is already ${if (on) "recording" else "off"}."
            b.on = on
            note(b, "all", if (on) "recording switched on by you: would-be trades only, no orders (not proven: ${McxNr7Rules.RECORD})"
                else "recording switched off by you" + if (b.trades.any { it.open }) "; the open would-be trade is left as it is" else "")
            save(b)
            if (on) "${McxNr7Rules.LABEL} on: it records what it would trade, and places no order." else "${McxNr7Rules.LABEL} off."
        }
        runCatching { Diag.record("mcx-nr7", said) }
        return said
    }

    // ---- the pass ---------------------------------------------------------------------------------------------------------

    /** One pass, at most once a minute (after the MCX paper arms in the same pass). Never throws. */
    suspend fun tick() {
        if (!ready()) return
        val f = feed
        val now = f.now()
        val stamp = now.atZone(IST).toEpochSecond() / 60
        if (stamp == lastMinute) return
        runCatching {
            kotlinx.coroutines.withTimeoutOrNull(PASS_BUDGET_MS) { lock.withLock {
                if (stamp == lastMinute) return@withLock              // another caller ran this minute's pass meanwhile
                val b = book()
                if (!b.on && b.trades.none { it.open }) return@withLock
                lastMinute = stamp
                var changed = false
                if (f.isOpen()) for (u in McxNr7Rules.UNDERLYINGS) {
                    changed = runCatching { pass(b, u, now, f) }.getOrElse { b.status[u] = "error: ${it.javaClass.simpleName}"; true } || changed
                }
                val closed = b.trades.count { !it.open }
                if (closed > CLOSED_KEPT) { var drop = closed - CLOSED_KEPT; b.trades.removeAll { !it.open && drop-- > 0 }; changed = true }
                if (changed) save(b)
            } }
        }
    }

    private fun day(bars: List<Upstox.Bar>, d: LocalDate): McxNr7Rules.Day? =
        McxNr7Rules.day(d, bars.filter { it.istDate == d }.map { mn(it) })

    private fun mn(b: Upstox.Bar) = McxNr7Rules.Minute(b.istMinute, b.open, b.high, b.low, b.close)

    /** The last session before [today] if it was an NR7 day ("hc,lc,date"), "none" otherwise, or null when not readable now. */
    private suspend fun nr7Of(fut: McxContract, today: LocalDate, now: LocalDateTime, cal: McxSession.Calendar, f: Feed): String? {
        val sessions = generateSequence(today.minusDays(1)) { it.minusDays(1) }.take(30).filter { cal.isTradingDay(it) }
            .take(McxNr7Rules.LOOKBACK).toList().reversed()
        if (sessions.size < McxNr7Rules.LOOKBACK) return null
        val bars = f.bars(fut.upstoxKey, sessions.first(), sessions.last(), now)
        val days = sessions.map { day(bars, it) ?: return null }        // a session with no data: not decided on a gap
        if (!McxNr7Rules.isNr7(days)) return "none"
        val d = days.last()
        return "${d.highClose},${d.lowClose},${d.date}"
    }

    private suspend fun pass(b: Book, u: String, now: LocalDateTime, f: Feed): Boolean {
        val today = now.toLocalDate()
        val cal = f.calendar()
        b.trades.firstOrNull { it.underlying == u && it.open }?.let { return manage(b, it, now, cal, f) }
        if (!b.on) return false
        val key = "$today|$u"
        if (key in b.decided) return false
        val all = f.contracts()
        val chain = McxInstruments.nearChain(all, u, today)
        if (chain.isEmpty()) return decide(b, u, key, "no option chain")
        val fut = McxInstruments.underlyingFuture(all, chain.first()) ?: return decide(b, u, key, "no future")
        val lv = b.nr7[key] ?: run {
            val tried = historyTried[key]
            if (tried != null && System.currentTimeMillis() - tried < 30 * 60_000L) return setStatus(b, u, "history not read yet (tried again shortly)")
            (nr7Of(fut, today, now, cal, f) ?: run { historyTried[key] = System.currentTimeMillis(); return setStatus(b, u, "history not read yet") })
                .also { b.nr7[key] = it; b.nr7.keys.sorted().dropLast(40).forEach { k -> b.nr7.remove(k) } }
        }
        if (lv == "none") return decide(b, u, key, "the last session was not an NR7 day")
        val (hc, lc, d) = lv.split(',')
        val nr7 = McxNr7Rules.Day(LocalDate.parse(d), hc.toDouble(), lc.toDouble(), hc.toDouble(), lc.toDouble())
        val fb = f.bars(fut.upstoxKey, today, today, now).filter { it.istDate == today }.map { mn(it) }
        val sig = McxNr7Rules.signal(nr7, fb)
        val minute = now.hour * 60 + now.minute
        if (sig == null) {
            if (minute > McxNr7Rules.ENTRY_UNTIL) return decide(b, u, key, "NR7 day $d: no break by 22:00")
            return setStatus(b, u, "NR7 day $d: watching for a close above %.2f or below %.2f (09:15-22:00)".format(Locale.ENGLISH, nr7.highClose, nr7.lowClose))
        }
        val expiry = chain.first().expiry
        val left = McxArmRules.tradingDaysLeft(today, expiry, cal) - 1           // full sessions before the expiry day
        val facts = "NR7 day $d, break ${if (sig.side > 0) "up" else "down"} at %s close %.2f".format(Locale.ENGLISH, hhmm(sig.minute), sig.close)
        if (!McxNr7Rules.expiryOk(today == expiry, left)) return decide(b, u, key, "$facts: too near the option expiry ($left sessions)")
        val strike = McxNr7Rules.strike(chain.map { it.strike }, sig.close, sig.side) ?: return decide(b, u, key, "$facts: no 1-ITM strike")
        val right = if (sig.side > 0) Right.CE else Right.PE
        val oc = chain.firstOrNull { it.strike == strike && it.right == right } ?: return decide(b, u, key, "$facts: no $strike ${right.name}")
        val ob = f.bars(oc.upstoxKey, today, today, now).filter { it.istDate == today }
        val first = ob.firstOrNull { it.istMinute > sig.minute && it.istMinute <= sig.minute + McxNr7Rules.FILL_WAIT && it.open > 0 }
        if (first == null) {
            if (minute > sig.minute + McxNr7Rules.FILL_WAIT + 1) return decide(b, u, key, "$facts: ${oc.tradingSymbol} did not print within 15 minutes")
            return setStatus(b, u, "$facts: waiting for ${oc.tradingSymbol}'s next print")
        }
        b.decided += key
        val qty = oc.multiplier * McxArmRules.LOTS
        val fill = first.open
        val be = fill + (McxCosts.roundTrip(fill, qty, true) / qty) + 2 * McxNr7Rules.half(first.istMinute) * fill
        val t = Trade(u, oc.tradingSymbol, sig.side, strike, qty, expiry, oc.upstoxKey, fut.upstoxKey, fill,
            LocalDateTime.ofEpochSecond(first.epochSecond, 0, java.time.ZoneOffset.ofHoursMinutes(5, 30)), nr7.date,
            McxNr7Rules.futureStop(nr7, sig.side), be, lastEpoch = first.epochSecond - 1)
        b.trades += t
        line(b, u, "would buy ${oc.tradingSymbol} @ %.2f (%s) · record only, no order".format(Locale.ENGLISH, fill, facts))
        manage(b, b.trades.last(), now, cal, f)
        return true
    }

    /** The last minute (epoch second of its start) a trade may be held to: the 3rd session's square-off, or the expiry eve's. */
    private fun lastEpoch(t: Trade, cal: McxSession.Calendar): Long {
        var d = t.entryTime.toLocalDate()
        var n = 1
        var guard = 0
        while (n < McxNr7Rules.HOLD_SESSIONS && guard < 30) {
            val next = generateSequence(d.plusDays(1)) { it.plusDays(1) }.take(10).firstOrNull { cal.isTradingDay(it) } ?: break
            if (!next.isBefore(t.expiry)) break                       // never into the expiry day
            d = next; n++; guard++
        }
        val close = cal.window(d)?.close?.let { it.hour * 60 + it.minute } ?: McxSession.close(d).let { it.hour * 60 + it.minute }
        val sq = McxNr7Rules.squareOff(close)
        return d.atTime(sq / 60, sq % 60).atZone(IST).toEpochSecond()
    }

    /** Walk the finished minutes since the last one seen; book the exit when one is reached. */
    private suspend fun manage(b: Book, t0: Trade, now: LocalDateTime, cal: McxSession.Calendar, f: Feed): Boolean {
        val i = b.trades.indexOf(t0)
        if (i < 0) return false
        var t = t0
        val from = LocalDateTime.ofEpochSecond(t.lastEpoch + 1, 0, java.time.ZoneOffset.ofHoursMinutes(5, 30)).toLocalDate()
        val end = lastEpoch(t, cal)
        val to = minOf(now.toLocalDate(), LocalDateTime.ofEpochSecond(end, 0, java.time.ZoneOffset.ofHoursMinutes(5, 30)).toLocalDate())
        val opt = f.bars(t.optionKey, from, to, now).filter { it.epochSecond > t.lastEpoch }.associateBy { it.epochSecond }
        val fut = f.bars(t.futureKey, from, to, now).filter { it.epochSecond > t.lastEpoch }.associateBy { it.epochSecond }
        val listed = f.contracts().filter { it.isOption && it.name == t.underlying && it.expiry == t.expiry }.map { it.strike }.distinct()
        val entryEpoch = t.entryTime.atZone(IST).toEpochSecond()
        for (e in (opt.keys + fut.keys).sorted()) {
            if (e > end) break
            val o = opt[e]?.let { mn(it) }
            val fc = fut[e]?.close ?: Double.NaN
            // An exit decided on a minute with no print: the next print's open within 10 minutes, else the last print (research).
            val why = t.pendingWhy
            if (why != null) {
                if (o != null && o.open > 0) { b.trades[i] = close(b, t.copy(lastEpoch = e), o.open, e, why); return true }
                if (e - t.pendingAt >= McxNr7Rules.NEXT_PRINT_MINUTES * 60L) { b.trades[i] = close(b, t.copy(lastEpoch = e), t.lastOpt, e, why); return true }
                t = t.copy(lastEpoch = e)
                continue
            }
            val (exit, held) = McxNr7Rules.step(t.held(), o, fc, listed, edgeOk = e > entryEpoch)
            t = t.copy(lock = held.lock, peak = held.peak, lastEpoch = e, lastOpt = o?.close?.takeIf { it > 0 } ?: t.lastOpt)
            if (exit != null) {
                val px = exit.price.takeIf { it.isFinite() && it > 0 }
                if (px != null) { b.trades[i] = close(b, t, px, e, exit.why); return true }
                t = t.copy(pendingWhy = exit.why, pendingAt = e)
                continue
            }
            if (e == end) { b.trades[i] = close(b, t, t.lastOpt, e, "time"); return true }
        }
        // Past the last minute (no print at it), or a pending exit at the close: out at the last print seen.
        if (now.atZone(IST).toEpochSecond() > end + 60) { b.trades[i] = close(b, t, t.lastOpt, end, t.pendingWhy ?: "time"); return true }
        b.trades[i] = t
        val s = "holding ${t.symbol} (would-be) since %s, fill %.2f, lock %s".format(Locale.ENGLISH, t.entryTime.toLocalTime(), t.fill,
            if (t.lock.isNaN()) "none yet" else "%.2f".format(Locale.ENGLISH, t.lock))
        return setStatus(b, t.underlying, s) || t != t0
    }

    private fun close(b: Book, t: Trade, px: Double, epoch: Long, why: String): Trade {
        val at = LocalDateTime.ofEpochSecond(epoch, 0, java.time.ZoneOffset.ofHoursMinutes(5, 30))
        val minIn = t.entryTime.hour * 60 + t.entryTime.minute
        val minOut = at.hour * 60 + at.minute
        val cost = McxCosts.charge("BUY", t.fill * t.qty, true).toDouble() + McxCosts.charge("SELL", px * t.qty, true).toDouble() +
            McxNr7Rules.half(minIn) * t.fill * t.qty + McxNr7Rules.half(minOut) * px * t.qty
        val done = t.copy(exit = px, exitTime = at, why = why, cost = cost, pendingWhy = null)
        line(b, t.underlying, "would sell ${t.symbol} @ %.2f (%s): %s after charges · record only".format(Locale.ENGLISH, px, why, rs(done.net ?: 0.0)))
        return done
    }

    private fun decide(b: Book, u: String, key: String, why: String): Boolean { b.decided += key; line(b, u, why); return true }

    private fun setStatus(b: Book, u: String, s: String): Boolean { if (b.status[u] == s) return false; b.status[u] = s; return true }

    private fun line(b: Book, u: String, s: String) { b.status[u] = s; note(b, u, s) }

    private fun note(b: Book, u: String, s: String) {
        b.log += "${feed.now().toLocalDate()} ${feed.now().toLocalTime().withSecond(0).withNano(0)} $u: $s"
        while (b.log.size > LOG_KEPT) b.log.removeAt(0)
    }

    private fun hhmm(m: Int) = "%02d:%02d".format(m / 60, m % 60)

    private fun rs(x: Double) = (if (x < 0) "-Rs " else "+Rs ") + "%,.0f".format(Locale.ENGLISH, kotlin.math.abs(x))

    // ---- the file -----------------------------------------------------------------------------------------------------------

    private fun readTrade(o: JSONObject): Trade? = runCatching {
        Trade(o.getString("u"), o.getString("sym"), o.getInt("side"), o.getDouble("strike"), o.getInt("qty"), LocalDate.parse(o.getString("expiry")),
            o.getString("okey"), o.getString("fkey"), o.getDouble("fill"), LocalDateTime.parse(o.getString("in")), LocalDate.parse(o.getString("nr7")),
            o.getDouble("fstop"), o.getDouble("be"), o.optDouble("lock", Double.NaN), o.optDouble("peak", o.getDouble("fill")),
            o.optLong("last", 0L), if (o.has("exit")) o.getDouble("exit") else null, o.optString("out").ifEmpty { null }?.let { LocalDateTime.parse(it) },
            o.optString("why").ifEmpty { null }, o.optDouble("cost", 0.0), o.optDouble("lastOpt", o.getDouble("fill")),
            o.optString("pend").ifEmpty { null }, o.optLong("pendAt", 0L))
    }.getOrNull()

    private fun writeTrade(t: Trade): JSONObject = JSONObject().put("u", t.underlying).put("sym", t.symbol).put("side", t.side).put("strike", t.strike)
        .put("qty", t.qty).put("expiry", t.expiry.toString()).put("okey", t.optionKey).put("fkey", t.futureKey).put("fill", t.fill)
        .put("in", t.entryTime.toString()).put("nr7", t.nr7Day.toString()).put("fstop", t.futureStop).put("be", t.breakeven)
        .put("peak", t.peak).put("last", t.lastEpoch).put("cost", t.cost).put("lastOpt", t.lastOpt)
        .put("pend", t.pendingWhy ?: "").put("pendAt", t.pendingAt)
        .apply {
            if (!t.lock.isNaN()) put("lock", t.lock)
            t.exit?.let { put("exit", it) }; t.exitTime?.let { put("out", it.toString()) }; t.why?.let { put("why", it) }
        }

    private fun save(b: Book) {
        val o = JSONObject().put("on", b.on)
            .put("trades", JSONArray().apply { b.trades.forEach { put(writeTrade(it)) } })
            .put("nr7", JSONObject().apply { b.nr7.forEach { (k, v) -> put(k, v) } })
            .put("decided", JSONArray().apply { b.decided.sorted().takeLast(200).forEach { put(it) } })
            .put("status", JSONObject().apply { b.status.forEach { (k, v) -> put(k, v) } })
            .put("log", JSONArray().apply { b.log.takeLast(LOG_KEPT).forEach { put(it) } })
        runCatching { written.write(file, o.toString().toByteArray(Charsets.UTF_8)) }
        publish(b)
    }

    private fun publish(b: Book) {
        // The MCX watch's plain hint (the main thread's): it runs while this records, even with every paper arm off.
        runCatching { McxMarket.noteArmsArmed(b.on || McxPaperArms.view.value.armed.values.any { it }) }
        _view.value = View(b.on, b.status.toMap(), b.trades.filter { it.open }, b.trades.filter { !it.open }.takeLast(60), b.log.takeLast(40))
    }

    /** The diagnostics' line. */
    fun diagLine(): String {
        val v = _view.value
        return "NR7 crude (record only): ${if (v.on) "recording" else "off"}, open ${v.open.size}, ${v.closed.size} closed would-be " +
            "%,.0f after charges".format(Locale.ENGLISH, v.closedNet) + " · no orders, ever"
    }

    /** TEST ONLY: as after a restart - the book read again from its file. */
    internal fun resetCacheForTest() { cache = null; lastMinute = -1L }

    /** TEST ONLY: a fresh book in [context]'s file, with [f] as the feed. */
    internal fun resetForTest(context: Context, f: Feed) {
        file = File(context.applicationContext.noBackupFilesDir, "mcx_nr7_record.vault")
        historyTried.clear()
        file.delete(); written.forget()
        cache = null; lastMinute = -1L; feed = f
        _view.value = View()
    }
}
