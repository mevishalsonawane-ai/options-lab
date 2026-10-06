package com.optionslab.app.data

import com.optionslab.ira.MarketRecord
import com.optionslab.ira.RecorderStats
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.time.LocalDate

/**
 * The "Market data" viewer's reads of the market recorder's day files ([MarketRecorder.dayRecords], parsed by
 * [RecorderStats]): decrypted and parsed off the main thread, the newest few parsed days and every day's short summary
 * kept in memory, each keyed by the file's size (today's growing file is read again; a finished day once). Read only.
 */
object RecordedData {
    /** Parsed days kept (a parsed day is a few thousand small rows). */
    private const val KEEP_PARSED = 4

    private class Parsed(val size: Long, val day: RecorderStats.Day)

    private val lock = Any()
    private val parsed = object : LinkedHashMap<LocalDate, Parsed>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<LocalDate, Parsed>?) = size > KEEP_PARSED
    }
    private val summaries = HashMap<LocalDate, Pair<Long, RecorderStats.Summary>>()

    /** The recorded days and their sizes, newest first (the folder listing). Off the main thread. */
    fun days(): List<Pair<LocalDate, Long>> = runCatching { MarketRecorder.recordedDays() }.getOrDefault(emptyList())

    /** [date]'s file ([size] bytes now) parsed: from memory when the file has not changed. Off the main thread. */
    fun day(date: LocalDate, size: Long): RecorderStats.Day {
        synchronized(lock) { parsed[date]?.takeIf { it.size == size }?.let { return it.day } }
        // A file that cannot be read at all (the key gone, the folder gone) is an empty day with one unreadable part.
        val r = runCatching { MarketRecorder.dayRecords(date) }.getOrElse { MarketRecord.Read(emptyList(), 1) }
        val day = RecorderStats.parse(r.lines, r.bad)
        val sum = RecorderStats.summary(date, day)
        synchronized(lock) { parsed[date] = Parsed(size, day); summaries[date] = size to sum }
        return day
    }

    /** Every day's summary (for the lessons and the cross-day views), [progress] after each; cancellable. Off the main thread. */
    suspend fun summaries(days: List<Pair<LocalDate, Long>>, progress: (Int) -> Unit = {}): List<RecorderStats.Summary> {
        val out = ArrayList<RecorderStats.Summary>(days.size)
        days.forEachIndexed { i, (date, size) ->
            currentCoroutineContext().ensureActive()
            val kept = synchronized(lock) { summaries[date]?.takeIf { it.first == size }?.second }
            out += kept ?: run { day(date, size); synchronized(lock) { summaries.getValue(date).second } }
            progress(i + 1)
        }
        return out
    }

    /** Forget everything kept (after an erase). */
    fun clear() = synchronized(lock) { parsed.clear(); summaries.clear() }
}
