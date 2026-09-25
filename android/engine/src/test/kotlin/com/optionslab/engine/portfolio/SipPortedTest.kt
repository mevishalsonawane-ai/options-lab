package com.optionslab.engine.portfolio

import com.optionslab.engine.portfolio.PortfolioPortedTest.Companion.gauss
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** IraAlgo's own `test/test_sip_*.py`, ported. */
class SipPortedTest {
    private fun date(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d)
    private fun approx(a: Double, b: Double, rel: Double = 1e-6, absTol: Double = 1e-9) = assertTrue(abs(a - b) <= rel * abs(b) + absTol, "$a vs $b")

    private fun bdays(start: String, end: String) = generateSequence(LocalDate.parse(start)) { it.plusDays(1) }
        .takeWhile { it <= LocalDate.parse(end) }.filter { it.dayOfWeek.value <= 5 }.toList()

    private fun prices(values: List<Double>, start: String = "2020-01-01") =
        Ser(PortfolioPortedTest.bdays(start, values.size), values.toDoubleArray())

    private fun flat(n: Int = 300, price: Double = 100.0) = prices(List(n) { price })

    private fun sip(px: Ser, start: LocalDate, end: LocalDate, amount: Double, frequency: String = "monthly", day: Int = 1,
                    stepUp: Double = 0.0, costs: CostModel? = null, pct: Double = 0.0, flatFee: Double = 0.0) =
        SipEngine.run(px, start, end, amount, frequency, day, stepUp, costs, pct, flatFee)

    private fun india(exchange: String = "NSE") = CostInputs(costExchange = exchange).build(0.0, 0.0)

    // ── schedule ────────────────────────────────────────────────────────────

    @Test fun `monthly dates land on the requested day, quarterly steps three months`() {
        assertEquals(listOf("2020-01-15", "2020-02-15", "2020-03-15", "2020-04-15", "2020-05-15", "2020-06-15"),
            SipSchedule.requestedDates(date(2020, 1, 1), date(2020, 6, 30), "monthly", 15).map { it.toString() })
        assertEquals(listOf(1, 4, 7, 10), SipSchedule.requestedDates(date(2020, 1, 1), date(2020, 12, 31), "quarterly", 1).map { it.monthValue })
        for ((freq, gap) in listOf("weekly" to 7L, "fortnightly" to 14L)) {
            val d = SipSchedule.requestedDates(date(2020, 1, 1), date(2020, 3, 1), freq)
            assertTrue((0 until d.size - 1).all { ChronoUnit.DAYS.between(d[it], d[it + 1]) == gap })
        }
    }

    @Test fun `days after the 28th are rejected, not clamped`() {
        assertTrue("28" in assertFailsWith<SipException> { SipSchedule.requestedDates(date(2020, 1, 1), date(2020, 6, 1), "monthly", 31) }.message!!)
    }

    @Test fun `step up raises on each anniversary, not the calendar year`() {
        val a = SipSchedule.applyStepUp(listOf(date(2020, 10, 1), date(2021, 4, 1), date(2021, 10, 5), date(2022, 10, 5)), 10000.0, 10.0)
        approx(a[0], 10000.0); approx(a[1], 10000.0); approx(a[2], 11000.0); approx(a[3], 12100.0)
        assertEquals(setOf(5000.0), SipSchedule.applyStepUp(SipSchedule.requestedDates(date(2020, 1, 1), date(2023, 1, 1), "monthly", 1), 5000.0, 0.0).toSet())
    }

    @Test fun `a holiday rolls to the next session, both dates kept, never past the window`() {
        val s = SipSchedule.build(bdays("2020-01-01", "2021-12-31"), date(2020, 1, 1), date(2020, 3, 31), 5000.0, "monthly", 5)
        assertEquals(date(2020, 1, 5), s[0].requested); assertEquals(date(2020, 1, 6), s[0].executed)
        val year = SipSchedule.build(bdays("2020-01-01", "2021-12-31"), date(2020, 1, 1), date(2020, 12, 31), 5000.0)
        assertTrue(year.any { it.requested != it.executed }); assertTrue(year.all { it.executed >= it.requested })
        assertTrue(SipSchedule.build(bdays("2020-01-01", "2021-12-31"), date(2020, 1, 1), date(2020, 6, 30), 5000.0).all { it.executed <= date(2020, 6, 30) })
        val stepped = SipSchedule.build(bdays("2020-01-01", "2022-12-31"), date(2020, 1, 1), date(2022, 12, 31), 10000.0, stepUpPercent = 10.0)
        approx(stepped.first().amount, 10000.0); approx(stepped.last().amount, 12100.0)
    }

