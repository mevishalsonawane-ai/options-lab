package com.optionslab.app.data

import com.optionslab.app.security.SecurePrefs
import com.optionslab.engine.options.ChainSnapshot
import com.optionslab.ira.GammaRegime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The gamma regime of NIFTY and BANKNIFTY (Boss, 9 Oct 2026; the logic is [GammaRegime]'s): net GEX and the zero-gamma
 * level from the option chain the app already prices. A LIVE READING and a shadow-log field only: no strategy reads it.
 *
 * Refreshed at most every 5 minutes per index, off the main thread: from the Options tab's own chain when it is priced
 * ([offer], called on its IO pricing), else from the order flow's 30-second loop while the price stream runs ([refreshIfDue]:
 * a chain read in the last 5 minutes is reused, a new one is read only with a Zerodha session in Live). The screens read
 * [state] and [convention] - memory only. The sign convention is Boss's choice (standard by default), kept as one setting
 * written only when he changes it.
 */
object GammaLive {
    val INDICES = listOf("NIFTY", "BANKNIFTY")
    private const val K_CONVENTION = "gex.convention"

    private val _state = MutableStateFlow<Map<String, GammaRegime.Result>>(emptyMap())
    /** Each index's latest regime (both conventions: [GammaRegime.Result.sign]). */
    val state: StateFlow<Map<String, GammaRegime.Result>> = _state

    private val _convention = MutableStateFlow(GammaRegime.Convention.STANDARD)
    val convention: StateFlow<GammaRegime.Convention> = _convention
    @Volatile private var conventionRead = false

    /** The saved convention, read once (call off the main thread). */
    fun loadConvention() {
        if (conventionRead) return
        conventionRead = true
        runCatching { SecurePrefs.getString(K_CONVENTION) }.getOrNull()
            ?.let { s -> GammaRegime.Convention.entries.firstOrNull { it.name == s } }?.let { _convention.value = it }
    }

    /** Boss chose [c] in the GEX tool: shown at once, saved once. */
    fun setConvention(c: GammaRegime.Convention) {
        conventionRead = true
        _convention.value = c
        runCatching { SecurePrefs.putAllSoon(mapOf(K_CONVENTION to c.name)) }
    }

    private fun due(underlying: String, now: Long): Boolean = _state.value[underlying]?.let { now - it.atMs >= GammaRegime.REFRESH_MS } ?: true

    /** The Options tab priced [snap] (on its IO thread): the regime from it, at most every 5 minutes per index. */
    fun offer(snap: ChainSnapshot, now: Long = System.currentTimeMillis()) {
        val u = snap.underlying.uppercase()
        if (u !in INDICES || !due(u, now)) return
        runCatching { GammaRegime.of(snap, now) }.getOrNull()?.let { r -> _state.value = _state.value + (u to r) }
    }

    /** From the order flow's loop (IO): each index whose regime is 5 minutes old, from a recent chain or a new one in Live. */
    suspend fun refreshIfDue(now: Long = System.currentTimeMillis()) {
        loadConvention()
        for (u in INDICES) {
            if (!due(u, now)) continue
            val lc = Market.recentChain(u, 12) ?: if (Broker.loggedIn && runCatching { AppSettings.load().live }.getOrDefault(false))
                runCatching { Market.liveChain(u, near = 12) }.getOrNull() else null
            if (lc == null) continue
            val symbols = lc.contracts.associate { (it.strike to it.right) to it.tradingSymbol }
            val rows = ChainSnapshot.rowsFrom(lc.series, symbols, lc.lotSize)
            offer(ChainSnapshot.of(u, lc.expiry, lc.spot, lc.lotSize, rows, Market.now()), now)
        }
    }

    /** TEST ONLY: set the regimes as if computed. Throws unless BuildConfig.DEBUG. */
    internal fun setForTest(m: Map<String, GammaRegime.Result>) {
        check(com.optionslab.app.BuildConfig.DEBUG) { "the test state exists only in debug builds" }
        _state.value = m
    }
}
