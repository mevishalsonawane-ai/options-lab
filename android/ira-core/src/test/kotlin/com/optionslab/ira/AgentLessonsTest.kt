package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Jarvis learns from the agents ([AgentLessons]): lessons strengthened, weakened, retired and merged; the confidence rules;
 * the caps; every lesson with its evidence; proposals only from strong lessons and never about a safety setting; Boss's
 * two noes resting a lesson 30 days; the questions; the file; and a year of the agents' data digested in under 50 ms.
 */
class AgentLessonsTest {
    private val A = AgentLessons
    private val d0: LocalDate = LocalDate.of(2026, 6, 1)

    private fun trip(owner: String, day: LocalDate, net: Double, h: Int = 11, m: Int = 0) =
        Insights.Trip("X", day.atTime(h, m), day.atTime(h, m).plusMinutes(20), net, owner)

    /** [days] days of [owner]'s trades from [from], [perDay] a day, each worth [net] (by day index). */
    private fun trips(owner: String, from: LocalDate, days: Int, perDay: Int, net: (Int) -> Double, h: Int) =
        (0 until days).flatMap { k -> (0 until perDay).map { trip(owner, from.plusDays(k.toLong()), net(k), h) } }

    private fun trips(owner: String, from: LocalDate, days: Int, perDay: Int, net: (Int) -> Double) = trips(owner, from, days, perDay, net, 11)

    private fun ms(d: LocalDate, h: Int = 11) = d.atTime(h, 0).atZone(com.optionslab.engine.IST).toInstant().toEpochMilli()

    private fun managedTrade(id: String, family: String, at: Long) = TradeManager.ManagedTrade("$family:x", id, family, "NIFTY", 1, "NIFTY25OCT25000CE",
        100.0, 75, 70.0, 160.0, TradeManager.Caps(at + 3_600_000L), TradeManager.Account.PAPER, at, 1.0)

    /** A settled managed trade on [day]: [lead] led an early exit worth [vs] rupees against the original rules. */
    private fun settled(id: String, family: String, lead: String, vs: Double, day: LocalDate): TradeManager.Record {
        val at = ms(day)
        val note = TradeManager.Note(at, TradeManager.EXIT_EARLY, "team", "out", 110.0, true, lead = lead, voters = listOf(lead))
        return TradeManager.Record(managedTrade(id, family, at), TradeManager.Mode.ACT, listOf(note),
            actual = TradeManager.Exit(at, 110.0, "trade manager"), manager = TradeManager.Exit(at, 110.0, "trade manager"),
            original = TradeManager.Exit(at + 1, 110.0 - vs / 75, "STOP"), actedAtMs = at)
    }

    private fun lesson(b: AgentLessons.Book, id: String) = b.lessons.first { it.id == A.norm(id) }

    // ---- confidence ----

    @Test fun strongNeedsThirtyAndTwoOfThreeSplitsAgreeing() {
        fun bs(vararg net: Double) = net.mapIndexed { i, x -> AgentLessons.Bucket(d0.plusDays(i.toLong()), 10, x, if (x > 0) 6 else 3) }
        val all = A.evidence(AgentLessons.Kind.RUPEES, "strat:a", bs(100.0, 200.0, 300.0))
        assertEquals(30, all.n); assertEquals(listOf(1, 1, 1), all.splits)
        assertEquals(AgentLessons.Confidence.STRONG, A.confidence(all))
        // Two of three agree: still strong.
        val two = A.evidence(AgentLessons.Kind.RUPEES, "strat:a", bs(500.0, -100.0, 300.0))
        assertEquals(2, two.agreeing); assertEquals(AgentLessons.Confidence.STRONG, A.confidence(two))
        // One of three: building only (the sum is up, the record is not consistent).
        val one = A.evidence(AgentLessons.Kind.RUPEES, "strat:a", bs(900.0, -100.0, -200.0))
        assertEquals(1, one.sign); assertEquals(1, one.agreeing); assertEquals(AgentLessons.Confidence.BUILDING, A.confidence(one))
        // 29 trades: never strong, however consistent.
        val few = A.evidence(AgentLessons.Kind.RUPEES, "strat:a", listOf(AgentLessons.Bucket(d0, 10, 1.0, 1), AgentLessons.Bucket(d0.plusDays(1), 10, 1.0, 1),
            AgentLessons.Bucket(d0.plusDays(2), 9, 1.0, 1)))
        assertEquals(AgentLessons.Confidence.BUILDING, A.confidence(few))
        // Under 10: weak. Fewer than three days: no splits, so never strong.
        assertEquals(AgentLessons.Confidence.WEAK, A.confidence(A.evidence(AgentLessons.Kind.RUPEES, "strat:a", bs(1.0).map { it.copy(n = 9) })))
        val oneDay = A.evidence(AgentLessons.Kind.RUPEES, "strat:a", listOf(AgentLessons.Bucket(d0, 100, 5_000.0, 60)))
        assertTrue(oneDay.splits.isEmpty()); assertEquals(AgentLessons.Confidence.BUILDING, A.confidence(oneDay))
    }

