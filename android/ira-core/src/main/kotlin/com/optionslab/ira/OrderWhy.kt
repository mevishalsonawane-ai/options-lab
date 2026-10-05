package com.optionslab.ira

import java.time.LocalTime
import java.util.Locale

/**
 * Why an order was cancelled or rejected (Boss, 5 Oct: "why was my last order canceled", asked twice, got only the day's
 * orders list): "why was my last order cancelled?", "why did my order get rejected?", "why was the BankNifty order
 * cancelled?", "mera order cancel kyun hua", "what happened to my last order?".
 *
 * The latest cancelled or rejected order of the day (paper, and Zerodha on an unlocked phone with a session) - or the one
 * named by index, strike, side, leg, venue or time - and why, in plain words, from what the app holds: the reason the app
 * noted when it cancelled the order itself (its paper book keeps one beside each cancel since 5 Oct: the position was
 * already closed, the arm was exiting on its index stop, the other exit of a stop-and-target pair filled, a market order
 * that could not fill at once, Boss's own cancel, the 15:15 square-off, the contract's expiry...), the order's own status
 * message (Zerodha's status_message, the paper book's rejection reason) worded plainly (margin, price band, freeze
 * quantity, market closed, static IP...), who placed it and which leg it was (entry, stop, target, index stop, square-off,
 * expiry exit), and what happened beside it (the position closed by another exit a moment later, so the stop was no
 * longer needed). A reason not recorded is said to be so, with what is known; one read from the orders is said as read.
 *
 * Reads only: nothing is placed, changed or cancelled here - "cancel my last order" stays its own command. No order id is
 * said. The app keeps it off a locked phone. Pure.
 */
object OrderWhy {
    /** What the question wants: a cancelled order, a rejected one, either, or simply the last order whatever became of it. */
    enum class Want { CANCELLED, REJECTED, ENDED, ANY }

    /** The question read: what it wants and what it names ([venue] "Paper" / "Zerodha", [side] "BUY" / "SELL", [leg]). */
    data class Asked(val want: Want, val venue: String? = null, val market: Market? = null, val side: String? = null,
                     val time: LocalTime? = null, val strike: String? = null, val right: String? = null, val leg: String? = null)

    /**
     * One of today's orders as the app holds it. [time]: placed; [ended]: when it was cancelled or rejected, when known;
     * [by]: who placed it ("Liquidity 15+5 · stop", "Manual · Chart"...); [message]: the broker's or paper book's own
     * message; [noted]: the reason key the app noted when it cancelled the order itself (see [noted]).
     */
    data class Ord(val venue: String, val time: LocalTime?, val symbol: String, val side: String, val qty: Int, val status: String,
                   val avg: Double = 0.0, val by: String? = null, val message: String? = null, val noted: String? = null,
                   val ended: LocalTime? = null, val product: String? = null, val type: String? = null)

    private const val ORD = "(?:order|orders|ordr|odr)"
    private const val LEGN = "(?:order|orders|ordr|odr|stop ?loss|stoploss|stop|sl)"
    private const val END = "(?:cancel|cancell?ed|canceld|cancelation|cancellation|reject|rejected|rejection|refused|failed|fail|bounced|" +
        "go through|(?:was ?n ?t|was not|not) placed)"
    private val WHY = listOf(
        rx("\\bwhy\\b.*\\b$LEGN\\b.*\\b$END\\b"),
        rx("\\bwhy\\b.*\\b$END\\b.*\\b$ORD\\b"),
        rx("\\b(?:reason|reasons) (?:for|of|behind) (?:the |my |that |this )?(?:last |latest )?(?:order )?(?:cancel|cancell?ation|cancelation|reject|rejection)\\b"),
        rx("\\b(?:rejection|cancell?ation|cancelation) reason\\b"),
        // Hinglish: "mera order cancel kyun hua", "order reject kyon ho gaya", "kyun cancel hua mera order".
        rx("\\b$LEGN\\b.*\\b(?:cancel|reject|rejected|cancelled|kat|radd)\\b.*\\b(?:kyun|kyon|kyu|kiu|kaise)\\b"),
        rx("\\b(?:kyun|kyon|kyu|kiu)\\b.*\\b(?:cancel|reject|rejected|cancelled|radd)\\b.*\\b$ORD\\b"),
        rx("\\b(?:kyun|kyon|kyu|kiu)\\b.*\\b$ORD\\b.*\\b(?:cancel|reject|rejected|cancelled|radd)\\b"),
    )
    /** "What happened to my last order?": one order, whatever became of it ("my orders" is the day's list, elsewhere). */
    private val HAPPENED = listOf(
        rx("\\bwhat (?:happened|happend|happen) (?:to|with) (?:my|the|that|this|our)\\b(?:[a-z0-9: ]*?) (?:order|ordr|odr)\\b(?! book)(?!s)"),
        rx("\\b(?:mere|mera|meri|us|is) (?:[a-z0-9: ]*?)(?:order|ordr) (?:ka|ke saath|ke sath|ko) kya hua\\b"),
    )
    /** Asked to do something (never answered here): "cancel my last order", "why don't you cancel it". */
    private val ACTING = rx("^(?:please |jarvis |pls )*(?:cancel|reject|modify|place|exit|close|square)\\b|\\bwhy (?:don ?t|dont|do not|not|should|shall|would|wouldnt|wouldn ?t) (?:i|we|you)\\b|\\b(?:then|and then|also) (?:cancel|close|exit|place)\\b|\\b(?:karo|kar do|kardo|kijiye)\\b")

