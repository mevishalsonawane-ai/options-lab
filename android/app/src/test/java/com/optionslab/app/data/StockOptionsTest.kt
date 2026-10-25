package com.optionslab.app.data

import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.Right
import com.optionslab.engine.Upstox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/** 25 Oct: today's NSE F&O stock options for ntfy alerts' stock signals - read from the master, saved, read back one stock at a time. */
class StockOptionsTest : RobolectricTest() {
    private fun ms(d: LocalDate) = d.atTime(15, 30).toInstant(ZoneOffset.ofHoursMinutes(5, 30)).toEpochMilli()

    @Test fun theMasterGivesTheStocksOptionsNearestTwoExpiriesAndTheirCashKeys() {
        val t = LocalDate.of(2026, 10, 26)
        fun opt(u: String, d: LocalDate, k: Int, r: String) = """{"segment":"NSE_FO","instrument_type":"$r","underlying_symbol":"$u","expiry":${ms(d)},""" +
            """"strike_price":$k,"lot_size":300,"instrument_key":"NSE_FO|$u$k$r${d.dayOfMonth}","trading_symbol":"$u $k $r"}"""
        val json = "[" + listOf(opt("ADANIENT", t.plusDays(1), 2500, "PE"), opt("ADANIENT", t.plusDays(1), 2500, "CE"), opt("ADANIENT", t.plusDays(30), 2500, "PE"),
            opt("ADANIENT", t.plusDays(60), 2500, "PE"), opt("ADANIENT", t.minusDays(3), 2500, "PE"), opt("NIFTY", t.plusDays(1), 25000, "PE"),
            """{"segment":"NSE_EQ","instrument_type":"EQ","trading_symbol":"ADANIENT","instrument_key":"NSE_EQ|INE423A01024","asset_symbol":null}""",
            """{"segment":"NSE_EQ","instrument_type":"EQ","trading_symbol":"NOOPT","instrument_key":"NSE_EQ|X"}""",
            """{"segment":"NSE_FO","instrument_type":"FUT","underlying_symbol":"ADANIENT","instrument_key":"F"}""").joinToString(",") + "]"
        val (opts, eq) = Net.parseStockMaster(json.byteInputStream(), 2, t)
        assertEquals(3, opts.size)
        assertTrue(opts.all { it.underlying == "ADANIENT" && it.expiry in setOf(t.plusDays(1), t.plusDays(30)) })
        assertEquals(mapOf("ADANIENT" to "NSE_EQ|INE423A01024"), eq)
    }

    @Test fun savedOncePerDayAndReadOneStockAtATime() {
        val d = Market.today()
        assertFalse(StockOptions.ready())
        assertTrue(StockOptions.listed("ADANIENT").isEmpty()); assertNull(StockOptions.eqKey("ADANIENT"))
        StockOptions.testFetch = { listOf(Upstox.Contract("ADANIENT", d.plusDays(1), 2500.0, Right.PE, 300, "NSE_FO|A", "ADANIENT 2500 PE"),
            Upstox.Contract("M&M", d.plusDays(1), 3000.0, Right.CE, 200, "NSE_FO|M", "M&M 3000 CE")) to mapOf("ADANIENT" to "NSE_EQ|A", "M&M" to "NSE_EQ|M") }
        StockOptions.refreshNow()
        assertTrue(StockOptions.ready())
        assertEquals(listOf(2500.0), StockOptions.listed("ADANIENT").map { it.strike })
        assertEquals(Right.CE, StockOptions.listed("M&M").single().right)
        assertEquals("NSE_EQ|M", StockOptions.eqKey("M&M")); assertNull(StockOptions.eqKey("TCS"))
        assertTrue(StockOptions.listed("TCS").isEmpty())
        assertTrue(Diag.lines().any { "stock options list saved: 2 stocks, 2 options" in it })
    }
}
