package com.optionslab.ira

import java.util.Locale

/**
 * Facts about one option or the chain, asked in plain words (Boss, 4 Oct: "price of BankNifty 52000 PE" got the index):
 * an option's price, the at-the-money strike, the lot size, the time left in the session. Read, never traded. Pure.
 */
object OptionFacts {
    sealed class Asked {
        data class Quote(val market: Market, val strike: Int, val right: String) : Asked()
        data class Atm(val market: Market) : Asked()
        data class LotSize(val market: Market) : Asked()
        object TimeLeft : Asked()
    }

    private val CHAIN = listOf(Market.NIFTY, Market.BANKNIFTY, Market.FINNIFTY)
    private val ORDER_WORDS = Regex("(?i)\\b(buy|sell|lots?|order|place|exit|close|square|kharido|becho)\\b")
    private val RIGHT = Regex("(?i)\\b(\\d{4,6})\\s*(ce|pe|call|put|calls|puts)\\b")
    private val QUOTE = Regex("(?i)\\b(price|premium|ltp|quote|trading at|how much is|what is|what s|whats|rate|bhav|kitne ka|kya chal raha)\\b")

    fun asked(text: String): Asked? {
        val t = text.lowercase(Locale.ENGLISH).replace("'", " ")
        // "When does the market open tomorrow / on Monday?" is the calendar's answer (MarketDays), not today's clock.
        val dayNamed = Regex("\\b(tomorrow|kal|parso|next|monday|tuesday|wednesday|thursday|friday|saturday|sunday|somvar|mangalvar|budhvar|guruvar|shukravar|shanivar|ravivar)\\b").containsMatchIn(t)
        if (!dayNamed && Regex("\\b(how long|how much time|time left|minutes left)\\b.*\\b(close|market|session|trading)\\b|\\bwhen does (the )?market close\\b|\\bmarket band hone (mein|me) kitna (time|samay)\\b|\\bkitna (time|samay) (bacha|baaki)\\b|\\bwhen (does|will) (the )?market (open|start)\\b|\\bmarket kab (khulega|khulta|open hoga)\\b|\\bwhat time (does|will) (the )?market (open|close)\\b").containsMatchIn(t))
            return Asked.TimeLeft
        val m = Market.mentioned(text).firstOrNull { it in CHAIN }
        if (!Regex("\\b(buy|sell|order|place)\\b").containsMatchIn(t) &&
            Regex("\\blot size\\b|\\bek lot (mein|me) kitne\\b|\\bone lot (is|has) how many\\b|\\bhow many (units|shares|quantity) in (a|one) lot\\b").containsMatchIn(t))
            return Asked.LotSize(m ?: Market.NIFTY)
        // An order's words are never a quote ("buy 2 lots Nifty 25000 CE" is the order review).
        if (ORDER_WORDS.containsMatchIn(t)) return null
        RIGHT.find(t)?.let { r ->
            if (m == null || !QUOTE.containsMatchIn(t) && !Regex("^\\s*\\S+\\s+\\d{4,6}\\s*(ce|pe|call|put)\\s*\\??\\s*$").containsMatchIn(t)) return null
            val right = if (r.groupValues[2].startsWith("c")) "CE" else "PE"
            return Asked.Quote(m, r.groupValues[1].toInt(), right)
        }
        if (Regex("\\batm strike (kya|kaunsa|konsa)\\b|\\b(atm|at the money)\\b.*\\bstrike\\b|\\bstrike\\b.*\\b(atm|at the money)\\b|\\bwhich strike is atm\\b").containsMatchIn(t))
            return Asked.Atm(m ?: Market.NIFTY)
        return null
    }

    private fun rs(v: Double) = "Rs %,.2f".format(Locale.ENGLISH, v)

    fun quote(m: Market, strike: Int, right: String, ltp: Double, bid: Double?, ask: Double?, oi: Long, lotSize: Int): String =
        "${m.label} $strike $right is at ${rs(ltp)}" + (if (bid != null && ask != null && bid > 0 && ask > 0) " (bid ${rs(bid)}, ask ${rs(ask)})" else "") +
            ", open interest %,d; one lot of $lotSize is about ${"Rs %,.0f".format(Locale.ENGLISH, ltp * lotSize)}.".format(Locale.ENGLISH, oi)

    fun timeLeft(minuteNow: Int, open: Int = 9 * 60 + 15, close: Int = 15 * 60 + 30, tradingDay: Boolean = true,
                 /** The next trading day after today, e.g. "Mon 6 Oct" (for "opens next on ..."). */ nextDay: String? = null): String = when {
        !tradingDay -> "The market is closed today, Boss." + (nextDay?.let { " It opens next on $it at 09:15." } ?: "")
        minuteNow < open -> "The market opens in ${open - minuteNow} minutes (09:15), Boss."
        minuteNow >= close -> "The market has closed for today (15:30), Boss." + (nextDay?.let { " It opens next on $it at 09:15." } ?: "")
        else -> (close - minuteNow).let { left -> "${if (left >= 60) "${left / 60} h ${left % 60} min" else "$left minutes"} left until the 15:30 close, Boss." }
    }
}
