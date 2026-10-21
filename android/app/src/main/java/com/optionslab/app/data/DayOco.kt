package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.SecurePrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 21 Oct, enhancement round 4 (idea 1): Boss's switch for "stop and target as one OCO at Zerodha in the day too" ([Protections]).
 *
 * A carried (NRML, not intraday) live protection with BOTH a stop and a target rests in the day as two DAY orders (an SL and a
 * LIMIT); the app cancels the other when one fills. With the phone dead, the other one keeps resting: if the stop filled, the
 * target could later fill too and open a short. On, such a protection rests all day as ONE two-leg GTT instead (the overnight
 * GTT is kept at the open, a new protection is placed as one): whichever leg fires, Zerodha cancels the other itself.
 *
 * The trade-off, why it is OFF by default and switched on only with the PIN: a GTT's stop is checked by Zerodha's server and then
 * sends a LIMIT order 5% past the trigger, which can miss in a gap; an SL order rests at the exchange. A trailing stop moves the
 * GTT in place (never deleted and placed again). Intraday (MIS) trades, stop-only and target-only protections are unchanged.
 *
 * Memory only for its readers (the watch's passes, the stream's looks): the switch is read once in the background at start.
 */
object DayOco {
    private const val K_ON = "g.dayOco.on"

    @Volatile private var on = false
    @Volatile private var loaded = false
    /** Boss changed the switch in this process (the start's late read never overrides it). */
    @Volatile private var switched = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class View(val on: Boolean = false)
    private val _view = MutableStateFlow(View())
    /** Bot settings' card. */
    val view: StateFlow<View> = _view

    fun init(@Suppress("UNUSED_PARAMETER") context: Context) { scope.launch { load() } }

    /** The switch from the settings vault (a worker thread). Until read it is off: the day's two orders, as before this round. */
    internal fun load() {
        runCatching {
            val saved = SecurePrefs.getBoolean(K_ON, false)
            if (!switched) on = saved
            loaded = true
        }
        _view.value = View(on)
    }

    fun isOn(): Boolean = on

    /** Read once if the start's read has not landed (a worker thread only: Bot settings' card). */
    fun refresh() { if (!loaded) load() else _view.value = View(on) }

    /**
     * Boss's switch (Bot settings). Switching it on takes the PIN ([pinConfirmed]: the card asks first); off never does. Each
     * protection changes form at its next look in market hours: on, the day's two orders stay until the close and the overnight
     * GTT is then kept at the open; off, a GTT resting in the day is replaced by the day's two orders (deleted first, confirmed).
     */
    fun setOn(on: Boolean, pinConfirmed: Boolean = false): String {
        if (on && !pinConfirmed) return "Enter your PIN to rest stops and targets as one GTT."
        this.on = on
        switched = true
        runCatching { SecurePrefs.putAllSoon(mapOf(K_ON to on)) }
        runCatching { Diag.record("protection", "Stop and target as one GTT in the day: ${if (on) "on" else "off"} (by you)") }
        _view.value = View(on)
        return if (on) "On: a carried (NRML) position's stop and target rest at Zerodha as one GTT all day, from the next open (or " +
            "when set). Whichever fires, Zerodha cancels the other, even with the phone off. A GTT's stop sends a limit order, which " +
            "can miss in a gap."
        else "Off: in the day the stop and target rest as two orders again (from the next look in market hours)."
    }

    /** TEST ONLY: the switch as given, nothing saved. */
    internal fun resetForTest(on: Boolean = false) { this.on = on; loaded = true; switched = false; _view.value = View(on) }
}
