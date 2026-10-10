package com.optionslab.app.ira

import com.optionslab.ira.BotHealth
import java.time.LocalDateTime

/**
 * The health of Boss's own running strategies ([BotHealth]): each ORB arm, Pine auto-trade script, strategy and Solo,
 * read from the app's own books (paper and Zerodha round trips, by the order that opened them) with its switch, its
 * last signal, its own daily loss limit and its tested record - the arms' backtest the nightly study keeps, a Pine
 * script's Strategy Lab backtest, else its own record before this week. Reads only. Unasked, in market hours, a strategy
 * behaving unusually is told once a day without amounts, and stopping it is offered (asked first, unless Boss chose
 * automatic stops in chat; only where stopping sells nothing on Zerodha) - never armed, started or added to.
 */
internal object IraBots {
    private const val TESTED_KEY = "jarvis.bots.tested"
    private const val TESTED_DAYS_KEY = "jarvis.bots.testedDays"
    private const val TOLD_KEY = "jarvis.bots.told"
    private const val SOLO = "Solo"

    /** The nightly study's arms backtest, kept per arm (counts and rupees of one lot only; no symbols). */
    fun saveTested(r: com.optionslab.engine.orb.ArmsBacktest.Result) = runCatching {
        val labels = com.optionslab.engine.orb.ArmsBacktest.ARMS.associate { it.source to it.label }
        val o = org.json.JSONObject()
        r.trades.groupBy { it.arm }.forEach { (src, ts) ->
            val label = labels[src] ?: return@forEach
            val t = BotHealth.tested("its backtest over ${r.days} BankNifty days (1 lot)", r.days, ts.sortedBy { it.day }.map { it.net }) ?: return@forEach
            o.put(label, org.json.JSONObject().put("source", t.source).put("trades", t.trades).put("perDay", t.perDay ?: 0.0)
                .put("winRate", t.winRate ?: 0.0).put("worst", t.worstStreak ?: 0).put("dd", t.maxDrawdown ?: 0.0))
        }
        com.optionslab.app.security.SecurePrefs.put(TESTED_KEY, o.toString())
        // And each arm's rupees by the day (one lot; dates and rupees only), for "which of my arms suits today?".
        val byDay = org.json.JSONObject()
        r.trades.groupBy { it.arm }.forEach { (src, ts) ->
            val label = labels[src] ?: return@forEach
            val d = org.json.JSONObject()
            com.optionslab.ira.ArmFit.daily(ts.map { it.day to it.net }).forEach { (day, net) -> d.put(day.toString(), net) }
            byDay.put(label, d)
        }
        com.optionslab.app.security.SecurePrefs.put(TESTED_DAYS_KEY, byDay.toString())
    }

    /** Each arm's backtest rupees by the day, by its label (empty until the nightly study has kept them). */
    private fun armTestedDays(): Map<String, Map<java.time.LocalDate, Double>> = runCatching {
        val o = org.json.JSONObject(com.optionslab.app.security.SecurePrefs.getString(TESTED_DAYS_KEY) ?: "{}")
        o.keys().asSequence().associateWith { k ->
            val d = o.getJSONObject(k)
            d.keys().asSequence().mapNotNull { day -> runCatching { java.time.LocalDate.parse(day) to d.getDouble(day) }.getOrNull() }.toMap()
        }
    }.getOrDefault(emptyMap())

    private fun armTested(): Map<String, BotHealth.Tested> = runCatching {
        val o = org.json.JSONObject(com.optionslab.app.security.SecurePrefs.getString(TESTED_KEY) ?: "{}")
        o.keys().asSequence().mapNotNull { k -> runCatching {
            val x = o.getJSONObject(k)
            k to BotHealth.Tested(x.getString("source"), x.getInt("trades"), x.getDouble("perDay"), "a day in its backtest",
                x.getDouble("winRate"), x.getInt("worst"), x.getDouble("dd"))
        }.getOrNull() }.toMap()
    }.getOrDefault(emptyMap())

    /** Who placed a round trip, with a Pine script's own name ("Pine · <name> · entry"); "Jarvis solo" is Solo. */
    private fun botOf(trip: com.optionslab.engine.RoundTrips.Trip, owners: Map<String, String>): String {
        val raw = trip.openOrderIds.firstNotNullOfOrNull { owners[it] } ?: owners[trip.closeOrderId]?.takeIf { !it.startsWith("Protection") }
        if (raw != null && raw.startsWith("Pine · ")) raw.split(" · ").takeIf { it.size >= 3 }?.let { return it[1].trim() }
        val o = com.optionslab.app.data.TradeBook.ownerOf(trip, owners)
        return if (o.equals("Jarvis solo", ignoreCase = true)) SOLO else o
    }

