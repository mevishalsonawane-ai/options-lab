package com.optionslab.app.widget

import android.appwidget.AppWidgetManager
import android.view.View
import android.widget.TextView
import com.optionslab.app.R
import com.optionslab.app.data.AppSettings
import com.optionslab.app.data.Broker
import com.optionslab.app.data.DailyPnl
import com.optionslab.app.data.Market
import com.optionslab.app.data.Paper
import com.optionslab.app.data.PnlTracker
import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.testing.Background
import com.optionslab.app.testing.Background.WED
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.Right
import com.optionslab.engine.sandbox.Quote
import com.optionslab.ira.OpenBook
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The "Open" widget only displays: today's P&L and only what is open, and nothing of the account at all (not even in
 * the vault) unless the owner turned on "Show my P&L on the widget". A failed Zerodha read says so.
 */
class OpenWidgetTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.BackgroundWatchdog()
    private var id = 0
    private val widgets get() = shadowOf(AppWidgetManager.getInstance(context))
    private fun view(): View = widgets.getViewFor(id)
    private fun tv(res: Int): TextView = view().findViewById(res)
    private fun text(res: Int) = tv(res).text.toString()
    private fun shown(res: Int) = view().findViewById<View>(res).visibility == View.VISIBLE

    private val open = Broker.Position("NIFTY26OCT25000CE", "MIS", -75, 100.0, 90.0, 750.0, m2m = 750.0)
    private val closed = Broker.Position("NIFTY26OCT24900PE", "MIS", 0, 0.0, 40.0, -200.0, m2m = -200.0)
    private fun order(sym: String, status: String) = Broker.OrderRow("o-$sym-$status", sym, "BUY", 75, 0, 80.0, 0.0, status, "", "2026-10-07 10:00:00",
        type = "LIMIT", pending = 75)

    @Before fun up() {
        Background.calendar(WED)
        Background.at(WED, 11, 0)
        // The switch's vault work runs on the widget's own thread in the app; here on the test's, so it is done on return.
        OpenWidget.inlineForTest = true
        id = widgets.createWidget(OpenWidget::class.java, R.layout.widget_open)
    }

    @After fun down() = Background.reset()

    private fun allow() {
        AppSettings.save(AppSettings.load().copy(widgetPnl = true))
        OpenWidget.refresh(context, true)
    }

    @Test fun offByDefaultItShowsOnlyTheNoteAndKeepsNothing() {
        OpenWidget.fromWatch(context, true, true, Broker.Positions(listOf(open), emptyList()), null)
        assertEquals(OpenWidget.OFF, text(R.id.ow_note))
        listOf(R.id.ow_pnl, R.id.ow_caption, R.id.ow_split, R.id.ow_row0, R.id.ow_more).forEach { assertEquals(false, shown(it)) }
        assertNull("nothing reaches the vault", SecurePrefs.getString("ow.zerodha"))
    }

    @Test fun thePnlOnTopThenOnlyWhatIsOpen() {
        allow()
        OpenWidget.fromWatch(context, true, true, Broker.Positions(listOf(open, closed), emptyList()), null)
        assertEquals("+₹550", text(R.id.ow_pnl))
        assertEquals(context.getColor(R.color.widget_gain), tv(R.id.ow_pnl).currentTextColor)
        assertEquals("Today's P&L · Zerodha", text(R.id.ow_caption))
        assertEquals("as of 11:00", text(R.id.ow_stamp))
        assertEquals("NIFTY26OCT25000CE", text(R.id.ow_t0))
        assertEquals("-75 · LTP 90.00", text(R.id.ow_d0))
        assertEquals("+₹750", text(R.id.ow_f0))
        assertEquals("the closed position is not shown", false, shown(R.id.ow_row1))

        OpenWidget.fromOrders(context, listOf(order("NIFTY26OCT25100CE", "OPEN"), order("NIFTY26OCT25200CE", "COMPLETE")))
        assertEquals("NIFTY26OCT25100CE", text(R.id.ow_t1))
        assertEquals("BUY 75 · LIMIT · OPEN", text(R.id.ow_d1))
        assertEquals("the completed order is not shown", false, shown(R.id.ow_row2))
        assertEquals(false, shown(R.id.ow_note))
    }

    @Test fun aFailedReadIsNotShownAsEmpty() {
        allow()
        OpenWidget.fromWatch(context, true, true, null, null)
        assertEquals("—", text(R.id.ow_pnl))
        assertEquals("Zerodha: could not read", text(R.id.ow_split))
        assertEquals(false, shown(R.id.ow_note))
        assertEquals(false, shown(R.id.ow_row0))
    }

    @Test fun nothingOpen() {
        allow()
        OpenWidget.fromWatch(context, true, true, Broker.Positions(listOf(closed), emptyList()), null)
        assertEquals("No open positions or orders", text(R.id.ow_note))
        assertEquals("−₹200", text(R.id.ow_pnl))
        assertEquals(context.getColor(R.color.widget_loss), tv(R.id.ow_pnl).currentTextColor)
    }

    @Test fun turningTheSwitchOffClearsTheWidgetAndTheVault() {
        allow()
        OpenWidget.fromWatch(context, true, true, Broker.Positions(listOf(open), emptyList()), null)
        AppSettings.save(AppSettings.load().copy(widgetPnl = false))
        OpenWidget.refresh(context, false)
        assertEquals(OpenWidget.OFF, text(R.id.ow_note))
        assertEquals(false, shown(R.id.ow_row0))
        assertNull(SecurePrefs.getString("ow.zerodha"))
        assertNull(SecurePrefs.getString("ow.at"))
    }

    @Test fun theSwitchTurnedOffWinsBeforeTheSettingsAreSaved() {
        allow()
        OpenWidget.fromWatch(context, true, true, Broker.Positions(listOf(open), emptyList()), null)
        // Off, while the settings file still says on (the app saves it a moment later): a pass in flight keeps nothing.
        OpenWidget.refresh(context, false)
        OpenWidget.fromWatch(context, true, true, Broker.Positions(listOf(open), emptyList()), null)
        OpenWidget.fromOrders(context, listOf(order("NIFTY26OCT25100CE", "OPEN")))
        assertEquals(OpenWidget.OFF, text(R.id.ow_note))
        assertEquals(false, shown(R.id.ow_pnl))
        assertNull(SecurePrefs.getString("ow.zerodha"))
        assertNull(SecurePrefs.getString("ow.zorders"))
    }

    @Test fun theWatchReadsOrdersOnlyWhileWorkingOnesAreShown() {
        assertEquals("switch off: no read", false, OpenWidget.wantsOrders(context, true))
        allow()
        OpenWidget.fromWatch(context, true, true, Broker.Positions(listOf(open), emptyList()), null)
        assertEquals("no working order shown", false, OpenWidget.wantsOrders(context, true))
        OpenWidget.fromOrders(context, listOf(order("NIFTY26OCT25100CE", "OPEN")))
        assertEquals(true, OpenWidget.wantsOrders(context, true))
        assertEquals("paper mode", false, OpenWidget.wantsOrders(context, false))
        OpenWidget.fromOrders(context, listOf(order("NIFTY26OCT25100CE", "COMPLETE")))
        assertEquals(false, OpenWidget.wantsOrders(context, true))
        assertEquals(false, shown(R.id.ow_row1))
    }

    // ---- filled from the phone at once (no watch pass, no network) ------------------------------------------------

    /** The switch already on before this widget was placed (as the owner had it): read from the settings, never moved here. */
    private fun alreadyOn() {
        AppSettings.save(AppSettings.load().copy(widgetPnl = true))
        // Back to a fresh process's view (the switch not read yet), the widget's work still on the test's thread.
        OpenWidget.resetForTest()
        OpenWidget.inlineForTest = true
    }

    /** The launcher's update, as when the widget is placed. */
    private fun updated() = OpenWidget().onUpdate(context, AppWidgetManager.getInstance(context), intArrayOf(id))

    private fun contract(strike: Int, right: Right): Paper.Contract {
        val exp = WED.plusDays(6)
        val sym = "NIFTY" + exp.format(DateTimeFormatter.ofPattern("ddMMMyy", Locale.ENGLISH)).uppercase(Locale.ENGLISH) + strike + right.name
        return Paper.Contract(sym, "NIFTY", exp, strike.toDouble(), right, 75, "NSE_FO|$sym")
    }

    @Test fun nothingKnownSaysToOpenTheAppOnceNotWaiting() {
        allow()
        assertEquals("Open IraAlgo once to fill this", text(R.id.ow_note))
        assertEquals("—", text(R.id.ow_pnl))
    }

    @Test fun placedInTheEveningItShowsThePaperPnlAtOnceWithNoWatch() {
        // The day's paper trading at 11:00: one round trip closed at a profit, one position still open.
        val booked = contract(24_500, Right.CE)
        val held = contract(24_600, Right.PE)
        runBlocking {
            assertTrue(Paper.place(booked, "BUY", 1, "MARKET", "MIS", null, null, Quote(100.0)).ok)
            assertTrue(Paper.place(booked, "SELL", 1, "MARKET", "MIS", null, null, Quote(120.0)).ok)
            assertTrue(Paper.place(held, "BUY", 1, "MARKET", "NRML", null, null, Quote(50.0)).ok)
        }
        File(context.filesDir, "paper.vault").setLastModified(Market.now().toInstant().toEpochMilli())
        // Evening: no watch pass, no live stream, the app not opened since; Paper mode, the switch on.
        Background.at(WED, 19, 0)
        alreadyOn()
        assertNull("nothing was kept for it", SecurePrefs.getString("ow.paper"))

        updated()

        val pnl = Paper.localSnapshot()!!.first.dayPnl
        assertTrue("a day with a profit booked", pnl > 0.0)
        assertEquals("Today's P&L · Paper", text(R.id.ow_caption))
        assertEquals(OpenBook.rs(pnl), text(R.id.ow_pnl))
        assertEquals(context.getColor(R.color.widget_gain), tv(R.id.ow_pnl).currentTextColor)
        assertEquals("as of when the open position was last marked", "as of 11:00", text(R.id.ow_stamp))
        assertEquals(held.symbol, text(R.id.ow_t0))
        assertEquals(false, shown(R.id.ow_row1))
        assertEquals(false, shown(R.id.ow_note))
    }

    @Test fun theSwitchTurnedOnFillsAtOnceFromThePhone() {
        val c = contract(24_500, Right.CE)
        runBlocking {
            assertTrue(Paper.place(c, "BUY", 1, "MARKET", "MIS", null, null, Quote(100.0)).ok)
            assertTrue(Paper.place(c, "SELL", 1, "MARKET", "MIS", null, null, Quote(90.0)).ok)
        }
        Background.at(WED, 18, 30)
        allow()
        val pnl = Paper.localSnapshot()!!.first.dayPnl
        assertEquals(OpenBook.rs(pnl), text(R.id.ow_pnl))
        assertEquals(context.getColor(R.color.widget_loss), tv(R.id.ow_pnl).currentTextColor)
        assertEquals("nothing open: all booked, current now", "as of 18:30", text(R.id.ow_stamp))
        assertEquals(OpenBook.NOTHING_OPEN, text(R.id.ow_note))
    }

    @Test fun switchedOffNothingIsKeptFromThePhone() {
        runBlocking { assertTrue(Paper.place(contract(24_500, Right.CE), "BUY", 1, "MARKET", "MIS", null, null, Quote(100.0)).ok) }
        Background.at(WED, 19, 0)
        updated()
        assertEquals(OpenWidget.OFF, text(R.id.ow_note))
        assertEquals(false, shown(R.id.ow_pnl))
        assertNull(SecurePrefs.getString("ow.paper"))
    }

    @Test fun zerodhaAfterHoursShowsTheDaysFigureTheAppRecorded() {
        Background.linkZerodha()
        AppSettings.save(AppSettings.load().copy(mode = "live"))
        Background.at(WED, 15, 20)
        PnlTracker.record(2_100.0)
        DailyPnl.record(true, 2_150.0, 3)
        Background.at(WED, 19, 0)
        alreadyOn()
        updated()
        assertEquals("Today's P&L · Zerodha", text(R.id.ow_caption))
        assertEquals("+₹2,150", text(R.id.ow_pnl))
        assertEquals("as of 15:20", text(R.id.ow_stamp))
        assertEquals("its positions were not read: never 'nothing open'", OpenBook.SEE_POSITIONS, text(R.id.ow_note))
    }
}
