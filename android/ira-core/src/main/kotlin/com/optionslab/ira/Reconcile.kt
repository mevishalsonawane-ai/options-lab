package com.optionslab.ira

import java.util.Locale

/**
 * 19 Nov 2026 (audit batch 2: an independent safety net). Nothing compared Zerodha's real positions with ALL the app's books:
 * the Zerodha sync ([PositionSync]) runs only inside Jarvis's lowest-priority words round, and "Stop and target on my manual
 * positions" leaves the app's own orders alone - so a live position that no book followed any more (a lost entry, a book read
 * wrong after a restart) could sit at Zerodha with nobody watching it.
 *
 * Every minute the watch's stop lane reads Zerodha's net positions and asks every book what it owns; this decides, pure (no clock
 * of its own, no Android, no network):
 *  - [find]: each position Zerodha holds that no book owns, with whether an exit stop rests for it at Zerodha (a working SL / SL-M
 *    order on the closing side, or an active GTT with a closing leg; null: the order book and the GTTs could not be read);
 *  - [Tracker]: said only once two reads in a row agree and it has lasted [ALERT_AFTER_MS], then again every [REPEAT_MS] while it
 *    lasts. A failed read changes nothing (it never reads as "flat", never clears and never alerts). "Zerodha holds something"
 *    ([Tracker.heldHint]) is kept for the watch's "is anything held?" so the watch and the dead-man never sleep while Zerodha holds
 *    anything; it turns false only after two flat reads in a row, and lapses [HINT_MS] after the last good read.
 *
 * It only reports: it NEVER places, changes or cancels an order and never touches a book (so it can never add risk).
 */
object Reconcile {
    /** How often the stop lane reconciles (while logged in, in NSE's or MCX's hours). */
    const val EVERY_MS = 60_000L
    /** A position no book owns is said once it has lasted this long (and two reads agreed). */
    const val ALERT_AFTER_MS = 60_000L
    /** The lane's minute can come a little early: this much slack on [ALERT_AFTER_MS]. */
    const val SLACK_MS = 5_000L
    /** Said again this often while it lasts. */
    const val REPEAT_MS = 5 * 60_000L
    /** "Zerodha holds something" stands this long after the last good read (outside the hours nobody reads: it lapses). */
    const val HINT_MS = 10 * 60_000L

    /** A Zerodha order as its order book says it ([type]: "SL", "SL-M", "LIMIT", ...; [working]: still at the exchange). */
    data class Order(val symbol: String, val side: String, val type: String, val working: Boolean)

    /** A Zerodha GTT: [active] and each leg's side. */
    data class Gtt(val symbol: String, val active: Boolean, val sides: List<String>)

    /** A position Zerodha holds ([qty] signed units, + long) that no book of the app owns; [stop]: an exit stop rests at Zerodha. */
    data class Finding(val symbol: String, val qty: Int, val stop: Boolean?)

    /**
     * Whether an exit stop rests at Zerodha for [qty] units of [symbol]: a working SL or SL-M order on the closing side, or an active
     * GTT with a leg on that side. Null when neither book could be read (unknown: never said as "no stop").
     */
    fun hasStop(symbol: String, qty: Int, orders: List<Order>?, gtts: List<Gtt>?): Boolean? {
        if (orders == null && gtts == null) return null
        val exit = if (qty > 0) "SELL" else "BUY"
        val byOrder = orders.orEmpty().any {
            it.working && it.symbol == symbol && it.side.equals(exit, true) && it.type.uppercase(Locale.ENGLISH) in STOP_TYPES
        }
        val byGtt = gtts.orEmpty().any { g -> g.active && g.symbol == symbol && g.sides.any { it.equals(exit, true) } }
        if (byOrder || byGtt) return true
        // One of the two not read: no stop found in the other is not proof there is none.
        return if (orders == null || gtts == null) null else false
    }

    private val STOP_TYPES = setOf("SL", "SL-M")

    /**
     * The positions in [positions] (tradingsymbol -> net units, nonzero) that no book owns ([owned]: every symbol any book follows,
     * holds or protects), sorted by symbol, each with [hasStop].
     */
    fun find(positions: Map<String, Int>, owned: Set<String>, orders: List<Order>?, gtts: List<Gtt>?): List<Finding> =
        positions.filter { (s, q) -> q != 0 && s.isNotBlank() && s !in owned }.toSortedMap()
            .map { (s, q) -> Finding(s, q, hasStop(s, q, orders, gtts)) }

    /** The RISK notice's words (names and quantities only, never rupees). */
    fun say(f: Finding): String = "Zerodha holds ${f.qty} ${f.symbol}, which no book of the app follows" + when (f.stop) {
        true -> " (an exit stop rests at Zerodha)"
        false -> ", and no exit stop rests at Zerodha"
        null -> " (whether an exit stop rests there could not be read)"
    }

    /** The notice's text for [fs]. */
    fun notice(fs: List<Finding>): String = fs.joinToString("; ") { say(it) } +
        ". Check it in Kite now. Reported only: the app placed, changed or cancelled nothing."

    /**
     * The debounce and the repeat (one caller at a time: the stop lane's reconcile step). [step] with each good read's findings and
     * whether Zerodha held anything; [failed] for a read that did not come back (nothing changes).
     */
    class Tracker {
        private var prev: Map<String, Int>? = null
        private val since = HashMap<String, Long>()
        private val toldAt = HashMap<String, Long>()
        private var held: Boolean? = null
        private var heldAt = 0L
        private var flatReads = 0

        /** The findings to say now (two reads agreed, lasted [ALERT_AFTER_MS], not said in the last [REPEAT_MS]). */
        @Synchronized fun step(nowMs: Long, found: List<Finding>, brokerHeld: Boolean): List<Finding> {
            // Held at once; flat only after two flat reads in a row (one read between a fill and its book is never "flat").
            if (brokerHeld) { held = true; flatReads = 0 } else { flatReads++; if (held == null || flatReads >= 2) held = false }
            heldAt = nowMs
            val last = prev
            val now = found.associate { it.symbol to it.qty }
            prev = now
            since.keys.retainAll(now.keys)
            toldAt.keys.retainAll(now.keys)
            val out = ArrayList<Finding>()
            for (f in found) {
                val first = since.getOrPut(f.symbol) { nowMs }
                val agreed = last != null && last[f.symbol]?.let { Integer.signum(it) == Integer.signum(f.qty) } == true
                if (!agreed || nowMs - first < ALERT_AFTER_MS - SLACK_MS) continue
                val told = toldAt[f.symbol]
                if (told != null && nowMs - told in 0 until REPEAT_MS) continue
                toldAt[f.symbol] = nowMs
                out += f
            }
            return out
        }

        /** A read that failed: nothing changes (never "flat", never cleared, never said). */
        @Synchronized fun failed() {}

        /** Zerodha held something at the last good read (and that read is at most [HINT_MS] old); false when unknown or flat. */
        @Synchronized fun heldHint(nowMs: Long): Boolean = held == true && nowMs - heldAt in 0 until HINT_MS

        /** The positions no book owned at the last good read (for the diagnostics). */
        @Synchronized fun open(): Set<String> = prev?.keys.orEmpty()
    }

    /** May the reconcile step run now: logged in to Zerodha, and NSE's session or MCX's is on; at most every [EVERY_MS]. */
    fun due(loggedIn: Boolean, nseHours: Boolean, mcxOpen: Boolean, lastMs: Long, nowMs: Long): Boolean =
        loggedIn && (nseHours || mcxOpen) && (lastMs <= 0L || lastMs > nowMs || nowMs - lastMs >= EVERY_MS - SLACK_MS)
}
