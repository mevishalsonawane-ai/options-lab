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

        /** The account P&L last published (the live watch refreshes it every minute), or null. */
        fun lastPnl(): Double? = pnlNow()

        /**
         * Battery, round 3: figures newer than the vault copy (key -> value, a null P&L meaning none). The live stream
         * publishes every 5 s and each vault write is a Keystore encryption and two disk syncs, so the figures reach the
         * vault at most once a minute and only when they differ from the vault's (a stamp that moved alone stays here);
         * in between they live here and every widget redraw reads them. A process death loses at most that minute; the widget then shows the last saved
         * figures with their own stamp.
         */
        private val unsaved = HashMap<String, Any?>()
        /** The stamp (HH:mm) of the last vault write, or null before the first one in this process. */
        private var savedAt: String? = null

        /** The vault's generation [unsaved] belongs to: a re-init or wipe of the vault drops what was held. */
        private var unsavedGen = Int.MIN_VALUE

        private fun fresh() {
            val g = SecurePrefs.generationNow()
            if (g != unsavedGen) { unsaved.clear(); savedAt = null; unsavedGen = g }
        }

        /** What the vault holds for [k]: the P&L as a Double, the rest as text, null when absent. */
        private fun vaultValue(k: String): Any? = when {
            SecurePrefs.getString(k) == null -> null
            k == K_PNL -> SecurePrefs.getDouble(K_PNL, 0.0)
            else -> SecurePrefs.getString(k)
        }

        private fun textNow(k: String): String? = synchronized(unsaved) {
            fresh()
            if (unsaved.containsKey(k)) unsaved[k] as String? else SecurePrefs.getString(k)
        }

        private fun pnlNow(): Double? = synchronized(unsaved) {
            fresh()
            if (unsaved.containsKey(K_PNL)) unsaved[K_PNL] as Double?
            else if (SecurePrefs.getString(K_PNL) != null) SecurePrefs.getDouble(K_PNL, 0.0) else null
        }

        /** Is at least one widget on a home screen? (Unknown counts as yes: the watch then reads its prices as before.) */
        fun placed(context: Context): Boolean = runCatching {
            AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, IraWidget::class.java)).isNotEmpty()
        }.getOrDefault(true)

        /** Record the latest figures and redraw every placed widget. */
        fun publish(context: Context, nifty: Pair<Double, Double>?, bank: Pair<Double, Double>?, pnl: Double?) {
            val m = HashMap<String, Any?>()
            nifty?.let { m[K_NIFTY] = "%.2f|%.4f".format(Locale.ROOT, it.first, it.second) }
            bank?.let { m[K_BANK] = "%.2f|%.4f".format(Locale.ROOT, it.first, it.second) }
            m[K_PNL] = pnl
            val at = Market.now().let { "%02d:%02d".format(it.hour, it.minute) }
            m[K_AT] = at
            val toVault: Map<String, Any?>? = synchronized(unsaved) {
                fresh()
                val all = HashMap<String, Any?>(unsaved).apply { putAll(m) }
                if (at != savedAt && all.any { (k, v) -> k != K_AT && v != vaultValue(k) }) {
                    // A new minute with figures the vault does not have: everything held so far goes in one write.
                    savedAt = at
                    unsaved.clear()
                    all
                } else { unsaved.putAll(m); null }   // held in memory (the same minute, or only the stamp moved); the redraw shows it
            }
            if (toVault != null) SecurePrefs.putAll(toVault)
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
            v.setTextViewText(R.id.w_nifty, line("NIFTY", textNow(K_NIFTY)))
            v.setTextViewText(R.id.w_bank, line("BANKNIFTY", textNow(K_BANK)))
            val showPnl = AppSettings.load().widgetPnl
            val pnl = pnlNow()
            if (showPnl && pnl != null) {
                v.setViewVisibility(R.id.w_pnl, View.VISIBLE)
                v.setTextViewText(R.id.w_pnl, String.format(Locale.ENGLISH, "P&L  Rs %+,.0f", pnl))
                v.setTextColor(R.id.w_pnl, context.getColor(if (pnl >= 0) R.color.widget_gain else R.color.widget_loss))
            } else v.setViewVisibility(R.id.w_pnl, View.GONE)
            val at = textNow(K_AT)
            v.setTextViewText(R.id.w_status, (if (Market.isOpen()) "Market open" else "Market shut") + (at?.let { " · $it IST" } ?: ""))
            val open = PendingIntent.getActivity(context, 9, Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, "almanac").putExtra(MainActivity.EXTRA_NONCE, MainActivity.nonce()),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            v.setOnClickPendingIntent(R.id.w_root, open)
            manager.updateAppWidget(ids, v)
        }
    }
}
