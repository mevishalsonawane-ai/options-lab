package com.optionslab.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.time.LocalDateTime

/**
 * 16 Nov 2026 (review of Boss's exception, the fifth: the step lock and the breakeven at +₹1,000 on R51, R72 and Liquidity 15+5): the
 * highest price a live rule trade's option traded after its entry minute - the stream's kept ticks while it streams the contract, else
 * the 1-minute candles' highs ([Paper.highSince], [Paper.gapHighSince]), as R186's minute highs - so its best price (and the breakevens and
 * step lock it earns) sees a spike between two looks, while the phone was away or across a restart.
 *
 * Never read on a look itself: [ask] starts one background read per trade at a time (a feed that is slow or stalled never holds a
 * position's stop check), [peek] returns the last high found (memory only). One shared helper for the arms' live books ([ArmLive],
 * [OrbArms]). Bounded ([KEPT]), forgotten with each trade ([forget]) and released under memory pressure ([trim]): the position watch
 * itself never depends on it (the stream's price still counts as before).
 */
internal object RuleHighs {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val highs = java.util.concurrent.ConcurrentHashMap<String, Double>()
    private val inFlight = java.util.concurrent.ConcurrentHashMap<String, Job>()
    /** When each trade's read was last started (the market's clock, ms): at most one a minute each ([EVERY_MS]); and its age for pruning. */
    private val asked = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val KEPT = 200
    private const val EVERY_MS = 60_000L
    private const val DAY_MS = 24 * 60 * 60_000L

    /** The last high found for the trade [key] (memory only; null: none yet). */
    fun peek(key: String): Double? = highs[key]

    /**
     * Starts a background read of [c]'s highest price after [since]'s minute for the trade [key] - unless one is running or one started
     * in the last minute (the feed read once a minute a trade, as the paper books' gap read). Trades not asked for a day are dropped.
     */
    fun ask(key: String, c: Paper.Contract, since: LocalDateTime, nowMs: Long = marketMs()) {
        if (inFlight[key]?.isActive == true) return
        asked[key]?.let { if (nowMs - it in 0 until EVERY_MS) return }
        asked[key] = nowMs
        if (asked.size > KEPT) asked.entries.filter { nowMs - it.value > DAY_MS }.forEach { forget(it.key) }
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            val h = listOfNotNull(runCatching { Paper.highSince(c, since) }.getOrNull(),
                runCatching { Paper.gapHighSince(c, since) }.getOrNull()?.high).filter { it.isFinite() && it > 0 }.maxOrNull()
            // (A trade forgotten meanwhile - closed, released - takes nothing back.)
            if (h != null && kotlinx.coroutines.currentCoroutineContext()[Job]?.isActive == true && asked.containsKey(key))
                highs.merge(key, h) { a, b -> maxOf(a, b) }
        }
        inFlight[key] = job
        job.invokeOnCompletion { inFlight.remove(key, job) }
        job.start()
    }

    /** The market's clock (the minutes the feed's candles are on; the tests' fixed clock), ms. */
    private fun marketMs(): Long = runCatching { Market.now().toInstant().toEpochMilli() }.getOrElse { System.currentTimeMillis() }

    /** The trade [key] closed: its high forgotten. */
    fun forget(key: String) { highs.remove(key); asked.remove(key); inFlight.remove(key)?.cancel() }

    /** Memory short: everything released (each trade asks again on its next regular look; the stops never depend on it). */
    fun trim() { highs.clear() }

    /** How many highs are kept (the tests). */
    internal fun kept(): Int = highs.size

    /** TEST ONLY: the reads under way finished. */
    internal suspend fun settleForTest() { inFlight.values.toList().joinAll() }

    /** TEST ONLY / erase: everything forgotten. */
    internal fun clear() { inFlight.values.forEach { it.cancel() }; inFlight.clear(); highs.clear(); asked.clear() }
}
