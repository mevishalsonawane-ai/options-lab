package com.optionslab.app.data

import com.optionslab.app.ira.IraHub
import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.app.work.DiscoveryWorker
import com.optionslab.engine.IST
import com.optionslab.ira.DiscoveryStats
import com.optionslab.ira.ShadowSim
import com.optionslab.ira.StrategyForge
import com.optionslab.ira.VariantLab
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max

/**
 * The strategy lab in the app ([VariantRunner], [DiscoveryWorker]): the arms' hook is one map write (no settings read); the
 * day's settle follows variants and ideas as virtual trades and never touches the paper book or the broker; nothing runs in
 * market hours; the forge runs only when the worker may; a live change needs the PIN; Jarvis answers the lab's questions.
 */
class VariantRunnerTest : RobolectricTest() {
    private lateinit var day: LocalDate

    private fun at(d: LocalDate, t: LocalTime) { Market.testClock = Clock.fixed(d.atTime(t).atZone(IST).toInstant(), IST) }

    /** Seeded days of a random-walk index with near-the-money options priced from it, ending on [last]. */
    private fun days(u: String, last: LocalDate, n: Int): List<ShadowSim.Day> {
        val r = DiscoveryStats.Rng(u.hashCode().toLong())
        val dates = ArrayList<LocalDate>()
        var d = last
        while (dates.size < n) { if (d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY) dates += d; d = d.minusDays(1) }
        var level = if (u == "BANKNIFTY") 50_000.0 else 24_000.0
        val step = ShadowSim.stepOf(u).toDouble()
        return dates.reversed().map { date ->
            val m = 375
            val mins = IntArray(m) { ShadowSim.OPEN + it }
            val o = DoubleArray(m); val h = DoubleArray(m); val l = DoubleArray(m); val c = DoubleArray(m)
            var x = level
            for (i in 0 until m) { o[i] = x; x *= 1 + 0.0003 * r.nextGaussian(); c[i] = x; h[i] = max(o[i], x) * 1.0001; l[i] = minOf(o[i], x) * 0.9999 }
            level = x
            val ix = ShadowSim.Series(mins, o, h, l, c)
            val atm = floor(o[0] / step + 0.5) * step
            val legs = (-4..4).flatMap { k -> listOf(1, -1).map { right ->
                val strike = atm + k * step
                fun p(s: Double, i: Int) = max(0.05, max(0.0, right * (s - strike)) + s * 0.004 * (0.6 + 0.4 * (m - i) / m) * exp(-kotlin.math.abs(s - strike) / (s * 0.01)))
                val po = DoubleArray(m) { p(o[it], it) }; val pc = DoubleArray(m) { p(c[it], it) }
                val ph = DoubleArray(m) { maxOf(p(h[it], it), p(l[it], it), po[it], pc[it]) }; val pl = DoubleArray(m) { minOf(p(h[it], it), p(l[it], it), po[it], pc[it]) }
                ShadowSim.Leg(strike, right, ShadowSim.Series(mins, po, ph, pl, pc, LongArray(m) { 100_000L + it }))
            } }
            ShadowSim.Day(date, u, ShadowSim.lotOf(u), date.dayOfWeek == DayOfWeek.THURSDAY, ix, legs, level * 1.01, level * 0.99, level)
        }
    }

    @Before fun up() {
        assumeTrue("The strategy lab is in IraAlgo with Jarvis only", VariantRunner.enabled())
        day = AutomationSupport.tradingDayAt(16, 0).toLocalDate()
        at(day, LocalTime.of(16, 0))
        VariantRunner.resetForTest()
        VariantRunner.init(context)
        VariantRunner.testDays = { u, last, n -> days(u, last, n) }
        runBlocking { IraHub.forgetAll() }
    }

    @After fun down() {
        VariantRunner.resetForTest()
        runBlocking { IraHub.forgetAll() }
        IraHub.clearRequests()
        Market.testClock = null
        SecurePrefs.countedThread = null
    }

