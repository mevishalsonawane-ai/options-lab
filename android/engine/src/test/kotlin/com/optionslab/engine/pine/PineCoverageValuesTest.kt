package com.optionslab.engine.pine

import java.time.LocalDateTime
import kotlin.math.E
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** math.*, na handling, colours, text, time and the built-in values. */
class PineCoverageValuesTest {
    private val H = "indicator(\"v\")\n"
    private fun v(expr: String, pre: String = "", b: List<Pine.Bar> = PC.bars(1.0), interval: String = "5m") = PC.value(expr, b, pre, interval)
    /** 1 when the condition holds on the last bar, else 0. */
    private fun yes(cond: String, pre: String = "", b: List<Pine.Bar> = PC.bars(1.0), interval: String = "5m") =
        assertEquals(1.0, v("($cond) ? 1 : 0", pre, b, interval), cond)
    private fun ist(t: Long) = LocalDateTime.ofEpochSecond(t, 0, java.time.ZoneOffset.ofHoursMinutes(5, 30))

    @Test fun mathFunctions() {
        assertEquals(2.5, v("math.abs(-2.5)")); assertEquals(-3.0, v("math.floor(-2.5)")); assertEquals(-2.0, v("math.ceil(-2.5)"))
        assertEquals(3.0, v("math.sqrt(9)")); assertEquals(1.0, v("math.log(math.e)")); assertEquals(2.0, v("math.log10(100)"))
        assertEquals(E, v("math.exp(1)")); assertEquals(-1.0, v("math.sign(-4)")); assertEquals(0.0, v("math.sign(0)"))
        assertEquals(8.0, v("math.pow(2, 3)"))
        assertEquals(3.0, v("math.round(2.5)")); assertEquals(2.35, v("math.round(2.346, 2)")); assertEquals(2.0, v("math.round(2.4, na)"))
        assertEquals(1.234567890123, v("math.round(1.234567890123456, 20)"))    // at most 12 places
        assertEquals(2.0, v("math.round(2.4, -3)"))
        PC.near(Double.NaN, v("math.round(na)"))
        PC.near(Double.NaN, v("math.sqrt(-1)"))                              // not a number becomes na
        PC.near(Double.NaN, v("math.log(0)"))                                 // -infinity becomes na
        assertEquals(PI, v("math.pi")); assertEquals(E, v("math.e")); assertEquals(1.618033988749895, v("math.phi"))
    }

    @Test fun variadicMathFunctions() {
        assertEquals(5.0, v("math.max(1, 5, 3)")); assertEquals(1.0, v("math.min(4, 1, 3)")); assertEquals(3.0, v("math.avg(1, 5, 3)"))
        PC.near(Double.NaN, v("math.max(1, na)")); PC.near(Double.NaN, v("math.min(na, 1)"))
        assertEquals(1.0, v("math.max(true, 0)"))                             // a bool counts as 1
    }

    @Test fun naHandling() {
        assertEquals(0.0, v("nz(na)")); assertEquals(5.0, v("nz(na, 5)")); assertEquals(3.0, v("nz(3, 5)"))
        assertEquals(0.0, v("nz(0 / 0)"))
        yes("na(na)"); yes("na(1 / 0)"); yes("not na(1)"); yes("na(x)", "x = close > 5 ? 1 : na")
        assertEquals(listOf(1.0, 1.0, 3.0), PC.plot("${H}x = bar_index == 1 ? na : close\nplot(fixnan(x))", PC.bars(1.0, 2.0, 3.0)).toList())
        PC.near(Double.NaN, PC.plot("${H}plot(fixnan(na))", PC.bars(1.0))[0])
    }

    @Test fun conversions() {
        assertEquals(2.0, v("int(2.7)")); assertEquals(-2.0, v("int(-2.7)")); PC.near(Double.NaN, v("int(na)"))
        assertEquals(2.5, v("float(2.5)")); assertEquals(1.0, v("float(true)"))
        yes("bool(1)"); yes("not bool(0)"); yes("not bool(na)")
    }

