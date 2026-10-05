package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BookDecayTest {
    private val fri = LocalDate.of(2026, 10, 9)
    private val friNoon = fri.atTime(12, 0)
    private val nextExpiry = LocalDate.of(2026, 10, 13)

    private fun opt(where: String, sym: String, qty: Int, theta: Double?, expiry: LocalDate = nextExpiry, right: String = "PE",
                    strike: Double = 25_000.0, spot: Double? = 25_100.0, ltp: Double = 80.0) =
        PositionHealth.Pos(where, sym, qty, 90.0, ltp, "NIFTY", strike, right, expiry, spot = spot, theta = theta)

    @Test fun asksAreRead() {
        for (s in listOf("what's my theta?", "what is my theta", "my net theta", "how much am I losing to time decay?",
            "how much do I pay in theta a day", "theta on my positions", "time decay on my book", "is theta working for me or against me",
            "am I paying theta", "am i long or short theta", "how much decay over the weekend on my positions",
            "how much will my positions decay over the weekend", "my positions' theta", "mera theta kitna hai",
            "meri positions ka theta kitna hai", "time decay se kitna nuksan ho raha hai", "decay kitna kha raha hai",
            "how much theta am i collecting", "weekend theta on my book", "what's my total time decay"))
            assertTrue(BookDecay.asked(s), s)
        for (s in listOf("what is theta", "what does theta mean", "explain time decay", "theta kya hota hai", "am i net long or short",
            "should I sell options for theta", "what if I sell a straddle for theta", "check my positions", "what's my p&l",
            "how is my put doing", "what was my theta yesterday", "what's your theta", "buy nifty 25000 put", "how much can I lose today",
            "what's nifty's iv", "which way am i leaning"))
            assertFalse(BookDecay.asked(s), s)
    }

    @Test fun eachAccountApartWithTheSplitAndTheWeekend() {
        val ps = listOf(
            opt("Zerodha", "NIFTY13OCT2625000PE", 75, -4.0),      // -300 a day
            opt("Zerodha", "NIFTY13OCT2624500PE", -150, -3.0),    // +450 a day
            opt("Paper", "BANKNIFTY13OCT2654000CE", 35, -10.0),   // -350 a day
        )
        assertEquals(-300.0, BookDecay.perDay(ps[0])!!, 1e-9)
        assertEquals(450.0, BookDecay.perDay(ps[1])!!, 1e-9)
        val a = BookDecay.answer(ps, friNoon, LocalDate.of(2026, 10, 12), zerodhaLoggedIn = true)
        assertTrue("On Zerodha your options gain about Rs 150 a day from time decay (the bought legs pay Rs 300, the sold legs collect Rs 450)." in a, a)
        assertTrue("On Paper your options lose about Rs 350 a day to time decay." in a, a)
        assertTrue(a.indexOf("On Zerodha") < a.indexOf("On Paper"), a)
        assertTrue("Weighing most: Zerodha NIFTY13OCT2624500PE short 150, +Rs 450 a day; Paper BANKNIFTY13OCT2654000CE long 35, -Rs 350 a day" in a, a)
        assertTrue("Till the next session on Mon 12 Oct (3 calendar days), at today's theta that is about +Rs 450 on Zerodha and -Rs 1,050 on Paper" in a, a)
        assertTrue("Nothing was changed." in a && "not logged in" !in a, a)
    }

    @Test fun anOrdinaryNightHasNoWeekendLine() {
        val a = BookDecay.answer(listOf(opt("Paper", "X", 75, -4.0)), LocalDate.of(2026, 10, 7).atTime(11, 0), LocalDate.of(2026, 10, 8), zerodhaLoggedIn = false)
        assertTrue("On Paper your options lose about Rs 300 a day to time decay." in a, a)
        assertTrue("Till the next session" !in a && "Weighing most" !in a, a)
        assertTrue(a.endsWith("Zerodha is not logged in today, so only the paper account was read."), a)
    }

    @Test fun expiringTodayIsItsTimeValueLeft() {
        // A 25000 call with spot 25100: 100 intrinsic, price 130 - 30 of time value on 75 = Rs 2,250, the seller's to keep.
        val ps = listOf(opt("Zerodha", "NIFTY09OCT2625000CE", -75, -40.0, expiry = fri, right = "CE", ltp = 130.0),
            opt("Paper", "NIFTY09OCT2625200PE", 75, -9.0, expiry = fri, right = "PE", strike = 25_200.0, spot = null, ltp = 120.0))
        assertEquals(2_250.0, BookDecay.timeValue(ps[0])!!, 1e-9)
        val a = BookDecay.answer(ps, friNoon, LocalDate.of(2026, 10, 12), zerodhaLoggedIn = true)
        assertTrue("Zerodha NIFTY09OCT2625000CE short 75 expires today with about Rs 2,250 of time value left on the position - gone by 15:30 (yours to keep as the seller)." in a, a)
        assertTrue("Paper NIFTY09OCT2625200PE long 75 expires today: whatever time value is left in it goes by 15:30 (yours to lose); its index price is not known just now" in a, a)
        // Not counted a day at a time, nor carried over the weekend.
        assertTrue("a day to time decay" !in a && "Till the next session" !in a, a)
        // After the close the expired legs are not counted.
        val late = BookDecay.answer(ps, fri.atTime(15, 45), LocalDate.of(2026, 10, 12), zerodhaLoggedIn = true)
        assertTrue("no time decay is left to count" in late, late)
    }

    @Test fun nothingOpenOrNoOptionsOrUnread() {
        assertEquals("You have no open option positions, Boss - nothing in your book is decaying.",
            BookDecay.answer(emptyList(), friNoon, null, zerodhaLoggedIn = true))
        val fut = PositionHealth.Pos("Zerodha", "NIFTY26OCTFUT", 75, 25_000.0, 25_100.0, "NIFTY")
        assertEquals("Your open position is not an option, Boss - it has no time decay.", BookDecay.answer(listOf(fut), friNoon, null, true))
        val a = BookDecay.answer(listOf(opt("Paper", "A", 75, -4.0), opt("Paper", "B", 75, null), fut), LocalDate.of(2026, 10, 7).atTime(11, 0), null, true)
        assertTrue("One option leg's theta could not be worked out just now" in a, a)
        assertTrue("One position is not an option and has no time decay." in a, a)
    }

    @Test fun aFailedZerodhaReadIsNeverNoPositions() {
        // Review, 5 Oct: logged in but the read failed or timed out - never "nothing in your book is decaying".
        val none = BookDecay.answer(emptyList(), friNoon, null, SinceMorning.Zerodha.FAILED)
        assertTrue(none.startsWith("I could not read Zerodha just now, Boss"), none)
        assertTrue("nothing in your book is decaying" !in none && "Your paper account has no open option positions." in none, none)
        val a = BookDecay.answer(listOf(opt("Paper", "X", 75, -4.0)), LocalDate.of(2026, 10, 7).atTime(11, 0), null, SinceMorning.Zerodha.FAILED)
        assertTrue("On Paper your options lose about Rs 300 a day to time decay." in a, a)
        assertTrue("I could not read Zerodha just now, Boss" in a && "only the paper account is given" in a && "not logged in" !in a, a)
        // The old flag reads as before.
        assertEquals(BookDecay.answer(emptyList(), friNoon, null, true), BookDecay.answer(emptyList(), friNoon, null, SinceMorning.Zerodha.READ))
        assertEquals(BookDecay.answer(emptyList(), friNoon, null, false), BookDecay.answer(emptyList(), friNoon, null, SinceMorning.Zerodha.LOGGED_OUT))
    }
}
