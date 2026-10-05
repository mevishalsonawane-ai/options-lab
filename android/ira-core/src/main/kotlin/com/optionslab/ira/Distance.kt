package com.optionslab.ira

import java.util.Locale

/** "How far is Nifty from 25000?", "BankNifty 52000 se kitna door hai": points and percent to a level, from the last price. Pure. */
object Distance {
    data class Asked(val market: Market, val level: Double)

    private val ASK = Regex("(?i)\\b(how far|how many points|kitna door|kitni door|distance)\\b")

    fun asked(text: String): Asked? {
        if (!ASK.containsMatchIn(text) || rx("(?i)\\b(ce|pe|call|put|lots?|buy|sell)\\b").containsMatchIn(text)) return null
        val m = Market.mentioned(text).firstOrNull { it != Market.GOLD } ?: return null
        val lv = rx("\\b(\\d{2,6}(?:\\.\\d+)?)\\b").findAll(text.replace(",", "")).mapNotNull { it.groupValues[1].toDoubleOrNull() }.filter { it >= 10 }.lastOrNull() ?: return null
        return Asked(m, lv)
    }

    fun say(a: Asked, price: Double): String {
        val d = a.level - price
        val pct = kotlin.math.abs(d) / price * 100
        val f = { v: Double -> "%,.2f".format(Locale.ENGLISH, v) }
        return when {
            kotlin.math.abs(d) < 0.005 -> "${a.market.label} is right at ${f(a.level)}, Boss."
            d > 0 -> "${a.market.label} is at ${f(price)}: ${f(d)} points (%.2f%%) below ${f(a.level)}, Boss.".format(Locale.ENGLISH, pct)
            else -> "${a.market.label} is at ${f(price)}: ${f(-d)} points (%.2f%%) above ${f(a.level)}, Boss.".format(Locale.ENGLISH, pct)
        }
    }
}