    // ---- strengthening, weakening, retiring, merging ----

    @Test fun newEvidenceStrengthensThenConfirms() {
        val first = A.pass(AgentLessons.Book(), AgentLessons.Inputs(d0.plusDays(3), trips = trips("ORB Fresh", d0, 4, 3) { 500.0 }))
        val l1 = lesson(first.book, "strat:ORB Fresh")
        assertEquals(AgentLessons.Status.NEW, l1.status); assertEquals(AgentLessons.Confidence.BUILDING, l1.confidence)
        assertTrue(first.changes.any { it.id == l1.id && it.kind == AgentLessons.ChangeKind.NEW })
        // The same day again: nothing new, nothing judged twice.
        val again = A.pass(first.book, AgentLessons.Inputs(d0.plusDays(3), trips = trips("ORB Fresh", d0, 4, 3) { 500.0 }))
        assertTrue(again.changes.none { it.id == l1.id }); assertEquals(12, A.evidence(lesson(again.book, "strat:ORB Fresh")).n)
        // Twenty more days of winners: strong and confirmed.
        val more = A.pass(again.book, AgentLessons.Inputs(d0.plusDays(12), trips = trips("ORB Fresh", d0, 12, 3) { 500.0 }))
        val l2 = lesson(more.book, "strat:ORB Fresh")
        assertEquals(AgentLessons.Confidence.STRONG, l2.confidence); assertEquals(AgentLessons.Status.CONFIRMED, l2.status)
        assertTrue(more.changes.any { it.id == l2.id && it.kind == AgentLessons.ChangeKind.CONFIRMED })
        assertEquals("ORB Fresh makes money", A.statement(l2))
        assertTrue(A.evidenceLine(l2).startsWith("36 trades, +Rs 18,000, 100% won"), A.evidenceLine(l2))
    }

    @Test fun contradictedLessonsWeakenThenRetire() {
        // 30 days of small winners: a strong lesson.
        val good = trips("Hero", d0, 30, 1) { 100.0 }
        var b = A.pass(AgentLessons.Book(), AgentLessons.Inputs(d0.plusDays(29), trips = good)).book
        assertEquals(AgentLessons.Status.CONFIRMED, lesson(b, "strat:Hero").status)
        // Fifteen losing days: the newest third turns against it - weakened, still saying what it said.
        val bad1 = good + trips("Hero", d0.plusDays(30), 15, 1) { -150.0 }
        val p1 = A.pass(b, AgentLessons.Inputs(d0.plusDays(44), trips = bad1))
        val w = lesson(p1.book, "strat:Hero")
        assertEquals(AgentLessons.Status.WEAKENED, w.status)
        assertTrue(p1.changes.any { it.kind == AgentLessons.ChangeKind.WEAKENED })
        // More losses: contradicted again - retired, the sum now down and the statement turned.
        val bad2 = bad1 + trips("Hero", d0.plusDays(45), 15, 1) { -150.0 }
        b = A.pass(p1.book, AgentLessons.Inputs(d0.plusDays(59), trips = bad2)).book
        val r = lesson(b, "strat:Hero")
        assertEquals(AgentLessons.Status.RETIRED, r.status)
        assertEquals(-1, r.sign); assertEquals("Hero loses money", A.statement(r)); assertTrue(r.flips >= 1)
        assertTrue(A.active(b).none { it.id == r.id })
        assertTrue(b.timeline.any { it.key == "first-retired" })
    }

