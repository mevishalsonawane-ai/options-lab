package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.SecurePrefs
import com.optionslab.engine.orb.FixedRupee
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 14 Nov 2026 (Boss: "Okay, now just make a change in liquidity, r51, r72. Make it 2k profit and 1k loss fix, dont break any thing";
 * CLAUDE.md's fifth exception, FAILED GATES): Boss's switch "Fixed ₹2k target / ₹1k stop (Liquidity, R51, R72)", ON by default.
 *
 * On, every NEW trade of Liquidity 15+5 (every book and index), R51 Donchian 20 and R72 Breadth Donchian - paper and its live copy -
 * enters with [FixedRupee]'s exits beside its own: sold at +₹2,000 or -₹1,000 open P&L for the whole position, the ₹1,000 stop only
 * where it is tighter than the arm's own. A trade keeps the exits it entered with (as the 30-60-100 lock and R52's 09:25 exit do: the
 * flag is written on the trade), so a restart applies the rule to the positions already open without reading this switch, and a
 * change of the switch never moves a stop already resting at Zerodha.
 *
 * Off puts the arms' own wider exits back for new trades - more risk on live - so switching it off takes the PIN where one is asked
 * ([com.optionslab.app.security.GuardPin], as the change timeline's Undo does: [com.optionslab.ira.AutoChange.fixedRupee] is live
 * scope). On again never asks (it only adds earlier exits). Every change is logged in the Autopilot change timeline and the
 * diagnostics.
 *
 * The switch is read once in the background at the start ([init]); the entries read memory only ([forEntry]). Until that read lands it
 * counts as ON - its default, and the side that only lowers risk. Nothing here touches the order or tick path.
 */
object FixedRupeeExits {
    internal const val K_ON = "g.fixedRupee.on"

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

    /** The switch from the settings vault (a worker thread only). Logged once in the change timeline as the 14 Nov update's (Boss's ask). */
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
        if (logOn) runCatching { com.optionslab.app.ira.Autopilot.logFixedRupeeOn() }
    }

    private fun publish() { runCatching { _view.value = View(on, loaded) } }

    /** The switch now, from memory (true until the start's read lands: the default, and the side that only lowers risk). */
    fun isOn(): Boolean = on

    /** Whether a new trade of the arm [source] enters with the rule (memory only; never in IraGoldAlgo, never another arm). */
    fun forEntry(source: String): Boolean = !com.optionslab.app.BuildConfig.GOLD && FixedRupee.covers(source) && on

    /** Read once if the start's read has not landed (a worker thread only: Bot settings' card). */
    fun refresh() { if (!loaded) load() else publish() }

    /** The diagnostics line. Memory only. */
    fun line(): String = "${FixedRupee.LABEL}: " + when {
        !loaded -> "not read yet (counts as on)"
        on -> "on - new Liquidity 15+5, R51 and R72 trades are sold at +₹2,000 or -₹1,000 for the whole position, paper and live"
        else -> "off - new trades use the arms' own exits"
    }

    /**
     * Boss's switch (Bot settings, the change timeline's Undo). Off needs [pinConfirmed] (the card asks the PIN where one is asked;
     * the Undo asks it itself): it puts the wider exits back, live too. On is at once. [log]: false for the change timeline's own Undo.
     * What to say.
     */
    fun set(on: Boolean, pinConfirmed: Boolean = false, log: Boolean = true): String {
        if (!on && !pinConfirmed) return "Turning the fixed ₹2k target / ₹1k stop off puts the arms' own wider exits back, live too: it takes your PIN. Nothing changed."
        // Review: the saved switch is read first (a worker thread: the card and the Undo both call this off the main thread), so a
        // flip before the start's read never records a change that did not happen.
        if (!loaded) load()
        synchronized(this) {
            if (this.on == on) return "The fixed ₹2k target / ₹1k stop is already ${if (on) "on" else "off"}."
            this.on = on
            loaded = true
            switched = true
        }
        runCatching { SecurePrefs.putAllSoon(mapOf(K_ON to on)) }
        runCatching { Diag.record("fixed-rupee", "${FixedRupee.LABEL}: ${if (on) "off → on" else "on → off"} (by you; new trades of Liquidity 15+5, R51 and R72)") }
        publish()
        if (log) runCatching { com.optionslab.app.ira.Autopilot.logFixedRupee(on) }
        return if (on) "On: new Liquidity 15+5, R51 and R72 trades are sold at +₹2,000 or -₹1,000 open P&L for the whole position, paper and " +
            "live, beside their own exits (failed gates, your exception)."
        else "Off: new Liquidity 15+5, R51 and R72 trades use their own exits again. Open trades keep the exits they entered with."
    }

    /** TEST ONLY: the switch as a fresh start would have it (read, [on]), nothing switched. */
    internal fun resetForTest(on: Boolean = false, loaded: Boolean = true) {
        this.on = on; this.loaded = loaded; switched = false
        publish()
    }
}
