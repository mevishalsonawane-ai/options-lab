package com.optionslab.ira

import com.optionslab.engine.sandbox.SandboxCosts
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * "What if lots" (Boss, 07 Oct 2026): "what if liquidity traded 3 lots", "how much with 1 lot this week", "3 lot pe kitna
 * banta", "is 2 lots better than 3". Liquidity 15+5's size as a hypothetical, from two places:
 *
 * - its own book: the closed paper trades over the span asked ([LiquidityRecord.spanOf]: this week, last week, this month,
 *   a day, the last N trades, everything), the lots it actually used beside the lots asked, and each trade recomputed at that
 *   size - the same premium move on the new quantity, with the charges worked out again for that quantity by the sandbox's
 *   own F&O schedule ([SandboxCosts.charge], the one the paper arms debit): brokerage is a flat Rs 20 an order, so the
 *   charges a lot fall as the lots grow, while STT, exchange, SEBI and stamp scale with the quantity. The net after
 *   charges, the worst trade, the deepest drawdown and the worst day, in rupees, at that size.
 * - the research ([ForwardCheck.LIQUIDITY], 1 lot): its expected net a trade and its worst drawdown, scaled to the lots.
 *
 * One plain line on risk: Jarvis does not know Boss's capital, so the drawdown is said in rupees only - never as a share,
 * never as a reason to trade more. Information only: nothing here changes the arm's lots (that stays the Lots setting's,
 * or "set liquidity to 3 lots", each asking first) or arms anything. Pure: no clock, no storage.
 */
object LotsWhatIf {
    /** What was asked: the hypothetical [lots] (one, or two or three to set side by side) over [span]. */
    data class Q(val lots: List<Int>, val span: LiquidityRecord.Q = LiquidityRecord.Q())

    /** The largest size a what-if is worked for (the arm's own setting offers [com.optionslab.engine.orb.LiquidityLots.CHOICES]). */
    const val MAX_LOTS = 10

    const val LOCKED = LiquidityRecord.LOCKED
    const val NOTE = "From the arm's own paper book and its research - facts, not advice."
    /** Every answer ends here: nothing was changed, and where the size is changed (asking first). */
    const val END = "This is a what-if; the arm's size is unchanged. To change lots: Strategies → Liquidity 15+5 → Lots (asks first)"

    /** Brokerage is a flat Rs 20 an order with 18% GST on it: Rs 47.20 a round trip, whatever the quantity ([SandboxCosts]). */
    const val FLAT_PER_TRADE = 2 * 20.0 * 1.18

    // ---- the question ------------------------------------------------------------------------------------------------

    private fun norm(text: String) = Spaced.joined(text)

    private val LIQ = Regex(" (liquidity|liquiditys|liqudity|liquidty|liq) ")
    private const val NUM = "(\\d{1,2}|one|two|three|four|five|six|seven|eight|nine|ten|ek|teen|char|chaar|paanch)"
    private val NUMBERS = mapOf("one" to 1, "ek" to 1, "two" to 2, "three" to 3, "teen" to 3, "four" to 4, "char" to 4, "chaar" to 4,
        "five" to 5, "paanch" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10)
    /** A count of lots: "3 lots", "1 lot", "teen lot". */
    private val LOT_N = Regex(" $NUM (lot|lots|lote) ")
    /** Two sizes set side by side: "2 or 3 lots", "2 lots vs 3", "3 lots instead of 2", "2 lots better than 3". */
    private val PAIR_A = Regex(" $NUM (or|vs|versus|ya|aur|and) $NUM (lot|lots) ")
    private val PAIR_B = Regex(" $NUM (lot|lots) (or|vs|versus|ya|than|better than|worse than|instead of|compared to|compared with|against|rather than|ke bajaye|ki jagah) $NUM ")
    /** Words of a hypothetical or a comparison. */
    private val HYPO = Regex(" (what if|if|agar|would|wouldve|could|couldve|had|have made|hota|hote|hoti|banta|bante|banti|bana|kitna|kitne|" +
        "how much|better|worse|vs|versus|compare|compared|comparison|instead|rather|with|ke saath|ke sath|se kitna) ")
    /**
     * Not a what-if: a change or an order (the size is changed only by its own confirmed route), the size now ("how many
     * lots", "is it trading"), advice asked, a budget or capital sum ("how many lots can I buy with 20000": Sizing).
     */
    private val NOT = Regex(" (set|change|changes|changed|changing|increase|decrease|reduce|raise|lower|make it|make liquidity|switch|turn|" +
        "karo|kar do|kardo|karna|kar de|karde|badhao|badha do|ghatao|ghata do|kam karo|update|put it|move|go to|go with|use|buy|sell|bech|becho|kharid|kharido|" +
        "order|orders|place|arm|disarm|start|stop|enable|disable|should|shall|recommend|suggest|advise|chahiye|can i|can we|afford|budget|margin|" +
        "capital|rupees|rs|inr|how many|currently|right now|abhi|is it trading|is liquidity trading|does liquidity trade|does it trade|" +
        "remind|alert|alarm|backtest|back test|shadow|shadows) ")
    /** Said without the arm's name: another arm, an index, an option or Boss's own trades are not the arm's what-if. */
    private val OTHER = Regex(" (hero|solo|orb|straddle|strangle|condor|spread|nifty|banknifty|bank nifty|finnifty|fin nifty|midcpnifty|midcap|sensex|gold|" +
        "ce|call|calls|put|puts|strike|premium|option|options|stock|stocks|future|futures|fut|i|im|ive|my|mine|me|mera|mere|meri|maine|mujhe|" +
        "we|our|hum|humne|strategy|strategies|bot|bots) ")
    private val BIG_NUMBER = Regex(" \\d{4,} ")

