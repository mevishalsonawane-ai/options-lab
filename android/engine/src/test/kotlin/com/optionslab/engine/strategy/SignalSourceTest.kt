package com.optionslab.engine.strategy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 14 Oct (Boss): a signal leg that names an option is not the default; it keeps working and is labelled. */
class SignalSourceTest {
    private fun leg(id: Int, segment: Segment, symbol: String?, exchange: String? = "NFO") =
        LegDef(id, segment, symbol = symbol, exchange = exchange, side = LegSide.BOTH, qty = 1, qtyMode = QtyMode.LOTS)

    private fun signal(vararg legs: LegDef) = StrategyDef(name = "Alerts", kind = StrategyKind.SIGNAL, underlying = "NIFTY",
        underlyingExchange = "NSE_INDEX", legs = legs.toList())

    @Test fun anIndexFutureOrStockSignalIsTheDefaultAndNotLabelled() {
        val d = signal(leg(1, Segment.FUTURES, "NIFTY26OCTFUT"), leg(2, Segment.CASH, "RELIANCE", "NSE"), leg(3, Segment.FUTURES, "CRUDEOIL26NOVFUT", "MCX"))
        assertTrue(SignalSource.optionLegs(d).isEmpty())
        assertNull(SignalSource.note(d))
    }

    @Test fun anOptionLegIsLabelledAndKeptAsItIs() {
        val opt = leg(2, Segment.FUTURES, "nifty26oct24500ce")
        val d = signal(leg(1, Segment.FUTURES, "NIFTY26OCTFUT"), opt, leg(3, Segment.FUTURES, "GOLDM26DEC72000PE", "MCX"))
        assertEquals(listOf(2, 3), SignalSource.optionLegs(d).map { it.id })
        assertEquals("Not the default signal: leg 2 (nifty26oct24500ce), leg 3 (GOLDM26DEC72000PE) name options, so their alerts may come " +
            "from an option's own chart. The default reads the index (for MCX the future) and then buys the option; this strategy keeps " +
            "working as you set it.", SignalSource.note(d))
        // Nothing about the definition changes.
        assertEquals(opt, d.legs[1])
    }

    @Test fun oneOptionLegSpacesAndLookAlikes() {
        assertTrue(SignalSource.isOptionLeg(leg(1, Segment.FUTURES, "NIFTY 24500 CE")))
        assertFalse(SignalSource.isOptionLeg(leg(1, Segment.FUTURES, "")))
        assertFalse(SignalSource.isOptionLeg(leg(1, Segment.FUTURES, null)))
        assertFalse(SignalSource.isOptionLeg(leg(1, Segment.CASH, "PFCE")), "a share whose name ends in CE is not an option")
        assertTrue(SignalSource.note(signal(leg(1, Segment.FUTURES, "BANKNIFTY26OCT52000PE")))!!
            .startsWith("Not the default signal: leg 1 (BANKNIFTY26OCT52000PE) names an option, so its alerts may come from an option's own chart."))
    }

    @Test fun aBatchStrategyHasNoSignalAndIsNeverLabelled() {
        val batch = StrategyDef(name = "Straddle", underlying = "NIFTY", underlyingExchange = "NSE_INDEX",
            legs = listOf(LegDef(1, Segment.OPTIONS, Position.S, 1, OptionType.CE, StrikeMode.ATM, "ATM", expiry = "weekly", symbol = "NIFTY26OCT24500CE")))
        assertTrue(SignalSource.optionLegs(batch).isEmpty())
        assertNull(SignalSource.note(batch))
        assertNull(SignalSource.note(signal()), "no legs: nothing to label")
    }
}
