package com.optionslab.ira

import java.util.Locale

/**
 * Jarvis on MCX (9 Oct 2026, Boss: "all MCX in the application"): reading only - the price of crude, natural gas, gold or
 * silver (the near-month future), and the MCX positions held. Jarvis never trades MCX: no order, no command, nothing that
 * acts is read here.
 *
 * Words: "crude", "crude oil", "natural gas" / "nat gas", "silver" always mean MCX; "gold" means MCX gold only with a word
 * that says so ("mcx gold", "goldm", "gold mini", "gold futures", "gold in rupees") - a plain "gold" stays the gold price
 * Jarvis already reads (XAUUSD). "mcx" / "commodities" alone: the four together. A question about prices or positions only;
 * anything that asks to buy, sell, chart, backtest or set an alarm is left to the rest of Jarvis. Pure.
 */
object McxTalk {
    /** What was asked: the commodities (near-month futures, by MCX name) and whether the MCX positions were asked for. */
    data class Asked(val names: List<String>, val positions: Boolean)

    /** One commodity's price as the app read it. [lastSession]: the last session's close, MCX being shut now. */
    data class Price(val name: String, val contract: String, val last: Double, val changePct: Double?, val lastSession: Boolean)

    /** One MCX position held: its label (CRUDEOIL 15 OCT 8550 CE), signed units, average and P&L, and where (Paper / Zerodha). */
    data class Held(val label: String, val qty: Int, val avg: Double, val pnl: Double?, val venue: String)

    val HEADLINE = listOf("CRUDEOIL", "NATURALGAS", "GOLDM", "SILVERM")

    private val NOT_WORD = Regex("[^a-z0-9 ]")
    private val SPACES = Regex("\\s+")
    private val ACTS = Regex(" (buy|sell|short|long|order|place|backtest|back test|test|chart|alarm|alert|strategy|arm|square|close|exit|cancel|stop|start|trade it|trade on) ")
    private val CRUDE = Regex(" (crude|crude oil|crudeoil|crudeoilm|crude mini) ")
    private val GAS = Regex(" (natural gas|naturalgas|nat gas|natgas|natgasmini|natural gas mini) ")
    private val SILVER = Regex(" (silver|silverm|silver mini|silvermic|chandi) ")
    private val GOLD = Regex(" gold ")
    private val GOLD_MCX = Regex(" (goldm|gold mini|goldmini|gold futures?|gold future|mcx gold|gold mcx|gold in rupees|gold rs|gold rupees|gold ten|goldten|gold petal|goldpetal|sona) ")
    private val MCX = Regex(" (mcx|commodity|commodities) ")
    private val POSITIONS = Regex(" (position|positions|holding|holdings|hold|open trades|my trades) ")

    /** What [text] asks about MCX, or null when it is not an MCX price or position question. */
    fun read(text: String): Asked? {
        val t = " " + SPACES.replace(NOT_WORD.replace(text.lowercase(Locale.ROOT), " "), " ").trim() + " "
        if (ACTS.containsMatchIn(t)) return null
        val names = ArrayList<String>()
        if (CRUDE.containsMatchIn(t)) names += "CRUDEOIL"
        if (GAS.containsMatchIn(t)) names += "NATURALGAS"
        if (GOLD_MCX.containsMatchIn(t) || (GOLD.containsMatchIn(t) && MCX.containsMatchIn(t))) names += "GOLDM"
        if (SILVER.containsMatchIn(t)) names += "SILVERM"
        val mcx = MCX.containsMatchIn(t)
        val positions = POSITIONS.containsMatchIn(t) && (mcx || names.isNotEmpty())
        if (names.isEmpty() && !mcx) return null
        return Asked(if (names.isEmpty() && !positions) HEADLINE else names, positions)
    }

    /** "Crude oil (CRUDEOIL 19 OCT FUT) Rs 8,552.00, up 1.20% today" / "... at the last close". */
    fun priceLine(p: Price): String {
        val label = com.optionslab.engine.mcx.Mcx.commodity(p.name)?.label ?: p.name
        val px = "Rs " + "%,.2f".format(Locale.ENGLISH, p.last)
        val move = when {
            p.lastSession -> "at the last close"
            p.changePct == null || !p.changePct.isFinite() -> "now"
            p.changePct >= 0 -> "up %.2f%% today".format(Locale.ENGLISH, 100 * p.changePct)
            else -> "down %.2f%% today".format(Locale.ENGLISH, -100 * p.changePct)
        }
        return "$label (${p.contract}) $px, $move"
    }

    /** One position: "Paper: CRUDEOIL 15 OCT 8550 CE, long 100 at 242.00, P&L Rs +1,250". */
    fun heldLine(h: Held): String {
        val side = if (h.qty >= 0) "long" else "short"
        val pnl = h.pnl?.takeIf { it.isFinite() }?.let { ", P&L Rs " + "%+,.0f".format(Locale.ENGLISH, it) } ?: ""
        return "${h.venue}: ${h.label}, $side ${kotlin.math.abs(h.qty)} at ${"%,.2f".format(Locale.ENGLISH, h.avg)}$pnl"
    }

    /**
     * The answer: the prices asked (one line each; a name with no price says so), then the positions when asked (or
     * "no MCX positions"). [open]: MCX trading now; shut, the prices are the last session's and said so. [locked]: the
     * phone is locked, so positions are not read out.
     */
    fun answer(asked: Asked, prices: List<Price>, held: List<Held>?, open: Boolean, locked: Boolean): String {
        val out = ArrayList<String>()
        for (n in asked.names) {
            val p = prices.firstOrNull { it.name == n }
            out += p?.let { priceLine(it) } ?: "${com.optionslab.engine.mcx.Mcx.commodity(n)?.label ?: n}: no price just now"
        }
        if (asked.names.isNotEmpty() && !open) out += "MCX is shut now; it trades 09:00 to 23:30 (23:55 in US winter)."
        if (asked.positions) out += when {
            locked -> "Unlock the phone for your positions, Boss."
            held == null -> "I could not read your MCX positions just now."
            held.isEmpty() -> "You hold no MCX positions."
            else -> "Your MCX positions: " + held.joinToString("; ") { heldLine(it) } + "."
        }
        return out.joinToString("\n").ifEmpty { "Nothing to say about MCX just now." }
    }
}
