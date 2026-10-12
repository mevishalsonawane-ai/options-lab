package com.optionslab.ira

import java.util.Locale

/**
 * Does anything armed go against a standing decision? (12 Oct: Jarvis switched on paper trading for a SENSEX strategy - BSE,
 * where the app places no orders - and a FINNIFTY one, barred by research X1 and Boss's yes on 9 Oct; and ORB stayed armed all
 * day though the morning's own trade check said "Tested: ORB lost in both years ... better switched off" - one bullet among
 * twenty.) Each armed bot or script is checked against:
 *
 *  - where it may trade at all ([PineVenue.alertsOnlyWhy]: BSE's indices, FINNIFTY): trading there is SERIOUS;
 *  - the research's tested record ([TradeCheck.RECORD]): lost in both tested years - a warning on paper, SERIOUS live.
 *
 * One [Violation] each, said plainly with the fix; the morning check shows each as a failing (✗) line and the Automation card
 * as a problem. Nothing is switched off here: Boss decides. Pure.
 */
object PolicyCheck {
    /** One armed thing: [who] ("ORB", a Pine script's name), on [symbol] (null: its usual index), [trades] places orders (false: alerts only), [live]. */
    data class Armed(val who: String, val symbol: String? = null, val trades: Boolean = true, val live: Boolean = false, val kind: String = "")

    data class Violation(val key: String, val severity: OpenProblems.Severity, val text: String, val fix: String) {
        /** The morning check's failing line. */
        fun line(): String = "Policy: $text. $fix"
    }

    private fun rs(x: Double) = (if (x < 0) "-Rs " else "+Rs ") + String.format(Locale.ENGLISH, "%,.0f", kotlin.math.abs(x))

    /** The research's tested record of [who] (both years' rupees a lot), or null when it has none. */
    fun record(who: String): Pair<Double, Double>? = TradeCheck.RECORD[who.trim()]

    fun check(armed: List<Armed>): List<Violation> {
        val out = ArrayList<Violation>()
        for (a in armed) {
            if (!a.trades) continue
            val what = (if (a.kind.isNotEmpty()) "${a.kind} " else "") + "\"${a.who}\""
            val acct = if (a.live) "LIVE" else "paper"
            a.symbol?.let { s -> PineVenue.alertsOnlyWhy(s)?.let { why ->
                out += Violation("policy:${a.who}:${s.uppercase(Locale.ENGLISH)}:venue", OpenProblems.Severity.SERIOUS,
                    "$what auto-trades ${s.uppercase(Locale.ENGLISH)} on $acct, but $why", "Set it to alerts only, or switch it off")
            } }
            record(a.who)?.let { (y1, y2) ->
                if (y1 < 0 && y2 < 0) out += Violation("policy:${a.who}:research", if (a.live) OpenProblems.Severity.SERIOUS else OpenProblems.Severity.WARN,
                    "$what is armed on $acct, but it lost in both tested years (${rs(y1)}, ${rs(y2)} a lot, real costs)",
                    "Switch it off" + if (a.live) ", or move it to paper" else ", or keep it only to watch on paper knowing it is expected to lose")
            }
        }
        return out.distinctBy { it.key }
    }
}
