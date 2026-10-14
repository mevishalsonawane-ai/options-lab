package com.optionslab.app.data

import com.optionslab.app.ira.Automations
import com.optionslab.ira.GammaMorning
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Jarvis's morning gamma line (Boss approved, 14 Oct 2026; the computation is [GammaMorning]'s, R28's GEX core). INFORMATION
 * ONLY: it never places, changes, skips or resizes an order, paper or live, and no strategy reads it.
 *
 * Once a trading day, from 10:00 (the minute R28 measured) to 10:15, in Jarvis's words lane (a worker; [watch]): each
 * index's nearest-expiry chain as the app already holds it in memory ([MemoryChain]: the price stream's ticks, else the last
 * chain read; nothing is fetched), read with R28's rules. An index whose chain is not on the phone, or whose prices are
 * all older than 30 minutes, or whose spot is older than [SPOT_MS], is left out; with none at all, nothing is said that
 * day. The line goes in the chat once and is said aloud by Boss's "Jarvis speaks" choice (muted, quiet hours as always),
 * never aloud on a locked phone. "What's gamma saying" is answered from the 10:00 reading, or a reading made then, labelled
 * with its time ([answer]). Its own switch under Settings → Jarvis → Market alerts ([Automations.Auto.GAMMA], on by default).
 */
object GammaMorningHost {
    private const val KEY_DONE = "jarvis.gamma.done"
    /** A spot older than this is not today's 10:00 price. */
    const val SPOT_MS = 5 * 60_000L

    /** Today's 10:00 readings (memory; a restart forgets them, and the answer then reads the chain as it is). */
    @Volatile private var morning: Pair<LocalDate, List<GammaMorning.Reading>>? = null
    private val busy = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun doneOn(): LocalDate? = runCatching {
        com.optionslab.app.security.SecurePrefs.getString(KEY_DONE)?.let { LocalDate.parse(it) }
    }.getOrNull()

    /** One index's reading from memory at [now] (null: no chain, a stale spot or chain, or not enough options). */
    internal fun readOne(index: String, now: LocalDateTime, nowMs: Long): GammaMorning.Reading? {
        val snap = MemoryChain.read(index, nowMs)
        if (snap.spotAgeMs == null || snap.spotAgeMs > SPOT_MS) return null
        val opts = snap.legs.map { GammaMorning.Option(it.strike, it.right, it.ltp, it.ageMs / 60_000.0, it.oi) }
        return GammaMorning.read(index, opts, snap.spot, now, snap.expiry)
    }

    /** Every index's reading now (those that can be read). Memory only; a worker thread. */
    fun readNow(now: LocalDateTime = Market.now().toLocalDateTime(), nowMs: Long = System.currentTimeMillis()): List<GammaMorning.Reading> =
        GammaMorning.INDICES.mapNotNull { runCatching { readOne(it, now, nowMs) }.getOrNull() }

    /** Today's 10:00 readings (empty: none made). */
    fun morning(day: LocalDate = Market.today()): List<GammaMorning.Reading> = morning?.takeIf { it.first == day }?.second.orEmpty()

    /**
     * Each round of the words lane: from 10:00 to 10:15 on a trading day, once, the line in the chat (and aloud by Boss's
     * choice, never on a locked phone). With no chain on the phone yet, the next round tries again within the window.
     */
    suspend fun watch() {
        if (!com.optionslab.app.BuildConfig.JARVIS || com.optionslab.app.BuildConfig.GOLD) return
        val now = Market.now().toLocalDateTime()
        val day = now.toLocalDate()
        // Battery: outside 10:00-10:15 nothing is read, not even the settings vault (the lane runs all session).
        if (now.toLocalTime().isBefore(GammaMorning.AT) || !now.toLocalTime().isBefore(GammaMorning.LATE)) return
        if (!Automations.on(Automations.Auto.GAMMA)) return
        if (!GammaMorning.due(now, runCatching { Market.isTradingDay(day) }.getOrDefault(false), doneOn())) return
        if (!busy.compareAndSet(false, true)) return
        try {
            val readings = readNow(now)
            val text = GammaMorning.note(readings) ?: return
            morning = day to readings
            // Kept as done before it is put, so a restart midway never says it twice.
            runCatching { com.optionslab.app.security.SecurePrefs.put(KEY_DONE, day.toString()) }
            post(text)
            Automations.acted(Automations.Auto.GAMMA, "Said the morning gamma line (${readings.size} index${if (readings.size == 1) "" else "es"}).")
        } finally {
            busy.set(false)
        }
    }

    /** In the chat; aloud only on an unlocked phone (Boss's "Jarvis speaks" choice, mute and quiet hours decide the rest). */
    private fun post(text: String) {
        val locked = runCatching { com.optionslab.app.ira.IraHub.locked() }.getOrDefault(true)
        if (locked) com.optionslab.app.ira.IraHub.note(text, from = Automations.Auto.GAMMA)
        else com.optionslab.app.ira.IraHub.noteAloud(text, com.optionslab.ira.SpeakChoice.Weight.IMPORTANT, Automations.Auto.GAMMA)
    }

    /** "What's gamma saying" ([index]: the one named, or null for all). Memory only; a worker thread. */
    fun answer(index: String?): String {
        val now = Market.now().toLocalDateTime()
        val trading = runCatching { Market.isTradingDay(now.toLocalDate()) }.getOrDefault(true)
        val m = morning(now.toLocalDate())
        val fresh = if (m.any { index == null || it.index == index }) emptyList() else readNow(now)
        return GammaMorning.answer(index, m, fresh, trading, now)
    }

    /** The diagnostics line for [day] (signs and strikes only). */
    fun diagLine(day: LocalDate): String = GammaMorning.diagLine(morning(day))

    /** TEST ONLY: forget today's line (memory). */
    internal fun resetForTest() { morning = null; busy.set(false) }
}
