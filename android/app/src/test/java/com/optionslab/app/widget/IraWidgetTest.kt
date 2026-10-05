package com.optionslab.app.widget

import android.appwidget.AppWidgetManager
import android.view.View
import android.widget.TextView
import com.optionslab.app.R
import com.optionslab.app.data.AppSettings
import com.optionslab.app.testing.Background
import com.optionslab.app.testing.Background.WED
import com.optionslab.app.testing.RobolectricTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.Shadows.shadowOf

/**
 * The home-screen widget only displays: index levels and whether the market is open, and the
 * account P&L only when the owner turned it on (a home screen is seen by whoever holds the phone).
 */
class IraWidgetTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.BackgroundWatchdog()
    private var id = 0
    private val widgets get() = shadowOf(AppWidgetManager.getInstance(context))
    private fun view(): View = widgets.getViewFor(id)
    private fun text(res: Int) = view().findViewById<TextView>(res).text.toString()
    private fun pnl() = view().findViewById<TextView>(R.id.w_pnl)

    @Before fun up() {
        Background.calendar(WED)
        Background.at(WED, 11, 0)
        id = widgets.createWidget(IraWidget::class.java, R.layout.widget_iraalgo)
    }

    @After fun down() = Background.reset()

    @Test fun beforeAnyDataItShowsDashes() {
        assertEquals("NIFTY  —", text(R.id.w_nifty))
        assertEquals("BANKNIFTY  —", text(R.id.w_bank))
        assertEquals("Market open", text(R.id.w_status))
        assertEquals(View.GONE, pnl().visibility)
    }

    @Test fun indexLevelsAndTheMarketStatus() {
        IraWidget.publish(context, 24_800.0 to 0.0123, 55_000.5 to -0.004, null)
        assertEquals(String.format(java.util.Locale.ENGLISH, "%-9s %,10.2f  %+.2f%%", "NIFTY", 24_800.0, 1.23), text(R.id.w_nifty))
        assertTrue(text(R.id.w_bank), text(R.id.w_bank).contains("55,000.50  -0.40%"))
        assertEquals("Market open · 11:00 IST", text(R.id.w_status))
        Background.at(WED, 18, 5)
        IraWidget.publish(context, null, null, null)
        assertEquals("the last levels stay", true, text(R.id.w_nifty).contains("24,800.00"))
        assertEquals("Market shut · 18:05 IST", text(R.id.w_status))
    }

    @Test fun theAccountPnlIsHiddenUnlessTheOwnerTurnsItOn() {
        IraWidget.publish(context, 24_800.0 to 0.01, null, 12_345.0)
        assertEquals("off by default", View.GONE, pnl().visibility)
        assertTrue("and not anywhere else on the widget", listOf(R.id.w_nifty, R.id.w_bank, R.id.w_status).none { text(it).contains("12,345") })

        AppSettings.save(AppSettings.load().copy(widgetPnl = true))
        IraWidget.publish(context, 24_800.0 to 0.01, null, 12_345.0)
        assertEquals(View.VISIBLE, pnl().visibility)
        assertEquals("P&L  Rs +12,345", pnl().text.toString())
        assertEquals(context.getColor(R.color.widget_gain), pnl().currentTextColor)

        IraWidget.publish(context, 24_800.0 to 0.01, null, -800.0)
        assertEquals("P&L  Rs -800", pnl().text.toString())
        assertEquals(context.getColor(R.color.widget_loss), pnl().currentTextColor)

        IraWidget.publish(context, 24_800.0 to 0.01, null, null)
        assertEquals("no account figure: nothing shown", View.GONE, pnl().visibility)

        AppSettings.save(AppSettings.load().copy(widgetPnl = false))
        IraWidget.publish(context, 24_800.0 to 0.01, null, 5_000.0)
        assertEquals(View.GONE, pnl().visibility)
    }

    @Test fun zerodhaChargesGoUnderThePnlAsAnEstimate() {
        val charges = { view().findViewById<TextView>(R.id.w_charges) }
        AppSettings.save(AppSettings.load().copy(widgetPnl = true))
        IraWidget.publish(context, 24_800.0 to 0.01, null, 2_575.0)
        assertEquals("no trades read yet: no charges line", View.GONE, charges().visibility)
        IraWidget.charges(context, 180.0)
        assertEquals("the P&L stays Zerodha's, before charges", "P&L  Rs +2,575", pnl().text.toString())
        assertEquals(View.VISIBLE, charges().visibility)
        assertEquals("Charges ≈ ₹180 (estimate)", charges().text.toString())
        AppSettings.save(AppSettings.load().copy(widgetPnl = false))
        IraWidget.publish(context, 24_800.0 to 0.01, null, 2_575.0)
        assertEquals("hidden with the P&L", View.GONE, charges().visibility)
    }

    @Test fun publishingWithNoWidgetPlacedOnlyRecordsTheFigures() {
        val other = shadowOf(AppWidgetManager.getInstance(context))
        IraWidget.publish(context, 24_900.0 to 0.0, 55_100.0 to 0.0, null)
        assertTrue(other.getViewFor(id).findViewById<TextView>(R.id.w_nifty).text.contains("24,900.00"))
        IraWidget().onUpdate(context, AppWidgetManager.getInstance(context), intArrayOf(id))
        assertTrue(text(R.id.w_nifty).contains("24,900.00"))
    }
}
