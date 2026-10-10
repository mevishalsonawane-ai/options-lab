package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.SecurePrefs
import com.optionslab.ira.FlowShadow
import com.optionslab.ira.OrderFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/**
 * The order flow beside every strategy that takes a direction (Boss, 9 Oct 2026; [FlowShadow], [OrderFlow]): each has its
 * own mode - SHADOW by default (the flow at each signal is logged, nothing changes), CONFIRM (an entry the flow does not
 * agree with at the decision is SKIPPED), OFF.
 *
 * What the flow can do is one thing only: [check] may answer "skip" for an entry the strategy has already decided on. It
 * never places, enlarges, reverses or exits anything, never flips a direction, and an exit never asks it. A skip on a live
 * entry only lowers risk; even so, turning CONFIRM on for a strategy that can reach Zerodha while the app is in Live takes
 * Boss's PIN or fingerprint ([set]). With no live read, an unreliable feed or a trap window CONFIRM has no opinion: the entry
 * goes ahead as the strategy decided, logged "flow_no_opinion" (the trap guard, [com.optionslab.ira.TrapGuard]).
 *
 * The log (one line an event, [FlowShadow]'s format) is a plain append-only file under noBackupFilesDir/orderflow, written
 * at most every 30 seconds from memory - never per tick, never through the Keystore. A paper entry's order id is kept and
 * its result read from the paper book later ([settle]); the underlying future's move 15 and 30 minutes after each signal
 * comes from the live flow ([onReads]).
 */
object FlowGate {
    private const val K_MODES = "flow.modes"
    private const val MAX_BYTES = 4L * 1024 * 1024

    @Volatile private var appContext: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    fun init(context: Context) { appContext = context.applicationContext }

    private fun dir(): File? = appContext?.let { File(it.noBackupFilesDir, "orderflow") }
    private fun file(): File? = dir()?.let { File(it, "shadow.log") }
    private fun oldFile(): File? = dir()?.let { File(it, "shadow.old.log") }

    /** The log's files (the older first), for the export. */
    fun files(): List<File> = listOfNotNull(oldFile(), file())

    // ---- the modes ------------------------------------------------------------------------------------------------------

    @Volatile private var cached: Map<String, FlowShadow.Setting>? = null
    private val _modes = MutableStateFlow<Map<String, FlowShadow.Setting>>(emptyMap())
    /** Every strategy's setting that is not the default (the Order flow section collects it). */
    val modes: StateFlow<Map<String, FlowShadow.Setting>> = _modes

    private fun all(): Map<String, FlowShadow.Setting> = cached ?: FlowShadow.decode(
        runCatching { SecurePrefs.getString(K_MODES) }.getOrNull()).also { cached = it; _modes.value = it }

    /** [key]'s mode and threshold (SHADOW at 55 unless Boss set it). */
    fun setting(key: String): FlowShadow.Setting = FlowShadow.setting(all(), key)

    private fun liveNow(): Boolean = runCatching { AppSettings.load().let { it.live && it.allowRealOrders } }.getOrDefault(false)

    /** Turning on CONFIRM for a strategy that can reach Zerodha, in Live: the screen asks for the PIN first. */
    fun needsPin(key: String, mode: OrderFlow.Mode): Boolean {
        val st = FlowShadow.strategy(key) ?: return false
        return setting(key).mode != OrderFlow.Mode.CONFIRM && OrderFlow.needsPin(mode, liveNow(), st.liveCapable)
    }

    /** What the refusal says when the PIN was not given. */
    const val PIN_NEEDED = "The app is in Live: confirm with your PIN or fingerprint to let the order flow skip this strategy's live entries."

