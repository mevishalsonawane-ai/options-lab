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

    /** Contracts fetched, then written, a batch at a time: a harvest stopped part-way keeps what it had. */
    private const val BATCH = 100

    suspend fun run(
        underlyings: List<String> = listOf("NIFTY", "BANKNIFTY"),
        expiries: Int = 3, days: Long = 1, indices: Boolean = true,
        onProgress: (Progress) -> Unit = {},
    ): Result {
        val today = Market.today()
        val start = today.minusDays(days)
        // Today's session is final once the market has closed (or never opened). Only then may a
        // retried run skip the contracts an earlier attempt already stored.
        val sessionFinal = !Market.isTradingDay(today) || Market.minuteNow() >= Market.CLOSE
        kotlinx.coroutines.yield()   // a stopped run ends here, not after the (blocking) master read
        onProgress(Progress("Reading the instrument master", 0, 1))
        // Today's master if the phone already has it: a retry does not download tens of MB again.
        val master = Market.contracts()
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
            // The expiring NIFTY chain is also kept in Upstox units, from this run's own fetch, so it
            // is never skipped until that capture is on disk.
            val expiryHeld = u == "NIFTY" && today in Store.deviceExpiryDays()
            val already = if (sessionFinal) Store.harvested(u, today) else emptySet()
            val todo = chain.filter { c -> c.instrumentKey !in already || (u == "NIFTY" && c.expiry == today && !expiryHeld) }
            val failures = ArrayList<String>()
            val gate = Semaphore(4)   // Upstox rate-limits hard at 429; 8 workers tripped it
            var done = chain.size - todo.size
            val touched = HashSet<LocalDate>()
            val expiringToday = ArrayList<Series>()
            for (batch in todo.chunked(BATCH)) {
                val fetched = coroutineScope {
                    batch.map { c ->
                        async {
                            gate.withPermit {
                                val r = try {
                                    // The current session is fetched for the WHOLE tracked chain, so
                                    // same_day means the partition holds the chain as it traded.
                                    val bars = (Net.history(c.instrumentKey, start, today) + Net.intraday(c.instrumentKey, tries = 6))
                                        .associateBy { it.epochSecond }.values.sortedBy { it.epochSecond }
                                    c to bars
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e      // stopped (e.g. by the system): not a failed contract
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
                val stored = ArrayList<String>()
                for ((c, bars) in fetched) {
                    var whole = true
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
                            whole = false
                        }
                    }
                    if (whole) stored += c.instrumentKey
                }
                // Written as each batch lands, then marked done: a retry resumes instead of starting over.
                for ((day, series) in byDay) Store.upsertDay(u, day, series)
                touched += byDay.keys
                if (sessionFinal) Store.markHarvested(u, today, stored)
            }

            // Days an earlier attempt stored are recorded too, so a resumed run still writes the manifest.
            if (todo.size < chain.size) touched += Store.deviceBarDays(u).filter { !it.isBefore(start) && !it.isAfter(today) }
            for (day in touched) {
                val part = Store.barSession(u, day)
                val opts = part?.options ?: emptyList()
                Store.recordManifest(u, Manifest.Entry(
                    session = day, nExpiries = opts.mapNotNull { it.expiry }.distinct().size, nContracts = opts.size,
                    scope = Manifest.scopeFor(day, today, failures.size, opts.size), collectedOn = today,
                ))
                if (day == today && failures.isEmpty()) sameDay[u] = opts.size
            }
            allFailures += failures

            // Expiry day: keep the expiring chain in Upstox units, exactly like
            // the PC's cache, so the forward record grows by one session.
            if (!expiryHeld && expiringToday.isNotEmpty() && Market.minuteNow() >= 15 * 60 + 30) {
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