    @Test fun `invalid schedules are rejected`() {
        val sessions = bdays("2020-01-01", "2021-12-31")
        assertTrue("positive" in assertFailsWith<SipException> { SipSchedule.build(sessions, date(2020, 1, 1), date(2020, 6, 1), 0.0) }.message!!)
        assertTrue("frequency must be" in assertFailsWith<SipException> { SipSchedule.build(sessions, date(2020, 1, 1), date(2020, 6, 1), 5000.0, "daily") }.message!!)
        assertFailsWith<SipException> { SipSchedule.build(sessions, date(2020, 1, 2), date(2020, 1, 3), 5000.0, "monthly", 15) }
    }

    // ── xirr ────────────────────────────────────────────────────────────────

    private fun flows(vararg f: Pair<String, Double>) = f.map { Pair(LocalDate.parse(it.first), it.second) }

    @Test fun `xirr matches the excel reference and zeroes its own npv`() {
        approx(Xirr.xirr(flows("2008-01-01" to -10000.0, "2008-03-01" to 2750.0, "2008-10-30" to 4250.0, "2009-02-15" to 3250.0, "2009-04-01" to 2750.0)), 0.373362535, 0.0, 1e-6)
        val f = (1..12).map { Pair(LocalDate.of(2020, it, 1), -5000.0) } + Pair(LocalDate.of(2021, 1, 1), 64000.0)
        assertTrue(abs(Xirr.npv(Xirr.xirr(f), f, f[0].first)) < 1e-6)
        assertTrue(abs(Xirr.xirr(flows("2020-01-01" to -100000.0, "2023-01-01" to 100000.0))) < 1e-9)
        approx(Xirr.xirr(flows("2021-01-01" to -1000.0, "2022-01-01" to 2000.0)), 1.0)
        val leap = Xirr.xirr(flows("2020-01-01" to -1000.0, "2021-01-01" to 2000.0))
        approx(leap, 2.0.pow(365.0 / 366) - 1); assertTrue(leap < 1.0)
        val loss = Xirr.xirr(flows("2020-01-01" to -100000.0, "2022-01-01" to 60000.0))
        assertTrue(loss > -1.0 && loss < 0.0)
    }

    @Test fun `undefined xirr raises rather than inventing a number`() {
        for (f in listOf(flows(), flows("2020-01-01" to -1000.0), flows("2020-01-01" to -1000.0, "2020-01-01" to 2000.0),
            flows("2020-01-01" to -1000.0, "2021-01-01" to -2000.0), flows("2020-01-01" to 1000.0, "2021-01-01" to 2000.0))) {
            assertFailsWith<XirrException> { Xirr.xirr(f) }
        }
        assertNull(Xirr.xirrOrNull(flows("2020-01-01" to -1000.0)))
        assertNotNull(Xirr.xirrOrNull(flows("2020-01-01" to -1000.0, "2021-01-01" to 1100.0)))
        approx(Xirr.absoluteReturn(600000.0, 942310.0), 0.5705, 0.0, 1e-4)
        assertEquals(0.0, Xirr.absoluteReturn(0.0, 100.0))
    }

    // ── engine ──────────────────────────────────────────────────────────────

    @Test fun `units accumulate at the close and value is units times price`() {
        val s = sip(flat(), date(2020, 1, 1), date(2020, 6, 30), 10000.0)
        assertEquals(6, s.installmentCount); approx(s.totalInvested, 60000.0); approx(s.totalUnits, 600.0)
        approx(s.finalValue, 60000.0); approx(s.value.last(), s.totalUnits * 100.0)
        val invested = s.cashFlows.filter { it.second < 0 }
        assertEquals(6, invested.size); assertTrue(invested.all { it.second == -10000.0 })
        assertEquals(s.dates.last(), s.cashFlows.last().first); approx(s.cashFlows.last().second, s.finalValue)
        approx(s.averageCost, s.averagePrice)
        assertTrue((1 until s.invested.size).all { s.invested[it] >= s.invested[it - 1] }); approx(s.invested.last(), s.totalInvested)
    }

