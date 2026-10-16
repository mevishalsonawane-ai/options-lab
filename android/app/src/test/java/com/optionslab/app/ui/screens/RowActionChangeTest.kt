package com.optionslab.app.ui.screens

import android.app.Application
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.data.Broker
import com.optionslab.app.testing.FakeUpstox
import com.optionslab.app.testing.ModelHolder
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.TradeFixtures
import com.optionslab.app.ui.AppModel
import com.optionslab.app.ui.theme.IraAlgoTheme
import com.optionslab.engine.sandbox.PositionRow
import com.optionslab.ira.ArmExits
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.time.LocalTime

/**
 * The position popup's change (16 Oct 2026, Boss on paper): NIFTY19OCT2623050CE, re-entered by R51 Donchian 20 after an
 * earlier trade on it closed +2,399, showed "Change +48.57%" (the day's P&L on the symbol over the open cost) and read as
 * "+50% profit, still not selling". The popup now shows the open trade's own move, "Open trade change +18.37%", and the
 * arm's exits that keep it open ("Exits: stop 103.87 · target 158.86 · time 10:45"). A short is mirrored; a closed position
 * and a zero average show no change; the Zerodha popup does the same.
 */
@RunWith(AndroidJUnit4::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(qualifiers = "w411dp-h3200dp")
class RowActionChangeTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule(order = 100) val dump = com.optionslab.app.testing.DumpOnFailure(compose)

    private lateinit var upstox: FakeUpstox
    private lateinit var holder: ModelHolder
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val sym = "NIFTY19OCT2623050CE"

    @Before fun up() {
        ArmExits.clear()
        TradeFixtures.paperSettings()
        upstox = FakeUpstox()
        TradeFixtures.seed(app, upstox)
        holder = ModelHolder(app)
    }

    @After fun down() {
        ArmExits.clear()
        holder.clear()
        upstox.close()
        assertTrue("nothing may reach the internet: ${NetworkGuard.blocked}", FakeUpstox.HOST !in NetworkGuard.blocked)
    }

    private fun show(t: RowTarget): AppModel {
        val m = holder.model
        compose.setContent { IraAlgoTheme("light") { RowActionPopup(m) } }
        m.rowAction.value = t
        compose.waitUntil(10_000) { inDialog("Account") }
        return m
    }

    private fun inDialog(text: String) =
        compose.onAllNodes(hasText(text) and hasAnyAncestor(isDialog())).fetchSemanticsNodes().isNotEmpty()

    /** Boss's row as the sandbox gave it: pnl and pnlPercent are the day's on the symbol, the closed trade's +2,399 included. */
    private fun row(qty: Int = 65, avg: Double = 122.20, ltp: Double = 144.65) =
        PositionRow(sym, "NFO", "NRML", qty, avg, ltp, 3_858.25, 48.57, 1_459.25, 2_399.0, 3_858.25, 1.0)

    private fun donchian(live: Boolean = false) = ArmExits.set("passed", listOf(
        ArmExits.Entry("passed", "R51 Donchian 20", live, sym, 122.20 * 0.85, 122.20 * 1.3, LocalTime.of(10, 45))))

    @Test fun aReEntryAfterAClosedTradeShowsTheOpenTradeAloneAndItsExits() {
        donchian()
        show(RowTarget.PaperPosition(row()))
        compose.waitUntil(10_000) { inDialog("Open trade change") }
        assertTrue(inDialog("+18.37%"))
        assertFalse("never the day's figure over the open cost", inDialog("+48.57%"))
        assertFalse("the old mixed line is gone", inDialog("Change"))
        assertTrue(inDialog("Exits"))
        assertTrue(inDialog("stop 103.87 · target 158.86 · time 10:45"))
        // The money lines are unchanged: the open trade's and the closed one's, apart.
        assertTrue(inDialog("Unrealised P&L") && inDialog("Realised today"))
    }

    @Test fun aShortIsMirrored() {
        show(RowTarget.PaperPosition(row(qty = -75, avg = 80.0, ltp = 70.0)))
        compose.waitUntil(10_000) { inDialog("Open trade change") }
        assertTrue(inDialog("+12.50%"))
        assertTrue(inDialog("SHORT 75"))
        assertFalse("no arm holds it: no exits line", inDialog("Exits"))
    }

    @Test fun aClosedPositionShowsNoChangeAndNoExits() {
        donchian()
        show(RowTarget.PaperPosition(row(qty = 0)))
        compose.waitUntil(10_000) { inDialog("CLOSED 0") }
        assertFalse(inDialog("Open trade change"))
        assertFalse(inDialog("Exits"))
        assertFalse(inDialog("+48.57%"))
    }

    @Test fun aZeroAverageShowsNoChange() {
        show(RowTarget.PaperPosition(row(qty = 50, avg = 0.0)))
        compose.waitUntil(10_000) { inDialog("LONG 50") }
        assertFalse(inDialog("Open trade change"))
        assertFalse(inDialog("Change"))
    }

    @Test fun theZerodhaPopupShowsTheOpenTradeAndOnlyALiveArmsExits() {
        donchian(live = false)
        show(RowTarget.LivePosition(Broker.Position(sym, "NRML", 65, 122.20, 144.65, 3_858.25)))
        compose.waitUntil(10_000) { inDialog("Open trade change") }
        // No trades read in this test: Zerodha's average is kept, and the line says so.
        assertTrue(inDialog("+18.37% (on Zerodha's average)"))
        assertFalse("a paper arm's trade is not the Zerodha position's", inDialog("Exits"))
        ArmExits.set("orb", listOf(ArmExits.Entry("orb", "ORB", true, sym, 92.2, 162.2, LocalTime.of(15, 10))))
        // Re-read on the next composition (the popup re-composes as the live book moves).
        holder.model.rowAction.value = RowTarget.LivePosition(Broker.Position(sym, "NRML", 65, 122.20, 145.0, 3_880.0))
        compose.waitUntil(10_000) { inDialog("stop 92.20 · target 162.20 · time 15:10") }
        assertEquals(1, compose.onAllNodes(hasText("Exits") and hasAnyAncestor(isDialog())).fetchSemanticsNodes().size)
    }

    @Test fun theZerodhaLinesUseTheOpenTradesOwnAverageFromTheDaysFills() {
        // Zerodha's average 103.75 (the day's buys together); the fills say the open trade was bought at 122.20.
        val p = Broker.Position(sym, "MIS", 65, 103.75, 144.65, 3_858.25, buyQty = 130, sellQty = 65)
        val trades = listOf(
            Broker.Trade("3", "O3", sym, "NFO", "BUY", 65, 122.20, "MIS", "2026-10-16 10:15:02"),
            Broker.Trade("2", "O2", sym, "NFO", "SELL", 65, 122.20, "MIS", "2026-10-16 10:05:40"),
            Broker.Trade("1", "O1", sym, "NFO", "BUY", 65, 85.30, "MIS", "2026-10-16 09:31:10"))
        assertEquals(listOf("Open trade average" to "122.20", "Open trade change" to "+18.37%"), liveOpenTradeLines(p, trades))
        // Not read: Zerodha's, said.
        assertEquals(listOf("Open trade change" to "+39.42% (on Zerodha's average)"), liveOpenTradeLines(p, null))
        // The same average: no extra line.
        assertEquals(listOf("Open trade change" to "+18.37%"), liveOpenTradeLines(p.copy(avg = 122.20), trades))
        // Closed: nothing.
        assertEquals(emptyList<Pair<String, String>>(), liveOpenTradeLines(p.copy(qty = 0), trades))
    }

    @Test fun theLinesAlone() {
        assertEquals(emptyList<Pair<String, String>>(), openTradeLines(false, sym, 0, 122.20, 144.65))
        donchian()
        assertEquals(listOf("Open trade change" to "+18.37%", "Exits" to "stop 103.87 · target 158.86 · time 10:45"),
            openTradeLines(false, sym, 65, 122.20, 144.65))
        assertEquals(listOf("Exits" to "stop 103.87 · target 158.86 · time 10:45"), openTradeLines(false, sym, 65, 0.0, 144.65))
        assertEquals(listOf("Open trade change" to "+18.37%"), openTradeLines(true, sym, 65, 122.20, 144.65))
    }
}
