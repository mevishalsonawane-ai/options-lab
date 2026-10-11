package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Research round 12 in the trade manager: the bundled block ([R12Defaults]) read through [ManagerConfig.parse], the default
 * modes per arm and the stored choices kept, the research committee in the Chair (its members alone decide; the live-only
 * specialists and their vetoes only record; its locks rise and never fall), and the verdict lines Jarvis and the record say.
 */
class R12ManagerTest {
    private val base = 29_333_340L * 60_000L
    private fun at(col: Int, sec: Int = 0) = base + col * 60_000L + sec * 1_000L
    private val entryMs = at(50, 20)
    private fun bar(col: Int, h: Double, l: Double, c: Double) = TradeManager.Bar(at(col), (h + l) / 2, h, l, c)
    private val P = TradeManager.Account.PAPER
    private val L = TradeManager.Account.LIVE

    private fun fresh(stop: Double? = 60.0, account: TradeManager.Account = P) = TradeManager.ManagedTrade("orb_fresh:ORB Fresh", "f1", "ORB Fresh",
        "BANKNIFTY", 1, "BANKNIFTY26OCT52000CE", 100.0, 30, stop, 140.0, TradeManager.Caps(at(345)), account, entryMs, 0.6)

    // ---- the bundled block ------------------------------------------------------------------------------------------------

    @Test fun theBundledBlockIsTheResearchsRulesAsSpecialistParameters() {
        val c = R12Defaults.configs
        val f = c.getValue("orb_fresh")
        assertEquals(setOf("price"), f.members)
        assertEquals(20.0, f.param("price", "paT")); assertEquals(0.03, f.param("price", "paM")); assertEquals(0.12, f.param("price", "pbA"))
        assertEquals(3.0, f.param("price", "vcK")); assertEquals(1.0, f.param("price", "vcArm"))
        assertEquals(1.0, f.weight("price")); assertEquals(1.0, f.quorum)
        assertFalse(f.extend)
        assertEquals(TradeManager.Mode.ACT, f.paperMode); assertEquals(TradeManager.Mode.SHADOW, f.liveMode)
        for (id in ManagerTeam.IDS - "price") assertEquals(0.0, f.weight(id), "$id records only")
        assertEquals(6.0, c.getValue("orb").param("price", "vcK"))
        val sweep = c.getValue("orb_sweep")
        assertEquals(setOf("oi", "price"), sweep.members); assertEquals(1.5, sweep.param("oi", "oiZ0")); assertEquals(45.0, sweep.param("price", "paT"))
        assertEquals(setOf("vwap", "delta"), sweep.extendMembers); assertTrue(sweep.extend); assertEquals(0.5, sweep.param("vwap", "xvw"))
        assertEquals(1.0, sweep.quorum, "the extension's own quorum is not the exit's")
        val fade = c.getValue("range_fade")
        assertEquals(setOf("price", "vwap"), fade.members); assertEquals(0.5, fade.param("vwap", "vwZ")); assertEquals(5.0, fade.param("vwap", "vwP"))
        assertEquals(setOf("oi", "price"), c.getValue("liquidity").members)
        assertEquals(setOf("profile", "price"), c.getValue("vixdiv/nifty").members)
        assertEquals(setOf("delta", "profile"), c.getValue("vixdiv/banknifty").members)
        assertEquals(2.0, c.getValue("vixdiv/banknifty").param("delta", "dpZ0"))
        assertEquals(c.getValue("vixdiv/nifty"), R12Defaults.config("vixdiv", "NIFTY"))
        val hero = c.getValue("hero")
        assertEquals(0.15, hero.param("price", "beB")); assertEquals(1.0, hero.param("profile", "mpP"))
        // Solo and Pine keep their whole committee (not tested by R12); the MCX legs record; Night and MCX trend are off.
        assertFalse(c.getValue("solo").isResearch); assertFalse(c.getValue("pine").isResearch)
        assertEquals(ManagerConfig("pine").specialists, c.getValue("pine").specialists)
        for (k in listOf("mcx-eve", "mcx-morning", "silver-night")) assertEquals(TradeManager.Mode.SHADOW, c.getValue(k).paperMode, k)
        for (k in listOf("night", "mcx-trend")) assertEquals(TradeManager.Mode.OFF, c.getValue(k).paperMode, k)
        // A research block survives the store's key=value round trip.
        assertEquals(f, ManagerConfig.parse(ManagerConfig.encode(f)).getValue("orb_fresh"))
        assertEquals(sweep, ManagerConfig.parse(ManagerConfig.encode(sweep)).getValue("orb_sweep"))
        // LIVE is never ACT by default, whatever a block says.
        assertEquals(TradeManager.Mode.SHADOW, ManagerConfig.parse("[pine]\nlive_mode=ACT").getValue("pine").liveMode)
    }

