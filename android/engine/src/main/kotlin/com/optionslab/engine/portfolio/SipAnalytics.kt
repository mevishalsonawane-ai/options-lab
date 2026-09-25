package com.optionslab.engine.portfolio

import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

data class SipHeadline(
    val invested: Double?, val finalValue: Double?, val gain: Double?, val multiple: Double?, val xirr: Double?,
    val absoluteReturn: Double?, val installments: Int, val units: Double?, val averageCost: Double?, val averagePrice: Double?,
    /** Negative: each unit cost less than the average price over the same dates (rupee-cost averaging). */
    val costAdvantage: Double?, val charges: Double?, val cash: Double?, val start: LocalDate, val end: LocalDate, val years: Double?,
)
data class Underwater(
    val sessionsBelow: Int, val sessionsTotal: Int, val shareBelow: Double?, val longestStreakSessions: Int,
    val longestStreakEnded: LocalDate?, val worstShortfall: Double?, val everUnderwater: Boolean,
)
data class NullablePoint(val date: LocalDate, val value: Double?)
data class SipDrawdown(val maxDrawdown: Double?, val troughDate: LocalDate?, val recoverySessions: Int?, val recovered: Boolean, val curve: List<NullablePoint>)
data class SipYear(val year: Int, val investedDuringYear: Double?, val investedToDate: Double?, val value: Double?, val gain: Double?, val change: Double?)
data class MonthlyHeatmap(val years: List<String>, val columns: List<String>, val values: List<List<Double?>>, val basis: String)
data class LumpsumComparison(
    val invested: Double?, val finalValue: Double?, val xirr: Double?, val entryPrice: Double?, val entryDate: LocalDate,
    val sipFinalValue: Double?, val difference: Double?, val sipWon: Boolean,
)
data class FrequencyRow(
    val frequency: String, val amountPerInstallment: Double?, val installments: Int, val invested: Double?,
    val finalValue: Double?, val xirr: Double?, val averageCost: Double?, val charges: Double?,
)
data class XirrPoint(val start: LocalDate, val end: LocalDate, val xirr: Double?)
data class RollingXirr(
    val years: Int, val windows: Int, val points: List<XirrPoint>, val best: Double?, val worst: Double?,
    val median: Double?, val positiveShare: Double?,
)
data class StartDateHeatmap(val rows: List<String>, val columns: List<String>, val values: List<List<Double?>>)
data class SipDateHeatmap(val days: List<Int>, val values: List<Double?>, val bestDay: Int?, val worstDay: Int?, val spread: Double?)
data class SipCase(
    val start: LocalDate, val invested: Double?, val finalValue: Double?, val installments: Int, val xirr: Double?,
    val multiple: Double?, val absoluteReturn: Double?, val averageCost: Double?,
)
data class SipCrisisRow(
    val key: String, val label: String, val note: String, val scope: String, val crisisStart: String, val crisisEnd: String,
    val marketMove: Double?, val marketTrough: Double?, val startedIntoIt: SipCase, val waitedForTheEnd: SipCase,
    val xirrAdvantage: Double?, val startingEarlyWon: Boolean,
)
data class SipCrisisSummary(val crises: Int, val earlyWins: Int, val share: Double?, val medianAdvantage: Double?)
data class SipCrisis(val summary: SipCrisisSummary, val periods: List<SipCrisisRow>)

/** Frequency, day of month, step-up and costs: the knobs every re-run shares. */
internal data class SipKw(val frequency: String, val dayOfMonth: Int, val stepUpPercent: Double, val costs: CostModel?)

/**
 * SIP metrics. Port of `sip/analytics.py` and `sip/crisis.py`: cash-flow
 * measures (XIRR and its variants) rather than price ratios, underwater time
 * against money paid in, and grids that re-run the whole simulation.
 */
internal object SipAnalyticsCalc {
    private const val MAX_GRID_CELLS = 900
    private val ROLLING_YEARS = listOf(1, 3, 5, 7, 10)
    private const val MIN_SIP_DAYS = 120
    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    fun f(x: Double?): Double? = if (x == null || !x.isFinite()) null else x

