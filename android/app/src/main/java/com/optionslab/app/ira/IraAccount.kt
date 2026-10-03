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

    /** The app's round trips (paper or Zerodha) with who made each, for the review. */
    fun trips(live: Boolean, owners: Map<String, String>): List<com.optionslab.ira.Insights.Trip> =
        runCatching { com.optionslab.app.data.TradeBook.trips(live) }.getOrDefault(emptyList()).map { t ->
            com.optionslab.ira.Insights.Trip(t.symbol, t.openedAt, t.closedAt, t.net, com.optionslab.app.data.TradeBook.ownerOf(t, owners))
        }

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
    /** Each section's lines as last read, and when (so a spoken question is answered at once). */
    private val cache = HashMap<Section, Pair<Long, List<String>>>()
    @Volatile private var cacheMode: String? = null
    const val FRESH_MS = 30_000L
    /** What Jarvis keeps ready while it listens: the questions asked most. */
    val WARM = setOf(Section.STATUS, Section.PNL, Section.POSITIONS, Section.ORDERS, Section.FUNDS, Section.RISK,
        Section.PROTECTIONS, Section.STRATEGIES, Section.ALARMS)

    /** Something changed (an order, a command): the next question reads afresh. */
    fun invalidate() = synchronized(cache) { cache.clear() }

    /**
     * [read], but from what was read in the last [FRESH_MS] when every section asked is there (questions naming a
     * market always read afresh).
     */
    suspend fun readFast(sections: Set<Section>, markets: List<com.optionslab.ira.Market> = emptyList(), question: String = ""): AppView? {
        // Asked with its own words (a time, this week's changes): always read afresh.
        if (sections.any { it in ASKED }) return read(sections, markets, question)
        val now = android.os.SystemClock.elapsedRealtime()
        if (markets.isEmpty() && testView == null) synchronized(cache) {
            val mode = cacheMode
            if (mode != null && sections.all { s -> cache[s]?.let { now - it.first < FRESH_MS } == true })
                return AppView(mode, sections.associateWith { cache.getValue(it).second })
        }
        val v = read(sections, markets) ?: return null
        if (markets.isEmpty() && testView == null) synchronized(cache) { v.lines.forEach { (k, l) -> cache[k] = now to l }; cacheMode = v.mode }
        return v
    }

    /** Reads [WARM] ahead (the listening keeper calls it), so those answers need no wait. */
    suspend fun warm() { invalidate(); readFast(WARM) }

    /** "Am I ready to go live?": each thing that should be in place first. */
    private suspend fun readyLines(s: AppSettings, today: java.time.LocalDate): List<String> {
        val protectedSymbols = runCatching { com.optionslab.app.data.Protections.active() }.getOrDefault(emptyList()).map { it.symbol }.toSet()
        val paperOpen = runCatching { com.optionslab.app.data.Paper.state.positions }.getOrNull()?.filter { it.quantity != 0 }?.map { it.symbol }.orEmpty()
        val liveOpen = if (Broker.loggedIn) withTimeoutOrNull(ZERODHA_MS) { runCatching { Broker.positionBook().net.filter { it.open }.map { it.symbol } }.getOrNull() }.orEmpty() else emptyList()
        val unguarded = (paperOpen + liveOpen).count { it !in protectedSymbols }
        val ctx = IraHub.appContext()
        return com.optionslab.ira.LiveReady.check(com.optionslab.ira.LiveReady.Facts(
            proven = if (com.optionslab.app.BuildConfig.JARVIS) com.optionslab.ira.JarvisTrades.proven(IraNewsTrades.closedRecord()) else null,
            zerodhaLoggedIn = Broker.loggedIn, killSwitchOn = s.guardKill, dailyLossLimit = s.guardDailyLoss, maxLots = s.guardMaxLots,
            voiceEnrolled = runCatching { VoiceGuard.enrolled }.getOrDefault(false),
            fingerprint = ctx?.let { com.optionslab.app.security.BiometricGate.fingerprintOn(it) } ?: false,
            marketOpenToday = com.optionslab.app.data.Market.isTradingDay(today), unguardedPositions = unguarded))
    }

    /** Sections answered from the question's own words: never from the cache. */
    private val ASKED = setOf(Section.WHATIF, Section.CHANGES, Section.SEARCH, Section.TIMEOFDAY, Section.REASONS, Section.EXPLAIN_POS)

    suspend fun read(sections: Set<Section>, markets: List<com.optionslab.ira.Market> = emptyList(), question: String = ""): AppView? {
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

            // No session today (a holiday or a weekend): "today's P&L" also gives the last session's.
            val closedDay = !com.optionslab.app.data.Market.isTradingDay(today)
            if (closedDay && Section.PNL in sections && Section.HISTORY !in sections) {
                val last = listOf(false, true).flatMap { live ->
                    val days = com.optionslab.app.data.DailyPnl.all(live).mapValues { it.value.pnl to it.value.trades }
                    if (!live || days.isNotEmpty()) AppFacts.history(if (live) "Zerodha" else "Paper", days, today).take(1) else emptyList()
                }
                out[Section.PNL] = listOf("No trading today: the market is closed.") + last + out[Section.PNL].orEmpty()
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
                    IraHub.closedToday(today)?.substringBefore(" Prices"),
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
            if (wants(Section.STUDY)) out[Section.STUDY] = IraStudy.lines()
            if (wants(Section.ACTIVITY)) out[Section.ACTIVITY] = IraActivity.lines()
            if (wants(Section.REGIME)) out[Section.REGIME] = IraStudy.regimeLines()
            if (wants(Section.LOSSES)) out[Section.LOSSES] = if (com.optionslab.app.BuildConfig.JARVIS) IraNewsTrades.lossReasons() +
                "For your own trades, ask \"review my week\": it shows where they lose." else listOf("Ask \"review my week\": it shows where your trades lose.")
            if (wants(Section.READY)) out[Section.READY] = readyLines(s, today)
            if (wants(Section.WHATIF)) out[Section.WHATIF] = if (com.optionslab.app.BuildConfig.JARVIS) IraNewsTrades.whatIf(question)
                else listOf("Replays of Jarvis's suggestions are in JarvisAlgo.")
            if (wants(Section.CHANGES)) out[Section.CHANGES] = com.optionslab.app.data.SettingsLog.lines()
            if (wants(Section.EXPLAIN_POS)) out[Section.EXPLAIN_POS] = IraCoach.explainPositions()
            if (wants(Section.SEARCH)) out[Section.SEARCH] = IraJournal.search(question)
            if (wants(Section.TIMEOFDAY)) out[Section.TIMEOFDAY] = IraJournal.timeOfDay()
            if (wants(Section.REASONS)) out[Section.REASONS] = IraJournal.reasons()
            if (wants(Section.REVIEW)) {
                val owners = runCatching { com.optionslab.app.data.Strategies.owners() }.getOrDefault(emptyMap())
                val r = ArrayList<String>()
                for (live in listOf(false, true)) {
                    val trips = IraAccount.trips(live, owners)
                    if (live && trips.isEmpty()) continue
                    val label = if (live) "Zerodha" else "Paper"
                    r += com.optionslab.ira.Insights.week(label, trips, today) + com.optionslab.ira.Insights.patterns(label, trips).drop(1)
                }
                if (com.optionslab.app.BuildConfig.JARVIS) r += IraNewsTrades.record()
                r += "Autopilot: ${if (IraHub.autopilot) "on" else "off"}."
                out[Section.REVIEW] = r
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
