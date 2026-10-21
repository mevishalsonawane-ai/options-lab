package com.optionslab.ira

import com.optionslab.ira.SundayReview.Book
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Jarvis's spoken Sunday review (TODO I15; 20 Oct, enhancement round 2): paper and live apart, the best and worst arm by net
 * after charges with their sample sizes, the biggest single cost, short weeks, ties, long names and the two-minute limit.
 */
class SundayReviewTest {
    private val mon: LocalDate = LocalDate.of(2026, 10, 12)      // Monday
    private val sun: LocalDate = mon.plusDays(6)
    private fun weekdays(d: LocalDate) = d.dayOfWeek.value <= 5

    private fun p(arm: String, day: Int, net: Double, charges: Double = 40.0) = SundayReview.Trade(Book.PAPER, arm, mon.plusDays(day.toLong()), net, charges)
    private fun l(arm: String, day: Int, net: Double, charges: Double = 60.0) = SundayReview.Trade(Book.LIVE, arm, mon.plusDays(day.toLong()), net, charges)

    private fun review(paper: List<SundayReview.Trade>?, live: List<SundayReview.Trade>? = emptyList(), tradingDay: (LocalDate) -> Boolean = ::weekdays) =
        SundayReview.compose(mon, paper, live, tradingDay)

    /** The paper part and the live part of [text] (live starts at "Live:"). */
    private fun parts(text: String): Pair<String, String> = text.substringBefore(" Live:") to ("Live:" + text.substringAfter(" Live:"))

    // ---- the week ----

    @Test fun dueOnSundaysOnly() {
        assertTrue(SundayReview.due(sun))
        for (d in 0L..5L) assertFalse(SundayReview.due(mon.plusDays(d)), mon.plusDays(d).dayOfWeek.name)
    }

    @Test fun theWeekReviewedIsTheOneJustEndedAtTheWeekendAndTheLastWholeOneMidweek() {
        assertEquals(mon, SundayReview.weekFor(sun))
        assertEquals(mon, SundayReview.weekFor(mon.plusDays(5)))
        assertEquals(mon.minusWeeks(1), SundayReview.weekFor(mon))
        assertEquals(mon.minusWeeks(1), SundayReview.weekFor(mon.plusDays(4)))
    }

    // ---- the books ----

    @Test fun anEmptyWeekSaysSoForBothBooks() {
        val s = review(emptyList(), emptyList())
        assertTrue(s.text.startsWith("Boss, your Sunday review for the week of 12 Oct, 5 sessions."), s.text)
        assertTrue("Paper: no paper trades this week." in s.text, s.text)
        assertTrue("Live: no live trades this week." in s.text, s.text)
        assertTrue("Nothing to change from one week's numbers." in s.text, s.text)
        assertFalse("₹" in s.text, s.text)
        assertTrue(s.paper.isEmpty() && s.live.isEmpty())
        assertTrue(s.text.endsWith("The full figures are in the chat."))
    }

    @Test fun paperOnlySaysTheBestTheWorstTheSampleAndThatThereWereNoLiveTrades() {
        val s = review(listOf(
            p("Liquidity Bank Nifty 15-min", 0, 1_200.0), p("Liquidity Bank Nifty 15-min", 1, -300.0), p("Liquidity Bank Nifty 15-min", 2, 900.0),
            p("Hero (expiry)", 3, -700.0, 45.0), p("Hero (expiry)", 3, -200.0),
            p("Solo (midday)", 4, 150.0),
        ))
        val t = s.text
        assertTrue("Paper: 6 trades, 3 won, net up ₹1,050 after ₹245 charges." in t, t)
        assertTrue("What worked: Liquidity Bank Nifty 15-min, up ₹1,800 on 3 trades, 2 won." in t, t)
        assertTrue("What cost money: Hero (expiry), down ₹900 on 2 trades, 0 won." in t, t)
        assertTrue("Biggest single cost: a Hero (expiry) trade on Thu 15 Oct, down ₹700, ₹45 of that in charges." in t, t)
        assertTrue("Also: Solo (midday), up ₹150 on 1 trade, 1 won." in t, t)
        assertTrue("Live: no live trades this week." in t, t)
        assertTrue("One thing to look at: Hero (expiry)'s paper trades" in t, t)
        assertTrue("6 paper trades is a small sample: a note, not a verdict." in t, t)
        assertTrue("I changed nothing" in t, t)
        // Paper only: nothing said about keeping the books apart (there is nothing to add together).
        assertFalse("kept apart" in t, t)
        assertEquals(listOf("Liquidity Bank Nifty 15-min", "Solo (midday)", "Hero (expiry)"), s.paper.map { it.arm })
    }

