package com.optionslab.app.widget

import android.appwidget.AppWidgetManager
import android.view.View
import android.widget.TextView
import com.optionslab.app.R
import com.optionslab.app.data.AppSettings
import com.optionslab.app.testing.Background
import com.optionslab.app.testing.Background.WED
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.ira.WidgetLiquidity
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
        IraWidget.inlineForTest = true
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

    @Test fun zerodhasExactChargesAreSaidWithoutTheEstimateWords() {
        val charges = { view().findViewById<TextView>(R.id.w_charges) }
        AppSettings.save(AppSettings.load().copy(widgetPnl = true))
        IraWidget.publish(context, 24_800.0 to 0.01, null, 2_575.0)
        IraWidget.charges(context, 180.0)
        assertEquals("Charges ≈ ₹180 (estimate)", charges().text.toString())
        IraWidget.charges(context, 163.4, exact = true)
        assertEquals("P&L  Rs +2,575", pnl().text.toString())
        assertEquals("Charges ₹163", charges().text.toString())
        IraWidget.charges(context, 200.0)
        assertEquals("an order filled since: the estimate again", "Charges ≈ ₹200 (estimate)", charges().text.toString())
        IraWidget.charges(context, 163.4, exact = true)
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

    // ---- Liquidity 15+5's row ------------------------------------------------------------------------------------

    private fun liq() = view().findViewById<TextView>(R.id.w_liq)
    private fun liqBox() = view().findViewById<View>(R.id.w_liq_box)
    private fun liqDetail() = view().findViewById<TextView>(R.id.w_liq2)
    private fun now() = com.optionslab.app.data.Market.now().toLocalDateTime()
    private val call get() = WidgetLiquidity.Open("BANKNIFTY", "CE", "BANKNIFTY25OCT54000CE", live = false, qty = 70, entry = 120.0, ltp = 140.0,
        level = 54_000.0, target = 54_200.0, index = 54_120.0, indexAt = now())

    @Test fun theLiquidityRowSaysTheArmAndHidesRupeesUnlessTheOwnerShowsThem() {
        org.junit.Assume.assumeFalse(com.optionslab.app.BuildConfig.GOLD)
        IraWidget.liquidityFake = WidgetLiquidity.Facts(now(), WidgetLiquidity.State.ARMED, lots = 2, paperTrades = 3, paperNet = 1_240.0)
        IraWidget.publish(context, 24_800.0 to 0.01, null, null)
        assertEquals(View.VISIBLE, liqBox().visibility)
        assertEquals("the net is a rupee figure: hidden with the P&L", "Liquidity: armed 2 lots · 3 paper trades", liq().text.toString())
        assertEquals(context.getColor(R.color.widget_ink), liq().currentTextColor)

        AppSettings.save(AppSettings.load().copy(widgetPnl = true))
        IraWidget.publish(context, 24_800.0 to 0.01, null, null)
        assertEquals("Liquidity: armed 2 lots · 3 paper trades +₹1,240 net", liq().text.toString())
        assertEquals(context.getColor(R.color.widget_gain), liq().currentTextColor)
        assertTrue("the index lines are unchanged", text(R.id.w_nifty).contains("24,800.00"))

        IraWidget.liquidityFake = WidgetLiquidity.Facts(now(), WidgetLiquidity.State.STOPPED, lots = 2)
        IraWidget.publish(context, 24_800.0 to 0.01, null, null)
        assertEquals("Liquidity: stopped for today", liq().text.toString())
        IraWidget.liquidityFake = WidgetLiquidity.Facts(now(), WidgetLiquidity.State.OFF, lots = 2)
        IraWidget.publish(context, 24_800.0 to 0.01, null, null)
        assertEquals("Liquidity: off", liq().text.toString())
    }

    @Test fun anOpenLiquidityPositionAndItsSecondLineWhereItFits() {
        org.junit.Assume.assumeFalse(com.optionslab.app.BuildConfig.GOLD)
        AppSettings.save(AppSettings.load().copy(widgetPnl = true))
        IraWidget.liquidityFake = WidgetLiquidity.Facts(now(), WidgetLiquidity.State.ARMED, lots = 1, paperTrades = 1, open = call)
        IraWidget.publish(context, 24_800.0 to 0.01, null, null)
        assertEquals("Liquidity: CE +₹1,400 · stop 150 · target 80 pts", liq().text.toString())
        assertEquals("the launcher did not say its height: one line", View.GONE, liqDetail().visibility)

        AppWidgetManager.getInstance(context).updateAppWidgetOptions(id, android.os.Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, WidgetLiquidity.DETAIL_MIN_DP + 20)
        })
        IraWidget.publish(context, 24_800.0 to 0.01, null, null)
        assertEquals(View.VISIBLE, liqDetail().visibility)
        assertEquals("BANKNIFTY25OCT54000CE 140.00 vs 120.00 (+16.7%)", liqDetail().text.toString())

        AppSettings.save(AppSettings.load().copy(widgetPnl = false))
        IraWidget.publish(context, 24_800.0 to 0.01, null, null)
        assertEquals("Liquidity: CE open · stop 150 · target 80 pts", liq().text.toString())
        assertEquals("the percent change goes with the rupees", "BANKNIFTY25OCT54000CE 140.00 vs 120.00", liqDetail().text.toString())
        assertTrue(listOf(R.id.w_liq, R.id.w_liq2, R.id.w_nifty, R.id.w_bank, R.id.w_status).none { text(it).contains("₹") })
    }

    @Test fun theArmsOwnBookIsReadWhenNothingIsFaked() {
        org.junit.Assume.assumeFalse(com.optionslab.app.BuildConfig.GOLD)
        IraWidget.publish(context, 24_800.0 to 0.01, null, null)
        assertEquals(View.VISIBLE, liqBox().visibility)
        assertTrue(liq().text.toString(), liq().text.startsWith("Liquidity: "))
    }

    @Test fun noLiquidityRowInTheGoldBuild() {
        org.junit.Assume.assumeTrue(com.optionslab.app.BuildConfig.GOLD)
        IraWidget.liquidityFake = WidgetLiquidity.Facts(now(), WidgetLiquidity.State.ARMED, lots = 2)
        IraWidget.publish(context, 24_800.0 to 0.01, null, null)
        assertEquals(View.GONE, liqBox().visibility)
    }
}