    @Test fun duplicatesMerge() {
        val l = AgentLessons.Lesson("strat:ORB", AgentLessons.Topic.STRATEGY, AgentLessons.Kind.RUPEES, "ORB", listOf("orb"), setOf("the bot trades"),
            listOf(AgentLessons.Bucket(d0, 5, 100.0, 3)), 1, AgentLessons.Status.NEW, AgentLessons.Confidence.WEAK, d0, d0, d0)
        val dup = l.copy(id = "STRAT:orb ", agents = setOf("the order flow"), buckets = listOf(AgentLessons.Bucket(d0.plusDays(1), 6, 50.0, 2)),
            firstSeen = d0.plusDays(1), lastSeen = d0.plusDays(1), rejections = 1)
        val m = A.merge(listOf(l, dup))
        assertEquals(1, m.size)
        assertEquals("strat:orb", m[0].id)
        assertEquals(11, A.evidence(m[0]).n); assertEquals(setOf("the bot trades", "the order flow"), m[0].agents)
        assertEquals(d0, m[0].firstSeen); assertEquals(d0.plusDays(1), m[0].lastSeen); assertEquals(1, m[0].rejections)
        // The same day twice is one day (the larger kept), never counted twice.
        assertEquals(6, A.evidence(A.merge(listOf(l, l.copy(buckets = listOf(AgentLessons.Bucket(d0, 6, 1.0, 1)))))[0]).n)
    }

    @Test fun foldedDaysAreNeverCountedTwice() {
        // 200 days: more than a lesson keeps one by one, so the oldest are folded into one bucket.
        val all = trips("Solo", d0, 200, 2) { 50.0 }
        val p1 = A.pass(AgentLessons.Book(), AgentLessons.Inputs(d0.plusDays(199), trips = all)).book
        val l1 = lesson(p1, "strat:Solo")
        assertEquals(AgentLessons.MAX_BUCKETS, l1.buckets.size); assertEquals(400, A.evidence(l1).n)
        assertEquals(d0, l1.firstSeen); assertEquals(d0, l1.buckets.first().from)
        // Read again (the file between): still 400. A source that kept only its last 30 days: still 400, plus the new day.
        val p2 = A.pass(A.decode(A.encode(p1)), AgentLessons.Inputs(d0.plusDays(199), trips = all)).book
        assertEquals(400, A.evidence(lesson(p2, "strat:Solo")).n)
        val p3 = A.pass(p2, AgentLessons.Inputs(d0.plusDays(200), trips = trips("Solo", d0.plusDays(171), 30, 2) { 50.0 }))
        assertEquals(402, A.evidence(lesson(p3.book, "strat:Solo")).n)
    }

    // ---- caps ----

