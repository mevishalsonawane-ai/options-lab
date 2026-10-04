package com.optionslab.app.ira

import com.optionslab.app.data.AppSettings
import com.optionslab.app.data.Paper
import com.optionslab.engine.Right
import com.optionslab.engine.orb.LiquidityRules
import com.optionslab.engine.orb.OrbRules
import com.optionslab.engine.orb.ProfitLock
import com.optionslab.ira.NewsTrade
import com.optionslab.ira.JarvisTrades
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.sync.withLock

/**
 * News trades (Jarvis): an idea from [NewsTrade] becomes a trade only on the owner's Approve. The option is picked as
 * the Liquidity 15+5 arm picks it (ATM on the index's strike step, the next expiry after today, 1 lot, a market buy);
 * it gets the arm's resting stop 15% below the price paid, a target of the ORB arms' +40 premium points, and the owner's
 * profit-lock ladder ([ProfitLock]) moves the stop up as the option gains - all through the app's own protections.
 * Every trade is recorded with its result, so news trades can be judged on their own record.
 */
internal object IraNewsTrades {
    const val TARGET_POINTS = OrbRules.TARGET_POINTS
    private const val KEY = "jarvis.newstrades"

    /** One news trade: the contract, entry, the best price seen and the stop in force; [closed] once out. */
    data class Pos(val symbol: String, val live: Boolean, val entry: Double, val qty: Int, val peak: Double, val stop: Double?,
                   val headline: String, val day: String, val closed: Boolean = false, val result: Double? = null,
                   /** For "why did it lose": the index, side and minute at entry, and the index and minute at the exit. */
                   val underlying: String? = null, val call: Boolean? = null, val spotIn: Double? = null, val minuteIn: Int? = null,
                   val spotOut: Double? = null, val minuteOut: Int? = null,
                   /** How sure Jarvis was (1-5) when it was suggested, for the bar it sets itself ([com.optionslab.ira.ActAlone.bar]). */
                   val stars: Int? = null)

    /** Every news / pattern trade; an entry that does not read back is skipped, never the whole record. */
    fun all(): List<Pos> = runCatching {
        val a = JSONArray(com.optionslab.app.security.SecurePrefs.getString(KEY) ?: "[]")
        (0 until a.length()).mapNotNull { i -> runCatching { a.getJSONObject(i).let { o ->
            Pos(o.getString("s"), o.getBoolean("l"), o.getDouble("e"), o.getInt("q"), o.getDouble("p"), if (o.has("st")) o.getDouble("st") else null,
                o.getString("h"), o.getString("d"), o.optBoolean("c"), if (o.has("r")) o.getDouble("r") else null,
                o.optString("u").ifEmpty { null }, if (o.has("cl")) o.getBoolean("cl") else null, if (o.has("si")) o.getDouble("si") else null,
                if (o.has("mi")) o.getInt("mi") else null, if (o.has("so")) o.getDouble("so") else null, if (o.has("mo")) o.getInt("mo") else null,
                if (o.has("cf")) o.getInt("cf") else null)
        } }.getOrNull() }
    }.getOrDefault(emptyList())

    /** One writer at a time: [place] and [tick] read, change and save the same record. */
    private val lock = kotlinx.coroutines.sync.Mutex()

    private fun save(list: List<Pos>) = runCatching {
        com.optionslab.app.security.SecurePrefs.put(KEY, JSONArray().apply { list.takeLast(200).forEach { p ->
            put(JSONObject().put("s", p.symbol).put("l", p.live).put("e", p.entry).put("q", p.qty).put("p", p.peak)
                .apply { p.stop?.let { put("st", it) }; p.result?.let { put("r", it) }; p.underlying?.let { put("u", it) }; p.call?.let { put("cl", it) }
                    p.spotIn?.let { put("si", it) }; p.minuteIn?.let { put("mi", it) }; p.spotOut?.let { put("so", it) }; p.minuteOut?.let { put("mo", it) }; p.stars?.let { put("cf", it) } }.put("h", p.headline).put("d", p.day).put("c", p.closed)) } }.toString())
    }

