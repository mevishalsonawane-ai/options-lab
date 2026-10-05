package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AirtimeTest {
    private val day = LocalDate.of(2026, 10, 5)
    private fun at(h: Int, m: Int): LocalDateTime = day.atTime(h, m)

    private fun sharp(m: Market, up: Boolean, t: LocalDateTime) = Airtime.Alert(Airtime.Source.SHARPMOVE, t, m, up,
        "Boss, ${m.label} ${if (up) "rose" else "fell"} 0.40% in 8 minutes. Timing only, not a cause; the details are in the chat.",
        "${m.label} ${if (up) "rose" else "fell"} 0.40% in 8 minutes")
    private fun moment(m: Market, up: Boolean, t: LocalDateTime) = Airtime.Alert(Airtime.Source.MOMENTS, t, m, up,
        "${m.label} went ${if (up) "above yesterday's high" else "below yesterday's low"}, Boss.", "${m.label} ${if (up) "above yesterday's high" else "below yesterday's low"}")
    private fun orb(m: Market, up: Boolean, t: LocalDateTime) = Airtime.Alert(Airtime.Source.ORB, t, m, up,
        "${m.label} broke ${if (up) "above" else "below"} its opening range.", "${m.label} out of its opening range ${if (up) "above" else "below"}")
    private fun vix(t: LocalDateTime) = Airtime.Alert(Airtime.Source.VIX, t, Market.VIX, true,
        "Fear is spiking, Boss: India VIX is up 11% today.", "India VIX up 11% on the day")
    private fun oi(t: LocalDateTime, n: Int) = Airtime.Alert(Airtime.Source.OI, t, null, null, "Nifty's biggest call wall moved to 2550$n.", "Nifty's call wall moved to 2550$n")

    private fun pass(s: Airtime.State, now: LocalDateTime, vararg a: Airtime.Alert): Pair<Airtime.Out, List<Boolean>> {
        var st = s; val pops = ArrayList<Boolean>()
        for (x in a) { val (n, pop) = Airtime.offer(st, x); st = n; pops += pop }
        return Airtime.flush(st, now) to pops
    }

    @Test fun oneAlertIsSpokenAsItIsAndPopsUp() {
        val (o, pops) = pass(Airtime.State(), at(11, 20), sharp(Market.NIFTY, false, at(11, 20)))
        assertEquals(listOf(true), pops)
        assertEquals("Boss, Nifty fell 0.40% in 8 minutes. Timing only, not a cause; the details are in the chat.", o.say)
        assertTrue(o.state.pending.isEmpty())
    }

    @Test fun alertsAboutTheSameMoveInOnePassAreOneLineLedByTheSharpMove() {
        val (o, pops) = pass(Airtime.State(), at(11, 21), moment(Market.NIFTY, false, at(11, 21)), sharp(Market.NIFTY, false, at(11, 21)),
            orb(Market.BANKNIFTY, false, at(11, 21)))
        assertEquals(listOf(true, false, false), pops)          // one pop-up for the move
        val say = o.say!!
        assertTrue(say.startsWith("Boss, Nifty fell 0.40%"), say)
        assertTrue(say.contains("Same move: Nifty below yesterday's low; BankNifty out of its opening range below."), say)
        assertFalse(say.contains("Also:"), say)
    }

    @Test fun aDifferentMoveInTheSamePassIsAnAlsoAndTheOppositeWayIsNotTheSameMove() {
        val (o, _) = pass(Airtime.State(), at(11, 21), sharp(Market.NIFTY, false, at(11, 21)), vix(at(11, 21)))
        assertTrue(o.say!!.contains("Also: India VIX up 11% on the day."), o.say)
        val (o2, pops) = pass(Airtime.State(), at(11, 21), sharp(Market.NIFTY, false, at(11, 21)), moment(Market.NIFTY, true, at(11, 21)))
        assertEquals(listOf(true, true), pops)
        assertTrue(o2.say!!.contains("Also: Nifty above yesterday's high."), o2.say)
    }

    @Test fun aLaterAlertAboutAMoveAlreadyToldIsNotSaidAgainButAFreshMoveIs() {
        val (first, _) = pass(Airtime.State(), at(11, 20), sharp(Market.NIFTY, false, at(11, 20)))
        // Four minutes on: the watch's moment about the same fall - chat only, no pop-up, nothing said.
        val (second, pops) = pass(first.state, at(11, 24), moment(Market.NIFTY, false, at(11, 24)))
        assertEquals(listOf(false), pops)
        assertNull(second.say)
        assertEquals(Airtime.How.SAME_MOVE, second.state.log.last().how)
        // Twenty minutes on, a new break is a new move.
        val (third, pops3) = pass(second.state, at(11, 40), orb(Market.NIFTY, false, at(11, 40)))
        assertEquals(listOf(true), pops3)
        assertNotNull(third.say)
        // BankNifty the same way within five minutes is the same market move; ten minutes later it is not.
        val (bn, _) = pass(third.state, at(11, 44), moment(Market.BANKNIFTY, false, at(11, 44)))
        assertNull(bn.say)
        val (bn2, _) = pass(bn.state, at(11, 52), moment(Market.BANKNIFTY, false, at(11, 52)))
        assertNotNull(bn2.say)
    }

    @Test fun anAlertNotAboutAMoveIsNeverMergedAway() {
        val (o, pops) = pass(Airtime.State(), at(12, 0), oi(at(12, 0), 0), oi(at(12, 0), 1))
        assertEquals(listOf(true, true), pops)
        assertTrue(o.say!!.contains("Also: Nifty's call wall moved to 25501."), o.say)
    }

    @Test fun atMostFourLinesAnHourAreSpokenThenTheChatOnlyUntilTheHourRollsOn() {
        var s = Airtime.State()
        val said = ArrayList<String?>()
        for ((i, min) in listOf(0, 12, 24, 36, 48).withIndex()) {
            val (o, pops) = pass(s, at(10, min), oi(at(10, min), i))
            assertEquals(listOf(true), pops)                 // over the limit it still pops up
            said += o.say; s = o.state
        }
        assertEquals(4, said.count { it != null })
        assertTrue(said[3]!!.endsWith("More market alerts this hour go to the chat only."), said[3])
        assertNull(said[4])
        assertEquals(Airtime.How.OVER_LIMIT, s.log.last().how)
        assertEquals(4, Airtime.spokenLastHour(s, at(10, 48)))
        // 11:01: the 10:00 line is more than an hour old - room for one more.
        val (later, _) = pass(s, at(11, 1), oi(at(11, 1), 9))
        assertNotNull(later.say)
    }

    @Test fun aStaleAlertGoesUnsaid() {
        val (s, _) = Airtime.offer(Airtime.State(), vix(at(10, 0)))
        val o = Airtime.flush(s, at(10, 7))
        assertNull(o.say)
        assertTrue(o.state.pending.isEmpty())
    }

    @Test fun theWatchsPopUpsAreDeduplicatedButNeverSpokenAndAnArmLossIsNeverHeld() {
        val burst = Watch.Alert("burst|NIFTY|11", "Nifty -0.52% in 30 minutes", "Nifty moved -0.52% in the last 30 minutes.")
        val w = Airtime.ofWatch(burst, at(11, 15))!!
        assertEquals(Market.NIFTY, w.subject); assertEquals(false, w.up); assertFalse(w.voice)
        val (s1, pop1) = Airtime.offer(Airtime.State(), w)
        assertTrue(pop1); assertTrue(s1.pending.isEmpty())
        // The sharp move about the same fall: no second pop-up, but it is still the first thing said aloud.
        val (s2, pop2) = Airtime.offer(s1, sharp(Market.NIFTY, false, at(11, 16)))
        assertFalse(pop2)
        assertNotNull(Airtime.flush(s2, at(11, 16)).say)
        assertEquals(true, Airtime.ofWatch(Watch.Alert("day|BANKNIFTY|up", "BankNifty +1.6% today", "x"), at(11, 0))!!.up)
        assertEquals(Market.VIX, Airtime.ofWatch(Watch.Alert("vix-jump|11", "India VIX +6% in 30 minutes", "x"), at(11, 0))!!.subject)
        assertNull(Airtime.ofWatch(Watch.Alert("arm|ORB", "ORB is down Rs 3,000 today", "x"), at(11, 0)))
    }

    @Test fun boss() {
        val (o, _) = pass(Airtime.State(), at(10, 0), orb(Market.NIFTY, true, at(10, 0)))
        assertTrue(o.say!!.startsWith("Boss, Nifty broke above"), o.say)
    }

    @Test fun askedAndTheAnswerSaysWhatWasHeldAndWhy() {
        listOf("why so quiet", "Jarvis, why are you so quiet today?", "did you hold back any alerts", "what alerts did you skip today",
            "how many alerts have you given today").forEach { assertTrue(Airtime.asked(it), it) }
        listOf("why is nifty quiet", "set an alert at 25000", "what alerts do i have").forEach { assertFalse(Airtime.asked(it), it) }
        assertTrue(Airtime.say(Airtime.State(), at(10, 0)).startsWith("No market alerts today yet, Boss."))
        val (a, _) = pass(Airtime.State(), at(11, 20), sharp(Market.NIFTY, false, at(11, 20)))
        val (b, _) = pass(a.state, at(11, 24), moment(Market.NIFTY, false, at(11, 24)))
        val say = Airtime.say(b.state, at(12, 0))
        assertTrue(say.startsWith("Boss, today 1 market alert went into what I said aloud;"), say)
        assertTrue(say.contains("1 went to the chat only because I had just told you about the same move"), say)
        assertTrue(say.contains("Kept to the chat: Nifty below yesterday's low (11:24)."), say)
        // Yesterday's log is gone the next day.
        assertTrue(Airtime.say(b.state, at(12, 0).plusDays(1)).startsWith("No market alerts today yet"))
    }

    @Test fun aKindBossLetsPassIsAskedAfterTheMoveRulesAndNeverCountsAsTold() {
        // The learned say holds the moments back: the line is the ORB's alone, and the moment is logged as learned.
        val asked = ArrayList<Airtime.Source>()
        var st = Airtime.State()
        for (x in listOf(moment(Market.NIFTY, true, at(10, 0)), orb(Market.NIFTY, true, at(10, 0)))) st = Airtime.offer(st, x).first
        val o = Airtime.flush(st, at(10, 0)) { a -> asked += a.source; a.source != Airtime.Source.MOMENTS }
        assertEquals(listOf(Airtime.Source.MOMENTS, Airtime.Source.ORB), asked)
        assertEquals("Boss, Nifty broke above its opening range.", o.say)
        assertEquals(Airtime.How.LEARNED, o.state.log.first { it.brief.contains("high") }.how)
        assertEquals(1, Airtime.heldToday(o.state, at(10, 1)))
        // All held by it: nothing said, nothing told - so the next alert about the move is still said.
        val (s2, _) = Airtime.offer(Airtime.State(), sharp(Market.NIFTY, false, at(11, 0)))
        val o2 = Airtime.flush(s2, at(11, 0)) { false }
        assertNull(o2.say)
        assertTrue(o2.state.spoken.isEmpty())
        // A line about a move already told is never asked of it (nothing to learn from).
        val (s3, _) = Airtime.offer(o.state, orb(Market.NIFTY, true, at(10, 3)))
        Airtime.flush(s3, at(10, 3)) { error("not asked") }
        assertTrue(Airtime.say(o.state, at(12, 0)).contains("1 of a kind you usually let pass"))
    }
}
