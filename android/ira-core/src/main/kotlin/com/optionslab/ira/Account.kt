package com.optionslab.ira

import java.util.Locale

/**
 * The owner's own trading as the app holds it - read by the app, handed here, never fetched from anywhere else (rule
 * 1: nothing outside the app). Ira reports it as facts: what was done, what is held, what it made; never advice.
 */
data class AccountView(
    /** "Paper" or "Live": which account the app is trading now. */
    val mode: String,
    /** The account read (the paper account; the live one's orders stay on the Trade page). */
    val account: String,
    val dayPnl: Double?, val realized: Double?, val unrealized: Double?,
    val positions: List<Held>,
    val orders: List<OrderLine>,
    val arms: List<ArmLine>,
) {
    data class Held(val symbol: String, val qty: Int, val avg: Double, val ltp: Double, val pnl: Double)
    /** One of today's orders; [by] is who placed it ("Manual · Ira", a strategy's name...). */
    data class OrderLine(val time: String, val symbol: String, val action: String, val qty: Int, val status: String,
                         val avgPrice: Double, val by: String?, val reason: String? = null)
    /** A strategy arm: [kind] "Pine" or "ORB"; [todayPnl] in rupees when known; [holding] what it holds now. */
    data class ArmLine(val name: String, val kind: String, val on: Boolean, val detail: String, val todayPnl: Double?,
                       val holding: String?, val tradesToday: Int = 0)
}

/** Answers about the owner's own trading and about Ira itself. Pure. */
object AccountAnswers {
    private fun rs(x: Double) = (if (x < 0) "-Rs " else "+Rs ") + "%,.2f".format(Locale.ENGLISH, kotlin.math.abs(x))
    private fun px(x: Double) = "%,.2f".format(Locale.ENGLISH, x)

    /** What the question asks about the account. */
    enum class Part { ORDERS, POSITIONS, PNL, ARMS }

    fun parts(q: Question): Set<Part> {
        val t = " " + q.text.lowercase().replace(Regex("[^a-z0-9& ]"), " ").replace(Regex("\\s+"), " ") + " "
        val out = LinkedHashSet<Part>()
        if (Regex(" (order|orders|trade|trades|fills|filled) ").containsMatchIn(t)) out += Part.ORDERS
        if (Regex(" (position|positions|holding|holdings|open trades) ").containsMatchIn(t)) out += Part.POSITIONS
        if (Regex(" (p&l|pnl|p l|profit|profits|loss|losses|made|lost|earned|returns?) ").containsMatchIn(t)) out += Part.PNL
        if (Regex(" (strategy|strategies|arm|arms|bot|bots|algo|algos|studies|study|scripts?) ").containsMatchIn(t)) out += Part.ARMS
        if (out.isEmpty()) out += listOf(Part.PNL, Part.POSITIONS, Part.ORDERS, Part.ARMS)   // "how am I doing", "my account"
        return out
    }

