package com.optionslab.ira

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Solo learning from the market itself (Boss, 3 Oct: "learn every second from the market instead of a strategy made in
 * advance"). No setup is designed beforehand: every minute it reads what the market is doing (the last 1-60 minutes'
 * moves, where the price sits in the day, the time, how busy the tape is), guesses whether the next [H] minutes go up,
 * and [H] minutes later learns from what really happened - an online logistic model, updated one minute at a time,
 * day after day. It trades only when it is confident AND its own recent guesses (made before it knew the answer) have
 * been right often enough. Minutes are the finest the phone keeps; ticks would mostly teach it noise. Pure.
 */
class Learner(private val cfg: Cfg = Cfg()) {
    data class Cfg(
        /** Minutes ahead it predicts (and holds a trade at most). */
        val horizon: Int = 15,
        /** How sure it must be: |p - 0.5| at least this. */
        val edge: Double = 0.10,
        /** Its own record must be at least this right, over at least [minScored] confident guesses. */
        val minHit: Double = 0.55,
        val minScored: Int = 200,
        /** The record is its last this many confident guesses. */
        val window: Int = 400,
        val rate: Double = 0.02,
        val l2: Double = 1e-4,
    )

    companion object {
        const val DIM = 12
        const val FIRST = 30          // the first minute it reads (the opening half hour is only learned from)
    }

    /** The model: weights, running feature means and spreads, the minute volatility it scales moves by. */
    val w = DoubleArray(DIM + 1)
    private val mean = DoubleArray(DIM)
    private val m2 = DoubleArray(DIM)
    var seen = 0L; private set
    /** Average size of a one-minute move (log), learned as it goes. */
    var vol = 0.0005; private set
    /** Its last confident guesses: right or wrong (scored only once the answer was known). */
    private val record = ArrayDeque<Boolean>()

    val scored: Int get() = record.size
    val hitRate: Double get() = if (record.isEmpty()) 0.0 else record.count { it }.toDouble() / record.size
    val ready: Boolean get() = record.size >= cfg.minScored && hitRate >= cfg.minHit

    /** What it reads at minute [m] of [day] (0 = 09:15); null before [FIRST]. */
    fun features(day: List<Candle>, m: Int, prevClose: Double?): DoubleArray? {
        if (m < FIRST || m >= day.size) return null
        val c = day[m].c
        fun ret(k: Int) = ln(c / day[maxOf(0, m - k)].c) / (vol * sqrt(k.toDouble()))
        val upTo = day.subList(0, m + 1)
        val hi = upTo.maxOf { it.h }; val lo = upTo.minOf { it.l }
        val last15 = day.subList(m - 14, m + 1)
        val range15 = (last15.maxOf { it.h } - last15.minOf { it.l }) / c / (vol * 15)
        val ups = last15.count { it.c > it.o } / 15.0 - 0.5
        val t = m / 375.0
        return doubleArrayOf(
            ret(1), ret(5), ret(15), ret(30), ret(minOf(60, m)),
            if (hi > lo) (c - lo) / (hi - lo) - 0.5 else 0.0,
            ln(c / day[0].o) / (vol * sqrt(m + 1.0)),
            prevClose?.let { ln(c / it) / (vol * sqrt(m + 1.0)) } ?: 0.0,
            t, t * t, range15, ups,
        )
    }

    private fun z(x: DoubleArray): DoubleArray = DoubleArray(DIM) { i ->
        val sd = if (seen > 1) sqrt(m2[i] / (seen - 1)) else 1.0
        ((x[i] - mean[i]) / (if (sd > 1e-9) sd else 1.0)).coerceIn(-5.0, 5.0)
    }

    /** The chance (0..1) that the next [Cfg.horizon] minutes go up. */
    fun p(x: DoubleArray): Double {
        val zx = z(x)
        var s = w[DIM]
        for (i in 0 until DIM) s += w[i] * zx[i]
        return 1 / (1 + exp(-s.coerceIn(-30.0, 30.0)))
    }

    /** Learns from one answer: [x] read [Cfg.horizon] minutes ago, [up] what happened; [guess] what it said then. */
    fun learn(x: DoubleArray, up: Boolean, guess: Double) {
        if (abs(guess - 0.5) >= cfg.edge) {
            record.addLast((guess > 0.5) == up)
            while (record.size > cfg.window) record.removeFirst()
        }
        val zx = z(x)
        val err = (if (up) 1.0 else 0.0) - p(x)
        val lr = cfg.rate / sqrt(1.0 + seen / 5_000.0)
        for (i in 0 until DIM) w[i] += lr * (err * zx[i] - cfg.l2 * w[i])
        w[DIM] += lr * err
        // The running mean and spread of each feature (Welford), so every reading is on one scale.
        seen++
        for (i in 0 until DIM) { val d = x[i] - mean[i]; mean[i] += d / seen; m2[i] += d * (x[i] - mean[i]) }
    }

