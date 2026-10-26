package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.SecurePrefs
import com.optionslab.ira.DayGttTally
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
    /** 21 Oct, enhancement round 5 (idea 1): the last week's counts ([DayGttTally]). */
    private const val K_TALLY = "g.dayOco.tally"

    @Volatile private var on = false
    @Volatile private var loaded = false
    /** Boss changed the switch in this process (the start's late read never overrides it). */
    @Volatile private var switched = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** 21 Oct, enhancement round 5 (idea 1): the day GTT's counts, the last week (memory; written on a worker thread). */
    @Volatile private var tally: List<DayGttTally.Day> = emptyList()
    @Volatile private var tallyLoaded = false
    private val tallyLock = Any()

    /** [line]: 21 Oct, round 5 - today's and the last week's counts ([line]). */
    data class View(val on: Boolean = false, val line: String = "")
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
        // 21 Oct, enhancement round 5 (idea 1): the week's counts; those seen before this read are added to them.
        runCatching {
            val saved = DayGttTally.decode(SecurePrefs.getString(K_TALLY))
            synchronized(tallyLock) { if (!tallyLoaded) { tally = DayGttTally.merge(saved, tally); tallyLoaded = true } }
            // Review: counts seen before this read were not written (nothing is, until it): written now.
            scope.launch { save() }
        }
        publish()
    }

    /**
     * 21 Oct, enhancement round 5 (idea 1): one day-GTT event counted for the diagnostics ([line]). Returns at once: memory here,
     * written on a worker thread (the protections' watch calls it; it never touches the disk or the settings itself).
     */
    fun note(e: DayGttTally.Event) {
        runCatching {
            val day = Market.today()
            synchronized(tallyLock) { tally = DayGttTally.add(tally, day, e) }
            publish()
            scope.launch { save() }
        }
    }

    private fun save() {
        runCatching {
            // Review: written under the lock, so two notes close together never save in reverse order.
            synchronized(tallyLock) { if (tallyLoaded) SecurePrefs.putAllSoon(mapOf(K_TALLY to DayGttTally.encode(tally))) }
        }
    }

    private fun publish() { runCatching { _view.value = View(on, line()) } }

    /** The diagnostics' line (round 5): the switch, today's counts and the last seven days'. Memory only. */
    fun line(): String = DayGttTally.line(on, tally, runCatching { Market.today() }.getOrElse { java.time.LocalDate.now(com.optionslab.engine.IST) })

    fun isOn(): Boolean = on

    /**
     * Off as read from the settings (review: until the start's read lands the switch reads off, and a first look after a restart
     * must never take a day GTT out for that): what turns a resting day GTT back into two orders.
     */
    fun isKnownOff(): Boolean = loaded && !on

    /** Read once if the start's read has not landed (a worker thread only: Bot settings' card). */
    fun refresh() { if (!loaded) load() else publish() }

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
        publish()
        return if (on) "On: a carried (NRML) position's stop and target rest at Zerodha as one GTT all day, from the next open (or " +
            "when set). Whichever fires, Zerodha cancels the other, even with the phone off. A GTT's stop sends a limit order, which " +
            "can miss in a gap."
        else "Off: in the day the stop and target rest as two orders again (from the next look in market hours)."
    }

    /**
     * 26 Oct: "Erase everything" ([PersonalStores]): the switch off and the week's counts forgotten. Both are settings keys, gone
     * with [SecurePrefs.wipe] (never read here), so they count as read: off, no counts. A save on its way writes these empty
     * counts, never the old ones (it copies them under [tallyLock]).
     */
    fun wipe() {
        // [switched]: a start's read still on its way (from before the erase) never turns the switch back on.
        on = false; loaded = true; switched = true
        synchronized(tallyLock) { tally = emptyList(); tallyLoaded = true }
        publish()
    }

    /** 26 Oct: whether the switch or any count is still in memory (the erase's tests). Memory only. */
    internal fun holdsForErase(): Boolean = on || tally.isNotEmpty()

    /** TEST ONLY: the switch as given, nothing saved. */
    internal fun resetForTest(on: Boolean = false, loaded: Boolean = true) {
        this.on = on; this.loaded = loaded; switched = false
        synchronized(tallyLock) { tally = emptyList(); tallyLoaded = loaded }
        publish()
    }

    /** TEST ONLY: today's count of [e]. */
    internal fun countForTest(e: DayGttTally.Event): Int = tally.firstOrNull { it.day == Market.today() }?.get(e) ?: 0
}