    private fun num(s: String): Int = s.toIntOrNull() ?: NUMBERS[s] ?: 0

    /** Is a what-if on Liquidity 15+5's lots asked? Null when not. */
    fun asked(text: String): Q? = askedKept.of(text) { askedFresh(text) }
    private val askedKept = Kept<Q?>(64)

    private fun askedFresh(text: String): Q? {
        val t = norm(text)
        if (!LOT_N.containsMatchIn(t)) return null
        val pairs = (PAIR_A.findAll(t).map { listOf(it.groupValues[1], it.groupValues[3]) } +
            PAIR_B.findAll(t).map { listOf(it.groupValues[1], it.groupValues[4]) }).toList()
        if (!HYPO.containsMatchIn(t) && pairs.isEmpty()) return null
        if (NOT.containsMatchIn(t) || BIG_NUMBER.containsMatchIn(t)) return null
        if (!LIQ.containsMatchIn(t) && OTHER.containsMatchIn(t)) return null
        // The sizes in the order said (a pair's two and each "N lots"), each once, by where it first stands in the words.
        val found = pairs.flatten() + LOT_N.findAll(t).map { it.groupValues[1] }.toList()
        val first = HashMap<Int, Int>()
        for (w in found.distinct()) first[num(w)] = minOf(first[num(w)] ?: Int.MAX_VALUE, t.indexOf(" $w "))
        val said = first.entries.sortedBy { it.value }.map { it.key }
        if (said.isEmpty()) return null
        return Q(said.take(3), LiquidityRecord.spanOf(text))
    }

    // ---- the arithmetic ----------------------------------------------------------------------------------------------

    /** Units in one lot of [r]'s contract (its own quantity when the lot is not known: then it counts as 1 lot). */
    fun unitsPerLot(r: LiquidityRecord.Row): Int =
        r.trade.lot?.takeIf { it > 0 } ?: (r.trade.qty / r.lots).roundToInt().coerceAtLeast(1)

    /** Both legs' charges for [qty] units bought at [entry] and sold at [exit] - the sandbox's own F&O schedule. */
    fun charges(entry: Double, exit: Double, qty: Int): Double =
        SandboxCosts.charge("BUY", BigDecimal(entry), qty).toDouble() + SandboxCosts.charge("SELL", BigDecimal(exit), qty).toDouble()

    /** [r] recomputed at [lots]: the same premium move on [lots] lots, less that quantity's own charges. */
    fun netAt(r: LiquidityRecord.Row, lots: Int): Double {
        val qty = lots * unitsPerLot(r)
        val exit = r.trade.exit ?: return 0.0
        return (exit - r.trade.entry) * qty - charges(r.trade.entry, exit, qty)
    }

    /** The charges of [r] at [lots]. */
    fun chargesAt(r: LiquidityRecord.Row, lots: Int): Double {
        val exit = r.trade.exit ?: return 0.0
        return charges(r.trade.entry, exit, lots * unitsPerLot(r))
    }

    /**
     * A size's figures over trades: [trades], [net] after [charges], [worst] (the trade and its net; null for none),
     * [drawdown] (the deepest fall of the running total, 0 or negative), [worstDay] (the day and its net; null for none).
     */
    data class Figures(val lots: Int?, val trades: Int, val net: Double, val charges: Double, val worst: Pair<LiquidityRecord.Row, Double>?,
        val drawdown: Double, val worstDay: Pair<LocalDate, Double>?) {
        /** Charges a lot a trade (null: no trade or no size). */
        val chargesPerLotTrade: Double? get() = if (trades == 0 || lots == null || lots == 0) null else charges / trades / lots
    }

