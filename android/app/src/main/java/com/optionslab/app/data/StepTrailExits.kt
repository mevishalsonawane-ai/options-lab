package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.SecurePrefs
import com.optionslab.engine.orb.StepTrail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 15 Nov 2026 (Boss's stop rule on Liquidity, R51 and R72; per lot; CLAUDE.md's fifth exception, FAILED GATES): Boss's switch, ON by
 * default. Its third version (Boss: "Switch to + picker + breakeven at +10% (V4)") replaced the morning's ₹500 step trail under the same
 * switch, so Boss's on / off carries over.
 *
 * On, every NEW trade of Liquidity 15+5 (every book and index), R51 Donchian 20 and R72 Breadth Donchian - paper and its live copy -
 * enters with [StepTrail]'s breakeven beside its own exits (its own stop stays): the stop moves to breakeven (the fill plus the charges)
 * once the option is 10% above the fill, never down. 16 Nov, its fourth version (Boss: "For r51/r72 use Step lock (+₹500 at +₹1,000, and
 * so on) And for liquidity use Breakeven at +₹1,000"), under the same switch: a new trade also takes [StepTrail.lockFor]'s lock - R51 /
 * R72 lock +₹500 a lot once the best open P&L is +₹1,000 a lot net and step up ₹500 at a time; Liquidity moves to breakeven at +₹1,000
 * a lot if that comes before +10%. A trade that entered before keeps V4 alone (its own flag). A trade keeps the rule it
 * entered with (the flag is written on the trade, and its best price or step with it), so a restart applies it to the positions already
 * open without reading this switch, and a change of the switch never moves a stop already resting at Zerodha.
 *
 * Off puts the arms' own wider exits back for new trades - more risk on live - so switching it off takes the PIN where one is asked
 * ([com.optionslab.app.security.GuardPin], as the change timeline's Undo does: [com.optionslab.ira.AutoChange.stepTrail] is live scope).
 * On again never asks (it only tightens stops). Every change is logged in the Autopilot change timeline and the diagnostics.
 *
 * The switch is read once in the background at the start ([init]); the entries read memory only ([forEntry]). Until that read lands it
 * counts as ON - its default, and the side that only lowers risk. Nothing here touches the order or tick path.
 */
object StepTrailExits {
    internal const val K_ON = "g.stepTrail.on"

    @Volatile private var on = true
    @Volatile private var loaded = false
    /** Boss changed the switch in this process (the start's late read never overrides it). */
    @Volatile private var switched = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** [loaded]: the switch was read (until then the card says so). */
    data class View(val on: Boolean = true, val loaded: Boolean = false)
    private val _view = MutableStateFlow(View())
    /** Bot settings' card. */
    val view: StateFlow<View> = _view

    fun init(@Suppress("UNUSED_PARAMETER") context: Context) {
        if (com.optionslab.app.BuildConfig.GOLD) return
        scope.launch { load() }
    }

    /** The switch from the settings vault (a worker thread only). Logged once in the change timeline as the 15 Nov update's (Boss's ask). */
    internal fun load() {
        // Review: under the same lock as [set], so a late read never overwrites Boss's change made meanwhile.
        val logOn = synchronized(this) {
            runCatching {
                val saved = SecurePrefs.getBoolean(K_ON, true)
                if (!switched) on = saved
                loaded = true
            }
            loaded && on
        }
        publish()
        if (logOn) runCatching { com.optionslab.app.ira.Autopilot.logStepTrailOn() }
    }

    private fun publish() { runCatching { _view.value = View(on, loaded) } }

    /** The switch now, from memory (true until the start's read lands: the default, and the side that only lowers risk). */
    fun isOn(): Boolean = on

    /** Whether a new trade of the arm [source] enters with the rule (memory only; never in IraGoldAlgo, never another arm). */
    fun forEntry(source: String): Boolean = !com.optionslab.app.BuildConfig.GOLD && StepTrail.covers(source) && on

    /** Read once if the start's read has not landed (a worker thread only: Bot settings' card). */
    fun refresh() { if (!loaded) load() else publish() }

    /** The diagnostics line. Memory only. */
    fun line(): String = "${StepTrail.DIAG_V5.replaceFirstChar { it.uppercase() }}: " + when {
        !loaded -> "not read yet (counts as on)"
        on -> "on - new Liquidity 15+5, R51 and R72 trades keep their own stop and move it to breakeven at 10 percent up; R51 and R72 add " +
            "the step lock from the first step, Liquidity breakeven at the first step if sooner; paper and live"
        else -> "off - new trades use the arms' own exits"
    }

    /**
     * Boss's switch (Bot settings, the change timeline's Undo). Off needs [pinConfirmed] (the card asks the PIN where one is asked;
     * the Undo asks it itself): it puts the wider exits back, live too. On is at once. [log]: false for the change timeline's own Undo.
     * What to say.
     */
    fun set(on: Boolean, pinConfirmed: Boolean = false, log: Boolean = true): String {
        if (!on && !pinConfirmed) return "Turning the step lock and breakeven off puts the arms' own exits back alone, live too: it takes your PIN. Nothing changed."
        // Review: the saved switch is read first (a worker thread: the card and the Undo both call this off the main thread), so a
        // flip before the start's read never records a change that did not happen.
        if (!loaded) load()
        synchronized(this) {
            if (this.on == on) return "The step lock and breakeven are already ${if (on) "on" else "off"}."
            this.on = on
            loaded = true
            switched = true
        }
        runCatching { SecurePrefs.putAllSoon(mapOf(K_ON to on)) }
        runCatching { Diag.record("step-trail", "${StepTrail.DIAG_V5}: ${if (on) "off → on" else "on → off"} (by you; new trades of Liquidity 15+5, R51 and R72)") }
        publish()
        if (log) runCatching { com.optionslab.app.ira.Autopilot.logStepTrail(on) }
        return if (on) "On: new Liquidity 15+5, R51 and R72 trades keep their own stop and move it to breakeven once the option is 10% up; " +
            "R51 and R72 also lock +₹500 a lot at +₹1,000 a lot and step it up ₹500 at a time; Liquidity moves to breakeven at +₹1,000 a lot " +
            "if that comes first. Paper and live, beside their own exits (failed gates, your exception)."
        else "Off: new Liquidity 15+5, R51 and R72 trades use their own exits again. Open trades keep the exits they entered with."
    }

    /**
     * Erase everything ([PersonalStores]): the switch as on a fresh phone - on, its default ([switched]: a start's read still on its way
     * never brings Boss's old "off" back). Its saved key goes with the settings vault.
     */
    fun wipe() {
        synchronized(this) { on = true; loaded = true; switched = true }
        publish()
    }

    /** Whether Boss's own choice (off) is still in memory (the erase's tests). Memory only. */
    internal fun holdsForErase(): Boolean = !on

    /** TEST ONLY: the switch as a fresh start would have it (read, [on]), nothing switched. */
    internal fun resetForTest(on: Boolean = false, loaded: Boolean = true) {
        this.on = on; this.loaded = loaded; switched = false
        publish()
    }
}
