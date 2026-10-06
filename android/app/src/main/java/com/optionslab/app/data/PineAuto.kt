package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.Vault
import com.optionslab.app.work.Notifier
import com.optionslab.engine.Right
import com.optionslab.engine.orb.OrbRules
import com.optionslab.engine.orb.ProfitLock
import com.optionslab.engine.pine.Pine
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Pine scripts that trade by themselves. On every completed candle of the chosen symbol and
 * interval the script runs; when its signal changes, the app buys the ATM option of the
 * nearest expiry after today (a CALL to go long, a PUT to go short, or just exits), as a
 * MARKET MIS order for the chosen lots, and sells the one it held.
 *
 * It follows the app's Paper / Live switch, decided at each order. Live needs the PIN (or
 * fingerprint) once when the script is switched on; every live order still passes the
 * account guard, the order limits, the kill switch and the static-IP check. Nothing is
 * decided outside market hours; at 15:15 what is held is sold; the day's stop (the
 * kill switch) sells and stands still. A newly switched-on script waits for the next
 * change of signal: it never jumps into a trade already under way.
 */
object PineAuto {
    private lateinit var app: Context
    private lateinit var file: File
    private val lock = Mutex()

    /**
     * [since] > 0: a live buy Zerodha has not confirmed (since then, epoch ms), its order [order] (null when even the
     * id was lost). [qty] is then at most what may have filled; it is settled from the order book on the next passes.
     * [peak]: the option's best price seen since it was bought (the profit lock's high-water mark; the buy price at first).
     * Every holding is a bought option (a CALL or a PUT), never a sold one, so the lock is only ever a long's.
     */
    data class Held(val symbol: String, val right: String, val qty: Int, val lotSize: Int, val entry: Double, val day: String,
                    val live: Boolean, val kite: String?, val order: String? = null, val since: Long = 0L, val peak: Double = entry) {
        val unconfirmed: Boolean get() = since > 0
    }
    data class Line(val at: Long, val script: Long, val text: String)

    private class Book(
        val held: HashMap<Long, Held> = HashMap(),
        val lastBar: HashMap<Long, Long> = HashMap(),
        val lastTarget: HashMap<Long, Int> = HashMap(),
        val liveOk: HashMap<Long, Boolean> = HashMap(),
        val log: ArrayList<Line> = ArrayList(),
        /** Script -> "day|rupees": what it made or lost on closed trades today. */
        val dayPnl: HashMap<Long, String> = HashMap(),
        /** Script -> the day it hit its own daily loss limit (no new trades until tomorrow). */
        val paused: HashMap<Long, String> = HashMap(),
    )

    private val _held = MutableStateFlow<Map<Long, Held>>(emptyMap())
    val held: StateFlow<Map<Long, Held>> = _held
    private val _log = MutableStateFlow<List<Line>>(emptyList())
    val log: StateFlow<List<Line>> = _log
    private var cache: Book? = null

    fun init(context: Context) {
        app = context.applicationContext
        file = File(app.noBackupFilesDir, "pine_auto.vault")
    }

    private fun book(): Book {
        cache?.let { return it }
        val b = runCatching {
            val o = JSONObject(String(Vault.readFileSteady(file) ?: return@runCatching null, Charsets.UTF_8))
            val bk = Book()
            o.optJSONObject("held")?.let { m -> m.keys().forEach { k -> val h = m.getJSONObject(k)
                bk.held[k.toLong()] = Held(h.getString("symbol"), h.getString("right"), h.getInt("qty"), h.optInt("lot", 1), h.getDouble("entry"),
                    h.getString("day"), h.optBoolean("live"), h.optString("kite").ifBlank { null }, h.optString("order").ifBlank { null },
                    h.optLong("since", 0L),
                    // Saved before the profit lock: the buy price, so no rung counts as reached that was never seen.
                    h.optDouble("peak", Double.NaN).takeIf { it.isFinite() && it > 0 } ?: h.getDouble("entry")) } }
            o.optJSONObject("lastBar")?.let { m -> m.keys().forEach { k -> bk.lastBar[k.toLong()] = m.getLong(k) } }
            o.optJSONObject("lastTarget")?.let { m -> m.keys().forEach { k -> bk.lastTarget[k.toLong()] = m.getInt(k) } }
            o.optJSONObject("liveOk")?.let { m -> m.keys().forEach { k -> bk.liveOk[k.toLong()] = m.getBoolean(k) } }
            o.optJSONArray("log")?.let { a -> for (i in 0 until a.length()) { val l = a.getJSONArray(i); bk.log += Line(l.getLong(0), l.getLong(1), l.getString(2)) } }
            o.optJSONObject("dayPnl")?.let { m -> m.keys().forEach { k -> bk.dayPnl[k.toLong()] = m.getString(k) } }
            o.optJSONObject("paused")?.let { m -> m.keys().forEach { k -> bk.paused[k.toLong()] = m.getString(k) } }
            // A restore not yet disarmed (the app clears the flag once it has): no script may trade live on restored approvals.
            if (com.optionslab.app.security.SecurePrefs.getBoolean(Backup.DISARM, false)) bk.liveOk.clear()
            bk
        }.getOrNull()
        if (b == null && file.exists()) Vault.setAside(file)
        return (b ?: Book()).also { cache = it; publish(it) }
    }

