package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** Made-up minute candles for tests: a seeded walk, weekdays, the market's own session. */
object Fixtures {
    fun c(t: LocalDateTime, o: Double, h: Double, l: Double, cl: Double) = Candle(t, o, h, l, cl)

    /** Candles of [minutes] each from [start], from (open, close) pairs, a point of wick each side. */
    fun bars(start: LocalDateTime, minutes: Long, oc: List<Pair<Double, Double>>, wick: Double = 1.0) =
        oc.mapIndexed { i, (o, cl) -> Candle(start.plusMinutes(minutes * i), o, maxOf(o, cl) + wick, minOf(o, cl) - wick, cl) }

    /** [days] trading days of 1-minute index bars from [from], a seeded walk with [drift] points a minute. */
    fun indexDays(days: Int, from: LocalDate = LocalDate.of(2026, 8, 3), start: Double = 24_000.0, drift: Double = 0.0, step: Double = 4.0, seed: Long = 7): List<Candle> {
        val r = java.util.Random(seed)
        val out = ArrayList<Candle>()
        var px = start; var d = from; var n = 0
        while (n < days) {
            if (d.dayOfWeek.value <= 5) {
                var t = d.atTime(LocalTime.of(9, 15))
                while (t.toLocalTime().isBefore(LocalTime.of(15, 30))) {
                    val o = px; px += drift + r.nextGaussian() * step
                    out += Candle(t, o, maxOf(o, px) + r.nextDouble() * step / 2, minOf(o, px) - r.nextDouble() * step / 2, px)
                    t = t.plusMinutes(1)
                }
                n++
            }
            d = d.plusDays(1)
        }
        return out
    }
}
