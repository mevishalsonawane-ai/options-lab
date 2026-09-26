package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.Vault
import com.optionslab.app.work.Notifier
import com.optionslab.engine.Right
import com.optionslab.engine.Upstox
import com.optionslab.engine.orb.Arm
import com.optionslab.engine.orb.Bar
import com.optionslab.engine.orb.OrbRules
import com.optionslab.engine.orb.PassRule
import com.optionslab.engine.orb.Replay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale

/**
 * The two ORB paper arms (ORB and ORB Fresh), run on the phone exactly as the
 * desktop's services/ai_signals/orb_arm.py runs them (strategies/orb/ORB_STRATEGY.md):
 *
 *  - every pass of the market watch, each ARMED arm decides once on the last
 *    completed 5-minute BANKNIFTY bar (idempotent within a bar);
 *  - an entry is a MARKET BUY of 1 lot MIS, booked at the actual fill, followed
 *    straight away by a RESTING SL-M SELL at fill - 40 (the book owns the stop:
 *    the arm never sends a second sell for it);
 *  - +40 target, 15:10 session end and the operator's Stop for today exit with
 *    a MARKET SELL after the resting stop has been taken out of the book;
 *  - re-entry from the bar after the exit bar, one position per arm.
 *
 * WHERE: a new entry follows the app's Paper/Live switch at the moment it is
 * placed (the owner's choice, 2026-09-26). In Live every entry waits for the
 * owner's approval with the PIN or fingerprint, never automatic. Each position
 * remembers its own account, and its stop and exit only ever go there:
 * flipping the switch while a position is open never moves its exit. Every
 * entry goes through the account guard (and in Live the kill switch and the
 * Bot settings caps as well).
 *
 * Prices are the Upstox public candles the paper account already uses; the
 * index is aggregated from 1-minute to 5-minute bars labelled by their start.
 * State lives in its own encrypted vault file and survives restarts.
 */
object OrbArms {
    private lateinit var file: File
    private lateinit var app: Context
    private val lock = Mutex()

    fun init(context: Context) {
        app = context.applicationContext
        file = File(app.filesDir, "orb.vault")
    }

    // ---- state -------------------------------------------------------------------

    data class Position(
        val arm: String, val symbol: String, val right: String, val qty: Int, val entry: Double, val entryTime: LocalDateTime,
        val signalBar: LocalDateTime, val entryOrderId: String?, val stopOrderId: String?, val stopTrigger: Double?,
        val exit: Double? = null, val exitTime: LocalDateTime? = null, val why: String? = null, val charges: Double = 0.0,
        /** True: entered at Zerodha under [kite] (its orders are Kite order ids); false: the paper account. */
        val live: Boolean = false, val kite: String? = null,
    ) {
        val open: Boolean get() = exit == null
        val day: LocalDate get() = entryTime.toLocalDate()
        val points: Double? get() = exit?.let { it - entry }
        val grossPnl: Double? get() = points?.let { it * qty }
    }

    data class Pending(val arm: String, val right: String, val signalBar: LocalDateTime, val expires: LocalDateTime)

    data class Legs(val day: LocalDate, val strike: Int, val expiry: LocalDate, val ce: Paper.Contract, val pe: Paper.Contract)

    private data class Book(
        val armed: MutableMap<String, Boolean> = HashMap(),
        val auto: MutableMap<String, Boolean> = HashMap(),
        var legs: Legs? = null,
        var range: Pair<Double, Double>? = null,
        var rangeDay: LocalDate? = null,
        val decided: MutableMap<String, MutableSet<String>> = HashMap(),   // "arm|day" -> bar starts
        val positions: MutableList<Position> = ArrayList(),
        val pending: MutableMap<String, Pending> = HashMap(),
        val status: MutableMap<String, String> = HashMap(),
        val replays: MutableMap<String, JSONObject> = LinkedHashMap(),     // day -> { arm: [trades], up: bool }
        val upDays: MutableMap<String, Boolean> = HashMap(),
    )

    private var cache: Book? = null

    private fun contractJson(c: Paper.Contract) = JSONArray().put(c.symbol).put(c.underlying).put(c.expiry.toString()).put(c.strike)
        .put(c.right.name).put(c.lotSize).put(c.feedKey)

    private fun contractOf(a: JSONArray) = Paper.Contract(a.getString(0), a.getString(1), LocalDate.parse(a.getString(2)), a.getDouble(3),
        Right.valueOf(a.getString(4)), a.getInt(5), a.getString(6))

