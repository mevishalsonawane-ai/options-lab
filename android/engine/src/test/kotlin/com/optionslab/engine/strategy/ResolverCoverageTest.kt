package com.optionslab.engine.strategy

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The symbol resolver's refusals and its expiry ranks, each named by code. */
class ResolverCoverageTest {
    private val today = LocalDate.of(2026, 5, 4)
    private val mc = MasterContract(buildList {
        for (e in listOf("05-MAY-26", "26-MAY-26", "30-JUN-26")) {
            val sym = e.replace("-", "")
            for (i in 0..4) {
                val s = 23500.0 + 50 * i
                add(Instrument("NIFTY$sym${s.toInt()}CE", "NFO", "NIFTY", e, s, 65, "CE", tickSize = 0.05))
                add(Instrument("NIFTY$sym${s.toInt()}PE", "NFO", "NIFTY", e, s, 65, "PE", tickSize = Double.NaN))
            }
        }
        add(Instrument("NIFTY26MAY26FUT", "NFO", "NIFTY", "26-MAY-26", null, 65, "FUT"))
        add(Instrument("NIFTY30JUN26FUT", "NFO", "NIFTY", "30-JUN-26", null, null, "FUT"))
        add(Instrument("RELIANCE", "NSE", "RELIANCE", null, null, 1, "EQ", tickSize = 0.0))
        add(Instrument("VEDL05MAY26292.5CE", "NFO", "VEDL", "05-MAY-26", 292.5, 1150, "CE"))
        add(Instrument("ONEEXP05MAY26100CE", "NFO", "ONEEXP", "05-MAY-26", 100.0, 10, "CE"))
    })

    private fun opt(vararg k: Pair<String, Any?>): LegDef {
        var l = LegDef(1, Segment.OPTIONS, Position.S, 1, OptionType.CE, StrikeMode.ATM, "ATM", expiry = "weekly")
        for ((key, v) in k) l = when (key) {
            "type" -> l.copy(optionType = v as OptionType?); "mode" -> l.copy(strikeMode = v as StrikeMode?)
            "offset" -> l.copy(atmOffset = v as String?); "strike" -> l.copy(strike = v as Double?); "expiry" -> l.copy(expiry = v as String?)
            "int" -> l.copy(strikeInt = v as Double?); "lots" -> l.copy(lots = v as Int); else -> error(key)
        }
        return l
    }
    private fun res(leg: LegDef, ltp: Double? = 23590.0, u: String = "NIFTY", ex: String = "NSE_INDEX") =
        SymbolResolver.resolveLeg(leg, u, ex, "intraday", mc, ltp, today)

    @Test fun `expiry ranks, fallbacks and refusals`() {
        fun rank(r: String, type: String = "options", u: String = "NIFTY") = SymbolResolver.resolveExpiryRank(mc, u, "NSE_INDEX", type, r, today)
        assertEquals("invalid_rank", rank("fortnightly").code)
        assertEquals("invalid_instrument_type", rank("weekly", "swaps").code)
        assertEquals("05-MAY-26", rank("Current", "ce").expiry)
        assertEquals("26-MAY-26", rank("next-week", "opt").expiry)
        assertEquals("26-MAY-26", rank("monthly").expiry, "the last expiry of May")
        val nm = rank("next month")
        assertEquals("30-JUN-26", nm.expiry); assertFalse(nm.fallback); assertEquals("30JUN26", nm.expirySymbol)
        assertEquals(listOf("05-MAY-26", "26-MAY-26", "30-JUN-26"), nm.available)
        val fut = rank("next", "FUT")
        assertEquals("30-JUN-26", fut.expiry)
        val lone = rank("next_week", u = "ONEEXP")
        assertTrue(lone.ok && lone.fallback); assertEquals("05-MAY-26", lone.expiry)
        assertTrue(rank("next_month", u = "ONEEXP").fallback)
        val none = SymbolResolver.resolveExpiryRank(mc, "NIFTY", "NSE_INDEX", "options", "weekly", LocalDate.of(2027, 1, 1))
        assertEquals("no_expiry", none.code)
        assertEquals("NIFTY", SymbolResolver.baseSymbol("nifty28oct25fut")); assertEquals("NIFTY", SymbolResolver.baseSymbol("NIFTY28OCT25"))
        assertEquals("BANK NIFTY", SymbolResolver.baseSymbol("bank nifty"))
    }