    /**
     * [key]'s mode and threshold set to [mode] and [threshold] (kept within 50-80). CONFIRM on a strategy that can reach
     * Zerodha while in Live needs [pinConfirmed]. Returns what to say. One settings write a change, never more.
     */
    fun set(key: String, mode: OrderFlow.Mode, threshold: Int = setting(key).threshold, pinConfirmed: Boolean = false): String {
        val st = FlowShadow.strategy(key) ?: return "No such strategy."
        if (needsPin(key, mode) && !pinConfirmed) return PIN_NEEDED
        val th = threshold.coerceIn(OrderFlow.MIN_THRESHOLD, OrderFlow.MAX_THRESHOLD)
        synchronized(lock) {
            val m = all().toMutableMap()
            val next = FlowShadow.Setting(mode, th)
            if (next == FlowShadow.Setting(FlowShadow.DEFAULT)) m.remove(key) else m[key] = next
            cached = m; _modes.value = m
            runCatching { SecurePrefs.putAllSoon(mapOf(K_MODES to FlowShadow.encode(m))) }
        }
        runCatching { Diag.record("flow", "${st.label}: order flow ${mode.name}" + if (mode == OrderFlow.Mode.CONFIRM) " at $th" else "") }
        return when (mode) {
            OrderFlow.Mode.OFF -> "${st.label}: order flow off."
            OrderFlow.Mode.SHADOW -> "${st.label}: the order flow is logged beside each signal; nothing changes."
            OrderFlow.Mode.CONFIRM -> "${st.label}: an entry is skipped unless the order flow agrees (${th} or more). It only ever skips; " +
                "unproven, it may also skip winners."
        }
    }

    // ---- the gate -------------------------------------------------------------------------------------------------------

    /** One signal's gate: its id (for the later lines), whether to skip it, and the flow's agreement. */
    data class Ticket(val id: String, val skip: Boolean, val agreement: OrderFlow.Agreement, val key: String)

    /** The verdict a skipped entry returns and is logged with. */
    const val SKIPPED = FlowShadow.SKIPPED

    private val seen = HashSet<String>()
    private val orders = LinkedHashMap<String, Pair<String, Long>>()     // id -> paper order id, when
    private data class Move(val id: String, val underlying: String, val side: Int, val mid: Double, val sec: Long, var m15: Double? = null)
    private val moves = ArrayList<Move>()
    private val buf = StringBuilder()
    @Volatile private var flushing = false
    @Volatile private var loaded = false

    /**
     * Strategy [key] decided an entry of direction [side] (+1 a call or a future bought, −1 a put or a future sold) on
     * [underlying] at its own time [at] ([what]: the contract, when one signal can buy two), [live] when it would go to
     * Zerodha. Logs the flow at this moment (SHADOW, CONFIRM), and says whether CONFIRM skips it. Never throws; an error is
     * never a skip.
     */
    fun check(key: String, underlying: String, side: Int, live: Boolean, at: String, what: String = "", decide: Boolean = true,
              contract: Paper.Contract? = null): Ticket {
        val id = FlowShadow.id(key, at, what)
        return runCatching {
            val s = setting(key)
            if (s.mode == OrderFlow.Mode.OFF || com.optionslab.app.BuildConfig.GOLD) return Ticket(id, false, OrderFlow.Agreement.UNKNOWN, key)
            val now = System.currentTimeMillis()
            val r = OrderFlowLive.readNow(underlying, now)
            val a = OrderFlow.agreement(r, side, s.threshold, (testNowSec ?: (now / 1000)))
            // [decide] false: the signal waits for Boss's approval - logged now, decided (and maybe skipped) at the approval.
            val skip = decide && OrderFlow.skips(s.mode, a)
            loadOnce()
            synchronized(lock) {
                if (seen.add(id)) {
                    append(FlowShadow.signal(id, now / 1000, key, underlying, side, live, s.mode, s.threshold, a, skip, r, bookOf(contract),
                        contextOf(underlying, r, now)))
                    r?.takeIf { it.mid > 0 }?.let { moves += Move(id, underlying, side, it.mid, now / 1000) }
                }
                if (skip) append(FlowShadow.verdict(id, SKIPPED))
            }
            // CONFIRM with no opinion (no read, an unreliable feed, a time trap): the trade follows the strategy, logged so.
            if (decide && s.mode == OrderFlow.Mode.CONFIRM && a == OrderFlow.Agreement.UNKNOWN) runCatching {
                val flags = r?.flags.orEmpty()
                val why = when {
                    r == null -> "no live flow"
                    com.optionslab.ira.TrapGuard.Trap.UNRELIABLE in flags -> "flow unreliable"
                    com.optionslab.ira.TrapGuard.Trap.TIME_WINDOW in flags -> "time window: " + (r?.timeWhy ?: "trap")
                    else -> "flow not ready"
                }
                synchronized(lock) { append(FlowShadow.verdict(id, "flow_no_opinion: $why")) }
            }
            if (skip) runCatching {
                val label = FlowShadow.strategy(key)?.label ?: key
                Diag.record("flow", "$label: skipped by flow (${if (live) "live" else "paper"} ${if (side > 0) "bullish" else "bearish"} entry on $underlying; " +
                    "${OrderFlow.word(r)}, needs ${s.threshold}" + (r?.takeIf { it.flags.isNotEmpty() }?.let { "; trap guard: ${OrderFlow.trapWords(it)}" } ?: "") + ")")
            }
            Ticket(id, skip, a, key)
        }.getOrElse { Ticket(id, false, OrderFlow.Agreement.UNKNOWN, key) }
    }

