package com.optionslab.ira

import com.optionslab.engine.orb.ArmsBacktest
import com.optionslab.engine.orb.Bar
import com.optionslab.engine.orb.LiquidityParams
import com.optionslab.engine.orb.LiquidityRules
import com.optionslab.engine.orb.OrbParams
import com.optionslab.engine.orb.OrbRules
import com.optionslab.engine.orb.ProfitLock
import com.optionslab.engine.orb.VixDivParams
import com.optionslab.engine.orb.VixDivRules
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The lab replays the REAL arms (S3): with the CURRENT parameters, each arm's lab replay reproduces the arm's own replay on
 * fixture days trade for trade - the ORB family the engine's own arms replay ([ArmsBacktest]); Liquidity 15+5 and VIX
 * divergence a pass-by-pass driver written the way the app's arms run (OrbArms.liquidityCycle / liquidityExits / priceCheck,
 * VixDivArm.decide / exits: every pass re-reads the minutes, re-folds the chart and re-reads the levels, as the arm does).
 * And the arms' parameter maps: their defaults ARE the arms' constants.
 */
class ArmLabTest {
    private fun key(t: ShadowSim.Trade) = listOf(t.day, t.entryMin, t.exitMin, t.dir, t.entry, t.exit, t.why)

    // ---- defaults -------------------------------------------------------------------------------------------------------

    @Test fun theParameterDefaultsAreTheArmsConstants() {
        for ((k, arm) in ArmParams.ORB_ARMS) {
            assertEquals(OrbParams.of(arm), ArmParams.orb(k, ArmParams.defaults(k)), k)
            assertEquals(OrbParams.of(arm), ArmParams.orb(k, null), k)
        }
        assertEquals(mapOf("until" to 840.0, "or" to 50.0, "buffer" to 0.0, "stopPts" to 40.0, "targetPts" to 40.0, "sideCap" to 0.0), ArmParams.defaults("orb"))
        assertEquals(mapOf("until" to 870.0, "stopPts" to 40.0, "targetPts" to 80.0), ArmParams.defaults("orb_sweep"))
        assertEquals(mapOf("until" to 840.0, "edge" to 0.10, "stopPts" to 40.0, "targetPts" to 40.0), ArmParams.defaults("range_fade"))
        assertEquals(LiquidityParams.DEFAULT, ArmParams.liquidity(ArmParams.defaults("liquidity")))
        assertEquals(LiquidityParams(0.15, LiquidityRules.TIME_STOP_MINUTES, LiquidityRules.TIME_STOP_GAIN, true, 0.0), LiquidityParams.DEFAULT)
        assertEquals(LiquidityRules.PREMIUM_STOP, ArmParams.defaults("liquidity").getValue("stopPct"))
        assertEquals(VixDivParams.DEFAULT, ArmParams.vixDiv(ArmParams.defaults("vix_div")))
        assertEquals(mapOf("move" to VixDivRules.INDEX_MOVE, "stopPct" to VixDivRules.LIQ_STOP, "ts" to VixDivRules.LIQ_MINUTES.toDouble()), ArmParams.defaults("vix_div"))
        assertEquals(emptyMap(), ArmParams.defaults("hero"))
        // Every strategy the lab tunes: its defaults are the arm's, every tunable has one, and each sits inside its bounds.
        for (s in VariantLab.STRATEGIES) {
            assertEquals(ArmParams.defaults(s.key), s.defaults, s.key)
            assertEquals(s.params.map { it.key }.toSet(), s.defaults.keys, s.key)
            for (p in s.params) assertEquals(s.defaults.getValue(p.key), p.clamp(s.defaults.getValue(p.key)), "${s.key} ${p.key}")
            assertTrue(s.key in ArmLab.KEYS)
        }
        // Another value moves only what it names.
        assertEquals(OrbParams.ORB.copy(stopPoints = 25.0, lastEntry = LocalTime.of(13, 30)),
            ArmParams.orb("orb", mapOf("stopPts" to 25.0, "until" to 810.0, "edge" to 0.2, "nonsense" to 1.0)))
        assertEquals(OrbParams.ORB.copy(orEnd = LocalTime.of(9, 45)), ArmParams.orb("orb_fresh", mapOf("or" to 35.0)))
        assertEquals(OrbParams.FADE.copy(edge = 0.15), ArmParams.orb("range_fade", mapOf("edge" to 0.15, "or" to 35.0, "buffer" to 0.001)))
        assertEquals(LiquidityParams(timeStopMinutes = 25, nextLevelTarget = false, lockPct = 0.45),
            ArmParams.liquidity(mapOf("ts" to 25.0, "tgt" to 0.0, "lock" to 0.45)))
        assertEquals(VixDivParams(0.0025, 0.1, 15), ArmParams.vixDiv(mapOf("move" to 0.0025, "stopPct" to 0.1, "ts" to 15.0)))
    }