    @Test fun capsDropRetiredThenTheOldestWeakFirst() {
        fun l(i: Int, c: AgentLessons.Confidence, s: AgentLessons.Status, seen: LocalDate) = AgentLessons.Lesson("strat:b$i", AgentLessons.Topic.STRATEGY,
            AgentLessons.Kind.RUPEES, "Bot $i", listOf("b$i"), setOf("the bot trades"), listOf(AgentLessons.Bucket(seen, 3, 10.0, 1)), 1, s, c, seen, seen, seen)
        val ls = (0 until 50).map { l(it, AgentLessons.Confidence.STRONG, AgentLessons.Status.CONFIRMED, d0.plusDays(it.toLong())) } +
            (50 until 150).map { l(it, AgentLessons.Confidence.BUILDING, AgentLessons.Status.NEW, d0.plusDays(it.toLong())) } +
            (150 until 350).map { l(it, AgentLessons.Confidence.WEAK, AgentLessons.Status.NEW, d0.plusDays(it.toLong())) } +
            (350 until 400).map { l(it, AgentLessons.Confidence.STRONG, AgentLessons.Status.RETIRED, d0.plusDays(it.toLong())) }
        val c = A.capped(AgentLessons.Book(ls))
        assertEquals(AgentLessons.MAX_LESSONS, c.lessons.size)
        val ids = c.lessons.map { it.id }.toSet()
        assertTrue((350 until 400).none { "strat:b$it" in ids }, "the retired go first")
        assertTrue((150 until 200).none { "strat:b$it" in ids }, "then the oldest weak")
        assertTrue((200 until 350).all { "strat:b$it" in ids } && (0 until 150).all { "strat:b$it" in ids })
        // And the file's size: the same order, until it fits.
        val small = A.capped(AgentLessons.Book(ls), bytes = 20_000)
        assertTrue(A.encode(small).length <= 20_000)
        assertTrue(small.lessons.all { it.status != AgentLessons.Status.RETIRED })
        assertTrue(small.lessons.count { it.confidence == AgentLessons.Confidence.STRONG } == 50)
    }

    @Test fun aPassNeverHoldsMoreThanTheCap() {
        val many = (0 until 500).flatMap { k -> trips("Bot $k", d0, 2, 1) { 10.0 } }
        val b = A.pass(AgentLessons.Book(), AgentLessons.Inputs(d0.plusDays(1), trips = many)).book
        assertTrue(b.lessons.size <= AgentLessons.MAX_LESSONS)
        assertTrue(A.encode(b).length <= AgentLessons.MAX_BYTES)
    }

    // ---- honesty ----

    @Test fun everyLessonCitesItsEvidence() {
        val days = 40
        val inputs = AgentLessons.Inputs(d0.plusDays(days.toLong()),
            trips = trips("Liquidity 15+5", d0, days, 2, { k -> if (k % 3 == 0) -200.0 else 300.0 }) + trips("ORB", d0, days, 1, { -100.0 }, h = 9),
            regimeToday = "busy",
            managed = (0 until days).map { settled("m$it", "solo", if (it % 2 == 0) "oi" else "vwap", if (it % 2 == 0) -400.0 else 250.0, d0.plusDays(it.toLong())) },
            findings = (0 until days).map { Findings.Finding("self-healing", if (it % 4 == 0) Findings.Kind.DATA_UNRELIABLE else Findings.Kind.OTHER, "*", atMs = ms(d0.plusDays(it.toLong()))) },
            moves = (0 until 20).map { MoveEvents.Named(d0.plusDays(it.toLong()), "BANKNIFTY", if (it % 3 == 0) "1415" else "0931", MoveEvents.Kind.BIG_UP, "f$it") })
        val b = A.pass(AgentLessons.Book(), inputs).book
        assertTrue(b.lessons.size >= 8, "${b.lessons.map { it.id }}")
        for (l in b.lessons) {
            val e = A.evidence(l)
            assertTrue(e.n > 0, l.id)
            assertTrue(l.agents.isNotEmpty(), l.id)
            val s = A.said(l)
            assertTrue(Regex("\\d").containsMatchIn(s) && "from " in s, s)
            if (l.kind == AgentLessons.Kind.RUPEES) assertTrue("Rs " in A.evidenceLine(l), A.evidenceLine(l))
        }
        // Rupees are never claimed without their count beside them.
        assertTrue(b.lessons.map { A.evidenceLine(it) }.all { !it.contains("Rs") || Regex("^\\d+ ").containsMatchIn(it) })
        // The specialists' parts and the manager are there, from their own agents.
        assertEquals(setOf("the specialists"), lesson(b, "spec:solo:oi").agents)
        assertEquals("The OI specialist on Solo hurts its exits against the original rules", A.statement(lesson(b, "spec:solo:oi")))
        assertTrue(lesson(b, "health:data").agents.contains("the findings bus"))
        assertEquals("The market data has had trouble often", A.statement(lesson(b, "health:data")))
    }

