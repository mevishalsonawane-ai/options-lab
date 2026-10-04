package com.optionslab.ira

import com.optionslab.ira.Command.Kind

/**
 * Jarvis checks his own work (Boss, 4 Oct, part 3 of "an AGI for this app"): after an action said it was done, the
 * app is read again and the action's effect looked for - the kill switch really on, paper really on, the bots really
 * stopped, nothing left open or working. A result that did not take effect is said plainly (and a plan stops there).
 * Pure: the app reads the facts.
 */
object Verify {
    /** The app as read after the action. Null: could not be read (then not judged). */
    data class Facts(
        val killOn: Boolean? = null, val live: Boolean? = null, val botsStopped: Boolean? = null,
        val anyArmed: Boolean? = null, val openPositions: Int? = null, val workingOrders: Int? = null,
    )

    /** Which facts [k]'s effect is seen in (empty: nothing to check). */
    fun needs(k: Kind): Set<String> = when (k) {
        Kind.KILL_ON -> setOf("kill")
        Kind.KILL_OFF -> setOf("kill")
        Kind.MODE_PAPER -> setOf("live")
        Kind.STOP_ALL -> setOf("bots", "armed")
        Kind.CLOSE_ALL -> setOf("positions")
        Kind.CANCEL_ALL -> setOf("orders")
        Kind.EXIT_ALL -> setOf("kill", "positions")
        else -> emptySet()
    }

    /** Null when [k] took effect (or cannot be judged), else what is still wrong, in words. */
    fun problem(k: Kind, f: Facts): String? = when (k) {
        Kind.KILL_ON -> if (f.killOn == false) "the kill switch still reads off" else null
        Kind.KILL_OFF -> if (f.killOn == true) "the kill switch still reads on" else null
        Kind.MODE_PAPER -> if (f.live == true) "the app is still in Live" else null
        Kind.STOP_ALL -> when { f.botsStopped == false -> "the strategies are not marked stopped"; f.anyArmed == true -> "an arm is still armed"; else -> null }
        Kind.CLOSE_ALL -> f.openPositions?.takeIf { it > 0 }?.let { "$it position${if (it > 1) "s are" else " is"} still open (an exit may still be filling)" }
        Kind.CANCEL_ALL -> f.workingOrders?.takeIf { it > 0 }?.let { "$it order${if (it > 1) "s are" else " is"} still working" }
        Kind.EXIT_ALL -> when {
            f.killOn == false -> "the kill switch still reads off"
            (f.openPositions ?: 0) > 0 -> "${f.openPositions} position${if (f.openPositions!! > 1) "s are" else " is"} still open (an exit may still be filling)"
            else -> null
        }
        else -> null
    }

    /** The result as said back: as it was, plus the check's word. */
    fun say(result: String, problem: String?, checked: Boolean): String = when {
        problem != null -> "Not done as asked: I checked and $problem. ($result)"
        checked -> "$result Checked: it took effect."
        else -> result
    }
}
