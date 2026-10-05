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
    }

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
            out[a.arm.label] = Conf("ORB arm", a.armed, signal = signal, holding = a.open != null, tested = tested[a.arm.label])
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
                .groupBy { botOf(it, owners) }.filterKeys { it in confs }
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
    suspend fun lines(question: String): List<String> = BotHealth.lines(bots(), com.optionslab.app.data.Market.now().toLocalDateTime(), question,
        runCatching { com.optionslab.app.data.LossBreaker.trippedToday() }.getOrDefault(false))

    /**
     * "Explain my bots' trades today" ([com.optionslab.ira.BotTrades]): today's arm trades from the arms' own book, their
     * switches and the day's opening range, with BankNifty's 1-minute candles ([bankNifty]) for the signal bars. Reads only.
     */
    suspend fun tradesToday(q: com.optionslab.ira.BotTrades.Q, bankNifty: List<com.optionslab.ira.Candle>): String {
        val v = com.optionslab.app.data.OrbArms.view()
        val trades = v.arms.flatMap { it.today }.map { p ->
            com.optionslab.ira.BotTrades.Trade(p.arm, p.symbol, p.right, p.qty, p.entry, p.entryTime, p.signalBar, p.exit, p.exitTime, p.why,
                p.charges, p.live, p.level, p.target, p.ladder, p.peak)
        }
        val switches = v.arms.map { com.optionslab.ira.BotTrades.Switch(it.arm.label, it.armed) }
        return com.optionslab.ira.BotTrades.answer(q, trades, switches, v.range, bankNifty, com.optionslab.app.data.Market.now().toLocalDateTime())
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
        IraHub.note(lines.joinToString("\n")); IraActivity.add(fresh.joinToString(" ") { it.text })
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
        BotHealth.spoken(said)?.let { JarvisVoice.announce(com.optionslab.ira.Overheard.said(it, IraHub.locked())) }
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
