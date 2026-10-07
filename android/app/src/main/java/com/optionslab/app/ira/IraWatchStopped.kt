package com.optionslab.app.ira

import android.content.Context

/**
 * The order watch went silent in market hours ([com.optionslab.app.work.Heartbeat] found it, tried to restart it and posted
 * its notification): Jarvis puts once in the chat which open positions, and which of their stops and targets, are now
 * unwatched, and what keeps the phone from stopping it again ([com.optionslab.ira.WatchStopped]). Only speaks: nothing is
 * placed, moved, closed or restarted here. Called once per stall (the Heartbeat's own once-a-day alert); with no open
 * position nothing is said. Reads the paper book and the arms' state from the phone; Zerodha's positions only when
 * logged in, waited on 5 seconds at most (run apart: [com.optionslab.app.data.Broker.within]). Logged in with Zerodha
 * unreadable and nothing else open, Jarvis still says that its positions could not be read.
 */
internal object IraWatchStopped {
    suspend fun tell(context: Context, since: java.time.LocalTime?) {
        if (!com.optionslab.app.BuildConfig.JARVIS) return
        val owners = runCatching { com.optionslab.app.data.Strategies.owners() }.getOrDefault(emptyMap())
        val prot = runCatching { com.optionslab.app.data.Protections.active() }.getOrDefault(emptyList())
        val arms = runCatching { com.optionslab.app.data.OrbArms.view().arms }.getOrDefault(emptyList())
            .mapNotNull { a -> a.open?.let { a.arm.label to it } }
        val legs = ArrayList<com.optionslab.ira.WatchStopped.Leg>()
        // Paper: from the book on the phone (no quote is fetched: the network may be what stopped the watch).
        runCatching {
            val st = com.optionslab.app.data.Paper.state
            st.positions.filter { it.quantity != 0 }.forEach { p ->
                val arm = arms.firstOrNull { (_, o) -> !o.live && o.symbol == p.symbol }
                val lastFill = st.trades.lastOrNull { it.symbol == p.symbol }
                val by = arm?.first ?: lastFill?.let { owners["paper:${it.orderId}"] }?.substringBefore(" · ")?.trim()
                    ?.let { com.optionslab.ira.ArmOwners.arm(it) }?.takeIf { it.isNotBlank() && it != "Manual" }
                val pr = prot.firstOrNull { !it.live && it.symbol == p.symbol }
                // A resting paper stop (SL / SL-M, the other side) fills only while the watch runs.
                val resting = st.orders.lastOrNull { o -> o.symbol == p.symbol && o.pendingQuantity > 0 &&
                    o.status == com.optionslab.engine.sandbox.OrderStatus.TRIGGER_PENDING && o.action == (if (p.quantity > 0) "SELL" else "BUY") }
                legs += com.optionslab.ira.WatchStopped.Leg("Paper", p.symbol, p.quantity, by,
                    stop = pr?.stop ?: arm?.second?.stopTrigger ?: resting?.triggerPrice?.toDouble(),
                    target = pr?.target)
            }
        }
        var unread = false
        if (com.optionslab.app.data.Broker.loggedIn) {
            val open = com.optionslab.app.data.Broker.within(5_000) { com.optionslab.app.data.Broker.positionBook() }
                ?.net?.filter { it.open }
            if (open == null) unread = true
            open.orEmpty().forEach { p ->
                val arm = arms.firstOrNull { (_, o) -> o.live && o.symbol == p.symbol }
                val pr = prot.firstOrNull { it.live && it.symbol == p.symbol }
                // The stop and whether it rests at Zerodha come from the same place: the protection's own stop (its
                // resting stop order), else the arm's stop trigger (the arm's resting stop order).
                val prStop: Double? = pr?.stop
                val armPos = arm?.second
                val armStop: Double? = armPos?.stopTrigger
                val stop: Double? = prStop ?: armStop
                val atBroker = if (prStop != null) pr?.stopOrderId != null else armStop != null && armPos?.stopOrderId != null
                legs += com.optionslab.ira.WatchStopped.Leg("Zerodha", p.symbol, p.qty, arm?.first, stop = stop,
                    target = pr?.target, stopAtBroker = stop != null && atBroker)
            }
        }
        val unrestricted = runCatching {
            context.getSystemService(android.os.PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName)
        }.getOrNull()
        val now = com.optionslab.app.data.Market.now().toLocalTime()
        val text = com.optionslab.ira.WatchStopped.text(since, now, legs, unrestricted, unread) ?: return
        // No switch gates it (the Heartbeat's own alert): filed with the position-safety notes (Coach) in Today's notes.
        runCatching { IraHub.noteAloud(text, com.optionslab.ira.SpeakChoice.Weight.IMPORTANT, null, com.optionslab.ira.TodayNotes.Category.COACH) }
        runCatching { IraActivity.add("Told Boss the order watch stopped with ${legs.size} position${if (legs.size == 1) "" else "s"} open.") }
    }
}
