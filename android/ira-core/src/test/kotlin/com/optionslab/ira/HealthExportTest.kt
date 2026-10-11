package com.optionslab.ira

import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
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

    @Test fun theReviewsPlantedSecretsAreAllRemoved() {
        // Review R5 #6: JSON-quoted keys, a PIN said in words, an Indian phone written with a space or a dash, a lowercase
        // client id and the Kite "Authorization: token key:secret" header (its key part too).
        val kiteKey = "q7w8e9r0t1y2u3i4"
        val kiteToken = "Zx81Lm4Pq7Rt0Vw3"
        val planted = mapOf(
            """{"password":"hunter2pass","pin":"4821"}""" to listOf("hunter2pass", "4821"),
            """{"api_key": "$kiteKey", "access_token": "$kiteToken"}""" to listOf(kiteKey, kiteToken),
            """{'api_secret': 'sEcReT99x'}""" to listOf("sEcReT99x"),
            "Boss said his PIN is 4821 today" to listOf("4821"),
            "my otp was 735190" to listOf("735190"),
            "password is hunter2pass" to listOf("hunter2pass"),
            "call +91 98765 43210 or 98765-43210 or +91-98765-43210" to listOf("98765 43210", "98765-43210", "43210"),
            "client ab1234 and xyz98765 logged in" to listOf("ab1234", "xyz98765"),
            "Authorization: token $kiteKey:$kiteToken" to listOf(kiteKey, kiteToken),
            "header authorization=token $kiteKey:$kiteToken sent" to listOf(kiteKey, kiteToken),
        )
        for ((line, secrets) in planted) {
            val out = HealthExport.redact(line)
            for (sec in secrets) assertFalse(out.contains(sec), "leaked $sec from: $line -> $out")
            assertTrue(out.contains(HealthExport.REDACTED), out)
        }
        // And through the whole report.
        val text = HealthExport.build(HealthBook.Book(), listOf(HealthExport.Trace(at(d0, 11), HealthBook.ExitKind.CRASH,
            "  at com.optionslab.X.f(X.kt:1)\n  body {\"pin\":\"4821\",\"password\":\"hunter2pass\"} phone 98765 43210 client ab1234")),
            listOf("Authorization: token $kiteKey:$kiteToken"), d0, "b", "d", ::hhmm)
        for (sec in listOf("4821", "hunter2pass", "98765 43210", "ab1234", kiteKey, kiteToken)) assertFalse(text.contains(sec), "leaked $sec\n$text")
        // Ordinary words and numbers stay.
        val plain = "Stall 1200 ms at 10:00 in BrainSection.invoke on 2026-10-12; 3 orders, median 180 ms; pin the chart; open 24500 CE"
        assertEquals(plain, HealthExport.redact(plain))
    }
}
