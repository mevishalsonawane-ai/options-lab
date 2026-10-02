package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class HelpersTest {
    private fun days(closes: List<Double>, range: (Int) -> Double = { 0.8 }): List<Study.Day> {
        val start = LocalDate.of(2026, 1, 1)
        return closes.mapIndexed { i, c ->
            val r = c * range(i) / 100
            Study.Day(start.plusDays(i.toLong()), c, c + r / 2, c - r / 2, c, closes.getOrNull(i - 1), null, null, null, null, null, null)
        }
    }

    @Test fun regimes() {
        assertEquals(Regime.Kind.UP, Regime.of(days((0 until 40).map { 24000.0 + it * 40 })))
        assertEquals(Regime.Kind.DOWN, Regime.of(days((0 until 40).map { 24000.0 - it * 40 })))
        assertEquals(Regime.Kind.SIDEWAYS, Regime.of(days((0 until 40).map { 24000.0 + if (it % 2 == 0) 100 else -100 })))
        assertEquals(Regime.Kind.VOLATILE, Regime.of(days((0 until 70).map { 24000.0 }) { if (it >= 60) 2.5 else 0.8 }))
        assertNull(Regime.of(days(listOf(1.0, 2.0))))
    }

    @Test fun regimeHistoryNeverPeeks() {
        val d = days((0 until 40).map { 24000.0 + it * 40 })
        val h = Regime.history(d)
        assertFalse(d[20].date in h)
        assertEquals(Regime.of(d.subList(0, 30)), h[d[30].date])
    }

    @Test fun armsByRegime() {
        val day = LocalDate.of(2026, 3, 2)
        val regimes = mapOf(day to Regime.Kind.UP, day.plusDays(1) to Regime.Kind.SIDEWAYS)
        val trades = listOf(ArmHealth.T(day, "ORB 5", 1000.0), ArmHealth.T(day.plusDays(1), "ORB 5", -400.0))
        val l = Regime.armsBy(trades, regimes)
        assertTrue(l.single().contains("trending up +Rs 1,000.00 (1)") && l.single().contains("sideways -Rs 400.00 (1)"), l.toString())
        val many = (0 until 6).map { ArmHealth.T(day, "Liquidity", 100.0) }
        assertEquals(listOf("Liquidity"), Regime.suits(many + trades, regimes, Regime.Kind.UP))
    }

    @Test fun liveReady() {
        val ok = LiveReady.Facts(null, true, false, 5000.0, 2, true, true, true, 0)
        assertTrue(LiveReady.check(ok).first().startsWith("Yes Boss"))
        val bad = LiveReady.check(ok.copy(proven = "Not proven.", zerodhaLoggedIn = false, unguardedPositions = 2))
        assertTrue(bad.first().startsWith("Not yet: 3 things"), bad.toString())
        assertTrue(bad[1] == "Not proven.")
        assertTrue(bad.any { it.contains("2 open positions have no stop") })
    }

    @Test fun lossReasons() {
        assertNull(LossReason.why(LossReason.Trip(true, 24000.0, 24100.0, 100.0, 120.0, 30, 600, false)))
        assertTrue(LossReason.why(LossReason.Trip(true, 24000.0, 23900.0, 100.0, 85.0, 30, 600, true))!!.startsWith("The index went the wrong way (100 points down)"))
        assertTrue(LossReason.why(LossReason.Trip(true, 24000.0, 24060.0, 100.0, 90.0, 60, 700, false))!!.contains("implied volatility"))
        assertTrue(LossReason.why(LossReason.Trip(false, 24000.0, 24005.0, 100.0, 80.0, 200, 915, false))!!.startsWith("Out at 15:15"))
        assertTrue(LossReason.why(LossReason.Trip(false, 24000.0, 24080.0, 100.0, 85.0, 1, 600, true))!!.contains("within 1 minute:"))
    }

    @Test fun strikeLiquidity() {
        assertNull(StrikeLiquidity.problem(100.0, 100.5, 1_000_000, 75))
        assertTrue(StrikeLiquidity.problem(20.0, 24.0, 1_000_000, 75)!!.contains("thin"))
        assertTrue(StrikeLiquidity.problem(100.0, 100.5, 750, 75)!!.contains("10 lots"))
        assertNull(StrikeLiquidity.problem(0.0, 0.0, 0, 75))
    }

    @Test fun activityAndSelfCheck() {
        val d = LocalDate.of(2026, 10, 2)
        assertEquals("I have not done anything yet today.", Activity.lines(d, emptyList()).single())
        val l = Activity.lines(d, listOf(Activity.Entry(LocalDateTime.of(2026, 10, 1, 9, 0), "old"), Activity.Entry(d.atTime(9, 5), "Stopped ORB 5.")))
        assertEquals(listOf("Today I did 1 thing:", "09:05 Stopped ORB 5."), l)
        assertEquals("Self-check: all working.", SelfCheck.lines(listOf("Voice" to true, "Model" to null)).first())
        assertEquals("Self-check: Data feed needs a look.", SelfCheck.lines(listOf("Voice" to true, "Data feed" to false)).first())
    }

    @Test fun batterySaver() {
        assertEquals(1000L, BatterySaver.gap(1000, 15, true))
        assertEquals(2000L, BatterySaver.gap(1000, 15, false))
        assertEquals(4000L, BatterySaver.gap(1000, 8, false))
        assertEquals(1000L, BatterySaver.gap(1000, 80, false))
    }

    @Test fun hindiKeepsNumbers() {
        val en = "Nifty is at 24,100.50, up 0.4%."
        assertEquals("निफ्टी 24,100.50 पर है, 0.4% ऊपर।", Hindi.accept(en, "निफ्टी 24,100.50 पर है, 0.4% ऊपर।"))
        assertNull(Hindi.accept(en, "निफ्टी 24,000 पर है, 0.4% ऊपर।"))
        assertNull(Hindi.accept(en, "Nifty is at 24,100.50, up 0.4%."))
    }

    @Test fun newQuestionsRoute() {
        assertEquals(setOf(Section.ACTIVITY), AppAnswers.sections("What did you do today?"))
        assertEquals(setOf(Section.READY), AppAnswers.sections("Am I ready to go live?"))
        assertEquals(setOf(Section.REGIME), AppAnswers.sections("What is the market regime?"))
        assertEquals(setOf(Section.LOSSES), AppAnswers.sections("Why did that trade lose?"))
        for (q in listOf("Am I ready to go live?", "Why did that trade lose?", "What did you do today?", "What is the market regime?"))
            assertTrue(Topic.ACCOUNT in Ask.parse(q).topics, q)
    }
}

class MuteTest {
    private fun k(s: String) = Commands.parse(s)?.kind

    @Test fun muteAndUnmute() {
        for (s in listOf("mute", "Jarvis mute", "be mute", "Jarvis, be quiet", "mute yourself", "stop talking", "don't speak", "go silent", "voice off", "shut up"))
            assertEquals(Command.Kind.MUTE, k(s), s)
        for (s in listOf("unmute", "Jarvis unmute", "speak again", "voice on", "you can speak now", "talk again"))
            assertEquals(Command.Kind.UNMUTE, k(s), s)
        assertEquals(Command.Kind.HINDI, k("reply in Hindi"))
        assertEquals(Command.Kind.ENGLISH, k("reply in English"))
        // Not mutes: questions and other commands.
        assertNull(k("is jarvis muted?"))
        assertEquals(Command.Kind.STOP_ALL, k("stop all strategies"))
        assertEquals(Wake.Heard.Ask("mute"), Wake.heard("Jarvis mute", false))
    }
}
