package com.optionslab.engine.signals

import com.optionslab.engine.Right
import com.optionslab.engine.signals.NtfyParser.Expiry
import com.optionslab.engine.signals.NtfyParser.Parsed
import com.optionslab.engine.signals.NtfyParser.StrikeRule
import com.optionslab.engine.signals.NtfyParser.Trig
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ntfy alerts' strict reader (25 Oct): every accepted form, every refusal, Hindi, caps and spaces. */
class NtfyParserTest {
    private val day = LocalDate.of(2026, 10, 26)

    private fun buy(text: String): Parsed.Buy = assertIs<Parsed.Buy>(NtfyParser.parse(text, day), text)
    private fun refused(text: String): String = assertIs<Parsed.Refused>(NtfyParser.parse(text, day), text).reason

    @Test fun theBoxForms() {
        assertEquals(Parsed.Buy("NIFTY", Right.CE, 24500.0), buy("BUY NIFTY 24500 CE"))
        val b = buy("NIFTY 24500 PE BUY @ 120 SL 90 TGT 180")
        assertEquals(Parsed.Buy("NIFTY", Right.PE, 24500.0, trig = Trig.ZONE, entry = 120.0, stop = 90.0, target = 180.0), b)
        assertEquals(Parsed.Buy("BANKNIFTY", Right.CE), buy("BANKNIFTY CE"))
        assertEquals(Parsed.Buy("SENSEX", Right.PE), buy("SENSEX PUT"))
        for ((w, r) in listOf("bullish" to Right.CE, "bearish" to Right.PE, "long" to Right.CE, "short" to Right.PE, "up" to Right.CE, "down" to Right.PE))
            assertEquals(Parsed.Buy("NIFTY", r), buy("NIFTY $w"), w)
    }

    @Test fun capsSpacesAndJoinedWords() {
        assertEquals(Parsed.Buy("NIFTY", Right.CE, 24500.0), buy("   buy    nifty   24500ce  "))
        assertEquals(Parsed.Buy("BANKNIFTY", Right.PE, 51000.0), buy("Bank Nifty 51,000 PE"))
        assertEquals(Parsed.Buy("BANKNIFTY", Right.CE), buy("nifty bank call"))
        assertEquals(Parsed.Buy("FINNIFTY", Right.CE), buy("Fin Nifty CALL"))
        assertEquals(Parsed.Buy("MIDCPNIFTY", Right.PE), buy("midcap nifty put"))
        assertEquals(Parsed.Buy("NIFTY", Right.CE), buy("NIFTY 50 call"))
        assertEquals(Parsed.Buy("NIFTY", Right.CE), buy("nifty50 call"))
        assertEquals(Parsed.Buy("NIFTY", Right.PE, 24500.0), buy("ce?? no: PE 24500 NIFTY".replace("ce?? no: ", "")))
        assertEquals(Parsed.Buy("NIFTY", Right.CE, 24500.0), buy("NIFTY CE24500"))
    }

    @Test fun hindi() {
        assertEquals(Parsed.Buy("NIFTY", Right.CE), buy("निफ्टी कॉल खरीदें"))
        assertEquals(Parsed.Buy("BANKNIFTY", Right.PE), buy("बैंक निफ्टी पुट"))
        assertEquals(Parsed.Buy("SENSEX", Right.CE), buy("सेंसेक्स तेजी"))
        assertEquals(Parsed.Buy("NIFTY", Right.PE, stop = 90.0, target = 180.0), buy("निफ्टी मंदी स्टॉप लॉस 90 टारगेट 180"))
        assertEquals(Parsed.Buy("NIFTY", Right.CE, 24500.0, stop = 90.0), buy("निफ्टी २४५०० कॉल SL ९०"))
        assertEquals(Parsed.Exit("NIFTY"), NtfyParser.parse("निफ्टी बेचो", day))
    }

