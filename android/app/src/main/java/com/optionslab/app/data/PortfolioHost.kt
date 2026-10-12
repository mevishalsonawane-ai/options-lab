package com.optionslab.app.data

import com.optionslab.app.security.SecurePrefs
import com.optionslab.ira.Findings
import com.optionslab.ira.PortfolioGuard
import com.optionslab.ira.PortfolioRisk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate

/**
 * Risk across the arms on the phone (TODO S12; ira-core's [PortfolioRisk] and [PortfolioGuard]).
 *
 *  - [refresh] (a worker thread, every 10 minutes from the brain's refresh, and at once on a settings change): the arms'
 *    closed paper and Zerodha trades (the trade book's round trips, each named by its arm) into the 60-day correlation
 *    matrix and each arm's stop loss; the capital and Boss's cap read here, once - then handed to the guard in memory.
 *  - [entry]: the pre-entry check every arm's NEW entry asks (paper and live, through the order flow's gate - the same place
 *    the brain's pause is asked), memory only. When correlated arms (rho above 0.6) already opened the same way today and
 *    the combined stop losses would pass the cap (2% of capital by default), the later entry is blocked for the day and a
 *    finding is posted. [doorRefusal]: the entry door's part for a live order (Broker's entry gate), memory only.
 *
 * It only blocks: it never places, sizes up, reverses or exits anything, and exits never ask it. No setting is read on the
 * order path (the config is in memory; OrderPathRound2Test's count stays 0).
 */
object PortfolioHost {
    private const val K_CAP = "g.portfolioCapPct"
    private const val K_ON = "g.portfolioGuard"
    private const val REFRESH_MS = 10 * 60_000L

    val guard = PortfolioGuard()

    data class View(val line: String = "Portfolio: not worked out yet.", val pairs: List<String> = emptyList(),
                    val capPct: Double = PortfolioRisk.CAP_PCT_DEFAULT, val on: Boolean = true, val capRs: Double = 0.0)

    private val _view = MutableStateFlow(View())
    /** What the Brain card and its sheet show. */
    val view: StateFlow<View> = _view

    @Volatile private var refreshedAt = 0L

    /** A look from the brain's worker: [refresh] at most every 10 minutes. */
    suspend fun refreshIfDue() {
        val now = System.currentTimeMillis()
        if (now - refreshedAt in 0 until REFRESH_MS) return
        refresh()
    }

    /** The records and the settings read (a worker thread), then handed to the guard. */
    suspend fun refresh() {
        val today = Market.today()
        // A read that fails (or is cancelled) keeps the last good matrix and stops (G9-3): "no owners" or "no trades" from a
        // failure would empty the guard, which then blocks nothing for ten minutes. Retried at the next look.
        val owners = readOrNull { testOwners?.invoke() ?: Strategies.owners() } ?: return
        val trades = ArrayList<PortfolioRisk.Trade>()
        for (live in listOf(false, true)) {
            val trips = readOrNull { TradeBook.trips(live) } ?: return
            for (t in trips) {
                val arm = PortfolioRisk.canonOrNull(TradeBook.ownerOf(t, owners), t.symbol) ?: continue
                trades += PortfolioRisk.Trade(arm, t.closedAt.toLocalDate(), t.net, live)
            }
        }
        val m = PortfolioRisk.matrix(trades, today)
        val stops = PortfolioRisk.stops(trades, today)
        val s = runCatching { AppSettings.load() }.getOrNull()
        val capPct = runCatching { SecurePrefs.getDouble(K_CAP, PortfolioRisk.CAP_PCT_DEFAULT) }.getOrDefault(PortfolioRisk.CAP_PCT_DEFAULT)
        val on = runCatching { SecurePrefs.getBoolean(K_ON, true) }.getOrDefault(true)
        val cfg = PortfolioGuard.Config(capital = s?.capital ?: 0.0, capPct = capPct, on = on)
        guard.update(m, stops, cfg)
        refreshedAt = System.currentTimeMillis()
        publish(today)
    }

