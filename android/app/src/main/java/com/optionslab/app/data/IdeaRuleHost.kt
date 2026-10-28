package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.SecurePrefs
import com.optionslab.ira.AutoChange
import com.optionslab.ira.IdeaWinRate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 28 Oct (Boss: "Dont create a pine script or stratergies automatically, until 65% + winrate is found"): the app's copy of
 * Boss's win-rate rule for automatic ideas ([IdeaWinRate]). On by default at 65%, logged once in the Autopilot change timeline
 * and undone there in one tap (with the PIN where one is set: it loosens Boss's own rule). Boss's setting "Automatic ideas:
 * minimum win rate" changes the number, always with the PIN. The rule is read from memory only (never the vault on a
 * creator's path); the vault is read and written on a worker.
 *
 * Every automatic creator asks [rule] before it creates, arms or proposes a new strategy or Pine script, and calls [held] for
 * one it holds: a note in the Autopilot change log (once per idea), nothing made. Boss's own scripts and switches never come
 * here, and nothing already armed is switched off.
 */
object IdeaRuleHost {
    private const val K_ON = "g.ideaRule.on"
    private const val K_MIN = "g.ideaRule.minPct"
    @Volatile private var on = true
    @Volatile private var minPct = IdeaWinRate.DEFAULT_PCT
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Reads the rule in the background; logs it once in the Autopilot timeline while on. */
    fun init(@Suppress("UNUSED_PARAMETER") context: Context) {
        scope.launch { load(); if (on) runCatching { com.optionslab.app.ira.Autopilot.logIdeaRuleOn(minPct) } }
    }

    /** The rule read from the settings (a worker thread). A bad number falls back to 65%. */
    internal fun load() {
        runCatching {
            on = SecurePrefs.getBoolean(K_ON, true)
            minPct = SecurePrefs.getDouble(K_MIN, IdeaWinRate.DEFAULT_PCT)
                .takeIf { it.isFinite() && it >= IdeaWinRate.MIN_CHOICE - 1e-9 && it <= IdeaWinRate.MAX_CHOICE + 1e-9 } ?: IdeaWinRate.DEFAULT_PCT
        }
    }

    /** The rule in force. Memory only. */
    fun rule(): IdeaWinRate.Rule = IdeaWinRate.Rule(on, minPct)

    /**
     * The rule switched [next] on or off: off is the change log's Undo ([log] false there: the Undo is its own record), on again
     * is free (it only holds ideas back) and is logged so it can be undone again. Saved in the background. What to say.
     */
    fun setOn(next: Boolean, log: Boolean = true): String {
        on = next
        runCatching { SecurePrefs.putAllSoon(mapOf(K_ON to next)) }
        if (log) runCatching { com.optionslab.app.ira.Autopilot.logIdeaRuleFlip(next, minPct) }
        runCatching { Diag.record("ideas", "the win-rate rule for automatic ideas ${if (next) "on at ${IdeaWinRate.pct(minPct)}" else "off"}") }
        return if (next) "Automatic ideas need a win rate of at least ${IdeaWinRate.pct(minPct)} again (out of sample, after costs)."
        else "Automatic ideas are judged by their own gates only again (no win-rate check)."
    }

    /** Boss's setting: the minimum set to [pct], only with [pinConfirmed] (up or down). What to say. */
    fun setMinPct(pct: Double, pinConfirmed: Boolean): String {
        val (next, said) = IdeaWinRate.change(rule(), pct, pinConfirmed)
        if (next == null) return said
        minPct = next.minPct
        runCatching { SecurePrefs.putAllSoon(mapOf(K_MIN to next.minPct)) }
        runCatching { Diag.record("ideas", "the minimum win rate for automatic ideas set to ${IdeaWinRate.pct(next.minPct)} with your PIN") }
        return said
    }

    /**
     * [creator]'s idea [id] ([name]) held with [words] ([IdeaWinRate.held]): a note in the Autopilot change log, once per idea,
     * and a line in Diag. Nothing is created, armed or traded.
     */
    fun held(creator: IdeaWinRate.Creator, id: String, name: String, words: String) {
        runCatching { com.optionslab.app.ira.Autopilot.logIdeaHeld(AutoChange.ideaHeld(creator, id, name, words)) }
        runCatching { Diag.record("ideas", "${creator.words} $name $words") }
    }

    /** TEST SEAM: the rule as the tests need it (memory only). */
    internal fun setForTest(on: Boolean = true, minPct: Double = IdeaWinRate.DEFAULT_PCT) { this.on = on; this.minPct = minPct }
}
