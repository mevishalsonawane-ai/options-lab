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
import com.optionslab.app.ira.IraHub
import com.optionslab.app.ira.JarvisActionReceiver
import com.optionslab.app.ira.JarvisApproval

/**
 * Jarvis on the home screen (Jarvis; the owner's wish, 2026-10-02): its last message, and when a trade it
 * suggested waits for Boss's answer, Approve and Reject right there (they reach only the app's own receiver; a home
 * screen is shown only on an unlocked phone). Tapping it opens the app, which still asks for the PIN or fingerprint.
 */
class JarvisWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = render(context, manager, ids)

    companion object {
        /** Redraws every placed Jarvis widget (when Jarvis speaks or a trade starts or stops waiting). */
        fun refresh(context: Context) {
            if (!com.optionslab.app.BuildConfig.JARVIS) return
            val mgr = AppWidgetManager.getInstance(context)
            val ids = runCatching { mgr.getAppWidgetIds(ComponentName(context, JarvisWidget::class.java)) }.getOrNull() ?: return
            if (ids.isNotEmpty()) render(context, mgr, ids)
        }

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val v = RemoteViews(context.packageName, R.layout.widget_jarvis)
            val st = IraHub.state.value
            val last = st.messages.lastOrNull { it.fromIra }?.text
            v.setTextViewText(R.id.j_msg, last?.let { com.optionslab.ira.Address.boss(it).take(260) } ?: "Hello Boss. Say \"Jarvis\" or open the app to ask me anything.")
            val waiting = IraHub.waitingTrade()
            if (waiting != null) {
                v.setViewVisibility(R.id.j_actions, View.VISIBLE)
                fun button(action: String) = PendingIntent.getBroadcast(context, (waiting xor action.hashCode().toLong() xor 0x5A5AL).toInt(),
                    Intent(context, JarvisActionReceiver::class.java).setAction(action).putExtra(JarvisApproval.EXTRA_ID, waiting),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                v.setOnClickPendingIntent(R.id.j_approve, button(JarvisApproval.ACTION_APPROVE))
                v.setOnClickPendingIntent(R.id.j_reject, button(JarvisApproval.ACTION_REJECT))
                v.setTextViewText(R.id.j_status, "A trade is waiting for your answer")
            } else {
                v.setViewVisibility(R.id.j_actions, View.GONE)
                v.setTextViewText(R.id.j_status, if (com.optionslab.app.data.Market.isOpen()) "Market open" else "Market shut")
            }
            val open = PendingIntent.getActivity(context, 19, Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, "almanac")
                .putExtra(MainActivity.EXTRA_NONCE, MainActivity.nonce()), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            v.setOnClickPendingIntent(R.id.j_root, open)
            runCatching { manager.updateAppWidget(ids, v) }
        }
    }
}
