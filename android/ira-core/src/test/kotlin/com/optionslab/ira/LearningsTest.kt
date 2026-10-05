package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LearningsTest {
    private val today = LocalDate.of(2026, 10, 5)
    private val now: LocalDateTime = today.atTime(15, 0)

    private val newWord = Corrections.Learned("nifty kaisa", "how is nifty", used = today, since = today.minusDays(2))
    private val oldWord = Corrections.Learned("bnf lvls", "what are the levels on banknifty", used = today.minusDays(3), since = today.minusDays(20))
    private val undated = Corrections.Learned("vix kya", "how is vix", used = today)
    private val newRoutine = Routine.Kept("BANKNIFTY|overview", Routine.Kind.DAILY, 9 * 60 + 20, null, today.plusDays(40), since = today.minusDays(1))
    private val oldRoutine = Routine.Kept("NIFTY|levels", Routine.Kind.DAILY, 10 * 60, null, today.plusDays(10), since = today.minusDays(35))

    /** VIX alerts said 8 times, never followed up, two held back this week. */
    private fun quietAlerts(): AlertSense.Log {
        var log = AlertSense.Log()
        for (d in 1..8) log = AlertSense.spoken(log, "VIX", today.minusDays(d.toLong()).atTime(10, 0))
        log = AlertSense.heldBack(log, "VIX", today.minusDays(2).atTime(11, 0))
        log = AlertSense.heldBack(log, "VIX", today.minusDays(1).atTime(11, 0))
        return log
    }

    private fun inputs() = Learnings.Inputs(words = listOf(newWord, oldWord, undated), routines = listOf(newRoutine, oldRoutine), alerts = quietAlerts(),
        plan = Improve.Plan(Improve.weekOf(today), listOf(Improve.Goal(Improve.Kind.FEWER_MISTAKES, 1, 3))))

    @Test fun recognisesTheQuestions() {
        assertEquals(Learnings.Ask.WEEK, Learnings.asked("What have you learned this week?"))
        assertEquals(Learnings.Ask.WEEK, Learnings.asked("jarvis what did you learn lately"))
        assertEquals(Learnings.Ask.CHANGED, Learnings.asked("What changed in how you work?"))
        assertEquals(Learnings.Ask.CHANGED, Learnings.asked("how have you changed this week, boss"))
        assertEquals(Learnings.Ask.ALL, Learnings.asked("Show me everything you've learned about me"))
        assertEquals(Learnings.Ask.ALL, Learnings.asked("everything you have learned"))
        // Others' questions stay theirs.
        assertNull(Learnings.asked("what have you learned about me"))
        assertNull(Learnings.asked("what have you learned?"))
        assertNull(Learnings.asked("what words have you learned"))
        assertNull(Learnings.asked("how is nifty"))
        assertTrue(AboutBoss.knowAsked("what have you learned about me"))
    }

    @Test fun whatDidYouLearnAboutMeIsTheOverview() {
        for (q in listOf("Jarvis, what did you learn about me?", "what did you learn about me so far", "what did you learn from me, boss",
                "tell me what you learned about me", "tumne mere baare mein kya seekha", "tumne mere baare mein kya seekha hai",
                "aapne mere baare me kya kya sikha", "mere baare mein tumne kya seekha", "jarvis tumne mujhse kya seekha")) {
            assertEquals(Learnings.Ask.ALL, Learnings.asked(q), q)
            // Not Boss's own facts, words or routine, never an order or a command, and no bundle.
            assertFalse(AboutBoss.knowAsked(q), q); assertFalse(Corrections.wordsAsked(q), q); assertNull(Corrections.forgetWordAsked(q), q)
            assertFalse(Routine.asked(q), q); assertFalse(Learnings.undoAsked(q), q)
            val p = Ask.parse(q); assertNull(p.order, q); assertNull(p.command, q)
            assertFalse(Bundle.acts(q), q)
        }
        // This week's stays the week's; what Boss told him stays his facts.
        assertEquals(Learnings.Ask.WEEK, Learnings.asked("is hafte tumne kya seekha"))
        assertNull(Learnings.asked("what did you learn from the market today"))
        assertNull(Learnings.asked("what did i tell you about me"))
    }

    /** One item in every area, each with an undo where the area has one by voice. */
    private val UNDOS: Map<Learnings.Area, String?> = mapOf(
        Learnings.Area.WORDS to "forget the word nifty kaisa", Learnings.Area.ROUTINES to "forget my routine", Learnings.Area.ALERTS to "say everything again",
        Learnings.Area.CLARITY to Clarity.UNDO, Learnings.Area.WORD_FIT to WordFit.UNDO, Learnings.Area.FIGURE_FIRST to FigureFirst.UNDO,
        Learnings.Area.MORNING to MorningSense.UNDO, Learnings.Area.STARS to HonestStars.UNDO, Learnings.Area.HOURS to TalkHours.UNDO,
        Learnings.Area.MORNING_ASKS to MorningAsks.UNDO, Learnings.Area.TURN_DOWNS to TurnDowns.UNDO, Learnings.Area.LENGTHS to TopicLength.UNDO,
        Learnings.Area.USUAL_INDEX to UsualIndex.UNDO, Learnings.Area.NICKNAMES to Nicknames.UNDO)

    private fun everyArea(): List<Learnings.Item> = Learnings.Area.entries.map { a ->
        Learnings.Item(a, "learned-${a.name.lowercase()}", today.minusDays(1), "why-${a.name.lowercase()}", UNDOS[a])
    }

    @Test fun theOverviewListsEveryAreaWithItsUndo() {
        val said = Learnings.say(everyArea(), Learnings.Ask.ALL, today, locked = false)
        for (a in Learnings.Area.entries) {
            assertTrue(said.contains(a.title), a.name)
            assertTrue(said.contains("learned-${a.name.lowercase()}"), a.name)
        }
        for ((a, undo) in UNDOS) assertTrue(said.contains("Undo: \"$undo\""), a.name)
        assertTrue(said.startsWith("Everything I've learned, Boss:"))
        assertTrue(said.contains(Learnings.NEVER_ACTS)); assertTrue(said.contains("undo everything you learned this week"))
    }

    @Test fun theNewAreasAreAllThere() {
        val names = Learnings.Area.entries.map { it.name }.toSet()
        for (n in listOf("LENGTHS", "TURN_DOWNS", "MORNING_ASKS", "HOURS", "STARS", "MORNING", "USUAL_INDEX", "NICKNAMES", "FIGURE_FIRST", "CLARITY",
                "WORD_FIT", "AGAIN", "WRONG_THING", "ARM_HABITS")) assertTrue(n in names, n)
        // Boss's own habits and records are personal: never said on a locked phone.
        for (a in listOf(Learnings.Area.WORDS, Learnings.Area.ROUTINES, Learnings.Area.STARS, Learnings.Area.TURN_DOWNS, Learnings.Area.USUAL_INDEX,
                Learnings.Area.NICKNAMES, Learnings.Area.ARM_HABITS, Learnings.Area.GOALS)) assertTrue(a.personal, a.name)
    }

    @Test fun eachUndoIsHeardAsItsOwnReset() {
        assertEquals(TopicLength.Request.RESET, TopicLength.asked(TopicLength.UNDO))
        assertEquals(TurnDowns.Request.RESET, TurnDowns.asked(TurnDowns.UNDO))
        assertEquals(MorningAsks.Request.RESET, MorningAsks.asked(MorningAsks.UNDO))
        assertEquals(TalkHours.Request.RESET, TalkHours.asked(TalkHours.UNDO))
        assertEquals(HonestStars.Request.RESET, HonestStars.asked(HonestStars.UNDO))
        assertEquals(MorningSense.Request.RESET, MorningSense.asked(MorningSense.UNDO))
        assertEquals(UsualIndex.Request.RESET, UsualIndex.asked(UsualIndex.UNDO))
        assertEquals(Nicknames.Request.RESET, Nicknames.asked(Nicknames.UNDO))
        assertEquals(Clarity.Request.RESET, Clarity.asked(Clarity.UNDO))
        assertEquals(FigureFirst.Request.RESET, FigureFirst.asked(FigureFirst.UNDO))
        assertTrue(Corrections.forgetWordAsked("forget the word nifty kaisa") != null)
        assertTrue(Routine.forgetAsked("forget my routine"))
    }

    @Test fun aLockedPhoneHearsNoneOfBosssOwn() {
        val said = Learnings.say(everyArea(), Learnings.Ask.ALL, today, locked = true)
        for (a in Learnings.Area.entries) {
            val item = "learned-${a.name.lowercase()}"
            if (a.personal) { assertFalse(said.contains(item), a.name); assertFalse(said.contains(a.title), a.name) }
            else assertTrue(said.contains(item), a.name)
        }
        for ((a, undo) in UNDOS) if (a.personal && undo != null) assertFalse(said.contains(undo), a.name)
        assertTrue(said.contains(Learnings.UNLOCK)); assertFalse(said.contains("undo everything"))
        assertTrue(said.contains("Boss"))
    }

    @Test fun recognisesTheUndo() {
        assertTrue(Learnings.undoAsked("Undo everything you learned this week"))
        assertTrue(Learnings.undoAsked("jarvis, forget everything you've learned this week please"))
        assertTrue(Learnings.undoAsked("unlearn this week"))
        assertFalse(Learnings.undoAsked("forget the word nifty kaisa"))
        assertFalse(Learnings.undoAsked("forget my routine"))
        assertFalse(Learnings.undoAsked("undo"))
        // A question to answer, not an order or a command; never learned as a wording; no bundle by itself.
        val p = Ask.parse("undo everything you learned this week")
        assertNull(p.order); assertNull(p.command)
        assertFalse(Bundle.acts("undo everything you learned this week"))
        assertFalse(Bundle.acts("show me everything you've learned about me"))
        assertNull(Corrections.learn("undo everything you learned this week", "how is nifty"))
    }

    @Test fun itemsTellWhenWhyAndUndo() {
        val items = Learnings.items(inputs(), now)
        val words = items.filter { it.area == Learnings.Area.WORDS }
        assertEquals(3, words.size)
        assertEquals("\"nifty kaisa\" means \"how is nifty\"", words.first().what)
        assertEquals(today.minusDays(2), words.first().on)
        assertEquals("forget the word nifty kaisa", words.first().undo)
        val routines = items.filter { it.area == Learnings.Area.ROUTINES }
        assertEquals(2, routines.size)
        assertEquals("forget my routine", routines.first().undo)
        val alerts = items.single { it.area == Learnings.Area.ALERTS }
        assertEquals("say everything again", alerts.undo)
        assertTrue(alerts.why.contains("2 kept to the chat this week"), alerts.why)
        assertEquals(today.minusDays(2), alerts.on)
        assertTrue(items.any { it.area == Learnings.Area.GOALS })
    }

    @Test fun theWeekShowsOnlyWhatChangedInIt() {
        val said = Learnings.say(Learnings.items(inputs(), now), Learnings.Ask.WEEK, today, locked = false)
        assertTrue(said.contains("nifty kaisa")); assertFalse(said.contains("bnf lvls")); assertFalse(said.contains("vix kya"))
        assertTrue(said.contains("Boss")); assertTrue(said.contains(Learnings.NEVER_ACTS))
        assertTrue(said.contains("undo everything you learned this week"))
        val all = Learnings.say(Learnings.items(inputs(), now), Learnings.Ask.ALL, today, locked = false)
        assertTrue(all.contains("bnf lvls")); assertTrue(all.contains("vix kya"))
    }

    @Test fun aLockedPhoneHearsNothingPersonal() {
        val said = Learnings.say(Learnings.items(inputs(), now), Learnings.Ask.ALL, today, locked = true)
        assertFalse(said.contains("nifty kaisa")); assertFalse(said.contains("Routines")); assertFalse(said.contains("goals for"))
        assertTrue(said.contains("fear (VIX)"))
        assertTrue(said.contains(Learnings.UNLOCK))
        assertFalse(said.contains("undo everything"))
    }

    @Test fun nothingLearned() {
        val said = Learnings.say(Learnings.items(Learnings.Inputs(), now), Learnings.Ask.WEEK, today, locked = false)
        assertTrue(said.startsWith("Nothing new"), said)
        assertTrue(Learnings.undo(Learnings.Inputs(), now).empty)
        assertEquals(Learnings.NOTHING, Learnings.done(Learnings.undo(Learnings.Inputs(), now)))
    }

    @Test fun undoTouchesOnlyThisWeeksLearning() {
        val i = inputs()
        val u = Learnings.undo(i, now)
        assertEquals(listOf(newWord), u.words)
        assertEquals(listOf(newRoutine), u.routines)
        assertEquals(listOf("VIX"), u.alerts.map { it.kind })
        assertEquals(1, u.goals)
        assertEquals(listOf(oldWord, undated), Learnings.keepWords(i.words, today))
        assertEquals(listOf(oldRoutine), Learnings.keepRoutines(i.routines, today))
        val dropped = Learnings.dropGoals(i.plan, today)!!
        assertTrue(dropped.goals.isEmpty()); assertEquals(i.plan!!.week, dropped.week)
        assertNull(Learnings.dropGoals(dropped, today))
        val offer = Learnings.offer(u)
        assertTrue(offer.startsWith("Boss, shall I undo"))
        assertTrue(offer.contains("PIN") && offer.contains("Live") && offer.contains("guard") && offer.contains("Google speech"))
        assertTrue(Learnings.done(u).startsWith("Done, Boss"))
    }

    @Test fun keptDatesSurviveSaving() {
        val k = newRoutine
        assertEquals(k, Routine.decodeKept(Routine.encode(k)))
        val old = k.copy(since = null)
        assertEquals(old, Routine.decodeKept(Routine.encode(old)))
        assertEquals(5, Routine.encode(old).split("#").size)
    }
}