    @Test fun colours() {
        yes("color.new(color.red, 50) == \"#F236457F\"")
        yes("color.new(#112233, 0) == \"#112233FF\"")
        yes("color.new(#11223344, 100) == \"#11223300\"")
        yes("color.new(color.red, 500) == \"#F2364500\"")                   // clamped to 100
        yes("na(color.new(na, 50))")
        yes("color.rgb(255, 0, 300) == \"#FF00FF\"")
        yes("color.rgb(-5, 16, 32, 50) == \"#001020\"")
        yes("color.from_gradient(10, 0, 100, color.red, color.green) == color.red")
        yes("color.from_gradient(90, 0, 100, color.red, color.green) == color.green")
        yes("color.from_gradient(na, 0, 100, color.red, color.green) == color.red")
        yes("color.from_gradient(5, 1, 1, color.red, color.green) == color.red")
    }

    @Test fun textFunctions() {
        yes("str.tostring(1.5) == \"1.5\""); yes("str.tostring(2) == \"2\""); yes("str.tostring(1.23456) == \"1.2346\"")
        yes("str.tostring(na) == \"NaN\""); yes("str.tostring(true) == \"true\"")
        yes("str.tostring(1e20) == \"100000000000000000000\"")
        yes("str.tostring(3.14159, \"#.##\") == \"3.14\""); yes("str.tostring(3.14159, format.mintick) == \"3.14\"")
        yes("str.tostring(3.14159, \"#\") == \"3\"")
        yes("str.tostring(\"abc\", \"#.##\") == \"abc\"")
        yes("str.format(\"{0} and {1,number,#.#} {0}\", 1, \"b\") == \"1 and b 1\"")
        yes("str.format(\"{0}\") == \"{0}\"")
        yes("str.format(\"{0}{0}\", \"\$1\") == \"\$1\$1\"")          // replacement text is not a regex group reference
        yes("str.format(na) == \"\"")
        // Text joined with + (numbers and bools become text; na makes it na).
        yes("\"a\" + 1 == \"a1\""); yes("1.5 + \"b\" == \"1.5b\""); yes("\"x\" + true == \"xtrue\""); yes("na(\"a\" + na)")
        // Text compares alphabetically.
        yes("\"abc\" < \"abd\""); yes("\"b\" > \"a\""); yes("\"a\" <= \"a\""); yes("\"a\" >= \"a\""); yes("not (\"b\" < \"a\")")
    }

    @Test fun optionalDigitsInANumberFormatAreDropped() {
        yes("str.tostring(3.1, \"#.##\") == \"3.1\"")
        yes("str.tostring(3, \"#.##\") == \"3\"")
        yes("str.tostring(3.1, \"0.00\") == \"3.10\"")
    }

    @Test fun strFormatAppliesNumberPatterns() {
        yes("str.format(\"{0,number,#.#}\", 1.234) == \"1.2\"")
    }

    @Test fun timeFunctions() {
        val b = PC.bars(1.0, 2.0, 3.0)                  // 09:15, 09:20, 09:25 IST on a Tuesday
        assertEquals((PC.T0 + 600) * 1000.0, v("time", b = b))
        assertEquals((PC.T0 + 600) * 1000.0, v("time(\"1\")", b = b))                   // a lower timeframe: the bar's own time
        assertEquals((PC.T0 + 600) * 1000.0, v("time(\"\")", b = b))
        val midnight = PC.T0 - (9 * 3600 + 15 * 60)
        assertEquals(midnight * 1000.0, v("time(\"D\")", b = b))
        assertEquals((PC.T0 - 86400 - (9 * 3600 + 15 * 60)) * 1000.0, v("time(\"W\")", b = b))  // the Monday
        assertEquals((midnight - 21 * 86400) * 1000.0, v("time(\"M\")", b = b))                  // the 1st
        assertEquals(PC.T0 * 1000.0, v("time(\"15\")", b = b))                            // 15-minute candles from 09:15
        assertEquals((PC.T0 + 600) * 1000.0, v("time(\"10\")", b = b))                  // 09:25 opens the second 10-minute candle
        // Sessions: in or out, with days (1 = Sunday ... 7 = Saturday; Tuesday is 3) and overnight.
        assertEquals((PC.T0 + 600) * 1000.0, v("time(timeframe.period, \"0915-0930\")", b = b))
        PC.near(Double.NaN, v("time(timeframe.period, \"0920-0925\")", b = b))
        assertEquals((PC.T0 + 600) * 1000.0, v("time(timeframe.period, \"0900-1000:3\")", b = b))
        PC.near(Double.NaN, v("time(timeframe.period, \"0900-1000:2\")", b = b))
        assertEquals((PC.T0 + 600) * 1000.0, v("time(timeframe.period, \"2200-1000\")", b = b))
        PC.near(Double.NaN, v("time(timeframe.period, \"2200-0900\")", b = b))
        assertEquals((PC.T0 + 600) * 1000.0, v("time(timeframe.period, \"any\")", b = b))   // unreadable: open all day
        assertEquals((PC.T0 + 600) * 1000.0, v("time(timeframe.period, \"\")", b = b))
    }

