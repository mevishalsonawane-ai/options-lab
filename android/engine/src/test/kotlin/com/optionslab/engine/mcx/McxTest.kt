package com.optionslab.engine.mcx

import com.optionslab.engine.Kite
import com.optionslab.engine.Right
import com.optionslab.engine.risk.AccountGuard
import com.optionslab.engine.sandbox.Instrument
import com.optionslab.engine.sandbox.InstrumentMaster
import com.optionslab.engine.sandbox.OrderRequest
import com.optionslab.engine.sandbox.PaperSpread
import com.optionslab.engine.sandbox.Quote
import com.optionslab.engine.sandbox.Sandbox
import com.optionslab.engine.sandbox.SandboxConfig
import com.optionslab.engine.sandbox.SandboxCosts
import com.optionslab.engine.sandbox.SandboxRules
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** MCX: contracts, session times (US clock change), costs, margins, expiry safety, paper fills (research/MCX_GUIDE.md). */
class McxTest {
    private fun d(s: String) = LocalDate.parse(s)
    private fun t(s: String) = LocalDateTime.parse(s)

    // ---- the instrument lists --------------------------------------------------------------------------------------------

    private val kiteCsv = """
        instrument_token,exchange_token,tradingsymbol,name,last_price,expiry,strike,tick_size,lot_size,instrument_type,segment,exchange
        273417,1068,MCXGOLDEX,"MCXGOLDEX",0,,0,0,1,EQ,INDICES,MCX
        111,570001,CRUDEOIL26OCTFUT,"CRUDEOIL",0,2026-10-19,0,1,1,FUT,MCX-FUT,MCX
        112,570002,CRUDEOIL26NOVFUT,"CRUDEOIL",0,2026-11-19,0,1,1,FUT,MCX-FUT,MCX
        113,570003,CRUDEOIL26DECFUT,"CRUDEOIL",0,2026-12-18,0,1,1,FUT,MCX-FUT,MCX
        148600327,580470,CRUDEOIL26OCT8550CE,"CRUDEOIL",0,2026-10-15,8550,0.1,1,CE,MCX-OPT,MCX
        148600328,580471,CRUDEOIL26OCT8550PE,"CRUDEOIL",0,2026-10-15,8550,0.1,1,PE,MCX-OPT,MCX
        148600329,580472,CRUDEOIL26NOV8550CE,"CRUDEOIL",0,2026-11-17,8550,0.1,1,CE,MCX-OPT,MCX
        201,560001,GOLD26DECFUT,"GOLD",0,2026-12-04,0,1,1,FUT,MCX-FUT,MCX
        202,560002,GOLD26OCT150000CE,"GOLD",0,2026-10-30,150000,0.5,1,CE,MCX-OPT,MCX
        301,590001,COTTON26NOVFUT,"COTTON",0,2026-11-30,0,10,1,FUT,MCX-FUT,MCX
        bad,590002,BADTOKEN26NOVFUT,"CRUDEOIL",0,2026-11-30,0,1,1,FUT,MCX-FUT,MCX
        401,bad,BADXT26NOVFUT,"CRUDEOIL",0,2026-11-30,0,1,1,FUT,MCX-FUT,MCX
        402,590003,BADEXP26NOVFUT,"CRUDEOIL",0,never,0,1,1,FUT,MCX-FUT,MCX
        403,590004,ODD,"CRUDEOIL",0,2026-11-30,0,1,1,XX,MCX-FUT,MCX
        404,590005,MISMATCH,"CRUDEOIL",0,2026-11-30,0,1,1,CE,MCX-FUT,MCX
        405,590006,NOTICK26NOVFUT,"NICKEL",0,2026-11-18,0,0,1,FUT,MCX-FUT,MCX
        406,590007,NOTICKOPT,"ZINC",0,2026-10-23,420,,1,CE,MCX-OPT,MCX
        407,590008,NOTICKUNKNOWN,"ZINCMINI",0,2026-10-23,420,,1,CE,MCX-OPT,MCX

    """.trimIndent()

    private fun parsed(mult: Map<Long, Int> = emptyMap(), names: Set<String>? = null) = McxInstruments.parseKite(kiteCsv.lineSequence(), names, mult)

