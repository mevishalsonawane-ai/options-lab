package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 20 Nov 2026: the "Share full settings and state" file's pure half ([StateShare]): its first line and headings, and the redaction -
 * a planted token, API key, client id, relay URL, IP, e-mail and phone number never reach the text, while the words that explain why
 * the app is (or is not) trading - trading symbols, reason codes, clock times, rupee figures, "PIN at arming" - stay as said.
 */
class StateShareTest {
    private val token = "k9Xa2Lm4Pq7Rt0Vw3Yz6Bc8De1Fg5Hj"
    private val apiKey = "abcd1234efgh5678"

    @Test fun everyPlantedSecretIsMasked() {
        val sample = """
            Zerodha session: access_token=$token api_key=$apiKey
            Authorization: token $apiKey:$token
            Logged in as AB1234 (client id: QX4321)
            Relay: wss://relay.example.net:8443/kite?key=$apiKey and ssh ubuntu@relay.example.net
            Static IP 13.234.56.78 and relay 10.0.0.12:22; ipv6 2406:da1a:6c3:a100::12 and fe80::1
            Write to boss.trader@example.com or call +91 98765 43210 or 9876543210
            pin: 482913 · jwt eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjMifQ.c2lnbmF0dXJl
            order 250120000123456 · hash 0123456789abcdef0123
        """.trimIndent()
        val out = StateShare.redact(sample)
        for (s in listOf(token, apiKey, "AB1234", "QX4321", "relay.example.net", "8443", "13.234.56.78", "10.0.0.12", "2406:da1a",
                "fe80::1", "boss.trader@example.com", "98765 43210", "9876543210", "482913", "eyJhbGciOiJIUzI1NiJ9", "250120000123456",
                "0123456789abcdef0123"))
            assertFalse(out.contains(s), "$s leaked:\n$out")
        assertTrue(out.contains(StateShare.LOGGED_IN_MASK), out)
        assertTrue(out.contains(StateShare.MASK), out)
    }

    @Test fun knownValuesAreMaskedWhereverTheyAppear() {
        val out = StateShare.redact("Welcome Ravi Kumar; topic set; relay my-box.lan", listOf("Ravi Kumar", "my-box.lan", "ab"))
        assertEquals("Welcome ${StateShare.MASK}; topic set; relay ${StateShare.MASK}", out)
    }

    @Test fun theWordsThatExplainTradingAreKept() {
        val line = "11-20 10:15:00 [armlive] R51 Donchian 20: Not mirrored today: 8 (last: the account stop rule: -Rs 41,200 from the best) · " +
            "MIDCPNIFTY27OCT2613250PE no_entry_window_closed · Gates: ✓ PIN at arming · ₹2,19,000 · 75 lots · 09:15-15:30 · R151 picker 0.4656"
        assertEquals(line, StateShare.redact(line))
        assertEquals("Trade live: on, out of shadow (real orders in Live)", StateShare.redact("Trade live: on, out of shadow (real orders in Live)"))
        assertEquals("token refreshed at 09:14", StateShare.redact("token refreshed at 09:14"))
        // An instrument's number, a financial year and a word that holds a known value are not secrets.
        assertEquals("stream token 25626500 subscribed · FY2026 · CY2025", StateShare.redact("stream token 25626500 subscribed · FY2026 · CY2025"))
        assertEquals("phone rooted: no · user ${StateShare.MASK}", StateShare.redact("phone rooted: no · user root", listOf("root")))
    }

    @Test fun theFileHasTheTopLineAndEveryHeadingInOrder() {
        val text = StateShare.build(StateShare.HEADINGS.map { StateShare.Section(it, listOf("line of $it", "two\nthree")) } +
            StateShare.Section("Empty", emptyList()), listOf(token))
        val lines = text.lines()
        assertEquals(StateShare.TOP_LINE, lines.first())
        var at = -1
        for (h in StateShare.HEADINGS) {
            val i = lines.indexOf("== $h")
            assertTrue(i > at, "$h in order")
            at = i
        }
        assertTrue(text.contains("== Empty\n(none)\n"))
        assertTrue(lines.contains("three"))
    }

    @Test fun theDiaryHelpersPickTodayAnAreaAndAnArm() {
        val d = LocalDate.of(2026, 11, 20)
        val lines = listOf("11-19 15:00:00 [orb] yesterday", "11-20 09:20:00 [orb] Liquidity 15+5: entry", "11-20 09:21:00 [armlive] R51 Donchian 20 refused",
            "not a diary line", "11-20 09:22:00 [passed-arms] r51 donchian 20 break skipped")
        val today = StateShare.today(lines, d)
        assertEquals(3, today.size)
        assertEquals(listOf("11-20 09:20:00 [orb] Liquidity 15+5: entry"), StateShare.inArea(today, "orb"))
        assertEquals(2, StateShare.naming(today, "R51 Donchian 20").size)
        assertEquals(1, StateShare.naming(today, "R51 Donchian 20", max = 1).size)
        assertTrue(StateShare.naming(today, " ").isEmpty())
        assertNull(StateShare.areaOf("not a diary line"))
        assertEquals("passed-arms", StateShare.areaOf(lines.last()))
        assertEquals(300, StateShare.inArea(List(400) { "11-20 09:00:00 [orders] n$it" }, "orders").size)
    }

    private data class Sample(val live: Boolean = false, val markets: Set<String> = setOf("NIFTY", "BANKNIFTY"), val lots: Int = 2, val name: String = "a, b")

    @Test fun aDataClassIsSplitIntoOneLinePerField() {
        assertEquals(listOf("live: false", "markets: [NIFTY, BANKNIFTY]", "lots: 2", "name: a, b"), StateShare.fields(Sample().toString()))
        assertNull(StateShare.fields("no fields here"))
        assertEquals(StateShare.fileName(LocalDate.of(2026, 11, 20)), "iraalgo-settings-and-state-2026-11-20.txt")
    }
}
