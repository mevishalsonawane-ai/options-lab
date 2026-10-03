package com.optionslab.app.ira

import com.optionslab.app.data.AppSettings
import com.optionslab.app.data.Paper
import com.optionslab.ira.Candle
import com.optionslab.ira.Solo
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import com.optionslab.ira.Market as IraMarket

/**
 * Solo (the owner's wish, 2026-10-03): Jarvis trades by himself, ON PAPER ONLY, when Boss switches it on. The setup and
 * the risk rules are [Solo]'s (pure, the same code the two-year backtest ran); this object feeds it fresh minute bars,
 * places and closes the paper option, keeps the record and stops itself when its own losses say so.
 *
 * Never live: Solo has no path to Zerodha. Real money stays a separate decision of Boss's, in the app, after the
 * paper record has earned it.
 */
internal object IraSolo {
    private const val KEY_ON = "jarvis.solo"
    private const val KEY_PAUSED = "jarvis.solo.paused"
    private const val KEY = "jarvis.solo.trades"
    private val IST = ZoneId.of("Asia/Kolkata")
    private val MARKETS = listOf(IraMarket.NIFTY, IraMarket.BANKNIFTY)

    /** One trade a day: chosen on 2024-25 (best there, smallest drawdown), checked on the other years; see SOLO.md. */
    val RULES = Solo.Rules(maxPerDay = 1)
    /** The day's loss limit for Solo's trades, and the drawdown from its best at which it pauses itself. */
    const val DAY_LOSS = 5_000.0
    const val PAUSE_DRAWDOWN = 15_000.0
    /** Rupees a round trip on top of the fills (brokerage and charges), as in the backtest. */
    private const val COSTS = 60.0

    /** The two-year test, in one line each (research data, real option prices, Rs 60 a trip), for Boss to judge. */
    val BACKTEST = listOf(
        "NIFTY Apr 2024-Apr 2025: 217 trades, 40% winners, +Rs 52,151 (1 lot of 75), worst drawdown Rs 23,261.",
        "NIFTY Apr 2025-Apr 2026: 219 trades, 36% winners, -Rs 42,968, worst drawdown Rs 45,221.",
        "BANKNIFTY Feb 2025-Feb 2026: 195 trades, 39% winners, +Rs 2,041 (1 lot of 30), worst drawdown Rs 21,860.",
    )

    var on: Boolean
        get() = runCatching { com.optionslab.app.security.SecurePrefs.getBoolean(KEY_ON, false) }.getOrDefault(false)
        set(v) {
            runCatching { com.optionslab.app.security.SecurePrefs.put(KEY_ON, v) }
            if (v) runCatching { com.optionslab.app.security.SecurePrefs.put(KEY_PAUSED, null) }
            IraActivity.add(if (v) "Solo switched on (paper only)." else "Solo switched off.")
        }

    /** Why Solo paused itself (null: not paused). Switching it on again clears it. */
    val paused: String? get() = runCatching { com.optionslab.app.security.SecurePrefs.getString(KEY_PAUSED) }.getOrNull()

    /** One Solo trade; [closed] once out, with [exitPrice] and [net] (rupees after costs). */
    data class T(val day: String, val market: String, val symbol: String, val call: Boolean, val qty: Int, val entry: Double,
                 val entryMinute: Int, val index: Double, val level: Double, val target: Double, val why: String,
                 val closed: Boolean = false, val exitPrice: Double? = null, val net: Double? = null, val exit: String? = null)

    fun all(): List<T> = runCatching {
        val a = JSONArray(com.optionslab.app.security.SecurePrefs.getString(KEY) ?: "[]")
        (0 until a.length()).mapNotNull { i -> runCatching { a.getJSONObject(i).let { o ->
            T(o.getString("d"), o.getString("m"), o.getString("s"), o.getBoolean("c"), o.getInt("q"), o.getDouble("e"), o.getInt("em"),
                o.getDouble("i"), o.getDouble("lv"), o.getDouble("tg"), o.getString("w"), o.optBoolean("x"),
                if (o.has("xp")) o.getDouble("xp") else null, if (o.has("n")) o.getDouble("n") else null, o.optString("xr").ifEmpty { null })
        } }.getOrNull() }
    }.getOrDefault(emptyList())

    private fun save(list: List<T>) = runCatching {
        com.optionslab.app.security.SecurePrefs.put(KEY, JSONArray().apply { list.takeLast(500).forEach { t ->
            put(JSONObject().put("d", t.day).put("m", t.market).put("s", t.symbol).put("c", t.call).put("q", t.qty).put("e", t.entry)
                .put("em", t.entryMinute).put("i", t.index).put("lv", t.level).put("tg", t.target).put("w", t.why).put("x", t.closed)
                .apply { t.exitPrice?.let { put("xp", it) }; t.net?.let { put("n", it) }; t.exit?.let { put("xr", it) } })
        } }.toString())
    }

