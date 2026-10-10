package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Boss's 08 Oct yes: only the FINNIFTY 15-minute "breakdown below the last 20 candles' low" auto-trade is switched off. */
class PineFinniftyOffTest {
    private val code = "//@version=5\nstrategy(\"x\")\nlo = ta.lowest(low, 20)[1]\nif close < lo\n    strategy.entry(\"S\", strategy.short)"

    @Test fun theBreakdownScriptOnFinniftyFifteenMinutesIsFound() {
        assertTrue(PineFinniftyOff.matches("Breakdown below 20 candles low", "", "FINNIFTY", "15m"))
        assertTrue(PineFinniftyOff.matches("My script", code, "FINNIFTY", "15m"), "by its code")
        assertTrue(PineFinniftyOff.matches("below the last 20 candles' low", "", "finnifty", "15"))
        assertTrue(PineFinniftyOff.matches("break-down fin", "", "FINNIFTY", "15min"))
    }

    @Test fun nothingElseIsTouched() {
        assertFalse(PineFinniftyOff.matches("Breakdown below 20 candles low", code, "BANKNIFTY", "15m"), "another index")
        assertFalse(PineFinniftyOff.matches("Breakdown below 20 candles low", code, "FINNIFTY", "5m"), "another chart")
        assertFalse(PineFinniftyOff.matches("Trend follow", "ema(close, 20)", "FINNIFTY", "15m"), "another script")
    }

    @Test fun theNoticeSaysWhyAndHowToUndo() {
        val n = PineFinniftyOff.notice("Breakdown 20")
        assertTrue("\"Breakdown 20\"" in n && "too thin" in n && "Arm it again on the Pine screen" in n, n)
    }
}