    /** One configured strategy: its kind, switch, own daily loss limit, last signal, holding, tested record and fixed book. */
    private data class Conf(val kind: String, val on: Boolean, val limit: Double? = null, val signal: LocalDateTime? = null,
                            val holding: Boolean = false, val tested: BotHealth.Tested? = null, val paperOnly: Boolean = false)

    /** Every strategy the app has, by name. Reads only. */
    private suspend fun confs(): Map<String, Conf> {
        val out = LinkedHashMap<String, Conf>()
        runCatching { com.optionslab.app.data.Strategies.all() }.getOrDefault(emptyList()).forEach { e ->
            out[e.def.name] = Conf("strategy", e.def.scheduler?.enabled == true || e.running)
        }
        // A Pine script Jarvis wrote and Boss approved carries its Strategy Lab backtest.
        val lab = runCatching { IraHub.state.value.proposals.filter { it.status == IraHub.Proposal.APPROVED && it.pineId != null && it.result.error == null } }
            .getOrDefault(emptyList()).associateBy { it.pineId }
        val held = runCatching { com.optionslab.app.data.PineAuto.held.value }.getOrDefault(emptyMap())
        runCatching { com.optionslab.app.data.PineScripts.items.value }.getOrDefault(emptyList()).forEach { x ->
            val r = lab[x.id]?.result
            val tested = r?.takeIf { it.days > 0 && it.trades > 0 }?.let {
                BotHealth.Tested("its Strategy Lab backtest over ${it.days} ${it.market.label} days", it.trades, it.trades.toDouble() / it.days,
                    "a day in its backtest", it.winRate / 100.0, null, null)
            }
            out[x.name] = Conf("Pine script", x.auto.on, x.auto.maxDayLoss.takeIf { it > 0 }, holding = held[x.id] != null, tested = tested)
        }
        val tested = armTested()
        runCatching { com.optionslab.app.data.OrbArms.view().arms }.getOrDefault(emptyList()).forEach { a ->
            val signal = (a.today.map { it.signalBar } + listOfNotNull(a.pending?.signalBar)).maxOrNull()
            if (a.arm.source == com.optionslab.engine.orb.LiquidityRules.ARM.source) {
                // Liquidity 15+5 per index (research X1): BANKNIFTY's health apart from FINNIFTY's and MIDCPNIFTY's, each
                // against its own record (the arm's backtest is all of them together).
                for (idx in com.optionslab.ira.LiquiditySplit.INDICES) {
                    val mine = a.today.filter { com.optionslab.ira.LiquiditySplit.indexOf(it.symbol) == idx }
                    out[com.optionslab.ira.LiquiditySplit.name(idx)] = Conf("ORB arm", a.armed, signal = mine.map { it.signalBar }.maxOrNull(),
                        holding = mine.any { it.open })
                }
            } else out[a.arm.label] = Conf("ORB arm", a.armed, signal = signal, holding = a.open != null, tested = tested[a.arm.label])
        }
        out[SOLO] = Conf("Solo", runCatching { IraSolo.on }.getOrDefault(false), paperOnly = true)
        return out
    }

    /** Strategies and Pine scripts with something live running or held (null: unknown, so none is offered a stop). */
    private suspend fun liveNames(): Set<String>? = runCatching {
        val runs = com.optionslab.app.data.Strategies.all()
            .filter { it.run?.mode == com.optionslab.engine.strategy.RunMode.LIVE }.map { it.def.name }
        val held = com.optionslab.app.data.PineAuto.held.value
        val pine = com.optionslab.app.data.PineScripts.items.value.filter { held[it.id]?.live == true }.map { it.name }
        (runs + pine).toSet()
    }.getOrNull()

    /** Every strategy with its trades (paper, and Zerodha when it traded there). Reads only. */
    suspend fun bots(): List<BotHealth.Bot> {
        val confs = confs()
        val live = runCatching { com.optionslab.app.data.AppSettings.load().live }.getOrDefault(false)
        val owners = runCatching { com.optionslab.app.data.Strategies.owners() }.getOrDefault(emptyMap())
        val out = ArrayList<BotHealth.Bot>()
        for (book in listOf(false, true)) {
            val where = if (book) "Zerodha" else "Paper"
            val byBot = runCatching { com.optionslab.app.data.TradeBook.trips(book) }.getOrDefault(emptyList())
                .groupBy { com.optionslab.ira.LiquiditySplit.owner(botOf(it, owners), it.symbol) }.filterKeys { it in confs }
            for ((name, c) in confs) {
                val trips = byBot[name].orEmpty()
                // On in the book it trades in now (Solo only ever on paper); shown in the other only when it traded there.
                val current = if (c.paperOnly) !book else book == live
                if (trips.isEmpty() && !current) continue
                out += BotHealth.Bot(name, c.kind, where, c.on && current, trips.map { BotHealth.Trade(it.openedAt, it.closedAt, it.net) },
                    tested = c.tested, dayLossLimit = c.limit, lastSignal = c.signal, holding = c.holding && current)
            }
        }
        return out
    }