    private fun save(b: Book) {
        while (b.log.size > 300) b.log.removeAt(0)
        val o = JSONObject()
        o.put("held", JSONObject().apply { b.held.forEach { (k, h) -> put(k.toString(), JSONObject().put("symbol", h.symbol).put("right", h.right)
            .put("qty", h.qty).put("lot", h.lotSize).put("entry", h.entry).put("day", h.day).put("live", h.live).put("kite", h.kite ?: "")
            .put("order", h.order ?: "").put("since", h.since).put("peak", h.peak)) } })
        o.put("lastBar", JSONObject().apply { b.lastBar.forEach { (k, v) -> put(k.toString(), v) } })
        o.put("lastTarget", JSONObject().apply { b.lastTarget.forEach { (k, v) -> put(k.toString(), v) } })
        o.put("liveOk", JSONObject().apply { b.liveOk.forEach { (k, v) -> put(k.toString(), v) } })
        o.put("log", JSONArray().apply { b.log.forEach { put(JSONArray().put(it.at).put(it.script).put(it.text)) } })
        o.put("dayPnl", JSONObject().apply { b.dayPnl.forEach { (k, v) -> put(k.toString(), v) } })
        o.put("paused", JSONObject().apply { b.paused.forEach { (k, v) -> put(k.toString(), v) } })
        Vault.writeFile(file, o.toString().toByteArray(Charsets.UTF_8))
        cache = b
        publish(b)
    }

    private fun publish(b: Book) { _held.value = HashMap(b.held); _log.value = b.log.toList() }

    private fun note(b: Book, id: Long, text: String) { b.log += Line(System.currentTimeMillis(), id, text) }

    private fun label(item: PineScripts.Item) = "Pine · ${item.name}"

    /**
     * TEST ONLY: a fixed clock and a candle source for the auto-trader's own checks (market hours, 15:15, the day,
     * the stale-candle guard). Both are null in the app, always: the time is then [Market.now] (market hours as
     * [Market.isOpen]) and candles come from [ChartFeed.bars], exactly as before. Their setters throw unless
     * BuildConfig.DEBUG (as Broker.testEndpoint), and no app code sets them; only the unit tests do.
     */
    @Volatile internal var testNow: java.time.ZonedDateTime? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test clock exists only in debug builds" }; field = v }
    @Volatile internal var testBars: ((symbol: String, interval: String) -> List<com.optionslab.engine.Upstox.Bar>)? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test candles exist only in debug builds" }; field = v }
    private fun clock(): java.time.ZonedDateTime = testNow ?: Market.now()
    private fun todayIst(): java.time.LocalDate = testNow?.toLocalDate() ?: Market.today()
    private fun isOpen(): Boolean = testNow?.let { Market.isTradingDay(it.toLocalDate()) && (it.hour * 60 + it.minute) in Market.OPEN until Market.CLOSE }
        ?: Market.isOpen()
    private fun epochSecondNow(): Long = testNow?.toEpochSecond() ?: (System.currentTimeMillis() / 1000)

    @Synchronized fun wipe() { cache = null; if (::file.isInitialized) file.delete(); _held.value = emptyMap(); _log.value = emptyList() }

    /**
     * Reset paper: paper holdings and their day's P&L / pause go; scripts holding at Zerodha keep theirs. A flat script's
     * tally and daily-loss pause are not marked paper or live, so they are cleared only while the app is in Paper: in Live
     * they are the Zerodha account's, as are those of a script cleared to trade live, and a reset of the paper account must never lift a live daily-loss pause.
     */
    suspend fun resetPaper() = lock.withLock {
        val b = book()
        val dropped = b.held.filterValues { !it.live }.keys.toSet()
        b.held.entries.removeAll { !it.value.live }
        // Never for a script cleared for Zerodha (even with the app back in Paper): its pause may be a live one.
        val live = OrbArms.liveNow()
        val clear = { id: Long -> b.liveOk[id] != true && (id in dropped || (!live && id !in b.held)) }
        b.dayPnl.keys.removeAll(clear)
        b.paused.keys.removeAll(clear)
        save(b); publish(b)
    }

    suspend fun load() = lock.withLock { book(); Unit }

    /**
     * Switch a script's auto-trading on or off. Off sells what it holds. [pinConfirmed]
     * (the PIN or fingerprint was asked just now) lets it place live orders.
     */
    suspend fun arm(id: Long, on: Boolean, pinConfirmed: Boolean = false): String = lock.withLock {
        val item = PineScripts.get(id) ?: return@withLock "not found"
        val b = book()
        if (on) {
            b.liveOk[id] = pinConfirmed
            b.lastTarget.remove(id); b.lastBar.remove(id)
            PineScripts.setAuto(id, item.auto.copy(on = true))
            note(b, id, "Switched on (${if (item.auto.mode == "alert") "alerts only" else if (OrbArms.liveNow()) "Live" else "Paper"}): waiting for the next signal on ${item.auto.symbol} ${item.auto.interval}")
        } else {
            PineScripts.setAuto(id, item.auto.copy(on = false))
            b.liveOk.remove(id)
            b.held[id]?.let { h -> runCatching { exit(b, id, item, h, "switched off") } }
            note(b, id, "Switched off")
        }
        save(b)
        "ok"
    }