    @Test fun `rupee cost averaging never pays more than the average price`() {
        val volatile = prices(List(40) { listOf(100.0, 80.0, 120.0, 60.0, 140.0, 90.0, 110.0, 70.0) }.flatten())
        val s = sip(volatile, date(2020, 1, 1), date(2021, 6, 30), 10000.0)
        assertTrue(s.averageCost <= s.averagePrice + 1e-9); assertTrue(s.averageCost < s.averagePrice)
    }

    @Test fun `charges reduce units, are per installment and on the value traded`() {
        val free = sip(flat(), date(2020, 1, 1), date(2020, 6, 30), 10000.0)
        val paid = sip(flat(), date(2020, 1, 1), date(2020, 6, 30), 10000.0, pct = 1.0)
        assertEquals(free.totalInvested, paid.totalInvested); assertTrue(paid.totalUnits < free.totalUnits)
        approx(paid.charges, 99.0 * 6)
        approx(sip(flat(), date(2020, 1, 1), date(2020, 6, 30), 10000.0, flatFee = 20.0).charges, 120.0)
    }

    @Test fun `step up and every frequency`() {
        val s = sip(flat(400), date(2020, 1, 1), date(2021, 6, 30), 10000.0, stepUp = 10.0)
        assertTrue(s.installments.last().amount > s.installments.first().amount)
        assertTrue(s.totalInvested > 10000 * s.installmentCount * 0.99)
        for ((freq, min) in listOf("weekly" to 20, "fortnightly" to 10, "monthly" to 5, "quarterly" to 1)) {
            assertTrue(sip(flat(400), date(2020, 1, 1), date(2020, 6, 30), 5000.0, freq).installmentCount >= min)
        }
    }

    @Test fun `no sessions, or no affordable share, is an error`() {
        assertTrue("no trading sessions" in assertFailsWith<SipException> { sip(flat(), date(2030, 1, 1), date(2030, 6, 30), 10000.0) }.message!!)
        assertTrue("afford a single share" in assertFailsWith<SipException> { sip(prices(List(200) { 100_000.0 }), date(2020, 1, 1), date(2020, 6, 30), 1000.0) }.message!!)
    }

    @Test fun `lumpsum deploys everything on day one and wins a steady rise`() {
        val lump = SipEngine.lumpsum(flat(), date(2020, 1, 1), date(2020, 6, 30), 60000.0, null)
        assertEquals(600L, lump.units); approx(lump.finalValue, 60000.0); assertEquals(2, lump.cashFlows.size)
        val rising = prices(List(300) { 100.0 + it })
        val s = sip(rising, date(2020, 1, 1), date(2021, 1, 31), 10000.0)
        assertTrue(SipEngine.lumpsum(rising, date(2020, 1, 1), date(2021, 1, 31), s.totalInvested, null).finalValue > s.finalValue)
    }

    @Test fun `statutory charges per installment, itemised, BSE differs only in the exchange fee`() {
        val s = sip(flat(), date(2020, 1, 1), date(2020, 6, 30), 10000.0, costs = india())
        assertEquals(6, s.installmentCount)
        val traded = s.totalInvested - s.cash - s.charges
        approx(s.charges, traded * 0.0011872, 5e-3); assertTrue(s.charges < 11.87 * 6)
        for (line in listOf("stt", "exchange_txn", "sebi", "stamp_duty", "tax")) assertTrue(s.chargeBreakdown.getValue(line) > 0, line)
        approx(s.chargeBreakdown.getValue("stt"), traded * 0.001, 1e-6)
        val bse = sip(flat(), date(2020, 1, 1), date(2020, 6, 30), 10000.0, costs = india("BSE"))
        assertTrue(bse.chargeBreakdown.getValue("exchange_txn") > s.chargeBreakdown.getValue("exchange_txn"))
        approx(bse.chargeBreakdown.getValue("stt"), s.chargeBreakdown.getValue("stt"))
        val free = sip(flat(), date(2020, 1, 1), date(2020, 6, 30), 10000.0)
        assertEquals(free.totalInvested, s.totalInvested); assertTrue(s.totalUnits < free.totalUnits); assertTrue(s.finalValue < free.finalValue)
        val lump = SipEngine.lumpsum(flat(), date(2020, 1, 1), date(2020, 6, 30), 60000.0, india())
        val t = lump.units * 100.0
        assertTrue(t > 0)
        approx(lump.finalValue, t + (60000 - t - t * 0.0011872), 1e-3)
    }

