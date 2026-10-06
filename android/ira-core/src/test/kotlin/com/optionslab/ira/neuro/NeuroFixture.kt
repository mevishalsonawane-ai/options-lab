package com.optionslab.ira.neuro

import com.optionslab.engine.IST
import com.optionslab.ira.dhan.DhanApi
import com.optionslab.ira.dhan.Files
import com.optionslab.ira.dhan.Plan
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.util.Random
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * TEST FIXTURE ONLY - a small synthetic Dhan store with planted relations, so the builder's statistics can be checked
 * against a known truth. None of these numbers is market data:
 *
 *  - NIFTY daily: quiet random sessions; on ~12% of sessions a planted gap down of 0.8%, and the session after it closes
 *    up 1.3% or more 80% of the time (GAP_DOWN PRECEDES BIG_UP);
 *  - BANKNIFTY daily random; HDFCBANK = 1.1 x BANKNIFTY + noise (CORRELATES); ICICIBANK follows BANKNIFTY only on its big
 *    days (CONTRIBUTES); YESBANK independent noise (no edge);
 *  - BANKNIFTY and HDFCBANK one-minute candles (Apr-Sep 2026) where BANKNIFTY's return is 0.7 x HDFCBANK's one minute
 *    earlier plus noise (HDFCBANK LEADS BANKNIFTY by 1 minute);
 *  - NIFTY weekly options from Jul 2025: every Tuesday is an expiry (the ATM straddle ends at intrinsic value) and a
 *    5-strikes-out call goes from 1 to 30 at 13:40 (ZERO_TO_HERO, OCCURS_IN expiry day, Tuesday and 13:00-14:30).
 *
 * Every session is a weekday from 2025-01-06 to 2026-10-05; "today" is 2026-10-06. Seeded: always the same store.
 */
class NeuroFixture(val files: Files, seed: Long = 7) {
    companion object {
        val TODAY: LocalDate = LocalDate.of(2026, 10, 6)
        val FIRST: LocalDate = LocalDate.of(2025, 1, 6)
        val LAST: LocalDate = LocalDate.of(2026, 10, 5)
        val MINUTES_FROM: LocalDate = LocalDate.of(2026, 4, 1)
        val MINUTES_TO: LocalDate = LocalDate.of(2026, 9, 30)
        val OPTIONS_FROM: LocalDate = LocalDate.of(2025, 7, 1)

        fun tmp(): Files = Files(kotlin.io.path.createTempDirectory("neuro").toFile())
        fun t(d: LocalDate, minute: Int): Long = d.atTime(LocalTime.of(minute / 60, minute % 60)).atZone(IST).toEpochSecond()
        fun t0(d: LocalDate): Long = d.atStartOfDay(IST).toEpochSecond()
        fun sessions(from: LocalDate, to: LocalDate): List<LocalDate> =
            generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.filter { it.dayOfWeek.value <= 5 }.toList()

        /** The window (on the plan's grid) a day's file belongs to. */
        fun window(d: LocalDate, len: Long, anchor: LocalDate): LocalDate = Plan.windows(d, d, len, anchor).first().first
    }

    private val rnd = Random(seed)
    private fun n() = rnd.nextGaussian()

    val niftyClose = HashMap<LocalDate, Double>()

    fun writeAll(upTo: LocalDate = LAST): NeuroFixture {
        val days = sessions(FIRST, upTo)
        nifty(days)
        bank(days)
        minutes(sessions(MINUTES_FROM, minOf(MINUTES_TO, upTo)))
        options(sessions(OPTIONS_FROM, upTo))
        return this
    }

    private fun writeDaily(group: Plan.Group, sym: String, candles: List<DhanApi.Candle>) {
        for ((w, cs) in candles.groupBy { window(java.time.Instant.ofEpochSecond(it.t).atZone(IST).toLocalDate(), Plan.DAY_WINDOW_DAYS, Plan.DAY_FROM) })
            files.writeText("${group.dir}/${Plan.safe(sym)}/day/$w.csv.gz", files.candlesCsv(cs))
    }

    private fun nifty(days: List<LocalDate>) {
        var c = 20000.0
        var bigNext = false
        val out = ArrayList<DhanApi.Candle>()
        for (d in days) {
            val gapDown = rnd.nextDouble() < 0.12
            val open = if (gapDown) c * (1 - 0.008) else c * (1 + (n() * 0.001).coerceIn(-0.003, 0.003))
            val ret = if (bigNext) 0.013 + abs(n()) * 0.002 else (n() * 0.004).coerceIn(-0.009, 0.009)
            bigNext = gapDown && rnd.nextDouble() < 0.8
            val close = c * (1 + ret)
            val high = max(open, close) * (1 + abs(n()) * 0.002); val low = min(open, close) * (1 - abs(n()) * 0.002)
            out += DhanApi.Candle(t0(d), open, high, low, close, 0, 0)
            niftyClose[d] = close
            c = close
        }
        writeDaily(Plan.Group.IDX, "NIFTY", out)
    }

