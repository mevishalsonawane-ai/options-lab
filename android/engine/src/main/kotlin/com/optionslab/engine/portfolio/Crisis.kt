package com.optionslab.engine.portfolio

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** A named stress window; `scope` is "india" for a domestic event, "global" otherwise. */
data class CrisisPeriod(val key: String, val label: String, val start: String, val end: String, val note: String = "", val scope: String = "global")

data class CrisisRow(
    val key: String, val label: String, val note: String, val start: LocalDate, val end: LocalDate,
    val portfolio: Double?, val benchmark: Double?, val excess: Double?, val days: Int, val sessions: Int,
    val coverage: Double?, val partial: Boolean, val scope: String,
)
data class ScopeSummary(val count: Int, val averageReturn: Double?, val hitRate: Double?)
data class CrisisSummary(
    val count: Int, val averageReturn: Double?, val hitRate: Double?, val worst: Double?, val best: Double?,
    val byScope: Map<String, ScopeSummary>,
)
data class CrisisAnalysis(val periods: List<CrisisRow>, val summary: CrisisSummary?)

/** Port of `portfolio/crisis.py`: portfolio vs benchmark across Indian stress windows. */
object Crises {
    val INDIA: List<CrisisPeriod> = listOf(
        CrisisPeriod("bear_1995", "1995-96 Bear Market", "1995-01-02", "1996-01-31", "The long slide after the 1994 peak", "india"),
        CrisisPeriod("asian_crisis", "Asian Financial Crisis", "1997-07-02", "1998-09-30", "Baht float and the contagion that followed", "global"),
        CrisisPeriod("pokhran", "Pokhran-II Sanctions", "1998-05-11", "1998-06-30", "Nuclear tests and the sanctions that followed", "india"),
        CrisisPeriod("russia_ltcm", "Russia Default / LTCM", "1998-08-03", "1998-10-30", "Sovereign default and a hedge-fund collapse", "global"),
        CrisisPeriod("kargil", "Kargil Conflict", "1999-05-03", "1999-07-26", "Border conflict; the market recovered before it ended", "india"),
        CrisisPeriod("dotcom", "Dot-com Crash", "2000-02-14", "2001-09-21", "Global technology unwind", "global"),
        CrisisPeriod("gfc", "Global Financial Crisis", "2008-01-08", "2009-03-09", "Nifty fell around 60% peak to trough", "global"),
        CrisisPeriod("gfc_recovery", "GFC Recovery", "2009-03-10", "2009-12-31", "The snap-back off the March 2009 low", "global"),
        CrisisPeriod("nine_eleven", "9/11 Attacks", "2001-09-11", "2001-09-21", "Markets shut worldwide; a sharp risk-off on reopening", "global"),
        CrisisPeriod("may_2006", "May 2006 EM Correction", "2006-05-11", "2006-06-14", "Emerging markets sold off about 30% in a month", "global"),
        CrisisPeriod("subprime_2007", "2007 Subprime Tremor", "2007-07-24", "2007-08-17", "The first crack before the crisis proper", "global"),
        CrisisPeriod("euro_debt", "European Debt Crisis", "2011-07-01", "2011-12-20", "Sovereign contagion and a global risk-off", "global"),
        CrisisPeriod("taper_tantrum", "Taper Tantrum", "2013-05-22", "2013-08-28", "Fed taper signal; the rupee hit a record low", "global"),
        CrisisPeriod("china_deval", "China Devaluation", "2015-08-11", "2016-02-11", "Yuan devaluation into the early-2016 low", "global"),
        CrisisPeriod("brexit", "Brexit Vote", "2016-06-23", "2016-06-30", "Referendum shock", "global"),
        CrisisPeriod("volmageddon", "Volmageddon", "2018-02-01", "2018-02-09", "Short-volatility unwind", "global"),
        CrisisPeriod("q4_2018", "Q4 2018 Selloff", "2018-10-01", "2018-12-26", "Global growth scare and tightening", "global"),
        CrisisPeriod("covid_crash", "COVID Crash", "2020-02-20", "2020-03-23", "Fastest bear market on record", "global"),
        CrisisPeriod("covid_recovery", "COVID Recovery", "2020-03-24", "2020-12-31", "Liquidity-driven rebound", "global"),
        CrisisPeriod("rate_shock_2022", "2022 Rate Shock", "2022-01-17", "2022-06-17", "Global tightening and the Ukraine invasion", "global"),
        CrisisPeriod("svb", "SVB / Banking Crisis", "2023-03-08", "2023-03-24", "US regional bank failures", "global"),
        CrisisPeriod("japan_carry", "Japan Carry Unwind", "2024-07-31", "2024-08-07", "Yen surge forced a global deleveraging", "global"),
        CrisisPeriod("tariff_2025", "2025 Tariff Shock", "2025-04-02", "2025-04-30", "Global tariff escalation", "global"),
        CrisisPeriod("ketan_parekh", "Ketan Parekh / UTI Crisis", "2001-03-01", "2001-04-30", "Broker default and the freezing of US-64", "india"),
        CrisisPeriod("election_2004", "2004 Election Crash", "2004-05-14", "2004-05-28", "17 May 2004: trading halted twice in one session", "india"),
        CrisisPeriod("satyam", "Satyam Scandal", "2009-01-07", "2009-01-30", "Corporate governance shock", "india"),
        CrisisPeriod("demonetisation", "Demonetisation", "2016-11-08", "2016-12-26", "Overnight withdrawal of 500 and 1000 rupee notes", "india"),
        CrisisPeriod("ilfs", "IL&FS / NBFC Crisis", "2018-09-01", "2018-10-26", "Credit freeze across non-bank lenders", "india"),
        CrisisPeriod("yes_bank", "Yes Bank Moratorium", "2020-03-05", "2020-03-13", "RBI moratorium on a major private bank", "india"),
        CrisisPeriod("adani", "Adani / Hindenburg", "2023-01-24", "2023-02-28", "Index-heavy single-group drawdown", "india"),
        CrisisPeriod("election_2024", "2024 Election Shock", "2024-06-03", "2024-06-05", "Counting-day reversal on an unexpected margin", "india"),
        CrisisPeriod("fii_selloff_2024", "Oct 2024 FII Selloff", "2024-09-27", "2024-11-21", "Sustained foreign outflows", "india"),
    )

