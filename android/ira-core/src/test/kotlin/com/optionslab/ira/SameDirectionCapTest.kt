package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The same-direction cap (19 Oct, [SameDirectionCap]): 11 same-bar index puts let 2 through and refuse 9 (each recorded once);
 * exits are never counted against it; Boss's manual orders are never counted; the live ledger; the Autopilot entry and its undo.
 */
class SameDirectionCapTest {
    private val day = LocalDate.of(2026, 10, 19)
    private val t0 = LocalDateTime.of(2026, 10, 19, 9, 30, 2)

    /** A tiny paper book: fills, held units and which orders are automatic (the app's Paper book does this with its own). */
    private class Book {
        val fills = ArrayList<SameDirectionCap.Fill>()
        val owners = HashMap<String, String>()
        val held = HashMap<String, Int>()
        private var seq = 0
        fun dir(symbol: String): Int? = when {
            symbol.startsWith("RELIANCE") -> null
            symbol.endsWith("CE") -> 1
            symbol.endsWith("PE") -> -1
            else -> null
        }
        fun open() = SameDirectionCap.openEntries(fills, held, emptyList(), ::dir) { owners[it] }

        /** An entry: refused by the cap when [owner] is automatic and the cap is full, else filled. */
        fun buy(symbol: String, owner: String?, qty: Int, at: LocalDateTime, max: Int = 2): String? {
            val d = dir(symbol)
            if (owner != null && d != null) SameDirectionCap.refusal(open(), d, max)?.let { return it }
            val id = "P${++seq}"
            fills += SameDirectionCap.Fill(id, symbol, true, qty, at)
            if (owner != null) owners[id] = owner
            held[symbol] = (held[symbol] ?: 0) + qty
            return null
        }
        fun sell(symbol: String, qty: Int, at: LocalDateTime) {
            fills += SameDirectionCap.Fill("S${++seq}", symbol, false, qty, at)
            held[symbol] = (held[symbol] ?: 0) - qty
        }
    }

    /** The 19 Oct 09:30 bar: R51 and R72 on four indices each, Liquidity's two books on one BANKNIFTY put and on FINNIFTY. */
    private val nineThirty = listOf(
        "R51 Donchian 20" to "BANKNIFTY55500PE", "R51 Donchian 20" to "FINNIFTY26000PE", "R51 Donchian 20" to "MIDCPNIFTY13000PE",
        "R51 Donchian 20" to "SENSEX81000PE",
        "R72 Breadth Donchian" to "BANKNIFTY55500PE", "R72 Breadth Donchian" to "FINNIFTY26000PE", "R72 Breadth Donchian" to "MIDCPNIFTY13000PE",
        "R72 Breadth Donchian" to "SENSEX81000PE",
        "Liquidity 15+5 BANKNIFTY 15m" to "BANKNIFTY55500PE", "Liquidity 15+5 BANKNIFTY 5m" to "BANKNIFTY55500PE",
        "Liquidity 15+5 FINNIFTY" to "FINNIFTY26000PE",
    )

    @Test fun elevenSameBarPutsLetTwoInAndRefuseNineEachRecorded() {
        val b = Book()
        var shadow = emptyList<SameDirectionCap.Blocked>()
        val entered = ArrayList<String>()
        nineThirty.forEachIndexed { i, (arm, sym) ->
            val why = b.buy(sym, arm, 30, t0.plusSeconds(i.toLong()))
            if (why == null) entered += arm
            else {
                assertTrue(why.startsWith("same-direction cap: 2 puts already open"), why)
                shadow = SameDirectionCap.add(shadow, SameDirectionCap.Blocked(1_000L * i, day, arm, sym, -1, 100.0, live = false, applied = true, why = why))
            }
        }
        // First to reach the door wins: the first two booked (R51 on BANKNIFTY and FINNIFTY).
        assertEquals(listOf("R51 Donchian 20", "R51 Donchian 20"), entered)
        assertEquals(9, shadow.size, "every refused entry is a would-be record (no two of them the same arm and option)")
        assertEquals(2, b.open().size)
        // Liquidity's capped entries counted apart in the words (its plain-rules record stays separable).
        val line = SameDirectionCap.line(shadow, day, paperOn = true, max = 2, liveOn = false)
        assertTrue(line.contains("today 9 paper entries refused"), line)
        assertTrue(line.contains("Liquidity's 3 counted apart"), line)
        assertTrue(line.contains("live: none"), line)
        // A call (the other way) still goes.
        assertNull(b.buy("NIFTY25000CE", "R51 Donchian 20", 75, t0.plusMinutes(1)))
    }