    /** [check] off the caller's thread (it may read the log once after a restart): for the strategies' suspend paths. */
    suspend fun gate(key: String, underlying: String, side: Int, live: Boolean, at: String, what: String = "", decide: Boolean = true,
                     contract: Paper.Contract? = null): Ticket = kotlinx.coroutines.withContext(Dispatchers.IO) {
        // A CONFIRM decision on a closed candle waits until 3 s after the minute for late packets (trades arrive 1-2 s late,
        // p95 3-6 s: HUNT R8). SHADOW and OFF never wait: they change nothing, not even the timing.
        if (decide && setting(key).mode == OrderFlow.Mode.CONFIRM && testNowSec == null) {
            val into = System.currentTimeMillis() % 60_000L
            if (into < com.optionslab.ira.TrapGuard.DECIDE_WAIT_MS) delay(com.optionslab.ira.TrapGuard.DECIDE_WAIT_MS - into)
        }
        check(key, underlying, side, live, at, what, decide, contract)
    }

    /**
     * The auction and the gamma regime at the signal, for the log only ([FlowShadow.Context]): the future's profile, regime,
     * delta, VWAP and TPO from the flow, the read's absorption note, the index's gamma regime under Boss's convention. Memory
     * only; null on any error (the signal is still logged).
     */
    private fun contextOf(underlying: String, r: OrderFlow.Read?, now: Long): FlowShadow.Context? = runCatching {
        val a = OrderFlowLive.auctionNow(underlying, now)
        val price = r?.mid?.takeIf { it > 0 } ?: a?.last
        FlowShadow.Context(price, a, r, GammaLive.state.value[underlying], GammaLive.convention.value,
            price?.let { p -> runCatching { OrderFlowLive.basis(underlying, p) }.getOrNull() })
    }.getOrNull()

    /** The traded contract's 5-level book from the stream at this moment, for the log ("" when the stream has none fresh). */
    private fun bookOf(c: Paper.Contract?): String = runCatching {
        if (c == null) return ""
        val token = if (c.isMcx) McxMarket.find(c.symbol)?.token
        else Broker.cachedInstruments()?.firstOrNull {
            it.name == c.underlying && it.expiry == c.expiry && it.right == c.right && kotlin.math.abs(it.strike - c.strike) < 1e-6
        }?.token
        val d = token?.let { KiteStream.tick(it, 5_000)?.depth } ?: return ""
        OrderFlow.bookText(d)
    }.getOrDefault("")

    /** What became of [t]'s signal ("entered", a refusal); logged once it is known. */
    fun outcome(t: Ticket?, verdict: String) {
        if (t == null || verdict == SKIPPED || setting(t.key).mode == OrderFlow.Mode.OFF) return
        runCatching { synchronized(lock) { append(FlowShadow.verdict(t.id, verdict)) } }
    }

