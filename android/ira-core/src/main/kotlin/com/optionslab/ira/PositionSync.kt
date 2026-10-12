package com.optionslab.ira

import java.util.Locale

/**
 * Zerodha reconciliation (round 4, read-only): what the app believes is open or resting - the arms' books, Pine's holdings,
 * the protections - against Zerodha's positions and order book as the regular passes last read them. It reports four
 * kinds of mismatch and nothing else; it never places, changes or cancels an order. Pure: no clock, no Android, no network.
 *
 *  - [Kind.UNTRACKED]: Zerodha holds a position no bot, Pine script or protection of the app is following;
 *  - [Kind.FLAT_AT_BROKER]: the app holds (or protects) a position Zerodha shows flat;
 *  - [Kind.QTY_DIFFERS]: both hold it, in different quantities;
 *  - [Kind.STOP_MISSING]: the app counts on a stop resting at Zerodha that is not working in the order book.
 *
 * A mismatch can be a moment's lag (an order filling between two reads), so the app says one only once it has stood in two
 * checks in a row ([Tracker]); each is noted once a day.
 */
object PositionSync {
    enum class Kind(val word: String) {
        UNTRACKED("not followed by the app"),
        FLAT_AT_BROKER("flat at Zerodha"),
        QTY_DIFFERS("quantity differs"),
        STOP_MISSING("stop not resting"),
    }

    /**
     * One thing the app believes about a live position: [who] holds or protects it, [qty] signed units (+ long), [holds]
     * false for a protection (an overlay on a position, not a holding of its own), [stopOrderId] the stop it counts on resting
     * at Zerodha, [unconfirmed] a fill Zerodha has not confirmed yet (its quantity is not checked).
     */
    data class Belief(val who: String, val symbol: String, val qty: Int, val holds: Boolean = true, val stopOrderId: String? = null,
                      val unconfirmed: Boolean = false)

    /** A Zerodha order as the order book says it: [working] still at the exchange. */
    data class Order(val id: String, val symbol: String, val status: String, val working: Boolean)

    data class Mismatch(val kind: Kind, val symbol: String, val who: String?, val appQty: Int?, val brokerQty: Int?) {
        val key: String get() = "${kind.name}:$symbol"
    }

    /**
     * The mismatches between [beliefs] and Zerodha's [positions] (tradingsymbol -> net units, all products together) and its
     * [orders] (null: the order book was not read fresh, so no stop is judged). Sorted by symbol.
     */
    fun check(beliefs: List<Belief>, positions: Map<String, Int>, orders: List<Order>?): List<Mismatch> {
        val out = ArrayList<Mismatch>()
        val bySym = beliefs.groupBy { it.symbol }
        for ((sym, bs) in bySym) {
            if (bs.any { it.unconfirmed }) continue                     // settling: the books decide it on their own pass
            val at = positions[sym] ?: 0
            val holders = bs.filter { it.holds }
            val who = bs.joinToString(", ") { it.who }.ifBlank { null }
            if (holders.isNotEmpty()) {
                val app = holders.sumOf { it.qty }
                when {
                    app != 0 && at == 0 -> out += Mismatch(Kind.FLAT_AT_BROKER, sym, who, app, 0)
                    app != at -> out += Mismatch(Kind.QTY_DIFFERS, sym, who, app, at)
                }
            } else {
                // Protections only: they guard a position; none at Zerodha (or the other way round) is a mismatch.
                val guarded = bs.first().qty
                if (at == 0 || Integer.signum(at) != Integer.signum(guarded)) out += Mismatch(Kind.FLAT_AT_BROKER, sym, who, guarded, at)
            }
            if (orders != null && at != 0) for (b in bs) {
                val id = b.stopOrderId?.takeIf { it.isNotBlank() } ?: continue
                val o = orders.firstOrNull { it.id == id }
                // A stop that filled is the exit: the position going flat is the next read's word, not a missing stop.
                if (o == null || (!o.working && !o.status.equals("COMPLETE", true)))
                    out += Mismatch(Kind.STOP_MISSING, sym, b.who, b.qty, at)
            }
        }
        for ((sym, q) in positions) if (q != 0 && sym !in bySym) out += Mismatch(Kind.UNTRACKED, sym, null, null, q)
        return out.distinctBy { it.key }.sortedWith(compareBy({ it.symbol }, { it.kind.ordinal }))
    }

