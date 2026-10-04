package com.optionslab.ira

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LaterTest {
    private val now = LocalDateTime.of(2026, 10, 3, 19, 50)   // a Saturday evening

    @Test fun aCommandForLaterIsReadOffItsTime() {
        val w = Later.split("start all the arms by tomorrow morning 9am", now)!!
        assertEquals(LocalDateTime.of(2026, 10, 4, 9, 0), w.at)
        assertEquals("start all the arms", w.rest)
        assertEquals(Command.Kind.START_ALL, Ask.parse(w.rest).command?.kind)
        assertEquals("tomorrow (Sun 4 Oct) at 09:00", Later.say(w.at, now))

        assertEquals(LocalDateTime.of(2026, 10, 4, 15, 15), Later.split("stop strategy 2 tomorrow at 3:15 pm", now)!!.at)
        assertEquals("stop strategy 2", Later.split("stop strategy 2 tomorrow at 3:15 pm", now)!!.rest)
        assertEquals(LocalDateTime.of(2026, 10, 3, 20, 20), Later.split("in 30 minutes stop all strategies", now)!!.at)
        // No day and the time has passed today: tomorrow. "at 2" is 2 pm in market terms.
        assertEquals(LocalDateTime.of(2026, 10, 4, 9, 20), Later.split("start all arms at 9:20", now)!!.at)
        assertEquals(LocalDateTime.of(2026, 10, 4, 14, 0), Later.split("stop all strategies at 2", now)!!.at)
        assertEquals(LocalDateTime.of(2026, 10, 4, 9, 15), Later.split("start all arms tomorrow morning", now)!!.at)
    }

    @Test fun noTimeNoLater() {
        assertNull(Later.split("start all the arms", now))
        assertNull(Later.split("stop strategy 2", now))                  // a bare number is not a time
        assertNull(Later.split("start all arms tomorrow", now))          // a day with no time
        assertNull(Later.split("start all arms today at 9am", now))      // already past
        assertNull(Later.split("start all arms in 900 hours", now))      // too far ahead
        assertNull(Later.split("start all arms at 13pm", now))
    }

    @Test fun onlySafeCommandsMayWait() {
        assertTrue(Command.Kind.START_ALL in Later.ALLOWED && Command.Kind.STOP_ONE in Later.ALLOWED)
        for (k in listOf(Command.Kind.MODE_LIVE, Command.Kind.KILL_OFF, Command.Kind.AUTOPILOT_ON, Command.Kind.JTRADES_LIVE, Command.Kind.CLOSE_ALL))
            assertTrue(k !in Later.ALLOWED, k.name)
    }
}

class DailyStopLossTest {
    @Test fun theDailyStopLossIsTheDailyLossLimit() {
        for (q in listOf("whats my daily stop loss", "what is my daily SL", "stop loss for the day?", "what's my max loss per day")) {
            val p = Ask.parse(q)
            assertNull(p.command, "$q is a question, not a command: ${p.command}")
            assertNull(p.order, q)
            val s = AppAnswers.sections(q)
            assertTrue(Section.RISK in s && Section.PROTECTIONS !in s && Section.PNL !in s, "$q -> $s")
        }
        // The stops on positions are still the stops.
        assertTrue(Section.PROTECTIONS in AppAnswers.sections("what are my stop losses on open positions"))
        // A change in those words sets the daily loss limit.
        val c = Ask.parse("set my daily stop loss to 5000").command
        assertEquals(Command.Kind.SET_LIMIT, c?.kind, "$c")
        assertEquals(SettingsTalk.Key.DAILY_LOSS.name, c?.target)
    }
}

class HushTest {
    @Test fun jarvisStopMeansStopTalking() {
        for (q in listOf("Jarvis stop", "Jarvis, stop it", "jarvis enough", "Jarvis be quiet", "jarvis shut up", "Jarvis never mind"))
            assertEquals(Wake.Heard.Hush, Wake.heard(q, false), q)
        // In the follow-up window, without the name too.
        assertEquals(Wake.Heard.Hush, Wake.heard("stop", true))
        assertTrue(Wake.hush("Stop talking!") && !Wake.hush("stop all strategies"))
        // Switching listening off still needs those words; stopping strategies is still a request.
        assertEquals(Wake.Heard.Stop, Wake.heard("Jarvis, stop listening", false))
        assertEquals(Wake.Heard.Ask("stop all strategies"), Wake.heard("Jarvis stop all strategies", false))
    }
}