    private fun book(): Book {
        cache?.let { return it }
        val b = Book()
        val ok = runCatching {
            val o = JSONObject(String(Vault.readFileSteady(file) ?: return@runCatching, Charsets.UTF_8))
            o.optJSONObject("armed")?.let { m -> m.keys().forEach { b.armed[it] = m.getBoolean(it) } }
            o.optJSONObject("auto")?.let { m -> m.keys().forEach { b.auto[it] = m.getBoolean(it) } }
            o.optJSONObject("legs")?.let { l ->
                b.legs = Legs(LocalDate.parse(l.getString("day")), l.getInt("strike"), LocalDate.parse(l.getString("expiry")),
                    contractOf(l.getJSONArray("ce")), contractOf(l.getJSONArray("pe")))
            }
            o.optJSONArray("range")?.let { b.range = it.getDouble(0) to it.getDouble(1); b.rangeDay = LocalDate.parse(it.getString(2)) }
            o.optJSONObject("decided")?.let { m -> m.keys().forEach { k -> val a = m.getJSONArray(k); b.decided[k] = (0 until a.length()).map { a.getString(it) }.toMutableSet() } }
            o.optJSONArray("positions")?.let { a ->
                for (i in 0 until a.length()) {
                    val p = a.getJSONObject(i)
                    b.positions += Position(p.getString("arm"), p.getString("symbol"), p.getString("right"), p.getInt("qty"), p.getDouble("entry"),
                        LocalDateTime.parse(p.getString("entryTime")), LocalDateTime.parse(p.getString("signalBar")),
                        p.optString("entryOrderId").ifEmpty { null }, p.optString("stopOrderId").ifEmpty { null },
                        if (p.has("stopTrigger")) p.getDouble("stopTrigger") else null,
                        if (p.has("exit")) p.getDouble("exit") else null,
                        p.optString("exitTime").ifEmpty { null }?.let { LocalDateTime.parse(it) }, p.optString("why").ifEmpty { null },
                        p.optDouble("charges", 0.0), p.optBoolean("live", false), p.optString("kite").ifEmpty { null })
                }
            }
            o.optJSONObject("pending")?.let { m -> m.keys().forEach { k -> val p = m.getJSONObject(k)
                b.pending[k] = Pending(k, p.getString("right"), LocalDateTime.parse(p.getString("bar")), LocalDateTime.parse(p.getString("expires"))) } }
            o.optJSONObject("status")?.let { m -> m.keys().forEach { b.status[it] = m.getString(it) } }
            o.optJSONObject("replays")?.let { m -> m.keys().forEach { b.replays[it] = m.getJSONObject(it) } }
            o.optJSONObject("upDays")?.let { m -> m.keys().forEach { b.upDays[it] = m.getBoolean(it) } }
        }.isSuccess
        if (!ok) {
            // Never overwrite what could not be read: set it aside and start clean, and say so.
            if (file.exists()) Vault.setAside(file)
            Notifier.post(app, 2016, Notifier.APPROVAL, "ORB arms could not be read",
                "Their saved state was set aside and both arms are disarmed. If an ORB position was open, check Trade → Paper now.", "almanac")
            return Book().also { cache = it }
        }
        cache = b
        return b
    }

    private fun save(b: Book) {
        val o = JSONObject()
        o.put("armed", JSONObject(b.armed as Map<*, *>))
        o.put("auto", JSONObject(b.auto as Map<*, *>))
        b.legs?.let { l -> o.put("legs", JSONObject().put("day", l.day.toString()).put("strike", l.strike).put("expiry", l.expiry.toString())
            .put("ce", contractJson(l.ce)).put("pe", contractJson(l.pe))) }
        b.range?.let { o.put("range", JSONArray().put(it.first).put(it.second).put(b.rangeDay.toString())) }
        // Only today's decided bars matter; older days are dropped.
        val today = Market.today().toString()
        o.put("decided", JSONObject().apply { b.decided.filterKeys { it.endsWith(today) }.forEach { (k, v) -> put(k, JSONArray(v.toList())) } })
        o.put("positions", JSONArray().apply {
            b.positions.takeLast(2000).forEach { p ->
                put(JSONObject().put("arm", p.arm).put("symbol", p.symbol).put("right", p.right).put("qty", p.qty).put("entry", p.entry)
                    .put("entryTime", p.entryTime.toString()).put("signalBar", p.signalBar.toString())
                    .put("entryOrderId", p.entryOrderId ?: "").put("stopOrderId", p.stopOrderId ?: "")
                    .apply { p.stopTrigger?.let { put("stopTrigger", it) }; p.exit?.let { put("exit", it) } }
                    .put("exitTime", p.exitTime?.toString() ?: "").put("why", p.why ?: "").put("charges", p.charges)
                    .put("live", p.live).put("kite", p.kite ?: ""))
            }
        })
        o.put("pending", JSONObject().apply { b.pending.forEach { (k, p) -> put(k, JSONObject().put("right", p.right).put("bar", p.signalBar.toString()).put("expires", p.expires.toString())) } })
        o.put("status", JSONObject(b.status as Map<*, *>))
        o.put("replays", JSONObject().apply { b.replays.entries.toList().takeLast(120).forEach { (k, v) -> put(k, v) } })
        o.put("upDays", JSONObject(b.upDays as Map<*, *>))
        Vault.writeFile(file, o.toString().toByteArray(Charsets.UTF_8))
        cache = b
    }

    private fun armOf(source: String): Arm = OrbRules.ARMS.first { it.source == source }

    /** New entries follow the app's Paper/Live switch. */
    fun liveNow(): Boolean = AppSettings.load().let { it.live && it.allowRealOrders }
    private fun now(): LocalDateTime = Market.now().toLocalDateTime()

    // ---- views for the UI ---------------------------------------------------------

    data class ArmView(
        val arm: Arm, val armed: Boolean, val automatic: Boolean, val status: String, val open: Position?, val mark: Double?,
        val pending: Pending?, val today: List<Position>,
    )

    data class View(
        val arms: List<ArmView>, val legs: Legs?, val range: Pair<Double, Double>?, val forward: PassRule.Verdict,
        val replay: JSONObject?, val replayDay: String?,
    )

    private val marks = java.util.concurrent.ConcurrentHashMap<String, Double>()

