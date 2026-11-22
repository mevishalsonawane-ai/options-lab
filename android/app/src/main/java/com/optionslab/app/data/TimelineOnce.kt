package com.optionslab.app.data

import com.optionslab.app.security.SecurePrefs
import com.optionslab.ira.Upkeep

/**
 * 22 Nov 2026 (B6 review, item 4): the change timeline's "switched on by the update" entries, logged ONCE per phone. Each was logged at
 * every start unless a kept entry of it was still in the timeline - and the timeline drops its oldest kept entries past 400, so a pruned
 * one came back as new. Now each has its own mark in the settings vault (under [Upkeep.TIMELINE_ONCE]: never in a backup, never taken from
 * one), written only once its entry is in the timeline (Autopilot's logBossOwnOnce), and never logged again after - as the cheaper
 * strike's ([CheaperStrike.K_ONCE]). A worker thread only (a settings read or write).
 */
internal object TimelineOnce {
    /** The breakeven at +10% (V4) on by the 15 Nov update ([StepTrailExits]). */
    const val STEP_TRAIL = Upkeep.TIMELINE_ONCE + "stepTrail"
    /** Its fourth version (V4 -> V5) on by the 16 Nov update ([StepTrailExits]). */
    const val STEP_LOCK = Upkeep.TIMELINE_ONCE + "stepLock"
    /** Paper entries on the live book on by the 28 Oct update ([BookPriceHost]). */
    const val BOOK_PRICE = Upkeep.TIMELINE_ONCE + "bookPrice"
    /** Boss's win-rate rule for automatic ideas on by the 28 Oct update ([IdeaRuleHost]). */
    const val IDEA_RULE = Upkeep.TIMELINE_ONCE + "ideaRule"
    /** The same-direction cap on paper by the 19 Oct update ([ExposureCapHost]). */
    const val SAME_DIR_CAP = Upkeep.TIMELINE_ONCE + "sameDirCap"
    /** Liquidity's no-twin rule on paper by the 20 Oct update ([LiquidityTest]). */
    const val LIQ_NO_TWIN = Upkeep.TIMELINE_ONCE + "liqNoTwin"

    /** Whether [key]'s entry is (or was) in the change timeline on this phone; null while the settings vault cannot be read (not now). */
    fun done(key: String): Boolean? {
        val d = runCatching { SecurePrefs.getBoolean(key, false) }.getOrNull() ?: return null
        return if (SecurePrefs.unreadable) null else d
    }

    /** [key]'s entry is in the change timeline: never logged again on this phone, whatever the timeline prunes later. */
    fun mark(key: String) { runCatching { SecurePrefs.putAllSoon(mapOf(key to true)) } }

    /**
     * The change timeline started afresh (its file could not be read and was set aside): every one-time mark forgotten - these and the
     * cheaper strike's - so the updates' entries, with their Undo, are logged again. 22 Nov 2026 (B6-E, merged with B6-F): the dead order
     * limits' note's mark too ([com.optionslab.app.ira.Autopilot.K_DEAD_LIMITS]), so that note (it has no Undo) is said again.
     */
    fun forgetAll() {
        runCatching { SecurePrefs.putAllSoon(SecurePrefs.keys(Upkeep.TIMELINE_ONCE).associateWith { null } + (CheaperStrike.K_ONCE to null) +
            (com.optionslab.app.ira.Autopilot.K_DEAD_LIMITS to null)) }
    }
}