    @Test fun zerodhasListGivesFuturesAndOptionsWithTheTablesMultipliers() {
        val all = parsed()
        // COTTON is not in the table (no multiplier known): skipped; the broken rows are skipped, never guessed.
        assertEquals(listOf("CRUDEOIL26OCTFUT", "CRUDEOIL26NOVFUT", "CRUDEOIL26DECFUT", "CRUDEOIL26OCT8550CE", "CRUDEOIL26OCT8550PE",
            "CRUDEOIL26NOV8550CE", "GOLD26DECFUT", "GOLD26OCT150000CE", "NOTICK26NOVFUT", "NOTICKOPT", "NOTICKUNKNOWN"), all.map { it.tradingSymbol })
        val ce = all.first { it.tradingSymbol == "CRUDEOIL26OCT8550CE" }
        assertEquals(100, ce.multiplier); assertEquals(0.1, ce.tick); assertEquals(Right.CE, ce.right); assertTrue(ce.isOption); assertFalse(ce.isFuture)
        assertEquals("MCX:CRUDEOIL26OCT8550CE", ce.kiteKey); assertEquals("MCX_FO|580470", ce.upstoxKey)
        assertEquals("CRUDEOIL15OCT268550CE", ce.paperSymbol); assertEquals("CRUDEOIL 15 OCT 8550 CE", ce.label)
        assertEquals(2, ce.kiteQuantity(250)); assertEquals(0, ce.copy(multiplier = 0).kiteQuantity(100))
        val fut = all.first()
        assertEquals("CRUDEOIL19OCT26FUT", fut.paperSymbol); assertEquals("CRUDEOIL 19 OCT FUT", fut.label); assertNull(fut.right)
        // A zero tick in the list: the table's (futures 0.10 for nickel; zinc options 0.01); unknown option tick: 0.05.
        assertEquals(0.10, all.first { it.tradingSymbol == "NOTICK26NOVFUT" }.tick)
        assertEquals(0.01, all.first { it.tradingSymbol == "NOTICKOPT" }.tick)
        assertEquals(0.05, all.first { it.tradingSymbol == "NOTICKUNKNOWN" }.tick)
        // The live multiplier wins over the table, and a name filter keeps only those.
        assertEquals(7, parsed(mapOf(570001L to 7L.toInt())).first().multiplier)
        assertEquals(setOf("GOLD"), parsed(names = setOf("GOLD")).map { it.name }.toSet())
        assertEquals(emptyList(), McxInstruments.parseKite(sequenceOf("", "only,a,header")))
    }

    private val upstoxJson = """[
        {"segment":"MCX_FO","instrument_type":"FUT","exchange_token":"570001","trading_symbol":"CRUDEOIL FUT 19 OCT 26","qty_multiplier":100.0,"expiry":1792434599000,"tick_size":100.0,"strike_price":0.0},
        {"segment":"MCX_FO","instrument_type":"CE","exchange_token":"580470","trading_symbol":"CRUDEOIL 8550 CE 15 OCT 26","qty_multiplier":100.0,"expiry":1792088999000,"tick_size":10.0,"strike_price":8550.0},
        {"segment":"MCX_FO","instrument_type":"PE","exchange_token":584953,"trading_symbol":"CRUDEOILM 8550 PE 15 OCT 26","qty_multiplier":10.0,"expiry":1792088999000,"tick_size":0.0,"strike_price":8550},
        {"segment":"MCX_FO","instrument_type":"FUT","exchange_token":"1","trading_symbol":"GOLDGUINEA FUT 30 OCT 26","qty_multiplier":1.0,"expiry":1793385000000,"tick_size":100.0},
        {"segment":"MCX_FO","instrument_type":"FUT","exchange_token":"2","trading_symbol":"ODDLOT FUT","qty_multiplier":2.5,"expiry":1793385000000},
        {"segment":"MCX_FO","instrument_type":"FUT","exchange_token":"3","trading_symbol":"NOEXPIRY FUT","qty_multiplier":2.0},
        {"segment":"MCX_FO","instrument_type":"FUT","exchange_token":"4","trading_symbol":"","qty_multiplier":2.0,"expiry":1},
        {"segment":"MCX_FO","instrument_type":"FUT","exchange_token":"x","trading_symbol":"BAD FUT","qty_multiplier":2.0,"expiry":1},
        {"segment":"MCX_FO","instrument_type":"OPTIDX","exchange_token":"5","trading_symbol":"X","qty_multiplier":2.0,"expiry":1},
        {"segment":"NSE_FO","instrument_type":"FUT","exchange_token":"6","trading_symbol":"NIFTY FUT","qty_multiplier":75.0,"expiry":1},
        "not an object"
    ]"""

    @Test fun upstoxsListGivesMultipliersAndAFallbackList() {
        val rows = McxInstruments.parseUpstox(upstoxJson)
        assertEquals(listOf(570001L, 580470L, 584953L, 1L), rows.map { it.exchangeToken })
        assertEquals(mapOf(570001L to 100, 580470L to 100, 584953L to 10, 1L to 1), McxInstruments.multipliers(rows))
        assertEquals(d("2026-10-19"), rows[0].expiry)
        assertEquals(0.10, rows[1].tick, 1e-12)
        assertEquals("CRUDEOILM", rows[2].name)
        assertEquals(emptyList(), McxInstruments.parseUpstox("{}"))
        val list = McxInstruments.fromUpstox(rows)
        assertEquals(listOf("CRUDEOIL26OCTFUT", "CRUDEOIL26OCT8550CE", "CRUDEOILM26OCT8550PE", "GOLDGUINEA26OCTFUT"), list.map { it.tradingSymbol })
        // A zero tick from Upstox: the table's option tick (CRUDEOILM 0.05); the token is 0 (no stream without Zerodha's list).
        assertEquals(0.05, list[2].tick); assertEquals(0L, list[2].token)
        assertEquals(1, McxInstruments.fromUpstox(rows, setOf("GOLDGUINEA")).size)
        assertEquals(0.05, McxInstruments.fromUpstox(listOf(rows[0].copy(name = "UNKNOWN", tick = 0.0))).single().tick)
        assertEquals(0.05, McxInstruments.fromUpstox(listOf(rows[1].copy(name = "UNKNOWN", tick = 0.0))).single().tick)
        assertEquals(1.0, McxInstruments.fromUpstox(listOf(rows[0].copy(tick = 0.0))).single().tick)
    }

