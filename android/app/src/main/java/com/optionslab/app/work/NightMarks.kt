package com.optionslab.app.work

import android.content.Context

/**
 * 22 Oct (the overnight kills, "excessive binder traffic during cached"): a few small marks that must outlive the process -
 * when each alarm was last armed, the night's CPU budget spent so far, the Candle agent's study day, the old data store's
 * clean-up - so a start in the background (an hourly night job) neither repeats that work nor forgets it after Android ends
 * the app ([com.optionslab.ira.NightStart] has the rules).
 *
 * A plain preferences file of its own: times, days and milliseconds only, nothing about the account - so not the settings
 * vault, whose every write is a Keystore call. Read and written OFF the main thread only: on it, nothing is read (the callers
 * then do the full work, as before) and nothing is written. Never on the order or tick path. Never throws.
 */
object NightMarks {
    private const val FILE = "night_marks"

    /** TEST ONLY: Robolectric runs the tests on the main thread; true lets them read and write the marks there. */
    @Volatile internal var testOffMain = false

    /** May the marks be read and written on this thread (any but the main one)? */
    internal fun usable(): Boolean = testOffMain || android.os.Looper.myLooper() != android.os.Looper.getMainLooper()

    fun get(c: Context?, key: String): String? {
        if (c == null || !usable()) return null
        return runCatching { c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(key, null) }.getOrNull()
    }

    @Volatile private var installedKnown: Long? = null

    /**
     * The installed app as a number: its APK file is new at every update (the version code stays 1). A file's date, read once a
     * process (an update ends the process); never on the main thread (there: 0, and nothing kept).
     */
    fun installed(c: Context): Long {
        installedKnown?.let { return it }
        if (!usable()) return 0L
        return runCatching {
            val dir = c.applicationInfo.sourceDir ?: return@runCatching 0L
            java.io.File(dir).lastModified() * 31 + dir.hashCode()
        }.getOrDefault(0L).also { if (it != 0L) installedKnown = it }
    }

    /** [value] kept for [key] (null removes it); written to disk in the background (a mark lost to a kill only means the work again). */
    fun put(c: Context?, key: String, value: String?) {
        if (c == null || !usable()) return
        runCatching {
            val e = c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            if (value == null) e.remove(key) else e.putString(key, value)
            e.apply()
        }
    }
}

/**
 * 22 Oct (the overnight kills): when each alarm was last armed ([NightMarks]; [com.optionslab.ira.NightStart.AlarmMark]: its
 * time, the installed app and the boot), so the app's start in the background arms only the alarms whose time changed
 * ([Jobs.scheduleAll]'s onlyChanged). Every arming records its mark, whoever asked; a mark that cannot be read, or a start on
 * the main thread, arms the alarm as before.
 */
internal object AlarmMarks {
    /** TEST ONLY: the phone's boot time instead of the clocks' (Robolectric's two clocks do not move together). */
    @Volatile internal var testBootAtMs: Long? = null

    /** The phone's boot as the wall clock puts it: the same all through one boot (to a few ms), another after a reboot. */
    private fun bootAt(): Long = testBootAtMs ?: (System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime())

    /**
     * Exact alarms allowed, asked once a process: Android ends the app when the permission is taken away (and drops its exact
     * alarms), so a new process sees the change and arms everything again (as inexact alarms).
     */
    @Volatile private var exactKnown: Boolean? = null
    private fun exact(c: Context): Boolean = exactKnown ?: runCatching { Jobs.canExact(c) }.getOrDefault(false).also { exactKnown = it }

    /** The installed app and the exact-alarm permission as one number (either changing arms everything again). */
    private fun want(c: Context, atMs: Long) =
        com.optionslab.ira.NightStart.AlarmMark(atMs, NightMarks.installed(c) * 2 + (if (exact(c)) 1 else 0), bootAt())

    /** Must alarm [key] be armed for [atMs] ([com.optionslab.ira.NightStart.OFF]: off)? [held]: Android still holds its intent. */
    fun needsArm(c: Context, key: String, atMs: Long, held: () -> Boolean): Boolean {
        val saved = com.optionslab.ira.NightStart.AlarmMark.decode(NightMarks.get(c, "alarm.$key")) ?: return true
        // The market clock the alarm's time was worked out on (the phone's own clock; the tests' fixed one).
        val now = runCatching { com.optionslab.app.data.Market.now().toInstant().toEpochMilli() }.getOrElse { System.currentTimeMillis() }
        val want = want(c, atMs)
        // Everything else first (no call into Android); then, for an alarm that is on, whether Android still holds it.
        if (com.optionslab.ira.NightStart.needsArm(saved, want, pendingThere = true, nowMs = now)) return true
        return com.optionslab.ira.NightStart.needsArm(saved, want, pendingThere = runCatching { held() }.getOrDefault(false), nowMs = now)
    }

    /** Alarm [key] was just armed for [atMs] (or cancelled: OFF). */
    fun armed(c: Context, key: String, atMs: Long) {
        // On the main thread (a boot's receiver) nothing is kept, nor worked out: the next start arms everything once, as before.
        if (!NightMarks.usable()) return
        NightMarks.put(c, "alarm.$key", want(c, atMs).encode())
    }
}