    @Test fun theArmsHookIsOneMapWriteWithNoSettingsRead() {
        SecurePrefs.readsForTest.set(0)
        SecurePrefs.countedThread = Thread.currentThread()
        repeat(2_000) { VariantRunner.saw("orb", false); VariantRunner.saw("liquidity15", true) }
        SecurePrefs.countedThread = null
        assertEquals("settings reads on the arms' pass", 0L, SecurePrefs.readsForTest.get())
        val runs = VariantRunner.runsOn(day)
        assertEquals(VariantLab.Run.PAPER, runs["orb"]); assertEquals(VariantLab.Run.LIVE, runs["liquidity"])
        assertEquals(VariantLab.Run.OFF, runs["orb_sweep"])
        assertEquals(VariantLab.Run.OFF, VariantRunner.runsOn(day.plusDays(1))["orb"])
    }

    @Test fun theSettleSimulatesWithoutTouchingThePaperBookOrTheBroker() = runBlocking<Unit> {
        val paperBefore = Paper.stateIfLoaded()
        VariantRunner.saw("orb", false)
        val s = VariantRunner.settleIfDue(Market.now())
        assertEquals(day, s.day)
        val b = VariantRunner.variants.value
        assertEquals(day, b.lastDay)
        assertTrue(b.alive("orb").isNotEmpty() || b.variants.any { it.strategy == "orb" })
        assertTrue(b.alive().size <= VariantLab.MAX_TOTAL)
        // Virtual only: the paper book was never even loaded, and the lab's code names no order path at all.
        assertTrue(Paper.stateIfLoaded() === paperBefore)
        val src = listOf(File("src/main/java/com/optionslab/app/data/VariantRunner.kt"), File("app/src/main/java/com/optionslab/app/data/VariantRunner.kt"))
            .firstOrNull { it.isFile }?.readText()
        if (src != null) for (w in listOf("Paper.", "Broker.", "placeOrder", "Kite.", "SecurePrefs", "AppSettings")) assertFalse(w, src.contains(w))
        // Kept in its own folder, never the vault; the same day is not settled twice.
        assertTrue(File(context.noBackupFilesDir, "strategylab/variants.txt").isFile)
        assertEquals("already settled", VariantRunner.settleIfDue(Market.now()).said)
    }

    @Test fun theSettleWaitsOnAHotPhone() = runBlocking<Unit> {
        try {
            com.optionslab.app.work.BackgroundBudget.resetForTest()
            com.optionslab.app.work.BackgroundBudget.testPhone = com.optionslab.ira.BudgetRule.Phone(80, true, 45.0, false)
            assertEquals("battery / heat budget", VariantRunner.settleIfDue(Market.now()).said)
            assertTrue(VariantRunner.variants.value.lastDay != day)
        } finally { com.optionslab.app.work.BackgroundBudget.resetForTest() }
    }

    @Test fun nothingRunsInMarketHours() = runBlocking<Unit> {
        at(day, LocalTime.of(11, 0))
        assertEquals("market hours", VariantRunner.settleIfDue(Market.now()).said)
        assertFalse(DiscoveryWorker.allowed(charging = true, idle = true, minute = 11 * 60, tradingDay = true))
    }

    @Test fun theWorkerRunsTheForgeOnlyWhenChargingAndIdleOrAfterSix() {
        assertFalse(DiscoveryWorker.allowed(charging = false, idle = true, minute = 17 * 60, tradingDay = true))
        assertFalse(DiscoveryWorker.allowed(charging = true, idle = false, minute = 17 * 60, tradingDay = true))
        assertTrue(DiscoveryWorker.allowed(charging = true, idle = true, minute = 17 * 60, tradingDay = true))
        assertTrue(DiscoveryWorker.allowed(charging = false, idle = false, minute = 18 * 60, tradingDay = true))
        assertTrue(DiscoveryWorker.allowed(charging = true, idle = true, minute = 11 * 60, tradingDay = false))
        assertFalse(DiscoveryWorker.allowed(charging = true, idle = true, minute = 15 * 60, tradingDay = true))
        // The worker itself: always a success, never a retry storm.
        val w = androidx.work.testing.TestListenableWorkerBuilder.from(context, DiscoveryWorker::class.java).build()
        assertEquals(androidx.work.ListenableWorker.Result.success(), runBlocking { w.doWork() })
    }

