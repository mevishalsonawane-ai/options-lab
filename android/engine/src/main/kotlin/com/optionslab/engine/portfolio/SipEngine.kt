package com.optionslab.engine.portfolio

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.pow

/** A SIP schedule that cannot be built (IraAlgo's `ScheduleError`) or a SIP that cannot be simulated (`SipError`). */
class SipException(message: String) : IllegalArgumentException(message)

/** One scheduled investment: the date asked for and the session it executed on. */
data class Installment(val requested: LocalDate, val executed: LocalDate, val amount: Double)

/**
 * SIP dates and amounts. Port of `sip/schedule.py`: monthly/quarterly on a
 * day 1-28 (never clamped), weekly/fortnightly from the start date, a holiday
 * rolls to the next session, a roll past the end drops the installment, and
 * step-up applies on each anniversary of the first installment.
 */
object SipSchedule {
    val FREQUENCIES = listOf("monthly", "fortnightly", "weekly", "quarterly")
    const val MAX_DAY_OF_MONTH = 28

    private fun monthAdd(anchor: LocalDate, months: Int): LocalDate {
        val total = anchor.monthValue - 1 + months
        return LocalDate.of(anchor.year + Math.floorDiv(total, 12), Math.floorMod(total, 12) + 1, anchor.dayOfMonth)
    }

    fun requestedDates(start: LocalDate, end: LocalDate, frequency: String = "monthly", dayOfMonth: Int = 1): List<LocalDate> {
        if (frequency !in FREQUENCIES) {
            throw SipException("frequency must be one of ${FREQUENCIES.joinToString(", ")}, got '$frequency'")
        }
        if (end < start) throw SipException("end date is before start date")
        if (frequency == "monthly" || frequency == "quarterly") {
            if (dayOfMonth !in 1..MAX_DAY_OF_MONTH) {
                throw SipException("day_of_month must be between 1 and $MAX_DAY_OF_MONTH; days after the 28th do not exist in every month")
            }
            val step = if (frequency == "monthly") 1 else 3
            var anchor = LocalDate.of(start.year, start.monthValue, dayOfMonth)
            if (anchor < start) anchor = monthAdd(anchor, step)
            val out = ArrayList<LocalDate>()
            var i = 0
            while (true) {
                val w = monthAdd(anchor, i * step)
                if (w > end) break
                out.add(w); i++
            }
            return out
        }
        val delta = if (frequency == "weekly") 7L else 14L
        val out = ArrayList<LocalDate>()
        var w = start
        while (w <= end) { out.add(w); w = w.plusDays(delta) }
        return out
    }

    fun applyStepUp(dates: List<LocalDate>, baseAmount: Double, stepUpPercent: Double = 0.0): List<Double> {
        if (dates.isEmpty()) return emptyList()
        if (stepUpPercent < 0) throw SipException("step_up_percent cannot be negative")
        val first = dates.first()
        return dates.map {
            val yearsElapsed = Math.floorDiv(ChronoUnit.DAYS.between(first, it), 365L)
            baseAmount * (1.0 + stepUpPercent / 100.0).pow(yearsElapsed.toDouble())
        }
    }

    fun build(
        sessions: List<LocalDate>, start: LocalDate, end: LocalDate, amount: Double,
        frequency: String = "monthly", dayOfMonth: Int = 1, stepUpPercent: Double = 0.0,
    ): List<Installment> {
        if (amount <= 0) throw SipException("SIP amount must be positive")
        if (sessions.isEmpty()) throw SipException("no trading sessions available for this period")
        val wanted = requestedDates(start, end, frequency, dayOfMonth)
        if (wanted.isEmpty()) throw SipException("the SIP window is too short to contain a single installment")
        val amounts = applyStepUp(wanted, amount, stepUpPercent)
        val out = ArrayList<Installment>()
        var cursor = 0
        for ((i, w) in wanted.withIndex()) {
            while (cursor < sessions.size && sessions[cursor] < w) cursor++
            if (cursor >= sessions.size) break
            val executed = sessions[cursor]
            if (executed > end) break
            out.add(Installment(w, executed, amounts[i]))
        }
        if (out.isEmpty()) throw SipException("no SIP installment could be placed on a trading session in this window")
        return out
    }
}

/** A completed SIP: dated cash flows (investments negative, terminal value positive) and daily curves. */
class SipRun internal constructor(
    val cashFlows: List<Pair<LocalDate, Double>>,
    val dates: List<LocalDate>,
    val value: DoubleArray,
    val invested: DoubleArray,
    val units: DoubleArray,
    val installments: List<Installment>,
    val totalInvested: Double,
    val finalValue: Double,
    val totalUnits: Double,
    val averageCost: Double,
    val averagePrice: Double,
    val cash: Double,
    val charges: Double,
    val chargeBreakdown: Map<String, Double>,
    val warnings: List<String>,
) {
    val installmentCount get() = installments.size
}

/**
 * The SIP simulation. Port of `sip/engine.py`: whole shares only (Indian cash
 * equity has no fractions), the unspent remainder carried to the next
 * installment, charges per installment from the same cost schedule the
 * portfolio uses (one buy order each), marked to market every session.
 */
