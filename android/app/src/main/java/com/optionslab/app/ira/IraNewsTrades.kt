package com.optionslab.app.ira

import com.optionslab.app.data.AppSettings
import com.optionslab.app.data.Paper
import com.optionslab.engine.Right
import com.optionslab.engine.orb.LiquidityRules
import com.optionslab.engine.orb.OrbRules
import com.optionslab.engine.orb.ProfitLock
import com.optionslab.ira.NewsTrade
import org.json.JSONArray
import org.json.JSONObject

/**
 * News trades (JarvisAlgo): an idea from [NewsTrade] becomes a trade only on the owner's Approve. The option is picked as
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
                   val headline: String, val day: String, val closed: Boolean = false, val result: Double? = null)

    fun all(): List<Pos> = runCatching {
        val a = JSONArray(com.optionslab.app.security.SecurePrefs.getString(KEY) ?: "[]")
        (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            Pos(o.getString("s"), o.getBoolean("l"), o.getDouble("e"), o.getInt("q"), o.getDouble("p"), if (o.has("st")) o.getDouble("st") else null,
                o.getString("h"), o.getString("d"), o.optBoolean("c"), if (o.has("r")) o.getDouble("r") else null)
        }
    }.getOrDefault(emptyList())

    private fun save(list: List<Pos>) = runCatching {
        com.optionslab.app.security.SecurePrefs.put(KEY, JSONArray().apply { list.takeLast(200).forEach { p ->
            put(JSONObject().put("s", p.symbol).put("l", p.live).put("e", p.entry).put("q", p.qty).put("p", p.peak)
                .apply { p.stop?.let { put("st", it) }; p.result?.let { put("r", it) } }.put("h", p.headline).put("d", p.day).put("c", p.closed)) } }.toString())
    }

    fun today(): Int { val d = com.optionslab.app.data.Market.today().toString(); return all().count { it.day == d } }

    /** The record so far: trades, wins, net (for "how are news trades doing"). */
    fun record(): String {
        val c = all().filter { it.closed && it.result != null }
        if (c.isEmpty()) return "No news trades closed yet."
        return "News trades: ${c.size} closed, ${c.count { it.result!! > 0 }} won, net ${com.optionslab.ira.AppFacts.rs(c.sumOf { it.result!! })}."
    }

    /** The option as Liquidity 15+5 picks it for [u] at [spot]: ATM on the strike step, next expiry after today. */
    private fun contract(u: String, spot: Double, call: Boolean): Paper.Contract? {
        val step = if (u == "NIFTY") 50 else LiquidityRules.strikeStep(u)
        val strike = OrbRules.atmStrike(spot, step)
        val listed = com.optionslab.app.data.Market.contracts().filter { it.underlying == u }.map { it.expiry }.distinct()
        val expiry = OrbRules.expiryAfter(com.optionslab.app.data.Market.today(), listed) ?: return null
        return Paper.contractFor(u, expiry, strike.toDouble(), if (call) Right.CE else Right.PE)
    }

    /** The owner approved [idea]: place it (Paper, or Zerodha through the app's order review), stop, target, record. */
    suspend fun place(idea: NewsTrade.Idea, spot: Double, headline: String): String {
        val u = idea.market.name
        val c = contract(u, spot, idea.call) ?: return "No ${u} option is listed for the next expiry."
        val s = AppSettings.load()
        val day = com.optionslab.app.data.Market.today().toString()
        if (!s.live) {
            val q = runCatching { Paper.quote(c) }.getOrNull()
            val r = Paper.place(c, "BUY", 1, "MARKET", "MIS", null, null, q)
            val fill = r.events.filterIsInstance<com.optionslab.engine.sandbox.SandboxEvent.Fill>().firstOrNull()
                ?: return "Paper: ${r.message}"
            r.orderId?.let { com.optionslab.app.data.Strategies.tagOwner("paper:$it", "Jarvis news · entry") }
            val stop = LiquidityRules.stopTrigger(fill.price)
            val prot = com.optionslab.app.data.Protections.protectPaper(c.symbol, "MIS", fill.quantity, fill.price, stop, null, fill.price + TARGET_POINTS)
            save(all() + Pos(c.symbol, false, fill.price, fill.quantity, fill.price, stop, headline, day))
            IraHub.appContext()?.let { com.optionslab.app.work.Notifier.orderFilled(it, "BUY", fill.quantity, c.symbol, fill.price, "Paper", "Jarvis news") }
            return "Bought ${c.symbol} at ${"%.2f".format(fill.price)} on paper. $prot Profit lock on."
        }
        val t = IraOrders.Ticket(u, c.expiry, c.strike, c.right, 1, true, c.lotSize)
        val sent = IraActions.trade(t, live = true)
        if (!sent.startsWith("Sent to Zerodha")) return sent
        val avg = Regex("COMPLETE \\d+ at ([\\d.]+)").find(sent)?.groupValues?.get(1)?.toDoubleOrNull()
            ?: return "$sent Set its stop from the position: the fill price was not confirmed yet."
        val stop = LiquidityRules.stopTrigger(avg)
        val prot = com.optionslab.app.data.Protections.protectLive(c.symbol, "NFO", s.orderProduct, c.lotSize, avg, stop, null, avg + TARGET_POINTS)
        save(all() + Pos(c.symbol, true, avg, c.lotSize, avg, stop, headline, day))
        return "$sent $prot Profit lock on."
    }

    /**
     * Every market-watch tick: each open news trade's best price, its profit-lock stop moved up a rung when earned
     * (the protection is re-set at the new stop, the target kept), and a closed one recorded and told.
     */
    suspend fun tick() {
        val list = all()
        if (list.none { !it.closed }) return
        val active = runCatching { com.optionslab.app.data.Protections.active() }.getOrDefault(emptyList())
        val out = list.map { p ->
            if (p.closed) return@map p
            val prot = active.firstOrNull { it.symbol == p.symbol && it.live == p.live }
            if (prot == null) {
                // Out: the stop, the target or the day's square-off took it; the result from the trade book.
                val trip = runCatching { com.optionslab.app.data.TradeBook.trips(p.live) }.getOrDefault(emptyList())
                    .lastOrNull { it.symbol == p.symbol && !it.openedAt.toLocalDate().isBefore(java.time.LocalDate.parse(p.day)) }
                val r = trip?.net
                IraHub.appContext()?.let { JarvisPopup.show(it, "News trade closed: ${p.symbol}", r?.let { x -> "Result ${com.optionslab.ira.AppFacts.rs(x)}. " } .orEmpty() + record()) }
                return@map p.copy(closed = true, result = r)
            }
            val ltp = runCatching { if (p.live) com.optionslab.app.data.Broker.quotes(listOf("NFO:${p.symbol}"))["NFO:${p.symbol}"]?.last
                else Paper.contractOf(p.symbol)?.let { Paper.lastPrice(it) } }.getOrNull() ?: return@map p
            val lock = ProfitLock.level(p.entry, TARGET_POINTS, p.peak)
            var stop = p.stop
            if (lock != null && (stop == null || lock > stop + 0.01)) {
                val r = if (p.live) com.optionslab.app.data.Protections.protectLive(p.symbol, "NFO", AppSettings.load().orderProduct, p.qty, p.entry, lock, null, p.entry + TARGET_POINTS)
                    else com.optionslab.app.data.Protections.protectPaper(p.symbol, "MIS", p.qty, p.entry, lock, null, p.entry + TARGET_POINTS)
                if (r.startsWith("Protected")) stop = lock
            }
            p.copy(peak = maxOf(p.peak, ltp), stop = stop)
        }
        save(out)
    }
}
