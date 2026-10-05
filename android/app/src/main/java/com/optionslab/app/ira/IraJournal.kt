package com.optionslab.app.ira

import com.optionslab.app.data.AppSettings
import com.optionslab.app.data.Broker
import com.optionslab.app.data.Paper
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The owner's own trading journal with Jarvis (Jarvis, the owner's wishes 2026-10-03): searching past trades,
 * the best and worst time of day, notes on why a trade was taken, the day's target, trades going nowhere, and news on
 * the positions held.
 */
internal object IraJournal {
    private val IST = ZoneId.of("Asia/Kolkata")

    // ---- trades --------------------------------------------------------------------------------------------------

    /** The round trips in the app's mode (Zerodha in Live, else paper). */
    fun trips(): List<com.optionslab.ira.TradeSearch.Trip> {
        val live = AppSettings.load().live
        return runCatching { com.optionslab.app.data.TradeBook.trips(live) }.getOrDefault(emptyList())
            .map { com.optionslab.ira.TradeSearch.Trip(it.symbol, it.openedAt, it.net) }
    }

    fun marketOf(symbol: String): com.optionslab.ira.Market? = when {
        symbol.startsWith("BANKNIFTY") -> com.optionslab.ira.Market.BANKNIFTY
        symbol.startsWith("FINNIFTY") -> com.optionslab.ira.Market.FINNIFTY
        symbol.startsWith("NIFTY") -> com.optionslab.ira.Market.NIFTY
        symbol.startsWith("SENSEX") -> com.optionslab.ira.Market.SENSEX
        else -> null
    }

    /** Was the trip on its contract's expiry day? */
    private fun isExpiry(t: com.optionslab.ira.TradeSearch.Trip): Boolean {
        val d = t.openedAt.toLocalDate()
        Paper.contractOf(t.symbol)?.let { return it.expiry == d }
        return Broker.cachedInstruments().orEmpty().firstOrNull { it.tradingSymbol == t.symbol }?.expiry == d
    }

    /** "How did my Thursday expiry trades do this month?" */
    fun search(question: String): List<String> {
        val f = com.optionslab.ira.TradeSearch.parse(question, com.optionslab.app.data.Market.today())
        return listOf(com.optionslab.ira.TradeSearch.answer(trips(), f, ::isExpiry, ::marketOf))
    }

    fun timeOfDay(): List<String> = listOf(com.optionslab.ira.TimeOfDay.line(trips()) ?: "Not enough trades yet to tell your best and worst time of day (3 in each hour).")

    // ---- notes ---------------------------------------------------------------------------------------------------

    private const val NOTES = "jarvis.notes"

    private fun notes(): List<Pair<LocalDateTime, String>> = runCatching {
        val a = JSONArray(com.optionslab.app.security.SecurePrefs.getString(NOTES) ?: "[]")
        (0 until a.length()).map { a.getJSONObject(it).let { o -> LocalDateTime.parse(o.getString("t")) to o.getString("n") } }
    }.getOrDefault(emptyList())

    /** A note on why the owner took a trade (secrets hidden first). */
    @Synchronized fun note(text: String): String {
        val n = com.optionslab.ira.Secrets.redact(text).trim().take(300)
        val all = (notes() + (LocalDateTime.now(IST).withNano(0) to n)).takeLast(300)
        com.optionslab.app.security.SecurePrefs.put(NOTES, JSONArray().apply { all.forEach { (t, x) -> put(JSONObject().put("t", t.toString()).put("n", x)) } }.toString())
        IraActivity.add("Noted: $n")
        return "Noted, Boss: \"$n\" (${com.optionslab.ira.TradeReasons.kind(n)}). I'll match it to the trade and tell you in the report card which reasons work."
    }

    /** Each note with the result of the trade it was about: the trip opened nearest the note (within an hour). */
    fun noted(): List<Pair<String, Double>> {
        val t = trips()
        return notes().mapNotNull { (at, n) ->
            t.filter { kotlin.math.abs(java.time.Duration.between(it.openedAt, at).toMinutes()) <= 60 }
                .minByOrNull { kotlin.math.abs(java.time.Duration.between(it.openedAt, at).toMinutes()) }?.let { n to it.net }
        }
    }

    fun reasons(): List<String> = com.optionslab.ira.TradeReasons.lines(noted()).ifEmpty {
        listOf("Not enough noted trades yet (3 for a reason). Say \"Jarvis, note: I bought because...\" when you take one.") }

    // ---- the day's target ----------------------------------------------------------------------------------------

    private const val TARGET = "jarvis.target"

    /** Today's target, or null (a target is for the day it was set). */
    fun target(): Double? = runCatching {
        val v = com.optionslab.app.security.SecurePrefs.getString(TARGET) ?: return@runCatching null
        if (v.substringBefore('|') != com.optionslab.app.data.Market.today().toString()) null else v.substringAfter('|').toDouble()
    }.getOrNull()

    fun setTarget(v: Double?) {
        com.optionslab.app.security.SecurePrefs.put(TARGET, v?.let { "${com.optionslab.app.data.Market.today()}|$it" })
    }

    private suspend fun pnlNow(): Double? = runCatching {
        if (AppSettings.load().live && Broker.loggedIn) null else Paper.snapshot().dayPnl
    }.getOrNull()

