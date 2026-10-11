package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What Jarvis tries to learn next ([LearningGoals]): value × thinness, at most three, deterministic, recording only. */
class LearningGoalsTest {
    private val day = LocalDate.of(2026, 10, 12)

    /** A lesson of [n] cases worth [rs] in all, over [n] days to [day]. */
    private fun lesson(id: String, topic: AgentLessons.Topic, name: String, n: Int, rs: Double,
                       conf: AgentLessons.Confidence = if (n >= AgentLessons.BUILDING_N) AgentLessons.Confidence.BUILDING else AgentLessons.Confidence.WEAK,
                       status: AgentLessons.Status = AgentLessons.Status.NEW, kind: AgentLessons.Kind = AgentLessons.Kind.RUPEES): AgentLessons.Lesson {
        val buckets = (0 until n).map { AgentLessons.Bucket(day.minusDays((n - it).toLong()), 1, rs / n, if (rs > 0) 1 else 0) }
        return AgentLessons.Lesson(id, topic, kind, name, emptyList(), setOf("the specialists"), buckets, if (rs >= 0) 1 else -1, status, conf,
            day.minusDays(n.toLong()), day, day)
    }

    private val oi = lesson("spec:liquidity:oi", AgentLessons.Topic.SPECIALIST, "The OI specialist on Liquidity 15+5", 12, -6_000.0)
    private val hero = lesson("strat:Hero", AgentLessons.Topic.STRATEGY, "Hero", 25, 2_500.0)
    private val trap = lesson("trap:pull_bid", AgentLessons.Topic.ORDER_FLOW, "Trades taken with the trap guard's \"pulled bids\" flag up", 10, -2_000.0)
    /** Stuck at building: 40 cases, the splits still disagree. */
    private val stuck = lesson("mgr:pine", AgentLessons.Topic.MANAGER, "The trade manager on Pine", 40, -8_000.0)
    private val strong = lesson("strat:Liquidity 15+5", AgentLessons.Topic.STRATEGY, "Liquidity 15+5", 60, 30_000.0, AgentLessons.Confidence.STRONG)
    private val rate = lesson("health:data", AgentLessons.Topic.DATA_HEALTH, "The market data", 5, 0.0, kind = AgentLessons.Kind.RATE)
    private val tiny = lesson("strat:Night", AgentLessons.Topic.STRATEGY, "Night", 5, 10.0)
    private val gone = lesson("strat:Old", AgentLessons.Topic.STRATEGY, "Old", 5, -9_000.0, status = AgentLessons.Status.RETIRED)
    private val all = listOf(hero, trap, gone, stuck, rate, oi, tiny, strong)

    @Test fun rankedByValueTimesThinnessCappedAtThree() {
        val r = LearningGoals.ranked(all, mapOf("trap:pull_bid" to "BANKNIFTY"), day)
        // OI: Rs 500 a case × 30 cases = 15,000 at stake, 18 of 30 missing -> 9,000. The manager (stuck at building, 40 cases): 20
        // more to settle, Rs 200 × 60 = 12,000 × 20/60 = 4,000. The trap flag: Rs 200 × 30 = 6,000 × 20/30 = 4,000 (a tie: by id).
        // Hero: Rs 100 × 30 × 5/30 = 500. Strong, retired, a rate and Rs 60 at stake: none.
        assertEquals(listOf("spec:liquidity:oi", "mgr:pine", "trap:pull_bid", "strat:Hero"), r.map { it.id })
        assertEquals(listOf(9_000.0, 4_000.0, 4_000.0, 500.0), r.map { it.score })
        val g = LearningGoals.next(LearningGoals.Book(), all, mapOf("trap:pull_bid" to "BANKNIFTY"), day)
        assertEquals(LearningGoals.MAX_GOALS, g.goals.size)
        assertEquals("Need 18 more Liquidity 15+5 trades to judge the OI specialist", g.goals[0].text)
        assertEquals("Need 20 more managed Pine trades to judge the trade manager", g.goals[1].text)
        assertEquals("Order flow: record 20 more trap events on BANKNIFTY (pulled bids)", g.goals[2].text)
        assertEquals(listOf(12, 40, 10), g.goals.map { it.have })
        assertEquals(listOf(30, 60, 30), g.goals.map { it.target })
        // The same input, the same goals (order of the lessons given does not matter).
        assertEquals(g, LearningGoals.next(LearningGoals.Book(), all.reversed(), mapOf("trap:pull_bid" to "BANKNIFTY"), day))
    }

