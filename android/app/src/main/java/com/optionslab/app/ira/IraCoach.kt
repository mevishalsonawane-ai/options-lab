package com.optionslab.app.ira

import com.optionslab.app.data.AppSettings
import com.optionslab.app.data.Broker
import com.optionslab.app.data.Paper
import com.optionslab.ira.AppFacts
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Jarvis watching over the owner's own trading (Jarvis, the owner's wishes 2026-10-02): the automatic trailing
 * stop, too many trades too fast, losses outgrowing wins, the opening-gap plan, open interest walls moving, "explain my
 * position" and the 15:35 wrap-up. The bots (ORB, Pine, strategy runs) and Jarvis's own trades manage their own exits.
 */
internal object IraCoach {
    private val IST = ZoneId.of("Asia/Kolkata")

    /** The automatic trailing stop (on by default: the owner asked for it to be automatic). */
    var autoTrail: Boolean
        get() = Automations.on(Automations.Auto.TRAIL)
        set(v) = Automations.set(Automations.Auto.TRAIL, v)

    /** Positions whose exits belong to a bot or to Jarvis's own trades. */
    suspend fun botSymbols(): Set<String> {
        val bots = HashSet<String>()
        runCatching { com.optionslab.app.data.OrbArms.view().arms.mapNotNull { it.open?.symbol }.forEach { bots += it } }
        runCatching { com.optionslab.app.data.PineAuto.held.value.values.forEach { bots += it.symbol } }
        runCatching { com.optionslab.app.data.Strategies.all().mapNotNull { it.run }.forEach { r -> r.openLegs().forEach { bots += it.symbol } } }
        runCatching { IraNewsTrades.all().filter { !it.closed }.forEach { bots += it.symbol } }
        return bots
    }

    /**
     * Every market-watch pass: each of the owner's own bought options with a stop (and no trail of its own) has its
     * stop moved up by [com.optionslab.ira.AutoTrail] - to the price paid once up 20%, then 15% under the best price.
     */
    /** A move that failed, per protection: (the stop tried, when) - not tried again for [RETRY_MINUTES]. */
    private val failedTrail = HashMap<Long, Pair<Double, LocalDateTime>>()
    private const val RETRY_MINUTES = 15L

    suspend fun trailWatch() {
        if (!com.optionslab.app.BuildConfig.JARVIS || !Automations.on(Automations.Auto.TRAIL) || !com.optionslab.app.data.Market.isOpen()) return
        val items = runCatching { com.optionslab.app.data.Protections.active() }.getOrNull() ?: return
        // Only the owner's own bought options with a resting stop, no trail of their own, not being removed.
        val mine = items.filter { it.qty > 0 && it.stop != null && it.trail == null && it.stopOrderId != null && it.note != "removing" }
        if (mine.isEmpty()) return
        val bots = botSymbols()
        val paper = runCatching { Paper.snapshot().positions.positions.filter { it.quantity > 0 }.associateBy { it.symbol } }.getOrDefault(emptyMap())
        val live = if (mine.any { it.live } && Broker.loggedIn) runCatching { Broker.positionBook().net.filter { it.qty > 0 }.associateBy { it.symbol } }.getOrDefault(emptyMap()) else emptyMap()
        val now = LocalDateTime.now(IST)
        for (it in mine) {
            if (it.symbol in bots) continue
            val (avg, ltp) = if (it.live) live[it.symbol]?.let { p -> p.avg to p.last } ?: continue
                else paper[it.symbol]?.let { p -> p.averagePrice to p.ltp } ?: continue
            val peak = maxOf(it.best, ltp)
            val stop = com.optionslab.ira.AutoTrail.next(avg, peak, it.stop) ?: continue
            if (ltp <= stop) continue                              // a stop must sit under the price
            val failed = synchronized(failedTrail) { failedTrail[it.id] }
            if (failed != null && failed.second.plusMinutes(RETRY_MINUTES).isAfter(now)) continue
            // Moved in place: if the broker refuses, the old stop stays exactly where it was.
            val why = com.optionslab.app.data.Protections.moveStop(it.id, stop)
            if (why == null) {
                synchronized(failedTrail) { failedTrail.remove(it.id) }
                val said = com.optionslab.ira.AutoTrail.say(it.symbol, stop, avg)
                IraHub.note(said); IraActivity.add(said); Automations.acted(Automations.Auto.TRAIL, said)
            } else {
                synchronized(failedTrail) { failedTrail[it.id] = stop to now }
                fun px(x: Double?) = x?.let { v -> "%.2f".format(java.util.Locale.ENGLISH, v) } ?: "?"
                val said = "Could not trail the stop on ${it.symbol} up to ${px(stop)}: $why Your stop stays at ${px(it.stop)}."
                IraHub.note(said); IraActivity.add(said)
                IraHub.appContext()?.let { c -> JarvisPopup.show(c, "Boss, check the stop on ${it.symbol}", said) }
            }
        }
    }

