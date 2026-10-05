package com.optionslab.app.ira

import com.optionslab.app.data.AppSettings
import com.optionslab.app.data.Broker
import com.optionslab.app.data.Paper
import com.optionslab.ira.AppFacts
import kotlinx.coroutines.async
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
    // Read-only: switched with "Guard my positions" on the Jarvis screen, with the fingerprint (it moves real stops).
    val autoTrail: Boolean
        get() = Automations.on(Automations.Auto.TRAIL)

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
        savePrefsMap(PARKED_KEY, parked); savePrefsMap(KEPT_KEY, kept)
        // (Nothing is armed while the kill switch, the day's loss limit or the day's stop holds.)
        val steps = com.optionslab.ira.DayPlan.plan(now0, record, now).filter { !(it.on && stopped) }
        val proposal = com.optionslab.ira.DayPlan.propose(steps, now) ?: return
        // Boss, 4 Oct: "while stopping anything the AI thinks should be stopped, take my approval first" - the plan is
        // asked (yes or no, or Confirm on the Ira screen) and done only then, as things stand at that moment.
        IraHub.offer("today's paper arm plan", "Boss, today's plan", proposal, suspend {
            val done = ArrayList<com.optionslab.ira.DayPlan.Step>()
            val p = prefsMap(PARKED_KEY)
            val nowViews = arms.view().arms
            // Checked again at the yes (it can come up to 30 minutes later): nothing is armed once the kill switch, the
            // day's loss limit or the day's stop holds.
            val s2 = runCatching { AppSettings.load() }.getOrNull()
            val stoppedNow = s2 == null || s2.guardKill || com.optionslab.app.data.LossBreaker.trippedToday() ||
                runCatching { com.optionslab.app.data.Strategies.stoppedToday() }.getOrDefault(true)
            for (step in steps) {
                if (step.on && stoppedNow) continue
                val v = nowViews.firstOrNull { it.arm.source == step.source } ?: continue
                if (v.armed == step.on) continue                  // already as planned (Boss changed it himself)
                // Armed on paper only: in Live an ordinary arm asks for the PIN, which is never given here, so it is not armed.
                val said = runCatching { arms.setArmed(step.source, step.on, v.automatic, pinConfirmed = false) }.getOrNull() ?: continue
                if (step.on && !said.contains("armed on paper") && !said.contains(" on paper, ")) continue
                if (step.on) p.remove(step.source) else p[step.source] = now.name
                done += step
            }
            savePrefsMap(PARKED_KEY, p)
            val text = com.optionslab.ira.DayPlan.say(done, now) ?: "Nothing needed changing any more."
            IraActivity.add(text); Automations.acted(Automations.Auto.PLAN, text)
            text
        }, addsRisk = steps.any { it.on })
    }

    /** At each market hour's first minutes: a strong habit's question answered unasked, once a day each. */
    suspend fun usualWatch() {
        if (!com.optionslab.app.BuildConfig.JARVIS || com.optionslab.app.BuildConfig.GOLD || !Automations.on(Automations.Auto.USUAL) ||
            !com.optionslab.app.data.Market.isOpen()) return
        val now = java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Kolkata"))
        // The hour's first minutes - 09:15 to 09:20 for the 9 o'clock hour, the market opening at 09:15 (review, 4 Oct).
        val from = if (now.hour == 9) 15 else 0
        if (now.minute !in from..from + 5) return
        val day = com.optionslab.app.data.Market.today().toString()
        val key = "jarvis.usual.told"
        val o = runCatching { org.json.JSONObject(com.optionslab.app.security.SecurePrefs.getString(key) ?: "{}") }.getOrDefault(org.json.JSONObject())
        val told = if (o.optString("d") == day) o.optJSONArray("k")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty() else emptySet()
        val due = com.optionslab.ira.Habits.due(IraTools.habits(), now.hour, told, IraTools.habitsLast(), now.toLocalDate()) ?: return
        val question = com.optionslab.ira.Habits.question(due) ?: return
        // Kept as told only once said: an answer that could not be given (old prices) is tried again on the next pass.
        val text = IraHub.usualAnswer(question) ?: return
        com.optionslab.app.security.SecurePrefs.put(key, org.json.JSONObject().put("d", day).put("k", org.json.JSONArray(told + due)).toString())
        IraHub.note(text); JarvisVoice.announce(com.optionslab.ira.Wake.spoken(text, 3)); Automations.acted(Automations.Auto.USUAL, question)
    }

    /**
     * Every market-watch pass in market hours: one of Boss's own positions (not a bot's) losing half, then three quarters,
     * of his daily loss limit for its account, or an option he sold 80% decayed - told once each a day by
     * [com.optionslab.ira.HeadsUp]. Words only: nothing is placed, changed or closed. Aloud only the plain words (no
     * symbol, no amount: a locked phone may be heard); the details are in the chat.
     */
    suspend fun headsUpWatch() {
        if (!com.optionslab.app.BuildConfig.JARVIS || com.optionslab.app.BuildConfig.GOLD || !Automations.on(Automations.Auto.HEADSUP) ||
            !com.optionslab.app.data.Market.isOpen()) return
        val s = runCatching { AppSettings.load() }.getOrNull() ?: return
        val bots = botSymbols()
        val open = ArrayList<com.optionslab.ira.HeadsUp.Pos>()
        runCatching { Paper.snapshot().takeIf { it.priced }?.positions?.positions.orEmpty().filter { it.quantity != 0 }.forEach {
            open += com.optionslab.ira.HeadsUp.Pos("P:${it.symbol}", it.symbol, false, it.quantity, it.averagePrice, it.ltp) } }
        if (Broker.loggedIn) {
            // The read blocks on the network: run apart, so the 15 s limit really ends the wait (the read itself may go on).
            val read = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).async { runCatching { Broker.passPositionBook().net }.getOrNull() }
            kotlinx.coroutines.withTimeoutOrNull(15_000) { read.await() }?.filter { it.qty != 0 }?.forEach {
                open += com.optionslab.ira.HeadsUp.Pos("L:${it.symbol}", it.symbol, true, it.qty, it.avg, it.last) }
        }
        val mine = open.filter { it.symbol !in bots }
        if (mine.isEmpty()) return
        val day = com.optionslab.app.data.Market.today().toString()
        val key = "jarvis.headsup.told"
        val o = runCatching { org.json.JSONObject(com.optionslab.app.security.SecurePrefs.getString(key) ?: "{}") }.getOrDefault(org.json.JSONObject())
        val told = if (o.optString("d") == day) o.optJSONArray("k")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty() else emptySet()
        val alerts = com.optionslab.ira.HeadsUp.check(mine, s.guardDailyLoss, s.guardPaperDailyLoss, told)
        if (alerts.isEmpty()) return
        // Kept as told before it is said: a heads-up is never repeated today, even if the app restarts mid-way.
        com.optionslab.app.security.SecurePrefs.put(key, org.json.JSONObject().put("d", day)
            .put("k", org.json.JSONArray(told + alerts.flatMap { it.marks })).toString())
        alerts.forEach { IraHub.note(it.text); IraActivity.add(it.text); Automations.acted(Automations.Auto.HEADSUP, it.text) }
        // One spoken line a pass, however many were noted (the worst first).
        JarvisVoice.announce(alerts.first().spoken)
    }

    /** 15:10-15:18 on a trading day, once: open Zerodha MIS positions named before the broker's own square-off. */
    suspend fun misWatch() {
        if (!com.optionslab.app.BuildConfig.JARVIS || com.optionslab.app.BuildConfig.GOLD) return
        val m = com.optionslab.app.data.Market
        if (!m.isTradingDay(m.today()) || !com.optionslab.ira.MisNudge.due(m.minuteNow()) || !Broker.loggedIn) return
        val key = "jarvis.mis.told"
        if (com.optionslab.app.security.SecurePrefs.getString(key) == m.today().toString()) return
        // The read blocks on the network: run apart, so the 15 s limit really ends the wait (the read itself may go on).
        val read = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).async { runCatching { Broker.passPositionBook().net }.getOrNull() }
        val open = kotlinx.coroutines.withTimeoutOrNull(15_000) { read.await() } ?: return
        com.optionslab.app.security.SecurePrefs.put(key, m.today().toString())
        val mis = open.filter { it.product.equals("MIS", true) && it.qty != 0 }.map { it.symbol to it.qty }
        val text = com.optionslab.ira.MisNudge.say(mis) ?: return
        IraHub.note(text); JarvisVoice.announce(com.optionslab.ira.MisNudge.say(mis, named = false)!!); IraActivity.add(text); Automations.acted(Automations.Auto.MIS, text)
    }

    /** 09:05-09:14 on a trading day, once: Zerodha not logged in while Live mode or a Zerodha arm needs it. */
    suspend fun loginWatch() {
        if (!com.optionslab.app.BuildConfig.JARVIS || com.optionslab.app.BuildConfig.GOLD) return
        val m = com.optionslab.app.data.Market
        val b = com.optionslab.app.data.Broker
        val minute = m.minuteNow()
        if (!m.isTradingDay(m.today()) || minute !in com.optionslab.ira.LoginNudge.FROM..com.optionslab.ira.LoginNudge.TO) return
        val key = "jarvis.login.nudged"
        if (com.optionslab.app.security.SecurePrefs.getString(key) == m.today().toString()) return
        // Only Live mode sends anything to Zerodha (an arm cleared for Live still trades on paper in Paper mode).
        val needs = runCatching { AppSettings.load().live }.getOrDefault(false)
        if (!com.optionslab.ira.LoginNudge.due(minute, b.configured, b.loggedIn, needs)) return
        com.optionslab.app.security.SecurePrefs.put(key, m.today().toString())
        val t = com.optionslab.ira.LoginNudge.say(minute)
        IraHub.note(t); JarvisVoice.announce(t); IraActivity.add(t)
    }

    private var relay = com.optionslab.ira.RelayWatch.State()
    private var relayAt = 0L

    /** Every 3 minutes, 08:30 to 15:30 on a trading day: is the static-IP relay server answering? */
    suspend fun relayWatch() {
        if (!com.optionslab.app.BuildConfig.JARVIS || !Automations.on(Automations.Auto.RELAY)) return
        val r = com.optionslab.app.data.Relay
        val m = com.optionslab.app.data.Market
        if (!r.enabled || r.host == null || !m.isTradingDay(m.today()) || !com.optionslab.ira.RelayWatch.due(m.minuteNow())) return
        val now = System.currentTimeMillis()
        if (now - relayAt < com.optionslab.ira.RelayWatch.EVERY_MIN * 60_000L - 5_000) return
        relayAt = now
        // Already connected (the market watch keeps it so in market hours): answering. Otherwise a plain TCP knock on the
        // server's SSH port, 5 s at most - it logs in to nothing and saves nothing (no user name, no server key).
        val host = r.host ?: return
        val ok = r.connected || kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { java.net.Socket().use { s -> s.connect(java.net.InetSocketAddress(host, 22), 5_000); true } }.getOrDefault(false)
        }
        val (next, say) = com.optionslab.ira.RelayWatch.next(relay, ok)
        relay = next
        say?.let { IraHub.note(it); JarvisVoice.announce(it); IraActivity.add(it); Automations.acted(Automations.Auto.RELAY, it) }
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

    /** Each index's [com.optionslab.ira.Moments.Look] at the last look (day|market). */
    private val momentLast = HashMap<String, com.optionslab.ira.Moments.Look>()
    private val momentTold = HashSet<String>()

    /**
     * Nifty or BankNifty filling its opening gap, or going past the previous session's high or low (market hours): each
     * told once a day, on the move - the first look of a day only records, so a state already there is not news.
     */
    fun momentsWatch() {
        if (!com.optionslab.app.BuildConfig.JARVIS || !Automations.on(Automations.Auto.MOMENTS) || !com.optionslab.app.data.Market.isOpen()) return
        val day = com.optionslab.app.data.Market.today().toString()
        for (m in listOf(com.optionslab.ira.Market.NIFTY, com.optionslab.ira.Market.BANKNIFTY)) {
            val s = IraHub.state.value.snaps[m] ?: continue
            if (s.at.toLocalDate().toString() != day) continue
            val bars = IraHub.recentBars(m)
            val now = com.optionslab.ira.Moments.look(s, bars) ?: continue
            val before = synchronized(momentLast) { momentLast.put("$day|${m.name}", now) } ?: continue
            for (a in com.optionslab.ira.Moments.alerts(s, bars, before, now)) {
                if (!synchronized(momentTold) { momentTold.add("$day|${a.key}") }) continue
                IraHub.appContext()?.let { JarvisPopup.show(it, a.title, a.text) }
                IraHub.note(a.text); JarvisVoice.announce(a.text); Automations.acted(Automations.Auto.MOMENTS, a.text)
            }
        }
    }

    /** Today's reads of each expiring chain (day|market), oldest first: the first is what decay is measured from. */
    private val expiryReads = HashMap<String, MutableList<com.optionslab.ira.ExpiryDay.Read>>()
    private val expiryTold = HashSet<String>()

    /**
     * The expiry-day companion (market hours, an index's expiry day): at 09:30, 12:00, 13:30, 14:30 and 15:20 the
     * at-the-money straddle and how much of it has gone, spot against max pain, and the day's / last hour's range. Each
     * slot told once a day, only within ten minutes of its time; the chain is read only then. Words only.
     */
    suspend fun expiryWatch() {
        if (!com.optionslab.app.BuildConfig.JARVIS || com.optionslab.app.BuildConfig.GOLD || !Automations.on(Automations.Auto.EXPIRYDAY) ||
            !com.optionslab.app.data.Market.isOpen()) return
        val now = LocalDateTime.now(IST)
        val slot = com.optionslab.ira.ExpiryDay.due(now.toLocalTime()) ?: return
        val day = com.optionslab.app.data.Market.today().toString()
        for (m in listOf(com.optionslab.ira.Market.NIFTY, com.optionslab.ira.Market.BANKNIFTY)) runCatching {
            if (!com.optionslab.app.data.Market.isExpiryDay(m.name)) return@runCatching
            val key = "$day|${m.name}|${slot.name}"
            if (synchronized(expiryTold) { key in expiryTold }) return@runCatching
            val c = kotlinx.coroutines.withTimeoutOrNull(25_000) { IraAccount.chain(m.name) } ?: return@runCatching
            val read = com.optionslab.ira.ExpiryDay.read(c, now) ?: return@runCatching
            if (!synchronized(expiryTold) { expiryTold.add(key) }) return@runCatching
            val first = synchronized(expiryReads) {
                val rs = expiryReads.getOrPut("$day|${m.name}") { ArrayList() }
                rs.add(read); rs.first()
            }
            val text = com.optionslab.ira.ExpiryDay.say(m, slot, first, read, IraHub.recentBars(m))
            IraHub.appContext()?.let { JarvisPopup.show(it, "${m.label}: expiry day", text) }
            IraHub.note(text); JarvisVoice.announce(text); Automations.acted(Automations.Auto.EXPIRYDAY, text)
        }
    }

    /** "How is expiry going?": today's expiry reads, or null when there are none. */
    fun expirySoFar(): String? {
        val day = com.optionslab.app.data.Market.today().toString()
        val reads = synchronized(expiryReads) {
            listOf(com.optionslab.ira.Market.NIFTY, com.optionslab.ira.Market.BANKNIFTY)
                .associateWith { m -> expiryReads["$day|${m.name}"]?.toList().orEmpty() }
        }
        return com.optionslab.ira.ExpiryDay.soFar(reads)
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

    /** The end of the last sharp move told for each index (day|market): one move is told once. */
    private val sharpTold = HashMap<String, LocalDateTime>()

    /**
     * Nifty or BankNifty moving sharply within minutes (market hours): what coincided - the headlines published around
     * then, India VIX and the other indices over the same minutes - told once per move, in full in the chat and briefly
     * aloud. Timing only, never a cause; words only.
     */
    fun sharpMoveWatch() {
        if (!com.optionslab.app.BuildConfig.JARVIS || com.optionslab.app.BuildConfig.GOLD || !Automations.on(Automations.Auto.SHARPMOVE) ||
            !com.optionslab.app.data.Market.isOpen()) return
        val day = com.optionslab.app.data.Market.today()
        val now = LocalDateTime.now(IST)
        val bars = (com.optionslab.ira.SharpMove.INDICES + com.optionslab.ira.Market.VIX).associateWith { IraHub.recentBars(it) }
        for (m in listOf(com.optionslab.ira.Market.NIFTY, com.optionslab.ira.Market.BANKNIFTY)) {
            val b = bars[m].orEmpty()
            if (b.lastOrNull()?.t?.toLocalDate() != day) continue
            val mv = com.optionslab.ira.SharpMove.latest(m, b) ?: continue
            val key = "$day|${m.name}"
            val told = synchronized(sharpTold) {
                if (!com.optionslab.ira.SharpMove.worthTelling(mv, now, sharpTold[key])) false else { sharpTold[key] = mv.to; true }
            }
            if (!told) continue
            val expiry = runCatching { com.optionslab.app.data.Market.isExpiryDay(m.name) }.getOrDefault(false)
            val c = com.optionslab.ira.SharpMove.context(mv, bars, IraHub.state.value.news, IST)
            val text = com.optionslab.ira.SharpMove.say(mv, c, expiry = expiry)
            IraHub.appContext()?.let { JarvisPopup.show(it, "${m.label} ${"%+.2f%%".format(java.util.Locale.ENGLISH, mv.pct)} in ${mv.minutes} minutes", text) }
            IraHub.note(text); JarvisVoice.announce(com.optionslab.ira.SharpMove.spoken(mv, c)); Automations.acted(Automations.Auto.SHARPMOVE, text)
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

    /** Jarvis's review of himself for the wrap-up: what his own record changed, and what he does tomorrow. */
    private suspend fun selfReview(): String? = runCatching {
        val bar = IraNewsTrades.actAloneBar()
        // The bar as it stood before today (kept with its day: a second wrap-up the same day still compares to yesterday).
        val key = "jarvis.review.bar"
        val today = com.optionslab.app.data.Market.today().toString()
        val saved = com.optionslab.app.security.SecurePrefs.getString(key)?.split('|')
        val before = if (saved?.getOrNull(1) == today) saved.getOrNull(2)?.toIntOrNull() else saved?.getOrNull(0)?.toIntOrNull()
        val minutes = IraNewsTrades.byMinute()
        val hours = (9..15).mapNotNull { h -> com.optionslab.ira.ActAlone.badHour(minutes, h * 60)?.let { "%02d:00-%02d:00".format(java.util.Locale.ENGLISH, h, h + 1) } }
        val kinds = IraNewsTrades.byKind()
        val badKinds = kinds.map { it.first }.distinct().filter { com.optionslab.ira.ActAlone.badKind(kinds, it) != null }
        val goals = runCatching { IraGoals.statuses() }.getOrDefault(emptyList()).filter { it.broken || it.near }.map { it.text }
        val lesson = runCatching { IraAccount.lessons().first.firstOrNull()?.text }.getOrNull()
        val verdicts = runCatching { IraExpert.verdicts() }.getOrDefault(emptyList())
        // Self-calibration: the conditions his scored ideas say he is weak in (sat out / taken smaller), and those that
        // earned their way back since the conditions kept before today (kept with their day, like the bar).
        val calibKey = "jarvis.review.calib"
        val outcomes = runCatching { IraNewsTrades.calibration() }.getOrDefault(emptyList())
        val day = com.optionslab.app.data.Market.today()
        val savedCalib = com.optionslab.app.security.SecurePrefs.getString(calibKey)?.split('\n')
        fun keys(s: String?): Set<String> = s.orEmpty().split('\t').filter { it.isNotBlank() }.toSet()
        val calibBefore = savedCalib?.let { if (it.getOrNull(1) == today) keys(it.getOrNull(2)) else keys(it.getOrNull(0)) }
        val calibNow = runCatching { com.optionslab.ira.SelfCalibration.sitOutKeys(outcomes, day) }.getOrDefault(emptySet())
        // And Solo's own paper trades, by the same rules (kept under their own key: a separate record).
        val soloKey = "jarvis.review.calib.solo"
        val soloOutcomes = runCatching { IraSolo.calibration() }.getOrDefault(emptyList())
        val savedSolo = com.optionslab.app.security.SecurePrefs.getString(soloKey)?.split('\n')
        val soloBefore = savedSolo?.let { if (it.getOrNull(1) == today) keys(it.getOrNull(2)) else keys(it.getOrNull(0)) }
        val soloNow = runCatching { com.optionslab.ira.SelfCalibration.sitOutKeys(soloOutcomes, day) }.getOrDefault(emptySet())
        val calibration = runCatching { com.optionslab.ira.SelfCalibration.review(outcomes, day, calibBefore) }.getOrDefault(emptyList()) +
            runCatching { com.optionslab.ira.SoloCalibration.review(soloOutcomes, day, soloBefore) }.getOrDefault(emptyList())
        // The kinds of answer Boss marks wrong, where Jarvis now asks to be checked (words only; kept with their day too).
        val doubtKey = "jarvis.review.doubt"
        val wrongs = runCatching { IraTools.mistakes() }.getOrDefault(emptyList())
        val askedKinds = runCatching { IraTools.askedKinds() }.getOrDefault(emptyMap())
        val savedDoubt = com.optionslab.app.security.SecurePrefs.getString(doubtKey)?.split('\n')
        val doubtBefore = savedDoubt?.let { if (it.getOrNull(1) == today) keys(it.getOrNull(2)) else keys(it.getOrNull(0)) }
        val doubtNow = runCatching { com.optionslab.ira.SelfDoubt.weakKeys(wrongs, askedKinds, day) }.getOrDefault(emptySet())
        val doubts = runCatching { com.optionslab.ira.SelfDoubt.review(wrongs, askedKinds, day, doubtBefore) }.getOrDefault(emptyList())
        val said = com.optionslab.ira.SelfReview.say(com.optionslab.ira.SelfReview.Facts(bar, before, hours, badKinds, goals, lesson,
            verdicts.filter { it.state == com.optionslab.ira.Vetting.State.HELD_UP }.map { it.name },
            verdicts.filter { it.state == com.optionslab.ira.Vetting.State.FAILED }.map { it.name }, calibration, doubts))
        com.optionslab.app.security.SecurePrefs.put(key, "$bar|$today|${before ?: bar}")
        com.optionslab.app.security.SecurePrefs.put(calibKey, calibNow.joinToString("\t") + "\n" + today + "\n" + (calibBefore ?: calibNow).joinToString("\t"))
        com.optionslab.app.security.SecurePrefs.put(soloKey, soloNow.joinToString("\t") + "\n" + today + "\n" + (soloBefore ?: soloNow).joinToString("\t"))
        com.optionslab.app.security.SecurePrefs.put(doubtKey, doubtNow.joinToString("\t") + "\n" + today + "\n" + (doubtBefore ?: doubtNow).joinToString("\t"))
        said?.let { IraActivity.add(it) }
        said
    }.getOrNull()

    /** Boss's open positions (paper, and Zerodha when logged in), each with its delta and gamma now when they can be worked out. */
    private suspend fun openLegs(): List<com.optionslab.ira.Exposure.Leg> {
        val now = java.time.ZonedDateTime.now(IST)
        fun spot(u: String) = runCatching { IraHub.state.value.snaps[com.optionslab.ira.Market.valueOf(u)]?.price }.getOrNull()
        /** Delta and gamma per unit of [u]: the index itself 1 and 0; an option's from its price now. */
        fun greeks(right: com.optionslab.engine.Right, u: String, strike: Double, expiry: LocalDate, price: Double): Pair<Double, Double>? {
            if (right == com.optionslab.engine.Right.IX) return 1.0 to 0.0
            val s = spot(u) ?: return null
            val t = com.optionslab.engine.options.OptionMath.timeToExpiryYears(now, expiry)
            val g = com.optionslab.engine.options.OptionMath.legGreeks(if (right == com.optionslab.engine.Right.CE) com.optionslab.engine.options.OptionType.CE
                else com.optionslab.engine.options.OptionType.PE, s, strike, t, price)?.greeks ?: return null
            return g.delta to g.gamma
        }
        val out = ArrayList<com.optionslab.ira.Exposure.Leg>()
        runCatching { Paper.snapshot().positions.positions.filter { it.quantity != 0 }.forEach { p ->
            val c = Paper.contractOf(p.symbol)
            val g = c?.let { runCatching { greeks(it.right, it.underlying, it.strike, it.expiry, p.ltp) }.getOrNull() }
            out += com.optionslab.ira.Exposure.Leg("Paper", p.symbol, p.quantity, p.averagePrice, p.ltp, c?.underlying, g?.first, g?.second)
        } }
        if (Broker.loggedIn) runCatching {
            val ins = Broker.cachedInstruments().orEmpty().associateBy { it.tradingSymbol }
            // The broker is not waited on past 8 seconds (a hung read would hang the answer).
            kotlinx.coroutines.withTimeoutOrNull(8_000) { Broker.positionBook() }?.net.orEmpty().filter { it.open }.forEach { p ->
                val i = ins[p.symbol]
                val g = i?.let { runCatching { greeks(it.right, it.name, it.strike, it.expiry, p.last) }.getOrNull() }
                out += com.optionslab.ira.Exposure.Leg("Zerodha", p.symbol, p.qty, p.avg, p.last, i?.name, g?.first, g?.second)
            }
        }
        return out
    }

    /** "What happens to my P&L if Nifty moves 100 points": a rough figure from the open positions' deltas. Reads only. */
    suspend fun moveLines(question: String): List<String> {
        val s = com.optionslab.ira.Exposure.moveAsked(question) ?: return listOf("Ask it with a size, Boss: \"what happens to my P&L if Nifty moves 100 points\".")
        val spot = runCatching { IraHub.state.value.snaps[s.market]?.price }.getOrNull()
        return com.optionslab.ira.Exposure.move(s, openLegs(), spot)
    }

    /** "Which of my positions is losing most": the open positions, worst first. Reads only. */
    suspend fun rankLines(): List<String> = com.optionslab.ira.Exposure.rank(openLegs())

    /**
     * The word before Boss sends an order he opened to review ([com.optionslab.ira.PreTrade]): just after a loss, past
     * his usual day or his own trade goal, in the first five minutes, or against a rule he asked me to remember. Shown on
     * the order review only (never spoken, no amounts); it never blocks, delays or changes the order. Opening orders only:
     * a closing order is never questioned. Null when there is nothing to say (or the switch is off, or IraGoldAlgo).
     */
    suspend fun preTrade(symbols: List<String>, exit: Boolean): String? {
        if (!com.optionslab.app.BuildConfig.JARVIS || com.optionslab.app.BuildConfig.GOLD || exit || !Automations.on(Automations.Auto.PRETRADE)) return null
        return runCatching {
            val live = runCatching { AppSettings.load().live }.getOrDefault(false)
            val owners = runCatching { com.optionslab.app.data.Strategies.owners() }.getOrDefault(emptyMap())
            val own = IraAccount.trips(live, owners).filter { it.owner.startsWith("Manual") }
            val market = symbols.firstNotNullOfOrNull { com.optionslab.ira.PreTrade.marketOf(it) }
            val expiry = market?.let { m -> runCatching { com.optionslab.app.data.Market.isExpiryDay(m.name) }.getOrDefault(false) } ?: false
            val goal = IraGoals.all().filter { it.kind == com.optionslab.ira.Goals.Kind.MAX_TRADES }.minOfOrNull { it.amount.toInt() }
            val r = com.optionslab.ira.PreTrade.reminders(own, LocalDateTime.now(IST), IraTools.memory().map { it.text }, market, expiry, goal)
            com.optionslab.ira.PreTrade.say(r)?.also { IraActivity.add("A word before an order: ${r.size} reminder${if (r.size == 1) "" else "s"}.") }
        }.getOrNull()
    }

    /** The 15:35 spoken wrap-up: the day's P&L, the scorecard's headline, tomorrow's events. */
    suspend fun daySummary(scorecard: String?) {
        if (!com.optionslab.app.BuildConfig.JARVIS) return
        if (!Automations.on(Automations.Auto.SUMMARY)) return
        val text = wrapUp(scorecard, review = true)
        IraHub.note(text)
        // A locked phone may be overheard: the day's figures stay in the chat.
        JarvisVoice.announce(com.optionslab.ira.Overheard.said(com.optionslab.ira.Wake.spoken(text, 10), runCatching { IraHub.locked() }.getOrDefault(true),
            "Boss, the day's wrap-up is in the chat."))
        Automations.acted(Automations.Auto.SUMMARY, text)
    }

    /**
     * The wrap-up's words. [review]: Jarvis's review of himself, which also keeps the day's bar - only at 15:35, never
     * when Boss asks for the wrap-up during the day ("wrap up my day").
     */
    suspend fun wrapUp(scorecard: String?, review: Boolean): String {
        val live = AppSettings.load().live
        val zerodha = live && Broker.loggedIn
        val pnl = runCatching {
            if (zerodha) Broker.positionBook().net.sumOf { it.pnl } else Paper.snapshot().dayPnl
        }.getOrNull()?.let { "${if (zerodha) "Zerodha" else "Paper"} today: ${AppFacts.rs(it)}." }
        val events = runCatching { IraEvents.upcoming(2).map { com.optionslab.ira.Events.line(it, com.optionslab.app.data.Market.today()) } }.getOrDefault(emptyList())
        // How Nifty's day went comes first: the market's story, then Boss's own.
        val story = IraHub.dayStory(com.optionslab.ira.Market.NIFTY)
        // Solo's day right after the market's story (the spoken wrap-up keeps the first sentences).
        // (Jarvis's own review right after the day's figures, so it is within what is spoken.)
        // Jarvis's own plan: what he did and what he carries to tomorrow.
        val agenda = runCatching { IraAgenda.wrapLine() }.getOrNull()
        // What stood out in the market today against its usual, and the offer of the whole story (after Boss's own figures,
        // so it never pushes them out of what is spoken).
        return listOfNotNull(story, com.optionslab.ira.DaySummary.say(pnl, scorecard, events), if (review) selfReview() else null, agenda, IraSolo.daySummary(),
            IraHub.marketWrapLine(),
            runCatching { com.optionslab.ira.Missed.say(IraTools.missedToday()) }.getOrNull()).joinToString(" ")
    }
}