    // ---- proposals ----

    private fun specBook(days: Int, vs: Double, from: LocalDate = d0) =
        (0 until days).map { settled("s$it", "pine", "oi", vs, from.plusDays(it.toLong())) }

    @Test fun onlyStrongLessonsProposeAndNeverASafetySetting() {
        // Weak and building: nothing proposed.
        val weak = A.pass(AgentLessons.Book(), AgentLessons.Inputs(d0.plusDays(12), managed = specBook(12, -300.0)))
        assertTrue(weak.proposals.isEmpty())
        // Strong and confirmed, hurting: mute the OI specialist for Pine - put to Boss.
        val strong = A.pass(AgentLessons.Book(), AgentLessons.Inputs(d0.plusDays(40), managed = specBook(40, -300.0)))
        val p = strong.proposals.single { it.kind == AgentLessons.ProposalKind.MUTE_SPECIALIST }
        assertEquals("pine", p.family); assertEquals("oi", p.specialist)
        assertTrue(p.text.startsWith("Boss, I learned that the OI specialist on Pine hurts") && "Shall I mute" in p.text, p.text)
        assertTrue(A.safe(p))
        // Already muted: not proposed again.
        assertTrue(A.pass(AgentLessons.Book(), AgentLessons.Inputs(d0.plusDays(40), managed = specBook(40, -300.0), muted = mapOf("pine:oi" to "x"))).proposals.isEmpty())
        // Proposed today: not again for a week.
        assertTrue(A.pass(strong.book, AgentLessons.Inputs(d0.plusDays(41), managed = specBook(41, -300.0))).proposals.isEmpty())
        // The manager helping a strategy that can act: act on its paper trades (paper only); never for one that cannot.
        val mgr = A.pass(AgentLessons.Book(), AgentLessons.Inputs(d0.plusDays(40), managed = (0 until 40).map { settled("g$it", "vixdiv", "vwap", 200.0, d0.plusDays(it.toLong())) },
            paperModes = mapOf("vixdiv" to TradeManager.Mode.SHADOW)))
        assertTrue(mgr.proposals.any { it.kind == AgentLessons.ProposalKind.MANAGER_ACT_PAPER && it.family == "vixdiv" })
        val cannot = A.pass(AgentLessons.Book(), AgentLessons.Inputs(d0.plusDays(40), managed = (0 until 40).map { settled("g$it", "liquidity", "vwap", 200.0, d0.plusDays(it.toLong())) }))
        assertTrue(cannot.proposals.none { it.kind == AgentLessons.ProposalKind.MANAGER_ACT_PAPER })
        // Every kind there is only lowers risk or acts on paper: none names the PIN, the lock, Live, AI-live, a guard, speech,
        // the day lock or real orders.
        for (k in AgentLessons.ProposalKind.entries)
            assertFalse(Regex("(?i)\\b(pin|lock|live|guard|google|speech|real order|zerodha)\\b").containsMatchIn(k.name.replace('_', ' ') + " " + k.words), k.name)
        assertEquals(3, AgentLessons.ProposalKind.entries.size)
        assertFalse(A.safe(p.copy(family = "live")))
        assertFalse(A.safe(p.copy(specialist = "day lock")))
    }

