package com.optionslab.engine.signals

import com.optionslab.engine.Right
import com.optionslab.engine.signals.NtfyParser.Expiry
import com.optionslab.engine.signals.NtfyParser.Parsed
import com.optionslab.engine.signals.NtfyParser.Trig
import com.optionslab.engine.signals.NtfyRules.Settings
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 26 Oct (IraThinker review, irathinker-app/REVIEW_2026-10-26.md "Compatibility"): IraThinker's System Feed payloads as
 * SystemFeed.java builds them. mom60's own exits (30% premium stop, 60-minute hold) are read and only ever make IraAlgo's exits
 * tighter or earlier; a strangle, a price alert and the market flush are information; a stock strike off the exchange's grid says
 * so (and whether the sender estimated it); tsIST is the send time.
 */
class NtfyIraThinkerTest {
    private val day = LocalDate.of(2026, 10, 26)
    private fun read(json: String) = NtfyParser.read("signal", json, day)

    /** SystemFeed.mom60Payload's shape exactly (key order as it puts them). */
    private fun mom60(premium: String = "412.5", premiumStop: String = "288.75", exitBy: String? = "\"2026-10-26T11:16:00+05:30\"",
                      paper: String = "true", symbol: String = "BANKNIFTY", yahoo: String = "^NSEBANK", extra: String = "") =
        """{"type":"signal","test":false,"source":"IraThinker","version":51,"strategy":"MOM60","signalId":"MOM60|$yahoo|1761457560",""" +
            """"tsIST":"2026-10-26T10:16:02+05:30","paper":$paper,"symbol":"$symbol","yahooSymbol":"$yahoo","side":"BUY_CALL","entry":56234.5,""" +
            """"option":{"strike":56200,"expiry":"2026-10-27","kind":"CE","premium":$premium},"premiumStop":$premiumStop""" +
            (exitBy?.let { ""","exitBy":$it""" } ?: "") + ""","dte":1,"riskRs":3712.5$extra}"""

    @Test fun mom60IsReadWithItsOwnExitsLabelledAndPaperOnly() {
        val b = assertIs<Parsed.Buy>(read(mom60()))
        assertEquals("BANKNIFTY", b.index); assertEquals(Right.CE, b.right); assertEquals(56200.0, b.strike)
        assertEquals(Expiry.On(LocalDate.of(2026, 10, 27)), b.expiry); assertEquals(Trig.UPTO, b.trig); assertEquals(412.5, b.entry)
        assertEquals(30.0, b.srcStopPct, "288.75 of 412.5 is a 30% stop"); assertNull(b.srcStop)
        assertEquals(LocalDateTime.of(2026, 10, 26, 11, 16), b.exitBy)
        assertEquals("IraThinker mom60", b.strategy); assertTrue(b.paper)
        assertNull(b.indexStop); assertNull(b.indexTarget); assertNull(b.stop, "a premium stop is never the message's own stop")
        assertTrue(b.line().contains("the source rule: stop 30%, out by 11:16 · IraThinker mom60 · paper only"), b.line())
        // FINNIFTY by its Yahoo symbol ^CNXFIN (not in IraAlgo's list): its "symbol" names it.
        assertEquals("FINNIFTY", assertIs<Parsed.Buy>(read(mom60(symbol = "FINNIFTY", yahoo = "^CNXFIN"))).index)
        assertEquals("SENSEX", assertIs<Parsed.Buy>(read(mom60(symbol = "SENSEX", yahoo = "^BSESN"))).index)
        // "paper": false, absent: not paper-only. Anything else ("yes", 1): paper-only (the safer reading).
        assertFalse(assertIs<Parsed.Buy>(read(mom60(paper = "false"))).paper)
        assertTrue(assertIs<Parsed.Buy>(read(mom60(paper = "\"yes\""))).paper)
        assertTrue(assertIs<Parsed.Buy>(read(mom60(paper = "1"))).paper)
    }

