package com.optionslab.engine.orb

import java.time.LocalDate
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 6 Nov 2026 (Boss: "R72 picker (shadow log)"): the phone's R72 picker is held to research R151's own code
 * (research/hunt/r151_donchian_winrate60/study.py `features` and the frozen R72-20-4-M2 model) on the fixtures
 * export_picker.py writes from it: three real days of Apr - Sep 2026, every R72 break of each (71), with every input the
 * phone's function gets. Each of the 28 features and the score must be Python's.
 */
class R72PickerParityTest {
    private val days = listOf("2026-04-07", "2026-06-24", "2026-09-01")

    @Suppress("UNCHECKED_CAST")
    private fun load(day: String): Map<String, Any?> = MiniJson.parse(
        GZIPInputStream(javaClass.getResourceAsStream("/r72picker/$day.json.gz")!!).bufferedReader().readText()) as Map<String, Any?>

    private fun d(v: Any?): Double = (v as Number?)?.toDouble() ?: Double.NaN

    @Suppress("UNCHECKED_CAST")
    private fun minutes(date: LocalDate, rows: List<Any?>?): PassedArms.DayBars? {
        if (rows == null) return null
        val n = PassedArms.Session.N
        val o = DoubleArray(n) { Double.NaN }; val h = o.copyOf(); val l = o.copyOf(); val c = o.copyOf()
        rows.forEachIndexed { r, x ->
            val v = x as List<Any?>? ?: return@forEachIndexed
            o[r] = d(v[0]); h[r] = d(v[1]); l[r] = d(v[2]); c[r] = d(v[3])
        }
        return PassedArms.DayBars(date, o, h, l, c)
    }

    @Suppress("UNCHECKED_CAST")
    private fun daily(rows: Any?): List<R72Picker.Daily> =
        (rows as List<List<Any?>>).map { R72Picker.Daily(LocalDate.parse(it[0] as String), d(it[1]), d(it[2])) }

    @Suppress("UNCHECKED_CAST")
    private fun inputs(day: String): List<Triple<R72Picker.Input, Map<String, Any?>, Double>> {
        val fx = load(day)
        val date = LocalDate.parse(day)
        val sy = fx["symbols"] as Map<String, Map<String, Any?>>
        val syms = R72Picker.INDICES.filter { it in sy }.associateWith { u ->
            val m = sy.getValue(u)
            R72Picker.Sym((m["hist5"] as List<List<Any?>>).map { r ->
                PassedArms.Bar5(LocalDate.parse(r[0] as String), (r[1] as Number).toInt(), d(r[2]), d(r[3]), d(r[4]), d(r[5]))
            }, minutes(date, m["today"] as List<Any?>?), daily(m["daily"]), d(m["today_daily_open"]))
        }
        val v = sy.getValue("INDIAVIX")
        val vixPrev = (v["prev_closes"] as List<List<Any?>>).last()[1].let { d(it) }
        return (fx["candidates"] as List<Map<String, Any?>>).map { c ->
            val inp = R72Picker.Input(c["und"] as String, date, (c["er"] as Number).toInt(), if (c["side"] == "C") 1 else -1,
                (c["earlier"] as Number).toInt(), (c["f_count"] as Number).toInt(), syms, minutes(date, v["today"] as List<Any?>?),
                vixPrev, daily(v["daily"]))
            Triple(inp, c["features"] as Map<String, Any?>, d(c["score"]))
        }
    }

    @Test fun everyFeatureAndScoreIsPythonsOnThreeRealDays() {
        var n = 0
        for (day in days) for ((inp, want, score) in inputs(day)) {
            val got = R72Picker.features(inp)
            R72Picker.FEATS.forEachIndexed { i, name ->
                val w = d(want[name])
                val tag = "$day ${inp.und} ${inp.er} ${inp.side} $name"
                if (w.isNaN()) assertTrue(got[i].isNaN(), "$tag: Python has none, got ${got[i]}")
                else assertEquals(w, got[i], 1e-9 * maxOf(1.0, kotlin.math.abs(w)), tag)
            }
            assertEquals(score, R72Picker.score(got), 1e-9, "$day ${inp.und} ${inp.er} score")
            n++
        }
        assertEquals(71, n)
    }

    /**
     * The look-ahead wall: every index's and VIX's minutes from the entry row on scrambled (the fixtures hold the whole day), the
     * features and the score do not change.
     */
    @Test fun nothingFromTheEntryMinuteOnIsRead() {
        val rnd = java.util.Random(151)
        for (day in days) for ((inp, _, _) in inputs(day)) {
            fun scramble(d: PassedArms.DayBars?): PassedArms.DayBars? = d?.let { x ->
                fun cut(a: DoubleArray) = DoubleArray(a.size) { r -> if (r < inp.er) a[r] else 1e5 * (1 + rnd.nextDouble()) }
                PassedArms.DayBars(x.date, cut(x.o), cut(x.h), cut(x.l), cut(x.c))
            }
            val later = R72Picker.Input(inp.und, inp.date, inp.er, inp.side, inp.earlier, inp.fCount,
                inp.syms.mapValues { (_, y) -> R72Picker.Sym(y.hist5, scramble(y.today), y.daily, y.todayOpen) }, scramble(inp.vixToday),
                inp.vixPrevClose, inp.vixDaily)
            val a = R72Picker.features(inp); val b = R72Picker.features(later)
            for (i in a.indices) assertTrue((a[i].isNaN() && b[i].isNaN()) || a[i] == b[i], "$day ${inp.und} ${inp.er} ${R72Picker.FEATS[i]}")
            assertEquals(R72Picker.score(a), R72Picker.score(b))
        }
    }

    /** Both decisions appear on these days, so the threshold is exercised either way. */
    @Test fun theDaysHoldBothDecisions() {
        val s = days.flatMap { inputs(it) }.map { it.third }
        assertTrue(s.any { R72Picker.takes(it) } && s.any { !R72Picker.takes(it) })
    }
}