    @Test fun liveOnlyIsItsOwnSectionAndPaperSaysNone() {
        val s = review(emptyList(), listOf(l("Liquidity Bank Nifty 15-min", 0, 2_000.0), l("Pine EMA", 1, -500.0)))
        val (paper, live) = parts(s.text)
        assertTrue("Paper: no paper trades this week." in paper, paper)
        assertFalse("₹" in paper, paper)
        assertTrue(live.startsWith("Live: 2 trades, 1 won, net up ₹1,500 after ₹120 charges."), live)
        assertTrue("What cost money: Pine EMA, down ₹500 on 1 trade, 0 won." in live, live)
        // Real money first in the one thing to look at.
        assertTrue("One thing to look at: Pine EMA cost real money this week" in live, live)
    }

    @Test fun bothBooksStayApartAndAreNeverAddedTogether() {
        val paperT = listOf(p("Hero (expiry)", 0, 3_000.0, 50.0), p("Solo (midday)", 1, -1_000.0, 50.0))
        val liveT = listOf(l("Hero (expiry)", 0, -400.0, 70.0))
        val s = review(paperT, liveT)
        val (paper, live) = parts(s.text)
        assertTrue("Paper: 2 trades, 1 won, net up ₹2,000 after ₹100 charges." in paper, paper)
        assertTrue("Live: 1 trade, 0 won, net down ₹400 after ₹70 charges." in live, live)
        // No combined figure anywhere: neither the sum of the nets (₹1,600) nor of the charges (₹170), nor 3 trades.
        assertFalse("1,600" in s.text, s.text)
        assertFalse("₹170" in s.text, s.text)
        assertFalse("3 trades" in s.text, s.text)
        // Each book's own figures stay in its own part.
        assertFalse("₹2,000" in live, live)
        assertFalse("₹400" in paper, paper)
        assertTrue("Paper and live are kept apart; I never add them together." in s.text, s.text)
        assertTrue("2 paper trades and 1 live trade are small samples" in s.text, s.text)
        assertEquals(listOf("Hero (expiry)", "Solo (midday)"), s.paper.map { it.arm })
        assertEquals(listOf("Hero (expiry)"), s.live.map { it.arm })
        // A trade goes to the book it names, whichever list it came in.
        val mixed = review(paperT + liveT, emptyList())
        assertEquals(s.text, mixed.text)
    }

    @Test fun aBookThatCouldNotBeReadIsSaidSoNeverAsNoTrades() {
        val s = review(listOf(p("Hero (expiry)", 0, 500.0)), null)
        assertTrue("Live: the live record could not be read this time, so I won't guess at it." in s.text, s.text)
        assertFalse("no live trades" in s.text, s.text)
        val q = review(null, emptyList())
        assertTrue("Paper: the paper record could not be read this time" in q.text, q.text)
    }

    @Test fun onlyThatWeeksTradesCount() {
        val s = review(listOf(p("Hero (expiry)", -1, 9_999.0), p("Hero (expiry)", 7, 8_888.0), p("Hero (expiry)", 6, 100.0)))
        assertTrue("Paper: 1 trade, 1 won, net up ₹100 after ₹40 charges." in s.text, s.text)
        assertFalse("9,999" in s.text || "8,888" in s.text, s.text)
    }

    // ---- the week's shape ----

