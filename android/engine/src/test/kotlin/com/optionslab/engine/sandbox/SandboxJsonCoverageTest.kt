package com.optionslab.engine.sandbox

import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The paper account's JSON store: exact round trips and a strict reader. */
class SandboxJsonCoverageTest {
    private val t = ZonedDateTime.of(LocalDateTime.of(LocalDate.of(2026, 9, 21), LocalTime.of(10, 0)), SandboxRules.IST)
    private val sb = Sandbox(SandboxConfig(chargesEnabled = true), InstrumentMaster.of(listOf(Instrument("RELIANCE", "NSE", "EQ"))))

    @Test fun `a state with charges, holdings and awkward strings round-trips exactly`() {
        var s = sb.newState(t)
        s = sb.place(s, OrderRequest("RELIANCE", "NSE", "BUY", 10, "MARKET", "CNC", strategy = "q\"b\\s\nn\rr\tt\u0001x"), Quote(100.0), t).state
        s = sb.place(s, OrderRequest("RELIANCE", "NSE", "SELL", 99, "MARKET", "CNC"), Quote(100.0), t).state   // rejected: a null-free reason
        s = s.copy(holdings = listOf(Holding("TCS", "NSE", 3, BigDecimal("1.10"), null, BigDecimal("0.00"), BigDecimal("0.0000"), LocalDate.of(2026, 9, 20),
            t.toLocalDateTime(), t.toLocalDateTime())))
        val json = SandboxJson.encode(s)
        assertTrue(json.contains("\"charges\":"), "a non-zero charge is written")
        assertTrue(json.contains("\\u0001"))
        val back = SandboxJson.decode(json)
        assertEquals(s, back)
        assertEquals(json, SandboxJson.encode(back))
        // A trade written without charges reads as zero.
        val noCharge = json.replace(Regex(",\"charges\":\"[0-9.]+\""), "")
        assertEquals(BigDecimal.ZERO, SandboxJson.decode(noCharge).trades.single().charges)
        val nullCharge = json.replace(Regex("\"charges\":\"[0-9.]+\""), "\"charges\":null")
        assertEquals(BigDecimal.ZERO, SandboxJson.decode(nullCharge).trades.single().charges)
    }

    private val good by lazy { SandboxJson.encode(sb.newState(t)) }

    @Test fun `the reader refuses anything it cannot fully restore`() {
        assertFailsWith<IllegalArgumentException> { SandboxJson.decode(good.replace("\"version\":1", "\"version\":2")) }
        assertFailsWith<IllegalArgumentException> { SandboxJson.decode(good.replace("\"orderSeq\":0,", "")) }
        assertFailsWith<IllegalStateException> { SandboxJson.decode(good.replace("\"orders\":[]", "\"orders\":{}")) }
        assertFailsWith<IllegalStateException> { SandboxJson.decode(good.replace("\"orders\":[]", "\"orders\":[1]")) }
        assertFailsWith<IllegalStateException> { SandboxJson.decode(good.replace("\"orderSeq\":0", "\"orderSeq\":\"0\"")) }
        assertFailsWith<IllegalStateException> { SandboxJson.decode(good.replace("\"resetCount\":0", "\"resetCount\":true")) }
        assertFailsWith<IllegalStateException> { SandboxJson.decode(good.replace(Regex("\"totalCapital\":\"[0-9.]+\""), "\"totalCapital\":5")) }
        assertFailsWith<IllegalStateException> { SandboxJson.decode("[]") }
        assertFailsWith<ArithmeticException> { SandboxJson.decode(good.replace("\"orderSeq\":0", "\"orderSeq\":0.5")) }
        val withOrder = SandboxJson.encode(sb.place(sb.newState(t), OrderRequest("RELIANCE", "NSE", "BUY", 1, "MARKET", "MIS"), Quote(100.0), t).state)
        assertFailsWith<IllegalStateException> { SandboxJson.decode(withOrder.replace("\"strategy\":\"\"", "\"strategy\":5")) }
    }

    @Test fun `the strict JSON reader`() {
        assertEquals(mapOf("a" to listOf(BigDecimal("1.50"), true, false, null, "x/y\b\u000c\n\r\t\"\\A"), "e" to emptyMap<String, Any?>(), "l" to emptyList<Any?>()),
            Json.parse(""" { "a" : [ 1.50 , true , false , null , "x\/y\b\f\n\r\t\"\\\u0041" ] , "e" : { } , "l" : [ ] } """))
        assertEquals(BigDecimal("-2E+3"), Json.parse("-2e3"))
        for (bad in listOf("", "   ", "{} x", "tru", "nul", "fals", "{1:2}", "{\"a\" 1}", "{\"a\":1 \"b\":2}", "{\"a\":1", "[1 2]", "[1", "[",
            "\"abc", "\"\\q\"", "\"\\", "\"\\u12\"", "\"\\u12", "@", "1.2.3", "-", "{\"a\":}")) {
            assertFailsWith<IllegalArgumentException>(bad) { Json.parse(bad) }
        }
    }
}