    @Volatile private var overtradeToldAt: LocalDateTime? = null

    /** More than 3 of the owner's own buys in 30 minutes: a word, at most once every 30 minutes. */
    suspend fun overtradeWatch() {
        if (!com.optionslab.app.BuildConfig.JARVIS || !Automations.on(Automations.Auto.OVERTRADE) || !com.optionslab.app.data.Market.isOpen()) return
        val now = LocalDateTime.now(IST)
        if (overtradeToldAt?.isAfter(now.minusMinutes(com.optionslab.ira.Overtrade.WINDOW_MINUTES)) == true) return
        val today = com.optionslab.app.data.Market.today().toString()
        val owners = runCatching { com.optionslab.app.data.Strategies.owners() }.getOrDefault(emptyMap())
        fun manual(owner: String?) = owner == null || owner == com.optionslab.app.data.Origins.OUTSIDE ||
            owner.startsWith(com.optionslab.app.data.Origins.MANUAL) && !owner.contains("Jarvis")
        fun at(ts: String) = runCatching { LocalDateTime.parse(ts.take(19).replace(' ', 'T')) }.getOrNull()
        val times = ArrayList<LocalDateTime>()
        runCatching { Paper.snapshot().orders.orders.filter { it.timestamp.startsWith(today) && it.status.equals("COMPLETE", true) && it.action.equals("BUY", true) &&
            manual(owners["paper:${it.orderId}"] ?: it.strategy.takeIf { s -> s.isNotBlank() }) }.mapNotNull { at(it.timestamp) }.forEach { times += it } }
        if (Broker.loggedIn) runCatching { Broker.orders().filter { it.placedAt.startsWith(today) && it.status.equals("COMPLETE", true) && it.side.equals("BUY", true) &&
            manual(owners["kite:${it.id}"] ?: owners[it.id] ?: com.optionslab.app.data.Origins.fromTag(it.tag)) }.mapNotNull { at(it.placedAt) }.forEach { times += it } }
        val n = com.optionslab.ira.Overtrade.count(times, now) ?: return
        overtradeToldAt = now
        val text = com.optionslab.ira.Overtrade.say(n)
        IraHub.appContext()?.let { JarvisPopup.show(it, "Boss, slow down?", text) }
        IraHub.note(text); JarvisVoice.announce(text); IraActivity.add("Warned: $n trades in 30 minutes.")
        Automations.acted(Automations.Auto.OVERTRADE, "Warned: $n trades in 30 minutes.")
    }

    /** The evening's check of the week: losses outgrowing wins. */
    fun lossSizeLine(): String? {
        val live = AppSettings.load().live
        val today = com.optionslab.app.data.Market.today()
        val nets = runCatching { com.optionslab.app.data.TradeBook.trips(live) }.getOrDefault(emptyList())
            .filter { !it.closedAt.toLocalDate().isBefore(today.minusDays(6)) }.map { it.net }
        return com.optionslab.ira.LossSize.check(nets)
    }

    private const val GAP_KEY = "jarvis.gap.record"

    /** Saved by the nightly study: each arm's record on gap-up, gap-down and flat days (BankNifty). */
    fun saveGapRecord(record: Map<com.optionslab.ira.GapPlan.Gap, List<String>>) = runCatching {
        com.optionslab.app.security.SecurePrefs.put(GAP_KEY, org.json.JSONObject().apply { record.forEach { (g, l) -> put(g.name, org.json.JSONArray(l)) } }.toString())
    }

