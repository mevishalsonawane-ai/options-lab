package com.optionslab.app.ira

import android.content.Context
import com.optionslab.app.data.Diag
import com.optionslab.app.data.Market
import com.optionslab.app.data.VariantRunner
import com.optionslab.ira.AutoScorecard
import com.optionslab.ira.VariantLab
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate
import java.time.YearMonth

/**
 * The monthly automation scorecard (S15; the figures are ira-core's [AutoScorecard]): on the 1st (from [SelfJobs]) the month
 * before's automatic changes per system against making no change, kept against reverted, the specialists muted and the
 * strategy lab's promotions - a sheet (Home → Automation card → Autopilot → Monthly scorecard), one notification, and Jarvis's "how did the
 * automation do last month?". Reads the agents' books only; changes nothing.
 */
object MonthlyScorecard {
    @Volatile private var appContext: Context? = null

    private val _card = MutableStateFlow<AutoScorecard.Card?>(null)
    val card: StateFlow<AutoScorecard.Card?> = _card

    private val _open = MutableStateFlow(false)
    /** The sheet open over the app ([SelfSheets]). */
    val open: StateFlow<Boolean> = _open

    fun init(context: Context) { appContext = context.applicationContext }

    /** The lab's promotions as the scorecard reads them (with each variant's shadow lead before it). */
    private fun promotions(b: VariantLab.Book): List<AutoScorecard.Promotion> = b.changes.map { c ->
        val v = b.variants.firstOrNull { it.id == c.variant }
        AutoScorecard.Promotion(c.day, VariantLab.strategy(c.strategy)?.label ?: c.strategy, v?.moved() ?: "new rules",
            v?.let { it.vSum - it.cSum }, c.status.name, c.kind == VariantLab.ChangeKind.LIVE_PROPOSAL)
    }

    /** [month]'s card from the books now (a worker thread); [notify]: the one notification of the 1st. */
    fun make(month: YearMonth, notify: Boolean = false): AutoScorecard.Card {
        runCatching { Autopilot.ensureLoaded() }
        runCatching { VariantRunner.ensureLoaded() }
        val health = SelfJobs.healthDays().count { YearMonth.from(it) == month }
        val c = AutoScorecard.of(Autopilot.book.value, month, runCatching { promotions(VariantRunner.variants.value) }.getOrDefault(emptyList()), health)
        _card.value = c
        runCatching { Diag.record("scorecard", AutoScorecard.notice(c)) }
        val ctx = appContext
        if (notify && ctx != null && !c.empty)
            runCatching { com.optionslab.app.work.Notifier.post(ctx, 5183, com.optionslab.app.work.Notifier.IRA, "Automation scorecard", AutoScorecard.notice(c), "almanac") }
        return c
    }

    /** Last month's card (made now when not made yet). */
    fun lastMonth(): AutoScorecard.Card {
        val m = YearMonth.from(runCatching { Market.today() }.getOrElse { LocalDate.now() }).minusMonths(1)
        return _card.value?.takeIf { it.month == m } ?: make(m)
    }

    /** "How did the automation do last month?" (a worker thread). */
    fun answer(): String = AutoScorecard.say(lastMonth())

    fun show() { _open.value = true }
    fun close() { _open.value = false }

    /** TEST ONLY. */
    internal fun resetForTest() { _card.value = null; _open.value = false }
}
