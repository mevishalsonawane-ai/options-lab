package com.optionslab.ira.dhan

import com.optionslab.engine.IST
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** When a chunk counts as done (complete), the fetch ledger, refreshing by appending, and the index IDs Dhan needs. */
class DhanCompleteTest {
    private fun tmp(): Files = Files(File(kotlin.io.path.createTempDirectory("dhanok").toFile(), "dhan"))
    private fun t(d: LocalDate, h: Int, m: Int) = d.atTime(LocalTime.of(h, m)).atZone(IST).toEpochSecond()
    private fun c(t: Long, close: Double = 1.0) = DhanApi.Candle(t, close, close, close, close, 1, 0)
    private val today: LocalDate = LocalDate.of(2026, 10, 6)

    /** The minute window 2026-05-08 .. 2026-08-01 (closed on [today]); its last weekday is Fri 2026-07-31. */
    private val from = LocalDate.of(2026, 5, 8)
    private val to = LocalDate.of(2026, 8, 1)
    private fun minTask() = Plan.Task(Plan.Kind.MIN, Plan.Group.IDX, "NIFTY", "13", "IDX_I", "INDEX", from, to)

    @Test fun aChunkFetchedBeforeItsWindowEndedIsNotDone() {
        val f = tmp()
        val task = minTask()
        assertEquals(to, from.plusDays(Plan.MIN_WINDOW_DAYS))
        // Fetched on 2026-07-15 while the window was open: its rows stop there. Its file exists, but it is not done.
        f.writeText(task.path, f.candlesCsv(listOf(c(t(from, 9, 15)), c(t(LocalDate.of(2026, 7, 15), 11, 0)))))
        f.markFetched(task.path, LocalDate.of(2026, 7, 15))
        assertFalse(f.done(task, today))
        assertEquals(LocalDate.of(2026, 7, 14), f.completeThrough(task.path, from, to))
        assertEquals(listOf(task), Plan.todo(listOf(task), today) { f.done(it, today) })
        // Refreshed after the window ended: appended from its last stored day, and now done.
        assertEquals(LocalDate.of(2026, 7, 15), f.resumeFrom(task.path, from))
        val merged = f.mergedCandles(task.path, listOf(c(t(LocalDate.of(2026, 7, 15), 11, 0), 2.0), c(t(LocalDate.of(2026, 7, 31), 15, 29))))
        assertEquals(3, merged.size)
        assertEquals(2.0, merged[1].close, "a fresh row replaces the stored one of its minute")
        f.writeText(task.path, f.candlesCsv(merged))
        f.markFetched(task.path, LocalDate.of(2026, 8, 3))
        assertTrue(f.done(task, today))
        assertEquals(emptyList(), Plan.todo(listOf(task), today) { f.done(it, today) })
        // An empty refresh reply never loses a stored row.
        assertEquals(3, f.mergedCandles(task.path, emptyList()).size)
        // A window that reaches today is never done: refreshed each run.
        val open = Plan.Task(Plan.Kind.MIN, Plan.Group.IDX, "NIFTY", "13", "IDX_I", "INDEX", LocalDate.of(2026, 9, 21), LocalDate.of(2026, 12, 15))
        f.writeText(open.path, f.candlesCsv(listOf(c(t(LocalDate.of(2026, 9, 21), 9, 15)))))
        f.markFetched(open.path, today.plusDays(1))
        assertFalse(f.done(open, today))
    }

    @Test fun theLedgerIsAppendedReadBackAndSurvivesACutLine() {
        val f = tmp()
        f.markFetched(mapOf("a.csv.gz" to LocalDate.of(2026, 1, 1), "b.csv.gz" to LocalDate.of(2026, 1, 2)))
        f.markFetched("a.csv.gz", LocalDate.of(2026, 2, 1))
        f.markFetched("b.csv.gz", null)
        // A crash cut the last line short: it is ignored, and the next line starts afresh.
        f.file(Files.FETCHED).appendText("c.csv.gz,2026-0")
        f.markFetched("d.csv.gz", LocalDate.of(2026, 3, 1))
        val back = Files(f.root)
        assertEquals(LocalDate.of(2026, 2, 1), back.fetchedOn("a.csv.gz"))
        assertNull(back.fetchedOn("b.csv.gz")); assertNull(back.fetchedOn("c.csv.gz"))
        assertEquals(LocalDate.of(2026, 3, 1), back.fetchedOn("d.csv.gz"))
        assertEquals("a.csv.gz,2026-02-01\nb.csv.gz,\n", f.file(Files.FETCHED).readText().lines().take(4).let { "${it[2]}\n${it[3]}\n" })
    }