    // ---- the ORB family ------------------------------------------------------------------------------------------------

    @Test fun theOrbFamilyIsTheEnginesOwnArmsReplay() {
        val days = LabTestData.days(40, 11, edge = 0.0004) + LabTestData.days(20, 12, start = LocalDate.of(2025, 6, 2))
        var n = 0
        for (d in days) {
            val own = ArmsBacktest.day(ArmLab.session(d), slip = 0.0) ?: emptyList()
            for ((k, arm) in ArmParams.ORB_ARMS) {
                val lab = ArmLab.trades(k, ArmParams.defaults(k), d)
                val mine = own.filter { it.arm == arm.source }
                assertEquals(mine.map { listOf(d.date, it.entryTime!!.hour * 60 + it.entryTime!!.minute, it.exitTime!!.hour * 60 + it.exitTime!!.minute,
                    if (it.right == "CE") 1 else -1, it.entry, it.exit, it.why) }, lab.map(::key), "$k ${d.date}")
                lab.forEach { assertEquals(ShadowSim.net(d.underlying, d.lot, it.entry, it.exit), it.net, 1e-9) }
                n += lab.size
            }
        }
        assertTrue(n > 20, "the fixture traded ($n)")
        // A variant is the same rules with other values: a tighter stop changes the record; no options, no trades.
        val tight = days.flatMap { ArmLab.trades("orb", mapOf("stopPts" to 20.0, "targetPts" to 80.0), it) }
        assertTrue(tight.map(::key) != days.flatMap { ArmLab.trades("orb", null, it) }.map(::key))
        assertTrue(LabTestData.days(5, 3, legs = false).all { ArmLab.trades("orb", null, it).isEmpty() })
        assertTrue(ArmLab.trades("hero", null, days.first()).isEmpty())
    }

    // ---- Liquidity 15+5: the arm's passes, written as the app runs them -------------------------------------------------

    private class Pos(val side: Int, val level: Double, val target: Double?, val signalBar: LocalDateTime, val entry: Double,
                      val entryTime: LocalDateTime, val entryMin: Int, val leg: ShadowSim.Series, val base: Double?, val lock: Double?, val cost: Double) {
        var peak = entry; var timed = false
    }

    /** OrbArms.liquidityBars: the closed bars; a bar missing its last minute is held back for two minutes after it ends. */
    private fun chart(ones: List<Bar>, tf: Int, t: LocalDateTime): List<Bar> {
        val have = ones.mapTo(HashSet()) { it.start }
        return LiquidityRules.completed(LiquidityRules.fold(ones, tf), tf, t).filter { b ->
            !t.isBefore(b.start.plusMinutes(tf + 2L)) || b.start.plusMinutes(tf - 1L) in have
        }
    }

