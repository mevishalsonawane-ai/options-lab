package com.optionslab.app.ira

import com.optionslab.engine.orb.HeroRules
import com.optionslab.ira.DayRecap
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The day recap ([DayRecap], Boss, 07 Oct 2026): "what happened on 3 Oct", "recap of yesterday", "how was Monday", "2 oct
 * ka recap" - one compact recap of that trading day, read from what the phone already keeps, each part with a time limit:
 * the index candles Jarvis holds ([IraHub.recentBars], else the market recorder's minute readings of that day), the
 * recorder's day file (its events, headlines and the FII/DII figures - read and decrypted on the phone, never fetched), the
 * calendar, Liquidity 15+5's and Hero's closed paper trades ([com.optionslab.app.data.OrbArms.closedPaper], a short wait on
 * the arms' lock, which is never held here across a slow read) and Solo (midday)'s trades ([IraSolo.all]). Each slow part is
 * read apart from the asker ([bounded]): past its limit the recap stops waiting for it and is said from the parts read in
 * time, with a line that some parts took too long ([DayRecap.SOME_SLOW]). Nothing is read
 * from the network. Words only: nothing here arms, places, closes or changes anything. On a locked phone the paper arms
 * are left out (the market's lines stay). In the chat only; not in IraGoldAlgo.
 */
internal object IraDayRecap {
    /** The recorder's list of kept day files. */
    private const val LIST_MS = 2_000L
    /** A short wait on the arms' lock (their pass may hold it across a network read). */
    private const val BOOK_MS = 3_000L
    /** One recorder day file read and decrypted. */
    private const val FILE_MS = 8_000L
    /**
     * The recorder files after the day read for its FII/DII figures (NSE gives them in the evening; the next day's file
     * keeps them). One: every part's limit together ([LIST_MS] + [FILE_MS] x (1 + this) + [BOOK_MS]) is 21 s at most.
     */
    private const val FLOW_FILES = 1

    /**
     * The slow reads, apart from the asker: a file read and decrypted cannot be interrupted (a held lock, a decrypt), so the
     * asker stops waiting at the limit and the read finishes, or fails, here on its own (its result dropped).
     */
    private val reads = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Set when a part took longer than its limit (the recap says so under what was read). */
    private class Slow { @Volatile var hit = false }

    /**
     * [read] on [reads], waited for at most [ms] (null: too slow - [slow] is marked - or it failed). The asker's own
     * cancellation is passed on, never taken for a failed read.
     */
    private suspend fun <T> bounded(ms: Long, slow: Slow, read: suspend () -> T): T? {
        val job = reads.async { read() }
        val done = try {
            withTimeoutOrNull(ms) { job.join(); true }
        } catch (e: CancellationException) {
            job.cancel(); throw e
        }
        if (done == null) { slow.hit = true; job.cancel(); return null }
        return try { job.await() } catch (e: Exception) { currentCoroutineContext().ensureActive(); null }
    }

    /** [day]'s records in the market recorder (null: no file kept for it, or it could not be read in time). */
    private suspend fun recorded(day: LocalDate, kept: Set<LocalDate>, slow: Slow): List<String>? =
        if (day !in kept) null else bounded(FILE_MS, slow) { com.optionslab.app.data.MarketRecorder.dayRecords(day).lines }

    /**
     * What the recap of [day] is made of, read now ([today]: when asked; [partial]: today, still running). Each part read on
     * its own, each within its limit: one that fails is said as not kept or not read, never guessed; one too slow is left
     * out and the recap says some parts took too long. Today and on a locked phone ([locked]) the paper arms are not read.
     */
    suspend fun facts(day: LocalDate, today: LocalDate, from: LocalDate?, fromWhy: String?, partial: Boolean, locked: Boolean): DayRecap.Facts {
        val rec = com.optionslab.app.data.MarketRecorder
        val slow = Slow()
        val kept = bounded(LIST_MS, slow) { rec.recordedDays().map { it.first }.toSet() } ?: emptySet()
        val lines = recorded(day, kept, slow)
        // The session before that day: its close gives the gap and the day's change (a held candle of another day never does).
        val before = runCatching { DayRecap.sessionBefore(day) { d -> com.optionslab.app.data.Market.isTradingDay(d) } }.getOrNull()
        // The indices: the 1-minute candles Jarvis holds, else the recorder's minute readings of that day.
        val indices = DayRecap.MARKETS.mapNotNull { m ->
            val bars = runCatching { IraHub.recentBars(m) }.getOrDefault(emptyList())
            runCatching { DayRecap.index(m, bars, day, before) }.getOrNull()
                ?: lines?.let { l -> runCatching { DayRecap.indexFromRecorder(m, l, DayRecap.prevClose(bars, day, before)) }.getOrNull() }
        }
        // The events: the recorder's calendar of that day, the built-in ones and Boss's own.
        val events = runCatching { lines?.let { DayRecap.eventsOf(it, day) }.orEmpty() }.getOrDefault(emptyList()) +
            runCatching { com.optionslab.ira.Events.builtIn(day, day) }.getOrDefault(emptyList()) +
            runCatching { IraEvents.owner().filter { it.day == day } }.getOrDefault(emptyList())
        val news = lines?.let { l -> runCatching { DayRecap.headlines(l) }.getOrDefault(emptyList()) }
        val base = DayRecap.Facts(day = day, today = today, from = from, fromWhy = fromWhy, partial = partial,
            indices = indices, events = events, news = news, locked = locked)
        if (day == today) return base.copy(slow = slow.hit)
        // The FII/DII figures NSE gave for that day: in that day's file, else in the next kept files.
        var flows = runCatching { lines?.let { DayRecap.flowsFor(it, day) }.orEmpty() }.getOrDefault(emptyList())
        if (flows.isEmpty()) for (d in kept.filter { it.isAfter(day) }.sorted().take(FLOW_FILES)) {
            flows = recorded(d, kept, slow)?.let { l -> runCatching { DayRecap.flowsFor(l, day) }.getOrDefault(emptyList()) }.orEmpty()
            if (flows.isNotEmpty()) break
        }
        if (locked) return base.copy(flows = flows, slow = slow.hit)
        // The paper arms: the arms' closed paper trades (a short wait on their lock), Solo (midday)'s own list.
        val closed = bounded(BOOK_MS, slow) { com.optionslab.app.data.OrbArms.closedPaper() }
        val liqAll = closed?.let { c -> runCatching { com.optionslab.ira.LiquidityRecord.rows(c.map { IraBots.tradeOf(it) }) }.getOrNull() }
        val heroAll = closed?.filter { it.arm == HeroRules.ARM.source && !it.live && !it.open }
        val soloAll = runCatching { IraSolo.midday(IraSolo.all()) }.getOrNull()
        val soloDays = soloAll?.mapNotNull { t -> runCatching { LocalDate.parse(t.day) }.getOrNull() }
        return base.copy(
            flows = flows,
            liquidity = liqAll?.filter { it.day == day }, liquidityFrom = liqAll?.minOfOrNull { it.day },
            solo = soloAll?.filter { it.day == day.toString() }?.map { t ->
                DayRecap.Paper(t.symbol, LocalTime.of(9, 15).plusMinutes(t.entryMinute.toLong()), if (t.closed) t.net else null, t.exit)
            },
            soloFrom = soloDays?.minOrNull(),
            hero = heroAll?.filter { it.day == day }?.map { p ->
                DayRecap.Paper(p.symbol, p.entryTime.toLocalTime(), (p.grossPnl ?: 0.0) - p.charges, p.why)
            },
            heroFrom = heroAll?.minOfOrNull { it.day },
            slow = slow.hit,
        )
    }

    /**
     * The answer to "what happened on 3 Oct" ([q]): a day still to come, no such day or a day with no session said as it is;
     * else the recap of the parts read within their limits (and, when some were too slow, that). [locked]: the paper arms
     * are left out.
     */
    suspend fun answer(q: DayRecap.Q, locked: Boolean): String {
        val mk = com.optionslab.app.data.Market
        val now = mk.now().toLocalDateTime()
        val today = now.toLocalDate()
        val book = runCatching { com.optionslab.app.data.Holidays.book() }.getOrNull()
        val plan = DayRecap.plan(q, today, { d -> mk.isTradingDay(d) },
            { d -> book?.takeIf { it.holiday(d) }?.upcoming(d)?.firstOrNull { it.first == d }?.second }, now = now.toLocalTime())
        DayRecap.say(plan, today)?.let { return it }
        val target: Triple<LocalDate, LocalDate?, String?> = when (plan) {
            is DayRecap.Plan.Session -> Triple(plan.day, plan.from, plan.fromWhy)
            is DayRecap.Plan.Today -> Triple(plan.day, null, null)
            else -> return DayRecap.UNKNOWN
        }
        val partial = target.first == today && now.toLocalTime().isBefore(LocalTime.of(15, 30))
        return DayRecap.say(facts(target.first, today, target.second, target.third, partial, locked))
    }
}
