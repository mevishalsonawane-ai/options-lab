package com.optionslab.app.work

import com.optionslab.ira.LoopMeter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Battery, 14 Oct: which background loops ran today and for how long ([LoopMeter]) - the evening watch's minutes after
 * 15:40 and its 15 s / 60 s MCX passes, the tick recorder's minutes, the price stream's minutes on, the order-flow pump's,
 * Jarvis's resting minutes. Shown as one "Battery today:" line in the diagnostics and on the Health page, so Boss can see
 * on the phone that the evening work rests. Memory only (a restart counts again); never on the order or tick path: the
 * loops note a start or an end on their own threads, and the stream's state is collected here (no polling).
 */
object BatteryMeter {
    val meter = LoopMeter(dayOf = { Instant.ofEpochMilli(it).atZone(com.optionslab.engine.IST).toLocalDate() })
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var started = false

    /** At the app's start: the stream's minutes on, from its own state (a collector suspends between changes). */
    fun init() {
        if (started) return
        started = true
        scope.launch {
            runCatching {
                com.optionslab.app.data.KiteStream.status.collect { meter.set(LoopMeter.STREAM, it == com.optionslab.app.data.KiteStream.Status.LIVE) }
            }
        }
    }

    /** The diagnostics' and the Health page's line. */
    fun line(): String = runCatching { LoopMeter.line(meter) }.getOrElse { "Battery today: could not read" }

    /**
     * 27 Oct (battery audit): [day]'s line for the uploaded diagnostics file (the evening upload often runs after midnight:
     * the day before's line is kept at the roll). Loop names, minutes and counts only - no personal data.
     */
    fun lineFor(day: java.time.LocalDate): String {
        restoreOnce()
        val f = runCatching { meter.figuresFor(day) }.getOrNull()
        if (f != null) { saveNow(); return f.line() }
        return runCatching { kept()[day]?.line() }.getOrNull()
            ?: "Battery today: not kept for $day (the app restarted before it was saved)"
    }

    // ---- kept on disk (27 Oct): a restart, or the next morning's catch-up upload in a new process, keeps the day's count ------

    @Volatile private var dir: java.io.File? = null
    @Volatile private var savedAt = 0L
    @Volatile private var restored = false
    private const val FILE = "battery_today.txt"
    /** At most one save in this long from [saveSoon] (Android's memory trims come ~50 times a day). */
    private const val SAVE_EVERY_MS = 10 * 60_000L

    /** At the app's start: where the figures are kept; the earlier process's figures are read back on a background thread. */
    fun init(context: android.content.Context) {
        dir = context.noBackupFilesDir
        init()
        scope.launch(Dispatchers.IO) { restoreOnce() }
    }

    /** The figures an earlier process saved, added in once ([LoopMeter.restore]). Off the main thread only. */
    @Synchronized internal fun restoreOnce() {
        if (restored || dir == null) return
        restored = true
        runCatching { kept().values.forEach { meter.restore(it) } }
    }

    /**
     * Android asks for memory back (the main thread may call this): today's and yesterday's figures are saved on a background
     * thread, at most once every [SAVE_EVERY_MS] - a kill may follow. Never on the order or tick path.
     */
    fun saveSoon() {
        val now = System.currentTimeMillis()
        if (now - savedAt < SAVE_EVERY_MS) return
        savedAt = now
        scope.launch(Dispatchers.IO) { saveNow() }
    }

    /** Today's and the day before's figures into the file (the last three days kept). Off the main thread only. */
    @Synchronized internal fun saveNow() {
        val d = dir ?: return
        lastSaveThread = Thread.currentThread().name
        // Never save before the earlier process's figures are in (they would be overwritten by this process's alone).
        restoreOnce()
        runCatching {
            val today = Instant.now().atZone(com.optionslab.engine.IST).toLocalDate()
            val all = java.util.TreeMap(kept())
            for (day in listOf(today.minusDays(1), today)) meter.figuresFor(day)?.let { all[day] = it }
            while (all.size > 3) all.remove(all.firstKey())
            val f = java.io.File(d, FILE)
            val tmp = java.io.File(d, "$FILE.tmp")
            tmp.writeText(all.values.joinToString("\n") { LoopMeter.encode(it) })
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }

    /** The figures on disk by day. Off the main thread only. */
    internal fun kept(): Map<java.time.LocalDate, LoopMeter.Figures> {
        val f = java.io.File(dir ?: return emptyMap(), FILE)
        return runCatching { if (f.isFile) f.readLines().mapNotNull { LoopMeter.decode(it) }.associateBy { it.day } else emptyMap() }
            .getOrDefault(emptyMap())
    }

    /** The thread of the last save (the tests: never the main thread from [saveSoon]). */
    @Volatile internal var lastSaveThread: String? = null

    /** TEST ONLY. */
    internal fun resetForTest(context: android.content.Context? = null) {
        savedAt = 0L; restored = false; lastSaveThread = null
        context?.let { dir = it.noBackupFilesDir }
        runCatching { dir?.let { java.io.File(it, FILE).delete() } }
    }
}
