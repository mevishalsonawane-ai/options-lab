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

/**
 * 20 Nov 2026 (audit B5, item 6: "a failed read of the order count counts as zero"; coordinator: a new automatic live skip starts in
 * shadow): the order count check - today's order count at Zerodha could not be read (read once more first, [Broker.accountNow]), so
 * the account guard's trades-per-day limit cannot be judged for a live ENTRY.
 *
 *  - SHADOW by default (CLAUDE.md: anything new that could skip a live trade starts logged only, behind a switch that is off by
 *    default): the entry is judged as it always was (the unknown count as 0) and goes; what it would have refused is logged
 *    ("would have refused: order count unknown") and counted per day, on Bot settings' card and in the diagnostics.
 *  - ON (Boss's switch in Bot settings): such an entry is refused ("the entry waits - try again in a moment"). On only ever lowers
 *    risk, so it needs no PIN; OFF adds live entries back, so it takes the PIN (the funds check's pattern, [FundsCheck]).
 *  - Only entries are ever asked: an exit or a stop never looks at the count ([Guard]: exits return before any limit).
 *  - Counted only when the unknown count is what would refuse the entry (nothing else refuses it), once per contract, side and
 *    minute (a screen's preview judged again is not counted twice).
 *
 * [judge] reads memory only (no settings read, no disk, no network): the switch is read once in the background at the start and the
 * count is kept in memory (the diagnostics' line has it; the log is the diagnostics' own).
 */
object OrderCountCheck {
    internal const val K_ON = "g.orderCountCheck.live"
    /** The card's and the diagnostics' name. */
    const val LABEL = "Order count check"
    /** What the log and the diagnostics say for one would-be refusal. */
    const val WORDS = "would have refused: order count unknown"

    @Volatile private var on = false
    @Volatile private var loaded = false
    /** Boss changed the switch in this process (the start's late read never overrides it). */
    @Volatile private var switched = false
    private val lock = Any()
    /** Today's would-be refusals (shadow) and refusals (on), and the keys already counted; the day they are of. */
    private var day: LocalDate? = null
    private var would = 0
    private var refused = 0
    private val seen = HashSet<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Bot settings' card: the switch, whether it was read, and today's counts. */
    data class View(val on: Boolean = false, val loaded: Boolean = false, val wouldToday: Int = 0, val refusedToday: Int = 0)
    private val _view = MutableStateFlow(View())
    val view: StateFlow<View> = _view

    private fun marketDay(): LocalDate = runCatching { Market.today() }.getOrElse { LocalDate.now() }

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

    /** The switch now, from memory (off until the start's read lands: its default, the side that changes nothing). */
    fun isOn(): Boolean = on

    /**
     * A live entry of [symbol] ([side]) that the unknown order count alone would refuse, at [minute] of the day: logged and counted
     * once. True when it is refused (the switch is on); false in shadow (it goes as before). Memory only; never throws.
     */
    fun judge(symbol: String, side: String, minute: Int): Boolean = runCatching {
        val applied = on
        val today = marketDay()
        val counted = synchronized(lock) {
            if (day != today) { day = today; would = 0; refused = 0; seen.clear() }
            if (!seen.add("$symbol|$side|$minute|$applied")) false
            else { if (applied) refused++ else would++; true }
        }
        if (counted) {
            runCatching {
                Diag.record("guard", "$LABEL: $symbol $side - " + if (applied) "refused: order count unknown (check on)" else "$WORDS (shadow: sent as before)")
            }
            publish()
        }
        applied
    }.getOrDefault(false)

    /** Today's counts: (would have refused, refused). Memory only. */
    fun today(): Pair<Int, Int> = synchronized(lock) { if (day == marketDay()) would to refused else 0 to 0 }

    /** The diagnostics' line: the switch and today's counts. Memory only. */
    fun line(): String {
        val (w, r) = today()
        return "$LABEL: " + when {
            !loaded -> "not read yet (counts as off: shadow)"
            on -> "on - a live entry is refused while today's order count cannot be read"
            else -> "shadow (off) - logged only"
        } + ". Today: $w would have been refused (sent as before)" + (if (r > 0) ", $r refused" else "") + "."
    }

    private fun publish() {
        runCatching { val (w, r) = today(); _view.value = View(on, loaded, w, r) }
    }

    /**
     * Boss's switch (Bot settings). On only lowers risk: at once. Off adds live entries back: it takes [pinConfirmed] (the card asks the
     * PIN). What to say. A worker thread only (the switch's first read may be made here).
     */
    fun set(on: Boolean, pinConfirmed: Boolean = false): String {
        if (!on && !pinConfirmed) return "Turning the order count check off lets live entries go while today's order count cannot be read: it takes your PIN. Nothing changed."
        if (!loaded) load()
        synchronized(lock) {
            if (this.on == on) return "The order count check is already ${if (on) "on" else "off (shadow)"}."
            this.on = on
            loaded = true
            switched = true
        }
        runCatching { SecurePrefs.putAllSoon(mapOf(K_ON to on)) }
        runCatching { Diag.record("guard", "$LABEL: ${if (on) "shadow → on" else "on → shadow"} (by you)") }
        publish()
        return if (on) "On: a live entry is refused while today's order count cannot be read (exits always go)."
        else "Off: in shadow - such entries go as before; each is logged."
    }

    /** Erase everything ([PersonalStores]): as on a fresh phone - off, nothing counted. */
    fun wipe() {
        synchronized(lock) { on = false; loaded = true; switched = true; day = null; would = 0; refused = 0; seen.clear() }
        publish()
    }

    /** Whether Boss's choice or a count is still in memory (the erase's check). Memory only. */
    internal fun holdsForErase(): Boolean = on || synchronized(lock) { would > 0 || refused > 0 }

    /** TEST ONLY: as a fresh start would have it (read, [on]), nothing switched, nothing counted. */
    internal fun resetForTest(on: Boolean = false, loaded: Boolean = true) {
        synchronized(lock) { this.on = on; this.loaded = loaded; switched = false; day = null; would = 0; refused = 0; seen.clear() }
        publish()
    }
}
