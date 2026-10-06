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
            val at = com.optionslab.app.data.Market.now().toLocalDateTime().withNano(0)
            val tag = TodayNotes.tag(text, from?.name, kind)
            _notes.update { TodayNotes.keep(it, TodayNotes.Note(at, text, tag.category, tag.source)) }
        }
    }

    /** Today's notes, newest first. */
    fun today(): List<TodayNotes.Note> = runCatching {
        TodayNotes.of(_notes.value, com.optionslab.app.data.Market.today())
    }.getOrDefault(emptyList())

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