    /** One Liquidity book's day, pass by pass, the way OrbArms runs it (paper, automatic, 1 lot). */
    private fun armDay(arm: com.optionslab.engine.orb.Arm, d: ShadowSim.Day, before: List<ShadowSim.Day>, p: LiquidityParams): List<ShadowSim.Trade> {
        val tf = LiquidityRules.minutesOf(arm)
        val und = d.underlying
        val feed = ArmLab.history(d, before).flatMap { ArmLab.ones(it) } + ArmLab.ones(d)
        val out = ArrayList<ShadowSim.Trade>()
        val decided = HashSet<LocalDateTime>()
        var pos: Pos? = null
        fun close(h: Pos, at: Int, px: Double, why: String) {
            out += ShadowSim.Trade(d.date, h.entryMin, at, h.side, h.entry, px, ShadowSim.net(und, d.lot, h.entry, px), why, false)
        }
        for (t in ShadowSim.OPEN + 1..15 * 60 + 30) {
            val now = d.date.atTime(t / 60, t % 60)
            // Paper.tick: the resting stop (the lock moved up into it) fills during the minute just closed, on its low.
            pos?.let { h ->
                val j = h.leg.atOrBefore(t - 1)
                if (j >= 0 && h.leg.minutes[j] == t - 1 && t - 1 >= h.entryMin) {
                    val rest = ProfitLock.restingStop(h.entry, h.base, h.lock, h.peak, h.cost)
                    val hit = rest?.let { ProfitLock.sellStopFill(it, h.leg.o[j], h.leg.l[j]) }
                    if (hit != null) { close(h, t - 1, hit, if (h.base != null && rest <= h.base) "stop" else "profit_lock"); pos = null }
                    else h.peak = maxOf(h.peak, h.leg.h[j])
                }
            }
            // priceCheck's 15:10, then liquidityExits on this pass's minutes (re-read, re-folded, the levels re-read).
            pos?.let { h ->
                val px = ArmLab.priceAt(h.leg, t) ?: h.entry
                if (t >= 15 * 60 + 10) { close(h, t, px, "session_end"); pos = null; return@let }
                val ones = feed.filter { !it.start.plusMinutes(1).isAfter(now) }
                val bars = chart(ones, tf, now)
                val since = ones.filter { !it.start.isBefore(h.entryTime) }
                val fresh = h.leg.atOrBefore(t - 1).takeIf { it >= 0 }?.let { h.leg.c[it] }
                val look = LiquidityRules.exitNow(h.side, h.level, h.target, h.signalBar, h.entry, h.entryTime, now, bars, since, und, fresh, h.timed, p)
                h.timed = look.timed
                look.why?.let { close(h, t, px, it); pos = null }
            }
            // The cycle (inside the arm's window, flat): the newest closed bar, once.
            if (pos != null || t < 9 * 60 + 20 || t > 15 * 60 + 12 || t >= 15 * 60 + 10) continue
            val bars = chart(feed.filter { it.start.isBefore(now) }, tf, now)
            if (bars.size < 2 * LiquidityRules.SWING_LOOKBACK + 2) continue
            val last = bars.last()
            if (last.start.toLocalDate() != d.date) continue
            if (!decided.add(last.start)) continue
            if (!LiquidityRules.mayEnterAt(last.start.plusMinutes(tf.toLong()))) continue
            val s = LiquidityRules.signal(bars, LiquidityRules.zones(bars)) ?: continue
            if (!LiquidityRules.hasRoom(s, last.close, und)) continue
            val strike = LiquidityRules.entryStrike(s.side, last.close, und)
            val leg = d.leg(strike.toDouble(), s.side)?.s ?: continue
            val fill = ArmLab.priceAt(leg, t) ?: continue
            pos = Pos(s.side, s.level, s.target, last.start, fill, now, t, leg, LiquidityRules.stopTrigger(fill, p.stopPct), p.lockTarget(fill),
                ProfitLock.roundTripPerUnit(fill, d.lot))
        }
        pos?.let { h -> close(h, h.leg.minutes.last(), h.leg.c.last(), "last_bar") }
        return out
    }

    private fun armAll(d: ShadowSim.Day, before: List<ShadowSim.Day>, p: LiquidityParams) =
        if (d.expiryDay || !d.optionBars) emptyList()
        else LiquidityRules.BOOKS.filter { LiquidityRules.underlyingOf(it) == d.underlying }.flatMap { armDay(it, d, before, p) }.sortedBy { it.entryMin }