    /** "How are my bots doing?", "is the ORB arm behaving?", "which strategy is losing?". Reads only. */
    suspend fun lines(question: String): List<String> {
        // ORB, ORB Fresh, ORB Sweep or Range Fade named: said first that Boss un-retired it on 07 Oct (on paper), with its
        // 2021-2026 record as information ([com.optionslab.engine.orb.RetiredArms.history]).
        val record = com.optionslab.engine.orb.RetiredArms.named(question).map { com.optionslab.engine.orb.RetiredArms.history(it) }
        // "How are the shadows doing?", "how are the retired arms doing?": the shadow tracker's record (no orders), on its own.
        if (SHADOWS.containsMatchIn(question)) return record + listOf(com.optionslab.app.data.ShadowArms.answer())
        val health = BotHealth.lines(bots(), com.optionslab.app.data.Market.now().toLocalDateTime(), question,
            runCatching { com.optionslab.app.data.LossBreaker.trippedToday() }.getOrDefault(false))
        // Liquidity 15+5 named: its pre-registered candidates (a), (b) and the volatility filter (c), each judged at 40 paper trades.
        val shadow = if (!question.contains("liquidity", ignoreCase = true)) emptyList()
            else listOfNotNull(runCatching { com.optionslab.engine.orb.LiquidityShadow.verdict(com.optionslab.app.data.OrbArms.liquidityShadow()) }.getOrNull())
        return record + health + shadow + liveVsBacktest(question)
    }

    /**
     * Live vs backtest ([com.optionslab.ira.ForwardCheck], the P&L tab's card): one line for the strategy named (Liquidity,
     * Solo, Hero), else one for each running one ("Liquidity 15+5 - Live vs backtest: ..."). Reads only.
     */
    private suspend fun liveVsBacktest(question: String): List<String> = runCatching {
        val rows = com.optionslab.app.data.ForwardRecords.rows().filter { !it.shadow }
        val named = rows.filter { r -> Regex("\\b${r.result.expectation.key}\\b", RegexOption.IGNORE_CASE).containsMatchIn(question) }
        if (named.size == 1) listOf(com.optionslab.ira.ForwardCheck.line(named.single().result))
        else named.ifEmpty { rows }.map { "${it.title} - ${com.optionslab.ira.ForwardCheck.line(it.result)}" }
    }.getOrDefault(emptyList())

    /** The shadows, or "the retired arms" (their research shadows), named ([lines]). */
    private val SHADOWS = Regex("\\b(shadows?|retired)\\b", RegexOption.IGNORE_CASE)

    /** An arm position as [com.optionslab.ira.BotTrades] reads it (also [IraTradeLessons]'s, so both say the same lesson). */
    internal fun tradeOf(p: com.optionslab.app.data.OrbArms.Position): com.optionslab.ira.BotTrades.Trade =
        com.optionslab.ira.BotTrades.Trade(p.arm, p.symbol, p.right, p.qty, p.entry, p.entryTime, p.signalBar, p.exit, p.exitTime, p.why,
            p.charges, p.live, p.level, p.target, p.ladder, p.peak, trough = p.low, lot = p.lot)

    /**
     * "Explain my bots' trades today" ([com.optionslab.ira.BotTrades]): today's arm trades from the arms' own book, their
     * switches and the day's opening range, with BankNifty's 1-minute candles ([bankNifty]) for the signal bars. Reads only.
     */
    suspend fun tradesToday(q: com.optionslab.ira.BotTrades.Q, bankNifty: List<com.optionslab.ira.Candle>): String {
        val v = com.optionslab.app.data.OrbArms.view()
        val trades = v.arms.flatMap { it.today }.map { tradeOf(it) }
        val switches = v.arms.map { com.optionslab.ira.BotTrades.Switch(it.arm.label, it.armed) }
        return com.optionslab.ira.BotTrades.answer(q, trades, switches, v.range, bankNifty, com.optionslab.app.data.Market.now().toLocalDateTime())
    }

    /**
     * "How did liquidity do this week", "liquidity last 10 trades", "is liquidity on track" ([com.optionslab.ira.LiquidityRecord]):
     * Liquidity 15+5's record over time from the arms' own book of closed paper trades ([com.optionslab.app.data.OrbArms.closedPaper],
     * every day it keeps). Reads only.
     */
    suspend fun liquidityRecord(q: com.optionslab.ira.LiquidityRecord.Q): String {
        val rows = com.optionslab.ira.LiquidityRecord.rows(com.optionslab.app.data.OrbArms.closedPaper().map { tradeOf(it) })
        return com.optionslab.ira.LiquidityRecord.answer(q, rows, com.optionslab.app.data.Market.today())
    }