    fun today(): Int { val d = com.optionslab.app.data.Market.today().toString(); return all().count { it.day == d } }

    // ---- the owner's rules for Jarvis's own trades --------------------------------------------------------------------

    /**
     * Boss's "keep your trades on paper" (even in Live). Off by default (Boss, 4 Oct: "everything should also work on
     * real, with my approval first"): in Live, once their paper record is proven ([JarvisTrades.proven]), Jarvis's
     * trades go to Zerodha - each only after his yes with the fingerprint. (A new key: the old default was on.)
     */
    var paperFirst: Boolean
        get() = runCatching {
            val p = com.optionslab.app.security.SecurePrefs
            // A choice Boss made before ("keep your trades on paper" or "let them go live") is kept; only the default changed.
            val old = "jarvis.trades.paper"
            val oldSet = p.getBoolean(old, true) == p.getBoolean(old, false)
            p.getBoolean("jarvis.trades.paper.v2", if (oldSet) p.getBoolean(old, true) else false)
        }.getOrDefault(true)
        set(v) { runCatching { com.optionslab.app.security.SecurePrefs.put("jarvis.trades.paper.v2", v) } }

    const val DEFAULT_LIMIT = 3_000.0
    /** Rupees Jarvis's trades may lose in a day before it suggests no more (its own limit, apart from the app's). */
    var dailyLimit: Double
        get() = runCatching { com.optionslab.app.security.SecurePrefs.getString("jarvis.trades.limit")?.toDouble() }.getOrNull() ?: DEFAULT_LIMIT
        set(v) { runCatching { com.optionslab.app.security.SecurePrefs.put("jarvis.trades.limit", v.toString()) } }

    /** Rupees one Jarvis trade may risk (its 15% stop), or null for 1 lot; used only once the paper record is proven. */
    var riskPerTrade: Double?
        get() = runCatching { com.optionslab.app.security.SecurePrefs.getString("jarvis.trades.risk")?.toDouble() }.getOrNull()
        set(v) { runCatching { com.optionslab.app.security.SecurePrefs.put("jarvis.trades.risk", v?.toString()) } }

    /** Lots for a trade at [premium]: by the owner's rupee risk once proven, else 1. */
    fun lotsFor(premium: Double, lotSize: Int): Int {
        if (JarvisTrades.proven(closedRecord()) != null) return 1
        return com.optionslab.ira.RiskSizing.lots(riskPerTrade, premium, lotSize, maxOf(1, AppSettings.load().guardMaxLots))
    }

    /**
     * Is the option cheap or dear now? The ATM option Jarvis would buy, its implied volatility now, and where that
     * stands in the last year's (from the nightly study): (rank 0..1, iv), or null when it cannot tell.
     */
    suspend fun ivNow(idea: NewsTrade.Idea, spot: Double): Pair<Double, Double>? {
        val u = idea.market.name
        val c = contract(u, spot, idea.call) ?: return null
        val px = runCatching { Paper.lastPrice(c) }.getOrNull()?.takeIf { it > 0 } ?: return null
        val now = java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Kolkata"))
        val days = java.time.temporal.ChronoUnit.DAYS.between(now.toLocalDate(), c.expiry).toDouble() +
            maxOf(0.0, (15.5 * 60 - (now.hour * 60 + now.minute)) / (24 * 60))
        val iv = com.optionslab.ira.IvRank.now(px, idea.call, spot, c.strike, maxOf(0.05, days)) ?: return null
        val rank = com.optionslab.ira.IvRank.rank(IraStudy.ivHistory(u), iv, now.toLocalDate()) ?: return null
        return rank to iv
    }

    /** Today's closed Jarvis trades have lost [dailyLimit] or more. */
    fun lossLimitHit(): Boolean {
        val d = com.optionslab.app.data.Market.today().toString()
        return all().filter { it.day == d && it.closed }.sumOf { it.result ?: 0.0 } <= -dailyLimit
    }

    fun closedRecord(): List<JarvisTrades.Closed> = all().filter { it.closed && it.result != null }
        .map { JarvisTrades.Closed(java.time.LocalDate.parse(it.day), it.result!!, it.live) }