    // ---- modes -----------------------------------------------------------------------------------------------------------

    @Test fun theDefaultModesPerArmAndBossesStoredChoicesKept() {
        val p = TradeManager.Policy()
        assertEquals(TradeManager.Mode.ACT, p.mode("orb_fresh", P))
        assertEquals(TradeManager.Mode.SHADOW, p.mode("orb_fresh", L))
        for (f in listOf("orb", "orb_sweep", "range_fade", "liquidity", "vixdiv", "hero", "mcx-eve", "mcx-morning", "silver-night"))
            for (a in TradeManager.Account.entries) assertEquals(TradeManager.Mode.SHADOW, p.mode(f, a), "$f $a")
        for (f in listOf("night", "mcx-trend")) assertEquals(TradeManager.Mode.OFF, p.mode(f, P), f)
        assertEquals(TradeManager.Mode.ACT, p.mode("solo", P)); assertEquals(TradeManager.Mode.ACT, p.mode("pine", P))
        assertEquals(TradeManager.Mode.SHADOW, p.mode("pine", L))
        assertEquals(setOf("solo", "pine", "orb_fresh"), TradeManager.ACT_ON_PAPER)
        // ORB Fresh's live exits do not take the manager's word yet: a stored LIVE ACT reads SHADOW.
        assertEquals(TradeManager.Mode.SHADOW, p.with("orb_fresh", L, TradeManager.Mode.ACT).mode("orb_fresh", L))
        // The other arms never act.
        assertEquals(TradeManager.Mode.SHADOW, p.with("orb", P, TradeManager.Mode.ACT).mode("orb", P))
        // Boss's stored choices win over the defaults.
        val stored = TradeManager.decode("orb_fresh.paper=SHADOW;night.paper=ACT;mcx-eve.paper=OFF")
        assertEquals(TradeManager.Mode.SHADOW, stored.mode("orb_fresh", P))
        assertEquals(TradeManager.Mode.ACT, stored.mode("night", P))
        assertEquals(TradeManager.Mode.OFF, stored.mode("mcx-eve", P))
    }

    @Test fun theOldOrbFamilyModeMovesToEveryArm() {
        val old = TradeManager.decode("orb.paper=OFF;orb.live=OFF;solo.paper=SHADOW")
        val m = TradeManager.perArm(old)
        for (arm in TradeManager.ORB_ARMS) assertEquals(TradeManager.Mode.OFF, m.mode(arm, P), arm)
        assertEquals(TradeManager.Mode.OFF, m.mode("orb_fresh", L))
        assertEquals(TradeManager.Mode.SHADOW, m.mode("solo", P))
        // An arm's own stored choice is kept.
        val mine = TradeManager.perArm(TradeManager.decode("orb.paper=OFF;orb_fresh.paper=SHADOW"))
        assertEquals(TradeManager.Mode.SHADOW, mine.mode("orb_fresh", P))
        assertEquals(TradeManager.Mode.OFF, mine.mode("range_fade", P))
        // Nothing stored for "orb": nothing changes.
        assertEquals(TradeManager.Policy(), TradeManager.perArm(TradeManager.Policy()))
        // Old records move to their arm's family.
        assertEquals("orb_fresh:ORB Fresh", TradeManager.perArmId("orb:ORB Fresh"))
        assertEquals("range_fade:Range Fade", TradeManager.perArmId("orb:Range Fade"))
        assertEquals("orb:ORB", TradeManager.perArmId("orb:ORB"))
        assertEquals("pine:1", TradeManager.perArmId("pine:1"))
    }

    // ---- the research committee in the Chair ----------------------------------------------------------------------------------

    private val cfg = R12Defaults.configs.getValue("orb_fresh")