    @Test fun nearAndNextFuturesTheChainAndTheFutureAnOptionTurnsInto() {
        val all = parsed()
        val day = d("2026-10-08")
        assertEquals(listOf("CRUDEOIL26OCTFUT", "CRUDEOIL26NOVFUT"), McxInstruments.futures(all, "CRUDEOIL", day).map { it.tradingSymbol })
        assertEquals(listOf("CRUDEOIL26NOVFUT", "CRUDEOIL26DECFUT"), McxInstruments.futures(all, "CRUDEOIL", d("2026-10-20")).map { it.tradingSymbol })
        assertEquals(d("2026-10-15"), McxInstruments.nearOptionExpiry(all, "CRUDEOIL", day))
        assertNull(McxInstruments.nearOptionExpiry(all, "SILVERM", day))
        assertEquals(listOf("CRUDEOIL26OCT8550CE", "CRUDEOIL26OCT8550PE"), McxInstruments.nearChain(all, "CRUDEOIL", day).map { it.tradingSymbol })
        assertEquals(emptyList(), McxInstruments.nearChain(all, "SILVERM", day))
        // Crude's October options -> the October future; gold's October options -> the December (bi-monthly) future.
        val ce = all.first { it.tradingSymbol == "CRUDEOIL26OCT8550CE" }
        assertEquals("CRUDEOIL26OCTFUT", McxInstruments.underlyingFuture(all, ce)?.tradingSymbol)
        assertEquals("GOLD26DECFUT", McxInstruments.underlyingFuture(all, all.first { it.tradingSymbol == "GOLD26OCT150000CE" })?.tradingSymbol)
        assertEquals(listOf("CRUDEOIL", "GOLD", "ZINC", "ZINCMINI"), McxInstruments.chainNames(all, day))
        assertEquals("CRUDEOIL26OCT8550CE", McxInstruments.find(all, "crudeoil26oct8550ce")?.tradingSymbol)
        assertEquals("CRUDEOIL26OCT8550CE", McxInstruments.find(all, "CRUDEOIL15OCT268550CE")?.tradingSymbol)
        assertNull(McxInstruments.find(all, "NOPE"))
        assertEquals("CRUDEOIL26OCT8550CE", McxInstruments.kiteSymbol("crudeoil", d("2026-10-15"), 8550.0, Right.CE))
        assertEquals("GOLDM26NOVFUT", McxInstruments.kiteSymbol("GOLDM", d("2026-11-05"), 0.0, null))
    }

    @Test fun theTableKnowsEveryListedCommodity() {
        assertEquals(20, Mcx.NAMES.size)
        assertEquals(Mcx.CHAIN_FIRST, Mcx.NAMES.filter { it in Mcx.CHAIN_FIRST }.let { Mcx.CHAIN_FIRST })
        assertTrue(Mcx.isMcxName("goldm")); assertFalse(Mcx.isMcxName("NIFTY"))
        assertTrue(Mcx.physical("GOLDM")); assertFalse(Mcx.physical("CRUDEOIL")); assertTrue(Mcx.physical("UNKNOWN"))
        assertEquals(1250, Mcx.fallbackMultiplier("NATURALGAS")); assertNull(Mcx.fallbackMultiplier("NIFTY"))
        assertEquals("Crude oil", Mcx.commodity("CRUDEOIL")?.label)
        assertEquals(d("2026-10-08"), Mcx.TABLE_AS_OF)
    }

    // ---- session times ---------------------------------------------------------------------------------------------------

    @Test fun theCloseFollowsTheUsClockChange() {
        // US summer time ends Sunday 1 Nov 2026: 23:30 up to Friday 30 Oct, 23:55 from Monday 2 Nov.
        assertEquals(LocalTime.of(23, 30), McxSession.close(d("2026-10-30")))
        assertEquals(LocalTime.of(23, 30), McxSession.close(d("2026-10-31")))
        assertEquals(LocalTime.of(23, 55), McxSession.close(d("2026-11-02")))
        assertEquals(LocalTime.of(23, 45), McxSession.misCut(d("2026-11-02")))
        assertEquals(LocalTime.of(23, 20), McxSession.misCut(d("2026-10-08")))
        // And back in March: 23:55 to Friday 6 Mar 2026, 23:30 from Monday 9 Mar (US summer time from Sunday 8 Mar).
        assertEquals(LocalTime.of(23, 55), McxSession.close(d("2026-03-06")))
        assertEquals(LocalTime.of(23, 30), McxSession.close(d("2026-03-09")))
        // 2027: summer time from Sunday 14 Mar, so Friday 12 Mar still closes at 23:55.
        assertEquals(LocalTime.of(23, 55), McxSession.close(d("2027-03-12")))
        assertEquals(LocalTime.of(23, 30), McxSession.close(d("2027-03-15")))
        assertTrue(McxSession.usWinter(d("2026-12-25"))); assertFalse(McxSession.usWinter(d("2026-07-01")))
    }

