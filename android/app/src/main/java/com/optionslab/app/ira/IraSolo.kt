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
    private const val KEY_FROM = "jarvis.solo.from"
    private val IST = ZoneId.of("Asia/Kolkata")
    private val MARKETS = listOf(IraMarket.NIFTY, IraMarket.BANKNIFTY)

    /**
     * One trade a day, and only while the setup's last 60 signals (on the index, before costs) averaged above zero:
     * chosen on 2024-25, checked on the other years; see SOLO.md.
     */
    val RULES = Solo.Rules(maxPerDay = 1, recentN = 60, profitLock = false, premiumStop = STOP_LOSS)
    /**
     * Always a stop-loss on the option itself (Boss's rule, 3 Oct: no ladder), sized by Solo's own study: tighter stops
     * (15-25%) sit inside an option's normal swings and are hit too often; at 30% it fired on only 10 of 511 tested
     * trades - insurance for a sharp fall, not an edge (40% scored about the same). See SOLO.md.
     */
    const val STOP_LOSS = 0.30

    /** The day's loss limit for Solo's trades, and the drawdown from its best at which it pauses itself. */
    const val DAY_LOSS = 5_000.0
    const val PAUSE_DRAWDOWN = 15_000.0
    /** Rupees a round trip on top of the fills (brokerage and charges), as in the backtest. */
    private const val COSTS = 60.0

    /** The two-year test, in one line each (research data, real option prices, Rs 60 a trip), for Boss to judge. */
    val BACKTEST = listOf(
        "NIFTY Apr 2024-Apr 2025: 186 trades, 40% winners, +Rs 69,393 (1 lot of 75), worst drawdown Rs 13,553.",
        "NIFTY Apr 2025-Apr 2026: 170 trades, 35% winners, -Rs 32,796, worst drawdown Rs 42,351.",
        "BANKNIFTY Feb 2025-Feb 2026: 155 trades, 43% winners, +Rs 14,853 (1 lot of 30), worst drawdown Rs 15,657.",
        "(Always a 30% stop-loss on the option - in the test it fired on only 10 of 511 trades: insurance, not an edge - and standing aside while its last 60 signals lost.)",
    )

    var on: Boolean
        get() = runCatching { com.optionslab.app.security.SecurePrefs.getBoolean(KEY_ON, false) }.getOrDefault(false)
        set(v) {
            runCatching { com.optionslab.app.security.SecurePrefs.put(KEY_ON, v) }
            // Switched on again: the drawdown that paused it is not counted again (measured from here).
            if (v) runCatching { com.optionslab.app.security.SecurePrefs.put(KEY_PAUSED, null); com.optionslab.app.security.SecurePrefs.put(KEY_FROM, all().size) }
            IraActivity.add(if (v) "Solo switched on (paper only)." else "Solo switched off.")
        }

    /** Why Solo paused itself (null: not paused). Switching it on again clears it. */
    val paused: String? get() = runCatching { com.optionslab.app.security.SecurePrefs.getString(KEY_PAUSED) }.getOrNull()

    /** One Solo trade; [closed] once out, with [exitPrice] and [net] (rupees after costs). */
    data class T(val day: String, val market: String, val symbol: String, val call: Boolean, val qty: Int, val entry: Double,
                 val entryMinute: Int, val index: Double, val level: Double, val target: Double, val why: String,
                 val closed: Boolean = false, val exitPrice: Double? = null, val net: Double? = null, val exit: String? = null,
                 /** The paper order ids of the entry and the exit (for the order book: "who placed it, which order"). */
                 val orderId: String? = null, val exitOrderId: String? = null)

    fun all(): List<T> = runCatching {
        val a = JSONArray(com.optionslab.app.security.SecurePrefs.getString(KEY) ?: "[]")
        (0 until a.length()).mapNotNull { i -> runCatching { a.getJSONObject(i).let { o ->
            T(o.getString("d"), o.getString("m"), o.getString("s"), o.getBoolean("c"), o.getInt("q"), o.getDouble("e"), o.getInt("em"),
                o.getDouble("i"), o.getDouble("lv"), o.getDouble("tg"), o.getString("w"), o.optBoolean("x"),
                if (o.has("xp")) o.getDouble("xp") else null, if (o.has("n")) o.getDouble("n") else null, o.optString("xr").ifEmpty { null },
                o.optString("oid").ifEmpty { null }, o.optString("xoid").ifEmpty { null })
        } }.getOrNull() }
    }.getOrDefault(emptyList())

    private fun save(list: List<T>) = runCatching {
        com.optionslab.app.security.SecurePrefs.put(KEY, JSONArray().apply { list.takeLast(500).forEach { t ->
            put(JSONObject().put("d", t.day).put("m", t.market).put("s", t.symbol).put("c", t.call).put("q", t.qty).put("e", t.entry)
                .put("em", t.entryMinute).put("i", t.index).put("lv", t.level).put("tg", t.target).put("w", t.why).put("x", t.closed)
                .apply { t.exitPrice?.let { put("xp", it) }; t.net?.let { put("n", it) }; t.exit?.let { put("xr", it) }
                    t.orderId?.let { put("oid", it) }; t.exitOrderId?.let { put("xoid", it) } })
        } }.toString())
    }

    private val lock = Mutex()

    /** The markets whose setup was offered as an idea today (one a market a day). */
    private val offered = HashSet<String>()

    /** What Solo was watching at its last pass (for "how is Solo doing"), and when. */
    @Volatile private var watch: Pair<java.time.LocalDateTime, String>? = null

    /** Each market's learned shadow record, worked out once a day from the finished sessions the app holds. */
    private val learned = HashMap<IraMarket, Pair<LocalDate, List<Double>>>()

    private fun learnedFor(m: IraMarket, bars: List<Candle>, today: LocalDate): List<Double> {
        synchronized(learned) { learned[m]?.takeIf { it.first == today }?.second }?.let { return it }
        // Worked out outside the lock ("how is Solo doing" reads the map from the main thread).
        val sessions = bars.filter { it.t.toLocalDate() != today }.groupBy { it.t.toLocalDate() }.toSortedMap().values
            .map { d -> session(d, d.first().t.toLocalDate(), 375) }.filter { it.size > Solo.CUT }
        // Kept for the day only once the full look-back is there (the app's stored days may still be loading).
        return Solo.learn(sessions, RULES).also { if (it.size >= (RULES.recentN ?: 0)) synchronized(learned) { learned[m] = today to it } }
    }

    /** The average day's range (high - low) over the earlier days the app holds (null: none yet). */
    private fun typicalRange(bars: List<Candle>, today: LocalDate): Double? =
        bars.filter { it.t.toLocalDate() != today }.groupBy { it.t.toLocalDate() }.toSortedMap().values.toList().takeLast(20)
            .map { d -> d.maxOf { it.h } - d.minOf { it.l } }.takeIf { it.isNotEmpty() }?.average()

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
        // Up to the last minute the feed has published (a copied minute is never read as a 5-minute close).
        val end = byMin.keys.max() + 1
        val out = ArrayList<Candle>(end)
        var last = first
        for (m in 0 until end) { val b = byMin[m] ?: last; out += b; last = b }
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
        if (!com.optionslab.app.BuildConfig.JARVIS) return@withLock
        if (!com.optionslab.app.data.Market.isOpen()) return@withLock
        val today = com.optionslab.app.data.Market.today()
        val now = minuteNow()
        val list = all()
        // An open trade is always seen through to its exit, even after Solo is switched off.
        list.lastOrNull { !it.closed }?.let { manage(it, list, today, now); return@withLock }
        // Switched off: its setup can still be offered to Boss as a trade idea (he approves each), once a market a day.
        val offering = !on && Automations.on(Automations.Auto.SOLO_IDEAS) && !IraNewsTrades.lossLimitHit()
        if (!on && !offering) return@withLock
        // Paused by its drawdown: neither trades nor ideas until Boss switches it on again.
        if (paused != null) return@withLock
        // The risk book: a professional's day.
        val mine = list.filter { it.day == today.toString() && it.closed }
        val book = mine.fold(Solo.Day()) { b, t -> b.after(t.net ?: 0.0) }
        if (book.canTrade(RULES, DAY_LOSS) != null) return@withLock
        val s = AppSettings.load()
        if (s.guardKill || com.optionslab.app.data.LossBreaker.trippedToday()) return@withLock
        // The trade check must say yes: an error reading it is a no (fail closed).
        val level = runCatching { IraHub.tradeCheckFast().level }.getOrNull() ?: return@withLock
        if (level == com.optionslab.ira.TradeCheck.Level.STOP) return@withLock
        val busy = mine.maxOfOrNull { it.entryMinute } ?: -1
        val seen = ArrayList<String>()
        for (m in MARKETS) {
            val bars = IraHub.freshBars(m)
            val day = session(bars, today, now)
            if (day.size < 20) continue
            val held = IraHub.recentBars(m) + bars
            val big = big(held, today) ?: continue
            // Learning: a market whose setup has not been working lately is watched, not traded.
            if (!Solo.working(learnedFor(m, held, today), RULES)) { seen += "${m.label}: the setup has not been working lately (its last 60 signals lost on average) - standing aside"; watch = java.time.LocalDateTime.now(IST) to seen.joinToString("; "); continue }
            // Waiting candles are said as the watch; with none, why nothing is set up (what became of today's big candles).
            seen += Solo.watching(day, day.size - 1, big, m.label, RULES).ifEmpty { listOfNotNull(Solo.story(day, day.size - 1, big, m.label, RULES)) }
            watch = java.time.LocalDateTime.now(IST) to seen.joinToString("; ")
            // The last two closes only (a pass can come a minute late); an older signal is not chased.
            val sig = (day.size - 1 downTo maxOf(0, day.size - 2)).firstNotNullOfOrNull { k -> Solo.signal(day, k, big, busy, RULES) } ?: continue
            val read = Solo.read(day, typicalRange(held, today), m.label)
            if (offering) {
                val key = "$today|${m.name}"
                if (synchronized(offered) { key in offered }) continue
                // (Approved, it is managed like Jarvis's other trades: their stop, target and profit lock.)
                val text = "Solo's setup on ${m.label}: ${sig.why}. $read"
                if (IraHub.offerSoloIdea(com.optionslab.ira.NewsTrade.Idea(m, sig.call, text, kind = "solo"), text)) synchronized(offered) { offered += key }
                return@withLock
            }
            enter(m, sig, list, today, read)
            return@withLock
        }
    }

    private suspend fun enter(m: IraMarket, sig: Solo.Signal, list: List<T>, today: LocalDate, read: String) {
        val u = m.name
        val c = IraNewsTrades.contract(u, sig.index, sig.call) ?: return
        // Boss's own paper position in this contract is never mixed with Solo's (its stop and close would touch it).
        if (runCatching { Paper.snapshot().positions.positions.any { it.symbol == c.symbol && it.quantity != 0 } }.getOrDefault(true)) return
        val q = runCatching { Paper.quote(c) }.getOrNull() ?: return
        com.optionslab.ira.StrikeLiquidity.problem(q.bid, q.ask, q.volume, c.lotSize)?.let { IraActivity.add("Solo skipped ${c.symbol}: $it"); return }
        val r = Paper.place(c, "BUY", 1, "MARKET", "MIS", null, null, q)
        val fill = r.events.filterIsInstance<com.optionslab.engine.sandbox.SandboxEvent.Fill>().firstOrNull()
        if (fill == null) {
            // Not filled now (a stale quote): the order is cancelled, never left to fill later with no stop and no record.
            r.orderId?.let { runCatching { Paper.cancel(it) } }
            return
        }
        r.orderId?.let { com.optionslab.app.data.Strategies.tagOwner("paper:$it", "Jarvis solo · entry") }
        // Always a stop-loss order on the option (30% below the fill, from Solo's study), besides its index stop.
        // On the 0.05 tick, below the fill (as the order is placed).
        val stopPx = kotlin.math.floor(fill.price * (1 - STOP_LOSS) / 0.05) * 0.05
        val prot = com.optionslab.app.data.Protections.protectPaper(c.symbol, "MIS", fill.quantity, fill.price, stopPx, null, null)
        if (!prot.startsWith("Protected")) IraActivity.add("Solo: the stop-loss on ${c.symbol} was not set ($prot); Solo's own exit still watches it.")
        val t = T(today.toString(), u, c.symbol, sig.call, fill.quantity, fill.price, sig.entryMinute, sig.index, sig.level, sig.target, sig.why,
            orderId = r.orderId)
        save(list + t)
        val line = "Solo (paper): bought ${c.symbol} at ${"%.2f".format(fill.price)}" +
            (com.optionslab.app.data.Origins.shortId(r.orderId)?.let { " (order $it)" } ?: "") + ". Why: ${sig.why}. Out if ${m.label} " +
            "${if (sig.call) "falls to" else "rises to"} ${"%,.0f".format(sig.level)}; target ${"%,.0f".format(sig.target)}; 15:10 at the latest. " +
            "Stop-loss on the option at ${"%.2f".format(kotlin.math.floor(fill.price * (1 - STOP_LOSS) / 0.05) * 0.05)} (30% down)." +
            (if (read.isNotEmpty()) " My read: $read" else "")
        tell(line)
        IraHub.appContext()?.let { com.optionslab.app.work.Notifier.orderFilled(it, "BUY", fill.quantity, c.symbol, fill.price, "Paper", "Jarvis solo · entry", r.orderId) }
    }

    private suspend fun manage(t: T, list: List<T>, today: LocalDate, now: Int) {
        val m = IraMarket.valueOf(t.market)
        val held = runCatching { Paper.snapshot().positions.positions.any { it.symbol == t.symbol && it.quantity != 0 } }.getOrDefault(true)
        if (!held) {
            // Closed outside Solo (its safety stop or the 15:15 square-off): the newest sell today is the exit. When no
            // sell is seen (settled overnight), it is counted at the safety stop - the worst it could have been.
            val sold = if (t.day == today.toString())
                runCatching { Paper.snapshot().trades.firstOrNull { it.symbol == t.symbol && it.action == "SELL" }?.price }.getOrNull() else null
            // Closed by its stop-loss: reviewed like any other exit.
            val byStop = sold != null && sold <= t.entry * (1 - STOP_LOSS) + 0.1
            val lesson = if (!byStop) null else runCatching {
                val day = session(IraHub.freshBars(m), today, now)
                Solo.review(Solo.Signal(t.call, t.entryMinute, t.index, t.level, t.target, 0, t.why), day, day.lastIndex, Solo.Exit.PREMIUM_STOP, t.entry, sold, m.label)
            }.getOrNull()
            finish(t, list, sold ?: t.entry * (1 - STOP_LOSS), if (byStop) "stop-loss on the option" else if (sold != null) "closed by the square-off" else "closed while the app was away (counted at its stop-loss)", lesson)
            return
        }
        val day = session(IraHub.freshBars(m), today, now)
        if (day.size <= t.entryMinute) return
        val s = Solo.Signal(t.call, t.entryMinute, t.index, t.level, t.target, 0, t.why)
        var best = 0.0
        var how: Solo.Exit? = null
        var at = day.lastIndex
        for (j in t.entryMinute until day.size) {
            how = Solo.exit(s, day[j], j, best, RULES)
            best = Solo.favour(s, day[j], best)
            if (how != null) { at = j; break }
        }
        if (how == null && now >= Solo.CUT) how = Solo.Exit.TIME
        how ?: return
        val r = Paper.close(t.symbol, "MIS")
        val px = r.events.filterIsInstance<com.optionslab.engine.sandbox.SandboxEvent.Fill>().firstOrNull()?.price
        // Solo's exit is labelled as its own (who placed it, which order) and notified like any fill.
        if (px != null) r.orderId?.let { oid ->
            com.optionslab.app.data.Strategies.tagOwner("paper:$oid", "Jarvis solo · exit")
            IraHub.appContext()?.let { com.optionslab.app.work.Notifier.orderFilled(it, "SELL", t.qty, t.symbol, px, "Paper", "Jarvis solo · exit", oid) }
        }
        if (px == null) {
            // Not filled (no fresh price): the exit order is cancelled and tried again next pass; the safety stop stays.
            r.orderId?.let { runCatching { Paper.cancel(it) } }
            return
        }
        runCatching { com.optionslab.app.data.Protections.removeSymbol(false, "NFO", t.symbol) }
        val lesson = runCatching { Solo.review(s, day, at, how, t.entry, px, m.label) }.getOrNull()
        finish(t, list, px, lesson = lesson, exitOrderId = r.orderId, why = when (how) {
            Solo.Exit.STOP -> "stop: ${m.label} through ${"%,.0f".format(t.level)}"
            Solo.Exit.TARGET -> "target reached"
            Solo.Exit.SLOW -> "no follow-through"
            Solo.Exit.LOCK -> "profit lock"
            Solo.Exit.PREMIUM_STOP -> "stop-loss on the option"
            Solo.Exit.TIME -> "15:10"
        })
    }

    private fun finish(t: T, list: List<T>, px: Double?, why: String, lesson: String? = null, exitOrderId: String? = null) {
        val net = px?.let { (it - t.entry) * t.qty - COSTS }
        val done = t.copy(closed = true, exitPrice = px, net = net, exit = why, exitOrderId = exitOrderId)
        val all = list.map { if (it === t) done else it }
        save(all)
        tell("Solo (paper): closed ${t.symbol}" + (px?.let { " at ${"%.2f".format(it)}" } ?: "") +
            (com.optionslab.app.data.Origins.shortId(exitOrderId)?.let { " (order $it)" } ?: "") + " - $why. " +
            (net?.let { "Result ${com.optionslab.ira.AppFacts.rs(it)}." } ?: "") + (lesson?.let { " Review: $it" } ?: "") + " " + record(all))
        // Discipline: a drawdown this deep from Solo's best means the setup is not working now - it stops and says so.
        var peak = 0.0; var eq = 0.0
        val from = runCatching { com.optionslab.app.security.SecurePrefs.getInt(KEY_FROM, 0) }.getOrDefault(0).coerceIn(0, all.size)
        for (x in all.drop(from).filter { it.closed }) { eq += x.net ?: 0.0; peak = maxOf(peak, eq) }
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

    /** How the setup has been doing lately in each market (as learned by today's passes; empty before the first). */
    private fun form(): String = runCatching {
        val today = com.optionslab.app.data.Market.today()
        // Only what today's passes already learned (never worked out here: this may run on the main thread).
        MARKETS.mapNotNull { m -> synchronized(learned) { learned[m]?.takeIf { it.first == today }?.second }?.let { Solo.form(it, RULES, m.label) } }.takeIf { it.isNotEmpty() }?.joinToString("; ", prefix = " Lately: ", postfix = ".")
    }.getOrNull() ?: ""

    /** Solo's day for the 15:35 wrap-up (null when it was off and did nothing). */
    fun daySummary(): String? = runCatching {
        val today = com.optionslab.app.data.Market.today().toString()
        // With no trade, why: the last pass's watch today ("At 15:28 Solo saw: ...").
        val why = watch?.takeIf { it.first.toLocalDate().toString() == today && it.second.isNotEmpty() }?.let { (at, w) -> "At %02d:%02d Solo saw: %s".format(at.hour, at.minute, w) }
        Solo.daySay(all().filter { it.day == today && it.closed }.map { it.net to it.exit }, on, on && paused != null, why)
    }.getOrNull()

    /** "How is Solo doing": on or off, paused or not, and the record. */
    fun status(): String = (if (on) "Solo is on, Boss (paper only; switch it off in Jarvis settings)." else "Solo is off, Boss: switch it on in Jarvis settings (paper only).") +
        (paused?.let { " $it" } ?: "") + " " + record() + form() + (watch?.takeIf { on && paused == null && com.optionslab.app.data.Market.isOpen() && it.first.toLocalDate() == com.optionslab.app.data.Market.today() &&
            all().none { t -> !t.closed } }?.let { (at, w) ->
            if (w.isEmpty()) " At %02d:%02d nothing was set up yet.".format(at.hour, at.minute) else " At %02d:%02d Solo saw: ".format(at.hour, at.minute) + w + "."
        } ?: "")

    /** Solo's paper record in one line. */
    fun record(list: List<T> = all()): String {
        val c = list.filter { it.closed && it.net != null }
        if (c.isEmpty()) return "Solo has no closed trades yet."
        val net = c.sumOf { it.net!! }
        return "Solo so far: ${c.size} trades, ${c.count { it.net!! > 0 }} won, net ${com.optionslab.ira.AppFacts.rs(net)} on paper."
    }
}
