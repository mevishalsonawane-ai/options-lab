package com.optionslab.app.ira

import com.optionslab.app.data.AppSettings
import com.optionslab.app.data.Broker
import com.optionslab.ira.Headroom

/**
 * "How close am I to my limits?" ([Headroom], usefulness round 13): each account as the guard itself judges it - Zerodha
 * from [Broker.accountNow] (logged in only), paper from [com.optionslab.app.data.Guard.paperAccount] - against the limits
 * the order checks use ([AppSettings.guardLimits]). Reads only: nothing is placed, changed or closed, and no limit is
 * moved. Boss's account, so the hub asks for it only on an unlocked phone; not in IraGoldAlgo.
 */
object IraHeadroom {
    private const val ZERODHA_MS = 8_000L

    suspend fun answer(asked: Headroom.Asked): String {
        val s = AppSettings.load()
        val books = ArrayList<Headroom.Book>()
        val loggedIn = runCatching { Broker.loggedIn }.getOrDefault(false)
        if (loggedIn) {
            val live = Broker.within(ZERODHA_MS) { Broker.accountNow() }
            if (live != null) books += Headroom.Book("Zerodha", live, s.guardLimits(paper = false), enforced = true)
        }
        val paper = runCatching { com.optionslab.app.data.Guard.paperAccount(com.optionslab.app.data.Paper.snapshot()) }.getOrNull()
        if (paper != null) books += Headroom.Book("Paper", paper, s.guardLimits(paper = true), enforced = false)
        // The account his mode trades first.
        val ordered = if (s.live) books else books.sortedBy { it.where != "Paper" }
        val tripped = runCatching { com.optionslab.app.data.LossBreaker.trippedToday() }.getOrDefault(false)
        val note = when {
            loggedIn && books.none { it.where == "Zerodha" } -> "Zerodha's account could not be read just now, so this is paper only. "
            !loggedIn && runCatching { Broker.configured }.getOrDefault(false) -> "Zerodha isn't logged in today, so this is paper only. "
            else -> ""
        }
        return note + Headroom.say(ordered, asked, tripped)
    }
}
