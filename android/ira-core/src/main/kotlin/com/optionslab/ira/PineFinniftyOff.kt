package com.optionslab.ira

import java.util.Locale

/**
 * Boss's 08 Oct yes (research/X1_TODAY_AND_FORWARD.md, Jarvis's request 2): the Pine auto-trade "breakdown below the last 20
 * candles' low" on FINNIFTY 15-minute is switched off once, on paper, with a notice. FINNIFTY's monthly options are too thin
 * for automatic stops and locks (0.42% half-spread, the widest of the four indices; a lock gapped by 11.6 points on 8 Oct)
 * and the script has no long-run record. Boss can arm it again on the Pine screen; this never runs a second time.
 *
 * Which script: auto-trading FINNIFTY on the 15-minute chart, and named or written as a breakdown below the lowest low of
 * the last 20 candles. Pure.
 */
object PineFinniftyOff {
    /** Kept once done (a setting, never carried by a backup into running again). */
    const val KEY = "pine.finnifty_breakdown_off.2026_10_08"

    const val TITLE = "Pine FinNifty breakdown switched off"

    /** What Boss is told for [name]. */
    fun notice(name: String): String =
        "\"$name\" (FINNIFTY 15-minute) no longer auto-trades on paper: FINNIFTY's options are too thin for automatic stops " +
            "(research X1, your yes on 8 Oct). Arm it again on the Pine screen if you want."

    private val LOWEST20 = Regex("(?i)lowest\\s*\\(\\s*low\\s*,\\s*20\\s*\\)")
    private val NAME = Regex("(?i)break\\s*-?down|20\\s*(candle|bar)s?'?\\s*low|below (the )?(last )?20")

    /** Whether the script ([name], [code]) auto-trading [symbol] on [interval] is the one Boss agreed to switch off. */
    fun matches(name: String, code: String, symbol: String, interval: String): Boolean {
        val fin = symbol.uppercase(Locale.ENGLISH).replace(" ", "") == "FINNIFTY"
        val m15 = interval.lowercase(Locale.ENGLISH).replace(" ", "") in setOf("15m", "15", "15min")
        return fin && m15 && (NAME.containsMatchIn(name) || LOWEST20.containsMatchIn(code))
    }
}