    private fun norm(q: String) = q.lowercase(Locale.ENGLISH).replace('’', '\'').replace("'", "").replace(rx("[?!.,;\"]"), " ")
        .replace(rx("\\s+"), " ").trim()

    /** Is [q] asking why an order was cancelled or rejected (or what became of one)? Null when not. */
    fun asked(q: String): Asked? = askedKept.of(q) { askedFresh(q) }

    /** The last words read (speed round 10: the hub reads them as said, then again in its order; [Kept], pure). */
    private val askedKept = Kept<Asked?>(64)

    private fun askedFresh(q: String): Asked? {
        val t = norm(q)
        if (ACTING.containsMatchIn(t)) return null
        val why = WHY.any { it.containsMatchIn(t) }
        val happened = !why && HAPPENED.any { it.containsMatchIn(t) }
        if (!why && !happened) return null
        val rej = rx("\\b(?:reject|rejected|rejection|refused|failed|fail|bounced|go through|(?:not|wasnt|was not) placed|radd)\\b").containsMatchIn(t)
        val can = rx("\\b(?:cancel|cancell?ed|canceld|cancelation|cancellation|kat)\\b").containsMatchIn(t)
        val want = when {
            happened && !rej && !can -> Want.ANY
            rej && !can -> Want.REJECTED
            can && !rej -> Want.CANCELLED
            else -> Want.ENDED
        }
        val venue = when {
            rx("\\bpaper\\b").containsMatchIn(t) -> "Paper"
            rx("\\b(?:zerodha|kite|live|real)\\b").containsMatchIn(t) -> "Zerodha"
            else -> null
        }
        val market = Market.mentioned(t).firstOrNull { it != Market.VIX && it != Market.GOLD }
        val side = when {
            rx("\\b(?:buy|bought|kharid|khareed)\\b").containsMatchIn(t) -> "BUY"
            rx("\\b(?:sell|sold|bech|becha)\\b").containsMatchIn(t) -> "SELL"
            else -> null
        }
        val time = rx("\\b(\\d{1,2}):(\\d{2})\\b").find(t)?.let { m ->
            runCatching { LocalTime.of(m.groupValues[1].toInt(), m.groupValues[2].toInt()) }.getOrNull()
        }
        val strike = rx("(?<![:\\d])(\\d{4,6})(?![:\\d])").find(t)?.groupValues?.get(1)
        val right = when {
            rx("\\b(?:ce|call|calls)\\b").containsMatchIn(t) -> "CE"
            strike != null && rx("\\b(?:pe|put|puts)\\b").containsMatchIn(t) -> "PE"
            rx("\\b(?:put|puts)\\b").containsMatchIn(t) -> "PE"
            else -> null
        }
        val leg = when {
            rx("\\b(?:stop ?loss|stoploss|sl|stop)\\b").containsMatchIn(t) -> "stop"
            rx("\\btarget\\b").containsMatchIn(t) -> "target"
            rx("\\bentry\\b").containsMatchIn(t) -> "entry"
            rx("\\bexit\\b").containsMatchIn(t) -> "exit"
            else -> null
        }
        return Asked(want, venue, market, side, time, strike, right, leg)
    }