    /** [t]'s entry filled on paper as order [orderId]: its result is read from the paper book when it closes ([settle]). */
    fun taken(t: Ticket?, orderId: String?) {
        if (t == null || orderId == null || setting(t.key).mode == OrderFlow.Mode.OFF) return
        runCatching {
            synchronized(lock) {
                append(FlowShadow.verdict(t.id, "entered"))
                append(FlowShadow.order(t.id, orderId))
                orders[t.id] = orderId to System.currentTimeMillis()
            }
        }
    }

    // ---- results ----------------------------------------------------------------------------------------------------------

    /** Once a second from the flow: each signal's future move 15 and 30 minutes on, in its direction. */
    fun onReads(reads: Map<String, OrderFlow.Read>, now: Long) {
        val sec = now / 1000
        synchronized(lock) {
            val iter = moves.iterator()
            while (iter.hasNext()) {
                val m = iter.next()
                val age = sec - m.sec
                val r = reads[m.underlying]?.takeIf { rd -> sec - rd.atSec <= OrderFlow.STALE_SEC }
                if (m.m15 == null && age >= 900 && r != null) m.m15 = m.side * (r.mid - m.mid)
                if (age >= 1800 && r != null) { append(FlowShadow.move(m.id, m.m15, m.side * (r.mid - m.mid))); iter.remove() }
                else if (age >= 2700) { append(FlowShadow.move(m.id, m.m15, null)); iter.remove() }
            }
        }
    }

    /**
     * The paper entries' results from the paper book (every 30 s off the main thread): an entry order's fills, then the
     * opposite fills of the same contract after it until its quantity is closed; net after both legs' charges.
     */
    fun settle() {
        loadOnce()
        val open = synchronized(lock) { HashMap(orders) }
        if (open.isEmpty()) { flushSoon(); return }
        val trades = runCatching { Paper.state.trades }.getOrNull() ?: return
        val now = System.currentTimeMillis()
        for ((id, v) in open) {
            val (orderId, at) = v
            val net = runCatching { netOf(trades, orderId) }.getOrNull()
            synchronized(lock) {
                if (net != null) { append(FlowShadow.result(id, net)); orders.remove(id) }
                else if (now - at > 4 * 86_400_000L) orders.remove(id)
            }
        }
        flushSoon()
    }

    /** The round trip's net of the paper entry [orderId] in [trades], or null while it is still open. */
    internal fun netOf(trades: List<com.optionslab.engine.sandbox.Trade>, orderId: String): Double? {
        val entry = trades.filter { it.orderId == orderId }
        if (entry.isEmpty()) return null
        val sym = entry.first().symbol
        val buy = entry.first().action.equals("BUY", true)
        val qty = entry.sumOf { it.quantity }
        val from = entry.maxOf { it.timestamp }
        var cash = -entry.sumOf { it.price.toDouble() * it.quantity } * (if (buy) 1 else -1)
        var charges = entry.sumOf { it.charges.toDouble() }
        var left = qty
        for (t in trades.filter { it.symbol == sym && it.orderId != orderId && !it.timestamp.isBefore(from) && it.action.equals("BUY", true) != buy }
            .sortedBy { it.timestamp }) {
            if (left <= 0) break
            val q = minOf(left, t.quantity)
            cash += t.price.toDouble() * q * (if (buy) 1 else -1)
            charges += t.charges.toDouble() * q / t.quantity.coerceAtLeast(1)
            left -= q
        }
        return if (left > 0) null else cash - charges
    }

    // ---- the file ---------------------------------------------------------------------------------------------------------

    private fun append(line: String) { buf.append(line).append('\n'); flushSoon() }

    /** Written at most every 30 s (or at once past 64 KB): never per tick. */
    private fun flushSoon() {
        if (buf.isEmpty() || flushing) return
        flushing = true
        scope.launch {
            delay(if (buf.length > 65_536) 0 else 30_000)
            runCatching { flush() }
            flushing = false
        }
    }