    // ---- every suggestion, for the evening scorecard ------------------------------------------------------------------

    private const val SKEY = "jarvis.suggestions"

    private fun suggestions(): List<Pair<Long, JarvisTrades.Suggestion>> = runCatching {
        val a = JSONArray(com.optionslab.app.security.SecurePrefs.getString(SKEY) ?: "[]")
        (0 until a.length()).map { a.getJSONObject(it) }.mapNotNull { o -> runCatching {
            o.getLong("id") to JarvisTrades.Suggestion(java.time.LocalDateTime.parse(o.getString("at")), com.optionslab.ira.Market.valueOf(o.getString("m")),
                o.getBoolean("c"), o.getDouble("s"), o.getString("src"), o.getString("a"), if (o.has("p")) o.getDouble("p") else null, if (o.has("l")) o.getInt("l") else null)
        }.getOrNull() }
    }.getOrDefault(emptyList())

    private fun saveSuggestions(l: List<Pair<Long, JarvisTrades.Suggestion>>) = runCatching {
        com.optionslab.app.security.SecurePrefs.put(SKEY, JSONArray().apply { l.takeLast(300).forEach { (id, x) ->
            put(JSONObject().put("id", id).put("at", x.at.toString()).put("m", x.market.name).put("c", x.call).put("s", x.spot).put("src", x.source).put("a", x.answer)
                .apply { x.points?.let { put("p", it) }; x.lot?.let { put("l", it) } }) } }.toString())
    }

