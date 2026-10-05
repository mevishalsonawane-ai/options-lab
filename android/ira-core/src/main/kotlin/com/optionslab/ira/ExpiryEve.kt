package com.optionslab.ira

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * The expiry-eve checklist (usefulness round 22, 2026-10-05): in the 15:35 wrap-up (and "wrap up my day") on the
 * trading day before an expiry, which of Boss's open legs - paper and Zerodha - expire on the next trading day, how far
 * each is in or out of the money at the close, its product (MIS or NRML), what tomorrow's 15:05 expiry square-off will
 * do with it (or that it is off), any legs kept to the 15:30 settlement, and how an in-the-money one settles (index
 * options in cash; stock options by delivery). Facts and reminders only: nothing here places, changes or closes
 * anything, and nothing is armed or switched. Null when nothing he holds expires on the next trading day. Pure.
 */
object ExpiryEve {
    /**
     * One open leg. [where] "Paper" / "Zerodha"; [product] "MIS" / "NRML" (null when not known); [right] "CE" / "PE";
     * [spot] the underlying's price now; [keptToSettlement] an Expiry Put leg the square-off leaves to the settlement.
     */
    data class Leg(
        val where: String, val symbol: String, val qty: Int, val product: String? = null,
        val underlying: String? = null, val strike: Double? = null, val right: String? = null, val spot: Double? = null,
        val keptToSettlement: Boolean = false,
    ) {
        /** Points in the money (+) or out of it (-), or null when not an option or no spot. */
        val itmBy: Double? get() {
            val s = spot ?: return null; val k = strike ?: return null
            return when (right) { "CE" -> s - k; "PE" -> k - s; else -> null }
        }
    }

    private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
    private fun n(x: Double) = "%,.0f".format(Locale.ENGLISH, x)

    private fun one(l: Leg): String {
        val side = if (l.qty > 0) "long" else "short"
        val parts = ArrayList<String>()
        parts += "${l.where} ${l.symbol}, ${abs(l.qty)} $side" + (l.product?.takeIf { it.isNotBlank() }?.let { " ${it.uppercase()}" } ?: "")
        val itm = l.itmBy
        parts += when {
            itm == null -> "how far from the strike is not known just now"
            abs(itm) < 0.5 -> "at the money (spot ${n(l.spot!!)})"
            itm > 0 -> "${n(itm)} points in the money (spot ${n(l.spot!!)})"
            else -> "${n(-itm)} points out of the money (spot ${n(l.spot!!)})"
        }
        if (l.keptToSettlement) parts += "an Expiry Put leg, left to the 15:30 settlement"
        return parts.joinToString(", ")
    }

    /**
     * The wrap-up's checklist for [expiry], the next trading day after [today] (only then: null on any other day or with
     * nothing expiring). [squareOffOn]: the 15:05 expiry square-off setting.
     */
    fun line(legs: List<Leg>, today: LocalDate, expiry: LocalDate?, squareOffOn: Boolean): String? {
        if (expiry == null || !expiry.isAfter(today) || legs.isEmpty()) return null
        val sb = StringBuilder()
        val many = legs.size > 1
        sb.append("Expiry eve, Boss: ${legs.size} open leg${if (many) "s" else ""} of yours expire${if (many) "" else "s"} tomorrow (${expiry.format(DAY)}) - ")
        sb.append(legs.sortedWith(compareBy<Leg> { it.where }.thenBy { it.symbol }).joinToString("; ") { one(it) }).append(".")
        val closable = legs.count { !it.keptToSettlement }
        if (closable > 0) sb.append(
            if (squareOffOn) " At 15:05 tomorrow the expiry square-off will close ${if (closable == legs.size) (if (many) "them" else "it") else "the other ${if (closable == 1) "one" else "$closable"}"}, MIS and NRML alike."
            else " The 15:05 expiry square-off is OFF: ${if (closable == 1) "it is" else "they are"} left to the 15:30 settlement unless you close ${if (closable == 1) "it" else "them"} yourself."
        )
        val mis = legs.count { it.product.equals("MIS", ignoreCase = true) }
        if (mis > 0) sb.append(" ${if (mis == 1) "One is" else "$mis are"} MIS, which the intraday square-off closes in the afternoon anyway.")
        val itm = legs.filter { (it.itmBy ?: 0.0) > 0 }
        val stockItm = itm.count { it.underlying != null && it.underlying.uppercase() !in PositionHealth.INDICES }
        val indexItm = itm.size - stockItm
        if (stockItm > 0) sb.append(" ${if (stockItm == 1) "One stock option is" else "$stockItm stock options are"} in the money now: stock options left open in the money settle by delivery of the shares, with the money or margin that needs.")
        if (indexItm > 0) sb.append(" Index options settle in cash.")
        sb.append(" Facts only, nothing is done - your call, Boss.")
        return sb.toString()
    }

    /** The next trading day after [today] by [isTradingDay] (within two weeks), or null. */
    fun nextTradingDay(today: LocalDate, isTradingDay: (LocalDate) -> Boolean): LocalDate? =
        (1L..14L).map { today.plusDays(it) }.firstOrNull(isTradingDay)
}