    @Test fun twoNoesRestALessonForThirtyDays() {
        val day = d0.plusDays(40)
        val strong = A.pass(AgentLessons.Book(), AgentLessons.Inputs(day, managed = specBook(40, -300.0)))
        val id = strong.proposals.single().lessonId
        val once = A.answered(strong.book, id, accepted = false, day)
        assertEquals(1, lesson(once, id).rejections); assertNull(lesson(once, id).cooldownUntil)
        assertTrue(lesson(once, id).weight < 1.0)
        val twice = A.answered(once, id, accepted = false, day.plusDays(7))
        val l = lesson(twice, id)
        assertEquals(day.plusDays(37), l.cooldownUntil)
        assertTrue(A.coolingDown(l, day.plusDays(20)))
        // Within the 30 days: never proposed, however strong.
        assertTrue(A.proposals(twice.lessons, AgentLessons.Inputs(day.plusDays(30))).isEmpty())
        // After them: proposed again.
        assertTrue(A.proposals(twice.lessons, AgentLessons.Inputs(day.plusDays(38))).isNotEmpty())
        // A yes: weight up, never proposed again, and a milestone.
        val yes = A.answered(strong.book, id, accepted = true, day)
        assertTrue(lesson(yes, id).accepted && lesson(yes, id).weight > 1.0)
        assertTrue(A.proposals(yes.lessons, AgentLessons.Inputs(day.plusDays(60))).isEmpty())
        assertTrue(yes.timeline.any { it.key == "first-accepted" })
    }

    // ---- growth and words ----

    @Test fun growthSaysLevelsAndMilestones() {
        val b = A.pass(AgentLessons.Book(), AgentLessons.Inputs(d0.plusDays(40), managed = specBook(40, -300.0),
            muted = mapOf("pine:oi" to "hurt more than it helped over 30 settled decisions"))).book
        val sk = A.skills(b).first { it.topic == AgentLessons.Topic.SPECIALIST }
        assertEquals(AgentLessons.Confidence.STRONG, sk.level); assertEquals(40, sk.n)
        assertEquals("The manager's specialists: strong (40 settled votes, 1 lesson)", sk.words())
        assertTrue(b.timeline.any { it.key == "first-strong" })
        assertTrue(b.timeline.any { it.key == "first-muted-evidence" && "OI on Pine" in it.text })
        val g = A.sayGrowth(b)
        assertTrue(g.startsWith("How I'm growing, Boss: ") && "Skills:" in g && "Milestones:" in g, g)
    }

    @Test fun theAnswersCarryTheirNumbers() {
        val day = d0.plusDays(40)
        val pass = A.pass(AgentLessons.Book(), AgentLessons.Inputs(day, managed = specBook(40, -300.0),
            trips = trips("Liquidity 15+5", d0, 40, 1, { 200.0 }, h = 9)))
        val b = pass.book
        val all = A.sayAll(b)
        assertTrue(all.startsWith("From the agents I've learned ") && "strong" in all && "Rs" in all, all)
        assertTrue(A.NEVER_ACTS in all)
        val today = A.sayToday(b, day)
        assertTrue(today.startsWith("What I learned today:"), today)
        assertTrue(A.sayToday(b, day.plusDays(1)).startsWith("I haven't studied today's records yet"))
        val liq = A.sayAbout(b, A.asked("what do you know about Liquidity?")!!)
        assertTrue(liq.startsWith("What I've learned about liquidity, Boss:") && "Liquidity 15+5 makes money" in liq, liq)
        val morn = A.sayAbout(b, A.asked("what have you learned about mornings")!!)
        assertTrue("entered in the morning (before 10:30) makes money" in morn, morn)
        val oi = A.sayAbout(b, A.asked("what do you know about the OI specialist")!!)
        assertTrue("The OI specialist on Pine hurts" in oi, oi)
        val why = A.sayWhy(A.about(b, A.asked("what do you know about the OI specialist")!!))
        assertTrue(why.startsWith("The evidence, Boss: ") && "40 settled votes" in why && "strong:" in why, why)
        assertEquals(null, A.appendFor(b, "hero"))
        val pine = A.appendFor(b, "pine")!!
        assertTrue(pine.startsWith("What I've learned: The ") && "Pine hurts" in pine && "strong" in pine, pine)
        assertTrue(A.sayAll(AgentLessons.Book()).startsWith("I haven't learned anything from the agents yet"))
        // The model's notes: short, with their numbers, at most 600 characters.
        val notes = A.forModel(b, "how is liquidity doing")
        assertTrue(notes.isNotEmpty() && notes.first().startsWith("Liquidity 15+5"), "$notes")
        assertTrue(notes.sumOf { it.length + 2 } <= AgentLessons.MODEL_CHARS)
        assertTrue(notes.all { Regex("\\d").containsMatchIn(it) })
        val many = A.pass(AgentLessons.Book(), AgentLessons.Inputs(d0.plusDays(40), trips = (0 until 60).flatMap { k -> trips("Bot number $k", d0, 40, 1, { 50.0 }) })).book
        assertTrue(A.forModel(many, "anything").joinToString("; ").length <= AgentLessons.MODEL_CHARS)
    }

