package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.Vault
import com.optionslab.engine.Kite
import com.optionslab.ira.Slippage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Slippage per fill (round 4, measure only): each app-placed Zerodha order that fills - the bots', Pine's, the protections'
 * exits, a hand order - is measured against the price it meant to pay (the signal's price when its caller gave one, else
 * its limit, its stop's trigger, or the stream's last price when it was sent), and each paper fill against the price the
 * sandbox filled it from (the paper book's modelled slippage, kept apart as paper). The figures are [Slippage]'s.
 *
 * The order path only notes the order in memory ([sent], a map put) and its end ([ended], a map remove): nothing there
 * reads the settings vault, the disk or the network, and nothing waits. The fill is kept on this object's own background
 * scope a moment later (the strategy's label is read then): in memory, and appended to a small file of encrypted lines
 * (one line a fill, never rewritten except to trim it to [Slippage.KEEP]). It never changes, delays or places an order.
 */
object FillSlippage {
    private const val FILE = "slippage.log"
    /** Orders noted and not yet ended, at most (the oldest dropped). */
    private const val MAX_PENDING = 300
    /** A stream price older than this is not the price at the decision. */
    private const val TICK_FRESH_MS = 5_000L
    /** The wait before a fill is kept: the caller labels its order right after Zerodha's answer. */
    internal var settleMs = 1_500L

    @Volatile private var dir: File? = null
    fun init(context: Context) { dir = context.applicationContext.noBackupFilesDir }

    private class Pending(val symbol: String, val side: String, val exit: Boolean, val arm: String?, val tag: String,
                          val intended: Double, val basis: Slippage.Basis, val tick: Double)

    private val pending = java.util.LinkedHashMap<String, Pending>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fileLock = Mutex()
    private val memLock = Any()
    private var kept: ArrayList<Slippage.Fill>? = null

    /**
     * A live order Zerodha accepted as [id] ([Broker]'s send): what it meant to pay - [intended] when the caller knew it,
     * else the order's limit or trigger, else the stream's fresh last price. Memory only; never throws.
     */
    fun sent(id: String, o: Kite.Order, exit: Boolean, arm: String?, intended: Double?) {
        try {
            val (px, basis) = when {
                intended != null && intended > 0 && intended.isFinite() -> intended to Slippage.Basis.SIGNAL
                o.orderType == "LIMIT" && o.price != null -> o.price!! to Slippage.Basis.LIMIT
                o.hasTrigger && o.triggerPrice != null -> o.triggerPrice!! to Slippage.Basis.TRIGGER
                else -> (streamLast(o) ?: return) to Slippage.Basis.LTP
            }
            val p = Pending(o.tradingSymbol, o.side.name, exit, arm, o.tag, px, basis, o.tickSize)
            synchronized(pending) {
                pending[id] = p
                while (pending.size > MAX_PENDING) pending.remove(pending.keys.first())
            }
        } catch (_: Throwable) {
        }
        // The live recording's snapshot of the market as the order went (a queue add; built and written in the background).
        try {
            LiveRecorder.order(o, exit, arm, intended, System.currentTimeMillis())
        } catch (_: Throwable) {
        }
    }

    private fun streamLast(o: Kite.Order): Double? {
        val token = Broker.tokenKnown("${o.exchange}:${o.tradingSymbol}") ?: return null
        val seen = KiteStream.seen(token) ?: return null
        if (System.currentTimeMillis() - seen.at > TICK_FRESH_MS) return null
        return seen.tick.last.takeIf { it > 0 }
    }

    /** Is order [id] noted and not yet ended? (A map look, for the order book's read to skip work.) */
    fun waiting(id: String): Boolean = synchronized(pending) { id in pending }

    fun anyWaiting(): Boolean = synchronized(pending) { pending.isNotEmpty() }

    /**
     * Order [id] ended at Zerodha ([status] terminal) having filled [filled] at [avg] on average: measured and kept in the
     * background. Anything else (still working, unknown, not noted) does nothing. Memory only on the caller's thread.
     */
    fun ended(id: String, status: String, avg: Double, filled: Int) {
        if (status !in com.optionslab.ira.OrderUpdate.TERMINAL) return
        val p = synchronized(pending) { pending.remove(id) } ?: return
        if (filled <= 0 || !(avg > 0)) return
        val at = System.currentTimeMillis()
        runCatching { LiveRecorder.fill(p.symbol, p.side, p.exit, filled, avg, p.intended, paper = false, arm = p.arm ?: p.tag) }
        val gen = erased
        scope.launch {
            delay(settleMs)
            val owner = runCatching { Strategies.owners()["kite:$id"] }.getOrNull()
            val day = runCatching { Market.today().toString() }.getOrDefault(java.time.LocalDate.now().toString())
            Slippage.Fill.of(day, at, Slippage.strategyOf(p.tag, p.arm, owner), p.symbol, p.side,
                if (p.exit) Slippage.Leg.EXIT else Slippage.Leg.ENTRY, filled, p.intended, avg, p.tick, paper = false, basis = p.basis)
                ?.let { keep(it, gen) }
        }
    }

    /**
     * A paper fill: the sandbox filled [qty] of [symbol] ([side]) at [avg] from [intended] (its limit, or the price it read).
     * Kept in the background as paper (the sandbox's modelled slippage, never mixed with Zerodha's).
     */
    fun paper(symbol: String, side: String, entry: Boolean, qty: Int, intended: Double?, avg: Double, limit: Boolean,
              basis: Slippage.Basis = if (limit) Slippage.Basis.LIMIT else Slippage.Basis.LTP) {
        if (intended == null) return
        val at = System.currentTimeMillis()
        val gen = erased
        scope.launch {
            val day = runCatching { Market.today().toString() }.getOrDefault(java.time.LocalDate.now().toString())
            Slippage.Fill.of(day, at, "Paper", symbol, side, if (entry) Slippage.Leg.ENTRY else Slippage.Leg.EXIT, qty, intended, avg, 0.05,
                paper = true, basis = basis)?.let { keep(it, gen) }
        }
    }

    /**
     * 25 Oct (app audit: "no fills measured yet" in every diagnostics): a RESTING paper order that filled on a pass ([Paper.tick],
     * the sweeper) - a stop measured against its trigger (what a stop-loss really costs past its level), a limit against its
     * price. A stop is an exit; a resting limit BUY an entry, a SELL an exit. A market order is measured where it is placed, not
     * here. Memory and a background write only.
     */
    fun resting(f: com.optionslab.engine.sandbox.SandboxEvent.Fill, o: com.optionslab.engine.sandbox.Order?) {
        val o2 = o ?: return
        val type = o2.priceType.uppercase(java.util.Locale.ENGLISH)
        val (intended, basis) = when {
            type == "SL-M" || type == "SL" -> o2.triggerPrice?.toDouble() to Slippage.Basis.TRIGGER
            type == "LIMIT" -> o2.price?.toDouble() to Slippage.Basis.LIMIT
            else -> return
        }
        val entry = type == "LIMIT" && f.action.equals("BUY", true)
        paper(f.symbol, f.action, entry, f.quantity, intended, f.price, limit = basis == Slippage.Basis.LIMIT, basis = basis)
    }

    /**
     * In memory, then one encrypted line appended to the file (trimmed to the newest [Slippage.KEEP] when it grows past twice that).
     * 26 Oct: [gen] is [erased] when the fill was seen: one seen before an erase ([wipe]) is neither kept nor written after it (the
     * file is touched under [memLock], as the wipe deletes it).
     */
    private suspend fun keep(f: Slippage.Fill, gen: Int) {
        val size = synchronized(memLock) {
            if (gen != erased) return
            val k = loadLocked()
            k += f
            while (k.size > Slippage.KEEP) k.removeAt(0)
            k.size
        }
        val d = dir ?: return
        fileLock.withLock {
            runCatching {
                val file = File(d, FILE)
                val line = android.util.Base64.encodeToString(Vault.encrypt(Slippage.encode(f).toByteArray(Charsets.UTF_8)), android.util.Base64.NO_WRAP)
                synchronized(memLock) {
                    if (gen != erased) return@runCatching
                    java.io.FileOutputStream(file, true).use { it.write((line + "\n").toByteArray(Charsets.US_ASCII)) }
                }
                appended += 1
                if (appended > 2 * Slippage.KEEP) {
                    // Rewritten once in a while from memory (already the newest KEEP): the file never grows without end.
                    appended = size
                    val all = synchronized(memLock) { ArrayList(kept.orEmpty()) }
                    val tmp = File(d, "$FILE.tmp")
                    val text = all.joinToString("") { x ->
                        android.util.Base64.encodeToString(Vault.encrypt(Slippage.encode(x).toByteArray(Charsets.UTF_8)), android.util.Base64.NO_WRAP) + "\n"
                    }
                    synchronized(memLock) {
                        if (gen != erased) return@runCatching
                        tmp.writeText(text, Charsets.US_ASCII)
                        if (!tmp.renameTo(file)) tmp.delete()
                    }
                }
            }
        }
    }

    /** The file's lines (as read, then appended; it is trimmed to the newest [Slippage.KEEP] when this passes twice that). */
    @Volatile private var appended = 0

    /** The kept fills (read from the file once). Under [memLock]; called off the order path only. */
    private fun loadLocked(): ArrayList<Slippage.Fill> {
        kept?.let { return it }
        val out = ArrayList<Slippage.Fill>()
        runCatching {
            val file = dir?.let { File(it, FILE) }
            if (file != null && file.isFile) {
                val lines = file.readLines(Charsets.US_ASCII)
                appended = lines.size
                for (l in lines) {
                    if (l.isBlank()) continue
                    // A line that cannot be read (damaged, or from a key since replaced) is skipped.
                    runCatching { Slippage.decode(String(Vault.decrypt(android.util.Base64.decode(l, android.util.Base64.NO_WRAP)), Charsets.UTF_8)) }
                        .getOrNull()?.let { out += it }
                }
            }
        }
        while (out.size > Slippage.KEEP) out.removeAt(0)
        kept = out
        return out
    }

    /** The kept fills, oldest first. Off the main thread and the order path (the first call reads the file). */
    fun fills(): List<Slippage.Fill> = synchronized(memLock) { ArrayList(loadLocked()) }

    private fun today(): String = runCatching { Market.today().toString() }.getOrDefault(java.time.LocalDate.now().toString())

    /** The order-speed section's lines. */
    fun cardLines(): List<String> = Slippage.lines(fills(), today())

    /** The diagnostics' and the health export's line. */
    fun diagLine(): String = Slippage.diagLine(fills(), today())

    /** Jarvis's answer ("what's my slippage", "slippage on ORB"). */
    fun answer(ask: Slippage.Ask): String = Slippage.answer(fills(), ask, today())

    /** 26 Oct: bumped by [wipe] (under [memLock]): a fill seen before it is never kept or written after it ([keep]). */
    @Volatile private var erased = 0

    /**
     * Erase everything: nothing noted, nothing kept in memory; the file removed. 26 Oct: its temp file too, deleted under
     * [memLock] (as [keep] writes), so a fill on its way never brings the file back.
     */
    fun wipe() {
        synchronized(pending) { pending.clear() }
        synchronized(memLock) {
            erased++
            filesForErase().forEach { runCatching { it.delete() } }
            kept = null
        }
        appended = 0
    }

    /** 26 Oct: whether any fill (noted or kept) is still in memory (the erase's tests). Memory only. */
    internal fun holdsForErase(): Boolean = synchronized(pending) { pending.isNotEmpty() } || synchronized(memLock) { !kept.isNullOrEmpty() }

    /** 26 Oct: the fills' file and its temp file; empty before [init]. */
    internal fun filesForErase(): List<File> = dir?.let { d -> listOf(File(d, FILE), File(d, "$FILE.tmp")) }.orEmpty()

    /** TEST ONLY: the fills in memory forgotten (the next read is the file's), as after a restart. */
    internal fun forgetMemoryForTest() { synchronized(memLock) { kept = null } }

    /** TEST ONLY: the file's text as written. */
    internal fun fileTextForTest(): String = dir?.let { File(it, FILE) }?.takeIf { it.isFile }?.readText().orEmpty()

    /** TEST ONLY: [wipe], and the normal wait before a fill is kept. */
    internal fun resetForTest() { wipe(); settleMs = 1_500L }
}