    /** Writes the buffered lines (append-only; over 4 MB the file becomes the old one and a new one starts). */
    fun flush() {
        val text = synchronized(lock) { buf.toString().also { buf.setLength(0) } }
        if (text.isEmpty()) return
        val f = file() ?: return
        synchronized(this) {
            f.parentFile?.mkdirs()
            if (f.length() > MAX_BYTES) { oldFile()?.let { o -> o.delete(); f.renameTo(o) } }
            java.io.FileOutputStream(f, true).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        }
        recordsAt = -1
    }

    private fun lines(): Sequence<String> {
        val out = ArrayList<String>()
        synchronized(this) { for (f in files()) if (f.exists()) runCatching { out += f.readLines(Charsets.UTF_8) } }
        synchronized(lock) { if (buf.isNotEmpty()) out += buf.toString().lines() }
        return out.asSequence()
    }

    /** After a restart: the signals already logged (never logged twice) and the paper entries still waiting for a result. */
    private fun loadOnce() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            loaded = true
        }
        val sigs = runCatching { FlowShadow.parse(lines()) }.getOrDefault(emptyList())
        synchronized(lock) {
            for (s in sigs) {
                seen += s.id
                val o = s.orderId
                if (o != null && s.net == null && !s.live && System.currentTimeMillis() / 1000 - s.epochSec < 4 * 86_400) orders[s.id] = o to s.epochSec * 1000
            }
        }
    }

    // ---- the record ---------------------------------------------------------------------------------------------------------

    @Volatile private var recordsAt = -1L
    private val _records = MutableStateFlow<List<FlowShadow.Record>>(emptyList())
    /** Each strategy's with / against record (refreshed by [refreshRecords], off the main thread). */
    val records: StateFlow<List<FlowShadow.Record>> = _records

    /** Reads the log again (call off the main thread) when it changed. */
    fun refreshRecords(): List<FlowShadow.Record> {
        val size = files().sumOf { it.length() } + buf.length
        if (size == recordsAt) return _records.value
        val r = runCatching { FlowShadow.summary(FlowShadow.parse(lines())) }.getOrDefault(emptyList())
        recordsAt = size
        _records.value = r
        return r
    }

    /** Jarvis's "how is order flow helping?" (reads the log; call off the main thread). */
    fun helpAnswer(): String = FlowShadow.helpAnswer(refreshRecords())

    /** The Diagnostics line: modes set, signals logged. */
    fun diagLine(): String {
        val m = all()
        val confirm = m.filterValues { it.mode == OrderFlow.Mode.CONFIRM }.keys
        val off = m.filterValues { it.mode == OrderFlow.Mode.OFF }.keys
        return "Order flow modes: SHADOW by default" + (if (confirm.isNotEmpty()) " · CONFIRM ${confirm.joinToString(", ")}" else "") +
            (if (off.isNotEmpty()) " · OFF ${off.joinToString(", ")}" else "") +
            " · log %,d bytes".format(Locale.ENGLISH, files().sumOf { it.length() })
    }

    // ---- tests --------------------------------------------------------------------------------------------------------------

    /** TEST ONLY: the decision's clock in epoch seconds (null: the phone's). Throws unless BuildConfig.DEBUG. */
    @Volatile internal var testNowSec: Long? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test clock exists only in debug builds" }; field = v }

    /** TEST ONLY: every signal logged so far (the file and what is buffered). */
    internal fun signalsForTest(): List<FlowShadow.Signal> = FlowShadow.parse(lines())

    /** TEST ONLY: forget the modes, the log in memory and the pending results. Throws unless BuildConfig.DEBUG. */
    internal fun resetForTest() {
        check(com.optionslab.app.BuildConfig.DEBUG) { "the test reset exists only in debug builds" }
        synchronized(lock) { cached = null; _modes.value = emptyMap(); seen.clear(); orders.clear(); moves.clear(); buf.setLength(0); loaded = false }
        recordsAt = -1; _records.value = emptyList(); testNowSec = null
    }
}
