package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The forge: its grammar makes only option-buying 1-lot near-the-money rules; its gates reject noise and keep a real planted
 * edge; the random twin, the global BH counter, the caps, the time budget, determinism, speed; the forward test; and the
 * meta-learning (weights move toward what works, the exploration floor holds, the gates never change).
 */
class StrategyForgeTest {
    private val day = LocalDate.of(2026, 10, 11)
    private val noise by lazy { LabTestData.days(140, 1234) }
    private val edged by lazy { LabTestData.days(160, 99, edge = 0.00006) }

    /** The planted edge's own rules: the ORB continuation the data was built with. */
    private val orb = StrategyForge.Candidate(1, day, "BANKNIFTY",
        ShadowSim.Rules(ShadowSim.Entry.ORB_BREAK, barMin = 1, orMin = 45, fromMin = 600, untilMin = 780, stopPct = 0.25, targetPct = 0.5, timeStopMin = 60),
        StrategyForge.ExitBlock.FIXED)

    @Test fun theGrammarMakesOnlyValidOptionBuyingOneLotStrategies() {
        val r = DiscoveryStats.Rng(7)
        val w = StrategyForge.weights(StrategyForge.Book())
        val d = LabTestData.days(3, 5)
        repeat(3_000) { i ->
            val c = StrategyForge.generate(i + 1, day, listOf("BANKNIFTY", "NIFTY"), w, r)
            assertNull(c.rules.invalid(), "${c.name}: ${c.rules.invalid()}")
            assertTrue(c.rules.entry in StrategyForge.ENTRIES)
            assertTrue(c.rules.stopPct != null && c.rules.stopPct!! <= 0.30, "a premium stop, never wider than 30%")
            assertTrue(c.rules.itm <= 1, "at or one strike in the money")
            assertTrue(c.rules.maxTrades <= 2)
            assertTrue(c.rules.filters.size <= 1)
            assertTrue(c.underlying in listOf("BANKNIFTY", "NIFTY"))
        }
        // Every virtual trade is one lot of a bought option: the rupees are (sell - buy) x one lot less the costs.
        val c = StrategyForge.generate(1, day, listOf("BANKNIFTY"), w, DiscoveryStats.Rng(1))
        for (t in d.flatMap { ShadowSim.run(c.rules.copy(filters = emptySet()), it) })
            assertEquals(ShadowSim.net("BANKNIFTY", 30, t.entry, t.exit), t.net, 1e-9)
    }

    @Test fun theGatesRejectNoise() {
        var b = StrategyForge.Book()
        var made = 0
        for (k in 0 until 3) {
            val n = StrategyForge.night(b, day.plusDays(k.toLong()), mapOf("BANKNIFTY" to noise), { 0L })
            b = n.book; made += n.made.size
        }
        assertEquals(60, made)
        assertEquals(60, b.tried)
        val passed = b.records.count { it.bt?.passed == true }
        assertTrue(passed <= 3, "$passed of 60 noise ideas passed (at least 95% must fail)")
        // Every rejection says which gate.
        assertTrue(b.records.filter { it.stage == StrategyForge.Stage.REJECTED }.all { it.why.startsWith("failed:") || it.why.contains("already") })
    }

    @Test fun theGatesKeepAPlantedRealEdge() {
        val bt = StrategyForge.backtest(orb, edged)
        assertTrue(bt.gates.all { it.second }, bt.gates.toString())
        assertTrue(bt.twinP < 0.05 && bt.trades >= StrategyForge.MIN_TRADES && bt.folds.count { it > 0 } >= 2 && bt.maxDD <= StrategyForge.MAX_DD)
        val (b, rec) = StrategyForge.judge(StrategyForge.Book(), orb, bt, edged, day)
        assertEquals(StrategyForge.Stage.FORWARD, rec.stage)
        assertEquals(1, b.tried); assertEquals(1, b.passed)
        // The same rules on noise fail.
        assertTrue(!StrategyForge.backtest(orb, noise).passed)
    }

    @Test fun theRandomTwinIsTheControl() {
        val bt = StrategyForge.backtest(orb, edged)
        assertTrue(bt.twinMean < bt.perTrade, "the twin made ${bt.twinMean} a trade against ${bt.perTrade}")
        val nb = StrategyForge.backtest(orb, noise)
        assertTrue(nb.twinP >= 0.05 || nb.trades < StrategyForge.MIN_TRADES || nb.net <= 0)
    }

