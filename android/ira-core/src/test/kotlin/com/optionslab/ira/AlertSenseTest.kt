package com.optionslab.ira

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AlertSenseTest {
    private val t0 = LocalDateTime.of(2026, 10, 1, 9, 30)

    /** [n] alerts of [kind] an hour apart; Boss asks after the first [followed]; mutes after the next [muted]. */
    private fun history(kind: String, n: Int, followed: Int = 0, muted: Int = 0, start: LocalDateTime = t0): AlertSense.Log {
        var log = AlertSense.Log()
        repeat(n) { i ->
            val at = start.plusHours(i.toLong())
            log = AlertSense.spoken(log, kind, at)
            if (i < followed) log = AlertSense.boss(log, AlertSense.Boss.ASKED, at.plusMinutes(2))
            else if (i < followed + muted) log = AlertSense.boss(log, AlertSense.Boss.MUTED, at.plusMinutes(1))
        }
        return log
    }

    private val later = t0.plusDays(1)

    @Test fun followedAndIgnoredAreTold() {
        var log = AlertSense.spoken(AlertSense.Log(), "ORB", t0)
        log = AlertSense.boss(log, AlertSense.Boss.OPENED, t0.plusMinutes(4))
        log = AlertSense.spoken(log, "ORB", t0.plusHours(1))
        log = AlertSense.boss(log, AlertSense.Boss.ASKED, t0.plusHours(1).plusMinutes(9))   // too late: ignored
        val r = AlertSense.records(log, later).single()
        assertEquals(2, r.said); assertEquals(1, r.followed); assertEquals(1, r.ignored)
    }

    @Test fun aMuteOutweighsAFollowUp() {
        var log = AlertSense.spoken(AlertSense.Log(), "VIX", t0)
        log = AlertSense.boss(log, AlertSense.Boss.ASKED, t0.plusMinutes(1))
        log = AlertSense.boss(log, AlertSense.Boss.MUTED, t0.plusMinutes(2))
        assertEquals(AlertSense.Reaction.MUTED, log.said.single().reaction)
        // A mute long after says nothing about the alert.
        var l2 = AlertSense.spoken(AlertSense.Log(), "VIX", t0)
        l2 = AlertSense.boss(l2, AlertSense.Boss.MUTED, t0.plusMinutes(10))
        assertNull(l2.said.single().reaction)
    }

    @Test fun anAlertStillInItsFollowUpTimeIsNotJudged() {
        val log = AlertSense.spoken(AlertSense.Log(), "ORB", t0)
        assertTrue(AlertSense.records(log, t0.plusMinutes(2)).isEmpty())
        assertTrue(AlertSense.waiting(log, t0.plusMinutes(2)))
        assertFalse(AlertSense.waiting(log, t0.plusMinutes(6)))
    }

    @Test fun aFewIgnoredDecideNothing() {
        assertEquals(AlertSense.Level.NORMAL, AlertSense.level(history("ORB", 3), "ORB", later))
        assertTrue(AlertSense.aloud(history("ORB", 3), "ORB", later))
    }

    @Test fun ignoredKindsAreSaidLessOften() {
        assertEquals(AlertSense.Level.FEWER, AlertSense.level(history("ORB", 4), "ORB", later))
        assertEquals(AlertSense.Level.HOLD, AlertSense.level(history("ORB", 8), "ORB", later))
        assertEquals(AlertSense.Level.FEWER, AlertSense.level(history("ORB", 8, followed = 2), "ORB", later))
        assertEquals(AlertSense.Level.NORMAL, AlertSense.level(history("ORB", 8, followed = 4), "ORB", later))
        // Mutes count double.
        assertEquals(AlertSense.Level.FEWER, AlertSense.level(history("OI", 4, followed = 1, muted = 2), "OI", later))
    }

    @Test fun fewerSaysEveryOtherHoldOneInFour() {
        var log = history("ORB", 4)
        val spoken = (0 until 6).map { i ->
            val at = later.plusMinutes(i.toLong())
            val aloud = AlertSense.aloud(log, "ORB", at)
            // A minute apart: the ones said are not judged yet, so the kind stays at FEWER.
            log = if (aloud) AlertSense.spoken(log, "ORB", at) else AlertSense.heldBack(log, "ORB", at)
            aloud
        }
        assertEquals(listOf(false, true, false, true, false, true), spoken)

        var hold = history("MOMENTS", 10)
        val aloud = (0 until 8).map { i ->
            val at = later.plusMinutes(i.toLong() * 10)
            val a = AlertSense.aloud(hold, "MOMENTS", at)
            hold = if (a) AlertSense.spoken(hold, "MOMENTS", at) else AlertSense.heldBack(hold, "MOMENTS", at)
            a
        }
        assertEquals(2, aloud.count { it })
        assertFalse(aloud[0])
    }

    @Test fun neverMoreOftenAndSafetyIsNeverHeld() {
        // Said more than ever and always ignored: a safety warning is still said every time (and not even kept).
        for (k in listOf("FEED", "RELAY", "HEADSUP", "COOLOFF", "MIS", "EXPIRY", "OVERTRADE", "PRETRADE", "TARGET", "POSNEWS", "SUMMARY")) {
            var log = AlertSense.Log()
            repeat(20) { i -> log = AlertSense.spoken(log, k, t0.plusMinutes(i * 10L)) }
            assertTrue(log.said.isEmpty(), k)
            assertTrue(AlertSense.aloud(log, k, later), k)
            assertEquals(AlertSense.Level.NORMAL, AlertSense.level(log, k, later))
        }
        // A well-followed kind is never pushed above "every time".
        assertTrue(AlertSense.aloud(history("ORB", 10, followed = 10), "ORB", later))
    }

    @Test fun recordsAgeOut() {
        val log = history("ORB", 8)
        assertEquals(AlertSense.Level.HOLD, AlertSense.level(log, "ORB", later))
        assertEquals(AlertSense.Level.NORMAL, AlertSense.level(log, "ORB", t0.plusDays(AlertSense.WINDOW_DAYS + 1)))
        // And the log itself stays bounded to the window.
        val pruned = AlertSense.spoken(log, "VIX", t0.plusDays(AlertSense.WINDOW_DAYS + 1))
        assertEquals(1, pruned.said.size)
    }

    @Test fun sayEverythingAgainStartsAfresh() {
        val log = AlertSense.heldBack(history("ORB", 8), "ORB", later)
        assertFalse(AlertSense.aloud(log, "ORB", later))
        val reset = AlertSense.reset(log, later)
        assertTrue(AlertSense.aloud(reset, "ORB", later.plusMinutes(1)))
        assertTrue(AlertSense.quieter(reset, later.plusMinutes(1)).isEmpty())
        assertTrue(AlertSense.sayAll(log, later).startsWith("Done, Boss"))
    }

    @Test fun asksAreRecognised() {
        assertEquals(AlertSense.Request.WHICH, AlertSense.asked("Jarvis, which alerts do you hold back?"))
        assertEquals(AlertSense.Request.WHICH, AlertSense.asked("what are you holding back"))
        assertEquals(AlertSense.Request.ALL, AlertSense.asked("Jarvis, say everything again"))
        assertEquals(AlertSense.Request.ALL, AlertSense.asked("don't hold anything back"))
        assertNull(AlertSense.asked("say that again"))
        assertNull(AlertSense.asked("what is Nifty doing"))
        assertNull(AlertSense.asked("buy 1 lot nifty and say everything again later"))
    }

    @Test fun whichAlertsAnswer() {
        val none = AlertSense.say(AlertSense.Log(), later)
        assertTrue(none.startsWith("I hold nothing back, Boss") && none.contains("loss-limit"), none)
        var log = history("ORB", 8)
        log = AlertSense.Log(log.said + history("VIX", 5, followed = 1).said)
        val s = AlertSense.say(log, later)
        assertTrue(s.startsWith("Boss, I say only one in 4 of these aloud: opening range breaks (you followed up 0 of my last 8)"), s)
        assertTrue(s.contains("every other one of these: fear (VIX) spikes (you followed up 1 of my last 5)"), s)
        assertTrue(s.contains("still go in the chat") && s.contains("never hold back a safety warning") && s.contains("say everything again"), s)
    }

    @Test fun nightlyReview() {
        var log = AlertSense.heldBack(history("ORB", 8), "ORB", later)
        log = AlertSense.heldBack(log, "ORB", later.plusMinutes(30))
        val lines = AlertSense.review(log, later.plusHours(6), before = emptySet())
        assertTrue(lines[0].startsWith("I now say fewer of opening range breaks (you followed up 0 of my last 8) aloud"), lines.toString())
        assertTrue(lines[1].startsWith("I kept 2 alerts to the chat only today"), lines.toString())
        // Known yesterday: not news again. A kind no longer held: said aloud again.
        assertEquals(1, AlertSense.review(log, later.plusHours(6), before = setOf("ORB")).size)
        val back = AlertSense.review(AlertSense.reset(log, later.plusHours(7)), later.plusHours(8), before = setOf("ORB"))
        assertEquals(listOf("I say opening range breaks aloud every time again - you followed them up lately, or asked me to"), back)
        assertTrue(AlertSense.review(AlertSense.Log(), later, null).isEmpty())
        // And it reaches the self-review.
        val said = SelfReview.say(SelfReview.Facts(3, 3, emptyList(), emptyList(), emptyList(), null, emptyList(), emptyList(), alerts = lines))
        assertTrue(said!!.contains("I now say fewer of opening range breaks"), said)
    }
}