    @Test fun aHolidayWeekIsSaidAsAShortWeekWithTheDayShut() {
        val diwali = mon.plusDays(3)
        val s = review(listOf(p("Hero (expiry)", 1, 300.0)), emptyList()) { d -> weekdays(d) && d != diwali }
        assertTrue(s.text.startsWith("Boss, your Sunday review for the week of 12 Oct, a short week of 4 sessions (Thu 15 Oct shut)."), s.text)
        val none = review(emptyList(), emptyList()) { false }
        assertTrue(none.text.startsWith("Boss, your Sunday review for the week of 12 Oct: the market had no sessions that week."), none.text)
        val three = review(emptyList(), emptyList()) { d -> d == mon || d == mon.plusDays(1) }
        assertTrue(three.text.startsWith("Boss, your Sunday review for the week of 12 Oct, a short week of 2 sessions."), three.text)
        // A Saturday special session counts as a session.
        val sat = review(emptyList(), emptyList()) { d -> weekdays(d) || d == mon.plusDays(5) }
        assertTrue(sat.text.startsWith("Boss, your Sunday review for the week of 12 Oct, 6 sessions."), sat.text)
    }

    @Test fun aWeekOfOnlyLossesSaysNothingMadeMoney() {
        val s = review(listOf(p("Hero (expiry)", 0, -200.0), p("Solo (midday)", 1, -900.0), p("Solo (midday)", 2, -100.0)))
        val t = s.text
        assertTrue("Paper: 3 trades, 0 won, net down ₹1,200" in t, t)
        assertTrue("Nothing made money; the smallest loss was Hero (expiry), down ₹200 on 1 trade, 0 won." in t, t)
        assertTrue("What cost money: Solo (midday), down ₹1,000 on 2 trades, 0 won." in t, t)
        assertTrue("Biggest single cost: a Solo (midday) trade on Tue 13 Oct, down ₹900" in t, t)
        assertFalse("What worked" in t, t)
    }

    @Test fun aWeekWithNoLossSaysNoTradeLostMoney() {
        val s = review(listOf(p("Hero (expiry)", 0, 200.0), p("Solo (midday)", 1, 900.0)))
        assertTrue("No single paper trade lost money." in s.text, s.text)
        assertTrue("Nothing lost money; the weakest was Hero (expiry), up ₹200 on 1 trade, 1 won." in s.text, s.text)
        assertTrue("Nothing to change from one week's numbers." in s.text, s.text)
    }

    @Test fun chargesLargerThanTheGrossAreSaidAndAreTheThingToLookAt() {
        // Gross +₹200 (net -₹150 + ₹350 charges): the trades made money, the costs took it.
        val s = review(listOf(p("Solo (midday)", 0, 50.0, 150.0), p("Solo (midday)", 1, -200.0, 200.0)))
        val t = s.text
        assertTrue("Paper: 2 trades, 1 won, net down ₹150 after ₹350 charges." in t, t)
        assertTrue("Charges of ₹350 were more than the ₹200 the trades made before charges." in t, t)
        assertTrue("One thing to look at: paper charges took more than the trades made" in t, t)
        // Gross itself a loss: not said as the charges' doing.
        val lost = review(listOf(p("Solo (midday)", 0, -500.0, 100.0)))
        assertFalse("were more than" in lost.text, lost.text)
    }

