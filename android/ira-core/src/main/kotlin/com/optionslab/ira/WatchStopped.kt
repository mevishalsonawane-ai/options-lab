package com.optionslab.ira

import java.time.Duration
import java.time.LocalTime
import java.util.Locale

/**
 * The order watch stopped while positions were open (usefulness round 21, 2026-10-05). On 5 Oct the notification said
 * "Order watch stopped: No check since 09:31 ... Tap to open IraAlgo and restart it." at 09:35 with paper positions open,
 * and nothing said which positions, or which of their stops and targets, had gone unwatched. When the dead-man check
 * finds the watch silent in market hours (it has already tried to restart it), Jarvis puts once in the chat: since when,
 * each open position and what about it is now unwatched - a stop or target the app keeps itself, or a paper book's
 * resting stop (both only checked while the watch runs) - and which stop still works (one resting at Zerodha), then
 * what keeps the phone from stopping it again (IraAlgo's battery set to Unrestricted). It only speaks: nothing is placed,
 * moved, closed or restarted here. With no open position it says nothing (the notification is enough). Pure.
 */
object WatchStopped {
    /**
     * One open position. [where] "Paper" or "Zerodha"; [qty] +long / -short; [by] the arm or strategy holding it (null:
     * Boss's own); [stop] / [target] its levels; [stopAtBroker] true when its stop is an order resting at Zerodha (the
     * exchange triggers it with no watch).
     */
    data class Leg(
        val where: String, val symbol: String, val qty: Int, val by: String? = null,
        val stop: Double? = null, val target: Double? = null, val stopAtBroker: Boolean = false,
    )

    const val BATTERY = "So the phone does not stop it again, set IraAlgo's battery to Unrestricted: App settings, then Battery, then Unrestricted."
    const val BATTERY_SET = "IraAlgo's battery is already Unrestricted, so something else stopped it (the phone's own cleaner, or the app closing); opening IraAlgo brings it back."
    const val BATTERY_MAYBE = "If IraAlgo's battery is not set to Unrestricted, set it (App settings, then Battery, then Unrestricted) so the phone does not stop it again."
    const val NOTE = "I have placed, moved and closed nothing, Boss; the app has already tried to restart the watch."

    private fun px(x: Double) = "%.2f".format(Locale.ENGLISH, x).removeSuffix(".00")
    private fun qty(q: Int) = if (q < 0) "short ${-q}" else "$q"

    private fun line(l: Leg): String {
        val who = l.by?.let { " ($it)" } ?: ""
        val head = "${l.where} ${l.symbol} ${qty(l.qty)}$who"
        val parts = ArrayList<String>()
        when {
            l.stop != null && l.stopAtBroker -> parts += "its stop ${px(l.stop)} rests at Zerodha, so that still works"
            l.stop != null && l.where == "Paper" -> parts += "its stop ${px(l.stop)} is not checked"
            l.stop != null -> parts += "its stop ${px(l.stop)} is kept by the app and is not checked"
            else -> parts += "no stop set"
        }
        if (l.target != null) parts += "its target ${px(l.target)} is not checked"
        if (l.by != null) parts += "its exit rules wait for the watch"
        return "- $head: ${parts.joinToString("; ")}."
    }

    /**
     * What Jarvis says, or null with no open position. [since] the last check today (null: none today); [now] the time
     * now; [unrestricted] IraAlgo's battery setting (null: unknown); [zerodhaUnread] true when Boss is logged in to Zerodha
     * but its positions could not be read just now (the relay timing out).
     */
    fun text(since: LocalTime?, now: LocalTime, legs: List<Leg>, unrestricted: Boolean?, zerodhaUnread: Boolean = false): String? {
        if (legs.isEmpty()) return null
        val ago = since?.let { Duration.between(it, now).toMinutes() }?.takeIf { it >= 0 }
        val stopped = when {
            since == null -> "the order watch has not run today"
            ago != null && ago >= 1 -> "the order watch stopped: no check since ${since.withSecond(0).withNano(0)} ($ago minute${if (ago == 1L) "" else "s"} ago)"
            else -> "the order watch stopped: no check since ${since.withSecond(0).withNano(0)}"
        }
        val n = legs.size
        val out = StringBuilder("Boss, $stopped, with ${if (n == 1) "1 position" else "$n positions"} open. Until it runs again:\n")
        // Paper first (nothing about it works without the watch), then Zerodha.
        legs.sortedBy { if (it.where == "Paper") 0 else 1 }.forEach { out.append(line(it)).append('\n') }
        if (zerodhaUnread) out.append("I could not read your Zerodha positions just now; any open there are unwatched too, except stops resting at Zerodha.\n")
        val bare = legs.count { !(it.stop != null && it.stopAtBroker) }
        out.append(if (bare == 0) "Every stop here rests at Zerodha; only targets and exit rules wait. "
            else "Opening IraAlgo restarts the watch. ")
        out.append(when (unrestricted) { false -> BATTERY; true -> BATTERY_SET; null -> BATTERY_MAYBE }).append(' ')
        out.append(NOTE)
        return out.toString().trim()
    }
}