    suspend fun view(): View = lock.withLock {
        val b = book()
        val day = Market.today()
        val arms = OrbRules.ARMS.map { a ->
            val open = b.positions.lastOrNull { it.arm == a.source && it.open }
            ArmView(a, b.armed[a.source] == true, b.auto[a.source] != false, b.status[a.source] ?: "", open, open?.let { marks[it.symbol] },
                b.pending[a.source], b.positions.filter { it.arm == a.source && it.day == day })
        }
        val lastReplay = b.replays.entries.lastOrNull()
        View(arms, b.legs?.takeIf { it.day == day }, b.range?.takeIf { b.rangeDay == day }, forward(b), lastReplay?.value, lastReplay?.key)
    }

    /** The pre-registered forward test on the closed arm trades, operator-closed trades excluded. */
    private fun forward(b: Book): PassRule.Verdict = PassRule.judge(
        b.positions.filter { !it.open && it.why != "operator_stop" && it.why != "closed_by_you" }.map { p ->
            PassRule.Closed(p.day, (p.grossPnl ?: 0.0) - p.charges, b.upDays[p.day.toString()])
        })

    suspend fun holding(): Boolean = lock.withLock { book().positions.any { it.open } }

    // ---- arming and approvals ------------------------------------------------------

    suspend fun setArmed(source: String, on: Boolean, automatic: Boolean): String = lock.withLock {
        val b = book()
        b.armed[source] = on
        b.auto[source] = automatic
        if (!on) b.pending.remove(source)
        save(b)
        val label = armOf(source).label
        if (on) "$label armed. " + (if (liveNow()) "The app is in LIVE: entries go to Zerodha, 1 lot, each one after your approval with the PIN."
            else "Paper account, ${if (automatic) "automatic" else "you approve each entry"}; entries follow the Paper/Live switch.") +
            " It decides on 5-minute BANKNIFTY bars from 10:05."
        else "$label disarmed." + if (b.positions.any { it.arm == source && it.open }) " Its open position is still managed to its exit." else ""
    }

    /** After a restore: both arms off, nothing waiting for approval. */
    suspend fun disarmAll() = lock.withLock {
        val b = book(); b.armed.clear(); b.auto.clear(); b.pending.clear(); save(b)
    }

    suspend fun approve(source: String, pinConfirmed: Boolean = false): String {
        // A live entry is sent only after the owner's PIN or fingerprint (the UI asks first).
        if (liveNow() && !pinConfirmed) return "The app is in Live: approve with your PIN on Home → Strategies."
        val p = lock.withLock { book().pending.remove(source)?.also { save(book()) } } ?: return "Nothing is waiting for approval."
        if (now().isAfter(p.expires)) return "The ${armOf(source).label} signal expired at ${hhmm(p.expires)}; a new break will ask again."
        return lock.withLock {
            val b = book()
            val legs = b.legs ?: return@withLock "The day's contracts are not loaded yet."
            // A paper approval never turns into a live order because the switch flipped meanwhile.
            val live = liveNow()
            if (live && !pinConfirmed) return@withLock "The app switched to Live: approve with your PIN on Home → Strategies."
            val msg = enter(b, armOf(source), if (p.right == "CE") legs.ce else legs.pe, p.signalBar, live)
            b.status[source] = msg; save(b); describe(msg)
        }
    }

    suspend fun skip(source: String): String = lock.withLock {
        val b = book(); b.pending.remove(source); b.status[source] = "skipped_by_you"; save(b); "Skipped."
    }

    // ---- the minute cycle ---------------------------------------------------------

    /**
     * One pass: manage open positions, then let each armed arm decide on the last
     * completed bar. Called by the market watch every pass (after Paper.tick, so a
     * resting stop has already been matched against the latest price).
     */
    suspend fun tick() {
        if (!Market.isTradingDay()) return
        lock.withLock {
            val b = book()
            val t = now()
            runCatching { priceCheck(b, t) }
            val anyArmed = OrbRules.ARMS.any { b.armed[it.source] == true }
            if (!anyArmed || !OrbRules.inWindow(t.toLocalTime())) { save(b); return@withLock }
            val bars = runCatching { indexBars(t) }.getOrNull()
            for (arm in OrbRules.ARMS) {
                if (b.armed[arm.source] != true) continue
                val s = if (bars == null) "no_index_data" else runCatching { cycle(b, arm, t, bars) }.getOrElse { "error: ${it.message}" }
                // "no_decision_bar" repeats within a bar; keep the bar's own verdict on screen.
                if (s != "no_decision_bar" || b.status[arm.source].isNullOrEmpty()) b.status[arm.source] = s
            }
            save(b)
        }
    }

    /** A lighter pass between minutes while a position is open: the stop, the target and the clock. */
    suspend fun priceCheckOnly() = lock.withLock { val b = book(); runCatching { priceCheck(b, now()) }; save(b) }