    @Test fun missingExitFieldsKeepTodaysExits() {
        val b = assertIs<Parsed.Buy>(read(mom60(premiumStop = "null", exitBy = null)))
        assertNull(b.srcStopPct); assertNull(b.srcStop); assertNull(b.exitBy); assertNull(b.holdMin); assertNull(b.srcTarget)
        assertFalse(b.line().contains("the source rule"), b.line())
        // A plain ORB signal carries none: nothing changes from before.
        val orb = """{"type":"signal","test":false,"source":"IraThinker","version":51,"strategy":"ORB_BREAKDOWN","signalId":"S","tsIST":"2026-10-26T10:12:44+05:30",""" +
            """"symbol":"NIFTY","yahooSymbol":"^NSEI","side":"BUY_PUT","entry":24480,"stop":24520,"target1":24400,"target2":24360,"score":0.7,""" +
            """"option":{"strike":24500,"expiry":"2026-10-28","kind":"PE","premium":120.5}}"""
        val o = assertIs<Parsed.Buy>(read(orb))
        assertNull(o.srcStopPct); assertNull(o.exitBy); assertFalse(o.paper); assertEquals("IraThinker ORB breakdown", o.strategy)
        val ex = NtfyRules.sourceExits(o, 100.0, day.atTime(10, 13), day.atTime(15, 15), Settings())
        assertEquals(NtfyRules.stop(null, null, 100.0, Settings()).first, ex.stop); assertTrue(ex.ours)
        assertEquals(NtfyRules.target(null, 100.0), ex.target); assertNull(ex.exitAt); assertTrue(ex.notes.isEmpty())
        // A premium stop with no premium to compare stays a premium level.
        val np = assertIs<Parsed.Buy>(read(mom60(premium = "null")))
        assertNull(np.srcStopPct); assertEquals(288.75, np.srcStop); assertEquals(Trig.MARKET, np.trig)
    }

