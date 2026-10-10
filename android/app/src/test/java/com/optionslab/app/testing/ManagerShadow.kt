package com.optionslab.app.testing

import com.optionslab.app.data.TradeManagerHost
import com.optionslab.ira.MarketBrain
import com.optionslab.ira.TradeManager

/**
 * The trade manager made to disagree with every held trade (the arms' SHADOW tests): each look sees a news window starting on
 * every index, so each managed trade gets a would-have early exit at once - and, in SHADOW, nothing may change because of it.
 */
object ManagerShadow {
    fun adverse() {
        TradeManagerHost.testNoWake = true
        TradeManagerHost.testBars = { emptyList() }
        TradeManagerHost.testSnapshot = { t, at ->
            TradeManager.Snapshot(at, t.entry, brain = MarketBrain.Context(windows = listOf(MarketBrain.Window("RBI policy", at, at + 600_000L))))
        }
    }

    /** One look by the manager now (the test's clock). */
    fun look() = TradeManagerHost.evaluate(TradeManagerHost.nowMs())

    /** The would-have early exits recorded for [family] (SHADOW: never acted on). */
    fun wouldHaveExited(family: String): List<TradeManager.Note> = TradeManagerHost.records.value
        .filter { it.trade.family == family }.flatMap { it.notes }.filter { it.kind == TradeManager.EXIT_EARLY }

    /**
     * [scenario] (an arm's own test, with its exact assertions on trades, exits and the paper book) run again with the manager
     * in SHADOW disagreeing at every pass ([looks] switches the test's look after each pass): those assertions hold unchanged,
     * and would-have exits were recorded for [family] that nothing acted on - its real exits stand as the original rules'.
     */
    fun rerun(family: String, looks: (Boolean) -> Unit, scenario: () -> Unit) {
        org.junit.Assert.assertEquals(TradeManager.Mode.SHADOW, TradeManagerHost.policy.value.mode(family, TradeManager.Account.PAPER))
        adverse()
        looks(true)
        try { scenario() } finally { looks(false) }
        val notes = wouldHaveExited(family)
        org.junit.Assert.assertTrue("the manager disagreed with $family", notes.isNotEmpty())
        org.junit.Assert.assertTrue(notes.none { it.acted })
        for (r in TradeManagerHost.records.value.filter { it.trade.family == family && it.closed }) org.junit.Assert.assertEquals(r.actual, r.original)
        TradeManagerHost.resetForTest()
    }

    fun off(family: String) {
        TradeManagerHost.setMode(family, TradeManager.Account.PAPER, TradeManager.Mode.OFF)
        TradeManagerHost.setMode(family, TradeManager.Account.LIVE, TradeManager.Mode.OFF)
    }

    fun shadow(family: String) {
        TradeManagerHost.setMode(family, TradeManager.Account.PAPER, TradeManager.Mode.SHADOW)
        TradeManagerHost.setMode(family, TradeManager.Account.LIVE, TradeManager.Mode.SHADOW)
    }
}
