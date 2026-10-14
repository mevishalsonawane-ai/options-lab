package com.optionslab.app.data

import com.optionslab.engine.Right
import java.time.LocalDate

/**
 * One index's nearest-expiry option chain as the app ALREADY holds it in memory (14 Oct 2026; for Jarvis's morning gamma
 * line, [GammaMorningHost], and the OI-wall shadow log, [OiWallShadowHost]): the price stream's last tick of each option
 * (with its OI and the day's volume), else the last chain any part of the app read ([Market.chainInMemory]: the Options tab,
 * the gamma regime's 5-minute refresh), with how old each price is. The same sources, in the same order, as the live
 * recorder's minute rows ([LiveRecorder]). Nothing is fetched: no network, no settings vault. It reads the day's option
 * list ([Broker.cachedInstruments], kept in memory once read), so it is called from workers only - never the main thread,
 * the order path or the tick path.
 */
object MemoryChain {
    /**
     * One option: its token when the day's list has it, last price, how old it is (ms), OI and the day's volume (units).
     * [fromTick]: from the price stream (its age is the last trade's; its volume the exchange's day total), else from the
     * last chain read (aged from the read; a candle chain's volume is only the minutes read).
     */
    data class Leg(val strike: Double, val right: String, val token: Long?, val ltp: Double, val ageMs: Long, val oi: Long?, val cumVol: Long?,
                   val fromTick: Boolean = false)

    /** The chain: spot and how old it is, the expiry, the lot, and the options at most [each] strikes either side. */
    data class Snap(val index: String, val spot: Double?, val spotAgeMs: Long?, val expiry: LocalDate?, val lot: Int?, val legs: List<Leg>)

    /** The day's option list by index, nearest expiry only (rebuilt when the list or the day changes). */
    @Volatile private var memo: Triple<Any, LocalDate, Map<String, List<com.optionslab.engine.Kite.Instrument>>>? = null

    private fun nearest(index: String, day: LocalDate): List<com.optionslab.engine.Kite.Instrument> {
        val all = runCatching { Broker.cachedInstruments() }.getOrNull() ?: return emptyList()
        val m = memo?.takeIf { it.first === all && it.second == day } ?: Triple<Any, LocalDate, Map<String, List<com.optionslab.engine.Kite.Instrument>>>(all, day,
            all.filter { !it.expiry.isBefore(day) && (it.right == Right.CE || it.right == Right.PE) }.groupBy { it.name }
                .mapValues { (_, l) -> l.minOf { it.expiry }.let { e -> l.filter { it.expiry == e } } }).also { memo = it }
        return m.third[index].orEmpty()
    }

    /** [index]'s chain at [nowMs] from memory; [each] strikes either side of spot (12: a little wider than the lake's 10). */
    fun read(index: String, nowMs: Long = System.currentTimeMillis(), each: Int = 12): Snap {
        val day = runCatching { Market.today() }.getOrElse { LocalDate.now(com.optionslab.ira.MarketRecord.IST) }
        val spotSeen = Broker.indexToken(index)?.let { KiteStream.seen(it) }?.takeIf { it.tick.last > 0 }
        val ins = nearest(index, day)
        val mem = runCatching { Market.chainInMemory(index) }.getOrNull()
        val expiry = ins.firstOrNull()?.expiry ?: mem?.second?.expiry?.takeIf { !it.isBefore(day) }
        val spot = spotSeen?.tick?.last ?: mem?.second?.spot?.takeIf { it > 0 }
        val spotAge = spotSeen?.let { (nowMs - it.at).coerceAtLeast(0) } ?: mem?.first?.let { (nowMs - it).coerceAtLeast(0) }
        val lot = ins.firstOrNull()?.lotSize?.takeIf { it > 0 } ?: mem?.second?.lotSize?.takeIf { it > 0 }
        if (spot == null || expiry == null) return Snap(index, spot, spotAge, expiry, lot, emptyList())
        val legs = ArrayList<Leg>()
        val seen = HashSet<Pair<Double, String>>()
        // The stream's ticks first: the last trade's own time when the packet has it, else when the tick reached the phone.
        val strikes = com.optionslab.ira.MarketRecord.around(ins.map { it.strike }, spot, each).toSet()
        for (o in ins) if (o.strike in strikes) KiteStream.seen(o.token)?.takeIf { it.tick.last > 0 }?.let { s ->
            val traded = s.tick.lastTradeTime?.takeIf { it > 0 }?.let { it * 1000 } ?: s.at
            legs += Leg(o.strike, o.right.name, o.token, s.tick.last, (nowMs - traded).coerceAtLeast(0), s.tick.oi.takeIf { it > 0 },
                s.tick.volume.takeIf { it > 0 }, fromTick = true)
            seen += o.strike to o.right.name
        }
        // Then the last chain read (the same expiry only), aged from when it was read.
        val lc = mem?.second?.takeIf { it.expiry == expiry }
        if (lc != null) {
            val readAt = mem?.first ?: nowMs
            val near = com.optionslab.ira.MarketRecord.around(lc.series.map { it.strike }, spot, each).toSet()
            for (s in lc.series) {
                if (s.size == 0 || s.strike !in near || (s.strike to s.right.name) in seen || (s.right != Right.CE && s.right != Right.PE)) continue
                val i = s.size - 1
                // A quote snapshot (Zerodha) carries the day's volume so far; 1-minute candles carry each minute's: summed.
                val vol = s.volume?.let { v -> if (lc.pricedAt != null) v[i] else v.sum() }?.takeIf { it > 0 }
                val token = ins.firstOrNull { it.strike == s.strike && it.right == s.right }?.token
                legs += Leg(s.strike, s.right.name, token, s.close[i], (nowMs - readAt).coerceAtLeast(0), s.oi[i].takeIf { it > 0 }, vol)
            }
        }
        return Snap(index, spot, spotAge, expiry, lot, legs)
    }
}
