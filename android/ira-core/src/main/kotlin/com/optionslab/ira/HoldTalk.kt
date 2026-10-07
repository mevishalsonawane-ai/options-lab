package com.optionslab.ira

/**
 * Hold to talk (Boss, 7 Oct: "press hold and talk, and when i release the mic then jarvis should reply"). While the mic
 * is held Jarvis keeps listening: a pause, or the recognizer closing its turn by itself, sends nothing - its words are
 * kept and listening goes on. Let go (or held [CAP_MS]), the whole hold's words are asked once, as one question; nothing
 * heard, nothing is asked. Words only, kept in memory for the one hold.
 */
object HoldTalk {
    /** A hold is answered after this long even if the mic is still held. */
    const val CAP_MS = 60_000L
    /** The silence the recognizer is asked to wait for while held (recognizers that honour it do not close the turn). */
    const val SILENCE_MS = 60_000L
    /** Let go, the recognizer's final reading is awaited this long; then the words read so far are asked. */
    const val RELEASE_WAIT_MS = 2_500L

    /**
     * What Jarvis says when a Talk opens the microphone: "Yes, Boss?" for the notification's or lock screen's Talk;
     * nothing for a [hold] - he listens silently, the listening light the only sign.
     */
    fun greeting(hold: Boolean): String? = if (hold) null else "Yes, Boss?"

    /** What the voice does after the recognizer closes a turn during a hold. */
    enum class Next { LISTEN, SEND }

    /** One hold's words: [startedAt] (elapsed ms) the press. */
    class Buffer(val startedAt: Long) {
        private val parts = ArrayList<String>()
        private var partial: String? = null
        /** The mic was let go (or the hold reached [CAP_MS]): the next close sends. */
        var released = false; private set
        /** Asked already: a hold is asked once at most. */
        var sent = false; private set

        /** The words the recognizer reads mid-turn (kept in case the turn ends without a final reading). */
        fun partial(words: String?) { words?.trim()?.takeIf { it.isNotEmpty() }?.let { partial = it } }

        /**
         * The recognizer closed a turn: its final reading [final] (null or blank: none - the words read mid-turn count
         * instead) is kept. [Next.SEND] once let go, else [Next.LISTEN]: listen again, the same hold.
         */
        fun turnEnded(final: String?): Next {
            val w = final?.trim()?.takeIf { it.isNotEmpty() } ?: partial
            partial = null
            if (w != null && w != parts.lastOrNull()) parts += w
            return if (released) Next.SEND else Next.LISTEN
        }

        fun release() { released = true }

        /** Held [CAP_MS] or more at [now] (elapsed ms). */
        fun capped(now: Long): Boolean = now - startedAt >= CAP_MS

        /** The words so far, joined (the open turn's words read mid-turn included), or null. */
        fun text(): String? = (parts + listOfNotNull(partial)).joinToString(" ").replace(Regex("\\s+"), " ").trim().ifEmpty { null }

        /** The question to ask - once: the whole hold's words, or null (nothing heard, or already asked). */
        fun take(): String? {
            if (sent) return null
            sent = true
            return text()
        }
    }
}