    private fun gapRecord(g: com.optionslab.ira.GapPlan.Gap): List<String> = runCatching {
        val a = org.json.JSONObject(com.optionslab.app.security.SecurePrefs.getString(GAP_KEY) ?: "{}").optJSONArray(g.name) ?: return@runCatching emptyList()
        (0 until a.length()).map { a.getString(it) }
    }.getOrDefault(emptyList())

    private const val ARM_RECORD_KEY = "jarvis.dayplan.record"
    private const val PARKED_KEY = "jarvis.dayplan.parked"
    private const val KEPT_KEY = "jarvis.dayplan.kept"

    /** Saved by the nightly study: each arm's (net, trades) by regime (BankNifty). */
    fun saveArmRecord(record: Map<String, Map<com.optionslab.ira.Regime.Kind, Pair<Double, Int>>>) = runCatching {
        com.optionslab.app.security.SecurePrefs.put(ARM_RECORD_KEY, org.json.JSONObject().apply {
            record.forEach { (arm, by) -> put(arm, org.json.JSONObject().apply { by.forEach { (k, v) -> put(k.name, org.json.JSONArray().put(v.first).put(v.second)) } }) }
        }.toString())
    }

    private fun armRecord(): Map<String, Map<com.optionslab.ira.Regime.Kind, Pair<Double, Int>>> = runCatching {
        val o = org.json.JSONObject(com.optionslab.app.security.SecurePrefs.getString(ARM_RECORD_KEY) ?: "{}")
        o.keys().asSequence().associateWith { arm -> val by = o.getJSONObject(arm)
            by.keys().asSequence().mapNotNull { k -> runCatching { com.optionslab.ira.Regime.Kind.valueOf(k) }.getOrNull()?.let { kind ->
                kind to by.getJSONArray(k).let { a -> a.getDouble(0) to a.getInt(1) } } }.toMap() }
    }.getOrDefault(emptyMap())

    private fun prefsMap(key: String): MutableMap<String, String> = runCatching {
        val o = org.json.JSONObject(com.optionslab.app.security.SecurePrefs.getString(key) ?: "{}")
        o.keys().asSequence().associateWith { o.getString(it) }.toMutableMap()
    }.getOrDefault(HashMap())

    private fun savePrefsMap(key: String, m: Map<String, String>) =
        com.optionslab.app.security.SecurePrefs.put(key, if (m.isEmpty()) null else org.json.JSONObject(m as Map<*, *>).toString())

    /** Every arm switched off for good (a restore, disarm all): nothing parked is armed again by the plan. */
    fun forgetParked() = runCatching { savePrefsMap(PARKED_KEY, emptyMap()); savePrefsMap(KEPT_KEY, emptyMap()) }