    @Test fun theCalendarKnowsEveningOnlyHolidaysAndMuhurat() {
        val cal = McxSession.DEFAULT
        // An ordinary Thursday: 09:00-23:30.
        assertEquals(McxSession.Window(LocalTime.of(9, 0), LocalTime.of(23, 30)), cal.window(d("2026-10-08")))
        assertTrue(cal.isOpen(t("2026-10-08T09:00"))); assertFalse(cal.isOpen(t("2026-10-08T08:59")))
        assertTrue(cal.isOpen(t("2026-10-08T23:29"))); assertFalse(cal.isOpen(t("2026-10-08T23:30")))
        // In US winter the same evening runs to 23:55.
        assertTrue(cal.isOpen(t("2026-11-03T23:50")))
        // Dussehra (an NSE holiday): MCX shut in the morning, open 17:00-23:30.
        assertFalse(cal.isOpen(t("2026-10-20T10:00"))); assertTrue(cal.isOpen(t("2026-10-20T17:00")))
        // Gandhi Jayanti and Christmas: shut all day; a Saturday: shut.
        assertNull(cal.window(d("2026-10-02"))); assertNull(cal.window(d("2026-12-25"))); assertNull(cal.window(d("2026-10-10")))
        // Sunday 8 Nov: the Diwali Muhurat hour.
        assertTrue(cal.isOpen(t("2026-11-08T18:30"))); assertFalse(cal.isOpen(t("2026-11-08T19:00")))
        assertTrue(cal.isTradingDay(d("2026-11-08"))); assertFalse(cal.isTradingDay(d("2026-11-07")))
        // Trading days back from Monday 2 Nov: Fri 30 Oct, Thu 29 Oct.
        assertEquals(d("2026-10-30"), cal.tradingDaysBefore(d("2026-11-02"), 1))
        assertEquals(d("2026-10-29"), cal.tradingDaysBefore(d("2026-11-02"), 2))
        assertEquals(d("2026-10-05"), cal.onOrAfter(d("2026-10-03")))
        assertEquals(d("2026-10-05"), cal.onOrAfter(d("2026-10-05")))
        // A day the owner marked shut, and the listed days ahead.
        val mine = McxSession.Calendar(added = setOf(d("2026-10-09")))
        assertNull(mine.window(d("2026-10-09")))
        val ahead = mine.upcoming(d("2026-10-09"))
        assertEquals(d("2026-10-09"), ahead.first().first)
        assertTrue(ahead.any { it.first == d("2026-12-25") && it.second == null })
        assertEquals("09:00-23:30", McxSession.say(cal.window(d("2026-10-08")))); assertEquals("shut", McxSession.say(null))
        assertTrue(McxSession.Window(LocalTime.of(9, 0), LocalTime.of(10, 0)).contains(LocalTime.of(9, 30)))
    }

    private val holidaysJson = """{"status":"success","data":[
        {"date":"2026-10-02","description":"Gandhi Jayanti","holiday_type":"TRADING_HOLIDAY","closed_exchanges":["NSE","MCX"],"open_exchanges":[]},
        {"date":"2026-10-20","description":"Dussehra","holiday_type":"TRADING_HOLIDAY","closed_exchanges":["NSE"],"open_exchanges":[{"exchange":"MCX","start_time":1792495800000,"end_time":1792519200000}]},
        {"date":"2026-09-14","description":"Ganesh","closed_exchanges":["NSE"],"open_exchanges":[{"exchange":"MCX","start_time":1789356600000,"end_time":1789408800000}]},
        {"date":"2026-11-08","description":"Muhurat","holiday_type":"SPECIAL_TIMING","closed_exchanges":[],"open_exchanges":[{"exchange":"NSE","start_time":1,"end_time":2},{"exchange":"MCX","start_time":1794141000000,"end_time":1794144600000}]},
        {"date":"2026-10-12","description":"NSE only","closed_exchanges":["NSE"],"open_exchanges":[{"exchange":"NSCOM","start_time":1,"end_time":2}]},
        {"date":"2026-10-13","closed_exchanges":["NSE"],"open_exchanges":[{"exchange":"MCX","start_time":"x","end_time":2}]},
        {"date":"2026-10-14","closed_exchanges":["NSE"],"open_exchanges":[{"exchange":"MCX","start_time":1}]},
        {"date":"not a date","closed_exchanges":["MCX"]},
        {"closed_exchanges":["MCX"]},
        "junk"
    ]}"""