    private val lock = Mutex()

    /** The minute of the session now (0 = 09:15). */
    private fun minuteNow(): Int = LocalTime.now(IST).let { it.hour * 60 + it.minute - (9 * 60 + 15) }

    /** Today's 1-minute bars as the session's minutes (0 = 09:15), gaps filled from the minute before; the forming minute left out. */
    internal fun session(bars: List<Candle>, today: LocalDate, now: Int): List<Candle> {
        val byMin = HashMap<Int, Candle>()
        for (b in bars) if (b.t.toLocalDate() == today) {
            val m = b.t.hour * 60 + b.t.minute - (9 * 60 + 15)
            if (m in 0 until now) byMin[m] = b
        }
        val first = byMin[0] ?: return emptyList()
        val out = ArrayList<Candle>(now)
        var last = first
        for (m in 0 until now) { val b = byMin[m] ?: last; out += b; last = b }
        return out
    }

    /** The body size that counts as big, from the earlier days the app holds (null: fewer than 100 15-minute candles yet). */
    private fun big(bars: List<Candle>, today: LocalDate): Double? {
        val days = bars.filter { it.t.toLocalDate() != today }.groupBy { it.t.toLocalDate() }.toSortedMap().values.toList().takeLast(20)
        val earlier15 = days.flatMap { d -> Solo.fifteen(session(d, d.first().t.toLocalDate(), 375)) }
        return Solo.bigBody(earlier15, RULES.bigQuantile)
    }

    /** Every market-watch pass while Solo is on: manage the open trade, or look for the next one. */
    suspend fun tick() = lock.withLock {
        if (!com.optionslab.app.BuildConfig.JARVIS || !on) return@withLock
        if (!com.optionslab.app.data.Market.isOpen()) return@withLock
        val today = com.optionslab.app.data.Market.today()
        val now = minuteNow()
        val list = all()
        list.lastOrNull { !it.closed }?.let { manage(it, list, today, now); return@withLock }
        if (paused != null) return@withLock
        // The risk book: a professional's day.
        val mine = list.filter { it.day == today.toString() && it.closed }
        val book = mine.fold(Solo.Day()) { b, t -> b.after(t.net ?: 0.0) }
        if (book.canTrade(RULES, DAY_LOSS) != null) return@withLock
        val s = AppSettings.load()
        if (s.guardKill || com.optionslab.app.data.LossBreaker.trippedToday()) return@withLock
        if (runCatching { IraHub.tradeCheckFast().level }.getOrNull() == com.optionslab.ira.TradeCheck.Level.STOP) return@withLock
        val busy = mine.maxOfOrNull { it.entryMinute } ?: -1
        for (m in MARKETS) {
            val bars = IraHub.freshBars(m)
            val day = session(bars, today, now)
            if (day.size < 20) continue
            val big = big(IraHub.recentBars(m) + bars, today) ?: continue
            // The last two closes only (a pass can come a minute late); an older signal is not chased.
            val sig = (day.size - 1 downTo maxOf(0, day.size - 2)).firstNotNullOfOrNull { k -> Solo.signal(day, k, big, busy, RULES) } ?: continue
            enter(m, sig, list, today)
            return@withLock
        }
    }

    private suspend fun enter(m: IraMarket, sig: Solo.Signal, list: List<T>, today: LocalDate) {
        val u = m.name
        val c = IraNewsTrades.contract(u, sig.index, sig.call) ?: return
        val q = runCatching { Paper.quote(c) }.getOrNull() ?: return
        com.optionslab.ira.StrikeLiquidity.problem(q.bid, q.ask, q.volume, c.lotSize)?.let { IraActivity.add("Solo skipped ${c.symbol}: $it"); return }
        val r = Paper.place(c, "BUY", 1, "MARKET", "MIS", null, null, q)
        val fill = r.events.filterIsInstance<com.optionslab.engine.sandbox.SandboxEvent.Fill>().firstOrNull() ?: return
        r.orderId?.let { com.optionslab.app.data.Strategies.tagOwner("paper:$it", "Jarvis solo · entry") }
        // A safety stop on the premium in case the app is stopped: Solo's own exit is on the index.
        com.optionslab.app.data.Protections.protectPaper(c.symbol, "MIS", fill.quantity, fill.price, fill.price * 0.6, null, null)
        val t = T(today.toString(), u, c.symbol, sig.call, fill.quantity, fill.price, sig.entryMinute, sig.index, sig.level, sig.target, sig.why)
        save(list + t)
        val line = "Solo (paper): bought ${c.symbol} at ${"%.2f".format(fill.price)}. Why: ${sig.why}. Out if ${m.label} " +
            "${if (sig.call) "falls to" else "rises to"} ${"%,.0f".format(sig.level)}; target ${"%,.0f".format(sig.target)}; 15:10 at the latest."
        tell(line)
        IraHub.appContext()?.let { com.optionslab.app.work.Notifier.orderFilled(it, "BUY", fill.quantity, c.symbol, fill.price, "Paper", "Jarvis solo") }
    }