    @Test fun timeframeChangeAndSeconds() {
        val b = listOf(PC.bar(0, 1.0, 1.0, 1.0, 1.0), PC.bar(1, 1.0, 1.0, 1.0, 1.0), Pine.Bar(PC.T0 + 86400, 1.0, 1.0, 1.0, 1.0, 1.0))
        assertEquals(listOf(0.0, 0.0, 1.0), PC.plot("${H}plot(timeframe.change(\"D\") ? 1 : 0)", b).toList())
        assertEquals(listOf(0.0, 1.0, 1.0), PC.plot("${H}plot(timeframe.change(\"5\") ? 1 : 0)", b).toList())
        assertEquals(0.0, PC.plot("${H}plot(timeframe.change(na) ? 1 : 0)", b)[2])
        assertEquals(3600.0, v("timeframe.in_seconds(\"60\")")); assertEquals(86400.0, v("timeframe.in_seconds(\"D\")"))
        assertEquals(300.0, v("timeframe.in_seconds()")); assertEquals(3600.0, v("timeframe.in_seconds()", interval = "1h"))
        assertEquals(300.0, v("timeframe.in_seconds(\"\")"))
        assertEquals(1.0, v("timeframe.in_seconds(\"1S\")")); assertEquals(30.0, v("timeframe.in_seconds(\"30s\")"))
        assertEquals(604800.0 * 2, v("timeframe.in_seconds(\"2W\")")); assertEquals(2592000.0, v("timeframe.in_seconds(\"M\")"))
        assertEquals(7200.0, v("timeframe.in_seconds(\"2H\")")); assertEquals(86400.0 * 3, v("timeframe.in_seconds(\"3d\")"))
        assertEquals(300.0, v("timeframe.in_seconds(\"5 minutes\")"))        // unreadable: 5 minutes
        assertEquals(300.0, v("timeframe.in_seconds(\"5x\")"))               // unknown unit: minutes
        assertEquals(60.0, v("timeframe.in_seconds(\"0\")"))                 // at least 1
        assertEquals(60.0, v("timeframe.in_seconds(\"99999999999999999999\")"))
    }

    @Test fun timestamps() {
        val want = LocalDateTime.of(2026, 9, 22, 9, 15).atZone(Pine.IST).toEpochSecond() * 1000.0
        assertEquals(want, v("timestamp(\"2026-09-22 09:15\")"))
        assertEquals(want, v("timestamp(\"2026-09-22T09:15:00\")"))
        assertEquals(want, v("timestamp(2026, 9, 22, 9, 15)"))
        assertEquals(want + 30_000, v("timestamp(2026, 9, 22, 9, 15, 30)"))
        assertEquals(want - (9 * 3600 + 15 * 60) * 1000.0, v("timestamp(2026, 9, 22)"))
        PC.near(Double.NaN, v("timestamp(2026, 13, 1)"))
        PC.near(Double.NaN, v("timestamp(\"not a date\")"))
        PC.near(Double.NaN, v("timestamp(2026, 9)"))
        // A timezone first is not read (it is text): the numbers after it still count.
        assertEquals(want, v("timestamp(\"GMT+5:30\", 2026, 9, 22, 9, 15)"))
    }