    fun answer(q: Question, v: AccountView?): Answer {
        if (v == null) return Answer("I could not read your account just now. Try again in a moment.", emptyList())
        val facts = ArrayList<String>()
        val parts = ArrayList<String>()
        val want = parts(q)
        val analyze = Regex("\\b(analy[sz]e|analysis|review|summar|how (am|did|are|is)|check)", RegexOption.IGNORE_CASE).containsMatchIn(q.text)
        val acct = "the ${v.account.lowercase()} account"

        if (AccountAnswers.Part.PNL in want) {
            if (v.dayPnl == null) parts += "I have no P&L for $acct today."
            else {
                val f = "today's P&L on ${v.account.lowercase()} ${rs(v.dayPnl)} after charges" +
                    (if (v.realized != null && v.unrealized != null) " (booked ${rs(v.realized)}, open ${rs(v.unrealized)})" else "")
                facts += f
                parts += "Today's P&L on $acct is ${rs(v.dayPnl)} after charges" +
                    (if (v.realized != null && v.unrealized != null) ": ${rs(v.realized)} booked and ${rs(v.unrealized)} on open positions." else ".")
            }
        }
        if (Part.POSITIONS in want) {
            if (v.positions.isEmpty()) parts += "No open positions on $acct."
            else {
                v.positions.forEach { p -> facts += "open ${p.symbol} qty ${p.qty} avg ${px(p.avg)} now ${px(p.ltp)} P&L ${rs(p.pnl)}" }
                parts += "${v.positions.size} open position${if (v.positions.size > 1) "s" else ""}: " + v.positions.take(4).joinToString("; ") { p ->
                    "${p.symbol} ${p.qty} at ${px(p.avg)}, now ${px(p.ltp)} (${rs(p.pnl)})" } + "."
            }
        }
        if (Part.ORDERS in want) {
            val o = v.orders
            if (o.isEmpty()) parts += "No orders on $acct today."
            else {
                val done = o.count { it.status.equals("COMPLETE", true) }
                val open = o.count { it.status.equals("OPEN", true) || it.status.contains("PENDING", true) }
                val rej = o.count { it.status.equals("REJECTED", true) || it.status.equals("CANCELLED", true) }
                facts += "orders today ${o.size}: $done filled, $open open, $rej rejected or cancelled"
                o.forEach { facts += "order ${it.time} ${it.action} ${it.symbol} qty ${it.qty} ${it.status.lowercase()}" +
                    (if (it.avgPrice > 0) " at ${px(it.avgPrice)}" else "") + (it.by?.let { b -> " by $b" } ?: "") }
                parts += "${o.size} order${if (o.size > 1) "s" else ""} today: $done filled, $open open, $rej rejected or cancelled. " +
                    "Latest: " + o.takeLast(3).reversed().joinToString("; ") { "${it.time} ${it.action} ${it.qty} ${it.symbol}, ${it.status.lowercase()}" +
                    (if (it.avgPrice > 0) " at ${px(it.avgPrice)}" else "") + (it.by?.let { b -> " ($b)" } ?: "") } + "."
                o.filter { it.reason != null && it.status.equals("REJECTED", true) }.takeLast(1).forEach {
                    parts += "The last rejection said: ${it.reason}"; facts += "rejected: ${it.reason}" }
                if (analyze) {
                    val by = o.groupBy { it.by ?: "unknown" }.entries.sortedByDescending { it.value.size }
                    parts += "By who placed them: " + by.joinToString(", ") { "${it.key} ${it.value.size}" } + "."
                    by.forEach { facts += "orders by ${it.key}: ${it.value.size}" }
                }
            }
        }
        if (Part.ARMS in want) {
            val on = v.arms.filter { it.on }
            if (v.arms.isEmpty()) parts += "No strategy arms are set up."
            else {
                parts += "${on.size} of ${v.arms.size} strategy arm${if (v.arms.size > 1) "s are" else " is"} switched on" +
                    (if (on.isEmpty()) "." else ": " + on.take(5).joinToString("; ") { a ->
                        "${a.name} (${a.kind}, ${a.detail})" + (a.todayPnl?.let { " today ${rs(it)}" } ?: "") +
                            (if (a.tradesToday > 0) ", ${a.tradesToday} trade${if (a.tradesToday > 1) "s" else ""}" else "") +
                            (a.holding?.let { h -> ", holding $h" } ?: "") } + ".")
                v.arms.forEach { a -> facts += "arm ${a.name} ${a.kind} ${if (a.on) "on" else "off"} ${a.detail}" +
                    (a.todayPnl?.let { " today ${rs(it)}" } ?: "") + (if (a.tradesToday > 0) " trades ${a.tradesToday}" else "") }
                if (analyze) {
                    val ranked = on.filter { it.todayPnl != null }.sortedByDescending { it.todayPnl }
                    if (ranked.size >= 2) parts += "Best today: ${ranked.first().name} (${rs(ranked.first().todayPnl!!)}); " +
                        "weakest: ${ranked.last().name} (${rs(ranked.last().todayPnl!!)})."
                }
            }
        }
        if (v.mode.equals("Live", true) && v.account.equals("Paper", true))
            parts += "You are in Live mode: your Zerodha orders are on the Trade page; I read the paper account and the arms."
        if (analyze) parts += "These are facts from the app, not advice."
        return Answer(parts.joinToString(" "), facts)
    }

    /** "Can you listen to me?", "what can you do?" - about Ira (Jarvis) itself. */
    fun help(q: Question, voice: Boolean): Answer {
        val t = q.text.lowercase()
        val aboutVoice = Regex("\\b(listen|hear|voice|speak|talk|mic|microphone|say)").containsMatchIn(t)
        val voiceLine = if (voice) "Voice: switch on \"Listen for Jarvis\" in the Voice card at the top of this page and allow the microphone. " +
            "Then say \"Jarvis, how is Nifty?\" - or \"Jarvis\", wait for \"Yes?\", and ask. Say \"Jarvis, stop listening\" to switch it off. " +
            "It hears you on this phone only."
        else "Voice is in JarvisAlgo only; here you type."
        if (aboutVoice) return Answer((if (voice) "Yes. " else "") + voiceLine, emptyList())
        return Answer("I can tell you about Nifty, BankNifty, FinNifty, Sensex, India VIX and gold: prices, trend, levels, " +
            "patterns, volatility and the news. I can read your own trading: today's orders, open positions, P&L and how your " +
            "strategy arms are doing (try \"analyze my orders\"). I can backtest a pattern as a strategy (\"backtest the " +
            "breakout on BankNifty 15m\") and prepare an order for you to confirm (\"buy 1 lot Nifty ATM CE\"). " +
            "I don't give buy or sell advice. " + voiceLine, emptyList())
    }
}
