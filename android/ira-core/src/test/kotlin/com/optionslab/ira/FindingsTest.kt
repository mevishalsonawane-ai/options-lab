package com.optionslab.ira

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The findings bus ([Findings]): post, subscribe, TTL, a bounded ring under many threads, consensus, reactions, the brake. */
class FindingsTest {
    private val t0 = 1_760_000_000_000L
    private fun f(who: String, kind: Findings.Kind, inst: String, dir: Int = 0, strength: Int = 70, at: Long = t0, ttl: Long = Findings.DEFAULT_TTL_MS, words: String = "") =
        Findings.Finding(who, kind, inst, dir, null, strength, at, ttl, emptyMap(), words)

    @Test fun postReadSubscribeAndExpire() {
        val bus = Findings.Bus(capacity = 8)
        val heard = CopyOnWriteArrayList<String>()
        val sub = bus.subscribe(Findings.Sub(setOf("BANKNIFTY"), setOf(Findings.Kind.SWEEP, Findings.Kind.DATA_UNRELIABLE)) { heard += it.who })
        bus.post(f("Liquidity 15+5", Findings.Kind.SWEEP, "BANKNIFTY", 1, words = "sweep taken at 55,100 (pool)").copy(level = 55_100.0, evidence = mapOf("pool" to 55_100.0)))
        bus.post(f("ORB", Findings.Kind.BREAK, "BANKNIFTY", 1))
        bus.post(f("Liquidity 15+5", Findings.Kind.SWEEP, "NIFTY", -1))
        bus.post(f("self-healing", Findings.Kind.DATA_UNRELIABLE, Findings.ALL, ttl = 60_000))
        assertEquals(listOf("Liquidity 15+5", "self-healing"), heard.toList(), "by instrument and kind; market-wide reaches all")
        assertEquals(4, bus.recent(t0).size)
        assertEquals(listOf("self-healing", "ORB", "Liquidity 15+5"), bus.recent(t0, "BANKNIFTY").map { it.who }, "newest first")
        assertEquals(1, bus.recent(t0, kinds = setOf(Findings.Kind.BREAK)).size)
        // TTL: the 1-minute finding is gone after a minute, the others after their 15.
        assertEquals(3, bus.recent(t0 + 61_000).size)
        assertTrue(bus.recent(t0 + Findings.DEFAULT_TTL_MS + 1).isEmpty())
        assertTrue(bus.recent(t0 + 1_000, withinMs = 0).isEmpty(), "posted within the last 0 ms: none")
        bus.unsubscribe(sub)
        bus.post(f("Liquidity 15+5", Findings.Kind.SWEEP, "BANKNIFTY"))
        assertEquals(2, heard.size)
        // The daily log's lines: one a post, drained once, and read back whole.
        val lines = bus.drainLines()
        assertEquals(5, lines.size)
        assertTrue(bus.drainLines().isEmpty())
        val back = Findings.parse(lines[0])!!
        assertEquals("Liquidity 15+5", back.who); assertEquals(55_100.0, back.level); assertEquals(mapOf("pool" to 55_100.0), back.evidence)
        assertEquals("sweep taken at 55;100 (pool)", back.words, "commas kept out of the log's fields")
        assertNull(Findings.parse("X|1"))
        // A subscriber that throws never stops the post.
        bus.subscribe(Findings.Sub(emptySet(), emptySet()) { throw IllegalStateException() })
        bus.post(f("x", Findings.Kind.OTHER, "NIFTY"))
        assertEquals(6, bus.posted)
    }

    @Test fun theRingIsBoundedAndSafeUnderManyThreads() {
        val bus = Findings.Bus(capacity = 64, pendingCap = 100)
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val done = CountDownLatch(8)
        val errors = CopyOnWriteArrayList<Throwable>()
        repeat(8) { w ->
            pool.execute {
                try {
                    start.await()
                    repeat(2_000) { i ->
                        bus.post(f("w$w", Findings.Kind.OTHER, if (i % 2 == 0) "NIFTY" else "BANKNIFTY", if (i % 3 == 0) 1 else -1))
                        if (i % 50 == 0) { val r = bus.recent(t0); check(r.size <= 64); check(r.all { it.seq >= 0 }) }
                    }
                } catch (e: Throwable) { errors += e } finally { done.countDown() }
            }
        }
        start.countDown()
        assertTrue(done.await(30, TimeUnit.SECONDS))
        pool.shutdown()
        assertTrue(errors.isEmpty(), errors.toString())
        assertEquals(16_000L, bus.posted)
        val r = bus.recent(t0)
        assertEquals(64, r.size, "the newest 64 kept")
        assertEquals(r.map { it.seq }.toSet().size, 64)
        assertTrue(r.all { it.seq >= 16_000 - 64 })
        assertTrue(bus.drainLines().size <= 108, "the unwritten lines are bounded too")
    }

