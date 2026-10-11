package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Per-regime settings (TODO S8): frozen at 09:45, only from passing conditional lessons, shadow 10 days, paper then live. */
class RegimeParamsTest {
    @AfterTest fun reset() = RegimeParams.resetForTest()

    private val d0 = LocalDate.of(2026, 10, 1)

    private fun lesson(id: String, sign: Int, cond: String?, condQ: Double?, status: AgentLessons.Status = AgentLessons.Status.CONFIRMED,
                       kind: AgentLessons.Kind = AgentLessons.Kind.RUPEES) =
        AgentLessons.Lesson(id, AgentLessons.Topic.STRATEGY, kind, id.substringAfter(':'), emptyList(), emptySet(), emptyList(), sign, status,
            AgentLessons.Confidence.BUILDING, d0, d0, d0, cond = cond, condQ = condQ)

    private fun busyDay(day: LocalDate, event: Boolean = false) = RegimeParams.Day(day, RegimeParams.Regime.BUSY, expiry = false, event = event, decidedAt = 585)

    @Test fun theBrainsRegimeReadsAsTheDays() {
        fun r(s: MarketBrain.State, t: MarketBrain.Trend = MarketBrain.Trend.UNKNOWN) = MarketBrain.Regime("NIFTY", s, trend = t)
        assertEquals(RegimeParams.Regime.BUSY, RegimeParams.decide(r(MarketBrain.State.BUSY)))
        assertEquals(RegimeParams.Regime.QUIET, RegimeParams.decide(r(MarketBrain.State.QUIET)))
        assertEquals(RegimeParams.Regime.TRENDING, RegimeParams.decide(r(MarketBrain.State.NORMAL, MarketBrain.Trend.DOWN)))
        assertEquals(RegimeParams.Regime.SIDEWAYS, RegimeParams.decide(r(MarketBrain.State.NORMAL, MarketBrain.Trend.MIXED)))
        assertNull(RegimeParams.decide(r(MarketBrain.State.NORMAL)))
        assertNull(RegimeParams.decide(r(MarketBrain.State.UNKNOWN)))
        assertNull(RegimeParams.decide(null))
    }

    @Test fun theDayIsDecidedAt0945AndNeverFlipsAfter() {
        val busy = MarketBrain.Regime("NIFTY", MarketBrain.State.BUSY)
        val quiet = MarketBrain.Regime("NIFTY", MarketBrain.State.QUIET)
        assertNull(RegimeParams.freeze(null, d0, 9 * 60 + 44, false, false) { busy })
        val day = RegimeParams.freeze(null, d0, 9 * 60 + 45, true, false) { busy }!!
        assertEquals(RegimeParams.Regime.BUSY, day.regime); assertTrue(day.expiry)
        // Later in the day the brain says quiet: the frozen day stands.
        assertEquals(day, RegimeParams.freeze(day, d0, 13 * 60, true, false) { quiet })
        // The next day decides afresh.
        assertEquals(RegimeParams.Regime.QUIET, RegimeParams.freeze(day, d0.plusDays(1), 10 * 60, false, false) { quiet }!!.regime)
    }

    @Test fun onlyRegimeConditionalLessonsThatPassedTheBarMakeOverrides() {
        val ls = listOf(
            lesson("strat:Liquidity 15+5", -1, "regime:busy", 0.04),                // loses on busy days: sat out
            lesson("strat:ORB", +1, "regime:busy", 0.04),                           // wins on busy days: never adds risk
            lesson("strat:ORB Fresh", -1, "expiry:yes", 0.01),                      // not a regime condition
            lesson("strat:Pine", -1, "regime:busy", 0.2),                           // did not pass the BH bar
            lesson("strat:Night (R3)", -1, "regime:quiet", 0.05, status = AgentLessons.Status.RETIRED),
            lesson("strat:Hero", -1, null, null),
            lesson("spec:pine:vwap", +1, "regime:normal", 0.08),                    // a normal day: trending and sideways
            lesson("spec:solo:delta", -1, "regime:quiet", 0.02),
            lesson("health:data", -1, "regime:busy", 0.01, kind = AgentLessons.Kind.RATE),
        )
        val o = RegimeParams.fromLessons(ls)
        assertEquals(setOf("arm:liquidity|BUSY", "spec:pine:vwap|TRENDING", "spec:pine:vwap|SIDEWAYS", "spec:solo:delta|QUIET"), o.map { it.key }.toSet())
        assertEquals(mapOf(RegimeParams.SIZE_KEY to 0.0), o.first { it.target == "arm:liquidity" }.params)
        assertEquals(RegimeParams.WEIGHT_UP, o.first { it.target == "spec:pine:vwap" }.params["weight"])
        assertEquals(RegimeParams.WEIGHT_DOWN, o.first { it.target == "spec:solo:delta" }.params["weight"])
        assertTrue(o.all { it.stage == RegimeParams.Stage.SHADOW })
    }