    @Test fun tiesAreSettledTheSameWayEveryTime() {
        val a = review(listOf(p("Beta", 0, 500.0), p("Alpha", 1, 500.0), p("Gamma", 2, -300.0), p("Delta", 3, -300.0)))
        assertTrue("What worked: Alpha, up ₹500" in a.text, a.text)
        assertTrue("What cost money: Delta, down ₹300" in a.text, a.text)
        // Two trades losing the same: the earlier day is the biggest single cost.
        assertTrue("Biggest single cost: a Gamma trade on Wed 14 Oct" in a.text, a.text)
        // The same input in any order: the same words.
        val b = review(listOf(p("Delta", 3, -300.0), p("Gamma", 2, -300.0), p("Alpha", 1, 500.0), p("Beta", 0, 500.0)))
        assertEquals(a.text, b.text)
        // Every arm level with the others: said once, not as a best and a worst.
        val level = review(listOf(p("Alpha", 0, 100.0), p("Beta", 1, 100.0)))
        assertTrue("Both arms ended the same, up ₹100 each: Alpha on 1 trade, Beta on 1 trade." in level.text, level.text)
        assertFalse("What worked" in level.text, level.text)
        // A paisa apart is level (figures are said to the rupee).
        val near = review(listOf(p("Alpha", 0, 100.2), p("Beta", 1, 99.9)))
        assertTrue("Both arms ended the same" in near.text, near.text)
        // A trade of -₹0.40 is no loss and no win.
        val flat = review(listOf(p("Alpha", 0, -0.4)))
        assertTrue("Paper: 1 trade, 0 won, net level after ₹40 charges." in flat.text, flat.text)
        assertTrue("No single paper trade lost money." in flat.text, flat.text)
    }

    @Test fun oneArmIsSaidOnce() {
        val s = review(listOf(p("Hero (expiry)", 0, 300.0), p("Hero (expiry)", 1, -100.0)))
        assertTrue("All of it was Hero (expiry)." in s.text, s.text)
        assertFalse("What worked" in s.text || "Also:" in s.text, s.text)
    }

    @Test fun veryLongArmNamesAreCutAtAWord() {
        val long = "Liquidity Bank Nifty fifteen minute opening range breakout with a very long name that goes on. And on!"
        assertEquals("Liquidity Bank Nifty fifteen", SundayReview.spoken(long))
        assertTrue(SundayReview.spoken(long).length <= SundayReview.NAME_CHARS)
        assertEquals("Supercalifragilisticexpialidocio", SundayReview.spoken("Supercalifragilisticexpialidociousandmore arm"))
        assertEquals("Unnamed arm", SundayReview.spoken("   "))
        assertEquals("ORB 2.0 v1", SundayReview.spoken("ORB 2.0 v1."))
        val s = review(listOf(p(long, 0, 500.0), p("x".repeat(400), 1, -200.0)))
        assertTrue("What worked: Liquidity Bank Nifty fifteen, up ₹500" in s.text, s.text)
        assertFalse("x".repeat(SundayReview.NAME_CHARS + 1) in s.text)
        assertTrue(s.words <= SundayReview.MAX_WORDS)
        // A name's own full stop never ends a sentence early (every sentence is said, none cut).
        assertFalse("goes on." in s.text)
    }

    // ---- two minutes at most ----

    private fun busy(arms: Int, words: Int = 4): List<SundayReview.Trade> = (0 until arms).flatMap { i ->
        val name = (0 until words).joinToString(" ") { w -> "Arm$i word$w" }
        listOf(p(name, i % 5, 100.0 * (i + 1)), p(name, (i + 1) % 5, -37.0 * i)) +
            listOf(l(name, i % 5, -50.0 * i - 10.0), l(name, (i + 2) % 5, 75.0 * i))
    }

    @Test fun neverPastTheWordLimitAndTheMiddleArmsGoFirst() {
        val s = review(busy(30).filter { it.book == Book.PAPER }, busy(30).filter { it.book == Book.LIVE })
        assertTrue(s.words <= SundayReview.MAX_WORDS, "${s.words} words: ${s.text}")
        assertEquals(SundayReview.words(s.text), s.words)
        // The must-say parts stayed: both books, each one's best, worst and biggest cost, the one change and the chat.
        assertTrue("Paper: 60 trades" in s.text && "Live: 60 trades" in s.text, s.text)
        assertTrue(s.text.contains("Biggest single cost") && s.text.contains("One thing to look at") && s.text.endsWith("The full figures are in the chat."), s.text)
        assertFalse("Also:" in s.text, s.text)
        // A small week keeps its middle arms.
        val small = review(busy(3).filter { it.book == Book.PAPER }, emptyList())
        assertTrue("Also:" in small.text, small.text)
        // As said aloud (each rupee figure read as rupees), still about two minutes: at most 300 words.
        val aloud = Aloud.say(s.text, SundayReview.sentences(s.text))
        assertTrue(SundayReview.words(aloud) <= 300, "${SundayReview.words(aloud)} spoken words: $aloud")
        assertFalse("The rest is in the chat" in aloud, aloud)
        assertTrue("rupees" in aloud, aloud)
    }

