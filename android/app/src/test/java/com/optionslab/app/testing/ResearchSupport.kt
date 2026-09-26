package com.optionslab.app.testing

import com.optionslab.app.data.AppSettings
import com.optionslab.app.data.ChartFeed
import com.optionslab.app.data.Store
import com.optionslab.app.ui.Arm
import com.optionslab.app.ui.ArmResult
import com.optionslab.app.ui.HealthResult
import com.optionslab.app.ui.SignalResult
import com.optionslab.app.ui.screens.ChartSource
import com.optionslab.engine.BacktestReport
import com.optionslab.engine.ExpiryPut
import com.optionslab.engine.Ic
import com.optionslab.engine.Monitor
import com.optionslab.engine.Right
import com.optionslab.engine.Session
import com.optionslab.engine.SignalBacktest
import com.optionslab.engine.Summary
import com.optionslab.engine.UtBot
import com.optionslab.engine.Upstox
import com.optionslab.engine.options.ChainSnapshot
import com.optionslab.engine.strategy.Presets
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Research, Lab and chart test data (area D). Everything is computed by the app's own [Store] and the
 * engine from the **bundled assets** (the PC's expiry chains and harvested bars), so the screens show real
 * numbers and nothing reaches the network. Each fixture is computed once per Robolectric sandbox.
 */
object ResearchFixtures {
    val settings = AppSettings(reduceMotion = true)

    /** The full expiry-put trial over the bundled 170 sessions (what "Run the trial" shows). */
    val report: BacktestReport by lazy { Store.backtest(settings.copy(includeDeviceSessions = false)) }

    /** A trial with no trades at all (every session skipped). */
    val emptyReport: BacktestReport by lazy { BacktestReport.assemble(emptyList(), emptyList(), emptyList(), settings.params(), 0, settings.capital, settings.survive) }

    /** The kill-condition checks over the trial. */
    val health: HealthResult by lazy {
        val rows = Monitor.rows(report.all)
        val (window, checks) = Monitor.healthOf(rows, settings.healthLast, settings.otmPct, settings.pinnedLot)
        HealthResult("backtest", window, rows.size, checks)
    }

    /** A health result with one check per status, so every colour and the "Look closer" verdict show. */
    val mixedHealth: HealthResult by lazy {
        health.copy(checks = listOf(
            Monitor.Check("Win rate", Monitor.Status.PASS, "93.3%", ">= 85%", "The strategy's edge is its win rate."),
            Monitor.Check("Cost share", Monitor.Status.WARN, "31% of credit", "< 30%", "Costs are eating the credit."),
            Monitor.Check("Tail loss", Monitor.Status.FAIL, "-6.1 credits", "> -5 credits", "One loss erased a month of wins."),
        ))
    }

    /** Two arms over the first 24 bundled sessions. */
    val arms: List<ArmResult> by lazy {
        val list = listOf(
            Arm("Naked 0.75%", "the published headline: quoted spread, lot 65", ExpiryPut.Params()),
            Arm("Naked 1.00%", "the 100% cell: roll spread, never billed a loss", ExpiryPut.Params(otmPct = 0.01, regime = "roll")),
        )
        val sessions = Store.expirySessions(false).take(24).toList()
        list.map { arm ->
            val (t, k) = ExpiryPut.runBacktest(sessions, arm.params)
            val sorted = t.sortedBy { it.session }
            var acc = 0.0
            ArmResult(arm, Summary.of(arm.name, sorted, k.size), k.size, sorted.map { acc += it.netPnl; acc })
        }
    }

    /** The IC table over a few harvested NIFTY sessions. */
    val ic: Ic.IcResult by lazy { Ic.measure("NIFTY", Store.barSessions("NIFTY").take(6), "quoted") }

    /** The last harvested NIFTY session (the replay page's first day). */
    val replayDay: LocalDate by lazy { Store.barDays("NIFTY").max() }
    val replaySession: Session by lazy { Store.barSession("NIFTY", replayDay)!! }

    /** Signal Lab's UT Bot replay of [replayDay], as AppModel.runSignal computes it. */
    val signal: SignalResult by lazy {
        val sess = replaySession
        val ix = sess.index!!
        val bars = SignalBacktest.Bars(ix.minutes, ix.open ?: ix.close, ix.high ?: ix.close, ix.low ?: ix.close, ix.close)
        val (b, s) = UtBot.signals(bars.high, bars.low, bars.close, 2.0, 1)
        val nearest = sess.options.mapNotNull { it.expiry }.filter { !it.isBefore(sess.day) }.minOrNull()
        val chain = sess.options.filter { it.expiry == nearest }
        SignalResult("NIFTY", sess.day, "utbot", bars, b, s, SignalBacktest.run(bars, chain, b, s, 65, 1, "quoted", underlying = "NIFTY"), nearest)
    }

    /** A short-straddle preset over a few harvested NIFTY sessions. */
    val preset: Presets.Result by lazy {
        Presets.backtest(Presets.ALL.first(), Store.barSessions("NIFTY").take(6), { s -> s.lotHint ?: 65 }, 1, 9 * 60 + 20, 15 * 60 + 15, null, null)
    }

    /** An option chain priced from the last bundled expiry session (for the chart's OPT dialog). */
    val chain: ChainSnapshot by lazy {
        val sess = Store.expirySessions(false).last()
        val ix = sess.index!!
        val expiry = sess.options.mapNotNull { it.expiry }.min()
        val series = sess.options.filter { it.expiry == expiry }
        val spot = ix.close.last()
        val near = series.map { it.strike }.distinct().sortedBy { kotlin.math.abs(it - spot) }.take(8).toSet()
        val rows = ChainSnapshot.rowsFrom(series.filter { it.strike in near }, emptyMap(), 75)
        ChainSnapshot.of("NIFTY", expiry, spot, 75, rows, expiry.atTime(11, 0).atZone(ZoneId.of("Asia/Kolkata")))
    }
}

/** Minute candles for tests: [n] bars from 09:15 IST on [day], rising by [step] from [start]. */
fun testBars(n: Int, start: Double = 100.0, step: Double = 0.5, day: LocalDate = LocalDate.of(2026, 9, 25)): List<Upstox.Bar> {
    val t0 = day.atTime(9, 15).atZone(ZoneId.of("Asia/Kolkata")).toEpochSecond()
    return (0 until n).map { i ->
        val o = start + i * step
        val c = o + step
        Upstox.Bar(t0 + 60L * i, o, maxOf(o, c) + 0.25, minOf(o, c) - 0.25, c, 1000L + i, 50_000L + 10 * i)
    }
}

/** A listed NIFTY option for chart tests (made up; nothing is looked up). */
val TEST_OPTION = Upstox.Contract("NIFTY", LocalDate.of(2026, 10, 1), 24800.0, Right.CE, 75, "NSE_FO|TEST1", "NIFTY26O0124800CE")

/**
 * A [ChartSource] with no network: candles from [bars] (or [failure]), contracts from [contracts].
 * Every candle request is recorded in [asked] as "symbol interval".
 */
class FakeChartSource(
    var bars: (String, String) -> List<Upstox.Bar> = { _, _ -> testBars(60) },
    var contracts: List<Upstox.Contract> = listOf(TEST_OPTION),
) : ChartSource {
    var failure: String? = null
    val asked = CopyOnWriteArrayList<String>()
    override fun contract(symbol: String) = contracts.firstOrNull { it.tradingSymbol.equals(symbol, ignoreCase = true) }
    override suspend fun bars(symbol: String, interval: String, fromSec: Long?, toSec: Long?): List<Upstox.Bar> {
        asked += "$symbol $interval"
        failure?.let { throw java.io.IOException(it) }
        return bars(symbol, interval)
    }
    override fun search(text: String) = contracts.filter { it.tradingSymbol.contains(text, ignoreCase = true) }
        .map { ChartFeed.Match(it.tradingSymbol, "NFO", "lot ${it.lotSize}") }
    override fun contracts() = contracts
    override suspend fun streamToken(symbol: String): Long? = null
}

/**
 * Six set-ups that span the device matrix (every size, every font scale, both themes): the secondary
 * research / Lab / chart states run on these to keep CI time in bounds; each page's main state runs on all 24.
 */
object ResearchMatrix {
    private val PICK = setOf("small-font2.0-light", "small-font1.0-dark", "phone-font1.3-light", "landscape-font1.0-light",
        "landscape-font2.0-dark", "tablet-font1.3-dark")
    fun six(): List<Array<Any>> = DeviceConfig.matrix().filter { (it[0] as DeviceConfig).name in PICK }
}
