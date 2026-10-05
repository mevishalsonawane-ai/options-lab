package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChargesTest {
    private val today = LocalDate.of(2026, 10, 8)            // a Thursday
    private fun oct(d: Int) = LocalDate.of(2026, 10, d)

    /** A trade opened on [date] at 10:00, held [held] minutes. */
    private fun t(date: LocalDate, held: Long, gross: Double, charges: Double, owner: String = "Manual") =
        LocalDateTime.of(date, LocalTime.of(10, 0)).let { Charges.Trip(it, it.plusMinutes(held), gross, charges, owner) }

    @Test fun asked() {
        for (q in listOf("how much did I pay in charges this week", "Jarvis, how much did I pay in brokerage this month?", "my charges",
                "what did charges cost me this month", "brokerage this week", "total charges last month", "which trades cost me most in charges",
                "how much have I spent on brokerage", "charges kitna laga", "how much did charges eat into my profit", "my brokerage today"))
            assertTrue(Charges.asked(q), q)
        for (q in listOf("what is brokerage", "charges for one lot of Nifty at 120", "what are the charges per order", "how was my month",
                "how much did I make this week", "open the cost calculator", "what is my p&l", "Nifty charges ahead"))
            assertFalse(Charges.asked(q), q)
    }

    @Test fun spans() {
        assertEquals(Charges.Span.WEEK, Charges.span("how much did I pay in charges this week"))
        assertEquals(Charges.Span.LAST_WEEK, Charges.span("my charges last week"))
        assertEquals(Charges.Span.LAST_MONTH, Charges.span("total charges last month"))
        assertEquals(Charges.Span.TODAY, Charges.span("my brokerage today"))
        assertEquals(Charges.Span.MONTH, Charges.span("my charges"))
        assertEquals(oct(5) to today, Charges.range(Charges.Span.WEEK, today))
        assertEquals(LocalDate.of(2026, 9, 28) to oct(4), Charges.range(Charges.Span.LAST_WEEK, today))
        assertEquals(LocalDate.of(2026, 9, 1) to LocalDate.of(2026, 9, 30), Charges.range(Charges.Span.LAST_MONTH, today))
        assertEquals(oct(1) to today, Charges.range(Charges.Span.MONTH, today))
        assertEquals(YearMonth.of(2026, 9), Charges.month(Charges.Span.LAST_MONTH, today))
        assertNull(Charges.month(Charges.Span.WEEK, today))
    }

    @Test fun routedAsAnAccountQuestion() {
        assertEquals(setOf(Topic.ACCOUNT), Ask.parse("Jarvis, how much did I pay in charges this week?").topics)
        assertNull(Ask.parse("how much did I pay in brokerage this month").order)
        assertEquals(setOf(Section.CHARGES), AppAnswers.sections("how much did I pay in charges this week"))
        assertEquals(setOf(Section.CHARGES), AppAnswers.sections("which trades cost me most in charges"))
        // An account answer: not read out on a locked phone to anyone but Boss's voice.
        assertEquals("Unlock the phone to hear your account.", LockRule.refuse(locked = true, acts = false, account = true, boss = false))
    }

    @Test fun kindsAndSmallTrades() {
        assertEquals(Charges.Kind.QUICK, Charges.kind(t(today, 3, 0.0, 0.0)))
        assertEquals(Charges.Kind.SHORT, Charges.kind(t(today, 10, 0.0, 0.0)))
        assertEquals(Charges.Kind.LONG, Charges.kind(t(today, 90, 0.0, 0.0)))
        val overnight = LocalDateTime.of(oct(6), LocalTime.of(15, 0)).let { Charges.Trip(it, it.plusHours(19), 0.0, 0.0, "Manual") }
        assertEquals(Charges.Kind.OVERNIGHT, Charges.kind(overnight))
        assertTrue(Charges.small(t(today, 3, 100.0, 60.0)))       // moved 100 against 60 in charges
        assertTrue(Charges.small(t(today, 3, -80.0, 60.0)))
        assertFalse(Charges.small(t(today, 3, 2_000.0, 60.0)))
        assertFalse(Charges.small(t(today, 3, 0.0, 0.0)))
    }

    @Test fun theWeeksCharges() {
        val trips = listOf(
            // Six quick scalps: small moves, Rs 60 each in charges.
            t(oct(5), 2, 100.0, 60.0), t(oct(5), 3, 80.0, 60.0), t(oct(6), 1, -50.0, 60.0),
            t(oct(6), 4, 110.0, 60.0), t(oct(7), 2, 90.0, 60.0), t(oct(7), 3, 70.0, 60.0),
            // Two longer trades, placed by the ORB arm.
            t(oct(6), 60, 2_000.0, 70.0, "ORB"), t(oct(7), 45, 1_000.0, 70.0, "ORB"),
            t(LocalDate.of(2026, 10, 2), 2, 500.0, 60.0),           // last week: left out
        )
        val lines = Charges.lines("Paper", trips, Charges.Span.WEEK, today)
        val all = lines.joinToString("\n")
        assertTrue(lines[0].startsWith("Paper charges this week (5 Oct to 8 Oct): Rs 500 on 8 closed trades, about Rs 63 a trade."), lines[0])
        // Gross 3,400; charges 500 = 15%.
        assertTrue(lines[1].contains("+Rs 3,400 before charges, so the charges took 15% of it"), lines[1])
        assertTrue(lines[1].contains("+Rs 2,900 after charges"), lines[1])
        // The scalps: gross 400, charges 360 = 90%.
        assertTrue(all.contains("Paper quick trades (held under 5 minutes): 6 trades, Rs 360 in charges (Rs 60 a trade); +Rs 400 before charges, so the charges took 90% of it."), all)
        assertTrue(all.contains("Paper small trades (moved less than twice their own charges): 6 of 8, Rs 360 in charges (72% of all)"), all)
        assertTrue(all.contains("Paper charges by who placed the trades: Manual Rs 360 (6 trades), ORB Rs 140 (2 trades)."), all)
        assertTrue(all.contains("Paper cost most in charges: quick trades (held under 5 minutes) - Rs 360 of the Rs 500 (72%) on 6 of 8 trades."), all)
        assertFalse(all.contains("buy", ignoreCase = true) || all.contains("should", ignoreCase = true), all)
    }

    @Test fun chargesThatTurnAProfitToALossAndTheMonthByKind() {
        val trips = listOf(t(oct(1), 2, 100.0, 60.0), t(oct(2), 2, 40.0, 60.0), t(oct(2), 3, -30.0, 60.0))
        val lines = Charges.lines("Zerodha", trips, Charges.Span.MONTH, today,
            mapOf("Brokerage" to 120.0, "STT" to 30.4, "SEBI" to 0.01, "GST" to 25.0), estimated = true)
        val all = lines.joinToString("\n")
        assertTrue(lines[0].endsWith("(the app's estimate of Zerodha's charges)."), lines[0])
        assertTrue(lines[1].contains("+Rs 110 before charges, -Rs 70 after: the charges turned it to a loss"), lines[1])
        assertTrue(all.contains("Zerodha: every trade moved less than twice its own charges."), all)
        assertTrue(all.contains("Zerodha every fill in October, by kind of charge: Brokerage Rs 120, STT Rs 30, GST Rs 25."), all)
        assertFalse(all.contains("SEBI"), all)
        // A loss before charges: they add to it.
        val loss = Charges.lines("Paper", listOf(t(oct(6), 40, -500.0, 70.0)), Charges.Span.WEEK, today)
        assertTrue(loss[1].contains("-Rs 500 before charges, so the charges added Rs 70 to the loss"), loss[1])
    }

    @Test fun noTradesNoCharges() {
        assertEquals(listOf("Paper: no closed trades today (8 Oct), so no charges on them."), Charges.lines("Paper", emptyList(), Charges.Span.TODAY, today))
    }

    @Test fun theAnswerIsAccountOnALockedPhone() {
        // Every line with an amount: the locked phone's net catches each one.
        val lines = Charges.lines("Paper", listOf(t(oct(6), 3, 100.0, 60.0), t(oct(6), 60, 900.0, 70.0)), Charges.Span.WEEK, today)
        lines.filter { it.contains("Rs ") }.also { assertTrue(it.size >= 4) }.forEach { assertTrue(Overheard.holdsAccount(it), it) }
    }
}