    @Test fun upstoxsHolidayListGivesMcxsOwnHours() {
        val m = McxSession.parseUpstox(holidaysJson)
        assertEquals(setOf(d("2026-10-02"), d("2026-10-20"), d("2026-11-08")), m.keys)
        assertNull(m[d("2026-10-02")])
        assertEquals(McxSession.Window(LocalTime.of(17, 0), LocalTime.of(23, 30)), m[d("2026-10-20")])
        assertEquals(McxSession.Window(LocalTime.of(18, 0), LocalTime.of(19, 0)), m[d("2026-11-08")])
        assertEquals(emptyMap(), McxSession.parseUpstox("not json"))
        assertEquals(emptyMap(), McxSession.parseUpstox("{}"))
        // A fetched year replaces the built-in list for that year (Dussehra comes only from the fetched list here).
        val cal = McxSession.Calendar(fetched = mapOf(d("2026-10-20") to McxSession.Window(LocalTime.of(17, 0), LocalTime.of(23, 30))))
        assertTrue(cal.isTradingDay(d("2026-10-02")), "2026 is fetched: the built-in Gandhi Jayanti is not used")
        assertFalse(cal.isOpen(t("2026-10-20T12:00")))
        assertEquals(listOf(d("2026-10-20")), cal.upcoming(d("2026-10-01")).map { it.first })
    }

    // ---- costs -------------------------------------------------------------------------------------------------------------

    @Test fun chargesMatchTheGuidesRoundTrips() {
        // CRUDEOIL future, 1 lot at 8,552: Rs 194 a round trip (research/MCX_GUIDE.md 1e).
        assertEquals(194.0, McxCosts.roundTrip(8552.0, 100, option = false), 0.6)
        // CRUDEOIL ATM option, Rs 24,200 of premium a lot: Rs 84.
        assertEquals(84.0, McxCosts.roundTrip(242.0, 100, option = true), 0.6)
        // Brokerage on a small future is 0.03% (under Rs 20): GOLDPETAL at 14,887.
        val b = McxCosts.breakdown("BUY", 14_887.0, option = false)
        assertEquals(14_887.0 * 0.0003, b.getValue("Brokerage"), 1e-9)
        assertEquals(0.0, b.getValue("CTT")); assertEquals(14_887.0 * 0.00002, b.getValue("Stamp duty"), 1e-9)
        val s = McxCosts.breakdown("SELL", 10_000.0, option = true)
        assertEquals(5.0, s.getValue("CTT"), 1e-9); assertEquals(0.0, s.getValue("Stamp duty"))
        assertEquals(emptyMap(), McxCosts.breakdown("BUY", 0.0, true))
        assertEquals(emptyMap(), McxCosts.breakdown("BUY", Double.NaN, true))
        assertEquals(BigDecimal("0.00"), McxCosts.charge("BUY", 0.0, true))
        // The sandbox's schedule switches on the exchange; NFO is unchanged.
        assertEquals(McxCosts.charge("SELL", 24_200.0, true), SandboxCosts.charge("SELL", BigDecimal("242"), 100, BigDecimal.ONE, "MCX", "CRUDEOIL15OCT268550CE"))
        assertEquals(McxCosts.charge("SELL", 855_200.0, false), SandboxCosts.charge("SELL", BigDecimal("8552"), 100, BigDecimal.ONE, "mcx", "CRUDEOIL19OCT26FUT"))
        assertEquals(SandboxCosts.charge("BUY", BigDecimal("100"), 75), SandboxCosts.charge("BUY", BigDecimal("100"), 75, BigDecimal.ONE, "NFO", "NIFTY28OCT2625000CE"))
        assertEquals(McxCosts.breakdown("BUY", 24_200.0, true), SandboxCosts.breakdown("BUY", 242.0, 100, "MCX", "CRUDEOIL15OCT268550PE"))
        assertEquals(SandboxCosts.breakdown("BUY", 100.0, 75), SandboxCosts.breakdown("BUY", 100.0, 75, "NFO", "X"))
    }

    // ---- margin ------------------------------------------------------------------------------------------------------------

    @Test fun marginIsZerodhasPerLotOrThePremium() {
        val fut = parsed().first()
        val ce = parsed().first { it.tradingSymbol == "CRUDEOIL26OCT8550CE" }
        assertEquals(267_290.0, McxMargin.required(fut, buy = true, lots = 1, price = 8552.0))
        assertEquals(2 * 300_000.0, McxMargin.required(fut, buy = false, lots = -2, price = 8552.0, feed = mapOf("CRUDEOIL" to 300_000.0)))
        assertEquals(242.0 * 100, McxMargin.required(ce, buy = true, lots = 1, price = 242.0))
        assertEquals(267_290.0, McxMargin.required(ce, buy = false, lots = 1, price = 242.0))
        assertNull(McxMargin.required(fut.copy(name = "UNKNOWN"), buy = true, lots = 1, price = 1.0))
        assertEquals(26_729.0, McxMargin.perLot("crudeoilm", mapOf("CRUDEOILM" to Double.NaN)))
        val feed = McxMargin.parseKite("""[{"tradingsymbol":"ALUMINI","nrml_margin":34655.0,"mis_margin":34655.0},
            {"tradingsymbol":"ALUMINI","nrml_margin":36661.0},{"tradingsymbol":"COPPER","nrml_margin":0},{"nrml_margin":5},"x"]""")
        assertEquals(mapOf("ALUMINI" to 34655.0), feed)
        assertEquals(emptyMap(), McxMargin.parseKite("nope"))
        assertEquals("1 lot of CRUDEOIL needs about Rs 267,290 (Zerodha's margin; MIS is the same on MCX)", McxMargin.say("CRUDEOIL", 1, 267_290.0))
        assertTrue(McxMargin.say("GOLDM", 2, 1.0).startsWith("2 lots"))
    }