    private fun bank(days: List<LocalDate>) {
        var b = 45000.0; var h = 1600.0; var i = 1200.0; var y = 20.0
        val bo = ArrayList<DhanApi.Candle>(); val ho = ArrayList<DhanApi.Candle>(); val io = ArrayList<DhanApi.Candle>(); val yo = ArrayList<DhanApi.Candle>()
        fun bar(d: LocalDate, prev: Double, close: Double) = DhanApi.Candle(t0(d), prev, max(prev, close) * 1.001, min(prev, close) * 0.999, close, 0, 0)
        for (d in days) {
            val rb = (n() * 0.008).coerceIn(-0.04, 0.04)
            val rh = 1.1 * rb + n() * 0.002
            val ri = if (abs(rb) >= 0.01) rb + n() * 0.001 else n() * 0.006
            val ry = n() * 0.01
            bo += bar(d, b, b * (1 + rb)); b *= 1 + rb
            ho += bar(d, h, h * (1 + rh)); h *= 1 + rh
            io += bar(d, i, i * (1 + ri)); i *= 1 + ri
            yo += bar(d, y, y * (1 + ry)); y *= 1 + ry
        }
        writeDaily(Plan.Group.IDX, "BANKNIFTY", bo)
        writeDaily(Plan.Group.EQ, "HDFCBANK", ho)
        writeDaily(Plan.Group.EQ, "ICICIBANK", io)
        writeDaily(Plan.Group.EQ, "YESBANK", yo)
    }

    private fun minutes(days: List<LocalDate>) {
        val bank = ArrayList<DhanApi.Candle>(); val hdfc = ArrayList<DhanApi.Candle>()
        for (d in days) {
            var pb = 45000.0; var ph = 1600.0
            var lastH = 0.0
            for (s in 0 until 375) {
                val rh = n() * 0.0005
                val rb = 0.7 * lastH + n() * 0.0004
                lastH = rh
                ph *= 1 + rh; pb *= 1 + rb
                val tt = t(d, 555 + s)
                hdfc += DhanApi.Candle(tt, ph, ph, ph, ph, 0, 0)
                bank += DhanApi.Candle(tt, pb, pb, pb, pb, 0, 0)
            }
        }
        for ((w, cs) in bank.groupBy { window(java.time.Instant.ofEpochSecond(it.t).atZone(IST).toLocalDate(), Plan.MIN_WINDOW_DAYS, Plan.ANCHOR) })
            files.writeText("idx/BANKNIFTY/min/$w.csv.gz", files.candlesCsv(cs))
        for ((w, cs) in hdfc.groupBy { window(java.time.Instant.ofEpochSecond(it.t).atZone(IST).toLocalDate(), Plan.MIN_WINDOW_DAYS, Plan.ANCHOR) })
            files.writeText("eq/HDFCBANK/min/$w.csv.gz", files.candlesCsv(cs))
    }

    private fun options(days: List<LocalDate>) {
        val rows = HashMap<String, ArrayList<DhanApi.OptionCandle>>()
        fun add(d: LocalDate, file: String, c: DhanApi.OptionCandle) {
            val w = window(d, Plan.OPT_WINDOW_DAYS, Plan.ANCHOR)
            rows.getOrPut("opt/NIFTY/WEEK/$w/$file.csv.gz") { ArrayList() } += c
        }
        for (d in days) {
            val spot = niftyClose[d] ?: continue
            val k = (spot / 50).roundToLong() * 50.0
            val expiry = d.dayOfWeek == DayOfWeek.TUESDAY
            for (m in listOf(555, 600, 700, 800, 860, 900, 925)) {
                val last = m == 925
                val ce = if (expiry && last) max(spot - k, 0.0) + 0.05 else 100.0
                val pe = if (expiry && last) max(k - spot, 0.0) + 0.05 else 100.0
                add(d, "CE+0", DhanApi.OptionCandle(t(d, m), ce, ce, ce, ce, 10, 1_000_000, 12.0, k, spot))
                add(d, "PE+0", DhanApi.OptionCandle(t(d, m), pe, pe, pe, pe, 10, 1_000_000, 12.0, k, spot))
                if (expiry) {
                    val (lo, hi) = if (m < 820) 1.0 to 1.2 else 20.0 to 30.0
                    add(d, "CE+5", DhanApi.OptionCandle(t(d, m), lo, hi, lo, hi, 10, 500_000, 20.0, k + 250, spot))
                }
            }
            if (expiry) add(d, "CE+5", DhanApi.OptionCandle(t(d, 820), 1.1, 30.0, 1.0, 29.0, 10, 500_000, 20.0, k + 250, spot))
        }
        for ((path, cs) in rows) files.writeText(path, files.optionsCsv(cs))
    }
}
