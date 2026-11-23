package com.optionslab.ira

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpkeepTest {
    private val today = LocalDate.of(2026, 10, 5)

    // ---- the backup ---------------------------------------------------------------------------------------------------

    @Test fun safetySettingsNeverTravel() {
        // PIN, Live mode, order limits, the guard and kill switch, locks, AI-live, the automations and the guard's stops,
        // Google speech, the voice print, the Zerodha keys.
        listOf("pin.hash", "pin.salt", "pin.fails", "k.mode", "k.maxLots", "k.allow", "g.kill", "g.loss", "g.v", "sec.bio", "lock.idleSeconds",
            "jarvis.trades.paper.v3", "jarvis.trades.limit", "jarvis.trades.weekly", "jarvis.auto.guard", "jarvis.auto.backup",
            "jarvis.auto.last.GUARD", "jarvis.group.guard", "jarvis.autotrail", "jarvis.autopilot", "jarvis.autolearn.mode", "jarvis.voice.google",
            "jarvis.voice.onlyboss", "jarvis.voice.nolisten", "jarvis.voiceprint", "jarvis.newstrades", "ira.model.verified", "ira.model.verified.fast",
            "kite.apiKey", "kite.accessToken", "draft.kite.secret", "relay.key", "ui.widgetPnl", "w.pnl", "jarvis.memory")
            .forEach { assertFalse(Upkeep.carried(it), it) }
    }

    @Test fun theGitHubTokenAndKeysNeverTravel() {
        // 13 Oct (TODO G1/G2): the GitHub token, its device copy, the passphrase's keys and the upload record stay on the phone.
        listOf("github.token.pin", "github.token.dev", "github.token.expiry", "github.key.live", "github.key.backup", "github.last",
            "github.live.done").forEach { assertFalse(Upkeep.carried(it), it) }
    }

    @Test fun theCheaperStrikesKeysNeverTravel() {
        // 20 Nov 2026 (Boss's exception): its switch adds live trades (a restore must never switch it on without the PIN), its record is this
        // phone's, and its one-time timeline mark must never come back from a backup.
        listOf("cheaperStrike.on", "cheaperStrike.log", "cheaperStrike.onceLogged").forEach { assertFalse(Upkeep.carried(it), it) }
    }

    @Test fun theDayLockNeverTravels() {
        // 08 Oct: the day lock's amount (Bot settings, never by voice) and the day it was reached are this phone's alone, as
        // the guard: a restored backup can never switch it off or raise it.
        listOf("g.dayLock", "breaker.daylock.paper", "breaker.daylock.live").forEach { assertFalse(Upkeep.carried(it), it) }
        // Nor is it one of the limits Jarvis may change by voice (or undo).
        assertTrue(SettingsTalk.Key.entries.none { "day lock" in it.label.lowercase() || it.name.contains("DAY_LOCK") })
    }

    @Test fun round7KeysThatCouldActStayOnThePhone() {
        // Solo's switch and record (its record earns real orders), its brain; timed commands; the limits' undo history.
        listOf("jarvis.solo", "jarvis.solo.trades", "jarvis.solo.paused", "jarvis.solo.from", "jarvis.solo.shadows",
            "solo.brain.NIFTY", "solo.brain.NIFTY.h30", "solo.brain.BANKNIFTY.at", "jarvis.later", "settings.history", "settings.undone",
            // The market recorder's switch and gap counts belong with its files, on this phone.
            "recorder.on", "recorder.gaps")
            .forEach { assertFalse(Upkeep.carried(it), it) }
    }

    @Test fun managerRegimeAndFreezeNeverTravel() {
        // Review 2: the trade manager's policy (ACT on live), the regime book (its live approval) and the freeze line.
        listOf("trademanager.policy", "trademanager.policy.perarm", "regime.book", "learn.freeze.threshold")
            .forEach { assertFalse(Upkeep.carried(it), it) }
    }

    @Test fun ntfyAndLeftoverTelegramSecretsNeverTravel() {
        // 25 Oct (review): the removed Telegram login's keys may still be on a phone the clean-up has not reached yet.
        listOf("telegram.api_id", "telegram.api_hash", "telegram.db_key", "telegram.logged_in", "ntfy.topic", "ntfy.token", "ntfy.server", "ntfy.prefix")
            .forEach { assertFalse(Upkeep.carried(it), it) }
    }

    @Test fun learningAndWordsStillTravel() {
        listOf("jarvis.reminders", "jarvis.daybook", "jarvis.learned", "jarvis.study", "jarvis.routine.kept", "jarvis.thinking",
            "jarvis.alertSense", "jarvis.dataAge", "jarvis.askedKinds", "jarvis.improve", "jarvis.notes", "jarvis.goals", "ui.theme", "jarvis.hindi")
            .forEach { assertTrue(Upkeep.carried(it), it) }
    }

    /**
     * 22 Nov 2026 (B6 review, item 1): the arms' live copies' second copy of their switches, the order flow's modes, the day the
     * self-review ran and the change timeline's one-time marks never travel; the self-review's parks and pauses, the brain's pause and
     * the guard's live peak do (a restore merges them, never milder: below), and so do the settings around them.
     */
    @Test fun liveCopySwitchesFlowModesTheReviewDayAndTimelineMarksNeverTravel() {
        listOf("armlive.switches", "armlive.shadow", "flow.modes", "brain.reviewedOn", "timeline.once.stepTrail", Upkeep.TIMELINE_ONCE + "x")
            .forEach { assertFalse(Upkeep.carried(it), it) }
        listOf("brain.parked", "brain.paused", "brain.pause", "brain.reactions", "brain.paperDrift", "brain.netFlatSec", "guard.peak.live",
            "flowmodes.other", "armlives").forEach { assertTrue(Upkeep.carried(it), it) }
    }

    @Test fun aRestoreNeverLowersThePeakNorDropsAParkAndOnlyTightensTheBrain() {
        // The peak: the higher; nothing on one side takes the other; no figure on either: none.
        assertEquals(120_000.0, Upkeep.higherPeak(120_000.0, 90_000.0))
        assertEquals(120_000.0, Upkeep.higherPeak(90_000.0, 120_000.0))
        assertEquals(90_000.0, Upkeep.higherPeak(null, 90_000.0))
        assertEquals(90_000.0, Upkeep.higherPeak(90_000.0, Double.NaN))
        assertNull(Upkeep.higherPeak(null, null))
        assertNull(Upkeep.higherPeak(Double.NaN, -5.0))
        // Parks and pauses: both sets together, never fewer.
        assertEquals("hero,liquidity,solo", Upkeep.union("liquidity, hero", "solo,liquidity"))
        assertEquals("liquidity", Upkeep.union("liquidity", null))
        assertEquals("liquidity", Upkeep.union("", "liquidity"))
        assertEquals("", Upkeep.union(null, ""))
        // 22 Nov 2026 (B6 review): an item this phone keeps as its own - the backup's never adds it; this phone's stays.
        val covered = { k: String -> ArmAutoStops.covers(k) }
        assertEquals("hero,solo", Upkeep.union("hero", "solo,liquidity", hereOnly = covered))
        assertEquals("hero,liquidity,solo", Upkeep.union("liquidity,hero", "solo", hereOnly = covered))
        assertEquals("", Upkeep.union(null, "liquidity", hereOnly = covered))
        // The brain's pause: each cause the stricter (a missing one counts as its default: news ON).
        val p = MarketBrain.decode(Upkeep.stricterPause("news=OFF;traps=ON;feed=OFF", "news=SHADOW;traps=OFF;bigmove=ON"))
        assertEquals(MarketBrain.Mode.SHADOW, p.mode(MarketBrain.Cause.NEWS))
        assertEquals(MarketBrain.Mode.ON, p.mode(MarketBrain.Cause.TRAPS))
        assertEquals(MarketBrain.Mode.SHADOW, p.mode(MarketBrain.Cause.FEED))      // the backup's missing one: its default (SHADOW)
        assertEquals(MarketBrain.Mode.ON, p.mode(MarketBrain.Cause.BIG_MOVE))
        assertEquals(MarketBrain.Mode.ON, MarketBrain.decode(Upkeep.stricterPause(null, "news=OFF")).mode(MarketBrain.Cause.NEWS))
        // The reactions: never milder than either side, globally and for each strategy either names.
        val r = Findings.decode(Upkeep.stricterReactions("*:data=OFF;orb:stops=ACT", "*:stops=OFF;liquidity:consensus=ACT;orb:stops=OFF"))
        assertEquals(Findings.Mode.ACT, r.mode("orb", Findings.Reaction.STOPS_CLUSTER))
        assertEquals(Findings.Mode.ACT, r.mode("liquidity", Findings.Reaction.OPPOSITE_CONSENSUS))
        assertEquals(Findings.Mode.ACT, r.mode("pine", Findings.Reaction.DATA_UNRELIABLE), "the default (ACT) beats the phone's OFF")
        assertEquals(Findings.Mode.SHADOW, r.mode("pine", Findings.Reaction.STOPS_CLUSTER), "the default (SHADOW) beats the backup's OFF")
        for (s in listOf("orb", "liquidity", "pine", "x")) for (re in Findings.Reaction.entries) {
            val a = Findings.decode("*:data=OFF;orb:stops=ACT").mode(s, re); val b = Findings.decode("*:stops=OFF;liquidity:consensus=ACT;orb:stops=OFF").mode(s, re)
            assertEquals(maxOf(a, b), r.mode(s, re), "$s $re")
        }
        assertEquals(Findings.Policy(), Findings.decode(Upkeep.stricterReactions(null, "")))
    }

    // ---- days in keys -------------------------------------------------------------------------------------------------

    @Test fun dayOfReadsEveryShapeOfKey() {
        assertEquals(today, Upkeep.dayOf("2026-10-05|NIFTY"))
        assertEquals(today, Upkeep.dayOf("2026-10-05|NIFTY|true"))
        assertEquals(today, Upkeep.dayOf("prevhigh|NIFTY|2026-10-05"))
        assertEquals(today, Upkeep.dayOf("2026-10-05"))
        assertEquals(today, Upkeep.dayOf("2026-10-05T11:20"))
        assertEquals(today, Upkeep.dayOf("weekly|2026-10-05"))
        assertEquals(today, Upkeep.dayOf("jarvis.usage.2026-10-05"))
        assertNull(Upkeep.dayOf("NIFTY"))
        assertNull(Upkeep.dayOf("jarvis.usage."))
        assertNull(Upkeep.dayOf("2026-13-45|NIFTY"))
        // A date inside a word is not the key's day.
        assertNull(Upkeep.dayOf("x2026-10-05"))
    }

    @Test fun staleDayKeysKeepsTheWindowAndOtherKeys() {
        val keys = listOf("jarvis.usage.2026-10-05", "jarvis.usage.2026-09-01", "jarvis.usage.2026-08-30", "jarvis.usage.2026-08-31", "jarvis.usage.junk",
            "jarvis.usageX", "jarvis.closedMorning.2026-01-01", "pin.hash")
        assertEquals(listOf("jarvis.usage.2026-08-30"), Upkeep.staleDayKeys(keys, "jarvis.usage.", today, 35))
        assertEquals(listOf("jarvis.closedMorning.2026-01-01"), Upkeep.staleDayKeys(keys, "jarvis.closedMorning.", today, 1))
        assertEquals(emptyList(), Upkeep.staleDayKeys(listOf("jarvis.closedMorning.2026-10-04"), "jarvis.closedMorning.", today, 1))
    }

    @Test fun dropOldDaysLeavesTodayAndUndatedEntries() {
        val told = linkedSetOf("2026-10-04|NIFTY|true", "2026-10-05|NIFTY|true", "prevlow|BANKNIFTY|2026-10-03", "2026-10-05", "2026-10-02", "free")
        Upkeep.dropOldDays(told, today)
        assertEquals(setOf("2026-10-05|NIFTY|true", "2026-10-05", "free"), told)
        val map = hashMapOf("2026-09-28|NIFTY" to 1, "2026-10-05|NIFTY" to 2)
        Upkeep.dropOldDays(map.keys, today)
        assertEquals(mapOf("2026-10-05|NIFTY" to 2), map)
        // A week kept (the weekly cap's "said once this week", keyed by its Monday).
        val cool = linkedSetOf("weekly|2026-09-28", "weekly|2026-09-21", "2026-10-01T10:30")
        Upkeep.dropOldDays(cool, today, 7)
        assertEquals(setOf("weekly|2026-09-28", "2026-10-01T10:30"), cool)
    }

    @Test fun trimOldestKeepsTheNewest() {
        val seen = LinkedHashSet<String>()
        (1..10).forEach { seen += "h$it" }
        Upkeep.trimOldest(seen, 4)
        assertEquals(listOf("h7", "h8", "h9", "h10"), seen.toList())
        Upkeep.trimOldest(seen, 10)
        assertEquals(4, seen.size)
        Upkeep.trimOldest(seen, 0)
        assertTrue(seen.isEmpty())
    }

    // ---- counts-only writes -------------------------------------------------------------------------------------------

    @Test fun countsWaitMaterialChangesDoNot() {
        val t0 = 1_000_000L
        assertTrue(Upkeep.dueToSave(null, t0, material = false))
        assertFalse(Upkeep.dueToSave(t0, t0 + 60_000, material = false))
        assertTrue(Upkeep.dueToSave(t0, t0 + 60_000, material = true))
        assertTrue(Upkeep.dueToSave(t0, t0 + Upkeep.COUNTS_EVERY_MS, material = false))
        // The clock went back: written.
        assertTrue(Upkeep.dueToSave(t0, t0 - 1, material = false))
    }

    @Test fun dataAgeCountsAloneAreNotMaterial() {
        val at = LocalDateTime.of(2026, 10, 5, 10, 0)
        val fresh = DataAge.Check(DataAge.Source.PRICES, "NIFTY", at.minusSeconds(5), 5, live = true)
        val one = DataAge.observe(DataAge.Log(), listOf(fresh), at)
        // The first check of a day adds the day: material.
        assertTrue(DataAge.material(DataAge.Log(), one))
        val two = DataAge.observe(one, listOf(fresh), at.plusMinutes(1))
        assertEquals(2, two.day(today)!!.checks)
        assertFalse(DataAge.material(one, two))
        // An answer, or old data (a spell), is.
        assertTrue(DataAge.material(two, DataAge.answered(two, listOf(fresh), warned = false, withheld = false, now = at.plusMinutes(2))))
        val old = fresh.copy(dataAt = at.minusMinutes(10), ageSec = 600)
        assertTrue(DataAge.material(two, DataAge.observe(two, listOf(old), at.plusMinutes(3))))
    }

    // ---- Boss's routines kept -----------------------------------------------------------------------------------------

    @Test fun keptRoutinesAreCappedLapsedFirst() {
        var kept = emptyList<Routine.Kept>()
        // 30 old weekday routines, all lapsed long ago, and 2 still in force.
        repeat(30) { i -> kept = kept + Routine.Kept("k$i", Routine.Kind.DAILY, 600 + i, null, today.minusDays(100L - i)) }
        val live1 = Routine.Kept("pnl", Routine.Kind.WEEKDAY, 560, DayOfWeek.MONDAY, today.plusDays(10))
        val live2 = Routine.Kept("events", Routine.Kind.DAILY, 555, null, today.plusDays(3))
        kept = kept + live1 + live2
        val k = Routine.Kept("new", Routine.Kind.DAILY, 570, null, today.plusDays(Routine.KEEP_DAYS))
        val out = Routine.put(kept, k)
        assertEquals(Routine.KEPT_MAX, out.size)
        assertTrue(live1 in out && live2 in out && k in out)
        // The longest lapsed went first.
        assertFalse(out.any { it.key == "k0" })
        assertTrue(out.any { it.key == "k29" })
        // Below the cap nothing changes but the replacement.
        val few = listOf(live1, live2)
        assertEquals(few + k, Routine.put(few, k))
        assertEquals(listOf(live2, live1.copy(until = today.plusDays(20))), Routine.put(few, live1.copy(until = today.plusDays(20))))
    }
}
