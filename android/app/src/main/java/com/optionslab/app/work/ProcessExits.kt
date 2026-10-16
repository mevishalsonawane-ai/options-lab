package com.optionslab.app.work

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.optionslab.app.data.Diag
import com.optionslab.app.data.Market
import com.optionslab.app.security.SecurePrefs
import com.optionslab.ira.ExitReason

/**
 * Why the app's process ended last time, from Android's own record (16 Oct; [ExitReason]). Read once per start, off the
 * main thread and after the open positions are restored (the positions-first thread's last step): each ending not told
 * before goes to the diary's watch area, and the newest one is the diagnostics' "Last process end:" line. Android 11+ only.
 */
object ProcessExits {
    private const val SEEN = "exit.seen"

    /** The newest ending, as the diary words it (null: none known, or Android 10 and older). */
    @Volatile var last: String? = null
        private set

    fun read(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching {
            val am = context.getSystemService(ActivityManager::class.java) ?: return
            val all = am.getHistoricalProcessExitReasons(context.packageName, 0, 10).map { it.timestamp to it }
            val zone = Market.now().zone
            fun words(e: android.app.ApplicationExitInfo) = ExitReason.line(e.timestamp, zone, e.reason, e.importance, e.pss, e.rss, e.description)
            all.maxByOrNull { it.first }?.let { last = words(it.second) }
            val since = SecurePrefs.getLong(SEEN, 0L)
            val fresh = ExitReason.fresh(all, since)
            for ((_, e) in fresh) Heartbeat.diary(words(e))
            fresh.lastOrNull()?.let { SecurePrefs.putAllSoon(mapOf(SEEN to it.first)) }
        }
    }

    fun diagLine(): String = "Last process end: " + (last ?: if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) "not known on this Android" else "none recorded")
}