    @Test fun `whole shares only, nothing created or lost, remainder carries`() {
        val px = prices(List(200) { 1500.0 })
        val s = sip(px, date(2020, 1, 1), date(2020, 6, 30), 10000.0)
        assertEquals(40.0, s.totalUnits); approx(s.totalUnits * 1500.0 + s.cash + s.charges, s.totalInvested)
        approx(s.averageCost, 1500.0)
        val year = sip(prices(List(400) { 1500.0 }), date(2020, 1, 1), date(2020, 12, 31), 10000.0)
        assertTrue(year.totalUnits > 6 * year.installmentCount)
        approx(sip(prices(List(400) { 1500.0 }), date(2020, 1, 1), date(2020, 1, 31), 10000.0).cash, 1000.0)
        val five = sip(px, date(2020, 1, 1), date(2020, 5, 31), 10000.0)
        assertTrue(five.cash > 0); approx(five.finalValue, five.totalUnits * 1500.0 + five.cash); approx(five.finalValue, five.totalInvested)
        assertEquals(40L, SipEngine.lumpsum(px, date(2020, 1, 1), date(2020, 6, 30), 60000.0, null).units)
        val dear = sip(prices(List(400) { 12000.0 }), date(2020, 1, 1), date(2020, 12, 31), 5000.0)
        assertTrue(dear.totalUnits >= 4)
    }

    // ── analytics ───────────────────────────────────────────────────────────

    private fun walk(seed: Long, start: String, n: Int): Ser {
        val r = gauss(seed, n, 0.0004, 0.012)
        var p = 100.0
        return prices(List(n) { p *= 1 + r[it]; p }, start)
    }

    @Test fun `a zero prior close yields a gap, the first month is never flat`() {
        val r = sip(walk(5, "2019-01-01", 252 * 3), date(2019, 1, 20), date(2021, 12, 31), 10000.0)
        val grid = SipAnalyticsCalc.monthlyHeatmap(r)
        assertNull(grid.values[0][0]); assertNull(grid.values[0][1])
        assertTrue("close-to-close" in grid.basis)
    }

    @Test fun `contribution is removed from the market figure`() {
        val r = sip(flat(300), date(2020, 1, 1), date(2020, 12, 31), 10000.0)
        val cells = SipAnalyticsCalc.monthlyHeatmap(r).values.flatten().filterNotNull()
        assertTrue(cells.isNotEmpty()); cells.forEach { assertTrue(abs(it) <= 1e-12) }
    }

    @Test fun `every metric is finite or null`() {
        val px = walk(11, "2019-01-01", 252 * 5)
        val r = sip(px, date(2019, 1, 1), date(2023, 12, 31), 10000.0)
        val all = Fixtures.tree(listOf(
            SipAnalyticsCalc.headline(r), SipAnalyticsCalc.underwater(r), SipAnalyticsCalc.drawdown(r), SipAnalyticsCalc.yearly(r),
            SipAnalyticsCalc.monthlyHeatmap(r),
            SipAnalyticsCalc.frequencyComparison(px, date(2019, 1, 1), date(2023, 12, 31), 10000.0, SipKw("monthly", 1, 0.0, null)),
        ))
        fun visit(x: Any?) { when (x) { is Double -> assertTrue(x.isFinite()); is Map<*, *> -> x.values.forEach(::visit); is List<*> -> x.forEach(::visit) } }
        visit(all)
    }