    /**
     * "What should I switch off?" ([com.optionslab.ira.SwitchOff]): each arm's switch and two-year test beside its closed paper
     * trades. Reads only. With the answer, the first armed arm that lost in both records (null: none), for [offerSwitchOff].
     */
    suspend fun switchOff(q: com.optionslab.ira.SwitchOff.Q): Pair<com.optionslab.ira.SwitchOff.Answer, com.optionslab.ira.SwitchOff.Arm?> {
        val armed = com.optionslab.app.data.OrbArms.view().arms.associate { it.arm.label to it.armed }
        val paper = bots().filter { it.where == "Paper" && it.name in armed }.associate { b -> b.name to b.trades.sortedBy { it.closedAt }.map { it.net } }
        val arms = armed.map { (name, on) -> com.optionslab.ira.SwitchOff.arm(name, on, paper[name].orEmpty()) }
        val a0 = com.optionslab.ira.SwitchOff.answer(q, arms)
        // Asked about ORB, ORB Fresh, ORB Sweep or Range Fade: said first that Boss un-retired it on 07 Oct, with its record.
        val record = com.optionslab.engine.orb.RetiredArms.named(q.arm.orEmpty()).joinToString(" ") { com.optionslab.engine.orb.RetiredArms.history(it) }
        val a = if (record.isEmpty()) a0 else a0.copy(text = "$record ${a0.text}")
        return a to a.offer.firstOrNull()?.let { n -> arms.firstOrNull { it.name == n } }
    }

    /**
     * The arms' paper week ([com.optionslab.ira.ArmWeek]), for Sunday evening: each arm's switch and its closed paper trades
     * (all of them: its paper test counts them all). Reads only; nothing is armed, stopped, placed or closed.
     */
    suspend fun armWeek(today: java.time.LocalDate): com.optionslab.ira.ArmWeek.Said? {
        val armed = com.optionslab.app.data.OrbArms.view().arms.associate { it.arm.label to it.armed }
        val paper = bots().filter { it.where == "Paper" && it.name in armed }
            .associate { b -> b.name to b.trades.map { com.optionslab.ira.ArmWeek.Trade(it.closedAt.toLocalDate(), it.net) } }
        return com.optionslab.ira.ArmWeek.say(armed.map { (name, on) -> com.optionslab.ira.ArmWeek.Arm(name, on, paper[name].orEmpty()) }, today)
    }

    /**
     * One armed arm that lost in both records, put to Boss as a yes or no - always asked, even with automatic stops on; only
     * when the arm found is exactly this one (an ORB arm's stop sells nothing on Zerodha: its open position is managed to its exit).
     */
    suspend fun offerSwitchOff(arm: com.optionslab.ira.SwitchOff.Arm) {
        if (!arm.armed) return
        val cmd = com.optionslab.ira.Command(com.optionslab.ira.Command.Kind.STOP_ONE, target = arm.name)
        val (what, act) = runCatching { IraActions.prepare(cmd) }.getOrNull() ?: (null to null)
        val exact = what != null && what.equals(com.optionslab.ira.Commands.describe(cmd, arm.name), ignoreCase = true)
        if (what != null && act != null && exact)
            IraHub.offer(what, "Boss, switch off ${arm.name}?", com.optionslab.ira.SwitchOff.ask(arm), act, alwaysAsk = true)
    }

    /**
     * "Why did my strategy lose today?" ([com.optionslab.ira.ArmDay]): today's arm trades from the arms' own book set beside
     * the index each traded - the day's gap, opening range and its breaks, and the index through each hold and after the
     * exit - from the 1-minute candles by underlying ([bars]). Reads only; nothing is armed, stopped, placed or closed.
     */
    suspend fun armDay(q: com.optionslab.ira.ArmDay.Q, bars: Map<String, List<com.optionslab.ira.Candle>>): String {
        val v = com.optionslab.app.data.OrbArms.view()
        val trades = v.arms.flatMap { it.today }.map { tradeOf(it) }
        return com.optionslab.ira.ArmDay.answer(q, trades, bars, v.range, com.optionslab.app.data.Market.now().toLocalDateTime())
    }

