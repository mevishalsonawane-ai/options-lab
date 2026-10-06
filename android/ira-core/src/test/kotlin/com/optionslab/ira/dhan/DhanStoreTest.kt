package com.optionslab.ira.dhan

import com.optionslab.engine.IST
import com.optionslab.engine.Right
import com.optionslab.ira.DhanData
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DhanStoreTest {
    private fun tmp(): Files = Files(kotlin.io.path.createTempDirectory("dhan").toFile())
    private fun t(d: LocalDate, h: Int, m: Int) = d.atTime(LocalTime.of(h, m)).atZone(IST).toEpochSecond()

    // ---- the plan ------------------------------------------------------------------------------------------------------

    @Test fun windowsSitOnAGridWithinDhansLimits() {
        val today = LocalDate.of(2026, 10, 6)
        for ((len, from) in listOf(Plan.MIN_WINDOW_DAYS to today.minusYears(5), Plan.OPT_WINDOW_DAYS to today.minusYears(2))) {
            val w = Plan.windows(from, today, len)
            assertTrue(w.first().second.isAfter(today) && !w.first().first.isAfter(today))
            assertTrue(!w.last().first.isAfter(from) && w.last().second.isAfter(from))
            for ((a, b) in w) assertEquals(len, ChronoUnit.DAYS.between(a, b))
            // Newest first, back to back: nothing missed, nothing fetched twice.
            for ((newer, older) in w.zipWithNext()) assertEquals(older.second, newer.first)
            // The same window on another day keeps its dates (and so its file).
            assertEquals(w.drop(1).take(3), Plan.windows(from, today.plusDays(len), len).drop(2).take(3))
        }
        assertTrue(Plan.MIN_WINDOW_DAYS < 90 && Plan.OPT_WINDOW_DAYS < 30)
        assertEquals(emptyList(), Plan.windows(today.plusDays(1), today, 25))
    }

    @Test fun theTaskListCoversEveryIndexAndCompanyAndResumes() {
        val today = LocalDate.of(2026, 10, 6)
        val master = DhanUniverse.Master(mapOf("NIFTY" to "13", "NIFTYNXT50" to "38"), mapOf("HDFCBANK" to "1333", "M&M" to "2031"),
            mapOf("NIFTY" to DhanUniverse.Future("35001", "NSE_FNO", "FUTIDX", LocalDate.of(2026, 10, 28))))
        val tasks = Plan.tasks(master, Plan.Choice(), today)
        assertEquals(tasks.size, tasks.map { it.path }.distinct().size, "one file per chunk")
        // Every index (NIFTY NEXT 50 with its master ID; the rest by their fallback IDs) has its daily candles.
        for (ix in DhanUniverse.INDICES) assertTrue(tasks.any { it.kind == Plan.Kind.DAY && it.symbol == ix.name }, ix.name)
        assertTrue(tasks.first().kind == Plan.Kind.EXPIRIES)
        // Index options: 21 strikes either side x call and put per window, newest window first.
        val nifty = tasks.filter { it.kind == Plan.Kind.OPT && it.symbol == "NIFTY" }
        assertEquals(42 * Plan.windows(today.minusYears(2), today, Plan.OPT_WINDOW_DAYS).size, nifty.size)
        assertTrue(nifty.all { it.flag == "WEEK" && it.instrument == "OPTIDX" && it.segment == "NSE_FNO" && it.days <= 30 })
        assertTrue(tasks.filter { it.kind == Plan.Kind.OPT && it.symbol == "SENSEX" }.all { it.segment == "BSE_FNO" })
        assertTrue(tasks.none { it.kind == Plan.Kind.OPT && it.symbol == "INDIAVIX" })
        assertTrue(tasks.filter { it.kind == Plan.Kind.MIN }.all { it.days <= 90 })
        // Companies: daily and minute candles, no options unless chosen; a symbol the master lacks is left out.
        assertTrue(tasks.any { it.symbol == "M&M" && it.path.startsWith("eq/M_M/day/") })
        assertTrue(tasks.none { it.group == Plan.Group.EQ && it.kind == Plan.Kind.OPT })
        assertTrue(tasks.none { it.symbol == "RELIANCE" })
        assertTrue(Plan.tasks(master, Plan.Choice(stockOptions = true), today).any { it.group == Plan.Group.EQ && it.kind == Plan.Kind.OPT && it.offset == 3 })
        assertTrue(Plan.tasks(master, Plan.Choice(stocks = false, futures = false, optionYears = 0), today).none { it.group != Plan.Group.IDX || it.kind == Plan.Kind.OPT })
        // Futures: the near contract.
        assertTrue(tasks.any { it.group == Plan.Group.FUT && it.id == "35001" })
        // Resuming: a chunk on disk whose window has ended is not fetched again; an open one is.
        val have = tasks.filter { !it.open(today) }.map { it.path }.toSet()
        val todo = Plan.todo(tasks, today) { it.path in have }
        assertTrue(todo.isNotEmpty() && todo.all { it.open(today) })
        // Without a master: the indices by their fallback IDs, no companies.
        val bare = Plan.tasks(null, Plan.Choice(), today)
        assertTrue(bare.any { it.symbol == "NIFTYNXT50" && it.id == "38" } && bare.none { it.group == Plan.Group.EQ } && bare.any { it.symbol == "NIFTY" && it.id == "13" })
    }

    @Test fun readsTheScripMasterAsAStream() {
        val csv = """
            EXCH_ID,SEGMENT,SECURITY_ID,ISIN,INSTRUMENT,UNDERLYING_SECURITY_ID,UNDERLYING_SYMBOL,SYMBOL_NAME,DISPLAY_NAME,INSTRUMENT_TYPE,SERIES,LOT_SIZE,SM_EXPIRY_DATE,STRIKE_PRICE,OPTION_TYPE
            NSE,I,13,NA,INDEX,13,NIFTY,NIFTY,Nifty 50,INDEX,X,1,,,
            NSE,D,45000,NA,OPTIDX,26000,NIFTYNXT50,NIFTYNXT50-Oct2026-70000-CE,"NIFTYNXT50 28 OCT 70000 CALL",OP,NA,25,2026-10-28,70000,CE
            NSE,D,35001,NA,FUTIDX,13,NIFTY,NIFTY-Oct2026-FUT,NIFTY OCT FUT,FUT,NA,75,2026-10-28,-0.01,XX
            NSE,D,35002,NA,FUTIDX,13,NIFTY,NIFTY-Nov2026-FUT,NIFTY NOV FUT,FUT,NA,75,2026-11-25,-0.01,XX
            NSE,D,35000,NA,FUTIDX,13,NIFTY,NIFTY-Sep2026-FUT,NIFTY SEP FUT,FUT,NA,75,2026-09-30,-0.01,XX
            NSE,E,1333,INE040A01034,EQUITY,,HDFCBANK,HDFC BANK LTD,HDFC Bank,ES,EQ,1,,,
            BSE,E,500180,INE040A01034,EQUITY,,HDFCBANK,HDFC BANK LTD,HDFC Bank,ES,A,1,,,
            NSE,E,2031,INE101A01026,EQUITY,,M&M,MAHINDRA & MAHINDRA LTD,"Mahindra, Mahindra",ES,EQ,1,,,
        """.trimIndent()
        val m = DhanUniverse.parseMaster(csv.reader().buffered(), setOf("HDFCBANK", "M&M"), LocalDate.of(2026, 10, 6))
        // The index's own IDX_I row gives its ID; an option's UNDERLYING_SECURITY_ID (26000) never does - Dhan's
        // rollingoption serves no data for it (NIFTYNXT50 has no INDEX row here: its IDX_I fallback, 38, is used).
        assertEquals("13", m.indexIds["NIFTY"]); assertNull(m.indexIds["NIFTYNXT50"])
        assertEquals(mapOf("HDFCBANK" to "1333", "M&M" to "2031"), m.equityIds)
        assertEquals("35001", m.nearFutures["NIFTY"]?.id)
        val back = DhanUniverse.readMaster(DhanUniverse.writeMaster(m))
        assertEquals(m, back)
        assertEquals(listOf("a", "b,c", "d\"e"), DhanUniverse.splitCsv("a,\"b,c\",\"d\"\"e\""))
        assertEquals("38", DhanUniverse.indexId(DhanUniverse.index("NIFTYNXT50")!!, m))
        assertEquals("38", DhanUniverse.indexId(DhanUniverse.index("NIFTYNXT50")!!, null))
        assertTrue(DhanUniverse.allConstituents().containsAll(listOf("HDFCBANK", "RELIANCE", "INFY")))
        assertEquals(DhanUniverse.allConstituents().size, DhanUniverse.allConstituents().distinct().size)
    }

    // ---- files -----------------------------------------------------------------------------------------------------------

    @Test fun candleFilesRoundTripCompactly() {
        val f = tmp()
        val d = LocalDate.of(2026, 9, 1)
        val c = listOf(DhanApi.Candle(t(d, 9, 16), 24500.05, 24510.0, 24490.5, 24505.25, 0, 0), DhanApi.Candle(t(d, 9, 15), 24499.0, 24501.0, 24498.0, 24500.0, 0, 0))
        f.writeText("idx/NIFTY/min/2026-08-01.csv.gz", f.candlesCsv(c + c))
        assertTrue(f.has("idx/NIFTY/min/2026-08-01.csv.gz")); assertFalse(f.has("idx/NIFTY/min/2026-09-01.csv.gz"))
        val back = f.candles(Plan.Group.IDX, "NIFTY", "min")
        assertEquals(c.sortedBy { it.t }, back)
        assertEquals(listOf("24499,24501,24498,24500", "24500.05,24510,24490.5,24505.25"),
            f.lines("idx/NIFTY/min/2026-08-01.csv.gz").map { it.split(',').subList(1, 5).joinToString(",") }.toList())
        assertEquals("0.05", Files.num(0.05)); assertEquals("100", Files.num(100.0)); assertEquals("0", Files.num(Double.NaN))
        f.addExpiries("NIFTY", listOf(d, d.minusDays(7))); f.addExpiries("NIFTY", listOf(d))
        assertEquals(listOf(d.minusDays(7), d), f.listedExpiries("NIFTY"))
        assertTrue(f.bytes() > 0)
        f.writeText("chain/NIFTY/2026-01-01.json.gz", "{}"); f.writeText("chain/NIFTY/2026-09-01.json.gz", "{}")
        f.pruneChains(d)
        assertFalse(f.has("chain/NIFTY/2026-01-01.json.gz")); assertTrue(f.has("chain/NIFTY/2026-09-01.json.gz"))
        assertEquals("{}", f.readText("chain/NIFTY/2026-09-01.json.gz"))
        File(f.root, "x.part").writeText("half"); f.dropPartials(); assertFalse(File(f.root, "x.part").exists())
        assertTrue(f.deleteAll()); assertFalse(f.root.exists()); assertEquals(0L, f.bytes())
    }

    // ---- expired options ----------------------------------------------------------------------------------------------------

    /** One window of NIFTY weekly options: three days, the middle one an expiry day; ATM call and put, one strike up call. */
    private fun optionStore(): Triple<Files, List<LocalDate>, LocalDate> {
        val f = tmp()
        val w = Plan.windows(LocalDate.of(2026, 3, 2), LocalDate.of(2026, 3, 2), Plan.OPT_WINDOW_DAYS).single().first
        val days = listOf(w.plusDays(1), w.plusDays(2), w.plusDays(3))
        // Last-minute prices: day 1 and 3 hold time value, day 2 (the expiry) almost none.
        val last = mapOf(days[0] to (120.0 to 110.0), days[1] to (3.0 to 2.0), days[2] to (150.0 to 140.0))
        fun rows(call: Boolean, offset: Int) = days.flatMap { d ->
            val (ce, pe) = last.getValue(d)
            val px = if (call) ce else pe
            val k = 25000.0 + 50 * offset
            listOf(DhanApi.OptionCandle(t(d, 9, 15), px * 2, px * 2.5, px * 1.5, px * 2, 100, 10, 12.0, k, 25000.0),
                DhanApi.OptionCandle(t(d, 15, 29), px, px, px, px - offset, 50, 12, 11.0, k, 25000.0))
        }
        val dir = "opt/NIFTY/WEEK/$w"
        f.writeText("$dir/CE+0.csv.gz", f.optionsCsv(rows(true, 0)))
        f.writeText("$dir/PE+0.csv.gz", f.optionsCsv(rows(false, 0)))
        f.writeText("$dir/CE+1.csv.gz", f.optionsCsv(rows(true, 1)))
        f.writeText("$dir/PE+1.csv.gz", f.optionsCsv(emptyList()))   // Dhan had nothing: header only, still done
        val mw = Plan.windows(days[0], days[0], Plan.MIN_WINDOW_DAYS).single().first
        f.writeText("idx/NIFTY/min/$mw.csv.gz", f.candlesCsv(days.flatMap { d ->
            listOf(DhanApi.Candle(t(d, 9, 15), 25000.0, 25010.0, 24990.0, 25000.0, 0, 0), DhanApi.Candle(t(d, 15, 29), 25000.0, 25000.0, 25000.0, 25000.0, 0, 0)) }))
        return Triple(f, days, w)
    }

    @Test fun expiryDaysAreReadFromTheData() {
        val (f, days, _) = optionStore()
        assertEquals(listOf(days[1]), ExpiredOptions.inferExpiries(f, "NIFTY", "WEEK"))
        f.addExpiries("NIFTY", listOf(days[2].plusDays(5)))
        assertEquals(listOf(days[1], days[2].plusDays(5)), ExpiredOptions.expiries(f, "NIFTY", "WEEK"))
        assertEquals(listOf("WEEK"), ExpiredOptions.flags(f, "NIFTY")); assertEquals(listOf("NIFTY"), ExpiredOptions.underlyings(f))
    }

    @Test fun scanGivesOneSeriesPerContractWithTheIndexAlongside() {
        val (f, days, _) = optionStore()
        val all = ExpiredOptions.scan(f, "NIFTY", "WEEK", before = days[2].plusDays(30)).toList()
        assertEquals(listOf(Triple(25000.0, Right.CE, 4), Triple(25000.0, Right.PE, 4), Triple(25050.0, Right.CE, 4)),
            all.map { Triple(it.strike, it.right, it.bars.size) })
        assertTrue(all.all { s -> s.expiry == days[1] && s.underlying == "NIFTY" && s.flag == "WEEK" })
        val ce = all.first()
        assertEquals(ce.bars.sortedBy { it.t }, ce.bars)
        assertTrue(ce.bars.all { it.spot == 25000.0 && it.offset == 0 && !it.day.isAfter(days[1]) })
        assertEquals(555, ce.bars.first().minute)
        assertEquals(3.0, ce.bars.last().close)
        assertEquals(1, all[2].bars.first().offset)
        // A contract that has not expired before the date asked is left out; a later listed expiry takes day 3's minutes.
        f.addExpiries("NIFTY", listOf(days[2].plusDays(5)))
        assertEquals(3, ExpiredOptions.scan(f, "NIFTY", "WEEK", before = days[2].plusDays(5)).count())
        val more = ExpiredOptions.scan(f, "NIFTY", "WEEK", before = days[2].plusDays(6)).toList()
        assertEquals(6, more.size); assertEquals(days[2].plusDays(5), more.last().expiry); assertEquals(2, more.last().bars.size)
        assertEquals(0, ExpiredOptions.scan(f, "BANKNIFTY", "MONTH", before = days[2]).count())
    }

    @Test fun theStoredDaysAsSessionsForTheReplays() {
        val (f, days, _) = optionStore()
        val s = ExpiredOptions.sessions(f, "NIFTY", "WEEK", before = days[2].plusDays(1), lot = { 75 }).toList()
        assertEquals(days.take(2), s.map { it.day })
        val first = s.first()
        assertNotNull(first.index); assertEquals(2, first.index!!.size); assertEquals(555, first.index!!.minutes[0])
        assertEquals(3, first.options.size)
        assertTrue(first.options.all { it.lot == 75 && it.expiry == days[1] && it.size == 2 })
        // No lot known that day: the index only.
        val bare = ExpiredOptions.sessions(f, "NIFTY", "WEEK", before = days[2].plusDays(1), lot = { null }).toList()
        assertTrue(bare.all { it.options.isEmpty() && it.index != null })
        // Days on or after `before` are left to the bundled and harvested record.
        assertEquals(listOf(days[0]), ExpiredOptions.sessions(f, "NIFTY", "WEEK", before = days[1], lot = { 75 }).map { it.day }.toList())
    }

    // ---- what Jarvis says from it ---------------------------------------------------------------------------------------------

    @Test fun jarvisSaysWhatIsKeptAndALastExpiry() {
        val (f, days, _) = optionStore()
        assertEquals(DhanData.NONE, DhanData.answer(DhanData.Q(DhanData.Kind.HAVE, null), null, days[2]))
        val dw = Plan.windows(Plan.DAY_FROM, days[2], Plan.DAY_WINDOW_DAYS, Plan.DAY_FROM).first().first
        f.writeText("idx/NIFTY/day/$dw.csv.gz", f.candlesCsv(listOf(
            DhanApi.Candle(t(days[0], 0, 0), 24900.0, 25100.0, 24850.0, 25000.0, 0, 0),
            DhanApi.Candle(t(days[1], 0, 0), 25000.0, 25200.0, 24950.0, 25150.5, 0, 0))))
        val have = DhanData.answer(DhanData.Q(DhanData.Kind.HAVE, null), f, days[2])
        assertTrue(have.startsWith("From Dhan, on this phone"), have)
        assertTrue("Nifty from ${days[0].year}" in have && "expired options of Nifty" in have && "nothing in it trades" in have, have)
        val move = DhanData.answer(DhanData.Q(DhanData.Kind.EXPIRY_MOVE, "NIFTY"), f, days[2])
        assertTrue("Nifty on its last expiry" in move && "ended 25,150.50" in move && "up 151 points (0.60%)" in move && "250-point range" in move, move)
        assertTrue("no options" in DhanData.answer(DhanData.Q(DhanData.Kind.EXPIRY_MOVE, "INDIAVIX"), f, days[2]))
        assertTrue("expiry dates" in DhanData.answer(DhanData.Q(DhanData.Kind.EXPIRY_MOVE, "BANKNIFTY"), f, days[2]))
        val empty = tmp()
        assertEquals(DhanData.NONE, DhanData.have(empty.manifest()))
    }

    @Test fun jarvisHearsTheQuestions() {
        assertEquals(DhanData.Q(DhanData.Kind.HAVE, null), DhanData.asked("what dhan data do you have"))
        assertEquals(DhanData.Q(DhanData.Kind.HAVE, null), DhanData.asked("dhan se kya data hai"))
        assertEquals(DhanData.Q(DhanData.Kind.HAVE, null), DhanData.asked("what have you downloaded"))
        assertEquals(DhanData.Q(DhanData.Kind.HAVE, "FINNIFTY"), DhanData.asked("what data do you have on finnifty"))
        assertEquals(DhanData.Q(DhanData.Kind.EXPIRY_MOVE, "BANKNIFTY"), DhanData.asked("how did banknifty move on last expiry in the dhan data"))
        assertEquals(DhanData.Q(DhanData.Kind.EXPIRY_MOVE, "NIFTYNXT50"), DhanData.asked("how did nifty next 50 do on its last expiry per dhan"))
        assertEquals(DhanData.Q(DhanData.Kind.EXPIRY_MOVE, "MIDCPNIFTY"), DhanData.asked("dhan data: how did midcap nifty move on expiry day"))
        for (s in listOf("thank you dhanyavad", "is the model downloaded", "delete the dhan data", "how did banknifty move on last expiry",
            "is my dhan token valid", "nifty kaisa hai"))
            assertNull(DhanData.asked(s), s)
    }
}
