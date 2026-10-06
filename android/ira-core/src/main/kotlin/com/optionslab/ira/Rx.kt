package com.optionslab.ira

import java.util.concurrent.ConcurrentHashMap

/**
 * A pattern written in place ("rx(\" (buy|sell) \")") compiled once and kept (2026-10-05): every question went through
 * dozens of these, each compiled afresh at every reading - a command check alone took about 0.15 ms on a desktop, several
 * times that on a phone, and one spoken question is checked several times. The pattern is the same text, so it matches
 * exactly as before. Only for patterns fixed in the code (never built from what Boss says): at most [KEPT] are kept.
 */
internal object Rx {
    const val KEPT = 4096   // 1024 filled up as the readers grew (5 Oct): a pattern past it was compiled afresh every time
    private val kept = ConcurrentHashMap<String, Regex>()

    private val keptIgnoringCase = ConcurrentHashMap<String, Regex>()

    fun of(pattern: String): Regex = kept[pattern] ?: Regex(pattern).also { if (kept.size < KEPT) kept.putIfAbsent(pattern, it) }

    /** [pattern] with [RegexOption.IGNORE_CASE], kept apart from the plain ones. */
    fun ignoringCase(pattern: String): Regex = keptIgnoringCase[pattern]
        ?: Regex(pattern, RegexOption.IGNORE_CASE).also { if (keptIgnoringCase.size < KEPT) keptIgnoringCase.putIfAbsent(pattern, it) }
}

/** [pattern] compiled once ([Rx]). */
internal fun rx(pattern: String): Regex = Rx.of(pattern)

/** [pattern] compiled once ([Rx]), as Regex(pattern, [option]); only [RegexOption.IGNORE_CASE] is kept. */
internal fun rx(pattern: String, option: RegexOption): Regex =
    if (option == RegexOption.IGNORE_CASE) Rx.ignoringCase(pattern) else Regex(pattern, option)

/**
 * The last [max] readings of a pure reader, by the words read (2026-10-05, speed round 8): one spoken question goes
 * through about ninety readers in the hub, and many of them read the same words again through the same helpers
 * (whether it acts, its Hinglish, its spelling, a command in it) - [Commands.parse] alone ran a dozen times for one
 * question. Only for readers whose answer depends on nothing but the words (no clock, no stored state), so a kept
 * reading is exactly what reading again would give. A reader that throws keeps nothing (it throws again next time).
 */
internal class Kept<V>(private val max: Int) {
    private object None

    private val kept = object : LinkedHashMap<String, Any>(max * 2, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Any>?) = size > max
    }

    /** [read] of [words], kept; when [keep] says no, given but not kept (a reading that is not the words' alone). */
    @Suppress("UNCHECKED_CAST")
    fun of(words: String, keep: (V) -> Boolean = { true }, read: () -> V): V {
        val k = synchronized(kept) { kept[words] }
        if (k != null) return (if (k === None) null else k) as V
        val v = read()
        if (keep(v)) synchronized(kept) { kept[words] = v ?: None }
        return v
    }

    /** How many readings are kept. */
    val size: Int get() = synchronized(kept) { kept.size }

    /** Every kept reading forgotten (tests). */
    fun clear() = synchronized(kept) { kept.clear() }
}

/** [read] of [words] kept in [kept], given back as [words] itself when it reads as the same words (a reader that returns its input). */
internal fun Kept<String>.same(words: String, read: () -> String): String = of(words, read = read).let { if (it == words) words else it }

/**
 * [s] with every character but a-z, 0-9 and those in [keep] made a space, each run of spaces made one and the ends
 * trimmed (speed round 10): in one pass exactly what replacing the pattern "[^a-z0-9<keep> ]" with a space, then "\\s+"
 * with one space, then trimming gives - the readers' shared way of spacing words, which cost two pattern passes in each
 * of some seventy readers per question. [keep] never holds whitespace.
 */
internal fun spacedWords(s: String, keep: String = ""): String {
    val out = StringBuilder(s.length)
    var space = false
    for (c in s) {
        if (c in 'a'..'z' || c in '0'..'9' || (keep.isNotEmpty() && keep.indexOf(c) >= 0)) {
            if (space && out.isNotEmpty()) out.append(' ')
            space = false
            out.append(c)
        } else space = true
    }
    return out.toString()
}

/**
 * The words said, spaced the readers' shared way, made once per words and kept (speed round 11): some sixty readers each
 * lowercased and spaced the same question again ([spacedWords] after a lowercase and an apostrophe replace or two), about
 * a seventh of the chain's time per question. Their ways come to two forms: [words] (an apostrophe a space, as a hyphen
 * is: "don t") and [joined] (apostrophes dropped: "dont"). Each is exactly what that reader made before. Only the words
 * said are kept, never an account figure; [Kept], pure.
 */
internal object Spaced {
    private val spaced = Kept<String>(64)
    private val apostrophesDropped = Kept<String>(64)
    private val made = java.util.concurrent.atomic.AtomicInteger()

    /** " " + [spacedWords] of [text] lowercased + " " (an apostrophe, straight or curly, read as a space). */
    fun words(text: String): String = spaced.of(text) { made.incrementAndGet(); " " + spacedWords(text.lowercase()) + " " }

    /** As [words], with the apostrophes (straight or curly) dropped first: "don't" is " dont ". */
    fun joined(text: String): String = apostrophesDropped.of(text) {
        made.incrementAndGet(); " " + spacedWords(text.lowercase().replace("'", "").replace("’", "")) + " "
    }

    /** How many forms were made rather than found kept (tests: the work a question costs). */
    val madeCount: Int get() = made.get()

    /** Every kept form forgotten and the count reset (tests). */
    fun forget() { spaced.clear(); apostrophesDropped.clear(); made.set(0) }
}

/** Does [s] hold a digit 0-9 (what `\\d` matches in a pattern)? A pattern that needs one cannot match without. */
internal fun hasDigit(s: CharSequence): Boolean {
    for (i in 0 until s.length) if (s[i] in '0'..'9') return true
    return false
}
