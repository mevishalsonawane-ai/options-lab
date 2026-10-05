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
        // "aaj ke charges" is read as "today charges" (Ask.reading) - still the charges route.
        for (q in listOf("aaj ke charges", "aaj ka brokerage", "aaj ki fees", "today's charges")) {
            assertTrue(Charges.asked(q), q)
            assertEquals(setOf(Section.CHARGES), AppAnswers.sections(q), q)
        }
        assertEquals(Charges.Span.TODAY, Charges.span("aaj ke charges"))
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

    // ---- Round 34: "why are my charges so high?" ----

    private val whyAsks = listOf("why are my charges so high", "Jarvis, why are my charges so high?", "charges itne zyada kyun",
        "charges itne zyada kyun hai", "what is eating my charges", "why is my brokerage so high today", "itne zyada charges kyun",
        "why so much brokerage", "why am I paying so much in charges", "where are my charges going", "what is driving my charges",
        "kyun itne zyada charges lag rahe", "my charges are too high", "why were my charges so high this week", "charges kyu itne",
        "why is the brokerage so much", "what makes my charges so high", "my charges breakdown", "why did I pay so much brokerage today")

    @Test fun whyAsked() {
        for (q in whyAsks) {
            assertTrue(Charges.whyAsked(q), q)
            assertTrue(Charges.asked(q), q)
        }
        for (q in listOf("what is brokerage", "why is nifty so high", "charges for one lot", "why did my last trade lose",
                "how much did I pay in charges this week", "my charges", "why are you so quiet", "what are the charges per order",
                "why so high nifty", "what is eating my profit", "why is vix so high"))
            assertFalse(Charges.whyAsked(q), q)
        assertNull(Charges.whySpan("why are my charges so high"))
        assertEquals(Charges.Span.WEEK, Charges.whySpan("why were my charges so high this week"))
        assertEquals(Charges.Span.TODAY, Charges.whySpan("why is my brokerage so high today"))
        assertEquals(Charges.Span.LAST_MONTH, Charges.whySpan("why were charges so much last month"))
        // span() keeps its month when none is said.
        assertEquals(Charges.Span.MONTH, Charges.span("why are my charges so high"))
    }

    @Test fun whyIsRoutedToTheChargesAnswerAndNeverActs() {
        for (q in whyAsks) {
            val p = Ask.parse(q)
            assertNull(p.order, q); assertNull(p.command, q)
            assertTrue(Topic.ACCOUNT in p.topics, "$q: ${p.topics}")
            assertEquals(setOf(Section.CHARGES), AppAnswers.sections(q), q)
        }
    }

    @Test fun owners() {
        assertEquals("ORB", Charges.owner("ORB · entry"))
        assertEquals("Pine Breakout", Charges.owner("Strategy: Pine Breakout · entry"))
        assertEquals("Manual", Charges.owner(null))
        assertEquals("Manual", Charges.owner("  "))
        assertEquals(ArmOwners.LIQUIDITY, Charges.owner("Liquidity 5m FINNIFTY"))
    }

    private fun at(d: LocalDate, h: Int, m: Int) = LocalDateTime.of(d, LocalTime.of(h, m))
    private fun leg(d: LocalDate, h: Int, m: Int, id: String, owner: String, side: String, price: Double, qty: Int) =
        Charges.Leg(at(d, h, m), id, owner, side, price, qty)

    /** Today: 7 orders in 8 fills (ORB's first order filled in two pieces), three round trips closed. */
    private val day = listOf(
        leg(today, 9, 20, "m1", "Manual", "BUY", 100.0, 75), leg(today, 9, 40, "m2", "Manual", "SELL", 110.0, 75),
        leg(today, 9, 30, "o1", "ORB", "BUY", 200.0, 75), leg(today, 9, 30, "o1", "ORB", "BUY", 200.0, 75),
        leg(today, 10, 5, "o2", "ORB", "SELL", 210.0, 150), leg(today, 11, 0, "o3", "ORB", "BUY", 50.0, 75),
        leg(today, 11, 20, "o4", "ORB", "SELL", 40.0, 75), leg(today, 12, 0, "j1", "Jarvis", "BUY", 120.0, 75),
        leg(oct(7), 10, 0, "y1", "Manual", "BUY", 90.0, 75),     // yesterday: left out
    )
    private val dayTrips = listOf(t(today, 20, 750.0, 50.0), t(today, 35, 1_500.0, 120.0, "ORB"), t(today, 20, -750.0, 50.0, "ORB"))

    @Test fun whyTheDaysChargesAreWhatTheyAre() {
        val w = day.filter { it.at.toLocalDate() == today }
        assertEquals(7, Charges.orders(w))
        val split = Charges.split(w)
        // Brokerage Rs 20 once per ORDER (7 orders, not 8 fills); STT 0.15% of Rs 42,750 of sells; exchange (with SEBI) on
        // Rs 93,000 turnover; GST 18% of brokerage + exchange; stamp 0.003% of Rs 50,250 of buys.
        assertEquals(140.0, split.getValue("Brokerage"), 1e-9)
        assertEquals(64.125, split.getValue("STT"), 1e-9)
        assertEquals(93_000 * 0.0003553 + 93_000 * 1e-6, split.getValue("Exchange"), 1e-6)
        assertEquals((140 + 93_000 * 0.0003553 + 93_000 * 1e-6) * 0.18, split.getValue("GST"), 1e-6)
        assertEquals(50_250 * 0.00003, split.getValue("Stamp duty"), 1e-9)
        assertEquals(PnlCharges.estimate(w.map { PnlCharges.Fill(it.side, it.price, it.qty, it.orderId) }), split.values.sum(), 0.01)

        val who = Charges.sources(w)
        assertEquals(listOf("ORB" to 4, "Manual" to 2, "Jarvis" to 1), who.map { it.name to it.orders })
        assertEquals(5, who.first().fills)
        assertEquals(split.values.sum(), who.sumOf { it.charges }, 1e-6)

        val lines = Charges.whyLines("Zerodha", day, dayTrips, null, today, estimated = true)
        val all = lines.joinToString("\n")
        assertEquals("Zerodha charges today (8 Oct): Rs 270 (the app's estimate), mostly brokerage on 7 orders (Rs 165 with GST); ORB placed 4 of the 7 orders.", lines[0])
        assertTrue(all.contains("Zerodha today (8 Oct): 7 orders, 8 fills (an order filled in pieces pays its Rs 20 brokerage once), 3 round trips closed."), all)
        assertTrue(all.contains("Zerodha split: brokerage Rs 140, STT Rs 64, exchange Rs 33, GST Rs 31, stamp Rs 2; Rs 270 in all."), all)
        assertTrue(all.contains("Zerodha about Rs 90 in charges a round trip, 2.3 orders a round trip"), all)
        assertTrue(all.contains("Zerodha orders by who placed them: ORB 4 orders (Rs "), all)
        assertTrue(all.contains("Manual 2 orders (Rs ") && all.contains("Jarvis 1 order (Rs "), all)
        assertTrue(all.contains("Zerodha most orders: ORB, 4 of 7 (57%)"), all)
        assertFalse(all.contains("contract note"), all)
        // Facts only: nothing tells Boss what to trade.
        for (word in listOf("should", "buy", "sell", "trade less", "reduce", "stop")) assertFalse(all.contains(word, ignoreCase = true), "$word: $all")
        // The short answer is the driver's one sentence.
        val short = ShortAnswer.of("why are my charges so high", all)
        assertTrue(short.line.contains("Rs 270") && short.line.contains("brokerage"), short.line)
        // Every line with an amount is the account's: never read out on a locked phone.
        lines.filter { it.contains("Rs ") }.forEach { assertTrue(Overheard.holdsAccount(it), it) }
    }

    @Test fun zerodhasContractNoteAndPaperKeptApart() {
        val z = Charges.whyLines("Zerodha", day, dayTrips, null, today, estimated = true, exact = 251.4)
        assertTrue(z.last() == "Zerodha: Zerodha's own contract note for 8 Oct says Rs 251.", z.last())
        val p = Charges.whyLines("Paper", day, dayTrips, null, today)
        assertTrue(p.all { it.startsWith("Paper") }, p.joinToString("\n"))
        assertFalse(p[0].contains("estimate"), p[0])
        // The contract note is the day's: not said for a week.
        assertFalse(Charges.whyLines("Zerodha", day, dayTrips, Charges.Span.WEEK, today, estimated = true, exact = 251.4).joinToString().contains("contract note"))
    }

    @Test fun whyOverAWeekTheLastDayAndNone() {
        val week = Charges.whyLines("Paper", day, dayTrips, Charges.Span.WEEK, today)
        assertTrue(week[0].startsWith("Paper charges this week (5 Oct to 8 Oct): Rs "), week[0])
        assertTrue(week[1].contains("8 orders, 9 fills"), week[1])
        // Nothing today: the last day with fills, said so.
        val earlier = Charges.whyLines("Paper", day.filter { it.at.toLocalDate() != today }, emptyList(), null, today)
        assertTrue(earlier[0].startsWith("Paper charges on 7 Oct, the last day with fills: Rs "), earlier[0])
        assertTrue(earlier[0].endsWith("all placed by Manual."), earlier[0])
        assertTrue(earlier.none { it.contains("a round trip") }, earlier.joinToString("\n"))
        assertEquals(listOf("Paper: no fills today (8 Oct), so no charges."), Charges.whyLines("Paper", emptyList(), emptyList(), null, today))
    }

    @Test fun sttCanBeTheDriver() {
        // Selling a big premium: the STT on the sells outweighs two orders' brokerage.
        val legs = listOf(leg(today, 9, 30, "s1", "Pine Breakout", "SELL", 300.0, 1_500), leg(today, 14, 0, "s2", "Pine Breakout", "BUY", 280.0, 1_500))
        val lines = Charges.whyLines("Paper", legs, listOf(t(today, 270, 30_000.0, 800.0, "Pine Breakout")), null, today)
        assertTrue(lines[0].contains("mostly STT on Rs 450,000 of sells (Rs 675)"), lines[0])
        assertTrue(lines[0].endsWith("all placed by Pine Breakout."), lines[0])
    }

    @Test fun theAnswerIsAccountOnALockedPhone() {
        // Every line with an amount: the locked phone's net catches each one.
        val lines = Charges.lines("Paper", listOf(t(oct(6), 3, 100.0, 60.0), t(oct(6), 60, 900.0, 70.0)), Charges.Span.WEEK, today)
        lines.filter { it.contains("Rs ") }.also { assertTrue(it.size >= 4) }.forEach { assertTrue(Overheard.holdsAccount(it), it) }
    }
}
