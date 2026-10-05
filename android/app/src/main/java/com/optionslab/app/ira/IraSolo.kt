package com.optionslab.app.ira

import com.optionslab.app.data.AppSettings
import com.optionslab.app.data.Paper
import com.optionslab.ira.Candle
import com.optionslab.ira.SelfCalibration
import com.optionslab.ira.Solo
import com.optionslab.ira.SoloCalibration
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
    /** The trades Solo sat out by its own record, followed on paper as if taken (no order) - see [SoloCalibration]. */
    private const val KEY_SHADOWS = "jarvis.solo.shadows"
    private val IST = ZoneId.of("Asia/Kolkata")
    private val MARKETS = listOf(IraMarket.NIFTY, IraMarket.BANKNIFTY)

    /**
     * Paper only, and no cap on how many trades a day (Boss, 3 Oct: "no limit, but only paper orders, even on a live
     * market" - Solo decides for itself): every setup it finds is taken, one at a time, while the setup's last 60
     * signals (on the index, before costs) averaged above zero. (The 2-year test in SOLO.md was run at one a day.)
     */
    val RULES = Solo.Rules(maxPerDay = Int.MAX_VALUE, lossesToStop = Int.MAX_VALUE, recentN = 60, profitLock = false, premiumStop = STOP_LOSS)
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
        "Its learning brain replayed minute by minute over two years of real prices (learning only from what had already happened):",
        "NIFTY Apr 2024-Apr 2025: 987 trades, 40% winners, -Rs 1,31,183 (1 lot of 75).",
        "NIFTY Apr 2025-Apr 2026: 963 trades, 42% winners, -Rs 1,15,255.",
        "BANKNIFTY Feb 2025-Feb 2026: 744 trades, 45% winners, -Rs 1,00,924 (1 lot of 30).",
        "It has not found an edge after costs yet - which is why it is on paper only. It keeps learning every minute.",
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
                 val orderId: String? = null, val exitOrderId: String? = null,
                 /** The conditions it came in, kept so Solo can judge itself by them ([SoloCalibration]); null: not known then. */
                 val regime: String? = null, val ivRank: Double? = null)

    fun all(): List<T> = runCatching {
        val a = JSONArray(com.optionslab.app.security.SecurePrefs.getString(KEY) ?: "[]")
        (0 until a.length()).mapNotNull { i -> runCatching { a.getJSONObject(i).let { o ->
            T(o.getString("d"), o.getString("m"), o.getString("s"), o.getBoolean("c"), o.getInt("q"), o.getDouble("e"), o.getInt("em"),
                o.getDouble("i"), o.getDouble("lv"), o.getDouble("tg"), o.getString("w"), o.optBoolean("x"),
                if (o.has("xp")) o.getDouble("xp") else null, if (o.has("n")) o.getDouble("n") else null, o.optString("xr").ifEmpty { null },
                o.optString("oid").ifEmpty { null }, o.optString("xoid").ifEmpty { null },
                o.optString("rg").ifEmpty { null }, if (o.has("iv")) o.getDouble("iv") else null)
        } }.getOrNull() }
    }.getOrDefault(emptyList())

    private fun save(list: List<T>) = runCatching {
        com.optionslab.app.security.SecurePrefs.put(KEY, JSONArray().apply { list.takeLast(500).forEach { t ->
            put(JSONObject().put("d", t.day).put("m", t.market).put("s", t.symbol).put("c", t.call).put("q", t.qty).put("e", t.entry)
                .put("em", t.entryMinute).put("i", t.index).put("lv", t.level).put("tg", t.target).put("w", t.why).put("x", t.closed)
                .apply { t.exitPrice?.let { put("xp", it) }; t.net?.let { put("n", it) }; t.exit?.let { put("xr", it) }
                    t.orderId?.let { put("oid", it) }; t.exitOrderId?.let { put("xoid", it) }
                    t.regime?.let { put("rg", it) }; t.ivRank?.takeIf { it.isFinite() }?.let { put("iv", it) } })
        } }.toString())
    }

    // ---- self-calibration (Solo judged by its own record; it only ever makes Solo more careful on paper) --------------

    /** The conditions a Solo trade came in (null: its day or market could not be read). */
    private fun conditionsOf(t: T): SelfCalibration.Conditions? = runCatching {
        SoloCalibration.conditions(LocalDate.parse(t.day), t.entryMinute, IraMarket.valueOf(t.market), t.call,
            t.regime?.let { com.optionslab.ira.Regime.Kind.valueOf(it) }, t.ivRank)
    }.getOrNull()

    private fun shadows(): List<SoloCalibration.Shadow> = runCatching {
        val a = JSONArray(com.optionslab.app.security.SecurePrefs.getString(KEY_SHADOWS) ?: "[]")
        (0 until a.length()).mapNotNull { i -> runCatching { a.getJSONObject(i).let { o ->
            SoloCalibration.Shadow(SelfCalibration.Conditions(java.time.LocalDateTime.parse(o.getString("at")), IraMarket.valueOf(o.getString("m")),
                o.getBoolean("c"), SoloCalibration.KIND, o.optString("rg").ifEmpty { null }?.let { com.optionslab.ira.Regime.Kind.valueOf(it) },
                if (o.has("iv")) o.getDouble("iv") else null),
                o.getString("s"), o.getInt("q"), o.getDouble("e"), o.getInt("du"), if (o.has("x")) o.getDouble("x") else null,
                if (o.has("i")) o.getDouble("i") else null)
        } }.getOrNull() }
    }.getOrDefault(emptyList())

    private fun saveShadows(list: List<SoloCalibration.Shadow>) = runCatching {
        com.optionslab.app.security.SecurePrefs.put(KEY_SHADOWS, JSONArray().apply { list.forEach { x ->
            put(JSONObject().put("at", x.c.at.toString()).put("m", x.c.market.name).put("c", x.c.call).put("s", x.symbol).put("q", x.qty)
                .put("e", x.entry).put("du", x.due)
                .apply { x.c.regime?.let { put("rg", it.name) }; x.c.ivRank?.takeIf { it.isFinite() }?.let { put("iv", it) }
                    x.exit?.let { put("x", it) }; x.spot?.let { put("i", it) } })
        } }.toString())
    }

    /** Solo's own record to judge itself by: its closed paper trades and the sat-out trades scored as if taken. */
    fun calibration(list: List<T> = all()): List<SelfCalibration.Outcome> = SoloCalibration.outcomes(
        list.filter { it.closed }.mapNotNull { t -> conditionsOf(t)?.let { SoloCalibration.outcome(it, t.net, t.qty) } }, shadows())

    /** Scores the sat-out trades whose time is up (the option's bid then); an earlier day's unscored one is dropped. */
    private suspend fun settleShadows(today: LocalDate, now: Int) {
        val all = shadows()
        val due = SoloCalibration.dueNow(all, today, now)
        val kept = SoloCalibration.prune(all, today)
        if (due.isEmpty()) { if (kept.size != all.size) saveShadows(kept); return }
        val done = HashMap<SoloCalibration.Shadow, Double>()
        for (x in due) {
            val c = Paper.contractOf(x.symbol)
                ?: x.spot?.let { IraNewsTrades.contract(x.c.market.name, it, x.c.call) }?.takeIf { it.symbol == x.symbol } ?: continue
            val q = runCatching { Paper.quote(c) }.getOrNull() ?: continue
            SoloCalibration.sellPrice(q.bid, q.ltp)?.let { done[x] = it }
        }
        // Written only when something changed (a price not read leaves it for the next pass, without a vault write).
        val next = kept.map { x -> done[x]?.let { x.copy(exit = it) } ?: x }
        if (next != all) saveShadows(next)
    }

    /** A trade Solo sits out by its own record: followed on paper as if taken (no order), one at a time a market. */
    private suspend fun shadow(m: IraMarket, sig: Solo.Signal, cond: SelfCalibration.Conditions, horizon: Int, why: String?) {
        val all = shadows()
        if (!SoloCalibration.mayShadow(all, m)) return
        val c = IraNewsTrades.contract(m.name, sig.index, sig.call) ?: return
        val q = runCatching { Paper.quote(c) }.getOrNull() ?: return
        val x = SoloCalibration.shadow(cond, c.symbol, c.lotSize, q.ask, q.ltp, sig.entryMinute, horizon, sig.index) ?: return
        saveShadows(all + x)
        IraActivity.add("Solo sat out a ${m.label} ${if (sig.call) "call" else "put"}" + (why?.let { ": $it." } ?: ": my record could not be read."))
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

    // ---- the learning brain (Boss, 3 Oct: Solo learns from the market itself, no strategy made in advance) -------------

    /** Why a trade came from the learning brain (its exit is the brain's: [Learner.Cfg.horizon] minutes at most). */
    private const val LEARNED = "Learned:"

    /** One view: a learner looking [h] minutes ahead, with its guesses waiting for their answers. */
    private class Mind(val h: Int, val l: com.optionslab.ira.Learner) {
        val pending = ArrayDeque<Triple<Int, DoubleArray, Double>>()
        var lastP: Double? = null
    }

    /** A market's brain: its views side by side (Boss: "tune itself" - it follows whichever has the best record). */
    private class Brain(val minds: List<Mind>) {
        var day: LocalDate? = null
        var done = 0
        var prevClose: Double? = null
        var lastPrice = 0.0
        /**
         * The view with the best record so far: one ready to trade always before one still learning (as the trading
         * choice, [Learner.pick], only takes ready ones); the 15-minute one until any has been scored.
         */
        val best: Mind get() = minds.maxByOrNull { (if (it.l.ready) 2.0 else if (it.l.scored > 0) 0.0 else -1.0) + it.l.hitRate } ?: minds.first()
    }
    private val brains = HashMap<IraMarket, Brain>()
    /** Minutes behind the newest that the brain reads (the feed fills a late minute from the one before). */
    private const val SETTLE = 2
    private fun brainKey(m: IraMarket) = "solo.brain.${m.name}"

    /** Reads minutes [Brain.done]+1..[upTo] of [day]: learns each answer as it comes due, then guesses again. */
    private fun feed(b: Brain, day: List<Candle>, upTo: Int, only: List<Mind> = b.minds) {
        for (m in maxOf(1, b.done + 1)..upTo) for (mind in only) {
            val l = mind.l
            l.tick(day[m - 1], day[m])
            while (mind.pending.isNotEmpty() && mind.pending.first().first + mind.h <= m) {
                val (m0, x, g) = mind.pending.removeFirst()
                l.learn(x, day[m0 + mind.h].c > day[m0].c, g)
            }
            val x = l.features(day, m, b.prevClose) ?: continue
            val g = l.p(x)
            mind.pending.addLast(Triple(m, x, g)); mind.lastP = g
        }
        b.done = maxOf(b.done, upTo)
        b.lastPrice = day[upTo].c
    }

    /** Where each view is kept (the 15-minute one under the key it always had). */
    private fun mindKey(m: IraMarket, h: Int) = if (h == 15) brainKey(m) else brainKey(m) + ".h$h"

    /** The market's brain: kept on the phone; the first time, it learns from the earlier days the phone holds. */
    private fun brain(m: IraMarket, held: List<Candle>, today: LocalDate): Brain = brains.getOrPut(m) {
        val b = Brain(com.optionslab.ira.Learner.HORIZONS.map { h -> Mind(h, com.optionslab.ira.Learner(com.optionslab.ira.Learner.Cfg(horizon = h))) })
        val fresh = b.minds.filter { mind ->
            val kept = runCatching { com.optionslab.app.security.SecurePrefs.getString(mindKey(m, mind.h)) }.getOrNull()
            kept == null || !mind.l.load(kept)
        }
        val earlier = held.filter { it.t.toLocalDate().isBefore(today) }.groupBy { it.t.toLocalDate() }.toSortedMap().values
            .map { d -> session(d, d.first().t.toLocalDate(), 375) }.filter { it.size > com.optionslab.ira.Learner.FIRST }
        // A view with nothing kept learns first from the earlier days the phone holds (the others carry on as saved).
        if (fresh.isNotEmpty()) for (d in earlier) {
            b.done = 0; fresh.forEach { it.pending.clear() }; feed(b, d, d.lastIndex, fresh); b.prevClose = d.last().c
        }
        b.done = 0
        if (fresh.size < b.minds.size) runCatching {
            // Saved part-way through today (the app restarted): today's minutes already learned are not learned twice.
            val (d, n) = com.optionslab.app.security.SecurePrefs.getString(brainKey(m) + ".at")!!.split("|")
            if (d == today.toString()) { b.day = today; b.done = n.toInt() }
        }
        b.prevClose = earlier.lastOrNull()?.last()?.c
        b
    }

    /** Every pass in market hours: each market's brain reads the new minutes and learns (whether Solo trades or not). */
    private suspend fun learnTick(today: LocalDate, now: Int) {
        // One save for every market's minds and marks (each save re-encrypts and rewrites the whole vault, and this runs
        // every minute of the session): the same values, kept as before.
        val save = HashMap<String, Any?>()
        for (m in MARKETS) {
            val bars = runCatching { IraHub.freshBars(m) }.getOrNull() ?: continue
            val day = session(bars, today, now)
            if (day.size < 2) continue
            runCatching {
                synchronized(brains) {
                    val b = brains[m] ?: brain(m, IraHub.recentBars(m) + bars, today)
                    if (b.day != today) {
                        b.day = today; b.done = 0; b.minds.forEach { it.pending.clear(); it.lastP = null }
                        // Yesterday's close (the app may have stayed open overnight).
                        b.prevClose = IraHub.recentBars(m).lastOrNull { it.t.toLocalDate().isBefore(today) }?.c ?: b.prevClose
                    }
                    // Two minutes behind the newest: a minute that arrives late is otherwise learned as a copy of the one before.
                    val upTo = day.lastIndex - SETTLE
                    if (upTo > b.done) {
                        feed(b, day, upTo)
                        // The market's minds and its mark, saved together below.
                        save.putAll(b.minds.associate { mind -> mindKey(m, mind.h) to mind.l.save() } + (brainKey(m) + ".at" to "$today|${b.done}"))
                    }
                }
            }
        }
        if (save.isNotEmpty()) runCatching { com.optionslab.app.security.SecurePrefs.putAll(save) }
    }

    /** A learned trade's horizon, from its reason ("... over the next 30 minutes"); 15 when not said. */
    private fun learnedHorizon(why: String): Int = Regex("over the next (\\d+) minutes").find(why)?.groupValues?.get(1)?.toIntOrNull() ?: 15

    /** "How is Solo's learning going", per market. */
    fun learning(): String = synchronized(brains) { MARKETS.mapNotNull { m -> brains[m]?.let { b -> runCatching {
            val best = b.best
            val views = b.minds.joinToString(", ") { v -> "${v.h} min " + if (v.l.scored == 0) "learning" else "%.0f%%".format(java.util.Locale.ENGLISH, v.l.hitRate * 100) }
            best.l.say(m.label) + " (following its ${best.h}-minute view; views: $views)" +
                (best.l.explain(m.label)?.let { ". $it" } ?: "") + (best.l.calibration(m.label)?.let { ". $it" } ?: "") }.getOrNull() } } }
        .ifEmpty { listOf("it starts learning at the next market session") }.joinToString("; ")

    /** Every market-watch pass while Solo is on: manage the open trade, or look for the next one. */
    suspend fun tick() = lock.withLock {
        if (!com.optionslab.app.BuildConfig.JARVIS || com.optionslab.app.BuildConfig.GOLD) return@withLock
        if (!com.optionslab.app.data.Market.isOpen()) return@withLock
        val today = com.optionslab.app.data.Market.today()
        val now = minuteNow()
        // The brains learn every pass, Solo on or off.
        learnTick(today, now)
        // The trades Solo sat out are scored when their time is up (paper only, no order).
        runCatching { settleShadows(today, now) }
        val list = all()
        // An open trade is always seen through to its exit, even after Solo is switched off.
        list.lastOrNull { !it.closed }?.let { manage(it, list, today, now); return@withLock }
        // Switched off: its setup can still be offered to Boss as a trade idea (he approves each), once a market a day.
        // In Live, once Solo's own paper record is proven, its setups are always asked (Boss, 4 Oct: real orders only
        // after his yes with the fingerprint); until then it trades on paper as before.
        // (Not when the day's loss limit for Jarvis's trades is hit: then Solo trades on paper only.)
        val soloLive = on && runCatching { AppSettings.load().live }.getOrDefault(false) && provenWhy() == null && !IraNewsTrades.lossLimitHit()
        val offering = soloLive || !on && Automations.on(Automations.Auto.SOLO_IDEAS) && !IraNewsTrades.lossLimitHit()
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
        val seen = ArrayList<String>()
        for (m in MARKETS) {
            val b = synchronized(brains) { brains[m] } ?: continue
            if (b.day != today || b.done < com.optionslab.ira.Learner.FIRST) continue
            seen += b.best.l.say(m.label)
            watch = java.time.LocalDateTime.now(IST) to seen.joinToString("; ")
            // No setup made in advance: of its views that would trade now, the one with the best record (it tunes itself).
            val pick = synchronized(brains) { com.optionslab.ira.Learner.pick(b.minds.map { v -> v.l.hitRate to v.lastP?.let { v.l.decide(it) } }) } ?: continue
            val mind = b.minds[pick]
            val p = mind.lastP ?: continue
            val call = mind.l.decide(p) ?: continue
            if (b.done + 1 >= Solo.LAST_ENTRY) continue
            val ix = b.lastPrice
            val sig = Solo.Signal(call, b.done + 1, ix, if (call) ix * 0.95 else ix * 1.05, if (call) ix * 1.05 else ix * 0.95, 0,
                "$LEARNED it reads a %.0f%% chance ${m.label} goes ${if (call) "up" else "down"} over the next %d minutes; %s"
                    .format(java.util.Locale.ENGLISH, (if (call) p else 1 - p) * 100, mind.h, mind.l.say(m.label)))
            val day = session(IraHub.freshBars(m), today, now)
            val read = Solo.read(day, typicalRange(IraHub.recentBars(m), today), m.label)
            if (offering) {
                val key = "$today|${m.name}"
                if (synchronized(offered) { key in offered }) continue
                val text = "Solo's read on ${m.label}: ${sig.why}. $read"
                if (IraHub.offerSoloIdea(com.optionslab.ira.NewsTrade.Idea(m, sig.call, text, kind = "solo"), text, solo = true)) synchronized(offered) { offered += key }
                return@withLock
            }
            // Its own record, by the conditions this trade comes in: where it is clearly bad Solo sits out (and follows
            // the trade on paper as if taken, so the condition can earn its way back); where it is losing, one a day at most.
            // It only ever stops or limits Solo's own paper trades. (Read failing: sat out - the careful side.)
            val regimeNow = runCatching { IraStudy.regimeOf(m) }.getOrNull()
            val ivNow = runCatching { IraNewsTrades.ivNow(com.optionslab.ira.NewsTrade.Idea(m, call, sig.why, kind = "solo"), ix)?.first }.getOrNull()
            val cond = SoloCalibration.conditions(today, sig.entryMinute, m, call, regimeNow, ivNow)
            val decision = runCatching { SoloCalibration.decide(calibration(list), cond, list.filter { it.day == today.toString() }.mapNotNull { conditionsOf(it) }) }.getOrNull()
            // The reason, as decided now (a failed read is written as the careful side it took).
            runCatching { IraThinking.add(com.optionslab.ira.Thinking.solo(IraThinking.now(), cond,
                decision ?: SoloCalibration.Decision(false, SelfCalibration.Action.SIT_OUT, null))) }
            if (decision == null || !decision.take) {
                runCatching { shadow(m, sig, cond, mind.h, decision?.text()) }
                continue
            }
            enter(m, sig, list, today, read, cond, decision.text())
            return@withLock
        }
    }

    private suspend fun enter(m: IraMarket, sig: Solo.Signal, list: List<T>, today: LocalDate, read: String,
                              cond: SelfCalibration.Conditions? = null, careful: String? = null) {
        val u = m.name
        val c = IraNewsTrades.contract(u, sig.index, sig.call) ?: return
        // Boss's own paper position in this contract is never mixed with Solo's (its stop and close would touch it).
        if (runCatching { Paper.snapshot().positions.positions.any { it.symbol == c.symbol && it.quantity != 0 } }.getOrDefault(true)) return
        val q = runCatching { Paper.quote(c) }.getOrNull() ?: return
        com.optionslab.ira.StrikeLiquidity.problem(q.bid, q.ask, q.volume, c.lotSize)?.let {
            IraActivity.add("Solo skipped ${c.symbol}: $it")
            runCatching { IraThinking.add(com.optionslab.ira.Thinking.soloThin(IraThinking.now(), m, sig.call, it)) }
            return
        }
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
            orderId = r.orderId, regime = cond?.regime?.name, ivRank = cond?.ivRank)
        save(list + t)
        val line = "Solo (paper): bought ${c.symbol} at ${"%.2f".format(fill.price)}" +
            (com.optionslab.app.data.Origins.shortId(r.orderId)?.let { " (order $it)" } ?: "") + ". Why: ${sig.why}. Out if ${m.label} " +
            "${if (sig.call) "falls to" else "rises to"} ${"%,.0f".format(sig.level)}; target ${"%,.0f".format(sig.target)}; 15:10 at the latest. " +
            "Stop-loss on the option at ${"%.2f".format(kotlin.math.floor(fill.price * (1 - STOP_LOSS) / 0.05) * 0.05)} (30% down)." +
            (if (read.isNotEmpty()) " My read: $read" else "") + (careful?.let { " Careful: $it." } ?: "")
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
            // The brain's trades are for its horizon: out when it has passed (the stop-loss on the option still guards it).
            if (t.why.startsWith(LEARNED) && j >= t.entryMinute + learnedHorizon(t.why)) { how = Solo.Exit.TIME; at = j; break }
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
        val learnedTrade = t.why.startsWith(LEARNED)
        val lesson = if (learnedTrade) null else runCatching { Solo.review(s, day, at, how, t.entry, px, m.label) }.getOrNull()
        finish(t, list, px, lesson = lesson, exitOrderId = r.orderId, why = if (learnedTrade && how == Solo.Exit.TIME) "its ${learnedHorizon(t.why)} minutes were up" else when (how) {
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
        // A locked phone may be overheard: Solo's symbol and result stay in the chat.
        runCatching { JarvisVoice.announce(com.optionslab.ira.Overheard.said(line, IraHub.locked(), "Boss, Solo has news on its paper trade: it's in the chat.")) }
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
    fun status(): String = (if (on) "Solo is on, Boss (on paper until its record is proven; then in Live it asks you each time; switch it off in Jarvis settings)." else "Solo is off, Boss: switch it on in Jarvis settings (paper until proven).") +
        (paused?.let { " $it" } ?: "") + " " + record() + " Learning: ${learning()}." + (watch?.takeIf { on && paused == null && com.optionslab.app.data.Market.isOpen() && it.first.toLocalDate() == com.optionslab.app.data.Market.today() &&
            all().none { t -> !t.closed } }?.let { (at, w) ->
            if (w.isEmpty()) " At %02d:%02d nothing was set up yet.".format(at.hour, at.minute) else " At %02d:%02d Solo saw: ".format(at.hour, at.minute) + w + "."
        } ?: "")

    /** Solo's closed record as [provenWhy] judges it: its own paper trades and its setups taken through Boss's approval. */
    fun closedRecord(): List<com.optionslab.ira.JarvisTrades.Closed> = all().filter { it.closed && it.net != null }
        .map { com.optionslab.ira.JarvisTrades.Closed(LocalDate.parse(it.day), it.net!!, false) } +
        // Its setups taken through Boss's approval (on Zerodha too) count against it: losing real money takes it back.
        runCatching { IraNewsTrades.all().filter { it.closed && it.result != null && it.headline.startsWith("pattern: solo") }
            .map { com.optionslab.ira.JarvisTrades.Closed(LocalDate.parse(it.day), it.result!!, false) } }.getOrDefault(emptyList())

    /** Null when Solo's own paper record has earned real orders (the same bar as Jarvis's trades), else why not. */
    fun provenWhy(): String? = com.optionslab.ira.JarvisTrades.proven(closedRecord())
        ?.replace("My trades", "Solo's trades")?.replace("My paper trades", "Solo's paper trades")

    /** Solo's paper record in one line. */
    fun record(list: List<T> = all()): String {
        val c = list.filter { it.closed && it.net != null }
        if (c.isEmpty()) return "Solo has no closed trades yet."
        val net = c.sumOf { it.net!! }
        return "Solo so far: ${c.size} trades, ${c.count { it.net!! > 0 }} won, net ${com.optionslab.ira.AppFacts.rs(net)} on paper."
    }
}
