package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SelfCalibrationTest {
    private val today = LocalDate.of(2026, 10, 5)

    private fun c(daysAgo: Long, hour: Int = 11, minute: Int = 0, market: Market = Market.NIFTY, call: Boolean = true, kind: String = "news",
                  regime: Regime.Kind? = null, iv: Double? = null) =
        SelfCalibration.Conditions(LocalDateTime.of(today.minusDays(daysAgo), java.time.LocalTime.of(hour, minute)), market, call, kind, regime, iv)

    /** [n] ideas in [cond] (one a day back from [from]), the first [wins] making [win] points, the rest losing [loss]. */
    private fun run(n: Int, wins: Int, cond: (Long) -> SelfCalibration.Conditions, from: Long = 1, win: Double = 20.0, loss: Double = -15.0) =
        (0 until n).map { i -> SelfCalibration.Outcome(cond(from + i), if (i < wins) win else loss) }

    @Test fun tagsNameEveryKnownCondition() {
        val t = SelfCalibration.tags(c(0, 9, 30, Market.BANKNIFTY, call = false, kind = "pattern hammer", regime = Regime.Kind.UP, iv = 0.8))
        assertEquals(listOf("pattern hammer", "BANKNIFTY", "OPEN", "AGAINST", "DEAR", "PUT"), t.map { it.key })
        // Unknown regime and options price: those conditions are simply not judged.
        assertEquals(listOf(SelfCalibration.Dim.KIND, SelfCalibration.Dim.INDEX, SelfCalibration.Dim.TIME, SelfCalibration.Dim.SIDE),
            SelfCalibration.tags(c(0, 14)).map { it.dim })
        assertEquals("WITH", SelfCalibration.tags(c(0, regime = Regime.Kind.DOWN, call = false)).first { it.dim == SelfCalibration.Dim.TREND }.key)
        assertEquals("AFTERNOON", SelfCalibration.tags(c(0, 13, 30)).first { it.dim == SelfCalibration.Dim.TIME }.key)
        assertEquals("CHEAP", SelfCalibration.tags(c(0, iv = 0.2)).first { it.dim == SelfCalibration.Dim.OPTIONS }.key)
    }

    @Test fun tooFewIdeasNeverJudge() {
        val o = run(SelfCalibration.MIN_ONE - 1, 0, { c(it) })
        val j = SelfCalibration.judge(o, c(0))
        assertEquals(SelfCalibration.Action.NORMAL, j.action)
        assertNull(j.text())
        assertTrue(SelfCalibration.say(o, today).contains("only 7"))
    }

    @Test fun aClearlyBadConditionIsSatOut() {
        // Hammer ideas in sideways markets: 1 of 12 worked. News in trending markets does fine.
        val bad = run(12, 1, { c(it, market = Market.BANKNIFTY, call = false, kind = "pattern hammer", regime = Regime.Kind.SIDEWAYS) })
        val good = run(12, 8, { c(it, hour = 14, kind = "news", regime = Regime.Kind.UP) })
        val o = bad + good
        val j = SelfCalibration.judge(o, c(0, kind = "pattern hammer", regime = Regime.Kind.SIDEWAYS))
        assertEquals(SelfCalibration.Action.SIT_OUT, j.action)
        assertTrue(j.text()!!.startsWith("I'm weak on pattern hammer ideas"), j.text())
        assertEquals(0, SelfCalibration.lots(1, j.action))
        // The good condition is left alone.
        assertEquals(SelfCalibration.Action.NORMAL, SelfCalibration.judge(o, c(0, hour = 14, kind = "news", regime = Regime.Kind.UP)).action)
    }

    @Test fun aPairCanBeBadWhereEachSideAloneIsFine() {
        // Hammers do well in trends; sideways news does well; hammers in sideways markets lose.
        val o = run(12, 10, { c(it, kind = "pattern hammer", regime = Regime.Kind.UP) }) +
            run(12, 10, { c(it, kind = "news", regime = Regime.Kind.SIDEWAYS) }) +
            run(11, 1, { c(it, kind = "pattern hammer", regime = Regime.Kind.SIDEWAYS) })
        val j = SelfCalibration.judge(o, c(0, kind = "pattern hammer", regime = Regime.Kind.SIDEWAYS))
        assertEquals(SelfCalibration.Action.SIT_OUT, j.action)
        assertEquals(listOf(SelfCalibration.Dim.KIND, SelfCalibration.Dim.TREND), j.why!!.tags.map { it.dim })
        assertEquals("pattern hammer ideas in sideways markets", j.why!!.what())
        assertEquals(SelfCalibration.Action.NORMAL, SelfCalibration.judge(o, c(0, kind = "pattern hammer", regime = Regime.Kind.UP)).action)
    }

    @Test fun losingButNotClearlyBadIsShrunk() {
        // 4 of 10 worked but the losses outweigh: losing, not clearly bad.
        val o = run(10, 4, { c(it) }, win = 10.0, loss = -10.0)
        val j = SelfCalibration.judge(o, c(0))
        assertEquals(SelfCalibration.Action.SHRINK, j.action)
        assertTrue(j.text()!!.contains("half size"))
    }

    @Test fun learningOnlyEverLowersSize() {
        for (planned in 0..6) for (a in SelfCalibration.Action.entries) {
            val l = SelfCalibration.lots(planned, a)
            assertTrue(l <= planned, "$a $planned -> $l")
            assertTrue(l >= 0)
        }
        assertEquals(1, SelfCalibration.lots(1, SelfCalibration.Action.SHRINK))
        assertEquals(2, SelfCalibration.lots(4, SelfCalibration.Action.SHRINK))
        assertEquals(3, SelfCalibration.lots(3, SelfCalibration.Action.NORMAL))
        // A strong record says so, and still never changes an action to more than normal.
        val strong = run(20, 17, { c(it) })
        assertEquals(SelfCalibration.Action.NORMAL, SelfCalibration.judge(strong, c(0)).action)
        assertTrue(SelfCalibration.strongest(strong, today).isNotEmpty())
    }

    @Test fun oldResultsAgeOutSoAConditionCanRecover() {
        val old = run(12, 0, { c(it) }, from = SelfCalibration.WINDOW_DAYS + 1)
        assertEquals(SelfCalibration.Action.NORMAL, SelfCalibration.judge(old, c(0)).action)
        // And only the latest [RECENT] ideas of a condition count: old losses, then a long good run.
        val turned = run(30, 0, { c(it) }, from = 40) + run(30, 22, { c(it) }, from = 1)
        assertEquals(SelfCalibration.Action.NORMAL, SelfCalibration.judge(turned, c(0)).action)
    }

    @Test fun futureAndBrokenResultsAreIgnored() {
        val o = run(12, 0, { c(-it) }) + SelfCalibration.Outcome(c(1), Double.NaN)
        assertEquals(SelfCalibration.Action.NORMAL, SelfCalibration.judge(o, c(0)).action)
    }

    @Test fun reviewSaysWhereHeSitsOutAndWhatCameBack() {
        val o = run(12, 1, { c(it, market = Market.BANKNIFTY, call = false, kind = "pattern hammer", regime = Regime.Kind.SIDEWAYS) }) +
            run(12, 8, { c(it, hour = 14, kind = "news", regime = Regime.Kind.UP) })
        val lines = SelfCalibration.review(o, today)
        assertTrue(lines.first().startsWith("I'm worse at pattern hammer ideas"), lines.toString())
        assertTrue(lines.first().endsWith("I'll sit those out"))
        // The conditions holding the very same ideas are said as one, not five times.
        val w = SelfCalibration.weakest(o, today)
        assertEquals(1, w.size, w.toString())
        assertEquals("pattern hammer ideas on BankNifty in the late morning in sideways markets buying puts", w[0].what())
        // Yesterday "news" was sat out; today it is fine: said as earned back.
        val back = SelfCalibration.review(o, today, setOf("KIND=news"))
        assertTrue(back.any { it.contains("news ideas is no longer clearly bad") }, back.toString())
        // Into the evening review.
        val said = SelfReview.say(SelfReview.Facts(3, 3, emptyList(), emptyList(), emptyList(), null, emptyList(), emptyList(), lines))!!
        assertTrue(said.contains("I'm worse at pattern hammer ideas"), said)
        assertTrue(said.contains("sit those out"))
    }

    @Test fun sitOutKeysAreStable() {
        val o = run(12, 0, { c(it, kind = "pattern hammer") })
        val keys = SelfCalibration.sitOutKeys(o, today)
        assertTrue("KIND=pattern hammer" in keys, keys.toString())
    }

    @Test fun answersWhereWeakestAndStrongest() {
        val o = run(12, 1, { c(it, market = Market.BANKNIFTY, call = false) }) + run(20, 17, { c(it, hour = 14, market = Market.NIFTY, call = true) })
        val s = SelfCalibration.say(o, today)
        assertTrue(s.contains("Weakest:"), s)
        assertTrue(s.contains("on BankNifty") || s.contains("buying puts"), s)
        assertTrue(s.contains("Strongest:"), s)
        assertTrue(s.contains("never makes me take more"), s)
        assertTrue(s.contains("Boss"))
    }

    @Test fun readsTheQuestion() {
        assertTrue(SelfCalibration.asked("Jarvis, where are you weakest?"))
        assertTrue(SelfCalibration.asked("where are you strongest"))
        assertTrue(SelfCalibration.asked("what are you bad at"))
        assertTrue(SelfCalibration.asked("what are your weak spots? your weak spots"))
        assertTrue(SelfCalibration.asked("when do you do best"))
        assertFalse(SelfCalibration.asked("where is nifty"))
        assertFalse(SelfCalibration.asked("what are my lessons"))
    }

    @Test fun suggestionsBecomeOutcomesWithTheirConditions() {
        val at = LocalDateTime.of(today, java.time.LocalTime.of(10, 0))
        val s = listOf(
            JarvisTrades.Suggestion(at, Market.NIFTY, true, 25_000.0, "pattern: HAMMER|NIFTY|5", JarvisTrades.SELF, 12.0, 75, Regime.Kind.SIDEWAYS, 0.8),
            JarvisTrades.Suggestion(at, Market.NIFTY, false, 25_000.0, "news: x", "rejected", -4.0),
            JarvisTrades.Suggestion(at, Market.NIFTY, false, 25_000.0, "news: y", "lapsed", null))
        val o = SelfCalibration.of(s)
        assertEquals(2, o.size)
        assertEquals("pattern hammer", o[0].c.kind)
        assertEquals(Regime.Kind.SIDEWAYS, o[0].c.regime)
        assertEquals(0.8, o[0].c.ivRank)
        assertEquals("news", o[1].c.kind)
        assertNull(o[1].c.regime)
    }
}