    @Test fun calendarValues() {
        val b = PC.bars(1.0, 2.0)                           // 2026-09-22 09:20 on the last bar
        assertEquals(2026.0, v("year", b = b)); assertEquals(9.0, v("month", b = b)); assertEquals(22.0, v("dayofmonth", b = b))
        assertEquals(3.0, v("dayofweek", b = b)); yes("dayofweek == dayofweek.tuesday", b = b)
        assertEquals(9.0, v("hour", b = b)); assertEquals(20.0, v("minute", b = b)); assertEquals(0.0, v("second", b = b))
        assertEquals(39.0, v("weekofyear", b = b))
        assertEquals((PC.T0 + 300 + 300) * 1000.0, v("time_close", b = b))
        assertEquals((PC.T0 + 300) * 1000.0, v("last_bar_time", b = PC.bars(1.0, 2.0)))
        assertEquals((PC.T0 - (9 * 3600 + 15 * 60)) * 1000.0, v("time_tradingday", b = b))
        assertEquals(1.0, v("last_bar_index", b = b)); assertEquals(1.0, v("bar_index", b = b))
        val now = System.currentTimeMillis()
        assertTrue(v("timenow") >= now - 60_000)
    }

    @Test fun barStates() {
        val b = PC.bars(1.0, 2.0, 3.0)
        assertEquals(listOf(1.0, 0.0, 0.0), PC.plot("${H}plot(barstate.isfirst ? 1 : 0)", b).toList())
        assertEquals(listOf(0.0, 0.0, 1.0), PC.plot("${H}plot(barstate.islast ? 1 : 0)", b).toList())
        assertEquals(listOf(0.0, 0.0, 1.0), PC.plot("${H}plot(barstate.islastconfirmedhistory ? 1 : 0)", b).toList())
        for (s in listOf("barstate.ishistory", "barstate.isnew", "barstate.isconfirmed")) yes(s, b = b)
        yes("not barstate.isrealtime", b = b)
    }

    @Test fun symbolInfo() {
        val r = Pine.run(PC.ok("${H}plot(syminfo.ticker == \"BANKNIFTY\" and syminfo.root == \"BANKNIFTY\" and syminfo.description == \"BANKNIFTY\" and " +
            "syminfo.tickerid == \"NSE:BANKNIFTY\" and syminfo.prefix == \"NSE\" and syminfo.timezone == \"Asia/Kolkata\" and syminfo.currency == \"INR\" and " +
            "syminfo.type == \"index\" and syminfo.session == \"regular\" and syminfo.pointvalue == 1 ? syminfo.mintick : -1)"),
            PC.bars(1.0), symbol = "BANKNIFTY", mintick = 0.25)
        assertEquals(0.25, r.plots[0][0])
    }

    @Test fun timeframeValuesForEachChartInterval() {
        fun tf(interval: String): List<Any> {
            val b = PC.bars(1.0)
            val period = listOf("1", "5", "60", "D", "W", "M").first { v("timeframe.period == \"$it\" ? 1 : 0", b = b, interval = interval) == 1.0 }
            return listOf(period, v("timeframe.multiplier", b = b, interval = interval)) +
                listOf("isintraday", "isdaily", "isweekly", "ismonthly", "isminutes", "isseconds", "isdwm").map { v("timeframe.$it ? 1 : 0", b = b, interval = interval) }
        }
        assertEquals(listOf("5", 5.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0), tf("5m"))
        assertEquals(listOf("60", 60.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0), tf("1h"))
        assertEquals(listOf("D", 1.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0), tf("1D"))
        assertEquals(listOf("W", 1.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0), tf("1W"))
        assertEquals(listOf("M", 1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 1.0), tf("1M"))
        assertEquals(listOf("5", 5.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0), tf("garbage!"))       // unreadable: 5 minutes
        assertEquals(listOf("1", 1.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0), tf("1"))
    }

    @Test fun constantsAndStylingNames() {
        yes("color.red == \"#F23645\""); yes("strategy.long == \"long\""); yes("dayofweek.sunday == 1")
        // A styling constant is its own name.
        yes("shape.triangleup == \"shape.triangleup\""); yes("plot.style_line == \"plot.style_line\"")
        // strategy.* values in an indicator: constants keep their value, the rest are na.
        yes("strategy.commission.percent == \"percent\"", pre = ""); yes("na(strategy.position_size)")
    }

    @Test fun runtimeError() {
        PC.runError("${H}if bar_index == 1\n    runtime.error(\"boom \" + str.tostring(close))\nplot(close)", PC.bars(1.0, 2.0), "Bar 2: boom 2")
        PC.runError("${H}runtime.error(na)\nplot(close)", PC.bars(1.0), "Bar 1: runtime.error")
    }
}