    @Test fun theQuestions() {
        for (s in listOf("what have you learned", "Jarvis, what have you learned?", "what did you learn", "what have you learnt so far",
            "what have you learned from the agents", "what did you learn from your bots", "what are your lessons", "what have the agents taught you"))
            assertEquals(AgentLessons.AskKind.ALL, A.asked(s)?.kind, s)
        for (s in listOf("what did you learn today", "What have you learned today?", "what did you learn from the agents today", "aaj kya seekha"))
            assertEquals(AgentLessons.AskKind.TODAY, A.asked(s)?.kind, s)
        for (s in listOf("how are you growing", "How are you growing, Jarvis?", "how have you grown", "show me your growth"))
            assertEquals(AgentLessons.AskKind.GROWTH, A.asked(s)?.kind, s)
        assertEquals("liquidity", A.asked("what do you know about Liquidity")?.tag)
        assertEquals("oi", A.asked("what do you know about the OI specialist?")?.tag)
        assertEquals("morning", A.asked("what have you learned about mornings")?.tag)
        assertEquals("orb_fresh", A.asked("what do you know about ORB Fresh")?.tag)
        assertEquals("mcx-morning", A.asked("what do you know about mcx morning")?.tag)
        assertEquals(AgentLessons.Topic.ORDER_FLOW, A.asked("what have you learned about order flow")?.topic)
        assertEquals(AgentLessons.Topic.SPECIALIST, A.asked("what do you know about the specialists")?.topic)
        assertEquals(AgentLessons.Topic.MANAGER, A.asked("what do you know about the trade manager")?.topic)
        // Not his: Boss himself, his charges, his conditional orders, the week's learning, words, the market, the night's study.
        for (s in listOf("what do you know about me", "what have you learned about me", "what have you learned about my charges",
            "what have you learned about my conditional orders", "what have you learned this week", "what words have you learned",
            "what did you learn from the market today", "what did you learn last night", "what do you know about nifty", "how is nifty",
            "what did you learn about me", "what did you learn from me", "how are you getting better", "show me everything you've learned"))
            assertNull(A.asked(s), s)
        assertTrue(A.whyAsked("why do you think that?")); assertTrue(A.whyAsked("Jarvis, what's the evidence?"))
        assertFalse(A.whyAsked("why is nifty falling")); assertFalse(A.whyAsked("how do you know that"))
    }

    // ---- the file ----

    @Test fun theFileRoundTrips() {
        val day = d0.plusDays(40)
        var b = A.pass(AgentLessons.Book(), AgentLessons.Inputs(day, managed = specBook(40, -300.0), regimeToday = "busy",
            trips = trips("Liquidity 15+5", d0, 40, 1, { 200.0 }))).book
        b = A.answered(b, "spec:pine:oi", accepted = false, day)
        val back = A.decode(A.encode(b))
        assertEquals(A.encode(b), A.encode(back))
        assertEquals(b.lessons.map { it.id }, back.lessons.map { it.id })
        assertEquals(b.lessons.map { A.evidence(it).n }, back.lessons.map { A.evidence(it).n })
        assertEquals(1, back.lessons.first { it.id == "spec:pine:oi" }.rejections)
        assertEquals(b.regimes, back.regimes); assertEquals(b.timeline, back.timeline)
        assertEquals(b.lastPass, back.lastPass); assertEquals(b.today, back.today); assertEquals(b.line, back.line)
        assertEquals(AgentLessons.Book(), A.decode(null)); assertEquals(AgentLessons.Book(), A.decode("garbage|x\nL|bad"))
        // A name with the separators in it is kept readable, never breaks the file.
        val odd = b.copy(lessons = b.lessons.map { it.copy(name = "A|B\nC") })
        assertEquals(b.lessons.size, A.decode(A.encode(odd)).lessons.size)
    }

