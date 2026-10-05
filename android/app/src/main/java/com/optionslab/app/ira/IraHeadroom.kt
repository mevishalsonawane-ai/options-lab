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
        val snap = runCatching { com.optionslab.app.data.Paper.snapshot() }.getOrNull()
        val paper = snap?.let { sn -> runCatching { com.optionslab.app.data.Guard.paperAccount(sn) }.getOrNull() }
        if (paper != null) books += Headroom.Book("Paper", paper, s.guardLimits(paper = true), enforced = false)
        // The account his mode trades first.
        val ordered = if (s.live) books else books.sortedBy { it.where != "Paper" }
        val tripped = runCatching { com.optionslab.app.data.LossBreaker.trippedToday() }.getOrDefault(false)
        val note = when {
            loggedIn && books.none { it.where == "Zerodha" } -> "Zerodha's account could not be read just now, so this is paper only. "
            !loggedIn && runCatching { Broker.configured }.getOrDefault(false) -> "Zerodha isn't logged in today, so this is paper only. "
            else -> ""
        }
        val base = note + Headroom.say(ordered, asked, tripped)
        if (asked != Headroom.Asked.LOSS) return base
        // The worst case from here with every open leg at its stop ([com.optionslab.ira.AtStops], round 26), against the
        // daily loss limit left on each account. Reads only: no stop is set, moved or suggested.
        val legs = runCatching { legs(snap, books.any { it.where == "Zerodha" }) }.getOrDefault(emptyList())
        val left: Map<String, Double?> = books.associate { b -> b.where to com.optionslab.ira.AtStops.left(b) }
        val worst = runCatching { com.optionslab.ira.AtStops.say(legs, left) }.getOrNull() ?: return base
        // Before the closing "your call" line, so the answer still ends with the decision left to him.
        val tail = "Facts only, Boss - what you do with them is your call."
        return if (base.endsWith(tail)) base.removeSuffix(tail) + worst + " " + tail else "$base $worst"
    }

    /**
     * Every open leg with its stop, as [IraWatchStopped] reads them: paper from the book (the protection's stop, else the
     * arm's stop trigger, else a resting SL order), Zerodha (only when its account was read) from the position book, its
     * stop the protection's or the arm's and whether that stop rests at Zerodha.
     */
    private suspend fun legs(snap: com.optionslab.app.data.Paper.Snapshot?, zerodha: Boolean): List<com.optionslab.ira.AtStops.Leg> {
        val prot = runCatching { com.optionslab.app.data.Protections.active() }.getOrDefault(emptyList())
        val arms = runCatching { com.optionslab.app.data.OrbArms.view().arms }.getOrDefault(emptyList())
            .mapNotNull { a -> a.open?.let { a.arm.label to it } }
        val out = ArrayList<com.optionslab.ira.AtStops.Leg>()
        if (snap != null) runCatching {
            val st = com.optionslab.app.data.Paper.state
            snap.positions.positions.filter { it.quantity != 0 }.forEach { p ->
                val arm = arms.firstOrNull { (_, o) -> !o.live && o.symbol == p.symbol }
                val pr = prot.firstOrNull { !it.live && it.symbol == p.symbol }
                val resting = st.orders.lastOrNull { o -> o.symbol == p.symbol && o.pendingQuantity > 0 &&
                    o.status == com.optionslab.engine.sandbox.OrderStatus.TRIGGER_PENDING && o.action == (if (p.quantity > 0) "SELL" else "BUY") }
                out += com.optionslab.ira.AtStops.Leg("Paper", p.symbol, p.quantity, p.ltp,
                    stop = pr?.stop ?: arm?.second?.stopTrigger ?: resting?.triggerPrice?.toDouble())
            }
        }
        if (zerodha) {
            val open = Broker.within(ZERODHA_MS) { Broker.positionBook() }?.net?.filter { it.open }.orEmpty()
            open.forEach { p ->
                val arm = arms.firstOrNull { (_, o) -> o.live && o.symbol == p.symbol }
                val pr = prot.firstOrNull { it.live && it.symbol == p.symbol }
                val prStop: Double? = pr?.stop
                val armPos = arm?.second
                val armStop: Double? = armPos?.stopTrigger
                val stop: Double? = prStop ?: armStop
                val atBroker = if (prStop != null) pr?.stopOrderId != null else armStop != null && armPos?.stopOrderId != null
                out += com.optionslab.ira.AtStops.Leg("Zerodha", p.symbol, p.qty, p.last, stop = stop, stopAtBroker = stop != null && atBroker)
            }
        }
        return out
    }
}