    @Test fun noProgressExitsThroughThePriceSpecialist() {
        val t = fresh()
        val bars = (50..69).map { bar(it, 102.9, 101.9, 102.4) }
        // Column 69 has not closed: no exit yet - only the chandelier lock, best 102.9 less 3 x the 1.0 range = 99.9.
        val early = ManagerTeam.decide(t, TradeManager.Snapshot(at(69, 30), 102.4, minuteBars = bars), ManagerTeam.start(t), cfg)
        assertEquals(99.9, assertIs<TradeManager.Decision.Trail>(early.decision).newStop, 1e-9)
        val s = ManagerTeam.decide(t, TradeManager.Snapshot(at(70, 5), 102.4, minuteBars = bars), early.state, cfg)
        val d = assertIs<TradeManager.Decision.ExitEarly>(s.decision)
        assertEquals(TradeManager.Rule.TEAM, d.rule)
        assertEquals("price", s.lead?.specialist)
        assertTrue("no progress: 20 minutes in" in d.reason, d.reason)
        // PB: a close at 88 exits too.
        val pb = ManagerTeam.decide(t, TradeManager.Snapshot(at(53, 1), 88.0, minuteBars = listOf(bar(50, 101.0, 99.0, 100.0), bar(52, 90.0, 87.5, 88.0))),
            ManagerTeam.start(t), cfg)
        assertTrue("closed 12% or more under" in assertIs<TradeManager.Decision.ExitEarly>(pb.decision).reason)
    }

    @Test fun theValueAreaRuleReadsTheMinuteBeforeTheEntryFromTheDaysMinutes() {
        // Review G4 M4: Hero's MP (1 minute): the index was above the prior day's value area at the minute before the entry
        // (column 49) and closed back inside at the entry's minute (50). R12 exits on column 50's close - also on the first
        // look, and after a restart (no minutes kept).
        val hero = R12Defaults.configs.getValue("hero")
        val t = fresh().copy(strategyId = "hero:Hero")
        fun m(col: Int, c: Double) = Auction.Minute(at(col) / 1000, c, c, c, c, 0, 0, 100, c * 100, c * c * 100)
        val minutes = R12Rules.indexMinutes(listOf(m(48, 25_010.0), m(49, 25_012.0), m(50, 24_990.0)))
        assertEquals(3, minutes.size)
        assertEquals(at(49), minutes[1].startMs)
        assertEquals((25_010.0 + 25_012.0) / 2, minutes[1].vwap!!, 1e-6)
        val s = TradeManager.Snapshot(at(51, 2), 101.0, futPrice = 24_990.0, priorValueHigh = 25_000.0, priorValueLow = 24_900.0, indexMinutes = minutes)
        for (st in listOf(ManagerTeam.start(t), ManagerTeam.resume(TradeManager.start(t)))) {
            val r = ManagerTeam.decide(t, s, st, hero)
            val mp = r.votes.first { it.specialist == "profile" }
            assertEquals(ManagerTeam.Kind.EXIT, mp.kind, mp.reason)
            assertEquals("r12-mp", mp.tag)
        }
        // Without the day's minutes (as before): the minute before the entry is never seen, and MP stays silent.
        val blind = ManagerTeam.decide(t, s.copy(indexMinutes = emptyList()), ManagerTeam.start(t), hero)
        assertEquals(ManagerTeam.Kind.HOLD, blind.votes.first { it.specialist == "profile" }.kind)
    }

    @Test fun theLiveOnlySpecialistsOnlyRecordUnderTheResearchCommittee() {
        val t = fresh()
        val news = MarketBrain.Context(windows = listOf(MarketBrain.Window("RBI policy", at(55), at(65))))
        val s = TradeManager.Snapshot(at(55, 10), 104.0, brain = news)
        // The original committee: the news veto exits at once.
        assertIs<TradeManager.Decision.ExitEarly>(ManagerTeam.decide(t, s, ManagerTeam.start(t), ManagerConfig("orb_fresh")).decision)
        // The research committee: recorded on the board (News votes EXIT with its veto), decides nothing.
        val r = ManagerTeam.decide(t, s, ManagerTeam.start(t), cfg)
        assertIs<TradeManager.Decision.Hold>(r.decision)
        assertTrue(r.votes.any { it.specialist == "news" && it.kind == ManagerTeam.Kind.EXIT && it.veto })
        // A muted member (Boss's choice) stops counting too.
        val bars = (50..69).map { bar(it, 102.9, 101.9, 102.4) }
        val muted = cfg.with("price") { it.copy(enabled = false) }
        assertIs<TradeManager.Decision.Hold>(ManagerTeam.decide(t, TradeManager.Snapshot(at(70, 5), 102.4, minuteBars = bars), ManagerTeam.start(t), muted).decision)
    }