    @Test fun entryStopTargetAndLots() {
        assertEquals(Parsed.Buy("NIFTY", Right.CE, trig = Trig.ABOVE, entry = 100.0), buy("NIFTY CE buy above 100"))
        assertEquals(Parsed.Buy("NIFTY", Right.CE, trig = Trig.ABOVE, entry = 100.0), buy("NIFTY CE abv 100"))
        assertEquals(Parsed.Buy("NIFTY", Right.CE, trig = Trig.ZONE, entry = 100.0), buy("NIFTY CE near 100"))
        assertEquals(Parsed.Buy("NIFTY", Right.CE, stopPct = 20.0), buy("NIFTY CE SL 20%"))
        assertEquals(Parsed.Buy("NIFTY", Right.CE, stop = 80.0), buy("NIFTY CE stop loss: 80"))
        assertEquals(Parsed.Buy("NIFTY", Right.CE, stop = 80.0), buy("NIFTY CE s/l 80"))
        assertEquals(Parsed.Buy("NIFTY", Right.CE, target = 180.0), buy("NIFTY CE target 180 / 220"))
        assertEquals(Parsed.Buy("NIFTY", Right.CE, target = 180.0), buy("NIFTY CE tgt 180,220"))
        assertEquals(Parsed.Buy("NIFTY", Right.CE, target = 180.0), buy("NIFTY CE T1 180"))
        assertEquals(Parsed.Buy("NIFTY", Right.CE, lots = 2), buy("NIFTY CE 2 lots"))
        assertEquals(Parsed.Buy("NIFTY", Right.CE, lots = 3), buy("NIFTY CE lots 3"))
        assertEquals(Parsed.Buy("NIFTY", Right.CE, 24500.0, lots = 1), buy("NIFTY 24500 CE 1 lot"))
        assertEquals(Parsed.Buy("NIFTY", Right.CE), buy("NIFTY CE at market"))
        assertEquals(Parsed.Buy("NIFTY", Right.CE, 24500.0), buy("buy nifty at 24500 ce"))
        assertEquals(Parsed.Buy("NIFTY", Right.CE, trig = Trig.ZONE, entry = 120.5), buy("NIFTY CE @ Rs 120.5"))
        assertEquals(Parsed.Buy("NIFTY", Right.CE, pick = StrikeRule.ITM1), buy("NIFTY ITM CE"))
        assertEquals(Parsed.Buy("NIFTY", Right.PE, pick = StrikeRule.OTM1), buy("NIFTY OTM PE"))
        assertEquals(Parsed.Buy("NIFTY", Right.PE, pick = StrikeRule.ATM), buy("NIFTY ATM PE"))
    }

    @Test fun expiries() {
        assertEquals(Expiry.Nearest, buy("NIFTY CE weekly").expiry)
        assertEquals(Expiry.Nearest, buy("NIFTY CE this week").expiry)
        assertEquals(Expiry.Next, buy("NIFTY CE next").expiry)
        assertEquals(Expiry.Next, buy("NIFTY CE next week").expiry)
        assertEquals(Expiry.On(LocalDate.of(2026, 10, 28)), buy("NIFTY CE 28 oct").expiry)
        assertEquals(Expiry.On(LocalDate.of(2026, 10, 28)), buy("NIFTY CE oct 28").expiry)
        assertEquals(Expiry.On(LocalDate.of(2026, 10, 28)), buy("NIFTY CE 28oct").expiry)
        assertEquals(Expiry.On(LocalDate.of(2026, 10, 28)), buy("NIFTY CE October 28").expiry)
        assertEquals(Expiry.On(LocalDate.of(2026, 11, 4)), buy("NIFTY CE 4/11").expiry)
        assertEquals(Expiry.On(LocalDate.of(2026, 11, 4)), buy("NIFTY CE 2026-11-04").expiry)
        assertEquals(Expiry.On(LocalDate.of(2026, 11, 4)), buy("NIFTY CE 4 nov 2026").expiry)
        // A date more than a week past is next year's.
        assertEquals(Expiry.On(LocalDate.of(2027, 1, 6)), buy("NIFTY CE 6 jan").expiry)
        assertEquals(Expiry.On(LocalDate.of(2026, 11, 4)), buy("NIFTY 24500 CE 4 nov @ 120 SL 90").expiry)
        assertTrue(refused("NIFTY CE weekly next").contains("two different expiries"))
        assertTrue(refused("NIFTY CE 31/2").contains("not a date"))
        assertTrue(refused("NIFTY CE oct").contains("month with no day"))
        assertTrue(refused("NIFTY CE 2026-02-31").contains("not a date"))
        assertTrue(refused("NIFTY CE 31 feb").contains("not a date"))
    }