    @Test fun aFileWithNoLedgerLineIsJudgedByItsLastCandleOnce() {
        val f = tmp()
        val task = minTask()
        // The last candle is the closing minute of the window's last weekday: whole - and the ledger now says so.
        f.writeText(task.path, f.candlesCsv(listOf(c(t(LocalDate.of(2026, 7, 31), 15, 29)))))
        assertTrue(f.done(task, today))
        assertEquals(to, f.fetchedOn(task.path))
        // Mid-session on the last day: complete only through the day before.
        val g = tmp()
        g.writeText(task.path, g.candlesCsv(listOf(c(t(LocalDate.of(2026, 7, 31), 11, 0)))))
        assertFalse(g.done(task, today))
        assertEquals(LocalDate.of(2026, 7, 30), g.completeThrough(task.path, from, to))
        assertNull(g.fetchedOn(task.path))
        // With the exchange calendar: 2026-07-31 a holiday makes Thursday the last session, so the 30th's close is whole.
        g.writeText(task.path, g.candlesCsv(listOf(c(t(LocalDate.of(2026, 7, 30), 15, 29)))))
        assertFalse(g.done(task, today))
        assertTrue(g.done(task, today) { it.dayOfWeek.value <= 5 && it != LocalDate.of(2026, 7, 31) })
        // A daily file cannot tell a final close from a mid-session one: done only with a ledger date.
        val day = Plan.Task(Plan.Kind.DAY, Plan.Group.IDX, "NIFTY", "13", "IDX_I", "INDEX", LocalDate.of(2020, 12, 30), LocalDate.of(2025, 12, 29))
        g.writeText(day.path, g.candlesCsv(listOf(c(LocalDate.of(2025, 12, 26).atStartOfDay(IST).toEpochSecond()))))
        assertFalse(g.done(day, today))
        g.markFetched(day.path, LocalDate.of(2025, 12, 29))
        assertTrue(g.done(day, today))
        // An empty file with no ledger line is not done (a header-only reply from before the ledger is fetched again).
        val opt = Plan.Task(Plan.Kind.OPT, Plan.Group.IDX, "NIFTY", "13", "NSE_FNO", "OPTIDX", LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 26), "WEEK", 9, true)
        g.writeText(opt.path, g.optionsCsv(emptyList()))
        assertFalse(g.done(opt, today))
        g.markFetched(opt.path, LocalDate.of(2026, 8, 26))
        assertTrue(g.done(opt, today))
    }

    @Test fun onlyTheStrikesDhanServesAreAsked() {
        assertEquals(-10..10, Plan.offsets(Plan.Group.IDX))
        assertEquals(-3..3, Plan.offsets(Plan.Group.EQ))
        assertEquals(42, Plan.optionFiles(Plan.Group.IDX).size)
        assertEquals(14, Plan.optionFiles(Plan.Group.EQ).size)
        assertTrue("CE+0.csv.gz" in Plan.optionFiles(Plan.Group.IDX) && "PE-10.csv.gz" in Plan.optionFiles(Plan.Group.IDX))
        val master = DhanUniverse.Master(emptyMap(), mapOf("HDFCBANK" to "1333"), emptyMap())
        val tasks = Plan.tasks(master, Plan.Choice(stockOptions = true), today).filter { it.kind == Plan.Kind.OPT }
        assertTrue(tasks.filter { it.group == Plan.Group.EQ }.all { it.offset in -3..3 })
        assertTrue(tasks.filter { it.group == Plan.Group.IDX }.all { it.offset in -10..10 })
        assertEquals(2, Plan.Choice().optionYears, "two years of options by default")
        assertEquals(LocalDate.of(2026, 7, 31), Plan.lastTradingDay(from, to))
        assertNull(Plan.lastTradingDay(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 3)))
    }

    @Test fun indicesAreAskedByTheirIdxIIdsNeverTheOptionsUnderlying() {
        val csv = """
            EXCH_ID,SEGMENT,SECURITY_ID,ISIN,INSTRUMENT,UNDERLYING_SECURITY_ID,UNDERLYING_SYMBOL,SYMBOL_NAME,DISPLAY_NAME,INSTRUMENT_TYPE,SERIES,LOT_SIZE,SM_EXPIRY_DATE,STRIKE_PRICE,OPTION_TYPE
            NSE,D,45001,NA,OPTIDX,26000,NIFTY,NIFTY-Oct2026-25000-CE,"NIFTY 07 OCT 25000 CALL",OP,NA,75,2026-10-07,25000,CE
            NSE,D,45002,NA,OPTIDX,26009,BANKNIFTY,BANKNIFTY-Oct2026-55000-CE,"BANKNIFTY 28 OCT 55000 CALL",OP,NA,35,2026-10-28,55000,CE
            BSE,D,85001,NA,OPTIDX,1,SENSEX,SENSEX-Oct2026-80000-CE,"SENSEX 09 OCT 80000 CALL",OP,NA,20,2026-10-09,80000,CE
            NSE,I,13,NA,INDEX,13,NIFTY,NIFTY,Nifty 50,INDEX,X,1,,,
            NSE,I,25,NA,INDEX,25,BANKNIFTY,BANKNIFTY,Nifty Bank,INDEX,X,1,,,
            BSE,I,51,NA,INDEX,51,SENSEX,SENSEX,Sensex,INDEX,X,1,,,
            NSE,D,99999,NA,INDEX,,FINNIFTY,FINNIFTY,Not an IDX_I row,INDEX,X,1,,,
        """.trimIndent()
        val m = DhanUniverse.parseMaster(csv.reader().buffered(), emptySet(), today)
        assertEquals(mapOf("NIFTY" to "13", "BANKNIFTY" to "25", "SENSEX" to "51"), m.indexIds)
        // Not in the master's IDX_I rows: the IDX_I constants.
        val ids = DhanUniverse.INDICES.associate { it.name to DhanUniverse.indexId(it, m) }
        assertEquals(mapOf("NIFTY" to "13", "BANKNIFTY" to "25", "FINNIFTY" to "27", "MIDCPNIFTY" to "442", "NIFTYNXT50" to "38",
            "SENSEX" to "51", "BANKEX" to "69", "INDIAVIX" to "21"), ids)
        assertTrue(ids.values.none { it in setOf("26000", "26009", "1") })
        // A kept subset written before this (its index IDs may be the options' underlying ones) keeps only its other rows.
        val old = DhanUniverse.readMaster("kind,key,id,segment,instrument,expiry\nI,NIFTY,26000,,,\nE,HDFCBANK,1333,,,\n")
        assertTrue(old.indexIds.isEmpty()); assertEquals("1333", old.equityIds["HDFCBANK"])
        assertEquals("13", DhanUniverse.indexId(DhanUniverse.index("NIFTY")!!, old))
        assertEquals(m, DhanUniverse.readMaster(DhanUniverse.writeMaster(m)))
    }

    // ---- packs ------------------------------------------------------------------------------------------------------------

    private fun gz(text: String): ByteArray = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(text.toByteArray()) } }.toByteArray()
    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { o ->
        ZipOutputStream(o).use { z -> for ((n, b) in entries) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
    }.toByteArray()

    private fun import(f: Files, vararg entries: Pair<String, ByteArray>): PackImport.Report {
        val imp = PackImport(f)
        imp.begin()
        imp.readPart(ByteArrayInputStream(zip(*entries)), 1, 1)
        return imp.finish()
    }

    @Test fun anImportedChunkIsDoneOnlyWhenThePackSaysItWasFetchedAfterItsWindowEnded() {
        val f = tmp()
        val task = minTask()
        val closing = gz(Files.CANDLE_HEADER + "\n${t(LocalDate.of(2026, 7, 31), 15, 29)},1,1,1,1,1,0\n")
        // Fetched (the pack's ledger says) on 2026-07-31, before the window was over: not done, fetched again.
        import(f, "dhan/${task.path}" to closing, "dhan/fetched.csv" to "${task.path},2026-07-31\n".toByteArray())
        assertEquals(LocalDate.of(2026, 7, 31), f.fetchedOn(task.path))
        assertFalse(f.done(task, today))
        assertFalse(f.has(Files.FETCHED) && f.file(Files.FETCHED).readText().contains("fetched.csv"), "the ledger is merged by line, not stored as a chunk")
        // A pack fetched after the window ended, with more rows: it replaces the file and brings its date.
        val more = gz(Files.CANDLE_HEADER + "\n${t(LocalDate.of(2026, 7, 31), 15, 28)},1,1,1,1,1,0\n${t(LocalDate.of(2026, 7, 31), 15, 29)},1,1,1,1,1,0\n")
        import(f, "dhan/${task.path}" to more, "dhan/fetched.csv" to "${task.path},2026-08-01\n".toByteArray())
        assertTrue(f.done(task, today))
        // Replaced by a pack file with no ledger line: the phone's line no longer applies (judged by its last candle).
        val g = tmp()
        g.writeText(task.path, g.candlesCsv(listOf(c(t(from, 9, 15)))))
        g.markFetched(task.path, LocalDate.of(2026, 9, 1))
        import(g, "dhan/${task.path}" to gz(Files.CANDLE_HEADER + "\n${t(LocalDate.of(2026, 7, 31), 11, 0)},1,1,1,1,1,0\n"))
        assertNull(g.fetchedOn(task.path))
        assertFalse(g.done(task, today))
        // The limits: room for a 3-4 GB six-year options pack, a manifest parsed whole kept small.
        assertTrue(PackImport.Limits().maxTotalBytes >= 8L * 1024 * 1024 * 1024)
        assertTrue(PackImport.Limits().maxManifestBytes <= 8L * 1024 * 1024)
        assertEquals("fetched.csv", PackImport.storePath("dhan/fetched.csv"))
        assertEquals("3.62 GB", Files.sizeText(3_620_000_000L)); assertEquals("812.4 MB", Files.sizeText(812_400_000L))
    }
}