    /** [block]'s value, or null when it failed; a cancellation goes on up, never read as "nothing" (G9-3). */
    private suspend fun <T> readOrNull(block: suspend () -> T): T? = try { block() } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) { null }

    /** TEST ONLY: the arms' owners read instead of the strategies' book. */
    @Volatile internal var testOwners: (suspend () -> Map<String, String>)? = null

    private fun publish(today: LocalDate) {
        val c = guard.config
        _view.value = View(PortfolioRisk.line(guard.matrix, guard.stops, c.capRs, guard.blocksToday(today).size),
            PortfolioRisk.pairLines(guard.matrix), c.capPct, c.on, c.capRs)
    }

    /** Boss's cap, % of capital (Bot settings). Returns what to say. */
    fun setCapPct(pct: Double): String {
        val v = pct.coerceIn(0.5, 10.0)
        runCatching { SecurePrefs.putAllSoon(mapOf(K_CAP to v)) }
        guard.setConfig(guard.config.copy(capPct = v))
        runCatching { publish(Market.today()) }
        runCatching { Diag.record("portfolio", "Portfolio cap set to $v% of capital") }
        return "Correlated arms together may risk at most $v% of capital on one day; a later entry past it is blocked."
    }

    /** Boss's switch (on by default). Off only stops the blocking; the matrix is still worked out. */
    fun setOn(on: Boolean): String {
        runCatching { SecurePrefs.putAllSoon(mapOf(K_ON to on)) }
        guard.setConfig(guard.config.copy(on = on))
        runCatching { publish(Market.today()) }
        runCatching { Diag.record("portfolio", "Portfolio guard ${if (on) "on" else "off"}") }
        return if (on) "Portfolio guard on." else "Portfolio guard off: correlated arms may all open on one day."
    }

    /**
     * An arm's NEW entry ([key]: its strategy key, "liquidity:BANKNIFTY", "orb"; [side] +1 / -1), paper or [live]: the block's
     * words, or null when it may go. Memory only; never throws.
     */
    fun entry(key: String, side: Int, live: Boolean, nowMs: Long = System.currentTimeMillis()): String? = runCatching {
        val today = Market.today()
        // Not counted as opened here (review R1): the entry may still be skipped (the flow, the account guard) or not fill;
        // [opened] counts it once taken.
        val d = guard.check(PortfolioRisk.canon(key), side, live, today, nowMs, open = false)
        if (!d.block) return@runCatching null
        runCatching { Diag.record("portfolio", d.why!!) }
        runCatching {
            SmartWorkers.found("portfolio guard", Findings.Kind.OTHER, Findings.ALL, side, null, 70, d.why!!, 12 * 3_600_000L)
        }
        runCatching { publish(today) }
        "blocked by the portfolio guard: correlated arms already opened the same way today and the combined stop loss passes the cap"
    }.getOrNull()

    /**
     * [key]'s entry leaning [side] was actually taken (a paper fill, an order sent to Zerodha; FlowGate's [FlowGate.taken] and
     * entered outcomes): from now on it counts against a later correlated entry. Memory only; never throws.
     */
    fun opened(key: String, side: Int, live: Boolean, nowMs: Long = System.currentTimeMillis(), claim: String = "") {
        runCatching { guard.opened(PortfolioRisk.canon(key), side, live, Market.today(), nowMs, claim) }
    }

    /**
     * The entry door's part for [arm]'s live order (Broker's entry gate; [arm]: the strategy key its entry check asked with):
     * refused only when the guard's latest live decision for that arm this way was a block. An order with no arm (a saved
     * strategy's basket leg, Boss's own) is never refused here. Memory only.
     */
    fun doorRefusal(arm: String?, symbol: String, buy: Boolean): String? = runCatching {
        if (arm == null) return@runCatching null
        val s = symbol.uppercase()
        val dir = when { s.endsWith("CE") -> 1; s.endsWith("PE") -> -1; else -> 1 } * (if (buy) 1 else -1)
        guard.doorRefusal(PortfolioRisk.canon(arm), dir, Market.today(), System.currentTimeMillis())
    }.getOrNull()

    /** Jarvis: "how correlated are my strategies?" / "why was X blocked?". Reads memory (refreshes first when stale). */
    suspend fun answer(q: String): String {
        refreshIfDue()
        return when (PortfolioRisk.asked(q)) {
            PortfolioRisk.Ask.WHY_BLOCKED -> PortfolioRisk.answerBlocked(q, guard.blocksToday(Market.today()))
            else -> PortfolioRisk.answerCorrelation(guard.matrix, guard.stops, guard.config.capRs)
        }
    }

    /** TEST ONLY. */
    internal fun resetForTest() { guard.clear(); guard.update(PortfolioRisk.Matrix(emptyList(), emptyMap(), emptyMap()), emptyMap(), PortfolioGuard.Config()); refreshedAt = 0L; _view.value = View(); testOwners = null }
}