    @Test fun goalsSteerRecordingOnlyNeverTrading() {
        val g = LearningGoals.next(LearningGoals.Book(), all, mapOf("trap:pull_bid" to "BANKNIFTY"), day).goals
        assertTrue(g.all { LearningGoals.safe(it) }, g.toString())
        assertEquals(LearningGoals.Record.MOVES, g[2].record)
        val p = LearningGoals.plan(g)
        assertEquals(LearningGoals.RecordingPlan.MAX_SHADOW_BYTES, p.shadowLogBytes)
        assertEquals(setOf("liquidity", "pine"), p.families)
        assertEquals(setOf("BANKNIFTY"), p.indices)
        assertTrue(LearningGoals.safe(p))
        assertEquals(LearningGoals.RecordingPlan(), LearningGoals.plan(emptyList()))
        // The plan holds recording fields only: nothing that could enter, exit, size or switch.
        assertEquals(setOf("shadowLogBytes", "families", "indices"),
            LearningGoals.RecordingPlan::class.java.declaredFields.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }.map { it.name }.toSet())
        assertFalse(LearningGoals.safe(p.copy(shadowLogBytes = 64L * 1024 * 1024)))
        // A goal's words can never be an instruction to trade.
        assertFalse(LearningGoals.safe(g[0].copy(text = "Buy 2 lots of BANKNIFTY to judge the OI specialist")))
        val said = LearningGoals.say(LearningGoals.Book(g, day))
        assertTrue(said.startsWith("What I'm trying to learn next, Boss:\n• Need 18 more Liquidity 15+5 trades to judge the OI specialist (12 of 30 so far; about Rs 15,000 at stake)"), said)
        assertTrue(said.endsWith("a goal never places, changes or sizes a trade."), said)
    }

    @Test fun aKeptGoalKeepsItsStartAndAMetOneIsRemembered() {
        val first = LearningGoals.next(LearningGoals.Book(), listOf(oi, stuck), emptyMap(), day)
        // A week on: 6 more OI cases; the manager's lesson turned strong.
        val oi2 = lesson("spec:liquidity:oi", AgentLessons.Topic.SPECIALIST, "The OI specialist on Liquidity 15+5", 18, -9_000.0)
        val stuck2 = stuck.copy(confidence = AgentLessons.Confidence.STRONG)
        val later = LearningGoals.next(first, listOf(oi2, stuck2), emptyMap(), day.plusDays(7))
        assertEquals(listOf("spec:liquidity:oi"), later.goals.map { it.id })
        assertEquals(12, later.goals[0].startN)
        assertEquals(day, later.goals[0].since)
        assertEquals(6, later.goals[0].progress)
        assertEquals(listOf("mgr:pine"), later.met.map { it.id })
        assertEquals(day.plusDays(7), later.met[0].on)
        assertEquals(later, LearningGoals.decode(LearningGoals.encode(later)))
    }

    @Test fun instrumentsFromTheShadowLog() {
        fun s(id: String, und: String, flags: List<String>, live: Boolean = false) = FlowShadow.Signal(id, 1L, "liquidity:$und", und, 1, live,
            OrderFlow.Mode.SHADOW, OrderFlow.Agreement.AGREES, false, null, flags = flags)
        val m = LearningGoals.instruments(listOf(s("1", "BANKNIFTY", listOf("PULL_BID")), s("2", "BANKNIFTY", listOf("PULL_BID")),
            s("3", "NIFTY", listOf("PULL_BID")), s("4", "NIFTY", listOf("PULL_BID"), live = true)))
        assertEquals("BANKNIFTY", m["trap:pull_bid"])
        assertEquals("NIFTY", m["flow:liquidity:nifty:with"])
    }

    @Test fun nothingWorthItSaysSo() {
        assertTrue(LearningGoals.next(LearningGoals.Book(), listOf(strong, tiny, rate), emptyMap(), day).goals.isEmpty())
        assertTrue(LearningGoals.say(LearningGoals.Book()).startsWith("Nothing in particular just now, Boss"))
    }

    @Test fun asked() {
        for (q in listOf("what are you trying to learn?", "Jarvis, what are you trying to learn", "what are your learning goals", "what do you want to learn next"))
            assertTrue(LearningGoals.asked(q), q)
        for (q in listOf("what have you learned", "what did you learn today", "what are my goals", "how are you growing"))
            assertFalse(LearningGoals.asked(q), q)
    }
}