    @Test fun `leg-level refusals`() {
        assertEquals("invalid_underlying", res(opt(), u = "").code)
        val z = res(opt("lots" to 0))
        assertEquals("invalid_lots", z.code); assertEquals("Lots on a NIFTY options leg must be a whole number above zero, got 0.", z.error)
        assertEquals("invalid_leg", res(opt("type" to null)).code)
        assertEquals("invalid_expiry", res(opt("expiry" to "31-FEB-26")).code)
        assertEquals("invalid_rank", res(opt("expiry" to "quarterly")).code)
        assertEquals("no_expiry", SymbolResolver.resolveLeg(opt(), "NIFTY", "NSE_INDEX", null, mc, 23590.0, LocalDate.of(2027, 1, 1)).code)
        for (bad in listOf(null, Double.NaN, -1.0, 0.0)) assertEquals("invalid_strike", res(opt("mode" to StrikeMode.STRIKE, "strike" to bad)).code, "$bad")
        assertEquals("invalid_offset", res(opt("offset" to "OTM9")).code)
        val noLtp = res(opt(), ltp = null)
        assertEquals("no_ltp", noLtp.code); assertEquals("No underlying price was supplied for NIFTY; the resolver takes the price as an input.", noLtp.error)
        assertEquals("Unusable last price nan supplied for NIFTY.", res(opt(), ltp = Double.NaN).error)
        assertEquals("no_ltp", res(opt(), ltp = 0.0).code)
        for (bad in listOf(0.0, -50.0, Double.POSITIVE_INFINITY)) assertEquals("invalid_leg", res(opt("int" to bad)).code)
        assertEquals("no_strikes", res(opt("type" to OptionType.PE), u = "ONEEXP", ex = "NSE").code, "an expiry with calls only")
        assertEquals("no_expiry", res(opt(), u = "RELIANCE", ex = "NSE").code)
        val far = res(opt("offset" to "OTM5"))
        assertEquals("offset_out_of_range", far.code); assertEquals(23600.0, far.atmStrike); assertEquals(23590.0, far.underlyingLtp)
        // Arithmetic ATM names a strike that is not listed.
        val missing = res(opt("int" to 50.0, "offset" to "OTM5"))
        assertEquals("contract_not_found", missing.code); assertEquals("NIFTY05MAY2623850CE", missing.symbol)
        assertEquals("SELL", missing.action); assertEquals("CE", missing.optionType)
        // A lot size the master contract does not give.
        val noLot = res(LegDef(1, Segment.FUTURES, Position.B, 1, expiry = "30-JUN-26"))
        assertEquals("invalid_lotsize", noLot.code); assertEquals("BUY", noLot.action)
        assertEquals("contract_not_found", res(LegDef(1, Segment.FUTURES, Position.B, 1, expiry = "05-MAY-26")).code)
    }