    /**
     * "Which of my arms suits today?" ([com.optionslab.ira.ArmFit]): each arm's switch, its backtest by the day and its closed
     * paper trades by the day, set beside today's BankNifty start ([bankNifty], India VIX's [vix]). Reads only; nothing is
     * armed, stopped, placed or closed.
     */
    suspend fun armFit(bankNifty: List<com.optionslab.ira.Candle>, vix: List<com.optionslab.ira.Candle>): String {
        val armed = com.optionslab.app.data.OrbArms.view().arms.associate { it.arm.label to it.armed }
        val paper = bots().filter { it.where == "Paper" && it.name in armed }
            .associate { b -> b.name to com.optionslab.ira.ArmFit.daily(b.trades.map { it.openedAt.toLocalDate() to it.net }) }
        val tested = armTestedDays()
        val arms = armed.map { (name, on) -> com.optionslab.ira.ArmFit.Arm(name, on, tested[name].orEmpty(), paper[name].orEmpty()) }
        val mk = com.optionslab.app.data.Market
        return com.optionslab.ira.ArmFit.answer(arms, bankNifty, vix, mk.today(), mk.now().toLocalDateTime())
    }

    /**
     * "What's the weakest link in my setup?" ([com.optionslab.ira.WeakLink]): the arms' closed paper trades from their own book
     * (the exit reason as booked, entry and exit times, rupees after charges), beside BankNifty's 1-minute candles
     * ([bankNifty]) for each day's opening gap. Reads only; nothing is armed, stopped, placed or closed.
     */
    suspend fun weakLink(bankNifty: List<com.optionslab.ira.Candle>): String {
        val closed = com.optionslab.app.data.OrbArms.closedPaper().mapNotNull { p ->
            val out = p.exitTime ?: return@mapNotNull null
            val gross = p.grossPnl ?: return@mapNotNull null
            com.optionslab.ira.WeakLink.Trade(com.optionslab.ira.BotTrades.label(p.arm), p.entryTime, out, p.why, gross - p.charges)
        }
        return com.optionslab.ira.WeakLink.answer(closed, bankNifty)
    }

    /**
     * "Why is my P&L different from what I expected?" ([com.optionslab.ira.PnlGap]): today's paper P&L taken apart - each leg's
     * booked and open part (as the positions screen shows them), the charges of today's trade legs, and each filled order's
     * intended level (a limit's price, a stop's trigger; none for a market order) against its fill. Reads only; nothing is
     * placed, changed or closed.
     */
    suspend fun pnlGap(): String {
        val snap = com.optionslab.app.data.Paper.snapshot(com.optionslab.app.data.Paper.SHARED_QUOTE_MS)
        val legs = snap.positions.positions.map { r -> com.optionslab.ira.PnlGap.Leg(r.symbol, r.todayRealizedPnl, r.totalPnlToday - r.todayRealizedPnl) }
        val fills = snap.orders.orders.filter { it.status.lowercase() == "complete" && it.filledQuantity > 0 }.map { o ->
            val type = o.priceType.uppercase()
            val intended = when (type) { "LIMIT" -> o.price; "SL", "SL-M" -> o.triggerPrice; else -> 0.0 }
            com.optionslab.ira.PnlGap.Fill(o.symbol, o.action.uppercase() == "BUY", type, intended.takeIf { it > 0 }, o.averagePrice, o.filledQuantity)
        }
        return com.optionslab.ira.PnlGap.answer(com.optionslab.ira.PnlGap.Day(legs, snap.trades.sumOf { it.charges }, snap.trades.size, fills))
    }

    /**
     * "What's changed in my arms' results this week vs last?" ([com.optionslab.ira.ArmChange]): each arm's switch and its closed
     * paper trades (the day each closed, rupees after charges), this week so far against last week. Reads only; nothing is
     * armed, stopped, placed or closed.
     */
    suspend fun armChange(): String {
        val armed = com.optionslab.app.data.OrbArms.view().arms.associate { it.arm.label to it.armed }
        val paper = bots().filter { it.where == "Paper" && it.name in armed }
            .associate { b -> b.name to b.trades.map { com.optionslab.ira.ArmChange.Trade(it.closedAt.toLocalDate(), it.net) } }
        val arms = armed.map { (name, on) -> com.optionslab.ira.ArmChange.Arm(name, on, paper[name].orEmpty()) }
        return com.optionslab.ira.ArmChange.answer(arms, com.optionslab.app.data.Market.today())
    }

    /**
     * "Am I net long or short?" ([com.optionslab.ira.NetLean]): Boss's open legs (Paper, and Zerodha when read) with each leg's
     * delta now, split by owner - the arms' open trades from their own book, other automations' symbols, the rest his own -
     * and each index's price now. Reads only; nothing is placed, changed or closed.
     */
    suspend fun netLean(market: com.optionslab.ira.Market?): String {
        val (legs, zerodha) = IraCoach.openLegsRead()
        val arms = runCatching {
            com.optionslab.app.data.OrbArms.view().arms.flatMap { it.today }.filter { it.open }
                .map { com.optionslab.ira.NetLean.ArmLeg(it.arm, it.symbol, it.qty, it.live) }
        }.getOrDefault(emptyList())
        val armSymbols = arms.map { it.symbol }.toSet()
        val others = runCatching { IraCoach.botSymbols() }.getOrDefault(emptySet()) - armSymbols
        val spots = com.optionslab.ira.Market.values().mapNotNull { m ->
            runCatching { IraHub.state.value.snaps[m]?.price }.getOrNull()?.let { m.name to it }
        }.toMap()
        return com.optionslab.ira.NetLean.answer(com.optionslab.ira.NetLean.Input(legs, arms, others, spots, zerodha, market))
    }

