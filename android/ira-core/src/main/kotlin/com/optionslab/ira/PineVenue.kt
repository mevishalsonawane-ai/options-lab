package com.optionslab.ira

import java.util.Locale

/**
 * Which symbols a strategy Jarvis adds may auto-trade (12 Oct): at 10:41 Jarvis added "breakdown below the last 20 candles'
 * low" on SENSEX 15m and at 16:24 "three red candles in a row" on FinNifty 15m, each switched on to trade on paper. SENSEX
 * options trade on BSE, where the app places no orders; FINNIFTY's options are too thin for automatic stops (research X1,
 * Boss's yes on 8-9 Oct: no automatic trading). Such a strategy is still added - as ALERTS ONLY, said why. Pure.
 */
object PineVenue {
    /** BSE's indices: their options trade on BSE, never through the app. */
    private val BSE = setOf("SENSEX", "BANKEX", "SENSEX50")

    /** Why [symbol] may not be auto-traded by a strategy Jarvis adds (alerts only), or null when it may. */
    fun alertsOnlyWhy(symbol: String): String? {
        val s = symbol.uppercase(Locale.ENGLISH).replace(" ", "")
        return when {
            s in BSE -> "$s options trade on BSE, where the app places no orders"
            s == "FINNIFTY" -> "FINNIFTY's options are too thin for automatic stops (research X1, your yes on 9 Oct: no automatic trading)"
            else -> null
        }
    }
}