    @Test fun exitsAreReduceOnlyAndNeverAShort() {
        assertEquals(Parsed.Exit("NIFTY"), NtfyParser.parse("EXIT NIFTY", day))
        assertEquals(Parsed.Exit("NIFTY", Right.CE), NtfyParser.parse("sell nifty 24500 ce", day))
        assertEquals(Parsed.Exit("BANKNIFTY", Right.PE), NtfyParser.parse("close banknifty put", day))
        assertEquals(Parsed.Exit("SENSEX"), NtfyParser.parse("book profit sensex", day))
        assertEquals(Parsed.Exit("NIFTY"), NtfyParser.parse("square off nifty", day))
        assertTrue(refused("exit").contains("names no index"))
        assertTrue(refused("buy nifty ce and exit").contains("both buy and sell"))
        assertTrue(refused("exit nifty and banknifty").contains("two indices"))
        assertTrue(refused("exit nifty ce pe").contains("both CE and PE"))
        assertEquals("exit NIFTY CE", Parsed.Exit("NIFTY", Right.CE).line())
    }

    @Test fun refusals() {
        assertTrue(refused("").contains("empty"))
        assertTrue(refused("🙂🙂").contains("empty"))
        assertTrue(refused("RELIANCE 2500 CE").contains("no index named"))
        assertTrue(refused("NIFTY BANKNIFTY CE").contains("two indices"))
        assertTrue(refused("NIFTY CE PE").contains("both CE and PE"))
        assertTrue(refused("NIFTY bullish bearish").contains("both CE and PE"))
        assertTrue(refused("NIFTY 24500").contains("no direction"))
        assertTrue(refused("NIFTY FUT buy").contains("futures"))
        assertTrue(refused("CRUDEOIL 6000 CE").contains("MCX"))
        assertTrue(refused("BTC call").contains("crypto"))
        assertTrue(refused("BANKEX CE").contains("BANKEX"))
        assertTrue(refused("don't buy NIFTY CE").contains("negative"))
        assertTrue(refused("if nifty breaks, buy ce").contains("negative"))
        assertTrue(refused("NIFTY 24500 CE 120").contains("number not understood"))
        assertTrue(refused("NIFTY 24500 25000 CE").contains("two strikes"))
        assertTrue(refused("NIFTY CE @ 120 @ 130").contains("two entry"))
        assertTrue(refused("NIFTY CE SL 90 SL 80").contains("two stops"))
        assertTrue(refused("NIFTY CE TGT 180 TGT 200").contains("two targets"))
        assertTrue(refused("NIFTY CE SL").contains("stop word"))
        assertTrue(refused("NIFTY CE TGT").contains("target word"))
        assertTrue(refused("NIFTY CE lots").contains("lot word"))
        assertTrue(refused("NIFTY CE 2 lots 3 lot").contains("two lot"))
        assertTrue(refused("NIFTY CE 1.5 lots").contains("whole number"))
        assertTrue(refused("NIFTY CE 99 lots").contains("whole number"))
        assertTrue(refused("NIFTY CE @ 100 SL 120").contains("not below the entry"))
        assertTrue(refused("NIFTY CE @ 100 TGT 90").contains("not above the entry"))
        assertTrue(refused("NIFTY CE SL 100 TGT 90").contains("stop is not below the target"))
        assertTrue(refused("NIFTY CE SL 120%").contains("percentage must be"))
        assertTrue(refused("NIFTY CE 20%").contains("percentage not understood"))
        assertTrue(refused("NIFTY CE @ 0").contains("zero"))
        assertTrue(refused("NIFTY CE @ 120 at market").contains("at market"))
        assertTrue(refused("NIFTY ATM 24500 CE").contains("both a strike"))
        assertTrue(refused("NIFTY ATM OTM CE").contains("two strike choices"))
        assertTrue(refused("NIFTY CE " + "x".repeat(700)).contains("too long"))
        // 25 Oct review: index levels are never strikes or premiums.
        assertTrue(refused("NIFTY PE below 24400").contains("index level"))
        assertTrue(refused("NIFTY CE breaks 24500").contains("index level"))
        assertTrue(refused("NIFTY CE above 24500").contains("looks like an index level"))
        assertTrue(refused("NIFTY CE SL 24400").contains("looks like an index level"))
    }

