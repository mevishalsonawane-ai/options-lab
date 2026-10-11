package com.optionslab.ira

import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The exportable health report ([HealthExport]) and its redaction: secrets planted everywhere a report could pick them up
 * - a stall's place, a crash trace's message, an ANR trace, an order line - never reach the text.
 */
class HealthExportTest {
    private val ist = ZoneId.of("Asia/Kolkata")
    private val d0 = LocalDate.of(2026, 10, 12)
    private fun at(d: LocalDate, h: Int, m: Int = 0) = d.atTime(h, m).atZone(ist).toInstant().toEpochMilli()
    private fun hhmm(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(ist).toLocalTime().toString().take(5)

    private val token = "k9Xa2Lm4Pq7Rt0Vw3Yz6Bc8De1Fg5Hj"
    private val apiKey = "abcd1234efgh5678"
    private val secrets = listOf(token, apiKey, "AB1234", "135792", "boss@example.com", "9876543210", "123456789012",
        "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjMifQ.c2lnbmF0dXJl", "s3cr3t-Pa55word")

    @Test fun plantedSecretsNeverReachTheReport() {
        var b = HealthBook.Book()
        b = HealthBook.stall(b, at(d0, 10), 1_200, "Choreographer.doFrame in BrainSection.invoke")
        b = HealthBook.exits(b, listOf(HealthBook.Exit(at(d0, 11), HealthBook.ExitKind.CRASH, "OrderFlowLive.pump")))
        b = HealthBook.orderPath(b, d0, 180, 4)
        val traces = listOf(
            HealthExport.Trace(at(d0, 11), HealthBook.ExitKind.CRASH, """
                java.lang.IllegalStateException: token=$token for user AB1234 pin: 135792
                    at com.optionslab.app.data.Broker.placeOrder(Broker.kt:120)
                    at com.optionslab.app.data.OrderFlowLive.pump(OrderFlowLive.kt:88)
                Caused by: java.io.IOException: https://api.kite.trade/session/token?api_key=$apiKey&access_token=$token
            """.trimIndent()),
            HealthExport.Trace(at(d0, 12), HealthBook.ExitKind.ANR, """
                "main" prio=5 tid=1 Blocked
                  at com.optionslab.app.ui.Root.Main(Root.kt:400)
                  Authorization: Bearer $token
                  email boss@example.com phone +91 9876543210 account 123456789012
                  jwt eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjMifQ.c2lnbmF0dXJl password=s3cr3t-Pa55word
            """.trimIndent()),
        )
        val orderLines = listOf("Signal to sent median 180 ms (4 orders) · api_key: $apiKey", "User id AB1234 · client_id=AB1234")
        val text = HealthExport.build(b, traces, orderLines, d0, "abc123", "Pixel 6 · Android 14", ::hhmm, known = listOf(apiKey, "Boss Kumar"))
        for (s in secrets) assertFalse(text.contains(s), "leaked: $s\n$text")
        assertFalse(text.contains("Boss Kumar"))
        // What it is for is still there: the stall's place, the frames, the order timing.
        assertTrue(text.contains("BrainSection.invoke"), text)
        assertTrue(text.contains("at com.optionslab.app.data.OrderFlowLive.pump(OrderFlowLive.kt:88)"), text)
        assertTrue(text.contains("java.lang.IllegalStateException: ‹message left out›"), text)
        assertTrue(text.contains("signal to sent median 180 ms over 4 order(s)"), text)
        assertTrue(text.contains("== ANRs and crashes"))
    }

    @Test fun redactionKeepsOrdinaryWordsAndNumbers() {
        val s = "Stall 1200 ms at 10:00 in BrainSection.invoke on 2026-10-12; 3 orders, median 180 ms"
        assertTrue(HealthExport.redact(s) == s, HealthExport.redact(s))
        assertFalse(HealthExport.redact("api_key=$apiKey").contains(apiKey))
        assertFalse(HealthExport.redact("my pin is set; PIN: 4321").contains("4321"))
    }

    @Test fun onlyTheLastFourteenDaysAndAtMostSixtyLinesOfATrace() {
        var b = HealthBook.Book()
        b = HealthBook.stall(b, at(d0.minusDays(20), 10), 900, "Old.place")
        b = HealthBook.stall(b, at(d0, 10), 900, "New.place")
        val long = (1..200).joinToString("\n") { "  at com.optionslab.X.f$it(X.kt:$it)" }
        val text = HealthExport.build(b, listOf(HealthExport.Trace(at(d0, 9), HealthBook.ExitKind.ANR, long)), emptyList(), d0, "b", "d", ::hhmm)
        assertFalse(text.contains("Old.place"))
        assertTrue(text.contains("New.place"))
        assertTrue(text.contains("f60(") && !text.contains("f61("))
    }
}