object SipEngine {
    private fun charge(amount: Double, costs: CostModel?, brokeragePercent: Double, brokerageFlat: Double): Double = when (costs) {
        is CostSchedule -> costs.charge(amount, 0.0, 1)
        // IraAlgo passes its flat model here too and crashes (it has no .charge), answering 500.
        is FlatCosts -> throw PortfolioException(500, "SIP backtest failed.")
        null -> amount * (brokeragePercent / 100.0) + brokerageFlat
    }

    private fun window(closes: Ser, start: LocalDate, end: LocalDate): Ser {
        val idx = closes.dates.indices.filter { !closes.v[it].isNaN() && closes.dates[it] >= start && closes.dates[it] <= end }
        return Ser(idx.map { closes.dates[it] }, DoubleArray(idx.size) { closes.v[idx[it]] })
    }

    internal fun run(
        closes: Ser, start: LocalDate, end: LocalDate, amount: Double,
        frequency: String = "monthly", dayOfMonth: Int = 1, stepUpPercent: Double = 0.0,
        costs: CostModel? = null, brokeragePercent: Double = 0.0, brokerageFlat: Double = 0.0,
        warnings: List<String> = emptyList(), symbol: String = "x",
    ): SipRun {
        if (closes.size == 0) throw SipException("no price data for this symbol and period")
        if (closes.v.all { it.isNaN() }) throw SipException("price series is empty after dropping missing sessions")
        val w = window(closes, start, end)
        if (w.size == 0) throw SipException("no trading sessions between $start and $end for $symbol")
        if (w.v.any { it <= 0 }) throw SipException("price series contains non-positive closes")
        val installments = SipSchedule.build(w.dates, start, end, amount, frequency, dayOfMonth, stepUpPercent)
        val priceOn = HashMap<LocalDate, Double>()
        w.dates.forEachIndexed { i, d -> priceOn[d] = w.v[i] }

        val unitsBy = HashMap<LocalDate, Double>()
        val cashBy = HashMap<LocalDate, Double>()
        val investedBy = HashMap<LocalDate, Double>()
        var totalUnits = 0.0
        var cash = 0.0
        var totalInvested = 0.0
        var totalCharges = 0.0
        val breakdown = LinkedHashMap<String, Double>()
        val flows = ArrayList<Pair<LocalDate, Double>>()
        for (inst in installments) {
            val price = priceOn.getValue(inst.executed)
            val budget = cash + inst.amount
            var shares = Py.floorDiv(budget, price).toLong()
            var c = charge(shares * price, costs, brokeragePercent, brokerageFlat)
            while (shares > 0 && shares * price + c > budget) {
                shares -= 1
                c = charge(shares * price, costs, brokeragePercent, brokerageFlat)
            }
            if (shares == 0L) c = 0.0
            val spent = shares * price
            cash = budget - spent - c
            totalUnits += shares
            totalInvested += inst.amount
            totalCharges += c
            if (costs is CostSchedule && shares != 0L) {
                for ((k, v) in costs.breakdown(spent, 0.0, 1)) if (k != "orders") breakdown[k] = (breakdown[k] ?: 0.0) + v
            }
            unitsBy[inst.executed] = totalUnits
            cashBy[inst.executed] = cash
            investedBy[inst.executed] = totalInvested
            flows.add(Pair(inst.executed, -inst.amount))
        }
        fun ffill(m: Map<LocalDate, Double>): DoubleArray {
            var last = 0.0
            return DoubleArray(w.size) { i -> m[w.dates[i]]?.let { last = it }; last }
        }
        val units = ffill(unitsBy)
        val invested = ffill(investedBy)
        val cashHeld = ffill(cashBy)
        val value = DoubleArray(w.size) { units[it] * w.v[it] + cashHeld[it] }
        val finalValue = value.last()
        flows.add(Pair(w.dates.last(), finalValue))
        if (totalUnits == 0.0) {
            throw SipException(
                "no installment could afford a single share: the price exceeds the amount available after charges. Increase the installment.",
            )
        }
        val deployed = totalInvested - totalCharges - cash
        val averageCost = if (totalUnits > 0) deployed / totalUnits else 0.0
        val averagePrice = Py.sum(installments.map { priceOn.getValue(it.executed) }) / installments.size
        return SipRun(
            flows, w.dates, value, invested, units, installments, totalInvested, finalValue, totalUnits,
            averageCost, averagePrice, cash, totalCharges, breakdown, warnings,
        )
    }

    /** The same total money deployed on the first session, whole shares, remainder kept as cash. */
    internal class Lumpsum(val invested: Double, val finalValue: Double, val units: Long, val entryPrice: Double, val entryDate: LocalDate, val cashFlows: List<Pair<LocalDate, Double>>)

    internal fun lumpsum(closes: Ser, start: LocalDate, end: LocalDate, amount: Double, costs: CostModel?): Lumpsum {
        val w = window(closes, start, end)
        if (w.size == 0) throw SipException("no sessions available for the lumpsum comparison")
        val entry = w.v[0]
        var units = Py.floorDiv(amount, entry).toLong()
        var c = charge(units * entry, costs, 0.0, 0.0)
        while (units > 0 && units * entry + c > amount) {
            units -= 1
            c = charge(units * entry, costs, 0.0, 0.0)
        }
        val residual = amount - units * entry - c
        val finalValue = units * w.v.last() + residual
        return Lumpsum(amount, finalValue, units, entry, w.dates[0], listOf(Pair(w.dates[0], -amount), Pair(w.dates.last(), finalValue)))
    }
}
