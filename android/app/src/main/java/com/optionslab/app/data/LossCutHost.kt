package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.SecurePrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 1 Nov (Boss: "Okay go ahead, but dont break any thing, as i am going live tomorrow"): the two paper loss-cut switches from
 * research ([com.optionslab.engine.orb.LossCutRules]; research/HUNT_R143_UNSEEN_TWO_YEARS.md):
 *  - R72 Breadth Donchian's wide-day skip ([wideDayOn]);
 *  - R52 Open outside value's no-progress exit ([noProgressOn]).
 * OFF by default: nothing changes until Boss switches one on. Each flip is logged in the Autopilot change timeline as Boss's own
 * and undone there in one tap. PAPER ONLY: while a live copy of the arm could be sent ([ExposureCapHost.liveMirrorPossible]) the
 * rule only notes what it would do (shadow) - it never skips or sells a trade that is, or could be, live. The switches are read
 * from memory only (the order and tick paths never read the vault).
 */
object LossCutHost {
    private const val K_WIDE = "g.lossCut.wideDay.on"
    private const val K_NP = "g.lossCut.noProgress.on"
    @Volatile private var wideOn = false
    @Volatile private var npOn = false
    /** Bumped on every flip ([load] never puts back a value read before one). */
    @Volatile private var flips = 0
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Reads the switches in the background (both stay off until read; off is the default). */
    fun init(@Suppress("UNUSED_PARAMETER") context: Context) {
        scope.launch { load() }
    }

    /** The switches read from the settings (a worker thread). */
    internal fun load() {
        val g = flips
        val w = runCatching { SecurePrefs.getBoolean(K_WIDE, false) }.getOrNull()
        val n = runCatching { SecurePrefs.getBoolean(K_NP, false) }.getOrNull()
        // A flip by Boss while this read ran wins (never put back by an older read).
        if (g != flips) return
        w?.let { wideOn = it }; n?.let { npOn = it }
    }

    /** R72's wide-day skip is on for paper. Memory only. */
    fun wideDayOn(): Boolean = wideOn

    /** R52's no-progress exit is on for paper. Memory only. */
    fun noProgressOn(): Boolean = npOn

    /** R72's wide-day skip set [on] (the timeline's Undo: [log] false). Saved in the background. What to say. */
    fun setWideDay(on: Boolean, log: Boolean = true): String {
        wideOn = on; flips++
        runCatching { SecurePrefs.putAllSoon(mapOf(K_WIDE to on)) }
        if (log) runCatching { com.optionslab.app.ira.Autopilot.logLossCut(com.optionslab.ira.AutoChange.wideDaySkip(on)) }
        runCatching { Diag.record("paper", "R72 wide-day skip ${if (on) "on" else "off"} (paper only)") }
        return if (on) "R72 Breadth Donchian now skips an index's paper breaks on a day after a very wide day. Paper only: while R72 " +
            "could go live it only notes what it would skip."
        else "R72 Breadth Donchian takes every paper break again."
    }

    /** R52's no-progress exit set [on] (the timeline's Undo: [log] false). Saved in the background. What to say. */
    fun setNoProgress(on: Boolean, log: Boolean = true): String {
        npOn = on; flips++
        runCatching { SecurePrefs.putAllSoon(mapOf(K_NP to on)) }
        if (log) runCatching { com.optionslab.app.ira.Autopilot.logLossCut(com.optionslab.ira.AutoChange.noProgressExit(on)) }
        runCatching { Diag.record("paper", "R52 no-progress exit ${if (on) "on" else "off"} (paper only)") }
        return if (on) "R52 Open outside value now sells a paper trade at its entry + 5 minutes if the option has not risen 5%. Paper " +
            "only: while R52 could go live it only notes what it would sell."
        else "R52 Open outside value keeps its own exits only."
    }

    /**
     * The rules act on [arm] only while it is surely paper only: the live switches known (memory), no live copy of [arm] possible
     * ([ExposureCapHost.liveMirrorPossible]), the live book read (and readable), and (for an exit) no live trade held on [symbol]. Anything unknown
     * counts as "could be live": the rule then only notes what it would do. Memory only (safe on the order and tick paths).
     */
    fun paperOnlyNow(arm: String, symbol: String? = null): Boolean = runCatching {
        ArmLive.liveFlagsKept() != null && ArmLive.view.value.let { it.loaded && !it.unreadable } && !ExposureCapHost.liveMirrorPossible(arm) &&
            (symbol == null || ArmLive.ownerOf(symbol) == null)
    }.getOrDefault(false)

    /** TEST SEAM: the switches as the tests need them (memory only). */
    internal fun setForTest(wide: Boolean = false, noProgress: Boolean = false) { wideOn = wide; npOn = noProgress }
}
