package com.optionslab.ira

/**
 * A stored record read (parsed) once and kept while its stored text is the same (speed round 12): the app read Boss's
 * routine log (up to [Routine.LOG_MAX] lines) out of its JSON twice for every answer that may end with a next-question
 * offer ([NextAsk]), and the mistakes list (up to 100 entries, each time parsed) for every answer's caution
 * ([SelfDoubt]) - on the answer's path, before the first word. The settings vault gives back the very text it holds
 * until a write changes it, so the text itself is the key: the same text (most often the same instance) is the same
 * reading; any write (a wipe, a restore, a "forget") gives other text and it is read afresh - exactly what reading again
 * would give. Pure: no clock, no device.
 */
class StoredOnce<T>(private val read: (String?) -> T) {
    private class Held<T>(val text: String?, val value: T)

    @Volatile private var held: Held<T>? = null
    private val reads = java.util.concurrent.atomic.AtomicInteger()

    /** [read] of [text], kept until other text comes. A read that throws keeps nothing (it throws again next time). */
    fun of(text: String?): T {
        held?.let { h -> if (h.text === text || h.text == text) return h.value }
        reads.incrementAndGet()
        val v = read(text)
        held = Held(text, v)
        return v
    }

    /** How many times the text was read rather than found kept (tests: the work an answer costs). */
    val readCount: Int get() = reads.get()

    /** The kept reading forgotten and the count reset (tests). */
    fun forget() { held = null; reads.set(0) }
}
