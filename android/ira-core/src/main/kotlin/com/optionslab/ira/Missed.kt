package com.optionslab.ira

/**
 * The words Jarvis could not place today, kept (redacted, the last [KEEP], each once) for the diagnostics and the
 * 15:45 wrap-up - so Boss can teach them by rephrasing, and each report shows what to handle next. Pure.
 */
object Missed {
    const val KEEP = 20

    fun add(list: List<String>, said: String): List<String> {
        val w = Secrets.redact(said).replace(rx("\\s+"), " ").trim().take(120)
        if (w.isEmpty()) return list
        return (list.filter { !it.equals(w, ignoreCase = true) } + w).takeLast(KEEP)
    }

    /** One line for the wrap-up, or null when everything was understood. */
    fun say(list: List<String>): String? = if (list.isEmpty()) null else
        "I didn't understand ${list.size} thing${if (list.size > 1) "s" else ""} today (" + list.takeLast(3).joinToString(", ") { "\"$it\"" } +
            "); say them another way next time and I'll learn them."
}
