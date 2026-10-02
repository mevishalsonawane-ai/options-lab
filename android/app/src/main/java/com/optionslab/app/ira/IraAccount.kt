package com.optionslab.app.ira

import com.optionslab.app.data.AppSettings
import com.optionslab.app.data.Broker
import com.optionslab.ira.AppFacts
import com.optionslab.ira.AppView
import com.optionslab.ira.Section
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Everything the app knows, read for Ira (rule 1: inside the app only; read only - nothing here places, cancels or
 * changes anything): the paper account and, when you are logged in to Zerodha, your Zerodha orders, positions and
 * funds; P&L by day; every strategy (the strategy module, the Pine arms, the ORB arms); risk limits and the loss
 * breaker; stops and targets; alarms; settings; and the app's status. Only the [sections] asked for are read.
 */
internal object IraAccount {
    /** TEST ONLY: the view instead of the app's books. */
    @Volatile var testView: (suspend (Set<Section>) -> AppView?)? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "test seam" }; field = v }

    /** A Zerodha read waits at most this long; a slow one is left out and said so. */
    private const val ZERODHA_MS = 8_000L

    /** The option chain of [u] (nearest expiry, the strikes near the money) as the Options tab prices it, or null. */
    suspend fun chain(u: String): com.optionslab.engine.options.ChainSnapshot? = runCatching {
        val lc = com.optionslab.app.data.Market.liveChain(u, near = 12)
        val symbols = lc.contracts.associate { (it.strike to it.right) to it.tradingSymbol }
        val rows = com.optionslab.app.data.OiBaseline.apply(com.optionslab.engine.options.ChainSnapshot.rowsFrom(lc.series, symbols, lc.lotSize))
        com.optionslab.engine.options.ChainSnapshot.of(u, lc.expiry, lc.spot, lc.lotSize, rows, com.optionslab.app.data.Market.now())
    }.getOrNull()

    /** [markets]: the indices the question names (the chain of Nifty and BankNifty when none). */
    suspend fun read(sections: Set<Section>, markets: List<com.optionslab.ira.Market> = emptyList()): AppView? {
        testView?.let { return it(sections) }
        return runCatching {
            val s = AppSettings.load()
            val mode = if (s.live) "Live" else "Paper"
            val out = LinkedHashMap<Section, List<String>>()
            val today = com.optionslab.app.data.Market.today()
            val wants = { x: Section -> x in sections }
            val zerodha = Broker.loggedIn && sections.any { it in setOf(Section.ORDERS, Section.POSITIONS, Section.PNL, Section.FUNDS) }

            if (sections.any { it in setOf(Section.ORDERS, Section.POSITIONS, Section.PNL, Section.FUNDS) }) {
                val snap = runCatching { com.optionslab.app.data.Paper.snapshot() }.getOrNull()
                val owners = runCatching { com.optionslab.app.data.Strategies.owners() }.getOrDefault(emptyMap())
                val orders = ArrayList<String>(); val pos = ArrayList<String>(); val pnl = ArrayList<String>(); val funds = ArrayList<String>()
                if (snap != null) {
                    orders += AppFacts.orders("Paper", snap.orders.orders.filter { it.timestamp.startsWith(today.toString()) }.sortedBy { it.timestamp }.map {
                        AppFacts.OrderLine(it.timestamp.drop(11).take(5), it.symbol, it.action, it.quantity, it.status, it.averagePrice,
                            owners["paper:${it.orderId}"] ?: it.strategy.takeIf { x -> x.isNotBlank() }, it.rejectionReason.takeIf { r -> r.isNotBlank() })
                    }, byWho = true)
                    pos += AppFacts.positions("Paper", snap.positions.positions.filter { it.quantity != 0 }.map {
                        AppFacts.Held(it.symbol, it.quantity, it.averagePrice, it.ltp, it.pnl) })
                    pnl += AppFacts.pnl("Paper", snap.dayPnl, snap.positions.totalTodayRealizedPnl, snap.positions.totalUnrealizedPnl)
                    funds += "Paper funds: ${AppFacts.amt(snap.funds.availableCash)} available, ${AppFacts.amt(snap.funds.utilisedDebits)} in use."
                } else { orders += "The paper account did not open just now."; pos += orders.last(); pnl += orders.last() }
                if (zerodha) {
                    val z = withTimeoutOrNull(ZERODHA_MS) {
                        runCatching {
                            Triple(Broker.orders(), Broker.positionBook(), runCatching { Broker.funds() }.getOrNull())
                        }.getOrNull()
                    }
                    if (z == null) orders += "Zerodha did not answer just now, so its orders are not included."
                    else {
                        val (zo, zp, zf) = z
                        orders += AppFacts.orders("Zerodha", zo.filter { it.placedAt.startsWith(today.toString()) || it.placedAt.length < 10 }.sortedBy { it.placedAt }.map {
                            AppFacts.OrderLine(it.placedAt.drop(11).take(5).ifBlank { it.placedAt.take(5) }, it.symbol, it.side, it.qty, it.status, it.avg,
                                owners[it.id] ?: it.tag.takeIf { t -> t.isNotBlank() }, it.message.takeIf { m -> m.isNotBlank() })
                        }, byWho = true)
                        pos += AppFacts.positions("Zerodha", zp.net.filter { it.open }.map { AppFacts.Held(it.symbol, it.qty, it.avg, it.last, it.pnl) })
                        pnl += AppFacts.pnl("Zerodha", zp.net.sumOf { it.pnl }, zp.net.sumOf { it.realised }, zp.net.sumOf { it.unrealised })
                        zf?.let { funds += "Zerodha funds: ${AppFacts.amt(it.available)} available, ${AppFacts.amt(it.used)} used, net ${AppFacts.amt(it.net)}." }
                    }
                } else if (Broker.linked) orders += "Zerodha: not logged in today, so only the paper account is read."
                out[Section.ORDERS] = orders; out[Section.POSITIONS] = pos; out[Section.PNL] = pnl; out[Section.FUNDS] = funds
            }

            if (wants(Section.HISTORY)) {
                val h = ArrayList<String>()
                for (live in listOf(false, true)) {
                    val days = com.optionslab.app.data.DailyPnl.all(live).mapValues { it.value.pnl to it.value.trades }
                    if (!live || days.isNotEmpty()) h += AppFacts.history(if (live) "Zerodha" else "Paper", days, today)
                    val trips = runCatching { com.optionslab.app.data.TradeBook.trips(live) }.getOrDefault(emptyList())
                    if (trips.isNotEmpty()) h += "${if (live) "Zerodha" else "Paper"} round trips recorded: ${trips.size}, " +
                        "${trips.count { it.net > 0 }} won, net ${AppFacts.rs(trips.sumOf { it.net })}."
                }
                out[Section.HISTORY] = h
            }

            if (wants(Section.STRATEGIES)) {
                val arms = ArrayList<AppFacts.ArmLine>()
                runCatching { com.optionslab.app.data.Strategies.all() }.getOrDefault(emptyList()).forEach { e ->
                    val r = e.run
                    arms += AppFacts.ArmLine(e.def.name, "Strategy", e.def.scheduler?.enabled == true,
                        "${e.def.underlying}, ${r?.let { "${it.mode.name.lowercase()} run ${it.status.name.lowercase()}" } ?: "not running"}",
                        r?.pnlTotal, null)
                }
                val held = com.optionslab.app.data.PineAuto.held.value
                com.optionslab.app.data.PineScripts.items.value.filter { it.auto.on || com.optionslab.app.data.PineAuto.todayOf(it.id) != null }.forEach { x ->
                    arms += AppFacts.ArmLine(x.name, "Pine", x.auto.on, "${x.auto.symbol} ${x.auto.interval}, ${x.auto.mode}",
                        com.optionslab.app.data.PineAuto.todayOf(x.id), held[x.id]?.let { h -> "${h.qty} ${h.symbol}" })
                }
                runCatching { com.optionslab.app.data.OrbArms.view().arms }.getOrDefault(emptyList()).forEach { a ->
                    val closed = a.today.filter { !it.open }
                    arms += AppFacts.ArmLine(a.arm.label, "ORB", a.armed, a.status.ifBlank { if (a.armed) "armed" else "off" },
                        if (closed.isEmpty()) null else closed.sumOf { (it.grossPnl ?: 0.0) - it.charges }, a.open?.let { "${it.qty} ${it.symbol}" }, a.today.size)
                }
                out[Section.STRATEGIES] = AppFacts.arms(arms, rank = true)
            }

            if (wants(Section.RISK)) out[Section.RISK] = listOf(
                "Kill switch: ${if (s.guardKill) "ON - new live positions are refused" else "off"}.",
                "Live limits: daily loss ${AppFacts.amt(s.guardDailyLoss)}, drawdown ${s.guardDrawdownPct}%, ${s.guardMaxOpen} open positions, " +
                    "${s.guardMaxTrades} trades a day, ${s.guardMaxLots} lots an order, order value ${AppFacts.amt(s.guardMaxValue)}, " +
                    "exposure ${AppFacts.amt(s.guardMaxExposure)}, no new positions after ${"%02d:%02d".format(s.guardCutoff / 60, s.guardCutoff % 60)}.",
                "Paper limits: daily loss ${AppFacts.amt(s.guardPaperDailyLoss)}, drawdown ${s.guardPaperDrawdownPct}%, ${s.guardPaperTrades} trades a day.",
                "Daily loss breaker: ${if (com.optionslab.app.data.LossBreaker.trippedToday()) "tripped today - the bots sold and stopped" else "not tripped today"}.",
                "Naked shorts: ${if (s.guardNakedShort) "refused" else "allowed"}. Expiry-day square-off at 15:05: ${if (s.expirySquareOff) "on" else "off"}.",
            )

            if (wants(Section.PROTECTIONS)) {
                val p = runCatching { com.optionslab.app.data.Protections.active() }.getOrDefault(emptyList())
                out[Section.PROTECTIONS] = if (p.isEmpty()) listOf("No stops or targets are set on any position.")
                else p.map { "${if (it.live) "Zerodha" else "Paper"} ${it.symbol} (${it.qty}): ${it.describe()}." }
            }

            if (wants(Section.ALARMS)) {
                val a = runCatching { com.optionslab.app.data.Alarms.all() }.getOrDefault(emptyList())
                out[Section.ALARMS] = (if (a.isEmpty()) listOf("No price alarms are set.") else a.map {
                    "Alarm: ${it.describe()}, ${if (!it.enabled) "off" else if (it.firedAtMillis > 0) "fired" else "waiting"}${it.note.takeIf { n -> n.isNotBlank() }?.let { n -> " ($n)" } ?: ""}." }) +
                    listOfNotNull(s.pnlLossAlert.takeIf { it > 0 }?.let { "P&L alert when the day's loss reaches ${AppFacts.amt(it)}." },
                        s.pnlProfitAlert.takeIf { it > 0 }?.let { "P&L alert when the day's profit reaches ${AppFacts.amt(it)}." })
            }

            if (wants(Section.SETTINGS)) out[Section.SETTINGS] = listOf(
                "Mode: $mode${if (s.live) "; real orders ${if (s.allowRealOrders) "allowed" else "not allowed"}" else " (orders go to the paper account)"}.",
                "Order product ${s.orderProduct}; one-tap orders ${if (s.oneTapOrders) "on" else "off"}; at most ${s.maxOrdersPerDay} orders a day, " +
                    "${s.maxLotsPerOrder} lots and ${AppFacts.amt(s.maxOrderValue)} an order.",
                "Fingerprint unlock ${if (s.biometric) "on" else "off"}; app locks after ${s.idleSeconds} seconds idle; nightly harvest ${if (s.nightlyHarvest) "on" else "off"}; " +
                    "market watch ${if (s.liveWatch) "on" else "off"}; theme ${s.theme}.",
            )

            if (wants(Section.STATUS)) {
                val m = com.optionslab.app.data.Market
                val hol = runCatching { com.optionslab.app.data.Holidays.book() }.getOrNull()
                out[Section.STATUS] = listOfNotNull(
                    "Mode: $mode.",
                    "Market: ${if (m.isOpen()) "open now" else "closed now"}; today is ${if (m.isTradingDay(today)) "a trading day" else "not a trading day"}.",
                    hol?.upcoming(today.plusDays(1))?.firstOrNull()?.let { (d, n) -> "Next market holiday: $d ($n)." },
                    listOf("NIFTY", "BANKNIFTY", "FINNIFTY").mapNotNull { u -> m.upcomingExpiries(u).firstOrNull()?.let { "$u $it" } }
                        .takeIf { it.isNotEmpty() }?.let { "Next expiries: ${it.joinToString(", ")}." },
                    when {
                        !Broker.configured -> "Zerodha: not set up."
                        Broker.loggedIn -> "Zerodha: logged in${Broker.userName?.let { " as $it" } ?: ""}; ${Broker.sentToday()} orders sent from the app today."
                        else -> "Zerodha: set up, not logged in today."
                    },
                    com.optionslab.app.security.SecurePrefs.getString("harvest.last")?.let { "Last data harvest: $it." },
                    com.optionslab.app.data.StaticIp.registered?.let { "Registered static IP: $it." },
                )
            }
            if (wants(Section.FLOWS)) out[Section.FLOWS] = com.optionslab.ira.Flows.lines(withTimeoutOrNull(20_000) { IraHub.flows() } ?: emptyList())
            if (wants(Section.CHAIN)) {
                val us = markets.mapNotNull { mk -> when (mk) { com.optionslab.ira.Market.NIFTY -> "NIFTY"; com.optionslab.ira.Market.BANKNIFTY -> "BANKNIFTY"
                    com.optionslab.ira.Market.FINNIFTY -> "FINNIFTY"; else -> null } }.ifEmpty { listOf("NIFTY", "BANKNIFTY") }
                out[Section.CHAIN] = us.flatMap { u -> withTimeoutOrNull(25_000) { chain(u) }?.let { com.optionslab.ira.ChainRead.lines(it) }
                    ?: listOf("The $u option chain did not load just now.") } +
                    (if (markets.any { it == com.optionslab.ira.Market.SENSEX || it == com.optionslab.ira.Market.GOLD }) listOf("Option chains are read for Nifty, BankNifty and FinNifty.") else emptyList())
            }
            if (wants(Section.EVENTS)) out[Section.EVENTS] = IraEvents.upcoming().map { com.optionslab.ira.Events.line(it, today) }
                .ifEmpty { listOf("No events in the next two weeks.") }
            AppView(mode, out)
        }.getOrNull()
    }
}
