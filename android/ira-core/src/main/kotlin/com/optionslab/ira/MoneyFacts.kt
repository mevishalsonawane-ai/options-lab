package com.optionslab.ira

import java.util.Locale
import kotlin.math.abs

/**
 * Which money a figure is (12 Oct, Boss: "in the morning my portfolio was 4k, in the evening it was negative"). The morning
 * check said Zerodha's real "₹4,674 available to trade"; the day report's title added the PAPER account's day (−₹1,374) to
 * Zerodha's, and Home's money card said only "Capital": two different accounts read as one. Every money figure now says
 * whose it is - LIVE (Zerodha, real money) or PAPER (practice) - and the two are never added together. When Zerodha's
 * available money is below zero, [negativeWhy] says why from the figures the app already read. Pure.
 */
object MoneyFacts {
    /** The account a figure is from, in words. */
    fun venue(live: Boolean): String = if (live) "Zerodha (LIVE, real money)" else "Paper (practice, not your Zerodha money)"

    /** Home's money card heading. */
    fun cardTitle(live: Boolean): String = if (live) "Capital · Zerodha (LIVE)" else "Capital · PAPER (practice)"

    /** The line under Home's money card: whose figures these are. */
    fun cardNote(live: Boolean): String =
        if (live) "Your real Zerodha account. Paper trades are not in these figures."
        else "The paper (practice) account: not your Zerodha money. Your real balance is under LIVE (Zerodha)."

    /** "−₹1,374" / "+₹4,674". */
    fun rs(x: Double): String = (if (x < 0) "−₹" else "+₹") + String.format(Locale.ENGLISH, "%,.0f", abs(x))

    /**
     * The day report's title: [base], then each account's day apart ("Paper −₹1,374 · Zerodha +₹0"), never one total of
     * both. Null figures (that account did not trade) are left out.
     */
    fun dayTitle(base: String, paper: Double?, live: Double?): String {
        val parts = listOfNotNull(paper?.let { "Paper ${rs(it)}" }, live?.let { "Zerodha ${rs(it)}" })
        return if (parts.isEmpty()) base else base + " · " + parts.joinToString(" · ")
    }

    /** How [negativeWhy]'s one sentence starts (a short answer keeps it beside the figure). */
    const val NEGATIVE_HEAD = "Zerodha's available money is below zero"

    /** Zerodha's funds as the app read them (equity segment): what [negativeWhy] explains from. */
    data class Funds(
        val available: Double, val used: Double, val m2m: Double = 0.0, val realised: Double = 0.0,
        val span: Double = 0.0, val exposure: Double = 0.0, val optionPremium: Double = 0.0,
    )

    /**
     * Why Zerodha's available money is below zero, from what was already read ([f] the funds, [openPositions] the open
     * positions, [charges] today's charges, null unknown); null when it is not below zero. Words only.
     */
    fun negativeWhy(f: Funds, openPositions: Int, charges: Double?): String? {
        if (!(f.available < 0)) return null
        val why = ArrayList<String>()
        if (f.m2m < 0) why += "your open positions are down ${rs(f.m2m).removePrefix("−")} (marked to market: counted against the cash until they close)"
        else if (openPositions > 0 && f.m2m == 0.0) why += "$openPositions position${if (openPositions == 1) " is" else "s are"} open"
        if (f.realised < 0) why += "trades closed today lost ${rs(f.realised).removePrefix("−")}"
        val blocked = f.span + f.exposure
        if (blocked > 0) why += "${rs(blocked).removePrefix("+")} is blocked as margin for open futures or sold options"
        if (f.optionPremium > 0) why += "${rs(f.optionPremium).removePrefix("+")} was paid for options bought today"
        if (charges != null && charges > 0) why += "today's charges are about ${rs(charges).removePrefix("+")}"
        if (why.isEmpty()) why += "no open position or trade explains it: most likely a debit Zerodha booked (charges, a DP charge or a settlement) - see Kite, Funds"
        return "$NEGATIVE_HEAD (${rs(f.available)}): " + why.joinToString("; ") + "; paper trades never touch it."
    }
}
