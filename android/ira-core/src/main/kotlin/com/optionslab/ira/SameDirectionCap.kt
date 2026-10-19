package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

/**
 * The same-direction cap (19 Oct, Boss: "Resolve the issues"). At 09:30 that day Nifty fell 0.42% in 10 minutes and the
 * automatic arms bought about 11 index puts on that one move (R51 Donchian 20 and R72 Breadth Donchian on four indices each,
 * Liquidity 15+5 twice on one BANKNIFTY put and once on FINNIFTY); the market bounced, every -15% stop hit within minutes and
 * the paper day lost Rs 18,121. The cap: at most [DEFAULT_MAX] automatic open option positions betting the same way on the NSE
 * index options ([INDICES]), across all arms - a long call bets up, a long put bets down. A new automatic entry past it is
 * refused with a plain reason and recorded as a would-be (shadow) entry, so each arm's own record stays comparable.
 *
 *  - It only ever refuses an ENTRY (lower risk): an exit is never asked, nothing is placed, sized up or sold here.
 *  - Boss's own manual orders are never capped, and never counted as an automatic entry.
 *  - First to reach the door wins: the paper book's own lock orders the entries of one bar, so when several arms fire on the
 *    same bar the first two booked go and the rest are refused.
 *  - Each automatic entry counts once, even two on the same option (Liquidity's 15m and 5m books both on one put count 2).
 *
 * Pure: the app hands in the book's fills, working orders and positions ([openEntries]); its own switches (paper on by default,
 * live in shadow, off by default) are kept by the app's host.
 */
object SameDirectionCap {
    const val DEFAULT_MAX = 2
    /** The choices offered in Bot settings. */
    val CHOICES = listOf(1, 2, 3, 4, 5)
    /** The NSE (and BSE SENSEX) index options it counts. */
    val INDICES = setOf("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX")
    private val EXCHANGES = setOf("NFO", "BFO")

    /**
     * The way a BUY of an option on [underlying] ([exchange], [right] "CE" / "PE") bets: +1 up (a call), -1 down (a put); null
     * when it is not an index option this cap counts (a stock, MCX, a future).
     */
    fun direction(underlying: String, exchange: String, right: String): Int? {
        if (underlying.uppercase(Locale.ENGLISH) !in INDICES || exchange.uppercase(Locale.ENGLISH) !in EXCHANGES) return null
        return when (right.uppercase(Locale.ENGLISH)) { "CE" -> 1; "PE" -> -1; else -> null }
    }

    /** "puts" / "calls" (or one of each). */
    fun words(dir: Int, n: Int): String = if (dir > 0) (if (n == 1) "call" else "calls") else (if (n == 1) "put" else "puts")

    /** One fill of the book: [buy] or a sell, [qty] units, by the order [orderId]. */
    data class Fill(val orderId: String, val symbol: String, val buy: Boolean, val qty: Int, val at: LocalDateTime)

    /** One automatic entry open now: its order, option, the way it bets and the arm that took it. */
    data class Open(val orderId: String, val symbol: String, val dir: Int, val owner: String)

    /**
     * The automatic entries open now. Per option, the BUY fills are queued by order (oldest first) and every SELL takes from the
     * oldest first (first in, first out); what remains is trimmed from the oldest to what the account holds now ([held], units;
     * an expiry or a square-off the fills do not show). The entries left whose order is automatic ([ownerOf] not null) count;
     * so do [working] automatic BUY orders not filled yet (a resting LIMIT entry). [dirOf]: the way an option bets, null for one
     * not counted ([direction]).
     */
    fun openEntries(fills: List<Fill>, held: Map<String, Int>, working: List<Pair<String, String>>,
                    dirOf: (String) -> Int?, ownerOf: (String) -> String?): List<Open> {
        val out = ArrayList<Open>()
        for ((symbol, fs) in fills.groupBy { it.symbol }) {
            val dir = dirOf(symbol) ?: continue
            val queue = ArrayList<Pair<String, Int>>()          // orderId, units still open
            for (f in fs.sortedBy { it.at }) {
                if (f.qty <= 0) continue
                if (f.buy) {
                    val i = queue.indexOfFirst { it.first == f.orderId }
                    if (i >= 0) queue[i] = f.orderId to queue[i].second + f.qty else queue += f.orderId to f.qty
                } else {
                    var left = f.qty
                    while (left > 0 && queue.isNotEmpty()) {
                        val (id, q) = queue[0]
                        if (q <= left) { left -= q; queue.removeAt(0) } else { queue[0] = id to q - left; left = 0 }
                    }
                }
            }
            var extra = queue.sumOf { it.second } - (held[symbol] ?: 0).coerceAtLeast(0)
            while (extra > 0 && queue.isNotEmpty()) {
                val (id, q) = queue[0]
                if (q <= extra) { extra -= q; queue.removeAt(0) } else { queue[0] = id to q - extra; extra = 0 }
            }
            for ((id, _) in queue) ownerOf(id)?.let { out += Open(id, symbol, dir, it) }
        }
        val seen = out.map { it.orderId }.toHashSet()
        for ((id, symbol) in working) {
            if (id in seen) continue
            val dir = dirOf(symbol) ?: continue
            ownerOf(id)?.let { out += Open(id, symbol, dir, it); seen += id }
        }
        return out
    }