    // ---- words ----

    private fun px(x: Double) = "%,.2f".format(Locale.ENGLISH, x)
    private fun hm(t: LocalTime) = "%02d:%02d".format(Locale.ENGLISH, t.hour, t.minute)

    private fun isCancelled(s: String) = s.contains("CANCEL", true)
    private fun isRejected(s: String) = s.contains("REJECT", true)
    private fun isFilled(s: String) = s.equals("COMPLETE", true) || s.equals("FILLED", true)
    private fun isOpen(s: String) = s.equals("OPEN", true) || s.contains("PENDING", true) || s.contains("TRIGGER", true)

    /** The step of an order from who placed it: "Liquidity 15+5 · stop" -> "stop"; null for a hand order or none. */
    fun leg(by: String?): String? = by?.substringAfter(" · ", "")?.trim()?.takeIf { it.isNotEmpty() && !by.startsWith("Manual") }

    /** The strategy, arm or rule that placed it: "Liquidity 15+5 · stop" -> "Liquidity 15+5". */
    fun who(by: String?): String? = by?.substringBefore(" · ")?.trim()?.takeIf { it.isNotEmpty() }

    /** A leg in words: "index_stop" -> "index stop", "stop" -> "stop-loss order". */
    fun legWords(leg: String): String {
        val l = leg.lowercase(Locale.ENGLISH).replace(' ', '_')
        return when {
            "index_stop" in l -> "index stop (the index went back through the level the trade was taken on)"
            "time_stop" in l -> "time stop (the trade had not moved in its time)"
            "profit_lock" in l -> "profit lock"
            "session_end" in l -> "end-of-session exit"
            "operator_stop" in l -> "exit when the arm was stopped"
            "backstop_square_off" in l || "square_off" in l || "square-off" in l -> "15:15 square-off"
            "closed_by_you" in l -> "your own close"
            "expiry" in l -> "expiry exit"
            "stop" in l || l == "sl" -> "stop-loss order"
            "target" in l -> "target order"
            "entry" in l -> "entry order"
            "exit" in l -> "exit"
            else -> leg.replace('_', ' ') + " order"
        }
    }

    /** Short form of a leg for "the position was closed by ...": the words before any bracket. */
    private fun legShort(leg: String) = legWords(leg).substringBefore(" (")

    /** Who placed it, said: "the stop-loss order of Liquidity 15+5", "placed by hand from Chart", "the 15:15 auto square-off". */
    fun placedBy(by: String?): String? {
        if (by.isNullOrBlank()) return null
        val w = who(by) ?: return null
        val l = leg(by)
        return when {
            by.startsWith("Manual") -> by.substringAfter(" · ", "").trim().takeIf { it.isNotEmpty() }?.let { "placed by hand from $it" } ?: "placed by hand"
            by.startsWith("Auto square-off") -> "the 15:15 auto square-off"
            by.startsWith("Expiry square-off") -> "the expiry square-off"
            by.startsWith("Outside IraAlgo") -> "placed outside this app (on Kite)"
            w == "Protection" && l != null -> "the ${legShort(l)} of your stop-and-target protection"
            l != null -> "the ${legShort(l)} of $w"
            else -> "placed by $w"
        }
    }