    @Test fun theVolatilityLockRisesNeverFallsAndNeverUnderTheStop() {
        val pre = (40..49).map { bar(it, 101.0, 99.0, 100.0) }
        val bars = pre + listOf(bar(50, 101.0, 99.0, 100.5), bar(51, 104.0, 100.0, 103.5), bar(52, 106.0, 103.0, 105.0),
            bar(53, 107.3, 104.1, 106.9), bar(54, 105.0, 101.0, 102.0))
        val t = fresh()
        var st = ManagerTeam.start(t)
        val want = mapOf(52 to 98.0, 53 to 99.4, 54 to 100.35)
        for ((c, lock) in want) {
            val s = ManagerTeam.decide(t, TradeManager.Snapshot(at(c, 2), 105.0, minuteBars = bars), st, cfg)
            assertEquals(lock, assertIs<TradeManager.Decision.Trail>(s.decision).newStop, 1e-9, "column $c")
            assertEquals("price", s.lead?.specialist)
            st = s.state
        }
        // Column 54's own proposal (100.0) is lower: the lock stays at 100.35.
        val s55 = ManagerTeam.decide(t, TradeManager.Snapshot(at(55, 2), 104.0, minuteBars = bars), st, cfg)
        assertIs<TradeManager.Decision.Hold>(s55.decision)
        assertEquals(100.35, s55.state.core.lock!!, 1e-9)
        // The premium through the lock: out on the lock.
        val hit = ManagerTeam.decide(t, TradeManager.Snapshot(at(55, 20), 100.3, minuteBars = bars), s55.state, cfg)
        assertEquals(TradeManager.Rule.LOCK, assertIs<TradeManager.Decision.ExitEarly>(hit.decision).rule)
        // Never under the stop already there: with a stop at 99.5, the 98.0 and 99.4 locks are not taken.
        val high = fresh(stop = 99.5)
        val a = ManagerTeam.decide(high, TradeManager.Snapshot(at(53, 2), 105.0, minuteBars = bars), ManagerTeam.start(high), cfg)
        assertIs<TradeManager.Decision.Hold>(a.decision)
        assertNull(a.state.core.lock)
        // And no half-profit trail without an extension.
        val b = ManagerTeam.decide(t, TradeManager.Snapshot(at(56, 2), 103.0, premiumHigh = 130.0, minuteBars = bars), s55.state, cfg)
        assertEquals(100.35, b.state.core.lock!!, 1e-9)
    }

    // ---- words -------------------------------------------------------------------------------------------------------------

    @Test fun theVerdictLinesAndJarvis() {
        val lines = TradeManager.researchLines()
        assertTrue(lines.any { it == "ORB Fresh: research-proven to cut losses (+₹14,365 on 474 holdout trades); the arm still loses overall." }, lines.toString())
        assertTrue("Liquidity: research says keep the original exits; recording only." in lines)
        assertTrue(lines.any { it.startsWith("Solo: R12: not tested") })
        val ask = TradeManager.asked("what did the research say about the trade manager?")
        assertNotNull(ask); assertTrue(ask.research)
        val a = TradeManager.answerResearch(TradeManager.Policy())
        assertTrue("ORB Fresh: research-proven" in a && "Liquidity: research says keep the original exits" in a, a)
        assertTrue("ORB Fresh: paper acting, live shadow (records only)" in a && "Night (R3): paper off" in a, a)
        assertEquals("TradeManager", CoverageTest().feature("what did the research say about the trade manager?"))
        assertFalse(Bundle.acts("what did the research say about the trade manager?"))
        // Solo's and Pine's record line carries the caveat.
        val r = TradeManager.Record(fresh().copy(strategyId = "solo", label = "Solo", tradeId = "s1"), TradeManager.Mode.ACT)
        val line = TradeManager.recordLines(listOf(r)).single()
        assertTrue(line.startsWith("Solo paper: 1 trade") && line.endsWith(TradeManager.SOLO_PINE_NOTE), line)
    }
}
