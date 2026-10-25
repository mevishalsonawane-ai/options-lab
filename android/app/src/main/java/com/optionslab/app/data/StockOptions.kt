package com.optionslab.app.data

import android.content.Context
import com.optionslab.engine.Right
import com.optionslab.engine.Upstox
import java.io.File
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 25 Oct 2026 (Boss: "I will like to add normal stocks to buy/sell on the basis of this alerts"): today's NSE F&O stock options
 * for ntfy alerts' stock signals - the two nearest expiries of each stock and each stock's cash key (for its price). Downloaded
 * once a day in the background ([refreshInBackground]: only while ntfy alerts is on with stock options allowed), written to one
 * small file ([FILE], a tab-separated line a contract), and read for one stock at a time when its signal comes ([listed], [eqKey]) - never a
 * download in the arm's pass, never on the main thread or the order path.
 */
object StockOptions {
    const val FILE = "contracts_stocks.txt"
    @Volatile private var file: File? = null
    private val refreshing = AtomicBoolean(false)
    /** One stock's list as read today (the file is small, but a signal's pass reads only its own stock). */
    private val mem = ConcurrentHashMap<String, Pair<LocalDate, List<Upstox.Contract>>>()
    @Volatile private var keys: Pair<LocalDate, Map<String, String>>? = null

    /** TEST SEAM (JVM tests only): the download (a fixture instead of the network). */
    @Volatile internal var testFetch: (() -> Pair<List<Upstox.Contract>, Map<String, String>>)? = null

    fun init(context: Context) { file = File(context.applicationContext.filesDir, FILE) }

    private fun today(): LocalDate = runCatching { Market.today() }.getOrElse { LocalDate.now() }

    /** The saved list's day, kept in memory once read (the refresh sets it): the pass never reads the file for it again. */
    @Volatile private var dayKept: Pair<Boolean, LocalDate?> = false to null

    fun savedDay(): LocalDate? {
        if (dayKept.first) return dayKept.second
        return readDay().also { dayKept = true to it }
    }

    private fun readDay(): LocalDate? = runCatching { file?.takeIf { it.exists() }?.bufferedReader()?.use { it.readLine() }?.removePrefix("day=")?.let { LocalDate.parse(it) } }.getOrNull()

    /** Today's list is saved. */
    fun ready(): Boolean = savedDay() == today()

    /** Fetch today's list on a background thread when it is not saved yet (one at a time). */
    fun refreshInBackground() {
        if (ready() || !refreshing.compareAndSet(false, true)) return
        Thread({ try { runCatching { refreshNow() }.onFailure { e -> runCatching { Diag.record("ntfy", "stock options list: ${e.javaClass.simpleName}") } } }
            finally { refreshing.set(false) } }, "stock-options").apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }
    }

    /** The download and the file (a worker thread). */
    internal fun refreshNow() {
        val f = file ?: return
        val (opts, eq) = (testFetch ?: { Net.fetchStockMaster() })()
        val tmp = File(f.parentFile, "$FILE.tmp")
        tmp.bufferedWriter().use { w ->
            w.write("day=${today()}\n")
            for ((u, k) in eq) w.write("E\t$u\t$k\n")
            for (c in opts) w.write(listOf("C", c.underlying, c.expiry, c.strike, c.right.name, c.lotSize, c.instrumentKey, c.tradingSymbol.replace('\t', ' ')).joinToString("\t") + "\n")
        }
        if (!tmp.renameTo(f)) { f.writeText(tmp.readText()); tmp.delete() }
        mem.clear(); keys = null; dayKept = true to today()
        runCatching { Diag.record("ntfy", "stock options list saved: ${eq.size} stocks, ${opts.size} options") }
    }

    /** [u]'s listed options today (empty: not saved today, or no such F&O stock). */
    fun listed(u: String): List<Upstox.Contract> {
        val d = today()
        mem[u]?.takeIf { it.first == d }?.let { return it.second }
        val f = file?.takeIf { it.exists() } ?: return emptyList()
        if (savedDay() != d) return emptyList()
        val out = ArrayList<Upstox.Contract>()
        f.bufferedReader().useLines { lines ->
            for (l in lines) {
                if (!l.startsWith("C\t$u\t")) continue
                val x = l.split('\t')
                if (x.size < 8) continue
                runCatching { out += Upstox.Contract(x[1], LocalDate.parse(x[2]), x[3].toDouble(), Right.valueOf(x[4]), x[5].toInt(), x[6], x[7]) }
            }
        }
        mem[u] = d to out
        return out
    }

    /** [u]'s cash key (its price), or null. */
    fun eqKey(u: String): String? {
        val d = today()
        keys?.takeIf { it.first == d }?.let { return it.second[u] }
        val f = file?.takeIf { it.exists() } ?: return null
        if (savedDay() != d) return null
        val m = HashMap<String, String>()
        f.bufferedReader().useLines { lines -> for (l in lines) if (l.startsWith("E\t")) l.split('\t').takeIf { it.size >= 3 }?.let { m[it[1]] = it[2] } }
        keys = d to m
        return m[u]
    }

    /** For tests. */
    internal fun wipe() { mem.clear(); keys = null; testFetch = null; file?.delete(); dayKept = false to null }
}