    @Test fun shadowTenTradingDaysThenPaperThenLiveOnlyWithApproval() {
        var b = RegimeParams.refresh(RegimeParams.Book(), listOf(lesson("strat:Liquidity 15+5", -1, "regime:busy", 0.04)))
        val ready = ArrayList<RegimeParams.Override>()
        // Quiet days and an event day count nothing.
        b = RegimeParams.observe(b, RegimeParams.Day(d0, RegimeParams.Regime.QUIET, false, false, 585)).first
        b = RegimeParams.observe(b, busyDay(d0.plusDays(1), event = true)).first
        assertTrue(b.overrides.single().shadowDays.isEmpty())
        for (i in 0 until 10) {
            val day = busyDay(d0.plusDays(2L + i))
            val (nb, r) = RegimeParams.observe(b, day)
            b = nb; ready += r
            // The same day seen twice counts once.
            b = RegimeParams.observe(b, day).first
            RegimeParams.use(b)
            if (i < 9) {
                assertNull(RegimeParams.overrideFor("liquidity", RegimeParams.Regime.BUSY), "still in shadow on day ${i + 1}")
                assertTrue(RegimeParams.wouldUse(date = day.day).single().contains("would have used the busy-day setting"))
            }
        }
        assertEquals(1, ready.size)
        assertEquals(RegimeParams.Stage.PAPER, b.overrides.single().stage)
        assertEquals(mapOf(RegimeParams.SIZE_KEY to 0.0), RegimeParams.overrideFor("Liquidity 15+5", RegimeParams.Regime.BUSY))
        val last = d0.plusDays(11)
        assertEquals(last, b.day?.day)
        assertEquals(mapOf(RegimeParams.SIZE_KEY to 0.0), RegimeParams.overrideToday("liquidity:BANKNIFTY".substringBefore(':'), date = last))
        // Yesterday's frozen day never applies today (09:15-09:45, or a day the brain's worker never ticked).
        assertNull(RegimeParams.overrideToday("liquidity", date = last.plusDays(1)), "a stale regime day never applies")
        // (Skipped on the one real day equal to the test's last day - 12 Oct 2026 - when the default date IS the test's.)
        if (java.time.LocalDate.now(com.optionslab.engine.IST) != last)
            assertNull(RegimeParams.overrideToday("liquidity"), "the default date is the real today, not the test's")
        assertTrue(RegimeParams.wouldUse(date = last.plusDays(1)).isEmpty())
        assertNull(RegimeParams.overrideFor("liquidity", RegimeParams.Regime.BUSY, live = true), "live waits for Boss's PIN")
        assertNull(RegimeParams.overrideFor("liquidity", RegimeParams.Regime.QUIET))
        b = RegimeParams.approveLive(b, "arm:liquidity|BUSY")
        RegimeParams.use(b)
        assertEquals(RegimeParams.Stage.LIVE, b.overrides.single().stage)
        assertEquals(mapOf(RegimeParams.SIZE_KEY to 0.0), RegimeParams.overrideFor("liquidity", RegimeParams.Regime.BUSY, live = true))
        // An event day: nothing applies today, whatever the regime.
        RegimeParams.use(b.copy(day = busyDay(d0.plusDays(30), event = true)))
        assertNull(RegimeParams.overrideToday("liquidity", date = d0.plusDays(30)))
        assertTrue(RegimeParams.line().contains("event day"))
        // Tonight the lesson is gone: so is the override; one that stays keeps its days and approval.
        assertTrue(RegimeParams.refresh(b, emptyList()).overrides.isEmpty())
        assertEquals(b.overrides, RegimeParams.refresh(b, listOf(lesson("strat:Liquidity 15+5", -1, "regime:busy", 0.03))).overrides.map { it.copy(q = 0.04) })
    }

    @Test fun nothingMovesToPaperByItselfWhileHeld() {
        // Review 2 #10: learning frozen (or Autopilot not SAFE): the 10th shadow day is not counted, nothing goes to paper.
        var b = RegimeParams.refresh(RegimeParams.Book(), listOf(lesson("strat:Liquidity 15+5", -1, "regime:busy", 0.04)))
        for (i in 0 until 9) b = RegimeParams.observe(b, busyDay(d0.plusDays(i.toLong()))).first
        assertEquals(9, b.overrides.single().shadowDays.size)
        val (held, none) = RegimeParams.observe(b, busyDay(d0.plusDays(9)), hold = true)
        assertTrue(none.isEmpty())
        assertEquals(RegimeParams.Stage.SHADOW, held.overrides.single().stage)
        assertNull(RegimeParams.overrideFor("liquidity", RegimeParams.Regime.BUSY, b = held))
        // Released: the next busy day finishes its shadow as before.
        val (open, ready) = RegimeParams.observe(held, busyDay(d0.plusDays(10)))
        assertEquals(1, ready.size)
        assertEquals(RegimeParams.Stage.PAPER, open.overrides.single().stage)
        // An override already on paper stays there while held.
        assertEquals(RegimeParams.Stage.PAPER, RegimeParams.observe(open, busyDay(d0.plusDays(11)), hold = true).first.overrides.single().stage)
    }

