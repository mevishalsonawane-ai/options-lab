package com.optionslab.ira

/**
 * What Jarvis says aloud, by Boss's choice (Settings, Voice: "Jarvis speaks: everything / answers and important notes /
 * only answers"), and why a line stayed on screen. A display preference only: it never touches the PIN, Live mode, the
 * guard or any confirm, and a safety warning or an answer to Boss is always spoken (unless muted or in quiet hours,
 * which are decided before this). Pure.
 */
object SpeakChoice {
    enum class Choice(val key: String, val label: String) {
        EVERYTHING("all", "Everything"),
        IMPORTANT("important", "Answers and important notes"),
        ANSWERS("answers", "Only answers");
        companion object {
            /** The default: answers and important notes. */
            fun of(key: String?): Choice = entries.firstOrNull { it.key == key } ?: IMPORTANT
        }
    }

    /** How much a line matters: an answer to Boss, a safety warning, an important note (a trade question, his positions, the wrap-up), a minor one (news, records). */
    enum class Weight { ANSWER, WARNING, IMPORTANT, MINOR }

    fun speaks(choice: Choice, weight: Weight): Boolean = when (weight) {
        Weight.ANSWER, Weight.WARNING -> true
        Weight.IMPORTANT -> choice != Choice.ANSWERS
        Weight.MINOR -> choice == Choice.EVERYTHING
    }

    /** Why a line of [weight] stayed on screen under [choice] (null: it was spoken). */
    fun why(choice: Choice, weight: Weight): String? = when {
        speaks(choice, weight) -> null
        weight == Weight.MINOR -> "it was a minor note, kept on screen (\"Jarvis speaks\" is set to ${choice.label.lowercase()})"
        else -> "\"Jarvis speaks\" is set to ${choice.label.lowercase()}"
    }
}