    @Test fun bossSenderAlerts() {
        // 25 Oct: Boss's sender puts the direction in the title.
        val t = NtfyParser.text("^NSEI Tripwire Hit: 22600.0! (Moving DOWN)", "Tripwire Value: 22600.0\nReasoning: The price moved from 22600.0 to " +
            "22600.0, which entered the 22600.0 threshold zone.")
        assertEquals(Parsed.Buy("NIFTY", Right.PE), NtfyParser.parse(t, day), "the level is the index's, never a strike")
        assertEquals(Parsed.Buy("NIFTY", Right.CE), NtfyParser.read("^NSEI Tripwire Hit: 22600.0! (Moving UP)", "Tripwire Value: 22600.0", day))
        assertTrue(assertIs<Parsed.Refused>(NtfyParser.read("⏱️ Status: ^NSEI is at 22600.0", "Regular check-in. The latest recorded price for ^NSEI is 22600.0.", day))
            .reason.contains("status"))
        assertEquals("only the body", NtfyParser.text(null, "only the body"))
        assertEquals("t\nb", NtfyParser.text("t", "b"))
        assertEquals("t", NtfyParser.text("t", " "))
    }

    @Test fun lines() {
        assertEquals("NIFTY 24500 CE (nearest expiry) at market, stop the arm's, target the arm's", Parsed.Buy("NIFTY", Right.CE, 24500.0).line())
        assertEquals("NIFTY 1 ITM PE (next expiry) 2 lots near 120, stop 20%, target 180", Parsed.Buy("NIFTY", Right.PE, expiry = Expiry.Next, lots = 2,
            trig = Trig.ZONE, entry = 120.0, stopPct = 20.0, target = 180.0, pick = StrikeRule.ITM1).line())
        assertEquals("SENSEX strike by the rule CE (2026-10-30) 1 lot at market, stop 90, target the arm's; index stop 80000, index target -",
            Parsed.Buy("SENSEX", Right.CE, expiry = Expiry.On(LocalDate.of(2026, 10, 30)), lots = 1, stop = 90.0, indexStop = 80000.0).line())
        assertEquals("120.5", NtfyParser.num(120.5))
        assertEquals(StrikeRule.OTM1, StrikeRule.of("otm1"))
        assertEquals(StrikeRule.ATM, StrikeRule.of("?"))
        assertEquals(Trig.ZONE, Trig.of("near"))
        assertEquals(Trig.MARKET, Trig.of(null))
    }

    // ---- the JSON signal (Boss's IraThinker) ---------------------------------------------------------------------------------

    private fun sig(side: String = "BUY_PUT", symbol: String = "NIFTY", yahoo: String = "^NSEI", test: Boolean = false, kind: String? = "PE",
                    strike: String = "null", expiry: String = "null", premium: String = "null", entry: String = "null", stop: String = "null",
                    t1: String = "null") =
        """{"type":"signal","test":$test,"source":"IraThinker","version":51,"strategy":"ORB","signalId":"S1","tsIST":"2026-10-26T10:12:44+05:30",""" +
            """"symbol":"$symbol","yahooSymbol":"$yahoo","side":"$side","entry":$entry,"stop":$stop,"target1":$t1,"target2":null,"score":0.7,""" +
            """"option":{"strike":$strike,"expiry":$expiry,"kind":${kind?.let { "\"$it\"" } ?: "null"},"premium":$premium}}"""

    private fun read(json: String) = NtfyParser.read("signal", json, day)