    /**
     * Once each trading morning (09:00 to 10:00, before the arms' first decisions): the PAPER arms fitted to BankNifty's
     * regime by [com.optionslab.ira.DayPlan] - only paper arms, never one trading Zerodha, never one Boss left off - and
     * Boss told what changed and why.
     */
    suspend fun dayPlanWatch() {
        if (!com.optionslab.app.BuildConfig.JARVIS || com.optionslab.app.BuildConfig.GOLD || !Automations.on(Automations.Auto.PLAN)) return
        val m = com.optionslab.app.data.Market
        val minute = m.minuteNow()
        if (!m.isTradingDay(m.today()) || minute < 9 * 60 || minute >= 10 * 60) return
        val key = "jarvis.dayplan.day"
        if (com.optionslab.app.security.SecurePrefs.getString(key) == m.today().toString()) return
        val now = IraStudy.regimeOf(com.optionslab.ira.Market.BANKNIFTY) ?: return
        val record = armRecord().ifEmpty { return }
        val arms = com.optionslab.app.data.OrbArms
        // Read first: if the arms or the settings cannot be read, the plan is tried again on the next pass.
        val views = arms.view().arms
        val s = AppSettings.load()
        com.optionslab.app.security.SecurePrefs.put(key, m.today().toString())
        val liveNow = arms.liveNow()
        val parked = prefsMap(PARKED_KEY); val kept = prefsMap(KEPT_KEY)
        // A parked arm found armed: Boss armed it again himself, so it is left alone while this regime holds.
        views.filter { it.armed && parked.containsKey(it.arm.source) }.forEach { parked.remove(it.arm.source); kept[it.arm.source] = now.name }
        kept.entries.removeAll { it.value != now.name }
        val now0 = views.map { v -> com.optionslab.ira.DayPlan.ArmNow(v.arm.source, v.arm.label, v.armed,
            paper = (v.arm.paperOnly || !liveNow) && !v.liveOk, parked = parked.containsKey(v.arm.source),
            kept = kept[v.arm.source]?.let { k -> runCatching { com.optionslab.ira.Regime.Kind.valueOf(k) }.getOrNull() }) }
        val stopped = s.guardKill || com.optionslab.app.data.LossBreaker.trippedToday() ||
            runCatching { com.optionslab.app.data.Strategies.stoppedToday() }.getOrDefault(true)
        val done = ArrayList<com.optionslab.ira.DayPlan.Step>()
        for (step in com.optionslab.ira.DayPlan.plan(now0, record, now)) {
            if (step.on && stopped) continue                      // the kill switch or the day's loss limit: nothing armed
            val v = views.first { it.arm.source == step.source }
            // Armed on paper only: in Live an ordinary arm asks for the PIN, which is never given here, so it is not armed.
            val said = runCatching { arms.setArmed(step.source, step.on, v.automatic, pinConfirmed = false) }.getOrNull() ?: continue
            if (step.on && !said.contains("armed on paper") && !said.contains(" on paper, ")) continue
            if (step.on) parked.remove(step.source) else parked[step.source] = now.name
            done += step
        }
        savePrefsMap(PARKED_KEY, parked); savePrefsMap(KEPT_KEY, kept)
        val text = com.optionslab.ira.DayPlan.say(done, now) ?: return
        IraHub.note(com.optionslab.ira.Address.boss(text)); JarvisVoice.announce(com.optionslab.ira.Address.boss(text))
        IraActivity.add(text); Automations.acted(Automations.Auto.PLAN, text)
    }

    /** Just after the open (09:16 to 09:30), once a day: BankNifty's gap and how the arms did on such days. */
    suspend fun gapWatch() {
        if (!com.optionslab.app.BuildConfig.JARVIS || !Automations.on(Automations.Auto.GAP)) return
        val m = com.optionslab.app.data.Market
        val minute = m.minuteNow()
        if (!m.isTradingDay(m.today()) || minute < 9 * 60 + 16 || minute > 9 * 60 + 30) return
        val key = "jarvis.gap.told"
        if (com.optionslab.app.security.SecurePrefs.getString(key) == m.today().toString()) return
        val snap = IraHub.state.value.snaps[com.optionslab.ira.Market.BANKNIFTY] ?: return
        val prev = snap.prevClose ?: return
        if (snap.at.toLocalDate() != m.today()) return
        com.optionslab.app.security.SecurePrefs.put(key, m.today().toString())
        val gap = (snap.open - prev) / prev * 100
        val text = com.optionslab.ira.GapPlan.say(com.optionslab.ira.Market.BANKNIFTY, gap, gapRecord(com.optionslab.ira.GapPlan.of(gap)))
        IraHub.note(com.optionslab.ira.Address.boss(text)); JarvisVoice.announce(com.optionslab.ira.Address.boss(text))
        Automations.acted(Automations.Auto.GAP, text)
    }

    private val walls = HashMap<String, com.optionslab.ira.OiShift.Walls>()
    private val wallTold = HashSet<String>()

