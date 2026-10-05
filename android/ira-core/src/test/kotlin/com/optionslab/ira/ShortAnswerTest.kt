package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShortAnswerTest {
    private fun line(q: String?, a: String) = ShortAnswer.of(q, a).line

    @Test fun pnlOneAccount() {
        val a = AppFacts.pnl("Paper", 2575.4, 1000.0, 1575.4) + " Paper: 2 open positions."
        assertEquals("Your paper P&L today is +Rs 2,575.", line("what's the P&L today", a))
        assertEquals("Your Zerodha P&L today is a loss of Rs 1,200.", line("what is my pnl", AppFacts.pnl("Zerodha", -1200.0, -1200.0, 0.0) + " More text here."))
    }

    @Test fun pnlBothAccountsInOneSentence() {
        val a = AppFacts.pnl("Zerodha", 1234.0, 1234.0, 0.0) + " " + AppFacts.pnl("Paper", -500.0, -500.0, 0.0)
        assertEquals("Zerodha +Rs 1,234, paper -Rs 500 today.", line("p&l today?", a))
    }

    @Test fun pnlKeepsZerodhaUnread() {
        val a = AppFacts.pnl("Paper", 2575.0, 2575.0, 0.0) + " Zerodha did not answer just now, so its orders are not included."
        assertEquals("Your paper P&L today is +Rs 2,575. Zerodha could not be read just now.", line("how much did I make today", a))
    }

    @Test fun positionsOrdersFunds() {
        val pos = (AppFacts.positions("Zerodha", listOf(AppFacts.Held("NIFTY24500CE", 75, 100.0, 120.0, 1500.0), AppFacts.Held("NIFTY24400PE", 75, 90.0, 80.0, -750.0))) +
            AppFacts.positions("Paper", emptyList())).joinToString(" ")
        assertEquals("Positions: Zerodha 2 open, paper none open.", line("my positions", pos))
        val ord = AppFacts.orders("Paper", listOf(AppFacts.OrderLine("09:20", "NIFTY24500CE", "BUY", 75, "COMPLETE", 100.0, "Manual"),
            AppFacts.OrderLine("09:40", "NIFTY24500CE", "SELL", 75, "OPEN", 0.0, "Manual")), byWho = true).joinToString(" ")
        assertEquals("Orders: paper 2 today (1 filled, 1 open, 0 rejected or cancelled).", line("show my orders", ord))
        val funds = "Paper funds: Rs 5,00,000.00 available, Rs 20,000.00 in use. Zerodha funds: Rs 1,23,456.70 available, Rs 0.00 used, net Rs 1,23,456.70."
        assertEquals("Available: paper Rs 5,00,000, Zerodha Rs 1,23,457.", line("how much margin do I have", funds))
    }

    @Test fun marketLevelKeepsAsOf() {
        val a = "Nifty is at 22,555.30 (+0.62% on the day) as of 14:05, in a range of 22,401.10 to 22,580.00 today. The 15-minute trend is up. Support is 22,400."
        assertEquals("Nifty is at 22,555, up 0.62% as of 14:05.", line("where is nifty", a))
        val v = "India VIX is at 13.42 (-2.1% on the day) as of 14:05, in a range of 13.1 to 13.9 today. Volatility is calm."
        assertEquals("India VIX is at 13.42, down 2.1% as of 14:05.", line("what's vix", v))
    }

    @Test fun thetaTradesLeftLastTradeBots() {
        assertEquals("Your book loses about Rs 640 a day to time decay (theta).",
            line("what's my theta", "Here is your book. Your book loses about Rs 640 a day to time decay (theta). The 24500 call loses most. It speeds up near expiry."))
        assertEquals("You have 3 trades left today under the guard.",
            line("how many trades left", "On Zerodha you may take 5 trades a day. You have 3 trades left today under the guard. Your loss room is wide."))
        assertEquals("Your last trade: BUY 75 NIFTY24500CE at 100.00, 09:20.",
            line("what was my last trade", "You traded twice today. Your last trade: BUY 75 NIFTY24500CE at 100.00, 09:20. Before it you sold one."))
        val arms = AppFacts.arms(listOf(AppFacts.ArmLine("ORB 15", "ORB", true, "BankNifty", 300.0, null), AppFacts.ArmLine("Trend", "Pine", false, "Nifty", null, null)), rank = false)
        assertEquals("1 of 2 strategies and arms are switched on.", line("are my bots running", arms.joinToString(" ")))
    }

    @Test fun warningsAndStalenessSurvive() {
        val a = "Nifty is at 22,555.30 (+0.62% on the day) as of 3 Oct 15:30, in a range of 22,401 to 22,580 today. The market is closed; these are the last prices. " +
            "The trend is up. Careful today: you are near the daily loss limit."
        val l = line("where is nifty", a)
        assertTrue(l.contains("as of 3 Oct 15:30"), l)
        assertTrue(l.contains("market is closed"), l)
        assertTrue(l.contains("daily loss limit"), l)
        assertFalse(l.contains("trend is up"), l)
        val locked = "Your positions are ready. Unlock the phone for the figures, Boss. There are details you can see then."
        assertTrue(line(null, locked).contains("Unlock the phone"))
        val gold = "Gold is at 71,250.00 as of 14:00. GOLD trades here are paper only. The trend is flat. More words here."
        assertTrue(line("gold price", gold).contains("paper only"))
    }

    @Test fun questionsConfirmsAndMoreStayWhole() {
        val confirm = "I have put that order on the Ira screen. Nothing is sent until you confirm it there. Check the lots."
        assertEquals(confirm, line("buy 1 lot nifty 24500 ce", confirm))
        val ask = "BankNifty broke out on the news. Shall I buy 1 lot of the 52000 call? Yes or no?"
        assertEquals(ask, line(null, ask))
        val full = "Nifty is at 22,555. The trend is up. Support is 22,400."
        assertEquals(full, line("more", full))
        assertEquals(full, line("aur batao", full))
        assertEquals(full, ShortAnswer.of("where is nifty", full, short = false).line)
        assertNull(ShortAnswer.of("where is nifty", "Nifty is at 22,555.").details)
    }

    @Test fun detailsAndRestAreKept() {
        val a = "Nifty is at 22,555.30 (+0.62% on the day) as of 14:05, in a range of 22,401 to 22,580 today. The 15-minute trend is up. Support is 22,400."
        val s = ShortAnswer.of("nifty", a)
        assertEquals(a, s.details)
        assertEquals("The 15-minute trend is up. Support is 22,400.", s.rest)
    }

    // ---- review, 5 Oct ------------------------------------------------------------------------------------------

    /** The reviewer's three examples: a failure is never cut from the line. */
    @Test fun failuresAreNeverDropped() {
        val set = "NIFTY24500PE (Zerodha, 75) had no stop, so I set one at 102.00, 15% under the 120.00 you paid. "
        // 1. The guard's report with a failed protection.
        val g = line(null, set + "Not protected: Insufficient funds. Required margin is 1200.")
        assertTrue(g.contains("Not protected: Insufficient funds."), g)
        // 2. An order's result, asked or not.
        for (q in listOf("close all", null)) {
            val r = line(q, "Sent to Zerodha: BUY 1 lot NIFTY 24500 PE. REJECTED 0 at 0.00 (order 12345678)")
            assertTrue(r.contains("REJECTED"), r)
            val c = line(q, "Done: closed NIFTY24500PE. Zerodha refused the exit for BANKNIFTY52000CE: market closed.")
            assertTrue(c.contains("Zerodha refused the exit for BANKNIFTY52000CE"), c)
            val p = line(q, "Closed 2 of 3 positions. BANKNIFTY52000CE failed: Zerodha rejected the order.")
            assertTrue(p.contains("BANKNIFTY52000CE failed"), p)
        }
        // 3. A confirmed exit with a failed leg.
        for (r in listOf("Kill switch on. Stopped 2 strategies for today. Paper: SELL 75 NIFTY24500PE filled at 90.00. Failed: no live price for BANKNIFTY52000CE, try again.",
                "Paper: SELL 75 NIFTY24500PE filled at 90.00 (order 1234). Zerodha refused: RMS rule, order blocked for BANKNIFTY52000CE.")) {
            val l = line("yes", r)
            assertTrue(l.contains("Failed: no live price") || l.contains("Zerodha refused"), l)
        }
        for (s in listOf("Not protected: x.", "It failed.", "Zerodha refused it.", "The order was rejected.", "Not sent.", "Not placed.",
                "Kill switch not set: x.", "I could not cancel it.", "Insufficient funds."))
            assertTrue(ShortAnswer.safety(s), s)
        assertFalse(ShortAnswer.safety("Nifty is at 24,612."))
    }

    @Test fun negativeFundsAreRead() {
        val f = "Paper funds: Rs 4,00,000.00 available, Rs 0.00 in use. Zerodha funds: Rs -12,000.50 available, Rs 50,000.00 used, net Rs 38,000.00."
        assertEquals("Available: paper Rs 4,00,000, Zerodha Rs -12,001.", line("what are my funds", f))
        // A funds sentence the line cannot read: the fallback for the whole answer, never one account silently left out.
        val odd = "Paper funds: Rs 4,00,000.00 available, Rs 0.00 in use. Zerodha funds: not read just now."
        val l = line("what are my funds", odd)
        assertFalse(l.startsWith("Available: paper"), l)
        assertTrue(l.contains("Paper funds"), l)
    }

    @Test fun keptFiguresLeadStaysInFront() {
        val note = KeptFigures.note(120_000L, java.time.LocalTime.of(14, 7))!!
        val both = note + " " + AppFacts.pnl("Paper", 2575.4, 1000.0, 1575.4) + " " + AppFacts.pnl("Zerodha", -8200.0, -8000.0, -200.0)
        assertEquals("$note Paper +Rs 2,575, Zerodha -Rs 8,200 today.", line("what's my pnl", both))
        val pos = note + " Paper: 2 open positions. 1. Paper position NIFTY24500PE: 75 at 120.00, now 90.00, -Rs 2,250.00. Zerodha: 1 open position."
        assertEquals("$note Positions: paper 2 open, Zerodha 1 open.", line("my positions", pos))
        val slow = KeptFigures.note(10_000L, java.time.LocalTime.of(14, 7), slow = true)!!
        val funds = "$slow Paper funds: Rs 4,00,000.00 available, Rs 0.00 in use. Zerodha funds: Rs 50,000.00 available, Rs 0.00 used, net Rs 50,000.00."
        assertEquals("$slow Available: paper Rs 4,00,000, Zerodha Rs 50,000.", line("my funds", funds))
        assertEquals(both, ShortAnswer.of("what's my pnl", both).details)
    }

    @Test fun paperLabelInAnyCase() {
        val l = line(null, "Your stop on NIFTY24500PE is now 102.00, trailed up from 95.00 as the price rose to 140.00. Paper only - nothing at Zerodha changes.")
        assertTrue(l.contains("Paper only"), l)
        assertTrue(ShortAnswer.safety("PAPER MODE: nothing is sent."))
        // The metal's name is no label.
        assertFalse(ShortAnswer.safety("Gold is at 71,250."))
    }

    @Test fun listNumberStaysWithItsItem() {
        val a = "2 of 3 strategies and arms are switched on. 1. ORB 15 (ORB, stop 40): on today -Rs 1,200.00, 2 trades today. 2. Gap fade (Pine, x): on today +Rs 300.00."
        assertEquals(listOf("2 of 3 strategies and arms are switched on.", "1. ORB 15 (ORB, stop 40): on today -Rs 1,200.00, 2 trades today.",
            "2. Gap fade (Pine, x): on today +Rs 300.00."), ShortAnswer.sentences(a))
        // A list item is data: its "stop" is no warning, so it is not pulled into the line without its number.
        assertEquals("2 of 3 strategies and arms are switched on.", line("my strategies", a))
    }

    @Test fun kinds() {
        assertEquals(ShortAnswer.Kind.PNL, ShortAnswer.kind("what's the P&L today"))
        assertEquals(ShortAnswer.Kind.OTHER, ShortAnswer.kind("my p&l this week"))
        assertEquals(ShortAnswer.Kind.TRADES_LEFT, ShortAnswer.kind("how many more trades can I take"))
        assertEquals(ShortAnswer.Kind.WHOLE, ShortAnswer.kind("tell me more"))
        assertEquals(ShortAnswer.Kind.WHOLE, ShortAnswer.kind("explain in detail"))
        assertEquals(ShortAnswer.Kind.OTHER, ShortAnswer.kind(null))
    }

    @Test fun roundsKeepSignsAndGrouping() {
        assertEquals("1,23,457", ShortAnswer.whole("1,23,456.70"))
        assertEquals("24,612", ShortAnswer.whole("24,612.40"))
        assertEquals("-Rs 1,200 and 24,613", ShortAnswer.tidy("-Rs 1,200.00 and 24,612.50"))
        assertEquals("0.42%", ShortAnswer.tidy("0.42%"))
    }

    // ---- the general fallback over every builder's sample output -------------------------------------------------

    private val NUMBER = Regex("[+\\-\\u2212]?(?:Rs )?\\d[\\d,]*(?:\\.\\d+)?")

    private fun corpus(): List<Pair<String?, String>> {
        val out = ArrayList<Pair<String?, String>>()
        val held = listOf(AppFacts.Held("NIFTY24500CE", 75, 100.0, 120.0, 1500.0), AppFacts.Held("BANKNIFTY52000PE", 30, 300.0, 250.0, -1500.0))
        out += "positions" to AppFacts.positions("Zerodha", held).joinToString(" ")
        out += "my orders" to AppFacts.orders("Zerodha", listOf(AppFacts.OrderLine("09:20", "NIFTY24500CE", "BUY", 75, "COMPLETE", 100.0, "ORB 15", id = "abcdef123456"),
            AppFacts.OrderLine("09:31", "NIFTY24500CE", "SELL", 75, "REJECTED", 0.0, "Manual", reason = "Margin is short.")), byWho = true).joinToString(" ")
        out += "pnl" to AppFacts.pnl("Zerodha", -3210.5, -3000.0, -210.5)
        out += null to AppFacts.history("Paper", mapOf(java.time.LocalDate.of(2026, 10, 1) to (1200.0 to 3), java.time.LocalDate.of(2026, 10, 2) to (-800.0 to 2),
            java.time.LocalDate.of(2026, 10, 5) to (450.0 to 1)), java.time.LocalDate.of(2026, 10, 5)).joinToString(" ")
        out += "bots" to AppFacts.arms(listOf(AppFacts.ArmLine("ORB 15", "ORB", true, "BankNifty, stop 40", 300.0, "1 lot"),
            AppFacts.ArmLine("Trend", "Pine", true, "Nifty", -120.0, null, 2)), rank = true).joinToString(" ")
        for (t in listOf("what is theta", "what is a straddle", "what is iv", "what is pcr", "what is max pain", "what is vix", "what is an iron condor", "what is delta"))
            Glossary.explain(t)?.let { out += t to it }
        for (t in listOf("where is the kill switch", "how do i add an alarm", "where are pine scripts", "how to see my orders"))
            out += t to AppAnswers.howto(" $t ").joinToString(" ")
        // Records and reads in their usual shape (Comebacks, VixBand, Overnight, DayAfter, WhereIWin, TradesADay, news).
        out += "comebacks" to "Boss, in the last 60 sessions BankNifty came back from a 1% fall 22 times out of 31, about 71%. On the 9 days it did not, it closed -1.4% on average. That is history, not a promise."
        out += "vix band" to "India VIX at 14.2 is in its usual band of 12 to 16 for this month. In that band Nifty's day spans about 180 points. Above 16 the span doubles. That is history, not a promise."
        out += "overnight" to "Overnight: US markets closed +0.8%, crude -1.2% and the dollar flat. GIFT Nifty points to a +60 open. Asian markets are mixed this morning, Japan up and Hong Kong down."
        out += "day after" to "After a day like yesterday, -1.6% on BankNifty, the next day opened lower 7 times of 12 and closed higher 5 times. The average next day was +0.2%. Nothing in that says what today does."
        out += "where i win" to "You win most on expiry days: 9 of 12 trades up, +Rs 14,200 in all. On other days 11 of 30 were up, -Rs 6,300. Mornings before 10:00 are your weakest hour, -Rs 4,100 over 14 trades."
        out += "trades a day" to "On days with 1 or 2 trades you made +Rs 8,400 over 18 days. On days with 5 or more you lost -Rs 9,900 over 6 days. Careful: your busiest days cost you most."
        out += null to "News: RBI kept the repo rate at 6.5%, as expected. Banks moved little on it; BankNifty is +0.1% since. Nothing in it changes your positions."
        out += "why is nifty falling" to "Nifty is down 0.9% today mostly on banks and IT: HDFC Bank -2.1% and Infosys -1.8% together took off about 90 points. FIIs sold Rs 2,300 crore yesterday. The 15-minute trend has been down since 10:30, with no support until 24,300."
        out += "model" to "Boss, the market has been choppy today, with Nifty moving between 24,410 and 24,560 without a clear direction for most of the session, which usually means the big players are waiting for something; the afternoon could still break either way, and you should not read much into the morning's moves."
        return out
    }

    @Test fun fallbackIsShortKeepsSignsAndWarnings() {
        val all = corpus()
        assertTrue(all.size >= 20, "${all.size}")
        for ((q, a) in all) {
            val s = ShortAnswer.of(q, a)
            val line = s.line
            val parts = ShortAnswer.sentences(a)
            val kept = parts.filter { ShortAnswer.safety(it) }
            // The line itself (before any safety sentence kept after it) is one sentence or about 25 words.
            val lead = line.let { l -> kept.fold(l) { acc, k -> acc.replace(k, "") } }.trim()
            assertTrue(ShortAnswer.sentences(lead).size <= 2 || lead.split(' ').size <= ShortAnswer.MAX_WORDS + 5, "too long: $lead  <- $a")
            assertTrue(lead.split(Regex("\\s+")).size <= ShortAnswer.MAX_WORDS + 6, "too many words: $lead")
            // Every warning sentence of the answer is still in the line.
            for (w in parts.filter { it.let(Aloud::warning) && !Regex("^\\d+\\. ").containsMatchIn(it) }) assertTrue(line.contains(w) || line.contains(w.take(25)), "warning lost: $w in $line")
            // A signed figure never loses or flips its sign.
            for (m in NUMBER.findAll(line)) {
                val v = m.value
                if (v.startsWith("-") || v.startsWith("+")) {
                    val first = v.trimStart('+', '-').removePrefix("Rs ").first()
                    assertTrue(Regex(Regex.escape(v.take(1)) + "(Rs )?" + first).containsMatchIn(a), "sign changed: $v in $line <- $a")
                }
            }
            if (s.details != null) assertEquals(a, s.details)
        }
    }
}
