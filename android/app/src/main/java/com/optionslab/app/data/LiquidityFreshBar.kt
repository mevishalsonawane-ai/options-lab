package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.SecurePrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 20 Nov 2026 (audit: "Liquidity has no fresh-bar check, so a live market buy can go minutes late"): Liquidity 15+5's fresh-bar check.
 *
 * A book decides on its chart's last completed bar. That bar is STALE when it is seen past its fill window (the research fills a bar at
 * its close or up to 2 minutes after it, [com.optionslab.engine.orb.LiquidityRules.window]), when its own last minute is missing from the
 * index's candles (it was built from part of its minutes), or when the index's newest minute is older than it should be (the feed fell
 * behind). [OrbArms.staleBar] says which.
 *
 *  - SHADOW by default (CLAUDE.md: anything new that could skip a live trade starts logged only, behind a switch that is off by default):
 *    the signal is decided and entered exactly as before; what the check would have skipped is logged ("would have skipped (stale bar)")
 *    and counted per day, on Bot settings' card.
 *  - ON (Boss's switch in Bot settings): a LIVE entry (automatic or an approval) on a stale bar is not made. The signal waits (in memory)
 *    and is decided again when fresh candles land, within its fill window; past that window it is never bought late. On only lowers risk,
 *    so it needs no PIN; OFF adds live entries back, so it takes the PIN (the funds check's pattern, [FundsCheck]).
 *  - PAPER is never skipped (the idea has not passed the research gates): it is logged in shadow too, whatever the switch says.
 *
 * Its sibling on the same card, the LIST WAIT (20 Nov 2026, coordinator: it can add a live entry, so it starts in shadow): a signal decided
 * while today's instrument list is not loaded yet. OFF (the default) as before: lost as "no_contract", logged "would have waited for the
 * list and entered" and counted. ON: the signal waits for the list (in memory) and is decided again within its fill window, never late.
 * On adds live entries, so it takes the PIN; off lowers risk, so it needs none.
 *
 * Memory only where the arms decide (under their lock: no settings read, no disk, no network): the switches are read once in the
 * background at the start; nothing else is kept on the phone.
 */
object LiquidityFreshBar {
    internal const val K_ON = "g.liquidityFreshBar.live"
    internal const val K_LIST = "g.liquidityListWait.live"

    /** The card's and the diagnostics' name. */
    const val LABEL = "Liquidity fresh-bar check"
    /** The would-skip's words in the diary. */
    const val WOULD = "would have skipped (stale bar)"
    /** The list wait's name. */
    const val LIST_LABEL = "Liquidity list wait"
    /** The list wait's words in the diary while it is off. */
    const val WOULD_WAIT = "would have waited for the list and entered"

    @Volatile private var on = false
    @Volatile private var listOn = false
    @Volatile private var loaded = false
    /** Boss changed a switch in this process (the start's late read never overrides it). */
    @Volatile private var switched = false
    @Volatile private var listSwitched = false
    private val lock = Any()
    /** Today's bars judged stale, by "book|bar": whether the entry was skipped (true) or only logged (false). */
    private val seen = LinkedHashMap<String, Boolean>()
    /** Today's signals the list wait (off) would have kept, by "book|bar". */
    private val listSeen = LinkedHashSet<String>()
    private var seenDay: LocalDate? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Bot settings' card: the switches, whether they were read, today's counts. */
    data class View(val on: Boolean = false, val loaded: Boolean = false, val wouldSkipToday: Int = 0, val skippedToday: Int = 0,
                    val listOn: Boolean = false, val listWouldWaitToday: Int = 0)
    private val _view = MutableStateFlow(View())
    val view: StateFlow<View> = _view

    fun init(@Suppress("UNUSED_PARAMETER") context: Context) {
        if (com.optionslab.app.BuildConfig.GOLD) return
        scope.launch { load() }
    }

    /** The switches from the settings vault (a worker thread only). */
    internal fun load() {
        val saved = runCatching { SecurePrefs.getBoolean(K_ON, false) }.getOrNull()
        val list = runCatching { SecurePrefs.getBoolean(K_LIST, false) }.getOrNull()
        synchronized(lock) {
            if (!switched && saved != null) on = saved
            if (!listSwitched && list != null) listOn = list
            loaded = true
        }
        publish()
    }

    /** Read once if the start's read has not landed (a worker thread only: Bot settings' card). */
    fun refresh() { if (!loaded) load() else publish() }

    /** The fresh-bar switch now, from memory (off until the start's read lands: the shadow, which changes nothing). */
    fun isOn(): Boolean = on

    /** The list wait now, from memory (off until the start's read lands: as before, which adds nothing). */
    fun listWaitOn(): Boolean = listOn

    /** Today's day for the records, cleared when [day] is a new one. Under [lock]. */
    private fun dayOf(day: LocalDate) { if (seenDay != day) { seen.clear(); listSeen.clear(); seenDay = day } }

    /**
     * Whether [book]'s entry on the bar starting at [bar] (stale for [why]) is skipped: only a [live] entry with the switch on. Said once
     * per book and bar ("would have skipped (stale bar)" in shadow and on paper; "skipped" when applied) and counted for today. Memory
     * only; never throws (a failure counts as not skipped: the entry goes as before).
     */
    fun judge(book: String, bar: LocalDateTime, why: String, live: Boolean): Boolean = runCatching {
        val applied = live && on
        val key = "$book|$bar"
        val first = synchronized(lock) {
            dayOf(bar.toLocalDate())
            val before = seen[key]
            seen[key] = applied || before == true
            if (seen.size > 500) seen.remove(seen.keys.first())
            before == null || (applied && before == false)
        }
        if (first) runCatching {
            Diag.record("orb", "$book ${bar.toLocalTime()} bar: " + (if (applied) "live entry skipped (stale bar, fresh-bar check on; decided again " +
                "if fresh candles land in its fill window)" else "$WOULD - ${if (live) "shadow: the live entry goes as before" else "paper: entered as before"}") + ": $why")
        }
        if (first) publish()
        applied
    }.getOrDefault(false)

    /** The list wait off: [book]'s signal on the bar starting at [bar] goes on as before (no contract); said and counted once. Memory only. */
    fun listWouldWait(book: String, bar: LocalDateTime) {
        runCatching {
            val first = synchronized(lock) {
                dayOf(bar.toLocalDate())
                if (listSeen.size > 500) listSeen.remove(listSeen.first())
                listSeen.add("$book|$bar")
            }
            if (first) {
                runCatching { Diag.record("orb", "$book ${bar.toLocalTime()} bar: $WOULD_WAIT (today's instrument list not loaded; list wait off: " +
                    "no contract, as before)") }
                publish()
            }
        }
    }

    private fun publish() {
        runCatching {
            val v = synchronized(lock) {
                View(on, loaded, seen.values.count { !it }, seen.values.count { it }, listOn, listSeen.size)
            }
            _view.value = v
        }
    }

    /**
     * Boss's fresh-bar switch (Bot settings). On only lowers risk: at once. Off adds live entries back: it takes [pinConfirmed] (the card
     * asks the PIN). What to say. A worker thread only.
     */
    fun set(on: Boolean, pinConfirmed: Boolean = false): String {
        if (!on && !pinConfirmed) return "Turning the fresh-bar check off lets Liquidity's live entries on a late or stale bar go again: it takes your PIN. Nothing changed."
        if (!loaded) load()
        synchronized(lock) {
            if (this.on == on) return "The fresh-bar check is already ${if (on) "on" else "off (shadow)"}."
            this.on = on
            loaded = true
            switched = true
        }
        runCatching { SecurePrefs.putAllSoon(mapOf(K_ON to on)) }
        runCatching { Diag.record("orb", "$LABEL: ${if (on) "shadow → on" else "on → shadow"} (by you)") }
        publish()
        return if (on) "On: a Liquidity live entry on a late or stale bar is not made; the bar is decided again if fresh candles land in time."
        else "Off: in shadow - such entries go to Zerodha as before; each is logged."
    }

    /**
     * Boss's list-wait switch (Bot settings). On adds live entries (a signal kept until the list lands): it takes [pinConfirmed]. Off is
     * as before (lowers risk): at once. What to say. A worker thread only.
     */
    fun setListWait(on: Boolean, pinConfirmed: Boolean = false): String {
        if (on && !pinConfirmed) return "Turning the list wait on lets a Liquidity signal wait for today's instrument list and then enter live: it takes your PIN. Nothing changed."
        if (!loaded) load()
        synchronized(lock) {
            if (listOn == on) return "The list wait is already ${if (on) "on" else "off"}."
            listOn = on
            loaded = true
            listSwitched = true
        }
        runCatching { SecurePrefs.putAllSoon(mapOf(K_LIST to on)) }
        runCatching { Diag.record("orb", "$LIST_LABEL: ${if (on) "off → on" else "on → off"} (by you)") }
        publish()
        return if (on) "On: a Liquidity signal decided before today's instrument list loads waits for it, within its fill window; never bought late."
        else "Off: such a signal is lost as before (no contract); each is logged."
    }

    /** Erase everything ([PersonalStores]): as on a fresh phone - both off, nothing seen. */
    fun wipe() {
        synchronized(lock) {
            on = false; listOn = false; loaded = true; switched = true; listSwitched = true
            seen.clear(); listSeen.clear(); seenDay = null
        }
        publish()
    }

    /** Whether Boss's choices or today's record are still in memory (the erase's check). */
    internal fun holdsForErase(): Boolean = on || listOn || synchronized(lock) { seen.isNotEmpty() || listSeen.isNotEmpty() }

    /** TEST ONLY: as a fresh start would have it (read, [on], [listOn]), nothing switched, nothing seen. */
    internal fun resetForTest(on: Boolean = false, loaded: Boolean = true, listOn: Boolean = false) {
        synchronized(lock) {
            this.on = on; this.listOn = listOn; this.loaded = loaded; switched = false; listSwitched = false
            seen.clear(); listSeen.clear(); seenDay = null
        }
        publish()
    }
}
