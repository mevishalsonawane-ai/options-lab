package com.optionslab.ira

/**
 * How sure the recognizer was of its best reading (its confidence scores, 0 to 1), for a noisy room (Boss, 4 Oct: "only
 * hear my voice", the TV and people nearby). Many on-device recognizers give no scores, or zeros: then nothing changes.
 * A faint reading only ever makes Jarvis do LESS - a follow-up without the name, or a yes, is let go - never more: the
 * name, the lock and the voice checks apply as before. Pure: the scores in, a judgement out.
 */
object Sure {
    /** Below this, a reading is the room more than Boss (a clear sentence reads 0.5 and up on recognizers that score). */
    const val FAINT = 0.25f

    /**
     * The best reading's score, or null when the recognizer gave none that mean anything: no array, one that does not
     * match the readings, a value outside 0 to 1 (-1: "not available"), or a zero best (scores not filled in).
     */
    fun best(scores: FloatArray?, readings: Int): Float? {
        if (scores == null || scores.isEmpty() || readings <= 0 || scores.size != readings) return null
        if (scores.any { it.isNaN() || it < 0f || it > 1f }) return null
        return scores[0].takeIf { it > 0f }
    }

    /**
     * Should a reading scored [best] be let go as the room's? Only a follow-up without the name (not [named]) that Boss
     * did not just call for (not [called]: after "Yes, Boss?" or the mic button, a quiet question is still his) - those
     * may only ask anyway, and the room can start them. Unknown scores: never.
     */
    fun faint(best: Float?, named: Boolean, called: Boolean): Boolean = best != null && best < FAINT && !named && !called

    /** A spoken yes scored [best] (unknown: taken as before): faint, it is not a yes - anything unclear is not a yes. */
    fun faintYes(best: Float?): Boolean = best != null && best < FAINT

    /** For the trace: the score as a number only (never the words). */
    fun say(best: Float?): String = if (best == null) "no score" else "sure %.2f".format(java.util.Locale.ENGLISH, best)
}

/**
 * How long Boss's words may stand still before his turn is closed, learned from his own pauses (Boss, 4 Oct: a fixed
 * 0.9 s cut a slow question in two at a breath, and kept a quick one waiting). The pauses are the gaps between his words
 * changing inside questions he asked Jarvis (a gap followed by more words: never the end of a turn). Only how long the
 * wait is changes: the turn then ends and its words go the usual way, with every rule. Pure.
 */
object BossPace {
    /** The wait before enough of Boss's pauses are known (as before). */
    const val DEFAULT_MS = 900L
    const val MIN_MS = 700L
    const val MAX_MS = 1_500L
    /** Added to his longer pauses, so a breath of his usual length never ends the turn. */
    const val MARGIN_MS = 300L
    /** Pauses known before the wait is his own; the last [KEEP] are kept. */
    const val LEAST = 8
    const val KEEP = 40

    /**
     * [gap] (ms) added to [gaps]: only a pause inside speech - a tiny one is the recognizer's own rhythm, and one longer
     * than the longest wait cannot be inside a closed turn (a stalled recognizer).
     */
    fun add(gaps: List<Long>, gap: Long): List<Long> = if (gap < 120 || gap > MAX_MS) gaps else (gaps + gap).takeLast(KEEP)

    /**
     * The wait for Boss's words to stand still: his longer pauses (the 90th of 100) and a margin, between [MIN_MS] and
     * [MAX_MS]; [DEFAULT_MS] until [LEAST] are known. A turn cut at a breath leaves the next pauses near the wait, which
     * then grows by the margin; a quick speaker's waits shrink.
     */
    fun endAfter(gaps: List<Long>): Long {
        if (gaps.size < LEAST) return DEFAULT_MS
        val s = gaps.sorted()
        val p90 = s[((s.size - 1) * 9) / 10]
        return (p90 + MARGIN_MS).coerceIn(MIN_MS, MAX_MS)
    }

    /** Kept on the phone as numbers only ("320,540,..."); a damaged value reads as none. */
    fun save(gaps: List<Long>): String = gaps.takeLast(KEEP).joinToString(",")
    fun load(s: String?): List<Long> = s.orEmpty().split(',').mapNotNull { it.trim().toLongOrNull() }.filter { it in 120..MAX_MS }.takeLast(KEEP)

    /** For the diagnostics: the wait and what it was learned from. */
    fun say(gaps: List<Long>): String = "End-of-speech wait: ${endAfter(gaps)} ms " +
        if (gaps.size < LEAST) "(default; ${gaps.size} of $LEAST pauses known)" else "(learned from ${gaps.size} of Boss's pauses)"
}