    @Test fun anExitIsNeverRefusedAndFreesItsPlace() {
        val b = Book()
        assertNull(b.buy("NIFTY25000PE", "R51 Donchian 20", 75, t0))
        assertNull(b.buy("BANKNIFTY55500PE", "Liquidity 15+5 BANKNIFTY 15m", 30, t0.plusSeconds(1)))
        assertNotNull(b.buy("FINNIFTY26000PE", "R72 Breadth Donchian", 65, t0.plusSeconds(2)))
        // The exit is a sell: the cap has nothing to say about it (only buys are asked) and it frees one place.
        b.sell("NIFTY25000PE", 75, t0.plusMinutes(3))
        assertEquals(1, b.open().size)
        assertNull(b.buy("FINNIFTY26000PE", "R72 Breadth Donchian", 65, t0.plusMinutes(4)))
        // A part exit keeps the entry counted (still open).
        b.sell("FINNIFTY26000PE", 30, t0.plusMinutes(5))
        assertEquals(2, b.open().size)
    }

    @Test fun bossesManualOrdersAreNeverCappedOrCounted() {
        val b = Book()
        repeat(4) { assertNull(b.buy("NIFTY2${it}000PE", null, 75, t0.plusSeconds(it.toLong())), "manual: never capped") }
        assertEquals(0, b.open().size, "manual: never counted")
        assertNull(b.buy("BANKNIFTY55500PE", "R51 Donchian 20", 30, t0.plusMinutes(1)))
        assertNull(b.buy("SENSEX81000PE", "R72 Breadth Donchian", 20, t0.plusMinutes(1)))
        assertNotNull(b.buy("FINNIFTY26000PE", "Liquidity 15+5 FINNIFTY", 65, t0.plusMinutes(1)))
    }

    @Test fun twoBooksOnOneOptionCountTwiceAndFifoClosesTheOldestFirst() {
        val b = Book()
        assertNull(b.buy("BANKNIFTY55500PE", "Liquidity 15+5 BANKNIFTY 15m", 30, t0))
        assertNull(b.buy("BANKNIFTY55500PE", "Liquidity 15+5 BANKNIFTY 5m", 30, t0.plusSeconds(1)))
        assertEquals(2, b.open().size, "both books count")
        assertNotNull(b.buy("NIFTY25000PE", "R51 Donchian 20", 75, t0.plusSeconds(2)))
        b.sell("BANKNIFTY55500PE", 30, t0.plusMinutes(2))
        assertEquals(listOf("Liquidity 15+5 BANKNIFTY 5m"), b.open().map { it.owner }, "the oldest closed first")
    }