class OutlookTest {
    @Test fun aPredictionIsAnsweredForThatIndexWithoutGuessing() {
        val q = "monday prediction for bank nifty"
        assertTrue(Outlook.asked(q))
        assertEquals(listOf(Market.BANKNIFTY), Market.mentioned(q).take(1))
        assertEquals("Monday", Outlook.session(q))
        assertTrue(Outlook.asked("what will nifty do tomorrow") && Outlook.asked("nifty outlook") && !Outlook.asked("how is nifty"))
        // Six days of BankNifty rising 100 points a day.
        val start = java.time.LocalDate.of(2026, 9, 28)
        val bars = (0 until 6).flatMap { d ->
            val base = 55_000.0 + d * 100
            (0 until 3).map { i -> Candle(start.plusDays(d.toLong()).atTime(9, 15 + i), base, base + 50, base - 50, base + 10 * i) }
        }
        val s = Outlook.say(Market.BANKNIFTY, bars, 12.0, trading = false, text = q)!!
        assertTrue(s.startsWith("Boss, I don't predict prices"), s)
        assertTrue(s.contains("for Monday") && s.contains("BankNifty closed at 55,520.00") && s.contains("has been rising"), s)
        assertTrue(s.contains("usual move in a day") && s.contains("pivots") && s.endsWith("not a forecast."), s)
        // The next hour and the next week too.
        assertEquals(Outlook.Span.HOUR, Outlook.span("bank nifty prediction for next hour"))
        val h = Outlook.say(Market.BANKNIFTY, bars, 12.0, trading = true, text = "bank nifty prediction for next hour")!!
        assertTrue(h.contains("for the next hour") && h.contains("usual move in an hour"), h)
        val w = Outlook.say(Market.BANKNIFTY, bars, 12.0, trading = false, text = "nifty bank outlook next week")!!
        assertTrue(w.contains("for the coming week") && w.contains("usual move in a week") && w.contains("Weekly pivots"), w)
        assertNull(Outlook.say(Market.VIX, bars, 12.0, false, q))
    }
}

class ReviewLaterTest {
    private val now = java.time.LocalDateTime.of(2026, 10, 5, 10, 0)

    @Test fun aTimedRequestIsNeverDoneNow() {
        // A time named, even one that cannot be used: never run at once.
        assertTrue(Later.mentionsTime("buy 1 lot nifty 25000 ce tomorrow at 9:30"))
        assertTrue(Later.mentionsTime("start all arms today at 9:30"))
        assertNull(Later.split("start all arms today at 9:30", now))              // passed: asked again, not run
        assertTrue(Later.mentionsTime("stop all strategies tomorrow"))
        assertTrue(!Later.mentionsTime("stop all strategies today") && !Later.mentionsTime("set alarm nifty 25000"))
        // "Kill switch on at 3" keeps its "on".
        val w = Later.split("kill switch on at 3 pm", now)!!
        assertEquals(Command.Kind.KILL_ON, Ask.parse(w.rest).command?.kind, w.rest)
    }

    @Test fun paperDailyStopLossIsThePaperLimit() {
        assertEquals(SettingsTalk.Key.PAPER_DAILY_LOSS.name, Ask.parse("set paper daily stop loss to 5000").command?.target)
        assertTrue(!Outlook.asked("what's your view on nifty option chain") && !Outlook.asked("what is the pcr bias") && !Outlook.asked("what is the news outlook"))
        assertTrue(Wake.hush("Jarvis stop"))
    }
}

class VoiceCheckWordsTest {
    @Test fun whyCantYouHearMeIsTheVoiceCheck() {
        for (q in listOf("why can't you hear me", "why aren't you listening", "listening is not working", "mic not working"))
            assertEquals(Command.Kind.VOICE_CHECK, Ask.parse(q).command?.kind, q)
    }
}


class OutlookBriefTest {
    @Test fun theMorningLineForAnIndex() {
        val start = java.time.LocalDate.of(2026, 9, 28)
        val bars = (0 until 6).flatMap { d ->
            val base = 25_000.0 + d * 50
            (0 until 3).map { i -> Candle(start.plusDays(d.toLong()).atTime(9, 15 + i), base, base + 40, base - 40, base + 10 * i) }
        }
        val b = Outlook.brief(Market.NIFTY, bars, 12.0)!!
        assertTrue(b.startsWith("Nifty: rising (up 5 of the last 5 sessions); a usual day spans about ") && b.contains("; pivot 25,"), b)
        assertNull(Outlook.brief(Market.VIX, bars, 12.0))
        assertNull(Outlook.brief(Market.NIFTY, bars.take(3), 12.0))
    }
}
