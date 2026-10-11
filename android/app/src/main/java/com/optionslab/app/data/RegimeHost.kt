package com.optionslab.app.data

import com.optionslab.app.security.SecurePrefs
import com.optionslab.ira.Findings
import com.optionslab.ira.ManagerConfig
import com.optionslab.ira.RegimeParams
import java.time.LocalDate

/**
 * Per-regime settings on the phone (TODO S8; the rules are ira-core's [RegimeParams]). From the brain's refresh ([tick],
 * a worker thread; memory checks until due):
 *  - at 09:45 on a trading day the day's regime is decided from the brain's NIFTY regime over the morning and frozen for
 *    the day (with the expiry-day and event-day flags) - it never flips mid-day, even after a restart (kept in the settings);
 *  - each override of that regime records "would have used the busy-day setting" (Diag, and the findings bus once) while in
 *    SHADOW; one that has done its [RegimeParams.SHADOW_DAYS] trading days applies on paper by itself, and a finding is
 *    posted and Boss asked: for live he approves it with his PIN on the Brain's Regime row ([approveLive]; nothing here
 *    turns anything on for live by itself);
 *  - after 15:45, once a day, the overrides are rebuilt from Jarvis's lessons (only passing regime-conditional ones).
 *
 * The arms read [RegimeParams.overrideToday] and the trade manager [managerConfig] - memory only, nothing on the order path.
 */
object RegimeHost {
    private const val K_BOOK = "regime.book"

    @Volatile private var loaded = false
    @Volatile private var rebuiltOn: LocalDate? = null

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            runCatching { RegimeParams.use(RegimeParams.decode(SecurePrefs.getString(K_BOOK))) }
            loaded = true
        }
    }

    private fun save(b: RegimeParams.Book) {
        RegimeParams.use(b)
        runCatching { SecurePrefs.putAllSoon(mapOf(K_BOOK to RegimeParams.encode(b))) }
    }

    /** One look (the brain's worker): freeze today's regime at 09:45, count the shadow day, rebuild after 15:45. */
    fun tick() {
        if (!runCatching { Market.isTradingDay() }.getOrDefault(false)) return
        val today = Market.today()
        val minute = Market.minuteNow()
        val kept = RegimeParams.book.day
        if (kept?.day == today && rebuiltOn == today) return
        ensureLoaded()
        val b0 = RegimeParams.book
        if (b0.day?.day != today && minute >= RegimeParams.DECIDE_MINUTE) {
            val event = runCatching { EventDays.refreshIfStale(); EventDays.isEventDay(today) }.getOrDefault(false)
            val expiry = runCatching { com.optionslab.ira.EventCalendar.EXPIRY_DAYS.keys.any { Market.upcomingExpiries(it).firstOrNull() == today } }.getOrDefault(false)
            val day = RegimeParams.freeze(b0.day, today, minute, expiry, event) {
                // The morning's read only: a phone that first looks after 10:15 keeps the day unknown (no override applies).
                if (minute <= RegimeParams.DECIDE_MINUTE + 30) SmartWorkers.brain.value.regimes["NIFTY"] else null
            } ?: return
            // Moving an override to paper by itself is an automatic change: never while learning is frozen or Autopilot
            // learning is not SAFE (review 2 #10).
            val (b1, ready) = RegimeParams.observe(b0, day, hold = holdPromotions())
            save(b1)
            runCatching { Diag.record("brain", RegimeParams.line(b1)) }
            // Jarvis's note on an event day (TODO S11): "Event day: learning ignores today's results".
            if (event && com.optionslab.app.BuildConfig.JARVIS) runCatching { EventDays.todayNote()?.let { com.optionslab.app.ira.IraNotes.add(it) } }
            for (l in RegimeParams.wouldUse(b1, today)) runCatching { Diag.record("brain", "Regime setting: $l") }
            for (o in ready) {
                runCatching {
                    SmartWorkers.found("regime settings", Findings.Kind.OTHER, Findings.ALL, 0, null, 60,
                        "${o.words}: shadow → paper after ${RegimeParams.SHADOW_DAYS} ${o.regime.words} days in shadow; " +
                            "for live, approve it with your PIN in $WHERE. [${o.key}]", 24 * 3_600_000L)
                }
                // Put to Boss (G9-2): live only with his PIN, on the Brain's Regime row - never by a yes alone.
                if (com.optionslab.app.BuildConfig.JARVIS) runCatching {
                    com.optionslab.app.ira.IraHub.offer("regime setting: ${o.key}", "Boss, a regime setting for live?",
                        "Boss, ${o.words} finished ${RegimeParams.SHADOW_DAYS} ${o.regime.words} days in shadow and now applies on paper. " +
                            "It only lowers risk; for live too, approve it with your PIN in $WHERE.",
                        suspend { "Approve it for live with your PIN in $WHERE." }, alwaysAsk = true)
                }
            }
        }
        if (rebuiltOn != today && minute >= 15 * 60 + 45) {
            // Jarvis's lessons read first (G9-1): before they are, the book is empty and every override - its shadow days and
            // live approval - would be dropped for good. Not read yet: no rebuild now; the next look tries again.
            val lessons = runCatching { com.optionslab.app.ira.JarvisLearning.ensureLoaded(); com.optionslab.app.ira.JarvisLearning.book.value.lessons }
                .getOrNull() ?: return
            rebuiltOn = today
            save(RegimeParams.refresh(RegimeParams.book, lessons))
        }
    }

    /** Where Boss approves an override for live with his PIN. */
    const val WHERE = "Home → Automation → Brain"

    /** The overrides on paper, waiting for Boss's PIN to apply on live too (the Brain's Regime row). Off the main thread. */
    fun readyForLive(): List<RegimeParams.Override> { ensureLoaded(); return RegimeParams.readyForLive() }

    /** No override moves from shadow to paper by itself now: learning frozen, or Autopilot learning off or shadow-only. */
    private fun holdPromotions(): Boolean {
        if (runCatching { com.optionslab.app.ira.LearningFreeze.holds() }.getOrDefault(true)) return true
        if (!com.optionslab.app.ira.Autopilot.enabled()) return false
        return runCatching { com.optionslab.app.ira.Autopilot.ensureLoaded(); com.optionslab.app.ira.Autopilot.mode.value != com.optionslab.ira.AutoChange.Mode.SAFE }
            .getOrDefault(true)
    }

    /** Boss approved [key] for live (the PIN was asked by Autopilot's flow). Lowers risk only; returns what to say. */
    fun approveLive(key: String, pinConfirmed: Boolean): String {
        if (!pinConfirmed) return "Live regime settings need your PIN."
        ensureLoaded()
        val b = RegimeParams.approveLive(RegimeParams.book, key)
        if (b == RegimeParams.book) return "That regime setting is not ready for live yet (it needs ${RegimeParams.SHADOW_DAYS} days in shadow)."
        save(b)
        runCatching { Diag.record("brain", "Regime setting $key approved for live") }
        return "Done: it now applies on live too, on its regime's days."
    }

    /** [cfg] with today's regime weights for its strategy (paper, or live once approved). Memory only. */
    fun managerConfig(cfg: ManagerConfig, live: Boolean): ManagerConfig =
        if (!loaded) cfg else runCatching { RegimeParams.applyTo(cfg, live, date = Market.today()) }.getOrDefault(cfg)

    /** TEST ONLY. */
    internal fun resetForTest() { loaded = true; rebuiltOn = null; RegimeParams.resetForTest() }
}
