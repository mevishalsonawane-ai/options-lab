package com.optionslab.app.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.data.OrbArms
import com.optionslab.app.ui.theme.IraAlgoTheme
import com.optionslab.engine.orb.FamilyPark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 13 Oct (Boss's choice): ORB, ORB Fresh, ORB Sweep and Range Fade parked. Each row says "parked on your choice (13 Oct)"
 * with its tested record, and one tap switches it back on through the switch's own flow (automatic / approve, the PIN in
 * Live); a parked arm still holding a position says it is still managed. Liquidity's and the Hero arm's rows are as before.
 */
@RunWith(AndroidJUnit4::class)
@org.robolectric.annotation.Config(qualifiers = "w411dp-h2400dp")
class FamilyParkRowsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val rec = RecordingStrategyActions()

    private fun parked(orbOpen: Boolean = false, except: Set<String> = emptySet()): OrbArms.View = StrategyFakes.orbView(orbOpen = orbOpen).let { v ->
        v.copy(arms = v.arms.map { a -> if (FamilyPark.covers(a.arm.source) && a.arm.source !in except) a.copy(bossParked = true) else a })
    }

    private fun rows(view: OrbArms.View, live: Boolean = false) {
        compose.setContent {
            IraAlgoTheme("light") {
                Column(Modifier.verticalScroll(rememberScrollState())) { OrbRowsContent(view, live, rec, StrategyFakes.reauthWhy) }
            }
        }
        compose.waitForIdle()
    }

    private fun tap(text: String) { compose.onNodeWithText(text).performClick(); compose.waitForIdle() }

    @Test fun eachParkedArmSaysSoWithItsRecord() {
        rows(parked())
        for (a in FamilyPark.ARMS) {
            compose.onNodeWithTag("orb-line:${a.source}", useUnmergedTree = true).assertTextEquals(keepNumbersWhole(FamilyPark.rowLine(a.source)))
            compose.onNodeWithText("Switch ${a.label} back on").assertExists()
        }
        assertTrue(keepNumbersWhole(FamilyPark.rowLine("orb")).contains("parked on your choice (13 Oct)"))
        // Liquidity and the Hero arm are untouched: no park line, no tap.
        compose.onNodeWithText("Switch Liquidity 15+5 back on").assertDoesNotExist()
        compose.onNodeWithText("Switch Hero (expiry) back on").assertDoesNotExist()
        assertTrue("nothing done by just showing it", rec.calls.isEmpty())
    }

    @Test fun oneTapSwitchesOrbBackOnThroughTheUsualChoice() {
        rows(parked())
        tap("Switch ORB back on")
        compose.onNodeWithText("Arm ORB (paper)").assertExists()
        assertTrue("nothing armed before Boss chooses", rec.calls.isEmpty())
        tap("Ask me to approve")
        // ORB Sweep and Range Fade are paper only: armed at once, as by their switch.
        tap("Switch ORB Sweep back on")
        tap("Switch Range Fade back on")
        assertEquals(listOf("orb arm orb on=true auto=false pin=false", "orb arm orb_sweep on=true auto=true pin=false",
            "orb arm range_fade on=true auto=true pin=false"), rec.calls)
    }

    @Test fun inLiveTheTapStillTakesThePin() {
        rows(parked(), live = true)
        tap("Switch ORB Fresh back on")
        compose.onNodeWithText("Arm ORB Fresh (LIVE)").assertExists()
        tap("Automatic")
        assertTrue("nothing armed before the PIN", rec.calls.isEmpty())
        tap("PIN OK")
        assertEquals(listOf("orb arm orb_fresh on=true auto=true pin=true"), rec.calls)
    }

    @Test fun aParkedArmHoldingAPositionSaysItIsStillManaged() {
        rows(parked(orbOpen = true))
        compose.onNodeWithText("IN TRADE · PAPER").assertExists()
        compose.onNodeWithText(keepNumbersWhole("${FamilyPark.ROW}: its open position is still managed to its exit.")).assertExists()
        compose.onNodeWithText("Switch ORB back on").assertExists()
    }

    @Test fun anArmSwitchedBackOnShowsNoParkLine() {
        rows(parked(except = setOf("orb")))
        compose.onNodeWithText("Switch ORB back on").assertDoesNotExist()
        compose.onNodeWithText("Switch ORB Fresh back on").assertExists()
    }
}
