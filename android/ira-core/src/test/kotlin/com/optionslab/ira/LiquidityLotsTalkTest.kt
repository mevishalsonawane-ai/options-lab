package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Liquidity 15+5's size by voice (Boss, 06 Oct: "run only Liquidity, at 2-3 lots"): "liquidity ko 3 lot karo", "set liquidity
 * to 2 lots" read as a change of [SettingsTalk.Key.LIQUIDITY_LOTS] - always said back and confirmed, and a raise is more risk
 * (Boss's own voice, never part of a plan); "how many lots is liquidity trading" is asked, answered from the arms' book.
 */
class LiquidityLotsTalkTest {
    private fun lots(said: String): Double? = Ask.parse(said).command?.takeIf {
        it.kind == Command.Kind.SET_LIMIT && it.target == SettingsTalk.Key.LIQUIDITY_LOTS.name
    }?.level

    @Test fun aChangeOfLiquiditysLotsIsReadInEnglishAndHinglish() {
        for ((said, n) in listOf(
            "liquidity ko 3 lot karo" to 3, "set liquidity to 2 lots" to 2, "liquidity 2 lot kar do" to 2, "set liquidity lots to 3" to 3,
            "make liquidity trade 3 lots" to 3, "liquidity ko do lot karo" to 2, "reduce liquidity to 1 lot" to 1,
            "Jarvis, set Liquidity 15+5 to 3 lots" to 3, "liquidity lots 2" to 2, "trade 3 lots on liquidity" to 3,
            "liquidity mein teen lot lagao" to 3, "change the liquidity size to 1" to 1,
        )) assertEquals(n.toDouble(), lots(said), said)
    }

    @Test fun anyNumberIsReadAsSaidAndTheAppSaysOneToThree() {
        assertEquals(5.0, lots("set liquidity to 5 lots"))
    }

    @Test fun questionsAndOtherLimitsAreNotAChange() {
        for (said in listOf("how many lots is liquidity trading", "liquidity kitne lot mein trade kar raha hai", "liquidity lots",
                "how is liquidity doing", "what size is liquidity trading")) assertNull(lots(said), said)
        // The account's own max lots stays its own setting.
        assertEquals(SettingsTalk.Key.MAX_LOTS.name, Ask.parse("set max lots to 3").command?.target)
        // A negation is never a change.
        assertNull(lots("don't set liquidity to 3 lots"))
    }

    @Test fun howManyLotsIsAskedOfTheArmNotTheBudgetSum() {
        for (said in listOf("how many lots is liquidity trading", "how many lots does liquidity trade", "liquidity kitne lot mein trade kar raha hai",
                "what size is liquidity trading", "liquidity lots", "liquidity size"))
            assertEquals(Honest.Asked.LiquidityLots, Honest.asked(said), said)
        // Without the arm named it is still the budget sum.
        assertEquals(Honest.Asked.LotsNoBudget, Honest.asked("how many lots can i buy"))
        assertFalse(SettingsTalk.liquidityLotsAsked("set liquidity to 3 lots"))
        assertFalse(SettingsTalk.liquidityLotsAsked("liquidity ko 3 lot karo"))
        assertFalse(SettingsTalk.liquidityLotsAsked("how many lots can i buy"))
    }

    @Test fun theAnswerSaysTheSizeEachIndexsQuantityAndTheLiveCap() {
        val said = Honest.liquidityLots(3, linkedMapOf("BANKNIFTY" to 30, "FINNIFTY" to 65), maxLots = 2)
        assertTrue(said.startsWith("Liquidity 15+5 buys 3 lots on each new entry, Boss (90 on BankNifty, 195 on FinNifty)"), said)
        assertTrue(said.contains("an open position keeps the quantity it was bought with"), said)
        assertTrue(said.contains("On Zerodha the Bot settings allow 2 lots, so a live entry would be refused"), said)
        val two = Honest.liquidityLots(2, emptyMap(), maxLots = 2)
        assertTrue(two.startsWith("Liquidity 15+5 buys 2 lots on each new entry, Boss;"), two)
        assertFalse(two.contains("refused"), two)
        assertTrue(Honest.liquidityLots(1, emptyMap(), maxLots = 0).startsWith("Liquidity 15+5 buys 1 lot on"))
        assertTrue(Honest.liquidityLots(3, emptyMap(), maxLots = 1).contains("allow 1 lot,"))
        assertEquals("Liquidity 15+5's lots are on its row on Home, Boss: Lots 1, 2 or 3.", Honest.say(Honest.Asked.LiquidityLots))
    }

    @Test fun moreLotsIsMoreRiskFewerIsNot() {
        val k = SettingsTalk.Key.LIQUIDITY_LOTS
        assertTrue(SettingsTalk.loosens(k, 2.0, 3.0), "a raise needs Boss's own voice and his yes")
        assertTrue(SettingsTalk.loosens(k, 1.0, 2.0))
        assertFalse(SettingsTalk.loosens(k, 3.0, 2.0), "a cut lowers risk")
        assertEquals("change Liquidity 15+5's lots a trade from 2 to 3", SettingsTalk.describe(k, 2.0, 3.0))
        // Said back and confirmed, as every limit change.
        assertTrue(Command.Kind.SET_LIMIT.reduces)
    }

    @Test fun neverPartOfAPlan() {
        assertFalse(Command.Kind.SET_LIMIT in Plan.ALLOWED)
        // Asked with another step, the plan refuses the whole of it (each step asked on its own).
        val steps = Plan.steps("set liquidity to 3 lots then switch to paper") { Ask.parse(it).command != null }
        assertEquals(listOf("set liquidity to 3 lots", "switch to paper"), steps)
        assertTrue(steps!!.any { Ask.parse(it).command?.kind !in Plan.ALLOWED }, "the app refuses it: ${Plan.ONLY_LOWERING}")
    }
}
