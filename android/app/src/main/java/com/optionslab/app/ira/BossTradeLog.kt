package com.optionslab.app.ira

import android.content.Context
import com.optionslab.app.data.ManagerSpecialists
import com.optionslab.app.data.SmartWorkers
import com.optionslab.app.data.TradeManagerHost
import com.optionslab.ira.BossTrades
import com.optionslab.ira.ManagerTeam
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Learning from Boss's own trades (S10; the rules are ira-core's [BossTrades]). When he places or closes a trade by hand (an
 * order labelled "Manual · <screen>" in [com.optionslab.app.data.Origins], never a bot's), [filled] is told - ONE memory
 * write on the order path: no settings read, no disk, no wait - and a small card asks, optionally, why ([prompt]: one tap
 * on a chip, dismissible, never in the way of the order). The trade is kept with its context, worked out off the order
 * path: the market's regime then, the time, the trade manager's specialists' votes on that index then, its risk to reward
 * when a bracket was set, and on the close its net from the trade book.
 *
 * Jarvis reads them as "your habits" and "your rules"; [warning] is the soft word in the order review when a rule of his
 * own record says such a trade loses (never a block). Kept in the encrypted vault (noBackupFilesDir/self/boss.vault): his
 * account's trades, never in a backup, never a key, an order id only as the paper / Zerodha id it was. Not in IraGoldAlgo.
 */
object BossTradeLog {
    private const val FILE = "boss.vault"
    /** A prompt not answered goes away by itself after this long. */
    const val PROMPT_MS = 3 * 60_000L

    @Volatile private var appContext: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    /**
     * One writer at a time: [drain] and [answer] both run on the IO pool and [com.optionslab.app.security.Vault.writeFile]
     * goes through one fixed "boss.vault.tmp", so two saves at once could interleave into a corrupt file. Never taken on the
     * order path ([filled] only queues).
     */
    private val io = Any()
    @Volatile private var loaded = false
    @Volatile private var trades: List<BossTrades.Trade> = emptyList()
    @Volatile private var rulesKept: Pair<Int, List<BossTrades.Rule>>? = null

    /** A hand fill waiting to be recorded (the order path's one write). */
    private data class Fill(val venueId: String, val symbol: String, val buy: Boolean, val live: Boolean, val atMs: Long, val entry: Double?,
                            val stop: Double?, val target: Double?, val closing: Boolean?)
    private val fills = ConcurrentLinkedQueue<Fill>()

    /** The card's question: on the entry or on the exit, for which trade. */
    data class Prompt(val tradeId: String, val symbol: String, val exit: Boolean, val atMs: Long)

    private val _prompt = MutableStateFlow<Prompt?>(null)
    val prompt: StateFlow<Prompt?> = _prompt

    fun enabled(): Boolean = com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD

    fun init(context: Context) { appContext = context.applicationContext }

    private fun file(): File? = appContext?.let { File(File(it.noBackupFilesDir, "self"), FILE) }

    fun ensureLoaded() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            val f = file()
            val read = runCatching { f?.takeIf { it.isFile }?.let { com.optionslab.app.security.Vault.readFileSteady(it) }?.toString(Charsets.UTF_8) }
            if (read.isFailure) {
                // Unreadable (a Keystore that stayed broken, a tampered file): moved aside, never overwritten. While it is still
                // in place (the move failed) nothing is saved over it - [loaded] stays false and the next read tries again.
                if (f != null && f.exists()) runCatching { com.optionslab.app.security.Vault.setAside(f) }
                if (f != null && f.exists()) return
            }
            // Trades recorded in memory before the file could be read are kept with the file's (the file's first, oldest).
            val kept = BossTrades.decode(read.getOrNull())
            trades = if (trades.isEmpty()) kept else (kept.filter { k -> trades.none { it.id == k.id } } + trades).takeLast(BossTrades.KEEP)
            loaded = true
        }
    }

    private fun save() {
        // Never over a file that was not read (a failed load): that would wipe Boss's record.
        if (!loaded) return
        val f = file() ?: return
        val text = BossTrades.encode(trades.takeLast(BossTrades.KEEP))
        runCatching { f.parentFile?.mkdirs(); com.optionslab.app.security.Vault.writeFile(f, text.toByteArray(Charsets.UTF_8)) }
    }

    // ---- the order path's hook ---------------------------------------------------------------------------------------------

    /**
     * A hand order filled ([venueId] "paper:<id>" / "kite:<id>"). [closing]: known to close (a square-off), null: worked out
     * afterwards. The order path's only cost: one queue add and one state write; the rest runs in the background.
     */
    fun filled(venueId: String, symbol: String, buy: Boolean, live: Boolean, entry: Double? = null, stop: Double? = null, target: Double? = null,
               closing: Boolean? = null) {
        if (!enabled()) return
        fills.add(Fill(venueId, symbol, buy, live, System.currentTimeMillis(), entry, stop, target, closing))
        _prompt.value = Prompt(venueId, symbol, exit = closing == true, atMs = System.currentTimeMillis())
        scope.launch { runCatching { drain() } }
    }

    /** The queued fills recorded with their context (a worker thread). */
    internal fun drain(): Unit = synchronized(io) {
        ensureLoaded()
        while (true) {
            val f = fills.poll() ?: break
            val closing = f.closing ?: closes(f)
            if (closing) closeOne(f) else openOne(f)
            if (_prompt.value?.tradeId == f.venueId) _prompt.value = _prompt.value?.copy(exit = closing)
        }
        rulesKept = null
        save()
    }

    /** Does [f] close (or cut) what was held? Paper: the paper book's position after it; live: a sell of an option bought before. */
    private fun closes(f: Fill): Boolean {
        if (!f.live) {
            val net = runCatching { com.optionslab.app.data.Paper.state.positions.filter { it.symbol == f.symbol }.sumOf { it.quantity } }.getOrNull()
            if (net != null) return if (f.buy) net <= 0 else net >= 0
        }
        return !f.buy && trades.any { it.symbol == f.symbol && it.live == f.live && !it.closed }
    }

    private fun regimeOf(symbol: String): String? = runCatching {
        val m = com.optionslab.ira.PreTrade.marketOf(symbol)?.name ?: return@runCatching null
        SmartWorkers.brain.value.regimes[m]?.state?.name?.lowercase(Locale.ENGLISH)
    }.getOrNull()

    /** The trade manager's specialists' latest votes on [symbol]'s index (from the trades it follows there now). */
    private fun votesOn(symbol: String): List<String> = runCatching {
        val m = com.optionslab.ira.PreTrade.marketOf(symbol)?.name ?: return@runCatching emptyList()
        val ids = TradeManagerHost.records.value.filter { !it.closed && it.trade.underlying.equals(m, ignoreCase = true) }.map { it.trade.tradeId }.toSet()
        ManagerSpecialists.views.value.filterKeys { it in ids }.values.flatMap { v ->
            v.votes.map { "${ManagerTeam.nameOf(it.specialist)} ${it.kind.name.lowercase(Locale.ENGLISH)} ${it.strength}" }
        }.distinct().take(8)
    }.getOrDefault(emptyList())

    private fun openOne(f: Fill) {
        val t = BossTrades.Trade(f.venueId, f.symbol, f.live, f.atMs, regime = regimeOf(f.symbol), votes = votesOn(f.symbol),
            rr = BossTrades.rr(f.entry, f.stop, f.target, f.buy))
        synchronized(lock) { trades = (trades.filter { it.id != t.id } + t).takeLast(BossTrades.KEEP) }
    }

    private fun closeOne(f: Fill) {
        val open = trades.lastOrNull { it.symbol == f.symbol && it.live == f.live && !it.closed } ?: return
        val net = runCatching {
            com.optionslab.app.data.TradeBook.trips(f.live).filter { it.symbol == f.symbol }.maxByOrNull { it.closedAt }?.net
        }.getOrNull() ?: 0.0
        synchronized(lock) { trades = trades.map { if (it.id == open.id) it.copy(closedMs = f.atMs, net = net) else it } }
        // The exit's "why?" is kept on the trade it closes.
        val cur = _prompt.value
        if (cur != null && cur.tradeId == f.venueId) _prompt.value = cur.copy(tradeId = open.id, exit = true)
    }

    // ---- Boss's answer -----------------------------------------------------------------------------------------------------

    /** Boss tapped a reason for [p] (an entry's [why] or an exit's [exitWhy]) and maybe wrote a [note]. Off the main thread. */
    fun answer(p: Prompt, why: BossTrades.Why? = null, exitWhy: BossTrades.ExitWhy? = null, note: String = "") {
        _prompt.value = null
        scope.launch {
            runCatching {
                synchronized(io) {
                    drain()
                    synchronized(lock) {
                        trades = trades.map { t ->
                            if (t.id != p.tradeId) t else t.copy(why = why ?: t.why, exitWhy = exitWhy ?: t.exitWhy,
                                note = note.trim().take(140).ifEmpty { t.note })
                        }
                    }
                    rulesKept = null
                    save()
                }
            }
        }
    }

    fun dismiss() { _prompt.value = null }

    // ---- what Jarvis reads -------------------------------------------------------------------------------------------------

    fun all(): List<BossTrades.Trade> { ensureLoaded(); return trades }

    fun rules(): List<BossTrades.Rule> {
        ensureLoaded()
        val ts = trades
        rulesKept?.takeIf { it.first == ts.hashCode() }?.let { return it.second }
        return BossTrades.rules(ts).also { rulesKept = ts.hashCode() to it }
    }

    /**
     * The soft word for the order review before a hand entry ([minute] IST now): his own record's losing rule that matches,
     * or null. A worker thread (the review reads it off the main thread); never blocks or changes the order.
     */
    fun warning(minute: Int = com.optionslab.app.data.Market.minuteNow()): String? = if (!enabled()) null else runCatching { BossTrades.warn(rules(), minute) }.getOrNull()

    fun sayHabits(): String = BossTrades.sayHabits(all())
    fun sayRules(): String = BossTrades.sayRules(rules(), all().count { it.closed })
    fun todayInsight(day: LocalDate): String? = BossTrades.todayInsight(rules(), all().filter { it.day == day })

    /** Hand fills not recorded yet (tests wait for none). */
    internal fun pending(): Int = fills.size

    /** TEST ONLY. */
    internal fun resetForTest() { synchronized(lock) { trades = emptyList() }; fills.clear(); _prompt.value = null; rulesKept = null; loaded = true }
    /** TEST ONLY: forget what is in memory, so the next read loads the file again (a new process would). */
    internal fun reloadForTest() { synchronized(lock) { trades = emptyList(); loaded = false }; rulesKept = null }
    internal fun fileForTest(): File? = file()
    internal fun setForTest(ts: List<BossTrades.Trade>) { synchronized(lock) { trades = ts }; rulesKept = null; loaded = true }
}