    private fun figures(lots: Int?, rows: List<LiquidityRecord.Row>, nets: List<Double>, charges: Double): Figures {
        val worst = rows.indices.minByOrNull { nets[it] }?.let { rows[it] to nets[it] }
        val days = LinkedHashMap<LocalDate, Double>()
        rows.forEachIndexed { i, r -> days[r.day] = (days[r.day] ?: 0.0) + nets[i] }
        val worstDay = days.entries.minByOrNull { it.value }?.let { it.key to it.value }
        return Figures(lots, rows.size, nets.sum(), charges, worst, ForwardCheck.drawdown(nets), worstDay)
    }

    /** [rows] (oldest exit first) recomputed at [lots]. */
    fun at(rows: List<LiquidityRecord.Row>, lots: Int): Figures =
        figures(lots, rows, rows.map { netAt(it, lots) }, rows.sumOf { chargesAt(it, lots) })

    /** [rows] as booked: their own nets and charges. */
    fun booked(rows: List<LiquidityRecord.Row>): Figures =
        figures(rows.map { it.lots }.distinct().singleOrNull()?.takeIf { it == Math.rint(it) }?.toInt(), rows, rows.map { it.net }, rows.sumOf { it.trade.charges })

    /** The backtest's expected net a trade at [lots]: its mean a lot times the lots, plus the flat brokerage not paid again. */
    fun expectedPerTrade(lots: Int, e: ForwardCheck.Expectation = ForwardCheck.LIQUIDITY): Double = lots * e.mean + FLAT_PER_TRADE * (lots - 1)

    /** The backtest's worst drawdown (1 lot) scaled to [lots]. */
    fun backtestWorst(lots: Int, e: ForwardCheck.Expectation = ForwardCheck.LIQUIDITY): Double = lots * e.maxDrawdown

    // ---- words -------------------------------------------------------------------------------------------------------

    private fun rs(x: Double) = LiquidityRecord.rs(x)
    private fun rsPlain(x: Double) = "Rs " + String.format(Locale.ENGLISH, "%,d", abs(x).roundToLong())
    private fun date(d: LocalDate) = "${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)}"
    private fun lotsWord(n: Int) = "$n lot${if (n == 1) "" else "s"}"
    private fun lotsWord(x: Double): String = if (x == Math.rint(x)) lotsWord(x.toInt()) else String.format(Locale.ENGLISH, "%.1f lots", x)
    private fun s(n: Int, one: String, many: String = one + "s") = "$n ${if (n == 1) one else many}"

    private fun tradeWords(r: LiquidityRecord.Row): String {
        val t = r.trade.entryTime
        return String.format(Locale.ENGLISH, "%s %02d:%02d %s %s", date(r.day), t.hour, t.minute, r.book, if (r.trade.right.equals("PE", true)) "PE" else "CE")
    }

    /** The lots it actually used: "2 lots on each", or "2 lots on 8, 1 lot on 4". */
    fun usedWords(rows: List<LiquidityRecord.Row>): String {
        val by = rows.groupBy { Math.round(it.lots * 10) / 10.0 }.entries.sortedByDescending { it.value.size }
        if (by.size == 1) return if (rows.size == 1) "at ${lotsWord(by[0].key)}" else "at ${lotsWord(by[0].key)} each"
        return "at " + by.joinToString(", ") { (l, these) -> "${lotsWord(l)} on ${these.size}" }
    }

    private fun figuresLine(head: String, f: Figures): String {
        val per = f.chargesPerLotTrade?.let { " (${rsPlain(it)} a lot a trade)" }.orEmpty()
        val parts = mutableListOf("net ${rs(f.net)} after ${rsPlain(f.charges)} charges$per")
        f.worst?.let { (r, n) -> parts += "worst trade ${rs(n)} (${tradeWords(r)})" }
        parts += "deepest drawdown ${if (f.drawdown < 0) rs(f.drawdown) else "none"}"
        f.worstDay?.let { (d, n) -> parts += "worst day ${date(d)} ${rs(n)}" }
        return "$head: " + parts.joinToString("; ") + "."
    }

