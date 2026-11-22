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
import java.time.LocalDateTime

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
 * a lot if that comes before +10%. A trade that entered before keeps V4 alone (its own flag). The fourth version has a switch of its own
 * under this one ([lockOn], on by default; logged as V4 -> V5 in the change timeline): its Undo goes back to V4 alone, with the PIN. When it
 * first took effect on this phone is kept ([lockSince]), so a live trade rebuilt from Zerodha keeps the version it entered with. A trade keeps the rule it
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
    /** 16 Nov: the fourth version's own switch (the step lock / breakeven at +₹1,000 on top of V4), and when it first took effect. */
    internal const val K_LOCK = "g.stepTrail.lock"
    internal const val K_LOCK_SINCE = "g.stepTrail.lockSince"
    /**
     * 16 Nov (review): each time Boss switched the fourth version off or on (IST, "time|1" on / "time|0" off, ";" between, the newest
     * [HISTORY_KEPT]), so a rebuilt trade's version can be told whenever it was placed.
     */
    internal const val K_LOCK_HISTORY = "g.stepTrail.lockHistory"
    private const val HISTORY_KEPT = 50

    @Volatile private var lock = true
    /** 16 Nov: when the fourth version first took effect on this phone (IST; null: not yet, or not read). */
    @Volatile private var since: LocalDateTime? = null
    @Volatile private var changes: List<Pair<LocalDateTime, Boolean>> = emptyList()
    /** 16 Nov (review): Boss changed the fourth version's switch in this process (its own flag: a late read never overrides it). */
    @Volatile private var switchedLock = false
    /** 16 Nov (review): the fourth version's saved switch was read (or Boss set it): until then the card reads it again. */
    @Volatile private var lockRead = false

    @Volatile private var on = true
    @Volatile private var loaded = false
    /** Boss changed the switch in this process (the start's late read never overrides it). */
    @Volatile private var switched = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + ScopeGuard.handler("StepTrailExits"))

    /** [loaded]: the switch was read (until then the card says so). */
    data class View(val on: Boolean = true, val loaded: Boolean = false,
                    /** 16 Nov: the fourth version's switch (the step lock / breakeven at +₹1,000 on top of V4). */
                    val lock: Boolean = true)
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
                val savedLock = SecurePrefs.getBoolean(K_LOCK, true)
                if (!switched) on = saved
                if (!switchedLock) lock = savedLock
                fun time(k: String) = SecurePrefs.getString(k)?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
                since = time(K_LOCK_SINCE) ?: since
                // Review: merged with the flips Boss made in this process before the read landed (never overwritten by the older copy).
                val savedHistory = SecurePrefs.getString(K_LOCK_HISTORY)?.let { historyOf(it) }.orEmpty()
                changes = (savedHistory + changes).distinct().sortedBy { it.first }.takeLast(HISTORY_KEPT)
                lockRead = true
                loaded = true
            }
            loaded && on
        }
        noteSince()
        publish()
        if (logOn) runCatching { com.optionslab.app.ira.Autopilot.logStepTrailOn() }
        // 16 Nov: the fourth version logged once as what it is (V4 -> V5), Boss's ask.
        if (logOn && lock) runCatching { com.optionslab.app.ira.Autopilot.logStepLockOn() }
    }

    /**
     * 16 Nov: when the fourth version's switch is on (read) and no start time is kept yet, now is kept as its start (a worker thread
     * only). Review: by its own switch alone - the rule's main switch off at the update does not delay it.
     */
    private fun noteSince() {
        val now = synchronized(this) { if (!(loaded && lock) || since != null) return; nowIst().also { since = it } }
        runCatching { SecurePrefs.putAllSoon(mapOf(K_LOCK_SINCE to now.toString())) }
    }

    private fun nowIst(): LocalDateTime = runCatching { Market.now().toLocalDateTime() }.getOrNull() ?: LocalDateTime.now(com.optionslab.engine.IST)

    private fun publish() { runCatching { _view.value = View(on, loaded, lock) } }

    /** 16 Nov: the fourth version's switch now, from memory (on until the start's read lands: its default, the side that lowers risk). */
    fun lockOn(): Boolean = lock

    /** 16 Nov: when the fourth version first took effect on this phone (memory; null: not known). */
    fun lockSince(): LocalDateTime? = since

    /**
     * 16 Nov (review): whether a trade placed at [t] entered with the fourth version, from when it took effect ([lockSince]) and Boss's
     * last switch-off and switch-on of it; null: cannot be told (no record of when it took effect). Memory only.
     */
    fun lockAt(t: LocalDateTime): Boolean? {
        val s0 = since ?: return null
        if (t.isBefore(s0)) return false
        // The switch as Boss last set it at or before [t] (on until their first change).
        return changes.lastOrNull { !it.first.isAfter(t) }?.second ?: true
    }

    /** [h] ("time|1;time|0;...") as Boss's changes, oldest first; unreadable parts skipped. */
    private fun historyOf(h: String): List<Pair<LocalDateTime, Boolean>> = h.split(';').mapNotNull { part ->
        val bits = part.split('|')
        if (bits.size != 2) null else runCatching { LocalDateTime.parse(bits[0]) to (bits[1] == "1") }.getOrNull()
    }.sortedBy { it.first }

    private fun historyText(): String = changes.joinToString(";") { "${it.first}|${if (it.second) 1 else 0}" }

    /**
     * 16 Nov: the fourth version's switch (Bot settings, the change timeline's Undo). Off - back to V4 alone for new trades - needs
     * [pinConfirmed] (it takes the step lock / breakeven at +₹1,000 away, live too); on is at once. [log]: false for the Undo. What to say.
     */
    fun setLock(on: Boolean, pinConfirmed: Boolean = false, log: Boolean = true): String {
        if (!on && !pinConfirmed) return "Going back to the breakeven at +10% alone takes the step lock away, live too: it takes your PIN. Nothing changed."
        if (!loaded) load()
        synchronized(this) {
            if (this.lock == on) return "The step lock and breakeven at +₹1,000 are already ${if (on) "on" else "off"}."
            this.lock = on
            loaded = true
            lockRead = true
            switchedLock = true
        }
        val at = nowIst()
        val text = synchronized(this) { changes = (changes + (at to on)).takeLast(HISTORY_KEPT); historyText() }
        runCatching { SecurePrefs.putAllSoon(mapOf(K_LOCK to on, K_LOCK_HISTORY to text)) }
        noteSince()
        runCatching { Diag.record("step-trail", "${StepTrail.DIAG_LOCK}: ${if (on) "off → on" else "on → off"} (by you; new trades of Liquidity 15+5, R51 and R72)") }
        publish()
        if (log) runCatching { com.optionslab.app.ira.Autopilot.logStepLock(on) }
        return if (on) "On: new R51 and R72 trades also lock +₹500 a lot at +₹1,000 a lot and step it up ₹500 at a time; new Liquidity trades " +
            "move to breakeven at +₹1,000 a lot if that comes before +10%. Paper and live (failed gates, your exception)."
        else "Back to V4: new trades keep the breakeven at +10% alone (no step lock). Open trades keep the version they entered with."
    }

    /** The switch now, from memory (true until the start's read lands: the default, and the side that only lowers risk). */
    fun isOn(): Boolean = on

    /** Whether a new trade of the arm [source] enters with the rule (memory only; never in IraGoldAlgo, never another arm). */
    fun forEntry(source: String): Boolean = !com.optionslab.app.BuildConfig.GOLD && StepTrail.covers(source) && on

    /**
     * 16 Nov (review): the saved switches and the fourth version's history read now if the start's read has not landed - a worker thread
     * only (a live trade rebuilt from Zerodha decides its version by them, [ArmLive.rebuiltRule], after its stop is watched).
     */
    fun ensureRead() { if (!loaded || !lockRead) load() }

    /** Read once if the start's read has not landed (a worker thread only: Bot settings' card). */
    fun refresh() { if (!loaded || !lockRead) load() else publish() }

    /** The diagnostics line. Memory only. */
    fun line(): String = "${StepTrail.DIAG.replaceFirstChar { it.uppercase() }}: " + when {
        !loaded -> "not read yet (counts as on)"
        on && lock -> "on - new Liquidity 15+5, R51 and R72 trades keep their own stop and move it to breakeven at 10 percent up; " +
            "${StepTrail.DIAG_LOCK} on top; paper and live"
        on -> "on - new Liquidity 15+5, R51 and R72 trades keep their own stop and move it to breakeven at 10 percent up; " +
            "${StepTrail.DIAG_LOCK} off (V4 alone); paper and live"
        else -> "off - new trades use the arms' own exits"
    }

    /**
     * Boss's switch (Bot settings, the change timeline's Undo). Off needs [pinConfirmed] (the card asks the PIN where one is asked;
     * the Undo asks it itself): it puts the wider exits back, live too. On is at once. [log]: false for the change timeline's own Undo.
     * What to say.
     */
    fun set(on: Boolean, pinConfirmed: Boolean = false, log: Boolean = true): String {
        if (!on && !pinConfirmed) return "Turning the breakeven at +10% off puts the arms' own exits back alone, live too: it takes your PIN. Nothing changed."
        // Review: the saved switch is read first (a worker thread: the card and the Undo both call this off the main thread), so a
        // flip before the start's read never records a change that did not happen.
        if (!loaded) load()
        synchronized(this) {
            if (this.on == on) return "The breakeven at +10% is already ${if (on) "on" else "off"}."
            this.on = on
            loaded = true
            switched = true
        }
        runCatching { SecurePrefs.putAllSoon(mapOf(K_ON to on)) }
        noteSince()
        runCatching { Diag.record("step-trail", "${StepTrail.LABEL}: ${if (on) "off → on" else "on → off"} (by you; new trades of Liquidity 15+5, R51 and R72)") }
        publish()
        if (log) runCatching { com.optionslab.app.ira.Autopilot.logStepTrail(on) }
        // Review: the rule on again with the fourth version's switch on: its V4 -> V5 entry is logged once if it never was (the update
        // found the rule off), so the lock never acts without its timeline entry.
        if (on && lock) runCatching { com.optionslab.app.ira.Autopilot.logStepLockOn() }
        return if (on) "On: new Liquidity 15+5, R51 and R72 trades keep their own stop and move it to breakeven once the option is 10% up" +
            (if (lock) "; R51 and R72 also lock +₹500 a lot at +₹1,000 a lot and step it up ₹500 at a time; Liquidity moves to breakeven at " +
                "+₹1,000 a lot if that comes first" else "") + ". Paper and live, beside their own exits (failed gates, your exception)."
        else "Off: new Liquidity 15+5, R51 and R72 trades use their own exits again. Open trades keep the exits they entered with."
    }

    /**
     * Erase everything ([PersonalStores]): the switch as on a fresh phone - on, its default ([switched]: a start's read still on its way
     * never brings Boss's old "off" back). Its saved key goes with the settings vault.
     */
    fun wipe() {
        // (Review: Boss's switch history goes too - its saved keys with the settings vault.)
        synchronized(this) { on = true; lock = true; loaded = true; lockRead = true; switched = true; switchedLock = true; changes = emptyList(); since = null }
        publish()
    }

    /** Whether Boss's own choice (off) is still in memory (the erase's tests). Memory only. */
    internal fun holdsForErase(): Boolean = !on || !lock || changes.isNotEmpty()

    /** TEST ONLY: the switch as a fresh start would have it (read, [on], [lock]; the fourth version's start [since]), nothing switched. */
    internal fun resetForTest(on: Boolean = false, loaded: Boolean = true, lock: Boolean = true, since: LocalDateTime? = null,
                              offAt: LocalDateTime? = null, onAt: LocalDateTime? = null, history: List<Pair<LocalDateTime, Boolean>>? = null) {
        this.on = on; this.lock = lock; this.since = since; this.loaded = loaded; lockRead = loaded
        changes = history ?: listOfNotNull(offAt?.let { it to false }, onAt?.let { it to true }).sortedBy { it.first }
        switched = false; switchedLock = false
        publish()
    }
}