    @Test fun approvalBeforeTheShadowEndsDoesNothing() {
        val b = RegimeParams.refresh(RegimeParams.Book(), listOf(lesson("strat:ORB", -1, "regime:busy", 0.04)))
        assertFalse(RegimeParams.approveLive(b, "arm:orb|BUSY").overrides.single().liveApproved)
    }

    @Test fun specialistWeightsAreLaidOverTheManagersConfigOnlyOnceOutOfShadow() {
        val o = RegimeParams.fromLessons(listOf(lesson("spec:pine:news", -1, "regime:busy", 0.01))).single()
        val days = (0 until 10).map { d0.plusDays(it.toLong()) }
        val cfg = ManagerConfig("pine")
        val shadow = RegimeParams.Book(listOf(o), busyDay(d0))
        assertEquals(cfg, RegimeParams.applyTo(cfg, live = false, b = shadow))
        val paper = RegimeParams.Book(listOf(o.copy(shadowDays = days)), busyDay(d0.plusDays(20)))
        val on = d0.plusDays(20)
        assertEquals(0.5, RegimeParams.applyTo(cfg, live = false, b = paper, date = on).spec("news").weight, 1e-9)
        assertEquals(cfg, RegimeParams.applyTo(cfg, live = true, b = paper, date = on))
        assertEquals(ManagerConfig("solo"), RegimeParams.applyTo(ManagerConfig("solo"), live = false, b = paper, date = on))
        assertEquals(cfg, RegimeParams.applyTo(cfg, live = false, b = paper.copy(day = null), date = on))
        // The next morning (before 09:45 freezes a new day): yesterday's weights never apply.
        assertEquals(cfg, RegimeParams.applyTo(cfg, live = false, b = paper, date = on.plusDays(1)))
    }

    @Test fun candidatesFromTheLabMeetTheSameBarAndOnlyLowerRisk() {
        val ok = RegimeParams.Candidate("ORB Fresh", RegimeParams.Regime.QUIET, mapOf(RegimeParams.SIZE_KEY to 0.5), 0.05, 25, 40, "lab variant v3")
        assertTrue(RegimeParams.passes(ok))
        assertFalse(RegimeParams.passes(ok.copy(nInside = 19)))
        assertFalse(RegimeParams.passes(ok.copy(q = 0.11)))
        assertFalse(RegimeParams.passes(ok.copy(params = mapOf(RegimeParams.SIZE_KEY to 1.5))))
        val b = RegimeParams.refresh(RegimeParams.Book(), emptyList(), listOf(ok, ok.copy(nOutside = 3)))
        assertEquals(listOf("arm:orb_fresh|QUIET"), b.overrides.map { it.key })
        // Two overrides of one key: the lower value wins.
        val o1 = RegimeParams.Override("arm:orb", RegimeParams.Regime.BUSY, mapOf(RegimeParams.SIZE_KEY to 0.5), "a", 0.01, "a",
            (0 until 10).map { d0.plusDays(it.toLong()) })
        val o2 = o1.copy(regime = RegimeParams.Regime.BUSY, params = mapOf(RegimeParams.SIZE_KEY to 0.0, "x" to 2.0), lessonId = "b")
        assertEquals(mapOf(RegimeParams.SIZE_KEY to 0.0, "x" to 2.0), RegimeParams.overrideFor("orb", RegimeParams.Regime.BUSY, b = RegimeParams.Book(listOf(o1, o2))))
    }

    @Test fun theBookIsSavedAndReadBack() {
        val o = RegimeParams.Override("spec:pine:vwap", RegimeParams.Regime.SIDEWAYS, mapOf("weight" to 1.25), "spec:pine:vwap", 0.08,
            "The vwap specialist | on Pine", listOf(d0, d0.plusDays(1)), liveApproved = true)
        val b = RegimeParams.Book(listOf(o), RegimeParams.Day(d0, null, true, false, 590))
        val back = RegimeParams.decode(RegimeParams.encode(b))
        assertEquals(b.day, back.day)
        assertEquals(o.copy(words = "The vwap specialist / on Pine"), back.overrides.single())
        assertEquals(RegimeParams.Book(), RegimeParams.decode(null))
        assertEquals(RegimeParams.Book(), RegimeParams.decode("junk\nO|bad"))
        assertTrue(RegimeParams.line(back).startsWith("Today: not known (decided 09:50"))
        assertTrue(RegimeParams.line(RegimeParams.Book()).contains("09:45"))
    }
}