    @Test fun `successful legs of every segment`() {
        val cash = res(LegDef(1, Segment.CASH, side = LegSide.SHORT), u = "RELIANCE", ex = "NSE")
        assertTrue(cash.ok); assertEquals("SELL", cash.action); assertEquals(1, cash.quantity); assertNull(cash.tickSize, "a zero tick is no tick")
        val idx = res(LegDef(1, Segment.CASH, side = LegSide.LONG), u = "NIFTY", ex = "NSE_INDEX")
        assertEquals("contract_not_found", idx.code)
        assertTrue(idx.error!!.endsWith("An index has no cash instrument of its own and cannot be traded directly."))
        assertFalse(res(LegDef(1, Segment.CASH), u = "TCS", ex = "NSE").error!!.contains("index"))
        val f = res(LegDef(1, Segment.FUTURES, Position.S, 2))
        assertEquals("NIFTY26MAY26FUT", f.symbol); assertEquals(130, f.quantity); assertEquals(false, f.detail["expiry_fallback"])
        val pe = res(opt("type" to OptionType.PE, "offset" to "itm1", "lots" to 3))
        assertEquals("NIFTY05MAY2623650PE", pe.symbol); assertEquals(195, pe.quantity); assertNull(pe.tickSize, "a NaN tick is no tick")
        assertEquals("ITM1", pe.detail["atm_offset"])
        val ce = res(opt("offset" to "ITM2"))
        assertEquals(0.05, ce.tickSize); assertEquals("NIFTY05MAY2623500CE", ce.symbol)
        val arith = res(opt("int" to 50.0, "type" to OptionType.PE, "offset" to "OTM1"), ltp = 23625.0)
        assertEquals(23600.0, arith.atmStrike, "23625/50 = 472.5 rounds to even 472"); assertEquals("NIFTY05MAY2623550PE", arith.symbol)
        val lit = res(opt("mode" to StrikeMode.STRIKE, "strike" to 292.5), u = "VEDL", ex = "NSE")
        assertTrue(lit.ok, lit.error); assertEquals("VEDL05MAY26292.5CE", lit.symbol); assertNull(lit.underlyingLtp)
        val pinned = res(opt("expiry" to "26MAY2026", "offset" to ""))
        assertEquals("NIFTY26MAY2623600CE", pinned.symbol); assertEquals("26MAY2026", pinned.expiry)
        val noExpiry = res(opt("expiry" to null))
        assertEquals("05-MAY-26", noExpiry.expiry, "saying nothing means the nearest")
        assertEquals("05-MAY-26", res(opt("expiry" to "")).expiry)
    }

    @Test fun `offset arithmetic and strike text`() {
        assertEquals(23600.0, SymbolResolver.offsetStrike(23600.0, "atm", 50.0, "CE"))
        assertEquals(23500.0, SymbolResolver.offsetStrike(23600.0, "ITM2", 50.0, "CE")); assertEquals(23700.0, SymbolResolver.offsetStrike(23600.0, "ITM2", 50.0, "PE"))
        assertEquals(23650.0, SymbolResolver.offsetStrike(23600.0, "OTM1", 50.0, "CE")); assertEquals(23550.0, SymbolResolver.offsetStrike(23600.0, "OTM1", 50.0, "PE"))
        assertFailsWith<IllegalArgumentException> { SymbolResolver.offsetStrike(23600.0, "XYZ1", 50.0, "CE") }
        val ks = listOf(1.0, 2.0, 3.0)
        assertNull(SymbolResolver.offsetStrikeFromActual(9.0, "ATM", "CE", ks))
        assertEquals(2.0, SymbolResolver.offsetStrikeFromActual(2.0, "atm", "CE", ks))
        assertEquals(3.0, SymbolResolver.offsetStrikeFromActual(2.0, "ITM1", "PE", ks)); assertEquals(1.0, SymbolResolver.offsetStrikeFromActual(2.0, "OTM1", "PE", ks))
        assertNull(SymbolResolver.offsetStrikeFromActual(2.0, "XYZ1", "CE", ks))
        assertEquals("23500", SymbolResolver.strikeText(23500.0)); assertEquals("292.5", SymbolResolver.strikeText(292.5))
        assertEquals("inf", SymbolResolver.strikeText(Double.POSITIVE_INFINITY))
        assertEquals("NIFTY05MAY2623500CE", SymbolResolver.optionSymbol("NIFTY", "05MAY26", 23500.0, "ce"))
    }

    @Test fun `resolved legs describe themselves`() {
        val d = res(opt()).asDict()
        assertEquals("NIFTY05MAY2623600CE", d["symbol"]); assertEquals(true, d["ok"])
        val failed = res(opt(), ltp = null)
        assertEquals("no_ltp", failed.asDict()["code"])
    }
}