    @Test fun theForgeRunsOnceANightWithinItsCaps() = runBlocking<Unit> {
        at(day, LocalTime.of(19, 0))
        val n = VariantRunner.forgeIfDue(day)!!
        assertTrue(n.made.size in 1..StrategyForge.PER_NIGHT)
        assertEquals(n.made.size, VariantRunner.forge.value.tried)
        assertTrue(VariantRunner.forge.value.forward.size <= StrategyForge.MAX_FORWARD)
        assertNull(VariantRunner.forgeIfDue(day))
        val f = File(context.noBackupFilesDir, "strategylab/forge.txt")
        assertTrue(f.isFile && f.length() <= StrategyForge.MAX_BYTES)
        // A stop from the worker: nothing more is made.
        VariantRunner.resetForTest(); VariantRunner.testDays = { u, last, k -> days(u, last, k) }
        val stopped = VariantRunner.forgeIfDue(day) { true }!!
        assertEquals(0, stopped.made.size)
    }

    @Test fun aLiveChangeNeedsThePinAndAPaperIdeaIsPaperOnly() {
        val s = VariantLab.strategy("liquidity")!!
        val c = VariantLab.Change("C1", "liquidity", "V1", VariantLab.ChangeKind.LIVE_PROPOSAL, VariantLab.ChangeStatus.OFFERED, day, s.defaults,
            s.defaults + ("stopPct" to 0.125), "it made more")
        val v = VariantLab.Variant("V1", "liquidity", c.to, c.from, day, 1.0, VariantLab.Status.PROPOSED)
        VariantRunner.setForTest(VariantLab.Book(variants = listOf(v), changes = listOf(c), tried = 1), StrategyForge.Book())
        assertEquals(VariantRunner.PIN_NEEDED, VariantRunner.acceptLive("C1", pinConfirmed = false))
        assertEquals(s.defaults, VariantRunner.variants.value.currentOf("liquidity"))
        assertTrue(VariantRunner.acceptLive("C1", pinConfirmed = true).startsWith("Applied with your PIN"))
        assertEquals(0.125, VariantRunner.variants.value.currentOf("liquidity").getValue("stopPct"), 1e-12)
        val idea = StrategyForge.Record(StrategyForge.Candidate(3, day, "BANKNIFTY",
            ShadowSim.Rules(ShadowSim.Entry.ORB_BREAK, stopPct = 0.2), StrategyForge.ExitBlock.FIXED), StrategyForge.Stage.PROPOSED, null)
        VariantRunner.setForTest(VariantLab.Book(), StrategyForge.Book(records = listOf(idea)))
        assertTrue("never a live order" in VariantRunner.approvePaper("F3") || "Live stays yours" in VariantRunner.approvePaper("F3"))
        assertEquals(StrategyForge.Stage.PAPER, VariantRunner.forge.value.find("F3")!!.stage)
    }

