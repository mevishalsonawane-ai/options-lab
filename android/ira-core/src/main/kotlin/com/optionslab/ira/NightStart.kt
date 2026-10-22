package com.optionslab.ira

import java.time.LocalDate

/**
 * 22 Oct (the overnight kills: "Android stopped it for using too much CPU or battery in the background ... excessive binder
 * traffic during cached" at 02:43 and 02:51, a background trim every 15-30 minutes, the phone at 44-46 °C): the rules that keep
 * a start in the background light. Each hourly night job started the app afresh, and each start re-armed every alarm (four
 * calls into Android each) and began its heavy reads while the job itself ended in a second; and every start forgot the night's
 * CPU already spent. The app keeps the marks these rules read (a small plain file: times, a day, milliseconds - nothing about
 * the account).
 *
 * Nothing here touches the open positions' start-up steps, the watch, a stop or an order. Pure; no Android, no clock.
 */
object NightStart {
    /** An alarm that is not armed (its job is off). */
    const val OFF = -1L

    /**
     * Two estimates of the phone's boot time (the wall clock minus the time since boot) closer than this are the same boot. A
     * clock change moves the estimate: then the alarms are armed again (the safe side).
     */
    const val BOOT_SLACK_MS = 5_000L

    /**
     * An alarm as it was last armed: its time ([OFF]: not armed), the app's version and the boot it was armed in (alarms do not
     * survive a reboot or an app update).
     */
    data class AlarmMark(val atMs: Long, val version: Long, val bootAtMs: Long) {
        fun encode(): String = "$atMs|$version|$bootAtMs"

        companion object {
            /** The mark back from [encode]'s words; null when there is none or it cannot be read (then the alarm is armed). */
            fun decode(s: String?): AlarmMark? {
                val p = s?.split('|') ?: return null
                if (p.size != 3) return null
                val at = p[0].toLongOrNull() ?: return null
                val v = p[1].toLongOrNull() ?: return null
                val b = p[2].toLongOrNull() ?: return null
                return AlarmMark(at, v, b)
            }
        }
    }

    /**
     * Must the alarm be armed again at a start in the background? Only when nothing changed may it be left as it is: the same
     * time (or off both times), the same app version, the same boot, and - for an alarm that is on - Android still holding it
     * ([pendingThere]: its pending intent exists; a force stop drops both) and its time still ahead of [nowMs]. Anything unknown
     * arms it (the old behaviour).
     */
    fun needsArm(saved: AlarmMark?, want: AlarmMark, pendingThere: Boolean, nowMs: Long): Boolean {
        if (saved == null) return true
        if (saved.version != want.version) return true
        if (kotlin.math.abs(saved.bootAtMs - want.bootAtMs) > BOOT_SLACK_MS) return true
        if (saved.atMs != want.atMs) return true
        if (want.atMs == OFF) return false
        if (want.atMs <= nowMs) return true
        return !pendingThere
    }

    // ---- the night's CPU budget, kept across a restart ----------------------------------------------------------------------

    /** The spent CPU is written again once it has grown by this much since the last write (and at the end of every run). */
    const val SAVE_STEP_MS = 5_000L

    /** [budget] ([BudgetRule.budgetOf]: "2026-10-22 night") and what it has spent, as kept. */
    fun carryEncode(budget: String, spentMs: Long): String = "$budget|${spentMs.coerceAtLeast(0L)}"

    /**
     * What [budget] had spent before this process started ([saved] from [carryEncode]): 0 for another budget (a new night or
     * day), nothing kept, or words that cannot be read.
     */
    fun carried(budget: String, saved: String?): Long {
        val s = saved ?: return 0L
        val cut = s.lastIndexOf('|')
        if (cut <= 0 || s.substring(0, cut) != budget) return 0L
        return s.substring(cut + 1).toLongOrNull()?.coerceAtLeast(0L) ?: 0L
    }

    /** Is the spent CPU worth writing again ([spentMs] now, [savedMs] at the last write)? */
    fun saveDue(savedMs: Long, spentMs: Long, stepMs: Long = SAVE_STEP_MS): Boolean = spentMs - savedMs >= stepMs

    /** A once-a-day job's kept day ([saved]: an ISO date) - null when nothing is kept or it cannot be read. */
    fun dayOf(saved: String?): LocalDate? = saved?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
}