    @Test fun aSignalBuys() {
        val b = assertIs<Parsed.Buy>(read(sig()))
        assertEquals("NIFTY", b.index); assertEquals(Right.PE, b.right); assertNull(b.strike); assertEquals(Expiry.Nearest, b.expiry)
        assertEquals(Trig.MARKET, b.trig); assertEquals("S1", b.signalId); assertEquals(LocalDateTime.of(2026, 10, 26, 10, 12, 44), b.sentAt)
        assertEquals("IraThinker · v51 · ORB · score 0.7", b.from)
        val c = assertIs<Parsed.Buy>(read(sig("BUY_CALL", kind = "CE", strike = "24500", expiry = "\"2026-10-28\"", premium = "120.5", entry = "24480",
            stop = "24400", t1 = "24600")))
        assertEquals(Right.CE, c.right); assertEquals(24500.0, c.strike); assertEquals(Expiry.On(LocalDate.of(2026, 10, 28)), c.expiry)
        assertEquals(Trig.UPTO, c.trig); assertEquals(120.5, c.entry); assertEquals(24400.0, c.indexStop); assertEquals(24600.0, c.indexTarget)
        assertNull(c.stop, "the stop is the index's, never the premium's")
        val p = assertIs<Parsed.Buy>(read(sig(entry = "24480", stop = "24560", t1 = "24300")))
        assertEquals(24560.0, p.indexStop)
        assertEquals("BANKNIFTY", assertIs<Parsed.Buy>(read(sig(symbol = "BANKNIFTY", yahoo = "^NSEBANK"))).index)
        assertEquals("SENSEX", assertIs<Parsed.Buy>(read(sig(symbol = "", yahoo = "^BSESN"))).index)
        assertEquals("FINNIFTY", assertIs<Parsed.Buy>(read(sig(symbol = "FINNIFTY", yahoo = ""))).index)
        assertEquals(Right.PE, assertIs<Parsed.Buy>(read(sig(kind = null))).right)
        assertEquals(Right.CE, assertIs<Parsed.Buy>(read(sig("LONG", kind = "CALL"))).right)
    }

    /** 25 Oct: Boss's own sample (a stock: ADANIENT's 2500 PE), its levels the stock's own; "add normal stocks". */
    @Test fun bossStockSignalBuysItsOption() {
        val j = """{"type":"signal","test":false,"source":"IraThinker","version":51,
 "strategy":"MANUAL","signalId":"MANUAL|ADANIENT.NS|ORB_DOWN|1",
 "tsIST":"2026-10-25T14:14:00+05:30","symbol":"Adani Enterprises",
 "yahooSymbol":"ADANIENT.NS","side":"BUY_PUT","entry":2503.0,
 "stop":2522.9,"target1":2463.2,"target2":2443.3,"score":0.62,
 "option":{"strike":2500,"expiry":"2026-10-27","kind":"PE","premium":26.4}}"""
        val b = assertIs<Parsed.Buy>(read(j))
        assertEquals("ADANIENT", b.index); assertTrue(b.stock); assertEquals(Right.PE, b.right); assertEquals(2500.0, b.strike)
        assertEquals(Expiry.On(LocalDate.of(2026, 10, 27)), b.expiry); assertEquals(Trig.UPTO, b.trig); assertEquals(26.4, b.entry)
        assertEquals(2522.9, b.indexStop); assertEquals(2463.2, b.indexTarget); assertEquals("MANUAL|ADANIENT.NS|ORB_DOWN|1", b.signalId)
        assertEquals(LocalDateTime.of(2026, 10, 25, 14, 14), b.sentAt)
        assertEquals("IraThinker · v51 · MANUAL · score 0.62", b.from)
        // A stock signal whose levels are on the wrong side is refused like an index one.
        assertIs<Parsed.Refused>(read(j.replace("\"stop\":2522.9", "\"stop\":2480.0")))
        assertEquals("M&M", NtfyParser.stockOf("m&m.ns")); assertNull(NtfyParser.stockOf("^NSEI.NS")); assertNull(NtfyParser.stockOf("A B.NS"))
        assertNull(NtfyParser.stockOf(null)); assertNull(NtfyParser.stockOf("TCS.BO"))
        assertTrue(!assertIs<Parsed.Buy>(read(sig())).stock)
    }