    private suspend fun cycle(b: Book, arm: Arm, t: LocalDateTime, bars: List<Bar>): String {
        val day = t.toLocalDate()
        if (b.positions.any { it.arm == arm.source && it.open }) return "holding"
        if (!t.toLocalTime().isBefore(OrbRules.SQUARE_OFF)) return "flat_after_square_off"
        if (Strategies.stoppedToday()) return "stopped_for_today"
        b.pending[arm.source]?.let { if (t.isAfter(it.expires)) b.pending.remove(arm.source) else return "awaiting_approval" }
        val rng = OrbRules.openingRange(bars) ?: return "waiting_for_opening_range"
        b.range = rng; b.rangeDay = day
        val legs = contracts(b, day, bars) ?: return "no_contract"
        val last = bars.last()
        val spent = b.decided.getOrPut("${arm.source}|$day") { HashSet() }
        if (!spent.add(last.start.toString())) return "no_decision_bar"
        val lastExit = b.positions.filter { it.arm == arm.source && it.day == day }.mapNotNull { it.exitTime }.maxOrNull()
        val (direction, why) = OrbRules.entrySignal(bars, rng, arm, lastExit)
        if (direction == 0) return why
        val right = if (direction > 0) "CE" else "PE"
        val c = if (direction > 0) legs.ce else legs.pe
        val live = liveNow()
        if (live || b.auto[arm.source] == false) {
            // Valid until the next bar completes: after that the signal is stale.
            b.pending[arm.source] = Pending(arm.source, right, last.start, last.start.plusMinutes(10))
            Notifier.post(app, 6960 + OrbRules.ARMS.indexOf(arm), Notifier.APPROVAL, "${arm.label}: approve BUY ${c.symbol}",
                "BANKNIFTY closed ${if (direction > 0) "above" else "below"} the opening range on the ${hhmm(last.start)} bar. " +
                    (if (live) "LIVE on Zerodha, 1 lot: approve with your PIN in the app" else "Paper account, 1 lot") +
                    ". Approve by ${hhmm(last.start.plusMinutes(10))} or it lapses.", "almanac")
            return "awaiting_approval"
        }
        return enter(b, arm, c, last.start, live)
    }

    /** The day's strike from the first completed bar at or after 09:20, and the nearest expiry after today; held all day. */
    private fun contracts(b: Book, day: LocalDate, bars: List<Bar>): Legs? {
        b.legs?.let { if (it.day == day) return it }
        val ref = OrbRules.strikeBar(bars) ?: return null
        val strike = OrbRules.atmStrike(ref.close)
        val listed = Market.contracts().filter { it.underlying == OrbRules.UNDERLYING }.map { it.expiry }.distinct()
        val expiry = OrbRules.expiryAfter(day, listed) ?: return null
        val ce = Paper.contractFor(OrbRules.UNDERLYING, expiry, strike.toDouble(), Right.CE) ?: return null
        val pe = Paper.contractFor(OrbRules.UNDERLYING, expiry, strike.toDouble(), Right.PE) ?: return null
        return Legs(day, strike, expiry, ce, pe).also { b.legs = it }
    }

    /** [live] is decided once by the caller, so the account cannot change between the check and the order. */
    private suspend fun enter(b: Book, arm: Arm, c: Paper.Contract, signalBar: LocalDateTime, live: Boolean): String {
        if (live) return enterLive(b, arm, c, signalBar)
        val ltp = Paper.lastPrice(c) ?: return "refused: no quote"             // never enter blind
        // The Upstox feed has no bid/ask, so the paper fill is the LTP slipped 5 bps: price the checks the same way.
        val expected = ltp * 1.0005
        if (OrbRules.stopTrigger(expected) == null) return "refused: premium %.2f is at or below 40, a 40-point stop has no level".format(Locale.ENGLISH, expected)
        if (Strategies.stoppedToday()) return "stopped_for_today"             // stop pressed mid-decision
        val snap = runCatching { Paper.snapshot() }.getOrNull()
        val refusals = Guard.check(Guard.paperOrder(c, "BUY", 1, expected), snap?.let { Guard.paperAccount(it) }, paper = true)
        if (refusals.isNotEmpty()) return "guard_refused: " + refusals.joinToString(" ")
        val buy = Paper.place(c, "BUY", 1, "MARKET", "MIS", null, null)
        val fill = filledOrCancelled(buy) ?: return "order_refused: ${if (buy.ok) "no price to fill at; the order was cancelled" else buy.message}"
        buy.orderId?.let { Strategies.tagOwner("paper:$it", "${arm.label} · entry") }
        Notifier.orderFilled(app, "BUY", fill.quantity, fill.symbol, fill.price, "Paper", arm.label)
        val trigger = OrbRules.stopTrigger(fill.price)
        var stopId: String? = null
        if (trigger != null) {
            val stop = Paper.place(c, "SELL", 1, "SL-M", "MIS", null, trigger)
            if (stop.ok) { stopId = stop.orderId; stopId?.let { Strategies.tagOwner("paper:$it", "${arm.label} · stop") } }
        }
        b.positions += Position(arm.source, c.symbol, c.right.name, fill.quantity, fill.price, now(), signalBar, buy.orderId, stopId, trigger,
            charges = chargesOf(buy.orderId))
        marks[c.symbol] = fill.price
        return "entered"
    }

