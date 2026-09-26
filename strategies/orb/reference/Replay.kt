package com.iraalgo.app.rules
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime

data class Trade(val signalBar: String, val exitBar: String, val right: String, val entry: Double, val exit: Double, val why: String)

object Replay {
    private fun r2(x: Double) = BigDecimal(x).setScale(2, RoundingMode.HALF_EVEN).toDouble()
    private fun hhmm(t: LocalDateTime) = "%02d:%02d".format(t.hour, t.minute)

    fun day(arm: Arm, index: List<Bar>, ce: List<Bar>, pe: List<Bar>, points: Double = 40.0): List<Trade> {
        val rng = OrbRules.openingRange(index) ?: return emptyList()
        val trades = mutableListOf<Trade>()
        var k = 0; var lastExit: LocalDateTime? = null; val n = index.size
        while (k < n - 1) {
            val (direction, _) = OrbRules.entrySignal(index.subList(0, k + 1), rng, arm, lastExit)
            if (direction == 0) { k++; continue }
            val right = if (direction > 0) "CE" else "PE"
            val leg = if (direction > 0) ce else pe
            val entry = leg[k + 1].open
            if (!(entry > 0)) { k++; continue }
            var exitPx = leg[n - 1].close; var x = n - 1; var why = "last_bar"
            for (j in k + 1 until n) {
                val t = index[j].start
                if (!t.toLocalTime().isBefore(OrbRules.SQUARE_OFF)) { exitPx = leg[j].open; x = j; why = "session_end"; break }
                if (OrbRules.exitReason(entry, leg[j].low, t, points) == "stop") { exitPx = minOf(entry - points, leg[j].open); x = j; why = "stop"; break }
                if (OrbRules.exitReason(entry, leg[j].high, t, points) == "target") { exitPx = entry + points; x = j; why = "target"; break }
            }
            trades += Trade(hhmm(index[k].start), hhmm(index[x].start), right, r2(entry), r2(exitPx), why)
            lastExit = index[x].start
            k = x + 1
        }
        return trades
    }
}