    @Test fun liquidityIsTheArmsOwnPassesTradeForTrade() {
        val days = LabTestData.days(16, 21, sigma = 0.0006)
        val test = days.drop(6).filter { !it.expiryDay }.take(5)
        val variants = listOf(LiquidityParams.DEFAULT, LiquidityParams(stopPct = 0.10, timeStopMinutes = 10, timeStopGain = 0.08),
            LiquidityParams(nextLevelTarget = false, lockPct = 0.3))
        var n = 0
        for (p in variants) for (d in test) {
            val values = mapOf("stopPct" to p.stopPct, "ts" to p.timeStopMinutes.toDouble(), "tsg" to p.timeStopGain,
                "tgt" to if (p.nextLevelTarget) 1.0 else 0.0, "lock" to p.lockPct)
            val lab = ArmLab.trades("liquidity", values, d, days)
            assertEquals(armAll(d, days, p).map(::key), lab.map(::key), "${d.date} $p")
            if (p == LiquidityParams.DEFAULT) n += lab.size
        }
        assertTrue(n >= 3, "the fixture traded Liquidity ($n)")
        // Late prints: a bar whose last minute is missing is held back two minutes, by the lab as by the arm.
        val gappy = test.map { d ->
            val keep = (0 until d.index.size).filter { d.index.minutes[it] % 15 != 14 || d.index.minutes[it] > 13 * 60 }
            val s = d.index
            ShadowSim.Day(d.date, d.underlying, d.lot, d.expiryDay, ShadowSim.Series(keep.map { s.minutes[it] }.toIntArray(),
                keep.map { s.o[it] }.toDoubleArray(), keep.map { s.h[it] }.toDoubleArray(), keep.map { s.l[it] }.toDoubleArray(),
                keep.map { s.c[it] }.toDoubleArray()), d.legs, d.prevHigh, d.prevLow, d.prevClose)
        }
        for (d in gappy) assertEquals(armAll(d, days, LiquidityParams.DEFAULT).map(::key), ArmLab.trades("liquidity", null, d, days).map(::key), "gappy ${d.date}")
        // An expiry day (its next-expiry options are not stored) and a day without options: nothing.
        days.filter { it.expiryDay }.forEach { assertTrue(ArmLab.trades("liquidity", null, it, days).isEmpty()) }
        assertTrue(ArmLab.trades("liquidity", null, LabTestData.days(3, 4, legs = false).last()).isEmpty())
    }

    @Test fun liquidityEntriesAreFrozen() {
        // S9: its variants carry exit keys only, and no value can reach an entry: the levels, the room filter, the strike, the hours.
        val s = VariantLab.strategy("liquidity")!!
        assertEquals(ArmParams.LIQUIDITY_EXIT_KEYS, s.params.map { it.key }.toSet())
        assertEquals(setOf("stopPct", "ts", "tsg", "tgt", "lock"), ArmParams.LIQUIDITY_EXIT_KEYS)
        for (k in listOf("until", "or", "buffer", "room", "itm", "lookback", "edge", "move")) {
            assertTrue(s.invalid(s.defaults + (k to 1.0)) != null, k)
            assertEquals(LiquidityParams.DEFAULT, ArmParams.liquidity(mapOf(k to 1.0)), k)
            assertEquals(s.defaults, s.values(mapOf(k to 1.0)), k)
        }
        // The entry constants, as researched (a change here is a change to the proven edge: never by the lab).
        assertEquals(20, LiquidityRules.SWING_LOOKBACK); assertEquals(2, LiquidityRules.CONTACTS); assertEquals(5, LiquidityRules.GAP)
        assertEquals(10, LiquidityRules.CONFIRM); assertEquals(300, LiquidityRules.MAX_AGE); assertEquals(1.0, LiquidityRules.MIN_ROOM_STOPS)
        assertEquals(1, LiquidityRules.ITM_STEPS); assertEquals(LocalTime.of(9, 20), LiquidityRules.FIRST_ENTRY)
        assertEquals(LocalTime.of(14, 0), LiquidityRules.LAST_ENTRY)
        assertEquals(listOf(30.0, 15.0, 8.0), listOf("BANKNIFTY", "FINNIFTY", "MIDCPNIFTY").map { LiquidityRules.indexStopPoints(it) })
        // Whatever the exits, every entry is one of the arm's own decisions (the decisions do not read the exit parameters).
        val days = LabTestData.days(14, 21, sigma = 0.0006)
        for (d in days.drop(6)) {
            val signals = LiquidityRules.BOOKS.filter { LiquidityRules.underlyingOf(it) == d.underlying }
                .flatMap { ArmLab.book(it, d, days).today.filter { x -> x.signal != null } }.map { it.signal!!.side }.toSet()
            for (v in listOf(mapOf("stopPct" to 0.25, "ts" to 30.0), mapOf("tgt" to 0.0, "lock" to 1.0))) {
                ArmLab.trades("liquidity", v, d, days).forEach { assertTrue(it.dir in signals) }
            }
        }
        // A smaller budget and a stricter bar.
        assertEquals(3, s.maxAlive); assertEquals(VariantLab.ALPHA / 2, s.alpha)
        assertTrue(VariantLab.STRATEGIES.filter { it.key != "liquidity" }.all { it.maxAlive == VariantLab.MAX_PER_STRATEGY && it.alpha == VariantLab.ALPHA })
    }

    // ---- VIX divergence: the arm's passes ----------------------------------------------------------------------------

