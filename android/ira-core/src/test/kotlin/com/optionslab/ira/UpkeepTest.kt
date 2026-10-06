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
            "jarvis.auto.last.GUARD", "jarvis.group.guard", "jarvis.autotrail", "jarvis.autopilot", "jarvis.voice.google",
            "jarvis.voice.onlyboss", "jarvis.voiceprint", "jarvis.newstrades", "ira.model.verified", "ira.model.verified.fast",
            "kite.apiKey", "kite.accessToken", "draft.kite.secret", "relay.key", "ui.widgetPnl", "w.pnl", "jarvis.memory")
            .forEach { assertFalse(Upkeep.carried(it), it) }
    }

    @Test fun round7KeysThatCouldActStayOnThePhone() {
        // Solo's switch and record (its record earns real orders), its brain; timed commands; the limits' undo history.
        listOf("jarvis.solo", "jarvis.solo.trades", "jarvis.solo.paused", "jarvis.solo.from", "jarvis.solo.shadows",
            "solo.brain.NIFTY", "solo.brain.NIFTY.h30", "solo.brain.BANKNIFTY.at", "jarvis.later", "settings.history", "settings.undone")
            .forEach { assertFalse(Upkeep.carried(it), it) }
    }

    @Test fun dhanCredentialsNeverTravel() {
        listOf("dhan.token", "dhan.clientId", "dhan.auto", "dhan.optionYears", "dhan.last")
            .forEach { assertFalse(Upkeep.carried(it), it) }
    }

    @Test fun learningAndWordsStillTravel() {
        listOf("jarvis.reminders", "jarvis.daybook", "jarvis.learned", "jarvis.study", "jarvis.routine.kept", "jarvis.thinking",
            "jarvis.alertSense", "jarvis.dataAge", "jarvis.askedKinds", "jarvis.improve", "jarvis.notes", "jarvis.goals", "ui.theme", "jarvis.hindi")
            .forEach { assertTrue(Upkeep.carried(it), it) }
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