    @Test fun theArmsReadTheirParametersFromMemoryAndAPaperChangeStartsTheNextSession() {
        // S1: a paper change decided after today's close is traded from the next session, never today; the arms' reads are memory only.
        val s = VariantLab.strategy("orb_sweep")!!
        val to = s.defaults + ("stopPts" to 30.0)
        val next = VariantRunner.fromAfter(day, Market.now())
        assertTrue(next.isAfter(day))
        val c = VariantLab.Change("C1", "orb_sweep", "V1", VariantLab.ChangeKind.AUTO_PAPER, VariantLab.ChangeStatus.APPLIED, day, s.defaults, to,
            "it made more", effective = next)
        VariantRunner.setForTest(VariantLab.Book(changes = listOf(c), tunes = listOf(VariantLab.Tune("orb_sweep", false, next, to, "C1"))), StrategyForge.Book())
        SecurePrefs.readsForTest.set(0)
        SecurePrefs.countedThread = Thread.currentThread()
        repeat(5_000) {
            VariantRunner.orbParams(com.optionslab.engine.orb.SweepRules.ARM, next, false)
            VariantRunner.liquidityParams(next, true); VariantRunner.vixParams(next)
        }
        SecurePrefs.countedThread = null
        assertEquals("settings reads on the arms' parameter reads", 0L, SecurePrefs.readsForTest.get())
        assertEquals(com.optionslab.engine.orb.OrbParams.SWEEP, VariantRunner.orbParams(com.optionslab.engine.orb.SweepRules.ARM, day, false))
        assertEquals(30.0, VariantRunner.orbParams(com.optionslab.engine.orb.SweepRules.ARM, next, false).stopPoints, 1e-12)
        assertEquals("a paper change never reaches the live account", com.optionslab.engine.orb.OrbParams.SWEEP,
            VariantRunner.orbParams(com.optionslab.engine.orb.SweepRules.ARM, next, true))
        assertEquals(com.optionslab.engine.orb.OrbParams.ORB, VariantRunner.orbParams(com.optionslab.engine.orb.OrbRules.ORB, next, false))
        // Undo (paper, no PIN): from the next session too.
        assertTrue(VariantRunner.undo("C1").startsWith("Undone"))
        assertEquals(com.optionslab.engine.orb.OrbParams.SWEEP, VariantRunner.orbParams(com.optionslab.engine.orb.SweepRules.ARM, next, false))
    }

    @Test fun aLiveChangeIsTradedFromTheNextSessionAndItsUndoToTheDefaultsNeedsNoPin() {
        val s = VariantLab.strategy("liquidity")!!
        val c = VariantLab.Change("C1", "liquidity", "V1", VariantLab.ChangeKind.LIVE_PROPOSAL, VariantLab.ChangeStatus.OFFERED, day, s.defaults,
            s.defaults + ("stopPct" to 0.125), "it made more")
        val v = VariantLab.Variant("V1", "liquidity", c.to, c.from, day, 1.0, VariantLab.Status.PROPOSED)
        VariantRunner.setForTest(VariantLab.Book(variants = listOf(v), changes = listOf(c), tried = 1), StrategyForge.Book())
        assertEquals(VariantRunner.PIN_NEEDED, VariantRunner.acceptLive("C1", pinConfirmed = false))
        assertTrue(VariantRunner.acceptLive("C1", pinConfirmed = true).startsWith("Applied with your PIN"))
        val next = VariantRunner.fromAfter(day, Market.now())
        assertEquals("never mid-day", com.optionslab.engine.orb.LiquidityParams.DEFAULT, VariantRunner.liquidityParams(day, true))
        assertEquals(0.125, VariantRunner.liquidityParams(next, true).stopPct, 1e-12)
        assertEquals(0.125, VariantRunner.liquidityParams(next, false).stopPct, 1e-12)
        // Back to the defaults lowers risk: no PIN.
        assertTrue(!VariantRunner.undoLiveNeedsPin("C1"))
        assertTrue(VariantRunner.undoLive("C1", pinConfirmed = false).startsWith("Undone"))
        assertEquals(VariantLab.ChangeStatus.UNDONE, VariantRunner.variants.value.change("C1")!!.status)
    }

    @Test fun jarvisAnswersTheLabsQuestions() {
        IraHub.init(context); IraHub.awaitLoadedBlocking()
        fun ask(q: String): String { IraHub.ask(q); return IraHub.state.value.messages.last().text }
        assertTrue(ask("how are my strategies improving?").startsWith("No strategy is running"))
        assertTrue(ask("did you find any new strategy?").startsWith("Not yet"))
        assertTrue(ask("how many ideas have you tried?").startsWith("0 new ideas tried"))
        assertTrue(ask("what is candidate F12?").startsWith("I have no candidate called F12"))
        assertTrue(ask("what have you learned from the new strategies?").startsWith("Nothing yet from the new strategies"))
        assertTrue(ask("which ideas keep failing?").startsWith("Too few tries"))
        assertTrue(ask("what are you exploring next?").startsWith("Next I lean toward"))
    }
}
