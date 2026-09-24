package com.optionslab.app.data

import com.optionslab.engine.Lots
import com.optionslab.engine.Manifest
import com.optionslab.engine.Series
import com.optionslab.engine.Session
import com.optionslab.engine.Upstox
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.LocalDate

/**
 * The nightly chain harvester (port of `harvest/cli.py`), on the phone.
 *
 * No free source serves EXPIRED contracts: Upstox drops a contract the moment
 * it settles and recycles its token. A session not collected ON ITS OWN DAY is
 * gone permanently - which is why this is scheduled after every close.
 */
object Harvester {
    data class Progress(val stage: String, val done: Int, val total: Int)

    data class Result(
        val bars: Int,
        val failures: List<String>,
        val sameDay: Map<String, Int>,
        val capturedExpiry: LocalDate?,
    ) {
        fun summary(): String = buildString {
            append("%,d bars".format(bars))
            if (failures.isNotEmpty()) append(", ${failures.size} contract(s) failed - today cannot claim same_day")
            capturedExpiry?.let { append(", expiry chain $it captured") }
        }
    }

    suspend fun run(
        underlyings: List<String> = listOf("NIFTY", "BANKNIFTY"),
        expiries: Int = 3, days: Long = 1, indices: Boolean = true,
        onProgress: (Progress) -> Unit = {},
    ): Result {
        val today = Market.today()
        val start = today.minusDays(days)
        onProgress(Progress("Reading the instrument master", 0, 1))
        val master = Market.contracts(forceRefresh = true)
        var total = 0
        val sameDay = HashMap<String, Int>()

        if (indices) {
            for ((i, e) in Upstox.INDEX_KEYS.entries.withIndex()) {
                onProgress(Progress("Index ${e.key}", i, Upstox.INDEX_KEYS.size))
                val bars = Net.history(e.value, start, today) + Net.intraday(e.value, tries = 6)
                for ((day, dayBars) in bars.groupBy { it.istDate }) {
                    Store.upsertDay(e.key, day, listOf(Upstox.toSeries(null, dayBars, day)))
                    total += dayBars.size
                }
            }
        }

        var captured: LocalDate? = null
        val allFailures = ArrayList<String>()
        for (u in underlyings) {
            val live = Upstox.contractsToRefresh(master.filter { it.underlying == u }, today)
            val chosen = live.map { it.expiry }.distinct().sorted().take(expiries).toSet()
            val chain = live.filter { it.expiry in chosen }
            val failures = ArrayList<String>()
            val gate = Semaphore(4)   // Upstox rate-limits hard at 429; 8 workers tripped it
            var done = 0
            val fetched = coroutineScope {
                chain.map { c ->
                    async {
                        gate.withPermit {
                            val r = try {
                                // The current session is fetched for the WHOLE tracked chain, so
                                // same_day means the partition holds the chain as it traded.
                                val bars = (Net.history(c.instrumentKey, start, today) + Net.intraday(c.instrumentKey, tries = 6))
                                    .associateBy { it.epochSecond }.values.sortedBy { it.epochSecond }
                                c to bars
                            } catch (e: Upstox.UpstoxError) {
                                throw e      // withdrawn auth is fatal for the whole run
                            } catch (_: Exception) {
                                synchronized(failures) { failures += c.tradingSymbol }
                                null
                            }
                            synchronized(this@Harvester) { done++; onProgress(Progress("$u chain", done, chain.size)) }
                            r
                        }
                    }
                }.awaitAll()
            }.filterNotNull()

            val byDay = HashMap<LocalDate, MutableList<Series>>()
            val expiringToday = ArrayList<Series>()
            for ((c, bars) in fetched) {
                for ((day, dayBars) in bars.groupBy { it.istDate }) {
                    try {
                        byDay.getOrPut(day) { ArrayList() } += Upstox.toSeries(c, dayBars, day)
                        total += dayBars.size
                        if (u == "NIFTY" && c.expiry == today && day == today) {
                            expiringToday += Upstox.toSeries(c, dayBars, day, contracts = false)
                        }
                    } catch (_: com.optionslab.engine.Units.UnitsMismatch) {
                        // Not whole lots: the convention was misidentified. Refuse it, loudly counted.
                        failures += c.tradingSymbol
                    }
                }
            }
            for ((day, series) in byDay) {
                Store.upsertDay(u, day, series)
                val part = Store.barSession(u, day)
                val opts = part?.options ?: emptyList()
                Store.recordManifest(u, Manifest.Entry(
                    session = day, nExpiries = opts.mapNotNull { it.expiry }.distinct().size, nContracts = opts.size,
                    scope = Manifest.scopeFor(day, today, failures.size, opts.size), collectedOn = today,
                ))
            }
            if (failures.isEmpty() && byDay.containsKey(today)) sameDay[u] = byDay.getValue(today).size
            allFailures += failures

            // Expiry day: keep the expiring chain in Upstox units, exactly like
            // the PC's cache, so the forward record grows by one session.
            if (expiringToday.isNotEmpty() && Market.minuteNow() >= 15 * 60 + 30) {
                val lot = Lots.lotFromChain(expiringToday)
                Store.writeExpiry(Session(today, lot, expiringToday))
                Store.invalidate()
                captured = today
            }
        }
        onProgress(Progress("Done", 1, 1))
        return Result(total, allFailures, sameDay, captured)
    }
}