    @Test fun theGlobalBhCounterCountsEveryIdeaEverTried() {
        val bt = StrategyForge.backtest(orb, edged)
        // After a thousand tries with nothing in them, the same p no longer passes the false-discovery check.
        val crowded = StrategyForge.Book(tried = 1_000, pvals = List(1_000) { 0.6 })
        val (b, rec) = StrategyForge.judge(crowded, orb, bt.copy(twinP = 0.004), edged, day)
        assertEquals(1_001, b.tried); assertEquals(1_001, b.pvals.size)
        assertEquals(StrategyForge.Stage.REJECTED, rec.stage)
        assertTrue(rec.why.contains("false-discovery"), rec.why)
        // Ideas forgotten from the list still count (as p = 1).
        val (b2, rec2) = StrategyForge.judge(StrategyForge.Book(tried = 5_000), orb, bt.copy(twinP = 0.004), edged, day)
        assertEquals(5_001, b2.tried); assertEquals(StrategyForge.Stage.REJECTED, rec2.stage)
    }

    private fun fake(pass: Boolean) = StrategyForge.Backtest(50, 100, if (pass) 5_000.0 else -1_000.0, 50.0, listOf(1.0, 1.0, 1.0),
        if (pass) 0.0001 else 0.9, 0.0, 100.0, false, listOf("x" to pass))

    @Test fun theCapsAndTheTimeBudget() {
        // At most ten in forward test: a passing idea beyond them waits outside.
        val full = StrategyForge.Book(records = (1..StrategyForge.MAX_FORWARD).map { StrategyForge.Record(orb.copy(n = it), StrategyForge.Stage.FORWARD, null) })
        val (_, rec) = StrategyForge.judge(full, orb.copy(n = 99), fake(true), emptyList(), day)
        assertEquals(StrategyForge.Stage.REJECTED, rec.stage); assertTrue("already in forward test" in rec.why)
        // The budget: a clock that moves 7 s a look stops the night early; nothing cut short is counted.
        var t = 0L
        val n = StrategyForge.night(StrategyForge.Book(), day, mapOf("BANKNIFTY" to noise), { t += 7_000; t })
        assertTrue(n.stoppedEarly && n.made.size < StrategyForge.PER_NIGHT)
        assertEquals(n.made.size, n.book.tried)
        // Cancelled: nothing at all.
        val c = StrategyForge.night(StrategyForge.Book(), day, mapOf("BANKNIFTY" to noise), { 0L }, cancelled = { true })
        assertEquals(0, c.book.tried); assertTrue(c.stoppedEarly)
        // Storage: thousands of old rejections are counted, not kept in full; the file stays under 5 MB.
        val old = StrategyForge.Book(tried = 3_000, rejected = 3_000, pvals = List(3_000) { 0.7 },
            records = (1..3_000).map { StrategyForge.Record(orb.copy(n = it), StrategyForge.Stage.REJECTED, fake(false), why = "failed: x") })
        val kept = StrategyForge.night(old, day, mapOf("BANKNIFTY" to noise), { 0L }, n = 1).book
        assertTrue(kept.records.count { it.stage == StrategyForge.Stage.REJECTED } <= StrategyForge.KEEP_REJECTED)
        assertEquals(3_001, kept.tried)
        assertTrue(StrategyForge.encode(kept).length <= StrategyForge.MAX_BYTES)
    }

    @Test fun aNightIsDeterministicAndReadsBack() {
        val a = StrategyForge.night(StrategyForge.Book(), day, mapOf("BANKNIFTY" to noise), { 0L }, n = 6).book
        val b = StrategyForge.night(StrategyForge.Book(), day, mapOf("BANKNIFTY" to noise), { 0L }, n = 6).book
        assertEquals(StrategyForge.encode(a), StrategyForge.encode(b))
        val back = StrategyForge.decode(StrategyForge.encode(a))
        assertEquals(StrategyForge.encode(a), StrategyForge.encode(back))
        // The same night is not run twice.
        assertEquals(a, StrategyForge.night(a, day, mapOf("BANKNIFTY" to noise), { 0L }).book)
    }

    @Test fun twentyCandidatesOnAYearOfMinutesInUnderAMinute() {
        val year = LabTestData.days(250, 31)
        val t0 = System.nanoTime()
        val n = StrategyForge.night(StrategyForge.Book(), day, mapOf("BANKNIFTY" to year), { (System.nanoTime() - t0) / 1_000_000 }, budgetMs = 120_000)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(StrategyForge.PER_NIGHT, n.made.size)
        assertTrue(ms <= 60_000, "20 candidates on 250 days took $ms ms")
    }