    @Volatile private var lastPass = 0L

    /**
     * Market hours, every five minutes at most: a strategy behaving unusually (far more trades today than its tested day,
     * or a losing run beyond its tested worst) is put in the chat and said once a day - without amounts (a locked phone
     * may be heard) - and stopping it is offered: asked first, unless Boss chose automatic stops. Only offered where
     * stopping sells nothing on Zerodha (a paper strategy, or an ORB arm, whose open position is managed to its exit);
     * Solo is switched only in Jarvis settings. Nothing is armed, started or added.
     */
    suspend fun watch() {
        if (!com.optionslab.app.BuildConfig.JARVIS || com.optionslab.app.BuildConfig.GOLD || !Automations.on(Automations.Auto.BOTS)) return
        val m = com.optionslab.app.data.Market
        val today = m.today()
        if (!m.isTradingDay(today) || !m.isOpen()) return
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastPass < 5 * 60_000L) return
        lastPass = nowMs
        val bots = bots()
        val odd = bots.flatMap { BotHealth.unusual(it, today) }
        if (odd.isEmpty()) return
        val saved = runCatching { com.optionslab.app.security.SecurePrefs.getString(TOLD_KEY) }.getOrNull().orEmpty()
        val told = if (saved.substringBefore('|') == today.toString()) saved.split('|').drop(1).toMutableSet() else mutableSetOf()
        val fresh = odd.filter { "${it.bot}:${it.where}:${it.flag}" !in told }
        if (fresh.isEmpty()) return
        fresh.forEach { told += "${it.bot}:${it.where}:${it.flag}" }
        runCatching { com.optionslab.app.security.SecurePrefs.put(TOLD_KEY, (listOf(today.toString()) + told).joinToString("|")) }
        // The figures go in the chat; what is said and shown unasked has no amount.
        val names = fresh.map { it.bot }.toSet()
        val lines = BotHealth.lines(bots.filter { it.name in names }, m.now().toLocalDateTime())
        IraHub.note(lines.joinToString("\n"), from = Automations.Auto.BOTS); IraActivity.add(fresh.joinToString(" ") { it.text })
        Automations.acted(Automations.Auto.BOTS, "Told a strategy behaving unusually.")
        val paper = runCatching { !com.optionslab.app.data.AppSettings.load().live }.getOrDefault(false)
        val liveHeld = liveNames()
        val said = ArrayList<BotHealth.Unusual>()
        for ((name, us) in fresh.groupBy { it.bot }) {
            val bot = bots.firstOrNull { it.name == name }
            val text = us.joinToString(" ") { it.text }
            // Stopping exits what it holds in its own mode, not the app's: a live run or a live Pine holding left from
            // before a switch to Paper would sell on Zerodha, so those are only told (review, 5 Oct).
            val stoppable = bot != null && bot.on && bot.kind != "Solo" && (bot.kind == "ORB arm" || (paper && liveHeld != null && name !in liveHeld))
            val cmd = com.optionslab.ira.Command(com.optionslab.ira.Command.Kind.STOP_ONE, target = name)
            val (what, act) = if (stoppable) runCatching { IraActions.prepare(cmd) }.getOrNull() ?: (null to null) else (null to null)
            // Only when the name found is exactly this strategy's (never a near match stopped by mistake).
            val exact = what != null && what.equals(com.optionslab.ira.Commands.describe(cmd, name), ignoreCase = true)
            if (what == null || act == null || !exact) said += us
            else IraHub.offer(what, "Boss, $name is behaving unusually", "$text Shall I $what?", act)
        }
        BotHealth.spoken(said)?.let { JarvisVoice.announce(com.optionslab.ira.Overheard.said(it, IraHub.locked()), urgent = true) }
    }

    // ---- switching off a losing arm (Boss's 06 Oct rule, [com.optionslab.ira.ArmCutoff]) ---------------------------

    private const val CUTOFF_KEY = "jarvis.bots.cutoff"
    @Volatile private var lastCutoff = 0L

    /**
     * The arms Boss could be asked about: each ORB-family arm (its own book's closed paper trades, after charges) and each
     * Pine auto-trade script, Strategy Lab arms among them (its closed paper round trips, after charges), with its switch now
     * and how to switch it off - an ORB arm disarmed, a script wound down; an open position is managed to its exit either
     * way, and nothing is ever switched on. Reads only.
     */
    internal suspend fun cutoffArms(): List<Pair<com.optionslab.ira.ArmCutoff.Arm, suspend () -> String>> {
        val out = ArrayList<Pair<com.optionslab.ira.ArmCutoff.Arm, suspend () -> String>>()
        val orb = com.optionslab.app.data.OrbArms.view().arms
        val closed = com.optionslab.app.data.OrbArms.closedPaper()
        val liquidity = com.optionslab.engine.orb.LiquidityRules.BOOKS.map { it.source }.toSet()
        for (a in orb) {
            // Liquidity 15+5, switched back on on paper on 06 Oct: judged only on its trades from then, and only from 40 of them
            // (its six-year record is the deciding evidence, not 15 trades).
            val since = com.optionslab.engine.orb.LiquidityShadow.SINCE
            // ORB, ORB Fresh, ORB Sweep and Range Fade, un-retired on paper by Boss on 07 Oct: judged only on their trades from
            // then (their record from before is what he already weighed), from the same 15 trades as any arm.
            val back = com.optionslab.engine.orb.RetiredArms.of(a.arm.source)?.let { com.optionslab.engine.orb.RetiredArms.UNRETIRED_ON }
            val mine = closed.filter { if (a.arm.liquidity) it.arm in liquidity && !it.day.isBefore(since)
                    else it.arm == a.arm.source && (back == null || !it.day.isBefore(back)) }
                .sortedBy { it.exitTime }.map { (it.grossPnl ?: 0.0) - it.charges }
            val src = a.arm.source; val auto = a.automatic
            val act: suspend () -> String = { com.optionslab.app.data.OrbArms.setArmed(src, false, automatic = auto) }
            out += Pair(com.optionslab.ira.ArmCutoff.Arm(a.arm.label, a.armed, mine,
                if (a.arm.liquidity) com.optionslab.ira.ArmCutoff.LIQUIDITY_MIN_TRADES else com.optionslab.ira.ArmCutoff.MIN_TRADES), act)
        }
        val paper = runCatching { bots() }.getOrDefault(emptyList()).filter { it.where == "Paper" && it.kind == "Pine script" }
            .associate { b -> b.name to b.trades.sortedBy { it.closedAt }.map { it.net } }
        for (x in com.optionslab.app.data.PineScripts.items.value.filter { it.auto.mode != "alert" }) {
            val trades = paper[x.name].orEmpty()
            val arm = com.optionslab.ira.ArmCutoff.Arm(x.name, x.auto.on, trades)
            val id = x.id
            val name = x.name
            val act: suspend () -> String = {
                val r = com.optionslab.app.data.PineAuto.windDown(id, "paper record negative (${trades.size} trades after charges; Boss approved)")
                if (r == "ok") com.optionslab.ira.ArmCutoff.done(arm) else "$name: $r."
            }
            out += Pair(arm, act)
        }
        return out
    }

    /**
     * Market days, every 30 minutes at most: an arm with at least 15 closed paper trades and a net below zero after charges
     * is put to Boss - approve or reject, in the Requests panel - once a day an arm. Done by itself only when Boss chose
     * automatic stops in chat ([IraHub.offer]). Never arms anything again.
     */
    suspend fun cutoffLosers() {
        if (!com.optionslab.app.BuildConfig.JARVIS || com.optionslab.app.BuildConfig.GOLD) return
        val m = com.optionslab.app.data.Market
        val today = m.today()
        if (!m.isTradingDay(today)) return
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastCutoff < 30 * 60_000L) return
        lastCutoff = nowMs
        val due = cutoffArms().filter { com.optionslab.ira.ArmCutoff.due(it.first) }.sortedBy { it.first.paper.sum() }
        if (due.isEmpty()) return
        val saved = runCatching { com.optionslab.app.security.SecurePrefs.getString(CUTOFF_KEY) }.getOrNull().orEmpty()
        val asked = if (saved.substringBefore('|') == today.toString()) saved.split('|').drop(1).toMutableSet() else mutableSetOf()
        val fresh = due.filter { it.first.name !in asked }
        if (fresh.isEmpty()) return
        for ((arm, act) in fresh) {
            asked += arm.name
            IraHub.offer("switch off ${arm.name}", "Boss, switch off ${arm.name}?", com.optionslab.ira.ArmCutoff.ask(arm), act)
        }
        runCatching { com.optionslab.app.security.SecurePrefs.put(CUTOFF_KEY, (listOf(today.toString()) + asked).joinToString("|")) }
    }

    // ---- a shadow that met the bar ([com.optionslab.engine.orb.ShadowRules.promotionDue]) --------------------------

    @Volatile private var lastPromotion = 0L

    /**
     * Market days, every 30 minutes at most: a shadow with 60 closed trades, net above zero after charges and a profit factor
     * of 1.2 or more is put to Boss - ONE request a variant, ever: "re-arm <arm> with <variant> on paper?". Approving re-arms
     * it on paper only ([com.optionslab.app.data.ShadowArms.promote]; never Zerodha); always asked, never done by itself.
     */
    suspend fun shadowPromotions() {
        if (!com.optionslab.app.BuildConfig.JARVIS || com.optionslab.app.BuildConfig.GOLD) return
        val m = com.optionslab.app.data.Market
        if (!m.isTradingDay(m.today())) return
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastPromotion < 30 * 60_000L) return
        lastPromotion = nowMs
        for (row in com.optionslab.app.data.ShadowArms.due()) {
            val v = row.variant
            com.optionslab.app.data.ShadowArms.asked(v.id)
            val id = v.id
            IraHub.offer(com.optionslab.engine.orb.ShadowRules.askTitle(v), "Boss, " + com.optionslab.engine.orb.ShadowRules.askTitle(v),
                com.optionslab.engine.orb.ShadowRules.ask(v, row.summary), { com.optionslab.app.data.ShadowArms.promote(id) }, addsRisk = true, alwaysAsk = true)
        }
    }

    // ---- what Boss does with his bots after losing days ([com.optionslab.ira.ArmHabits]) --------------------------

    /** Each trading day's switches and day signs (bot names, armed, ended down or up - never an amount). */
    private const val SWITCHES_KEY = "jarvis.bots.switches"
    @Volatile private var armCache: com.optionslab.ira.ArmHabits.Log? = null
    @Volatile private var lastNote = 0L

    private fun names(x: org.json.JSONObject, k: String): Set<String> =
        x.optJSONArray(k)?.let { arr -> (0 until arr.length()).map { arr.getString(it) }.toSet() } ?: emptySet()

    fun armLog(): com.optionslab.ira.ArmHabits.Log = armCache ?: runCatching {
        val a = org.json.JSONArray(com.optionslab.app.security.SecurePrefs.getString(SWITCHES_KEY) ?: "[]")
        com.optionslab.ira.ArmHabits.Log((0 until a.length()).map { i -> a.getJSONObject(i).let { x ->
            com.optionslab.ira.ArmHabits.Day(java.time.LocalDate.parse(x.getString("d")), names(x, "k"), names(x, "a"), names(x, "l"), names(x, "w"))
        } })
    }.getOrDefault(com.optionslab.ira.ArmHabits.Log()).also { armCache = it }

    private fun armSave(log: com.optionslab.ira.ArmHabits.Log) {
        armCache = log
        val a = org.json.JSONArray()
        log.days.forEach { d ->
            a.put(org.json.JSONObject().put("d", d.date.toString()).put("k", org.json.JSONArray(d.known.toList()))
                .put("a", org.json.JSONArray(d.armed.toList())).put("l", org.json.JSONArray(d.lost.toList())).put("w", org.json.JSONArray(d.won.toList())))
        }
        com.optionslab.app.security.SecurePrefs.put(SWITCHES_KEY, a.toString())
    }

    /**
     * Market hours, every five minutes at most: each bot's switch (armed at any time today) and whether its closed trades
     * today net down or up - names and signs only, for "do I usually disarm my bots after losses?". Notes only: nothing is
     * armed, disarmed, stopped or offered. Solo (Jarvis's own paper trades) is left out.
     */
    suspend fun noteSwitches() {
        if (!com.optionslab.app.BuildConfig.JARVIS || com.optionslab.app.BuildConfig.GOLD) return
        val m = com.optionslab.app.data.Market
        val today = m.today()
        if (!m.isTradingDay(today) || !m.isOpen()) return
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastNote < 5 * 60_000L) return
        lastNote = nowMs
        val byName = bots().filter { it.kind != "Solo" }.groupBy { it.name }
        if (byName.isEmpty()) return
        val switches = byName.mapValues { (_, bs) -> bs.any { it.on } }
        val todays = byName.mapValues { (_, bs) -> bs.flatMap { b -> b.trades.filter { it.closedAt.toLocalDate() == today } } }
        val lost = todays.filter { it.value.isNotEmpty() && it.value.sumOf { t -> t.net } < 0 }.keys
        val won = todays.filter { it.value.isNotEmpty() && it.value.sumOf { t -> t.net } > 0 }.keys
        val before = armLog()
        val after = com.optionslab.ira.ArmHabits.note(before, today, switches, lost, won)
        if (after !== before) armSave(after)
    }

    /** "Do I usually disarm my bots after losses?" (the phone unlocked: the hub checks). */
    fun armHabitsSay(question: String): String = runCatching { com.optionslab.ira.ArmHabits.say(armLog(), question) }
        .getOrDefault("I could not read your bots' record just now, Boss.")
}
