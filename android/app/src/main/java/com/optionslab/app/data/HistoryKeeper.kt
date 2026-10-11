package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.SecurePrefs
import com.optionslab.engine.Right
import com.optionslab.engine.Upstox
import com.optionslab.ira.HistoryCodec
import com.optionslab.ira.HistoryKeep
import com.optionslab.ira.HistoryVault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The history keeper on the phone (TODO S5, 11 Oct; the vault and the plan are ira-core's [HistoryVault], [HistoryKeep]).
 * After 15:45 on a trading day (and on later days for what is still missing) it keeps each day's 1-minute history in the
 * vault under noBackupFilesDir/history: the six indices incl. India VIX, the index futures with OI, the options the app
 * watched (ATM +/-5 of the two nearest expiries of each traded index), their end-of-day chain (LTP, OI, IV a strike) and
 * MCX's near-month futures (after MCX's 23:30 close).
 *
 * Sources are what the app already reads: the public candle feed the chart and the harvest use (Upstox's v3 candles) for
 * the indices, the options and MCX; Zerodha's 1-minute candles with OI for the futures, only with a session (Kite's
 * historical data where the plan permits; refused: that day's futures are given up). No new or paid source; no Dhan.
 *
 * Off the main thread and out of market hours: [kick] is a memory check (at most one look every 15 minutes) and launches
 * one run on its own low-priority thread; each run has a 4-minute budget and stops between pieces, and a piece is written
 * whole or not at all, so the next run resumes. The cap is Boss's (Bot settings, 100-1000 MB, 300 by default).
 */
object HistoryKeeper {
    private const val K_CAP = "hv.capMb"
    private const val K_TRIED = "hv.tried"
    private const val PAUSE_MS = 150L
    private const val LOOK_EVERY_MS = 15 * 60_000L

    @Volatile private var app: Context? = null
    @Volatile private var kept: HistoryVault? = null
    private val running = AtomicBoolean(false)
    @Volatile private var lookedAt = 0L
    /** The last run's words, for Bot settings and the diagnostics. */
    @Volatile var lastRun: String? = null
        private set

    private val scope by lazy {
        CoroutineScope(SupervisorJob() + Executors.newSingleThreadExecutor { r ->
            Thread(r, "history-keeper").apply { priority = Thread.MIN_PRIORITY; isDaemon = true }
        }.asCoroutineDispatcher())
    }

    fun init(context: Context) { app = context.applicationContext }

    /** The vault (null before [init]); its cap read once from the settings, off the main thread by its callers. */
    fun vault(): HistoryVault? {
        kept?.let { return it }
        val c = app ?: return null
        synchronized(this) {
            kept?.let { return it }
            val cap = runCatching { SecurePrefs.getInt(K_CAP, HistoryVault.DEFAULT_CAP_MB) }.getOrDefault(HistoryVault.DEFAULT_CAP_MB)
            return HistoryVault(File(c.noBackupFilesDir, "history"), cap).also { kept = it }
        }
    }

    /** Boss's cap (Bot settings): kept within 100-1000 MB, the oldest days dropped at once if over. Returns what to say. */
    fun setCapMb(mb: Int): String {
        val v = HistoryVault.clampMb(mb).toInt()
        runCatching { SecurePrefs.putAllSoon(mapOf(K_CAP to v)) }
        vault()?.setCapMb(v)
        runCatching { Diag.record("history", "History vault cap set to $v MB") }
        return "Market history kept on the phone: up to $v MB (the oldest days go first)."
    }

    /** "212 MB of 300 MB · 143 days (12 Mar - 10 Oct)" (reads the folder: off the main thread). */
    fun usageLine(): String = vault()?.usageLine() ?: "Not ready yet."

    // ---- the read API (the lab, the pre-flight, the manager's counterfactuals) ----------------------------------------------

    /** [symbol]'s 1-minute bars on [day] from the vault (empty when not kept). Reads a file: off the main thread. */
    fun bars(symbol: String, day: LocalDate): List<HistoryCodec.Bar> = runCatching { vault()?.bars(symbol, day) }.getOrNull().orEmpty()

    /** [underlying]'s end-of-day chain on [day], or null. */
    fun chain(underlying: String, day: LocalDate): HistoryCodec.Chain? = runCatching { vault()?.chain(underlying, day) }.getOrNull()

    // ---- the run ----------------------------------------------------------------------------------------------------------

    /**
     * A look (the brain's refresh and the evening reports call it): memory checks only until due - after 15:45 on a trading
     * day, any time on another day - and at most every 15 minutes; then one run launched on the keeper's own thread.
     */
    fun kick() {
        val now = System.currentTimeMillis()
        if (now - lookedAt in 0 until LOOK_EVERY_MS) return
        val due = runCatching { HistoryKeep.due(Market.minuteNow(), Market.isTradingDay()) }.getOrDefault(false)
        if (!due || app == null) return
        // The background budget (S18): not on a low battery, a hot phone, power saving or past tonight's CPU cap.
        if (!runCatching { com.optionslab.app.work.BackgroundBudget.allow(com.optionslab.ira.BudgetRule.Job.HISTORY) }.getOrDefault(true)) return
        if (!running.compareAndSet(false, true)) return
        lookedAt = now
        scope.launch {
            try { runOnce(now) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) {
                lastRun = "Stopped: ${e.javaClass.simpleName}"
            } finally {
                // The run's CPU counted against tonight's budget, however long it took.
                runCatching { com.optionslab.app.work.BackgroundBudget.done(com.optionslab.ira.BudgetRule.Job.HISTORY) }
                running.set(false)
            }
        }
    }

    private fun tried(): MutableSet<String> = runCatching { SecurePrefs.getString(K_TRIED).orEmpty().split(',').filter { it.isNotBlank() }.toMutableSet() }
        .getOrDefault(mutableSetOf())

    private fun markTried(set: MutableSet<String>, p: HistoryKeep.Piece) {
        set += "${p.day}|${p.family}"
        val keep = set.sortedDescending().take(200)
        runCatching { SecurePrefs.putAllSoon(mapOf(K_TRIED to keep.joinToString(","))) }
    }

    private suspend fun runOnce(startMs: Long) {
        val v = vault() ?: return
        val today = Market.today()
        val minute = Market.minuteNow()
        val days = (0L..12L).map { today.minusDays(it) }.filter { runCatching { Market.isTradingDay(it) }.getOrDefault(false) }
        val tried = tried()
        val plan = HistoryKeep.plan(today, minute, days, { f, d -> v.has(f, d) }, { f, d -> "$d|$f" in tried })
        if (plan.isEmpty()) { lastRun = "Up to date: ${v.usageLine()}"; return }
        val budget = HistoryKeep.Budget(startMs)
        var done = 0
        for (p in plan) {
            if (budget.spent(System.currentTimeMillis())) break
            // The battery and heat budget asked again between pieces (a phone that heats up or runs low mid-run stops here).
            if (!runCatching { com.optionslab.app.work.BackgroundBudget.allow(com.optionslab.ira.BudgetRule.Job.HISTORY) }.getOrDefault(true)) break
            // Already written by an earlier piece of this run (the options write their chain too).
            if (v.has(p.family, p.day)) continue
            val ok = runCatching { fetch(v, p, today) }.getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it; null }
            when (ok) {
                null -> {}                                           // a read failed: tried again on the next run
                false -> markTried(tried, p)                         // the source has nothing for it: given up
                true -> done++
            }
            delay(PAUSE_MS)
        }
        lastRun = "Kept $done piece${if (done == 1) "" else "s"} of ${plan.size}; ${v.usageLine()}"
        runCatching { Diag.record("history", lastRun!!) }
    }

    private fun bar(b: Upstox.Bar) = HistoryCodec.Bar(b.epochSecond, b.open, b.high, b.low, b.close, b.volume, b.oi)

    /** [key]'s 1-minute bars of [day] from the public feed (today's from its intraday endpoint). */
    private suspend fun publicDay(key: String, day: LocalDate, today: LocalDate): List<HistoryCodec.Bar> =
        (if (day == today) Net.intraday(key) else Net.history(key, day, day)).filter { it.istDate == day }
            .distinctBy { it.epochSecond }.sortedBy { it.epochSecond }.map { bar(it) }

    /** Reads that failed in one piece: a piece with nothing and a failed read is tried again, never given up. */
    private class Tally { var failed = 0 }

    /** [block]'s value, or null: a refusal by Zerodha (not permitted) is "nothing"; any other failure is counted in [t]. */
    private inline fun <T> tryRead(t: Tally, block: () -> T): T? = try { block() } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Broker.KiteError) { null } catch (e: Throwable) { t.failed++; null }

    /** Nothing kept: given up (false) when every read answered, else thrown so the next run tries again. */
    private fun nothing(t: Tally): Boolean = if (t.failed > 0) throw java.io.IOException("${t.failed} reads failed") else false

    /** Each piece's attempts with failed reads (this process; memory only). */
    private val partialTries = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /**
     * A piece with some reads failed is not written yet - written, it is never fetched again - but thrown so the next run
     * tries again, until [HistoryKeep.PARTIAL_TRIES] runs have tried; then what it has is kept (G9-7).
     */
    private fun partial(t: Tally, p: HistoryKeep.Piece) {
        if (t.failed <= 0) { partialTries.remove("${p.day}|${p.family}"); return }
        val n = partialTries.merge("${p.day}|${p.family}", 1) { a, b -> a + b } ?: 1
        if (!HistoryKeep.keepPartial(t.failed, n)) throw java.io.IOException("${t.failed} reads failed (try $n)")
        partialTries.remove("${p.day}|${p.family}")
    }

    /** True: written; false: the source has nothing for it; throws: a read failed (tried again). */
    private suspend fun fetch(v: HistoryVault, p: HistoryKeep.Piece, today: LocalDate): Boolean {
        val f = p.family
        val t = Tally()
        return when {
            f == HistoryVault.Family.INDEX -> {
                val out = HistoryKeep.INDICES.mapNotNull { n ->
                    val key = Upstox.INDEX_KEYS[n] ?: ChartFeed.EXTRA_INDICES[n] ?: return@mapNotNull null
                    tryRead(t) { publicDay(key, p.day, today) }?.takeIf { it.isNotEmpty() }?.let { HistoryCodec.Series(n, it) }
                }
                if (out.isEmpty()) nothing(t) else { partial(t, p); v.putBars(f, p.day, out); true }
            }
            f == HistoryVault.Family.FUTURES -> {
                if (!Broker.loggedIn) throw java.io.IOException("no Zerodha session for the futures")
                val futs = Broker.futures().filter { it.name in HistoryKeep.TRADED && !it.expiry.isBefore(p.day) }
                    .groupBy { it.name }.mapNotNull { (_, l) -> l.minByOrNull { it.expiry } }
                val out = futs.mapNotNull { fu ->
                    tryRead(t) { Broker.minuteBars(fu.token, p.day) }?.filter { it.istDate == p.day }?.takeIf { it.isNotEmpty() }
                        ?.let { HistoryCodec.Series(fu.tradingSymbol, it.map { b -> bar(b) }) }.also { delay(PAUSE_MS) }
                }
                if (out.isEmpty()) nothing(t) else { partial(t, p); v.putBars(f, p.day, out); true }
            }
            f == HistoryVault.Family.MCX -> {
                val near = McxMarket.nearFutures(runCatching { McxMarket.contracts() }.getOrDefault(McxMarket.cached()))
                    .groupBy { it.name }.mapNotNull { (_, l) -> l.filter { !it.expiry.isBefore(p.day) }.minByOrNull { it.expiry } }
                val out = near.mapNotNull { c ->
                    tryRead(t) { publicDay(c.upstoxKey, p.day, today) }?.takeIf { it.isNotEmpty() }
                        ?.let { HistoryCodec.Series(c.tradingSymbol, it) }.also { delay(PAUSE_MS) }
                }
                if (out.isEmpty()) nothing(t) else { partial(t, p); v.putBars(f, p.day, out); true }
            }
            f.startsWith("opt-") || HistoryVault.Family.isChain(f) -> options(v, f.substringAfter('-'), p.day, today)
            else -> false
        }
    }

    /**
     * [u]'s watched options on [day] (ATM +/-5 of its two nearest expiries, around the index's close) and, from the same
     * bars, its end-of-day chain: the last price, the OI and the implied volatility of each strike. Both families written.
     */
    private suspend fun options(v: HistoryVault, u: String, day: LocalDate, today: LocalDate): Boolean {
        val master = Market.cachedContracts()?.second ?: runCatching { Market.contracts() }.getOrNull() ?: throw java.io.IOException("no contract list")
        val listed = master.filter { it.underlying == u && !it.expiry.isBefore(day) }
        if (listed.isEmpty()) return false
        // A past day whose nearest expiry has since expired: today's list no longer has it (nor does the public feed), so its
        // two nearest cannot be kept - given up, said, never stored as the later ones (G9-8).
        if (HistoryKeep.missesNearest(day, today, listed.map { it.expiry })) {
            runCatching { Diag.record("history", "$u options of $day given up: its nearest expiry has expired since") }
            return false
        }
        val t = Tally()
        // The index's last bar (about 15:29): each option is priced at the same minute for its IV, not at its own 15:39 (G9-5).
        val spotBar = v.bars(u, day).lastOrNull()
            ?: (Upstox.INDEX_KEYS[u] ?: ChartFeed.EXTRA_INDICES[u])?.let { k -> tryRead(t) { publicDay(k, day, today) }?.lastOrNull() }
            ?: return nothing(t)
        val spot = spotBar.close
        val spotAt = java.time.Instant.ofEpochSecond(spotBar.epochSec).atZone(java.time.ZoneId.of("Asia/Kolkata")).toLocalDateTime()
        val expiries = listed.map { it.expiry }.distinct().sorted().take(2)
        val series = ArrayList<HistoryCodec.Series>()
        val rows = ArrayList<HistoryCodec.ChainRow>()
        for (e in expiries) {
            val ofExp = listed.filter { it.expiry == e }
            val strikes = HistoryKeep.strikes(ofExp.map { it.strike }, spot).toSet()
            for (c in ofExp.filter { it.strike in strikes && it.right != Right.IX }.sortedWith(compareBy({ it.strike }, { it.right }))) {
                val bars = tryRead(t) { publicDay(c.instrumentKey, day, today) }.orEmpty()
                delay(PAUSE_MS)
                if (bars.isEmpty()) continue
                series += HistoryCodec.Series(c.tradingSymbol, bars)
                val last = bars.last()
                val call = c.right == Right.CE
                val paired = HistoryKeep.pairedBar(bars, spotBar.epochSec)
                val iv = paired?.let { pb ->
                    runCatching { com.optionslab.ira.MarketRecord.iv(pb.close, call, spot, c.strike, spotAt, e) }.getOrNull()
                }
                rows += HistoryCodec.ChainRow(e, c.strike, call, paired?.close ?: last.close, last.oi, iv)
            }
        }
        if (series.isEmpty()) return nothing(t)
        partial(t, HistoryKeep.Piece(day, HistoryVault.Family.options(u)))
        v.putBars(HistoryVault.Family.options(u), day, series)
        val at = day.atTime(15, 30).atZone(java.time.ZoneId.of("Asia/Kolkata")).toEpochSecond()
        v.putChains(u, day, listOf(HistoryCodec.Chain(u, at, spot, rows)))
        return true
    }

    /** TEST ONLY. */
    internal fun resetForTest() { app = null; kept = null; lookedAt = 0L; lastRun = null }
}
