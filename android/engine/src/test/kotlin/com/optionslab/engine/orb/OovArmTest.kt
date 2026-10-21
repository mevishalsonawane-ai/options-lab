package com.optionslab.engine.orb

import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * "R52 Open outside value" ([OovArm]): the previous session's TPO value area and the 09:20 signal, each against the
 * research's own code on synthetic sessions (android/tools/passed_arms_fixture.py: research/hunt/h37/sigs.py `profile_levels`
 * and `mp_signals`), then the rule's edges: no levels, a short session, a missing first bar, the fill window, the strike and
 * the Solo layer added as its stop.
 */
class OovArmTest {
    @Suppress("UNCHECKED_CAST")
    private val fx: Map<String, Any?> by lazy {
        MiniJson.parse(javaClass.getResourceAsStream("/passedarms/parity.json")!!.bufferedReader().readText()) as Map<String, Any?>
    }

    private fun arr(x: Any?): DoubleArray = (x as List<*>).map { (it as Double?) ?: Double.NaN }.toDoubleArray()

    @Suppress("UNCHECKED_CAST")
    internal fun fixtureDays(): List<PassedArms.DayBars> = (fx["days"] as List<Map<String, Any?>>).mapIndexed { i, d ->
        PassedArms.DayBars(LocalDate.of(2026, 9, 1).plusDays(i.toLong()), arr(d["o"]), arr(d["h"]), arr(d["l"]), arr(d["c"]))
    }

    @Test fun theValueAreaIsTheResearchsOnEverySession() {
        val days = fixtureDays()
        @Suppress("UNCHECKED_CAST") val want = fx["levels"] as List<List<Double?>>
        for ((i, d) in days.withIndex()) {
            val got = OovArm.valueArea(d)
            if (want[i][0] == null) { assertNull(got, "session $i"); continue }
            assertNotNull(got, "session $i")
            assertEquals(want[i][0]!!, got.poc, 1e-9, "POC $i")
            assertEquals(want[i][1]!!, got.vah, 1e-9, "VAH $i")
            assertEquals(want[i][2]!!, got.val_, 1e-9, "VAL $i")
        }
    }

    @Test fun theOpeningSignalIsTheResearchsOnEverySession() {
        val days = fixtureDays()
        @Suppress("UNCHECKED_CAST") val want = (fx["oov"] as List<Double>).map { it.toInt() }
        assertTrue(want.contains(1) && want.contains(-1), "the fixture has a call day and a put day")
        for (i in days.indices) {
            val lev = if (i == 0) null else OovArm.valueArea(days[i - 1])
            // Decided at 09:20 on the minutes known then (rows 0..4).
            assertEquals(want[i], OovArm.signal(days[i].through(4), lev), "session $i")
        }
    }

    private val day = LocalDate.of(2026, 10, 20)
    private fun session(px: (Int) -> Double, rows: Iterable<Int> = 0 until 375): PassedArms.DayBars {
        val o = DoubleArray(375) { Double.NaN }; val h = o.copyOf(); val l = o.copyOf(); val c = o.copyOf()
        for (r in rows) { val p = px(r); o[r] = p; h[r] = p + 1; l[r] = p - 1; c[r] = p }
        return PassedArms.DayBars(day, o, h, l, c)
    }

    @Test fun theEdges() {
        val lev = OovArm.Levels(25_000.0, 25_100.0, 24_900.0)
        // Above value at the open and at the 09:19 close: the call; inside: nothing; below: the put.
        assertEquals(1, OovArm.signal(session({ 25_150.0 }, 0..4), lev))
        assertEquals(0, OovArm.signal(session({ if (it == 4) 25_050.0 else 25_150.0 }, 0..4), lev))
        assertEquals(-1, OovArm.signal(session({ 24_800.0 }, 0..4), lev))
        assertEquals(0, OovArm.signal(session({ 25_000.0 }, 0..4), lev))
        // No levels (a short or missing previous session), no first bar, the bar not complete yet: nothing.
        assertEquals(0, OovArm.signal(session({ 25_150.0 }, 0..4), null))
        assertEquals(0, OovArm.signal(session({ 25_150.0 }, 5..9), lev))
        assertEquals(0, OovArm.signal(session({ 25_150.0 }, 0..3), lev))
        assertEquals(0, OovArm.signal(session({ 25_150.0 }, IntRange.EMPTY), lev))
        assertNull(OovArm.valueArea(session({ 25_000.0 }, 0 until 199)))
        assertNotNull(OovArm.valueArea(session({ 25_000.0 + it }, 0 until 200)))
        assertNull(OovArm.valueArea(session({ 0.0 }, 0 until 375).let { PassedArms.DayBars(day, it.o, it.h, it.l, DoubleArray(375) { -1.0 }) }))
        // A flat session: one bin, the POC is it and the value area is that bin.
        val flat = OovArm.valueArea(session({ 25_000.0 }))!!
        assertTrue(flat.val_ < 25_000.0 && flat.vah > 25_000.0 && flat.poc in flat.val_..flat.vah)
        // The strike: 1-ITM of the 09:19 close.
        assertEquals(25_100, OovArm.strike("NIFTY", 1, session({ 25_150.0 }, 0..4)))
        assertEquals(25_200, OovArm.strike("NIFTY", -1, session({ 25_150.0 }, 0..4)))
        // The fill window 09:20-09:22.
        assertTrue(OovArm.mayFill(LocalTime.of(9, 20)) && OovArm.mayFill(LocalTime.of(9, 22)))
        assertTrue(!OovArm.mayFill(LocalTime.of(9, 19)) && !OovArm.mayFill(LocalTime.of(9, 23)))
    }

    @Test fun itsSettingsAreR52sWithTheSoloLayerAddedAndSaidSo() {
        assertEquals(PassedArms.PremiumExits(0.30, null, 60, listOf(0.40 to 0.10), LocalTime.of(15, 10)), OovArm.EXITS)
        assertEquals("MIDCPNIFTY", OovArm.REPORT_APART)
        assertTrue(OovArm.ARM.paperOnly)
        assertTrue(OovArm.STOP_ADDED.contains("the research had none") && OovArm.STOP_ADDED.contains("−30%"))
        for (w in listOf("value area", "70%", "1-ITM", "60 minutes", "expiry day", "SENSEX", "paper only")) assertTrue(w in OovArm.RULES, w)
        assertTrue("294 trades" in OovArm.RECORD && "MIDCPNIFTY" in OovArm.RECORD)
    }
}
