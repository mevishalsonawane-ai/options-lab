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
 *  - ON (Boss's switch in Bot settings): a LIVE entry (automatic or an approval) on a stale bar is not made. The bar is not spent: it is
 *    decided again when fresh candles land, within its fill window; past that window it is never bought late. On only lowers risk, so it
 *    needs no PIN; OFF adds live entries back, so it takes the PIN (the funds check's pattern, [FundsCheck]).
 *  - PAPER is never skipped (the idea has not passed the research gates): it is logged in shadow too, whatever the switch says.
 *
 * Memory only where the arms decide (under their lock: no settings read, no disk, no network): the switch is read once in the background
 * at the start; nothing else is kept on the phone.
 */
object LiquidityFreshBar {
    internal const val K_ON = "g.liquidityFreshBar.live"

    /** The card's and the diagnostics' name. */
    const val LABEL = "Liquidity fresh-bar check"
    /** The would-skip's words in the diary. */
    const val WOULD = "would have skipped (stale bar)"

    @Volatile private var on = false
    @Volatile private var loaded = false
    /** Boss changed the switch in this process (the start's late read never overrides it). */
    @Volatile private var switched = false
    private val lock = Any()
    /** Today's bars judged stale, by "book|bar": whether the entry was skipped (true) or only logged (false). */
    private val seen = LinkedHashMap<String, Boolean>()
    private var seenDay: LocalDate? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Bot settings' card: the switch, whether it was read, today's counts. */
    data class View(val on: Boolean = false, val loaded: Boolean = false, val wouldSkipToday: Int = 0, val skippedToday: Int = 0)
    private val _view = MutableStateFlow(View())
    val view: StateFlow<View> = _view

    fun init(@Suppress("UNUSED_PARAMETER") context: Context) {
        if (com.optionslab.app.BuildConfig.GOLD) return
        scope.launch { load() }
    }

    /** The switch from the settings vault (a worker thread only). */
    internal fun load() {
        val saved = runCatching { SecurePrefs.getBoolean(K_ON, false) }.getOrNull()
        synchronized(lock) {
            if (!switched && saved != null) on = saved
            loaded = true
        }
        publish()
    }

    /** Read once if the start's read has not landed (a worker thread only: Bot settings' card). */
    fun refresh() { if (!loaded) load() else publish() }

    /** The switch now, from memory (off until the start's read lands: the shadow, which changes nothing). */
    fun isOn(): Boolean = on

    /**
     * Whether [book]'s entry on the bar starting at [bar] (stale for [why]) is skipped: only a [live] entry with the switch on. Said once
     * per book and bar ("would have skipped (stale bar)" in shadow and on paper; "skipped" when applied) and counted for today. Memory
     * only; never throws (a failure counts as not skipped: the entry goes as before).
     */
    fun judge(book: String, bar: LocalDateTime, why: String, live: Boolean): Boolean = runCatching {
        val applied = live && on
        val key = "$book|$bar"
        val first = synchronized(lock) {
            val day = bar.toLocalDate()
            if (seenDay != day) { seen.clear(); seenDay = day }
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

    private fun publish() {
        runCatching {
            val (would, skipped) = synchronized(lock) { seen.values.count { !it } to seen.values.count { it } }
            _view.value = View(on, loaded, would, skipped)
        }
    }

    /**
     * Boss's switch (Bot settings). On only lowers risk: at once. Off adds live entries back: it takes [pinConfirmed] (the card asks the
     * PIN). What to say. A worker thread only.
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

    /** Erase everything ([PersonalStores]): as on a fresh phone - off, nothing seen. */
    fun wipe() {
        synchronized(lock) { on = false; loaded = true; switched = true; seen.clear(); seenDay = null }
        publish()
    }

    /** Whether Boss's choice or today's record is still in memory (the erase's check). */
    internal fun holdsForErase(): Boolean = on || synchronized(lock) { seen.isNotEmpty() }

    /** TEST ONLY: as a fresh start would have it (read, [on]), nothing switched, nothing seen. */
    internal fun resetForTest(on: Boolean = false, loaded: Boolean = true) {
        synchronized(lock) { this.on = on; this.loaded = loaded; switched = false; seen.clear(); seenDay = null }
        publish()
    }
}