    @Test fun consensusCountsAgreeingAndConflictingFindingsInTheWindow() {
        val all = listOf(
            f("Liquidity", Findings.Kind.SWEEP, "BANKNIFTY", 1, 70), f("ORB", Findings.Kind.BREAK, "BANKNIFTY", 1, 60),
            f("order flow", Findings.Kind.ABSORPTION, "BANKNIFTY", 1, 50), f("trap guard", Findings.Kind.PULL, "BANKNIFTY", -1, 80),
            f("old", Findings.Kind.BREAK, "BANKNIFTY", -1, 90, at = t0 - 20 * 60_000L, ttl = 60 * 60_000L),
            f("Nifty's", Findings.Kind.BREAK, "NIFTY", -1), f("stream", Findings.Kind.DATA_HEALTH, "BANKNIFTY", 0),
        )
        val c = Findings.consensus(all, "banknifty", t0)
        assertEquals(3, c.bullish); assertEquals(1, c.bearish); assertEquals(2, c.net); assertEquals(1, c.lean)
        assertEquals("3 bullish, 1 bearish (leaning bullish)", c.words())
        assertEquals("no directional findings", Findings.consensus(all, "FINNIFTY", t0).words())
        assertEquals(2, Findings.consensus(all, "BANKNIFTY", t0, windowMs = 60 * 60_000L).bearish, "a longer window counts the older one")
    }

    @Test fun reactionsOnlyLowerRiskShadowByDefaultAndActWhenSwitched() {
        val stops = listOf(f("ORB", Findings.Kind.STOP_HIT, "BANKNIFTY", at = t0, ttl = 30 * 60_000L),
            f("Liquidity", Findings.Kind.STOP_HIT, "BANKNIFTY", at = t0 + 6 * 60_000L, ttl = 30 * 60_000L))
        val now = t0 + 8 * 60_000L
        val shadow = Findings.react(stops, Findings.Policy(), "pine", "BANKNIFTY", 1, now)
        assertFalse(shadow.block, "SHADOW by default: logged, never acted on")
        assertEquals(Findings.Reaction.STOPS_CLUSTER, shadow.shadow.single().reaction)
        assertTrue(shadow.shadow.single().detail.contains("6 min apart"), shadow.shadow.single().detail)
        val act = Findings.Policy(perStrategy = mapOf("pine" to mapOf(Findings.Reaction.STOPS_CLUSTER to Findings.Mode.ACT)))
        assertTrue(Findings.react(stops, act, "pine", "BANKNIFTY", 1, now).block)
        assertFalse(Findings.react(stops, act, "orb", "BANKNIFTY", 1, now).block, "per strategy")
        assertFalse(Findings.react(stops, act, "pine", "NIFTY", 1, now).block, "that index only")
        assertFalse(Findings.react(stops, act, "pine", "BANKNIFTY", 1, t0 + 6 * 60_000L + Findings.CLUSTER_PAUSE_MS + 1).block, "15 minutes")
        // Two stops of ONE strategy, or 11 minutes apart: no cluster.
        val one = listOf(stops[0], stops[0].copy(atMs = t0 + 60_000L))
        assertTrue(Findings.hits(one, "pine", "BANKNIFTY", 1, now).isEmpty())
        val apart = listOf(stops[0], stops[1].copy(atMs = t0 + 11 * 60_000L))
        assertTrue(Findings.hits(apart, "pine", "BANKNIFTY", 1, t0 + 12 * 60_000L).isEmpty())
        // Strong opposite consensus: three strong bearish findings against a bullish entry (its own never counted).
        val bears = listOf(f("a", Findings.Kind.PULL, "NIFTY", -1, 60), f("b", Findings.Kind.BREAK, "NIFTY", -1, 80),
            f("c", Findings.Kind.STOP_HUNT, "NIFTY", -1, 90), f("weak", Findings.Kind.BREAK, "NIFTY", -1, 40))
        assertEquals(Findings.Reaction.OPPOSITE_CONSENSUS, Findings.hits(bears, "orb", "NIFTY", 1, t0).single().reaction)
        assertTrue(Findings.hits(bears, "orb", "NIFTY", -1, t0).isEmpty(), "agreeing findings never block")
        assertTrue(Findings.hits(bears, "a", "NIFTY", 1, t0).isEmpty(), "its own finding is not counted against it")
        assertTrue(Findings.hits(bears, "orb", "NIFTY", 1, t0 + Findings.OPPOSITE_MS + 1).isEmpty())
        // Data unreliable acts by default, everywhere.
        val data = listOf(f("self-healing", Findings.Kind.DATA_UNRELIABLE, Findings.ALL, ttl = 60_000, words = "stream stale and Zerodha down"))
        val v = Findings.react(data, Findings.Policy(), "orb", "BANKNIFTY", 1, t0 + 1_000)
        assertTrue(v.block); assertTrue(v.why!!.contains("stream stale"))
        assertFalse(Findings.react(data, Findings.Policy(global = mapOf(Findings.Reaction.DATA_UNRELIABLE to Findings.Mode.OFF)), "orb", "BANKNIFTY", 1, t0).block)
        assertFalse(Findings.react(data, Findings.Policy(), "orb", "BANKNIFTY", 1, t0 + 61_000).block, "expired")
    }