    @Test fun anOptionTheBookNoLongerHoldsIsNotCounted() {
        // Expired or squared off with no sell fill in the book: trimmed to what is held.
        val fills = listOf(SameDirectionCap.Fill("A", "NIFTY25000PE", true, 75, t0), SameDirectionCap.Fill("B", "NIFTY25000PE", true, 75, t0.plusSeconds(1)))
        val open = SameDirectionCap.openEntries(fills, mapOf("NIFTY25000PE" to 75), emptyList(), { -1 }) { "arm" }
        assertEquals(listOf("B"), open.map { it.orderId })
        assertTrue(SameDirectionCap.openEntries(fills, emptyMap(), emptyList(), { -1 }) { "arm" }.isEmpty())
        // A partly filled order counted once; a resting automatic BUY not filled yet counts; a stock never.
        val working = listOf("W" to "NIFTY24000PE", "A" to "NIFTY25000PE", "X" to "RELIANCE")
        val o2 = SameDirectionCap.openEntries(fills, mapOf("NIFTY25000PE" to 150), working, { s -> if (s == "RELIANCE") null else -1 }) { "arm" }
        assertEquals(listOf("A", "B", "W"), o2.map { it.orderId })
    }

    @Test fun directionCountsOnlyIndexOptions() {
        assertEquals(-1, SameDirectionCap.direction("NIFTY", "NFO", "PE"))
        assertEquals(1, SameDirectionCap.direction("sensex", "BFO", "CE"))
        assertNull(SameDirectionCap.direction("RELIANCE", "NFO", "CE"))
        assertNull(SameDirectionCap.direction("CRUDEOIL", "MCX", "PE"))
        assertNull(SameDirectionCap.direction("NIFTY", "NFO", "FUT"))
        assertEquals("1 put", "1 ${SameDirectionCap.words(-1, 1)}")
        assertEquals("calls", SameDirectionCap.words(1, 3))
    }

    @Test fun aBadNumberNeverBlocksEverything() {
        val one = listOf(SameDirectionCap.Open("A", "NIFTY25000PE", -1, "arm"))
        assertNull(SameDirectionCap.refusal(emptyList(), -1, 0), "0 is read as 1: the first still goes")
        assertNotNull(SameDirectionCap.refusal(one, -1, 0))
        assertNull(SameDirectionCap.refusal(one, 1, 1), "the other way is not counted")
        val many = (1..6).map { SameDirectionCap.Open("O$it", "NIFTY2${it}000PE", -1, "arm $it") }
        assertTrue(SameDirectionCap.refusal(many, -1, 2)!!.endsWith("and 2 more)"))
    }

    @Test fun theWouldBeRecordKeepsOnePerArmAndOptionAndReadsBack() {
        val a = SameDirectionCap.Blocked(1_000L, day, "R51 Donchian 20", "NIFTY25000PE", -1, 101.5, live = false, applied = true, why = "same-direction cap: 2 puts | x")
        var l = SameDirectionCap.add(emptyList(), a)
        l = SameDirectionCap.add(l, a.copy(atMs = 61_000L))
        assertEquals(1, l.size, "the arm retrying its bar a minute later is the same would-be entry")
        l = SameDirectionCap.add(l, a.copy(atMs = 1_000L + SameDirectionCap.DEDUPE_MS))
        assertEquals(2, l.size, "a new break later is recorded again")
        l = SameDirectionCap.add(l, a.copy(live = true, applied = false))
        assertEquals(3, l.size)
        val back = l.map { SameDirectionCap.decode(it.encode()) }
        assertEquals(l.map { it.copy(why = it.why.replace('|', '/')) }, back)
        assertNull(SameDirectionCap.decode("nonsense"))
        assertEquals(300, (1..400).fold(emptyList<SameDirectionCap.Blocked>()) { acc, i -> SameDirectionCap.add(acc, a.copy(symbol = "S$i")) }.size)
        val line = SameDirectionCap.line(l, day, paperOn = true, max = 2, liveOn = false)
        assertTrue(line.contains("live: 1 would have been refused (logged only)"), line)
        assertTrue(SameDirectionCap.line(l, day.plusDays(1), paperOn = false, max = 3, liveOn = true).contains("paper off, at most 3; live on): no paper entry refused today"))
    }

