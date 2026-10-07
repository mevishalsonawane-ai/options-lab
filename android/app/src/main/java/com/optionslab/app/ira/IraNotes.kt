package com.optionslab.app.ira

import com.optionslab.ira.TodayNotes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * "Today's notes" (Boss, 06 Oct): every note Jarvis puts in the chat by himself ([IraHub.note]), kept with its time and
 * its category ([TodayNotes]; from the automation that posted it when it says so, else read from its first words) - the
 * Ira page's and Settings' "Today's notes" list them, and "what did you tell me today" sums them up.
 *
 * Kept in memory for today only (an older day's go as a new day's first note comes; at most [TodayNotes.MAX]); never
 * written to a file and never logged. Read only: nothing here says, places, closes, arms or switches anything.
 */
internal object IraNotes {
    private val _notes = MutableStateFlow<List<TodayNotes.Note>>(emptyList())
    /** The kept notes, oldest first ([today] for today's, newest first). */
    val notes: StateFlow<List<TodayNotes.Note>> = _notes.asStateFlow()

    /** A note just posted in the chat: kept with [from] (the automation that posted it) or [kind]. Never throws. */
    fun add(text: String, from: Automations.Auto? = null, kind: TodayNotes.Category? = null) {
        runCatching {
            if (text.isBlank()) return
            // To the nanosecond, like the catch-up's anchors ([seen], [caughtUp]): a note in the same second still compares.
            val at = com.optionslab.app.data.Market.now().toLocalDateTime()
            val tag = TodayNotes.tag(text, from?.name, kind)
            _notes.update { TodayNotes.keep(it, TodayNotes.Note(at, text, tag.category, tag.source)) }
        }
    }

    /** Today's notes, newest first. */
    fun today(): List<TodayNotes.Note> = runCatching {
        TodayNotes.of(_notes.value, com.optionslab.app.data.Market.today())
    }.getOrDefault(emptyList())

    // ---- "Catch me up" ([com.optionslab.ira.CatchUp]): when the Ira page was last seen, and the last catch-up ----------

    /** When the Ira page last stopped being in front of Boss, and when the last catch-up was said (null: not known). */
    private var seenAt: java.time.LocalDateTime? = null
    private var caughtAt: java.time.LocalDateTime? = null
    private var anchorsRead = false

    /**
     * When this run of the app started keeping notes (the notes live in memory only, the anchors in the vault): an anchor
     * before it means the notes in between are gone ([com.optionslab.ira.CatchUp.restarted]). Null when the clock could not
     * be read. Settable by the tests. The process's own start, not when this object was first used (the first note or
     * look may come long after the start): otherwise "I restarted at" would name that later moment.
     */
    @Volatile internal var keptSince: java.time.LocalDateTime? = processStart()

    /** When this process started, on the market clock; now when that can't be read. Never throws. */
    private fun processStart(): java.time.LocalDateTime? = runCatching {
        val up = android.os.SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime()
        now()?.minus(java.time.Duration.ofMillis(up.coerceAtLeast(0L)))
    }.getOrNull() ?: now()

    /** Now, to the nanosecond (null when the clock can't be read). Never throws. */
    fun now(): java.time.LocalDateTime? = runCatching { com.optionslab.app.data.Market.now().toLocalDateTime() }.getOrNull()

    /**
     * The two moments from the vault, once it has been read (this phone's only: [com.optionslab.ira.Upkeep.PRIVATE]); a
     * failed read is tried again next time. A moment already set here is newer and kept. Never throws.
     */
    private fun readAnchors() {
        if (anchorsRead) return
        runCatching {
            val seen = com.optionslab.app.security.SecurePrefs.getString(com.optionslab.ira.CatchUp.KEY_SEEN)
            val done = com.optionslab.app.security.SecurePrefs.getString(com.optionslab.ira.CatchUp.KEY_DONE)
            fun parse(v: String?) = v?.let { runCatching { java.time.LocalDateTime.parse(it) }.getOrNull() }
            if (seenAt == null) seenAt = parse(seen)
            if (caughtAt == null) caughtAt = parse(done)
            anchorsRead = true
        }
    }

    private fun stamp(key: String, at: java.time.LocalDateTime) {
        runCatching { com.optionslab.app.security.SecurePrefs.putAllSoon(mapOf(key to at.toString())) }
    }

    /**
     * The Ira page just stopped being in front of Boss (paused or left): what he saw runs to now. Reads nothing from the
     * vault (it runs on the main thread); the write is queued. Never throws.
     */
    @Synchronized fun seen() {
        val at = now() ?: return
        seenAt = at
        stamp(com.optionslab.ira.CatchUp.KEY_SEEN, at)
    }

    /**
     * A catch-up was just said in full (unlocked): the next one counts from [at] - taken before the notes were read, so a
     * note posted while the digest was made is said again rather than lost. Never throws.
     */
    @Synchronized fun caughtUp(at: java.time.LocalDateTime) {
        caughtAt = at
        stamp(com.optionslab.ira.CatchUp.KEY_DONE, at)
    }

    /** From when the catch-up counts: the later of [seen] and [caughtUp]; null (all of today's) when neither is known. */
    @Synchronized fun catchUpFrom(): java.time.LocalDateTime? {
        readAnchors()
        return com.optionslab.ira.CatchUp.anchor(seenAt, caughtAt)
    }

    /** Tests: both moments forgotten (in memory; the vault is not read again), and no restart known. */
    @Synchronized internal fun forgetCatchUp() { seenAt = null; caughtAt = null; anchorsRead = true; keptSince = null }

    /** Forgotten with the conversation (Boss's own button) and by the tests. */
    fun clear() { _notes.value = emptyList() }

    /** Settings → Jarvis → What Jarvis does by itself: the row of a group's switch ([com.optionslab.app.ui.SettingSpot]'s key). */
    fun groupKey(g: Automations.Group): String = "jarvis.switch.group.${g.name}"

    /** ... and of a behaviour's own switch beneath its group. */
    fun subKey(a: Automations.Auto): String = "jarvis.switch.sub.${a.name}"

    /** Solo's own switch (its card on the same page). */
    const val SOLO_KEY = "jarvis.solo"

    /**
     * The switch behind notes from [source] (an [Automations.Auto]'s name, or [TodayNotes.SOLO]): the Settings row's key and
     * its label - a behaviour's own switch when it has one, else its group's; null when it has none (always on, retired,
     * not an automation).
     */
    fun switchOf(source: String?): Pair<String, String>? {
        if (source == null) return null
        if (source == TodayNotes.SOLO) return SOLO_KEY to "Solo (midday)"
        val a = Automations.Auto.entries.firstOrNull { it.name == source } ?: return null
        if (a in Automations.ALWAYS || a in Automations.RETIRED) return null
        if (Automations.isSub(a)) return subKey(a) to a.label
        val g = Automations.groupOf(a) ?: return null
        return groupKey(g) to g.label
    }

    /** The switches behind [category]'s notes in [notes], each once, newest first. */
    fun switches(notes: List<TodayNotes.Note>, category: TodayNotes.Category): List<Pair<String, String>> =
        TodayNotes.sources(notes, category).mapNotNull { switchOf(it) }.distinctBy { it.first }
}
