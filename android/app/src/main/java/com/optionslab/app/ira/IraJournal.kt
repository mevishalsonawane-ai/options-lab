package com.optionslab.app.ira

import com.optionslab.app.data.AppSettings
import com.optionslab.app.data.Broker
import com.optionslab.app.data.Paper
import kotlinx.coroutines.async
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

    // ---- trade replay --------------------------------------------------------------------------------------------

    /** [symbol]'s minute candles from the start of [day] (India time), or none when the feed has none (an expired contract). */
    private suspend fun minutes(symbol: String, day: java.time.LocalDate): List<com.optionslab.ira.Candle> = runCatching {
        kotlinx.coroutines.withTimeoutOrNull(20_000) {
            com.optionslab.app.data.ChartFeed.bars(symbol, "1m", day.atStartOfDay(IST).toEpochSecond(), null)
        }.orEmpty().map { b ->
            com.optionslab.ira.Candle(java.time.Instant.ofEpochSecond(b.epochSecond).atZone(IST).toLocalDateTime(), b.open, b.high, b.low, b.close)
        }
    }.getOrDefault(emptyList())

    /**
     * Each of [trips] against its contract's own minute candles and its index's (read only: candles from the chart
     * feed, nothing placed or changed). Each contract and index is read once, all at the same time.
     */
    suspend fun replays(trips: List<com.optionslab.engine.RoundTrips.Trip>, owners: Map<String, String>): List<com.optionslab.ira.TradeReplay.Replay> =
        kotlinx.coroutines.coroutineScope {
            val now = LocalDateTime.now(IST)
            val wanted = LinkedHashSet<Pair<String, java.time.LocalDate>>()
            trips.forEach { t ->
                wanted += t.symbol to t.openedAt.toLocalDate()
                marketOf(t.symbol)?.let { m -> IraHub.LIVE[m]?.let { s -> wanted += s to t.openedAt.toLocalDate() } }
            }
            val got = wanted.map { k -> async { k to minutes(k.first, k.second) } }.map { it.await() }.toMap()
            trips.map { t ->
                val d = t.openedAt.toLocalDate()
                val m = marketOf(t.symbol)
                val ix = m?.let { IraHub.LIVE[it] }?.let { got[it to d] }?.takeIf { it.isNotEmpty() }
                com.optionslab.ira.TradeReplay.of(
                    com.optionslab.ira.TradeReplay.Trip(t.symbol, t.direction, t.qty, t.entry, t.exit, t.openedAt, t.closedAt, t.net,
                        com.optionslab.app.data.TradeBook.ownerOf(t, owners)),
                    got[t.symbol to d].orEmpty(), ix, m, now)
            }
        }

    /** "How was my last trade?", "go over my trades today": the app's mode, the last trade in full or today's a line each. */
    suspend fun replay(question: String): List<String> {
        val scope = com.optionslab.ira.TradeReplay.asked(question) ?: com.optionslab.ira.TradeReplay.Scope.LAST
        val live = AppSettings.load().live
        val owners = runCatching { com.optionslab.app.data.Strategies.owners() }.getOrDefault(emptyMap())
        val all = runCatching { com.optionslab.app.data.TradeBook.trips(live) }.getOrDefault(emptyList())
        val today = com.optionslab.app.data.Market.today()
        val picked = if (scope == com.optionslab.ira.TradeReplay.Scope.LAST) listOfNotNull(all.maxByOrNull { it.closedAt })
            else all.filter { it.day == today }.sortedBy { it.closedAt }.takeLast(MAX_REPLAYED)
        return com.optionslab.ira.TradeReplay.answer(if (live) "Zerodha" else "Paper", scope, replays(picked, owners))
    }

    /** The journal's replay of a day's closed trades: a line each, then what the exits show. */
    suspend fun replayLines(trips: List<com.optionslab.engine.RoundTrips.Trip>, owners: Map<String, String>): List<String> {
        val r = replays(trips.sortedBy { it.closedAt }.takeLast(MAX_REPLAYED), owners)
        return r.map { com.optionslab.ira.TradeReplay.short(it) } + com.optionslab.ira.TradeReplay.exits(r)
    }

    /** At most this many trades are replayed at once (each reads its contract's candles). */
    private const val MAX_REPLAYED = 12

    fun timeOfDay(): List<String> = listOf(com.optionslab.ira.TimeOfDay.line(trips()) ?: "Not enough trades yet to tell your best and worst time of day (3 in each hour).")

    // ---- notes ---------------------------------------------------------------------------------------------------

    private const val NOTES = "jarvis.notes"

    /** Boss's notes on why he took a trade, with when (the month's review matches them to the trades). */
    fun notes(): List<Pair<LocalDateTime, String>> = runCatching {
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
        if (AppSettings.load().live && Broker.loggedIn) null else Paper.snapshot(Paper.SHARED_QUOTE_MS).dayPnl
    }.getOrNull()

    /** The day's target reached: told once, with the advice to protect the gains and stop. */
    suspend fun targetWatch() {
        if (!com.optionslab.app.BuildConfig.JARVIS || !Automations.on(Automations.Auto.TARGET)) return
        val t = target() ?: return
        // Told already today (every pass after): the P&L is not read.
        val key = "jarvis.target.told"
        if (com.optionslab.app.security.SecurePrefs.getString(key) == com.optionslab.app.data.Market.today().toString()) return
        val pnl = (if (AppSettings.load().live && Broker.loggedIn) runCatching { Broker.passPositionBook().net.sumOf { it.pnl } }.getOrNull() else pnlNow()) ?: return
        if (!com.optionslab.ira.DayTarget.reached(pnl, t)) return
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
        val open = ArrayList<Triple<String, Boolean, Pair<Double, Double>>>()   // symbol, live, (avg, ltp)
        runCatching { Paper.snapshot(Paper.SHARED_QUOTE_MS).positions.positions.filter { it.quantity > 0 }.forEach { open += Triple(it.symbol, false, it.averagePrice to it.ltp) } }
        if (Broker.loggedIn) runCatching { Broker.passPositionBook().net.filter { it.qty > 0 }.forEach { open += Triple(it.symbol, true, it.avg to it.last) } }
        val keys = open.map { (s, l, _) -> (if (l) "L:" else "P:") + s }.toSet()
        synchronized(firstSeen) { firstSeen.keys.retainAll(keys) }
        // Nothing open (most passes): the bots' holdings are not read.
        if (open.isEmpty()) return
        val bots = IraCoach.botSymbols()
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
        runCatching { Paper.snapshot(Paper.SHARED_QUOTE_MS).positions.positions.filter { it.quantity != 0 }.forEach { p ->
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

    /** Headlines already told against a position, the newest 1,000 only (the app can run for days). */
    private val newsTold = LinkedHashSet<String>()

    /** A headline on an index the owner holds: good or bad for the side held, told at once (once per headline). */
    suspend fun positionNews(h: com.optionslab.ira.Headline) {
        if (!com.optionslab.app.BuildConfig.JARVIS || !Automations.on(Automations.Auto.POSNEWS)) return
        if (!synchronized(newsTold) { newsTold.add(h.title).also { com.optionslab.ira.Upkeep.trimOldest(newsTold, 1_000) } }) return
        val text = com.optionslab.ira.PositionNews.say(h.title, h.tone, held(), h.markets) ?: return
        IraHub.appContext()?.let { JarvisPopup.show(it, "Boss, news on your position", text) }
        IraHub.note(text); JarvisVoice.announce(com.optionslab.ira.Wake.spoken(text, 3)); Automations.acted(Automations.Auto.POSNEWS, text)
    }
}
