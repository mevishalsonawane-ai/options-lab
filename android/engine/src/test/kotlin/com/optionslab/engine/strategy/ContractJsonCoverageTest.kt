package com.optionslab.engine.strategy

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContractJsonCoverageTest {
    private val today = LocalDate.of(2026, 5, 4)

    private val mc = MasterContract(listOf(
        Instrument("GOLD", "MCX", "GOLD", null, null, 100, "FUT"),                 // root row itself
        Instrument("GOLDM26MAY26FUT", "MCX", "GOLDM", "26-MAY-26", null, 10, "FUT"),
        Instrument("GOLD05JUN26FUT", "MCX", "GOLDX", "05-JUN-26", null, 100, "FUT"),
        Instrument("GOLD05APR26FUT", "MCX", "GOLDX", "05-APR-26", null, 100, "FUT"),
        Instrument("GOLD26MAY26FUT", "MCX", "GOLDX", "26-MAY-26", null, 100, "FUT"),
        Instrument("NIFTY26MAY26FUT", "NFO", "NIFTY", "26-MAY-26", null, 65, "FUTIDX"),
        Instrument("NIFTY05MAY2623600CE", "NFO", "NIFTY", "05-MAY-26", 23600.0, 65, "CE"),
        Instrument("NIFTY05MAY2623550CE", "NFO", "NIFTY", "05-MAY-26", 23550.0, 65, "CE"),
        Instrument("NIFTY05MAY2623550CE", "NFO", "NIFTY", "05-MAY-26", 23550.0, 65, "CE"),
        Instrument("NIFTY28APR2623550CE", "NFO", "NIFTY", "28-APR-26", 23550.0, 65, "CE"),
        Instrument("NIFTY05MAY26XPE", "NFO", "NIFTY", "05-MAY-26", null, 65, "PE"),
        Instrument("NIFTY12MAY2623600CE", "NFO", "NIFTY", "garbled", 23600.0, 65, "CE"),
        Instrument("USDINR26MAY26FUT", "CDS", "USDINR", "26-MAY-26", null, 1000, "FUTCUR"),
        Instrument("USDINR26MAY2684CE", "CDS", "USDINR", "26-MAY-26", 84.0, 1000, "CE"),
        Instrument("BTC26MAY26FUT", "CRYPTO", null, "26-MAY-26", null, 1, "FUT"),
        Instrument("BTC26MAY2690000CE", "CRYPTO", null, "26-MAY-26", 90000.0, 1, "CE"),
        Instrument("BTC26MAY26000CE", "CRYPTO", null, "26-MAY-26", 0.0, 1, "CE"),
        Instrument("CRUDE26MAYFUT", "NCO", null, "26-MAY-26", null, 0, "FUTCOM"),
        Instrument("SILVERMAY26FUT", "MCX", "SILVERX", "26-MAY-26", null, 30, "FUTCOM"),
        Instrument("ZINC26MAY", "MCX", "ZINCX", "26-MAY-26", null, 5, "OPTFUT"),
        Instrument("RELIANCE", "NSE", "RELIANCE", null, null, 1, "EQ"),
    ))

    // ------------------------------------------------------------ lot sizes

    @Test fun `lot size - name first, then an anchored prefix, never a neighbour`() {
        for ((s, e) in listOf(null to "NFO", "" to "NFO", "NIFTY" to null, "NIFTY" to "")) assertNull(mc.lotSizeFor(s, e))
        assertNull(mc.lotSizeFor("RELIANCE", "NSE"), "cash trades in units")
        assertEquals(65, mc.lotSizeFor("nifty", "nfo"))
        assertEquals(10, mc.lotSizeFor("GOLDM", "MCX"))
        assertEquals(100, mc.lotSizeFor("GOLD", "MCX"), "root row itself, not GOLDM")
        assertEquals(1000, mc.lotSizeFor("USDINR", "CDS"))
        assertEquals(1, mc.lotSizeFor("BTC", "CRYPTO"), "prefix match with a digit after the root")
        assertNull(mc.lotSizeFor("CRUDE", "NCO"), "zero lot size is unknown")
        assertNull(mc.lotSizeFor("SILVER", "MCX"), "SILVERMAY: letter after the root")
        assertNull(mc.lotSizeFor("TCS", "NFO"))
    }

    @Test fun `contract existence and whole lots`() {
        assertFalse(mc.contractExists(null, "NFO")); assertFalse(mc.contractExists("", "NFO"))
        assertFalse(mc.contractExists("X", null)); assertFalse(mc.contractExists("X", ""))
        assertTrue(mc.contractExists("nifty26may26fut", "nfo"))
        assertFalse(mc.contractExists("NIFTY", "NFO"))
        assertTrue(mc.contractExists("ANYTHING", "BSE"), "no rows for the venue: not downloaded")
        assertEquals(true to 65, mc.quantityIsWholeLots(130, "NIFTY", "NFO"))
        assertEquals(false to 65, mc.quantityIsWholeLots(100, "NIFTY", "NFO"))
        assertEquals(false to 65, mc.quantityIsWholeLots(0, "NIFTY", "NFO"))
        assertEquals(true to null, mc.quantityIsWholeLots(7, "RELIANCE", "NSE"))
        assertEquals(true to null, mc.quantityIsWholeLots(7, "CRUDE", "NCO"))
    }

    @Test fun `resolve quantity accepts Python-ish values and refuses fabricated sizes`() {
        fun q(v: Any?, mode: String = "lots", s: String = "NIFTY", e: String = "NFO") = mc.resolveQuantity(v, mode, s, e)
        assertEquals(MasterContract.Quantity(65, 65, null), q(true))
        assertEquals("Quantity must be greater than zero", q(false).error)
        assertEquals(130, q(2L).quantity); assertEquals(195, q(3).quantity); assertEquals(130, q(2.9).quantity)
        assertEquals("nan is not a whole number", q(Double.NaN).error)
        assertEquals("inf is not a whole number", q(Double.POSITIVE_INFINITY).error)
        assertEquals(260, q(" 4 ").quantity)
        assertEquals(10, q("1_0", "units", "RELIANCE", "NSE").quantity)
        assertEquals("'x' is not a whole number", q("x").error)
        assertEquals("None is not a whole number", q(null).error)
        assertEquals("[1] is not a whole number", q(listOf(1)).error)
        assertEquals("Quantity must be greater than zero", q(-2).error)
        assertEquals("100 is not a whole number of lots; NIFTY trades in lots of 65", q(100, "units").error)
        assertEquals(MasterContract.Quantity(130, 65, null), q(130, "units"))
        assertEquals(MasterContract.Quantity(7, null, null), q(7, "units", "RELIANCE", "NSE"))
        assertTrue(q(2, "lots", "RELIANCE", "NSE").error!!.startsWith("No lot size is known for RELIANCE on NSE"))
    }

    // ------------------------------------------------------------ expiries

    @Test fun `expiry dates refuse bad input`() {
        assertEquals("Symbol parameter is required and cannot be empty", mc.expiryDates(" ", "NFO", "options", today).second)
        assertEquals("Exchange parameter is required and cannot be empty", mc.expiryDates("NIFTY", "", "options", today).second)
        assertEquals("Instrumenttype must be either \"futures\" or \"options\"", mc.expiryDates("NIFTY", "NFO", "equity", today).second)
        assertTrue(mc.expiryDates("NIFTY", "NSE", "futures", today).second!!.startsWith("Exchange must be one of: NFO, BFO"))
        assertEquals(emptyList<String>() to null, mc.expiryDates("TCS", "NFO", "options", today))
    }

    @Test fun `expiry dates per venue, live only, unparseable last`() {
        assertEquals(listOf("05-MAY-26", "garbled"), mc.expiryDates(" nifty ", "nfo", " Options ", today).first)
        assertEquals(listOf("26-MAY-26"), mc.expiryDates("NIFTY", "NFO", "futures", today).first)
        // MCX futures: GOLD and GOLDM both match the prefix; the anchored primary keeps only GOLD{date}.
        assertEquals(listOf("26-MAY-26", "05-JUN-26"), mc.expiryDates("GOLD", "MCX", "futures", today).first)
        assertEquals(listOf("26-MAY-26"), mc.expiryDates("USDINR", "CDS", "futures", today).first)
        assertEquals(listOf("26-MAY-26"), mc.expiryDates("USDINR", "CDS", "options", today).first)
        assertEquals(listOf("26-MAY-26"), mc.expiryDates("BTC", "CRYPTO", "futures", today).first)
        assertEquals(listOf("26-MAY-26"), mc.expiryDates("BTC", "CRYPTO", "options", today).first)
        // NCO has no instrument-type filter; the alternative pattern `{sym}DDMMMFUT` finds it.
        assertEquals(listOf("26-MAY-26"), mc.expiryDates("CRUDE", "NCO", "futures", today).first)
        // ...and, with no type filter on NCO, the `{sym}DDMMM` options alternative lists the same future's expiry.
        assertEquals(listOf("26-MAY-26"), mc.expiryDates("CRUDE", "NCO", "options", today).first)
        // `{sym}MMMYYFUT` alternative.
        assertEquals(listOf("26-MAY-26"), mc.expiryDates("SILVER", "MCX", "futures", today).first)
        // Options alternative `{sym}DDMMM`.
        assertEquals(listOf("26-MAY-26"), mc.expiryDates("ZINC", "MCX", "options", today).first)
        // Nothing matches any pattern.
        assertEquals(emptyList(), mc.expiryDates("NIFTY", "BFO", "options", today).first)
    }

    @Test fun `available strikes - exact ladder and the crypto listing`() {
        assertEquals(listOf(23550.0, 23600.0), mc.availableStrikes("NIFTY", "05may26", "ce", "nfo"))
        assertEquals(emptyList(), mc.availableStrikes("NIFTY", "05MAY26", "PE", "NFO"), "no strike on the PE row")
        assertEquals(listOf(90000.0), mc.availableStrikes("btc", "26MAY26", "CE", "CRYPTO"))
    }

    @Test fun `near-month futures and the underlying to quote`() {
        assertNull(mc.nearMonthFutures("", "MCX", today)); assertNull(mc.nearMonthFutures("GOLD", "", today))
        assertEquals("GOLD26MAY26FUT", mc.nearMonthFutures("gold", "mcx", today)!!.symbol)
        assertEquals("GOLD05JUN26FUT", mc.nearMonthFutures("GOLD", "MCX", LocalDate.of(2026, 5, 27))!!.symbol)
        assertNull(mc.nearMonthFutures("GOLD", "MCX", LocalDate.of(2026, 7, 1)))
        assertNull(mc.nearMonthFutures("SILVER", "MCX", today))
        assertEquals("NIFTY" to "NSE", mc.underlyingQuote("NIFTY", "nse", today))
        assertEquals("GOLD26MAY26FUT" to "MCX", mc.underlyingQuote("GOLD", "MCX", today))
        assertNull(mc.underlyingQuote("ZINC", "MCX", today))
    }

    @Test fun `exchange mapping`() {
        assertEquals("NFO", MasterContract.optionExchange("nse_index"))
        assertEquals("BFO", MasterContract.optionExchange("BSE"))
        for (x in listOf("MCX", "CDS", "NCO", "BCD", "NCDEX", "CRYPTO")) assertEquals(x, MasterContract.optionExchange(x.lowercase()))
        assertEquals("NFO", MasterContract.optionExchange("LSE"))
        assertEquals("NFO", MasterContract.derivativesExchange(null))
        assertEquals("MCX", MasterContract.derivativesExchange(" mcx "))
        assertEquals("BFO", MasterContract.derivativesExchange("BSE_INDEX"))
        assertEquals("NSE", MasterContract.CASH_EXCHANGE_FOR["NSE_INDEX"])
    }

    @Test fun `expiry parsing follows strptime`() {
        assertNull(Expiries.parseAny(null)); assertNull(Expiries.parseAny("  "))
        assertEquals(LocalDate.of(2026, 5, 5), Expiries.parseAny(" 5-may-26 "))
        assertEquals(LocalDate.of(2026, 5, 5), Expiries.parseAny("05-MAY-2026"))
        assertEquals(LocalDate.of(2026, 5, 5), Expiries.parseAny("05MAY26"))
        assertEquals(LocalDate.of(2026, 5, 5), Expiries.parseAny("5MAY2026"))
        assertEquals(LocalDate.of(1999, 5, 5), Expiries.parseAny("05-MAY-99"))
        assertEquals(LocalDate.of(2068, 5, 5), Expiries.parseAny("05-MAY-68"))
        assertNull(Expiries.parseAny("05-XYZ-26")); assertNull(Expiries.parseAny("31-FEB-26")); assertNull(Expiries.parseAny("2026-05-05"))
        assertEquals(LocalDate.of(2026, 5, 5), Expiries.parseDashed("05-May-2026"))
        assertNull(Expiries.parseDashed("05MAY26"))
        assertEquals(LocalDate.of(2026, 5, 5), Expiries.parseStrict("05-MAY-26"))
        assertNull(Expiries.parseStrict("05-MAY-2026"))
        assertEquals("05MAY26", Expiries.symbolForm(LocalDate.of(2026, 5, 5)))
        assertEquals("31DEC99", Expiries.symbolForm(LocalDate.of(1999, 12, 31)))
    }

    // ------------------------------------------------------------ Py

    @Test fun `python repr and str`() {
        assertEquals("None", Py.repr(null))
        assertEquals("True", Py.repr(true)); assertEquals("False", Py.repr(false))
        assertEquals("5.0", Py.repr(5.0)); assertEquals("2.5", Py.repr(2.5f)); assertEquals("7", Py.repr(7L))
        assertEquals("{'a': 1, 'b': [None, 'x']}", Py.repr(linkedMapOf("a" to 1, "b" to listOf(null, "x"))))
        assertEquals("[1, 2]", Py.str(listOf(1, 2))); assertEquals("{'k': 'v'}", Py.str(mapOf("k" to "v")))
        assertEquals("abc", Py.str("abc")); assertEquals("5.0", Py.str(5.0))
        assertEquals("x", Py.repr(object { override fun toString() = "x" }))
        assertEquals("\"it's\"", Py.repr("it's"))
        assertEquals("'say \"hi\"'", Py.repr("say \"hi\""))
        assertEquals("'both \\' and \"'", Py.repr("both ' and \""))
        assertEquals("'a\\\\b\\n\\r\\t\\x01\\x7f'", Py.repr("a\\b\n\r\t\u0001\u007f"))
        assertEquals(12L, Py.parseInt(" +1_2 ")); assertEquals(-3L, Py.parseInt("-3"))
        assertNull(Py.parseInt("1__2")); assertNull(Py.parseInt("_1")); assertNull(Py.parseInt("1.0"))
        assertNull(Py.parseInt("99999999999999999999"), "beyond Long: not representable")
        assertEquals(2, Py.len("a\uD83D\uDE00"))
    }

    // ------------------------------------------------------------ Json

    @Test fun `json writes every value kind`() {
        val out = Json.write(linkedMapOf(
            "n" to null, "t" to true, "f" to false, "i" to 1, "l" to 2L, "s" to 3.toShort(), "b" to 4.toByte(),
            "d" to 5.0, "fl" to 1.5f, "nan" to Double.NaN, "inf" to Double.POSITIVE_INFINITY, "ninf" to Double.NEGATIVE_INFINITY,
            "e" to RunMode.LIVE, "arr" to arrayOf(1, "x"), "set" to linkedSetOf(1),
            "str" to "q\"b\\n\nr\rt\tb\bf\u000Cc\u0001é",
        ))
        assertEquals("""{"n":null,"t":true,"f":false,"i":1,"l":2,"s":3,"b":4,"d":5.0,"fl":1.5,"nan":NaN,"inf":Infinity,"ninf":-Infinity,"e":"LIVE","arr":[1,"x"],"set":[1],"str":"q\"b\\n\nr\rt\tb\bf\fc\u0001é"}""", out)
        assertFailsWith<IllegalArgumentException> { Json.write(Any()) }
    }

    @Test fun `json parses what it writes and every escape`() {
        val text = """ { "a" : [ 1 , -2.5e1 , 3E2 , -Infinity , NaN , Infinity , true , false , null , { } , [ ] ] ,
            "s" : "q\"\\\/\b\f\n\r\t\u00e9x" } """
        val v = Json.parse(text) as Map<*, *>
        val a = v["a"] as List<*>
        assertEquals(listOf<Any?>(1L, -25.0, 300.0, Double.NEGATIVE_INFINITY), a.take(4))
        assertTrue((a[4] as Double).isNaN())
        assertEquals(listOf<Any?>(Double.POSITIVE_INFINITY, true, false, null, emptyMap<String, Any?>(), emptyList<Any?>()), a.drop(5))
        assertEquals("q\"\\/\b\u000C\n\r\téx", v["s"])
        val round = linkedMapOf("x" to listOf(1L, 2.0, "s\u0002"), "y" to linkedMapOf("z" to null))
        assertEquals(round, Json.parse(Json.write(round)))
        assertEquals(12345678901234L, Json.parse("12345678901234"))
        assertEquals(1e30, Json.parse("1000000000000000000000000000000"), "integral but beyond Long: read as a double")
    }

    @Test fun `json refuses malformed text with ParseError`() {
        for (bad in listOf("", "  ", "[1] x", "tru", "nul", "Nan", "Inf", "@", "{1:2}", "{\"a\" 1}", "{\"a\":1 \"b\":2}", "[1 2]",
            "\"abc", "\"\\q\"", "-", "1.2.3", "--1")) {
            assertFailsWith<Json.ParseError>(bad) { Json.parse(bad) }
        }
    }

    @Test fun `BUG - truncated json is a ParseError`() {
        for (bad in listOf("{", "[1", "{\"a\":1", "[", "\"\\u12")) {
            assertFailsWith<Json.ParseError>(bad) { Json.parse(bad) }
        }
    }

    @Test fun `typed reads over a parsed object`() {
        val o = JsonObj(mapOf("s" to "x", "d" to 2L, "b" to true, "o" to mapOf("k" to 1L), "l" to listOf(1L), "n" to null))
        assertTrue(o.has("s")); assertFalse(o.has("n")); assertFalse(o.has("missing"))
        assertEquals("x", o.reqStr("s")); assertEquals("2", o.str("d"))
        assertEquals("missing q", assertFailsWith<Json.ParseError> { o.reqStr("q") }.message)
        assertEquals(2.0, o.dbl("d")); assertEquals(2, o.int("d")); assertEquals(2L, o.long("d")); assertNull(o.long("n"))
        assertEquals(true, o.bool("b")); assertEquals(1L, o.obj("o")!!.long("k")); assertNull(o.obj("n"))
        assertEquals(listOf<Any?>(1L), o.list("l"))
    }

    // ------------------------------------------------------------ Host

    private val monday = ZonedDateTime.of(2026, 5, 4, 10, 0, 0, 0, IST)
    private val chain = MasterContract(buildList {
        for (i in 0..8) {
            val s = 23400.0 + 50 * i
            add(Instrument("NIFTY05MAY26${s.toInt()}CE", "NFO", "NIFTY", "05-MAY-26", s, 65, "CE"))
            add(Instrument("NIFTY05MAY26${s.toInt()}PE", "NFO", "NIFTY", "05-MAY-26", s, 65, "PE"))
        }
    })
    private val def = StrategyDef(
        id = 7, name = "Straddle", underlying = "NIFTY", underlyingExchange = "NSE_INDEX",
        entryTime = LocalTime.of(9, 20), exitTime = LocalTime.of(15, 15),
        legs = listOf(
            LegDef(1, Segment.OPTIONS, Position.S, 1, OptionType.CE, StrikeMode.ATM, "ATM", expiry = "weekly", slPts = 10.0),
            LegDef(2, Segment.OPTIONS, Position.S, 1, OptionType.PE, StrikeMode.ATM, "ATM", expiry = "weekly", slPts = 10.0),
        ),
    )

    /** A venue that leaves orders working until told how they end. */
    private class SlowVenue : StrategyHost.Executor {
        val placed = ArrayList<Action.PlaceOrder>()
        val cancels = ArrayList<String>()
        var fillStatus: StrategyHost.Status? = null
        var statusOverride: MutableMap<String, StrategyHost.Status?> = HashMap()
        var cancelOk = true
        val events = ArrayList<Pair<Event, Boolean>>()
        override fun place(order: Action.PlaceOrder): StrategyHost.Placed {
            placed += order
            return StrategyHost.Placed.Accepted("B${placed.size}", "OPEN", 0, null)
        }
        override fun cancel(brokerId: String): Boolean { cancels += brokerId; return cancelOk }
        override fun status(brokerId: String) = if (brokerId in statusOverride) statusOverride[brokerId] else fillStatus
        override fun event(e: Event, alert: Boolean) { events += e to alert }
    }

    @Test fun `host polls working orders, cancels them on stop and closes a leg`() {
        val host = StrategyHost()
        val venue = SlowVenue()
        val ids = HashMap<Long, String>()
        var checkpoints = 0
        var run = host.start(def, SymbolResolver.resolve(def, chain, 23590.0, monday), monday, 1, RunMode.SANDBOX, "manual", venue, ids) { checkpoints++ }
        assertEquals(2, venue.placed.size)
        assertEquals(3, checkpoints, "once before the first order and once per accepted order")
        assertEquals(RunStatus.ENTERING, run.status)
        // Poll: the venue has no news for B1 and an unknown id answers null.
        venue.statusOverride["B1"] = null
        venue.fillStatus = StrategyHost.Status("COMPLETE", 65, 100.0)
        run = host.tick(run, def, emptyList(), monday, null, venue, ids)
        assertEquals("open", run.leg(2)!!.status); assertEquals("complete", run.leg(2)!!.entryStatus)
        assertEquals("open", run.leg(1)!!.entryStatus)
        // Stop: leg 1's working entry is cancelled at the venue and reads back as cancelled (status null -> "cancelled").
        venue.statusOverride["B1"] = null
        venue.fillStatus = StrategyHost.Status("COMPLETE", 65, 101.0)
        run = host.stop(run, def, monday, "manual", venue, ids)
        assertEquals(listOf("B1"), venue.cancels)
        assertEquals("cancelled", run.orders[ids.entries.first { it.value == "B1" }.key]!!.status)
        assertEquals(RunStatus.EXITING, run.status, "leg 2's exit is still working")
        assertEquals("exit_close_all", venue.placed.last().kind)
        run = host.tick(run, def, emptyList(), monday, null, venue, ids)
        assertEquals(RunStatus.CLOSED, run.status)
        assertTrue(venue.events.any { it.first.kind == "run_stopped" })
    }

    @Test fun `host skips a cancel it cannot route or the venue refuses`() {
        val host = StrategyHost()
        val venue = SlowVenue()
        val ids = HashMap<Long, String>()
        val run = host.start(def, SymbolResolver.resolve(def, chain, 23590.0, monday), monday, 1, RunMode.SANDBOX, "manual", venue, ids)
        venue.cancelOk = false
        val stopped = host.stop(run, def, monday, "manual", venue, ids)
        assertEquals(2, venue.cancels.size)
        assertEquals(RunStatus.EXITING, stopped.status)
        ids.clear()
        venue.cancels.clear()
        host.stop(run, def, monday, "manual", venue, ids)
        assertTrue(venue.cancels.isEmpty(), "no broker id, nothing to cancel")
    }

    @Test fun `host closeLeg and alerts reach the executor`() {
        val host = StrategyHost()
        val venue = SlowVenue()
        val ids = HashMap<Long, String>()
        var run = host.start(def, SymbolResolver.resolve(def, chain, 23590.0, monday), monday, 1, RunMode.SANDBOX, "manual", venue, ids)
        venue.fillStatus = StrategyHost.Status("COMPLETE", 65, 100.0)
        run = host.poll(run, def, venue, monday, ids)
        assertEquals(RunStatus.ACTIVE, run.status)
        run = host.closeLeg(run, def, 1, monday, venue, ids)
        assertEquals("exit_leg_manual", venue.placed.last().kind)
        run = host.poll(run, def, venue, monday, ids)
        assertEquals("closed", run.leg(1)!!.status)
        // A late fill on the closed run reaches the executor as an alert.
        host.closeLeg(run, def, 1, monday, venue, ids)
        assertTrue(venue.events.any { it.second.not() && it.first.message == "That leg is not open" })
    }

    @Test fun `validator check keeps id and live switch and passes the lot lookup through`() {
        val ok = StrategyValidator.check(def.copy(id = 42, liveEnabled = true)) { _, _ -> 65 }
        assertTrue(ok is StrategyValidator.Result.Ok)
        assertEquals(42L, (ok as StrategyValidator.Result.Ok).def.id)
    }
}
