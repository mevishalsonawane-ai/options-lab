package com.optionslab.ira

/**
 * Trading words explained (Jarvis self-improvement, 2026-10-03): "what is theta", "explain max pain", "what does short
 * covering mean". Plain answers in Indian-market terms. A word that names a figure Jarvis can read (PCR, max pain, VIX,
 * open interest) is explained only when its meaning is asked, so "what is the Nifty PCR" still gets the number. Pure.
 */
object Glossary {
    private data class Term(val words: List<String>, val figure: Boolean, val text: String)

    private val TERMS = listOf(
        Term(listOf("pcr", "put call ratio", "put-call ratio"), true, "PCR, the put-call ratio, is the open interest in puts divided by that in calls. Above about 1.2 more puts are written, which traders read as support below; under about 0.7 calls dominate, read as resistance above. It is a mood gauge, not a timing signal."),
        // (Round 10: "what is a call wall" was handed here by the chain's drift and found nothing.)
        Term(listOf("call wall", "put wall", "oi wall"), false, "A call wall is the strike with the most call open interest, and a put wall the one with the most put open interest. Option writers are said to defend them, so traders read the call wall as resistance and the put wall as support - a reading of positions, not a rule, and walls shift through the day."),
        Term(listOf("max pain"), true, "Max pain is the strike at which option buyers together would lose the most at expiry, so option writers gain the most. Prices are often said to drift towards it near expiry, but it is a tendency, not a rule."),
        Term(listOf("vix", "india vix", "volatility index"), true, "India VIX is the market's own guess of Nifty's swing over the next month, from option prices, as a yearly percentage. Divide it by about 16 for a usual one-day move: VIX 16 means about a 1% day. Rising VIX means fear and dearer options."),
        Term(listOf("open interest", "oi"), true, "Open interest is the number of option or futures contracts still open. For futures, rising OI with rising price means fresh buying and with falling price fresh selling; falling OI means positions are being closed. For options, rising OI at a strike usually means writers are adding there."),
        Term(listOf("theta", "time decay"), false, "Theta is how much an option's price falls each day from time passing, all else the same. It works against buyers and for sellers, and speeds up in the last days before expiry."),
        Term(listOf("delta"), false, "Delta is how much an option's price moves for a 1-point move in the index: about 0.5 at the money, near 1 deep in the money, near 0 far out of it. It is also a rough chance of expiring in the money."),
        Term(listOf("gamma"), false, "Gamma is how fast delta changes as the index moves. It is highest at the money near expiry, which is why expiry-day options can jump so sharply."),
        Term(listOf("vega"), false, "Vega is how much an option's price changes for a 1-point change in implied volatility. Buyers gain when volatility rises, sellers when it falls."),
        Term(listOf("implied volatility", "iv"), true, "Implied volatility is the swing the option price assumes. High IV makes options dear; it often falls after a big event, which hurts buyers even if they were right on direction."),
        Term(listOf("atm", "at the money"), false, "At the money is the strike nearest the index price. In the money: a call below the price or a put above it. Out of the money: the other way, cheaper but needing a bigger move."),
        Term(listOf("itm", "in the money", "otm", "out of the money"), false, "In the money is a call with a strike below the price, or a put above it: it has real value now. Out of the money is the opposite: cheaper, but all time value, needing a bigger move."),
        Term(listOf("straddle"), false, "A straddle is a call and a put at the same strike. Bought, it gains from a big move either way; sold, it gains if the market stays still, with risk on both sides."),
        Term(listOf("strangle"), false, "A strangle is an out-of-the-money call and put. Cheaper than a straddle to buy, needs a bigger move; sold, it has a wider range to stay inside."),
        Term(listOf("covered call", "covered calls"), false, "A covered call sells a call against shares or a future already held: the premium is earned, and the gain above the strike is given up."),
        Term(listOf("iron condor"), false, "An iron condor sells a strangle and buys a further strangle as protection: a limited gain if the market stays in a range, and a limited loss if it breaks out."),
        Term(listOf("bull call spread"), false, "A bull call spread buys a call and sells a higher one: cheaper than the call alone, with gain and loss both capped. A bear put spread is the mirror for a fall."),
        Term(listOf("short covering"), false, "Short covering is sellers buying back to close their positions, often pushing the price up quickly, with open interest falling."),
        Term(listOf("long unwinding"), false, "Long unwinding is buyers selling to close, pulling the price down with open interest falling: weakness from exits, not fresh shorts."),
        Term(listOf("premium"), false, "The premium is an option's price: what the buyer pays and the seller keeps if it expires worthless. Its value is intrinsic (in the money part) plus time value."),
        Term(listOf("stop loss", "stoploss", "sl"), false, "A stop loss is a price at which a trade is closed to cap the loss. On my trades it is 15% below the option's entry price, and the profit lock moves it up as the trade gains."),
        Term(listOf("trailing stop", "trailing stop loss", "trail"), false, "A trailing stop follows the price as a trade gains, so a winner is not allowed to turn into a loser. It never moves back against the trade."),
        Term(listOf("support"), true, "Support is a price where buying has stopped falls before, such as the day's low, the previous close or a round number. Below it, sellers often speed up."),
        Term(listOf("resistance"), true, "Resistance is a price where selling has stopped rises before. A clean break above it often brings more buying; a failure there often brings a pullback."),
        Term(listOf("breakout"), false, "A breakout is a move through a level that held before, ideally with rising volume. A false breakout comes straight back inside and often runs the other way."),
        Term(listOf("gap up", "gap down", "gap"), true, "A gap is the open far from the previous close, after overnight news or global markets. Gaps often partly fill in the first hour; one that holds shows strength."),
        Term(listOf("vwap"), false, "VWAP is the average price of the day weighted by volume. Above it buyers are in control for the day, below it sellers; big traders use it to judge their fills."),
        Term(listOf("orb", "opening range breakout", "opening range"), false, "The opening range is the high and low of the first 15 minutes. An opening range breakout trades the first clean move out of it, with the other side as the stop."),
        Term(listOf("expiry"), true, "Expiry is the day options stop trading and settle. Nifty's weekly options expire on Tuesday (the exchange can change the day); time decay and gamma are fiercest that day."),
        Term(listOf("lot size", "lot"), false, "A lot is the fixed number of units in one option or futures contract, set by the exchange and revised from time to time: a 1-point move in the option is worth one lot's units in rupees. The order screen shows the current size."),
        Term(listOf("margin"), false, "Margin is the money the broker blocks to let you hold a position. Buying options needs only the premium; selling options or futures needs far more, as the risk is open."),
        Term(listOf("square off", "squareoff"), false, "Squaring off is closing a position: selling what was bought or buying back what was sold. Intraday (MIS) positions are squared off by the broker near the close if left open."),
        Term(listOf("hedge", "hedging"), false, "A hedge is a second position that gains when the first loses, such as a bought put against bought shares. It costs some profit for limited loss."),
        Term(listOf("scalping", "scalp"), false, "Scalping is many quick trades for small moves. Charges and slippage take a large share of each gain, so it needs tight discipline."),
        Term(listOf("fii", "fiis", "dii", "diis"), true, "FIIs are foreign institutional investors and DIIs domestic ones, such as mutual funds. Their daily net buying or selling is a guide to the big money's mood."),
        // (Understanding round 15: "what is an inside day" found nothing; whether one day was one is InsideDays'.)
        Term(listOf("inside day", "inside days", "nr7", "nr7 day", "nr 7", "narrow range day", "narrow range 7"), false, "An inside day is a session whose high is below the day before's high and whose low is above its low: the whole day stayed inside the previous one's range. An NR7 day is the narrowest range (high to low) of the last seven sessions. Both mark the market coiling; traders watch the break of that range, which can come either way and does not always lead to a bigger day. Ask me \"was yesterday an inside day\" or \"what happens after an inside day\" for the record on this phone."),
        Term(listOf("m2m", "mtm", "mark to market"), false, "Mark to market is a position's profit or loss at today's price, before it is closed."),
    )

