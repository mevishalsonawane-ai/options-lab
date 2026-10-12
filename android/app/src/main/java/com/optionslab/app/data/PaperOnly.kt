package com.optionslab.app.data

import com.optionslab.app.security.SecurePrefs
import com.optionslab.ira.PaperOnlyPeriod
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate

/**
 * Boss's paper-only period on the phone (12 Oct: "For 10 days I will be on paper only"; the rules are ira-core's
 * [PaperOnlyPeriod]). Until its end date, switching the app to Live asks his PIN and shows the period's reason and end date
 * (the Masthead's switch and Settings → Zerodha → Mode); nothing on the order path reads it, so an exit or a square-off is never
 * blocked. It only lowers risk:
 *
 *  - [set] a period, or a later end: no PIN; an earlier end, or [end] while it runs: the PIN ([PaperOnlyPeriod.needsPin]);
 *  - its key ([KEY], "k." - a private setting) is never carried by a backup, and only the Settings card calls [set] and [end]:
 *    never Jarvis's voice, Autopilot or another agent (a source test in ira-core keeps that so).
 *
 * It never switches the mode itself: Boss does.
 */
object PaperOnly {
    const val KEY = "k.paperOnly"

    private val _period = MutableStateFlow<PaperOnlyPeriod.Period?>(null)
    /** The period as set (null: none). Read from memory after [ensureLoaded]. */
    val period: StateFlow<PaperOnlyPeriod.Period?> = _period
    @Volatile private var loaded = false

    /** Read once from the settings vault (a worker thread). */
    fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            _period.value = runCatching { PaperOnlyPeriod.decode(SecurePrefs.getString(KEY)) }.getOrNull()
            loaded = true
        }
    }

    private fun today(): LocalDate = runCatching { Market.today() }.getOrElse { LocalDate.now(com.optionslab.engine.IST) }

    /** The period in force today, or null (memory only: what the Live switch asks). */
    fun activeNow(): PaperOnlyPeriod.Period? = _period.value?.takeIf { PaperOnlyPeriod.active(it, today()) }

    /**
     * The Settings card's "Save": a period to [until] with [reason]. Without [pinConfirmed] only a change that lowers risk is
     * made (a new period, a later end). What to say. A worker thread.
     */
    fun set(until: LocalDate, reason: String, pinConfirmed: Boolean): String {
        ensureLoaded()
        val t = today()
        PaperOnlyPeriod.invalid(until, t)?.let { return it }
        val old = _period.value
        val new = PaperOnlyPeriod.Period(until, reason.trim().take(PaperOnlyPeriod.MAX_REASON), old?.takeIf { PaperOnlyPeriod.active(it, t) }?.setOn ?: t)
        if (PaperOnlyPeriod.needsPin(old, new, t) && !pinConfirmed) return "An earlier end needs your PIN."
        SecurePrefs.put(KEY, PaperOnlyPeriod.encode(new))
        _period.value = new
        runCatching { Diag.record("mode", "paper-only period until ${new.until}") }
        return PaperOnlyPeriod.status(new, t) + ". Switching to Live asks your PIN until then; exits are never blocked."
    }

    /** The Settings card's "End now": ends the period (the PIN while it runs). What to say. A worker thread. */
    fun end(pinConfirmed: Boolean): String {
        ensureLoaded()
        val old = _period.value ?: return "No paper-only period is set."
        if (PaperOnlyPeriod.needsPin(old, null, today()) && !pinConfirmed) return "Ending the paper-only period needs your PIN."
        SecurePrefs.put(KEY, null)
        _period.value = null
        runCatching { Diag.record("mode", "paper-only period ended by you") }
        return "The paper-only period is over. The app stays in its mode until you switch it."
    }

    /** TEST ONLY. */
    internal fun resetForTest(p: PaperOnlyPeriod.Period? = null) { _period.value = p; loaded = true }
}
