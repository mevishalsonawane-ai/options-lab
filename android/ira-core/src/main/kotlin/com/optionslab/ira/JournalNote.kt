package com.optionslab.ira

/**
 * The "Journal" line a trade's popup shows for its note and tags: the tags, then " · " and the note when there is one
 * (as the popup always wrote it). Pure: speed round 4 moved the journal's read off the main thread, and the popup only
 * formats here. Null when there is nothing to show.
 */
object JournalNote {
    fun line(tags: Collection<String>, note: String): String? {
        if (tags.isEmpty() && note.isBlank()) return null
        return tags.joinToString() + if (note.isNotBlank()) " · $note" else ""
    }
}