    private val MEANING = Regex(" (mean|means|meaning|explain|define|definition|what do you mean by|in simple words) ")
    private val WHATIS = Regex("^ (what is|what s|whats|what are|what does|what do) ")

    /** "Theta kya hai", "PCR ka matlab", "doji matlab kya hota hai" (audit, 5 Oct): the meaning asked in Hinglish. */
    private val HINDI_MEANING = listOf(
        Regex("^(.+?)\\s+(?:ka\\s+|ki\\s+|ke\\s+)?(?:matlab|meaning|arth)(?:\\s+kya)?(?:\\s+(?:hai|hota\\s+hai|hoti\\s+hai))?$") to "what does $1 mean",
        Regex("^(.+?)\\s+kya\\s+(?:hai|hota\\s+hai|hoti\\s+hai|hote\\s+hain)$") to "what is $1",
    )

    /** The explanation of a trading word asked about in [said], or null. */
    fun explain(said: String): String? {
        val plain = said.lowercase().replace(rx("[^a-z0-9 ]"), " ").replace(rx("\\s+"), " ").trim()
        // ("Mera stop loss kya hai" is Boss's own stop, not the word.)
        val text = HINDI_MEANING.takeUnless { rx("\\b(mera|meri|mere|apna|apni|apne|hamara|hamari)\\b").containsMatchIn(plain) }.orEmpty()
            .firstOrNull { it.first.matches(plain) }?.let { (r, to) -> r.replace(plain, to) } ?: said
        val t = " " + text.lowercase().replace(rx("[^a-z0-9 ]"), " ").replace(rx("\\s+"), " ").trim()
            .replace(rx("^(jarvis|hey jarvis|ok jarvis|boss) "), "") + " "
        val meaning = MEANING.containsMatchIn(t)
        // Only a plain "what does it mean" outweighs "today" or a number ("explain the gap down today" wants today's gap).
        val strict = rx(" (mean|means|meaning|define|definition|what do you mean by|in simple words) ").containsMatchIn(t)
        if (!meaning && !WHATIS.containsMatchIn(t)) return null
        val named = Market.mentioned(text).any { it != Market.VIX }
        // The longest word first ("iron condor" before "condor", "max pain" before "pain").
        val hit = TERMS.flatMap { term -> term.words.map { it to term } }.sortedByDescending { it.first.length }
            .firstOrNull { (w, _) -> t.contains(" ${w.replace("-", " ")} ") }?.second ?: return null
        // A figure Jarvis can read is explained only when its meaning is asked (else the number is what is wanted).
        if (hit.figure && !meaning) return null
        if (named && !meaning) return null
        // "What is the iv today", "what is the premium on 25000 ce": a figure wanted now, not the word.
        // ("NR7" is the word's own figure, never a number asked about.)
        if (!strict && (rx(" (today|now|current|currently|next|this|on|for|data|doing|available|at|of) ").containsMatchIn(t) || rx("\\d").containsMatchIn(t.replace(" nr7 ", " nr ")))) return null
        // "What is my margin / my stop loss": about the owner's own account, not the word.
        if (!meaning && rx(" (my|our) ").containsMatchIn(t)) return null
        return hit.text
    }
}