    private fun sip(closes: Ser, start: LocalDate, end: LocalDate, amount: Double, kw: SipKw, frequency: String = kw.frequency, dayOfMonth: Int = kw.dayOfMonth) =
        SipEngine.run(closes, start, end, amount, frequency, dayOfMonth, kw.stepUpPercent, kw.costs)

    private fun sipXirr(closes: Ser, start: LocalDate, end: LocalDate, amount: Double, kw: SipKw, dayOfMonth: Int = kw.dayOfMonth): Double? {
        val r = try { sip(closes, start, end, amount, kw, dayOfMonth = dayOfMonth) } catch (e: RuntimeException) { return null }
        return Xirr.xirrOrNull(r.cashFlows)
    }

    fun headline(r: SipRun): SipHeadline {
        val rate = Xirr.xirrOrNull(r.cashFlows)
        return SipHeadline(
            f(r.totalInvested), f(r.finalValue), f(r.finalValue - r.totalInvested),
            if (r.totalInvested != 0.0) f(r.finalValue / r.totalInvested) else null,
            f(rate), f(Xirr.absoluteReturn(r.totalInvested, r.finalValue)), r.installmentCount, f(r.totalUnits),
            f(r.averageCost), f(r.averagePrice),
            if (r.averagePrice != 0.0) f((r.averageCost - r.averagePrice) / r.averagePrice) else null,
            f(r.charges), f(r.cash), r.dates.first(), r.dates.last(),
            f(ChronoUnit.DAYS.between(r.dates.first(), r.dates.last()) / 365.0),
        )
    }

    fun underwater(r: SipRun): Underwater {
        val below = BooleanArray(r.value.size) { r.value[it] < r.invested[it] }
        val count = below.count { it }
        var longest = 0; var current = 0; var longestEnd: LocalDate? = null
        below.forEachIndexed { i, flag ->
            if (flag) { current++; if (current > longest) { longest = current; longestEnd = r.dates[i] } } else current = 0
        }
        var worst = Double.NaN
        for (i in r.value.indices) {
            if (r.invested[i] == 0.0) continue
            val gap = (r.value[i] - r.invested[i]) / r.invested[i]
            if (!gap.isNaN() && (worst.isNaN() || gap < worst)) worst = gap
        }
        val total = below.size
        return Underwater(count, total, if (total > 0) f(count.toDouble() / total) else null, longest, longestEnd, f(worst), count > 0)
    }

    fun drawdown(r: SipRun): SipDrawdown {
        val v = r.value
        var peak = Double.NEGATIVE_INFINITY
        val peaks = DoubleArray(v.size) { peak = maxOf(peak, v[it]); peak }
        val dd = DoubleArray(v.size) { if (peaks[it] != 0.0) (v[it] - peaks[it]) / peaks[it] else Double.NaN }
        var trough = -1
        for (i in dd.indices) if (!dd[i].isNaN() && (trough < 0 || dd[i] < dd[trough])) trough = i
        var recovery: Int? = null
        if (trough >= 0) {
            val peakValue = peaks[trough]
            for (j in trough until v.size) if (v[j] >= peakValue) { recovery = j - trough; break }
        }
        return SipDrawdown(
            if (trough >= 0) f(dd[trough]) else null,
            if (trough >= 0) r.dates[trough] else null,
            recovery, recovery != null,
            dd.indices.map { NullablePoint(r.dates[it], f(dd[it])) },
        )
    }