    @Test fun badExitNumbersAreRefusedNeverGuessed() {
        fun r(j: String) = assertIs<Parsed.Refused>(read(j), j).reason
        assertTrue(r(mom60(premiumStop = "\"thirty\"")).contains("premiumStop not understood"))
        assertTrue(r(mom60(premiumStop = "-5")).contains("premiumStop not understood"))
        assertTrue(r(mom60(premiumStop = "0")).contains("premiumStop not understood"))
        assertTrue(r(mom60(premiumStop = "450")).contains("not below the premium"))
        assertTrue(r(mom60(premiumStop = "412.5")).contains("not below the premium"))
        assertTrue(r(mom60(exitBy = "\"at 11\"")).contains("exitBy not understood"))
        assertTrue(r(mom60(exitBy = "42")).contains("exitBy not understood"))
        assertTrue(r(mom60(exitBy = "\"2026-10-26T10:00:00+05:30\"")).contains("not after its own time"))
        assertTrue(r(mom60(extra = ""","holdMin":0""")).contains("hold not understood"))
        assertTrue(r(mom60(extra = ""","holdMin":12.5""")).contains("hold not understood"))
        assertTrue(r(mom60(extra = ""","holdMinutes":999""")).contains("hold not understood"))
        assertTrue(r(mom60(extra = ""","holdMin":"60"""")).contains("holdMin not understood"))
        assertTrue(r(mom60(premiumStop = "null", extra = ""","stopPct":100""")).contains("between 0 and 100"))
        assertTrue(r(mom60(premiumStop = "null", extra = ""","premiumStopPct":0""")).contains("between 0 and 100"))
        assertTrue(r(mom60(extra = ""","premiumTarget":400""")).contains("not above the premium"))
        assertTrue(r(mom60(extra = ""","premiumTarget":-1""")).contains("premiumTarget not understood"))
        // Explicit fields read: a percent, a target, a hold.
        val b = assertIs<Parsed.Buy>(read(mom60(premiumStop = "null", exitBy = null, extra = ""","stopPct":15,"premiumTarget":500,"holdMin":45""")))
        assertEquals(15.0, b.srcStopPct); assertEquals(500.0, b.srcTarget); assertEquals(45, b.holdMin)
        // An exitBy given in UTC reads as IST.
        assertEquals(LocalDateTime.of(2026, 10, 26, 11, 16), assertIs<Parsed.Buy>(read(mom60(exitBy = "\"2026-10-26T05:46:00Z\""))).exitBy)
    }

    @Test fun aPayloadOnlyEverTightensOrShortensTheExits() {
        val s = Settings() // IraAlgo's own stop: 20% under the fill, target 40% over it.
        val sq = day.atTime(15, 15); val fill = day.atTime(10, 16, 30)
        val b = assertIs<Parsed.Buy>(read(mom60()))
        // mom60's 30% stop is WIDER than IraAlgo's 20%: IraAlgo's kept, and the record says so. Its 11:16 exit is earlier: used.
        val ex = NtfyRules.sourceExits(b, 400.0, fill, sq, s)
        assertEquals(320.0, ex.stop, 1e-9); assertTrue(ex.ours)
        assertTrue(ex.notes.any { it.contains("wider than IraAlgo's own (320): IraAlgo's kept") }, ex.notes.toString())
        assertEquals(560.05, ex.target, 1e-9)
        assertEquals(day.atTime(11, 16), ex.exitAt); assertTrue(ex.notes.any { it == "out by 11:16 (the source rule's hold)" })
        // A 10% stop is TIGHTER: used.
        val tight = NtfyRules.sourceExits(b.copy(srcStopPct = 10.0), 400.0, fill, sq, s)
        assertEquals(360.0, tight.stop, 1e-9); assertFalse(tight.ours); assertTrue(tight.notes.any { it == "the source rule's stop (10%) used" })
        // An absolute premium stop: tighter used, wider or above the fill not.
        assertEquals(350.0, NtfyRules.sourceExits(b.copy(srcStopPct = null, srcStop = 350.0), 400.0, fill, sq, s).stop, 1e-9)
        assertEquals(320.0, NtfyRules.sourceExits(b.copy(srcStopPct = null, srcStop = 200.0), 400.0, fill, sq, s).stop, 1e-9)
        assertEquals(320.0, NtfyRules.sourceExits(b.copy(srcStopPct = null, srcStop = 410.0), 400.0, fill, sq, s).stop, 1e-9)
        // A target: nearer used, further not.
        assertEquals(450.0, NtfyRules.sourceExits(b.copy(srcTarget = 450.0), 400.0, fill, sq, s).target, 1e-9)
        val far = NtfyRules.sourceExits(b.copy(srcTarget = 900.0), 400.0, fill, sq, s)
        assertEquals(560.05, far.target, 1e-9); assertTrue(far.notes.any { it.contains("further than IraAlgo's") })
        // A hold from the fill: 45 minutes; with exitBy too, the earlier.
        assertEquals(day.atTime(11, 1, 30), NtfyRules.sourceExits(b.copy(exitBy = null, holdMin = 45), 400.0, fill, sq, s).exitAt)
        assertEquals(day.atTime(11, 1, 30), NtfyRules.sourceExits(b.copy(holdMin = 45), 400.0, fill, sq, s).exitAt)
        // A hold past the square-off (or the expiry day's 15:05): the square-off comes first, no time exit.
        val late = NtfyRules.sourceExits(b.copy(exitBy = day.atTime(15, 30)), 400.0, day.atTime(14, 30), sq, s)
        assertNull(late.exitAt); assertTrue(late.notes.any { it.contains("the square-off comes first") })
        val expiry = NtfyRules.sourceExits(b.copy(exitBy = day.atTime(15, 10)), 400.0, day.atTime(14, 30), day.atTime(NtfyRules.squareOff(true)), s)
        assertNull(expiry.exitAt, "expiry day: 15:10 is after the 15:05 square-off")
        assertEquals(day.atTime(15, 4), NtfyRules.sourceExits(b.copy(exitBy = day.atTime(15, 4)), 400.0, day.atTime(14, 30), day.atTime(15, 5), s).exitAt)
        // The message's own text stop path is unchanged (a JSON signal never sets it).
        val text = NtfyParser.parse("NIFTY 24500 CE SL 90", day) as Parsed.Buy
        assertEquals(90.0, NtfyRules.sourceExits(text, 120.0, fill, sq, s).stop)
    }

    @Test fun sellSideAndMultiLegMessagesAreInformation() {
        // SystemFeed.stranglePayload exactly.
        val strangle = """{"type":"signal","test":false,"source":"IraThinker","version":51,"strategy":"STRANGLE","signalId":"STRANGLE|^NSEBANK|2026-10-26",""" +
            """"tsIST":"2026-10-26T09:30:05+05:30","paper":true,"symbol":"BANKNIFTY","yahooSymbol":"^NSEBANK","side":"SELL_STRANGLE","entry":56100,""" +
            """"legs":[{"kind":"CE","strike":56300,"premium":310.2},{"kind":"PE","strike":55900,"premium":295.4}],"creditPts":605.6,"creditRs":18168,""" +
            """"breakevenUpper":56905.6,"breakevenLower":55294.4,"expiry":"2026-10-27","dte":1,"exitDate":"2026-10-27","exitPlan":"SETTLE_AT_EXPIRY 2026-10-27"}"""
        val i = assertIs<Parsed.Info>(read(strangle))
        assertTrue(i.reason.startsWith(NtfyParser.ONLY_BUYS), i.reason); assertTrue(i.reason.contains("SELL_STRANGLE"))
        assertEquals("information, not traded: IraAlgo only buys options", NtfyParser.ONLY_BUYS)
        // Any other sale or several legs, whatever the symbol.
        for (side in listOf("SELL_STRADDLE", "SHORT_STRANGLE", "SELL_CE", "SELL_PE", "BUY_CALL_SPREAD", "IRON_CONDOR"))
            assertIs<Parsed.Info>(read(strangle.replace("SELL_STRANGLE", side).replace("\"legs\"", "\"x\"")
                .replace("\"strategy\":\"STRANGLE\"", "\"strategy\":\"S\"")), side)
        assertIs<Parsed.Info>(read(strangle.replace("SELL_STRANGLE", "BUY_CALL")), "legs: a multi-leg trade")
        // SELL / SELL_CALL / SELL_PUT stay exits of what the arm holds (reduce-only), never information.
        val exit = strangle.replace(",\"legs\":[{\"kind\":\"CE\",\"strike\":56300,\"premium\":310.2},{\"kind\":\"PE\",\"strike\":55900,\"premium\":295.4}]", "")
            .replace("\"strategy\":\"STRANGLE\"", "\"strategy\":\"S\"")
        assertEquals(Parsed.Exit("BANKNIFTY", Right.CE, "STRANGLE|^NSEBANK|2026-10-26"), read(exit.replace("SELL_STRANGLE", "SELL_CALL")))
        assertEquals(Parsed.Exit("BANKNIFTY", null, "STRANGLE|^NSEBANK|2026-10-26"), read(exit.replace("SELL_STRANGLE", "SELL")))
        // Price alerts and the market flush: information, never "a signal with no side" / "no symbol".
        val price = """{"type":"signal","test":false,"source":"IraThinker","version":51,"strategy":"PRICE_ALERT","signalId":"^NSEI|PRICE_ALERT|ABOVE|2460000|1",""" +
            """"tsIST":"2026-10-26T10:00:00+05:30","symbol":"NIFTY 50","yahooSymbol":"^NSEI","level":24600,"direction":"ABOVE","ltp":24603.1,"recurring":false}"""
        assertEquals("information, not traded: a price alert on NIFTY 50 at 24600, not a trade signal", assertIs<Parsed.Info>(read(price)).reason)
        val flush = """{"type":"signal","test":false,"source":"IraThinker","version":51,"strategy":"MARKET_FLUSH","signalId":"MARKET_FLUSH|2026-10-26",""" +
            """"tsIST":"2026-10-26T10:00:00+05:30","date":"2026-10-26","down":31,"total":48}"""
        assertTrue(assertIs<Parsed.Info>(read(flush)).reason.startsWith("information, not traded: the market's context"))
        // A test stays a test.
        assertIs<Parsed.Refused>(read(strangle.replace("\"test\":false", "\"test\":true")))
    }

    @Test fun aStockStrikeOffTheGridSaysSoAndWhetherItWasEstimated() {
        val infy = """{"type":"signal","test":false,"source":"IraThinker","version":51,"strategy":"ORB_BREAKOUT","signalId":"INFY-1",""" +
            """"tsIST":"2026-10-26T10:00:00+05:30","symbol":"Infosys","yahooSymbol":"INFY.NS","side":"BUY_CALL","entry":1131,"stop":1120,""" +
            """"target1":1153,"target2":1164,"score":0.6,"option":{"strike":1130,"expiry":"2026-10-27","kind":"CE","premium":14.2%s}}"""
        assertFalse(assertIs<Parsed.Buy>(read(infy.format(""))).strikeGuess)
        for (f in listOf(""","strikeEstimated":true""", ""","strikeVerified":false""", ""","strikeSource":"estimated"""", ""","strikeSource":"GUESS""""))
            assertTrue(assertIs<Parsed.Buy>(read(infy.format(f))).strikeGuess, f)
        assertFalse(assertIs<Parsed.Buy>(read(infy.format(""","strikeVerified":true,"strikeSource":"chain""""))).strikeGuess)
        val why = NtfyRules.offGrid("INFY", 1130.0, Right.CE, LocalDate.of(2026, 10, 27), listOf(1100.0, 1120.0, 1140.0, 1160.0), guessed = true)
        assertEquals("not traded: INFY 1130 CE is not listed for 2026-10-27 - not on the exchange's strike grid (listed either side: 1120 and 1140); " +
            "the message says this strike is estimated by the sender, not read from the exchange. Never another strike in its place.", why)
        val plain = NtfyRules.offGrid("INFY", 1500.0, Right.PE, LocalDate.of(2026, 10, 27), listOf(1100.0, 1120.0), guessed = false)
        assertTrue(plain.contains("(listed either side: 1120)") && !plain.contains("estimated"), plain)
        assertFalse(NtfyRules.offGrid("INFY", 1130.0, Right.CE, LocalDate.of(2026, 10, 27), emptyList(), false).contains("either side"))
    }

    /** IraThinker's tsIST is SystemFeed.nowIst(): "yyyy-MM-dd'T'HH:mm:ssXXX" in IST, stamped when it sends. The stale rule reads it. */
    @Test fun theSendTimeIsWhatTheStaleRuleReads() {
        val b = assertIs<Parsed.Buy>(read(mom60()))
        assertEquals(LocalDateTime.of(2026, 10, 26, 10, 16, 2), b.sentAt)
        // The arm counts the earlier of tsIST and ntfy's own time; 8 minutes on it is stale with the 5-minute rule.
        assertTrue(NtfyRules.stale(b.sentAt!!, b.sentAt!!.plusMinutes(8), 5)!!.startsWith("stale, not traded: 8 minutes old"))
        assertNull(NtfyRules.stale(b.sentAt!!, b.sentAt!!.plusMinutes(4), 5))
        // A tsIST that is not a time: none (ntfy's own time then counts).
        assertNull(assertIs<Parsed.Buy>(read(mom60().replace("2026-10-26T10:16:02+05:30", "10:16"))).sentAt)
    }
}