    private suspend fun manage(t: T, list: List<T>, today: LocalDate, now: Int) {
        val m = IraMarket.valueOf(t.market)
        val held = runCatching { Paper.snapshot().positions.positions.any { it.symbol == t.symbol && it.quantity != 0 } }.getOrDefault(true)
        if (!held) {
            // Closed outside Solo (its safety stop or the 15:15 square-off): the last sell is the exit.
            val px = runCatching { Paper.snapshot().trades.lastOrNull { it.symbol == t.symbol && it.action == "SELL" }?.price }.getOrNull()
            finish(t, list, px, "closed by the safety stop or the square-off")
            return
        }
        val day = session(IraHub.freshBars(m), today, now)
        if (day.size <= t.entryMinute) return
        val s = Solo.Signal(t.call, t.entryMinute, t.index, t.level, t.target, 0, t.why)
        var best = 0.0
        var how: Solo.Exit? = null
        for (j in t.entryMinute until day.size) {
            how = Solo.exit(s, day[j], j, best, RULES)
            best = Solo.favour(s, day[j], best)
            if (how != null) break
        }
        if (how == null && now >= Solo.CUT) how = Solo.Exit.TIME
        how ?: return
        val r = Paper.close(t.symbol, "MIS")
        val px = r.events.filterIsInstance<com.optionslab.engine.sandbox.SandboxEvent.Fill>().firstOrNull()?.price
        runCatching { com.optionslab.app.data.Protections.removeSymbol(false, "NFO", t.symbol) }
        finish(t, list, px, when (how) {
            Solo.Exit.STOP -> "stop: ${m.label} through ${"%,.0f".format(t.level)}"
            Solo.Exit.TARGET -> "target reached"
            Solo.Exit.SLOW -> "no follow-through"
            Solo.Exit.TIME -> "15:10"
        })
    }

    private fun finish(t: T, list: List<T>, px: Double?, why: String) {
        val net = px?.let { (it - t.entry) * t.qty - COSTS }
        val done = t.copy(closed = true, exitPrice = px, net = net, exit = why)
        val all = list.map { if (it === t) done else it }
        save(all)
        tell("Solo (paper): closed ${t.symbol}" + (px?.let { " at ${"%.2f".format(it)}" } ?: "") + " - $why. " +
            (net?.let { "Result ${com.optionslab.ira.AppFacts.rs(it)}." } ?: "") + " " + record(all))
        // Discipline: a drawdown this deep from Solo's best means the setup is not working now - it stops and says so.
        var peak = 0.0; var eq = 0.0
        for (x in all.filter { it.closed }) { eq += x.net ?: 0.0; peak = maxOf(peak, eq) }
        if (peak - eq >= PAUSE_DRAWDOWN && paused == null) {
            val why2 = "Solo is down ${com.optionslab.ira.AppFacts.rs(eq - peak).removePrefix("-")} from its best: paused until you switch it on again."
            runCatching { com.optionslab.app.security.SecurePrefs.put(KEY_PAUSED, why2) }
            tell(why2)
        }
    }

    private fun tell(line: String) {
        IraActivity.add(line)
        IraHub.note(line)
        runCatching { JarvisVoice.announce(line) }
    }

    /** "How is Solo doing": on or off, paused or not, and the record. */
    fun status(): String = (if (on) "Solo is on, Boss (paper only)." else "Solo is off, Boss: switch it on in Jarvis settings (paper only).") +
        (paused?.let { " $it" } ?: "") + " " + record()

    /** Solo's paper record in one line. */
    fun record(list: List<T> = all()): String {
        val c = list.filter { it.closed && it.net != null }
        if (c.isEmpty()) return "Solo has no closed trades yet."
        val net = c.sumOf { it.net!! }
        return "Solo so far: ${c.size} trades, ${c.count { it.net!! > 0 }} won, net ${com.optionslab.ira.AppFacts.rs(net)} on paper."
    }
}