    /** Plain words, no rupees: "NIFTY..CE: flat at Zerodha (the app holds 75 for ORB)". */
    fun say(m: Mismatch): String = "${m.symbol}: " + when (m.kind) {
        Kind.UNTRACKED -> "Zerodha holds ${m.brokerQty}; no bot, Pine script or protection of the app follows it"
        Kind.FLAT_AT_BROKER -> "flat at Zerodha, but the app ${if (m.who?.contains("Protection") == true && m.appQty != null) "protects" else "holds"} ${m.appQty}" +
            (m.who?.let { " ($it)" } ?: "")
        Kind.QTY_DIFFERS -> "the app holds ${m.appQty}" + (m.who?.let { " ($it)" } ?: "") + ", Zerodha ${m.brokerQty}"
        Kind.STOP_MISSING -> "the stop ${m.who?.let { "$it " } ?: ""}counts on is not resting in Zerodha's order book"
    }

    /** The diagnostics / health line. [checkedAt]: "HH:mm" of the last check, null: none yet ([why] then says why). */
    fun line(ms: List<Mismatch>, checkedAt: String?, why: String? = null): String = when {
        checkedAt == null -> "Zerodha sync: not checked yet" + (why?.let { " ($it)" } ?: "")
        ms.isEmpty() -> "Zerodha sync ($checkedAt): in sync"
        else -> "Zerodha sync ($checkedAt): ${ms.size} mismatch${if (ms.size == 1) "" else "es"} - " + ms.joinToString("; ") { say(it) }
    }

    /** Is [q] asking whether the app's positions agree with Zerodha's? */
    fun asked(q: String): Boolean {
        val t = q.lowercase(Locale.ENGLISH)
        return Regex("\\b(?:positions?|orders?|books?|stops?|app|everything)\\b.*\\b(?:in sync|synced|match(?:es|ing)?|reconcile[d]?|agree)\\b").containsMatchIn(t) ||
            Regex("\\b(?:reconcil\\w*|sync check|out of sync)\\b").containsMatchIn(t) && Regex("\\b(?:zerodha|kite|positions?|orders?|app)\\b").containsMatchIn(t)
    }

    /** Jarvis's answer (an unlocked phone only: it names positions and quantities; never rupees). */
    fun answer(ms: List<Mismatch>, checkedAt: String?, why: String?): String = when {
        checkedAt == null -> "I have not compared the app with Zerodha yet, Boss" + (why?.let { ": $it" } ?: "") + "."
        ms.isEmpty() -> "Yes, Boss: at $checkedAt the app's positions and stops matched Zerodha's books."
        else -> "Not fully, Boss. At $checkedAt: " + ms.joinToString("; ") { say(it) } +
            ". I only report this; nothing was placed, changed or cancelled - check the order book in Trade."
    }

    /**
     * Debounce and dedupe: a mismatch is [Step.confirmed] once it stood in two checks in a row, and [Step.fresh] the first
     * time it is confirmed on a day (to be noted once). Not thread-safe: the caller checks from one worker.
     */
    class Tracker {
        private var last: Set<String> = emptySet()
        private var day: String = ""
        private val noted = HashSet<String>()

        data class Step(val confirmed: List<Mismatch>, val fresh: List<Mismatch>)

        fun step(day: String, now: List<Mismatch>): Step {
            if (day != this.day) { this.day = day; noted.clear() }
            val confirmed = now.filter { it.key in last }
            last = now.map { it.key }.toSet()
            val fresh = confirmed.filter { noted.add(it.key) }
            return Step(confirmed, fresh)
        }
    }
}
