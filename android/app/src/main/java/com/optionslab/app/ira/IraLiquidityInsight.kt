package com.optionslab.app.ira

import com.optionslab.ira.LiquidityInsight
import com.optionslab.ira.LiquidityRecord
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.withTimeoutOrNull

/**
 * "What's working" ([LiquidityInsight], Boss, 07 Oct 2026): Liquidity 15+5's closed paper trades cut by entry time, book,
 * side, exit, room to the next level and weekday, a group said to stand out only past 2 standard errors from the rest,
 * beside the research. Asked ("what's working for liquidity", "where does liquidity lose", "liquidity patterns", [answer]),
 * and once a week ([watch], [Automations.Auto.LIQINSIGHT], its own switch under Coach me, on): on Saturday morning, when at
 * least [LiquidityInsight.MIN_NEW] trades closed since the last look, one note in the chat, tagged for Today's notes (Coach).
 *
 * Reads only, bounded: the arms' book through its own reader ([com.optionslab.app.data.OrbArms.closedPaper]: its lock is
 * held only to copy the book - a lock held by the arms' tick is waited on a few seconds at most). Words only: nothing here
 * changes a rule, the lots or a switch. Nothing of the book is logged. In the chat only (never spoken); never in quiet hours.
 * Not in IraGoldAlgo.
 */
internal object IraLiquidityInsight {
    /** A short wait on the arms' lock. */
    private const val BOOK_MS = 3_000L
    private val busy = java.util.concurrent.atomic.AtomicBoolean(false)

    /** The arm's closed paper trades (oldest exit first), or null when the book could not be read in time. */
    private suspend fun rows(): List<LiquidityRecord.Row>? {
        val closed = withTimeoutOrNull(BOOK_MS) { runCatching { com.optionslab.app.data.OrbArms.closedPaper() }.getOrNull() } ?: return null
        return LiquidityRecord.rows(closed.map { IraBots.tradeOf(it) })
    }

    /** The answer to [focus] (the hub keeps it off a locked phone). */
    suspend fun answer(focus: LiquidityInsight.Focus): String {
        val rows = rows() ?: return "Liquidity 15+5's book took too long to read just now, Boss - ask again in a moment."
        return LiquidityInsight.answer(focus, rows)
    }

    private fun doneOn(): LocalDate? = runCatching {
        com.optionslab.app.security.SecurePrefs.getString(LiquidityInsight.KEY_DONE)?.let { LocalDate.parse(it) }
    }.getOrNull()

    private fun mark(): LocalDateTime? = runCatching {
        com.optionslab.app.security.SecurePrefs.getString(LiquidityInsight.KEY_MARK)?.let { LocalDateTime.parse(it) }
    }.getOrNull()

    /**
     * The words lane's rounds, the closed day's morning and the hourly study worker: on a Saturday morning
     * ([LiquidityInsight.due]), once, the week's look. The text is made first (a book not read in time: nothing kept, the next
     * round tries again); then the day is kept as looked at - with the newest exit counted, when there is a note - and only
     * then the note is put, so a restart midway never puts it twice. Fewer than [LiquidityInsight.MIN_NEW] new trades: the
     * day is kept, nothing is said.
     */
    suspend fun watch() {
        if (!com.optionslab.app.BuildConfig.JARVIS || com.optionslab.app.BuildConfig.GOLD || !Automations.on(Automations.Auto.LIQINSIGHT)) return
        if (runCatching { JarvisVoice.quietNow() }.getOrDefault(true)) return
        val now = com.optionslab.app.data.Market.now().toLocalDateTime()
        if (!LiquidityInsight.due(now, doneOn())) return
        if (!busy.compareAndSet(false, true)) return
        try {
            val rows = rows() ?: return
            val text = runCatching { LiquidityInsight.weekly(rows, mark()) }.getOrNull()
            val keep = HashMap<String, Any?>()
            keep[LiquidityInsight.KEY_DONE] = now.toLocalDate().toString()
            if (text != null) LiquidityInsight.markOf(rows)?.let { keep[LiquidityInsight.KEY_MARK] = it.toString() }
            val kept = runCatching { com.optionslab.app.security.SecurePrefs.putAll(keep) }.isSuccess
            if (!kept || text == null) return
            IraHub.note(text, from = Automations.Auto.LIQINSIGHT, kind = com.optionslab.ira.TodayNotes.Category.COACH)
            Automations.acted(Automations.Auto.LIQINSIGHT, "Put Liquidity 15+5's weekly patterns in the chat.")
        } finally {
            busy.set(false)
        }
    }
}