    @Test fun aSignalExits() {
        assertEquals(Parsed.Exit("NIFTY", Right.PE, "S1"), read(sig("EXIT")))
        assertEquals(Parsed.Exit("NIFTY", null, "S1"), read(sig("SELL", kind = null)))
        assertEquals(Parsed.Exit("NIFTY", Right.CE, "S1"), read(sig("EXIT_CALL", kind = null)))
        assertEquals(Parsed.Exit("NIFTY", Right.PE, "S1"), read(sig("EXIT_PUT")))
        assertTrue(assertIs<Parsed.Refused>(read(sig("EXIT_CALL", kind = "PE"))).reason.contains("kind is PE"))
        assertTrue(assertIs<Parsed.Refused>(read(sig("EXIT_PUT", kind = "CE"))).reason.contains("kind is CE"))
    }

    @Test fun aSignalIsRefused() {
        fun r(j: String) = assertIs<Parsed.Refused>(read(j)).reason
        assertTrue(r(sig(test = true)).contains("test signal"))
        assertTrue(r(sig().replace("\"test\":false", "\"test\":\"true\"")).contains("test signal"))
        assertTrue(r(sig().replace("\"test\":false", "\"test\":1")).contains("test signal"))
        assertTrue(assertIs<Parsed.Buy>(read(sig().replace("\"test\":false,", ""))).index == "NIFTY", "no test field: a real signal")
        assertTrue(r(sig(symbol = "NIFTY", yahoo = "ADANIENT.NS")).contains("disagree"))
        assertTrue(r(sig(symbol = "TEST", yahoo = "TEST")).contains("not an index or NSE stock signal (TEST)"))
        assertTrue(r(sig(symbol = "Reliance", yahoo = "RELIANCE.BO", strike = "2500")).contains("not an index or NSE stock signal"))
        assertTrue(r(sig(symbol = "Reliance", yahoo = "RELIANCE.NS")).contains("needs its option strike"))
        assertTrue(r(sig(symbol = "", yahoo = "")).contains("no symbol"))
        assertTrue(r(sig(symbol = "BANKNIFTY", yahoo = "^NSEI")).contains("different indices"))
        assertTrue(r(sig(side = "BUY_CALL")).contains("but option kind PE"))
        assertTrue(r(sig(side = "HOLD")).contains("side not understood"))
        assertTrue(r(sig(kind = "FUT")).contains("kind not understood"))
        assertTrue(r(sig(strike = "120")).contains("strike not understood"))
        assertTrue(r(sig(expiry = "\"soon\"")).contains("expiry not understood"))
        assertTrue(r(sig("BUY_CALL", kind = "CE", entry = "24500", stop = "24600")).contains("index stop"))
        assertTrue(r(sig("BUY_CALL", kind = "CE", entry = "24500", t1 = "24400")).contains("index target"))
        assertTrue(r(sig("BUY_CALL", kind = "CE", stop = "24600", t1 = "24400")).contains("wrong sides"))
        assertTrue(r("""{"type":"signal","test":true "side":"BUY_PUT"}""").contains("not valid JSON"))
        assertTrue(r("""{"type":"note"}""").contains("not \"signal\""))
        assertTrue(r("""{"type":"signal","symbol":"NIFTY"}""").contains("no side"))
        assertTrue(r("{" + " ".repeat(4_100) + "}").contains("too long"))
        // Boss's pasted test signal (25 Oct): test, and an unknown symbol.
        assertTrue(r("""{"type":"signal","test":true,"source":"IraThinker","version":51,"strategy":"TEST","signalId":"TEST","tsIST":"2026-10-23T23:12:44+05:30",""" +
            """"symbol":"TEST","yahooSymbol":"TEST","side":"BUY_PUT","entry":null,"stop":null,"target1":null,"target2":null,"score":null,""" +
            """"option":{"strike":null,"expiry":null,"kind":"PE","premium":null}}""").contains("test signal"))
        assertNull(NtfyParser.indexOf(null))
    }
}