    // ---- expiry safety -----------------------------------------------------------------------------------------------------

    @Test fun optionsAreClosedBy2300TheTradingDayBeforeExpiry() {
        val cal = McxSession.DEFAULT
        // CRUDEOIL options expire Thu 15 Oct 2026: closed from Wed 14 Oct 23:00.
        val e = d("2026-10-15")
        assertEquals(t("2026-10-14T23:00"), McxExpiry.optionExitAt(e, cal))
        assertFalse(McxExpiry.optionExitDue(e, t("2026-10-14T22:59"), cal))
        assertTrue(McxExpiry.optionExitDue(e, t("2026-10-14T23:00"), cal))
        assertTrue(McxExpiry.optionExitDue(e, t("2026-10-15T09:01"), cal))
        assertFalse(McxExpiry.optionExitDue(e, t("2026-10-15T09:01"), cal, McxExpiry.Config(enabled = false)))
        // GOLDM options expire Thu 29 Oct; SILVERM Tue 27 Oct; an expiry on Monday 2 Nov goes back to Friday 30 Oct.
        assertEquals(t("2026-10-30T23:00"), McxExpiry.optionExitAt(d("2026-11-02"), cal))
        // NATURALGAS 23 Oct: Thursday 22 Oct.
        assertEquals(t("2026-10-22T23:00"), McxExpiry.optionExitAt(d("2026-10-23"), cal))
        // The day before is the Muhurat hour (ends 19:00): 15 minutes before it ends.
        assertEquals(t("2026-11-08T18:45"), McxExpiry.optionExitAt(d("2026-11-09"), cal))
        // Configurable time.
        assertEquals(t("2026-10-14T21:30"), McxExpiry.optionExitAt(e, cal, McxExpiry.Config(optionExitTime = LocalTime.of(21, 30))))
        // A day the calendar cannot see a window for (owner marked it shut after the fact): the configured time on it.
        val shut = McxSession.Calendar(added = setOf(d("2026-10-14")))
        assertEquals(t("2026-10-13T23:00"), McxExpiry.optionExitAt(e, shut))
        val why = McxExpiry.due("CRUDEOIL", e, option = true, now = t("2026-10-14T23:05"), cal = cal)
        assertEquals(McxExpiry.Why.OPTION_DEVOLVES, why?.why)
        assertTrue(why!!.text.contains("turn into futures"))
        assertNull(McxExpiry.due("CRUDEOIL", e, option = true, now = t("2026-10-14T20:00"), cal = cal))
    }

    @Test fun deliveryFuturesGoFiveTradingDaysBeforeExpiryCrudeStays() {
        val cal = McxSession.DEFAULT
        // The default was raised from 2 to 5 trading days (9 Oct; research/MCX_TREND.md: roll about 5 days before expiry).
        assertEquals(5, McxExpiry.Config().futureExitTradingDays)
        assertEquals(5, McxExpiry.DEFAULT_FUTURE_EXIT_DAYS)
        // GOLDM November future expires Thu 5 Nov: closed from Thu 29 Oct (4, 3, 2 Nov, Fri 30 Oct, Thu 29 Oct).
        val e = d("2026-11-05")
        assertEquals(d("2026-10-29"), McxExpiry.futureExitDay(e, cal))
        assertFalse(McxExpiry.futureExitDue("GOLDM", e, t("2026-10-28T23:00"), cal))
        assertTrue(McxExpiry.futureExitDue("GOLDM", e, t("2026-10-29T09:00"), cal))
        assertFalse(McxExpiry.futureExitDue("CRUDEOIL", d("2026-10-19"), t("2026-10-19T10:00"), cal))
        assertFalse(McxExpiry.futureExitDue("GOLDM", e, t("2026-11-04T09:00"), cal, McxExpiry.Config(enabled = false)))
        // The old 2-day setting still reads as asked (Tue 3 Nov), and 0 is taken as 1.
        assertEquals(d("2026-11-03"), McxExpiry.futureExitDay(e, cal, McxExpiry.Config(futureExitTradingDays = 2)))
        assertEquals(d("2026-11-04"), McxExpiry.futureExitDay(e, cal, McxExpiry.Config(futureExitTradingDays = 0)))
        val x = McxExpiry.due("GOLDM", e, option = false, now = t("2026-10-29T10:00"), cal = cal)
        assertEquals(McxExpiry.Why.FUTURE_DELIVERY, x?.why)
        assertTrue(x!!.text.contains("delivery") && x.text.contains("5 trading days"))
        assertNull(McxExpiry.due("GOLDM", e, option = false, now = t("2026-10-28T10:00"), cal = cal))
    }

