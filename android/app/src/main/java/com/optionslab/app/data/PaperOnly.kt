package com.optionslab.app.data

import com.optionslab.app.security.SecurePrefs
import com.optionslab.ira.PaperOnlyPeriod
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate

/**
 * Boss's paper-only period on the phone (12 Oct: "For 10 days I will be on paper only"; the rules are ira-core's
 * [PaperOnlyPeriod]). Until its end date, switching the app to Live asks his PIN and shows the period's reason and end date
 * (the Masthead's switch and Settings → Zerodha → Mode). 18 Oct (Boss): while it runs, every NEW live entry is refused at the
 * order door ([entryRefusal], memory only, from every source), even when the app is already Live; an exit, a stop, a target,
 * a square-off or a GTT is never blocked. It only lowers risk:
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

    /** Read once from the settings vault (a worker thread). 18 Oct, review: a failed read stays unread (tried again next time). */
    fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val raw = runCatching { readRaw() }.getOrElse { return }
            // An unreadable vault reads as empty: that is not "no period" (it stays unread, so the Live switch asks the PIN).
            if (vaultUnreadable()) return
            _period.value = runCatching { PaperOnlyPeriod.decode(raw) }.getOrNull()
            loaded = true
        }
    }

    /** How the period is read from the vault, and whether the vault could not be read (tests make a read fail). */
    @Volatile internal var readRaw: () -> String? = { SecurePrefs.getString(KEY) }
    @Volatile internal var vaultUnreadable: () -> Boolean = { SecurePrefs.unreadable }

    /** Read yet (memory only). */
    val isLoaded: Boolean get() = loaded

    /**
     * 18 Oct review: a period the start could not read (the vault unreadable for a moment) is tried again on a background thread,
     * so the bots' live entries are not refused all day for want of a screen opening it. Exits never wait for it. Nothing when
     * already read. 18 Oct (second review): no longer given up after ten minutes - tried again with a growing wait
     * ([retryWaitMs]: 20 s, doubling, at most 5 min) until it is read (one sleeping daemon thread; [until] only for tests); the
     * 09:00 morning check and each Zerodha login read it too ([ensureLoaded], worker threads).
     */
    fun loadSoon(everyMs: Long = 20_000L, maxWaitMs: Long = 5 * 60_000L, until: (() -> Boolean)? = null) {
        if (loaded) return
        val keepOn = until ?: { true }
        Thread({
            var i = 0
            while (true) {
                runCatching { ensureLoaded() }
                if (loaded) { runCatching { Diag.record("mode", "paper-only period read after ${i + 1} tries") }; return@Thread }
                if (!runCatching(keepOn).getOrDefault(false)) {
                    runCatching { Diag.record("mode", "paper-only period still unread: new live entries stay refused until it is read") }
                    return@Thread
                }
                try { Thread.sleep(retryWaitMs(i, everyMs, maxWaitMs)) } catch (_: InterruptedException) { return@Thread }
                i++
            }
        }, "paper-only").apply { isDaemon = true }.start()
    }

    /** The wait before try [i] + 2 of [loadSoon]: [everyMs], doubling each time, at most [maxWaitMs]. */
    internal fun retryWaitMs(i: Int, everyMs: Long = 20_000L, maxWaitMs: Long = 5 * 60_000L): Long {
        var w = everyMs.coerceAtLeast(1L)
        repeat(i.coerceIn(0, 40)) { if (w < maxWaitMs) w *= 2 }
        return w.coerceAtMost(maxWaitMs)
    }

    /** What the Live switch says while the period is not read yet (or could not be read): it asks the PIN, never goes Live without. */
    const val UNREAD_ASK = "Your paper-only period could not be checked yet. Enter your PIN to switch to Live. Exits and square-offs are never blocked."

    /**
     * 18 Oct, review: why switching to Live asks the PIN now, or null when it need not. It fails closed: before the period has
     * been read from the vault (the first moments after a cold start, or a failed read) the answer is [UNREAD_ASK], never null.
     * Memory only.
     */
    fun liveAskNow(): String? = if (!loaded) UNREAD_ASK else activeNow()?.let { PaperOnlyPeriod.liveAsk(it) }

    /**
     * 18 Oct (Boss: the paper-only days block every new live entry): why a NEW live entry may not go now, or null when it may.
     * Read on the order path ([Broker.placeOrder]): memory only - no disk, no SecurePrefs, no lock. It fails closed for entries
     * only: before the period is read (the first moments after a start, or a failed read) an entry is refused; exits never
     * come here. The period is read ahead of time at the app's start ([ensureLoaded], a background thread).
     */
    fun entryRefusal(): String? = if (!loaded) ENTRY_UNREAD else activeNow()?.let { entryWhy(it) }

    /** True when [why] is one of this period's entry refusals ([ENTRY_UNREAD] or [entryWhy]). */
    fun isRefusal(why: String): Boolean = why == ENTRY_UNREAD || why.startsWith(WHY_START)
    private const val WHY_START = "You set a paper-only period until "

    /** The refusal while the period has not been read yet. */
    const val ENTRY_UNREAD = "Your paper-only period could not be checked yet, so no new live entry goes to Zerodha. Exits, stops and square-offs still go."

    /** The refusal inside the period: its end date and reason. */
    fun entryWhy(p: PaperOnlyPeriod.Period): String = WHY_START + PaperOnlyPeriod.day(p.until) +
        (if (p.reason.isNotBlank()) " (\"${p.reason}\")" else "") + ", so no new live entry goes to Zerodha. Exits, stops and square-offs still go."

    /** [liveAskNow] after reading the period if it is not read yet. A worker thread (the vault read). */
    fun liveAskRead(): String? { runCatching { ensureLoaded() }; return liveAskNow() }

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
        return PaperOnlyPeriod.status(new, t) + ". Switching to Live asks your PIN, and no new live entry goes, until then; exits are never blocked."
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

    /**
     * 26 Oct (erase everything): the period (its end date and Boss's reason) dropped from memory; its key goes with the settings.
     * Left unread, as at a cold start, so a new live entry is refused until it is read again ([entryRefusal]: risk only lowered;
     * exits never wait). Reads nothing.
     */
    fun wipe() { synchronized(this) { _period.value = null; loaded = false } }

    /** A period in memory (the erase's test; memory only). */
    internal fun holdsForErase(): Boolean = _period.value != null

    /** TEST ONLY. */
    internal fun resetForTest(p: PaperOnlyPeriod.Period? = null) { _period.value = p; loaded = true }
    /** TEST ONLY: as at a cold start, before the period is read. */
    internal fun unreadForTest() { _period.value = null; loaded = false }
}