    // ---- speed ----

    @Test fun aYearOfTheAgentsDataIsDigestedInUnderFiftyMs() {
        val days = 250
        val owners = listOf("Liquidity 15+5", "ORB", "ORB Fresh", "Solo", "Hero", "Night (R3)", "VIX divergence", "MCX evening", "Pine", "Range Fade")
        val trips = owners.flatMapIndexed { o, owner -> trips(owner, d0, days, 4, { k -> if ((k + o) % 3 == 0) -150.0 else 120.0 }, h = 9 + o % 6) }
        val families = listOf("solo", "pine", "orb_fresh", "vixdiv")
        val specs = listOf("oi", "vwap", "flow", "volume", "traps")
        val managed = (0 until 2_000).map { settled("m$it", families[it % 4], specs[it % 5], if (it % 3 == 0) -200.0 else 150.0, d0.plusDays((it % days).toLong())) }
        val keys = listOf("orb", "orb_fresh", "liquidity:BANKNIFTY", "solo", "pine")
        val flow = (0 until 5_000).map { k ->
            FlowShadow.Signal("s$k", ms(d0.plusDays((k % days).toLong())) / 1000, keys[k % 5], "BANKNIFTY", if (k % 2 == 0) 1 else -1, false, OrderFlow.Mode.SHADOW,
                if (k % 3 == 0) OrderFlow.Agreement.DISAGREES else OrderFlow.Agreement.AGREES, false, 60, net = if (k % 4 == 0) -100.0 else 80.0,
                flags = if (k % 7 == 0) listOf("PULL_BID") else emptyList(), wouldPause = if (k % 11 == 0) listOf("traps") else emptyList(),
                consensus = (k % 3) to (k % 2))
        }
        val findings = (0 until 30_000).map { k -> Findings.Finding("w", if (k % 500 == 0) Findings.Kind.DATA_UNRELIABLE else Findings.Kind.OTHER, "NIFTY",
            atMs = ms(d0.plusDays((k % 30).toLong() + 220))) }
        val moves = (0 until 300).map { MoveEvents.Named(d0.plusDays((it % days).toLong()), "NIFTY", if (it % 2 == 0) "0935" else "1310", MoveEvents.Kind.BIG_DOWN, "m$it") }
        val inputs = AgentLessons.Inputs(d0.plusDays(days.toLong()), trips, "normal", emptyList(), managed, flow, findings, emptyList(), moves)
        val book = AgentLessons.Book(regimes = (0 until days).associate { d0.plusDays(it.toLong()) to listOf("busy", "quiet", "normal")[it % 3] })
        // Warm up (the JIT), then the best of a few runs; and a second pass over a kept book (the nightly case) as quick.
        repeat(3) { A.pass(book, inputs) }
        var best = Long.MAX_VALUE
        var kept = book
        repeat(5) {
            val t = System.nanoTime()
            kept = A.pass(book, inputs).book
            best = minOf(best, System.nanoTime() - t)
        }
        var again = Long.MAX_VALUE
        repeat(5) {
            val t = System.nanoTime()
            A.pass(kept, inputs)
            again = minOf(again, System.nanoTime() - t)
        }
        assertTrue(again < 50_000_000L, "a pass over a kept book took ${again / 1_000_000} ms")
        assertTrue(best < 50_000_000L, "a year of data took ${best / 1_000_000} ms")
        val last = A.pass(AgentLessons.Book(), inputs)
        assertTrue(last.book.lessons.size in 50..AgentLessons.MAX_LESSONS, "${last.book.lessons.size}")
        assertNotNull(last.line)
    }
}