    @Test fun theHardCapHoldsWhateverIsSaid() {
        // A worst case: both books unreadable is short; both full with every sentence long - the cut still ends cleanly.
        val many = (0 until 200).map { i -> p("Arm $i", i % 5, if (i % 2 == 0) 1_234_567.0 + i else -2_345_678.0 - i, 99_999.0) }
        val s = review(many, many.map { it.copy(book = Book.LIVE) })
        assertTrue(s.words <= SundayReview.MAX_WORDS, "${s.words}")
        assertTrue(s.text.endsWith("."), s.text)
    }

    @Test fun wordsAndSentencesAreCountedAsSaid() {
        assertEquals(0, SundayReview.words("   "))
        assertEquals(3, SundayReview.words("net up ₹3,200"))
        assertEquals(2, SundayReview.words("one - two"))
        assertEquals(2, SundayReview.sentences("Paper: 2 trades, net up ₹1,050.25. Live: none."))
    }

    @Test fun theLockedNoticeHoldsNoFigure() {
        assertFalse(Overheard.holdsAccount(SundayReview.READY_TITLE + ". " + SundayReview.READY_TEXT))
        assertFalse(Regex("\\d|₹|Rs").containsMatchIn(SundayReview.READY_TITLE + SundayReview.READY_TEXT))
        // The review itself does hold the account (so a locked phone never says it).
        assertTrue(Overheard.holdsAccount(review(listOf(p("Hero (expiry)", 0, 500.0))).text))
    }

    // ---- the question ----

    @Test fun theSpokenReviewIsAskedInPlainWords() {
        for (q in listOf("Sunday review", "jarvis, sunday summary please", "read me the weekly review", "say the week's review aloud",
                "weekly review aloud", "speak the sunday review", "weekly review sunao"))
            assertTrue(SundayReview.asked(q), q)
        // 20 Oct, review: Boss's own week, an index's, a strategy's or the bots' record keep their own answers.
        for (q in listOf("weekly review", "how did this week go", "what is the review of sunday's news", "review my trades", "sunday",
                "read me my weekly review", "sunday report on banknifty", "read the weekly review for liquidity", "sunday summary of the arms"))
            assertFalse(SundayReview.asked(q), q)
        // The written weekly review's own question is not taken from it.
        assertTrue(WeeklyReview.asked("weekly review") != null)
    }

    // ---- 21 Oct, enhancement round 3 (idea 7): the catch-up ----

    @Test fun theCatchUpIsDueOnASundayEveningFrom18UntilMidnightOnce() {
        val sun = LocalDate.of(2026, 10, 25)
        assertTrue(SundayReview.catchUpDue(sun.atTime(18, 0), null))
        assertTrue(SundayReview.catchUpDue(sun.atTime(21, 30), "2026-10-18"), "said last Sunday: due again this one")
        assertTrue(SundayReview.catchUpDue(sun.atTime(23, 59), null))
        assertFalse(SundayReview.catchUpDue(sun.atTime(17, 59), null), "before the review's hour")
        assertFalse(SundayReview.catchUpDue(sun.atTime(19, 0), sun.toString()), "said already tonight")
        assertFalse(SundayReview.catchUpDue(sun.plusDays(1).atTime(0, 5), null), "Monday: missed for good, never said late")
        assertFalse(SundayReview.catchUpDue(sun.minusDays(1).atTime(19, 0), null), "Saturday")
        assertFalse(SundayReview.catchUpDue(LocalDate.of(2026, 11, 10).atTime(19, 0), null), "a holiday weekday is not Sunday")
    }
}