    fun rollingXirr(closes: Ser, amount: Double, kw: SipKw, stepMonths: Int = 3): List<RollingXirr> {
        if (closes.size == 0) return emptyList()
        val first = closes.dates.first()
        val last = closes.dates.last()
        val out = ArrayList<RollingXirr>()
        for (span in ROLLING_YEARS) {
            val points = ArrayList<XirrPoint>()
            var cursor = first
            while (true) {
                val end = LocalDate.of(cursor.year + span, cursor.monthValue, minOf(cursor.dayOfMonth, 28))
                if (end > last) break
                val rate = sipXirr(closes, cursor, end, amount, kw)
                if (rate != null) points.add(XirrPoint(cursor, end, f(rate)))
                val month = cursor.monthValue - 1 + stepMonths
                cursor = LocalDate.of(cursor.year + month / 12, month % 12 + 1, minOf(cursor.dayOfMonth, 28))
            }
            if (points.isEmpty()) continue
            val rates = points.mapNotNull { it.xirr }
            out.add(
                RollingXirr(
                    span, points.size, points,
                    if (rates.isNotEmpty()) f(rates.max()) else null,
                    if (rates.isNotEmpty()) f(rates.min()) else null,
                    if (rates.isNotEmpty()) f(Np.median(rates.toDoubleArray())) else null,
                    if (rates.isNotEmpty()) f(rates.count { it > 0 }.toDouble() / rates.size) else null,
                ),
            )
        }
        return out
    }

    fun startDateHeatmap(closes: Ser, amount: Double, kw: SipKw, durations: List<Int> = listOf(1, 3, 5, 7, 10)): StartDateHeatmap {
        if (closes.size == 0) return StartDateHeatmap(emptyList(), emptyList(), emptyList())
        val first = closes.dates.first(); val last = closes.dates.last()
        var starts = ArrayList<LocalDate>()
        var cursor = LocalDate.of(first.year, first.monthValue, 1)
        while (cursor <= last) { starts.add(cursor); cursor = cursor.plusMonths(1) }
        val usable = durations.filter { it != 0 }
        while (starts.isNotEmpty() && starts.size * usable.size > MAX_GRID_CELLS) starts = ArrayList(starts.filterIndexed { i, _ -> i % 2 == 0 })
        val values = starts.map { s ->
            usable.map { span ->
                val end = LocalDate.of(s.year + span, s.monthValue, 1)
                if (end <= last) f(sipXirr(closes, s, minOf(end, last), amount, kw)) else null
            }
        }
        return StartDateHeatmap(
            starts.map { "${it.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)} ${it.year}" },
            usable.map { "${it}Y" }, values,
        )
    }

    fun sipDateHeatmap(closes: Ser, start: LocalDate, end: LocalDate, amount: Double, kw: SipKw): SipDateHeatmap {
        val days = (1..28).toList()
        val rates = days.map { f(sipXirr(closes, start, end, amount, kw, dayOfMonth = it)) }
        val clean = rates.filterNotNull()
        return SipDateHeatmap(
            days, rates,
            if (clean.isNotEmpty()) days[rates.indexOf(clean.max())] else null,
            if (clean.isNotEmpty()) days[rates.indexOf(clean.min())] else null,
            if (clean.isNotEmpty()) f(clean.max() - clean.min()) else null,
        )
    }

    fun frequencyComparison(closes: Ser, start: LocalDate, end: LocalDate, monthlyAmount: Double, kw: SipKw): List<FrequencyRow> {
        val perYear = linkedMapOf("weekly" to 52, "fortnightly" to 26, "monthly" to 12, "quarterly" to 4)
        val annual = monthlyAmount * 12
        val out = ArrayList<FrequencyRow>()
        for ((freq, count) in perYear) {
            val amount = annual / count
            val r = try { sip(closes, start, end, amount, kw, frequency = freq) } catch (e: RuntimeException) { continue }
            out.add(FrequencyRow(freq, f(amount), r.installmentCount, f(r.totalInvested), f(r.finalValue), f(Xirr.xirrOrNull(r.cashFlows)), f(r.averageCost), f(r.charges)))
        }
        return out
    }

    fun yearly(r: SipRun): List<SipYear> {
        val out = ArrayList<SipYear>()
        var i = 0
        while (i < r.dates.size) {
            val year = r.dates[i].year
            var j = i
            while (j + 1 < r.dates.size && r.dates[j + 1].year == year) j++
            val added = r.invested[j] - r.invested[i]
            out.add(SipYear(year, f(added), f(r.invested[j]), f(r.value[j]), f(r.value[j] - r.invested[j]), f(r.value[j] - r.value[i] - added)))
            i = j + 1
        }
        return out
    }