    /** Called by the market watch every pass. */
    suspend fun tick() = lock.withLock {
        val all = PineScripts.items.value
        // A restore not yet disarmed: every script counts as switched off (what it holds is still sold).
        val disarm = com.optionslab.app.security.SecurePrefs.getBoolean(Backup.DISARM, false)
        val on = if (disarm) emptyList() else all.filter { it.auto.on }
        val b = book()
        if (on.isEmpty() && b.held.isEmpty()) return@withLock
        // Held by a script that is no longer on (turned off elsewhere, a restore, deleted): sell it.
        for ((id, h) in b.held.toMap()) if (on.none { it.id == id }) {
            val item = all.firstOrNull { it.id == id } ?: PineScripts.Item(id, "deleted script", "")
            runCatching { exit(b, id, item, h, "no longer auto-trading") }
        }
        val s = AppSettings.load()
        // The kill switch guards Zerodha only: in Paper mode the scripts keep trading ("Stop for today" still stops them).
        // Who stopped the day is said in the script's log (by Boss, the daily loss limit, the tile) - never just "stopped".
        val dayWhy = Strategies.stoppedWhy()
        val stopped = dayWhy != null || (s.guardKill && OrbArms.liveNow())
        stopSays = dayWhy?.let { "the day's stop (${com.optionslab.ira.DayStop.by(it)})" } ?: "the day's stop (the kill switch is on in Live)"
        stopResumes = dayWhy?.let { com.optionslab.ira.DayStop.mayLift(it) } ?: false
        for (item in on) runCatching { one(b, item, stopped) }.onFailure { e -> note(b, item.id, "Error: ${e.message ?: e.javaClass.simpleName}") }
        save(b)
    }

    private fun stepSeconds(iv: String): Long = when (iv) { "1m" -> 60; "5m" -> 300; "15m" -> 900; "1h" -> 3600; else -> 86400 }
    private fun lookbackDays(iv: String): Long = when (iv) { "1m" -> 4; "5m" -> 12; "15m" -> 30; "1h" -> 90; else -> 700 }

    /** What holds the scripts this pass ([tick]): "the day's stop (by you)", "... (by the daily loss limit)", the kill switch. */
    @Volatile private var stopSays = "the day's stop"
    /** Whether that stop is lifted by "start all" / Start bot (never the daily loss limit's). */
    @Volatile private var stopResumes = false
    /** Scripts whose log already says today why they stand still (said once a day for each reason). */
    private val stopNoted = java.util.Collections.synchronizedSet(HashSet<String>())

    private fun noteStopOnce(b: Book, id: Long) {
        if (!stopNoted.add("$id|${todayIst()}|$stopSays")) return
        note(b, id, "Paused by $stopSays: no new entries today" +
            if (stopResumes) " (\"start all\" to Jarvis, or Start bot on Home, resumes it)." else ".")
    }