    private fun windowReturn(s: Ser, start: LocalDate, end: LocalDate): Double? {
        var count = 0
        var p = 1.0
        s.dates.forEachIndexed { i, d -> if (d >= start && d <= end) { count++; p *= (1.0 + s.v[i]) } }
        return if (count < 2) null else p - 1.0
    }

    internal fun analyse(returns: Ser, benchmark: Ser?, d: Disp, periods: List<CrisisPeriod> = INDIA, minCoverage: Double = 0.6): CrisisAnalysis {
        if (returns.size == 0) return CrisisAnalysis(emptyList(), null)
        val first = returns.dates.first()
        val last = returns.dates.last()
        data class Raw(val period: CrisisPeriod, val port: Double, val bench: Double?, val partial: Boolean)
        val raws = ArrayList<Raw>()
        val rows = ArrayList<CrisisRow>()
        for (period in periods) {
            val start = LocalDate.parse(period.start)
            val end = LocalDate.parse(period.end)
            if (end < first || start > last) continue
            val cs = maxOf(start, first)
            val ce = minOf(end, last)
            val span = ChronoUnit.DAYS.between(start, end).let { if (it == 0L) 1L else it }
            val coverage = ChronoUnit.DAYS.between(cs, ce).toDouble() / span
            val port = windowReturn(returns, cs, ce) ?: continue
            val sessions = returns.dates.count { it >= cs && it <= ce }
            val bench = if (benchmark != null) windowReturn(benchmark, cs, ce) else null
            val partial = coverage < minCoverage
            raws.add(Raw(period, port, bench, partial))
            rows.add(
                CrisisRow(
                    period.key, period.label, period.note, cs, ce, d.c(port), d.c(bench), d.c(bench?.let { port - it }),
                    ChronoUnit.DAYS.between(cs, ce).toInt(), sessions, d.c(d.r(minOf(coverage, 1.0), 3)), partial,
                    periods.firstOrNull { it.key == period.key }?.scope ?: "global",
                ),
            )
        }
        val complete = raws.filter { !it.partial }
        var summary: CrisisSummary? = null
        if (complete.isNotEmpty()) {
            val beat = complete.filter { it.bench != null && it.port - it.bench > 0 }
            val anyExcess = complete.any { it.bench != null }
            val byScope = LinkedHashMap<String, ScopeSummary>()
            for (scope in listOf("global", "india")) {
                val group = complete.filter { it.period.scope == scope }
                if (group.isEmpty()) continue
                byScope[scope] = ScopeSummary(
                    group.size,
                    d.c(Np.mean(group.map { it.port })),
                    if (group.any { it.bench != null }) {
                        d.c(group.count { (it.bench?.let { b -> it.port - b } ?: 0.0) > 0 }.toDouble() / group.size)
                    } else null,
                )
            }
            summary = CrisisSummary(
                complete.size,
                d.c(Np.mean(complete.map { it.port })),
                if (anyExcess) d.c(beat.size.toDouble() / complete.size) else null,
                d.c(complete.minOf { it.port }),
                d.c(complete.maxOf { it.port }),
                byScope,
            )
        }
        return CrisisAnalysis(rows, summary)
    }
}