    private fun researchLines(lots: List<Int>): List<String> {
        val e = ForwardCheck.LIQUIDITY
        val out = ArrayList<String>()
        val per = lots.joinToString("; ") { n ->
            if (n == 1) "at 1 lot ${rs(expectedPerTrade(1, e))} a trade"
            else "at ${lotsWord(n)} about ${rs(expectedPerTrade(n, e))} a trade"
        }
        out += "Research: the backtest (${e.trades} trades at 1 lot) made ${rs(e.mean)} a lot a trade after charges - $per" +
            (if (lots.any { it > 1 }) " (brokerage is flat, so each lot past the first saves about ${rsPlain(FLAT_PER_TRADE)} a trade)" else "") + "."
        out += "Its worst run was ${rs(e.maxDrawdown)} a lot - " + lots.joinToString("; ") { n ->
            if (n == 1) "at 1 lot the backtest's worst run was ${rs(backtestWorst(1, e))}" else "at ${lotsWord(n)} the backtest's worst run would have been about ${rs(backtestWorst(n, e))}"
        } + "."
        return out
    }

    private fun riskLine(lots: List<Int>): String {
        val worst = backtestWorst(lots.max())
        return "Risk: I don't know your capital, so I can't say what share of it a ${rs(worst)} run would be - that rupee figure is the one to weigh."
    }

    /** The answer to [q] at [today] from the arm's closed paper trades [all] ([LiquidityRecord.rows]' order: oldest exit first). */
    fun answer(q: Q, all: List<LiquidityRecord.Row>, today: LocalDate): String {
        val lots = q.lots.distinct()
        if (lots.isEmpty() || lots.any { it < 1 || it > MAX_LOTS })
            return "I work a what-if for 1 to $MAX_LOTS lots, Boss - like \"what if liquidity traded 3 lots\".\n$END"
        val out = ArrayList<String>()
        val sizes = lots.joinToString(" vs ") { lotsWord(it) }
        val shown = LiquidityRecord.pick(q.span, all, today)
        val span = LiquidityRecord.spanWords(q.span, today, shown)
        when {
            all.isEmpty() -> out += "Liquidity 15+5 has no closed paper trade in its book yet, Boss - nothing of its own to work $sizes on."
            shown.isEmpty() -> out += "Liquidity 15+5 closed no paper trade $span, Boss - nothing of its own to work $sizes on."
            else -> {
                out += "Boss, Liquidity 15+5 at $sizes $span: ${s(shown.size, "closed paper trade")}, traded ${usedWords(shown)}."
                val asBooked = booked(shown)
                out += figuresLine("As booked", asBooked)
                val at = lots.map { at(shown, it) }
                at.forEach { f -> out += figuresLine("At ${lotsWord(f.lots!!)}", f) }
                if (at.size >= 2) {
                    val a = at.first(); val b = at.last()
                    out += "${lotsWord(b.lots!!)} against ${lotsWord(a.lots!!)}: ${rs(b.net - a.net)} on net, the drawdown ${ddWords(b.drawdown - a.drawdown)}" +
                        twoCharges(a, b) + "."
                } else if (asBooked.lots != null && asBooked.lots != lots[0]) {
                    val b = at[0]
                    out += "Against as booked: ${rs(b.net - asBooked.net)} on net, the drawdown ${ddWords(b.drawdown - asBooked.drawdown)}" +
                        (asBooked.chargesPerLotTrade?.let { c -> b.chargesPerLotTrade?.let { "; charges ${rsPlain(it)} a lot a trade against ${rsPlain(c)}" } }.orEmpty()) + "."
                }
                if (shown.size < LiquidityRecord.MIN_TRADES)
                    out += "${s(shown.size, "trade")} ${if (shown.size == 1) "is" else "are"} too few to read much from; the backtest's ${ForwardCheck.LIQUIDITY.trades} are the steadier guide."
            }
        }
        out += researchLines(lots)
        out += riskLine(lots)
        if (lots.any { it > 3 }) out += "(The arm's Lots setting offers 1, 2 or 3; more here is arithmetic only.)"
        out += NOTE
        out += END
        return out.joinToString("\n")
    }

    private fun ddWords(diff: Double): String = when {
        diff < -0.5 -> "${rsPlain(diff)} deeper"
        diff > 0.5 -> "${rsPlain(diff)} shallower"
        else -> "the same"
    }

    private fun twoCharges(a: Figures, b: Figures): String {
        val ca = a.chargesPerLotTrade ?: return ""
        val cb = b.chargesPerLotTrade ?: return ""
        return "; charges ${rsPlain(cb)} a lot a trade against ${rsPlain(ca)} (brokerage is flat an order)"
    }
}