    @Test fun theForwardTestProposesARealEdgeAndRetiresNoise() {
        val start = StrategyForge.Book(tried = 1, records = listOf(StrategyForge.Record(orb, StrategyForge.Stage.FORWARD, null)))
        var b = start
        val proposed = ArrayList<StrategyForge.Record>()
        for (d in LabTestData.days(70, 555, edge = 0.00006, start = LocalDate.of(2026, 1, 5))) {
            val f = StrategyForge.forwardDay(b, d.date, mapOf("BANKNIFTY" to d)); b = f.book
            proposed += f.events.filterIsInstance<StrategyForge.Event.Proposed>().map { it.record }
        }
        val p = proposed.single()
        assertTrue(p.fwdDays >= StrategyForge.FWD_MIN_DAYS && p.fwd.size >= StrategyForge.FWD_MIN_TRADES)
        assertTrue("kept passing" in p.why)
        assertEquals(1, b.proposed)
        // Boss's yes makes it paper - never live (there is no live stage at all).
        val paper = StrategyForge.approve(b, orb.id, day)
        assertEquals(StrategyForge.Stage.PAPER, paper.find(orb.id)!!.stage)
        assertTrue(StrategyForge.Stage.entries.none { it.name.contains("LIVE") })
        // Noise is retired with its record and a reason.
        val nc = orb.copy(n = 2, rules = orb.rules.copy(entry = ShadowSim.Entry.VWAP_RECLAIM, barMin = 5))
        var nb = StrategyForge.Book(tried = 2, records = listOf(StrategyForge.Record(nc, StrategyForge.Stage.FORWARD, null)))
        for (d in LabTestData.days(70, 556, start = LocalDate.of(2026, 1, 5))) nb = StrategyForge.forwardDay(nb, d.date, mapOf("BANKNIFTY" to d)).book
        val r = nb.find("F2")!!
        assertTrue(r.stage == StrategyForge.Stage.RETIRED, "${r.stage}: ${r.why}")
        assertTrue(r.why.startsWith("retired:"))
        assertEquals(1, nb.retired)
        assertEquals(1, nb.blocks.getValue(StrategyForge.key(ShadowSim.Entry.VWAP_RECLAIM)).fwdFailed)
    }

    // ---- meta-learning ----------------------------------------------------------------------------------------------------

    @Test fun blockWeightsMoveTowardBlocksThatSucceed() {
        val truth = StrategyForge.ENTRIES.associateWith { if (it == ShadowSim.Entry.SQUEEZE_BREAK) 0.6 else 0.03 }
        val rng = DiscoveryStats.Rng(2024)
        var b = StrategyForge.Book()
        val late = HashMap<ShadowSim.Entry, Int>()
        repeat(500) { i ->
            val c = StrategyForge.generate(b.tried + 1, day, listOf("BANKNIFTY"), StrategyForge.weights(b), rng)
            val pass = rng.nextDouble() < truth.getValue(c.rules.entry)
            b = StrategyForge.judge(b, c, fake(pass), emptyList(), day).first
            if (i >= 300) late.merge(c.rules.entry, 1, Int::plus)
        }
        val w = StrategyForge.weights(b)
        val best = StrategyForge.ENTRIES.maxBy { StrategyForge.mean(w.getValue(StrategyForge.key(it))) }
        assertEquals(ShadowSim.Entry.SQUEEZE_BREAK, best)
        assertTrue((late[ShadowSim.Entry.SQUEEZE_BREAK] ?: 0) >= 100, "late picks $late")
        // Every try counted in the global counter.
        assertEquals(500, b.tried)
    }

    @Test fun theExplorationFloorHolds() {
        val blocks = StrategyForge.ENTRIES.associate { e ->
            StrategyForge.key(e) to if (e == ShadowSim.Entry.ORB_BREAK) StrategyForge.BlockStat(1_000, 1_000) else StrategyForge.BlockStat(1_000, 0)
        }
        val w = StrategyForge.weights(StrategyForge.Book(blocks = blocks))
        val rng = DiscoveryStats.Rng(1)
        val keys = StrategyForge.ENTRIES.map { StrategyForge.key(it) }
        val n = 9_000
        val counts = HashMap<String, Int>()
        repeat(n) { counts.merge(StrategyForge.pick(keys, w, rng), 1, Int::plus) }
        val floor = n * StrategyForge.EXPLORE / keys.size
        for (k in keys) assertTrue((counts[k] ?: 0) >= floor * 0.7, "$k picked ${counts[k]} (floor ${floor})")
        assertTrue(StrategyForge.EXPLORE >= 0.10)
    }

    @Test fun theGatesAreUnchangedByTheWeights() {
        val learned = StrategyForge.Book(blocks = mapOf(StrategyForge.key(ShadowSim.Entry.ORB_BREAK) to StrategyForge.BlockStat(100, 0, 0, 50)))
        val bt = StrategyForge.backtest(orb, edged)
        val (_, a) = StrategyForge.judge(StrategyForge.Book(), orb, bt, edged, day)
        val (_, b) = StrategyForge.judge(learned, orb, bt, edged, day)
        assertEquals(a.bt!!.gates, b.bt!!.gates)
        assertEquals(a.stage, b.stage)
        // The thresholds themselves.
        assertEquals(40, StrategyForge.MIN_TRADES); assertEquals(0.05, StrategyForge.TWIN_ALPHA); assertEquals(0.10, StrategyForge.Q)
        assertEquals(20, StrategyForge.FWD_MIN_DAYS); assertEquals(30, StrategyForge.FWD_MIN_TRADES)
    }
}