    /**
     * A reason the app noted when it cancelled the order itself, as a clause after "because": "position_closed",
     * "exit:index_stop", "oco:stop", "oco:target", "unfilled_market", "you", "jarvis", "strategy", "protection_removed",
     * "protection_replaced", "square_off", "expiry", "day_end"; any other text is said as it is.
     */
    fun noted(key: String): String {
        val k = key.trim()
        return when {
            k.startsWith("exit:") -> "the arm was closing the position itself on its ${legWords(k.removePrefix("exit:"))}, so it took its resting stop " +
                "out of the book first - the position could not be sold twice"
            k == "position_closed" -> "the position it was guarding was already closed, so the app took it out of the book - left there, it could " +
                "have filled as a fresh position"
            k == "oco:stop" -> "the stop of the same pair filled, so its target was cancelled (one cancels the other)"
            k == "oco:target" -> "the target of the same pair filled, so its stop was cancelled (one cancels the other)"
            k == "unfilled_market" -> "it was a market order the paper book could not fill at once (no fresh price), so the app cancelled it " +
                "straight away - it can never fill late as a trade nobody is watching"
            k == "you" -> "you cancelled it yourself in the app"
            k == "jarvis" -> "you asked me to cancel it, and confirmed"
            k == "strategy" -> "its strategy took it out of the book under its own rules"
            k == "protection_removed" -> "its stop-and-target protection was removed, and its resting exits went with it"
            k == "protection_replaced" -> "a new stop-and-target protection was set on the position, replacing the old one"
            k == "square_off" -> "the paper account's 15:15 intraday square-off cancels every intraday order still working"
            k == "expiry" -> "the contract expired, and the order ended with it"
            k == "day_end" -> "the trading day ended, and the paper book closed out the order still working from it"
            else -> k.trimEnd('.')
        }
    }

    /** The broker's or paper book's message in plain words, when it is one of the usual ones. */
    fun plain(message: String): String? {
        val m = message.lowercase(Locale.ENGLISH)
        return when {
            rx("margin|insufficient|funds|fund limit|shortfall").containsMatchIn(m) -> "there was not enough margin for it"
            rx("kill ?switch").containsMatchIn(m) -> "the kill switch was on"
            rx("account guard|guard").containsMatchIn(m) -> "the app's account guard refused it"
            rx("static ip|\\bip\\b|whitelist").containsMatchIn(m) -> "it did not come from your registered static IP, which the exchange now asks for"
            rx("freeze").containsMatchIn(m) -> "the quantity was above the exchange's freeze limit for one order"
            rx("circuit|price band|price range|outside the (?:daily )?(?:price )?(?:band|range|limit)|dpr|price protection|market protection").containsMatchIn(m) ->
                "its price was outside the band the exchange allows right now"
            rx("market(?:s)? (?:is |are )?(?:closed|not open)|after market|outside (?:market|trading) hours|\\bamo\\b|market hours").containsMatchIn(m) ->
                "the market was closed"
            rx("trigger").containsMatchIn(m) -> "its trigger price was already crossed or not allowed"
            rx("lot size|multiple of|lot").containsMatchIn(m) -> "the quantity was not a whole number of lots"
            rx("token|session|logged out|login").containsMatchIn(m) -> "the Zerodha session had ended"
            rx("\\bban\\b|banned|blocked|not allowed|rms").containsMatchIn(m) -> "the broker's risk system (RMS) blocked it"
            rx("cancelled by (?:the )?user|user cancel").containsMatchIn(m) -> "it was cancelled by hand (from the app or Kite)"
            rx("expired|validity").containsMatchIn(m) -> "its validity ran out"
            else -> null
        }
    }

    private fun said(m: String): String {
        var s = m.replace("`", "").replace('"', '\'').trim().trimEnd('.', ' ')
        if (s.length > 140) s = s.take(137).trimEnd() + "..."
        return s
    }

    private fun matches(o: Ord, a: Asked): Boolean =
        (a.venue == null || o.venue.equals(a.venue, true)) &&
            (a.market == null || o.symbol.uppercase(Locale.ENGLISH).startsWith(a.market.name)) &&
            (a.side == null || o.side.equals(a.side, true)) &&
            (a.time == null || o.time?.let { it.hour == a.time.hour && it.minute == a.time.minute } == true ||
                o.ended?.let { it.hour == a.time.hour && it.minute == a.time.minute } == true) &&
            (a.strike == null || o.symbol.contains(a.strike)) &&
            (a.right == null || o.symbol.uppercase(Locale.ENGLISH).endsWith(a.right)) &&
            (a.leg == null || leg(o.by)?.lowercase(Locale.ENGLISH)?.let { l -> a.leg in l || (a.leg == "stop" && l == "sl") || (a.leg == "exit" && l != "entry") } == true)

    private fun wanted(o: Ord, w: Want) = when (w) {
        Want.CANCELLED -> isCancelled(o.status)
        Want.REJECTED -> isRejected(o.status)
        Want.ENDED -> isCancelled(o.status) || isRejected(o.status)
        Want.ANY -> true
    }