    @Test fun newOptionBuysAreRefusedOnExpiryDayAndAfter1500TheDayBefore() {
        val cal = McxSession.DEFAULT
        val e = d("2026-10-15")
        assertNull(McxExpiry.entryRefusal("CRUDEOIL", e, option = true, buy = true, now = t("2026-10-14T14:59"), cal = cal))
        assertNotNull(McxExpiry.entryRefusal("CRUDEOIL", e, option = true, buy = true, now = t("2026-10-14T15:00"), cal = cal))
        assertTrue(McxExpiry.entryRefusal("CRUDEOIL", e, true, true, t("2026-10-15T10:00"), cal)!!.contains("expiry day"))
        assertNull(McxExpiry.entryRefusal("CRUDEOIL", e, option = true, buy = false, now = t("2026-10-15T10:00"), cal = cal))
        assertNull(McxExpiry.entryRefusal("CRUDEOIL", e, option = true, buy = true, now = t("2026-10-13T20:00"), cal = cal))
        // Delivery futures from their exit day; crude futures never.
        assertNotNull(McxExpiry.entryRefusal("GOLDM", d("2026-11-05"), option = false, buy = true, now = t("2026-10-29T10:00"), cal = cal))
        assertNull(McxExpiry.entryRefusal("GOLDM", d("2026-11-05"), option = false, buy = false, now = t("2026-10-28T10:00"), cal = cal))
        assertNull(McxExpiry.entryRefusal("CRUDEOIL", d("2026-10-19"), option = false, buy = true, now = t("2026-10-19T10:00"), cal = cal))
    }

    // ---- paper fills -------------------------------------------------------------------------------------------------------

    @Test fun mcxPaperSpreadDependsOnTheContractAndTheHour() {
        val morning = LocalTime.of(11, 0); val evening = LocalTime.of(20, 0)
        assertEquals(0.0060, PaperSpread.mcxHalfSpread("CRUDEOIL15OCT268550CE", morning))
        assertEquals(0.0030, PaperSpread.mcxHalfSpread("CRUDEOIL15OCT268550CE", evening))
        assertEquals(0.0030, PaperSpread.mcxHalfSpread("NATURALGAS23OCT26300PE", LocalTime.of(17, 0)))
        assertEquals(0.0080, PaperSpread.mcxHalfSpread("GOLDM29OCT26150000CE", morning))
        assertEquals(0.0040, PaperSpread.mcxHalfSpread("SILVERM27OCT26230000PE", evening))
        assertEquals(0.0120, PaperSpread.mcxHalfSpread("CRUDEOILM15OCT268550CE", morning))
        assertEquals(0.0060, PaperSpread.mcxHalfSpread("NATGASMINI23OCT26300CE", evening))
        assertEquals(0.0003, PaperSpread.mcxHalfSpread("CRUDEOIL19OCT26FUT", morning))
        // A real book still wins; NFO is unchanged by the exchange-aware call.
        assertEquals(0.01, PaperSpread.halfSpread("CRUDEOIL15OCT268550CE", Quote(100.0, 99.0, 101.0), "MCX", morning), 1e-12)
        assertEquals(0.0060, PaperSpread.halfSpread("CRUDEOIL15OCT268550CE", null, "MCX", morning))
        assertEquals(0.0016, PaperSpread.halfSpread("NIFTY28OCT2625000CE", null, "NFO", morning))
    }

    private val ist = SandboxRules.IST
    private fun z(s: String): ZonedDateTime = t(s).atZone(ist)
    private val opt = "CRUDEOIL15OCT268550CE"
    private val fut = "GOLDM05NOV26FUT"
    private val master = InstrumentMaster.of(listOf(
        Instrument(opt, "MCX", "OPTFUT", lotSize = 100, tickSize = 0.1, expiry = d("2026-10-15"), strike = 8550.0, marginPerLot = 267_290.0),
        Instrument(fut, "MCX", "FUTCOM", lotSize = 10, tickSize = 1.0, expiry = d("2026-11-05"), marginPerLot = 136_807.0),
    ))
    private val cfg = SandboxConfig(startingCapital = BigDecimal("1000000.00"), stopSlippageBps = BigDecimal("10"), spreadFallbackBps = BigDecimal("5"),
        chargesEnabled = true, pnlAlwaysToFunds = true, paperSpread = true, mcxSessionAware = true)

    @Test fun aPaperMcxOptionBuyPaysTheMcxSpreadAndTheCommodityCharges() {
        val sb = Sandbox(cfg, master)
        val s0 = sb.newState(z("2026-10-08T11:00"))
        val out = sb.place(s0, OrderRequest(opt, "MCX", "BUY", 100, "MARKET", "NRML"), Quote(242.0), z("2026-10-08T11:00"))
        assertTrue(out.result.ok, out.result.message)
        val tr = out.state.trades.single()
        // 242 x 1.006 = 243.452 -> 243.50 on the 0.10 tick.
        assertEquals(BigDecimal("243.50"), tr.price)
        assertEquals(McxCosts.charge("BUY", 243.5 * 100, true).toDouble(), tr.charges.toDouble(), 0.011)
        // In the evening the same buy pays the narrower half-spread: 242 x 1.003 = 242.726 -> 242.80.
        val ev = sb.place(s0, OrderRequest(opt, "MCX", "BUY", 100, "MARKET", "NRML"), Quote(242.0), z("2026-10-08T20:00"))
        assertEquals(BigDecimal("242.80"), ev.state.trades.single().price)
    }

