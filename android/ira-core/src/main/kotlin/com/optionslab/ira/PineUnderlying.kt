package com.optionslab.ira

import java.util.Locale

/**
 * Which chart a Pine script may auto-trade on (Boss, 14 Oct: "all the indicators and strategies should be on the regular
 * funds/index, and on the basis of that we buy options; make this default"). A Pine script reads its signal from the INDEX
 * chart and then buys the option the strike rule picks; it never runs on an option's own candles, an MCX contract or any
 * other symbol. The symbol is checked when the saved scripts are read and when a script is switched on: an index (and the
 * ways people write one: "nifty", "Bank Nifty", "NSE:NIFTY BANK") is kept as its root; anything else is refused with
 * [refusal] and the script stays switched off. Pure.
 */
object PineUnderlying {
    /** The indices a Pine script runs on (the Pine screen's picker). */
    val ROOTS: List<String> = listOf("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX")

    /** Other ways an index is written, with spaces, dashes and underscores taken out, to its root. */
    private val ALIASES: Map<String, String> = mapOf(
        "NIFTY50" to "NIFTY", "NIFTYFIFTY" to "NIFTY",
        "NIFTYBANK" to "BANKNIFTY",
        "NIFTYFIN" to "FINNIFTY", "NIFTYFINSERVICE" to "FINNIFTY", "NIFTYFINANCIALSERVICES" to "FINNIFTY",
        "MIDCAPNIFTY" to "MIDCPNIFTY", "NIFTYMIDSELECT" to "MIDCPNIFTY", "NIFTYMIDCAPSELECT" to "MIDCPNIFTY",
        "BSESENSEX" to "SENSEX",
    )

    /** An exchange prefix as TradingView writes one ("NSE:NIFTY"). */
    private val PREFIX = Regex("^(NSE|BSE|NSE_INDEX|BSE_INDEX|INDEX)\\s*:\\s*")
    private val SEPARATORS = Regex("[\\s_-]+")

    /** The index root [symbol] names ("Bank Nifty" -> "BANKNIFTY"), or null when it is not an index (an option, MCX, blank). */
    fun rootOf(symbol: String?): String? {
        val s = symbol?.trim()?.uppercase(Locale.ENGLISH)?.replace(PREFIX, "")?.replace(SEPARATORS, "") ?: return null
        if (s.isEmpty()) return null
        return if (s in ROOTS) s else ALIASES[s]
    }

    /** What is said when [symbol] is not an index: the script is not switched on. */
    fun refusal(symbol: String?): String {
        val shown = symbol?.trim()?.take(40)?.ifEmpty { null } ?: "(no symbol)"
        return "Pine scripts run on the index chart and then buy the option: $shown is not an index"
    }
}
