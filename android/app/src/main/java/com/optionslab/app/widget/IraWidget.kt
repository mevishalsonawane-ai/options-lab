package com.optionslab.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.optionslab.app.MainActivity
import com.optionslab.app.R
import com.optionslab.app.data.AppSettings
import com.optionslab.app.data.Market
import com.optionslab.app.security.SecurePrefs
import java.util.Locale

/**
 * The home-screen widget: NIFTY and BANKNIFTY and whether the market is open.
 * The account P&L appears only if the owner turned it on (More → Security),
 * because a home screen is seen by anyone holding the unlocked phone. Tapping
 * it opens the app, which still asks for the PIN or fingerprint.
 */
class IraWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = render(context, manager, ids)

    companion object {
        private const val K_NIFTY = "w.nifty"
        private const val K_BANK = "w.bank"
        private const val K_PNL = "w.pnl"
        private const val K_AT = "w.at"

        /** Record the latest figures and redraw every placed widget. */
        fun publish(context: Context, nifty: Pair<Double, Double>?, bank: Pair<Double, Double>?, pnl: Double?) {
            val m = HashMap<String, Any?>()
            nifty?.let { m[K_NIFTY] = "%.2f|%.4f".format(Locale.ROOT, it.first, it.second) }
            bank?.let { m[K_BANK] = "%.2f|%.4f".format(Locale.ROOT, it.first, it.second) }
            m[K_PNL] = pnl
            m[K_AT] = Market.now().let { "%02d:%02d".format(it.hour, it.minute) }
            SecurePrefs.putAll(m)
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, IraWidget::class.java))
            if (ids.isNotEmpty()) render(context, mgr, ids)
        }

        private fun line(name: String, raw: String?): String {
            val (last, chg) = raw?.split("|")?.let { it[0].toDouble() to it[1].toDouble() } ?: return "$name  —"
            return String.format(Locale.ENGLISH, "%-9s %,10.2f  %+.2f%%", name, last, 100 * chg)
        }

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val v = RemoteViews(context.packageName, R.layout.widget_iraalgo)
            v.setTextViewText(R.id.w_nifty, line("NIFTY", SecurePrefs.getString(K_NIFTY)))
            v.setTextViewText(R.id.w_bank, line("BANKNIFTY", SecurePrefs.getString(K_BANK)))
            val showPnl = AppSettings.load().widgetPnl
            val pnl = if (SecurePrefs.getString(K_PNL) != null) SecurePrefs.getDouble(K_PNL, 0.0) else null
            if (showPnl && pnl != null) {
                v.setViewVisibility(R.id.w_pnl, View.VISIBLE)
                v.setTextViewText(R.id.w_pnl, String.format(Locale.ENGLISH, "P&L  Rs %+,.0f", pnl))
                v.setTextColor(R.id.w_pnl, context.getColor(if (pnl >= 0) R.color.widget_gain else R.color.widget_loss))
            } else v.setViewVisibility(R.id.w_pnl, View.GONE)
            val at = SecurePrefs.getString(K_AT)
            v.setTextViewText(R.id.w_status, (if (Market.isOpen()) "Market open" else "Market shut") + (at?.let { " · $it IST" } ?: ""))
            val open = PendingIntent.getActivity(context, 9, Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, "almanac"),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            v.setOnClickPendingIntent(R.id.w_root, open)
            manager.updateAppWidget(ids, v)
        }
    }
}