    /** Every 15 minutes in market hours: the biggest call and put open interest strikes; a move is told once a day. */
    suspend fun oiWatch() {
        if (!com.optionslab.app.BuildConfig.JARVIS || !Automations.on(Automations.Auto.OI) || !com.optionslab.app.data.Market.isOpen()) return
        val day = com.optionslab.app.data.Market.today().toString()
        for (u in listOf("NIFTY", "BANKNIFTY")) runCatching {
            val c = kotlinx.coroutines.withTimeoutOrNull(25_000) { IraAccount.chain(u) } ?: return@runCatching
            val call = c.rows.filter { (it.ce?.oi ?: 0) > 0 }.maxByOrNull { it.ce!!.oi }?.strike
            val put = c.rows.filter { (it.pe?.oi ?: 0) > 0 }.maxByOrNull { it.pe!!.oi }?.strike
            val now = com.optionslab.ira.OiShift.Walls(call, put)
            val before = synchronized(walls) { walls.put(u, now) } ?: return@runCatching
            com.optionslab.ira.OiShift.say(u, before, now).forEach { line ->
                if (synchronized(wallTold) { wallTold.add("$day|$line") }) {
                    IraHub.appContext()?.let { JarvisPopup.show(it, "$u: open interest moved", line) }
                    IraHub.note(line); JarvisVoice.announce(line); Automations.acted(Automations.Auto.OI, line)
                }
            }
        }
    }

    private val orbTold = HashSet<String>()
    /** Where each index stood against its range at the last look (day|market -> true above, false below, null inside). */
    private val orbLast = HashMap<String, Boolean?>()

    /**
     * Nifty or BankNifty leaving its opening range: told once per side per day (after 09:30, market hours), and only on
     * the move out - a break seen already outside at the first look (a restart, the app opened late) is not news.
     */
    fun orbWatch() {
        if (!com.optionslab.app.BuildConfig.JARVIS || !Automations.on(Automations.Auto.ORB) || !com.optionslab.app.data.Market.isOpen()) return
        val now = java.time.LocalTime.now(IST)
        if (now.isBefore(java.time.LocalTime.of(9, 30))) return
        val day = com.optionslab.app.data.Market.today().toString()
        for (m in listOf(com.optionslab.ira.Market.NIFTY, com.optionslab.ira.Market.BANKNIFTY)) {
            val s = IraHub.state.value.snaps[m] ?: continue
            if (s.at.toLocalDate().toString() != day) continue
            val now = com.optionslab.ira.OpeningRange.broken(s)
            val key = "$day|${m.name}"
            val seen = synchronized(orbLast) { orbLast.containsKey(key) }
            val before = synchronized(orbLast) { orbLast.put(key, now) }
            val up = now ?: continue
            if (!seen || before == up) continue
            if (!synchronized(orbTold) { orbTold.add("$day|${m.name}|$up") }) continue
            val line = com.optionslab.ira.OpeningRange.alert(s, up)
            IraHub.appContext()?.let { JarvisPopup.show(it, "${m.label}: opening range", line) }
            IraHub.note(line); JarvisVoice.announce(line); Automations.acted(Automations.Auto.ORB, line)
        }
    }

    /** VIX's change on the day at the last look (a spike is told on the way up, once). */
    private val vixLast = HashMap<String, Double?>()
    private val vixTold = HashSet<String>()

    /**
     * India VIX up 10% or more on the day (market hours): told once, as it crosses - the first look of a day only records,
     * so a spike already there when the app starts is not told as news.
     */
    fun vixWatch() {
        if (!com.optionslab.app.BuildConfig.JARVIS || !Automations.on(Automations.Auto.VIX) || !com.optionslab.app.data.Market.isOpen()) return
        val day = com.optionslab.app.data.Market.today().toString()
        val v = IraHub.state.value.snaps[com.optionslab.ira.Market.VIX] ?: return
        if (v.at.toLocalDate().toString() != day) return
        val (seen, before) = synchronized(vixLast) { val s = vixLast.containsKey(day); val b = vixLast[day]; vixLast[day] = v.changePct; s to b }
        if (!seen) return
        val line = com.optionslab.ira.VixSpike.alert(v, before) ?: return
        if (!synchronized(vixTold) { vixTold.add(day) }) return
        IraHub.appContext()?.let { JarvisPopup.show(it, "India VIX spiking", line) }
        IraHub.note(line); JarvisVoice.announce(line); Automations.acted(Automations.Auto.VIX, line)
    }