    fun monthlyHeatmap(r: SipRun): MonthlyHeatmap {
        val value = Openstatz.resample(Ser(r.dates, r.value), "ME", Double.NaN) { it.last() }
        val invested = Openstatz.resample(Ser(r.dates, r.invested), "ME", Double.NaN) { it.last() }
        val grid = java.util.TreeMap<Int, Array<Double?>>()
        for (i in value.dates.indices) {
            val pct = if (i == 0) Double.NaN else {
                val prior = value.v[i - 1]
                val added = invested.v[i] - invested.v[i - 1]
                val market = (value.v[i] - prior) - added
                market / (if (prior != 0.0) prior else Double.NaN)
            }
            val dt = value.dates[i]
            grid.getOrPut(dt.year) { arrayOfNulls(12) }[dt.monthValue - 1] = f(pct)
        }
        return MonthlyHeatmap(grid.keys.map { it.toString() }, MONTHS, grid.values.map { it.toList() }, "close-to-close, contribution removed")
    }

    fun lumpsumComparison(closes: Ser, r: SipRun, start: LocalDate, end: LocalDate, costs: CostModel?): LumpsumComparison? {
        val lump = try { SipEngine.lumpsum(closes, start, end, r.totalInvested, costs) } catch (e: RuntimeException) { return null }
        return LumpsumComparison(
            f(lump.invested), f(lump.finalValue), f(Xirr.xirrOrNull(lump.cashFlows)), f(lump.entryPrice), lump.entryDate,
            f(r.finalValue), f(lump.finalValue - r.finalValue), r.finalValue > lump.finalValue,
        )
    }

    private fun case(closes: Ser, start: LocalDate, end: LocalDate, amount: Double, kw: SipKw): SipCase? {
        val r = try { sip(closes, start, end, amount, kw) } catch (e: RuntimeException) { return null }
        return SipCase(
            start, f(r.totalInvested), f(r.finalValue), r.installmentCount, f(Xirr.xirrOrNull(r.cashFlows)),
            if (r.totalInvested != 0.0) f(r.finalValue / r.totalInvested) else null,
            if (r.totalInvested != 0.0) f((r.finalValue - r.totalInvested) / r.totalInvested) else null,
            f(r.averageCost),
        )
    }

    fun crisis(closes: Ser, amount: Double, kw: SipKw, periods: List<CrisisPeriod> = Crises.INDIA): List<SipCrisisRow> {
        if (closes.size == 0) return emptyList()
        val first = closes.dates.first(); val last = closes.dates.last()
        val out = ArrayList<SipCrisisRow>()
        for (p in periods) {
            val cs = LocalDate.parse(p.start); val ce = LocalDate.parse(p.end)
            if (cs < first || ce > last) continue
            if (ChronoUnit.DAYS.between(ce, last) < MIN_SIP_DAYS) continue
            val into = case(closes, cs, last, amount, kw) ?: continue
            val waited = case(closes, ce, last, amount, kw) ?: continue
            val win = closes.v.filterIndexed { i, _ -> closes.dates[i] >= cs && closes.dates[i] <= ce }
            val move = if (win.size >= 2) win.last() / win.first() - 1.0 else null
            val trough = if (win.size >= 2) win.min() / win.first() - 1.0 else null
            val ix = into.xirr; val wx = waited.xirr
            out.add(
                SipCrisisRow(
                    p.key, p.label, p.note, p.scope, p.start, p.end, f(move), f(trough), into, waited,
                    if (ix != null && wx != null) f(ix - wx) else null, ix != null && wx != null && ix > wx,
                ),
            )
        }
        return out.sortedByDescending { it.crisisStart }
    }

    fun crisisSummary(rows: List<SipCrisisRow>): SipCrisisSummary {
        val scored = rows.filter { it.xirrAdvantage != null }
        if (scored.isEmpty()) return SipCrisisSummary(0, 0, null, null)
        val wins = scored.count { it.startingEarlyWon }
        val adv = scored.map { it.xirrAdvantage!! }.sorted()
        val mid = adv.size / 2
        val median = if (adv.size % 2 == 1) adv[mid] else (adv[mid - 1] + adv[mid]) / 2
        return SipCrisisSummary(scored.size, wins, f(wins.toDouble() / scored.size), f(median))
    }
}
