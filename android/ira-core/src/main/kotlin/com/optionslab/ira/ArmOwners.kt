package com.optionslab.ira

import com.optionslab.engine.orb.LiquidityRules
import java.util.Locale

/**
 * Who a paper or Zerodha trade belongs to, as the app shows its arms (usefulness round 21, 2026-10-05). Liquidity 15+5 is
 * one switch and one row, but its six books tag their own orders: entries "Liquidity 15+5 · entry", while exits and live
 * orders carry the book's own name - "Liquidity 15m", "Liquidity 5m", "Liquidity 30m FINNIFTY", "Liquidity 5m FINNIFTY", "Liquidity 15m MIDCPNIFTY", "Liquidity 5m MIDCPNIFTY" (and
 * "Liquidity 15m FINNIFTY", the 30-minute book's name before it was renamed). A trade under any of them is Liquidity 15+5's,
 * so "how are my bots doing?" ([BotHealth]), "what should I switch off?" ([SwitchOff]) and the calendar's strategy filter
 * see one arm with all its trades, never "no closed paper trades yet". Every other name is returned as it is. Pure.
 */
object ArmOwners {
    /** The arm Boss arms and sees: "Liquidity 15+5". */
    val LIQUIDITY: String = LiquidityRules.ARM.label

    private val BOOKS: Set<String> = LiquidityRules.BOOKS.map { it.label.lowercase(Locale.ENGLISH) }.toSet()

    /** A book by its pattern too: "Liquidity <n>m", optionally followed by its index ("Liquidity 15m FINNIFTY"). */
    private val BOOK = Regex("^liquidity \\d{1,2} ?m(in)?( [a-z]+)?$")

    /** [owner] ("Liquidity 5m FINNIFTY", "ORB", "Manual") as the arm it belongs to ("Liquidity 15+5", "ORB", "Manual"). */
    fun arm(owner: String): String {
        val o = owner.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ENGLISH)
        return if (o in BOOKS || BOOK.matches(o)) LIQUIDITY else owner
    }
}