    /** The day's target reached: told once, with the advice to protect the gains and stop. */
    suspend fun targetWatch() {
        if (!com.optionslab.app.BuildConfig.JARVIS || !Automations.on(Automations.Auto.TARGET)) return
        val t = target() ?: return
        val pnl = (if (AppSettings.load().live && Broker.loggedIn) runCatching { Broker.passPositionBook().net.sumOf { it.pnl } }.getOrNull() else pnlNow()) ?: return
        if (!com.optionslab.ira.DayTarget.reached(pnl, t)) return
        val key = "jarvis.target.told"
        if (com.optionslab.app.security.SecurePrefs.getString(key) == com.optionslab.app.data.Market.today().toString()) return
        com.optionslab.app.security.SecurePrefs.put(key, com.optionslab.app.data.Market.today().toString())
        val text = com.optionslab.ira.DayTarget.say(pnl, t)
        // A locked phone may be overheard or seen: the amount stays in the chat.
        val locked = runCatching { IraHub.locked() }.getOrDefault(true)
        val plain = "Boss, you've reached your day's target. The figures are in the chat."
        IraHub.appContext()?.let { JarvisPopup.show(it, "Boss, target reached", if (locked) plain else text) }
        IraHub.note(text); JarvisVoice.announce(if (locked) plain else text); Automations.acted(Automations.Auto.TARGET, text)
    }

    // ---- trades going nowhere ------------------------------------------------------------------------------------

    private val firstSeen = HashMap<String, LocalDateTime>()
    private val staleAsked = HashSet<String>()

    /**
     * A position of the owner's own (not a bot's) open 45 minutes and within 5% of what was paid: closing is offered,
     * asked yes or no, never done alone. Once per position a day.
     */
    suspend fun staleWatch() {
        if (!com.optionslab.app.BuildConfig.JARVIS || !Automations.on(Automations.Auto.STALE) || !com.optionslab.app.data.Market.isOpen()) return
        val now = LocalDateTime.now(IST)
        val bots = IraCoach.botSymbols()
        val open = ArrayList<Triple<String, Boolean, Pair<Double, Double>>>()   // symbol, live, (avg, ltp)
        runCatching { Paper.snapshot().positions.positions.filter { it.quantity > 0 }.forEach { open += Triple(it.symbol, false, it.averagePrice to it.ltp) } }
        if (Broker.loggedIn) runCatching { Broker.passPositionBook().net.filter { it.qty > 0 }.forEach { open += Triple(it.symbol, true, it.avg to it.last) } }
        val keys = open.map { (s, l, _) -> (if (l) "L:" else "P:") + s }.toSet()
        synchronized(firstSeen) { firstSeen.keys.retainAll(keys) }
        val day = com.optionslab.app.data.Market.today().toString()
        for ((symbol, live, px) in open) {
            if (symbol in bots) continue
            val k = (if (live) "L:" else "P:") + symbol
            val since = synchronized(firstSeen) { firstSeen.getOrPut(k) { now } }
            if (!com.optionslab.ira.StaleTrade.stale(since, now, px.first, px.second)) continue
            if (!synchronized(staleAsked) { staleAsked.add("$day|$k") }) continue
            val minutes = java.time.Duration.between(since, now).toMinutes()
            val text = com.optionslab.ira.StaleTrade.say(symbol, minutes, px.first, px.second)
            IraHub.offerClose(symbol, live, text)
            Automations.acted(Automations.Auto.STALE, text)
        }
    }

    // ---- news on the positions held ------------------------------------------------------------------------------

    /** The positions held now, by index and side (bots' too: news is about the money at risk). */
    suspend fun held(): List<com.optionslab.ira.PositionNews.Held> {
        val out = ArrayList<com.optionslab.ira.PositionNews.Held>()
        runCatching { Paper.snapshot().positions.positions.filter { it.quantity != 0 }.forEach { p ->
            val c = Paper.contractOf(p.symbol) ?: return@forEach
            val m = marketOf(c.underlying) ?: return@forEach
            out += com.optionslab.ira.PositionNews.Held(p.symbol, m, c.right == com.optionslab.engine.Right.CE, p.quantity > 0)
        } }
        if (Broker.loggedIn) runCatching {
            val ins = Broker.cachedInstruments().orEmpty().associateBy { it.tradingSymbol }
            Broker.passPositionBook().net.filter { it.open }.forEach { p ->
                val i = ins[p.symbol] ?: return@forEach
                val m = marketOf(i.name) ?: return@forEach
                out += com.optionslab.ira.PositionNews.Held(p.symbol, m, i.right == com.optionslab.engine.Right.CE, p.qty > 0)
            }
        }
        return out
    }

    private val newsTold = HashSet<String>()

    /** A headline on an index the owner holds: good or bad for the side held, told at once (once per headline). */
    suspend fun positionNews(h: com.optionslab.ira.Headline) {
        if (!com.optionslab.app.BuildConfig.JARVIS || !Automations.on(Automations.Auto.POSNEWS)) return
        if (!synchronized(newsTold) { newsTold.add(h.title) }) return
        val text = com.optionslab.ira.PositionNews.say(h.title, h.tone, held(), h.markets) ?: return
        IraHub.appContext()?.let { JarvisPopup.show(it, "Boss, news on your position", text) }
        IraHub.note(text); JarvisVoice.announce(com.optionslab.ira.Wake.spoken(text, 3)); Automations.acted(Automations.Auto.POSNEWS, text)
    }
}