    /**
     * Why an automatic entry betting [dir] may not open now, or null when it may: "same-direction cap: 2 puts already open
     * (NIFTY... by R51 Donchian 20; ...)". [max] below 1 is read as 1 (the cap never blocks everything by a bad number).
     */
    fun refusal(open: List<Open>, dir: Int, max: Int): String? {
        val same = open.filter { it.dir == dir }
        val cap = max.coerceAtLeast(1)
        if (same.size < cap) return null
        val who = same.take(4).joinToString("; ") { "${it.symbol} by ${it.owner}" } + if (same.size > 4) "; and ${same.size - 4} more" else ""
        return "same-direction cap: ${same.size} ${words(dir, same.size)} already open ($who)"
    }

    // ---- the would-be (shadow) record ----------------------------------------------------------------------------------

    /**
     * One entry the cap refused, or (live, its switch off) would have refused: kept as a would-be entry with the price it would
     * have filled at, so the arm's record can still count it. [applied] false: logged only (live in shadow).
     */
    data class Blocked(val atMs: Long, val day: LocalDate, val owner: String, val symbol: String, val dir: Int, val price: Double?,
                       val live: Boolean, val applied: Boolean, val why: String) {
        fun encode(): String = listOf(atMs.toString(), day.toString(), clean(owner), clean(symbol), dir.toString(),
            price?.let { "%.2f".format(Locale.ENGLISH, it) } ?: "", if (live) "1" else "0", if (applied) "1" else "0", clean(why)).joinToString("|")
    }

    private fun clean(s: String) = s.replace('|', '/').replace('\n', ' ')

    fun decode(line: String): Blocked? = runCatching {
        val p = line.split('|')
        if (p.size < 9) return null
        Blocked(p[0].toLong(), LocalDate.parse(p[1]), p[2], p[3], p[4].toInt(), p[5].toDoubleOrNull(), p[6] == "1", p[7] == "1", p[8])
    }.getOrNull()

    /** Liquidity 15+5's books (their plain-rules record is the 26 Oct live candidate: its capped entries are counted apart). */
    fun isLiquidity(owner: String): Boolean = owner.startsWith("Liquidity", ignoreCase = true)

    /** The same refusal again within [DEDUPE_MS] (an arm retrying its bar each look) is recorded once. */
    const val DEDUPE_MS = 10 * 60_000L

    /** [list] with [b] added unless the same owner's same option, live or paper, is already in it from the last [DEDUPE_MS]. */
    fun add(list: List<Blocked>, b: Blocked, keep: Int = 300): List<Blocked> {
        if (list.any { it.owner == b.owner && it.symbol == b.symbol && it.live == b.live && b.atMs - it.atMs in 0 until DEDUPE_MS }) return list
        return (list + b).takeLast(keep)
    }

    /**
     * The words for Bot settings and the diagnostics: "Same-direction cap (paper on, at most 2; live in shadow): today 9 paper
     * entries refused (R51 Donchian 20 4, R72 Breadth Donchian 4, Liquidity 15+5 1, counted apart); live: none would have been".
     */
    fun line(list: List<Blocked>, today: LocalDate, paperOn: Boolean, max: Int, liveOn: Boolean): String {
        val t = list.filter { it.day == today }
        val paper = t.filter { !it.live }
        val live = t.filter { it.live }
        val head = "Same-direction cap (paper ${if (paperOn) "on" else "off"}, at most $max; live ${if (liveOn) "on" else "in shadow, logged only"})"
        val p = if (paper.isEmpty()) "no paper entry refused today" else "today ${paper.size} paper ${if (paper.size == 1) "entry" else "entries"} refused (" +
            paper.groupingBy { it.owner }.eachCount().entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key} ${it.value}" } + ")"
        val liq = paper.count { isLiquidity(it.owner) }
        val l = when {
            live.isEmpty() -> "live: none"
            liveOn -> "live: ${live.count { it.applied }} refused"
            else -> "live: ${live.size} would have been refused (logged only)"
        }
        return "$head: $p" + (if (liq > 0) "; Liquidity's $liq counted apart from its plain-rules record" else "") + "; $l."
    }

    // ---- live (shadow by default): the bots' live entries kept in memory ------------------------------------------------

    /**
     * The bots' live index-option entries this process sent (memory only, never the network): each added when the entry door
     * let it through ([entered]), taken off by an exit order of the same option ([exited]) or when a positions read made after
     * it no longer shows the option held ([reconcile]). What [refusal] counts for a live entry.
     */
    class LiveLedger {
        private data class E(val symbol: String, val dir: Int, val owner: String, val atMs: Long, val id: String)
        private val list = ArrayList<E>()
        private var seq = 0L

        @Synchronized fun entered(symbol: String, dir: Int, owner: String, atMs: Long) { list += E(symbol, dir, owner, atMs, "live-${++seq}") }

        /** One entry of [symbol] closed (the oldest first). */
        @Synchronized fun exited(symbol: String) { list.indexOfFirst { it.symbol == symbol }.takeIf { it >= 0 }?.let { list.removeAt(it) } }

        /** A positions read made at [readAtMs]: an entry sent before it whose option it shows not held is closed. */
        @Synchronized fun reconcile(held: Map<String, Int>, readAtMs: Long) {
            list.removeAll { it.atMs < readAtMs && (held[it.symbol] ?: 0) <= 0 }
        }

        /** A new day: yesterday's are gone (MIS squared off; a carried option is counted again from the next read). */
        @Synchronized fun clear() { list.clear() }

        @Synchronized fun open(): List<Open> = list.map { Open(it.id, it.symbol, it.dir, it.owner) }
    }
}