    private fun vixArm(d: ShadowSim.Day, p: VixDivParams): List<ShadowSim.Trade> {
        if (!d.optionBars) return emptyList()
        val vx = d.vix ?: return emptyList()
        val u = d.underlying
        val index = ArmLab.candles(d.date, d.index); val vix = ArmLab.candles(d.date, vx)
        var decided = false
        var pos: Triple<ShadowSim.Series, Int, Double>? = null
        var side = 0
        val out = ArrayList<ShadowSim.Trade>()
        for (t in ShadowSim.OPEN + 1..15 * 60 + 30) {
            pos?.let { (leg, at, fill) ->
                val j = leg.atOrBefore(t - 1)
                val stop = VixDivRules.stop(fill, p.stopPct)
                if (VixDivRules.liquidityExit(u) && j >= 0 && leg.minutes[j] == t - 1 && t - 1 >= at && leg.l[j] <= stop) {
                    out += ShadowSim.Trade(d.date, at, t - 1, side, fill, minOf(stop, leg.o[j]), ShadowSim.net(u, d.lot, fill, minOf(stop, leg.o[j])), "stop_15", false)
                    pos = null; return@let
                }
                val bars = (0 until leg.size).filter { leg.minutes[it] < t }.map { VixDivRules.Minute(leg.minutes[it], leg.l[it], leg.c[it]) }
                val why = VixDivRules.exit(VixDivRules.Held(u, fill, at), bars, t, p)
                if (why != null) {
                    val px = ArmLab.priceAt(leg, t) ?: fill
                    out += ShadowSim.Trade(d.date, at, t, side, fill, px, ShadowSim.net(u, d.lot, fill, px), why, false); pos = null
                }
            }
            if (decided || t < VixDivRules.CHECKS.first()) continue
            val ix = index.filter { it.istMinute < t }; val vv = vix.filter { it.istMinute < t }
            if (VixDivRules.awaiting(ix, vv, t) != null) continue
            val sig = VixDivRules.readings(ix, vv, t, p.indexMove).firstOrNull { it.side != 0 }
            if (sig == null) { if (VixDivRules.dayOver(t)) decided = true; continue }
            decided = true
            if (!VixDivRules.inEntryWindow(sig, t)) continue
            val leg = d.leg(VixDivRules.strike(sig.indexClose, u, sig.side).toDouble(), sig.side)?.s ?: continue
            val fill = ArmLab.priceAt(leg, t) ?: continue
            pos = Triple(leg, t, fill); side = sig.side
        }
        return out
    }

    @Test fun vixDivergenceIsTheArmsOwnPassesTradeForTrade() {
        val days = LabTestData.days(40, 31, underlying = "NIFTY", vix = true, sigma = 0.0006) +
            LabTestData.days(30, 32, underlying = "BANKNIFTY", vix = true, sigma = 0.0006)
        var n = 0
        for (p in listOf(VixDivParams.DEFAULT, VixDivParams(0.0015, 0.10, 10))) for (d in days) {
            val lab = ArmLab.trades("vix_div", mapOf("move" to p.indexMove, "stopPct" to p.stopPct, "ts" to p.minutes.toDouble()), d)
            assertEquals(vixArm(d, p).map(::key), lab.map(::key), "${d.underlying} ${d.date} $p")
            if (p == VixDivParams.DEFAULT) n += lab.size
        }
        assertTrue(n >= 2, "the fixture traded VIX divergence ($n)")
        assertTrue(days.filter { it.underlying == "BANKNIFTY" }.flatMap { ArmLab.trades("vix_div", null, it) }.none { it.why == "stop_15" })
        assertTrue(ArmLab.trades("vix_div", null, LabTestData.days(2, 1, underlying = "NIFTY").last()).isEmpty(), "no VIX kept: nothing")
    }

    @Test fun aCandleListIsTheSeriesMinuteByMinute() {
        val d = LabTestData.days(1, 2).single()
        val c = ArmLab.candles(d.date, d.index)
        assertEquals(d.index.size, c.size)
        assertEquals(ShadowSim.OPEN, c.first().istMinute); assertEquals(d.date, c.first().istDate)
        assertEquals(d.index.c[10], c[10].close)
        val ones: List<Bar> = ArmLab.ones(d)
        assertEquals(d.date.atTime(9, 15), ones.first().start)
        assertEquals(OrbRules.UNDERLYING, d.underlying)
    }
}