    /** One minute's move, to keep the volatility scale current. */
    fun tick(prev: Candle, now: Candle) {
        val r = abs(ln(now.c / prev.c))
        if (r.isFinite()) vol = 0.995 * vol + 0.005 * maxOf(r, 1e-5)
    }

    /** The model as text, kept on the phone so the learning carries on from day to day. */
    fun save(): String = listOf("L1", seen.toString(), vol.toString(), w.joinToString(","), mean.joinToString(","), m2.joinToString(","),
        record.joinToString("") { if (it) "1" else "0" }).joinToString("|")

    /** Back from [save] (false, and the model untouched, when the text is not one). */
    fun load(text: String): Boolean = runCatching {
        val p = text.split("|")
        require(p.size == 7 && p[0] == "L1")
        val ws = p[3].split(",").map { it.toDouble() }; val ms = p[4].split(",").map { it.toDouble() }; val vs = p[5].split(",").map { it.toDouble() }
        require(ws.size == DIM + 1 && ms.size == DIM && vs.size == DIM && ws.all { it.isFinite() })
        seen = p[1].toLong(); vol = p[2].toDouble()
        ws.forEachIndexed { i, x -> w[i] = x }; ms.forEachIndexed { i, x -> mean[i] = x }; vs.forEachIndexed { i, x -> m2[i] = x }
        record.clear(); p[6].forEach { record.addLast(it == '1') }
        true
    }.getOrDefault(false)

    /** In words: "Nifty: 312 guesses scored, 54% right lately; ready to trade" (or still learning). */
    fun say(label: String): String = if (record.isEmpty()) "$label: still learning (no confident guess scored yet)"
        else "$label: %.0f%% of its last %d confident guesses were right - %s".format(java.util.Locale.ENGLISH, hitRate * 100, record.size,
            if (ready) "trading on paper when it is sure" else "watching until its record is good enough")

    /** The trade now, if any: true = buy a call, false = a put, null = none. */
    fun decide(p: Double): Boolean? = if (!ready || abs(p - 0.5) < cfg.edge) null else p > 0.5

    // ---- the test over history: the same learner, minute by minute, with real option prices -------------------------

    data class Report(val trades: List<Solo.Trade>, val days: Int, val hitRate: Double, val scored: Int) {
        val net get() = trades.sumOf { it.net }
        val wins get() = trades.count { it.net > 0 }
    }

    /**
     * Plays the learner over [days] in order: it reads, guesses and learns every minute; it trades (1 lot, the nearest
     * strike, one at a time) when it decides to, out after [Cfg.horizon] minutes, on a 30% stop on the premium, or at
     * 15:10. Nothing ahead of the minute is ever read: the answer to a guess is learned only once that minute is reached.
     */
    fun backtest(days: Sequence<Solo.HistDay>, step: Int, lot: Int, costs: Double = 60.0, slip: Double = 0.5, stop: Double = 0.30): Report {
        val out = ArrayList<Solo.Trade>()
        var prevClose: Double? = null
        var n = 0
        for (d in days) {
            n++
            val ix = d.index
            val pending = ArrayDeque<Triple<Int, DoubleArray, Double>>()
            var busyUntil = -1
            for (m in 1 until minOf(ix.size, 375)) {
                tick(ix[m - 1], ix[m])
                // Answers now known: learned.
                while (pending.isNotEmpty() && pending.first().first + cfg.horizon <= m) {
                    val (m0, x, g) = pending.removeFirst()
                    learn(x, ix[m0 + cfg.horizon].c > ix[m0].c, g)
                }
                val x = features(ix, m, prevClose) ?: continue
                if (m + cfg.horizon >= ix.size) continue
                val g = p(x)
                pending.addLast(Triple(m, x, g))
                if (m <= busyUntil || m + 1 >= Solo.LAST_ENTRY) continue
                if (d.date == d.expiry && m >= 315) continue
                val call = decide(g) ?: continue
                val want = Solo.strike(ix[m].c, step, call)
                val k = if (d.options.containsKey(want to call)) want
                    else d.options.keys.filter { it.second == call }.minByOrNull { abs(it.first - want) }?.first ?: continue
                val leg = d.options[k to call] ?: continue
                val inBar = leg.getOrNull(m + 1) ?: continue
                val ep = inBar.o + slip
                var x2 = minOf(m + 1 + cfg.horizon, Solo.CUT); var how = Solo.Exit.TIME
                for (j in m + 1..x2) {
                    val b = leg.getOrNull(j) ?: continue
                    if (b.l <= ep * (1 - stop)) { x2 = j; how = Solo.Exit.PREMIUM_STOP; break }
                }
                val xp = if (how == Solo.Exit.PREMIUM_STOP) ep * (1 - stop) - slip
                    else ((x2 downTo m + 1).firstNotNullOfOrNull { leg.getOrNull(it) }?.c ?: ep) - slip
                val net = (xp - ep) * lot - costs
                out += Solo.Trade(d.date, call, k, m + 1, x2, how, ep, xp, net, (xp - ep) / ep)
                busyUntil = x2
            }
            prevClose = ix.last().c
        }
        return Report(out, n, hitRate, scored)
    }
}
