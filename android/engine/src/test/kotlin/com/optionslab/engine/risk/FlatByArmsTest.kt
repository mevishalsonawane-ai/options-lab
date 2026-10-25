package com.optionslab.engine.risk

import com.optionslab.engine.NseHours
import com.optionslab.engine.mcx.McxArmRules
import com.optionslab.engine.mcx.McxEveRules
import com.optionslab.engine.mcx.McxMorningRules
import com.optionslab.engine.mcx.McxSession
import com.optionslab.engine.mcx.McxUsSilverRules
import com.optionslab.engine.orb.DonchianArm
import com.optionslab.engine.orb.Ft3Rules
import com.optionslab.engine.orb.HeroRules
import com.optionslab.engine.orb.HeroTicketRules
import com.optionslab.engine.orb.LiveMirror
import com.optionslab.engine.orb.NightRules
import com.optionslab.engine.orb.OovArm
import com.optionslab.engine.orb.OrbRules
import com.optionslab.engine.orb.Solo2
import com.optionslab.engine.orb.SoloMidday
import com.optionslab.engine.orb.VixDivRules
import com.optionslab.engine.signals.NtfyRules
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every arm (22 Oct; hidden ones too) against the hard flat-by: each intraday arm's own time exit comes BEFORE its
 * segment's flat-by, so the flat-by is the backstop, never the first exit; the arms held by design (Night (R3) overnight,
 * the MCX arms' NRML futures) are NRML, which the flat-by leaves alone.
 */
class FlatByArmsTest {
    private val day = LocalDate.of(2026, 10, 22)
    private fun m(t: LocalTime) = t.hour * 60 + t.minute
    private val index = FlatBy.minute(FlatBy.Segment.NSE_INDEX, day)
    private val stock = FlatBy.minute(FlatBy.Segment.NSE_STOCK, day)

    @Test fun everyNseIntradayArmExitsBeforeTheIndexFlatBy() {
        // The arm's own time exit (minute of the day), by arm; each is MIS on paper and at the live mirror.
        val own = linkedMapOf(
            "ORB, ORB Fresh, ORB Sweep, Range Fade, Liquidity 15+5 (OrbRules.SQUARE_OFF)" to m(OrbRules.SQUARE_OFF),
            "Hero (expiry)" to m(HeroRules.EXIT_AT),
            "Hero ₹1,000 ticket" to m(HeroTicketRules.EXIT_AT),
            "FT3 near-ATM morning" to m(Ft3Rules.EXIT_AT),
            "VIX divergence (BANKNIFTY eod)" to VixDivRules.EOD,
            "R51 Donchian 20" to m(DonchianArm.SQUARE_OFF),
            "R52 OOV" to m(OovArm.SQUARE_OFF),
            "Solo 2.0 S3 / S4" to m(Solo2.exits(Solo2.Setup.S3).squareOff),
            "Solo 2.0 S1 and Jarvis Solo (midday)" to m(SoloMidday.EXIT),
            "ntfy alerts on paper" to m(NtfyRules.SQ),
            "the live mirror's own MIS square-off" to m(LiveMirror.SQUARE_OFF),
            "the expiry-day square-off" to NseHours.EXPIRY_SQUARE_OFF,
        )
        for ((arm, minute) in own) assertTrue(minute < index, "$arm exits at ${FlatBy.text(minute)}, before the ${FlatBy.text(index)} flat-by")
        // Pine auto-trade and a scheduled basket defined INTRADAY sell at 15:15 and their own exit time: before 15:20 / 15:25 too.
        assertTrue(15 * 60 + 15 < stock && stock < index)
        assertEquals(15 * 60 + 25, index); assertEquals(15 * 60 + 20, stock)
    }

    @Test fun theArmsHeldByDesignAreNrmlAndLeftAlone() {
        // Night (R3): bought at 15:20, sold the next session at 09:16 - never an intraday position.
        assertEquals(LocalTime.of(9, 16), NightRules.EXIT_AT)
        assertTrue(m(NightRules.EXIT_AT) < NseHours.OPEN + 5)
        assertTrue(!FlatBy.intraday("NRML"))
        // The MCX arms' futures are NRML (McxPaperArms places NRML): the flat-by never touches them; their own flats stand.
        val window = McxSession.Window(McxSession.OPEN, McxSession.close(day))
        val mcxFlat = FlatBy.minute(FlatBy.Segment.MCX, day)
        assertTrue(McxEveRules.FLAT_BY < mcxFlat, "the NG evening call is flat by 23:15, before MCX's ${FlatBy.text(mcxFlat)}")
        assertTrue(McxArmRules.squareOffMinute(McxEveRules.SOURCE, 20 * 60, null, window, day) <= mcxFlat)
        assertTrue(McxMorningRules.exitMinute(10 * 60 + 21, window) < mcxFlat)
        assertEquals(mcxFlat, McxUsSilverRules.exitMinute(day, window), "US-night silver is out at the same minute MCX squares MIS")
        // MCX's flat-by is MCX's own MIS cut: ten minutes before the close, with the close moving with the US clocks.
        assertEquals(m(McxSession.misCut(day)), mcxFlat)
        assertEquals(23 * 60 + 45, FlatBy.minute(FlatBy.Segment.MCX, LocalDate.of(2026, 11, 3)))
    }
}
