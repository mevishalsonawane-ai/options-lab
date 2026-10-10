package com.optionslab.ira

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * The expiry-eve checklist (usefulness round 22, 2026-10-05): in the 15:45 wrap-up (and "wrap up my day") on the
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

    // ---- the question (understanding round 15): "what expires tomorrow?", "kal kya expire ho raha hai" ------------

    private fun norm(text: String) = " " + text.lowercase(Locale.ENGLISH).replace("’", "").replace("'", "")
        .replace(rx("[^a-z0-9 ]"), " ").replace(rx("\\s+"), " ").trim() + " "

    /** Expiring named as a verb, in the future ("expires", "expiring", "expire ho raha hai", "expire hoga"). */
    private val EXPIRE = rx(" (expire|expires|expiring|expire ho raha|expire ho rahi|expire ho rahe|expire hoga|expire hogi|expire honge|" +
        "expire hone wala|expire hone wali|expire hone wale|expiry ho raha|expiry ho rahi|expiry hoga|expiry hogi) ")
    /** The next trading day named. */
    private val TOMORROW = rx(" (tomorrow|tomorrows|tmrw|kal|next trading day|next session) ")
    /** Asked of what ("what", "which", "anything", "kya", "kaun si", "do I have"). */
    private val WHAT = rx(" (what|whats|which|anything|any|something|how many|kya|kaun|kaunsa|kaunsi|kaun si|kaun se|kaunse|kaun sa|konse|kon se|kuch|do i have|is there|are there) ")
    /** Boss's own named ("my", "mine", "meri"). */
    private val MINE = rx(" (my|mine|i|me|mera|meri|mere|hamara|hamari|hamare) ")
    /** Not this: an order or an act, a forecast, when the expiry is (the calendar's), the market's expiry (unless his own is named), a meaning, an alert. */
    private val NOT = rx(" (will nifty|will banknifty|should|shall|buy|sell|close|exit|square|roll|hedge|when|kab|which day|what day|kis din|" +
        "max pain|pin|record|history|alert|alerts|remind|notify|mean|meaning|define|explain|if|agar|expired|yesterday) ")
    private val MARKET = rx(" (nifty|bank nifty|banknifty|finnifty|fin nifty|midcap|midcpnifty|sensex|bankex|index|indices|weekly|monthly|contract|contracts|series) ")

    /** "What expires tomorrow?", "which of my positions expire tomorrow?", "kal kya expire ho raha hai": Boss's legs that expire next. */
    fun asked(text: String): Boolean {
        val t = norm(text)
        if (NOT.containsMatchIn(t)) return false
        if (!EXPIRE.containsMatchIn(t) || !TOMORROW.containsMatchIn(t) || !WHAT.containsMatchIn(t)) return false
        // "Does Nifty expire tomorrow?", "which index expires tomorrow?": the calendar's, unless his own is named.
        if (MARKET.containsMatchIn(t) && !MINE.containsMatchIn(t)) return false
        return true
    }

    const val LOCKED = "Your positions stay out of it on a locked phone, Boss - unlock it for that."

    /**
     * The answer to [asked]: [line] (the checklist, when something he holds expires on [expiry], the next trading day), else
     * that nothing does - saying whether Zerodha's positions were actually read ([zerodhaRead]) and, when not, why: not
     * logged in ([loggedIn] false), or logged in but the read failed or timed out (round 23: it used to claim both were
     * read whenever Zerodha was logged in). A checklist from Paper alone says so too. [undated]: open Zerodha positions
     * read but not in the instruments on the phone (SENSEX/BFO, stock options, MCX), so their expiry couldn't be told -
     * said so, never counted as "nothing expires". Facts only; nothing acts.
     */
    fun answer(line: String?, expiry: LocalDate?, zerodhaRead: Boolean, loggedIn: Boolean = zerodhaRead, undated: Int = 0): String {
        if (line != null) return line + (unread(zerodhaRead, loggedIn, undated)?.let { " $it" } ?: "")
        if (expiry == null) return "I couldn't tell the next trading day just now, Boss, so I can't say what expires."
        val gap = undatedLine(undated)
        if (zerodhaRead && gap != null)
            return "Nothing I could date of yours expires on the next trading day (${expiry.format(DAY)}), Boss - Paper read; $gap. Facts only, nothing is done."
        return "Nothing you hold expires on the next trading day (${expiry.format(DAY)}), Boss" +
            (when {
                zerodhaRead -> " - your Paper and Zerodha positions both read."
                loggedIn -> " - on Paper, that is: Zerodha's positions couldn't be read just now, so I can't say the same for them."
                else -> " - I read your Paper positions; Zerodha isn't logged in, so its positions weren't read."
            }) +
            " Facts only, nothing is done."
    }

    /**
     * Said after a checklist when Zerodha is logged in but its positions weren't read (only Paper's legs are in it), or when
     * [undated] open Zerodha positions couldn't be dated (not in the instruments on the phone).
     */
    fun unread(zerodhaRead: Boolean, loggedIn: Boolean, undated: Int = 0): String? = when {
        loggedIn && !zerodhaRead -> "Zerodha's positions couldn't be read just now, so only your Paper legs are listed."
        zerodhaRead && undated > 0 -> undatedLine(undated)!!.replaceFirstChar { it.uppercase() } + ", so they aren't listed."
        else -> null
    }

    /** "2 Zerodha positions I couldn't date (not in the instruments on the phone) - check those yourself", or null for none. */
    fun undatedLine(undated: Int): String? =
        if (undated <= 0) null
        else "$undated Zerodha position${if (undated == 1) "" else "s"} I couldn't date (not in the instruments on the phone) - check ${if (undated == 1) "it" else "those"} yourself"

    /** The next trading day after [today] by [isTradingDay] (within two weeks), or null. */
    fun nextTradingDay(today: LocalDate, isTradingDay: (LocalDate) -> Boolean): LocalDate? =
        (1L..14L).map { today.plusDays(it) }.firstOrNull(isTradingDay)
}
