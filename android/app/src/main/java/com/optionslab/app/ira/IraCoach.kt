package com.optionslab.app.ira

import com.optionslab.app.data.AppSettings
import com.optionslab.app.data.Broker
import com.optionslab.app.data.Paper
import com.optionslab.ira.AppFacts
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Jarvis watching over the owner's own trading (JarvisAlgo, the owner's wishes 2026-10-02): the automatic trailing
 * stop, too many trades too fast, losses outgrowing wins, the opening-gap plan, open interest walls moving, "explain my
 * position" and the 15:35 wrap-up. The bots (ORB, Pine, strategy runs) and Jarvis's own trades manage their own exits.
 */
internal object IraCoach {
    private val IST = ZoneId.of("Asia/Kolkata")

    /** The automatic trailing stop (on by default: the owner asked for it to be automatic). */
    var autoTrail: Boolean
        get() = runCatching { com.optionslab.app.security.SecurePrefs.getBoolean("jarvis.autotrail", true) }.getOrDefault(true)
        set(v) { runCatching { com.optionslab.app.security.SecurePrefs.put("jarvis.autotrail", v) } }

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
    suspend fun trailWatch() {
        if (!com.optionslab.app.BuildConfig.JARVIS || !autoTrail || !com.optionslab.app.data.Market.isOpen()) return
        val items = runCatching { com.optionslab.app.data.Protections.active() }.getOrNull() ?: return
        val mine = items.filter { it.qty > 0 && it.stop != null && it.trail == null }
        if (mine.isEmpty()) return
        val bots = botSymbols()
        val paper = runCatching { Paper.snapshot().positions.positions.filter { it.quantity > 0 }.associateBy { it.symbol } }.getOrDefault(emptyMap())
        val live = if (mine.any { it.live } && Broker.loggedIn) runCatching { Broker.positionBook().net.filter { it.qty > 0 }.associateBy { it.symbol } }.getOrDefault(emptyMap()) else emptyMap()
        for (it in mine) {
            if (it.symbol in bots) continue
            val (avg, ltp) = if (it.live) live[it.symbol]?.let { p -> p.avg to p.last } ?: continue
                else paper[it.symbol]?.let { p -> p.averagePrice to p.ltp } ?: continue
            val peak = maxOf(it.best, ltp)
            val stop = com.optionslab.ira.AutoTrail.next(avg, peak, it.stop) ?: continue
            if (ltp <= stop) continue                              // a stop must sit under the price
            val r = if (it.live) com.optionslab.app.data.Protections.protectLive(it.symbol, it.exchange, it.product, it.qty, ltp, stop, null, it.target)
                else com.optionslab.app.data.Protections.protectPaper(it.symbol, it.product, it.qty, ltp, stop, null, it.target)
            val said = if (r.startsWith("Protected")) com.optionslab.ira.AutoTrail.say(it.symbol, stop, avg) else "Could not move the stop on ${it.symbol}: $r"
            IraHub.note(said)
            IraActivity.add(said)
            if (!r.startsWith("Protected")) IraHub.appContext()?.let { c -> JarvisPopup.show(c, "Boss, check the stop on ${it.symbol}", said) }
        }
    }

    @Volatile private var overtradeToldAt: LocalDateTime? = null

    /** More than 3 of the owner's own buys in 30 minutes: a word, at most once every 30 minutes. */
    suspend fun overtradeWatch() {
        if (!com.optionslab.app.BuildConfig.JARVIS || !com.optionslab.app.data.Market.isOpen()) return
        val now = LocalDateTime.now(IST)
        if (overtradeToldAt?.isAfter(now.minusMinutes(com.optionslab.ira.Overtrade.WINDOW_MINUTES)) == true) return
        val today = com.optionslab.app.data.Market.today().toString()
        val owners = runCatching { com.optionslab.app.data.Strategies.owners() }.getOrDefault(emptyMap())
        fun manual(owner: String?) = owner == null || owner.startsWith(com.optionslab.app.data.Origins.MANUAL) && !owner.contains("Jarvis")
        fun at(ts: String) = runCatching { LocalDateTime.parse(ts.take(19).replace(' ', 'T')) }.getOrNull()
        val times = ArrayList<LocalDateTime>()
        runCatching { Paper.snapshot().orders.orders.filter { it.timestamp.startsWith(today) && it.status.equals("COMPLETE", true) && it.action.equals("BUY", true) &&
            manual(owners["paper:${it.orderId}"] ?: it.strategy.takeIf { s -> s.isNotBlank() }) }.mapNotNull { at(it.timestamp) }.forEach { times += it } }
        if (Broker.loggedIn) runCatching { Broker.orders().filter { it.placedAt.startsWith(today) && it.status.equals("COMPLETE", true) && it.side.equals("BUY", true) &&
            manual(owners[it.id] ?: it.tag.takeIf { t -> t.isNotBlank() }) }.mapNotNull { at(it.placedAt) }.forEach { times += it } }
        val n = com.optionslab.ira.Overtrade.count(times, now) ?: return
        overtradeToldAt = now
        val text = com.optionslab.ira.Overtrade.say(n)
        IraHub.appContext()?.let { JarvisPopup.show(it, "Boss, slow down?", text) }
        IraHub.note(text); JarvisVoice.announce(text); IraActivity.add("Warned: $n trades in 30 minutes.")
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

    /** Just after the open (09:16 to 09:30), once a day: BankNifty's gap and how the arms did on such days. */
    suspend fun gapWatch() {
        if (!com.optionslab.app.BuildConfig.JARVIS) return
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
    }

    private val walls = HashMap<String, com.optionslab.ira.OiShift.Walls>()
    private val wallTold = HashSet<String>()

    /** Every 15 minutes in market hours: the biggest call and put open interest strikes; a move is told once a day. */
    suspend fun oiWatch() {
        if (!com.optionslab.app.BuildConfig.JARVIS || !com.optionslab.app.data.Market.isOpen()) return
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
                    IraHub.note(line); JarvisVoice.announce(line)
                }
            }
        }
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
        val pnl = runCatching {
            if (live && Broker.loggedIn) Broker.positionBook().net.sumOf { it.pnl } else Paper.snapshot().dayPnl
        }.getOrNull()?.let { "${if (live) "Zerodha" else "Paper"} today: ${AppFacts.rs(it)}." }
        val events = runCatching { IraEvents.upcoming(2).map { com.optionslab.ira.Events.line(it, com.optionslab.app.data.Market.today()) } }.getOrDefault(emptyList())
        val text = com.optionslab.ira.DaySummary.say(pnl, scorecard, events)
        IraHub.note(text)
        JarvisVoice.announce(com.optionslab.ira.Wake.spoken(text, 5))
    }
}