    @Test fun aPaperMcxFutureBlocksZerodhasMarginAndMisGoesAtTheDatedCut() {
        val sb = Sandbox(cfg, master)
        val now = z("2026-10-08T11:00")
        val s0 = sb.newState(now)
        val out = sb.place(s0, OrderRequest(fut, "MCX", "BUY", 10, "MARKET", "MIS"), Quote(147_897.0), now)
        assertTrue(out.result.ok, out.result.message)
        val pos = out.state.positions.single()
        assertEquals(136_807.0, pos.marginBlocked.toDouble(), 0.01)
        // A sold option blocks the per-lot margin too.
        val sold = sb.place(s0, OrderRequest(opt, "MCX", "SELL", 100, "MARKET", "NRML"), Quote(242.0), now)
        assertEquals(267_290.0, sold.state.positions.single().marginBlocked.toDouble(), 0.01)
        // MIS on MCX squares off at 23:20 in October (not NSE's 15:15).
        val at1515 = sb.squareOffDue(out.state, z("2026-10-08T15:20"), mapOf(Sandbox.key(fut, "MCX") to Quote(147_900.0)))
        assertEquals(10, at1515.state.positions.single().quantity)
        val at2320 = sb.squareOffDue(out.state, z("2026-10-08T23:20"), mapOf(Sandbox.key(fut, "MCX") to Quote(147_900.0)))
        assertEquals(0, at2320.state.positions.single().quantity)
        // A new MIS order after the cut is refused; in November the cut is 23:45.
        assertFalse(sb.place(s0, OrderRequest(fut, "MCX", "BUY", 10, "MARKET", "MIS"), Quote(147_897.0), z("2026-10-08T23:25")).result.ok)
        assertEquals(LocalTime.of(23, 45), cfg.squareOffTime("MCX", d("2026-11-02")))
        assertEquals(LocalTime.of(15, 15), cfg.squareOffTime("NFO", d("2026-11-02")))
        assertEquals(LocalTime.of(23, 30), SandboxConfig().squareOffTime("MCX", d("2026-11-02")))
        // An expiring MCX contract is expired from the day's real close.
        assertFalse(SandboxRules.isContractExpiredNow(d("2026-11-05"), "MCX", t("2026-11-05T23:40"), cfg))
        assertTrue(SandboxRules.isContractExpiredNow(d("2026-11-05"), "MCX", t("2026-11-05T23:55"), cfg))
        assertTrue(SandboxRules.isContractExpiredNow(d("2026-11-05"), "MCX", t("2026-11-05T23:40"), SandboxConfig()))
    }

    // ---- guards -----------------------------------------------------------------------------------------------------------

    @Test fun theAccountGuardUsesMcxsOwnEntryCutoff() {
        val a = AccountGuard.Account(100_000.0, 100_000.0, 100_000.0, 0.0, emptyList(), 0, 20 * 60)
        val l = AccountGuard.Limits(maxOrderValue = 0.0, mcxEntryCutoffMinute = 23 * 60)
        val nfo = AccountGuard.Order("NIFTY28OCT2625000CE", "BUY", 75, 75, 100.0)
        val mcx = AccountGuard.Order("CRUDEOIL26OCT8550CE", "BUY", 100, 100, 242.0, exchange = "MCX")
        assertTrue(AccountGuard.refusals(nfo, a, l).any { it.startsWith("No new entries after 14:30") })
        assertTrue(AccountGuard.refusals(mcx, a, l).isEmpty())
        assertTrue(AccountGuard.refusals(mcx, a.copy(minuteOfDay = 23 * 60 + 5), l).any { it.startsWith("No new entries after 23:00") })
        assertTrue(AccountGuard.refusals(mcx, a.copy(minuteOfDay = 23 * 60 + 5), l.copy(mcxEntryCutoffMinute = null)).isEmpty())
    }

    @Test fun zerodhasValueCapCountsAnMcxLotsUnits() {
        val o = Kite.Order("CRUDEOIL26OCTFUT", Kite.Side.BUY, 1, 1, "NRML", "LIMIT", 8552.0, 1.0, "MCX", multiplier = 100)
        assertTrue(Kite.refusals(o, Kite.Limits(maxOrderValue = 500_000.0), 0, false).any { it.contains("855,200") })
        assertTrue(Kite.refusals(o.copy(multiplier = 1), Kite.Limits(maxOrderValue = 500_000.0), 0, false).isEmpty())
        val m = o.copy(orderType = "MARKET", price = null)
        assertTrue(Kite.refusals(m, Kite.Limits(maxOrderValue = 500_000.0), 0, false, refPrice = 8552.0).any { it.contains("at the last price") })
    }
}
