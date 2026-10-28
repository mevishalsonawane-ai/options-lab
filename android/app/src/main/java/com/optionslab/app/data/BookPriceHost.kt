package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.SecurePrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 28 Oct (Boss: "Yes fix finnifty"): the paper switch for entries on an option that has a live bid and ask but NO last price
 * ([com.optionslab.ira.BookPrice]; [Paper.entryPrice]). On by default (Boss's ask), logged once in the Autopilot change timeline,
 * undone there in one tap. Paper only: a fill priced this way is never mirrored live ([ArmLive.entered]); exits and marks read
 * the bid whatever this says (never weakened). The switch is read from memory only (the order path never reads the vault).
 */
object BookPriceHost {
    private const val K_ON = "g.paperBookPrice.on"
    @Volatile private var paperOn = true
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Reads the switch in the background; logs it once in the Autopilot timeline while on. */
    fun init(@Suppress("UNUSED_PARAMETER") context: Context) {
        scope.launch { load(); if (paperOn) runCatching { com.optionslab.app.ira.Autopilot.logBookPriceOn() } }
    }

    /** The switch read from the settings (a worker thread). */
    internal fun load() {
        runCatching { paperOn = SecurePrefs.getBoolean(K_ON, true) }
    }

    /** Whether a paper entry may fill on the live book when there is no last price. Memory only. */
    fun paperOn(): Boolean = paperOn

    /** The switch set [on] (the timeline's Undo; [log] false there). Saved in the background. What to say. */
    fun setPaperOn(on: Boolean, log: Boolean = true): String {
        paperOn = on
        runCatching { SecurePrefs.putAllSoon(mapOf(K_ON to on)) }
        if (log && on) runCatching { com.optionslab.app.ira.Autopilot.logBookPriceOn() }
        runCatching { Diag.record("paper", "Paper entries on the live book (no last price) ${if (on) "on" else "off"}") }
        return if (on) "Paper entries on an option with no last price fill at the ask when the spread is sane (never mirrored live)."
        else "Paper entries on an option with no last price are refused again (no fresh price). Exits still read its bid."
    }

    /** TEST SEAM: the switch as the tests need it (memory only). */
    internal fun setForTest(on: Boolean) { paperOn = on }
}
