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

    /** How many of Android's records are read (the app's health keeps the ANRs and crashes among them too). */
    private const val MAX_RECORDS = 16
    /** This process's one read of Android's record (22 Oct; a List of ApplicationExitInfo: typed Any for Android 10 and older). */
    @Volatile private var kept: List<Any>? = null

    /**
     * Android's record of the app's last [MAX_RECORDS] process endings, asked of Android once per process (22 Oct, the overnight
     * kills: this and the app's health each asked at every start). A failed ask is not kept (the next caller asks again).
     */
    @Synchronized
    internal fun records(context: Context): List<android.app.ApplicationExitInfo> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        @Suppress("UNCHECKED_CAST")
        val have = kept as List<android.app.ApplicationExitInfo>?
        if (have != null) return have
        val all = testAsk?.invoke() ?: run {
            val am = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
            am.getHistoricalProcessExitReasons(context.packageName, 0, MAX_RECORDS).toList()
        }
        kept = all
        return all
    }

    /** TEST ONLY: Android's answer instead of asking it (the tests count the asks). */
    @Volatile internal var testAsk: (() -> List<android.app.ApplicationExitInfo>)? = null

    /** TEST ONLY: the next [records] asks Android again. */
    internal fun resetForTest() { kept = null; last = null; testAsk = null }

    fun read(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching {
            val all = records(context).map { it.timestamp to it }
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