    @Test fun theLiveLedgerCountsTheBotsLiveEntriesUntilTheyClose() {
        val ledger = SameDirectionCap.LiveLedger()
        ledger.entered("NIFTY25000PE", -1, "ORB", 1_000L)
        ledger.entered("BANKNIFTY55500PE", -1, "Liquidity 15+5 BANKNIFTY 15m", 2_000L)
        assertNotNull(SameDirectionCap.refusal(ledger.open(), -1, 2), "a third live put would be refused")
        ledger.exited("NIFTY25000PE")
        assertNull(SameDirectionCap.refusal(ledger.open(), -1, 2))
        // A stop that filled at Zerodha: the next positions read shows it gone.
        ledger.entered("FINNIFTY26000PE", -1, "Pine", 3_000L)
        ledger.reconcile(mapOf("FINNIFTY26000PE" to 65), readAtMs = 4_000L)
        assertEquals(1, ledger.open().size)
        // An entry sent after the read is not judged by it.
        ledger.entered("SENSEX81000PE", -1, "ORB", 5_000L)
        ledger.reconcile(emptyMap(), readAtMs = 4_500L)
        assertEquals(listOf("SENSEX81000PE"), ledger.open().map { it.symbol })
        ledger.exited("NOT-HELD")
        ledger.clear()
        assertTrue(ledger.open().isEmpty())
    }

    @Test fun theAutopilotEntryLowersRiskOnPaperAndItsUndoSwitchesItOff() {
        val on = AutoChange.sameDirCap(true)
        assertEquals(AutoChange.Kind.PAPER_FILTER, on.kind)
        assertEquals(AutoChange.SAME_DIR_TARGET, on.target)
        assertEquals(AutoChange.Scope.PAPER, on.scope)
        assertEquals(AutoChange.Risk.LOWER, AutoChange.riskOf(on))
        assertFalse(AutoChange.never(on))
        assertTrue(AutoChange.bossOwn(on.kind), "shown in the timeline as your ask, never undone by \"undo Autopilot's last change\"")
        val book0 = AutoChange.Book()
        val (book, entry) = AutoChange.record(book0, on, AutoChange.Status.KEPT, 1L, day, by = "your choice", note = "19 Oct")
        val u = assertNotNull(AutoChange.undo(book, entry.id))
        assertEquals(AutoChange.SWITCHED_OFF, u.inverse.to)
        assertEquals(AutoChange.SAME_DIR_TARGET, u.inverse.target)
        assertFalse(u.needsPin, "paper only: no PIN to undo")
        assertTrue(AutoChange.sameDirCap(false).reason.contains("off"))
    }

    /** 20 Oct, enhancement round 1 (idea 9): entries the cap only LOGGED are counted apart from the ones it refused. */
    @Test fun entriesOnlyLoggedAreCountedApartFromRefusals() {
        fun b(i: Int, live: Boolean, applied: Boolean, owner: String = "R51 Donchian 20", d: java.time.LocalDate = day) =
            SameDirectionCap.Blocked(i.toLong(), d, owner, "NIFTY$i", -1, 100.0, live = live, applied = applied, why = "cap")
        val l = listOf(b(1, false, true), b(2, false, false), b(3, false, false, owner = "Liquidity 15+5"), b(4, true, false), b(5, true, true),
            b(6, false, false, d = day.minusDays(1)))
        assertEquals(2 to 1, SameDirectionCap.onlyLogged(l, day))
        assertEquals(1 to 0, SameDirectionCap.onlyLogged(l, day.minusDays(1)))
        assertEquals(0 to 0, SameDirectionCap.onlyLogged(emptyList(), day))
        val line = SameDirectionCap.line(l, day, paperOn = true, max = 2, liveOn = false)
        assertTrue(line.contains("today 1 paper entry refused"), line)
        assertTrue(line.contains("2 paper entries only logged (let through: they could go live)"), line)
        // A Liquidity entry only logged was let through: it is in its plain-rules record, so it is not "counted apart".
        assertFalse(line.contains("Liquidity's"), line)
    }
}