    /** Resting stop, +40 target, 15:10 exit, the 15:15 backstop and the operator stop, for every open position. */
    private suspend fun priceCheck(b: Book, t: LocalDateTime) {
        val stopped = Strategies.stoppedToday()
        val liveOpen = b.positions.withIndex().filter { it.value.open && it.value.live }
        if (liveOpen.isNotEmpty()) runCatching { priceCheckLive(b, t, liveOpen, stopped) }
        val open = b.positions.withIndex().filter { it.value.open && !it.value.live }
        if (open.isEmpty()) return
        val orders = Paper.state.orders.associateBy { it.orderId }
        for ((i, p) in open) {
            val c = Paper.contractOf(p.symbol) ?: continue
            // The resting stop filled in the paper book: that is the exit.
            val so = p.stopOrderId?.let { orders[it] }
            if (so != null && so.status == "complete") {
                b.positions[i] = p.copy(exit = so.averagePrice?.toDouble() ?: p.stopTrigger, exitTime = so.updateTimestamp, why = "stop",
                    charges = p.charges + chargesOf(so.orderId))
                continue
            }
            // A stop found cancelled or rejected is gone, not retried.
            val cur = if (so != null && (so.status == "cancelled" || so.status == "rejected")) p.copy(stopOrderId = null).also { b.positions[i] = it } else p
            // The paper account squared the MIS off at 15:15 (the backstop): book it at the last price.
            val net = Paper.state.positions.filter { it.symbol == p.symbol && it.product == "MIS" }.sumOf { it.quantity }
            val ltp = Paper.lastPrice(c)
            ltp?.let { marks[p.symbol] = it }
            // The position is gone from the paper book without the arm selling it: the 15:15 square-off
            // (or a restart after it), or the owner closed it (a notification's Close button, the Trade tab).
            // Its resting stop comes out of the book at once, so it can never fill as a short.
            if (net <= 0) {
                cur.stopOrderId?.let { runCatching { Paper.cancel(it) } }
                val backstop = cur.day.isBefore(t.toLocalDate()) || !t.toLocalTime().isBefore(LocalTime.of(15, 15))
                // Book the actual closing fill (slippage and charges included) when there is one.
                val sq = Paper.state.trades.lastOrNull { it.symbol == p.symbol && it.action == "SELL" && it.strategy == "AUTO_SQUARE_OFF" && !it.timestamp.isBefore(cur.entryTime) }
                b.positions[i] = cur.copy(exit = sq?.price?.toDouble() ?: ltp ?: cur.entry, exitTime = sq?.timestamp ?: t,
                    why = if (backstop) "backstop_square_off" else "closed_by_you",
                    stopOrderId = null, charges = cur.charges + (sq?.charges?.toDouble() ?: 0.0))
                continue
            }
            if (ltp == null) continue                                               // no price at all: hold
            val why = when {
                stopped -> "operator_stop"
                !t.toLocalTime().isBefore(OrbRules.SQUARE_OFF) -> "session_end"
                else -> OrbRules.exitReason(cur.entry, ltp, t).takeIf { it == "target" || (it == "stop" && cur.stopOrderId == null) }
            } ?: continue
            b.positions[i] = exit(cur, c, why)
        }
    }

    /** Take the resting stop out of the book first, then sell; if the stop filled meanwhile, that is the exit. */
    private suspend fun exit(p: Position, c: Paper.Contract, why: String): Position {
        p.stopOrderId?.let { id ->
            Paper.cancel(id)
            val so = Paper.state.orders.firstOrNull { it.orderId == id }
            if (so?.status == "complete") return p.copy(exit = so.averagePrice?.toDouble(), exitTime = so.updateTimestamp, why = "stop",
                charges = p.charges + chargesOf(id))
        }
        val sell = Paper.place(c, "SELL", p.qty / c.lotSize.coerceAtLeast(1), "MARKET", "MIS", null, null)
        val fill = filledOrCancelled(sell) ?: return p.copy(stopOrderId = null)    // nothing left working; retried on the next pass
        sell.orderId?.let { Strategies.tagOwner("paper:$it", "${armOf(p.arm).label} · $why") }
        Notifier.orderFilled(app, "SELL", fill.quantity, fill.symbol, fill.price, "Paper", armOf(p.arm).label)
        return p.copy(stopOrderId = null, exit = fill.price, exitTime = now(), why = why, charges = p.charges + chargesOf(sell.orderId))
    }

    // ---- Zerodha (the app in Live) --------------------------------------------------

    /**
     * The limit under a live stop: SL, not SL-M (Zerodha can refuse SL-M on index options),
     * 5% (at least 2 points) below the trigger so it fills in a fast fall. If the price runs
     * through even that, the app's own check sells at market.
     */
    private fun stopLimit(trigger: Double, tick: Double): Double =
        com.optionslab.engine.Kite.onTick(trigger - maxOf(2.0, trigger * 0.05), tick, com.optionslab.engine.Kite.Side.SELL).coerceAtLeast(tick)

    private fun kiteCharge(side: String, price: Double, qty: Int): Double =
        com.optionslab.engine.sandbox.SandboxCosts.charge(side, java.math.BigDecimal(price), qty).toDouble()