    /** The latest of [os] (ended time, else placed; the later in the list when the same). */
    private fun latest(os: List<IndexedValue<Ord>>): Ord? =
        os.maxWithOrNull(compareBy<IndexedValue<Ord>> { it.value.ended ?: it.value.time ?: LocalTime.MIN }.thenBy { it.index })?.value

    /** The order in short: "Paper SELL 60 FINNIFTY27OCT2624800CE placed at 09:21". */
    private fun short(o: Ord) = "${o.venue} ${o.side.uppercase(Locale.ENGLISH)} ${o.qty} ${o.symbol}" + (o.time?.let { " placed at ${hm(it)}" } ?: "")

    private fun what(w: Want) = when (w) {
        Want.CANCELLED -> "cancelled"; Want.REJECTED -> "rejected"; Want.ENDED -> "cancelled or rejected"; Want.ANY -> ""
    }

    /** What [a] names, said: " BankNifty", " paper", " stop-loss". */
    private fun named(a: Asked): String = listOfNotNull(a.venue?.let { if (it == "Paper") "paper" else it }, a.market?.label,
        a.strike?.let { s -> s + (a.right?.let { " $it" } ?: "") }, a.side?.lowercase(Locale.ENGLISH), a.leg?.let { if (it == "stop") "stop-loss" else it },
        a.time?.let { "${hm(it)}" }).joinToString(" ").let { if (it.isEmpty()) "" else " $it" }

    /** The fill beside [o]: the same account, option and side, filled at or after it was placed (the exit that closed it). */
    private fun sibling(o: Ord, all: List<Ord>): Ord? = all.filter {
        it !== o && it.venue == o.venue && it.symbol == o.symbol && it.side.equals(o.side, true) && isFilled(it.status) &&
            (o.time == null || it.time == null || !it.time.isBefore(o.time))
    }.minByOrNull { it.time ?: LocalTime.MAX }

    private fun fillSaid(s: Ord): String {
        val by = leg(s.by)?.let { l -> " by " + (who(s.by)?.let { "$it's " } ?: "the ") + legShort(l) }
            ?: placedBy(s.by)?.let { " ($it)" } ?: ""
        return (s.time?.let { "at ${hm(it)}" } ?: "later") + by + " (${s.side.uppercase(Locale.ENGLISH)} ${s.qty}" +
            (if (s.avg > 0) " at ${px(s.avg)}" else "") + ")"
    }

    /** Why [o] ended, as sentences. */
    fun why(o: Ord, all: List<Ord>): String {
        val ended = if (isRejected(o.status)) "rejected" else "cancelled"
        val out = ArrayList<String>()
        val l = leg(o.by)?.lowercase(Locale.ENGLISH)
        val s = sibling(o, all)
        val msg = o.message?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", true) }
        val noted = o.noted?.trim()?.takeIf { it.isNotEmpty() }
        if (noted != null) {
            out += "It was $ended because ${noted(noted)}."
            if (s != null && (noted.startsWith("exit:") || noted == "position_closed" || noted.startsWith("oco:")))
                out += "The position was closed ${fillSaid(s)}."
        }
        if (msg != null) {
            val p = plain(msg)
            out += (if (noted == null && p != null) "It was $ended because $p. " else "") + "${if (o.venue == "Paper") "The paper book" else o.venue} said: \"${said(msg)}\"."
        }
        if (noted == null && msg == null) {
            val protective = l != null && l != "entry" && ("stop" in l || "target" in l || l == "sl")
            when {
                protective && s != null -> {
                    val sl = leg(s.by)?.lowercase(Locale.ENGLISH)
                    out += if ("target" in l!! && sl != null && "stop" in sl && "index" !in sl) "Its stop filled ${fillSaid(s)}, so its target was cancelled (one cancels the other)."
                    else if ("stop" in l && sl != null && "target" in sl) "Its target filled ${fillSaid(s)}, so its stop was cancelled (one cancels the other)."
                    else "The position was closed ${fillSaid(s)}, so the ${legShort(l)} was no longer needed and was taken out of the book."
                    out += "That is read from the orders: the app did not note a reason on the order itself."
                }
                isCancelled(o.status) && (o.ended ?: o.time)?.let { !it.isBefore(LocalTime.of(15, 15)) } == true && o.product?.equals("NRML", true) != true -> {
                    out += "It ended at or after 15:15, when intraday orders still working are squared off - most likely that, though no reason was recorded."
                }
                else -> {
                    out += "The app has no reason recorded for it, and ${if (o.venue == "Paper") "the paper book" else o.venue} gave no message" +
                        (if (o.venue == "Zerodha" && isCancelled(o.status)) " (Zerodha says nothing for a cancel made from the app or Kite)" else "") + "."
                    if (s != null && l != "entry") out += "What happened beside it: the same option was ${if (s.side.equals("SELL", true)) "sold" else "bought"} ${fillSaid(s)}."
                }
            }
        }
        return out.joinToString(" ")
    }