    @Test fun thePolicyTextKeepsStrategyKeysWithColons() {
        val p = Findings.Policy(mapOf(Findings.Reaction.DATA_UNRELIABLE to Findings.Mode.SHADOW),
            mapOf("liquidity:BANKNIFTY" to mapOf(Findings.Reaction.STOPS_CLUSTER to Findings.Mode.ACT, Findings.Reaction.OPPOSITE_CONSENSUS to Findings.Mode.ACT)))
        val text = Findings.encode(p)
        assertEquals("*:data=SHADOW;liquidity:BANKNIFTY:stops=ACT,consensus=ACT", text)
        assertEquals(p, Findings.decode(text))
        assertEquals(Findings.Policy(), Findings.decode("nonsense;x:bad=ACT"))
        assertEquals(Findings.Mode.ACT, Findings.Policy().mode("orb", Findings.Reaction.DATA_UNRELIABLE))
        assertEquals(Findings.Mode.SHADOW, Findings.Policy().mode("orb", Findings.Reaction.STOPS_CLUSTER))
    }

    @Test fun theBrakeCombinesTheBrainsPauseAndTheReactionsAndNeverSaysEnter() {
        val day = java.time.LocalDate.of(2026, 12, 4)
        val ctx = MarketBrain.Context(windows = MarketBrain.windows(day, listOf("RBI policy"), mcx = false))
        val rbi = day.atTime(10, 1).atZone(java.time.ZoneId.of("Asia/Kolkata")).toInstant().toEpochMilli()
        // A news window blocks a paper entry and a live one alike (the brake does not even ask which).
        val d = EntryBrake.decide(ctx, MarketBrain.Policy(), emptyList(), Findings.Policy(), "liquidity:BANKNIFTY", "BANKNIFTY", 1, rbi)
        assertTrue(d.block); assertEquals("paused: RBI policy window", d.why)
        // Outside it, shadow notes from both, the consensus for the log, and no block.
        val later = rbi + 30 * 60_000L
        val bears = List(3) { f("w$it", Findings.Kind.BREAK, "BANKNIFTY", -1, 90, at = later) }
        val q = EntryBrake.decide(ctx.copy(traps = mapOf("BANKNIFTY" to setOf("STOP_HUNT"))), MarketBrain.Policy(), bears, Findings.Policy(),
            "orb", "BANKNIFTY", 1, later)
        assertFalse(q.block)
        assertEquals(2, q.shadow.size)
        assertTrue(q.shadow[0].startsWith("brain: trap alert")); assertTrue(q.shadow[1].startsWith("consensus: 3 strong bearish"))
        assertEquals("0 bullish, 3 bearish (leaning bearish)", q.consensus!!.words())
    }

    @Test fun jarvisSaysWhatTheBotsAreSaying() {
        assertEquals("BANKNIFTY", Findings.asked("what are the bots saying about banknifty?"))
        assertEquals("NIFTY", Findings.asked("what do the strategies think about nifty"))
        assertEquals("", Findings.asked("what are the bots seeing in the market"))
        assertNull(Findings.asked("let me see my bots"))
        assertNull(Findings.asked("how are my strategies doing vs backtest"))
        assertNull(Findings.asked("what is banknifty doing"))
        val hhmm = { _: Long -> "10:31" }
        val all = listOf(f("Liquidity 15+5", Findings.Kind.SWEEP, "BANKNIFTY", 1, 70, words = "sweep taken at 55,100 (pool)"),
            f("trap guard", Findings.Kind.PULL, "BANKNIFTY", -1, 70, words = "pull detected"))
        val a = Findings.answer("BANKNIFTY", all, t0, hhmm)
        assertTrue(a.startsWith("On BANKNIFTY the bots say: 1 bullish, 1 bearish (split) in the last 15 minutes."), a)
        assertTrue("• 10:31 Liquidity 15+5: sweep taken at 55,100 (pool) (bullish, strength 70)" in a, a)
        assertTrue("exits never wait" in a)
        assertTrue(Findings.answer("NIFTY", all, t0, hhmm).startsWith("Nothing from the bots on NIFTY"))
    }
}
