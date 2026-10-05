package com.optionslab.app.ira

import com.optionslab.app.data.AppSettings
import com.optionslab.app.data.Broker
import com.optionslab.ira.AppFacts
import com.optionslab.ira.AppView
import com.optionslab.ira.Section
import kotlinx.coroutines.sync.withLock
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
    /** What the results teach (part 5): the closed trades of the app's mode, every owner, read for lessons. */
    suspend fun lessons(): Pair<List<com.optionslab.ira.Lessons.Lesson>, Int> {
        val live = runCatching { com.optionslab.app.data.AppSettings.load().live }.getOrDefault(false)
        val owners = runCatching { com.optionslab.app.data.Strategies.owners() }.getOrDefault(emptyMap())
        val all = trips(live, owners)
        return com.optionslab.ira.Lessons.of(all) to all.size
    }

    fun trips(live: Boolean, owners: Map<String, String>): List<com.optionslab.ira.Insights.Trip> {
        val round = runCatching { com.optionslab.app.data.TradeBook.trips(live) }.getOrDefault(emptyList())
        // Named again only when the round trips or the owners changed (the book gives the same list while its trades do).
        return (if (live) liveNamed else paperNamed).of(round to HashMap(owners)) { (r, o) ->
            r.map { t -> com.optionslab.ira.Insights.Trip(t.symbol, t.openedAt, t.closedAt, t.net, com.optionslab.app.data.TradeBook.ownerOf(t, o)) }
        }
    }

    private val liveNamed = com.optionslab.ira.SameInput<Pair<List<com.optionslab.engine.RoundTrips.Trip>, Map<String, String>>, List<com.optionslab.ira.Insights.Trip>>()
    private val paperNamed = com.optionslab.ira.SameInput<Pair<List<com.optionslab.engine.RoundTrips.Trip>, Map<String, String>>, List<com.optionslab.ira.Insights.Trip>>()

    /** A Zerodha read waits at most this long; a slow one is left out and said so. */
    private const val ZERODHA_MS = 8_000L

    /**
     * Today's reads of each option chain, in memory only (never saved): "how has OI shifted since morning?" compares
     * the newest with the day's first ([com.optionslab.ira.ChainIntel]). Market data only.
     */
    val chainBook = com.optionslab.ira.ChainIntel.Book()

    /**
     * Battery (round 6): each index's chain read at most once in one background pass of Jarvis's (the OI watch and the
     * day's chain record read the same Nifty and BankNifty chains moments apart: without a Zerodha session each is about
     * 50 downloads of a contract's day of candles). A failed read is not kept: the next asker reads again, as before.
     * Market data only; never kept past the pass.
     */
    internal class ChainPass {
        private val got = java.util.concurrent.ConcurrentHashMap<String, com.optionslab.app.data.Market.LiveChain>()
        fun has(u: String): Boolean = got.containsKey(u)
        suspend fun live(u: String): com.optionslab.app.data.Market.LiveChain =
            got[u] ?: com.optionslab.app.data.Market.liveChain(u, near = 12).also { got[u] = it }
    }

    /**
     * The option chain of [u] (nearest expiry, the strikes near the money) as the Options tab prices it, or null. [pass]:
     * the background pass's own reads, shared ([ChainPass]); a question always reads afresh.
     */
    suspend fun chain(u: String, pass: ChainPass? = null): com.optionslab.engine.options.ChainSnapshot? = runCatching {
        val lc = pass?.live(u) ?: com.optionslab.app.data.Market.liveChain(u, near = 12)
        // The minute its data is from (a quote snapshot's minute, else the end of the newest 1-minute bar), for its age.
        val seen: java.time.LocalDateTime? = runCatching {
            val minute = lc.pricedAt ?: lc.series.mapNotNull { it.minutes.lastOrNull() }.maxOrNull()?.plus(1)
            val at = minute?.let { com.optionslab.app.data.Market.today().atStartOfDay().plusMinutes(it.toLong()) }
            IraTools.chainSeen(u, at)
            at
        }.getOrNull()
        val symbols = lc.contracts.associate { (it.strike to it.right) to it.tradingSymbol }
        val rows = com.optionslab.app.data.OiBaseline.apply(com.optionslab.engine.options.ChainSnapshot.rowsFrom(lc.series, symbols, lc.lotSize))
        val snap = com.optionslab.engine.options.ChainSnapshot.of(u, lc.expiry, lc.spot, lc.lotSize, rows, com.optionslab.app.data.Market.now())
        // Kept to compare with later in the day, with the time its data is from (else the minute it was read).
        runCatching {
            val at = seen ?: com.optionslab.app.data.Market.now().toLocalDateTime().withSecond(0).withNano(0)
            chainBook.keep(com.optionslab.ira.ChainIntel.read(snap, at))
        }
        snap
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
        val liveOpen = if (Broker.loggedIn) Broker.within(ZERODHA_MS) { Broker.positionBook().net.filter { it.open }.map { it.symbol } }.orEmpty() else emptyList()
        val unguarded = (paperOpen + liveOpen).count { it !in protectedSymbols }
        val ctx = IraHub.appContext()
        return com.optionslab.ira.LiveReady.check(com.optionslab.ira.LiveReady.Facts(
            proven = if (com.optionslab.app.BuildConfig.JARVIS) com.optionslab.ira.JarvisTrades.proven(IraNewsTrades.closedRecord()) else null,
            zerodhaLoggedIn = Broker.loggedIn, killSwitchOn = s.guardKill, dailyLossLimit = s.guardDailyLoss, maxLots = s.guardMaxLots,
            voiceEnrolled = runCatching { VoiceGuard.enrolled }.getOrDefault(false),
            fingerprint = ctx?.let { com.optionslab.app.security.BiometricGate.fingerprintOn(it) } ?: false,
            marketOpenToday = com.optionslab.app.data.Market.isTradingDay(today), unguardedPositions = unguarded))
    }

    /**
     * Battery (round 6): one question's paper account, read once when a section first needs it and shared by the rest
     * (the orders, positions, P&L and funds, "explain my position", the health check, what needs to happen, the move and
     * the ranking each read it before, and each read downloads every held contract's day of candles without a Zerodha
     * session). [reuseMs] as [com.optionslab.app.data.Paper.snapshot] takes it: 0 = fresh prices. Reads only; a failed
     * read is null (each section then says what it said before when the account did not open). Never kept past the question.
     */
    internal class PaperOnce(private val reuseMs: Long) {
        private val lock = kotlinx.coroutines.sync.Mutex()
        private var done = false
        private var view: com.optionslab.app.data.Paper.Snapshot? = null

        suspend fun get(): com.optionslab.app.data.Paper.Snapshot? = lock.withLock {
            if (!done) { view = runCatching { com.optionslab.app.data.Paper.snapshot(reuseMs) }.getOrNull(); done = true }
            view
        }
    }

    /** Sections whose answer prices the paper account fresh (the rest may share a price read in the last 20 s). */
    private val PRICED_FRESH = setOf(Section.ORDERS, Section.POSITIONS, Section.PNL, Section.FUNDS, Section.EXPLAIN_POS)

    /** Sections answered from the question's own words: never from the cache. */
    private val ASKED = setOf(Section.WHATIF, Section.CHANGES, Section.SEARCH, Section.TIMEOFDAY, Section.REASONS, Section.EXPLAIN_POS, Section.MISTAKES, Section.MOVE, Section.RANK, Section.REPLAY, Section.MONTH, Section.CHARGES, Section.HEALTH, Section.BOTS, Section.TAX, Section.NEED, Section.STREAKS, Section.NUMBERS)

    suspend fun read(sections: Set<Section>, markets: List<com.optionslab.ira.Market> = emptyList(), question: String = ""): AppView? {
        testView?.let { return it(sections) }
        return runCatching {
            val s = AppSettings.load()
            val mode = if (s.live) "Live" else "Paper"
            val out = LinkedHashMap<Section, List<String>>()
            val today = com.optionslab.app.data.Market.today()
            val wants = { x: Section -> x in sections }
            val zerodha = Broker.loggedIn && sections.any { it in setOf(Section.ORDERS, Section.POSITIONS, Section.PNL, Section.FUNDS) }
            // One paper snapshot for the whole question (fresh when a section prices the account as it is now).
            val paperOnce = PaperOnce(if (sections.any { it in PRICED_FRESH }) 0L else com.optionslab.app.data.Paper.SHARED_QUOTE_MS)

            if (sections.any { it in setOf(Section.ORDERS, Section.POSITIONS, Section.PNL, Section.FUNDS) }) {
                val snap = paperOnce.get()
                val owners = runCatching { com.optionslab.app.data.Strategies.owners() }.getOrDefault(emptyMap())
                val orders = ArrayList<String>(); val pos = ArrayList<String>(); val pnl = ArrayList<String>(); val funds = ArrayList<String>()
                if (snap != null) {
                    orders += AppFacts.orders("Paper", snap.orders.orders.filter { it.timestamp.startsWith(today.toString()) }.sortedBy { it.timestamp }.map {
                        AppFacts.OrderLine(it.timestamp.drop(11).take(5), it.symbol, it.action, it.quantity, it.status, it.averagePrice,
                            owners["paper:${it.orderId}"] ?: it.strategy.takeIf { x -> x.isNotBlank() }, it.rejectionReason.takeIf { r -> r.isNotBlank() }, it.orderId)
                    }, byWho = true)
                    pos += AppFacts.positions("Paper", snap.positions.positions.filter { it.quantity != 0 }.map {
                        AppFacts.Held(it.symbol, it.quantity, it.averagePrice, it.ltp, it.pnl) })
                    pnl += AppFacts.pnl("Paper", snap.dayPnl, snap.positions.totalTodayRealizedPnl, snap.positions.totalUnrealizedPnl)
                    funds += "Paper funds: ${AppFacts.amt(snap.funds.availableCash)} available, ${AppFacts.amt(snap.funds.utilisedDebits)} in use."
                } else { orders += "The paper account did not open just now."; pos += orders.last(); pnl += orders.last() }
                if (zerodha) {
                    val z = Broker.within(ZERODHA_MS) {
                        Triple(Broker.orders(), Broker.positionBook(), runCatching { Broker.funds() }.getOrNull())
                    }
                    if (z == null) orders += "Zerodha did not answer just now, so its orders are not included."
                    else {
                        val (zo, zp, zf) = z
                        orders += AppFacts.orders("Zerodha", zo.filter { it.placedAt.startsWith(today.toString()) || it.placedAt.length < 10 }.sortedBy { it.placedAt }.map {
                            AppFacts.OrderLine(it.placedAt.drop(11).take(5).ifBlank { it.placedAt.take(5) }, it.symbol, it.side, it.qty, it.status, it.avg,
                                // Zerodha orders are kept under "kite:<id>" (the bare id never matched: the raw tag showed).
                                owners["kite:${it.id}"] ?: com.optionslab.app.data.Origins.fromTag(it.tag.takeIf { t -> t.isNotBlank() }) ?: it.tag.takeIf { t -> t.isNotBlank() },
                                it.message.takeIf { m -> m.isNotBlank() }, it.id)
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
                else listOf("Replays of Jarvis's suggestions are in IraAlgo.")
            if (wants(Section.CHANGES)) out[Section.CHANGES] = com.optionslab.app.data.SettingsLog.lines()
            if (wants(Section.EXPLAIN_POS)) out[Section.EXPLAIN_POS] = IraCoach.explainPositions(paperOnce)
            if (wants(Section.MOVE)) out[Section.MOVE] = IraCoach.moveLines(question, paperOnce)
            if (wants(Section.RANK)) out[Section.RANK] = IraCoach.rankLines(paperOnce)
            // "Check my positions": each open position's expiry, decay, distance from the strike, spread and stops (read only).
            if (wants(Section.HEALTH)) out[Section.HEALTH] = IraCoach.healthLines(paperOnce)
            // "For my 24500 put to work, what needs to happen?": breakeven, distance, time, typical move, decay (read only).
            if (wants(Section.NEED)) out[Section.NEED] = IraCoach.needLines(question, paperOnce)
            // "Am I on a winning streak?", "what's my best weekday?": Boss's own runs of days and trades (read only).
            if (wants(Section.STREAKS)) {
                val owners = runCatching { com.optionslab.app.data.Strategies.owners() }.getOrDefault(emptyMap())
                val open = runCatching { com.optionslab.app.data.Market.isOpen() }.getOrDefault(false)
                val first = com.optionslab.ira.MyStreaks.weekdayAsked(question)
                val r = ArrayList<String>()
                for (live in listOf(true, false)) {
                    val all = trips(live, owners)
                    if (live && all.isEmpty()) continue
                    r += com.optionslab.ira.MyStreaks.lines(if (live) "Zerodha" else "Paper", all, today, open, first)
                }
                r += com.optionslab.ira.MyStreaks.CLOSING
                out[Section.STREAKS] = r
            }
            // "What's my average win and loss?", "my profit factor", "do I hold my losers longer?": Boss's own numbers (read only).
            if (wants(Section.NUMBERS)) {
                val owners = runCatching { com.optionslab.app.data.Strategies.owners() }.getOrDefault(emptyMap())
                val span = com.optionslab.ira.MyNumbers.span(question)
                val r = ArrayList<String>()
                for (live in listOf(true, false)) {
                    val all = trips(live, owners)
                    if (live && all.isEmpty()) continue
                    r += com.optionslab.ira.MyNumbers.lines(if (live) "Zerodha" else "Paper", all, span, today)
                }
                r += com.optionslab.ira.MyNumbers.CLOSING
                out[Section.NUMBERS] = r
            }
            // "How are my bots doing?": each strategy today and this week against its tested record (read only).
            if (wants(Section.BOTS)) out[Section.BOTS] = IraBots.lines(question)
            // "How was my last trade?": the trades against their own candles (read only).
            if (wants(Section.REPLAY)) out[Section.REPLAY] = IraJournal.replay(question)
            // "How was my month?": Boss's own trades (not the bots') this month (or last) against the month before.
            if (wants(Section.MONTH)) {
                val owners = runCatching { com.optionslab.app.data.Strategies.owners() }.getOrDefault(emptyMap())
                val month = com.optionslab.ira.MonthReview.month(question, today)
                val notes = IraJournal.notes()
                val r = ArrayList<String>()
                for (live in listOf(false, true)) {
                    val own = trips(live, owners).filter { it.owner.startsWith("Manual") }
                    if (live && own.isEmpty()) continue
                    r += com.optionslab.ira.MonthReview.lines(if (live) "Zerodha" else "Paper", own, month, today, notes)
                }
                out[Section.MONTH] = r
            }
            // "How much did I pay in charges this week?": the closed trades' charges (every owner) - the app's own sums.
            if (wants(Section.CHARGES)) {
                val owners = runCatching { com.optionslab.app.data.Strategies.owners() }.getOrDefault(emptyMap())
                val span = com.optionslab.ira.Charges.span(question)
                val month = com.optionslab.ira.Charges.month(span, today)
                val r = ArrayList<String>()
                for (live in listOf(false, true)) {
                    val trips = runCatching { com.optionslab.app.data.TradeBook.trips(live) }.getOrDefault(emptyList()).map { t ->
                        com.optionslab.ira.Charges.Trip(t.openedAt, t.closedAt, t.gross, t.charges, com.optionslab.app.data.TradeBook.ownerOf(t, owners))
                    }
                    if (live && trips.isEmpty()) continue
                    val byCharge = if (month == null) emptyMap() else runCatching { com.optionslab.app.data.TradeBook.charges(live, month) }.getOrDefault(emptyMap())
                    r += com.optionslab.ira.Charges.lines(if (live) "Zerodha" else "Paper", trips, span, today, byCharge, estimated = live)
                }
                out[Section.CHARGES] = r
            }
            // "What's my F&O turnover this year?": the financial year's facts from the trade books (not tax advice).
            if (wants(Section.TAX)) out[Section.TAX] = IraTax.lines(question, today)
            if (wants(Section.SEARCH)) out[Section.SEARCH] = IraJournal.search(question)
            if (wants(Section.TIMEOFDAY)) out[Section.TIMEOFDAY] = IraJournal.timeOfDay()
            if (wants(Section.REASONS)) out[Section.REASONS] = IraJournal.reasons()
            if (wants(Section.MISTAKES)) out[Section.MISTAKES] = com.optionslab.ira.Mistakes.lines(IraTools.mistakes()) +
                com.optionslab.ira.Corrections.lines(IraTools.learned())
            if (wants(Section.REVIEW)) {
                val owners = runCatching { com.optionslab.app.data.Strategies.owners() }.getOrDefault(emptyMap())
                val r = ArrayList<String>()
                for (live in listOf(false, true)) {
                    val trips = IraAccount.trips(live, owners)
                    if (live && trips.isEmpty()) continue
                    val label = if (live) "Zerodha" else "Paper"
                    r += com.optionslab.ira.Insights.week(label, trips, today) + com.optionslab.ira.Insights.patterns(label, trips).drop(1)
                    // Boss's own trades (not the bots') against last week, and the habits that cost money.
                    trips.filter { it.owner.startsWith("Manual") }.takeIf { it.isNotEmpty() }?.let { own ->
                        r += com.optionslab.ira.WeekReview.lines(label, own, today) }
                }
                if (com.optionslab.app.BuildConfig.JARVIS) r += IraNewsTrades.record()
                r += "Autopilot: ${if (IraHub.autopilot) "on" else "off"}."
                out[Section.REVIEW] = r
            }
            if (wants(Section.FLOWS)) out[Section.FLOWS] = com.optionslab.ira.Flows.lines(withTimeoutOrNull(20_000) { IraHub.flows() } ?: emptyList())
            if (wants(Section.CHAIN)) {
                val us = markets.mapNotNull { mk -> when (mk) { com.optionslab.ira.Market.NIFTY -> "NIFTY"; com.optionslab.ira.Market.BANKNIFTY -> "BANKNIFTY"
                    com.optionslab.ira.Market.FINNIFTY -> "FINNIFTY"; else -> null } }.ifEmpty { listOf("NIFTY", "BANKNIFTY") }
                out[Section.CHAIN] = us.flatMap { u -> withTimeoutOrNull(25_000) { chain(u) }?.let { listOfNotNull(IraTools.chainNote(u)) + com.optionslab.ira.ChainRead.lines(it) }
                    ?: listOf("The $u option chain did not load just now.") } +
                    (if (markets.any { it == com.optionslab.ira.Market.SENSEX || it == com.optionslab.ira.Market.GOLD }) listOf("Option chains are read for Nifty, BankNifty and FinNifty.") else emptyList())
            }
            if (wants(Section.EVENTS)) out[Section.EVENTS] = IraEvents.upcoming().map { com.optionslab.ira.Events.line(it, today) }
                .ifEmpty { listOf("No events in the next two weeks.") }
            AppView(mode, out)
        }.getOrNull()
    }
}