    /** "Explain my position": each open position's P&L, room to its stop and target, time left and time decay. */
    suspend fun explainPositions(): List<String> {
        val prot = runCatching { com.optionslab.app.data.Protections.active() }.getOrDefault(emptyList())
        val out = ArrayList<String>()
        val now = java.time.ZonedDateTime.now(IST)
        val minuteNow = now.hour * 60 + now.minute
        fun spot(u: String) = runCatching { IraHub.state.value.snaps[com.optionslab.ira.Market.valueOf(u)]?.price }.getOrNull()
        fun theta(call: Boolean, u: String, strike: Double, expiry: LocalDate, price: Double): Double? {
            val s = spot(u) ?: return null
            val t = com.optionslab.engine.options.OptionMath.timeToExpiryYears(now, expiry)
            return com.optionslab.engine.options.OptionMath.legGreeks(if (call) com.optionslab.engine.options.OptionType.CE else com.optionslab.engine.options.OptionType.PE,
                s, strike, t, price)?.greeks?.theta
        }
        fun left(product: String, expiry: LocalDate?): Int? = when {
            product.equals("MIS", true) -> (15 * 60 + 15 - minuteNow).takeIf { it > 0 }
            expiry != null -> (java.time.temporal.ChronoUnit.MINUTES.between(now.toLocalDateTime(), expiry.atTime(15, 30))).toInt().takeIf { it > 0 }
            else -> null
        }
        runCatching { Paper.snapshot().positions.positions.filter { it.quantity != 0 }.forEach { p ->
            val c = Paper.contractOf(p.symbol)
            val pr = prot.lastOrNull { !it.live && it.symbol == p.symbol }
            out += com.optionslab.ira.PositionTalk.lines(com.optionslab.ira.PositionTalk.Pos(p.symbol, "Paper", p.quantity, p.averagePrice, p.ltp, pr?.stop, pr?.target,
                left(p.product, c?.expiry), c?.let { theta(it.right == com.optionslab.engine.Right.CE, it.underlying, it.strike, it.expiry, p.ltp) }))
        } }
        if (Broker.loggedIn) runCatching {
            val ins = Broker.cachedInstruments().orEmpty().associateBy { it.tradingSymbol }
            Broker.positionBook().net.filter { it.open }.forEach { p ->
                val i = ins[p.symbol]
                val pr = prot.lastOrNull { it.live && it.symbol == p.symbol }
                out += com.optionslab.ira.PositionTalk.lines(com.optionslab.ira.PositionTalk.Pos(p.symbol, "Zerodha", p.qty, p.avg, p.last, pr?.stop, pr?.target,
                    left(p.product, i?.expiry), i?.let { theta(it.right == com.optionslab.engine.Right.CE, it.name, it.strike, it.expiry, p.last) }))
            }
        }
        return out.ifEmpty { listOf("You have no open positions.") }
    }

    /** The 15:35 spoken wrap-up: the day's P&L, the scorecard's headline, tomorrow's events. */
    suspend fun daySummary(scorecard: String?) {
        if (!com.optionslab.app.BuildConfig.JARVIS) return
        val live = AppSettings.load().live
        if (!Automations.on(Automations.Auto.SUMMARY)) return
        val zerodha = live && Broker.loggedIn
        val pnl = runCatching {
            if (zerodha) Broker.positionBook().net.sumOf { it.pnl } else Paper.snapshot().dayPnl
        }.getOrNull()?.let { "${if (zerodha) "Zerodha" else "Paper"} today: ${AppFacts.rs(it)}." }
        val events = runCatching { IraEvents.upcoming(2).map { com.optionslab.ira.Events.line(it, com.optionslab.app.data.Market.today()) } }.getOrDefault(emptyList())
        // How Nifty's day went comes first: the market's story, then Boss's own.
        val story = IraHub.dayStory(com.optionslab.ira.Market.NIFTY)
        // Solo's day right after the market's story (the spoken wrap-up keeps the first sentences).
        val text = listOfNotNull(story, IraSolo.daySummary(), com.optionslab.ira.DaySummary.say(pnl, scorecard, events)).joinToString(" ")
        IraHub.note(text)
        JarvisVoice.announce(com.optionslab.ira.Wake.spoken(text, 7))
        Automations.acted(Automations.Auto.SUMMARY, text)
    }
}