    /**
     * A live entry: MARKET BUY 1 lot MIS at Zerodha, then a resting SL SELL 40 below the
     * fill. Only reached after the owner approved with the PIN (see [approve]).
     */
    private suspend fun enterLive(b: Book, arm: Arm, c: Paper.Contract, signalBar: LocalDateTime): String {
        val s = AppSettings.load()
        if (s.guardKill) return "refused: the kill switch is on"
        if (!Broker.loggedIn) return "refused: not logged in to Zerodha today"
        val ins = (Broker.cachedInstruments() ?: runCatching { Broker.instruments() }.getOrNull())?.firstOrNull {
            it.name == c.underlying && it.expiry == c.expiry && it.right == c.right && kotlin.math.abs(it.strike - c.strike) < 1e-6
        } ?: return "refused: ${c.symbol} is not listed on Zerodha"
        val sym = ins.tradingSymbol
        val key = "NFO:$sym"
        val last = runCatching { Broker.quotes(listOf(key))[key]?.last }.getOrNull()?.takeIf { it > 0 } ?: return "refused: no quote"
        if (OrbRules.stopTrigger(last) == null) return "refused: premium %.2f is at or below 40, a 40-point stop has no level".format(Locale.ENGLISH, last)
        if (Strategies.stoppedToday()) return "stopped_for_today"
        val o = com.optionslab.engine.Kite.Order(sym, com.optionslab.engine.Kite.Side.BUY, ins.lotSize, ins.lotSize, "MIS", "MARKET", null,
            ins.tickSize, "NFO", "iraorb")
        val acct = runCatching {
            Guard.liveAccount(Broker.positionBook(), runCatching { Broker.funds() }.getOrNull(), runCatching { Broker.orders().size }.getOrDefault(0))
        }.getOrNull()
        val refusals = Guard.check(Guard.liveOrder(o).copy(price = last), acct)
        if (refusals.isNotEmpty()) return "guard_refused: " + refusals.joinToString(" ")
        val why = com.optionslab.engine.Kite.refusals(o, s.limits(), Broker.sentToday(), false, refPrice = last)
        if (why.isNotEmpty()) return "refused: " + why.joinToString("; ")
        val id = try {
            Broker.placeOrder(o)
        } catch (e: Broker.KiteError) {
            return "order_refused: " + (e.message ?: "Zerodha refused the order")
        } catch (e: Broker.NotLoggedIn) {
            return "refused: not logged in to Zerodha today"
        } catch (e: Exception) {
            // The answer was lost, not necessarily the order: look before calling it unsent.
            runCatching { Broker.findRecent(o, emptyList()) }.getOrNull() ?: return "order_refused: ${e.message}; no matching order found at Zerodha"
        }
        Strategies.tagOwner("kite:$id", "${arm.label} · entry")
        var f = runCatching { Broker.awaitOrder(id, 15_000) }.getOrNull()
        if (f == null || f.filled <= 0) {
            // Not filled in 15 s: take it out so it can never fill later as an untracked entry.
            if (f?.status !in setOf("REJECTED", "CANCELLED")) runCatching { Broker.cancel(id) }
            f = runCatching { Broker.orderState(id) }.getOrNull()
            if (f == null || f.filled <= 0) return "order_refused: Zerodha ${f?.status?.lowercase() ?: "did not answer"}" +
                (f?.message?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")
        }
        val fill = f.avgPrice.takeIf { it > 0 } ?: last
        Notifier.orderFilled(app, "BUY", f.filled, sym, fill, "Live", arm.label)
        val trigger = OrbRules.stopTrigger(fill)?.let { com.optionslab.engine.Kite.onTick(it, ins.tickSize, com.optionslab.engine.Kite.Side.SELL) }
        var stopId: String? = null
        if (trigger != null) {
            stopId = runCatching {
                Broker.placeOrder(com.optionslab.engine.Kite.Order(sym, com.optionslab.engine.Kite.Side.SELL, f.filled, ins.lotSize, "MIS", "SL",
                    stopLimit(trigger, ins.tickSize), ins.tickSize, "NFO", "iraorb", triggerPrice = trigger))
            }.getOrNull()
            if (stopId != null) Strategies.tagOwner("kite:$stopId", "${arm.label} · stop")
            else com.optionslab.app.work.Alerts.error("${arm.label}: the −40 stop could not be placed at Zerodha; the app watches it instead.", "ORB live")
        }
        b.positions += Position(arm.source, c.symbol, c.right.name, f.filled, fill, now(), signalBar, id, stopId, trigger,
            charges = kiteCharge("BUY", fill, f.filled), live = true, kite = sym)
        marks[c.symbol] = fill
        return "entered_live"
    }

    /** Stop, target, 15:10 and the operator stop for positions held at Zerodha. */
    private suspend fun priceCheckLive(b: Book, t: LocalDateTime, open: List<IndexedValue<Position>>, stopped: Boolean) {
        if (!Broker.loggedIn) return
        val orders = Broker.orders().associateBy { it.id }
        val net = Broker.positionBook().net
        val keys = open.mapNotNull { it.value.kite }.map { "NFO:$it" }.distinct()
        val q = runCatching { Broker.quotes(keys) }.getOrDefault(emptyMap())
        for ((i, p) in open) {
            val sym = p.kite ?: continue
            val so = p.stopOrderId?.let { orders[it] }
            if (so != null && so.status == "COMPLETE") {
                val px = so.avg.takeIf { it > 0 } ?: p.stopTrigger ?: p.entry
                Notifier.orderFilled(app, "SELL", so.filled, sym, px, "Live", "${armOf(p.arm).label} · stop")
                b.positions[i] = p.copy(exit = px, exitTime = t, why = "stop", stopOrderId = null, charges = p.charges + kiteCharge("SELL", px, p.qty))
                continue
            }
            if (so?.status == "REJECTED") com.optionslab.app.work.Alerts.error(
                "${armOf(p.arm).label}: Zerodha rejected the stop (${so.message.ifBlank { "no reason given" }}). The app now watches the −40 stop itself.", "ORB live")
            val cur = if (so != null && (so.status == "CANCELLED" || so.status == "REJECTED")) p.copy(stopOrderId = null).also { b.positions[i] = it } else p
            val held = net.filter { it.symbol == sym && it.exchange == "NFO" && it.product == "MIS" }.sumOf { it.qty }
            val ltp = q["NFO:$sym"]?.last?.takeIf { it > 0 }
            ltp?.let { marks[p.symbol] = it }
            // Gone at Zerodha without the arm selling it: Zerodha's MIS square-off, or closed by hand.
            if (held <= 0) {
                cur.stopOrderId?.let { runCatching { Broker.cancel(it) } }
                val backstop = cur.day.isBefore(t.toLocalDate()) || !t.toLocalTime().isBefore(LocalTime.of(15, 15))
                val px = ltp ?: cur.entry
                b.positions[i] = cur.copy(exit = px, exitTime = t, why = if (backstop) "backstop_square_off" else "closed_by_you",
                    stopOrderId = null, charges = cur.charges + kiteCharge("SELL", px, cur.qty))
                continue
            }
            if (ltp == null) continue
            // The price ran through the stop's limit without it filling: sell at market instead.
            val tick = runCatching { Broker.spec("NFO", sym).tickSize }.getOrDefault(0.05)
            val runThrough = cur.stopTrigger?.let { ltp < stopLimit(it, tick) } == true
            val why = when {
                stopped -> "operator_stop"
                !t.toLocalTime().isBefore(OrbRules.SQUARE_OFF) -> "session_end"
                else -> OrbRules.exitReason(cur.entry, ltp, t).takeIf { it == "target" || (it == "stop" && (cur.stopOrderId == null || runThrough)) }
            } ?: continue
            b.positions[i] = exitLive(cur, sym, minOf(held, cur.qty), why)
        }
    }

    /** Take the resting stop out first, then MARKET SELL what is held; if the stop filled meanwhile, that is the exit. */
    private suspend fun exitLive(p: Position, sym: String, held: Int, why: String): Position {
        val label = armOf(p.arm).label
        p.stopOrderId?.let { id ->
            runCatching { Broker.cancel(id) }
            val st = runCatching { Broker.orderState(id) }.getOrNull()
            if (st?.status == "COMPLETE") {
                val px = st.avgPrice.takeIf { it > 0 } ?: p.stopTrigger ?: p.entry
                return p.copy(exit = px, exitTime = now(), why = "stop", stopOrderId = null, charges = p.charges + kiteCharge("SELL", px, p.qty))
            }
        }
        // Read what is held again after the stop came out: a stop that part-filled meanwhile must not turn the sell into a short.
        val still = runCatching { Broker.positionBook().net.filter { it.symbol == sym && it.exchange == "NFO" && it.product == "MIS" }.sumOf { it.qty } }
            .getOrNull() ?: return p.copy(stopOrderId = null)
        val qty = minOf(held, still)
        if (qty <= 0) return p.copy(stopOrderId = null)                          // gone already: booked on the next pass
        val spec = runCatching { Broker.spec("NFO", sym) }.getOrNull() ?: return p.copy(stopOrderId = null)
        val o = com.optionslab.engine.Kite.Order(sym, com.optionslab.engine.Kite.Side.SELL, qty, spec.lotSize, "MIS", "MARKET", null, spec.tickSize, "NFO", "iraorb")
        val bad = com.optionslab.engine.Kite.refusals(o, AppSettings.load().limits(), Broker.sentToday(), false, exit = true)
        if (bad.isNotEmpty()) {
            com.optionslab.app.work.Alerts.error("$label: the Zerodha exit was not sent (${bad.joinToString("; ")}). Close $sym in Trade.", "ORB live")
            return p.copy(stopOrderId = null)
        }
        val id = try { Broker.placeOrder(o) } catch (e: Exception) {
            runCatching { Broker.findRecent(o, emptyList()) }.getOrNull() ?: run {
                com.optionslab.app.work.Alerts.error("$label: the Zerodha exit failed (${e.message}); retrying on the next pass.", "ORB live")
                return p.copy(stopOrderId = null)
            }
        }
        Strategies.tagOwner("kite:$id", "$label · $why")
        val f = runCatching { Broker.awaitOrder(id, 15_000) }.getOrNull()
        if (f == null || f.filled <= 0) return p.copy(stopOrderId = null)          // checked again next pass against the position book
        Notifier.orderFilled(app, "SELL", f.filled, sym, f.avgPrice, "Live", label)
        return p.copy(stopOrderId = null, exit = f.avgPrice, exitTime = now(), why = why, charges = p.charges + kiteCharge("SELL", f.avgPrice, f.filled))
    }

    data class Filled(val quantity: Int, val symbol: String, val price: Double)

    /**
     * The fill of a MARKET order just placed. The paper book can accept one and leave it
     * OPEN (no fresh price): it is cancelled straight away so it can never fill later as an
     * untracked entry or a second exit. If it filled before the cancel, that fill counts.
     */
    private suspend fun filledOrCancelled(r: Paper.Result): Filled? {
        r.events.filterIsInstance<com.optionslab.engine.sandbox.SandboxEvent.Fill>().firstOrNull()?.let { return Filled(it.quantity, it.symbol, it.price) }
        val id = r.orderId ?: return null
        if (!r.ok) return null
        Paper.cancel(id)
        val o = Paper.state.orders.firstOrNull { it.orderId == id } ?: return null
        return if (o.status == "complete") Filled(o.quantity, o.symbol, o.averagePrice?.toDouble() ?: return null) else null
    }

    private fun chargesOf(orderId: String?): Double =
        Paper.state.trades.filter { it.orderId == orderId }.sumOf { it.charges.toDouble() }

    // ---- data ------------------------------------------------------------------

    /** Today's completed 5-minute BANKNIFTY bars, built from the 1-minute feed. */
    private suspend fun indexBars(t: LocalDateTime): List<Bar> =
        OrbRules.completed(fiveMinute(Net.intraday(Upstox.INDEX_KEYS.getValue(OrbRules.UNDERLYING)), t.toLocalDate(), t), t)

    /**
     * 1-minute bars folded into 5-minute bars labelled by their start. A bar still
     * missing a minute is kept out for two minutes after it ends, so a late print
     * cannot turn into a different signal once decided.
     */
    fun fiveMinute(ones: List<Upstox.Bar>, day: LocalDate, now: LocalDateTime? = null): List<Bar> =
        ones.filter { it.istDate == day }.groupBy { it.istMinute - it.istMinute % 5 }.toSortedMap().mapNotNull { (m, g) ->
            val start = day.atTime(m / 60, m % 60)
            val s = g.sortedBy { it.epochSecond }
            if (now != null && s.size < 5 && now.isBefore(start.plusMinutes(7))) return@mapNotNull null
            Bar(start, s.first().open, s.maxOf { it.high }, s.minOf { it.low }, s.last().close)
        }

    // ---- the evening replay (TODO A8) ----------------------------------------------

    /**
     * After 15:35 on a trading day: replay the day on its own bars (decide on a
     * bar's close, fill on the next bar's open) for both arms, beside what the
     * paper arms actually did, and note whether the index closed up or down for
     * the pass rule. No orders. Once a day.
     */
    @Volatile private var lastReplayTry = 0L

    suspend fun replayIfDue(): Boolean {
        // Called on every refresh: when the day's data is not in yet, try again at most every 10 minutes.
        if (System.currentTimeMillis() - lastReplayTry < 10 * 60_000) return false
        lastReplayTry = System.currentTimeMillis()
        runCatching { backfillUpDays() }
        val t = now()
        val day = t.toLocalDate()
        if (!Market.isTradingDay(day) || t.toLocalTime().isBefore(LocalTime.of(15, 35))) return false
        val legs = lock.withLock { book().let { b -> if (b.replays.containsKey(day.toString())) return false; b.legs?.takeIf { it.day == day } } }
        val index = fiveMinute(Net.intraday(Upstox.INDEX_KEYS.getValue(OrbRules.UNDERLYING)), day)
        if (index.isEmpty()) return false
        val out = JSONObject().put("up", index.last().close > index.first().open)
        if (legs != null) {
            val ce = fiveMinute(Net.intraday(legs.ce.feedKey), day).associateBy { it.start }
            val pe = fiveMinute(Net.intraday(legs.pe.feedKey), day).associateBy { it.start }
            val aligned = index.filter { it.start in ce && it.start in pe }
            for (arm in OrbRules.ARMS) {
                val trades = Replay.day(arm, aligned, aligned.map { ce.getValue(it.start) }, aligned.map { pe.getValue(it.start) })
                out.put(arm.source, JSONArray().apply {
                    trades.forEach { tr -> put(JSONObject().put("bar", tr.signalBar).put("exitBar", tr.exitBar).put("right", tr.right)
                        .put("entry", tr.entry).put("exit", tr.exit).put("why", tr.why).put("pnl", tr.points * legs.ce.lotSize)) }
                })
            }
            out.put("strike", legs.strike).put("lot", legs.ce.lotSize)
        } else out.put("note", "No strike was fixed today (the arms were not running at 09:20), so there is nothing to replay the options on.")
        lock.withLock { val b = book(); b.replays[day.toString()] = out; b.upDays[day.toString()] = out.getBoolean("up"); save(b) }
        return true
    }

    /** Up or down day (index close vs open) for past trade days the evening replay never recorded, for the pass rule. */
    private suspend fun backfillUpDays() {
        val missing = lock.withLock { book().let { b -> b.positions.map { it.day }.distinct().filter { it.isBefore(Market.today()) && !b.upDays.containsKey(it.toString()) } } }
        if (missing.isEmpty()) return
        val key = Upstox.INDEX_KEYS.getValue(OrbRules.UNDERLYING)
        val found = HashMap<String, Boolean>()
        for (d in missing.takeLast(10)) {
            val bars = runCatching { fiveMinute(Net.history(key, d, d), d) }.getOrNull().orEmpty()
            if (bars.isNotEmpty()) found[d.toString()] = bars.last().close > bars.first().open
        }
        if (found.isNotEmpty()) lock.withLock { val b = book(); b.upDays.putAll(found); save(b) }
    }

    // ---- text ------------------------------------------------------------------

    private fun hhmm(t: LocalDateTime) = "%02d:%02d".format(t.hour, t.minute)

    /** The arm's status in plain words. */
    fun describe(s: String): String = when {
        s.isEmpty() -> "Waits for the market watch."
        s == "entered" -> "Entered (paper)."
        s == "entered_live" -> "Entered at Zerodha (live)."
        s == "holding" -> "Holding a position."
        s == "waiting_for_opening_range" -> "Waiting for the opening range (09:15-10:00)."
        s == "inside_range" -> "Waiting for a breakout: the last bar closed inside the range."
        s == "not_a_fresh_break" -> "Last bar continued an earlier break; ORB Fresh waits for a fresh one."
        s == "cooling_down_after_exit" -> "Just exited; may re-enter from the next bar."
        s == "no_decision_bar" -> "No decision bars now (entries only 10:05-14:25)."
        s == "flat_after_square_off" -> "Done for the day (square-off 15:10)."
        s == "stopped_for_today" -> "Stopped for today by you."
        s == "awaiting_approval" -> if (liveNow()) "Breakout: waiting for your approval with PIN (live)." else "Breakout: waiting for your approval."
        s == "skipped_by_you" -> "You skipped the last signal."
        s == "no_contract" -> "The day's BANKNIFTY contracts could not be loaded."
        s == "no_index_data" -> "No BANKNIFTY bars yet."
        s.startsWith("guard_refused: ") -> "Refused by Bot settings: " + s.removePrefix("guard_refused: ")
        s.startsWith("refused: ") -> "Refused: " + s.removePrefix("refused: ")
        s.startsWith("order_refused: ") -> "The order was refused: " + s.removePrefix("order_refused: ")
        s.startsWith("error: ") -> "Could not check: " + s.removePrefix("error: ")
        else -> s
    }

    @Synchronized fun wipe() { cache = null; if (::file.isInitialized) file.delete() }
}