    /**
     * The answer to [a] from today's [orders] (paper and Zerodha, oldest first). [notes]: what the app could not read
     * ("Zerodha is not logged in today, so only paper orders were read.").
     */
    fun answer(a: Asked, orders: List<Ord>, notes: List<String> = emptyList()): String {
        val tail = notes.joinToString("") { " " + it.trim() }
        if (orders.isEmpty()) return "There are no orders today, Boss, so nothing was cancelled or rejected.$tail"
        val idx = orders.withIndex().toList()
        val pool = idx.filter { matches(it.value, a) }
        val nameSaid = named(a)
        if (a.want == Want.ANY) {
            val o = latest(pool) ?: return "I don't see a$nameSaid order today, Boss.$tail"
            val head = "Boss, your last$nameSaid order: ${short(o)}" + (placedBy(o.by)?.let { ", $it" } ?: "")
            if (isCancelled(o.status) || isRejected(o.status))
                return "$head - ${if (isRejected(o.status)) "rejected" else "cancelled"}${o.ended?.let { " at ${hm(it)}" } ?: ""}. ${why(o, orders)}$tail"
            val state = when {
                isFilled(o.status) -> "filled" + (if (o.avg > 0) " at ${px(o.avg)}" else "")
                isOpen(o.status) -> "still working (${o.status.lowercase(Locale.ENGLISH)})"
                else -> o.status.lowercase(Locale.ENGLISH)
            }
            val ended = latest(pool.filter { wanted(it.value, Want.ENDED) })
            return "$head - $state." + (ended?.let { e -> " The last cancelled or rejected one: ${short(e)}" + (placedBy(e.by)?.let { ", $it" } ?: "") +
                " - ${if (isRejected(e.status)) "rejected" else "cancelled"}. ${why(e, orders)}" } ?: "") + tail
        }
        val hits = pool.filter { wanted(it.value, a.want) }
        var o = latest(hits)
        var lead = "Boss, your last ${what(a.want)}$nameSaid order"
        if (o == null) {
            // Asked for a cancel and the order was rejected (or the other way round): the other is said, as such.
            val other = if (a.want == Want.ENDED) null else latest(pool.filter { wanted(it.value, Want.ENDED) })
            val none = "None of today's$nameSaid orders was ${what(a.want)}, Boss."
            if (other == null) {
                val anyEnded = if (nameSaid.isEmpty()) null else latest(idx.filter { wanted(it.value, Want.ENDED) })
                return none + (anyEnded?.let { " The last cancelled or rejected order today was ${short(it)}" + (placedBy(it.by)?.let { b -> ", $b" } ?: "") + "." } ?: "") + tail
            }
            o = other
            lead = "$none The last ${if (isRejected(other.status)) "rejected" else "cancelled"}$nameSaid one"
        }
        val count = hits.size
        val more = if (count > 1) " It is the latest of $count ${what(a.want)} orders today; name one by time or index (\"why was the " +
            "${hm(hits.first().value.time ?: LocalTime.of(9, 15))} order ${if (isRejected(hits.first().value.status)) "rejected" else "cancelled"}\") for another." else ""
        return "$lead: ${short(o)}" + (placedBy(o.by)?.let { ", $it" } ?: "") +
            (o.ended?.let { " - ${if (isRejected(o.status)) "rejected" else "cancelled"} at ${hm(it)}" } ?: "") + ". " + why(o, orders) + more + tail
    }
}
