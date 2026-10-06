package com.optionslab.ira.dhan

import com.optionslab.engine.strategy.Json
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DhanApiTest {
    @Test fun onlyDataPathsAreAllowed() {
        assertEquals(setOf("/charts/historical", "/charts/intraday", "/charts/rollingoption", "/optionchain", "/optionchain/expirylist",
            "/marketfeed/ltp", "/marketfeed/ohlc", "/marketfeed/quote"), DhanApi.ALLOWED_PATHS)
        // Every path in the allowed set is a market-data path.
        for (p in DhanApi.ALLOWED_PATHS) assertTrue(p.startsWith("/charts/") || p.startsWith("/optionchain") || p.startsWith("/marketfeed/"), p)
        // Dhan's order, position, holding, funds, margin, statement, trader-control and portfolio endpoints: never.
        for (p in listOf("/orders", "/orders/slicing", "/orders/external/123", "/super/orders", "/forever/orders", "/conditional/orders",
            "/positions", "/positions/convert", "/holdings", "/fundlimit", "/margincalculator", "/margincalculator/multi", "/ledger",
            "/trades", "/trades/2026-01-01/2026-02-01/0", "/killswitch", "/edis/tpin", "/edis/form", "/profile", "/ip/setIP",
            "/charts/historical/../../orders", "/charts/historical?x=1", "/charts/", "/charts/historicalx", "", "/", "orders",
            "/marketfeed/ltp/../../orders", "/optionchain/../positions")) {
            assertFalse(DhanApi.isAllowed(p), p)
            assertFailsWith<DhanApi.NotAllowed>(p) { DhanApi.requireAllowed(p) }
        }
        assertEquals("https://api.dhan.co/v2/charts/intraday", DhanApi.requireAllowed("/charts/intraday"))
        assertTrue(DhanApi.urlAllowed("https://api.dhan.co/v2/charts/rollingoption"))
        assertTrue(DhanApi.urlAllowed(DhanApi.MASTER_URL))
        assertFalse(DhanApi.urlAllowed("https://api.dhan.co/v2/orders"))
        assertFalse(DhanApi.urlAllowed("https://api.dhan.co.evil.example/v2/charts/intraday"))
        assertFalse(DhanApi.urlAllowed("http://api.dhan.co/v2/charts/intraday"))
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun requestBodies() {
        val d = LocalDate.of(2026, 1, 1)
        val h = Json.parse(DhanApi.historicalBody("13", "IDX_I", "INDEX", d, d.plusDays(10))) as Map<String, Any?>
        assertEquals("13", h["securityId"]); assertEquals("IDX_I", h["exchangeSegment"]); assertEquals("2026-01-11", h["toDate"])
        val i = Json.parse(DhanApi.intradayBody("2885", "NSE_EQ", "EQUITY", d, d.plusDays(85))) as Map<String, Any?>
        assertEquals("2026-01-01 09:15:00", i["fromDate"]); assertEquals(1L, i["interval"])
        val r = Json.parse(DhanApi.rollingBody("13", "NSE_FNO", "OPTIDX", "WEEK", 1, -3, false, d, d.plusDays(25))) as Map<String, Any?>
        assertEquals(13L, r["securityId"]); assertEquals("ATM-3", r["strike"]); assertEquals("PUT", r["drvOptionType"]); assertEquals("WEEK", r["expiryFlag"])
        assertEquals(DhanApi.ROLLING_FIELDS, r["requiredData"])
        assertEquals("ATM", DhanApi.strikeWord(0)); assertEquals("ATM+10", DhanApi.strikeWord(10))
        val e = Json.parse(DhanApi.expiryListBody("25", "IDX_I")) as Map<String, Any?>
        assertEquals(25L, e["UnderlyingScrip"])
        val c = Json.parse(DhanApi.chainBody("25", "IDX_I", d)) as Map<String, Any?>
        assertEquals("2026-01-01", c["Expiry"])
    }

    @Test fun parsesReplies() {
        val candles = DhanApi.parseCandles("""{"open":[1.5,2],"high":[2,3],"low":[1,1.5],"close":[1.8,2.5],"volume":[10,20],"timestamp":[1700000000,1700000060],"open_interest":[5,6]}""")
        assertEquals(2, candles.size); assertEquals(1.8, candles[0].close); assertEquals(6L, candles[1].oi)
        assertEquals(emptyList(), DhanApi.parseCandles(""))
        val rolling = DhanApi.parseRolling("""{"data":{"ce":null,"pe":{"open":[10],"high":[12],"low":[9],"close":[11],"volume":[100],"oi":[50],"iv":[14.2],"strike":[24500],"spot":[24480.5],"timestamp":[1700000000]}}}""")
        assertEquals(1, rolling.size); assertEquals(24500.0, rolling[0].strike); assertEquals(24480.5, rolling[0].spot); assertEquals(14.2, rolling[0].iv)
        assertEquals(emptyList(), DhanApi.parseRolling("""{"data":{"ce":null,"pe":null}}"""))
        assertEquals(listOf(LocalDate.of(2026, 10, 7), LocalDate.of(2026, 10, 14)),
            DhanApi.parseExpiries("""{"data":["2026-10-14","2026-10-07"],"status":"success"}"""))
    }

    @Test fun failuresAndWaits() {
        assertEquals(DhanApi.Failure.RETRY, DhanApi.classify(429, null))
        assertEquals(DhanApi.Failure.RETRY, DhanApi.classify(503, ""))
        assertEquals(DhanApi.Failure.AUTH, DhanApi.classify(401, ""))
        assertEquals(DhanApi.Failure.AUTH, DhanApi.classify(400, """{"errorCode":"DH-901"}"""))
        // DH-905 is an input error (refused: no file, counted failed); DH-907 is "no data" (an empty, done chunk).
        assertEquals(DhanApi.Failure.REFUSED, DhanApi.classify(400, """{"errorCode":"DH-905"}"""))
        assertEquals(DhanApi.Failure.NO_DATA, DhanApi.classify(400, """{"errorCode":"DH-907"}"""))
        assertEquals(DhanApi.Failure.REFUSED, DhanApi.classify(400, """{"errorCode":"DH-906"}"""))
        assertEquals(1_000L, DhanApi.backoffMs(0))
        assertEquals(8_000L, DhanApi.backoffMs(3))
        assertEquals(30_000L, DhanApi.backoffMs(0, retryAfterSec = 30))
        assertEquals(DhanApi.MAX_WAIT_MS, DhanApi.backoffMs(20, jitter = 1.0))
        assertTrue((0 until DhanApi.MAX_ATTEMPTS).map { DhanApi.backoffMs(it) }.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test fun tokenExpiryIsADateOnly() {
        fun b64(s: String) = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())
        val token = b64("""{"alg":"HS512"}""") + "." + b64("""{"iss":"dhan","exp":1767225600,"dhanClientId":"1000000001"}""") + ".sig"
        assertEquals(LocalDate.of(2026, 1, 1), DhanApi.tokenExpiry(token))
        assertNull(DhanApi.tokenExpiry("not-a-jwt"))
        assertNull(DhanApi.tokenExpiry("a.b.c"))
        assertTrue(DhanApi.tokenShapeOk(token)); assertFalse(DhanApi.tokenShapeOk("short")); assertFalse(DhanApi.tokenShapeOk("has a space in it that is long"))
        assertTrue(DhanApi.clientIdOk("1000000001")); assertFalse(DhanApi.clientIdOk("12a4")); assertFalse(DhanApi.clientIdOk("12"))
    }
}
