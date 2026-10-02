package com.optionslab.app.ira

import com.optionslab.ira.AccountView

/**
 * The owner's own trading for Ira, read from what the app already keeps (rule 1: inside the app only): the paper
 * account (today's orders, open positions, P&L), who placed each order, the Pine arms and the ORB arms. Read only.
 */
internal object IraAccount {
    /** TEST ONLY: the view instead of the app's books. */
    @Volatile var testView: (suspend () -> AccountView?)? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "test seam" }; field = v }

    suspend fun read(): AccountView? {
        testView?.let { return it() }
        return runCatching {
            val live = runCatching { com.optionslab.app.data.AppSettings.load().live }.getOrDefault(false)
            val snap = com.optionslab.app.data.Paper.snapshot()
            val owners = runCatching { com.optionslab.app.data.Strategies.owners() }.getOrDefault(emptyMap())
            val today = com.optionslab.app.data.Market.today().toString()
            val positions = snap.positions.positions.filter { it.quantity != 0 }.map {
                AccountView.Held(it.symbol, it.quantity, it.averagePrice, it.ltp, it.pnl)
            }
            val orders = snap.orders.orders.filter { it.timestamp.startsWith(today) }.sortedBy { it.timestamp }.map {
                AccountView.OrderLine(it.timestamp.drop(11).take(5), it.symbol, it.action, it.quantity, it.status, it.averagePrice,
                    owners["paper:${it.orderId}"] ?: it.strategy.takeIf { s -> s.isNotBlank() }, it.rejectionReason.takeIf { r -> r.isNotBlank() })
            }
            val held = com.optionslab.app.data.PineAuto.held.value
            val pine = com.optionslab.app.data.PineScripts.items.value.filter { it.auto.on || com.optionslab.app.data.PineAuto.todayOf(it.id) != null }.map { s ->
                AccountView.ArmLine(s.name, "Pine", s.auto.on, "${s.auto.symbol} ${s.auto.interval}",
                    com.optionslab.app.data.PineAuto.todayOf(s.id), held[s.id]?.let { h -> "${h.qty} ${h.symbol}" })
            }
            val orb = runCatching { com.optionslab.app.data.OrbArms.view().arms }.getOrDefault(emptyList()).map { a ->
                val closed = a.today.filter { !it.open }
                AccountView.ArmLine(a.arm.label, "ORB", a.armed, a.status.ifBlank { if (a.armed) "armed" else "off" },
                    if (closed.isEmpty()) null else closed.sumOf { (it.grossPnl ?: 0.0) - it.charges },
                    a.open?.let { "${it.qty} ${it.symbol}" }, a.today.size)
            }
            AccountView(if (live) "Live" else "Paper", "Paper", snap.dayPnl, snap.positions.totalTodayRealizedPnl,
                snap.positions.totalUnrealizedPnl, positions, orders, pine + orb)
        }.getOrNull()
    }
}