    // ── crisis ──────────────────────────────────────────────────────────────

    private val crash = CrisisPeriod("test_crash", "Test Crash", "2020-02-19", "2020-03-23", "", "global")

    private fun shocked(start: String = "2015-01-01", end: String = "2025-06-30", shock: Boolean = true): Ser {
        val dates = bdays(start, end)
        val r = gauss(4, dates.size, 0.0006, 0.011)
        dates.forEachIndexed { i, d -> if (shock && d >= date(2020, 2, 19) && d <= date(2020, 3, 23)) r[i] -= 0.012 }
        var p = 100.0
        return Ser(dates, DoubleArray(dates.size) { p *= 1 + r[it]; p })
    }

    private val kw = SipKw("monthly", 1, 0.0, null)

    @Test fun `both crisis variants run to the same end, starting into it buys cheaper`() {
        val rows = SipAnalyticsCalc.crisis(shocked(), 10000.0, kw, listOf(crash))
        assertEquals(1, rows.size)
        val row = rows[0]
        assertEquals(date(2020, 2, 19), row.startedIntoIt.start); assertEquals(date(2020, 3, 23), row.waitedForTheEnd.start)
        assertTrue(row.startedIntoIt.installments > row.waitedForTheEnd.installments)
        assertTrue(row.startedIntoIt.averageCost!! < row.waitedForTheEnd.averageCost!!)
        assertTrue(row.marketMove!! < 0); assertTrue(row.marketTrough!! <= row.marketMove!!)
    }

    @Test fun `crises outside the data or without runway are dropped`() {
        assertTrue(SipAnalyticsCalc.crisis(shocked("2022-01-01"), 10000.0, kw, listOf(crash)).isEmpty())
        val late = CrisisPeriod("late", "Late Crash", "2025-05-01", "2025-05-20", "", "global")
        assertTrue(SipAnalyticsCalc.crisis(shocked(shock = false), 10000.0, kw, listOf(late)).isEmpty())
        assertTrue(SipAnalyticsCalc.crisis(Ser(emptyList(), DoubleArray(0)), 10000.0, kw).isEmpty())
        assertEquals(0, SipAnalyticsCalc.crisisSummary(emptyList()).crises)
    }

    @Test fun `crisis rows are newest first and the summary counts`() {
        val rows = SipAnalyticsCalc.crisis(shocked(shock = false), 10000.0, kw)
        assertEquals(rows.map { it.crisisStart }.sortedDescending(), rows.map { it.crisisStart })
        val s = SipAnalyticsCalc.crisisSummary(rows)
        assertEquals(rows.count { it.xirrAdvantage != null }, s.crises)
        assertTrue(s.earlyWins in 0..s.crises); approx(s.share!!, s.earlyWins.toDouble() / s.crises)
    }

    // ── service validation ──────────────────────────────────────────────────

    private val infy = mapOf("INFY" to bdays("2024-01-01", "2024-04-30").map { DailyBar(it, 100.0, 100.0, 100.0, 100.0) })

    private fun request(amount: Double = 1000.0, frequency: String = "monthly", day: Int = 1) = SipRequest(
        "INFY", "NSE", date(2024, 1, 1), date(2024, 4, 30), amount, frequency, day, includeGrids = false,
    )

    @Test fun `invalid amounts and days are refused before any price is read`() {
        for (a in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.0, -1.0)) {
            val e = assertFailsWith<PortfolioException> { SipBacktest.run(request(amount = a), emptyMap()) }
            assertEquals(400, e.status); assertEquals("amount must be a positive number", e.message)
        }
        for ((freq, day) in listOf("monthly" to 0, "monthly" to 29, "quarterly" to 0, "quarterly" to 29)) {
            val e = assertFailsWith<PortfolioException> { SipBacktest.run(request(frequency = freq, day = day), emptyMap()) }
            assertEquals(400, e.status); assertEquals("day_of_month must be an integer between 1 and 28", e.message)
        }
        for (day in listOf(1, 28)) assertEquals(4, SipBacktest.run(request(day = day), infy).installments.size)
    }
}