    private suspend fun one(b: Book, item: PineScripts.Item, stopped: Boolean) {
        val id = item.id
        val t = clock()
        val mins = t.hour * 60 + t.minute
        var h = b.held[id]
        // A buy Zerodha did not confirm: settled from the order book first (its unfilled rest cancelled).
        if (h != null && h.unconfirmed) {
            h = settle(b, id, h)
            if (h == null) b.held.remove(id) else b.held[id] = h
        }
        // Sold outside the app (a notification's Close, the Trade tab, the broker's square-off).
        if (h != null && !h.unconfirmed && gone(h)) { note(b, id, "${h.symbol} is no longer held (closed outside the auto-trader)"); b.held.remove(id); h = null }
        if (h != null && (stopped || (item.auto.squareOff && mins >= 15 * 60 + 15) || h.day != todayIst().toString())) {
            exit(b, id, item, h, if (stopped) stopSays else "15:15 square-off"); return
        }
        val a = item.auto
        val today = todayIst().toString()
        // The held option's own stop-loss, target, profit lock and the script's daily loss limit: checked every pass.
        // The profit lock only ever sells sooner: the higher of the target ladder (on the target, else twice the stop) and
        // the percentage trail on the gain (2026-10-06: every script, no stop or target needed), read from the best price
        // seen BEFORE this look, as the ORB arms do; the stop, 15:15 and the day's stop above come first.
        val lockRef = if (a.profitLock) ProfitLock.pineReference(a.targetPts, a.stopPts) else null
        val trail = if (a.profitLock) a.trail else null
        if (h != null && isOpen() && (a.stopPts > 0 || a.targetPts > 0 || a.maxDayLoss > 0 || a.profitLock)) {
            val ltp = runCatching { optionLtp(h) }.getOrNull()
            val cost = ProfitLock.roundTripPerUnit(h.entry, h.qty)
            if (ltp != null) {
                val why = when {
                    a.stopPts > 0 && ltp <= h.entry - a.stopPts -> "stop-loss"
                    a.targetPts > 0 && ltp >= h.entry + a.targetPts -> "target"
                    ProfitLock.lockExits(h.entry, lockRef, trail, cost, h.peak, ltp) -> "profit lock"
                    a.maxDayLoss > 0 && realizedToday(b, id) + (ltp - h.entry) * h.qty <= -a.maxDayLoss -> "daily loss limit"
                    else -> null
                }
                if (why != null) {
                    val locked = if (why == "profit lock") ProfitLock.lockLevel(h.entry, lockRef, trail, cost, h.peak) else null
                    note(b, id, "${h.symbol} at ${"%.2f".format(java.util.Locale.ENGLISH, ltp)}: $why" +
                        (locked?.let { " (locked at ${"%.2f".format(java.util.Locale.ENGLISH, it)})" } ?: ""))
                    exit(b, id, item, h, why)
                    if (why == "daily loss limit") { b.paused[id] = today; note(b, id, "Daily loss limit reached: no more trades today") }
                    return
                }
                // Still held: a new best price raises the high-water mark (kept with the holding, so it survives a restart).
                if (ltp > h.peak) {
                    val was = ProfitLock.lockLevel(h.entry, lockRef, trail, cost, h.peak)
                    val raised = h.copy(peak = ltp)
                    b.held[id] = raised
                    val rung = ProfitLock.lockLevel(raised.entry, lockRef, trail, cost, raised.peak)
                    // Said when it first locks and then on each rise of 1% of the buy price or more (the trail moves with
                    // every new best: not a line a pass).
                    if (rung != null && (was == null || rung >= was + maxOf(0.01 * h.entry, 0.05))) note(b, id, "${raised.symbol}: profit lock now at ${"%.2f".format(java.util.Locale.ENGLISH, rung)} " +
                        "(best ${"%.2f".format(java.util.Locale.ENGLISH, ltp)})")
                    h = raised
                }
            }
        }
        if (a.maxDayLoss > 0 && realizedToday(b, id) <= -a.maxDayLoss && b.paused[id] != today) {
            b.paused[id] = today; note(b, id, "Daily loss limit reached: no more trades today")
        }
        if (b.paused[id] == today) { b.lastBar.remove(id); return }
        // Stopped for the day or the kill switch: a real pause - on the way back a signal that changed meanwhile is not chased.
        if (stopped) { b.lastBar.remove(id); noteStopOnce(b, id); return }
        if (!isOpen() || mins >= 15 * 60 + 15) return
        val script = PineScripts.script(item) ?: run { note(b, id, "The script has errors: nothing traded"); return }
        val step = stepSeconds(item.auto.interval)
        val now = epochSecondNow()
        // At most 15 s: this runs in the watch's risk steps, before the stops, the expiry square-off and the strategies.
        // The fetch runs apart and is only waited for, so even a read stuck in the network cannot hold the pass.
        val fetch = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).async {
            testBars?.invoke(item.auto.symbol, item.auto.interval)
                ?: ChartFeed.bars(item.auto.symbol, item.auto.interval, now - lookbackDays(item.auto.interval) * 86400, null, quick = true)
        }
        val fetched = kotlinx.coroutines.withTimeoutOrNull(15_000) { fetch.await() }
            ?: run { fetch.cancel(); note(b, id, "No candles for ${item.auto.symbol} within 15 s: this pass skipped"); return }
        val bars = fetched.filter { it.epochSecond + step <= now }                    // completed candles only
        val last = bars.lastOrNull() ?: return
        // A feed that failed today can hand back yesterday's candles: never trade on those, or on a stalled feed.
        if (step < 86_400 && (java.time.Instant.ofEpochSecond(last.epochSecond).atZone(com.optionslab.engine.IST).toLocalDate() != todayIst() ||
                now - last.epochSecond > step * 3 + 120)) return
        if (b.lastBar[id] == last.epochSecond) return
        // Only a pause the owner made (stopped for the day, the kill switch, its daily loss limit) means a signal that changed
        // meanwhile is not chased. Missed candles (a slow pass, a failed fetch, the app away) are not a pause: every pass
        // reads the day's candles again and acts on the signal as it is now (29 Sep).
        val watchedBefore = b.lastBar[id]
        val resumed = bars.size >= 2 && b.lastTarget[id] != null && watchedBefore == null
        b.lastBar[id] = last.epochSecond
        val r = Pine.run(script, bars.map { PineScripts.toPine(it) }, PineScripts.inputValues(item, script), item.auto.symbol, item.auto.interval,
            budgetMs = 5_000)
        r.error?.let { note(b, id, "Script stopped: ${it.message}"); return }
        val prev = b.lastTarget[id]
        val target = targetOf(item, script, r, prev ?: 0)
        b.lastTarget[id] = target
        if (prev == null) { note(b, id, "Watching: the signal now is ${describe(target)}; it trades on the next change"); return }
        if (target == prev) return
        val want: String? = when {
            target > 0 -> "CE"
            target < 0 -> if (item.auto.shortWith == "put") "PE" else null
            else -> null
        }
        if (resumed && !changedOnLastBar(item, script, r, bars, watchedBefore, prev, target)) {
            // What it holds is sold if the signal no longer backs it; nothing new is bought until the next change.
            note(b, id, "Back after a pause: the signal is now ${describe(target)}; it trades on the next change, not this one")
            if (a.mode != "alert" && h != null && h.right != want) exit(b, id, item, h, "signal changed during a pause")
            return
        }
        note(b, id, "Signal: ${describe(target)} at ${"%.2f".format(java.util.Locale.ENGLISH, last.close)}")
        if (a.mode == "alert") {
            // Alerts only: tell the owner, place nothing.
            Notifier.post(app, 5000 + (id % 1000).toInt(), Notifier.RISK, "${item.name}: ${describe(target)}",
                "${a.symbol} ${a.interval} at ${"%,.2f".format(java.util.Locale.ENGLISH, last.close)} · Pine signal (alerts only, no order placed)", "pine")
            return
        }
        if (h?.right == want) return
        if (h != null) { exit(b, id, item, h, "signal changed"); if (b.held.containsKey(id)) return }
        if (want == null) return
        val live = OrbArms.liveNow()
        if (live && b.liveOk[id] != true) {
            note(b, id, "Live needs your PIN once: switch auto-trade off and on again for this script. Nothing was sent.")
            return
        }
        enter(b, item, Right.valueOf(want), last.close, live)
    }

    /** Strike spacing used for the ATM option. */
    fun strikeStep(u: String) = when (u) { "BANKNIFTY", "SENSEX" -> 100; "MIDCPNIFTY" -> 25; else -> 50 }

    private fun describe(t: Int) = when { t > 0 -> "BUY"; t < 0 -> "SELL"; else -> "FLAT" }

    /** What [id] made or lost on closed trades today. */
    private fun realizedToday(b: Book, id: Long): Double {
        val v = b.dayPnl[id] ?: return 0.0
        val (day, amt) = v.split('|', limit = 2).let { it[0] to it.getOrNull(1) }
        return if (day == todayIst().toString()) amt?.toDoubleOrNull() ?: 0.0 else 0.0
    }

    private fun addPnl(b: Book, id: Long, pnl: Double) { b.dayPnl[id] = "${todayIst()}|${realizedToday(b, id) + pnl}" }

    /** Today's P&L of script [id]: closed trades and, when it holds one, the open option. */
    fun todayOf(id: Long): Double? = cache?.let { realizedToday(it, id) }

    /** The held option's last price, paper or Zerodha. */
    private suspend fun optionLtp(h: Held): Double? = if (!h.live) Paper.contractOf(h.symbol)?.let { Paper.lastPrice(it) }
        else h.kite?.let { sym -> if (!Broker.loggedIn) null else Broker.quotes(listOf("NFO:$sym"))["NFO:$sym"]?.last?.takeIf { it > 0 } }

    /** +1 long, -1 short, 0 flat: the strategy's own position, or the last buy/sell signal. */
    /**
     * Whether the change from [prev] to [target] was made by the newest candle itself, not by one the pause hid:
     * the candles after the last one watched ([watched], epoch seconds; null when none) are replayed up to the
     * one before the newest, and the change is the newest candle's own when the signal was still [prev] there.
     * A feed that stalled for a while and came back does not lose a change made just now.
     */
    private fun changedOnLastBar(item: PineScripts.Item, s: Pine.Script, r: Pine.Run, bars: List<com.optionslab.engine.Upstox.Bar>,
                                 watched: Long?, prev: Int, target: Int): Boolean {
        val n = r.position.size - 1
        if (n < 1 || n != bars.size - 1) return false
        // A strategy's position after the newest candle holds the orders of the candles before it.
        if (item.auto.buy == "strategy" && s.kind == Pine.Kind.STRATEGY) return Math.signum(r.position[n]).toInt() != target
        val bi = s.signals.indexOf(item.auto.buy); val si = s.signals.indexOf(item.auto.sell)
        val from = if (watched == null) 0 else bars.indexOfLast { it.epochSecond <= watched } + 1
        var t = prev
        for (i in from until n) t = when {
            bi >= 0 && r.signals[bi][i] -> 1
            si >= 0 && r.signals[si][i] -> -1
            else -> t
        }
        return t == prev
    }

    private fun targetOf(item: PineScripts.Item, s: Pine.Script, r: Pine.Run, prev: Int): Int {
        val n = r.position.size - 1
        if (n < 0) return prev
        if (item.auto.buy == "strategy" && s.kind == Pine.Kind.STRATEGY) return Math.signum(r.nextPosition).toInt()
        val bi = s.signals.indexOf(item.auto.buy); val si = s.signals.indexOf(item.auto.sell)
        return when {
            bi >= 0 && r.signals[bi][n] -> 1
            si >= 0 && r.signals[si][n] -> -1
            else -> prev
        }
    }

    // ---- orders ---------------------------------------------------------------------------

    private suspend fun enter(b: Book, item: PineScripts.Item, right: Right, spot: Double, live: Boolean) {
        val id = item.id
        val u = item.auto.symbol
        if (u == "SENSEX") { note(b, id, "SENSEX options trade on BSE, which the app does not place orders on: use Alerts only"); return }
        val strike = OrbRules.atmStrike(spot, strikeStep(u))
        val today = todayIst()
        val listed = Market.contracts().filter { it.underlying == u }.map { it.expiry }.distinct()
        val expiry = OrbRules.expiryAfter(today, listed) ?: run { note(b, id, "No $u expiry after today is listed: nothing bought"); return }
        val c = Paper.contractFor(u, expiry, strike.toDouble(), right) ?: run { note(b, id, "$u $strike $right is not listed: nothing bought"); return }
        val lots = item.auto.lots.coerceIn(1, 50)
        if (!live) {
            val ltp = Paper.lastPrice(c) ?: run { note(b, id, "No price for ${c.symbol}: nothing bought"); return }
            val snap = runCatching { Paper.snapshot() }.getOrNull()
            val refusals = Guard.check(Guard.paperOrder(c, "BUY", lots, ltp * 1.0005), snap?.let { Guard.paperAccount(it) }, paper = true)
            if (refusals.isNotEmpty()) { note(b, id, "Guard refused: ${refusals.joinToString(" ")}"); return }
            val buy = Paper.place(c, "BUY", lots, "MARKET", "MIS", null, null)
            val fill = filledOrCancelled(buy) ?: run { note(b, id, "Paper buy not filled: ${buy.message}"); return }
            buy.orderId?.let { Strategies.tagOwner("paper:$it", "${label(item)} · entry") }
            Notifier.orderFilled(app, "BUY", fill.first, c.symbol, fill.second, "Paper", label(item), buy.orderId)
            b.held[id] = Held(c.symbol, right.name, fill.first, c.lotSize, fill.second, today.toString(), false, null)
            note(b, id, "Bought ${fill.first} ${c.symbol} at ${"%.2f".format(java.util.Locale.ENGLISH, fill.second)} (paper)")
            return
        }
        val s = AppSettings.load()
        if (s.guardKill) { note(b, id, "Kill switch is on: nothing sent"); return }
        // A phone that failed the security check never sends a real order on its own.
        val findings = runCatching { com.optionslab.app.security.Integrity.reportWithin(app, 60_000) }.getOrDefault(emptyList())
        if (com.optionslab.app.security.Integrity.compromised(findings)) { note(b, id, "This phone failed the security check: no live order sent"); return }
        if (!Broker.loggedIn) { note(b, id, "Not logged in to Zerodha today: nothing sent"); return }
        val ins = (Broker.cachedInstruments() ?: runCatching { Broker.instruments() }.getOrNull())?.firstOrNull {
            it.name == c.underlying && it.expiry == c.expiry && it.right == c.right && kotlin.math.abs(it.strike - c.strike) < 1e-6
        } ?: run { note(b, id, "${c.symbol} is not listed on Zerodha: nothing sent"); return }
        val sym = ins.tradingSymbol
        val key = "NFO:$sym"
        val quote = runCatching { Broker.quotes(listOf(key))[key]?.last }.getOrNull()?.takeIf { it > 0 }
            ?: run { note(b, id, "No Zerodha quote for $sym: nothing sent"); return }
        val o = com.optionslab.engine.Kite.Order(sym, com.optionslab.engine.Kite.Side.BUY, lots * ins.lotSize, ins.lotSize, "MIS", "MARKET", null,
            ins.tickSize, "NFO", "irapine")
        val acct = Broker.accountNow()      // positions, funds and orders read in parallel
        val refusals = Guard.check(Guard.liveOrder(o).copy(price = quote), acct)
        if (refusals.isNotEmpty()) { note(b, id, "Guard refused: ${refusals.joinToString(" ")}"); return }
        val why = com.optionslab.engine.Kite.refusals(o, s.limits(), Broker.sentToday(), false, refPrice = quote)
        if (why.isNotEmpty()) { note(b, id, "Refused: ${why.joinToString("; ")}"); return }
        val known = knownKite()
        val orderId: String? = try { Broker.placeOrder(o) } catch (e: Exception) {
            if (Broker.definite(e)) { note(b, id, "Zerodha refused the buy: ${e.message}"); return }
            // The answer was lost, not necessarily the order: look (the book can trail the POST) before calling it unsent.
            try { Broker.findRecentRetrying(o, known) ?: run { note(b, id, "Zerodha did not answer the buy (${e.message}) and no such order is in its book: nothing bought"); return } }
            catch (_: Exception) { null }                                          // the order book could not be read either
        }
        orderId?.let { Strategies.tagOwner("kite:$it", "${label(item)} · entry") }
        var f = orderId?.let { runCatching { Broker.awaitOrder(it, 15_000) }.getOrNull() }
        if (orderId != null && f?.status !in DONE) {
            // Not finished in 15 s: the unfilled rest is cancelled so it can never fill later as an untracked entry;
            // only what filled is booked.
            runCatching { Broker.cancel(orderId) }
            f = runCatching { Broker.orderState(orderId) }.getOrNull()
        }
        if (f == null || f.status !in DONE) {
            // No answer: it may have filled, or still be working. Tracked as held (at most the full quantity) and
            // settled from the order book on the next passes, rather than left unwatched.
            b.held[id] = Held(c.symbol, right.name, o.quantity, ins.lotSize, quote, today.toString(), true, sym, orderId, System.currentTimeMillis())
            runCatching { save(b) }
            com.optionslab.app.work.Alerts.error("${label(item)}: Zerodha did not confirm the buy of $sym. It is treated as held until the order book shows what filled.", "Pine auto-trade")
            note(b, id, "Zerodha did not confirm the buy of $sym: tracked as held until checked")
            return
        }
        if (f.filled <= 0) { note(b, id, "Zerodha ${f.status.lowercase()}: no position"); return }
        val px = f.avgPrice.takeIf { it > 0 } ?: quote
        Notifier.orderFilled(app, "BUY", f.filled, sym, px, "Live", label(item), f.orderId)
        b.held[id] = Held(c.symbol, right.name, f.filled, ins.lotSize, px, today.toString(), true, sym)
        runCatching { save(b) }   // a live position is written down at once, not at the end of the pass
        note(b, id, "Bought ${f.filled} $sym at ${"%.2f".format(java.util.Locale.ENGLISH, px)} (LIVE)")
    }

    /** Terminal order states at Zerodha. */
    private val DONE = setOf("COMPLETE", "REJECTED", "CANCELLED")

    /** Kite order ids this phone already tracks (every bot's): never adopted for a lost reply. */
    private suspend fun knownKite(): List<String> =
        runCatching { Strategies.owners().keys.filter { it.startsWith("kite:") }.map { it.removePrefix("kite:") } }.getOrDefault(emptyList())

    /**
     * A live buy Zerodha had not confirmed: whatever of it still works is cancelled, then only what filled is
     * kept. Null when nothing was bought; [h] (its order id filled in) while Zerodha cannot tell yet.
     */
    private suspend fun settle(b: Book, id: Long, h: Held): Held? {
        val sym = h.kite ?: return null
        if (!Broker.loggedIn) return h
        if (h.day != todayIst().toString()) {
            // A day old: the MIS order and position are long settled; what is held now is read from the position book.
            note(b, id, "The buy of $sym on ${h.day} was never confirmed by Zerodha; check that day's contract note")
            return null
        }
        val oid = h.order ?: run {
            // The order id itself was lost: the entry is found in today's book by its symbol, side, tag and time.
            val since = java.time.Instant.ofEpochMilli(h.since).atZone(com.optionslab.engine.IST).toLocalDateTime().minusSeconds(30)
            val row = runCatching { Broker.latestTagged(sym, "BUY", "irapine", since, knownKite()) }.getOrElse { return h }
                ?: run { note(b, id, "No buy of $sym reached Zerodha: nothing held"); return null }
            Strategies.tagOwner("kite:${row.id}", "Pine · entry")
            row.id
        }
        var st = runCatching { Broker.orderState(oid) }.getOrNull() ?: return h.copy(order = oid)
        if (st.status !in DONE) {
            runCatching { Broker.cancel(oid) }
            st = runCatching { Broker.orderState(oid) }.getOrNull() ?: return h.copy(order = oid)
            if (st.status !in DONE) return h.copy(order = oid)
        }
        if (st.filled <= 0) { note(b, id, "The unconfirmed buy of $sym did not fill (${st.status.lowercase()}): nothing held"); return null }
        val px = st.avgPrice.takeIf { it > 0 } ?: h.entry
        note(b, id, "Zerodha confirmed the buy: ${st.filled} $sym at ${"%.2f".format(java.util.Locale.ENGLISH, px)} (LIVE)")
        return h.copy(qty = st.filled, entry = px, order = null, since = 0L, peak = maxOf(h.peak, px))
    }

    /** Sell what the script holds. Removes it from [b] once sold (or found gone). */
    private suspend fun exit(b: Book, id: Long, item: PineScripts.Item, h0: Held, why: String) {
        var h = h0
        if (!h.live) {
            val c = Paper.contractOf(h.symbol) ?: run { b.held.remove(id); return }
            val net = Paper.state.positions.filter { it.symbol == h.symbol && it.product == "MIS" }.sumOf { it.quantity }
            if (net <= 0) { b.held.remove(id); note(b, id, "${h.symbol} already closed"); return }
            val lots = (minOf(net, h.qty) / c.lotSize.coerceAtLeast(1)).coerceAtLeast(1)
            val sell = Paper.place(c, "SELL", lots, "MARKET", "MIS", null, null)
            val fill = filledOrCancelled(sell) ?: run { note(b, id, "Paper sell of ${h.symbol} not filled (${sell.message}); retrying next pass"); return }
            sell.orderId?.let { Strategies.tagOwner("paper:$it", "${label(item)} · $why") }
            Notifier.orderFilled(app, "SELL", fill.first, h.symbol, fill.second, "Paper", label(item), sell.orderId)
            addPnl(b, id, (fill.second - h.entry) * fill.first)
            b.held.remove(id)
            note(b, id, "Sold ${fill.first} ${h.symbol} at ${"%.2f".format(java.util.Locale.ENGLISH, fill.second)} ($why) · P&L ${"%+.0f".format(java.util.Locale.ENGLISH, (fill.second - h.entry) * fill.first)}")
            return
        }
        val sym = h.kite ?: run { b.held.remove(id); return }
        if (!Broker.loggedIn) { note(b, id, "Not logged in to Zerodha: cannot sell $sym. Close it in Trade."); return }
        // A buy not yet confirmed: its unfilled rest is cancelled first, and only what filled is sold.
        if (h.unconfirmed) {
            h = settle(b, id, h) ?: run { b.held.remove(id); return }
            b.held[id] = h
        }
        val still = runCatching { Broker.positionBook().net.filter { it.symbol == sym && it.exchange == "NFO" && it.product == "MIS" }.sumOf { it.qty } }
            .getOrNull() ?: return
        if (minOf(still, h.qty) <= 0 && !h.unconfirmed) { b.held.remove(id); note(b, id, "$sym already closed"); return }
        // Its own exits still working (an earlier pass's, or one whose reply was lost) come out first; every other sell
        // resting on the symbol (a protection's stop, an ORB stop, the owner's own limit) is left alone and subtracted:
        // together they never sell more than is held, and none of them can block this exit.
        val orders = runCatching { Broker.orders() }.getOrNull() ?: return
        orders.filter { it.working && it.symbol == sym && it.side == "SELL" && it.tag == "irapine" }.forEach { runCatching { Broker.cancel(it.id, it.variety) } }
        val working = runCatching { Broker.orders().filter { it.working && it.symbol == sym && it.side == "SELL" && it.product == "MIS" } }.getOrNull() ?: return
        val spec = runCatching { Broker.spec("NFO", sym) }.getOrNull() ?: return
        val qty = com.optionslab.engine.risk.ExitQty.sendable(still, working.sumOf { com.optionslab.engine.risk.ExitQty.remaining(it.qty, it.filled, it.pending) },
            h.qty, spec.lotSize)
        if (qty <= 0) {
            note(b, id, if (h.unconfirmed) "$sym: the buy is not confirmed yet and nothing of it is held to sell; checked again next pass"
                else "$sym: other exits already working at Zerodha cover what is held; checked again next pass")
            return
        }
        val o = com.optionslab.engine.Kite.Order(sym, com.optionslab.engine.Kite.Side.SELL, qty, spec.lotSize, "MIS", "MARKET", null, spec.tickSize, "NFO", "irapine")
        val bad = com.optionslab.engine.Kite.refusals(o, AppSettings.load().limits(), Broker.sentToday(), false, exit = true)
        if (bad.isNotEmpty()) {
            com.optionslab.app.work.Alerts.error("${label(item)}: the Zerodha exit was not sent (${bad.joinToString("; ")}). Close $sym in Trade.", "Pine live")
            return
        }
        val orderId = try { Broker.placeOrder(o, exit = true) } catch (e: Exception) {
            val found = if (Broker.definite(e)) null else runCatching { Broker.findRecentRetrying(o, knownKite() + orders.map { it.id }) }.getOrNull()
            found ?: run {
                com.optionslab.app.work.Alerts.error("${label(item)}: the Zerodha exit failed (${e.message}); retrying on the next pass.", "Pine live")
                return
            }
        }
        Strategies.tagOwner("kite:$orderId", "${label(item)} · $why")
        var f = runCatching { Broker.awaitOrder(orderId, 15_000) }.getOrNull()
        if (f?.status !in DONE) {
            // Not finished in 15 s: the rest is cancelled, so the next pass starts from what Zerodha says is held.
            runCatching { Broker.cancel(orderId) }
            f = runCatching { Broker.orderState(orderId) }.getOrNull() ?: f
        }
        if (f == null || f.filled <= 0) return
        val px = f.avgPrice.takeIf { it > 0 } ?: h.entry
        Notifier.orderFilled(app, "SELL", f.filled, sym, px, "Live", label(item), f.orderId)
        addPnl(b, id, (px - h.entry) * f.filled)
        if (f.filled >= h.qty && !h.unconfirmed) b.held.remove(id) else b.held[id] = h.copy(qty = (h.qty - f.filled).coerceAtLeast(0))
        note(b, id, "Sold ${f.filled} $sym at ${"%.2f".format(java.util.Locale.ENGLISH, px)} ($why, LIVE)")
    }

    private suspend fun gone(h: Held): Boolean = if (!h.live) {
        Paper.state.positions.filter { it.symbol == h.symbol && it.product == "MIS" }.sumOf { it.quantity } <= 0
    } else {
        if (!Broker.loggedIn) false
        else runCatching { Broker.positionBook().net.filter { it.symbol == h.kite && it.exchange == "NFO" && it.product == "MIS" }.sumOf { it.qty } <= 0 }
            .getOrDefault(false)
    }

    /** The fill of a MARKET paper order; one left open (no fresh price) is cancelled so it cannot fill later. */
    private suspend fun filledOrCancelled(r: Paper.Result): Pair<Int, Double>? {
        r.events.filterIsInstance<com.optionslab.engine.sandbox.SandboxEvent.Fill>().firstOrNull()?.let { return it.quantity to it.price }
        val oid = r.orderId ?: return null
        if (!r.ok) return null
        Paper.cancel(oid, "unfilled_market")
        val o = Paper.state.orders.firstOrNull { it.orderId == oid } ?: return null
        return if (o.status == "complete") o.quantity to (o.averagePrice?.toDouble() ?: return null) else null
    }
}
