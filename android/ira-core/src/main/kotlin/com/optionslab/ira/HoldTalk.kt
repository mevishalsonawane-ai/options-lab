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
    /** While a yes-or-no question waits, a hold is its answer only when it is this short (words) ([shortAnswer]). */
    const val ANSWER_WORDS = 4
    /** Said when a hold too long for a yes or no comes while one is waiting (the question stays open). */
    const val SAY_YES_OR_NO = "Say yes or no, Boss."
    /** Said when recognizer errors end a hold before any words were heard. */
    const val NOT_CAUGHT = "I didn't catch that."
    /** Recognizer errors in a row that end a hold (any error other than a timeout ends it at once). */
    const val MAX_ERRORS = 3
    /** The wait before listening again after a timeout mid-hold, doubled each time in a row (300, 600, 1200 ms). */
    const val ERROR_WAIT_MS = 300L

    /**
     * Can a held [words] be the answer to a waiting yes-or-no question? Only a short one (1 to [ANSWER_WORDS] words): a
     * long held ramble with an "ok" or "sure" somewhere in it is never taken for a yes (nor a no).
     */
    fun shortAnswer(words: String?): Boolean = count(words) in 1..ANSWER_WORDS

    /** The wait (ms) before listening again after the [inRow]th timeout in a row (1: 300, 2: 600, 3+: 1200). */
    fun errorWait(inRow: Int): Long = ERROR_WAIT_MS shl (inRow - 1).coerceIn(0, 2)

    private fun count(words: String?): Int = words?.trim()?.split(Regex("\\s+"))?.count { it.isNotEmpty() } ?: 0

    /**
     * What Jarvis says when a Talk opens the microphone: "Yes, Boss?" for the notification's or lock screen's Talk;
     * nothing for a [hold] - he listens silently, the listening light the only sign.
     */
    fun greeting(hold: Boolean): String? = if (hold) null else "Yes, Boss?"

    /** What the voice does after the recognizer closes a turn during a hold. */
    enum class Next { LISTEN, SEND }

    /**
     * One hold's words: [startedAt] (elapsed ms) the press. Each recognizer turn of the hold is a segment: the audio of the
     * segment with the most words is kept for the voice check ([audio]), the recognizer's lowest score across them
     * ([sure]), and the final segment's readings ([alternatives]). Kept in memory for the one hold only.
     */
    class Buffer(val startedAt: Long) {
        private val parts = ArrayList<String>()
        private var partial: String? = null
        private var segments = 0
        private var bestWords = 0
        private var lastReadings: List<String> = emptyList()
        /** The audio of the segment with the most words (null: no segment had shared audio). */
        var audio: ShortArray? = null; private set
        /** The recognizer's lowest score for a segment's best reading (null: none scored). */
        var sure: Float? = null; private set
        /** Recognizer errors in a row this hold ([turnFailed]); a turn that closes normally resets it. */
        var errors = 0; private set
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
        fun turnEnded(final: String?, audio: ShortArray? = null, sure: Float? = null, readings: List<String> = emptyList()): Next {
            val f = final?.trim()?.takeIf { it.isNotEmpty() }
            errors = 0
            if (f != null) keep(f, audio, sure, readings) else keep(partial, audio, null, emptyList())
            partial = null
            return if (released) Next.SEND else Next.LISTEN
        }

        /**
         * The recognizer gave an error mid-hold ([timeout]: a timeout or "no match", which a pause may give): the words read
         * mid-turn are kept. The wait before listening again ([errorWait]), or null: the hold ends - any other error, or
         * the [MAX_ERRORS]th in a row.
         */
        fun turnFailed(timeout: Boolean, audio: ShortArray? = null): Long? {
            keep(partial, audio, null, emptyList())
            partial = null
            errors++
            return if (!timeout || errors >= MAX_ERRORS) null else errorWait(errors)
        }

        private fun keep(w: String?, pcm: ShortArray?, score: Float?, readings: List<String>) {
            if (w == null) return
            segments++
            val n = count(w)
            if (pcm != null && pcm.isNotEmpty() && n > bestWords) { audio = pcm; bestWords = n }
            if (score != null) sure = sure?.let { minOf(it, score) } ?: score
            val r = readings.map { it.trim() }.filter { it.isNotEmpty() }
            lastReadings = if (r.firstOrNull() == w) r else emptyList()
            if (w != parts.lastOrNull()) parts += w
        }

        /** More than one recognizer turn gave this hold's words (the open turn's words read mid-turn count as one). */
        fun spliced(): Boolean = segments + (if (partial != null) 1 else 0) > 1

        /**
         * The readings to judge: the whole hold's words, then each other reading of the final segment with the earlier
         * segments before it (just the words when the final segment had no other readings).
         */
        fun alternatives(): List<String> {
            val q = text() ?: return emptyList()
            if (partial != null || lastReadings.size < 2 || parts.lastOrNull() != lastReadings.first()) return listOf(q)
            val before = parts.dropLast(1)
            return listOf(q) + lastReadings.drop(1).map { join(before + it) }
        }

        private fun join(words: List<String>) = words.joinToString(" ").replace(Regex("\\s+"), " ").trim()

        fun release() { released = true }

        /** Held [CAP_MS] or more at [now] (elapsed ms). */
        fun capped(now: Long): Boolean = now - startedAt >= CAP_MS

        /** The words so far, joined (the open turn's words read mid-turn included), or null. */
        fun text(): String? = join(parts + listOfNotNull(partial)).ifEmpty { null }

        /** The question to ask - once: the whole hold's words, or null (nothing heard, or already asked). */
        fun take(): String? {
            if (sent) return null
            sent = true
            return text()
        }
    }
}
