package com.optionslab.ira

import com.optionslab.engine.Kite
import com.optionslab.engine.Right
import java.io.StringReader
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The saved instruments list read as a stream (memory audit, 14 Oct 2026, item 5): the same rows as the old whole-file
 * parse, the escapes Android's JSON writer uses, rows that do not read skipped, a file of another shape null, and the
 * repeated names and expiries shared.
 */
class InstrumentsFileTest {
    private val exp = LocalDate.of(2026, 10, 20)

    /** The file as Broker writes it with Android's JSONObject (a slash written as "\/", whole doubles without ".0"). */
    private val text = """{"day":"2026-10-14","i":[[13434114,"NIFTY26O2024500CE","NIFTY","2026-10-20",24500,75,"CE",0.05],""" +
        """[13434370,"NIFTY26O2024500PE","NIFTY","2026-10-20",24500.5,75,"PE",5.0E-2],[1,"A\/B \"x\" é","BANKNIFTY","2026-10-28",50000,35,"CE",0.05]]}"""

    @Test fun theRowsReadAsTheyWereWritten() {
        val (day, list) = assertNotNull(InstrumentsFile.read(StringReader(text)))
        assertEquals("2026-10-14", day)
        assertEquals(3, list.size)
        assertEquals(Kite.Instrument(13434114L, "NIFTY26O2024500CE", "NIFTY", exp, 24500.0, 75, Right.CE, 0.05), list[0])
        assertEquals(Kite.Instrument(13434370L, "NIFTY26O2024500PE", "NIFTY", exp, 24500.5, 75, Right.PE, 0.05), list[1])
        assertEquals("A/B \"x\" é", list[2].tradingSymbol)
        assertEquals(LocalDate.of(2026, 10, 28), list[2].expiry)
        // The repeated words are one object each.
        assertSame(list[0].name, list[1].name)
        assertSame(list[0].expiry, list[1].expiry)
    }

    @Test fun spacesKeysInAnyOrderAndUnknownKeysAreFine() {
        val t = "{ \"x\" : {\"a\":[1,{\"b\":null}],\"c\":\"}\"} ,\n \"i\" : [ [ 7 , \"S\" , \"NIFTY\" , \"2026-10-20\" , 1 , 2 , \"PE\" , 0.1 ] ] , \"day\" : \"2026-10-13\" , \"n\":null, \"t\":true }"
        val (day, list) = assertNotNull(InstrumentsFile.read(StringReader(t)))
        assertEquals("2026-10-13", day)
        assertEquals(listOf(Kite.Instrument(7L, "S", "NIFTY", exp, 1.0, 2, Right.PE, 0.1)), list)
    }

    @Test fun rowsThatDoNotReadAreSkipped() {
        val t = """{"day":"2026-10-14","i":[[1,"S","NIFTY","not a date",1,1,"CE",0.05],[2,"S"],[],[3,null,"NIFTY","2026-10-20",1,1,"CE",0.05],""" +
            """[4,"OK","NIFTY","2026-10-20",1,1,"CE",0.05]]}"""
        assertEquals(listOf(4L), assertNotNull(InstrumentsFile.read(StringReader(t))).second.map { it.token })
    }

    @Test fun emptyAndOtherShapes() {
        assertEquals("" to emptyList(), InstrumentsFile.read(StringReader("{\"i\":[]}")))
        assertEquals("d" to emptyList(), InstrumentsFile.read(StringReader("{\"day\":\"d\",\"i\":[]}")))
        // No list, not an object, cut short, empty: null (the caller keeps what it had).
        for (bad in listOf("{}", "{\"day\":\"d\"}", "[]", "", "{\"day\":\"d\",\"i\":[[1,\"S\"", "{\"i\":[] x", "not json", "{\"i\":[]"))
            assertNull(InstrumentsFile.read(StringReader(bad)), bad)
    }

    @Test fun aDownloadedListIsCompactedToo() {
        val a = Kite.Instrument(1, "A", String(charArrayOf('N', 'I', 'F', 'T', 'Y')), exp, 1.0, 1, Right.CE, 0.05)
        val b = a.copy(token = 2, tradingSymbol = "B", name = String(charArrayOf('N', 'I', 'F', 'T', 'Y')), expiry = LocalDate.parse("2026-10-20"))
        val out = InstrumentsFile.compact(listOf(a, b))
        assertEquals(listOf(a, b), out)
        assertSame(out[0].name, out[1].name); assertSame(out[0].expiry, out[1].expiry)
        assertSame(a, out[0], "an instrument already sharing its words is not copied")
        assertTrue(InstrumentsFile.compact(emptyList()).isEmpty())
    }
}