    @Synchronized fun suggested(id: Long, idea: NewsTrade.Idea, spot: Double, source: String) =
        saveSuggestions(suggestions() + (id to JarvisTrades.Suggestion(java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Kolkata")), idea.market, idea.call, spot, source, "waiting")))

    @Synchronized fun answered(id: Long, answer: String) =
        saveSuggestions(suggestions().map { (i, x) -> if (i == id && x.answer == "waiting") i to x.copy(answer = answer) else i to x })

    /**
     * The evening scorecard (after the day's option prices are kept): each of today's suggestions played on the real
     * option prices - what it made, or would have made - and whether the owner's answer was the better choice.
     */
    @Synchronized fun scorecard(day: java.time.LocalDate = com.optionslab.app.data.Market.today()): List<String> {
        val l = suggestions()
        val sessions = HashMap<String, com.optionslab.engine.Session?>()
        val out = l.map { (i, x) ->
            if (x.at.toLocalDate() != day || x.points != null) return@map i to x
            val u = x.market.name
            val sess = sessions.getOrPut(u) { runCatching { com.optionslab.app.data.Store.barSession(u, day) }.getOrNull() } ?: return@map i to x
            val p = runCatching { JarvisTrades.OptionSim.trade(sess, x.at, x.call, x.spot, JarvisTrades.strikeStep(u)) }.getOrNull()
            i to x.copy(points = p, lot = sess.lotHint ?: sess.options.firstOrNull()?.lot, answer = if (x.answer == "waiting") "lapsed" else x.answer)
        }
        saveSuggestions(out)
        return JarvisTrades.scorecard(day, out.map { it.second })
    }

    /** The record so far: trades, wins, net (for "how are news trades doing"). */
    /** (confidence, rupees) of each closed trade that knew its confidence. */
    fun byConfidence(): List<Pair<Int, Double>> = all().filter { it.closed && it.result != null && it.stars != null }.map { it.stars!! to it.result!! }

    /** (entry minute of day, rupees) of each closed trade that knew its minute. */
    fun byMinute(): List<Pair<Int, Double>> = all().filter { it.closed && it.result != null && it.minuteIn != null }.map { it.minuteIn!! to it.result!! }

    /** (kind of idea, rupees) of each closed trade. */
    fun byKind(): List<Pair<String, Double>> = all().filter { it.closed && it.result != null }.map { com.optionslab.ira.Preference.kind(it.headline) to it.result!! }

    /** The confidence a trade needs before Jarvis takes it on paper by himself (raised by his own losses). */
    fun actAloneBar(): Int = com.optionslab.ira.ActAlone.bar(byConfidence())

    fun record(): String {
        val c = all().filter { it.closed && it.result != null }
        val where = if (paperFirst) "on paper until proven" else "in the app's mode"
        val limit = " Daily loss limit for my trades: ${com.optionslab.ira.AppFacts.rs(dailyLimit).removePrefix("+")}" + if (lossLimitHit()) ", hit today." else "."
        if (c.isEmpty()) return "No trades of mine closed yet (they go $where).$limit"
        fun line(name: String, l: List<Pos>) = if (l.isEmpty()) null else "$name ${l.size} closed, ${l.count { it.result!! > 0 }} won, net ${com.optionslab.ira.AppFacts.rs(l.sumOf { it.result!! })}"
        return "My trades (they go $where): " + listOfNotNull(line("news", c.filter { !it.headline.startsWith("pattern:") }), line("patterns", c.filter { it.headline.startsWith("pattern:") }))
            .joinToString("; ") + "." + (JarvisTrades.proven(closedRecord())?.let { " $it" } ?: " The paper record has earned live trading.") +
            (com.optionslab.ira.ActAlone.say(byConfidence())?.let { " $it" } ?: "") + limit
    }

    /** The option as Liquidity 15+5 picks it for [u] at [spot]: ATM on the strike step, next expiry after today. */
    internal fun contract(u: String, spot: Double, call: Boolean): Paper.Contract? {
        val step = JarvisTrades.strikeStep(u)
        val strike = OrbRules.atmStrike(spot, step)
        val listed = com.optionslab.app.data.Market.contracts().filter { it.underlying == u }.map { it.expiry }.distinct()
        val expiry = OrbRules.expiryAfter(com.optionslab.app.data.Market.today(), listed) ?: return null
        return Paper.contractFor(u, expiry, strike.toDouble(), if (call) Right.CE else Right.PE)
    }

    /**
     * The owner approved [idea]: place it (Paper, or Zerodha through the app's order review), stop, target, record.
     * [paperOnly]: Jarvis took it by itself - it goes on paper or nowhere, whatever changed since it was checked.
     */
    suspend fun place(idea: NewsTrade.Idea, spot: Double, headline: String, paperOnly: Boolean = false, solo: Boolean = false,
                      liveApproved: Boolean = false, stars: Int? = null): String =
        lock.withLock { placeLocked(idea, spot, headline, paperOnly, solo, liveApproved, stars) }

    /**
     * [solo]: Solo's setup (its own record decides live). [liveApproved]: Boss approved with the fingerprint (or the
     * phone has none): without it, a trade that would now reach Zerodha is not placed at all.
     */
    private suspend fun placeLocked(idea: NewsTrade.Idea, spot: Double, headline: String, paperOnly: Boolean, solo: Boolean,
                                    liveApproved: Boolean, stars: Int?): String {
        IraAccount.invalidate()
        val u = idea.market.name
        val c = contract(u, spot, idea.call) ?: return "No ${u} option is listed for the next expiry."
        val s = AppSettings.load()
        val day = com.optionslab.app.data.Market.today().toString()
        val quote = runCatching { Paper.quote(c) }.getOrNull()
        val premium = quote?.ltp ?: 0.0
        // A thin strike (a wide gap between buyers and sellers, or hardly traded) is not bought: the fill and the exit would be poor.
        quote?.let { q -> com.optionslab.ira.StrikeLiquidity.problem(q.bid, q.ask, q.volume, c.lotSize) }?.let { return "$it Not placed." }
        val lots = lotsFor(premium, c.lotSize)
        val nowMin = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Kolkata")).let { it.hour * 60 + it.minute }
        if (lots < 1) return "Your risk per trade is smaller than one lot's stop risk (about ${com.optionslab.ira.AppFacts.rs(premium * 0.15 * c.lotSize).removePrefix("+")}): not placed."
        // Paper first: until their own record is proven (checked again now), Jarvis's trades go on paper even in Live mode.
        if (!s.live || !earned(solo)) {
            val r = Paper.place(c, "BUY", lots, "MARKET", "MIS", null, null, quote)
            val fill = r.events.filterIsInstance<com.optionslab.engine.sandbox.SandboxEvent.Fill>().firstOrNull()
                ?: return "Paper: ${r.message}"
            r.orderId?.let { com.optionslab.app.data.Strategies.tagOwner("paper:$it", "Jarvis news · entry") }
            val stop = LiquidityRules.stopTrigger(fill.price)
            val prot = com.optionslab.app.data.Protections.protectPaper(c.symbol, "MIS", fill.quantity, fill.price, stop, null, fill.price + TARGET_POINTS)
            val guarded = prot.startsWith("Protected")
            save(all() + Pos(c.symbol, false, fill.price, fill.quantity, fill.price, if (guarded) stop else null, headline, day,
                underlying = u, call = idea.call, spotIn = spot, minuteIn = nowMin, stars = stars))
            val oid = com.optionslab.app.data.Origins.shortId(r.orderId)?.let { " Order $it." } ?: ""
            IraActivity.add("Bought ${c.symbol} on paper at ${"%.2f".format(fill.price)} ($headline).$oid")
            IraHub.appContext()?.let { com.optionslab.app.work.Notifier.orderFilled(it, "BUY", fill.quantity, c.symbol, fill.price, "Paper", "Jarvis news · entry", r.orderId) }
            if (!guarded) unguarded(c.symbol, prot)
            return "Bought ${c.symbol} at ${"%.2f".format(fill.price)} on paper${if (s.live) " (my trades stay on paper until proven)" else ""}.$oid $prot" +
                if (guarded) " Profit lock on." else ""
        }
        // Never real money without Boss's yes: a trade Jarvis took by itself that would now reach Zerodha is dropped.
        if (paperOnly) return "Not placed: it would now go to Zerodha, and I only act by myself on paper. Ask me if you want it."
        if (!liveApproved) return "Not placed: it would now go to Zerodha with real money, which needs your fingerprint. Ask me again and approve it there."
        val t = IraOrders.Ticket(u, c.expiry, c.strike, c.right, lots, true, c.lotSize)
        val sent = IraActions.trade(t, live = true)
        if (!sent.startsWith("Sent to Zerodha")) return sent
        val avg = Regex("COMPLETE \\d+ at ([\\d.]+)").find(sent)?.groupValues?.get(1)?.toDoubleOrNull()
            ?: return "$sent Set its stop from the position: the fill price was not confirmed yet."
        val stop = LiquidityRules.stopTrigger(avg)
        // Zerodha names the contract its own way: protections, quotes and the trade book use its trading symbol.
        val zs = runCatching { com.optionslab.app.data.Broker.find(com.optionslab.app.data.Broker.instruments(), u, c.expiry, c.strike, c.right)?.tradingSymbol }
            .getOrNull() ?: return "$sent But I could not find its Zerodha symbol to set the stop: set it from the position now."
        val prot = com.optionslab.app.data.Protections.protectLive(zs, "NFO", s.orderProduct, c.lotSize * lots, avg, stop, null, avg + TARGET_POINTS)
        val guarded = prot.startsWith("Protected")
        save(all() + Pos(zs, true, avg, c.lotSize * lots, avg, if (guarded) stop else null, headline, day,
            underlying = u, call = idea.call, spotIn = spot, minuteIn = nowMin, stars = stars))
        IraActivity.add("Bought $zs on Zerodha at ${"%.2f".format(avg)} ($headline).")
        if (!guarded) unguarded(zs, prot)
        return "$sent $prot" + if (guarded) " Profit lock on." else ""
    }

    /** The stop could not be set: the owner is told at once (the position is still tracked). */
    private fun unguarded(symbol: String, why: String) {
        IraHub.appContext()?.let { JarvisPopup.show(it, "Boss, set a stop on $symbol", why) }
        IraHub.note("$symbol has no stop: $why Set one from the position.")
    }

    /**
     * Every market-watch tick: each open news trade's best price, its profit-lock stop moved up a rung when earned (the
     * protection re-set at the new stop while the price is above it; at or below it the trade is closed now), and a
     * trade the position book shows closed is recorded with its result (retried until the trade book has it).
     */
    suspend fun tick() = lock.withLock {
        val list = all()
        if (list.none { !it.closed || (it.result == null && recent(it)) }) return@withLock
        val paperNet = runCatching { Paper.state.positions }.getOrNull()
        val liveNet = if (list.any { it.live && !it.closed }) runCatching { com.optionslab.app.data.Broker.positionBook().net }.getOrNull() else null
        if (list.any { it.live && it.closed && it.result == null && recent(it) })
            runCatching { com.optionslab.app.data.TradeBook.recordLive(com.optionslab.app.data.Broker.trades()) }
        val out = list.map { p ->
            if (p.closed) {
                // Closed before its trip reached the trade book: look again for a few days.
                if (p.result != null || !recent(p)) return@map p
                return@map p.copy(result = tripOf(p))
            }
            // Out or not: from the position book (never from the protections, which can fail or be removed by hand).
            val net = if (p.live) liveNet?.filter { it.symbol == p.symbol }?.sumOf { it.qty } ?: return@map p
                else paperNet?.filter { it.symbol == p.symbol }?.sumOf { it.quantity } ?: return@map p
            if (net == 0) {
                val r = tripOf(p)
                IraHub.appContext()?.let { JarvisPopup.show(it, "News trade closed: ${p.symbol}", r?.let { x -> "Result ${com.optionslab.ira.AppFacts.rs(x)}. " }.orEmpty() + record()) }
                IraActivity.add("${p.symbol} closed" + (r?.let { x -> ", ${com.optionslab.ira.AppFacts.rs(x)}" } ?: "") + ".")
                return@map p.copy(closed = true, result = r, spotOut = p.underlying?.let { spotOf(it) },
                    minuteOut = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Kolkata")).let { it.hour * 60 + it.minute })
            }
            val ltp = runCatching { if (p.live) com.optionslab.app.data.Broker.quotes(listOf("NFO:${p.symbol}"))["NFO:${p.symbol}"]?.last
                else Paper.contractOf(p.symbol)?.let { Paper.lastPrice(it) } }.getOrNull() ?: return@map p
            val peak = maxOf(p.peak, ltp)
            val lock = ProfitLock.level(p.entry, TARGET_POINTS, peak)
            var stop = p.stop
            if (lock != null && (stop == null || lock > stop + 0.01)) {
                if (ltp > lock) {
                    // Re-set at the new rung, priced from now (a stop must sit below the current price).
                    val r = if (p.live) com.optionslab.app.data.Protections.protectLive(p.symbol, "NFO", AppSettings.load().orderProduct, p.qty, ltp, lock, null, p.entry + TARGET_POINTS)
                        else com.optionslab.app.data.Protections.protectPaper(p.symbol, "MIS", p.qty, ltp, lock, null, p.entry + TARGET_POINTS)
                    if (r.startsWith("Protected")) stop = lock else unguarded(p.symbol, r)
                } else {
                    // Already at or under the locked profit: out now.
                    val said = closeNow(p)
                    IraHub.note("${p.symbol}: back to the profit lock, so I closed it. $said")
                }
            }
            p.copy(peak = peak, stop = stop)
        }
        save(out)
        Unit
    }

    /** Answered suggestions since the last preferences reset: (kind, approved). */
    fun answers(): List<Pair<String, Boolean>> {
        val since = runCatching { com.optionslab.app.security.SecurePrefs.getString("jarvis.pref.reset")?.let(java.time.LocalDateTime::parse) }.getOrNull()
        return suggestions().map { it.second }.filter { (it.answer == "approved" || it.answer == "rejected") && (since == null || it.at.isAfter(since)) }
            .map { com.optionslab.ira.Preference.kind(it.source) to (it.answer == "approved") }
    }

    /** True the first time a kind is skipped (so it is said once). */
    @Synchronized fun toldSkip(kind: String): Boolean {
        val k = "jarvis.pref.told"
        val told = com.optionslab.app.security.SecurePrefs.getString(k).orEmpty().split('\n').filter { it.isNotBlank() }.toSet()
        if (kind in told) return false
        com.optionslab.app.security.SecurePrefs.put(k, (told + kind).joinToString("\n"))
        return true
    }

    /** "Reset my preferences": every kind is offered again. */
    fun resetPreferences() {
        com.optionslab.app.security.SecurePrefs.put("jarvis.pref.reset", java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Kolkata")).toString())
        com.optionslab.app.security.SecurePrefs.put("jarvis.pref.told", null)
    }

    /** "If the stop hits you lose about Rs X (Y% of your capital)" for the trade Jarvis would place. */
    suspend fun riskLine(idea: NewsTrade.Idea, spot: Double): String? {
        val c = contract(idea.market.name, spot, idea.call) ?: return null
        val px = runCatching { Paper.lastPrice(c) }.getOrNull()?.takeIf { it > 0 } ?: return null
        val capital = if (goesLive()) runCatching { com.optionslab.app.data.Broker.funds().net }.getOrNull()
            else runCatching { Paper.snapshot().funds.let { it.availableCash + it.utilisedDebits } }.getOrNull()
        return com.optionslab.ira.TradeRisk.say(px, c.lotSize, lotsFor(px, c.lotSize), capital)
    }

    /** Not enough money for 1 lot (the lots it would take) with 20% to spare, or null. */
    suspend fun marginProblem(idea: NewsTrade.Idea, spot: Double): String? {
        val c = contract(idea.market.name, spot, idea.call) ?: return null
        val px = runCatching { Paper.lastPrice(c) }.getOrNull()?.takeIf { it > 0 } ?: return null
        val cost = px * c.lotSize * maxOf(1, lotsFor(px, c.lotSize))
        return if (goesLive()) com.optionslab.ira.MarginCheck.problem(runCatching { com.optionslab.app.data.Broker.funds().available }.getOrNull(), cost, "Zerodha")
            else com.optionslab.ira.MarginCheck.problem(runCatching { Paper.snapshot().funds.availableCash }.getOrNull(), cost, "the paper account")
    }

    /** Each closed trade's close time and result, for the cooling-off after losses. */
    fun closedTimes(): List<Pair<java.time.LocalDateTime, Double>> = all().filter { it.closed && it.result != null && it.minuteOut != null }.mapNotNull { p ->
        runCatching { java.time.LocalDate.parse(p.day).atTime(p.minuteOut!! / 60, p.minuteOut % 60) to p.result!! }.getOrNull() }

    /** The last 7 days' suggestions, for the Saturday report card. */
    fun weekSuggestions(today: java.time.LocalDate): List<JarvisTrades.Suggestion> =
        suggestions().map { it.second }.filter { !it.at.toLocalDate().isBefore(today.minusDays(6)) }

    /**
     * "What if I had taken the 10:30 suggestion?": the suggestion nearest that time (today, else the last day with
     * one), played on the real option prices the phone keeps.
     */
    fun whatIf(question: String): List<String> {
        val minute = com.optionslab.ira.WhatIf.minute(question)
        val all = suggestions().map { it.second }
        if (all.isEmpty()) return listOf("I have not suggested any trade yet.")
        val day = all.map { it.at.toLocalDate() }.filter { !it.isAfter(com.optionslab.app.data.Market.today()) }.maxOrNull() ?: return listOf("I have not suggested any trade yet.")
        val onDay = all.filter { it.at.toLocalDate() == day }
        val s = if (minute == null) onDay.last() else onDay.minByOrNull { kotlin.math.abs(it.at.hour * 60 + it.at.minute - minute) }!!
        if (minute != null && kotlin.math.abs(s.at.hour * 60 + s.at.minute - minute) > 15)
            return listOf("I made no suggestion near %02d:%02d on $day; the nearest was at %02d:%02d.".format(minute / 60, minute % 60, s.at.hour, s.at.minute))
        val what = "The %02d:%02d ${s.market.label} ${if (s.call) "call" else "put"} on $day (${s.answer})".format(s.at.hour, s.at.minute)
        val u = s.market.name
        val sess = runCatching { com.optionslab.app.data.Store.barSession(u, day) }.getOrNull()
            ?: return listOf("$what: I can replay it once that day's option prices are saved (after the close).")
        val p = runCatching { JarvisTrades.OptionSim.trade(sess, s.at, s.call, s.spot, JarvisTrades.strikeStep(u)) }.getOrNull()
            ?: return listOf("$what: that option has no prices for the time, so I cannot replay it.")
        val lot = sess.lotHint ?: sess.options.firstOrNull()?.lot
        return listOf("$what would have made %+.1f points".format(java.util.Locale.ENGLISH, p) + (lot?.let { " (${com.optionslab.ira.AppFacts.rs(p * it)} a lot)" } ?: "") +
            ", with the 15% stop, the +40 target, the profit lock and the 15:15 exit, after costs.")
    }

    /** The index's latest price as Jarvis last read it. */
    private fun spotOf(u: String): Double? = runCatching { IraHub.state.value.snaps[com.optionslab.ira.Market.valueOf(u)]?.price }.getOrNull()

    /** Will Jarvis's next trade go to Zerodha with real money (Live mode, paper-first off, the paper record proven)? */
    fun goesLive(solo: Boolean = false): Boolean = AppSettings.load().live && earned(solo)

    /** Has the record earned real orders: Solo's own paper record for its setups, else Jarvis's (and paper-first off). */
    private fun earned(solo: Boolean): Boolean =
        !paperFirst && if (solo) IraSolo.provenWhy() == null else JarvisTrades.proven(closedRecord()) == null

    /** Why each of Jarvis's recent losing trades lost (the last five), in words. */
    fun lossReasons(): List<String> {
        val lost = all().filter { it.closed && (it.result ?: 0.0) < 0 }.takeLast(5)
        if (lost.isEmpty()) return listOf("None of my trades has lost yet.")
        return lost.reversed().map { p ->
            val res = p.result ?: 0.0
            val head = "${p.day} ${p.symbol} (${com.optionslab.ira.AppFacts.rs(res)}): "
            val qty = maxOf(1, p.qty)
            val trip = if (p.call != null && p.spotIn != null && p.spotOut != null && p.minuteIn != null && p.minuteOut != null)
                com.optionslab.ira.LossReason.Trip(p.call, p.spotIn, p.spotOut, p.entry, p.entry + res / qty,
                    maxOf(0, p.minuteOut - p.minuteIn), p.minuteOut, p.stop != null && p.entry + res / qty <= p.stop + 0.5) else null
            head + (trip?.let { com.optionslab.ira.LossReason.why(it) } ?: "it hit its stop; the index prices around it were not recorded.")
        }
    }

    private fun recent(p: Pos) = runCatching { !java.time.LocalDate.parse(p.day).isBefore(com.optionslab.app.data.Market.today().minusDays(4)) }.getOrDefault(false)

    private fun tripOf(p: Pos): Double? = runCatching { com.optionslab.app.data.TradeBook.trips(p.live) }.getOrDefault(emptyList())
        .lastOrNull { it.symbol == p.symbol && !it.openedAt.toLocalDate().isBefore(java.time.LocalDate.parse(p.day)) }?.net

    /** Sells the whole position at market (paper) or through the app's square-off (Zerodha); its protection goes. */
    private suspend fun closeNow(p: Pos): String {
        runCatching { com.optionslab.app.data.Protections.removeSymbol(p.live, "NFO", p.symbol) }
        if (p.live) return IraActions.closeLiveSymbol(p.symbol)
        val c = Paper.contractOf(p.symbol) ?: return "The paper contract was not found."
        val lots = (p.qty / maxOf(1, c.lotSize)).coerceAtLeast(1)
        val r = Paper.place(c, "SELL", lots, "MARKET", "MIS", null, null, runCatching { Paper.quote(c) }.getOrNull())
        r.orderId?.let { com.optionslab.app.data.Strategies.tagOwner("paper:$it", "Jarvis news · exit") }
        return "Paper: ${r.message}" + (com.optionslab.app.data.Origins.shortId(r.orderId)?.let { " (order $it)" } ?: "")
    }
}
